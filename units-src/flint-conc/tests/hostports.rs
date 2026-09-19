//! BRIDGES: the half of `conc.rs` the host drives.
//!
//! `threads.rs` next door covers what a program can do on its own -- spawn,
//! join, a channel between two green threads. This covers what it cannot: a
//! capability it has to ASK the host for on its system port, messages the host
//! pushes in, and the event queue the two talk over.
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
// THE WIRE PRIMITIVES, because `open` takes its arguments ENCODED now
// (`DECISIONS.md#the-codec-is-guest-code`). A test that drives the builtin
// directly has no `flint.port/open` to do that for it, so it writes the
// payload itself -- which is what a guest encoder looks like in bytecode.
//
// Each one takes the writer as its first argument and ANSWERS THE WRITER, so
// they chain on the stack: `[w]` in, `[w]` out, and the value being written is
// pushed between.
extern "C" fn wwriter_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_wire_writer(rt, b, n)
}
extern "C" fn wvec_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_wire_vec(rt, b, n)
}
extern "C" fn wstr_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_wire_str(rt, b, n)
}
extern "C" fn wnil_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_wire_nil(rt, b, n)
}
extern "C" fn wopaque_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_wire_opaque(rt, b, n)
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

/// The open-request in a drain.
///
/// A drain is no longer one event: installing a bridge pushes an `EV_RETAIN`
/// first, because the host's count is maintained in exactly one place and that
/// place is the event stream (`DECISIONS.md#ports-are-the-hosts`). Finding the request by
/// KIND rather than by index is what a host does anyway.
fn open_ev(evs: &[Ev]) -> &Ev {
    evs.iter()
        .find(|e| e.kind == conc::EV_OPEN as u32)
        .unwrap_or_else(|| panic!("no open-request in {evs:?}"))
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
    let ww = b.conc("flint/wire-writer", wwriter_);
    let wvec = b.conc("flint/wire-vec", wvec_);
    let wstr = b.conc("flint/wire-str", wstr_);
    let wnil = b.conc("flint/wire-nil", wnil_);
    let wop = b.conc("flint/wire-opaque", wopaque_);
    let name = b.w.k_string("fs");
    let one = b.w.k_int(1);
    let three = b.w.k_int(3);
    let body = {
        let mut a = Asm::new();
        // `[name, w]` on the stack throughout: the name for `open`'s refusal
        // message, and the writer being filled beside it.
        a.konst(name);
        a.native(ww, 0);
        if cap_host_id.is_some() {
            a.konst(three);
        } else {
            a.konst(one);
        }
        a.native(wvec, 2);
        a.konst(name);
        a.native(wstr, 2);
        if cap_host_id.is_some() {
            a.native(wnil, 1);
            a.op(op::LOCAL).u8v(0);
            a.native(wop, 2);
        }
        a.native(open, 2);
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
            rt.new_opaque(l, id as i64)
        }
        None => NIL,
    };
    // A SYSTEM PORT, because `open` is a request ON one (`DECISIONS.md#ports-are-the-hosts`).
    // A sandbox given none cannot ask for anything, which the last test here
    // checks; every other test needs one installed before the program runs.
    let l = rt.string("system");
    rt.install_system_port(SYSTEM, l);
    rt.run_program(arg);
    rt
}

/// The host's id for this sandbox's system port. The HOST picks it: a sandbox
/// no longer mints port ids, which is the whole of `ports-are-the-hosts`.
const SYSTEM: i64 = 1;
/// The host's id for the port these tests grant when asked.
const GRANTED: i64 = 500;

/// Grant an open with `GRANTED`, the way a host answers a request.
fn grant(rt: &mut Rt, token: u32) -> bool {
    rt.host_grant(token as i64, GRANTED)
}

/// A message as the host writes it: the wire format, because a bridge carries
/// VALUES and the runtime decodes what arrives. A host that wrote raw bytes
/// here would be writing something the runtime cannot read.
fn wire(rt: &mut Rt, s: &str) -> Vec<u8> {
    let v = rt.string(s);
    rt.encode(v).expect("a string encodes")
}

