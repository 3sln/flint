//! Green threads, ports, and the scheduler.
//!
//! # Nothing here suspends a wasm frame
//!
//! The obvious reading of "a blocking `open` that asks the host" runs straight
//! into the one hard problem in wasm: a synchronous export cannot be suspended
//! mid-execution. The usual escapes are JSPI (JS hosts only) and Asyncify (size
//! and speed on every function, forever). flint needs **neither**, because it is
//! an interpreter: a green thread is a VM state, the scheduler picks a runnable
//! one, and *blocking* means *not runnable yet*. The interpreter never leaves
//! its own loop and the host is never blocked. See `doc/decisions/0005`.
//!
//! # Nothing here is in a pure module
//!
//! Every function in this file is reached only from the concurrency unit's
//! builtins, or through `Rt::sched_hook`, which is `None` until one of those
//! builtins runs. A program that never mentions `spawn`, `channel` or `open`
//! never exports any of them, so `--gc-sections` deletes the lot -- the same
//! mechanism that keeps the XML parser out (`doc/decisions/0003`).
//!
//! # And nothing here is new work for the collector
//!
//! The GC's design rests on *the value stack IS the root set*. With N threads
//! there are N stacks, and a parked one is full of live references nothing is
//! executing. Rather than teach the collector about threads, a parked thread
//! **is an ordinary heap object whose slots hold its saved stack**, and the
//! thread table hangs off `singletons[SING_SCHED]`, which the collector already
//! traces. The root walk did not change at all.

use alloc::vec::Vec;

use crate::obj::*;
use crate::gc::INTERN_PORT;
use crate::rt::{Rt, SING_SCHED};
use crate::value::{Value, NIL, PARK};
use crate::vm::{Frame, Handler};

// --- thread ----------------------------------------------------------------

pub const TH_STATUS: u32 = 0;
pub const TH_PARK_ON: u32 = 1;
pub const TH_RESULT: u32 = 2;
/// Saved value stack: a Vals object, so tracing it is the ordinary walk.
pub const TH_STACK: u32 = 3;
/// Saved interpreter frames, as raw bytes. Frames hold only indices -- the
/// closure of a frame is `stack[ret_to]`, never a copy -- so there is nothing
/// in here for the collector to find, and a raw blob is the honest encoding.
pub const TH_FRAMES: u32 = 4;
pub const TH_HANDLERS: u32 = 5;
/// Dynamic bindings: a map of var -> value, per green thread (section 4).
pub const TH_BINDINGS: u32 = 6;
pub const TH_ID: u32 = 7;
pub const TH_ENTRY: u32 = 8;
/// The port an in-flight `open` is waiting on, so that re-executing the call
/// after the park finds the same request rather than making a second one.
pub const TH_PENDING: u32 = 9;
/// The waiter token this thread is parked on, or -1. One token type for every
/// kind of park (`doc/decisions/0006`).
pub const TH_TOKEN: u32 = 10;
/// An error handed to a parked thread, to be raised when it next runs. The
/// scheduler uses it to say "the other end of your port is gone" in the thread
/// that cares rather than in whichever thread noticed.
pub const TH_FAIL: u32 = 11;
pub const TH_LEN: u32 = 12;

pub const ST_NEW: i64 = 0;
pub const ST_RUNNABLE: i64 = 1;
pub const ST_PARKED: i64 = 2;
pub const ST_DONE: i64 = 3;
pub const ST_FAILED: i64 = 4;

/// `park_on` for a thread that simply used up its slice. Not a port, and not
/// nil, so the scheduler can tell "ready again immediately" from "waiting".
pub const PARK_YIELD: Value = Value((crate::value::TAG_FIXNUM << 48) | 0);

// --- port ------------------------------------------------------------------
//
// A port is one END. `channel` makes two of them; `open` makes two of them, one
// of which the host holds. The two ends have **separate lifetimes**
// (`doc/decisions/0006`):
//
// * a **host end** is a strong root -- it must be, or every handle the host is
//   holding is a use-after-free waiting for a collection;
// * a **flint end** is ordinary reachable memory, and when the collector finds
//   it unreachable that is semantically identical to the script having called
//   `close`. The scheduler notices and raises `:closed` on its behalf.
//
// Which is why the two ends do **not** point at each other with `Value`s: each
// holds the other's *id*, and ids are resolved through a weak table
// (`INTERN_PORT`). A strong peer link would keep a dropped end alive for ever,
// and would also defeat the liveness check that wakes a thread parked on a port
// whose peer has gone.

pub const PT_ID: u32 = 0;
pub const PT_STATE: u32 = 1;
/// Buffer bound. **Bytes** for a host end, messages for a channel end: the
/// point of back-pressure is to bound memory, and one 4 MB message is not one
/// message's worth of memory.
pub const PT_CAP: u32 = 2;
/// Inbox: a vector used as a FIFO with a read cursor, so both ends are O(1).
pub const PT_INBOX: u32 = 3;
pub const PT_HEAD: u32 = 4;
/// Bytes currently queued against this end's bound.
pub const PT_BYTES: u32 = 5;
/// The other end's **id**, not the other end. See above.
pub const PT_PEER: u32 = 6;
pub const PT_LABEL: u32 = 7;
pub const PT_KIND: u32 = 8;
/// Where this end sits in `roots.singletons` when it is a host end, so closing
/// can let go of the strong root. -1 otherwise.
pub const PT_ROOT: u32 = 9;
/// Format the creating side asked for, as a keyword; nil for a channel.
pub const PT_FORMAT: u32 = 10;
/// Whatever the cljc layer wants to remember about this port -- codec options,
/// mostly. Here rather than in a side table so that it dies with the port.
pub const PT_OPTS: u32 = 11;
/// 1 when this port's bytes are not text. A host port carries **bytes**; most
/// formats happen to be UTF-8, but a binary one (Transit-msgpack) is not, so
/// its payloads travel as vectors of 0..255 rather than as strings.
pub const PT_BINARY: u32 = 12;
pub const PT_LEN: u32 = 13;

/// One end of a `channel` pair: no host involvement at all.
pub const K_CHANNEL: i64 = 0;
/// The end a script holds after `open`. Ordinary memory.
pub const K_FLINT: i64 = 1;
/// The end the host holds. A strong root until the host closes it.
pub const K_HOST: i64 = 2;

pub const P_PENDING: i64 = 0;
pub const P_OPEN: i64 = 1;
pub const P_CLOSED: i64 = 2;
pub const P_REFUSED: i64 = 3;
/// The peer closed cleanly; this end may still drain what is already buffered
/// and then reads end-of-stream. A channel is freed only when BOTH ends are
/// done, so this is a real, describable state rather than a race
/// (`doc/decisions/0006`).
pub const P_HALF: i64 = 4;
/// The peer went away without closing -- collected, or a host that hung up.
/// Unlike `P_HALF` nobody said goodbye, so a receive here **errors** rather than
/// reading as a tidy end of stream.
pub const P_ORPHANED: i64 = 5;

/// Messages, for a channel end.
pub const DEFAULT_CAP: i64 = 16;
/// Bytes, for a host end. Back-pressure exists to bound memory.
pub const DEFAULT_HOST_CAP: i64 = 1 << 20;

// --- waiters ---------------------------------------------------------------
//
// Everything that parks parks the same way -- `open`, a send to a full port, a
// receive on an empty one -- through one table with one token type. A token is
// `(generation << 16) | index`: a bare index is reusable, so a late or
// duplicated reply from the host would resume whatever now occupies that slot,
// which is a wrong thread woken with a stranger's value and unfindable in
// production. The generation makes that a rejection instead.

pub const W_GEN: u32 = 0;
pub const W_THREAD: u32 = 1;
pub const W_KIND: u32 = 2;
pub const W_PORT: u32 = 3;
pub const W_NEXT: u32 = 4;
pub const W_LEN: u32 = 5;

pub const WK_OPEN: i64 = 1;
pub const WK_SEND: i64 = 2;
pub const WK_RECEIVE: i64 = 3;
pub const WK_JOIN: i64 = 4;

// --- scheduler state -------------------------------------------------------

pub const SC_THREADS: u32 = 0;
pub const SC_CURRENT: u32 = 1;
pub const SC_NEXTID: u32 = 2;
/// Pending host events, oldest first: each is `[op id payload]`.
pub const SC_EVENTS: u32 = 3;
pub const SC_EHEAD: u32 = 4;
/// Live port **ids**, not ports: a strong list would pin every port for ever,
/// and then nothing could ever notice a flint end being dropped.
pub const SC_PORTS: u32 = 5;
/// `[id peer-id]` for every pair ever made, so that when one end is collected
/// we can still say whose peer it was.
pub const SC_PAIRS: u32 = 6;
pub const SC_WAITERS: u32 = 7;
pub const SC_WFREE: u32 = 8;
pub const SC_LEN: u32 = 9;

/// The one outbound queue. One export, one call per pump, one ordering rule.
pub const EV_OPEN: i64 = 1;
pub const EV_MESSAGE: i64 = 2;
pub const EV_CLOSED: i64 = 3;

