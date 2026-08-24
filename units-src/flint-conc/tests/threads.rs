//! Green threads and ports, driven through the real image format and the real
//! builtins. These live in the unit's own crate because the builtins do.

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
    fn int(&mut self, n: i16) -> &mut Self {
        self.op(op::INT);
        self.b.extend_from_slice(&n.to_le_bytes());
        self
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
    /// A native import slot for one of the *runtime's* own builtins.
    fn rt_native(&mut self, name: &str) -> u32 {
        let slot = Rt::host_native_slot(name).unwrap_or_else(|| panic!("no builtin {name}"));
        let c = self.w.k_string(name);
        self.w.add_native(c, slot)
    }
    fn run(self) -> (Rt, Value) {
        let bytes = self.w.finish();
        let mut rt = self.rt;
        assert!(rt.load_image(&bytes), "image did not load");
        let argv = rt.empty_vec();
        let v = rt.run_program(argv);
        (rt, v)
    }
}

extern "C" fn spawn_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_spawn(rt, b, n)
}
extern "C" fn join_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_thread_join(rt, b, n)
}

#[test]
fn a_spawned_thread_runs_and_joins() {
    let mut b = Build::new();
    let spawn = b.conc("flint/spawn", spawn_);
    let join = b.conc("flint/thread-join", join_);
    let worker = {
        let body = {
            let mut a = Asm::new();
            a.int(42).op(op::RETURN);
            a.done()
        };
        let n = b.w.k_string("worker");
        b.w.add_fn(n, 0, false, 1, &body)
    };
    let body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(worker as u16).u8v(0);
        a.native(spawn, 1).native(join, 1).op(op::RETURN);
        a.done()
    };
    let n = b.w.k_string("main");
    b.w.entry = b.w.add_fn(n, 1, false, 2, &body);
    let (mut rt, v) = b.run();
    if rt.failed() {
        let e = rt.clear_error();
        let mut b1 = flint_rt::rt::sbuf();
        let k = rt.ex_kind(e);
        let ks: String = rt.as_str(k, &mut b1).unwrap_or("?").into();
        let m = rt.ex_message(e);
        let mut b2 = flint_rt::rt::sbuf();
        let ms: String = rt.as_str(m, &mut b2).unwrap_or("?").into();
        panic!("threw {ks}: {ms}");
    }
    assert_eq!(rt.as_i64(v), Some(42));
}

extern "C" fn channel_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_channel(rt, b, n)
}
extern "C" fn send_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_port_send(rt, b, n)
}
extern "C" fn recv_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_port_receive(rt, b, n)
}
extern "C" fn yield_(rt: *mut Rt, b: u32, n: u32) -> u64 {
    flint_conc::flint_b_yield(rt, b, n)
}

