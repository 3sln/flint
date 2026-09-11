//! Several executors inside one sandbox (`DECISIONS.md#drivers`).
//!
//! One heap, K threads on it. Everything here exists to make three things true
//! at once: only one thread mutates the heap's structure at a time, no thread
//! is running when the collector moves an object, and no thread waits forever
//! for either.
//!
//! ## Where a thread may stop
//!
//! Not anywhere. A collection MOVES objects, so a thread that stops while
//! holding a `Value` in a Rust local resumes holding a stale pointer -- the
//! same rule as `doc/HANDOFF.md`, except that under one thread it only applied
//! around your OWN allocations and now applies around everyone's.
//!
//! So the only safepoint is the interpreter's checkpoint, between two
//! bytecode instructions, where `ip` has been written back and every live
//! value is on the value stack by construction. A thread inside a native does
//! not poll and cannot stop; the collector waits for it. That is a latency
//! cost and not a correctness one, and it is the reason the poll lives exactly
//! where it does.
//!
//! ## Why a spin rather than a condition variable
//!
//! The runtime is `#![no_std]` -- it compiles to wasm, where there is no
//! `Mutex` and no `Condvar` -- and a safepoint is short: a minor collection is
//! sub-millisecond and the executors are few. Atomics are in `core`; blocking
//! primitives are not.

use crate::gc::ExecRoots;
use core::sync::atomic::{AtomicPtr, AtomicU32, AtomicU64, Ordering};

/// How many executors one sandbox may have.
///
/// A fixed array rather than a growable one because it is written from several
/// threads: registering an executor while the collector reads the list has to
/// be a single atomic store, and a `Vec` reallocating underneath that reader
/// is exactly the race this exists to avoid.
pub const MAX_EXECUTORS: usize = 64;

/// How many instructions an executor counts locally before telling anyone.
///
/// The whole reason gas is batched: a shared counter incremented once per
/// instruction would put an atomic read-modify-write on the interpreter's
/// hottest line and have every thread fighting for one cache line, which costs
/// more than the work it is counting.
///
/// The price is that a limit stops the program a little late -- by at most
/// this times the number of executors (`DECISIONS.md#resource-limits`).
pub const GAS_BATCH: u64 = 4096;

pub struct Parallel {
    /// Non-zero while an executor is staging a collection and wants everyone
    /// else stopped.
    stop: AtomicU32,
    /// Executors registered in this sandbox, including the one that made it.
    live: AtomicU32,
    /// Executors currently stopped at the safepoint.
    parked: AtomicU32,
    /// Executors currently RUNNING, and therefore able to reach a safepoint.
    ///
    /// The distinction between this and `live` is the whole of why a first
    /// attempt at this deadlocked. A collector must wait only for threads that
    /// can actually stop: an executor that has finished its work, or is
    /// sitting in host code, polls nothing, and waiting for it is waiting
    /// forever. It is registered -- its roots still have to be scanned -- but
    /// it is not running.
    ///
    /// This is the same split a JVM makes between a thread "in Java" and one
    /// "in native", and for the same reason.
    active: AtomicU32,
    /// The allocation lock: 0 free, 1 held. Only one thread may be inside the
    /// collector's bookkeeping at a time.
    alloc: AtomicU32,
    /// Each executor's roots, by address, registered once when it is created.
    ///
    /// Registered rather than published at each park because an executor's
    /// roots do not move -- it lives in a box for exactly this reason -- and a
    /// list built once cannot race with a list being built.
    slots: [AtomicPtr<ExecRoots>; MAX_EXECUTORS],
    /// One lock per intern table (strings, keywords, symbols, ports).
    ///
    /// One EACH rather than one for all four because it costs nothing, and not
    /// SHARDED because sharding would optimise something that is not
    /// happening: on the most string-heavy workload we have -- flint compiling
    /// construe -- the tables are probed 18 247 times across 4.25 seconds, a
    /// duty cycle of about 0.04%. 26% of `Rt::string` calls reach a table, but
    /// `string` itself is not hot enough for that to matter.
    interns: [AtomicU32; 4],
    /// Gas, flushed from per-executor batches.
    spent: AtomicU64,
    limit: AtomicU64,
}

impl Default for Parallel {
    fn default() -> Parallel {
        Parallel::new()
    }
}