/// Instructions a thread runs before the scheduler takes it off. Fixed, because
/// a deterministic answer is most of what this project is for: the same program
/// and the same order of host events must give the same result every time.
pub const SLICE_DEFAULT: u64 = 4096;
pub static mut SLICE_OVERRIDE: u64 = 0;
#[inline]
pub fn slice() -> u64 { unsafe { if SLICE_OVERRIDE != 0 { SLICE_OVERRIDE } else { SLICE_DEFAULT } } }

fn fx(v: Value) -> i64 {
    v.as_fixnum()
}

impl Rt {
    // --- small helpers -----------------------------------------------------

    pub fn is_thread(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_THREAD
    }
    pub fn is_port(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_PORT
    }

    fn new_obj(&mut self, t: u8, n: u32) -> Value {
        let a = self.alloc(t, n);
        if a == 0 {
            return NIL;
        }
        Value::heap(a)
    }

    // --- scheduler state ---------------------------------------------------

    pub fn sched(&self) -> Value {
        self.roots.singletons[SING_SCHED]
    }

    /// Create the scheduler on first use, enrolling whatever is running now as
    /// thread 0. Installing the hook here -- rather than at startup -- is what
    /// keeps `run_program` a straight line in a program that never spawns.
    pub fn ensure_sched(&mut self) -> Value {
        let s = self.sched();
        if !s.is_nil() {
            return s;
        }
        let base = self.mark();
        let sc = self.new_obj(TY_SCHED, SC_LEN);
        if sc.is_nil() {
            return NIL;
        }
        let si = self.push(sc);
        let ev = self.empty_vec();
        let pv = self.empty_vec();
        self.set(self.r(si), SC_EVENTS, ev);
        self.set(self.r(si), SC_EHEAD, Value::fixnum(0));
        self.set(self.r(si), SC_PORTS, pv);
        let pairs = self.empty_vec();
        self.set(self.r(si), SC_PAIRS, pairs);
        let ws = self.empty_vec();
        self.set(self.r(si), SC_WAITERS, ws);
        self.set(self.r(si), SC_WFREE, Value::fixnum(-1));
        self.set(self.r(si), SC_NEXTID, Value::fixnum(1));
        self.set(self.r(si), SC_CURRENT, Value::fixnum(0));
        // The running thread becomes thread 0. Its stack is the live one, so it
        // has nothing saved until it parks.
        let th = self.new_obj(TY_THREAD, TH_LEN);
        if th.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let ti = self.push(th);
        self.set(self.r(ti), TH_STATUS, Value::fixnum(ST_RUNNABLE));
        self.set(self.r(ti), TH_ID, Value::fixnum(0));
        self.set(self.r(ti), TH_TOKEN, Value::fixnum(-1));
        let empty = self.empty_map();
        self.set(self.r(ti), TH_BINDINGS, empty);
        let ts = self.empty_vec();
        let tsi = self.push(ts);
        let t = self.r(ti);
        let ts = self.vec_conj(self.r(tsi), t);
        self.set(self.r(si), SC_THREADS, ts);
        let out = self.r(si);
        self.roots.singletons[SING_SCHED] = out;
        self.pop_to(base);
        self.sched_hook = Some(scheduler);
        let at = self.steps + slice();
        self.set_slice_end(at);
        out
    }

    pub fn current_thread(&self) -> Value {
        let s = self.sched();
        if s.is_nil() {
            return NIL;
        }
        let i = fx(self.slot(s, SC_CURRENT)) as u32;
        self.vec_nth(self.slot(s, SC_THREADS), i).unwrap_or(NIL)
    }

    // --- saving and restoring a VM state -----------------------------------

    /// Save the live VM state into `th`. Public so that the collector tests can
    /// build the exact situation section 3 of `doc/decisions/0005` warns about:
    /// a parked thread holding the only reference to a value.
    pub fn save_thread_state(&mut self, th: Value) {
        self.save_current_state(th)
    }

    /// The saved stack of a parked thread, for the same tests.
    pub fn thread_saved_stack(&self, th: Value) -> Value {
        self.slot(th, TH_STACK)
    }

    fn save_current_state(&mut self, th: Value) {
        unsafe { crate::gc::PHASE = 5; }
        let base = self.mark();
        let ti = self.push(th);
        let n = self.roots.stack_top as u32;
        let sv = self.new_obj(TY_NODE, n);
        if sv.is_nil() {
            self.pop_to(base);
            return;
        }
        for i in 0..n {
            let v = self.roots.stack[i as usize];
            self.gc.set_slot(sv.as_heap(), i, v);
        }
        self.set(self.r(ti), TH_STACK, sv);

        let fb = self.new_obj(TY_RAW, (self.frames.len() * 20) as u32);
        if !fb.is_nil() {
            let addr = fb.as_heap();
            let bytes = self.gc.sp.bytes_mut(addr + HDR, (self.frames.len() * 20) as u32);
            for (k, f) in self.frames.iter().enumerate() {
                let o = k * 20;
                bytes[o..o + 4].copy_from_slice(&(f.fp as u32).to_le_bytes());
                bytes[o + 4..o + 8].copy_from_slice(&f.ip.to_le_bytes());
                bytes[o + 8..o + 12].copy_from_slice(&f.end.to_le_bytes());
                bytes[o + 12..o + 16].copy_from_slice(&(f.ret_to as u32).to_le_bytes());
                bytes[o + 16..o + 20].copy_from_slice(&(f.handlers as u32).to_le_bytes());
            }
        }
        self.set(self.r(ti), TH_FRAMES, fb);

        let hb = self.new_obj(TY_RAW, (self.handlers.len() * 16) as u32);
        if !hb.is_nil() {
            let addr = hb.as_heap();
            let bytes = self.gc.sp.bytes_mut(addr + HDR, (self.handlers.len() * 16) as u32);
            for (k, h) in self.handlers.iter().enumerate() {
                let o = k * 16;
                bytes[o..o + 4].copy_from_slice(&(h.frame as u32).to_le_bytes());
                bytes[o + 4..o + 8].copy_from_slice(&(h.stack_top as u32).to_le_bytes());
                bytes[o + 8..o + 12].copy_from_slice(&h.target.to_le_bytes());
                bytes[o + 12..o + 16].copy_from_slice(&(h.shadow as u32).to_le_bytes());
            }
        }
        self.set(self.r(ti), TH_HANDLERS, hb);
        self.pop_to(base);

        self.frames.clear();
        self.handlers.clear();
        self.roots.stack_top = 0;
    }

