//! A HOST-REQUESTED, STREAMED SNAPSHOT on the native runtime
//! (`DECISIONS.md#snapshots`), end to end through a compiled image.
//!
//! In the CLI crate because this is the one place the compiler, the runtime and
//! the concurrency unit's builtins are all linked; the runtime crate cannot
//! name `flint_conc`. Driven by hand at the port level, not through
//! `serve::Host`, because what is asserted is the protocol itself: which events
//! come out, in which order, carrying which bytes.

use crate::{base64_decode, build_spec_split, compile_split, load_compiler, parse_slots, SLOTS};
use flint_rt::codec::{self, Val, Wire};
use flint_rt::native::Program;
use std::path::PathBuf;

const EV_MESSAGE: u32 = 2;
const EV_CLOSED: u32 = 3;
const SYS: u32 = 1000;
const CALLS: u32 = 1001;
const DEST: u32 = 1002;

/// The fixture every runtime runs: `test/snapstream/snap.cljc`.
fn src_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test/snapstream")
}

/// The image: `FLINT_SNAPSTREAM_IMG` if set -- so the ports and this run the
/// SAME bytes and a stream can cross between them -- else compiled here.
fn image() -> Vec<u8> {
    if let Ok(p) = std::env::var("FLINT_SNAPSTREAM_IMG") {
        let bytes = std::fs::read(&p).unwrap();
        // A MODULE carries its image; that image is what wasm runs, and a
        // fingerprint is of the image, so this is how native and wasm share one.
        return if p.ends_with(".wasm") {
            flint_rt::native::bytecode_of(&bytes).unwrap()
        } else {
            bytes
        };
    }
    let (envelope, files) = build_spec_split(&[src_dir()], "snap/main",
                                             &parse_slots(SLOTS).unwrap(), false, false, &[],
                                             None, &[], false, None).unwrap();
    let mut c = load_compiler().unwrap();
    let r = compile_split(&mut c, &["project", &envelope], &files);
    assert_eq!(r.code, 0, "compile failed: {}", r.out);
    base64_decode(r.out.split('\n').next().unwrap()).unwrap()
}

fn load(img: &[u8]) -> Program {
    Program::load_with(img, 512 * 1024 * 1024, flint_conc::HOST_CATALOGUE).unwrap()
}

fn kw_map(pairs: &[(&str, Val)]) -> Vec<u8> {
    let v = Val::Map(pairs.iter().map(|(k, v)| (Val::Keyword(None, k.to_string()), v.clone()))
                     .collect());
    let mut w = Wire::new();
    v.write(&mut w);
    w.done()
}

fn port_msg(op: &str, port: u32) -> Vec<u8> {
    let mut w = Wire::new();
    w.map(2);
    w.keyword(None, "op");
    w.keyword(None, op);
    w.keyword(None, "port");
    w.port(port);
    w.done()
}

/// Pump until nothing more happens, collecting every event.
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

/// Call `snap/<f>` on the bound call port, answering its `:value`.
fn call(p: &mut Program, tx: i64, f: &str) -> Val {
    let m = kw_map(&[("tx", Val::Int(tx)), ("op", Val::Keyword(None, "call".into())),
                     ("fn", Val::Str(format!("snap/{f}"))), ("args", Val::Vector(vec![]))]);
    assert!(p.host_deliver(CALLS, &m), "the call port would not take the call");
    for e in pump(p) {
        if e.kind == EV_MESSAGE && e.a == CALLS {
            let v = codec::parse(&e.payload).unwrap();
            if v.get("tx").and_then(|t| t.as_i64()) == Some(tx) {
                return v.get("value").cloned().unwrap_or_else(|| panic!("threw: {v:?}"));
            }
        }
    }
    panic!("no answer to {f}");
}

/// Ask for a snapshot on the system port; answer the chunks, the terminator,
/// and whether the destination was closed after it.
fn request(p: &mut Program) -> (Vec<Vec<u8>>, Option<Val>, bool) {
    assert!(p.host_deliver(SYS, &port_msg("snapshot", DEST)));
    let mut chunks = Vec::new();
    let mut end = None;
    let mut closed = false;
    for e in pump(p) {
        if e.a != DEST {
            continue;
        }
        if e.kind == EV_CLOSED {
            closed = true;
        } else if e.kind == EV_MESSAGE {
            assert!(!closed, "a message arrived after the close");
            match codec::parse(&e.payload).unwrap() {
                Val::Bytes(b) => {
                    assert!(end.is_none(), "a chunk arrived after the terminator");
                    chunks.push(b);
                }
                v => end = Some(v),
            }
        }
    }
    (chunks, end, closed)
}

fn booted(img: &[u8]) -> Program {
    let mut p = load(img);
    assert!(p.install_port(SYS, "system", true));
    assert!(p.host_deliver(SYS, &port_msg("bind", CALLS)));
    let _ = pump(&mut p);
    p
}