impl Parallel {
    pub fn new() -> Parallel {
        Parallel {
            stop: AtomicU32::new(0),
            live: AtomicU32::new(0),
            parked: AtomicU32::new(0),
            active: AtomicU32::new(0),
            alloc: AtomicU32::new(0),
            interns: [
                AtomicU32::new(0),
                AtomicU32::new(0),
                AtomicU32::new(0),
                AtomicU32::new(0),
            ],
            slots: [const { AtomicPtr::new(core::ptr::null_mut()) }; MAX_EXECUTORS],
            spent: AtomicU64::new(0),
            limit: AtomicU64::new(0),
        }
    }

    /// Claim a slot for a new executor. `None` when the sandbox is full.
    pub fn register(&self, roots: *mut ExecRoots) -> Option<usize> {
        for (i, slot) in self.slots.iter().enumerate() {
            if slot
                .compare_exchange(core::ptr::null_mut(), roots, Ordering::AcqRel, Ordering::Relaxed)
                .is_ok()
            {
                self.live.fetch_add(1, Ordering::AcqRel);
                return Some(i);
            }
        }
        None
    }

    pub fn deregister(&self, id: usize) {
        if self.slots[id].swap(core::ptr::null_mut(), Ordering::AcqRel) != core::ptr::null_mut() {
            self.live.fetch_sub(1, Ordering::AcqRel);
        }
    }

    pub fn executors(&self) -> u32 {
        self.live.load(Ordering::Acquire)
    }

    /// Begin running guest code: from here this thread polls and can be
    /// stopped.
    ///
    /// Blocks while a collection is being staged, because joining the set of
    /// threads a collector is waiting for AFTER it counted them would leave it
    /// waiting for one more than will ever arrive.
    pub fn enter(&self) {
        loop {
            while self.stop.load(Ordering::Acquire) != 0 {
                core::hint::spin_loop();
            }
            self.active.fetch_add(1, Ordering::AcqRel);
            if self.stop.load(Ordering::Acquire) == 0 {
                return;
            }
            // A stop was staged between the check and the increment. Back out
            // and wait: the stager may already have read the old count.
            self.active.fetch_sub(1, Ordering::AcqRel);
        }
    }

    /// Stop running guest code. Nothing waits for this thread until it enters
    /// again.
    pub fn leave(&self) {
        self.active.fetch_sub(1, Ordering::AcqRel);
    }

    pub fn running(&self) -> u32 {
        self.active.load(Ordering::Acquire)
    }

    /// Is someone waiting for this thread to stop?
    #[inline(always)]
    pub fn stop_requested(&self) -> bool {
        // Relaxed: this is a hint that costs a plain load. Missing it by one
        // instruction only delays the safepoint by one instruction, and the
        // acquire that matters happens in `park` below.
        self.stop.load(Ordering::Relaxed) != 0
    }

    /// Stop here until the collection is over.
    ///
    /// Called only from a safepoint -- between two bytecode instructions, with
    /// every live value on the value stack. See the module note.
    pub fn park(&self) {
        self.parked.fetch_add(1, Ordering::AcqRel);
        while self.stop.load(Ordering::Acquire) != 0 {
            core::hint::spin_loop();
        }
        self.parked.fetch_sub(1, Ordering::AcqRel);
    }

    /// Take the allocation lock, stopping at safepoints while waiting.
    ///
    /// Polling WHILE spinning is not politeness, it is the deadlock fix: a
    /// thread that spun here without parking would never reach a safepoint,
    /// and the executor staging a collection would wait for it forever.
    pub fn lock_alloc(&self) {
        loop {
            if self
                .alloc
                .compare_exchange_weak(0, 1, Ordering::Acquire, Ordering::Relaxed)
                .is_ok()
            {
                return;
            }
            if self.stop_requested() {
                self.park();
            }
            core::hint::spin_loop();
        }
    }

    pub fn unlock_alloc(&self) {
        self.alloc.store(0, Ordering::Release);
    }

    /// Take an intern table's lock, stopping at safepoints while waiting.
    ///
    /// **The caller must have every live value rooted**, because this can
    /// park. That is not a burden in practice: the only two places it is taken
    /// are a bare probe, where nothing is live, and a re-probe after a fresh
    /// object has been pushed on the root stack.
    ///
    /// The invariant that makes the collector's life simple: this lock is only
    /// ever held across a hash probe, which does not allocate and so cannot
    /// reach a safepoint. **A parked thread therefore never holds an intern
    /// lock**, and the collector can walk and rewrite the tables during a stop
    /// without taking anything.
    pub fn lock_intern(&self, table: usize) {
        loop {
            if self.interns[table]
                .compare_exchange_weak(0, 1, Ordering::Acquire, Ordering::Relaxed)
                .is_ok()
            {
                return;
            }
            if self.stop_requested() {
                self.park();
            }
            core::hint::spin_loop();
        }
    }