    fn restore_state(&mut self, th: Value) {
        unsafe { crate::gc::PHASE = 2; }
        let sv = self.slot(th, TH_STACK);
        self.frames.clear();
        self.handlers.clear();
        self.roots.stack_top = 0;
        if !sv.is_nil() {
            let n = self.olen(sv);
            if self.roots.stack.len() < n as usize + 8 {
                self.roots.stack.resize(n as usize + 8, NIL);
            }
            for i in 0..n {
                let v = self.slot(sv, i);
                self.roots.stack[i as usize] = v;
            }
            self.roots.stack_top = n as usize;
        }
        let fb = self.slot(th, TH_FRAMES);
        if !fb.is_nil() {
            let n = self.olen(fb) as usize / 20;
            let bytes: Vec<u8> = raw_bytes(&self.gc.sp, fb.as_heap()).to_vec();
            for k in 0..n {
                let o = k * 20;
                let g = |i: usize| {
                    u32::from_le_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]])
                };
                self.frames.push(Frame {
                    fp: g(o) as usize,
                    ip: g(o + 4),
                    end: g(o + 8),
                    ret_to: g(o + 12) as usize,
                    handlers: g(o + 16) as usize,
                });
            }
        }
        let hb = self.slot(th, TH_HANDLERS);
        if !hb.is_nil() {
            let n = self.olen(hb) as usize / 16;
            let bytes: Vec<u8> = raw_bytes(&self.gc.sp, hb.as_heap()).to_vec();
            for k in 0..n {
                let o = k * 16;
                let g = |i: usize| {
                    u32::from_le_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]])
                };
                self.handlers.push(Handler {
                    frame: g(o) as usize,
                    stack_top: g(o + 4) as usize,
                    target: g(o + 8),
                    shadow: g(o + 12) as usize,
                });
            }
        }
    }

    // --- parking -----------------------------------------------------------

    /// Signal a park. `park_on` is the wake key; `park_on_port` is what
    /// registers the waiter that gives it a token.
    pub fn park(&mut self, on: Value) -> Value {
        self.park_on = on;
        self.thrown = PARK;
        NIL
    }

    // --- spawning ----------------------------------------------------------

    /// A new green thread running `f` (no arguments).
    ///
    /// It **inherits a snapshot of its spawner's dynamic bindings**, which is
    /// what Clojure conveys to `future` and agents, and what somebody debugging
    /// at three in the morning will assume. The snapshot is taken here: later
    /// `binding` in the spawner does not reach the child.
    pub fn spawn_thread(&mut self, f: Value) -> Value {
        // Rooted first: `ensure_sched` allocates, and `f` is a Rust local.
        let base = self.mark();
        let fi = self.push(f);
        self.ensure_sched();
        let th = self.new_obj(TY_THREAD, TH_LEN);
        if th.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let ti = self.push(th);
        let s = self.sched();
        let si = self.push(s);
        let id = fx(self.slot(self.r(si), SC_NEXTID));
        self.set(self.r(si), SC_NEXTID, Value::fixnum(id + 1));
        self.set(self.r(ti), TH_STATUS, Value::fixnum(ST_NEW));
        self.set(self.r(ti), TH_ID, Value::fixnum(id));
        self.set(self.r(ti), TH_TOKEN, Value::fixnum(-1));
        let ff = self.r(fi);
        self.set(self.r(ti), TH_ENTRY, ff);
        // Inherit a SNAPSHOT of the spawner's dynamic bindings, as Clojure
        // conveys them to `future` and agents. A snapshot: rebinding in the
        // spawner afterwards does not reach the child.
        let binds = self.roots.singletons[crate::rt::SING_BINDINGS];
        let binds = if binds.is_nil() { self.empty_map() } else { binds };
        self.set(self.r(ti), TH_BINDINGS, binds);
        let ts = self.slot(self.r(si), SC_THREADS);
        let tsi = self.push(ts);
        let t = self.r(ti);
        let nts = self.vec_conj(self.r(tsi), t);
        self.set(self.r(si), SC_THREADS, nts);
        let out = self.r(ti);
        self.pop_to(base);
        out
    }
    // --- ports -------------------------------------------------------------

    /// Look a port up by id. The table is **weak**, so a miss means the object
    /// has been collected -- which, for a flint end, is what "the script is
    /// finished with it" looks like.
    pub fn port_by_id(&mut self, id: i64) -> Value {
        if id < 0 {
            return NIL;
        }
        let sp = &self.gc.sp;
        match self.roots.interns[INTERN_PORT].lookup(id as u32, |v| {
            v.is_heap() && ty(sp, v.as_heap()) == TY_PORT
        }) {
            Ok(v) => v,
            Err(_) => NIL,
        }
    }

    fn register_port(&mut self, p: Value) {
        let id = fx(self.slot(p, PT_ID)) as u32;
        self.intern_into(INTERN_PORT, id, p);
        // The scheduler keeps ids, not references: a strong list here would
        // pin every port for ever and there would be nothing to notice.
        let base = self.mark();
        let pi = self.push(p);
        let s = self.sched();
        let si = self.push(s);
        let ids = self.slot(self.r(si), SC_PORTS);
        let ii = self.push(ids);
        let nids = self.vec_conj(self.r(ii), Value::fixnum(id as i64));
        self.set(self.r(si), SC_PORTS, nids);
        let _ = pi;
        self.pop_to(base);
    }

    /// A host end must outlive every flint reference to it, so it goes in
    /// `singletons`, which the collector already traces. Returns the slot.
    fn root_port(&mut self, p: Value) -> i64 {
        for i in crate::rt::SING_COUNT..self.roots.singletons.len() {
            if self.roots.singletons[i].is_nil() {
                self.roots.singletons[i] = p;
                return i as i64;
            }
        }
        self.roots.singletons.push(p);
        (self.roots.singletons.len() - 1) as i64
    }

    fn unroot_port(&mut self, p: Value) {
        let slot = fx(self.slot(p, PT_ROOT));
        if slot >= 0 && (slot as usize) < self.roots.singletons.len() {
            self.roots.singletons[slot as usize] = NIL;
            self.set(p, PT_ROOT, Value::fixnum(-1));
        }
    }

    fn new_port(&mut self, cap: i64, label: Value, kind: i64, state: i64, format: Value) -> Value {
        let base = self.mark();
        let li = self.push(label);
        let fi = self.push(format);
        let p = self.new_obj(TY_PORT, PT_LEN);
        if p.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let pi = self.push(p);
        let s = self.sched();
        let si = self.push(s);
        let id = fx(self.slot(self.r(si), SC_NEXTID));
        self.set(self.r(si), SC_NEXTID, Value::fixnum(id + 1));
        self.set(self.r(pi), PT_ID, Value::fixnum(id));
        self.set(self.r(pi), PT_STATE, Value::fixnum(state));
        self.set(self.r(pi), PT_CAP, Value::fixnum(cap));
        let ev = self.empty_vec();
        self.set(self.r(pi), PT_INBOX, ev);
        self.set(self.r(pi), PT_HEAD, Value::fixnum(0));
        self.set(self.r(pi), PT_BYTES, Value::fixnum(0));
        self.set(self.r(pi), PT_PEER, Value::fixnum(-1));
        let l = self.r(li);
        self.set(self.r(pi), PT_LABEL, l);
        self.set(self.r(pi), PT_KIND, Value::fixnum(kind));
        self.set(self.r(pi), PT_ROOT, Value::fixnum(-1));
        let f = self.r(fi);
        self.set(self.r(pi), PT_FORMAT, f);
        let o = self.empty_map();
        self.set(self.r(pi), PT_OPTS, o);
        self.set(self.r(pi), PT_BINARY, Value::fixnum(0));
        let pv = self.r(pi);
        self.register_port(pv);
        if kind == K_HOST {
            let pv = self.r(pi);
            let slot = self.root_port(pv);
            self.set(self.r(pi), PT_ROOT, Value::fixnum(slot));
        }
        let out = self.r(pi);
        self.pop_to(base);
        out
    }

    fn link_peers(&mut self, a: Value, b: Value) {
        let ida = fx(self.slot(a, PT_ID));
        let idb = fx(self.slot(b, PT_ID));
        self.set(a, PT_PEER, Value::fixnum(idb));
        self.set(b, PT_PEER, Value::fixnum(ida));
        // Ids only. When one end is collected its object is gone, so the
        // pairing has to be recorded somewhere that does not hold it alive.
        let base = self.mark();
        let sc = self.sched();
        let si = self.push(sc);
        for (x, y) in [(ida, idb), (idb, ida)] {
            let pairs = self.slot(self.r(si), SC_PAIRS);
            let pi = self.push(pairs);
            let e = self.empty_vec();
            let ei = self.push(e);
            let ne = self.vec_conj(self.r(ei), Value::fixnum(x));
            self.set_r(ei, ne);
            let ne = self.vec_conj(self.r(ei), Value::fixnum(y));
            self.set_r(ei, ne);
            let ev = self.r(ei);
            let np = self.vec_conj(self.r(pi), ev);
            self.set(self.r(si), SC_PAIRS, np);
            self.pop_to(pi);
        }
        self.pop_to(base);
    }

    /// A coupled pair. What goes into one comes out of the other, both ways.
    pub fn make_channel(&mut self, cap: i64, label: Value) -> Value {
        self.ensure_sched();
        let base = self.mark();
        let li = self.push(label);
        let l = self.r(li);
        let a = self.new_port(cap, l, K_CHANNEL, P_OPEN, NIL);
        let ai = self.push(a);
        let l = self.r(li);
        let b = self.new_port(cap, l, K_CHANNEL, P_OPEN, NIL);
        let bi = self.push(b);
        let (av, bv) = (self.r(ai), self.r(bi));
        self.link_peers(av, bv);
        let v = self.empty_vec();
        let vi = self.push(v);
        let av = self.r(ai);
        let nv = self.vec_conj(self.r(vi), av);
        self.set_r(vi, nv);
        let bv = self.r(bi);
        let nv = self.vec_conj(self.r(vi), bv);
        self.pop_to(base);
        nv
    }

    fn peer_of(&mut self, p: Value) -> Value {
        let id = fx(self.slot(p, PT_PEER));
        self.port_by_id(id)
    }

    fn inbox_count(&self, p: Value) -> u32 {
        let ib = self.slot(p, PT_INBOX);
        let head = fx(self.slot(p, PT_HEAD)) as u32;
        self.vec_count(ib) - head
    }

    fn port_enqueue(&mut self, p: Value, v: Value) {

        let base = self.mark();
        let pi = self.push(p);
        let vi = self.push(v);
        let ib = self.slot(self.r(pi), PT_INBOX);
        let ibi = self.push(ib);
        let vv = self.r(vi);
        let nib = self.vec_conj(self.r(ibi), vv);
        self.set(self.r(pi), PT_INBOX, nib);
        self.pop_to(base);
    }

    fn port_dequeue(&mut self, p: Value) -> Value {

        let head = fx(self.slot(p, PT_HEAD)) as u32;
        let ib = self.slot(p, PT_INBOX);
        let v = self.vec_nth(ib, head).unwrap_or(NIL);
        let n = self.vec_count(ib);
        if head + 1 >= n {
            // Drained: drop the backing vector so nothing stays reachable.
            let e = self.empty_vec();
            self.set(p, PT_INBOX, e);
            self.set(p, PT_HEAD, Value::fixnum(0));
        } else {
            self.set(p, PT_HEAD, Value::fixnum(head as i64 + 1));
        }
        v
    }

    // --- waiters and their tokens ------------------------------------------

    fn waiters(&self) -> Value {
        self.slot(self.sched(), SC_WAITERS)
    }

    /// Register a waiter for the current thread and return its token.
    fn new_waiter(&mut self, kind: i64, port: Value) -> i64 {
        let base = self.mark();
        let pi = self.push(port);
        let s = self.sched();
        let si = self.push(s);
        let free = fx(self.slot(self.r(si), SC_WFREE));
        let th = self.current_thread();
        let ti = self.push(th);
        let (idx, w) = if free >= 0 {
            let ws = self.slot(self.r(si), SC_WAITERS);
            let w = self.vec_nth(ws, free as u32).unwrap_or(NIL);
            let next = fx(self.slot(w, W_NEXT));
            self.set(self.r(si), SC_WFREE, Value::fixnum(next));
            (free, w)
        } else {
            let w = self.new_obj(TY_NODE, W_LEN);
            if w.is_nil() {
                self.pop_to(base);
                return -1;
            }
            let wi = self.push(w);
            self.set(self.r(wi), W_GEN, Value::fixnum(0));
            let ws = self.slot(self.r(si), SC_WAITERS);
            let wsi = self.push(ws);
            let wv = self.r(wi);
            let nws = self.vec_conj(self.r(wsi), wv);
            let idx = self.vec_count(nws) as i64 - 1;
            self.set(self.r(si), SC_WAITERS, nws);
            (idx, self.r(wi))
        };
        let wi = self.push(w);
        let t = self.r(ti);
        self.set(self.r(wi), W_THREAD, t);
        self.set(self.r(wi), W_KIND, Value::fixnum(kind));
        let pv = self.r(pi);
        self.set(self.r(wi), W_PORT, pv);
        let gen = fx(self.slot(self.r(wi), W_GEN));
        self.pop_to(base);
        // 1-based, so that 0 is never a valid token: a host ABI where the
        // zero value means something is a trap waiting for an uninitialised
        // variable.
        (gen << 16) | (idx + 1)
    }

    fn waiter_at(&mut self, token: i64) -> Value {
        if token <= 0 || (token & 0xFFFF) == 0 {
            return NIL;
        }
        let idx = (token & 0xFFFF) as u32 - 1;
        let gen = token >> 16;
        let ws = self.waiters();
        let w = self.vec_nth(ws, idx).unwrap_or(NIL);
        if w.is_nil() {
            return NIL;
        }
        if fx(self.slot(w, W_GEN)) != gen || self.slot(w, W_THREAD).is_nil() {
            return NIL;
        }
        w
    }

    /// Free a waiter slot and bump its generation, so a token naming it can
    /// never be honoured twice.
    fn free_waiter(&mut self, token: i64) {
        let w = self.waiter_at(token);
        if w.is_nil() {
            return;
        }
        let idx = (token & 0xFFFF) - 1;
        let gen = fx(self.slot(w, W_GEN));
        self.set(w, W_GEN, Value::fixnum((gen + 1) & 0xFFFF));
        self.set(w, W_THREAD, NIL);
        self.set(w, W_PORT, NIL);
        let s = self.sched();
        let free = self.slot(s, SC_WFREE);
        self.set(w, W_NEXT, free);
        self.set(s, SC_WFREE, Value::fixnum(idx));
    }

    /// How many green threads are parked with a token outstanding. A host that
    /// never answers leaks these; the deadlock report names them.
    pub fn outstanding_waiters(&mut self) -> u32 {
        let ws = self.waiters();
        let n = self.vec_count(ws);
        let mut c = 0;
        for i in 0..n {
            let w = self.vec_nth(ws, i).unwrap_or(NIL);
            if !w.is_nil() && !self.slot(w, W_THREAD).is_nil() {
                c += 1;
            }
        }
        c
    }

    // --- parking -----------------------------------------------------------

    /// Park the current thread until `port` (or any other object used as a wake
    /// key -- a thread, for `join`) makes it runnable, registering a waiter. The VM sees
    /// `thrown == PARK` in the check it already makes after every native call,
    /// rewinds to the call, and returns; resuming re-executes the call. A
    /// parking builtin must therefore decide to park **before** it changes
    /// anything.
    fn park_on_port(&mut self, kind: i64, port: Value) -> Value {
        let base = self.mark();
        let pi = self.push(port);
        let pv = self.r(pi);
        let token = self.new_waiter(kind, pv);
        let th = self.current_thread();
        if !th.is_nil() {
            self.set(th, TH_TOKEN, Value::fixnum(token));
        }
        let pv = self.r(pi);
        self.pop_to(base);
        self.park(pv)
    }

    fn wake_waiter(&mut self, w: Value) {
        let th = self.slot(w, W_THREAD);
        if th.is_nil() {
            return;
        }
        self.set(th, TH_STATUS, Value::fixnum(ST_RUNNABLE));
        self.set(th, TH_PARK_ON, NIL);
        let token = fx(self.slot(th, TH_TOKEN));
        self.set(th, TH_TOKEN, Value::fixnum(-1));
        self.free_waiter(token);
    }

    /// Make every thread waiting on `p` runnable again. They re-execute the
    /// call they parked in, which is what makes "wake" correct without anyone
    /// having to reason about who gets the value: whoever runs first takes it,
    /// and the others simply park again.
    pub fn wake_on(&mut self, p: Value) {
        let ws = self.waiters();
        let n = self.vec_count(ws);
        for i in 0..n {
            let w = self.vec_nth(ws, i).unwrap_or(NIL);
            if w.is_nil() || self.slot(w, W_THREAD).is_nil() {
                continue;
            }
            if self.slot(w, W_PORT).bits() == p.bits() {
                self.wake_waiter(w);
            }
        }
    }

    /// Wake everything parked on `p` with an error instead of a value. Used
    /// when the peer end has been collected: that receive can never succeed,
    /// and a hang is the worst possible way to say so.
    fn fail_waiters_on(&mut self, p: Value, msg: &str) {
        let base = self.mark();
        let pi = self.push(p);
        let ws = self.waiters();
        let wsi = self.push(ws);
        let n = self.vec_count(self.r(wsi));
        for i in 0..n {
            let w = self.vec_nth(self.r(wsi), i).unwrap_or(NIL);
            if w.is_nil() || self.slot(w, W_THREAD).is_nil() {
                continue;
            }
            if self.slot(w, W_PORT).bits() != self.r(pi).bits() {
                continue;
            }
            let th = self.slot(w, W_THREAD);
            let ti = self.push(th);
            self.wake_waiter(w);
            let e = self.make_error("IllegalStateException", msg);
            let ei = self.push(e);
            let ev = self.r(ei);
            self.set(self.r(ti), TH_FAIL, ev);
            self.pop_to(ti);
        }
        self.pop_to(base);
    }

    // --- what may cross a port ---------------------------------------------

    /// `Ok` if `v` is data. Functions are refused **by name**, because "cannot
    /// send that" sends somebody hunting through a nested structure.
    pub fn check_sendable(&mut self, v: Value) -> Result<(), alloc::string::String> {
        self.check_sendable_at(v, 0)
    }

    fn describe_fn(&mut self, v: Value) -> alloc::string::String {
        let t = ty(&self.gc.sp, v.as_heap());
        let name = match t {
            TY_CLOSURE => {
                let idx = self.slot(v, 0).as_fixnum() as usize;
                let namec = self.image.fns.get(idx).map(|d| d.name as usize).unwrap_or(usize::MAX);
                self.roots.consts.get(namec).copied().unwrap_or(NIL)
            }
            TY_NATIVEFN => self.slot(v, 1),
            _ => NIL,
        };
        let mut b = crate::rt::sbuf();
        match self.as_str(name, &mut b) {
            Some(s) if !s.is_empty() => s.into(),
            _ => "an anonymous fn".into(),
        }
    }

    fn check_sendable_at(&mut self, v: Value, depth: u32) -> Result<(), alloc::string::String> {
        if depth > 64 {
            return Err("value nested too deeply to send".into());
        }
        if !v.is_heap() {
            return Ok(());
        }
        let t = ty(&self.gc.sp, v.as_heap());
        match t {
            TY_CLOSURE | TY_NATIVEFN | TY_MULTIFN => {
                let n = self.describe_fn(v);
                Err(alloc::format!(
                    "a port carries data only; {n} is a function, and a closure's meaning is \
                     its environment -- which does not travel"
                ))
            }
            TY_ATOM => Err("a port carries data only; this is an atom".into()),
            TY_VAR => Err("a port carries data only; this is a var".into()),
            TY_THREAD => Err("a port carries data only; this is a thread".into()),
            // Ports are not transferable and cannot be sent (doc/decisions/0006).
            // No ownership transfer, no capability leaking through a message,
            // and a wire format that never has to represent a port. The cost --
            // a capability cannot be delegated at run time -- is in the README.
            TY_PORT => Err("a port cannot be sent through a port: only data crosses. \
                            A capability cannot be delegated at run time."
                .into()),
            TY_STR | TY_SYM | TY_KW | TY_BIGINT | TY_REGEX => Ok(()),
            _ => {
                let base = self.mark();
                let vi = self.push(v);
                let mut out = Ok(());
                if self.is_map(self.r(vi)) {
                    let mut items: Vec<Value> = Vec::new();
                    let mut st = &mut items;
                    self.map_for_each(v, &mut st, &mut |_rt, k, val, st| {
                        st.push(k);
                        st.push(val);
                    });
                    for it in items {
                        let ii = self.push(it);
                        out = self.check_sendable_at(self.r(ii), depth + 1);
                        self.pop_to(ii);
                        if out.is_err() {
                            break;
                        }
                    }
                } else if self.is_set(self.r(vi)) {
                    let mut items: Vec<Value> = Vec::new();
                    let mut st = &mut items;
                    self.set_for_each(v, &mut st, &mut |_rt, k, st| st.push(k));
                    for it in items {
                        let ii = self.push(it);
                        out = self.check_sendable_at(self.r(ii), depth + 1);
                        self.pop_to(ii);
                        if out.is_err() {
                            break;
                        }
                    }
                } else if self.is_sequential(self.r(vi)) {
                    let s = self.seq(self.r(vi));
                    let si = self.push(s);
                    while !self.r(si).is_nil() {
                        let f = self.first(self.r(si));
                        let fi = self.push(f);
                        out = self.check_sendable_at(self.r(fi), depth + 1);
                        self.pop_to(fi);
                        if out.is_err() {
                            break;
                        }
                        let nx = self.next(self.r(si));
                        self.set_r(si, nx);
                    }
                }
                self.pop_to(base);
                out
            }
        }
    }
}

