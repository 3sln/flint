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
//! its own loop and the host is never blocked. See `DECISIONS.md#threads-and-ports`.
//!
//! # Nothing here is in a pure module
//!
//! Every function in this file is reached only from the concurrency unit's
//! builtins, or through `Rt::sched_hook`, which is `None` until one of those
//! builtins runs. A program that never mentions `spawn`, `channel` or `open`
//! never exports any of them, so `--gc-sections` deletes the lot -- the same
//! mechanism that keeps the XML parser out (`DECISIONS.md#namespace-units`).
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
/// needs (`DECISIONS.md#emit-wasm-instead-of-dispatch`): which compiled arity, and which block to come
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
/// kind of park (`DECISIONS.md#host-abi`).
pub const TH_TOKEN: u32 = 10;
/// An error handed to a parked thread, to be raised when it next runs. The
/// scheduler uses it to say "the other end of your port is gone" in the thread
/// that cares rather than in whichever thread noticed.
pub const TH_FAIL: u32 = 11;
/// Arguments for the thread's entry, or nil for the no-argument case a
/// `spawn` makes. A CALL from the host arrives with them
/// (`DECISIONS.md#structured-ports`), and a green thread is what runs it -- the called
/// function may open a port and park, and a call that ran on the host's stack
/// could not.
pub const TH_ARGS: u32 = 12;
/// The transaction id of the CALL this thread is answering, or -1.
///
/// A thread with one sends its result back on the system port when it finishes,
/// which is the whole of "nothing is called automatically": there is no entry
/// point the runtime invokes, only calls the host asks for and answers it gets.
pub const TH_TX: u32 = 13;
pub const TH_LEN: u32 = 14;

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
// (`DECISIONS.md#ports-are-the-hosts`).
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
/// A handle on a port the HOST owns (`DECISIONS.md#ports-are-the-hosts`).
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
/// (`DECISIONS.md#host-abi`).
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
/// Parked on `host_request`, which is `WK_OPEN` generalised: the answer is a
/// VALUE rather than necessarily a port (`DECISIONS.md#workspace-capabilities` step 7).
pub const WK_REQUEST: i64 = 5;

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
/// The SYSTEM port, if this sandbox was given one (`DECISIONS.md#ports-are-the-hosts`).
///
/// A sandbox no longer manufactures its own end and offers it up: the host owns
/// the port, keeps one end, and passes the other in at construction. Absent is
/// a normal state and not an error -- a sandbox without one runs logic and
/// cannot ask the host for anything, which is the honest default for confined
/// code rather than a degraded mode.
pub const SC_SYSTEM: u32 = 9;
/// Host ids of every BRIDGE this sandbox holds a handle for -- ids, not
/// references, so the list pins nothing (`DECISIONS.md#ports-are-the-hosts`).
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
/// The guest is asking the host for SOMETHING, and the answer is an ordinary
/// value rather than a port (`DECISIONS.md#workspace-capabilities` step 7).
///
/// `EV_OPEN` is the special case of this whose answer happens to be a port, and
/// it stays: a port is granted by id through `host_grant` and never encoded, so
/// collapsing the two would mean encoding a handle, which is the one thing the
/// codec must not do for a port it is lending rather than sending.
pub const EV_REQUEST: i64 = 6;

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
    /// Install the scheduler as the singleton the collector already traces.
    pub(crate) fn install_sched(&mut self, s: Value) {
        self.roots.shared.singletons[SING_SCHED] = s;
    }

    /// Is a program actually running? See `something-running` in the kin
    /// vocabulary: an empty frame stack means thread 0 represents no stack.
    pub(crate) fn something_running(&self) -> bool {
        !self.frames.is_empty()
    }

    /// Make the scheduler if there is not one, and hand it back.
    ///
    /// The OBJECT is generated, from `kin/schedmake.kin` -- twelve slots and
    /// five on thread 0, every one of them a default something later reads
    /// without checking. What stays here is what kin has no answer for: two
    /// function references, and this runtime's own notion of "a scheduler now
    /// exists".
    pub fn ensure_sched(&mut self) -> Value {
        let s = self.sched();
        if !s.is_nil() {
            return s;
        }
        // The decoder's route to `install_bridge_port`, set HERE and nowhere
        // else. See `Rt::bridge_hook`: reaching it directly from `codec.rs` put
        // the whole scheduler in every module, including ones with no ports.
        self.bridge_hook = Some(|rt, id| rt.install_bridge_port(id, NIL, true));
        let out = self.new_sched_at();
        if out.is_nil() {
            return NIL;
        }
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
        self.vec_nth(self.slot(s, SC_THREADS), i, NIL)
    }

    // --- saving and restoring a VM state -----------------------------------

    /// Save the live VM state into `th`. Public so that the collector tests can
    /// build the exact situation section 3 of `DECISIONS.md#threads-and-ports` warns about:
    /// a parked thread holding the only reference to a value.
    pub fn save_thread_state(&mut self, th: Value) {
        self.save_current_state(th)
    }

    /// The saved stack of a parked thread, for the same tests.
    pub fn thread_saved_stack(&self, th: Value) -> Value {
        self.slot(th, TH_STACK)
    }

    pub(crate) fn save_current_state(&mut self, th: Value) {
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
                bytes[o..o + 4].copy_from_slice(&f.fp.to_le_bytes());
                bytes[o + 4..o + 8].copy_from_slice(&f.ip.to_le_bytes());
                bytes[o + 8..o + 12].copy_from_slice(&f.end.to_le_bytes());
                bytes[o + 12..o + 16].copy_from_slice(&f.ret_to.to_le_bytes());
                bytes[o + 16..o + 20].copy_from_slice(&f.handlers.to_le_bytes());
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

        // UNBILLED, like the two buffers above it, and it was the odd one out.
        //
        // The stack and the frames are saved with `alloc_unbilled` because
        // their SIZE is a property of the calling convention rather than of
        // the program; the handler buffer is the same kind of thing and was
        // allocated with `new_obj`, which charges. Nothing argued for the
        // difference -- there was no comment on it -- and the consequence was
        // that gas depended on WHERE a thread happened to be preempted, since
        // what is charged is the number of handlers live at that moment.
        //
        // That is the defect `bin/conform-hosts`'s gas row had been reporting
        // as 6 steps. Found by asking a per-type allocation histogram which
        // objects differed between two orderings of the same two expressions:
        // exactly one, `TY_RAW`, five gas (`DECISIONS.md#resource-limits`).
        let hb = {
            let a = self.alloc_unbilled(TY_RAW, (self.handlers.len() * 16) as u32);
            if a == 0 { NIL } else { Value::heap(a) }
        };
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
                note_segment(f.instrs, f.flags & crate::vm::FRAME_RESUMED != 0);
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

    pub fn restore_state(&mut self, th: Value) {
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
                    fp: g(o),
                    ip: g(o + 4),
                    end: g(o + 8),
                    ret_to: g(o + 12),
                    handlers: g(o + 16),
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
                    flags: crate::vm::FRAME_RESUMED,
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

    // `park` AND `park_on_port` ARE GENERATED (`kin/sched.kin`) and are
    // methods on `Rt` from there.
    //
    // A park is TWO WRITES: `park_on` says what is being waited for, so
    // `needs_host` can tell a bridge from a channel; `thrown = PARK` is what
    // unwinds every frame between the builtin and the top, exactly as a throw
    // does. `park_on_port` registers the waiter FIRST and parks second, and
    // puts the token on the thread so a wake can retire the waiter without
    // searching for it.

    // --- spawning ----------------------------------------------------------

    /// A new green thread running `f` (no arguments).
    ///
    /// It **inherits a snapshot of its spawner's dynamic bindings**, which is
    /// what Clojure conveys to `future` and agents, and what somebody debugging
    /// at three in the morning will assume. The snapshot is taken here: later
    /// `binding` in the spawner does not reach the child.
    /// GENERATED, from `kin/portmake.kin`. Every slot it fills is a default
    /// something later reads without checking.
    pub fn spawn_thread(&mut self, f: Value) -> Value {
        self.spawn_at(f)
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

    pub(crate) fn register_port(&mut self, p: Value) {
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
    /// GENERATED, from `kin/portmake.kin`. Every slot of a port is a default
    /// something later READS without checking, so three copies of this were
    /// three chances for one to drift by a line.
    fn new_port(&mut self, cap: i64, label: Value, kind: i64, state: i64, id: i64) -> Value {
        self.new_port_at(cap, label, kind, state, id)
    }

    /// The handle in THIS sandbox for the host's port `host_id`, minting one if
    /// this sandbox does not hold it yet (`DECISIONS.md#ports-are-the-hosts`).
    ///
    /// **This is the reference count, and it is a count of HOLDERS.** The weak
    /// intern table is what makes that possible: one handle object per host id
    /// per sandbox, so a port that arrives in two messages -- or is handed in
    /// twice, or arrives having already been handed in -- is the same object
    /// both times. `=` says yes, a map keyed by it hits, and the host is told
    /// exactly once that this sandbox took a reference.
    ///
    /// `announce` says whether to PUSH `EV_RETAIN`, and it is false for a host
    /// that installed or granted the port itself: that host already knows, and
    /// an event it does not need is traffic queued before the program has even
    /// started -- which makes `main` come back 2 ("the host is needed") when
    /// nothing is parked, and `test/globalport.mjs` states the property that
    /// breaks: "installing one does not disturb a program that does not know it
    /// exists". True only for the DECODER, where a port arriving inside a
    /// message is the one case the host could not have known about.
    ///
    /// Either way it is one increment per sandbox, on the MISS that mints the
    /// handle. Counting arrivals instead would make the number mean "how many
    /// references" rather than "how many holders", which is not a number anyone
    /// can act on: the host wants to know when it may let the port go, and that
    /// is when the last holder drops it. The matching `EV_RELEASE` is pushed by
    /// `reap_ports` when the collector finds the handle unreachable, or promptly
    /// by `close` -- and that one always goes out, because a drop is never
    /// something the host asked for.
    ///
    /// The object in this heap carries the id and nothing else that crosses: no
    /// pointer into host memory, no pointer out of it. The collector traces and
    /// moves it like any other object, with no special case -- and it is NOT
    /// rooted, because a handle nothing refers to is precisely what a release
    /// is for. The system port is the exception, and it is rooted by being in
    /// `SC_SYSTEM` rather than by anything here.
    /// GENERATED, from `kin/portinstall.kin`. The ORDER is the point: every
    /// runtime rooted the label AFTER building the scheduler, which is a live
    /// rooting bug on the first host port installed into a fresh sandbox.
    pub fn install_bridge_port(&mut self, host_id: i64, label: Value, announce: bool) -> Value {
        self.install_bridge_at(host_id, label, announce)
    }

    /// Install the system port: the bridge a sandbox is DRIVEN over.
    ///
    /// A sandbox that is given one can ask for more ports on it; a sandbox that
    /// is not has no way to reach anything outside itself, which is the honest
    /// meaning of "no capabilities" and is the default.
    /// GENERATED, from `kin/portinstall.kin`.
    pub fn install_system_port(&mut self, host_id: i64, label: Value) -> Value {
        self.install_system_at(host_id, label)
    }

    /// The system port. **Not reachable from guest code, and that is the point.**
    ///
    /// It is not a capability the sandbox holds, it is the TRANSPORT the sandbox
    /// is driven over: calls in arrive on it, and requests out -- for a
    /// capability, for another port -- leave on it. Handing it to guest code
    /// would make it ambient authority inside the sandbox, which is the thing
    /// `DECISIONS.md#opaque-values` and `ports-are-the-hosts` both exist to prevent. There is no
    /// builtin that answers it; only the runtime looks it up.
    /// The system port's id, or -1. What a HOST asks, since it cannot hold the
    /// port itself -- the value is deliberately not reachable from outside.
    pub fn system_port_id(&mut self) -> i64 {
        let p = self.system_port();
        if p.is_nil() {
            -1
        } else {
            fx(self.slot(p, PT_ID))
        }
    }

    /// `pub`, not `pub(crate)`: `flint/system-port` is a BUILTIN, and the
    /// builtin lives in the `flint-conc` unit crate. `flint.system/boot` is a
    /// thunk that fetches the port rather than closing over it, which is why
    /// the guest can reach this at all (`DECISIONS.md#bridges-are-the-only-door`).
    pub fn system_port(&mut self) -> Value {
        let s = self.sched();
        if s.is_nil() {
            return NIL;
        }
        self.slot(s, SC_SYSTEM)
    }

    /// GENERATED, from `kin/portmake.kin`.
    fn link_peers(&mut self, a: Value, b: Value) {
        self.link_peers_at(a, b);
    }

    /// A coupled pair. What goes into one comes out of the other, both ways.
    /// GENERATED, from `kin/portmake.kin`. Every slot it fills is a default
    /// something later reads without checking.
    pub fn make_channel(&mut self, cap: i64, label: Value) -> Value {
        self.channel_at(cap, label)
    }

    pub(crate) fn peer_of(&mut self, p: Value) -> Value {
        let id = fx(self.slot(p, PT_PEER));
        self.port_by_id(id)
    }

    /// How many messages are readable right now.
    ///
    /// Reservations that have not been filled are INCLUDED, because the bound
    /// this feeds is on occupancy rather than on readability: a reserved slot
    /// is spoken for. A reader asks `port_dequeue`, which tells readable from
    /// reserved by the sequence word.
    /// GENERATED (`kin/portring.kin`).
    fn inbox_count(&self, p: Value) -> u32 {
        self.ring_inbox_count(p)
    }

    #[inline]
    /// GENERATED (`kin/portring.kin`).
    fn cursor(&self, p: Value, which: u32) -> u64 {
        self.ring_cursor(p, which) as u64
    }

    /// One slot, read atomically. Cursors and sequence words are fixnums like
    /// any other slot -- the collector sees nothing unusual -- and the atomic
    /// operates on the tagged word, so a compare-and-swap compares tagged
    /// against tagged and never invents a value.
    #[inline]
    pub fn slot_atomic(&self, o: Value, i: u32) -> Value {
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
    pub fn cas_slot_barriered(&mut self, o: Value, i: u32, want: Value, next: Value) -> bool {
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
    pub fn cas_slot(&self, o: Value, i: u32, want: Value, next: Value) -> bool {
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
    /// GENERATED (`kin/portring.kin`). Every message a sandbox receives goes
    /// through this and its twin, and a divergence in either is a message
    /// lost or delivered twice -- which the three copies agreed on only
    /// because nothing ever ran them against each other.
    fn port_enqueue(&mut self, p: Value, v: Value) -> bool {
        self.ring_enqueue(p, v)
    }

    /// Take the next message, or `NIL` when there is none.
    ///
    /// The mirror image: swap the message out for `EMPTY`, which frees the slot
    /// in the same step that takes the value. A slot holding `EMPTY` under the
    /// read cursor means the ring is drained, because a slot is never claimed
    /// without being filled.
    /// GENERATED (`kin/portring.kin`) -- see `port_enqueue`.
    fn port_dequeue(&mut self, p: Value) -> Value {
        self.ring_dequeue(p)
    }

    // --- waiters and their tokens ------------------------------------------

    fn waiters(&self) -> Value {
        self.slot(self.sched(), SC_WAITERS)
    }

    // `new_waiter` IS GENERATED (`kin/sched.kin`) and is a method on `Rt`
    // from there. It mints the token a host echoes back: `(gen << 16) | (idx
    // + 1)`, with `+ 1` so that zero is never valid -- `TH_TOKEN` carries
    // zero-meaning-absent, and a host ABI whose zero value means something is
    // a trap waiting for an uninitialised variable.

    /// Spawn the control plane, ONCE, and only when there is a door.
    ///
    /// `drive` asks every iteration because the system port may not exist yet
    /// when the loop first runs -- a host installs it, and until it has there
    /// is nothing for the control plane to serve. Answering "not yet" is
    /// therefore normal rather than an error.
    ///
    /// **No guest code runs here.** `flint.system/boot` is a THUNK, so this
    /// takes its var's value and spawns it -- nothing is called. The first
    /// version called a flint function to build a closure over the port, and
    /// that re-entered `drive` from inside `drive`: the nested scheduler ran,
    /// found the boot flag already set, and the outer call came back with
    /// nothing callable. The sandbox then tore itself down with no message
    /// ever served, and the only visible symptom was "the call was never
    /// answered" (`DECISIONS.md#bridges-are-the-only-door`).
    ///
    /// **Initialisers must have run**, because a var is nil until they have --
    /// which is what `ensure_started` is for.
    ///
    /// ABSENT `flint.system` IS NOT AN ERROR. A module built before this
    /// existed has no control plane, and a sandbox nothing can call is a
    /// coherent thing to be; failing here would make every old artefact
    /// unloadable.
    pub fn boot_system_thread_once(&mut self) {
        if self.system_booted {
            return;
        }
        if self.system_port().is_nil() {
            return;
        }
        self.system_booted = true;
        if !self.ensure_started() {
            return;
        }
        let idx = match self.var_named("flint.system/boot") {
            Some(i) => i,
            None => return,
        };
        let f = self
            .roots
            .shared
            .globals
            .get(idx as usize)
            .map_or(NIL, |g| g.get());
        if f.is_nil() || !self.is_callable(f) {
            return;
        }
        self.spawn_thread(f);
    }

    // `abandon_current_thread` IS GENERATED, from `kin/sched.kin`, and lands
    // on `Rt` in `crate::kgen::rt::sched` -- so callers here reach it by name
    // with nothing in between. It was hand-written in this file and in neither
    // port, which is how a top-level park went unreported on both of them.

    /// The bindings live RIGHT NOW, which a spawn inherits. The read side of
    /// `install_bindings` below.
    pub(crate) fn current_bindings(&self) -> Value {
        self.roots.shared.singletons[crate::rt::SING_BINDINGS]
    }

    /// Install a thread's dynamic bindings as the live ones.
    ///
    /// One line, named by the vocabulary so `kin/sched.kin` can say it.
    /// Bindings travel WITH the thread: `settle` saves them back.
    pub fn install_bindings(&mut self, binds: Value) {
        self.roots.shared.singletons[crate::rt::SING_BINDINGS] = binds;
    }

    /// Give the thread about to run a fresh turn.
    ///
    /// From the CURRENT step count, so every thread gets the same size turn
    /// however long the last one ran.
    pub fn begin_slice(&mut self) {
        let at = self.steps + SLICE;
        self.set_slice_end(at);
    }

    /// Empty the interpreter, for a thread that has no state to restore.
    ///
    /// All three stacks together: a NEW thread starts on a clean interpreter
    /// or it inherits whatever the last one left behind.
    pub fn reset_exec_state(&mut self) {
        self.frames.clear();
        self.handlers.clear();
        self.roots.stack_top = 0;
    }

    // THE WAITER TABLE IS GENERATED (`kin/sched.kin`): `waiter_at`,
    // `free_waiter` and `outstanding_waiters` are methods on `Rt` from there,
    // and the call sites below are unchanged.
    //
    // `outstanding_waiters` GAINED A NIL-SCHEDULER GUARD in the move. Both
    // ports had one and this copy did not, so nothing could say whether that
    // was a missing guard or a dead branch; zero is what is true of a runtime
    // that never made a scheduler, and the alternative is reaching into nil to
    // find out.

    // --- parking -----------------------------------------------------------

    // `wake_on` AND `wake_waiter` ARE GENERATED (`kin/sched.kin`) and are
    // methods on `Rt` from there; the call sites below are unchanged.
    //
    // `wake_on` wakes ALL threads parked on a port, not the first: a woken
    // thread re-executes the operation it parked on and parks again if the
    // queue is still full or empty, which is what makes the ring's `EMPTY`
    // handshake safe to wake early. And `wake_waiter` frees the waiter AFTER
    // clearing the thread's park -- `free_waiter` refuses a waiter whose
    // thread is already nil, so the other order leaks a slot per wake.

    /// Wake everything parked on `p` with an error instead of a value. Used
    /// when the peer end has been collected: that receive can never succeed,
    /// and a hang is the worst possible way to say so.
    pub(crate) fn fail_waiters_on(&mut self, p: Value, msg: &str) {
        let base = self.mark();
        let pi = self.push(p);
        let ws = self.waiters();
        let wsi = self.push(ws);
        let n = self.vec_count(self.r(wsi));
        for i in 0..n {
            let w = self.vec_nth(self.r(wsi), i, NIL);
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

    /// Check a batch of children that was collected into a HOST vector.
    ///
    /// **ROOTED BEFORE ANY OF THEM IS CHECKED, and that is the whole point.**
    /// `map_for_each` and `set_for_each` cannot call back into
    /// `check_sendable_at` -- the borrow is already held -- so the children are
    /// gathered into a `Vec<Value>` first. That vector is HOST memory and no
    /// root at all, and `check_sendable_at` allocates: it interns the strings
    /// it walks past. So checking the first child moved every one still sitting
    /// in the vector, and the second push handed the collector an address that
    /// had already been forwarded.
    ///
    /// The sequential branch never had this because it re-derives `first` from
    /// a rooted seq each time round. This does the same thing the only way a
    /// batch can: push them all while they are still fresh -- nothing allocates
    /// between the gather and the pushes -- and then read each one BACK out of
    /// the shadow stack, which the collector updates.
    ///
    /// Caught by `test/document.clj`'s stale-pointer row, which exists because
    /// `port_send` had the identical bug one level up and was fixed there.
    fn check_each(
        &mut self,
        items: &[Value],
        depth: u32,
        carry: u8,
    ) -> Result<(), alloc::string::String> {
        let ibase = self.mark();
        for it in items {
            self.push(*it);
        }
        let mut out = Ok(());
        for n in 0..items.len() {
            let child = self.r(ibase + n);
            out = self.check_sendable_at(child, depth + 1, carry);
            if out.is_err() {
                break;
            }
        }
        self.pop_to(ibase);
        out
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
            // `DECISIONS.md#host-abi` refused this outright -- "an endpoint cannot
            // be delegated at run time" -- and `structured-ports` REVERSES it, which is the
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
            // An opaque value is identity and nothing else (DECISIONS.md#opaque-values).
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
            // reason: `DECISIONS.md#host-abi` says an endpoint is not transferable
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
                    out = self.check_each(&items, depth, carry);
                } else if self.is_set(self.r(vi)) {
                    let mut items: Vec<Value> = Vec::new();
                    let mut st = &mut items;
                    self.set_for_each(self.r(vi), &mut st, &mut |_rt, k, st| st.push(k));
                    out = self.check_each(&items, depth, carry);
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
/// GENERATED (`kin/settle.kin`). The end of every turn a thread takes, and
/// the one place that decides which of three things just happened. It was
/// written three times, and the copies had already drifted on the park
/// branch: both ports cleared the PARK sentinel here and this one did not,
/// because `parked` in the VM had already cleared it. The generated body
/// keeps the clear -- free where it is redundant, load-bearing where it is
/// not -- so the three no longer disagree about a field they all write.
pub fn settle(rt: &mut Rt, result: Value) {
    rt.settle_thread(result);
}

// THE SCHEDULER'S PREDICATES ARE GENERATED (`kin/sched.kin`) and are methods
// on `Rt`: `sched_pick`, `sched_pending_events`, `sched_needs_host` and
// `sched_all_settled`. The four free-function wrappers that used to stand here
// are gone with the hand-written `drive` that was their only caller -- a
// wrapper whose whole body is one delegating call is drift waiting to happen,
// and the vocabulary already names what `drive` calls.

/// Enter a NEW thread's closure, with the interpreter already emptied.
///
/// `TH_ARGS` IS NOT PASSED, and that is not an omission: it is only ever
/// written NIL, on both paths that create a thread. It is vestigial, from
/// before a call carried its arguments on the thread object -- the control
/// plane is flint now and `flint.system/serve` applies the arguments itself
/// (`DECISIONS.md#bridges-are-the-only-door`). Neither port has the slot at
/// all, which is how it was noticed.
pub fn run_entry(rt: &mut Rt, f: Value) -> Value {
    rt.run_thread_entry(f, NIL)
}

pub fn run_one(rt: &mut Rt, i: u32) {
    // GENERATED (`kin/sched.kin`). The last part of the scheduler that was
    // written three times, and the one whose cost is BILLED TO THE PROGRAM:
    // gas is charged per interpreter step, so three implementations of the
    // resume path are three prices for running the same code.
    rt.sched_run_one(i);
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
/// The answer the thread a host started left behind: its value, or its error
/// put back on `rt.thrown` where the boundary looks for it.
/// GENERATED (`kin/mainanswer.kin`). What a settled program left behind.
/// The two ports returned `TH_RESULT` unconditionally, so a program that
/// THREW came back as one that RETURNED AN EXCEPTION OBJECT, with a success
/// status. One slot holding two different things, and only the status saying
/// which -- see the drivers file.
pub fn settled_answer(rt: &mut Rt) -> Value {
    rt.main_answer()
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

/// NAME the deadlock rather than hang on it.
///
/// Lifted out of `drive` when the loop became generated (`kin/sched.kin`) and
/// left HAND-WRITTEN ON PURPOSE: it builds a host string naming each stuck
/// thread and what it is waiting on, and a diagnostic message is the wrong
/// thing to force through a generator. One vocabulary entry, three
/// implementations, and nothing about the ORDER -- which is the part that was
/// worth generating -- lives here.
pub fn report_deadlock(rt: &mut Rt) {
    // Nothing runnable, nothing the host can help with: the
    // remaining threads are waiting on each other.
    let s = rt.sched();
    let ts = rt.slot(s, SC_THREADS);
    let n = rt.vec_count(ts);
    let mut stuck = 0;
    let mut detail = alloc::string::String::new();
    for i in 0..n {
        let th = rt.vec_nth(ts, i, NIL);
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

/// THE SCHEDULER LOOP. GENERATED (`kin/sched.kin`), and the ORDER is why.
///
/// Run whatever is runnable; when nothing is, decide what that means. The
/// three runtimes answered that in a different ORDER for long enough to
/// matter: asking "has the program finished?" before "does anything need the
/// host?" closes the system port out from under a call that has just parked
/// on an `open`, and the grant then arrives for a port that is already gone
/// (`DECISIONS.md#the-codec-is-guest-code`). Native was fixed and both ports
/// kept the superseded order, comment and all, because an order written three
/// times is three orders.
pub fn drive(rt: &mut Rt) -> Value {
    rt.sched_drive()
}


impl Rt {
    /// Start a thread's entry closure with the frame stack empty.
    /// Start a thread's entry closure with the frame stack empty.
    ///
    /// `args` is nil for a `spawn`, which takes a function of no arguments, and
    /// a vector for a CALL the host asked for (`DECISIONS.md#structured-ports`).
    fn run_thread_entry(&mut self, f: Value, args: Value) -> Value {
        if !self.is_callable(f) {
            self.throw_str("ClassCastException", "spawn wants a function of no arguments");
            return NIL;
        }
        // NOTHING IS PUSHED AS A ROOT HERE. The root stack and the value stack
        // are the same stack, and `enter` takes the callee's frame from
        // `callee_at` upward -- so a root pushed first would be read as an
        // argument, and the extra entries left behind would underflow `vpop`.
        // `run_one` empties the stack before calling this for exactly that
        // reason.
        //
        // `f` and `args` are therefore Rust locals across `vec_nth`, which
        // walks a trie and does not allocate. The original code held `f` the
        // same way.
        let n = if self.is_vector(args) { self.vec_count(args) } else { 0 };
        let callee_at = self.roots.stack_top;
        self.vpush(f);
        for k in 0..n {
            let a = self.vec_nth(args, k, NIL);
            self.vpush(a);
        }
        if !self.enter(f, callee_at, n as usize) {
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
    /// GENERATED, from `kin/schedlists.kin`. The record is read POSITIONALLY
    /// by the host's drain, so its field order is a contract with no error to
    /// raise when it is wrong.
    pub(crate) fn push_event(&mut self, kind: i64, a: i64, b: i64, payload: Value) {
        self.push_event_at(kind, a, b, payload);
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
        // A WRITER IS AN ENCODING, NOT A VALUE, so it is not walked: there is
        // nothing in it to check, and `check_sendable` would refuse the type it
        // does not know (`DECISIONS.md#the-codec-is-guest-code`).
        let writer = self.is_writer(self.r(vi));
        if writer && !crosses_a_heap(kind) {
            self.pop_to(base);
            return self.throw_str(
                "IllegalArgumentException",
                "send: a wire writer is an encoding, and a channel carries values -- \
                 send the value itself, or send this on a bridge",
            );
        }
        let carry = if crosses_a_heap(kind) { CARRY_CROSSING } else { CARRY_LOCAL };
        if !writer {
            if let Err(e) = self.check_sendable_via(self.r(vi), carry) {
                self.pop_to(base);
                return self.throw_str("IllegalArgumentException", &e);
            }
        }
        if crosses_a_heap(kind) {
            // THE ENCODING ARRIVES ALREADY WRITTEN. It is the GUEST that wrote
            // it (`DECISIONS.md#the-codec-is-guest-code`): `flint.port/send`
            // builds a writer, fills it and hands it over, and the runtime
            // carries the bytes without ever holding an encoder.
            //
            // That is the direction the safety rule runs in. A DECODER
            // reachable from the guest would be an encoder read backwards, and
            // since `K_PORT` and `K_SENTINEL` carry their identity inline as
            // integers a guest can write, such a guest could mint any host id
            // it liked. The guest writes; only `wirescan` mints, and only for
            // bytes that actually arrived over a bridge.
            if !writer {
                self.pop_to(base);
                return self.throw_str(
                    "ClassCastException",
                    "send: a bridge carries an encoding -- use `flint.port/send`, which \
                     writes one, rather than the builtin with a bare value",
                );
            }
            // STRUCTURALLY COMPLETE, OR IT DOES NOT GO: `wire-vec 3` with two
            // values emitted is a message no reader can read, and shipping it
            // reports the fault at the end that did not commit it.
            if !self.wire_complete(self.r(vi)) {
                self.pop_to(base);
                return self.throw_str(
                    "IllegalStateException",
                    "send: this encoding is unfinished -- a container was opened and \
                     not filled",
                );
            }
            let wv = self.r(vi);
            let enc = self.wire_finish(wv);
            if enc.is_nil() {
                self.pop_to(base);
                return self.throw_str("IllegalStateException", "send: this encoding is empty");
            }
            self.set_r(vi, enc);
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
            // THE SAME PREDICATE THE HOST PATH USES, generated from
            // `kin/portbytes.kin`. The two paths park where the other refuses
            // and claim atomically where the other does not; what they must
            // never differ in is WHICH MESSAGES FIT.
            if !self.fits_in_budget(queued, len, cap) {
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

    /// Receive on a BRIDGE as a live reader, for a guest that decodes itself.
    ///
    /// Not a second way to receive: `flint.port/receive` uses this on a bridge
    /// and `port-receive` on a channel, because a channel carries VALUES and
    /// has no encoding to read.
    ///
    /// NIL FOR END OF STREAM, exactly as `port_receive` answers it -- and a
    /// non-bytes value passes straight through, so a channel reaching here by
    /// mistake answers what it holds rather than a reader over nothing.
    ///
    /// THE READER MAY MINT. These are bytes that arrived over a bridge, which
    /// is the whole of what `RD_LIVE` distinguishes: a reader built over bytes
    /// the guest already had must not be able to conjure a port
    /// (`DECISIONS.md#the-codec-is-guest-code`).
    pub fn port_receive_reader(&mut self, p: Value) -> Value {
        let v = self.port_receive(p);
        if v.is_nil() || !self.is_bytes(v) {
            return v;
        }
        let base = self.mark();
        let vi = self.push(v);
        let vv = self.r(vi);
        let out = self.wire_reader(vv, true);
        self.pop_to(base);
        out
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
                // A bridge queues `[len value]`, and `len` is the number
                // `host_deliver` actually CHARGED -- the length of the encoded
                // message, which is what bounds the host's queue.
                //
                // It used to be recomputed from the value, `s_bytes` on
                // whatever came out of the ring. That was wrong twice. It
                // refunded the string's length where the encoded length had
                // been charged, so the bound drifted every message; and once a
                // bridge carried VALUES rather than bytes it walked a keyword
                // as a string and read off the end of the heap. `abcd` arriving
                // on a port was a segfault.
                let item = self.r(vi);
                let n = fx(self.vec_nth(item, 0, NIL));
                let v = self.vec_nth(item, 1, NIL);
                self.set_r(vi, v);
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
        // WHAT A DRAINED PORT DOES IS GENERATED, from `kin/portdrain.kin`,
        // and it makes the state changes that go with its answer. Only the
        // THROW stays here: its message is a host string, and kin has no
        // string to carry one -- the same line `report_deadlock` draws.
        match self.receive_drained(self.r(pi)) {
            1 => {
                self.pop_to(base);
                return NIL;
            }
            2 => {
                self.pop_to(base);
                return self.throw_str("IllegalStateException", "receive: the other end of this port is gone, so this can never complete");
            }
            _ => {}
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
    /// A capability is an OPAQUE VALUE (`DECISIONS.md#opaque-values`) and nothing more.
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
    /// (`DECISIONS.md#ports-are-the-hosts`).
    ///
    /// A sandbox cannot make a bridge. It used to: `open` allocated a PAIR of
    /// ends here, kept one and offered the other up as "the host's", which made
    /// the confined thing the author of its own authority and gave the port an
    /// id that meant nothing in any other sandbox. Now nothing is allocated
    /// until the host answers, and what comes back is a handle on a port the
    /// host already owns.
    ///
    /// Combined with `structured-ports`'s rule -- flint is given no way to turn an integer
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
        // value crosses, and anything an opaque value carries survives the trip
        // because the format already knew how to write one down.
        //
        // ENCODED BY THE GUEST (`DECISIONS.md#the-codec-is-guest-code`).
        // `flint.port/open` writes `[name ...args]` with `flint.wire` and hands
        // the writer in; this checks it is finished and takes its bytes. The
        // runtime holds no encoder, which is the direction the safety rule runs
        // in -- a decoder reachable from the guest would be an encoder read
        // backwards, and `K_PORT`/`K_SENTINEL` carry their identity inline.
        if !self.wire_complete(self.r(ai)) {
            self.pop_to(base);
            return self.throw_str(
                "IllegalStateException",
                "open: this encoding is unfinished -- a container was opened and not filled",
            );
        }
        let av = self.r(ai);
        let payload = self.wire_finish(av);
        if payload.is_nil() {
            self.pop_to(base);
            return self.throw_str(
                "IllegalStateException",
                "open: this encoding has already been sent",
            );
        }
        let pi = self.push(payload);
        let sys_id = fx(self.slot(self.r(si), PT_ID));
        let pv = self.r(pi);
        self.push_event(EV_OPEN, token, sys_id, pv);
        let target = self.r(si);
        self.pop_to(base);
        self.park(target)
    }

    /// Ask the host for something, and get a VALUE back.
    ///
    /// `port_open` generalised (`DECISIONS.md#workspace-capabilities` step 7). The two differ in
    /// exactly one place -- what the answer may be -- and that difference is
    /// load-bearing, so they are two functions rather than one with a flag: a
    /// port is granted BY ID through `host_grant` and never encoded, because
    /// encoding a handle the host is lending is the one thing the codec must
    /// not do. Everything else here is the same park, the same waiter, the same
    /// system port.
    ///
    /// The guest cannot construct a request it was not compiled to make: the
    /// capability guard on the wrapper is a compile-time construct
    /// (`DECISIONS.md#workspace-capabilities` step 8), and this primitive is the thing it
    /// guards. What the host does with the request is the host's business, and
    /// it may refuse -- which is a normal outcome and arrives catchably.
    pub fn host_request(&mut self, what: Value, args: Value) -> Value {
        self.ensure_sched();
        let base = self.mark();
        let ni = self.push(what);
        let ai = self.push(args);
        let th = self.current_thread();
        let ti = self.push(th);
        let pending = self.slot(self.r(ti), TH_PENDING);
        if !pending.is_nil() {
            // Second time round: the host has answered.
            self.set(self.r(ti), TH_PENDING, NIL);
            // A one-element VECTOR is an answer, holding whatever the host
            // sent. Anything else is a refusal.
            //
            // The wrapper is not decoration. An answer may be any value at all,
            // NIL included, so "answered" cannot be read off the value's type
            // the way `port_open` reads it off `is_port` -- a host answering nil
            // and a host refusing would be the same bits.
            if self.is_vector(pending) {
                // THE ANSWER'S BYTES, AS A LIVE READER. The guest decodes it --
                // `flint.host/request` calls `flint.wire/read-from`. LIVE
                // because these bytes came from the host across the boundary,
                // which is the one place minting is allowed
                // (`DECISIONS.md#the-codec-is-guest-code`).
                let b = if self.vec_count(pending) > 0 {
                    self.vec_nth(pending, 0, NIL)
                } else {
                    NIL
                };
                let bi = self.push(b);
                let bv = self.r(bi);
                let out = self.wire_reader(bv, true);
                self.pop_to(base);
                return out;
            }
            let mut b = crate::rt::sbuf();
            let n: alloc::string::String = self.as_str(self.r(ni), &mut b).unwrap_or("?").into();
            self.pop_to(base);
            let msg = alloc::format!("the host refused the request {n:?}");
            return self.throw_str("SecurityException", &msg);
        }
        // NO SYSTEM PORT, NO ASKING -- the same honesty as `port_open`: pushing
        // an event nothing will drain parks the thread for ever and reads as a
        // hang rather than as a refusal.
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
        let target = self.r(si);
        let token = self.new_waiter(WK_REQUEST, target);
        let t = self.r(ti);
        self.set(t, TH_TOKEN, Value::fixnum(token));
        let t = self.r(ti);
        self.set(t, TH_PENDING, Value::fixnum(0));
        // `[what & args]`, ENCODED BY THE GUEST -- the same payload shape
        // `port_open` sends, so a host that routes one routes the other, and
        // written the same way it is (`DECISIONS.md#the-codec-is-guest-code`).
        if !self.wire_complete(self.r(ai)) {
            self.pop_to(base);
            return self.throw_str(
                "IllegalStateException",
                "request: this encoding is unfinished -- a container was opened and not filled",
            );
        }
        let av = self.r(ai);
        let payload = self.wire_finish(av);
        if payload.is_nil() {
            self.pop_to(base);
            return self.throw_str(
                "IllegalStateException",
                "request: this encoding has already been sent",
            );
        }
        let pi = self.push(payload);
        let sys_id = fx(self.slot(self.r(si), PT_ID));
        let pv = self.r(pi);
        self.push_event(EV_REQUEST, token, sys_id, pv);
        let target = self.r(si);
        self.pop_to(base);
        self.park(target)
    }

    /// Walk an encoding and MINT EVERY PORT IN IT, answering them as a vector.
    ///
    /// NIL when the encoding cannot be read, which is how a malformed message
    /// stays refused at the boundary rather than arriving as something else.
    /// The WALK is `kin/wirescan.kin`; this is the `&[u8]`-shaped door to it,
    /// and the door is what stays per-runtime.
    ///
    /// MINTING HAS TO HAPPEN HERE even though decoding does not: a port must
    /// exist before anything can be delivered on it, and a host binds a port
    /// and calls on it without pumping in between, deliberately.
    fn scan_ports(&mut self, bytes: &[u8]) -> Value {
        let base = self.mark();
        let b = self.new_bytes(bytes);
        let bi = self.push(b);
        // NOT LIVE: this reader is a cursor for the walk and is never handed to
        // a guest, so it must not be able to mint on the guest's behalf.
        let bv = self.r(bi);
        let rd = self.wire_reader(bv, false);
        let ri = self.push(rd);
        let e = self.empty_vec();
        let ai = self.push(e);
        let rv = self.r(ri);
        let ok = self.wire_scan_at(rv, ai, 0);
        // TRAILING BYTES ARE A MALFORMED MESSAGE, not a short one: a walk that
        // succeeded but left something unread has not read what was sent.
        let rv = self.r(ri);
        if !ok || self.wire_left(rv) != 0 {
            self.pop_to(base);
            return NIL;
        }
        let out = self.r(ai);
        self.pop_to(base);
        out
    }

    /// The host's answer to an `EV_REQUEST`, as encoded bytes.
    ///
    /// NOT decoded here. The bytes reach the parked thread as bytes and the
    /// guest reads them (`DECISIONS.md#the-codec-is-guest-code`), which is the
    /// mirror of `send` taking a writer rather than a value.
    pub fn host_answer(&mut self, token: i64, bytes: &[u8]) -> bool {
        let w = self.waiter_at(token);
        if w.is_nil() {
            return false;
        }
        let base = self.mark();
        let wi = self.push(w);
        // NOT DECODED HERE (`DECISIONS.md#the-codec-is-guest-code`). The bytes
        // reach the parked thread as bytes and the GUEST reads them. The
        // WRAPPER stays, because an answer may be any value at all, nil
        // included: "answered" is read off the wrapper, never off the value.
        let v = self.new_bytes(bytes);
        let vi = self.push(v);
        // Wrapped, so that a host answering nil is distinguishable from a host
        // refusing. See `host_request`.
        let one = self.empty_vec();
        let oi = self.push(one);
        let nv = self.vec_conj(self.r(oi), self.r(vi));
        self.set_r(oi, nv);
        let th = self.slot(self.r(wi), W_THREAD);
        if !th.is_nil() {
            let ov = self.r(oi);
            self.set(th, TH_PENDING, ov);
        }
        let wv = self.r(wi);
        self.wake_waiter(wv);
        self.pop_to(base);
        true
    }

    /// Wait for `t` to finish. Parks rather than spinning: a spinning joiner
    /// would always be runnable, so the scheduler would never get the chance to
    /// hand control back to the host and a thread waiting on a host port would
    /// never be answered.
    /// GENERATED (`kin/threadjoin.kin`). Wait for `t` and answer its value.
    /// The self-join refusal was HERE AND NOWHERE ELSE: both ports parked and
    /// let the deadlock reporter catch it a turn later, naming the symptom
    /// rather than the mistake. All three refuse it by name now.
    pub fn thread_join(&mut self, t: Value) -> Value {
        self.join_at(t)
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
    /// GENERATED, from `kin/reapports.kin`. One line, as `reap_ports` and
    /// `close_all_bridges` are: what follows from an end closing is a DECISION
    /// -- which events, in which order, and that the PEER goes half-closed
    /// rather than closed -- and it was written three times.
    pub(crate) fn close_side_effects(&mut self, p: Value) {
        self.close_effects(p);
    }

    /// Drop `id` from the held list, so the sweep does not release it twice.
    /// GENERATED, from `kin/schedlists.kin`.
    pub(crate) fn forget_bridge(&mut self, id: i64) {
        self.forget_bridge_at(id);
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
    /// GENERATED, from `kin/porthost.kin`.
    pub fn host_continue(&mut self, token: i64, ok: bool) -> bool {
        self.host_continue_at(token, ok)
    }

    /// Grant an open: hand the waiting thread a handle on the host's port
    /// `host_port_id` (`DECISIONS.md#ports-are-the-hosts`).
    ///
    /// The id is the HOST's. It is the same id in every sandbox that holds this
    /// port, which is what makes a handle sendable between two of them at all,
    /// and it is the id the retain and release events name.
    ///
    /// If this sandbox already holds that port, the SAME handle comes back and
    /// no reference is taken -- granting a port twice is not two holders.
    /// GENERATED, from `kin/porthost.kin`.
    pub fn host_grant(&mut self, token: i64, host_port_id: i64) -> bool {
        self.host_grant_at(token, host_port_id)
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
        // bookkeeping on a second object (`DECISIONS.md#ports-are-the-hosts`).
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
        let len = bytes.len() as i64;
        let pv = self.r(pi);
        if !self.claim_bytes(pv, len) {
            self.pop_to(base);
            return false;
        }
        // From here every refusal has to hand the bytes back, or a message the
        // guest never saw goes on counting against its bound for ever.
        macro_rules! give_back {
            () => {{
                // GENERATED, from `kin/portbytes.kin`.
                let pv = self.r(pi);
                self.give_back(pv, len);
            }};
        }
        // SCANNED, NOT DECODED (`DECISIONS.md#the-codec-is-guest-code`). The
        // bytes go into the queue AS BYTES and the guest decodes them; what
        // must still happen here is the MINTING, because a port has to exist
        // before anything can be delivered on it.
        //
        // THE SYSTEM PORT IS NOT SPECIAL HERE ANY MORE. It used to be routed
        // out at this point into a runtime-side `system_message`; the control
        // plane is flint code now (`DECISIONS.md#bridges-are-the-only-door`),
        // so a control message is delivered like any other and `flint.system/serve`
        // is what reads it.
        let ports = self.scan_ports(bytes);
        if ports.is_nil() {
            // Refused rather than delivered as anything else: a message the
            // format cannot read is the host's error, and turning it into a
            // string here would hand the guest something that silently was not
            // what was sent.
            give_back!();
            self.pop_to(base);
            return false;
        }
        let pri = self.push(ports);
        let v = self.new_bytes(bytes);
        let vi = self.push(v);
        // `[len bytes ports]`. `len` because the refund has to be the number
        // that was CHARGED and nothing about the bytes says what that was;
        // `ports` because THE BRIDGE OWNS THE REFERENCE while the message is in
        // flight, and the intern table is weak on purpose. A mark of its own,
        // since `pop_to(vi)` would drop the slot being written.
        {
            let m = self.mark();
            let e = self.empty_vec();
            let ei = self.push(e);
            let c1 = self.vec_conj(self.r(ei), Value::fixnum(len));
            self.set_r(ei, c1);
            let vv = self.r(vi);
            let c2 = self.vec_conj(self.r(ei), vv);
            self.set_r(ei, c2);
            let pv = self.r(pri);
            let c3 = self.vec_conj(self.r(ei), pv);
            self.set_r(vi, c3);
            self.pop_to(m);
        }
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
    /// GENERATED, from `kin/porthost.kin`.
    pub fn host_close_port(&mut self, host_port_id: i64) {
        self.host_close_at(host_port_id);
    }

    /// This end's state, resolved rather than remembered.
    ///
    /// A port whose peer has been collected is orphaned whether or not the
    /// scheduler has got round to noticing, and a query that answered `:open`
    /// until then would be a notification wearing a query's clothes.
    /// GENERATED, from `kin/porthost.kin`.
    pub fn port_state_now(&mut self, p: Value) -> i64 {
        self.port_state_now_at(p)
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
            let e = self.vec_nth(self.r(ei), i, NIL);
            let kind = fx(self.vec_nth(e, 0, NIL));
            let a = fx(self.vec_nth(e, 1, NIL));
            let b = fx(self.vec_nth(e, 2, NIL));
            let payload = self.vec_nth(e, 3, NIL);
            let off = out.len() as u32;
            let plen = if self.is_bytes(payload) {
                let n = self.b_count(payload);
                let bs = self.b_to_vec(payload);
                out.extend_from_slice(&bs);
                n
            } else if self.is_vector(payload) {
                let n = self.vec_count(payload);
                for k in 0..n {
                    let b = self.vec_nth(payload, k, NIL);
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
    /// GENERATED (`kin/reapports.kin`). A collection is a RELEASE for a bridge
    /// and an ORPHANING for a channel, and this is the only place either is
    /// noticed -- which is why it was worth writing once rather than three
    /// times. The port that made it a candidate is recorded there: it used to
    /// rebuild both lists on every drive iteration whether or not anything had
    /// died, and the allocation is billed, so the scheduler's own bookkeeping
    /// was charged to the program.
    pub fn reap_ports(&mut self) {
        self.reap_all();
    }

    /// The peer of a port that has already been collected. Recorded separately
    /// because the object is gone by the time we notice.
    pub(crate) fn peer_id_of_dead(&mut self, id: i64) -> i64 {
        let s = self.sched();
        let pairs = self.slot(s, SC_PAIRS);
        let n = self.vec_count(pairs);
        for i in 0..n {
            let e = self.vec_nth(pairs, i, NIL);
            if fx(self.vec_nth(e, 0, NIL)) == id {
                return fx(self.vec_nth(e, 1, NIL));
            }
        }
        -1
    }

    // Program exit: close and release every bridge, so a host is never left
    // holding a reference for a sandbox that has finished, and leave the events
    // for the final drain.
    //
    // GENERATED, from `kin/reapports.kin`. One line, as `reap_ports` is: the
    // body is `close_bridges` next door, and this name stays because it is the
    // vocabulary word `sched-drive` calls -- a kin function of the same name
    // would shadow the word and leave the scheduler's drivers unable to stub it.
    //
    // Three hand-written copies before this, and they did not agree on the
    // `vec_nth` default: native passed `NIL` and both ports passed `NOT_FOUND`.
    // Unreachable, `k` being bounded by the count, which is exactly the kind of
    // difference that survives.
    pub fn close_all_bridges(&mut self) {
        self.close_bridges();
    }

}
