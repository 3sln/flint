//! HOST PORTS: the half of `conc.rs` the host drives.
//!
//! `threads.rs` next door covers what a program can do on its own -- spawn,
//! join, a channel between two green threads. This covers what it cannot: a
//! capability it has to ASK the host for, bytes the host pushes in, and the
//! event queue the two talk over.
//!
//! Every assertion here has a mirror on the JVM and CLR ports
//! (`RtHostPorts.java`, `--rt-hostports`), and `bin/conform-hosts` runs all
//! three. That is the point of the file: the host protocol is an ABI, and an
//! ABI three runtimes implement separately is three ABIs until something looks.

use flint_rt::conc;
use flint_rt::image::ImageWriter;
use flint_rt::rt::Rt;
use flint_rt::value::{Value, NIL};
use flint_rt::vm::op;

#[derive(Default)]
struct Asm {
    b: Vec<u8>,
}

impl Asm {
    fn new() -> Asm {
        Default::default()
    }
    fn op(&mut self, o: u8) -> &mut Self {
        self.b.push(o);
        self
    }
    fn u8v(&mut self, v: u8) -> &mut Self {
        self.b.push(v);
        self
    }
    fn u16v(&mut self, v: u16) -> &mut Self {
        self.b.extend_from_slice(&v.to_le_bytes());
        self
    }
    fn konst(&mut self, c: u32) -> &mut Self {
        self.op(op::CONST).u16v(c as u16)
    }
    fn native(&mut self, idx: u32, argc: u8) -> &mut Self {
        self.op(op::NATIVE).u16v(idx as u16).u8v(argc)
    }
    fn done(self) -> Vec<u8> {
        self.b
    }
}

struct Build {
    w: ImageWriter,
    rt: Rt,
}

impl Build {
    fn new() -> Build {
        let mut rt = Rt::new();
        rt.install_host_natives();
        Build { w: ImageWriter::new(), rt }
    }
    fn conc(&mut self, name: &str, f: flint_rt::vm::NativeFn) -> u32 {
        let slot = self.rt.add_host_native(f);
        let c = self.w.k_string(name);
        self.w.add_native(c, slot)
    }
}

extern "C" fn open_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_open(rt, b, n)
}
extern "C" fn recv_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_port_receive(rt, b, n)
}
extern "C" fn send_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_port_send(rt, b, n)
}

/// One decoded outbound event.
#[derive(Debug)]
struct Ev {
    kind: u32,
    a: u32,
    b: u32,
    payload: Vec<u8>,
}

fn drain(rt: &mut Rt) -> Vec<Ev> {
    let mut buf: Vec<u8> = Vec::new();
    let n = rt.drain_events(&mut buf) as usize;
    let at = |b: &[u8], i: usize| u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]]);
    (0..n)
        .map(|i| {
            let r = i * 20;
            let off = at(&buf, r + 12) as usize;
            let len = at(&buf, r + 16) as usize;
            Ev {
                kind: at(&buf, r),
                a: at(&buf, r + 4),
                b: at(&buf, r + 8),
                payload: buf[off..off + len].to_vec(),
            }
        })
        .collect()
}

/// Run to completion, draining the trailing events a host has to drain.
///
/// AT EXIT the runtime closes every flint end and pushes an `EV_CLOSED` for it,
/// and a run with events pending comes back 2 rather than 0 -- so a host is
/// never left guessing whether more is coming. That means the last pump is two
/// pumps, and that is the protocol rather than an accident.
fn finish(rt: &mut Rt) -> (Value, Vec<Ev>) {
    let mut evs = Vec::new();
    let mut v = rt.resume();
    for _ in 0..4 {
        if rt.status != 2 {
            break;
        }
        evs.extend(drain(rt));
        v = rt.resume();
    }
    (v, evs)
}

