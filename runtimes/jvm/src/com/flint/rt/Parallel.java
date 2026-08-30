package com.flint.rt;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/// Several executors inside one sandbox (`doc/decisions/0028`), ported from
/// `runtime/src/par.rs`.
///
/// One heap, K host threads on it. Everything here exists to make three things
/// true at once: only one thread mutates the heap's structure at a time, no
/// thread is running when the collector moves an object, and no thread waits
/// forever for either.
///
/// ## Where a thread may stop
///
/// NOT ANYWHERE. A collection MOVES objects, so a thread that stops while
/// holding a value in a host local resumes holding a stale pointer -- the same
/// rule the whole runtime is built on, except that under one thread it only
/// applied around your OWN allocations and now applies around everyone's.
///
/// So the only safepoint is the interpreter's checkpoint, between two bytecode
/// instructions, where `ip` has been written back and every live value is on
/// the value stack by construction. A thread inside a builtin does not poll and
/// cannot stop; the collector waits for it. That is a latency cost and not a
/// correctness one, and it is the reason the poll lives exactly where it does.
///
/// ## Why a spin rather than a lock
///
/// The Rust is `#![no_std]` and compiles to wasm, where there is no mutex and
/// no condition variable; atomics are in `core` and blocking primitives are
/// not. The port could use `synchronized` -- and does not, because the point of
/// this exercise is that the three runtimes are the same runtime. A safepoint
/// is short: a minor collection is sub-millisecond and the executors are few.
public final class Parallel {
    /// How many executors one sandbox may have.
    public static final int MAX_EXECUTORS = 64;

    /// How many instructions an executor counts locally before telling anyone.
    ///
    /// The whole reason gas is batched: a shared counter incremented once per
    /// instruction would put an atomic read-modify-write on the interpreter's
    /// hottest line and have every thread fighting for one cache line, which
    /// costs more than the work it is counting.
    ///
    /// The price is that a limit stops the program a little late -- by at most
    /// this times the number of executors (`doc/decisions/0009`).
    public static final long GAS_BATCH = 4096;

    /// Non-zero while an executor is staging a collection and wants everyone
    /// else stopped.
    private final AtomicInteger stop = new AtomicInteger();
    /// Executors registered in this sandbox, including the one that made it.
    private final AtomicInteger live = new AtomicInteger();
    /// Executors currently stopped at the safepoint.
    private final AtomicInteger parked = new AtomicInteger();
    /// Executors currently RUNNING, and therefore able to reach a safepoint.
    ///
    /// The distinction between this and `live` is the whole of why a first
    /// attempt at this deadlocked. A collector must wait only for threads that
    /// CAN actually stop: an executor that has finished its work, or is sitting
    /// in host code, polls nothing, and waiting for it is waiting forever. It
    /// is registered -- its roots still have to be scanned -- but it is not
    /// running.
    ///
    /// This is the same split a JVM makes between a thread "in Java" and one
    /// "in native", and for the same reason.
    private final AtomicInteger active = new AtomicInteger();
    /// The allocation lock: 0 free, 1 held. Only one thread may be inside the
    /// collector's bookkeeping at a time.
    private final AtomicInteger alloc = new AtomicInteger();
    /// One lock per intern table (strings, keywords, symbols, ports).
    ///
    /// One EACH rather than one for all four because it costs nothing, and not
    /// SHARDED because sharding would optimise something that is not happening:
    /// the tables are probed rarely next to the work around them.
    private final AtomicInteger[] interns = {
        new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger()
    };
    /// Gas, flushed from per-executor batches.
    private final AtomicLong spent = new AtomicLong();
    private final AtomicLong limit = new AtomicLong();

    public int executors() { return live.get(); }
    public int running() { return active.get(); }

    public int register() {
        int n = live.incrementAndGet();
        if (n > MAX_EXECUTORS) { live.decrementAndGet(); return -1; }
        return n - 1;
    }

    public void deregister() { live.decrementAndGet(); }

    /// Begin running guest code: from here this thread polls and can be
    /// stopped.
    ///
    /// BLOCKS while a collection is being staged, because joining the set of
    /// threads a collector is waiting for AFTER it counted them would leave it
    /// waiting for one more than will ever arrive.
    public void enter() {
        for (;;) {
            while (stop.get() != 0) Thread.onSpinWait();
            active.incrementAndGet();
            if (stop.get() == 0) return;
            // A stop was staged between the check and the increment. Back out
            // and wait: the stager may already have read the old count.
            active.decrementAndGet();
        }
    }