/// What the guest presented, as a host reads it.
///
/// The runtime used to answer this itself -- `presented_capability`, plus
/// `PRESENTED_NONE` and `PRESENTED_UNKNOWN` -- and the cutover removed all
/// three. The sandbox does not have the concept: `open` forwards its arguments
/// and the payload is that argument vector, encoded. A host decodes it and
/// looks for whatever it projected in.
///
/// So this is the Rust half of what `sdks/esm/src/guest.js` does in JS, and it
/// is deliberately the same shape -- if the two read the wire form differently
/// then one of them is wrong about an ABI.
///
/// `None` means nothing was presented. `Some(0)` means something was, and the
/// guest minted it: `flint/opaque` gives host id 0 and a guest cannot set that
/// field. Those two must stay distinguishable, or a host that allows
/// unauthenticated opens accepts every forgery.
fn presented(rt: &mut Rt, payload: &[u8]) -> Option<u64> {
    let v = rt.decode(payload).ok()?;
    // `[name, ...args]`, and the capability is whichever of those args is an
    // opaque value. SCANNED rather than read at a fixed index, because the two
    // callers put it in different places: this test drives the builtin
    // directly, `(open name nil cap)`, while `flint.port/open` takes an options
    // MAP and the capability is a key in it. A host looks for what it
    // projected in; it does not count arguments.
    let n = rt.count_of(v);
    for k in 1..n {
        let idx = rt.integer(k as i64);
        let arg = rt.coll_nth(v, idx, flint_rt::value::NOT_FOUND);
        if rt.is_opaque(arg) {
            return Some(rt.opaque_host_id(arg) as u64);
        }
    }
    None
}

#[test]
fn open_asks_the_host_and_parks_until_it_answers() {
    let mut rt = opener(None);
    assert_eq!(rt.status, 2, "parked on the host, not finished and not deadlocked");
    let evs = drain(&mut rt);
    assert_eq!(
        evs.iter().filter(|e| e.kind == conc::EV_OPEN as u32).count(),
        1,
        "exactly one open-request: {evs:?}"
    );
    // NO RETAIN FOR AN EXPLICIT INSTALL. This host installed the system port,
    // so it already knows it holds it, and an event it does not need is traffic
    // queued before the program has even started -- which makes the first run
    // come back "the host is needed" when nothing is parked
    // (`DECISIONS.md#ports-are-the-hosts`). The retain carries only what a host could not have
    // known: a port arriving inside a message.
    assert!(
        !evs.iter().any(|e| e.kind == conc::EV_RETAIN as u32),
        "an explicit install announces nothing: {evs:?}"
    );
    // Nothing was presented -- and NOTHING is a distinct answer from "something
    // I do not recognise".
    let p = open_ev(&evs).payload.clone();
    assert_eq!(presented(&mut rt, &p), None);
    // The request went out ON THE SYSTEM PORT: there is no port for it yet.
    assert_eq!(open_ev(&evs).b as i64, SYSTEM, "asked on the system port");
    let token = open_ev(&evs).a;
    // A GRANT NAMES A PORT. `host_continue(token, true)` cannot: there is no
    // port until the host says which one, so that form is refused outright.
    assert!(!rt.host_continue(token as i64, true), "a grant must name a port");
    assert!(grant(&mut rt, token), "a fresh token is honoured");
    // A SECOND answer on the same token is refused: the generation moved on.
    assert!(!grant(&mut rt, token), "a duplicate reply is refused");
    // A GRANT is the host's own act too, so it announces nothing either -- the
    // host chose the id and knows it lent it. What it does get, at the end, is
    // the RELEASE, because a drop is never something the host asked for.
    let after = drain(&mut rt);
    assert!(
        !after.iter().any(|e| e.kind == conc::EV_RETAIN as u32),
        "granting announces nothing: {after:?}"
    );
    let msg = wire(&mut rt, "hello");
    assert!(rt.host_deliver(GRANTED, &msg));
    let (v, tail) = finish(&mut rt);
    // A BRIDGE DELIVERS BYTES NOW, and the guest decodes them
    // (`DECISIONS.md#the-codec-is-guest-code`). This program calls the
    // `flint/port-receive` BUILTIN rather than `flint.port/receive`, so no
    // flint library is in the picture and what it gets is the encoding.
    //
    // Decoded here with the host-side decoder, which is what a host would do:
    // the claim under test is that the message crossed intact, and that is the
    // same claim it always was.
    assert!(rt.is_bytes(v), "a bridge carries bytes: {v:?}");
    let raw = rt.b_to_vec(v);
    let decoded = rt.decode(&raw).expect("the delivered bytes decode");
    let mut sb = flint_rt::rt::sbuf();
    assert_eq!(rt.as_str(decoded, &mut sb), Some("hello"));
    assert_eq!(rt.status, 0);
    // And the host was TOLD the port closed, rather than left to infer it.
    assert!(
        tail.iter().any(|e| e.kind == conc::EV_CLOSED as u32 && e.a as i64 == GRANTED),
        "exit closes the bridge and says so: {tail:?}"
    );
    // And RELEASES it: one per retain, so the host's count reaches zero and
    // whatever the port stood for can go.
    assert!(
        tail.iter().any(|e| e.kind == conc::EV_RELEASE as u32 && e.a as i64 == GRANTED),
        "exit releases the bridge: {tail:?}"
    );
}