#[test]
fn a_host_requested_snapshot_streams_the_one_shot_export_and_the_copy_carries_on() {
    let img = image();
    let mut a = booted(&img);
    assert_eq!(call(&mut a, 1, "bump"), Val::Int(1));

    let (chunks, end, closed) = request(&mut a);
    let stream: Vec<u8> = chunks.concat();
    // For the crossing: a port imports what native streamed.
    if let Ok(p) = std::env::var("FLINT_SNAPSTREAM_OUT") {
        std::fs::write(p, &stream).unwrap();
    }
    assert!(chunks.len() >= 2, "the ballast should need several chunks, got {}", chunks.len());
    assert!(chunks[..chunks.len() - 1].iter().all(|c| c.len() == 65536),
            "every chunk but the last is full");
    assert_eq!(end.as_ref().and_then(|v| v.get("op")).and_then(|o| match o {
                   Val::Keyword(_, n) => Some(n.as_str()), _ => None }),
               Some("end"), "terminator: {end:?}");
    assert_eq!(end.as_ref().and_then(|v| v.get("size")).and_then(|s| s.as_i64()),
               Some(stream.len() as i64), "the terminator names the byte count");
    assert!(closed, "the destination is closed after the terminator");

    // THE INSTANCE ASKED carries on: the request did not stop it.
    assert_eq!(call(&mut a, 2, "bump"), Val::Int(2));

    // THE STREAM IS THE ONE-SHOT EXPORT. A fresh instance takes the
    // concatenation, and the one-shot export of what it took is the same bytes.
    let mut c = load(&img);
    assert_eq!(c.import_live(&stream), 0, "import refused the concatenated stream");
    let one_shot = c.export_live().expect("the one-shot export refused");
    assert!(one_shot == stream, "streamed {} bytes, one-shot {} bytes, and they differ",
            stream.len(), one_shot.len());

    // THE COPY CARRIES ON FROM THE SNAPSHOT, not from where `a` is now, and
    // does NOT stream itself again to a port that belonged to `a`.
    let mut b = load(&img);
    assert_eq!(b.import_live(&stream), 0);
    let evs = pump(&mut b);
    // It still HOLDS the destination when it arrives -- the request was in
    // `SC_SNAP` when the export was taken -- so the rehydration announces it
    // (`EV_RETAIN`) like any bridge a copy holds, and a host that did not carry
    // that port across ignores it. Its first drive drops every request it
    // inherited. What it must not do is send on the destination or close it.
    assert!(evs.iter().all(|e| !(e.a == DEST && (e.kind == EV_MESSAGE || e.kind == EV_CLOSED))),
            "the copy streamed again: {evs:?}");
    assert_eq!(call(&mut b, 3, "bump"), Val::Int(2), "the copy's counter is the snapshot's");
    assert_eq!(call(&mut b, 4, "sizes"), Val::Int(30000));

    // And the copy's control plane answers a snapshot request of its own.
    let (chunks2, end2, closed2) = request(&mut b);
    assert!(!chunks2.is_empty() && closed2, "the copy could not be snapshotted: {end2:?}");

    // Another layout and another program are refused by name.
    assert_eq!(load(&img).import_live(&stream[..8]), 1);
}

