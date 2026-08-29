using System.Collections.Generic;

namespace Flint;

/// Green threads for the CLR port (`doc/decisions/0005`).
///
/// The same design as the JVM's and the wasm runtime's, which is the point:
/// `doc/decisions/0010` says the bytecode makes a port cheap and does nothing
/// to make two ports AGREE, and `runtimes/conform/green.cljc` compares the
/// actual interleaving of three threads across all three runtimes. Three
/// schedulers written three ways would have to be tuned into agreement; one
/// design implemented three times agrees by construction.
///
/// .NET has no Loom to borrow even if we wanted to -- it chose async/await and
/// shelved its green-threads prototype -- so the continuation was always going
/// to have to be ours. Flattening the interpreter is what made it data.
public static class Green {
    // Matching `conc.rs`'s `ST_*` and the JVM's, so all three describe a
    // thread the same way.
    public const int New = 0, Runnable = 1, Parked = 2, Done = 3, Failed = 4;

    /// A green thread: a frame list, and what it is waiting for.
    public sealed class Thread {
        public readonly long Id;
        public int Status = New;
        /// The closure to enter, until it has been entered.
        public object Entry;
        public object[] Args;
        /// The continuation, once it has been.
        public List<Vm.Frame> Frames = new();
        public object Result;
        /// What it is parked on: a port, or another thread for a join.
        public object ParkOn;
        /// Set by a courtesy yield, so the scheduler knows this thread is
        /// runnable rather than waiting.
        public bool Yielded;

        public Thread(long id, object entry) { Id = id; Entry = entry; }
    }

    public sealed class Sched {
        public readonly List<Thread> Threads = new();
        public int Current;
        public long NextId = 1;
        public Thread Cur => Current < Threads.Count ? Threads[Current] : null;
    }

    /// Thrown by a builtin that has decided to park.
    ///
    /// Unwinding the CLR stack is safe precisely because the flint frames are
    /// not on it. What it loses is any CLR frame between the park and the
    /// scheduler -- a builtin that called back into flint and parked inside
    /// that call -- which the wasm runtime refuses by name for the same reason.
    public sealed class Park : System.Exception { }
}