#[test]
fn a_refusal_is_a_security_exception_rather_than_a_hang() {
    let mut rt = opener(None);
    let evs = drain(&mut rt);
    assert!(rt.host_continue(open_ev(&evs).a as i64, false));
    finish(&mut rt);
    assert!(rt.failed(), "a refused open must throw");
    let e = rt.clear_error();
    let mut kb = flint_rt::rt::sbuf();
    let k = rt.ex_kind(e);
    assert_eq!(rt.as_str(k, &mut kb), Some("SecurityException"));
}

/// A forgery must not read as an absence.
///
/// A guest can mint an opaque value all day -- `flint/opaque` gives it host id
/// 0. If that reached the host as "nothing presented", a host that falls back
/// to allowing unauthenticated opens would accept every forgery. The two must
/// be distinguishable, and this is the assertion that says so.
#[test]
fn a_guest_minted_capability_is_unknown_not_absent() {
    let mut rt = opener(Some(0));
    let evs = drain(&mut rt);
    let p = open_ev(&evs).payload.clone();
    assert_eq!(
        presented(&mut rt, &p),
        Some(0),
        "a guest-minted opaque must not read as an absence"
    );
    let mut rt2 = opener(Some(77));
    let evs2 = drain(&mut rt2);
    let p2 = open_ev(&evs2).payload.clone();
    assert_eq!(presented(&mut rt2, &p2), Some(77), "a real host id travels");
}

#[test]
fn the_guest_buffer_bounds_what_the_host_may_push() {
    let mut rt = opener(None);
    let evs = drain(&mut rt);
    assert!(grant(&mut rt, open_ev(&evs).a));
    // One message under the cap goes in; a second that would cross it is
    // REFUSED rather than queued, so a server answering in waves cannot make
    // the whole answer resident at once.
    let big = {
        let s: String = core::iter::repeat('x')
            .take(conc::DEFAULT_BRIDGE_CAP as usize)
            .collect();
        wire(&mut rt, &s)
    };
    let more = wire(&mut rt, "more");
    assert!(rt.host_deliver(GRANTED, &big), "the first wave fits");
    assert!(!rt.host_deliver(GRANTED, &more), "the second must wait");
    finish(&mut rt);
    assert!(rt.host_deliver(GRANTED, &more), "after a receive there is room again");
}

