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
/// Bytes per saved frame. Five interpreter words plus the two the AOT entry
/// needs (`doc/decisions/0013`): which compiled arity, and which block to come
/// back at.
pub const FRAME_REC: usize = 28;
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
// A port is one END, and there are exactly two kinds of them
// (`doc/decisions/0027`).
//
// * A **channel** end joins two green threads inside one sandbox. Both ends are
//   in this heap, so a message is a pointer move: nothing is encoded, nothing
//   is copied, and an identity crossing one still means what it meant.
// * A **bridge** end is a HANDLE on a port the host owns. The id in it is the
//   HOST's, so it means the same thing in every sandbox, and the messages are
//   encoded bytes that live outside both heaps. No collector ever holds a
//   pointer it does not own.
//
// There is no third kind, and in particular there is no "host port". A sandbox
// used to manufacture a PAIR of ends on `open` -- one for itself, one standing
// in for the host -- which made the sandbox the author of its own authority and
// gave a port an id that meant nothing anywhere else. A bridge is handed in, or
// it is asked for on the system port and handed back; it is never made here.
//
// The two ends of a CHANNEL do not point at each other with `Value`s: each
// holds the other's *id*, resolved through the weak table `INTERN_PORT`. A
// strong peer link would keep a dropped end alive for ever and would defeat the
// liveness check that wakes a thread parked on a port whose peer has gone.
//
// That same weak table is what makes a bridge handle **interned**: one handle
// object per host id per sandbox. A port arriving in two different messages is
// one object, so `=` says yes and a map keyed by a port hits -- and the host is
// told exactly once that this sandbox holds it, and exactly once when it stops.

pub const PT_ID: u32 = 0;
pub const PT_STATE: u32 = 1;
/// Buffer bound. **Bytes** for a host end, messages for a channel end: the
/// point of back-pressure is to bound memory, and one 4 MB message is not one
/// message's worth of memory.
pub const PT_CAP: u32 = 2;
/// Inbox: a FIXED ring of `PT_RING` slots, written and read without a lock.
///
/// ONE array, and a slot's own word says whether it is vacant: `Value::EMPTY`
/// is in the special tag space, so no program can produce one and no message
/// can be mistaken for a free slot. That is what collapses the protocol --
/// claiming a slot and filling it are a single compare-and-swap.
///
/// It used to be a persistent vector with a read cursor, and `conj`-then-store
/// is a read-modify-write with an ALLOCATION in the middle -- which takes the
/// allocation lock and can stage a safepoint, so the window was not theoretical.
/// Two executors sending into one channel lost exactly half the traffic: 4 000
/// sent, 2 000 received.
///
/// A ring is fixed at creation, so nothing about a send allocates and there is
/// no shared structure to rebuild. See `port_enqueue` for the protocol.
pub const PT_INBOX: u32 = 3;
/// Read cursor. Monotonic, never wrapped: the slot is `read % PT_RING`, so a
/// cursor and its slot are different things and the empty and full cases do not
/// collide the way two wrapped indices do.
pub const PT_READ: u32 = 4;
/// Bytes currently queued against this end's bound.
pub const PT_BYTES: u32 = 5;
/// The other end's **id**, not the other end. See above.
pub const PT_PEER: u32 = 6;
pub const PT_LABEL: u32 = 7;
pub const PT_KIND: u32 = 8;
/// Write cursor. Monotonic, never wrapped, as `PT_READ`.
pub const PT_WRITE: u32 = 9;
/// Ring capacity, in messages. A channel's is what `channel` was asked for; a
/// bridge end's is `RING_MESSAGES`, because its own bound (`PT_CAP`) is in
/// BYTES and the two are different questions.
pub const PT_RING: u32 = 10;
pub const PT_LEN: u32 = 11;

/// How many messages a bridge end's ring holds.
///
/// Its `PT_CAP` bounds BYTES, which is the bound that matters for memory, and
/// this bounds the COUNT so the ring can be one fixed allocation. A host that
/// fills it is told to offer the message again, exactly as it is told when the
/// byte bound is reached -- back-pressure either way, not an error.
///
/// 64 rather than 1024, and the difference is not free: the ring is allocated
/// EAGERLY, two arrays per end and two ends per bridge, so each doubling costs
/// 32 bytes per message of capacity per bridge whether or not a message is ever
/// sent. At 1024 that is 32 KB per `open`, which `test/document.mjs` measured
/// as a real regression -- its module peak went from 56 800 to 86 632 bytes on
/// the same workload, and the memory-scaling bound it exists to defend failed.
///
/// The byte bound is what actually caps memory here, and 64 pending messages
/// is a deep queue for a boundary that back-pressures correctly when it fills.
pub const RING_MESSAGES: i64 = 64;

/// What a carrying port can convey, for `check_sendable_at`.
///
/// A CHANNEL encodes nothing: both ends are in this heap, so an identity
/// crossing one is a pointer move and anything may go.
pub const CARRY_LOCAL: u8 = 0;
/// A BRIDGE, whose encoding the RUNTIME owns. Identities cross as `K_PORT` /
/// `K_SENTINEL`, and only what means something on the far side may go -- see
/// the table on `TY_PORT`.
///
/// There used to be a third class, for a port whose codec ran in the SANDBOX.
/// There is no such port any more: encoding happens at the bridge boundary, in
/// the runtime, and a guest is never handed an encoder. The rule that class
/// existed to enforce -- that no identity may cross a codec the guest drives --
/// is now enforced by the guest not having one.
pub const CARRY_CROSSING: u8 = 1;

/// One end of a `channel` pair: no host involvement at all.
pub const K_CHANNEL: i64 = 0;
/// A handle on a port the HOST owns (`doc/decisions/0027`).
///
/// The id is the host's, not this sandbox's. Everything queued on it lives in
/// host memory as encoded bytes, so no collector ever holds a pointer it does
/// not own, and nothing here is a cross-heap reference.
pub const K_BRIDGE: i64 = 1;

/// Does this kind carry BYTES across a boundary, rather than values inside one
/// heap?
///
/// One predicate rather than a widening `==` at each of six sites, because the
/// last time this was a set of scattered comparisons one of them was missed.
#[inline]
pub fn crosses_a_heap(kind: i64) -> bool {
    kind == K_BRIDGE
}

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
/// Bytes, for a bridge end. Back-pressure exists to bound memory.
pub const DEFAULT_BRIDGE_CAP: i64 = 1 << 20;

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
/// The SYSTEM port, if this sandbox was given one (`doc/decisions/0027`).
///
/// A sandbox no longer manufactures its own end and offers it up: the host owns
/// the port, keeps one end, and passes the other in at construction. Absent is
/// a normal state and not an error -- a sandbox without one runs logic and
/// cannot ask the host for anything, which is the honest default for confined
/// code rather than a degraded mode.
pub const SC_SYSTEM: u32 = 9;
/// Host ids of every BRIDGE this sandbox holds a handle for -- ids, not
/// references, so the list pins nothing (`doc/decisions/0027`).
///
/// This is the walk that turns a collection into a release. The handle objects
/// live in the weak `INTERN_PORT` table; after a collection an id whose lookup
/// misses is one whose handle was not forwarded, and that is exactly this
/// sandbox letting go. Walking a list of ports held is proportional to a
/// handful, not to the heap.
pub const SC_BRIDGES: u32 = 10;
pub const SC_LEN: u32 = 11;