// --- the scheduler ---------------------------------------------------------

/// Called by `run_program` once the entry function has finished, thrown, or
/// parked. Runs until every thread is finished, or until nothing can progress
/// without the host.
fn scheduler(rt: &mut Rt, first: Value) -> Value {
    settle(rt, first);
    drive(rt)
}

/// Record the outcome of the thread that was running, and take it off.
fn settle(rt: &mut Rt, result: Value) {
    unsafe { crate::gc::PHASE = 1; }
    let th = rt.current_thread();
    if th.is_nil() {
        return;
    }
    let base = rt.mark();
    let ti = rt.push(th);
    // Dynamic bindings travel with the thread.
    let binds = rt.roots.singletons[crate::rt::SING_BINDINGS];
    rt.set(rt.r(ti), TH_BINDINGS, binds);
    if !rt.park_on.is_nil() {
        let on = rt.park_on;
        rt.park_on = NIL;
        if on.bits() == PARK_YIELD.bits() {
            rt.set(rt.r(ti), TH_STATUS, Value::fixnum(ST_RUNNABLE));
            rt.set(rt.r(ti), TH_PARK_ON, NIL);
        } else {
            let oi = rt.push(on);
            rt.set(rt.r(ti), TH_STATUS, Value::fixnum(ST_PARKED));
            let o = rt.r(oi);
            rt.set(rt.r(ti), TH_PARK_ON, o);
        }
        let t = rt.r(ti);
        rt.save_current_state(t);
    } else if rt.failed() {
        let e = rt.clear_error();
        let ei = rt.push(e);
        rt.set(rt.r(ti), TH_STATUS, Value::fixnum(ST_FAILED));
        let ev = rt.r(ei);
        rt.set(rt.r(ti), TH_RESULT, ev);
        let t = rt.r(ti);
        rt.wake_on(t);
        rt.frames.clear();
        rt.handlers.clear();
        rt.roots.stack_top = 0;
    } else {
        let ri = rt.push(result);
        rt.set(rt.r(ti), TH_STATUS, Value::fixnum(ST_DONE));
        let rv = rt.r(ri);
        rt.set(rt.r(ti), TH_RESULT, rv);
        rt.set(rt.r(ti), TH_STACK, NIL);
        let t = rt.r(ti);
        rt.wake_on(t);
        rt.frames.clear();
        rt.handlers.clear();
        rt.roots.stack_top = 0;
    }
    rt.pop_to(base);
}