/// `(receive (open "fs" nil cap))`, as a whole program.
///
/// It parks inside `open`, so `run_program` returns with `status == 2` -- "the
/// host is needed" -- and the test IS the host from there on.
fn opener(cap_host_id: Option<u64>) -> Rt {
    let mut b = Build::new();
    let open = b.conc("flint/open", open_);
    let recv = b.conc("flint/port-receive", recv_);
    let name = b.w.k_string("fs");
    let body = {
        let mut a = Asm::new();
        a.konst(name);
        if cap_host_id.is_some() {
            a.op(op::NIL);
            a.op(op::LOCAL).u8v(0);
            a.native(open, 3);
        } else {
            a.native(open, 1);
        }
        a.native(recv, 1).op(op::RETURN);
        a.done()
    };
    let n = b.w.k_string("main");
    b.w.entry = b.w.add_fn(n, 1, false, 4, &body);
    let bytes = b.w.finish();
    let mut rt = b.rt;
    assert!(rt.load_image(&bytes), "image did not load");
    // `main`'s one argument is the capability, when there is one.
    let arg = match cap_host_id {
        Some(id) => {
            let l = rt.string("fs");
            rt.new_opaque(l, id)
        }
        None => NIL,
    };
    rt.run_program(arg);
    rt
}

#[test]
fn open_asks_the_host_and_parks_until_it_answers() {
    let mut rt = opener(None);
    assert_eq!(rt.status, 2, "parked on the host, not finished and not deadlocked");
    let evs = drain(&mut rt);
    assert_eq!(evs.len(), 1, "exactly one open-request");
    assert_eq!(evs[0].kind, conc::EV_OPEN as u32);
    assert_eq!(evs[0].payload, b"fs", "the capability asked for, by name");
    // Nothing was presented -- and NOTHING is a distinct answer from "something
    // I do not recognise".
    assert_eq!(rt.presented_capability(evs[0].b), conc::PRESENTED_NONE);
    let token = evs[0].a as i64;
    assert!(rt.host_continue(token, true), "a fresh token is honoured");
    // A SECOND answer on the same token is refused: the generation moved on.
    assert!(!rt.host_continue(token, true), "a duplicate reply is refused");
    let host_id = evs[0].b;
    assert!(rt.host_deliver(host_id as i64, b"hello"));
    let (v, tail) = finish(&mut rt);
    let mut sb = flint_rt::rt::sbuf();
    assert_eq!(rt.as_str(v, &mut sb), Some("hello"));
    assert_eq!(rt.status, 0);
    // And the host was TOLD the port closed, rather than left to infer it.
    assert!(
        tail.iter().any(|e| e.kind == conc::EV_CLOSED as u32 && e.a == host_id),
        "exit closes the flint end and says so: {tail:?}"
    );
}

#[test]
fn a_refusal_is_a_security_exception_rather_than_a_hang() {
    let mut rt = opener(None);
    let evs = drain(&mut rt);
    assert!(rt.host_continue(evs[0].a as i64, false));
    finish(&mut rt);
    assert!(rt.failed(), "a refused open must throw");
    let e = rt.clear_error();
    let mut kb = flint_rt::rt::sbuf();
    let k = rt.ex_kind(e);
    assert_eq!(rt.as_str(k, &mut kb), Some("SecurityException"));
}

/// The case `PRESENTED_UNKNOWN` exists for.
///
/// A guest can mint an opaque value all day -- `flint/opaque` gives it host id
/// 0. If the runtime reported that as "nothing presented", a host that falls
/// back to allowing unauthenticated opens would accept the forgery. The two
/// must be distinguishable, and this is the assertion that says so.
#[test]
fn a_guest_minted_capability_is_unknown_not_absent() {
    let mut rt = opener(Some(0));
    let evs = drain(&mut rt);
    assert_eq!(
        rt.presented_capability(evs[0].b),
        conc::PRESENTED_UNKNOWN,
        "a guest-minted opaque must not read as an absence"
    );
    let mut rt2 = opener(Some(77));
    let evs2 = drain(&mut rt2);
    assert_eq!(rt2.presented_capability(evs2[0].b), 77, "a real host id travels");
}

#[test]
fn the_guest_buffer_bounds_what_the_host_may_push() {
    let mut rt = opener(None);
    let evs = drain(&mut rt);
    let host_id = evs[0].b as i64;
    assert!(rt.host_continue(evs[0].a as i64, true));
    // One message under the cap goes in; a second that would cross it is
    // REFUSED rather than queued, so a server answering in waves cannot make
    // the whole answer resident at once.
    let big = vec![b'x'; conc::DEFAULT_HOST_CAP as usize];
    assert!(rt.host_deliver(host_id, &big), "the first wave fits");
    assert!(!rt.host_deliver(host_id, b"more"), "the second must wait");
    finish(&mut rt);
    assert!(rt.host_deliver(host_id, b"more"), "after a receive there is room again");
}

