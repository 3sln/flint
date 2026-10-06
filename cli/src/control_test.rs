//! THE CONTROL PLANE, AT THE HOST'S BOUNDARY (`DECISIONS.md#the-control-plane-is-the-runtimes`).
//!
//! `:bind`, `:unbind` and `:close` are served by the RUNTIME (`kin/control.kin`)
//! and a bound port's calls by the call loop the compiler puts in every image
//! (`src/flint/callentry.cljc`). This is `test/system.cljc`'s rows -- which drove
//! `flint.system/serve` over a local channel -- asked of the thing that replaced
//! it, through the events a host actually sees.

use crate::{base64_decode, build_spec_split, compile_split, load_compiler, parse_slots, SLOTS};
use flint_rt::codec::{self, Val, Wire};
use flint_rt::native::Program;
use std::path::PathBuf;

const EV_MESSAGE: u32 = 2;
const EV_CLOSED: u32 = 3;
const SYS: u32 = 2000;
const CALLS: u32 = 2001;

fn image() -> Vec<u8> {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test/control");
    let (envelope, files) = build_spec_split(&[dir], "ctl/main", &parse_slots(SLOTS).unwrap(),
                                             false, false, &[], None, &[], false, None).unwrap();
    let mut c = load_compiler().unwrap();
    let r = compile_split(&mut c, &["project", &envelope], &files);
    assert_eq!(r.code, 0, "compile failed: {}", r.out);
    base64_decode(r.out.split('\n').next().unwrap()).unwrap()
}

fn load(img: &[u8]) -> Program {
    Program::load_with(img, 512 * 1024 * 1024, flint_conc::HOST_CATALOGUE).unwrap()
}

fn op_msg(op: &str, port: Option<u32>) -> Vec<u8> {
    let mut w = Wire::new();
    w.map(if port.is_some() { 2 } else { 1 });
    w.keyword(None, "op");
    w.keyword(None, op);
    if let Some(p) = port {
        w.keyword(None, "port");
        w.port(p);
    }
    w.done()
}

fn pump(p: &mut Program) -> Vec<flint_rt::native::Event> {
    let mut all = Vec::new();
    for _ in 0..1000 {
        let _ = p.resume();
        let evs = p.drain_events();
        if evs.is_empty() {
            break;
        }
        all.extend(evs);
    }
    all
}

/// Call `ctl/<f>` on the bound port, answering the whole reply.
fn call(p: &mut Program, tx: i64, f: &str) -> Val {
    let mut w = Wire::new();
    let v = Val::Map(vec![
        (Val::Keyword(None, "tx".into()), Val::Int(tx)),
        (Val::Keyword(None, "op".into()), Val::Keyword(None, "call".into())),
        (Val::Keyword(None, "fn".into()), Val::Str(format!("ctl/{f}"))),
        (Val::Keyword(None, "args".into()),
         Val::Vector(if f == "greet" { vec![Val::Str("ada".into()), Val::Str("alan".into())] }
                     else { vec![] })),
    ]);
    v.write(&mut w);
    assert!(p.host_deliver(CALLS, &w.done()), "the call port would not take {f}");
    for e in pump(p) {
        if e.kind == EV_MESSAGE && e.a == CALLS {
            let v = codec::parse(&e.payload).unwrap();
            if v.get("tx").and_then(|t| t.as_i64()) == Some(tx) {
                return v;
            }
        }
    }
    panic!("no answer to {f}");
}

fn kw(v: &Val, k: &str) -> Option<String> {
    match v.get(k) {
        Some(Val::Keyword(_, n)) => Some(n.clone()),
        _ => None,
    }
}

fn text(v: &Val, k: &str) -> String {
    match v.get(k) {
        Some(Val::Str(s)) => s.clone(),
        other => format!("{other:?}"),
    }
}

#[test]
fn bind_call_unbind_close() {
    let img = image();
    let mut p = load(&img);
    assert!(p.install_port(SYS, "system", true));
    assert!(p.host_deliver(SYS, &op_msg("bind", Some(CALLS))));
    let _ = pump(&mut p);

    let ok = call(&mut p, 1, "greet");
    assert_eq!(ok.get("value"), Some(&Val::Str("hi ada and alan".into())), "call: {ok:?}");

    // A THROW IS DATA, and the thread survives it on the same port.
    let threw = call(&mut p, 2, "boom");
    assert_eq!(kw(&threw, "op").as_deref(), Some("throw"), "{threw:?}");
    assert!(text(&threw, "message").contains("inner blew up"), "{threw:?}");
    assert_eq!(text(&threw, "kind"), "ExceptionInfo", "the kind a catch selects on: {threw:?}");
    assert_eq!(call(&mut p, 3, "tally").get("value"), Some(&Val::Int(7)));

    // A NAME THE SHAKE REMOVED says what to do about it.
    let absent = call(&mut p, 4, "nope");
    assert_eq!(kw(&absent, "op").as_deref(), Some("throw"));
    assert!(text(&absent, "message").contains(":exports"), "{absent:?}");

    // AN OP NOBODY KNOWS IS IGNORED, not fatal.
    assert!(p.host_deliver(SYS, &op_msg("no-such-op", None)));
    let _ = pump(&mut p);
    assert_eq!(call(&mut p, 5, "tally").get("value"), Some(&Val::Int(7)));

    // A NIL MESSAGE ON A BOUND PORT IS NOT A GOODBYE.
    let mut w = Wire::new();
    Val::Nil.write(&mut w);
    assert!(p.host_deliver(CALLS, &w.done()));
    let _ = pump(&mut p);
    let after_nil = call(&mut p, 6, "tally");
    assert_eq!(after_nil.get("value"), Some(&Val::Int(7)), "{after_nil:?}");
    assert_eq!(after_nil.get("tx").and_then(|t| t.as_i64()), Some(6));

    // UNBIND closes the bound port: the host is told, and no call is served.
    assert!(p.host_deliver(SYS, &op_msg("unbind", Some(CALLS))));
    let evs = pump(&mut p);
    assert!(evs.iter().any(|e| e.kind == EV_CLOSED && e.a == CALLS), "unbind: {evs:?}");

    // CLOSE: a port bound since is closed too, and the control plane is over --
    // a later bind is not served, and the sandbox settles and closes its door.
    const MORE: u32 = 2002;
    assert!(p.host_deliver(SYS, &op_msg("bind", Some(MORE))));
    let _ = pump(&mut p);
    assert!(p.host_deliver(SYS, &op_msg("close", None)));
    let evs = pump(&mut p);
    assert!(evs.iter().any(|e| e.kind == EV_CLOSED && e.a == MORE), "close: {evs:?}");
    assert!(evs.iter().any(|e| e.kind == EV_CLOSED && e.a == SYS),
            "the sandbox did not settle after close: {evs:?}");
}

/// A BIND BEFORE THE FIRST DRIVE runs the program's initialisers first: a
/// call resolves its `:fn` to a var's value, which is nil until they have.
#[test]
fn the_initialisers_run_before_the_first_call() {
    let img = image();
    let mut p = load(&img);
    assert!(p.install_port(SYS, "system", true));
    assert!(p.host_deliver(SYS, &op_msg("bind", Some(CALLS))));
    // No pump between the bind and the call: deliberate, as `Host::caller` does.
    assert_eq!(call(&mut p, 1, "tally").get("value"), Some(&Val::Int(7)));
}