/// A worker builds a string, parks twice with it live on its own stack, and
/// only then sends it. With `stress` on, every allocation collects -- so if a
/// parked thread's saved stack were not a root, the string would be gone by the
/// time it is sent.
#[test]
fn parked_threads_survive_collection_with_their_values_intact() {
    let mut b = Build::new();
    let spawn = b.conc("flint/spawn", spawn_);
    let channel = b.conc("flint/channel", channel_);
    let send = b.conc("flint/port-send", send_);
    let recv = b.conc("flint/port-receive", recv_);
    let join = b.conc("flint/thread-join", join_);
    let yieldn = b.conc("flint/yield", yield_);
    let str_join = b.rt_native("flint/str-join");
    let nth = b.rt_native("nth");
    let k0 = b.w.k_string("abcdefgh");
    let k1 = b.w.k_string("ijklmnop");

    // (fn [] (let [s (str-join ["abcdefgh" "ijklmnop"])] (yield) (yield) (send a s) s))
    let worker = {
        let body = {
            let mut a = Asm::new();
            a.op(op::CONST).u16v(k0 as u16);
            a.op(op::CONST).u16v(k1 as u16);
            a.op(op::VECTOR).u16v(2);
            a.native(str_join, 1).op(op::SET_LOCAL).u8v(0);
            a.native(yieldn, 0).op(op::POP);
            a.native(yieldn, 0).op(op::POP);
            a.op(op::UPVAL).u8v(0).op(op::LOCAL).u8v(0).native(send, 2).op(op::POP);
            a.op(op::LOCAL).u8v(0).op(op::RETURN);
            a.done()
        };
        let n = b.w.k_string("worker");
        b.w.add_fn_upvals(n, 0, false, 2, &body, 1)
    };
    // main: [pair 0] [a 1] [b 2] [got 3] [w 4]
    let body = {
        let mut a = Asm::new();
        a.int(1).op(op::NIL).native(channel, 2).op(op::SET_LOCAL).u8v(0);
        a.op(op::LOCAL).u8v(0).int(0).native(nth, 2).op(op::SET_LOCAL).u8v(1);
        a.op(op::LOCAL).u8v(0).int(1).native(nth, 2).op(op::SET_LOCAL).u8v(2);
        a.op(op::LOCAL).u8v(1).op(op::CLOSURE).u16v(worker as u16).u8v(1);
        a.native(spawn, 1).op(op::SET_LOCAL).u8v(4);
        a.op(op::LOCAL).u8v(2).native(recv, 1).op(op::SET_LOCAL).u8v(3);
        a.op(op::LOCAL).u8v(4).native(join, 1).op(op::POP);
        a.op(op::LOCAL).u8v(3).op(op::RETURN);
        a.done()
    };
    let n = b.w.k_string("main");
    b.w.entry = b.w.add_fn(n, 1, false, 6, &body);

    let bytes = b.w.finish();
    let mut rt = b.rt;
    assert!(rt.load_image(&bytes));
    rt.gc.stress = true; // collect at every allocation
    let argv = rt.empty_vec();
    let v = rt.run_program(argv);
    assert!(!rt.failed(), "threw under stress");
    let mut buf = flint_rt::rt::sbuf();
    assert_eq!(
        rt.as_str(v, &mut buf),
        Some("abcdefghijklmnop"),
        "the string the parked worker was holding came back intact"
    );
    assert!(
        rt.gc.stats.minor + rt.gc.stats.major > 20,
        "stress mode should have collected many times"
    );
}

/// The negative control, in the shape `without_the_barrier_the_reference_would_
/// be_lost` set. Two identical runs; in the second the thread table is unhooked
/// from the root set, and the collector then does *not* copy the parked stack.
/// If this ever stops showing a difference, the test above has stopped proving
/// anything.
/// Park a distinctively large string inside a thread and collect, holding *no*
/// other reference to it. Returns how many bytes the collector copied and
/// whether the string came back intact.
fn park_a_string_and_collect(unhook: bool) -> (u64, bool) {
    // Big enough that its presence or absence dominates the byte count, so the
    // control below is a measurement rather than a guess.
    let big: String = "parked-".repeat(1024);
    let mut rt = Rt::new();
    rt.spawn_thread(NIL);
    let s = rt.string(&big);
    rt.roots.stack[0] = s;
    rt.roots.stack_top = 1;
    // Find the thread through the scheduler's own table, and hold nothing else:
    // a shadow root here would keep the stack alive by a second path and the
    // control would prove nothing.
    let ts = rt.slot(rt.sched(), conc::SC_THREADS);
    let th = rt.vec_nth(ts, 1).unwrap();
    rt.save_thread_state(th);
    rt.roots.stack_top = 0;
    rt.pop_to(0);

    if unhook {
        rt.roots.singletons[flint_rt::rt::SING_SCHED] = NIL;
    }
    let before = rt.gc.stats.bytes_copied;
    rt.collect();
    let copied = rt.gc.stats.bytes_copied - before;
    if unhook {
        return (copied, false);
    }
    let ts = rt.slot(rt.sched(), conc::SC_THREADS);
    let th = rt.vec_nth(ts, 1).unwrap();
    let saved = rt.thread_saved_stack(th);
    let back = rt.slot(saved, 0);
    let n = rt.str_len(back);
    (copied, n as usize == big.len())
}

#[test]
fn without_the_thread_table_as_a_root_a_parked_stack_would_be_lost() {
    let (rooted, intact) = park_a_string_and_collect(false);
    assert!(intact, "with the thread table rooted the parked value survives");
    let (unrooted, _) = park_a_string_and_collect(true);
    assert!(
        rooted - unrooted >= 7 * 1024,
        "negative control: with SING_SCHED cleared the collector must not copy the \
         parked thread's 7 KB string (copied {rooted} bytes rooted vs {unrooted} unrooted)"
    );
}