#[test]
fn the_host_hanging_up_reads_as_end_of_stream() {
    let mut rt = opener(None);
    let evs = drain(&mut rt);
    let host_id = evs[0].b as i64;
    assert!(rt.host_continue(evs[0].a as i64, true));
    assert_eq!(rt.host_port_state(host_id), conc::P_OPEN);
    rt.host_close_port(host_id);
    // HALF-closed, not closed: anything already delivered is still readable.
    assert_eq!(rt.host_port_state(host_id), conc::P_HALF);
    let (v, _) = finish(&mut rt);
    assert!(v.is_nil(), "a drained, closed port receives nil");
    if rt.failed() {
        let e = rt.clear_error();
        let mut b1 = flint_rt::rt::sbuf();
        let k: String = rt.as_str(rt.ex_kind(e), &mut b1).unwrap_or("?").into();
        let mut b2 = flint_rt::rt::sbuf();
        let m: String = rt.as_str(rt.ex_message(e), &mut b2).unwrap_or("?").into();
        panic!("end of stream is not an error, but got {k}: {m}");
    }
    // An id the runtime has never heard of is 255, which a host also treats as
    // done -- the case a missed `:closed` event would otherwise leak.
    assert_eq!(rt.host_port_state(999_999), 255);
}

#[test]
fn a_send_leaves_as_one_event_carrying_its_bytes() {
    let mut b = Build::new();
    let open = b.conc("flint/open", open_);
    let send = b.conc("flint/port-send", send_);
    let name = b.w.k_string("log");
    let msg = b.w.k_string("a line");
    let body = {
        let mut a = Asm::new();
        a.konst(name).native(open, 1);
        a.op(op::SET_LOCAL).u8v(1);
        a.op(op::LOCAL).u8v(1);
        a.konst(msg);
        a.native(send, 2).op(op::RETURN);
        a.done()
    };
    let n = b.w.k_string("main");
    b.w.entry = b.w.add_fn(n, 1, false, 4, &body);
    let bytes = b.w.finish();
    let mut rt = b.rt;
    assert!(rt.load_image(&bytes));
    rt.run_program(NIL);
    let evs = drain(&mut rt);
    let host_id = evs[0].b;
    assert!(rt.host_continue(evs[0].a as i64, true));
    let (_, evs) = finish(&mut rt);
    let msgs: Vec<&Ev> = evs.iter().filter(|e| e.kind == conc::EV_MESSAGE as u32).collect();
    assert_eq!(msgs.len(), 1, "one send, one event");
    assert_eq!(msgs[0].a, host_id, "addressed to the host's end");
    assert_eq!(msgs[0].payload, b"a line");
    assert_eq!(msgs[0].b as usize, "a line".len(), "the length travels too");
}

/// A port cannot be sent through a port, and neither can a function or an
/// opaque value (`doc/decisions/0006`, `0022`).
#[test]
fn only_data_crosses_a_port() {
    let mut rt = Rt::new();
    rt.install_host_natives();
    rt.ensure_sched();
    let pair = rt.make_channel(4, NIL);
    let pi = rt.push(pair);
    let a = rt.vec_nth(rt.r(pi), 0).unwrap();
    let ai = rt.push(a);
    assert!(rt.check_sendable(rt.r(ai)).is_err(), "a port may not be sent");
    let l = rt.string("fs");
    let li = rt.push(l);
    let o = rt.new_opaque(rt.r(li), 9);
    let oi = rt.push(o);
    assert!(rt.check_sendable(rt.r(oi)).is_err(), "an opaque value may not be sent");
    let s = rt.string("data");
    let si = rt.push(s);
    assert!(rt.check_sendable(rt.r(si)).is_ok(), "a string is data");
    // And NESTED: the walk is what makes the refusal worth anything.
    let v = rt.empty_vec();
    let vi = rt.push(v);
    let nested = rt.vec_conj(rt.r(vi), rt.r(ai));
    let ni = rt.push(nested);
    assert!(rt.check_sendable(rt.r(ni)).is_err(), "a port inside a vector too");
}