/// Compile `src` as `probe.cljc` with entry `probe/main`, answering the
/// compiler's exit code and output.
fn compile_probe(tag: &str, src: &str) -> (i32, String) {
    let dir = std::env::temp_dir().join(format!("flint-snapstream-{}-{tag}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("probe.cljc"), src).unwrap();
    let (envelope, files) = build_spec_split(&[dir.clone()], "probe/main",
                                             &parse_slots(SLOTS).unwrap(), false, false, &[],
                                             None, &[], false, None).unwrap();
    let mut c = load_compiler().unwrap();
    let r = compile_split(&mut c, &["project", &envelope], &files);
    let _ = std::fs::remove_dir_all(&dir);
    (r.code, r.out)
}

/// NO SOURCE CAN NAME A WAY IN. The three builtins a guest once reached --
/// the two that took a snapshot and the one that answered the system port --
/// are gone, so every route to them is a compile error rather than a run-time
/// refusal: by name, in value position, and from a thread the program spawns.
/// The control differs in exactly the builtin named.
#[test]
fn no_source_can_name_a_snapshot_builtin_or_the_system_port() {
    let routes = [
        ("export", "(flint.rt/snapshot-export)"),
        ("chunk", "(flint.rt/snapshot-chunk 0 16)"),
        ("by-value", "(let [f flint.rt/snapshot-export] (f))"),
        ("on-a-thread", "(flint.thread/join (flint.thread/spawn (fn [] (flint.rt/snapshot-export))))"),
        ("system-port", "(flint.rt/system-port)"),
        ("system-port-by-value", "(let [f flint.rt/system-port] (f))"),
    ];
    for (tag, body) in routes {
        let src = format!("(ns probe (:require [flint.thread])) (defn main [_] (str {body}))");
        let (code, out) = compile_probe(tag, &src);
        assert_ne!(code, 0, "{tag}: a program naming a removed builtin COMPILED");
        assert!(out.contains("no such builtin"), "{tag}: refused for another reason: {out}");
    }
    // THE CONTROL: the same shape, naming a builtin that exists.
    let (code, out) = compile_probe("control",
        "(ns probe (:require [flint.thread])) (defn main [_] (str (flint.rt/port? 1)))");
    assert_eq!(code, 0, "the control did not compile: {out}");
}

/// THE RUNTIME TAKES ONLY THE HOST'S REQUEST, ON THE SYSTEM PORT.
/// The same message on a bound call port is an ordinary message there -- a
/// call with no `:fn` -- and an op in a namespace is not the op. The control, on the same sandbox: the exact request
/// on the system port is served.
#[test]
fn only_the_hosts_request_on_the_system_port_is_served() {
    let img = image();
    let mut a = booted(&img);
    let chunk_sent = |evs: &[flint_rt::native::Event]| evs.iter().any(|e| {
        e.kind == EV_MESSAGE && matches!(codec::parse(&e.payload), Ok(Val::Bytes(_)))
    });
    // ON A CALL PORT: delivered to the call thread, which answers it as a call.
    assert!(a.host_deliver(CALLS, &port_msg("snapshot", DEST)));
    let evs = pump(&mut a);
    assert!(!chunk_sent(&evs), "a request on a CALL port was served");
    // AN OP IN A NAMESPACE, on the system port: `:x/snapshot` is not
    // `:snapshot`, as `(case (:op m) :snapshot ..)` said.
    let mut w = Wire::new();
    w.map(2);
    w.keyword(None, "op");
    w.keyword(Some("x"), "snapshot");
    w.keyword(None, "port");
    w.port(DEST);
    assert!(a.host_deliver(SYS, &w.done()));
    let evs = pump(&mut a);
    assert!(!chunk_sent(&evs), "a request for `:x/snapshot` was served as `:snapshot`");
    // THE CONTROL.
    let (chunks, end, closed) = request(&mut a);
    assert!(!chunks.is_empty() && closed, "the host's own request was not served: {end:?}");
    // And the sandbox still serves calls after all three.
    assert_eq!(call(&mut a, 1, "bump"), Val::Int(1));
}

/// A PROGRAM'S OWN `flint.system` IS NOT A CONTROL PLANE.
///
/// It was: the runtime looked `flint.system/boot` up by name and trusted the
/// thread it ran on, and which source backs a namespace is the resolver's --
/// the host's -- answer. Measured 2026-10-05: a program supplying
/// `test/snapstream-shadow/flint/system.cljc` took a 53 554-byte export through
/// it. The control plane is runtime code now and the call loop is compiled into
/// every image and named by index
/// (`DECISIONS.md#the-control-plane-is-the-runtimes`), so the program's
/// `flint.system/boot` -- kept in the image, and handed nothing -- never runs.
/// The controls, on the same sandbox: a call is served, and the host's own
/// snapshot request is.
#[test]
fn a_program_cannot_ship_its_own_control_plane() {
    let dir = src_dir().join("../snapstream-shadow");
    let (envelope, files) = build_spec_split(&[dir], "probe/main", &parse_slots(SLOTS).unwrap(),
                                             false, false, &[], None, &[], false, None).unwrap();
    let mut c = load_compiler().unwrap();
    let r = compile_split(&mut c, &["project", &envelope], &files);
    assert_eq!(r.code, 0, "the program compiles -- its file is the resolver's answer: {}", r.out);
    let img = base64_decode(r.out.split('\n').next().unwrap()).unwrap();
    let mut p = load(&img);
    assert!(p.install_port(SYS, "system", true));
    assert!(p.host_deliver(SYS, &port_msg("bind", CALLS)));
    let mut evs = pump(&mut p);
    let m = kw_map(&[("tx", Val::Int(1)), ("op", Val::Keyword(None, "call".into())),
                     ("fn", Val::Str("probe/hi".into())), ("args", Val::Vector(vec![]))]);
    assert!(p.host_deliver(CALLS, &m));
    evs.extend(pump(&mut p));
    let answered = evs.iter().any(|e| e.kind == EV_MESSAGE && e.a == CALLS
        && codec::parse(&e.payload).ok().and_then(|v| v.get("value").cloned())
            == Some(Val::Str("hi".into())));
    assert!(answered, "THE CONTROL: the call was not served: {evs:?}");
    assert!(evs.iter().all(|e| !(e.kind == EV_MESSAGE && e.a == SYS)),
            "something sent on the system port -- the program's `boot` ran: {evs:?}");
    let (chunks, end, closed) = request(&mut p);
    assert!(!chunks.is_empty() && closed, "the host's request was not served: {end:?}");
}