/// Round-robin from just after the current thread. Deterministic by
/// construction: no randomness, no clock, no host-order dependence beyond the
/// order in which the host actually answers.
fn pick(rt: &mut Rt) -> Option<u32> {
    let s = rt.sched();
    let ts = rt.slot(s, SC_THREADS);
    let n = rt.vec_count(ts);
    if n == 0 {
        return None;
    }
    let cur = fx(rt.slot(s, SC_CURRENT)) as u32;
    for k in 1..=n {
        let i = (cur + k) % n;
        let th = rt.vec_nth(ts, i).unwrap_or(NIL);
        if th.is_nil() {
            continue;
        }
        let st = fx(rt.slot(th, TH_STATUS));
        if st == ST_NEW || st == ST_RUNNABLE {
            return Some(i);
        }
    }
    None
}

/// Anything the host has not taken yet.
fn pending_events(rt: &mut Rt) -> bool {
    let s = rt.sched();
    let evs = rt.slot(s, SC_EVENTS);
    let head = fx(rt.slot(s, SC_EHEAD)) as u32;
    rt.vec_count(evs) > head
}

fn needs_host(rt: &mut Rt) -> bool {
    if pending_events(rt) {
        return true;
    }
    let s = rt.sched();
    let ts = rt.slot(s, SC_THREADS);
    let n = rt.vec_count(ts);
    for i in 0..n {
        let th = rt.vec_nth(ts, i).unwrap_or(NIL);
        if th.is_nil() {
            continue;
        }
        if fx(rt.slot(th, TH_STATUS)) == ST_PARKED {
            let on = rt.slot(th, TH_PARK_ON);
            if rt.is_port(on) && fx(rt.slot(on, PT_KIND)) == K_FLINT {
                return true;
            }
        }
    }
    false
}

fn run_one(rt: &mut Rt, i: u32) {
    unsafe { crate::gc::PHASE = 4; }
    let s = rt.sched();
    rt.set(s, SC_CURRENT, Value::fixnum(i as i64));
    let ts = rt.slot(s, SC_THREADS);
    let th = rt.vec_nth(ts, i).unwrap_or(NIL);
    if th.is_nil() {
        return;
    }
    let base = rt.mark();
    let ti = rt.push(th);
    let st = fx(rt.slot(rt.r(ti), TH_STATUS));
    let binds = rt.slot(rt.r(ti), TH_BINDINGS);
    rt.roots.singletons[crate::rt::SING_BINDINGS] = binds;
    let at = rt.steps + slice();
    rt.set_slice_end(at);
    let v = if st == ST_NEW {
        rt.frames.clear();
        rt.handlers.clear();
        rt.roots.stack_top = 0;
        let f = rt.slot(rt.r(ti), TH_ENTRY);
        rt.set(rt.r(ti), TH_STATUS, Value::fixnum(ST_RUNNABLE));
        rt.set(rt.r(ti), TH_ENTRY, NIL);
        rt.run_thread_entry(f)
    } else {
        let t = rt.r(ti);
        rt.restore_state(t);
        rt.set(rt.r(ti), TH_STACK, NIL);
        let fail = rt.slot(rt.r(ti), TH_FAIL);
        if fail.is_nil() {
            rt.run(0)
        } else {
            // Raised here, in the thread it concerns, rather than in whichever
            // thread noticed the port had gone. `try` in this thread can catch
            // it like any other error.
            rt.set(rt.r(ti), TH_FAIL, NIL);
            rt.thrown = fail;
            if rt.unwind_from_resume() {
                rt.run(0)
            } else {
                NIL
            }
        }
    };
    rt.pop_to(base);
    settle(rt, v);
}

/// Is the program over?
///
/// **When the entry function has returned and nothing else can run, it is.** Its
/// value *is* the answer, so there is nothing left to compute; any thread still
/// parked at that point is waiting for something that is never coming --
/// typically a service thread parked on a port whose work is done. Threads that
/// are still runnable get to finish first, because `pick` is tried before this.
///
/// Defining completion any other way means a driver that leaves a reader parked
/// keeps the whole program alive for ever, which is the bug this replaced.
fn main_finished(rt: &mut Rt) -> bool {
    let s = rt.sched();
    let ts = rt.slot(s, SC_THREADS);
    let th = rt.vec_nth(ts, 0).unwrap_or(NIL);
    if th.is_nil() {
        return true;
    }
    let st = fx(rt.slot(th, TH_STATUS));
    st == ST_DONE || st == ST_FAILED
}

/// The main loop, also re-entered from the host's `resume`.
pub fn drive(rt: &mut Rt) -> Value {
    loop {
        // What the collector left behind is the lifetime rule: a flint end that
        // nothing refers to any more has been closed, whether or not anybody
        // said so (doc/decisions/0006).
        rt.reap_ports();
        match pick(rt) {
            Some(i) => run_one(rt, i),
            None => {
                // The entry function's value IS the answer, so once it has
                // returned and nothing else can run, the program is over --
                // whatever a service thread may still be parked on. Asking
                // "does anything need the host?" first would keep a driver's
                // reader thread alive for ever.
                if main_finished(rt) {
                    // Exit closes every flint end and leaves the events for one
                    // last drain, so a host never has to guess whether more is
                    // coming.
                    rt.close_all_flint_ends();
                    if pending_events(rt) {
                        rt.status = 2;
                        return NIL;
                    }
                    rt.status = 0;
                    return main_result(rt);
                }
                if needs_host(rt) {
                    rt.status = 2;
                    return NIL;
                }
                rt.status = 0;
                {
                    // Nothing runnable, nothing the host can help with: the
                    // remaining threads are waiting on each other.
                    let s = rt.sched();
                    let ts = rt.slot(s, SC_THREADS);
                    let n = rt.vec_count(ts);
                    let mut stuck = 0;
                    let mut detail = alloc::string::String::new();
                    for i in 0..n {
                        let th = rt.vec_nth(ts, i).unwrap_or(NIL);
                        if th.is_nil() {
                            continue;
                        }
                        let st = fx(rt.slot(th, TH_STATUS));
                        if st == ST_PARKED {
                            stuck += 1;
                            let on = rt.slot(th, TH_PARK_ON);
                            let what = if rt.is_port(on) {
                                let mut b = crate::rt::sbuf();
                                let l = rt.slot(on, PT_LABEL);
                                let lab: alloc::string::String =
                                    rt.as_str(l, &mut b).unwrap_or("").into();
                                alloc::format!(
                                    "port {}{}",
                                    fx(rt.slot(on, PT_ID)),
                                    if lab.is_empty() {
                                        alloc::string::String::new()
                                    } else {
                                        alloc::format!(" {lab:?}")
                                    }
                                )
                            } else if rt.is_thread(on) {
                                alloc::format!("thread {}", fx(rt.slot(on, TH_ID)))
                            } else {
                                "something".into()
                            };
                            detail.push_str(&alloc::format!(
                                "\n  thread {} waiting on {}",
                                fx(rt.slot(th, TH_ID)),
                                what
                            ));
                        }
                    }
                    let msg = alloc::format!(
                        "deadlock: {stuck} green thread(s) are parked and nothing can wake them{detail}"
                    );
                    rt.throw_str("IllegalStateException", &msg);
                }
                return NIL;
            }
        }
    }
}

