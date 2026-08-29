using System.Collections.Generic;

namespace Flint;

/// The concurrency builtins: green threads and LOCAL channels
/// (`doc/decisions/0005`). The CLR half of what `Conc.java` does, kept
/// deliberately line-for-line with it so the two agree by construction rather
/// than by testing.
///
/// Local only. A channel joins two green threads inside one sandbox and passes
/// values BY REFERENCE -- same heap, nothing to protect either end from. A
/// global port crosses a heap, is the host's, and is a different object
/// (`doc/decisions/0027`); it is not built here yet.
public static class Conc {
    public sealed class Chan {
        public readonly long Id;
        public readonly int Cap;
        public readonly string Label;
        public readonly Queue<object> Inbox = new();
        public Chan Peer;
        public bool Closed;
        public Chan(long id, int cap, string label) { Id = id; Cap = cap; Label = label; }
        public override string ToString() =>
            "#<port " + Id + (Label == null ? "" : " " + Label) + ">";
    }

    private static long _nextChan = 1;

    public static void Install(System.Action<string, Builtins.Fn> def) {
        def("flint/spawn", (vm, a) => {
            var t = new Green.Thread(vm.Sched.NextId++, a[0]);
            vm.Sched.Threads.Add(t);
            return t;
        });
        def("flint/self", (vm, a) => vm.Sched.Cur);
        def("flint/thread?", (vm, a) => a[0] is Green.Thread);
        def("flint/thread-id", (vm, a) => ((Green.Thread) a[0]).Id);
        def("flint/thread-state", (vm, a) => Kw.Of(null, StateName(((Green.Thread) a[0]).Status)));
        def("flint/thread-result", (vm, a) => ((Green.Thread) a[0]).Result);

        def("flint/yield", (vm, a) => {
            var t = vm.Sched.Cur;
            if (t == null || vm.Sched.Threads.Count < 2) return null;
            throw vm.YieldNow();
        });

        def("flint/thread-join", (vm, a) => {
            var t = (Green.Thread) a[0];
            if (t.Status == Green.Done) return t.Result;
            if (t.Status == Green.Failed) throw new FlintThrow(t.Result);
            if (ReferenceEquals(vm.Sched.Cur, t)) throw new FlintThrow("a thread cannot join itself");
            throw vm.ParkOn(t);
        });

        def("flint/channel", (vm, a) => {
            int cap = a.Length > 0 && a[0] is long n ? (int) n : 16;
            string label = a.Length > 1 ? a[1] as string : null;
            var x = new Chan(_nextChan++, cap, label);
            var y = new Chan(_nextChan++, cap, label);
            x.Peer = y; y.Peer = x;
            return new Vec(new List<object> { x, y });
        });
        def("flint/port?", (vm, a) => a[0] is Chan);
        def("flint/port-id", (vm, a) => ((Chan) a[0]).Id);
        def("flint/port-label", (vm, a) => ((Chan) a[0]).Label);
        def("flint/port-host?", (vm, a) => false);
        def("flint/port-state", (vm, a) => {
            var c = (Chan) a[0];
            return Kw.Of(null, c.Closed || (c.Peer.Closed && c.Inbox.Count == 0) ? "closed" : "open");
        });
        def("flint/port-close", (vm, a) => {
            var c = (Chan) a[0];
            c.Closed = true;
            vm.WakeOn(c);
            vm.WakeOn(c.Peer);
            return null;
        });

        /// A send goes into the PEER's inbox, which is what makes a pair
        /// two-way. Parks on a full inbox; the receiver draining it wakes us.
        def("flint/port-send", (vm, a) => {
            var c = (Chan) a[0];
            var to = c.Peer;
            if (to.Closed)
                throw new FlintThrow("the other end of this port is gone, so nothing can ever receive this");
            if (to.Inbox.Count >= to.Cap) throw vm.ParkOn(to);
            to.Inbox.Enqueue(a[1]);
            vm.WakeOn(to);
            return null;
        });

        /// A receive takes from this end's OWN inbox. Parks when empty and the
        /// peer is open; reads as end-of-stream once the peer has closed and
        /// everything buffered is drained.
        def("flint/port-receive", (vm, a) => {
            var c = (Chan) a[0];
            if (c.Inbox.Count > 0) {
                var v = c.Inbox.Dequeue();
                vm.WakeOn(c);
                return v;
            }
            if (c.Peer.Closed || c.Closed) return null;
            throw vm.ParkOn(c);
        });
    }

    private static string StateName(int st) => st switch {
        Green.New => "new",
        Green.Runnable => "runnable",
        Green.Parked => "parked",
        Green.Done => "done",
        _ => "failed",
    };
}