    pub fn unlock_intern(&self, table: usize) {
        self.interns[table].store(0, Ordering::Release);
    }

    /// Ask every other executor to stop, and wait until they have.
    ///
    /// The caller holds the allocation lock, so no one else can be staging a
    /// collection at the same time.
    /// Spin until `f` is true, or panic saying what everyone was doing.
    ///
    /// A deadlock here used to be a hang, and a hang tells you nothing: you
    /// cannot tell "waiting for a thread that will never park" from "slow".
    /// The bound is enormous -- far longer than any real safepoint -- so it
    /// only fires on a genuine one, and then it names the state.
    fn spin_until(&self, what: &str, mut f: impl FnMut() -> bool) {
        let mut spins: u64 = 0;
        while !f() {
            spins += 1;
            if spins > 2_000_000_000 {
                panic!(
                    "flint: safepoint deadlock in {what}: \
                     stop={} live={} active={} parked={}",
                    self.stop.load(Ordering::Relaxed),
                    self.live.load(Ordering::Relaxed),
                    self.active.load(Ordering::Relaxed),
                    self.parked.load(Ordering::Relaxed),
                );
            }
            core::hint::spin_loop();
        }
    }

    /// Ask every other RUNNING executor to stop, and wait until they have.
    ///
    /// `i_am_running` is not a detail. The target is "every running executor
    /// except me", and subtracting one unconditionally assumes the caller is
    /// one of them. It is not always: an allocation can happen outside guest
    /// code -- loading an image, running initialisers, a host call -- and there
    /// the count is one too low, so the collector starts with a peer still
    /// executing. That reads back as a root stack whose `stack_top` is past
    /// its `stack.len()`, from an executor that was mid-push.
    pub fn stage_stop(&self, i_am_running: bool) {
        // FIRST, wait for the previous stop's parkers to finish leaving.
        //
        // Without this, a `parked` count left over from the last stop is
        // mistaken for this one's and the collector starts while a thread is
        // still running. The window is small and entirely real: `park` exits
        // its spin when `stop` clears and only THEN decrements, so a thread
        // that has been released but has not yet decremented still reads as
        // parked. Staging again in that instant sees a count it did not earn.
        //
        // Nothing can park while `stop` is 0, so this loop terminates.
        self.spin_until("draining a previous stop", || {
            self.parked.load(Ordering::Acquire) == 0
        });
        self.stop.store(1, Ordering::Release);
        // Re-read the target every time round rather than once. An executor
        // that finishes while this waits lowers the count, and a target
        // captured before it left would never be reached.
        let mine = if i_am_running { 1 } else { 0 };
        self.spin_until("waiting for executors to park", || {
            let want = self.active.load(Ordering::Acquire).saturating_sub(mine);
            self.parked.load(Ordering::Acquire) >= want
        });
    }

    pub fn release_stop(&self) {
        self.stop.store(0, Ordering::Release);
    }

    /// Every OTHER executor's roots, for the collector to scan.
    ///
    /// Only valid between `stage_stop` and `release_stop`: that is the window
    /// in which nothing else is touching them.
    pub fn parked_roots(&self, mine: usize) -> impl Iterator<Item = *mut ExecRoots> + '_ {
        self.slots.iter().enumerate().filter_map(move |(i, s)| {
            if i == mine {
                return None;
            }
            let p = s.load(Ordering::Acquire);
            if p.is_null() {
                None
            } else {
                Some(p)
            }
        })
    }

    // --- gas ---------------------------------------------------------------

    pub fn set_limit(&self, n: u64) {
        self.limit.store(n, Ordering::Release);
        self.spent.store(0, Ordering::Release);
    }

    pub fn limit(&self) -> u64 {
        self.limit.load(Ordering::Acquire)
    }

    /// Add this executor's batch to the total and read the total back.
    pub fn flush_gas(&self, batch: u64) -> u64 {
        self.spent.fetch_add(batch, Ordering::AcqRel) + batch
    }

    pub fn spent(&self) -> u64 {
        self.spent.load(Ordering::Acquire)
    }
}