fn main_result(rt: &mut Rt) -> Value {
    let s = rt.sched();
    let ts = rt.slot(s, SC_THREADS);
    let th = rt.vec_nth(ts, 0).unwrap_or(NIL);
    if th.is_nil() {
        return NIL;
    }
    let r = rt.slot(th, TH_RESULT);
    if fx(rt.slot(th, TH_STATUS)) == ST_FAILED {
        rt.thrown = r;
        return NIL;
    }
    r
}

impl Rt {
    /// Start a thread's entry closure with the frame stack empty.
    fn run_thread_entry(&mut self, f: Value) -> Value {
        if !self.is_callable(f) {
            self.throw_str("ClassCastException", "spawn wants a function of no arguments");
            return NIL;
        }
        let callee_at = self.roots.stack_top;
        self.vpush(f);
        if !self.enter(f, callee_at, 0) {
            self.roots.stack_top = callee_at;
            return NIL;
        }
        self.run(0)
    }
}
// --- port operations -------------------------------------------------------

impl Rt {
    fn need_port(&mut self, p: Value, what: &str) -> bool {
        if !self.is_port(p) {
            let msg = alloc::format!("{what} wants a port");
            self.throw_str("ClassCastException", &msg);
            return false;
        }
        true
    }

    /// Append an outbound event. `payload` is a string whose bytes the host will
    /// read; the drain copies them into one contiguous buffer.
    fn push_event(&mut self, kind: i64, a: i64, b: i64, payload: Value) {
        let base = self.mark();
        let pi = self.push(payload);
        let v = self.empty_vec();
        let vi = self.push(v);
        for x in [kind, a, b] {
            let nv = self.vec_conj(self.r(vi), Value::fixnum(x));
            self.set_r(vi, nv);
        }
        let p = self.r(pi);
        let nv = self.vec_conj(self.r(vi), p);
        self.set_r(vi, nv);
        let s = self.sched();
        let si = self.push(s);
        let evs = self.slot(self.r(si), SC_EVENTS);
        let ei = self.push(evs);
        let e = self.r(vi);
        let nevs = self.vec_conj(self.r(ei), e);
        self.set(self.r(si), SC_EVENTS, nevs);
        self.pop_to(base);
    }

    pub fn port_send(&mut self, p: Value, v: Value) -> Value {
        if !self.need_port(p, "send") {
            return NIL;
        }
        // Never park against a peer that is gone: a script blocking forever on a
        // host that has hung up is the same failure as a host leaking a handle,
        // seen from the other side.
        let st = fx(self.slot(p, PT_STATE));
        if st != P_OPEN {
            let why = match st {
                P_CLOSED => "this end is closed",
                P_HALF => "the other end has closed, so nothing can receive this",
                P_ORPHANED => "the other end is gone, so nothing can ever receive this",
                P_REFUSED => "the host refused this capability",
                _ => "this port is not open yet",
            };
            let msg = alloc::format!("send: {why}");
            return self.throw_str("IllegalStateException", &msg);
        }
        if let Err(e) = self.check_sendable(v) {
            return self.throw_str("IllegalArgumentException", &e);
        }
        let base = self.mark();
        let pi = self.push(p);
        let vi = self.push(v);
        let kind = fx(self.slot(self.r(pi), PT_KIND));
        if kind == K_FLINT {
            // Bound the host's queue in BYTES: back-pressure exists to bound
            // memory, and one 4 MB message is not one message's worth of it.
            let binary = fx(self.slot(self.r(pi), PT_BINARY)) == 1;
            let encoded = if binary {
                self.is_vector(self.r(vi))
            } else {
                self.is_string(self.r(vi))
            };
            if !encoded {
                self.pop_to(base);
                return self.throw_str(
                    "IllegalArgumentException",
                    "a host port carries bytes; flint.port/send encodes for you, so this is a \
                     raw send of something that is not already encoded (a string, or a vector \
                     of 0..255 on a binary port)",
                );
            }
            let host = self.peer_of(self.r(pi));
            if host.is_nil() {
                self.pop_to(base);
                return self.throw_str("IllegalStateException", "the host has closed this port");
            }
            let hi = self.push(host);
            let len = if binary {
                self.vec_count(self.r(vi)) as i64
            } else {
                self.str_len(self.r(vi)) as i64
            };
            let cap = fx(self.slot(self.r(hi), PT_CAP));
            let queued = fx(self.slot(self.r(hi), PT_BYTES));
            if queued > 0 && queued + len > cap {
                let target = self.r(hi);
                self.pop_to(base);
                return self.park_on_port(WK_SEND, target);
            }
            self.set(self.r(hi), PT_BYTES, Value::fixnum(queued + len));
            let id = fx(self.slot(self.r(hi), PT_ID));
            let payload = self.r(vi);
            self.push_event(EV_MESSAGE, id, len, payload);
            self.pop_to(base);
            return NIL;
        }
        // A channel end: bound in messages, since nothing is serialised and the
        // values are shared rather than copied.
        let peer = self.peer_of(self.r(pi));
        if peer.is_nil() {
            self.pop_to(base);
            return self.throw_str(
                "IllegalStateException",
                "the other end of this port is gone, so nothing can ever receive this",
            );
        }
        let pei = self.push(peer);
        if fx(self.slot(self.r(pei), PT_STATE)) == P_CLOSED {
            self.pop_to(base);
            return self.throw_str("IllegalStateException", "the other end of this port is closed");
        }
        let cap = fx(self.slot(self.r(pei), PT_CAP));
        if self.inbox_count(self.r(pei)) as i64 >= cap {
            let target = self.r(pei);
            self.pop_to(base);
            return self.park_on_port(WK_SEND, target);
        }
        let val = self.r(vi);
        let target = self.r(pei);
        self.port_enqueue(target, val);
        let target = self.r(pei);
        self.wake_on(target);
        self.pop_to(base);
        NIL
    }

    pub fn port_receive(&mut self, p: Value) -> Value {
        if !self.need_port(p, "receive") {
            return NIL;
        }
        let base = self.mark();
        let pi = self.push(p);
        if self.inbox_count(self.r(pi)) > 0 {
            let target = self.r(pi);
            let v = self.port_dequeue(target);
            let vi = self.push(v);
            if fx(self.slot(self.r(pi), PT_KIND)) == K_FLINT {
                // Room again for the host to deliver the next wave.
                let n = if self.is_vector(self.r(vi)) {
                    self.vec_count(self.r(vi)) as i64
                } else {
                    self.str_len(self.r(vi)) as i64
                };
                let queued = fx(self.slot(self.r(pi), PT_BYTES));
                let left = if queued > n { queued - n } else { 0 };
                self.set(self.r(pi), PT_BYTES, Value::fixnum(left));
            }
            // Space freed: whoever was blocked sending here can try again.
            let target = self.r(pi);
            self.wake_on(target);
            let out = self.r(vi);
            self.pop_to(base);
            return out;
        }
        let st = fx(self.slot(self.r(pi), PT_STATE));
        // Drained and finished cleanly: end of stream, a normal answer.
        if st == P_CLOSED || st == P_HALF {
            self.pop_to(base);
            return NIL;
        }
        // Drained and the peer vanished: nobody said goodbye, so say so rather
        // than pretending the stream ended tidily -- and never park, because a
        // script blocked forever on a host that hung up is the same failure as
        // a host leaking a handle, seen from the other side.
        if st == P_ORPHANED {
            self.pop_to(base);
            return self.throw_str(
                "IllegalStateException",
                "receive: the other end of this port is gone, so this can never complete",
            );
        }
        let peer = self.peer_of(self.r(pi));
        if peer.is_nil() {
            self.set(self.r(pi), PT_STATE, Value::fixnum(P_ORPHANED));
            self.pop_to(base);
            return self.throw_str(
                "IllegalStateException",
                "receive: the other end of this port is gone, so this can never complete",
            );
        }
        let pst = fx(self.slot(peer, PT_STATE));
        if pst == P_CLOSED || pst == P_HALF || pst == P_ORPHANED {
            self.set(self.r(pi), PT_STATE, Value::fixnum(P_HALF));
            self.pop_to(base);
            return NIL;
        }
        let target = self.r(pi);
        self.pop_to(base);
        self.park_on_port(WK_RECEIVE, target)
    }

