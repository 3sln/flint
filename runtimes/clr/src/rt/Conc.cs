namespace Flint.Rt;


/// Green threads, ports, and the scheduler. Ported from `runtime/src/conc.rs`
/// (`doc/decisions/0005`).
///
/// # Nothing here suspends a host frame
///
/// A green thread IS a VM state. The scheduler picks a runnable one, and
/// BLOCKING MEANS NOT RUNNABLE YET -- the interpreter never leaves its own loop
/// and the host is never blocked. On wasm that was forced: a synchronous export
/// cannot be suspended mid-execution, and the usual escapes (JSPI, Asyncify)
/// cost either portability or size on every function forever.
///
/// Here it is a CHOICE, and the same one, for two reasons that matter more than
/// the wasm constraint:
///
///  * A HOST THREAD CANNOT BE CAPTURED. Snapshots need the whole VM state, and
///    a thread parked inside a `synchronized` block or a `Monitor.Wait` has
///    state on the host's stack that no snapshot can reach. A green thread's
///    continuation is an ordinary heap object.
///  * IT IS THE SAME SCHEDULER. Three runtimes agreeing on the interleaving is
///    a fact about the port; three host schedulers agreeing would be luck.
///
/// # And nothing here is new work for the collector
///
/// The GC's design rests on THE VALUE STACK IS THE ROOT SET. With N threads
/// there are N stacks, and a parked one is full of live references nothing is
/// executing. Rather than teach the collector about threads, a parked thread is
/// AN ORDINARY HEAP OBJECT WHOSE SLOTS HOLD ITS SAVED STACK, and the thread
/// table hangs off `singletons[SING_SCHED]`, which the collector already
/// traces. The root walk did not change at all.
public static class Conc {

    // --- thread ------------------------------------------------------------

    public const int TH_STATUS = 0, TH_PARK_ON = 1, TH_RESULT = 2;
    /// Saved value stack: a Vals object, so tracing it is the ordinary walk.
    public const int TH_STACK = 3;
    /// Saved frames, as RAW bytes. A frame holds only indices -- its closure is
    /// `stack[retTo]`, never a copy -- so there is nothing in here for the
    /// collector to find, and a raw blob is the honest encoding.
    public const int TH_FRAMES = 4;
    /// Bytes per saved frame. Five interpreter words plus the two the AOT entry
    /// needs, so the record is the same width in every build and a snapshot and
    /// a thread save are interchangeable between them.
    public const int FRAME_REC = 28;
    public const int TH_HANDLERS = 5, TH_BINDINGS = 6, TH_ID = 7, TH_ENTRY = 8;
    public const int TH_PENDING = 9, TH_TOKEN = 10, TH_FAIL = 11, TH_LEN = 12;

    public const int ST_NEW = 0, ST_RUNNABLE = 1, ST_PARKED = 2,
                            ST_DONE = 3, ST_FAILED = 4;

    /// The wake key for a COURTESY yield, distinct from parking on a port.
    ///
    /// The distinction is load-bearing: a park REWINDS so the call re-executes
    /// and finds its value, while a yield must NOT -- re-executing a `yield`
    /// yields again, forever, and the scheduler reports it made no progress.
    public static readonly long PARK_YIELD = Val.Fixnum(0);

    // --- port --------------------------------------------------------------

    public const int PT_ID = 0, PT_STATE = 1, PT_CAP = 2, PT_INBOX = 3,
        PT_HEAD = 4, PT_BYTES = 5, PT_PEER = 6, PT_LABEL = 7, PT_KIND = 8,
        PT_ROOT = 9, PT_FORMAT = 10, PT_OPTS = 11, PT_BINARY = 12,
        PT_PRESENTED = 13, PT_LEN = 14;

    public const int P_OPEN = 0, P_CLOSED = 1, P_HALF = 2, P_ORPHANED = 3;
    public const int K_CHANNEL = 0, K_FLINT = 1, K_HOST = 2, K_GLOBAL = 3;

    // --- waiter ------------------------------------------------------------

    public const int W_THREAD = 1, W_KIND = 2, W_PORT = 3, W_NEXT = 4, W_LEN = 5;
    public const int WK_OPEN = 1, WK_SEND = 2, WK_RECEIVE = 3, WK_JOIN = 4;

