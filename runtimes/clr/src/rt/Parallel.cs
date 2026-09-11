namespace Flint.Rt;

using System.Threading;

/// Several executors inside one sandbox (`DECISIONS.md#drivers`), ported from
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
/// instructions, where `Ip` has been written back and every live value is on
/// the value stack by construction. A thread inside a builtin does not poll and
/// cannot stop; the collector waits for it. That is a latency cost and not a
/// correctness one, and it is the reason the poll lives exactly where it does.
///
/// ## Why a spin rather than a lock
///
/// The Rust is `#![no_std]` and compiles to wasm, where there is no mutex and
/// no condition variable. The port could use `Monitor` -- and does not, because
/// the point of this exercise is that the three runtimes are the same runtime.
/// A safepoint is short: a minor collection is sub-millisecond and the
/// executors are few.
public sealed class Parallel {
    /// How many executors one sandbox may have.
    public const int MaxExecutors = 64;

    /// How many instructions an executor counts locally before telling anyone.
    ///
    /// The whole reason gas is batched: a shared counter incremented once per
    /// instruction would put an atomic read-modify-write on the interpreter's
    /// hottest line and have every thread fighting for one cache line.
    public const long GasBatch = 4096;

    /// Non-zero while an executor is staging a collection and wants everyone
    /// else stopped.
    int stop;
    /// Executors registered in this sandbox, including the one that made it.
    int live;
    /// Executors currently stopped at the safepoint.
    int parked;
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
    int active;
    /// The allocation lock: 0 free, 1 held.
    int alloc;
    /// One lock per intern table (strings, keywords, symbols, ports).
    readonly int[] interns = new int[4];
    /// Gas, flushed from per-executor batches.
    long spent;
    long limitv;

    public int Executors() => Volatile.Read(ref live);
    public int Running() => Volatile.Read(ref active);

    public int Register() {
        int n = Interlocked.Increment(ref live);
        if (n > MaxExecutors) { Interlocked.Decrement(ref live); return -1; }
        return n - 1;
    }

    public void Deregister() => Interlocked.Decrement(ref live);

    /// Begin running guest code: from here this thread polls and can be
    /// stopped.
    ///
    /// BLOCKS while a collection is being staged, because joining the set of
    /// threads a collector is waiting for AFTER it counted them would leave it
    /// waiting for one more than will ever arrive.
    public void Enter() {
        for (;;) {
            while (Volatile.Read(ref stop) != 0) Thread.SpinWait(1);
            Interlocked.Increment(ref active);
            if (Volatile.Read(ref stop) == 0) return;
            // A stop was staged between the check and the increment. Back out
            // and wait: the stager may already have read the old count.
            Interlocked.Decrement(ref active);
        }
    }

    /// Stop running guest code. Nothing waits for this thread until it enters
    /// again.
    public void Leave() => Interlocked.Decrement(ref active);

    /// Is someone waiting for this thread to stop? A plain read: missing it by
    /// one instruction only delays the safepoint by one instruction, and the
    /// ordering that matters happens in `Park`.
    public bool StopRequested() => Volatile.Read(ref stop) != 0;

    /// Stop here until the collection is over. Called ONLY from a safepoint.
    public void Park() {
        Interlocked.Increment(ref parked);
        while (Volatile.Read(ref stop) != 0) Thread.SpinWait(1);
        Interlocked.Decrement(ref parked);
    }

    /// Take the allocation lock, stopping at safepoints while waiting.
    ///
    /// Polling WHILE spinning is not politeness, it is the deadlock fix: a
    /// thread that spun here without parking would never reach a safepoint, and
    /// the executor staging a collection would wait for it for ever.
    public void LockAlloc() {
        for (;;) {
            if (Interlocked.CompareExchange(ref alloc, 1, 0) == 0) return;
            if (StopRequested()) Park();
            Thread.SpinWait(1);
        }
    }

    public void UnlockAlloc() => Volatile.Write(ref alloc, 0);

    /// Take an intern table's lock, stopping at safepoints while waiting.
    ///
    /// THE CALLER MUST HAVE EVERY LIVE VALUE ROOTED, because this can park.
    ///
    /// The invariant that makes the collector's life simple: this lock is only
    /// ever held across a hash probe, which does not allocate and so cannot
    /// reach a safepoint. A PARKED THREAD THEREFORE NEVER HOLDS AN INTERN LOCK,
    /// and the collector can walk and rewrite the tables during a stop without
    /// taking anything.
    public void LockIntern(int table) {
        for (;;) {
            if (Interlocked.CompareExchange(ref interns[table], 1, 0) == 0) return;
            if (StopRequested()) Park();
            Thread.SpinWait(1);
        }
    }

    public void UnlockIntern(int table) => Volatile.Write(ref interns[table], 0);

    /// Spin until `f` is true, or fail saying what everyone was doing.
    ///
    /// A deadlock here used to be a hang, and a hang tells you nothing: you
    /// cannot tell "waiting for a thread that will never park" from "slow".
    void SpinUntil(string what, System.Func<bool> f) {
        long spins = 0;
        while (!f()) {
            if (++spins > 2_000_000_000L) {
                throw new System.InvalidOperationException(
                    "flint: safepoint deadlock in " + what + ": stop=" + Volatile.Read(ref stop)
                    + " live=" + Volatile.Read(ref live) + " active=" + Volatile.Read(ref active)
                    + " parked=" + Volatile.Read(ref parked));
            }
            Thread.SpinWait(1);
        }
    }

    /// Ask every other RUNNING executor to stop, and wait until they have.
    ///
    /// `iAmRunning` is not a detail. The target is "every running executor
    /// except me", and subtracting one unconditionally assumes the caller is
    /// one of them. It is not always: an allocation can happen OUTSIDE guest
    /// code -- loading an image, running initialisers, a host call -- and there
    /// the count is one too low, so the collector starts with a peer still
    /// executing.
    public void StageStop(bool iAmRunning) {
        // FIRST, wait for the previous stop's parkers to finish leaving.
        //
        // Without this, a `parked` count left over from the last stop is
        // mistaken for this one's and the collector starts while a thread is
        // still running. `Park` exits its spin when `stop` clears and only THEN
        // decrements, so a thread that has been released but has not yet
        // decremented still reads as parked.
        SpinUntil("draining a previous stop", () => Volatile.Read(ref parked) == 0);
        Volatile.Write(ref stop, 1);
        // Re-read the target every time round rather than once. An executor
        // that finishes while this waits lowers the count, and a target
        // captured before it left would never be reached.
        int mine = iAmRunning ? 1 : 0;
        SpinUntil("waiting for executors to park", () => {
            int want = System.Math.Max(Volatile.Read(ref active) - mine, 0);
            return Volatile.Read(ref parked) >= want;
        });
    }

    public void ReleaseStop() => Volatile.Write(ref stop, 0);

    // --- gas ----------------------------------------------------------------

    public void SetLimit(long n) { Volatile.Write(ref limitv, n); Volatile.Write(ref spent, 0); }
    public long Limit() => Volatile.Read(ref limitv);
    public long Spent() => Volatile.Read(ref spent);

    /// Add this executor's batch to the total and read the total back.
    public long FlushGas(long batch) => Interlocked.Add(ref spent, batch);
}