#[test]
fn the_host_hanging_up_reads_as_end_of_stream() {
    let mut rt = opener(None);
    let evs = drain(&mut rt);
    assert!(grant(&mut rt, open_ev(&evs).a));
    assert_eq!(rt.host_port_state(GRANTED), conc::P_OPEN);
    rt.host_close_port(GRANTED);
    // HALF-closed, not closed: anything already delivered is still readable.
    assert_eq!(rt.host_port_state(GRANTED), conc::P_HALF);
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
    let ww = b.conc("flint/wire-writer", wwriter_);
    let wvec = b.conc("flint/wire-vec", wvec_);
    let wstr = b.conc("flint/wire-str", wstr_);
    let name = b.w.k_string("log");
    let msg = b.w.k_string("a line");
    let one = b.w.k_int(1);
    let body = {
        let mut a = Asm::new();
        a.konst(name);
        a.native(ww, 0);
        a.konst(one);
        a.native(wvec, 2);
        a.konst(name);
        a.native(wstr, 2);
        a.native(open, 2);
        a.op(op::SET_LOCAL).u8v(1);
        a.op(op::LOCAL).u8v(1);
        // THE MESSAGE IS ENCODED TOO. A bridge carries an encoding, not a
        // value, so this writes one the same way the payload above was
        // written -- `flint.port/send` is what does it for ordinary code.
        a.native(ww, 0);
        a.konst(msg);
        a.native(wstr, 2);
        a.native(send, 2).op(op::RETURN);
        a.done()
    };
    let n = b.w.k_string("main");
    b.w.entry = b.w.add_fn(n, 1, false, 4, &body);
    let bytes = b.w.finish();
    let mut rt = b.rt;
    assert!(rt.load_image(&bytes));
    let l = rt.string("system");
    rt.install_system_port(SYSTEM, l);
    rt.run_program(NIL);
    let evs = drain(&mut rt);
    assert!(grant(&mut rt, open_ev(&evs).a));
    let (_, evs) = finish(&mut rt);
    let msgs: Vec<&Ev> = evs.iter().filter(|e| e.kind == conc::EV_MESSAGE as u32).collect();
    assert_eq!(msgs.len(), 1, "one send, one event");
    assert_eq!(msgs[0].a as i64, GRANTED, "addressed to the bridge");
    // ENCODED, because a bridge carries values and the runtime is what writes
    // them. The host reads it back with the same codec it uses everywhere else,
    // rather than being handed bytes the guest chose the shape of.
    let back = rt.decode(&msgs[0].payload).expect("the host can read what left");
    let mut sb = flint_rt::rt::sbuf();
    assert_eq!(rt.as_str(back, &mut sb), Some("a line"));
    assert_eq!(msgs[0].b as usize, msgs[0].payload.len(), "the length travels too");
}

/// What may cross, and it depends on the CARRIER.
///
/// A CHANNEL end never crosses a bridge: the host has never been told it exists
/// and its id would name one of our objects from outside. A BRIDGE handle does
/// cross, because its id is the host's own and means the same thing on the far
/// side -- that is how a capability is delegated, which `structured-ports` made the point
/// rather than the omission `host-abi` called the right default.
///
/// An opaque value crosses a bridge, and the runtime owning the encoding is
/// what makes that safe: the guest cannot mint one from bytes, which is what the
/// old third carry class existed to prevent and what removing the guest's codec
/// now prevents by construction.
#[test]
fn what_may_cross_depends_on_the_carrier() {
    let mut rt = Rt::new();
    rt.install_host_natives();
    rt.ensure_sched();
    let pair = rt.make_channel(4, NIL);
    let pi = rt.push(pair);
    let a = rt.vec_nth(rt.r(pi), 0, flint_rt::value::NIL);
    let ai = rt.push(a);
    // A CHANNEL end may cross a channel -- both ends are in this heap, so it is
    // a pointer move -- and may NOT cross a bridge: the host has never been told
    // it exists, so its id would name one of our objects from outside.
    assert!(
        rt.check_sendable_via(rt.r(ai), conc::CARRY_LOCAL).is_ok(),
        "a channel end crosses a channel"
    );
    assert!(
        rt.check_sendable_via(rt.r(ai), conc::CARRY_CROSSING).is_err(),
        "a channel end may not cross a bridge"
    );
    let l = rt.string("fs");
    let li = rt.push(l);
    let o = rt.new_opaque(rt.r(li), 9);
    let oi = rt.push(o);
    assert!(
        rt.check_sendable_via(rt.r(oi), conc::CARRY_CROSSING).is_ok(),
        "an opaque value crosses a bridge: the runtime owns the encoding"
    );
    let s = rt.string("data");
    let si = rt.push(s);
    assert!(rt.check_sendable_via(rt.r(si), conc::CARRY_CROSSING).is_ok(), "a string is data");
    // And NESTED: the walk is what makes the refusal worth anything.
    let v = rt.empty_vec();
    let vi = rt.push(v);
    let nested = rt.vec_conj(rt.r(vi), rt.r(ai));
    let ni = rt.push(nested);
    assert!(
        rt.check_sendable_via(rt.r(ni), conc::CARRY_CROSSING).is_err(),
        "a channel end inside a vector too"
    );
}

