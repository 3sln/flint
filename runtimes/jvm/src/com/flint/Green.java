package com.flint;

import java.util.ArrayList;
import java.util.List;

/// Green threads for the JVM port (`doc/decisions/0005`).
///
/// ## Why not virtual threads
///
/// Loom would be the obvious answer and it is the wrong one, for a reason that
/// is a property of flint rather than of Loom: **flint's scheduler is
/// deterministic and asserted to be** — `runtimes/conform/green.cljc` compares
/// the actual interleaving of three threads across all three runtimes, and
/// `test/threads.clj` runs the same program five times and demands one answer.
/// Virtual threads are scheduled by a ForkJoinPool, so the interleaving would
/// be whatever the JVM chose that day.
///
/// Pinning them to a single carrier and handing off explicitly would recover
/// determinism — and at that point Loom is being used only to capture a
/// continuation, which the flattened interpreter already gives us as data. So
/// this is the same scheduler the wasm runtime has, over the same shape of
/// state, and the two agree because they are the same design rather than
/// because they were tuned to.
///
/// The CLR settles it either way: .NET has no Loom, having chosen async/await
/// instead. One design across three runtimes beats two that have to be kept in
/// agreement (`doc/decisions/0010`).
public final class Green {
    private Green() {}

    // Statuses, matching `conc.rs`'s `ST_*` so the two ports and the runtime
    // describe a thread the same way.
    public static final int NEW = 0, RUNNABLE = 1, PARKED = 2, DONE = 3, FAILED = 4;

    /// A green thread: a frame list, and what it is waiting for.
    ///
    /// The frame list IS the continuation, which is the whole reason the
    /// interpreter had to stop recursing first. Everything here is data, so a
    /// thread can be parked, resumed, and — the point of `doc/decisions/0015` —
    /// captured in a snapshot.
    public static final class Thread {
        public final long id;
        public int status = NEW;
        /// The closure to enter, until it has been entered.
        public Object entry;
        /// The continuation, once it has.
        public ArrayList<Vm.Frame> frames = new ArrayList<>();
        public Object result;
        /// Arguments, for a thread that has not been entered yet.
        public Object[] args;
        /// What it is parked on: a port, or another thread for a join.
        public Object parkOn;
        /// Dynamic bindings are per GREEN thread, and a spawn inherits a
        /// snapshot (`doc/decisions/0005`).
        public Object bindings;
        /// Set by a courtesy yield, so the scheduler knows this thread is
        /// runnable rather than waiting.
        public boolean yielded;

        Thread(long id, Object entry, Object bindings) {
            this.id = id;
            this.entry = entry;
            this.bindings = bindings;
        }
    }

    /// The scheduler. One per `Vm`, and deliberately not thread-safe: green
    /// threads are flint's concurrency, real threads are the host's, and
    /// mixing them is `0028`'s problem rather than this file's.
    public static final class Sched {
        public final List<Thread> threads = new ArrayList<>();
        public int current = 0;
        public long nextId = 1;

        public Thread cur() {
            return current < threads.size() ? threads.get(current) : null;
        }
    }

    /// Thrown by a builtin that has decided to park. Carries nothing: what the
    /// thread is waiting on is recorded on the thread itself before this is
    /// raised, because the scheduler needs it and an exception is a control
    /// transfer rather than a message.
    ///
    /// Unwinding the JAVA stack is safe here precisely because the flint frames
    /// are not on it. What it does lose is any Java frame between the park and
    /// the scheduler — a builtin that called back into flint and parked inside
    /// that call. The wasm runtime refuses that case by name ("cannot park
    /// here: this native was reached through `apply`"), and so does this.
    public static final class Park extends RuntimeException {
        public Park() { super(null, null, false, false); }
    }
}