    /// Ask the host for a capability. Blocking from the program's point of
    /// view; from wasm's point of view the thread stops being runnable.
    ///
    /// The **runtime** creates the pair -- the host never holds two ends and
    /// never hands one back. It is told the token to answer with and the id of
    /// the end it will hold.
    pub fn port_open(&mut self, name: Value, format: Value) -> Value {
        self.ensure_sched();
        let base = self.mark();
        let ni = self.push(name);
        let fi = self.push(format);
        let th = self.current_thread();
        let ti = self.push(th);
        let pending = self.slot(self.r(ti), TH_PENDING);
        if !pending.is_nil() {
            // Second time round: the host has answered.
            self.set(self.r(ti), TH_PENDING, NIL);
            let st = fx(self.slot(pending, PT_STATE));
            if st == P_OPEN {
                self.pop_to(base);
                return pending;
            }
            let mut b = crate::rt::sbuf();
            let n: alloc::string::String = self.as_str(self.r(ni), &mut b).unwrap_or("?").into();
            self.pop_to(base);
            let msg = alloc::format!("the host refused the capability {n:?}");
            return self.throw_str("SecurityException", &msg);
        }
        let (nm, fmt) = (self.r(ni), self.r(fi));
        let flint_end = self.new_port(DEFAULT_HOST_CAP, nm, K_FLINT, P_PENDING, fmt);
        let ei = self.push(flint_end);
        let nm = self.r(ni);
        let fmt = self.r(fi);
        let host_end = self.new_port(DEFAULT_HOST_CAP, nm, K_HOST, P_PENDING, fmt);
        let hi = self.push(host_end);
        let (ev, hv) = (self.r(ei), self.r(hi));
        self.link_peers(ev, hv);
        let ev = self.r(ei);
        self.set(self.r(ti), TH_PENDING, ev);
        let target = self.r(ei);
        let token = self.new_waiter(WK_OPEN, target);
        let t = self.r(ti);
        self.set(t, TH_TOKEN, Value::fixnum(token));
        let host_id = fx(self.slot(self.r(hi), PT_ID));
        let nm = self.r(ni);
        self.push_event(EV_OPEN, token, host_id, nm);
        let target = self.r(ei);
        self.pop_to(base);
        self.park(target)
    }

    /// Wait for `t` to finish. Parks rather than spinning: a spinning joiner
    /// would always be runnable, so the scheduler would never get the chance to
    /// hand control back to the host and a thread waiting on a host port would
    /// never be answered.
    pub fn thread_join(&mut self, t: Value) -> Value {
        if !self.is_thread(t) {
            return self.throw_str("ClassCastException", "join wants a thread");
        }
        let st = fx(self.slot(t, TH_STATUS));
        if st == ST_DONE {
            return self.slot(t, TH_RESULT);
        }
        if st == ST_FAILED {
            let e = self.slot(t, TH_RESULT);
            self.thrown = e;
            return NIL;
        }
        let cur = self.current_thread();
        if !cur.is_nil() && cur.bits() == t.bits() {
            return self.throw_str("IllegalStateException", "a thread cannot join itself");
        }
        self.park_on_port(WK_JOIN, t)
    }

    pub fn port_close(&mut self, p: Value) -> Value {
        if !self.need_port(p, "close") {
            return NIL;
        }
        let base = self.mark();
        let pi = self.push(p);
        if fx(self.slot(self.r(pi), PT_STATE)) != P_CLOSED {
            self.set(self.r(pi), PT_STATE, Value::fixnum(P_CLOSED));
            let pv = self.r(pi);
            self.close_side_effects(pv);
        }
        self.pop_to(base);
        NIL
    }

    /// Everything that follows from an end closing, however it closed: tell the
    /// host if it is the peer, and wake anybody parked on either side.
    fn close_side_effects(&mut self, p: Value) {
        let base = self.mark();
        let pi = self.push(p);
        let kind = fx(self.slot(self.r(pi), PT_KIND));
        if kind == K_FLINT {
            let host = self.peer_of(self.r(pi));
            if !host.is_nil() {
                let hi = self.push(host);
                let id = fx(self.slot(self.r(hi), PT_ID));
                self.set(self.r(hi), PT_STATE, Value::fixnum(P_CLOSED));
                self.push_event(EV_CLOSED, id, 0, NIL);
                self.pop_to(hi);
            }
        }
        let target = self.r(pi);
        self.wake_on(target);
        // The peer becomes HALF-closed rather than closed: it may still drain
        // what is already in its buffer, and only then reads end-of-stream. The
        // channel is not freed until both ends are done.
        let peer = self.peer_of(self.r(pi));
        if !peer.is_nil() && fx(self.slot(peer, PT_STATE)) == P_OPEN {
            self.set(peer, PT_STATE, Value::fixnum(P_HALF));
            self.wake_on(peer);
        }
        self.pop_to(base);
    }

    // --- the host's side ---------------------------------------------------

    /// Resume whatever is waiting on `token`.
    ///
    /// **Records and returns; it never re-enters the scheduler**, because a host
    /// may well call this from inside a host function that wasm itself invoked,
    /// and re-entering there would run the scheduler on top of itself. The
    /// answer is taken now and acted on at the next pump.
    ///
    /// Returns false when the token is stale or already used: the generation in
    /// it no longer matches the slot, which is exactly the late-or-duplicated
    /// reply that would otherwise resume a stranger's thread.
    pub fn host_continue(&mut self, token: i64, ok: bool) -> bool {
        let w = self.waiter_at(token);
        if w.is_nil() {
            return false;
        }
        let base = self.mark();
        let wi = self.push(w);
        let kind = fx(self.slot(self.r(wi), W_KIND));
        if kind == WK_OPEN {
            let p = self.slot(self.r(wi), W_PORT);
            let pi = self.push(p);
            let state = if ok { P_OPEN } else { P_REFUSED };
            self.set(self.r(pi), PT_STATE, Value::fixnum(state));
            let host = self.peer_of(self.r(pi));
            if !host.is_nil() {
                self.set(host, PT_STATE, Value::fixnum(state));
                if !ok {
                    // Refused: the host never gets a handle, so nothing needs
                    // to keep this end alive.
                    self.unroot_port(host);
                }
            }
            self.pop_to(pi);
        }
        let wv = self.r(wi);
        self.wake_waiter(wv);
        self.pop_to(base);
        true
    }

    /// Put bytes into the flint end of a host port. Wakes a parked receiver;
    /// it does not run anything.
    /// Put bytes into the flint end of a host port.
    ///
    /// Returns **false when the guest's buffer is full**, and the host must hold
    /// the message and offer it again after the next pump. Without that, a
    /// server answering "in waves" would simply push every wave at once and the
    /// whole answer would be resident in the guest heap -- which is precisely
    /// what waves exist to prevent. Inbound needs the same back-pressure as
    /// outbound; it is the same buffer bound, seen from the other side.
    pub fn host_deliver(&mut self, host_port_id: i64, bytes: &[u8]) -> bool {
        let host = self.port_by_id(host_port_id);
        if host.is_nil() {
            return false;
        }
        let base = self.mark();
        let hi = self.push(host);
        let flint = self.peer_of(self.r(hi));
        if flint.is_nil() {
            self.pop_to(base);
            return false;
        }
        let pi = self.push(flint);
        let queued = fx(self.slot(self.r(pi), PT_BYTES));
        let cap = fx(self.slot(self.r(pi), PT_CAP));
        if queued > 0 && queued + bytes.len() as i64 > cap {
            self.pop_to(base);
            return false;
        }
        self.set(self.r(pi), PT_BYTES, Value::fixnum(queued + bytes.len() as i64));
        let v = if fx(self.slot(self.r(pi), PT_BINARY)) == 1 {
            let mark = self.mark();
            for b in bytes {
                self.push(Value::fixnum(*b as i64));
            }
            let vv = self.vec_from_roots(mark, bytes.len());
            self.pop_to(mark);
            vv
        } else {
            let s: alloc::string::String =
                core::str::from_utf8(bytes).unwrap_or("").into();
            self.string(&s)
        };
        let vi = self.push(v);
        let (target, val) = (self.r(pi), self.r(vi));
        self.port_enqueue(target, val);
        let target = self.r(pi);
        self.wake_on(target);
        self.pop_to(base);
        true
    }

    /// The host lets go of its end. The port may now be collected.
    pub fn host_close_port(&mut self, host_port_id: i64) {
        let host = self.port_by_id(host_port_id);
        if host.is_nil() {
            return;
        }
        let base = self.mark();
        let hi = self.push(host);
        self.set(self.r(hi), PT_STATE, Value::fixnum(P_CLOSED));
        let flint = self.peer_of(self.r(hi));
        if !flint.is_nil() {
            let fi = self.push(flint);
            // Half-closed: whatever the host already delivered is still there to
            // be read, and only then does it read as end of stream.
            if fx(self.slot(self.r(fi), PT_STATE)) == P_OPEN {
                self.set(self.r(fi), PT_STATE, Value::fixnum(P_HALF));
            }
            let target = self.r(fi);
            self.wake_on(target);
            self.pop_to(fi);
        }
        let hv = self.r(hi);
        self.unroot_port(hv);
        self.pop_to(base);
    }