/// The one outbound queue. One export, one call per pump, one ordering rule.
pub const EV_OPEN: i64 = 1;
pub const EV_MESSAGE: i64 = 2;
pub const EV_CLOSED: i64 = 3;
/// This sandbox now holds a reference to the host's port `a`. Pushed exactly
/// once per port per sandbox, on the miss that mints the handle.
pub const EV_RETAIN: i64 = 4;
/// This sandbox no longer holds the host's port `a` -- the collector found the
/// handle unreachable, or the script closed it. Exactly one per `EV_RETAIN`.
pub const EV_RELEASE: i64 = 5;

/// Instructions a thread runs before the scheduler takes it off. Fixed, because
/// a deterministic answer is most of what this project is for: the same program
/// and the same order of host events must give the same result every time.
pub const SLICE: u64 = 4096;

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

    pub(crate) fn new_obj(&mut self, t: u8, n: u32) -> Value {
        let a = self.alloc(t, n);
        if a == 0 {
            return NIL;
        }
        Value::heap(a)
    }

    // --- scheduler state ---------------------------------------------------

    pub fn sched(&self) -> Value {
        self.roots.shared.singletons[SING_SCHED]
    }

    /// Create the scheduler on first use, enrolling whatever is running now as
    /// thread 0. Installing the hook here -- rather than at startup -- is what
    /// keeps `run_program` a straight line in a program that never spawns.
    pub fn ensure_sched(&mut self) -> Value {
        let s = self.sched();
        if !s.is_nil() {
            return s;
        }
        // The decoder's route to `install_bridge_port`, set HERE and nowhere
        // else. See `Rt::bridge_hook`: reaching it directly from `codec.rs` put
        // the whole scheduler in every module, including ones with no ports.
        self.bridge_hook = Some(|rt, id| rt.install_bridge_port(id, NIL));
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
        let brs = self.empty_vec();
        self.set(self.r(si), SC_BRIDGES, brs);
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
        self.roots.shared.singletons[SING_SCHED] = out;
        self.pop_to(base);
        self.sched_hook = Some(scheduler);
        let at = self.steps + SLICE;
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
        let base = self.mark();
        let ti = self.push(th);
        let n = self.roots.stack_top as u32;
        // UNBILLED: see `alloc_unbilled`. The size of a saved stack is a
        // property of the calling convention, not of the program, and billing
        // it made the same program cost more interpreted than compiled.
        let sv = {
            let a = self.alloc_unbilled(TY_NODE, n);
            if a == 0 { NIL } else { Value::heap(a) }
        };
        if sv.is_nil() {
            self.pop_to(base);
            return;
        }
        for i in 0..n {
            let v = self.roots.stack[i as usize];
            self.set_slot(sv.as_heap(), i, v);
        }
        self.set(self.r(ti), TH_STACK, sv);

        let fb = {
            let a = self.alloc_unbilled(TY_RAW, (self.frames.len() * FRAME_REC) as u32);
            if a == 0 { NIL } else { Value::heap(a) }
        };
        if !fb.is_nil() {
            let addr = fb.as_heap();
            let bytes = self.gc.sp.bytes_mut(addr + HDR, (self.frames.len() * FRAME_REC) as u32);
            for (k, f) in self.frames.iter().enumerate() {
                let o = k * FRAME_REC;
                bytes[o..o + 4].copy_from_slice(&(f.fp as u32).to_le_bytes());
                bytes[o + 4..o + 8].copy_from_slice(&f.ip.to_le_bytes());
                bytes[o + 8..o + 12].copy_from_slice(&f.end.to_le_bytes());
                bytes[o + 12..o + 16].copy_from_slice(&(f.ret_to as u32).to_le_bytes());
                bytes[o + 16..o + 20].copy_from_slice(&(f.handlers as u32).to_le_bytes());
                // The record is the same width in both builds, so a snapshot
                // and a thread save are interchangeable between them.
                #[cfg(feature = "aot")]
                {
                    bytes[o + 20..o + 24].copy_from_slice(&f.aot_idx.to_le_bytes());
                    bytes[o + 24..o + 28].copy_from_slice(&f.aot_block.to_le_bytes());
                }
                #[cfg(not(feature = "aot"))]
                {
                    bytes[o + 20..o + 28].fill(0xFF);
                }
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

        // Every frame here is interrupted mid-body, not finished. Record each
        // as a SEGMENT -- otherwise the per-frame histogram silently loses every
        // invocation that ever parks, which is exactly the population the
        // re-entry question is about.
        #[cfg(feature = "diagnostics")]
        unsafe {
            use crate::aotstat::*;
            for f in &self.frames {
                note_segment(f.instrs, f.resumed);
            }
            COUNTS[if self.park_on.bits() == PARK_YIELD.bits() {
                C_SAVES_YIELD
            } else {
                C_SAVES_PARK
            }] += 1;
        }
        self.frames.clear();
        self.handlers.clear();
        self.roots.stack_top = 0;
    }

    fn restore_state(&mut self, th: Value) {
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
            // The restored stack, checked with the predicate that broke this
            // open: a young-range pointer must be in the LIVE half. A parking
            // native re-reads its arguments from here on resume.
            #[cfg(feature = "diagnostics")]
            {
                self.gc.restores_checked += 1;
                self.gc.restore_values += n;
            }
            #[cfg(feature = "diagnostics")]
            for i in 0..(n as usize) {
                let v = self.roots.stack[i];
                if v.is_heap()
                    && self.gc.is_young(v.as_heap())
                    && !self.gc.in_live_half(v.as_heap())
                {
                    let k = self.gc.restore_stale as usize;
                    if k < 8 {
                        let t = crate::obj::ty(&self.gc.sp, v.as_heap()) as u32;
                        self.gc.restore_bad[k] =
                            [i as crate::mem::Addr, v.as_heap(), t as crate::mem::Addr, self.gc.stats.minor as crate::mem::Addr];
                    }
                    self.gc.restore_stale += 1;
                }
            }
        }
        #[cfg(feature = "diagnostics")]
        unsafe {
            crate::aotstat::COUNTS[crate::aotstat::C_RESTORES] += 1;
        }
        let fb = self.slot(th, TH_FRAMES);
        if !fb.is_nil() {
            let n = self.olen(fb) as usize / FRAME_REC;
            let bytes: Vec<u8> = raw_bytes(&self.gc.sp, fb.as_heap()).to_vec();
            for k in 0..n {
                let o = k * FRAME_REC;
                let g = |i: usize| {
                    u32::from_le_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]])
                };
                self.frames.push(Frame {
                    fp: g(o) as usize,
                    ip: g(o + 4),
                    end: g(o + 8),
                    ret_to: g(o + 12) as usize,
                    handlers: g(o + 16) as usize,
                    // A thread comes back at the instruction it parked ON, so
                    // that is where compiled code may take over again.
                    //
                    // Only for a frame that HAS compiled code. Setting this
                    // unconditionally asked the interpreter to enter compiled
                    // code for every restored frame, and the first uncompiled
                    // one indexed the empty side of the table and trapped.
                    #[cfg(feature = "aot")]
                    aot_idx: g(o + 20),
                    #[cfg(feature = "aot")]
                    aot_ip: if g(o + 20) == crate::vm::AOT_NONE {
                        crate::vm::AOT_NEVER
                    } else {
                        g(o + 4)
                    },
                    // LOOKED UP, not taken from the save. `aot_block` is the
                    // block to resume at `aot_ip`, and `aot_ip` is NOT saved --
                    // the restore substitutes `ip` for it. Those are the same
                    // offset for a frame that parked, and different for a frame
                    // that BAILED: a bail leaves `ip` on the instruction handed
                    // back and `aot_block` on the chunk AFTER it. Pairing that
                    // block with that ip re-entered compiled code one chunk
                    // early, skipping the handed-back instruction entirely --
                    // in `vec`, the `(vector 0)` of `(into [] coll)`, so the
                    // tail call found `coll` where `into` should have been and
                    // raised "value is not a function (object type 13)".
                    //
                    // The block a re-entry point maps to is knowable from the
                    // ip alone, and `None` means "not a re-entry point" -- so
                    // this is both the fix and the check the pair never had.
                    #[cfg(feature = "aot")]
                    aot_block: if g(o + 20) == crate::vm::AOT_NONE {
                        0
                    } else {
                        crate::vm::AOT_LOOKUP
                    },
                    // The instruction count does not survive the save, so this
                    // invocation is measured from the resume. That is the right
                    // cut: what re-entry buys is exactly the instructions run
                    // AFTER coming back.
                    #[cfg(feature = "diagnostics")]
                    instrs: 0,
                    #[cfg(feature = "diagnostics")]
                    resumed: true,
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
        let binds = self.roots.shared.singletons[crate::rt::SING_BINDINGS];
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
        match self.roots.shared.interns[INTERN_PORT].lookup(id as u32, |v| {
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

    /// A port object. `id` is `-1` to mint one from this sandbox's counter,
    /// which is what a channel end does; a bridge handle passes the HOST's id
    /// instead, because that is the id that means the same thing on both sides.
    fn new_port(&mut self, cap: i64, label: Value, kind: i64, state: i64, id: i64) -> Value {
        let base = self.mark();
        let li = self.push(label);
        let p = self.new_obj(TY_PORT, PT_LEN);
        if p.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let pi = self.push(p);
        let s = self.sched();
        let si = self.push(s);
        let id = if id >= 0 {
            id
        } else {
            let n = fx(self.slot(self.r(si), SC_NEXTID));
            self.set(self.r(si), SC_NEXTID, Value::fixnum(n + 1));
            n
        };
        self.set(self.r(pi), PT_ID, Value::fixnum(id));
        self.set(self.r(pi), PT_STATE, Value::fixnum(state));
        self.set(self.r(pi), PT_CAP, Value::fixnum(cap));
        // The ring, allocated ONCE and never again: a send must not allocate,
        // because allocation is where the old inbox lost messages.
        //
        // A channel's ring is what `channel` was asked for; a bridge's is
        // `RING_MESSAGES`, because its own bound (`PT_CAP`) is in BYTES and the
        // two are different questions.
        let ring = if kind == K_CHANNEL { cap.max(1) } else { RING_MESSAGES };
        self.set(self.r(pi), PT_RING, Value::fixnum(ring));
        let slots = self.new_obj(crate::obj::TY_NODE, ring as u32);
        let sli = self.push(slots);
        for i in 0..ring as u32 {
            self.set(self.r(sli), i, crate::value::EMPTY);
        }
        let sv = self.r(sli);
        self.set(self.r(pi), PT_INBOX, sv);
        self.set(self.r(pi), PT_READ, Value::fixnum(0));
        self.set(self.r(pi), PT_WRITE, Value::fixnum(0));
        self.set(self.r(pi), PT_BYTES, Value::fixnum(0));
        self.set(self.r(pi), PT_PEER, Value::fixnum(-1));
        let l = self.r(li);
        self.set(self.r(pi), PT_LABEL, l);
        self.set(self.r(pi), PT_KIND, Value::fixnum(kind));
        let pv = self.r(pi);
        self.register_port(pv);
        let out = self.r(pi);
        self.pop_to(base);
        out
    }

    /// The handle in THIS sandbox for the host's port `host_id`, minting one if
    /// this sandbox does not hold it yet (`doc/decisions/0027`).
    ///
    /// **This is the reference count, and it is a count of HOLDERS.** The weak
    /// intern table is what makes that possible: one handle object per host id
    /// per sandbox, so a port that arrives in two messages -- or is handed in
    /// twice, or arrives having already been handed in -- is the same object
    /// both times. `=` says yes, a map keyed by it hits, and the host is told
    /// exactly once that this sandbox took a reference.
    ///
    /// `EV_RETAIN` goes out only on a MISS. The alternative, counting arrivals,
    /// would make the number mean "how many references" rather than "how many
    /// holders", which is not a number anyone can act on: the host wants to know
    /// when it may let the port go, and that is when the last holder drops it.
    /// The matching `EV_RELEASE` is pushed by `reap_ports` when the collector
    /// finds the handle unreachable.
    ///
    /// The object in this heap carries the id and nothing else that crosses: no
    /// pointer into host memory, no pointer out of it. The collector traces and
    /// moves it like any other object, with no special case -- and it is NOT
    /// rooted, because a handle nothing refers to is precisely what a release
    /// is for. The system port is the exception, and it is rooted by being in
    /// `SC_SYSTEM` rather than by anything here.
    pub fn install_bridge_port(&mut self, host_id: i64, label: Value) -> Value {
        self.ensure_sched();
        if host_id < 0 {
            return NIL;
        }
        // Already held: hand back the SAME object and say nothing to the host.
        let existing = self.port_by_id(host_id);
        if !existing.is_nil() && fx(self.slot(existing, PT_KIND)) == K_BRIDGE {
            return existing;
        }
        let base = self.mark();
        let li = self.push(label);
        let l = self.r(li);
        let p = self.new_port(DEFAULT_BRIDGE_CAP, l, K_BRIDGE, P_OPEN, host_id);
        if p.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let pi = self.push(p);
        // Recorded as HELD, which is what `reap_ports` walks to notice the drop.
        let sc = self.sched();
        let sci = self.push(sc);
        let brs = self.slot(self.r(sci), SC_BRIDGES);
        let bi = self.push(brs);
        let nb = self.vec_conj(self.r(bi), Value::fixnum(host_id));
        self.set(self.r(sci), SC_BRIDGES, nb);
        // One increment, now that the handle exists and is interned.
        self.push_event(EV_RETAIN, host_id, 0, NIL);
        let out = self.r(pi);
        self.pop_to(base);
        out
    }

    /// Install the system port: the bridge a sandbox is DRIVEN over.
    ///
    /// A sandbox that is given one can ask for more ports on it; a sandbox that
    /// is not has no way to reach anything outside itself, which is the honest
    /// meaning of "no capabilities" and is the default.
    pub fn install_system_port(&mut self, host_id: i64, label: Value) -> Value {
        let p = self.install_bridge_port(host_id, label);
        if p.is_nil() {
            return NIL;
        }
        let base = self.mark();
        let pi = self.push(p);
        let s = self.sched();
        let si = self.push(s);
        let pv = self.r(pi);
        self.set(self.r(si), SC_SYSTEM, pv);
        let out = self.r(pi);
        self.pop_to(base);
        out
    }

    /// The system port. **Not reachable from guest code, and that is the point.**
    ///
    /// It is not a capability the sandbox holds, it is the TRANSPORT the sandbox
    /// is driven over: calls in arrive on it, and requests out -- for a
    /// capability, for another port -- leave on it. Handing it to guest code
    /// would make it ambient authority inside the sandbox, which is the thing
    /// `doc/decisions/0022` and `0027` both exist to prevent. There is no
    /// builtin that answers it; only the runtime looks it up.
    pub(crate) fn system_port(&mut self) -> Value {
        let s = self.sched();
        if s.is_nil() {
            return NIL;
        }
        self.slot(s, SC_SYSTEM)
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
        let a = self.new_port(cap, l, K_CHANNEL, P_OPEN, -1);
        let ai = self.push(a);
        let l = self.r(li);
        let b = self.new_port(cap, l, K_CHANNEL, P_OPEN, -1);
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

    /// How many messages are readable right now.
    ///
    /// Reservations that have not been filled are INCLUDED, because the bound
    /// this feeds is on occupancy rather than on readability: a reserved slot
    /// is spoken for. A reader asks `port_dequeue`, which tells readable from
    /// reserved by the sequence word.
    fn inbox_count(&self, p: Value) -> u32 {
        let w = self.cursor(p, PT_WRITE);
        let r = self.cursor(p, PT_READ);
        w.saturating_sub(r) as u32
    }

    #[inline]
    fn cursor(&self, p: Value, which: u32) -> u64 {
        fx(self.slot_atomic(p, which)) as u64
    }

    /// One slot, read atomically. Cursors and sequence words are fixnums like
    /// any other slot -- the collector sees nothing unusual -- and the atomic
    /// operates on the tagged word, so a compare-and-swap compares tagged
    /// against tagged and never invents a value.
    #[inline]
    fn slot_atomic(&self, o: Value, i: u32) -> Value {
        Value(self.gc.sp.atomic_load(crate::obj::slot_addr(o.as_heap(), i)))
    }

    /// Compare-and-swap a slot, AND run the write barrier when it lands.
    ///
    /// The barrier is the whole reason this is not a bare compare-and-swap. A
    /// ring big enough to miss the nursery lives in the old generation, and an
    /// old object pointing at a young one is an edge the collector finds only
    /// through the remembered set. Skipping it made every bridge trap while
    /// channels passed, because a bridge's ring is larger than a small
    /// channel's.
    ///
    /// Remembered after the swap lands, which is safe because a collection
    /// cannot run in between: the only safepoint is the interpreter's
    /// checkpoint, and there is not one here.
    #[inline]
    fn cas_slot_barriered(&mut self, o: Value, i: u32, want: Value, next: Value) -> bool {
        let obj = o.as_heap();
        if !self.gc.sp.cas(crate::obj::slot_addr(obj, i), want.0, next.0) {
            return false;
        }
        if next.is_heap() && self.gc.is_young(next.as_heap()) && !self.gc.is_young(obj) {
            self.gc.remember(obj, &mut self.roots.own.remembered);
        }
        true
    }

    #[inline]
    fn cas_slot(&self, o: Value, i: u32, want: Value, next: Value) -> bool {
        self.gc.sp.cas(crate::obj::slot_addr(o.as_heap(), i), want.0, next.0)
    }

    /// `port_enqueue`, exposed for the parallel tests: they drive the ring from
    /// two host threads without a green-thread scheduler in the way, and
    /// back-pressure is a RETURN VALUE here rather than a park.
    pub fn port_try_enqueue(&mut self, p: Value, v: Value) -> bool {
        self.port_enqueue(p, v)
    }

    /// How many messages are in `p`'s ring.
    pub fn inbox_depth(&self, p: Value) -> u32 {
        self.inbox_count(p)
    }

    /// Put `v` in `p`'s inbox. `false` means the ring is full.
    ///
    /// ONE compare-and-swap does the whole thing. The slot's own word is the
    /// lease: `EMPTY` means vacant, and swapping `EMPTY` for the message both
    /// CLAIMS the slot and FILLS it, in a step no other thread can observe half
    /// of. Winning the swap is the confirmation; losing it means somebody else
    /// took that slot and this sender looks at the next one.
    ///
    /// It replaced a reserve-then-publish protocol with a sequence word per
    /// slot -- a second array the same size as the ring -- and the sequence
    /// word existed only to describe a state this design does not have. A slot
    /// was reservable but not yet written, which meant a reader could meet one,
    /// a drain had to be allowed to skip one, a skip had to be signalled by a
    /// generation counter, and a sender whose reservation spanned a bump had to
    /// start over. None of that exists when there is no such state: a slot is
    /// vacant or it holds a whole message.
    ///
    /// The cursor is still here and still moved by compare-and-swap, but only
    /// to say WHICH slot to try next. It is a hint about order, not a claim on
    /// anything -- so a sender that loses the race for a slot simply advances,
    /// and who claimed it never mattered.
    fn port_enqueue(&mut self, p: Value, v: Value) -> bool {
        let ring = fx(self.slot(p, PT_RING)) as u64;
        if ring == 0 {
            return false;
        }
        let inbox = self.slot(p, PT_INBOX);
        loop {
            let w = self.cursor(p, PT_WRITE);
            let r = self.cursor(p, PT_READ);
            if w.saturating_sub(r) >= ring {
                return false;
            }
            let idx = (w % ring) as u32;
            // The cursor moves FIRST, so two senders reading the same `w` do
            // not both sit on one slot: the loser sees `w + 1` next time round
            // and tries the slot after it. Failing here is contention, not
            // fullness, and the fullness test above is the only thing that says
            // full.
            if !self.cas_slot(p, PT_WRITE, Value::fixnum(w as i64), Value::fixnum(w as i64 + 1)) {
                continue;
            }
            if self.cas_slot_barriered(inbox, idx, crate::value::EMPTY, v) {
                return true;
            }
            // The slot still holds a message nobody has taken. The cursor has
            // moved past it, which is correct -- it is not a slot to write --
            // and the next turn of the loop tries the next one.
            core::hint::spin_loop();
        }
    }

    /// Take the next message, or `NIL` when there is none.
    ///
    /// The mirror image: swap the message out for `EMPTY`, which frees the slot
    /// in the same step that takes the value. A slot holding `EMPTY` under the
    /// read cursor means the ring is drained, because a slot is never claimed
    /// without being filled.
    fn port_dequeue(&mut self, p: Value) -> Value {
        let ring = fx(self.slot(p, PT_RING)) as u64;
        if ring == 0 {
            return NIL;
        }
        let inbox = self.slot(p, PT_INBOX);
        loop {
            let r = self.cursor(p, PT_READ);
            if r >= self.cursor(p, PT_WRITE) {
                return NIL;
            }
            let idx = (r % ring) as u32;
            let v = self.slot_atomic(inbox, idx);
            if v.bits() == crate::value::EMPTY.bits() {
                // Claimed by a sender that has not landed its swap yet. It is
                // one instruction away; there is nothing here to take.
                return NIL;
            }
            if !self.cas_slot(p, PT_READ, Value::fixnum(r as i64), Value::fixnum(r as i64 + 1)) {
                continue;
            }
            if self.cas_slot_barriered(inbox, idx, v, crate::value::EMPTY) {
                return v;
            }
            core::hint::spin_loop();
        }
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

    /// `Ok` if `v` may cross this carrier.
    ///
    /// Functions are refused **by name**, because "cannot send that" sends
    /// somebody hunting through a nested structure.
    ///
    /// `carry` is what the carrying port can reach, and it decides what a port
    /// VALUE inside the message is allowed to be. See `Carry` above.
    pub fn check_sendable_via(
        &mut self,
        v: Value,
        carry: u8,
    ) -> Result<(), alloc::string::String> {
        self.check_sendable_at(v, 0, carry)
    }

    fn describe_fn(&mut self, v: Value) -> alloc::string::String {
        let t = ty(&self.gc.sp, v.as_heap());
        let name = match t {
            TY_CLOSURE => {
                let idx = self.slot(v, 0).as_fixnum() as usize;
                let namec = self.image.fns.get(idx).map(|d| d.name as usize).unwrap_or(usize::MAX);
                self.roots.shared.consts.get(namec).copied().unwrap_or(NIL)
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

    fn check_sendable_at(
        &mut self,
        v: Value,
        depth: u32,
        carry: u8,
    ) -> Result<(), alloc::string::String> {
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
            // `doc/decisions/0006` refused this outright -- "an endpoint cannot
            // be delegated at run time" -- and `0025` REVERSES it, which is the
            // point: a capability a program holds becomes something it can hand
            // on rather than only use.
            //
            // What made the old rule seem necessary was the worry that also
            // covers an opaque: a wire format that can write a port down is one
            // a receiver could write down too, and then a port is mintable from
            // an integer. `codec.rs` answers it -- the guest hands over a VALUE
            // and the runtime encodes it, so for `K_PORT` to appear in the
            // bytes the guest had to be holding the port, and a decoder
            // reachable from the guest refuses the tag (`decode_guest`).
            //
            // WHICH port may go WHERE is the other half, and it is not
            // symmetric:
            //
            //   | sent        | through     |               |
            //   | ----------- | ----------- | ------------- |
            //   | host port   | channel     | yes           |
            //   | host port   | host port   | yes           |
            //   | channel end | channel     | yes           |
            //   | channel end | host port   | NO            |
            //
            // The last row is the whole asymmetry. A channel is internal: both
            // ends live in this heap, the host was never told it exists and has
            // no id for it, so what would cross is a number meaningful only
            // here -- and a host that sent it back would be naming one of our
            // objects from outside, which is the integer-to-port conversion
            // this design exists to prevent. A host port is the opposite: its
            // id is the HOST's, so it already means something over there.
            TY_PORT if carry == CARRY_LOCAL => Ok(()),
            TY_PORT if carry == CARRY_CROSSING => {
                let kind = fx(self.slot(v, PT_KIND));
                if crosses_a_heap(kind) {
                    Ok(())
                } else {
                    Err("a channel endpoint cannot be sent to the host: both its ends live \
                         in this heap and the host has never been told it exists, so its id \
                         would name one of our objects from outside. A host port can be \
                         sent, because its id is the host's own."
                        .into())
                }
            }
            TY_PORT => {
                Err("a port cannot be sent through a port whose codec runs in the sandbox: \
                     the receiver could write the same bytes, and then a port is mintable \
                     from an integer. A channel carries one, and so does a host port \
                     opened with :format :flint."
                    .into())
            }
            // An opaque value is identity and nothing else (doc/decisions/0022).
            // The old rule refused it outright, and the reasoning was: anything
            // a codec could write down is something the RECEIVER could write
            // down too, and then it is mintable -- the whole property gone.
            //
            // That is right for a guest-side codec and wrong for `:format
            // :flint`, and the difference is who holds the decoder. On a wire
            // port the runtime encodes on the way out and decodes on the way in
            // (`is_wire_port`), and no decoder for that format is reachable from
            // the guest -- `codec.rs`'s one safety rule is exactly that, and
            // `decode_guest` is what enforces it. So the bytes are only ever
            // written and read on the host's side of the line: a guest can hand
            // over an opaque it HOLDS and can be handed one back, and still has
            // no way to turn an integer into one.
            //
            // A port is still refused on both, and not for a serialisation
            // reason: `doc/decisions/0006` says an endpoint is not transferable
            // and cannot be delegated at run time. That is a design decision
            // about ownership, which the encoding does not change.
            crate::obj::TY_OPAQUE => Ok(()),
            TY_STR | crate::obj::TY_ROPE | TY_SYM | TY_KW | TY_BIGINT | TY_REGEX => Ok(()),
            _ => {
                let base = self.mark();
                let vi = self.push(v);
                let mut out = Ok(());
                if self.is_map(self.r(vi)) {
                    let mut items: Vec<Value> = Vec::new();
                    let mut st = &mut items;
                    self.map_for_each(self.r(vi), &mut st, &mut |_rt, k, val, st| {
                        st.push(k);
                        st.push(val);
                    });
                    for it in items {
                        let ii = self.push(it);
                        out = self.check_sendable_at(self.r(ii), depth + 1, carry);
                        self.pop_to(ii);
                        if out.is_err() {
                            break;
                        }
                    }
                } else if self.is_set(self.r(vi)) {
                    let mut items: Vec<Value> = Vec::new();
                    let mut st = &mut items;
                    self.set_for_each(self.r(vi), &mut st, &mut |_rt, k, st| st.push(k));
                    for it in items {
                        let ii = self.push(it);
                        out = self.check_sendable_at(self.r(ii), depth + 1, carry);
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
                        out = self.check_sendable_at(self.r(fi), depth + 1, carry);
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
    let th = rt.current_thread();
    if th.is_nil() {
        return;
    }
    let base = rt.mark();
    let ti = rt.push(th);
    // Dynamic bindings travel with the thread.
    let binds = rt.roots.shared.singletons[crate::rt::SING_BINDINGS];
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
            if rt.is_port(on) && crosses_a_heap(fx(rt.slot(on, PT_KIND))) {
                return true;
            }
        }
    }
    false
}

fn run_one(rt: &mut Rt, i: u32) {
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
    rt.roots.shared.singletons[crate::rt::SING_BINDINGS] = binds;
    let at = rt.steps + SLICE;
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
/// Re-enter the scheduler after the host has answered.
///
/// What a host calls when `status` came back 2. There is no separate resume
/// state: the answer was already recorded by `host_continue`/`host_deliver`,
/// and this only starts the loop again. That is why those two record rather
/// than run -- a host calling one from inside a host function the runtime
/// itself invoked would otherwise run the scheduler on top of itself.
pub fn resume(rt: &mut Rt) -> Value {
    if rt.sched().is_nil() {
        return NIL;
    }
    drive(rt)
}

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
                    rt.close_all_bridges();
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
                P_REFUSED => "the host refused to open this",
                _ => "this port is not open yet",
            };
            let msg = alloc::format!("send: {why}");
            return self.throw_str("IllegalStateException", &msg);
        }
        // Root FIRST, check second. `check_sendable` walks the value, and
        // walking a sequential value allocates -- so it can collect, and an
        // unrooted `p`/`v` comes back pointing into the abandoned semispace.
        // Nothing downstream can tell that from a live pointer: `is_young`
        // spans both halves, so the write barrier and the generational
        // invariant both pass, and the stale address is copied faithfully into
        // the inbox and every clone of it thereafter.
        let base = self.mark();
        let pi = self.push(p);
        let vi = self.push(v);
        let kind = fx(self.slot(self.r(pi), PT_KIND));
        let carry = if crosses_a_heap(kind) { CARRY_CROSSING } else { CARRY_LOCAL };
        if let Err(e) = self.check_sendable_via(self.r(vi), carry) {
            self.pop_to(base);
            return self.throw_str("IllegalArgumentException", &e);
        }
        if crosses_a_heap(kind) {
            // ENCODING HAPPENS HERE, ALWAYS, AND ONLY HERE.
            //
            // A bridge carries bytes, and the runtime is what writes them. The
            // guest hands over a VALUE and is handed one back; it never sees an
            // encoding, has no encoder, and cannot choose one. That is not a
            // convenience -- `codec.rs` states the safety rule it enforces: a
            // decoder reachable from the guest would be an encoder read
            // backwards, and since `K_PORT` and `K_SENTINEL` carry their
            // identity inline as integers a guest can write, such a guest could
            // mint any host id it liked. An opaque value's whole meaning is that
            // it cannot.
            match self.encode(self.r(vi)) {
                Ok(b) => {
                    let bv = self.new_bytes(&b);
                    self.set_r(vi, bv);
                }
                Err(e) => {
                    self.pop_to(base);
                    let msg = alloc::format!("send: this cannot cross a bridge: {e}");
                    return self.throw_str("IllegalArgumentException", &msg);
                }
            }
            // Bound the queue in BYTES: back-pressure exists to bound memory,
            // and one 4 MB message is not one message's worth of it.
            //
            // On the handle itself. A bridge is ONE object here -- the far end
            // is the host's registry and is not in any heap -- so it is its own
            // accounting, where a host port used to need a second object to
            // carry the count.
            let len = self.b_count(self.r(vi)) as i64;
            let cap = fx(self.slot(self.r(pi), PT_CAP));
            let queued = fx(self.slot(self.r(pi), PT_BYTES));
            if queued > 0 && queued + len > cap {
                let target = self.r(pi);
                self.pop_to(base);
                return self.park_on_port(WK_SEND, target);
            }
            self.set(self.r(pi), PT_BYTES, Value::fixnum(queued + len));
            let id = fx(self.slot(self.r(pi), PT_ID));
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
        // TRY, then park -- rather than ask whether it is full and then put.
        //
        // The old order had a window between the question and the answer, and
        // with two executors that window is where messages went missing. The
        // reservation IS the question now: it either claims a slot or reports
        // the ring full, in one atomic step, and nothing can have changed in
        // between because there is no in between.
        let val = self.r(vi);
        let target = self.r(pei);
        if self.port_enqueue(target, val) {
            let target = self.r(pei);
            self.wake_on(target);
            self.pop_to(base);
            return NIL;
        }
        // FULL: back-pressure, which is the point of a bound. Park, and the
        // send re-runs when a receive frees a slot -- a parked builtin is
        // re-executable across a resume, so the whole put happens again rather
        // than being resumed halfway.
        let target = self.r(pei);
        self.pop_to(base);
        self.park_for_space(target)
    }

    /// Park until there is room in `p`'s ring.
    ///
    /// The re-check after registering is not belt and braces: `wake_on` only
    /// reaches waiters that are ALREADY in the list, so a receive that drains
    /// the ring between the failed reservation and the registration would wake
    /// nobody, and this thread would sleep with space in front of it. Register,
    /// look again, and give the token back if the answer changed.
    fn park_for_space(&mut self, p: Value) -> Value {
        let base = self.mark();
        let pi = self.push(p);
        let pv = self.r(pi);
        let token = self.new_waiter(WK_SEND, pv);
        let th = self.current_thread();
        if !th.is_nil() {
            self.set(th, TH_TOKEN, Value::fixnum(token));
        }
        let pv = self.r(pi);
        let ring = fx(self.slot(pv, PT_RING)) as u64;
        if (self.inbox_count(pv) as u64) < ring {
            // Room appeared while we were registering. Drop the waiter and let
            // the send run again immediately rather than waiting for a wake
            // that has already been and gone.
            self.free_waiter(token);
            if !th.is_nil() {
                self.set(th, TH_TOKEN, Value::fixnum(-1));
            }
            self.pop_to(base);
            // A yield rather than a park: the thread stays runnable and the
            // send runs again on its next turn, which is what "look again"
            // means when there is nothing left to wait for.
            return self.park(PARK_YIELD);
        }
        let pv = self.r(pi);
        self.pop_to(base);
        self.park(pv)
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
            if crosses_a_heap(fx(self.slot(self.r(pi), PT_KIND))) {
                // Room again for the host to deliver the next wave.
                let n = if self.is_bytes(self.r(vi)) {
                    self.b_count(self.r(vi)) as i64
                } else if self.is_vector(self.r(vi)) {
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
        // A BRIDGE has no peer OBJECT to ask about: the far end is the
        // host's registry and is not in any heap (`doc/decisions/0027`). Its own
        // state is the whole answer, and the states above have already covered
        // every way that can say "no more" -- so an empty buffer here means
        // "nothing yet", which is what parking is for.
        if fx(self.slot(self.r(pi), PT_KIND)) != K_BRIDGE {
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
        }
        let target = self.r(pi);
        self.pop_to(base);
        self.park_on_port(WK_RECEIVE, target)
    }

    /// Ask the host to open `name`, forwarding `args` verbatim. Blocking from
    /// the program's point of view; from the scheduler's, the thread stops
    /// being runnable.
    ///
    /// # The runtime does not know what a capability is
    ///
    /// It used to. There was a grant table in the sandbox, a `PT_PRESENTED`
    /// slot on every port, an ABI export to read it back, and a `flint_grant`
    /// to fill the table -- so a host could not decide for itself what a
    /// capability meant, because three layers had already decided.
    ///
    /// A capability is an OPAQUE VALUE (`doc/decisions/0022`) and nothing more.
    /// The host projects one into a sandbox by any means it likes -- an
    /// argument, a call, a port -- and a program that wants something a
    /// capability enables PRESENTS it with the request. This function forwards
    /// whatever it was given and takes no view; the host decodes the arguments
    /// and answers. Capabilities are optional because nothing here requires one.
    ///
    /// What makes that safe is not a table but the encoding: an opaque value
    /// crosses as `K_SENTINEL` plus the host id it was ISSUED with, and guest
    /// code cannot mint that id -- `flint/opaque` gives 0. So a host recognises
    /// its own grants and nothing else, which is the whole check and it lives
    /// where the grants do.
    ///
    /// The **runtime** creates the pair -- the host never holds two ends and
    /// never hands one back. It is told the token to answer with and the id of
    /// the end it will hold.
    /// Ask for a port. **A REQUEST ON THE SYSTEM PORT**, not a construction
    /// (`doc/decisions/0027`).
    ///
    /// A sandbox cannot make a bridge. It used to: `open` allocated a PAIR of
    /// ends here, kept one and offered the other up as "the host's", which made
    /// the confined thing the author of its own authority and gave the port an
    /// id that meant nothing in any other sandbox. Now nothing is allocated
    /// until the host answers, and what comes back is a handle on a port the
    /// host already owns.
    ///
    /// Combined with `0025`'s rule -- flint is given no way to turn an integer
    /// into a port -- this is stronger than either piece alone: a sandbox can
    /// obtain a port neither by fabrication NOR by construction. Every port it
    /// will ever hold was handed to it.
    pub fn port_open(&mut self, name: Value, args: Value) -> Value {
        self.ensure_sched();
        let base = self.mark();
        let ni = self.push(name);
        let ai = self.push(args);
        let th = self.current_thread();
        let ti = self.push(th);
        let pending = self.slot(self.r(ti), TH_PENDING);
        if !pending.is_nil() {
            // Second time round: the host has answered.
            self.set(self.r(ti), TH_PENDING, NIL);
            // A GRANT left the handle here; a refusal left the sentinel below.
            // Only a refusal is a refusal -- the host may grant and then close
            // the port before this thread is next scheduled, and the port is
            // then `P_HALF` ("granted, and now finished"), which is not the same
            // as "you may not have this".
            if self.is_port(pending) {
                self.pop_to(base);
                return pending;
            }
            let mut b = crate::rt::sbuf();
            let n: alloc::string::String = self.as_str(self.r(ni), &mut b).unwrap_or("?").into();
            self.pop_to(base);
            let msg = alloc::format!("the host refused to open {n:?}");
            return self.throw_str("SecurityException", &msg);
        }
        // NO SYSTEM PORT, NO ASKING. A sandbox given no transport has no way to
        // reach anything outside itself, and saying so here is more honest than
        // pushing an event nothing will ever drain -- that would park the thread
        // for ever and read as a hang rather than as a refusal.
        let sys = self.system_port();
        if sys.is_nil() {
            let mut b = crate::rt::sbuf();
            let n: alloc::string::String = self.as_str(self.r(ni), &mut b).unwrap_or("?").into();
            self.pop_to(base);
            let msg = alloc::format!(
                "this sandbox was given no system port, so it cannot ask for {n:?}"
            );
            return self.throw_str("SecurityException", &msg);
        }
        let si = self.push(sys);
        // The waiter hangs off the SYSTEM port, because that is the port the
        // request went out on and there is no other port yet -- the whole point
        // is that the answer is what creates one.
        let target = self.r(si);
        let token = self.new_waiter(WK_OPEN, target);
        let t = self.r(ti);
        self.set(t, TH_TOKEN, Value::fixnum(token));
        // Mark the thread as awaiting an answer with a value that is NOT a port,
        // so the resume above can tell "granted" from "refused" by type rather
        // than by a state flag on an object that no longer exists until granted.
        let t = self.r(ti);
        self.set(t, TH_PENDING, Value::fixnum(0));
        // THE ARGUMENTS, ENCODED, are the payload -- not a bare name string.
        // That is the whole of "the host does what it wants with them": one
        // value crosses, the host decodes it, and anything an opaque value
        // carries (`K_SENTINEL` plus the id the host issued) survives the trip
        // because `codec.rs` already knew how to write one down.
        let mut call: alloc::vec::Vec<u8> = alloc::vec::Vec::new();
        {
            let v = self.empty_vec();
            let vi = self.push(v);
            let nm = self.r(ni);
            let nv = self.vec_conj(self.r(vi), nm);
            self.set_r(vi, nv);
            let a = self.r(ai);
            let n = if self.is_vector(a) { self.vec_count(a) } else { 0 };
            for k in 0..n {
                let x = self.vec_nth(self.r(ai), k).unwrap_or(NIL);
                let xi = self.push(x);
                let nv = self.vec_conj(self.r(vi), self.r(xi));
                self.set_r(vi, nv);
                self.pop_to(xi);
            }
            // A value the codec refuses is the program's error, not the host's:
            // say so here rather than sending something the host cannot read.
            match self.encode(self.r(vi)) {
                Ok(b) => call = b,
                Err(e) => {
                    self.pop_to(base);
                    let msg = alloc::format!("open: this cannot be sent to the host: {e}");
                    return self.throw_str("IllegalArgumentException", &msg);
                }
            }
        }
        let payload = self.new_bytes(&call);
        let pi = self.push(payload);
        let sys_id = fx(self.slot(self.r(si), PT_ID));
        let pv = self.r(pi);
        self.push_event(EV_OPEN, token, sys_id, pv);
        let target = self.r(si);
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
    /// host if this was a bridge, and wake anybody parked on either side.
    fn close_side_effects(&mut self, p: Value) {
        let base = self.mark();
        let pi = self.push(p);
        let kind = fx(self.slot(self.r(pi), PT_KIND));
        if crosses_a_heap(kind) {
            // A CLOSE IS A RELEASE, and it is the prompt one.
            //
            // Dropping the last reference and waiting for the collector gets
            // here too, via `reap_ports`, but that is the backstop rather than
            // the mechanism -- it is not prompt, and a host holding a socket
            // until then is a real cost. Closing says so now. The id is dropped
            // from `SC_BRIDGES` in the same breath, so the sweep does not send
            // a second release for a port already let go.
            let id = fx(self.slot(self.r(pi), PT_ID));
            self.push_event(EV_CLOSED, id, 0, NIL);
            self.forget_bridge(id);
            self.push_event(EV_RELEASE, id, 0, NIL);
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

    /// Drop `id` from the held list, so the sweep does not release it twice.
    fn forget_bridge(&mut self, id: i64) {
        let base = self.mark();
        let s = self.sched();
        if s.is_nil() {
            return;
        }
        let si = self.push(s);
        let brs = self.slot(self.r(si), SC_BRIDGES);
        let bi = self.push(brs);
        let n = self.vec_count(self.r(bi));
        let keep = self.empty_vec();
        let ki = self.push(keep);
        for k in 0..n {
            let x = fx(self.vec_nth(self.r(bi), k).unwrap_or(NIL));
            if x == id {
                continue;
            }
            let nk = self.vec_conj(self.r(ki), Value::fixnum(x));
            self.set_r(ki, nk);
        }
        let keep = self.r(ki);
        self.set(self.r(si), SC_BRIDGES, keep);
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
        if ok {
            // A GRANT HAS TO NAME A PORT. There is no port to grant until the
            // host says which one -- that is what `0027` inverted -- so this
            // form can only ever mean a refusal, and a host that means to grant
            // calls `host_grant`. Answering `true` here would have to invent a
            // port, which is exactly the construction the sandbox may not do
            // and the host must not be able to do by accident.
            return false;
        }
        let w = self.waiter_at(token);
        if w.is_nil() {
            return false;
        }
        let base = self.mark();
        let wi = self.push(w);
        // The refusal is left on the thread as a non-port, which `port_open`
        // reads on resume. Nothing else has to be cleaned up, because a refused
        // open allocated nothing in the first place.
        let wv = self.r(wi);
        self.wake_waiter(wv);
        self.pop_to(base);
        true
    }

    /// Grant an open: hand the waiting thread a handle on the host's port
    /// `host_port_id` (`doc/decisions/0027`).
    ///
    /// The id is the HOST's. It is the same id in every sandbox that holds this
    /// port, which is what makes a handle sendable between two of them at all,
    /// and it is the id the retain and release events name.
    ///
    /// If this sandbox already holds that port, the SAME handle comes back and
    /// no reference is taken -- granting a port twice is not two holders.
    pub fn host_grant(&mut self, token: i64, host_port_id: i64) -> bool {
        let w = self.waiter_at(token);
        if w.is_nil() {
            return false;
        }
        let base = self.mark();
        let wi = self.push(w);
        let label = self.slot(self.r(wi), W_PORT);
        let label = if label.is_nil() { NIL } else { self.slot(label, PT_LABEL) };
        let li = self.push(label);
        let l = self.r(li);
        let p = self.install_bridge_port(host_port_id, l);
        if p.is_nil() {
            self.pop_to(base);
            return false;
        }
        let pi = self.push(p);
        // Onto the thread that asked, which `port_open` reads when it resumes.
        let th = self.slot(self.r(wi), W_THREAD);
        if !th.is_nil() {
            let pv = self.r(pi);
            self.set(th, TH_PENDING, pv);
        }
        let wv = self.r(wi);
        self.wake_waiter(wv);
        self.pop_to(base);
        true
    }

    /// Put a message into a bridge from the host's side. Wakes a parked
    /// receiver; it does not run anything.
    ///
    /// Returns **false when the guest's buffer is full**, and the host must hold
    /// the message and offer it again after the next pump. Without that, a
    /// server answering "in waves" would simply push every wave at once and the
    /// whole answer would be resident in the guest heap -- which is precisely
    /// what waves exist to prevent. Inbound needs the same back-pressure as
    /// outbound; it is the same buffer bound, seen from the other side.
    ///
    /// The bytes are DECODED here, by the runtime, which is the mirror of
    /// `port_send` encoding them. The HOST wrote them, so the live tags are
    /// honoured -- `decode`, not `decode_guest`. That is the whole asymmetry: an
    /// opaque the host issued arrives as itself, with the id it was given, and
    /// nothing the guest can write reaches this call.
    pub fn host_deliver(&mut self, host_port_id: i64, bytes: &[u8]) -> bool {
        let host = self.port_by_id(host_port_id);
        if host.is_nil() {
            return false;
        }
        let base = self.mark();
        let hi = self.push(host);
        // A bridge is ONE object and the id is its own, so the lookup above has
        // already found the end to deliver into. There is no pair and no peer
        // hop: that indirection existed only because a host port kept its
        // bookkeeping on a second object (`doc/decisions/0027`).
        let pi = {
            let pv = self.r(hi);
            self.push(pv)
        };
        // BACK-PRESSURE, in bytes, and it is claimed atomically for the same
        // reason the ring slot is: two host threads delivering into one end
        // would both read the same `queued`, both decide there is room, and
        // both write -- and the bound that exists to cap memory would be the
        // one thing not enforced. Claimed BEFORE the message is built, and
        // given back if anything after this refuses.
        let cap = fx(self.slot(self.r(pi), PT_CAP));
        let len = bytes.len() as i64;
        loop {
            let pv = self.r(pi);
            let queued = fx(self.slot_atomic(pv, PT_BYTES));
            if queued > 0 && queued + len > cap {
                self.pop_to(base);
                return false;
            }
            if self.cas_slot(
                pv,
                PT_BYTES,
                Value::fixnum(queued),
                Value::fixnum(queued + len),
            ) {
                break;
            }
        }
        // From here every refusal has to hand the bytes back, or a message the
        // guest never saw goes on counting against its bound for ever.
        macro_rules! give_back {
            () => {{
                let pv = self.r(pi);
                loop {
                    let q = fx(self.slot_atomic(pv, PT_BYTES));
                    let back = if q > len { q - len } else { 0 };
                    if self.cas_slot(pv, PT_BYTES, Value::fixnum(q), Value::fixnum(back)) {
                        break;
                    }
                }
            }};
        }
        let v = match self.decode(bytes) {
            Ok(v) => v,
            // Refused rather than delivered as anything else: a message the
            // format cannot read is the host's error, and turning it into a
            // string here would hand the guest something that silently was not
            // what was sent.
            Err(_) => {
                give_back!();
                self.pop_to(base);
                return false;
            }
        };
        let vi = self.push(v);
        let (target, val) = (self.r(pi), self.r(vi));
        if !self.port_enqueue(target, val) {
            // The ring is full even though the byte bound had room: the guest
            // has not drained, so the host is told to offer this again. Same
            // answer as the byte bound gives, because it is the same
            // situation -- back-pressure, not an error.
            give_back!();
            self.pop_to(base);
            return false;
        }
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
        // HALF-CLOSED, not closed: whatever the host already delivered is still
        // there to be read, and only when that is drained does it read as end
        // of stream. There is one object now, not a pair, so this is the state
        // of the handle itself rather than of a second end standing in for it.
        if fx(self.slot(self.r(hi), PT_STATE)) == P_OPEN {
            self.set(self.r(hi), PT_STATE, Value::fixnum(P_HALF));
        }
        let target = self.r(hi);
        self.wake_on(target);
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
        let p = self.port_by_id(host_port_id);
        if p.is_nil() {
            // Never heard of, or the handle has been collected. Either way a
            // host treats it as done, which is the case a missed `:closed`
            // event would otherwise leak.
            return 255;
        }
        self.port_state_now(p)
    }

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
            let plen = if self.is_bytes(payload) {
                let n = self.b_count(payload);
                self.b_append(payload, out);
                n
            } else if self.is_vector(payload) {
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
        let s = self.sched();
        if s.is_nil() {
            return;
        }
        let base = self.mark();
        let si = self.push(s);
        // --- bridges: a collection is a RELEASE ------------------------------
        //
        // The handle is ordinary memory and is not rooted, so the collector
        // finding it unreachable IS this sandbox letting the port go. One
        // `EV_RELEASE` per `EV_RETAIN`, which is what makes the host's count a
        // count of holders rather than of arrivals (`doc/decisions/0027`).
        let brs = self.slot(self.r(si), SC_BRIDGES);
        let bi = self.push(brs);
        let bn = self.vec_count(self.r(bi));
        let held = self.empty_vec();
        let hi = self.push(held);
        for k in 0..bn {
            let id = fx(self.vec_nth(self.r(bi), k).unwrap_or(NIL));
            if self.port_by_id(id).is_nil() {
                self.push_event(EV_RELEASE, id, 0, NIL);
                continue;
            }
            let nh = self.vec_conj(self.r(hi), Value::fixnum(id));
            self.set_r(hi, nh);
        }
        let held = self.r(hi);
        self.set(self.r(si), SC_BRIDGES, held);

        // --- channels: a collected end orphans its peer -----------------------
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
            let peer_id = self.peer_id_of_dead(id);
            let peer = self.port_by_id(peer_id);
            if peer.is_nil() {
                continue;
            }
            let pi = self.push(peer);
            let pst = fx(self.slot(self.r(pi), PT_STATE));
            if pst != P_CLOSED && pst != P_ORPHANED {
                // Its peer vanished without closing, which is not the same as a
                // tidy close and should not read like one.
                self.set(self.r(pi), PT_STATE, Value::fixnum(P_ORPHANED));
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

    /// Program exit: close and release every bridge, so a host is never left
    /// holding a reference for a sandbox that has finished, and leave the events
    /// for the final drain.
    pub fn close_all_bridges(&mut self) {
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
            if crosses_a_heap(fx(self.slot(self.r(pi), PT_KIND)))
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