    // --- scheduler ---------------------------------------------------------

    public const int SC_THREADS = 0, SC_CURRENT = 1, SC_NEXTID = 2,
        SC_EVENTS = 3, SC_EHEAD = 4, SC_PORTS = 5, SC_PAIRS = 6,
        SC_WAITERS = 7, SC_WFREE = 8, SC_SYSTEM = 9, SC_LEN = 10;

    /// Instructions a thread runs before the scheduler takes the slice back.
    /// Preemptive, so a thread with no `yield` in it cannot starve the others.
    public const long SLICE = 4096;

    static long Fx(long v) { return Val.AsFixnum(v); }

    static long NewObj(Rt rt, int ty, int len) {
        long a = rt.Alloc(ty, len);
        return a == 0 ? Val.Nil : Val.Heap(a);
    }

    public static bool IsThread(Rt rt, long v) {
        return Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyThread;
    }
    public static bool IsPort(Rt rt, long v) {
        return Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyPort;
    }

    public static long Sched(Rt rt) { return rt.roots.Singletons[Rt.SingSched]; }

    /// Create the scheduler on first use, enrolling whatever is running now as
    /// THREAD 0. Built here rather than at startup so a program that never
    /// spawns runs a straight line with no scheduler in it at all.
    public static long EnsureSched(Rt rt) {
        long s = Sched(rt);
        if (!Val.IsNil(s)) return s;
        int bas = rt.Mark();
        int si = rt.Push(NewObj(rt, Obj.TySched, SC_LEN));
        if (Val.IsNil(rt.R(si))) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_EVENTS, Vec.Empty(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_EHEAD, Val.Fixnum(0));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_PORTS, Vec.Empty(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_PAIRS, Vec.Empty(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_WAITERS, Vec.Empty(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_WFREE, Val.Fixnum(-1));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_NEXTID, Val.Fixnum(1));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_CURRENT, Val.Fixnum(0));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_SYSTEM, Val.Nil);
        // The running thread becomes thread 0. Its stack is the LIVE one, so it
        // has nothing saved until it parks.
        int ti = rt.Push(NewObj(rt, Obj.TyThread, TH_LEN));
        if (Val.IsNil(rt.R(ti))) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STATUS, Val.Fixnum(ST_RUNNABLE));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_ID, Val.Fixnum(0));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_TOKEN, Val.Fixnum(-1));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_BINDINGS, Maps.Empty(rt));
        int tsi = rt.Push(Vec.Empty(rt));
        long ts = Vec.Conj(rt, rt.R(tsi), rt.R(ti));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_THREADS, ts);
        long outv = rt.R(si);
        rt.roots.Singletons[Rt.SingSched] = outv;
        rt.PopTo(bas);
        rt.schedInstalled = true;
        rt.SetSliceEnd(rt.steps + SLICE);
        return outv;
    }

    public static long CurrentThread(Rt rt) {
        long s = Sched(rt);
        if (Val.IsNil(s)) return Val.Nil;
        return Vec.Nth(rt, rt.Slot(s, SC_THREADS), (int) Fx(rt.Slot(s, SC_CURRENT)));
    }

    // --- saving and restoring a VM state ------------------------------------

    /// Save the live VM state into `th`, then leave the interpreter EMPTY.
    ///
    /// The stack becomes a `Obj.TyNode` -- an ordinary Vals object -- which is the
    /// whole trick: the collector traces it as it traces anything, and a parked
    /// thread holding the only reference to a value keeps that value alive with
    /// no new rule.
    public static void SaveCurrentState(Rt rt, long th) {
        int bas = rt.Mark();
        int ti = rt.Push(th);
        int n = rt.roots.StackTop;
        long sv = NewObj(rt, Obj.TyNode, n);
        if (Val.IsNil(sv)) { rt.PopTo(bas); return; }
        for (int i = 0; i < n; i++) rt.SetSlot(Val.AsHeap(sv), i, rt.roots.Stack[i]);
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STACK, sv);

        long fb = NewObj(rt, Obj.TyRaw, rt.frames.Count * FRAME_REC);
        if (!Val.IsNil(fb)) {
            long a = Val.AsHeap(fb) + Obj.Hdr;
            for (int k = 0; k < rt.frames.Count; k++) {
                Frame f = rt.frames[k];
                long o = a + (long) k * FRAME_REC;
                rt.gc.sp.WriteU32(o, f.Fp);
                rt.gc.sp.WriteU32(o + 4, f.Ip);
                rt.gc.sp.WriteU32(o + 8, f.End);
                rt.gc.sp.WriteU32(o + 12, f.RetTo);
                rt.gc.sp.WriteU32(o + 16, f.Handlers);
                // The two AOT words. This runtime compiles nothing, so they are
                // written as "none" rather than omitted -- the record has to be
                // the same width in every build.
                rt.gc.sp.WriteU32(o + 20, -1);
                rt.gc.sp.WriteU32(o + 24, -1);
            }
        }
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_FRAMES, fb);

        long hb = NewObj(rt, Obj.TyRaw, rt.handlers.Count * 16);
        if (!Val.IsNil(hb)) {
            long a = Val.AsHeap(hb) + Obj.Hdr;
            for (int k = 0; k < rt.handlers.Count; k++) {
                Rt.Handler h = rt.handlers[k];
                long o = a + (long) k * 16;
                rt.gc.sp.WriteU32(o, h.frame);
                rt.gc.sp.WriteU32(o + 4, h.stackTop);
                rt.gc.sp.WriteU32(o + 8, h.target);
                rt.gc.sp.WriteU32(o + 12, h.shadow);
            }
        }
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_HANDLERS, hb);
        rt.PopTo(bas);

        rt.frames.Clear();
        rt.handlers.Clear();
        rt.roots.StackTop = 0;
    }

    static void RestoreState(Rt rt, long th) {
        long sv = rt.Slot(th, TH_STACK);
        rt.frames.Clear();
        rt.handlers.Clear();
        rt.roots.StackTop = 0;
        if (!Val.IsNil(sv)) {
            int n = Obj.Len(rt.gc.sp, Val.AsHeap(sv));
            if (rt.roots.Stack.Length < n + 8) rt.roots.Stack = new long[n + 8];
            for (int i = 0; i < n; i++) rt.roots.Stack[i] = rt.Slot(sv, i);
            rt.roots.StackTop = n;
        }
        long fb = rt.Slot(th, TH_FRAMES);
        if (!Val.IsNil(fb)) {
            int n = Obj.Len(rt.gc.sp, Val.AsHeap(fb)) / FRAME_REC;
            long a = Val.AsHeap(fb) + Obj.Hdr;
            for (int k = 0; k < n; k++) {
                long o = a + (long) k * FRAME_REC;
                Frame f = new Frame();
                f.Fp = rt.gc.sp.ReadU32(o);
                f.Ip = rt.gc.sp.ReadU32(o + 4);
                f.End = rt.gc.sp.ReadU32(o + 8);
                f.RetTo = rt.gc.sp.ReadU32(o + 12);
                f.Handlers = rt.gc.sp.ReadU32(o + 16);
                rt.frames.Add(f);
            }
        }
        long hb = rt.Slot(th, TH_HANDLERS);
        if (!Val.IsNil(hb)) {
            int n = Obj.Len(rt.gc.sp, Val.AsHeap(hb)) / 16;
            long a = Val.AsHeap(hb) + Obj.Hdr;
            for (int k = 0; k < n; k++) {
                long o = a + (long) k * 16;
                Rt.Handler h = new Rt.Handler();
                h.frame = rt.gc.sp.ReadU32(o);
                h.stackTop = rt.gc.sp.ReadU32(o + 4);
                h.target = rt.gc.sp.ReadU32(o + 8);
                h.shadow = rt.gc.sp.ReadU32(o + 12);
                rt.handlers.Add(h);
            }
        }
    }

    // --- parking ------------------------------------------------------------

    /// Signal a park. `parkOn` is the wake key; the PARK sentinel in `thrown`
    /// is what makes the interpreter unwind outv to the scheduler.
    public static long Park(Rt rt, long on) {
        rt.parkOn = on;
        rt.thrown = Val.Park;
        return Val.Nil;
    }

    // --- spawning -----------------------------------------------------------

    /// A new green thread running `f` (no arguments).
    ///
    /// It INHERITS A SNAPSHOT of its spawner's dynamic bindings, which is what
    /// Clojure conveys to `future` and agents, and what somebody debugging at
    /// three in the morning will assume. A snapshot: later `binding` in the
    /// spawner does not reach the child.
    public static long Spawn(Rt rt, long f) {
        // Rooted FIRST: `ensureSched` allocates, and `f` is a host local.
        int bas = rt.Mark();
        int fi = rt.Push(f);
        EnsureSched(rt);
        int ti = rt.Push(NewObj(rt, Obj.TyThread, TH_LEN));
        if (Val.IsNil(rt.R(ti))) { rt.PopTo(bas); return Val.Nil; }
        int si = rt.Push(Sched(rt));
        long id = Fx(rt.Slot(rt.R(si), SC_NEXTID));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_NEXTID, Val.Fixnum(id + 1));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STATUS, Val.Fixnum(ST_NEW));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_ID, Val.Fixnum(id));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_TOKEN, Val.Fixnum(-1));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_ENTRY, rt.R(fi));
        long binds = rt.roots.Singletons[Rt.SingBindings];
        if (Val.IsNil(binds)) binds = Maps.Empty(rt);
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_BINDINGS, binds);
        int tsi = rt.Push(rt.Slot(rt.R(si), SC_THREADS));
        long nts = Vec.Conj(rt, rt.R(tsi), rt.R(ti));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_THREADS, nts);
        long outv = rt.R(ti);
        rt.PopTo(bas);
        return outv;
    }

    // --- waiters ------------------------------------------------------------

    static long Waiters(Rt rt) { return rt.Slot(Sched(rt), SC_WAITERS); }

    /// A waiter records WHICH THREAD is parked on WHICH PORT. Slots are reused
    /// through a free list rather than compacted, because a token is an index
    /// into this vector and compacting would invalidate every one held.
    static long NewWaiter(Rt rt, long kind, long port) {
        int bas = rt.Mark();
        int pi = rt.Push(port);
        int si = rt.Push(Sched(rt));
        long free = Fx(rt.Slot(rt.R(si), SC_WFREE));
        long token;
        int wi;
        if (free >= 0) {
            long w = Vec.Nth(rt, rt.Slot(rt.R(si), SC_WAITERS), (int) free);
            rt.SetSlot(Val.AsHeap(rt.R(si)), SC_WFREE, rt.Slot(w, W_NEXT));
            token = free;
            wi = rt.Push(w);
        } else {
            long w = NewObj(rt, Obj.TyNode, W_LEN);
            if (Val.IsNil(w)) { rt.PopTo(bas); return -1; }
            wi = rt.Push(w);
            int wsi = rt.Push(rt.Slot(rt.R(si), SC_WAITERS));
            token = Vec.Count(rt, rt.R(wsi));
            long nws = Vec.Conj(rt, rt.R(wsi), rt.R(wi));
            rt.SetSlot(Val.AsHeap(rt.R(si)), SC_WAITERS, nws);
        }
        rt.SetSlot(Val.AsHeap(rt.R(wi)), W_KIND, Val.Fixnum(kind));
        rt.SetSlot(Val.AsHeap(rt.R(wi)), W_PORT, rt.R(pi));
        rt.SetSlot(Val.AsHeap(rt.R(wi)), W_NEXT, Val.Fixnum(-1));
        rt.SetSlot(Val.AsHeap(rt.R(wi)), W_THREAD, CurrentThread(rt));
        rt.PopTo(bas);
        return token;
    }

    static void FreeWaiter(Rt rt, long token) {
        if (token < 0) return;
        long s = Sched(rt);
        long w = Vec.Nth(rt, rt.Slot(s, SC_WAITERS), (int) token);
        if (w == Val.NotFound || Val.IsNil(w)) return;
        rt.SetSlot(Val.AsHeap(w), W_THREAD, Val.Nil);
        rt.SetSlot(Val.AsHeap(w), W_PORT, Val.Nil);
        rt.SetSlot(Val.AsHeap(w), W_NEXT, rt.Slot(s, SC_WFREE));
        rt.SetSlot(Val.AsHeap(s), SC_WFREE, Val.Fixnum(token));
    }

    static long ParkOnPort(Rt rt, long kind, long port) {
        int bas = rt.Mark();
        int pi = rt.Push(port);
        long token = NewWaiter(rt, kind, rt.R(pi));
        long th = CurrentThread(rt);
        if (!Val.IsNil(th)) rt.SetSlot(Val.AsHeap(th), TH_TOKEN, Val.Fixnum(token));
        long pv = rt.R(pi);
        rt.PopTo(bas);
        return Park(rt, pv);
    }

    static void WakeWaiter(Rt rt, long w) {
        long th = rt.Slot(w, W_THREAD);
        if (Val.IsNil(th)) return;
        rt.SetSlot(Val.AsHeap(th), TH_STATUS, Val.Fixnum(ST_RUNNABLE));
        rt.SetSlot(Val.AsHeap(th), TH_PARK_ON, Val.Nil);
        long token = Fx(rt.Slot(th, TH_TOKEN));
        rt.SetSlot(Val.AsHeap(th), TH_TOKEN, Val.Fixnum(-1));
        FreeWaiter(rt, token);
    }

    /// Make every thread waiting on `p` runnable again.
    ///
    /// They RE-EXECUTE the call they parked in, which is what makes "wake"
    /// correct without anyone reasoning about who gets the value: whoever runs
    /// first takes it, and the others simply park again.
    public static void WakeOn(Rt rt, long p) {
        long ws = Waiters(rt);
        int n = Vec.Count(rt, ws);
        for (int i = 0; i < n; i++) {
            long w = Vec.Nth(rt, ws, i);
            if (Val.IsNil(w) || Val.IsNil(rt.Slot(w, W_THREAD))) continue;
            if (rt.Slot(w, W_PORT) == p) WakeWaiter(rt, w);
        }
    }

    // --- channels -----------------------------------------------------------

    /// A pair of ends. Each is a port whose peer is the other; sending on one
    /// arrives on the other's inbox.
    public static long Channel(Rt rt, long cap, long label) {
        int bas = rt.Mark();
        EnsureSched(rt);
        int li = rt.Push(label);
        int si = rt.Push(Sched(rt));
        long id = Fx(rt.Slot(rt.R(si), SC_NEXTID));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_NEXTID, Val.Fixnum(id + 2));
        int ai = rt.Push(NewPort(rt, id, cap, rt.R(li)));
        int bi = rt.Push(NewPort(rt, id + 1, cap, rt.R(li)));
        rt.SetSlot(Val.AsHeap(rt.R(ai)), PT_PEER, rt.R(bi));
        rt.SetSlot(Val.AsHeap(rt.R(bi)), PT_PEER, rt.R(ai));
        int vi = rt.Push(Vec.Empty(rt));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(ai)));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(bi)));
        long outv = rt.R(vi);
        rt.PopTo(bas);
        return outv;
    }

    static long NewPort(Rt rt, long id, long cap, long label) {
        int bas = rt.Mark();
        int li = rt.Push(label);
        int pi = rt.Push(NewObj(rt, Obj.TyPort, PT_LEN));
        if (Val.IsNil(rt.R(pi))) { rt.PopTo(bas); return Val.Nil; }
        long p = Val.AsHeap(rt.R(pi));
        rt.SetSlot(p, PT_ID, Val.Fixnum(id));
        rt.SetSlot(p, PT_STATE, Val.Fixnum(P_OPEN));
        rt.SetSlot(p, PT_CAP, Val.Fixnum(cap));
        rt.SetSlot(p, PT_INBOX, Vec.Empty(rt));
        rt.SetSlot(p, PT_HEAD, Val.Fixnum(0));
        rt.SetSlot(p, PT_BYTES, Val.Fixnum(0));
        rt.SetSlot(p, PT_PEER, Val.Nil);
        rt.SetSlot(p, PT_LABEL, rt.R(li));
        rt.SetSlot(p, PT_KIND, Val.Fixnum(K_CHANNEL));
        rt.SetSlot(p, PT_ROOT, Val.Nil);
        rt.SetSlot(p, PT_FORMAT, Val.Nil);
        rt.SetSlot(p, PT_OPTS, Val.Nil);
        rt.SetSlot(p, PT_BINARY, Val.False);
        rt.SetSlot(p, PT_PRESENTED, Val.Fixnum(-1));
        long outv = rt.R(pi);
        rt.PopTo(bas);
        return outv;
    }

    static int InboxCount(Rt rt, long p) {
        return Vec.Count(rt, rt.Slot(p, PT_INBOX)) - (int) Fx(rt.Slot(p, PT_HEAD));
    }

    static void Enqueue(Rt rt, long p, long v) {
        int bas = rt.Mark();
        int pi = rt.Push(p), vi = rt.Push(v);
        int ibi = rt.Push(rt.Slot(rt.R(pi), PT_INBOX));
        long nib = Vec.Conj(rt, rt.R(ibi), rt.R(vi));
        rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_INBOX, nib);
        rt.PopTo(bas);
    }

    static long Dequeue(Rt rt, long p) {
        int head = (int) Fx(rt.Slot(p, PT_HEAD));
        long ib = rt.Slot(p, PT_INBOX);
        long v = Vec.Nth(rt, ib, head);
        if (head + 1 >= Vec.Count(rt, ib)) {
            // Drained: drop the backing vector so nothing stays reachable.
            rt.SetSlot(Val.AsHeap(p), PT_INBOX, Vec.Empty(rt));
            rt.SetSlot(Val.AsHeap(p), PT_HEAD, Val.Fixnum(0));
        } else {
            rt.SetSlot(Val.AsHeap(p), PT_HEAD, Val.Fixnum(head + 1));
        }
        return v == Val.NotFound ? Val.Nil : v;
    }

    /// Send `v` to the PEER's inbox. Parks when the peer is full.
    public static long Send(Rt rt, long p, long v) {
        if (!IsPort(rt, p)) throw new System.InvalidCastException("send wants a port, got " + rt.Describe(p));
        long st = Fx(rt.Slot(p, PT_STATE));
        if (st == P_CLOSED || st == P_ORPHANED) {
            throw new System.InvalidOperationException("send: this port is closed");
        }
        long peer = rt.Slot(p, PT_PEER);
        if (Val.IsNil(peer)) throw new System.InvalidOperationException("send: the other end is gone");
        long cap = Fx(rt.Slot(peer, PT_CAP));
        if (cap > 0 && InboxCount(rt, peer) >= cap) {
            // FULL: park on the PEER, because that is what a receive there
            // frees. Parking on this end would never be woken.
            return ParkOnPort(rt, WK_SEND, peer);
        }
        int bas = rt.Mark();
        int pi = rt.Push(peer);
        Enqueue(rt, rt.R(pi), v);
        WakeOn(rt, rt.R(pi));
        rt.PopTo(bas);
        return Val.Nil;
    }

    /// Take from this port's inbox. Parks when empty.
    public static long Receive(Rt rt, long p) {
        if (!IsPort(rt, p)) throw new System.InvalidCastException("receive wants a port, got " + rt.Describe(p));
        int bas = rt.Mark();
        int pi = rt.Push(p);
        if (InboxCount(rt, rt.R(pi)) > 0) {
            int vi = rt.Push(Dequeue(rt, rt.R(pi)));
            // Space freed: whoever was blocked sending here can try again.
            WakeOn(rt, rt.R(pi));
            long outv = rt.R(vi);
            rt.PopTo(bas);
            return outv;
        }
        long st = Fx(rt.Slot(rt.R(pi), PT_STATE));
        // Drained and finished cleanly: end of stream, a normal answer.
        if (st == P_CLOSED || st == P_HALF) { rt.PopTo(bas); return Val.Nil; }
        long peer = rt.Slot(rt.R(pi), PT_PEER);
        if (Val.IsNil(peer)) {
            rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(P_ORPHANED));
            rt.PopTo(bas);
            // Never park: a script blocked for ever on an end that hung up is
            // the same failure as a leaked handle, seen from the other side.
            throw new System.InvalidOperationException(
                "receive: the other end of this port is gone, so this can never complete");
        }
        long pst = Fx(rt.Slot(peer, PT_STATE));
        if (pst == P_CLOSED || pst == P_HALF || pst == P_ORPHANED) {
            rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(P_HALF));
            rt.PopTo(bas);
            return Val.Nil;
        }
        long target = rt.R(pi);
        rt.PopTo(bas);
        return ParkOnPort(rt, WK_RECEIVE, target);
    }

    public static long Close(Rt rt, long p) {
        if (!IsPort(rt, p)) return Val.Nil;
        rt.SetSlot(Val.AsHeap(p), PT_STATE, Val.Fixnum(P_CLOSED));
        long peer = rt.Slot(p, PT_PEER);
        if (!Val.IsNil(peer)) WakeOn(rt, peer);
        WakeOn(rt, p);
        return Val.Nil;
    }

    // --- joining ------------------------------------------------------------

    public static long Join(Rt rt, long t) {
        if (!IsThread(rt, t)) throw new System.InvalidCastException("join wants a thread, got " + rt.Describe(t));
        long st = Fx(rt.Slot(t, TH_STATUS));
        if (st == ST_DONE) return rt.Slot(t, TH_RESULT);
        if (st == ST_FAILED) {
            // The thread's failure becomes the joiner's, which is what makes a
            // silent dead thread impossible to ignore.
            rt.thrown = rt.Slot(t, TH_RESULT);
            return Val.Nil;
        }
        // Parked ON THE THREAD OBJECT, which `settle` wakes when it finishes.
        // A thread is a wake key like any port -- that is why `wakeOn` takes a
        // value rather than a port.
        return ParkOnPort(rt, WK_JOIN, t);
    }

    public static long State(Rt rt, long t) {
        if (!IsThread(rt, t)) return Val.Nil;
        switch ((int) Fx(rt.Slot(t, TH_STATUS))) {
            case ST_NEW:
            case ST_RUNNABLE: return Str.Keyword(rt, null, "runnable");
            case ST_PARKED: return Str.Keyword(rt, null, "parked");
            case ST_DONE: return Str.Keyword(rt, null, "done");
            default: return Str.Keyword(rt, null, "failed");
        }
    }

    // --- the scheduler ------------------------------------------------------

    /// Record the outcome of the thread that was running, and take it off.
    static void Settle(Rt rt, long result) {
        long th = CurrentThread(rt);
        if (Val.IsNil(th)) return;
        int bas = rt.Mark();
        int ti = rt.Push(th);
        // Dynamic bindings travel WITH the thread.
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_BINDINGS, rt.roots.Singletons[Rt.SingBindings]);
        if (!Val.IsNil(rt.parkOn)) {
            long on = rt.parkOn;
            rt.parkOn = Val.Nil;
            // AND the sentinel. A park travels as `thrown = PARK` so that every
            // frame between the builtin and here unwinds; this is where it stops
            // being in flight. Leaving it set made the NEXT thread's clean
            // finish read as a failure -- it ran fine, returned a value, and
            // `settle` recorded the sentinel as its result.
            rt.thrown = Val.Nil;
            if (on == PARK_YIELD) {
                // A COURTESY yield: still runnable, and it must NOT rewind.
                // Re-executing the `yield` yields again, for ever.
                rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STATUS, Val.Fixnum(ST_RUNNABLE));
                rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_PARK_ON, Val.Nil);
            } else {
                int oi = rt.Push(on);
                rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STATUS, Val.Fixnum(ST_PARKED));
                rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_PARK_ON, rt.R(oi));
            }
            SaveCurrentState(rt, rt.R(ti));
        } else if (!Val.IsNil(rt.thrown)) {
            int ei = rt.Push(rt.thrown);
            rt.thrown = Val.Nil;
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STATUS, Val.Fixnum(ST_FAILED));
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_RESULT, rt.R(ei));
            WakeOn(rt, rt.R(ti));
            rt.frames.Clear(); rt.handlers.Clear(); rt.roots.StackTop = 0;
        } else {
            int ri = rt.Push(result);
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STATUS, Val.Fixnum(ST_DONE));
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_RESULT, rt.R(ri));
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STACK, Val.Nil);
            WakeOn(rt, rt.R(ti));
            rt.frames.Clear(); rt.handlers.Clear(); rt.roots.StackTop = 0;
        }
        rt.PopTo(bas);
    }

    /// Round-robin from just after the current thread. DETERMINISTIC by
    /// construction: no randomness, no clock, no host-order dependence. That is
    /// what lets three runtimes agree on an interleaving.
    static int Pick(Rt rt) {
        long s = Sched(rt);
        long ts = rt.Slot(s, SC_THREADS);
        int n = Vec.Count(rt, ts);
        if (n == 0) return -1;
        int cur = (int) Fx(rt.Slot(s, SC_CURRENT));
        for (int k = 1; k <= n; k++) {
            int i = (cur + k) % n;
            long th = Vec.Nth(rt, ts, i);
            if (Val.IsNil(th) || th == Val.NotFound) continue;
            long st = Fx(rt.Slot(th, TH_STATUS));
            if (st == ST_NEW || st == ST_RUNNABLE) return i;
        }
        return -1;
    }

    static void RunOne(Rt rt, int i) {
        long s = Sched(rt);
        rt.SetSlot(Val.AsHeap(s), SC_CURRENT, Val.Fixnum(i));
        long th = Vec.Nth(rt, rt.Slot(s, SC_THREADS), i);
        if (Val.IsNil(th) || th == Val.NotFound) return;
        int bas = rt.Mark();
        int ti = rt.Push(th);
        long st = Fx(rt.Slot(rt.R(ti), TH_STATUS));
        rt.roots.Singletons[Rt.SingBindings] = rt.Slot(rt.R(ti), TH_BINDINGS);
        rt.SetSliceEnd(rt.steps + SLICE);
        long v;
        if (st == ST_NEW) {
            rt.frames.Clear(); rt.handlers.Clear(); rt.roots.StackTop = 0;
            long f = rt.Slot(rt.R(ti), TH_ENTRY);
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STATUS, Val.Fixnum(ST_RUNNABLE));
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_ENTRY, Val.Nil);
            v = RunEntry(rt, f);
        } else {
            RestoreState(rt, rt.R(ti));
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STACK, Val.Nil);
            v = rt.Run(0);
        }
        rt.PopTo(bas);
        Settle(rt, v);
    }

    static long RunEntry(Rt rt, long f) {
        int calleeAt = rt.roots.StackTop;
        rt.VPush(f);
        if (!rt.Enter(f, calleeAt, 0)) { rt.roots.StackTop = calleeAt; return Val.Nil; }
        return rt.Run(0);
    }

    /// Is the program over?
    ///
    /// WHEN THE ENTRY FUNCTION HAS RETURNED AND NOTHING ELSE CAN RUN, IT IS.
    /// Its value IS the answer, so there is nothing left to compute; a thread
    /// still parked then is waiting for something that is never coming.
    /// Threads still runnable get to finish first, because `pick` is tried
    /// before this.
    ///
    /// Defining completion any other way means a program that leaves a reader
    /// parked never terminates.
    static bool MainFinished(Rt rt) {
        long th = Vec.Nth(rt, rt.Slot(Sched(rt), SC_THREADS), 0);
        if (Val.IsNil(th) || th == Val.NotFound) return true;
        long st = Fx(rt.Slot(th, TH_STATUS));
        return st == ST_DONE || st == ST_FAILED;
    }

    static long MainResult(Rt rt) {
        long th = Vec.Nth(rt, rt.Slot(Sched(rt), SC_THREADS), 0);
        long r = (Val.IsNil(th) || th == Val.NotFound) ? Val.Nil : rt.Slot(th, TH_RESULT);
        return r;
    }

    /// The scheduler hook: settle whatever just stopped, then drive.
    public static long Scheduler(Rt rt, long first) {
        Settle(rt, first);
        return Drive(rt);
    }

    public static long Drive(Rt rt) {
        for (;;) {
            int i = Pick(rt);
            if (i >= 0) { RunOne(rt, i); continue; }
            if (MainFinished(rt)) { rt.status = 0; return MainResult(rt); }
            // Nothing runnable and the entry has not returned: the remaining
            // threads are waiting on each other. Named rather than hung.
            long s = Sched(rt);
            long ts = rt.Slot(s, SC_THREADS);
            int n = Vec.Count(rt, ts);
            int stuck = 0;
            for (int k = 0; k < n; k++) {
                long th = Vec.Nth(rt, ts, k);
                if (!Val.IsNil(th) && th != Val.NotFound
                    && Fx(rt.Slot(th, TH_STATUS)) == ST_PARKED) stuck++;
            }
            rt.status = 0;
            throw new System.InvalidOperationException(
                "every thread is parked: " + stuck + " waiting, and nothing can wake them");
        }
    }
}