    /// Stop running guest code. Nothing waits for this thread until it enters
    /// again.
    public void leave() { active.decrementAndGet(); }

    /// Is someone waiting for this thread to stop? A plain read: missing it by
    /// one instruction only delays the safepoint by one instruction, and the
    /// ordering that matters happens in `park`.
    public boolean stopRequested() { return stop.get() != 0; }

    /// Stop here until the collection is over. Called ONLY from a safepoint --
    /// between two bytecode instructions, with every live value on the value
    /// stack.
    public void park() {
        parked.incrementAndGet();
        while (stop.get() != 0) Thread.onSpinWait();
        parked.decrementAndGet();
    }

    /// Take the allocation lock, stopping at safepoints while waiting.
    ///
    /// Polling WHILE spinning is not politeness, it is the deadlock fix: a
    /// thread that spun here without parking would never reach a safepoint, and
    /// the executor staging a collection would wait for it forever.
    public void lockAlloc() {
        for (;;) {
            if (alloc.compareAndSet(0, 1)) return;
            if (stopRequested()) park();
            Thread.onSpinWait();
        }
    }

    public void unlockAlloc() { alloc.set(0); }

    /// Take an intern table's lock, stopping at safepoints while waiting.
    ///
    /// THE CALLER MUST HAVE EVERY LIVE VALUE ROOTED, because this can park.
    ///
    /// The invariant that makes the collector's life simple: this lock is only
    /// ever held across a hash probe, which does not allocate and so cannot
    /// reach a safepoint. A PARKED THREAD THEREFORE NEVER HOLDS AN INTERN LOCK,
    /// and the collector can walk and rewrite the tables during a stop without
    /// taking anything.
    public void lockIntern(int table) {
        for (;;) {
            if (interns[table].compareAndSet(0, 1)) return;
            if (stopRequested()) park();
            Thread.onSpinWait();
        }
    }

    public void unlockIntern(int table) { interns[table].set(0); }

    /// Spin until `f` is true, or fail saying what everyone was doing.
    ///
    /// A deadlock here used to be a hang, and a hang tells you nothing: you
    /// cannot tell "waiting for a thread that will never park" from "slow". The
    /// bound is enormous -- far longer than any real safepoint -- so it only
    /// fires on a genuine one, and then it names the state.
    interface Cond { boolean ok(); }

    private void spinUntil(String what, Cond f) {
        long spins = 0;
        while (!f.ok()) {
            if (++spins > 2_000_000_000L) {
                throw new IllegalStateException(
                    "flint: safepoint deadlock in " + what + ": stop=" + stop.get()
                    + " live=" + live.get() + " active=" + active.get()
                    + " parked=" + parked.get());
            }
            Thread.onSpinWait();
        }
    }

    /// Ask every other RUNNING executor to stop, and wait until they have.
    ///
    /// `iAmRunning` is not a detail. The target is "every running executor
    /// except me", and subtracting one unconditionally assumes the caller is
    /// one of them. It is not always: an allocation can happen OUTSIDE guest
    /// code -- loading an image, running initialisers, a host call -- and there
    /// the count is one too low, so the collector starts with a peer still
    /// executing. That reads back as a root stack whose `stackTop` is past its
    /// length, from an executor that was mid-push.
    public void stageStop(boolean iAmRunning) {
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
        spinUntil("draining a previous stop", () -> parked.get() == 0);
        stop.set(1);
        // Re-read the target every time round rather than once. An executor
        // that finishes while this waits lowers the count, and a target
        // captured before it left would never be reached.
        int mine = iAmRunning ? 1 : 0;
        spinUntil("waiting for executors to park", () -> {
            int want = Math.max(active.get() - mine, 0);
            return parked.get() >= want;
        });
    }

    public void releaseStop() { stop.set(0); }

    // --- gas ----------------------------------------------------------------

    public void setLimit(long n) { limit.set(n); spent.set(0); }
    public long limit() { return limit.get(); }
    public long spent() { return spent.get(); }

    /// Add this executor's batch to the total and read the total back.
    public long flushGas(long batch) { return spent.addAndGet(batch); }
}