/// The case `0005` actually asked for and the suite did not have: a thread that
/// parks **on a waiter** -- a send into a full buffer, a receive on an empty one
/// -- across a collection, repeatedly, so that waiter slots are recycled.
///
/// The existing stress test above parks with `yield`, which registers no waiter
/// at all. That is the untested COMBINATION, and it is where the bug lived: the
/// compound-keys bug taught the same lesson, since stress mode was already
/// running on the map tests and still missed it because scalar keys never
/// allocate in `=`.
///
/// Three sends through a one-slot channel force the sender to park twice and the
/// receiver to park at least once, so a waiter is allocated, freed and reused
/// while every allocation is collecting.
#[test]
fn a_waiter_park_survives_collection_at_every_allocation() {
    let (got, collections) = ping_pong_under_stress(true);
    assert_eq!(
        got,
        Some(6),
        "the three values sent through a one-slot channel all arrived"
    );
    assert!(collections > 20, "stress mode should have collected many times");
}

/// The negative control, in the shape `without_the_barrier_the_reference_would_
/// be_lost` set: the same program with stress off must pass, so a failure above
/// is about collection and not about the program being wrong.
#[test]
fn the_same_ping_pong_is_correct_without_stress() {
    let (got, _) = ping_pong_under_stress(false);
    assert_eq!(got, Some(6));
}

/// `(let [[a b] (channel 1)]
///    (spawn (fn [] (send a 1) (send a 2) (send a 3)))
///    (+ (recv b) (recv b) (recv b)))`
///
/// Unrolled rather than looped so the bytecode stays readable; the point is the
/// parking, not the counting.
fn ping_pong_under_stress(stress: bool) -> (Option<i64>, u64) {
    let mut b = Build::new();
    let spawn = b.conc("flint/spawn", spawn_);
    let channel = b.conc("flint/channel", channel_);
    let send = b.conc("flint/port-send", send_);
    let recv = b.conc("flint/port-receive", recv_);
    let join = b.conc("flint/thread-join", join_);
    let nth = b.rt_native("nth");
    let plus = b.rt_native("flint/add");

    let worker = {
        let body = {
            let mut a = Asm::new();
            for v in 1..=3i16 {
                a.op(op::UPVAL).u8v(0);
                a.int(v);
                a.native(send, 2).op(op::POP);
            }
            a.op(op::NIL).op(op::RETURN);
            a.done()
        };
        let n = b.w.k_string("worker");
        b.w.add_fn_upvals(n, 0, false, 2, &body, 1)
    };
    // main: [pair 0] [a 1] [b 2] [w 3]
    let body = {
        let mut a = Asm::new();
        a.int(1).op(op::NIL).native(channel, 2).op(op::SET_LOCAL).u8v(0);
        a.op(op::LOCAL).u8v(0).int(0).native(nth, 2).op(op::SET_LOCAL).u8v(1);
        a.op(op::LOCAL).u8v(0).int(1).native(nth, 2).op(op::SET_LOCAL).u8v(2);
        a.op(op::LOCAL).u8v(1).op(op::CLOSURE).u16v(worker as u16).u8v(1);
        a.native(spawn, 1).op(op::SET_LOCAL).u8v(3);
        // (+ (recv b) (recv b) (recv b)) -- each may park on an empty buffer
        a.op(op::LOCAL).u8v(2).native(recv, 1);
        a.op(op::LOCAL).u8v(2).native(recv, 1);
        a.native(plus, 2);
        a.op(op::LOCAL).u8v(2).native(recv, 1);
        a.native(plus, 2);
        a.op(op::SET_LOCAL).u8v(4);
        a.op(op::LOCAL).u8v(3).native(join, 1).op(op::POP);
        a.op(op::LOCAL).u8v(4).op(op::RETURN);
        a.done()
    };
    let n = b.w.k_string("main");
    b.w.entry = b.w.add_fn(n, 1, false, 6, &body);

    let bytes = b.w.finish();
    let mut rt = b.rt;
    assert!(rt.load_image(&bytes));
    rt.gc.stress = stress;
    let argv = rt.empty_vec();
    let v = rt.run_program(argv);
    if rt.failed() {
        let e = rt.clear_error();
        let m = rt.ex_message(e);
        let mut buf = flint_rt::rt::sbuf();
        let ms: String = rt.as_str(m, &mut buf).unwrap_or("?").into();
        panic!("threw under stress={stress}: {ms}");
    }
    let n = rt.as_i64(v);
    (n, rt.gc.stats.minor + rt.gc.stats.major)
}
