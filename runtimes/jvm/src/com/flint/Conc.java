package com.flint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/// The concurrency builtins: green threads and LOCAL channels
/// (`doc/decisions/0005`).
///
/// Local only, deliberately. A channel joins two green threads inside one
/// sandbox and passes values BY REFERENCE — same heap, same collector, nothing
/// to protect either end from. A global port crosses a heap and is the host's
/// (`doc/decisions/0027`); it is a different object with a different cost and
/// is not built here yet.
public final class Conc {
    private Conc() {}

    /// A local channel end. Two of them, each holding the other.
    public static final class Chan {
        public final long id;
        public final int cap;
        public final String label;
        public final ArrayDeque<Object> inbox = new ArrayDeque<>();
        public Chan peer;
        public boolean closed;
        Chan(long id, int cap, String label) { this.id = id; this.cap = cap; this.label = label; }
        @Override public String toString() {
            return "#<port " + id + (label == null ? "" : " " + label) + ">";
        }
    }

    private static long nextChan = 1;

    static void install() {
        Builtins.def("flint/spawn", (vm, a) -> {
            Green.Thread t = new Green.Thread(vm.sched.nextId++, a[0], null);
            vm.sched.threads.add(t);
            return t;
        });
        Builtins.def("flint/self", (vm, a) -> vm.sched.cur());
        Builtins.def("flint/thread?", (vm, a) -> a[0] instanceof Green.Thread);
        Builtins.def("flint/thread-id", (vm, a) -> ((Green.Thread) a[0]).id);
        Builtins.def("flint/thread-state", (vm, a) -> Kw.of(null, stateName(((Green.Thread) a[0]).status)));
        Builtins.def("flint/thread-result", (vm, a) -> ((Green.Thread) a[0]).result);

        /// `yield` is a COURTESY yield: the call has already happened, so it
        /// must not be re-executed on resume. It parks on nothing and is made
        /// runnable again immediately, which lets every other runnable thread
        /// have a turn before this one continues.
        Builtins.def("flint/yield", (vm, a) -> {
            Green.Thread t = vm.sched.cur();
            if (t == null || vm.sched.threads.size() < 2) return null;
            throw vm.yieldNow();
        });

        Builtins.def("flint/thread-join", (vm, a) -> {
            Green.Thread t = (Green.Thread) a[0];
            if (t.status == Green.DONE) return t.result;
            if (t.status == Green.FAILED) throw new Vm.Thrown(t.result);
            Green.Thread me = vm.sched.cur();
            if (me == t) throw new Vm.Thrown("a thread cannot join itself");
            throw vm.park(t);
        });

        Builtins.def("flint/channel", (vm, a) -> {
            int cap = a.length > 0 && a[0] instanceof Long n ? n.intValue() : 16;
            String label = a.length > 1 && a[1] instanceof String s ? s : null;
            Chan x = new Chan(nextChan++, cap, label);
            Chan y = new Chan(nextChan++, cap, label);
            x.peer = y; y.peer = x;
            List<Object> pair = new ArrayList<>();
            pair.add(x); pair.add(y);
            return pair;
        });
        Builtins.def("flint/port?", (vm, a) -> a[0] instanceof Chan);
        Builtins.def("flint/port-id", (vm, a) -> ((Chan) a[0]).id);
        Builtins.def("flint/port-label", (vm, a) -> ((Chan) a[0]).label);
        Builtins.def("flint/port-host?", (vm, a) -> false);
        Builtins.def("flint/port-state", (vm, a) -> {
            Chan c = (Chan) a[0];
            return Kw.of(null, c.closed ? "closed" : (c.peer.closed && c.inbox.isEmpty() ? "closed" : "open"));
        });
        Builtins.def("flint/port-close", (vm, a) -> {
            Chan c = (Chan) a[0];
            c.closed = true;
            vm.wakeOn(c);
            vm.wakeOn(c.peer);
            return null;
        });

        /// A send goes into the PEER's inbox, which is what makes a pair
        /// two-way. Parks when that inbox is full, and the receiver draining it
        /// is what wakes the sender.
        Builtins.def("flint/port-send", (vm, a) -> {
            Chan c = (Chan) a[0];
            Chan to = c.peer;
            if (to.closed) throw new Vm.Thrown(
                "the other end of this port is gone, so nothing can ever receive this");
            if (to.inbox.size() >= to.cap) throw vm.park(to);
            to.inbox.add(a[1]);
            vm.wakeOn(to);
            return null;
        });

        /// A receive takes from this end's OWN inbox. Parks when it is empty
        /// and the peer is still open; reads as end-of-stream once the peer has
        /// closed and everything buffered is drained.
        Builtins.def("flint/port-receive", (vm, a) -> {
            Chan c = (Chan) a[0];
            if (!c.inbox.isEmpty()) {
                Object v = c.inbox.poll();
                vm.wakeOn(c);
                return v;
            }
            if (c.peer.closed || c.closed) return null;
            throw vm.park(c);
        });

        // Dynamic bindings are per green thread, and a spawn inherits a
        // snapshot. Not built here yet: the JVM port keeps them per REAL
        // thread (`Builtins.dyn*`), which is right for its own threading and
        // wrong for flint's. Named rather than silently left.
    }

    private static String stateName(int st) {
        switch (st) {
            case Green.NEW: return "new";
            case Green.RUNNABLE: return "runnable";
            case Green.PARKED: return "parked";
            case Green.DONE: return "done";
            default: return "failed";
        }
    }
}
