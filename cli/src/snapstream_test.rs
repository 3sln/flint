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
    // It still HOLDS the destination -- `p` is a local of the parked call --
    // so the rehydration announces it (`EV_RETAIN`) like any bridge a copy
    // holds, and a host that did not carry that port across ignores it. What
    // it must not do is send on it or close it.
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

#[test]
fn guest_code_cannot_take_a_snapshot() {
    let img = image();
    let mut a = booted(&img);
    let refusal = |v: Val| match v {
        Val::Str(s) => s,
        other => panic!("guest code TOOK a snapshot: {other:?}"),
    };
    for (tx, f) in [(1, "steal"), (2, "steal-chunk"), (3, "steal-by-value"),
                    (4, "steal-on-a-thread")] {
        let msg = refusal(call(&mut a, tx, f));
        assert!(msg.contains("guest code cannot take one"), "{f}: {msg}");
    }
    // A `:snapshot` the GUEST sends on the system port goes OUT, to the host:
    // it is an ordinary message there, and nothing is exported.
    let m = kw_map(&[("tx", Val::Int(5)), ("op", Val::Keyword(None, "call".into())),
                     ("fn", Val::Str("snap/ask-on-the-system-port".into())),
                     ("args", Val::Vector(vec![]))]);
    assert!(a.host_deliver(CALLS, &m));
    let evs = pump(&mut a);
    assert!(evs.iter().any(|e| e.kind == EV_MESSAGE && e.a == SYS),
            "the guest's message should have left on the system port");
    assert!(evs.iter().all(|e| !(e.kind == EV_MESSAGE
                                 && matches!(codec::parse(&e.payload), Ok(Val::Bytes(_))))),
            "a chunk was sent for a guest's request");

    // THE CONTROL: the same builtin, asked for by the HOST, does run.
    let (chunks, _, closed) = request(&mut a);
    assert!(!chunks.is_empty() && closed, "the host's request was refused too");
}

/// THE HOLE THIS GUARD DOES NOT CLOSE, kept as a test that fails today.
///
/// The guard trusts the system thread because the stdlib's `flint.system`
/// runs no guest code on it. But a program's sources may DEFINE `flint.system`
/// and the compiler takes theirs in place of the stdlib's
/// (`test/snapstream-shadow/flint/system.cljc`) -- so the thread the runtime
/// trusts runs the program's own code, and its request is honoured. Measured
/// 2026-10-05: a 53 554-byte export, answered to the program. The same is true
/// of `flint.port` and `flint.wire`, which that thread calls.
///
/// Ignored, not deleted: run with `--ignored` to see it. Whether a program may
/// replace a stdlib namespace is a question about the whole trusted base, not
/// about snapshots, and it is the maintainer's (`DECISIONS.md#snapshots`).
#[test]
#[ignore]
fn a_program_cannot_ship_its_own_control_plane() {
    let dir = src_dir().join("../snapstream-shadow");
    let (envelope, files) = build_spec_split(&[dir], "probe/main", &parse_slots(SLOTS).unwrap(),
                                             false, false, &[], None, &[], false, None).unwrap();
    let mut c = load_compiler().unwrap();
    let r = compile_split(&mut c, &["project", &envelope], &files);
    if r.code != 0 {
        return; // refused at compile time: the outcome wanted
    }
    let img = base64_decode(r.out.split('\n').next().unwrap()).unwrap();
    let mut p = load(&img);
    assert!(p.install_port(SYS, "system", true));
    let _ = p.run(&[]);
    for e in pump(&mut p) {
        if e.kind == EV_MESSAGE && e.a == SYS {
            let v = codec::parse(&e.payload).unwrap();
            assert!(!matches!(v.get("stolen"), Some(Val::Int(_))),
                    "a program's own control plane took a snapshot: {v:?}");
        }
    }
}