    /// This end's state, resolved rather than remembered.
    ///
    /// A port whose peer has been collected is orphaned whether or not the
    /// scheduler has got round to noticing, and a query that answered `:open`
    /// until then would be a notification wearing a query's clothes.
    pub fn port_state_now(&mut self, p: Value) -> i64 {
        let st = fx(self.slot(p, PT_STATE));
        if st != P_OPEN {
            return st;
        }
        let peer_id = fx(self.slot(p, PT_PEER));
        if peer_id < 0 {
            return st;
        }
        if self.port_by_id(peer_id).is_nil() {
            self.set(p, PT_STATE, Value::fixnum(P_ORPHANED));
            return P_ORPHANED;
        }
        st
    }

    /// **The query, not the notification.** What state is the *runtime* end of
    /// this port in, asked by host id?
    ///
    /// If an event were the only way to learn that a port had closed, then an
    /// event dropped, missed or not yet drained would be an unrecoverable leak:
    /// a host handle to a port nobody will ever mention again. This makes the
    /// pushed `:closed` an optimisation over polling rather than the sole
    /// carrier of the truth. 255 means the runtime knows nothing about this id,
    /// which a host should also treat as "done".
    pub fn host_port_state(&mut self, host_port_id: i64) -> i64 {
        let host = self.port_by_id(host_port_id);
        if host.is_nil() {
            return 255;
        }
        let flint = self.peer_of(host);
        if flint.is_nil() {
            // The runtime end has been collected: as good as closed, and this is
            // exactly the case a missed event would have lost.
            return P_CLOSED;
        }
        self.port_state_now(flint)
    }

    /// Serialise every pending event into one contiguous buffer and hand it
    /// over. One call per pump: the boundary crossing is tens of nanoseconds,
    /// the marshalling is the cost, so everything pending goes at once.
    ///
    /// Layout: `count` records of five little-endian `u32`s --
    /// `kind, a, b, payload-offset, payload-len` -- followed by the payload
    /// bytes, all offsets relative to the start of the buffer.
    pub fn drain_events(&mut self, out: &mut alloc::vec::Vec<u8>) -> u32 {
        out.clear();
        let s = self.sched();
        if s.is_nil() {
            return 0;
        }
        let base = self.mark();
        let si = self.push(s);
        let evs = self.slot(self.r(si), SC_EVENTS);
        let ei = self.push(evs);
        let n = self.vec_count(self.r(ei));
        let header = (n as usize) * 20;
        out.resize(header, 0);
        for i in 0..n {
            let e = self.vec_nth(self.r(ei), i).unwrap_or(NIL);
            let kind = fx(self.vec_nth(e, 0).unwrap_or(NIL));
            let a = fx(self.vec_nth(e, 1).unwrap_or(NIL));
            let b = fx(self.vec_nth(e, 2).unwrap_or(NIL));
            let payload = self.vec_nth(e, 3).unwrap_or(NIL);
            let off = out.len() as u32;
            let plen = if self.is_vector(payload) {
                let n = self.vec_count(payload);
                for k in 0..n {
                    let b = self.vec_nth(payload, k).unwrap_or(NIL);
                    out.push(b.as_fixnum() as u8);
                }
                n
            } else {
                let mut buf = crate::rt::sbuf();
                match self.as_str(payload, &mut buf) {
                    Some(t) => {
                        let owned: alloc::string::String = t.into();
                        out.extend_from_slice(owned.as_bytes());
                        owned.len() as u32
                    }
                    None => 0,
                }
            };
            let rec = (i as usize) * 20;
            out[rec..rec + 4].copy_from_slice(&(kind as u32).to_le_bytes());
            out[rec + 4..rec + 8].copy_from_slice(&(a as u32).to_le_bytes());
            out[rec + 8..rec + 12].copy_from_slice(&(b as u32).to_le_bytes());
            out[rec + 12..rec + 16].copy_from_slice(&off.to_le_bytes());
            out[rec + 16..rec + 20].copy_from_slice(&plen.to_le_bytes());
            // A message leaving the queue frees its bytes against the bound.
            if kind == EV_MESSAGE {
                let port = self.port_by_id(a);
                if !port.is_nil() {
                    let queued = fx(self.slot(port, PT_BYTES));
                    let left = if queued > b { queued - b } else { 0 };
                    self.set(port, PT_BYTES, Value::fixnum(left));
                    self.wake_on(port);
                }
            }
        }
        let empty = self.empty_vec();
        self.set(self.r(si), SC_EVENTS, empty);
        self.set(self.r(si), SC_EHEAD, Value::fixnum(0));
        self.pop_to(base);
        n
    }

    // --- reachability, which is also the lifetime rule ----------------------

    /// Reconcile the port table with what the collector left alive.
    ///
    /// A flint end that nothing refers to any more has gone from the weak table.
    /// That is semantically identical to the script having called `close`, so we
    /// close it on the script's behalf -- and a thread parked on a port whose
    /// *peer* has gone can never proceed, so it is woken with an error rather
    /// than left hanging. Both facts are ones the collector has already worked
    /// out; this only reads them.
    pub fn reap_ports(&mut self) {
        unsafe { crate::gc::PHASE = 3; }
        let s = self.sched();
        if s.is_nil() {
            return;
        }
        let base = self.mark();
        let si = self.push(s);
        let ids = self.slot(self.r(si), SC_PORTS);
        let ii = self.push(ids);
        let n = self.vec_count(self.r(ii));
        let mut live = self.empty_vec();
        let li = self.push(live);
        for k in 0..n {
            let id = fx(self.vec_nth(self.r(ii), k).unwrap_or(NIL));
            let p = self.port_by_id(id);
            if !p.is_nil() {
                let nl = self.vec_conj(self.r(li), Value::fixnum(id));
                self.set_r(li, nl);
                continue;
            }
            // This end has been collected. Tell whoever is affected.
            unsafe { if crate::gc::REAPED_N < 32 { crate::gc::REAPED[crate::gc::REAPED_N] = id; crate::gc::REAPED_N += 1; } }
            let peer_id = self.peer_id_of_dead(id);
            let peer = self.port_by_id(peer_id);
            if peer.is_nil() {
                continue;
            }
            let pi = self.push(peer);
            let pkind = fx(self.slot(self.r(pi), PT_KIND));
            let pst = fx(self.slot(self.r(pi), PT_STATE));
            if pst != P_CLOSED && pst != P_ORPHANED {
                // Its peer vanished without closing, which is not the same as a
                // tidy close and should not read like one.
                unsafe { if crate::gc::ORPHANED_N < 32 { crate::gc::ORPHANED[crate::gc::ORPHANED_N] = fx(self.slot(self.r(pi), PT_ID)); crate::gc::ORPHANED_N += 1; } }
                self.set(self.r(pi), PT_STATE, Value::fixnum(P_ORPHANED));
                if pkind == K_HOST {
                    let hid = fx(self.slot(self.r(pi), PT_ID));
                    self.push_event(EV_CLOSED, hid, 0, NIL);
                }
            }
            let target = self.r(pi);
            self.fail_waiters_on(
                target,
                "the other end of this port is unreachable, so this can never complete",
            );
            let target = self.r(pi);
            self.wake_on(target);
            self.pop_to(pi);
        }
        live = self.r(li);
        self.set(self.r(si), SC_PORTS, live);
        self.pop_to(base);
    }

    /// The peer of a port that has already been collected. Recorded separately
    /// because the object is gone by the time we notice.
    fn peer_id_of_dead(&mut self, id: i64) -> i64 {
        let s = self.sched();
        let pairs = self.slot(s, SC_PAIRS);
        let n = self.vec_count(pairs);
        for i in 0..n {
            let e = self.vec_nth(pairs, i).unwrap_or(NIL);
            if fx(self.vec_nth(e, 0).unwrap_or(NIL)) == id {
                return fx(self.vec_nth(e, 1).unwrap_or(NIL));
            }
        }
        -1
    }

    /// Program exit: close every flint end so a host is never left guessing
    /// whether more is coming, and leave the events for the final drain.
    pub fn close_all_flint_ends(&mut self) {
        let s = self.sched();
        if s.is_nil() {
            return;
        }
        let base = self.mark();
        let si = self.push(s);
        let ids = self.slot(self.r(si), SC_PORTS);
        let ii = self.push(ids);
        let n = self.vec_count(self.r(ii));
        for k in 0..n {
            let id = fx(self.vec_nth(self.r(ii), k).unwrap_or(NIL));
            let p = self.port_by_id(id);
            if p.is_nil() {
                continue;
            }
            let pi = self.push(p);
            if fx(self.slot(self.r(pi), PT_KIND)) == K_FLINT
                && fx(self.slot(self.r(pi), PT_STATE)) != P_CLOSED
            {
                self.set(self.r(pi), PT_STATE, Value::fixnum(P_CLOSED));
                let pv = self.r(pi);
                self.close_side_effects(pv);
            }
            self.pop_to(pi);
        }
        self.pop_to(base);
    }
}