/// A KEYWORD delivered from the host arrives as a keyword.
///
/// The decoder is new on this side of the boundary (`DECISIONS.md#ports-are-the-hosts`): the
/// guest never decoded anything before, so this is the first time the runtime
/// builds a value out of bytes it did not write.
#[test]
fn a_keyword_crosses_a_bridge_inbound() {
    for name in ["a", "abcd", "abcde", "abcdef", "nope", "a-longer-keyword"] {
        let mut rt = opener(None);
        let evs = drain(&mut rt);
        assert!(grant(&mut rt, open_ev(&evs).a));
        let msg = {
            let v = rt.keyword(None, name);
            rt.encode(v).expect("a keyword encodes")
        };
        assert!(rt.host_deliver(GRANTED, &msg), "delivered {name}");
        let (v, _) = finish(&mut rt);
        // BYTES, then the keyword. The builtin hands over the encoding and the
        // decode is the guest's, so the keyword is recovered here rather than
        // arriving as one -- see the comment in the grant test above.
        //
        // The lengths matter and are why this loops: a keyword's name is
        // length-prefixed, and the inline/heap boundary sits between `abcd`
        // and `abcde`.
        assert!(rt.is_bytes(v), "{name}: a bridge carries bytes, not {v:?}");
        let raw = rt.b_to_vec(v);
        let decoded = rt.decode(&raw).expect("the delivered bytes decode");
        assert!(
            rt.is_keyword(decoded),
            "{name}: a keyword arrives as a keyword, not {decoded:?}"
        );
    }
}

/// A VAR IS FINDABLE BY NAME, and an absent one answers nil.
///
/// What this replaced: a test that delivered `{:tx n :op :call ..}` to the
/// system port and expected the answer back on it. That protocol was moved out
/// of the runtime and into the image (`DECISIONS.md#bridges-are-the-only-door`)
/// -- the system port carries CONTROL only now, calls go on a port it binds,
/// and `test/system.cljc` tests the protocol itself, in flint, once, for all
/// four runtimes instead of once per runtime in three languages.
///
/// What is left here is the primitive that protocol rests on and that no flint
/// test can reach: looking a var up by the name a host sent as a string.
/// `flint.system/answer` calls it for every inbound call, and a miss is the
/// ORDINARY case -- the shake drops unreachable code, and a string does not
/// keep a var alive (`DECISIONS.md#vars-is-its-own-grant`) -- so nil-for-absent
/// is as load-bearing as the hit.
#[test]
fn a_var_is_findable_by_name() {
    let mut b = Build::new();
    // `(defn answer [x] x)` -- an identity, so that what comes back out of the
    // var table is visibly the thing that went in. A VAR, because a call is
    // looked up by name in the var table, and an initialiser that sets it,
    // because that is how a program binds one.
    let fvar = {
        let c = b.w.k_string("mod/answer");
        b.w.add_var(c)
    };
    let body = {
        let mut a = Asm::new();
        a.op(op::LOCAL).u8v(0);
        a.op(op::RETURN);
        a.done()
    };
    let n = b.w.k_string("answer");
    let f = b.w.add_fn(n, 1, false, 4, &body);
    let init_body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(f as u16).u8v(0);
        a.op(op::SET_VAR).u16v(fvar as u16);
        a.op(op::NIL).op(op::RETURN);
        a.done()
    };
    let n2 = b.w.k_string("init");
    let init = b.w.add_fn(n2, 0, false, 4, &init_body);
    b.w.init.push(init);
    b.w.entry = f;
    let bytes = b.w.finish();
    let mut rt = b.rt;
    assert!(rt.load_image(&bytes), "image did not load");

    // INITIALISERS FIRST. A var is nil until they have run, so a lookup before
    // this finds the name and an empty global -- which is a different answer
    // from "no such name" and would make the miss below meaningless.
    assert!(rt.ensure_started(), "initialisers ran");

    let idx = rt.var_named("mod/answer").expect("the name is in the var table");
    let g = rt
        .roots
        .shared
        .globals
        .get(idx as usize)
        .map_or(NIL, |g| g.get());
    assert!(!g.is_nil(), "and the var holds something");
    assert!(rt.is_callable(g), "and what it holds can be called");

    assert!(
        rt.var_named("mod/nope").is_none(),
        "a name that is not there is nil, not an error"
    );
}
