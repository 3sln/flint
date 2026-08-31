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

    /// `PT_INBOX` is a FIXED ring of `PT_RING` slots with a sequence word each,
    /// reserved by compare-and-swap and published by the sequence store. It was
    /// a persistent vector and a read cursor, and `conj`-then-store is a
    /// read-modify-write with an allocation in the middle -- two executors
    /// sending into one channel lost exactly half the traffic. See the Rust
    /// `port_enqueue` for the protocol, which this mirrors step for step.
    public const int PT_ID = 0, PT_STATE = 1, PT_CAP = 2, PT_INBOX = 3,
        PT_READ = 4, PT_BYTES = 5, PT_PEER = 6, PT_LABEL = 7, PT_KIND = 8,
        PT_ROOT = 9, PT_FORMAT = 10, PT_OPTS = 11, PT_BINARY = 12,
        PT_WRITE = 13, PT_RING = 14, PT_LEN = 15;

    /// How many messages a bridge end's ring holds. Its `PT_CAP` bounds BYTES,
    /// which is the bound that matters for memory; this bounds the count so the
    /// ring can be one fixed allocation.
    public const long RingMessages = 64;

    /// A port's STATE. `P_PENDING` is an `open` the host has not answered yet
    /// and `P_REFUSED` is one it declined -- distinct from `P_CLOSED`, because
    /// "you may not have this" and "this is finished" are different answers.
    ///
    /// THE NUMBERS ARE THE RUST'S, and they are an ABI: `flint_port_state`
    /// answers one of them to a host that reads it as a number. A port that
    /// merely spelled the same six names in a different order would agree with
    /// the native runtime on every transcript that renders them and disagree
    /// with every host that reads them.
    public const int P_PENDING = 0, P_OPEN = 1, P_CLOSED = 2, P_REFUSED = 3,
                     P_HALF = 4, P_ORPHANED = 5;
    public const int K_CHANNEL = 0, K_FLINT = 1, K_HOST = 2, K_GLOBAL = 3;

    /// A channel's default buffer, in MESSAGES.
    public const long DEFAULT_CAP = 16;

    /// How much a host port will buffer before a send parks.
    public const long DEFAULT_HOST_CAP = 1 << 20;

    /// What the host is told about, drained through `DrainEvents`.
    public const int EV_OPEN = 1, EV_MESSAGE = 2, EV_CLOSED = 3;

    // --- waiter ------------------------------------------------------------

    // Everything that parks parks the same way -- `open`, a send to a full
    // port, a receive on an empty one -- through one table with one token type.
    // A token is `(generation << 16) | (index + 1)`: a bare index is reusable,
    // so a late or duplicated reply from the host would resume whatever now
    // occupies that slot -- a wrong thread woken with a stranger's value, and
    // unfindable in production. The generation makes that a rejection instead.
    public const int W_GEN = 0, W_THREAD = 1, W_KIND = 2, W_PORT = 3,
                     W_NEXT = 4, W_LEN = 5;
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

    public static long Sched(Rt rt) { return rt.roots.shared.Singletons[Rt.SingSched]; }

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
        rt.roots.shared.Singletons[Rt.SingSched] = outv;
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
        long binds = rt.roots.shared.Singletons[Rt.SingBindings];
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
    /// through a free list rather than compacted, because a token names an
    /// index into this vector and compacting would invalidate every one held.
    static long NewWaiter(Rt rt, long kind, long port) {
        int bas = rt.Mark();
        int pi = rt.Push(port);
        int si = rt.Push(Sched(rt));
        int ti = rt.Push(CurrentThread(rt));
        long free = Fx(rt.Slot(rt.R(si), SC_WFREE));
        long idx;
        int wi;
        if (free >= 0) {
            long w = Vec.Nth(rt, rt.Slot(rt.R(si), SC_WAITERS), (int) free);
            rt.SetSlot(Val.AsHeap(rt.R(si)), SC_WFREE, Val.Fixnum(Fx(rt.Slot(w, W_NEXT))));
            idx = free;
            wi = rt.Push(w);
        } else {
            long w = NewObj(rt, Obj.TyNode, W_LEN);
            if (Val.IsNil(w)) { rt.PopTo(bas); return -1; }
            wi = rt.Push(w);
            rt.SetSlot(Val.AsHeap(rt.R(wi)), W_GEN, Val.Fixnum(0));
            int wsi = rt.Push(rt.Slot(rt.R(si), SC_WAITERS));
            long nws = Vec.Conj(rt, rt.R(wsi), rt.R(wi));
            idx = Vec.Count(rt, nws) - 1;
            rt.SetSlot(Val.AsHeap(rt.R(si)), SC_WAITERS, nws);
        }
        rt.SetSlot(Val.AsHeap(rt.R(wi)), W_THREAD, rt.R(ti));
        rt.SetSlot(Val.AsHeap(rt.R(wi)), W_KIND, Val.Fixnum(kind));
        rt.SetSlot(Val.AsHeap(rt.R(wi)), W_PORT, rt.R(pi));
        long gen = Fx(rt.Slot(rt.R(wi), W_GEN));
        rt.PopTo(bas);
        // 1-based, so that 0 is never a valid token: a host ABI where the zero
        // value means something is a trap waiting for an uninitialised variable.
        return (gen << 16) | (idx + 1);
    }

    static long WaiterAt(Rt rt, long token) {
        if (token <= 0 || (token & 0xFFFF) == 0) return Val.Nil;
        int idx = (int) (token & 0xFFFF) - 1;
        long gen = token >> 16;
        long w = Vec.Nth(rt, Waiters(rt), idx);
        if (w == Val.NotFound || Val.IsNil(w)) return Val.Nil;
        if (Fx(rt.Slot(w, W_GEN)) != gen || Val.IsNil(rt.Slot(w, W_THREAD))) return Val.Nil;
        return w;
    }

    /// Free a waiter slot and BUMP ITS GENERATION, so a token naming it can
    /// never be honoured twice.
    static void FreeWaiter(Rt rt, long token) {
        long w = WaiterAt(rt, token);
        if (Val.IsNil(w)) return;
        long idx = (token & 0xFFFF) - 1;
        long gen = Fx(rt.Slot(w, W_GEN));
        rt.SetSlot(Val.AsHeap(w), W_GEN, Val.Fixnum((gen + 1) & 0xFFFF));
        rt.SetSlot(Val.AsHeap(w), W_THREAD, Val.Nil);
        rt.SetSlot(Val.AsHeap(w), W_PORT, Val.Nil);
        long s = Sched(rt);
        rt.SetSlot(Val.AsHeap(w), W_NEXT, rt.Slot(s, SC_WFREE));
        rt.SetSlot(Val.AsHeap(s), SC_WFREE, Val.Fixnum(idx));
    }

    /// How many green threads are parked with a token outstanding. A host that
    /// never answers leaks these; the deadlock report names them.
    public static int OutstandingWaiters(Rt rt) {
        long s = Sched(rt);
        if (Val.IsNil(s)) return 0;
        long ws = Waiters(rt);
        int n = Vec.Count(rt, ws), c = 0;
        for (int i = 0; i < n; i++) {
            long w = Vec.Nth(rt, ws, i);
            if (!Val.IsNil(w) && w != Val.NotFound && !Val.IsNil(rt.Slot(w, W_THREAD))) c++;
        }
        return c;
    }

    /// Park until there is room in `p`'s ring.
    ///
    /// The re-check after registering is not belt and braces: `WakeOn` reaches
    /// only waiters ALREADY in the list, so a receive that drains the ring
    /// between the failed reservation and the registration would wake nobody.
    static long ParkForSpace(Rt rt, long p) {
        int bas = rt.Mark();
        int pi = rt.Push(p);
        long token = NewWaiter(rt, WK_SEND, rt.R(pi));
        long th = CurrentThread(rt);
        if (!Val.IsNil(th)) rt.SetSlot(Val.AsHeap(th), TH_TOKEN, Val.Fixnum(token));
        long ring = Fx(rt.Slot(rt.R(pi), PT_RING));
        if (InboxCount(rt, rt.R(pi)) < ring) {
            FreeWaiter(rt, token);
            if (!Val.IsNil(th)) rt.SetSlot(Val.AsHeap(th), TH_TOKEN, Val.Fixnum(-1));
            rt.PopTo(bas);
            // A yield rather than a park: the thread stays runnable and the
            // send runs again on its next turn.
            return Park(rt, PARK_YIELD);
        }
        long pv = rt.R(pi);
        rt.PopTo(bas);
        return Park(rt, pv);
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

    /// A coupled pair. What goes into one comes out of the other, both ways.
    public static long Channel(Rt rt, long cap, long label) {
        EnsureSched(rt);
        int bas = rt.Mark();
        int li = rt.Push(label);
        int ai = rt.Push(NewPort(rt, cap, rt.R(li), K_CHANNEL, P_OPEN, Val.Nil));
        int bi = rt.Push(NewPort(rt, cap, rt.R(li), K_CHANNEL, P_OPEN, Val.Nil));
        LinkPeers(rt, rt.R(ai), rt.R(bi));
        int vi = rt.Push(Vec.Empty(rt));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(ai)));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(bi)));
        long outv = rt.R(vi);
        rt.PopTo(bas);
        return outv;
    }

    /// Install a GLOBAL port the host owns, and hand back the handle
    /// (`doc/decisions/0027`).
    ///
    /// The inversion that file exists to make: a sandbox does not manufacture
    /// an endpoint and offer it up, it is GIVEN one. `hostId` is the HOST's,
    /// not this sandbox's -- it means the same thing on both sides, which is
    /// what makes a handle sendable between two sandboxes at all.
    ///
    /// The object in this heap carries the id and nothing else that crosses:
    /// no pointer into host memory, no pointer out of it.
    public static long InstallGlobalPort(Rt rt, long hostId, long label, long format) {
        EnsureSched(rt);
        int bas = rt.Mark();
        int li = rt.Push(label), fi = rt.Push(format);
        int pi = rt.Push(NewPort(rt, DEFAULT_HOST_CAP, rt.R(li), K_GLOBAL, P_OPEN, rt.R(fi)));
        if (Val.IsNil(rt.R(pi))) { rt.PopTo(bas); return Val.Nil; }
        // The HOST's id replaces the one `NewPort` minted from this sandbox's
        // counter. A sandbox-local id would mean something different in every
        // other sandbox, which is the coupling `0027` removes.
        rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_ID, Val.Fixnum(hostId));
        // Rooted for as long as the host says it exists: the host holds the
        // other end, so this one cannot be reclaimed just because the guest
        // dropped its last reference.
        long slot = RootPort(rt, rt.R(pi));
        rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_ROOT, Val.Fixnum(slot));
        RegisterPort(rt, rt.R(pi));
        long outv = rt.R(pi);
        rt.PopTo(bas);
        return outv;
    }

    /// The system port: the one a sandbox is given at construction, if it is
    /// given one at all.
    public static long InstallSystemPort(Rt rt, long hostId, long label, long format) {
        long p = InstallGlobalPort(rt, hostId, label, format);
        if (Val.IsNil(p)) return Val.Nil;
        int bas = rt.Mark();
        int pi = rt.Push(p);
        int si = rt.Push(Sched(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_SYSTEM, rt.R(pi));
        long outv = rt.R(pi);
        rt.PopTo(bas);
        return outv;
    }

    /// The system port. NOT reachable from guest code, and that is the point.
    ///
    /// It is not a capability the sandbox holds, it is the TRANSPORT the
    /// sandbox is driven over: calls in arrive on it, and requests out -- for a
    /// capability, for another port -- leave on it. Handing it to guest code
    /// would make it ambient authority inside the sandbox, which is the thing
    /// `doc/decisions/0022` and `0027` both exist to prevent. There is no
    /// builtin that answers it; only the runtime looks it up.
    public static long SystemPort(Rt rt) {
        long s = Sched(rt);
        return Val.IsNil(s) ? Val.Nil : rt.Slot(s, SC_SYSTEM);
    }

    /// A port of any kind. The id comes from the scheduler so that every port
    /// in a sandbox has a distinct one, which is what the registry is keyed by.
    static long NewPort(Rt rt, long cap, long label, long kind, long state, long format) {
        int bas = rt.Mark();
        int li = rt.Push(label), fi = rt.Push(format);
        int pi = rt.Push(NewObj(rt, Obj.TyPort, PT_LEN));
        if (Val.IsNil(rt.R(pi))) { rt.PopTo(bas); return Val.Nil; }
        int si = rt.Push(Sched(rt));
        long id = Fx(rt.Slot(rt.R(si), SC_NEXTID));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_NEXTID, Val.Fixnum(id + 1));
        long p = Val.AsHeap(rt.R(pi));
        rt.SetSlot(p, PT_ID, Val.Fixnum(id));
        rt.SetSlot(p, PT_STATE, Val.Fixnum(state));
        rt.SetSlot(p, PT_CAP, Val.Fixnum(cap));
        // The ring, allocated ONCE: a send must not allocate. NOT on a K_HOST
        // end, which never has a message put in it -- both directions go
        // elsewhere. ONE array: a slot's own word says whether it is vacant.
        long ring = kind == K_CHANNEL ? System.Math.Max(cap, 1) : kind == K_HOST ? 0 : RingMessages;
        rt.SetSlot(p, PT_RING, Val.Fixnum(ring));
        if (ring == 0) {
            rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_INBOX, Val.Nil);
        } else {
            int sli = rt.Push(NewObj(rt, Obj.TyNode, (int) ring));
            for (int i = 0; i < ring; i++) rt.SetSlot(Val.AsHeap(rt.R(sli)), i, Val.Empty);
            rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_INBOX, rt.R(sli));
        }
        p = Val.AsHeap(rt.R(pi));
        rt.SetSlot(p, PT_READ, Val.Fixnum(0));
        rt.SetSlot(p, PT_WRITE, Val.Fixnum(0));
        rt.SetSlot(p, PT_BYTES, Val.Fixnum(0));
        // PEERS ARE LINKED BY ID, never by object. When one end is collected
        // its object is gone, and a field holding the peer would keep it alive
        // -- which is exactly what `doc/decisions/0006` says must not happen:
        // an unreachable flint end MEANS the script is finished with it.
        rt.SetSlot(p, PT_PEER, Val.Fixnum(-1));
        rt.SetSlot(p, PT_LABEL, rt.R(li));
        rt.SetSlot(p, PT_KIND, Val.Fixnum(kind));
        rt.SetSlot(p, PT_ROOT, Val.Fixnum(-1));
        rt.SetSlot(p, PT_FORMAT, rt.R(fi));
        rt.SetSlot(p, PT_OPTS, Maps.Empty(rt));
        rt.SetSlot(p, PT_BINARY, Val.Fixnum(0));
        RegisterPort(rt, rt.R(pi));
        if (kind == K_HOST) {
            long slot = RootPort(rt, rt.R(pi));
            rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_ROOT, Val.Fixnum(slot));
        }
        long outv = rt.R(pi);
        rt.PopTo(bas);
        return outv;
    }

    /// The registry. WEAK on purpose (`doc/decisions/0006`): the flint end of a
    /// port is ordinary reachable memory, and when the collector finds it
    /// unreachable that MEANS the script is finished with it. The scheduler
    /// keeps IDS, not references -- a strong list would pin every port for ever
    /// and there would be nothing to notice.
    static void RegisterPort(Rt rt, long p) {
        int id = (int) Fx(rt.Slot(p, PT_ID));
        int bas = rt.Mark();
        int pi = rt.Push(p);
        Interns t = rt.roots.shared.interns[Interns.PORT];
        rt.roots.shared.par.LockIntern(Interns.PORT);
        try {
            t.Lookup(id, v => false);
            if (t.NeedsGrow()) { t.Grow(); t.Lookup(id, v => false); }
            t.InsertAt(t.slot, id, rt.R(pi));
        } finally {
            rt.roots.shared.par.UnlockIntern(Interns.PORT);
        }
        int si = rt.Push(Sched(rt));
        int ii = rt.Push(rt.Slot(rt.R(si), SC_PORTS));
        long nids = Vec.Conj(rt, rt.R(ii), Val.Fixnum(id));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_PORTS, nids);
        rt.PopTo(bas);
    }

    /// Look a port up by id. A miss means the object has been collected --
    /// which, for a flint end, is what "the script is finished with it" looks
    /// like.
    public static long PortById(Rt rt, long id) {
        if (id < 0) return Val.Nil;
        Interns t = rt.roots.shared.interns[Interns.PORT];
        rt.roots.shared.par.LockIntern(Interns.PORT);
        try {
            long v = t.Lookup((int) id, x => Val.IsHeap(x)
                && Obj.Ty(rt.gc.sp, Val.AsHeap(x)) == Obj.TyPort
                && Fx(rt.Slot(x, PT_ID)) == id);
            return v == Val.NotFound ? Val.Nil : v;
        } finally {
            rt.roots.shared.par.UnlockIntern(Interns.PORT);
        }
    }

    /// A HOST end must outlive every flint reference to it, so it goes in
    /// `Singletons`, which the collector already traces. Returns the slot.
    static long RootPort(Rt rt, long p) {
        long[] sg = rt.roots.shared.Singletons;
        for (int i = Rt.SingCount; i < sg.Length; i++) {
            if (Val.IsNil(sg[i])) { sg[i] = p; return i; }
        }
        int old = sg.Length;
        long[] bigger = new long[System.Math.Max(old * 2, Rt.SingCount + 8)];
        System.Array.Copy(sg, bigger, old);
        for (int i = old; i < bigger.Length; i++) bigger[i] = Val.Nil;
        bigger[old] = p;
        rt.roots.shared.Singletons = bigger;
        return old;
    }

    static void UnrootPort(Rt rt, long p) {
        long slot = Fx(rt.Slot(p, PT_ROOT));
        long[] sg = rt.roots.shared.Singletons;
        if (slot >= 0 && slot < sg.Length) {
            sg[(int) slot] = Val.Nil;
            rt.SetSlot(Val.AsHeap(p), PT_ROOT, Val.Fixnum(-1));
        }
    }

    /// Link two ends. IDS ONLY, and the pairing is recorded in the scheduler as
    /// well, because when one end is collected its object is gone and the other
    /// end still has to be able to find out what happened to it.
    static void LinkPeers(Rt rt, long a, long b) {
        long ida = Fx(rt.Slot(a, PT_ID)), idb = Fx(rt.Slot(b, PT_ID));
        rt.SetSlot(Val.AsHeap(a), PT_PEER, Val.Fixnum(idb));
        rt.SetSlot(Val.AsHeap(b), PT_PEER, Val.Fixnum(ida));
        int bas = rt.Mark();
        int si = rt.Push(Sched(rt));
        long[][] pairs = { new long[]{ida, idb}, new long[]{idb, ida} };
        foreach (long[] xy in pairs) {
            int pi = rt.Push(rt.Slot(rt.R(si), SC_PAIRS));
            int ei = rt.Push(Vec.Empty(rt));
            rt.SetR(ei, Vec.Conj(rt, rt.R(ei), Val.Fixnum(xy[0])));
            rt.SetR(ei, Vec.Conj(rt, rt.R(ei), Val.Fixnum(xy[1])));
            long np = Vec.Conj(rt, rt.R(pi), rt.R(ei));
            rt.SetSlot(Val.AsHeap(rt.R(si)), SC_PAIRS, np);
            rt.PopTo(pi);
        }
        rt.PopTo(bas);
    }

    /// The peer of a port that may itself be gone.
    static long PeerOf(Rt rt, long p) { return PortById(rt, Fx(rt.Slot(p, PT_PEER))); }

    /// The peer of an id whose OBJECT has been collected. Read from the
    /// scheduler's pair list, which is the only place that survives it.
    static long PeerIdOfDead(Rt rt, long id) {
        long ps = rt.Slot(Sched(rt), SC_PAIRS);
        int n = Vec.Count(rt, ps);
        for (int i = 0; i < n; i++) {
            long e = Vec.Nth(rt, ps, i);
            if (Fx(Vec.Nth(rt, e, 0)) == id) return Fx(Vec.Nth(rt, e, 1));
        }
        return -1;
    }

    /// How many messages are in the ring, reservations included: a reserved
    /// slot is spoken for even before it is filled.
    static int InboxCount(Rt rt, long p) {
        return (int) System.Math.Max(0, Cursor(rt, p, PT_WRITE) - Cursor(rt, p, PT_READ));
    }

    static long Cursor(Rt rt, long p, int which) => Fx(SlotAtomic(rt, p, which));

    /// One slot, read atomically. Cursors and sequence words are fixnums like
    /// any other slot -- the collector sees nothing unusual -- and the atomic
    /// operates on the TAGGED word.
    static long SlotAtomic(Rt rt, long o, int i) =>
        rt.gc.sp.AtomicLoad(Obj.SlotAddr(Val.AsHeap(o), i));

    /// Compare-and-swap a slot, AND run the write barrier when it lands. See
    /// the Rust: a ring in the old generation pointing at a young value is an
    /// edge the collector finds only through the remembered set.
    static bool CasSlotBarriered(Rt rt, long o, int i, long want, long next) {
        long obj = Val.AsHeap(o);
        if (!rt.gc.sp.Cas(Obj.SlotAddr(obj, i), want, next)) return false;
        if (Val.IsHeap(next) && rt.gc.IsYoung(Val.AsHeap(next)) && !rt.gc.IsYoung(obj)) {
            rt.gc.Remember(obj, rt.roots);
        }
        return true;
    }

    static bool CasSlot(Rt rt, long o, int i, long want, long next) =>
        rt.gc.sp.Cas(Obj.SlotAddr(Val.AsHeap(o), i), want, next);

    /// Put `v` in `p`'s ring. False means full.
    ///
    /// ONE compare-and-swap: the slot's own word is the lease, and swapping
    /// Empty for the message both claims the slot and fills it. Mirrors the
    /// Rust, including why there is no sequence word.
    static bool Enqueue(Rt rt, long p, long v) {
        long ring = Fx(rt.Slot(p, PT_RING));
        if (ring == 0) return false;
        long inbox = rt.Slot(p, PT_INBOX);
        for (;;) {
            long w = Cursor(rt, p, PT_WRITE);
            long r = Cursor(rt, p, PT_READ);
            if (w - r >= ring) return false;
            int idx = (int) (w % ring);
            if (!CasSlot(rt, p, PT_WRITE, Val.Fixnum(w), Val.Fixnum(w + 1))) continue;
            if (CasSlotBarriered(rt, inbox, idx, Val.Empty, v)) return true;
        }
    }

    /// Take the next message, or nil. The mirror image: swap the message out
    /// for Empty, freeing the slot in the step that takes the value.
    static long Dequeue(Rt rt, long p) {
        long ring = Fx(rt.Slot(p, PT_RING));
        if (ring == 0) return Val.Nil;
        long inbox = rt.Slot(p, PT_INBOX);
        for (;;) {
            long r = Cursor(rt, p, PT_READ);
            if (r >= Cursor(rt, p, PT_WRITE)) return Val.Nil;
            int idx = (int) (r % ring);
            long v = SlotAtomic(rt, inbox, idx);
            if (v == Val.Empty) return Val.Nil;
            if (!CasSlot(rt, p, PT_READ, Val.Fixnum(r), Val.Fixnum(r + 1))) continue;
            if (CasSlotBarriered(rt, inbox, idx, v, Val.Empty)) return v;
        }
    }

    // --- what may cross a port ----------------------------------------------

    /// Null if `v` is data; otherwise WHY it cannot be sent. Functions are
    /// refused BY NAME, because "cannot send that" sends somebody hunting
    /// through a nested structure.
    public static string CheckSendable(Rt rt, long v) {
        return CheckSendableAt(rt, v, 0, CarrySandboxed);
    }

    /// The same, for a carrier that may convey IDENTITIES. See the Rust.
    public static string CheckSendableVia(Rt rt, long v, int carry) {
        return CheckSendableAt(rt, v, 0, carry);
    }

    /// What a carrying port can convey. A CHANNEL encodes nothing, so anything
    /// may go; a host port whose encoding the RUNTIME owns carries identities
    /// that mean something on the far side; a host port whose codec runs in the
    /// SANDBOX carries none, because a guest-side decoder is an encoder read
    /// backwards.
    public const int CarryLocal = 0, CarryCrossing = 1, CarrySandboxed = 2;

    static string DescribeFn(Rt rt, long v) {
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        long name = Val.Nil;
        if (t == Obj.TyClosure) {
            int idx = (int) Fx(rt.Slot(v, 0));
            int namec = idx < rt.fns.Length ? rt.fns[idx].Name : -1;
            name = namec >= 0 && namec < rt.roots.shared.Consts.Length
                 ? rt.roots.shared.Consts[namec] : Val.Nil;
        } else if (t == Obj.TyNativefn) {
            name = rt.Slot(v, 1);
        }
        string n = Str.IsString(rt, name) ? Str.Text(rt, name) : "";
        return n.Length == 0 ? "an anonymous fn" : n;
    }

    static string CheckSendableAt(Rt rt, long v, int depth, int carry) {
        if (depth > 64) return "value nested too deeply to send";
        if (!Val.IsHeap(v)) return null;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        switch (t) {
            case Obj.TyClosure: case Obj.TyNativefn: case Obj.TyMultifn:
                return "a port carries data only; " + DescribeFn(rt, v)
                     + " is a function, and a closure's meaning is its environment"
                     + " -- which does not travel";
            case Obj.TyAtom:   return "a port carries data only; this is an atom";
            case Obj.TyVar:    return "a port carries data only; this is a var";
            case Obj.TyThread: return "a port carries data only; this is a thread";
            // `doc/decisions/0006` refused this outright -- "an endpoint cannot
            // be delegated at run time" -- and `0025` REVERSES it: a capability
            // a program holds becomes something it can hand on.
            //
            // WHICH port may go WHERE is not symmetric. A channel is internal:
            // both ends live in this heap and the host was never told it
            // exists, so its id would name one of our objects from outside --
            // the integer-to-port conversion this design exists to prevent. A
            // host port's id is the HOST's own and already means something
            // there. See the table in the Rust.
            case Obj.TyPort:
                if (carry == CarryLocal) return null;
                if (carry == CarryCrossing) {
                    if (CrossesAHeap(Fx(rt.Slot(v, PT_KIND)))) return null;
                    return "a channel endpoint cannot be sent to the host: both its ends"
                         + " live in this heap and the host has never been told it exists,"
                         + " so its id would name one of our objects from outside. A host"
                         + " port can be sent, because its id is the host's own.";
                }
                return "a port cannot be sent through a port whose codec runs in the"
                     + " sandbox: the receiver could write the same bytes, and then a port"
                     + " is mintable from an integer. A channel carries one, and so does a"
                     + " host port opened with :format :flint.";
            // An opaque value is identity and nothing else
            // (`doc/decisions/0022`), so there is nothing to serialise that
            // would still BE it. Anything a codec could write down is something
            // the receiver could write down too, and then it is mintable --
            // which is the entire property gone.
            case Obj.TyOpaque:
                if (carry != CarrySandboxed) return null;
                return "an opaque value cannot be sent through a port whose codec runs in"
                     + " the sandbox: the receiver could write the same bytes, and then it"
                     + " is mintable. Open the port with :format :flint, where the runtime"
                     + " encodes and only the host can decode.";
            case Obj.TyStr: case Obj.TyRope: case Obj.TySym: case Obj.TyKw:
            case Obj.TyBigint: case Obj.TyRegex:
                return null;
            default: break;
        }
        int bas = rt.Mark();
        int vi = rt.Push(v);
        string outs = null;
        if (Maps.IsMap(rt, rt.R(vi))) {
            // Materialised ON THE SHADOW STACK, not into a host list. The walk
            // below allocates, so anything held in a host `List<long>` across
            // it comes back naming the address the object had before the
            // nursery flipped -- `doc/decisions/0031`, which is exactly the rule
            // a list of raw `long`s is invisible to.
            int at = rt.Mark();
            int en = Maps.Entries(rt, rt.R(vi), at);
            for (int i = 0; i < 2 * en; i++) {
                outs = CheckSendableAt(rt, rt.R(at + i), depth + 1, carry);
                if (outs != null) break;
            }
            rt.PopTo(at);
        } else if (Sets.IsSet(rt, rt.R(vi))) {
            int ei = rt.Push(Sets.ElementVector(rt, rt.R(vi)));
            int en = Vec.Count(rt, rt.R(ei));
            for (int i = 0; i < en; i++) {
                outs = CheckSendableAt(rt, Vec.Nth(rt, rt.R(ei), i), depth + 1, carry);
                if (outs != null) break;
            }
        } else if (rt.IsSequential(rt.R(vi))) {
            int si = rt.Push(Seqs.Seq(rt, rt.R(vi)));
            while (!Val.IsNil(rt.R(si))) {
                int fi = rt.Push(Seqs.First(rt, rt.R(si)));
                outs = CheckSendableAt(rt, rt.R(fi), depth + 1, carry);
                rt.PopTo(fi);
                if (outs != null) break;
                rt.SetR(si, Seqs.Next(rt, rt.R(si)));
            }
        }
        rt.PopTo(bas);
        return outs;
    }

    // --- the outbound event queue -------------------------------------------

    /// Append an outbound event. `payload` is a string (or byte string) whose
    /// bytes the host will read; the drain copies them into one contiguous
    /// buffer.
    static void PushEvent(Rt rt, long kind, long a, long b, long payload) {
        int bas = rt.Mark();
        int pi = rt.Push(payload);
        int vi = rt.Push(Vec.Empty(rt));
        foreach (long x in new long[]{ kind, a, b }) {
            rt.SetR(vi, Vec.Conj(rt, rt.R(vi), Val.Fixnum(x)));
        }
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(pi)));
        int si = rt.Push(Sched(rt));
        int ei = rt.Push(rt.Slot(rt.R(si), SC_EVENTS));
        long nevs = Vec.Conj(rt, rt.R(ei), rt.R(vi));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_EVENTS, nevs);
        rt.PopTo(bas);
    }

    /// Does this KIND carry BYTES across a boundary, rather than values inside
    /// one heap? True for a host port and for a global port, and the two paths
    /// are the same path -- a host port is just a global port whose far end is
    /// the host. One predicate rather than a widening `==` at each of six
    /// sites, because the last time this was a set of scattered comparisons one
    /// of them was missed.
    public static bool CrossesAHeap(long kind) { return kind == K_FLINT || kind == K_GLOBAL; }

    /// Does this port carry VALUES rather than bytes? `:format :flint` means the
    /// wire codec, run by the RUNTIME at the boundary rather than by a codec in
    /// the sandbox. See the Rust `is_wire_port`.
    public static bool IsWirePort(Rt rt, long p) {
        long f = rt.Slot(p, PT_FORMAT);
        return !Val.IsNil(f) && f == Str.Keyword(rt, null, "flint");
    }

    static bool NeedPort(Rt rt, long p, string what) {
        if (!IsPort(rt, p)) {
            rt.ThrowStr("ClassCastException", what + " wants a port");
            return false;
        }
        return true;
    }

    public static long Send(Rt rt, long p, long v) {
        if (!NeedPort(rt, p, "send")) return Val.Nil;
        // Never park against a peer that is gone: a script blocking for ever on
        // a host that has hung up is the same failure as a host leaking a
        // handle, seen from the other side.
        long st = Fx(rt.Slot(p, PT_STATE));
        if (st != P_OPEN) {
            string why = st switch {
                P_CLOSED => "this end is closed",
                P_HALF => "the other end has closed, so nothing can receive this",
                P_ORPHANED => "the other end is gone, so nothing can ever receive this",
                P_REFUSED => "the host refused to open this",
                _ => "this port is not open yet",
            };
            return rt.ThrowStr("IllegalStateException", "send: " + why);
        }
        // Root FIRST, check second. `CheckSendable` walks the value, and walking
        // a sequential value allocates -- so it can collect, and an unrooted
        // `p`/`v` comes back pointing into the abandoned semispace. Nothing
        // downstream can tell that from a live pointer.
        int bas = rt.Mark();
        int pi = rt.Push(p), vi = rt.Push(v);
        long kind = Fx(rt.Slot(rt.R(pi), PT_KIND));
        int carry = !CrossesAHeap(kind) ? CarryLocal
                  : IsWirePort(rt, rt.R(pi)) ? CarryCrossing
                  : CarrySandboxed;
        string bad = CheckSendableVia(rt, rt.R(vi), carry);
        if (bad != null) { rt.PopTo(bas); return rt.ThrowStr("IllegalArgumentException", bad); }
        if (CrossesAHeap(kind)) {
            // Bound the host's queue in BYTES: back-pressure exists to bound
            // memory, and one 4 MB message is not one message's worth of it.
            bool binary = Fx(rt.Slot(rt.R(pi), PT_BINARY)) == 1;
            bool encoded = binary
                ? (Bytes.IsBytes(rt, rt.R(vi)) || rt.IsHeapTy(rt.R(vi), Obj.TyVec))
                : Str.IsString(rt, rt.R(vi));
            if (!encoded) {
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalArgumentException",
                    "a host port carries bytes; flint.port/send encodes for you, so this is a "
                    + "raw send of something that is not already encoded (a string, or a vector "
                    + "of 0..255, or a byte string, on a binary port)");
            }
            // The host reads contiguous bytes, so the rope stops here. This is
            // the boundary `doc/decisions/0011` means by "flatten before
            // matching": the tree is an internal representation and nothing
            // outside the module has to know about it.
            if (!binary) rt.SetR(vi, Str.Flatten(rt, rt.R(vi)));
            // Whose bookkeeping the back-pressure lives on.
            //
            // A HOST port is a pair: two objects in this heap, and the far end
            // carries the id the host knows and the byte count. A GLOBAL port
            // is ONE object -- the far end is the host's registry and is not in
            // any heap (`doc/decisions/0027`) -- so it is its own accounting.
            int hi;
            if (kind == K_GLOBAL) {
                hi = rt.Push(rt.R(pi));
            } else {
                long hostEnd = PeerOf(rt, rt.R(pi));
                if (Val.IsNil(hostEnd)) {
                    rt.PopTo(bas);
                    return rt.ThrowStr("IllegalStateException", "the host has closed this port");
                }
                hi = rt.Push(hostEnd);
            }
            long len = binary
                ? (Bytes.IsBytes(rt, rt.R(vi)) ? Bytes.Count(rt, rt.R(vi)) : Vec.Count(rt, rt.R(vi)))
                : Str.ByteLen(rt, rt.R(vi));
            long cap = Fx(rt.Slot(rt.R(hi), PT_CAP));
            long queued = Fx(rt.Slot(rt.R(hi), PT_BYTES));
            if (queued > 0 && queued + len > cap) {
                long tgt = rt.R(hi);
                rt.PopTo(bas);
                return ParkOnPort(rt, WK_SEND, tgt);
            }
            rt.SetSlot(Val.AsHeap(rt.R(hi)), PT_BYTES, Val.Fixnum(queued + len));
            long hid = Fx(rt.Slot(rt.R(hi), PT_ID));
            PushEvent(rt, EV_MESSAGE, hid, len, rt.R(vi));
            rt.PopTo(bas);
            return Val.Nil;
        }
        // A channel end: bound in MESSAGES, since nothing is serialised and the
        // values are shared rather than copied.
        long peer = PeerOf(rt, rt.R(pi));
        if (Val.IsNil(peer)) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalStateException",
                "the other end of this port is gone, so nothing can ever receive this");
        }
        int pei = rt.Push(peer);
        if (Fx(rt.Slot(rt.R(pei), PT_STATE)) == P_CLOSED) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalStateException", "the other end of this port is closed");
        }
        // TRY, then park -- rather than ask whether it is full and then put.
        // The reservation IS the question, in one atomic step.
        if (Enqueue(rt, rt.R(pei), rt.R(vi))) {
            WakeOn(rt, rt.R(pei));
            rt.PopTo(bas);
            return Val.Nil;
        }
        // FULL: back-pressure. Park on the PEER, because that is what a receive
        // there frees; parking on this end would never be woken.
        long full = rt.R(pei);
        rt.PopTo(bas);
        return ParkForSpace(rt, full);
    }

    /// Take from this port's inbox. Parks when empty.
    public static long Receive(Rt rt, long p) {
        if (!NeedPort(rt, p, "receive")) return Val.Nil;
        int bas = rt.Mark();
        int pi = rt.Push(p);
        if (InboxCount(rt, rt.R(pi)) > 0) {
            int vi = rt.Push(Dequeue(rt, rt.R(pi)));
            if (CrossesAHeap(Fx(rt.Slot(rt.R(pi), PT_KIND)))) {
                // Room again for the host to deliver the next wave.
                long n = Bytes.IsBytes(rt, rt.R(vi)) ? Bytes.Count(rt, rt.R(vi))
                       : rt.IsHeapTy(rt.R(vi), Obj.TyVec) ? Vec.Count(rt, rt.R(vi))
                       : Str.ByteLen(rt, rt.R(vi));
                long queued = Fx(rt.Slot(rt.R(pi), PT_BYTES));
                rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_BYTES, Val.Fixnum(queued > n ? queued - n : 0));
            }
            // Space freed: whoever was blocked sending here can try again.
            WakeOn(rt, rt.R(pi));
            long outv = rt.R(vi);
            rt.PopTo(bas);
            return outv;
        }
        long st = Fx(rt.Slot(rt.R(pi), PT_STATE));
        // Drained and finished cleanly: end of stream, a normal answer.
        if (st == P_CLOSED || st == P_HALF) { rt.PopTo(bas); return Val.Nil; }
        // Drained and the peer vanished: nobody said goodbye, so say so rather
        // than pretending the stream ended tidily -- and never park, because a
        // script blocked for ever on a host that hung up is the same failure as
        // a host leaking a handle, seen from the other side.
        if (st == P_ORPHANED) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalStateException",
                "receive: the other end of this port is gone, so this can never complete");
        }
        // A GLOBAL port has no peer OBJECT to ask about: the far end is the
        // host's registry and is not in any heap (`doc/decisions/0027`). Its own
        // state is the whole answer, and the states above have already covered
        // every way that can say "no more" -- so an empty buffer here means
        // "nothing yet", which is what parking is for.
        if (Fx(rt.Slot(rt.R(pi), PT_KIND)) != K_GLOBAL) {
            long peer = PeerOf(rt, rt.R(pi));
            if (Val.IsNil(peer)) {
                rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(P_ORPHANED));
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalStateException",
                    "receive: the other end of this port is gone, so this can never complete");
            }
            long pst = Fx(rt.Slot(peer, PT_STATE));
            if (pst == P_CLOSED || pst == P_HALF || pst == P_ORPHANED) {
                rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(P_HALF));
                rt.PopTo(bas);
                return Val.Nil;
            }
        }
        long target = rt.R(pi);
        rt.PopTo(bas);
        return ParkOnPort(rt, WK_RECEIVE, target);
    }

    public static long Close(Rt rt, long p) {
        if (!NeedPort(rt, p, "close")) return Val.Nil;
        int bas = rt.Mark();
        int pi = rt.Push(p);
        if (Fx(rt.Slot(rt.R(pi), PT_STATE)) != P_CLOSED) {
            rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(P_CLOSED));
            CloseSideEffects(rt, rt.R(pi));
        }
        rt.PopTo(bas);
        return Val.Nil;
    }

    /// Everything that follows from an end closing, however it closed: tell the
    /// host if it is the peer, and wake anybody parked on either side.
    static void CloseSideEffects(Rt rt, long p) {
        int bas = rt.Mark();
        int pi = rt.Push(p);
        if (CrossesAHeap(Fx(rt.Slot(rt.R(pi), PT_KIND)))) {
            long hostEnd = PeerOf(rt, rt.R(pi));
            if (!Val.IsNil(hostEnd)) {
                int hi = rt.Push(hostEnd);
                long id = Fx(rt.Slot(rt.R(hi), PT_ID));
                rt.SetSlot(Val.AsHeap(rt.R(hi)), PT_STATE, Val.Fixnum(P_CLOSED));
                PushEvent(rt, EV_CLOSED, id, 0, Val.Nil);
                rt.PopTo(hi);
            }
        }
        WakeOn(rt, rt.R(pi));
        // The peer becomes HALF-closed rather than closed: it may still drain
        // what is already in its buffer, and only then reads end-of-stream. The
        // channel is not freed until both ends are done.
        long peer2 = PeerOf(rt, rt.R(pi));
        if (!Val.IsNil(peer2) && Fx(rt.Slot(peer2, PT_STATE)) == P_OPEN) {
            rt.SetSlot(Val.AsHeap(peer2), PT_STATE, Val.Fixnum(P_HALF));
            WakeOn(rt, peer2);
        }
        rt.PopTo(bas);
    }

    // --- opening a capability -----------------------------------------------

    /// Ask the host to open `name`, forwarding `args` verbatim.
    ///
    /// # The runtime does not know what a capability is
    ///
    /// It used to. There was a grant table in the sandbox, a `PT_PRESENTED`
    /// slot on every port, an export to read it back, and a check inside the
    /// SDK -- four places knowing a concept that belongs to whoever is lending
    /// the authority.
    ///
    /// A capability is an OPAQUE VALUE (`doc/decisions/0022`) and nothing more.
    /// The host projects one in by any means it likes, and a program that wants
    /// something a capability enables PRESENTS it with the request. This
    /// forwards whatever it was given and takes no view. What makes that safe
    /// is the ENCODING, not a table: an opaque value crosses as `K_SENTINEL`
    /// plus the host id it was issued with, and guest code cannot mint that id.
    public static long PortOpen(Rt rt, long name, long args) {
        EnsureSched(rt);
        int bas = rt.Mark();
        int ni = rt.Push(name), ai = rt.Push(args);
        int ti = rt.Push(CurrentThread(rt));
        long pending = rt.Slot(rt.R(ti), TH_PENDING);
        if (!Val.IsNil(pending)) {
            // Second time round: the host has answered.
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_PENDING, Val.Nil);
            // ONLY A REFUSAL IS A REFUSAL. The host may answer and then close
            // the port before this thread is next scheduled, and the port is
            // then `P_HALF` -- "granted, and now finished", not "you may not
            // have this".
            if (Fx(rt.Slot(pending, PT_STATE)) != P_REFUSED) { rt.PopTo(bas); return pending; }
            string nm2 = Str.IsString(rt, rt.R(ni)) ? Str.Text(rt, rt.R(ni)) : "?";
            rt.PopTo(bas);
            return rt.ThrowStr("SecurityException", "the host refused to open \"" + nm2 + "\"");
        }
        int ei = rt.Push(NewPort(rt, DEFAULT_HOST_CAP, rt.R(ni), K_FLINT, P_PENDING, Val.Nil));
        int hi = rt.Push(NewPort(rt, DEFAULT_HOST_CAP, rt.R(ni), K_HOST, P_PENDING, Val.Nil));
        LinkPeers(rt, rt.R(ei), rt.R(hi));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_PENDING, rt.R(ei));
        long token = NewWaiter(rt, WK_OPEN, rt.R(ei));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_TOKEN, Val.Fixnum(token));
        long hostId = Fx(rt.Slot(rt.R(hi), PT_ID));
        // THE ARGUMENTS, ENCODED, are the payload -- not a bare name string.
        // That is the whole of "the host does what it wants with them": one
        // value crosses, and anything an opaque value carries survives the trip
        // because the codec already knew how to write one down.
        int vi = rt.Push(Vec.Empty(rt));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(ni)));
        int an = rt.IsHeapTy(rt.R(ai), Obj.TyVec) ? Vec.Count(rt, rt.R(ai)) : 0;
        for (int k = 0; k < an; k++) {
            rt.SetR(vi, Vec.Conj(rt, rt.R(vi), Vec.Nth(rt, rt.R(ai), k)));
        }
        byte[] call;
        try {
            call = Codec.Encode(rt, rt.R(vi));
        } catch (Codec.Refused e) {
            // A value the codec refuses is the PROGRAM's error, not the host's:
            // say so here rather than sending something the host cannot read.
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalArgumentException",
                "open: this cannot be sent to the host: " + e.Message);
        }
        int pi = rt.Push(Bytes.Of(rt, call));
        PushEvent(rt, EV_OPEN, token, hostId, rt.R(pi));
        long target = rt.R(ei);
        rt.PopTo(bas);
        return Park(rt, target);
    }

    // --- the host's side ----------------------------------------------------

    /// Resume whatever is waiting on `token`.
    ///
    /// RECORDS AND RETURNS; it never re-enters the scheduler, because a host may
    /// well call this from inside a host function the runtime itself invoked,
    /// and re-entering there would run the scheduler on top of itself. The
    /// answer is taken now and acted on at the next pump.
    ///
    /// Returns false when the token is stale or already used: the generation in
    /// it no longer matches the slot, which is exactly the late-or-duplicated
    /// reply that would otherwise resume a stranger's thread.
    public static bool HostContinue(Rt rt, long token, bool ok) {
        long w = WaiterAt(rt, token);
        if (Val.IsNil(w)) return false;
        int bas = rt.Mark();
        int wi = rt.Push(w);
        if (Fx(rt.Slot(rt.R(wi), W_KIND)) == WK_OPEN) {
            int pi = rt.Push(rt.Slot(rt.R(wi), W_PORT));
            long state = ok ? P_OPEN : P_REFUSED;
            rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(state));
            long hostEnd = PeerOf(rt, rt.R(pi));
            if (!Val.IsNil(hostEnd)) {
                rt.SetSlot(Val.AsHeap(hostEnd), PT_STATE, Val.Fixnum(state));
                // Refused: the host never gets a handle, so nothing needs to
                // keep this end alive.
                if (!ok) UnrootPort(rt, hostEnd);
            }
            rt.PopTo(pi);
        }
        WakeWaiter(rt, rt.R(wi));
        rt.PopTo(bas);
        return true;
    }

    /// Put bytes into the flint end of a host port. Wakes a parked receiver; it
    /// does not run anything.
    ///
    /// Returns FALSE WHEN THE GUEST'S BUFFER IS FULL, and the host must hold the
    /// message and offer it again after the next pump. Without that, a server
    /// answering "in waves" would simply push every wave at once and the whole
    /// answer would be resident in the guest heap -- which is precisely what
    /// waves exist to prevent. Inbound needs the same back-pressure as
    /// outbound; it is the same buffer bound, seen from the other side.
    public static bool HostDeliver(Rt rt, long hostPortId, byte[] bytes) {
        long hostEnd = PortById(rt, hostPortId);
        if (Val.IsNil(hostEnd)) return false;
        int bas = rt.Mark();
        int hi = rt.Push(hostEnd);
        // A GLOBAL port is ONE object and the id is its own, so the lookup has
        // already found the end to deliver into. A HOST port is a pair, and the
        // id belongs to the far end, so the delivery goes to its peer.
        int pi;
        if (Fx(rt.Slot(rt.R(hi), PT_KIND)) == K_GLOBAL) {
            pi = rt.Push(rt.R(hi));
        } else {
            long flint = PeerOf(rt, rt.R(hi));
            if (Val.IsNil(flint)) { rt.PopTo(bas); return false; }
            pi = rt.Push(flint);
        }
        // BACK-PRESSURE in bytes, CLAIMED ATOMICALLY: two host threads
        // delivering into one end would both read the same `queued`, both find
        // room, and both write -- and the bound that exists to cap memory would
        // be the one thing not enforced.
        long cap = Fx(rt.Slot(rt.R(pi), PT_CAP));
        long len = bytes.Length;
        for (;;) {
            long pv = rt.R(pi);
            long queued = Fx(SlotAtomic(rt, pv, PT_BYTES));
            if (queued > 0 && queued + len > cap) { rt.PopTo(bas); return false; }
            if (CasSlot(rt, pv, PT_BYTES, Val.Fixnum(queued), Val.Fixnum(queued + len))) break;
        }
        // One object, not one boxed fixnum per byte, on a binary port: a vector
        // would cost a 32-way trie and an allocation per 32 bytes for data the
        // codec immediately walks back into bytes.
        long v = Fx(rt.Slot(rt.R(pi), PT_BINARY)) == 1
               ? Bytes.Of(rt, bytes)
               : Str.Of(rt, System.Text.Encoding.UTF8.GetString(bytes));
        int vi = rt.Push(v);
        if (!Enqueue(rt, rt.R(pi), rt.R(vi))) {
            // The ring is full though the byte bound had room: the guest has
            // not drained. Give the bytes back and tell the host to offer this
            // again. Back-pressure, not an error.
            GiveBack(rt, rt.R(pi), len);
            rt.PopTo(bas);
            return false;
        }
        WakeOn(rt, rt.R(pi));
        rt.PopTo(bas);
        return true;
    }

    /// Return bytes claimed against a port's bound for a message never delivered.
    static void GiveBack(Rt rt, long p, long len) {
        for (;;) {
            long q = Fx(SlotAtomic(rt, p, PT_BYTES));
            long back = q > len ? q - len : 0;
            if (CasSlot(rt, p, PT_BYTES, Val.Fixnum(q), Val.Fixnum(back))) return;
        }
    }

    /// The host lets go of its end. The port may now be collected.
    public static void HostClosePort(Rt rt, long hostPortId) {
        long hostEnd = PortById(rt, hostPortId);
        if (Val.IsNil(hostEnd)) return;
        int bas = rt.Mark();
        int hi = rt.Push(hostEnd);
        rt.SetSlot(Val.AsHeap(rt.R(hi)), PT_STATE, Val.Fixnum(P_CLOSED));
        long flint = PeerOf(rt, rt.R(hi));
        if (!Val.IsNil(flint)) {
            int fi = rt.Push(flint);
            // Half-closed: whatever the host already delivered is still there
            // to be read, and only then does it read as end of stream.
            if (Fx(rt.Slot(rt.R(fi), PT_STATE)) == P_OPEN) {
                rt.SetSlot(Val.AsHeap(rt.R(fi)), PT_STATE, Val.Fixnum(P_HALF));
            }
            WakeOn(rt, rt.R(fi));
            rt.PopTo(fi);
        }
        UnrootPort(rt, rt.R(hi));
        rt.PopTo(bas);
    }

    /// This end's state, RESOLVED rather than remembered.
    ///
    /// A port whose peer has been collected is orphaned whether or not the
    /// scheduler has got round to noticing, and a query that answered `:open`
    /// until then would be a notification wearing a query's clothes.
    public static long PortStateNow(Rt rt, long p) {
        long st = Fx(rt.Slot(p, PT_STATE));
        if (st != P_OPEN) return st;
        long peerId = Fx(rt.Slot(p, PT_PEER));
        if (peerId < 0) return st;
        if (Val.IsNil(PortById(rt, peerId))) {
            rt.SetSlot(Val.AsHeap(p), PT_STATE, Val.Fixnum(P_ORPHANED));
            return P_ORPHANED;
        }
        return st;
    }

    /// THE QUERY, NOT THE NOTIFICATION. What state is the RUNTIME end of this
    /// port in, asked by host id?
    ///
    /// If an event were the only way to learn that a port had closed, then an
    /// event dropped, missed or not yet drained would be an unrecoverable leak:
    /// a host handle to a port nobody will ever mention again. This makes the
    /// pushed `:closed` an optimisation over polling rather than the sole
    /// carrier of the truth. 255 means the runtime knows nothing about this id,
    /// which a host should also treat as "done".
    public static long HostPortState(Rt rt, long hostPortId) {
        long hostEnd = PortById(rt, hostPortId);
        if (Val.IsNil(hostEnd)) return 255;
        long flint = PeerOf(rt, hostEnd);
        // The runtime end has been collected: as good as closed, and this is
        // exactly the case a missed event would have lost.
        if (Val.IsNil(flint)) return P_CLOSED;
        return PortStateNow(rt, flint);
    }

    /// Serialise every pending event into one contiguous buffer and hand it
    /// over. One call per pump: the boundary crossing is tens of nanoseconds,
    /// the marshalling is the cost, so everything pending goes at once.
    ///
    /// Layout: `count` records of five little-endian `u32`s --
    /// `kind, a, b, payload-offset, payload-len` -- followed by the payload
    /// bytes, all offsets relative to the start of the buffer.
    /// What `DrainEvents` hands back: the COUNT and the buffer.
    ///
    /// The Rust signature is `drain_events(&mut Vec<u8>) -> u32` -- it fills a
    /// buffer and answers how many records it wrote. The pair travels together
    /// rather than the count being inferred from the first payload offset,
    /// which is derivable but is not what the wire says.
    public readonly record struct Events(int Count, byte[] Bytes);

    public static Events DrainEvents(Rt rt) {
        long s = Sched(rt);
        if (Val.IsNil(s)) return new Events(0, System.Array.Empty<byte>());
        int bas = rt.Mark();
        int si = rt.Push(s);
        int ei = rt.Push(rt.Slot(rt.R(si), SC_EVENTS));
        int n = Vec.Count(rt, rt.R(ei));
        var outb = new System.IO.MemoryStream();
        byte[] header = new byte[n * 20];
        outb.Write(header, 0, header.Length);
        for (int i = 0; i < n; i++) {
            long e = Vec.Nth(rt, rt.R(ei), i);
            long kind = Fx(Vec.Nth(rt, e, 0));
            long a = Fx(Vec.Nth(rt, e, 1));
            long b = Fx(Vec.Nth(rt, e, 2));
            long payload = Vec.Nth(rt, e, 3);
            int off = (int) outb.Length;   // the header is already in `outb`
            int plen;
            if (Bytes.IsBytes(rt, payload)) {
                byte[] bs = Bytes.ToArray(rt, payload);
                outb.Write(bs, 0, bs.Length);
                plen = bs.Length;
            } else if (rt.IsHeapTy(payload, Obj.TyVec)) {
                int m = Vec.Count(rt, payload);
                for (int k = 0; k < m; k++) outb.WriteByte((byte) Fx(Vec.Nth(rt, payload, k)));
                plen = m;
            } else if (Str.IsString(rt, payload)) {
                byte[] bs = System.Text.Encoding.UTF8.GetBytes(Str.Text(rt, payload));
                outb.Write(bs, 0, bs.Length);
                plen = bs.Length;
            } else {
                plen = 0;
            }
            int rec = i * 20;
            Le32(header, rec, kind);
            Le32(header, rec + 4, a);
            Le32(header, rec + 8, b);
            Le32(header, rec + 12, off);
            Le32(header, rec + 16, plen);
            // A message leaving the queue frees its bytes against the bound.
            if (kind == EV_MESSAGE) {
                long port = PortById(rt, a);
                if (!Val.IsNil(port)) {
                    long queued = Fx(rt.Slot(port, PT_BYTES));
                    rt.SetSlot(Val.AsHeap(port), PT_BYTES, Val.Fixnum(queued > b ? queued - b : 0));
                    WakeOn(rt, port);
                }
            }
        }
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_EVENTS, Vec.Empty(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_EHEAD, Val.Fixnum(0));
        rt.PopTo(bas);
        byte[] buf = outb.ToArray();
        System.Array.Copy(header, buf, header.Length);
        return new Events(n, buf);
    }

    static void Le32(byte[] b, int at, long v) {
        b[at] = (byte) v; b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16); b[at + 3] = (byte) (v >> 24);
    }

    // --- reachability, which is also the lifetime rule -----------------------

    /// Reconcile the port table with what the collector left alive.
    ///
    /// A flint end that nothing refers to any more has gone from the weak table.
    /// That is semantically identical to the script having called `close`, so we
    /// close it on the script's behalf -- and a thread parked on a port whose
    /// PEER has gone can never proceed, so it is woken with an error rather than
    /// left hanging. Both facts are ones the collector has already worked out;
    /// this only reads them.
    public static void ReapPorts(Rt rt) {
        long s = Sched(rt);
        if (Val.IsNil(s)) return;
        int bas = rt.Mark();
        int si = rt.Push(s);
        int ii = rt.Push(rt.Slot(rt.R(si), SC_PORTS));
        int n = Vec.Count(rt, rt.R(ii));
        int li = rt.Push(Vec.Empty(rt));
        for (int k = 0; k < n; k++) {
            long id = Fx(Vec.Nth(rt, rt.R(ii), k));
            long p = PortById(rt, id);
            if (!Val.IsNil(p)) {
                rt.SetR(li, Vec.Conj(rt, rt.R(li), Val.Fixnum(id)));
                continue;
            }
            // This end has been collected. Tell whoever is affected.
            long peer = PortById(rt, PeerIdOfDead(rt, id));
            if (Val.IsNil(peer)) continue;
            int pi = rt.Push(peer);
            long pkind = Fx(rt.Slot(rt.R(pi), PT_KIND));
            long pst = Fx(rt.Slot(rt.R(pi), PT_STATE));
            if (pst != P_CLOSED && pst != P_ORPHANED) {
                // Its peer vanished WITHOUT closing, which is not the same as a
                // tidy close and should not read like one.
                rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(P_ORPHANED));
                if (pkind == K_HOST) {
                    PushEvent(rt, EV_CLOSED, Fx(rt.Slot(rt.R(pi), PT_ID)), 0, Val.Nil);
                }
            }
            FailWaitersOn(rt, rt.R(pi),
                "the other end of this port is unreachable, so this can never complete");
            WakeOn(rt, rt.R(pi));
            rt.PopTo(pi);
        }
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_PORTS, rt.R(li));
        rt.PopTo(bas);
    }

    /// Wake everything parked on `p` with an ERROR instead of a value. Used when
    /// the peer end has been collected: that receive can never succeed, and a
    /// hang is the worst possible way to say so.
    static void FailWaitersOn(Rt rt, long p, string msg) {
        int bas = rt.Mark();
        int pi = rt.Push(p);
        int wsi = rt.Push(Waiters(rt));
        int n = Vec.Count(rt, rt.R(wsi));
        for (int i = 0; i < n; i++) {
            long w = Vec.Nth(rt, rt.R(wsi), i);
            if (w == Val.NotFound || Val.IsNil(w) || Val.IsNil(rt.Slot(w, W_THREAD))) continue;
            if (rt.Slot(w, W_PORT) != rt.R(pi)) continue;
            int ti = rt.Push(rt.Slot(w, W_THREAD));
            WakeWaiter(rt, w);
            long e = rt.MakeError("IllegalStateException", msg);
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_FAIL, e);
            rt.PopTo(ti);
        }
        rt.PopTo(bas);
    }

    /// Program exit: close every flint end so a host is never left guessing
    /// whether more is coming, and leave the events for the final drain.
    public static void CloseAllFlintEnds(Rt rt) {
        long s = Sched(rt);
        if (Val.IsNil(s)) return;
        int bas = rt.Mark();
        int si = rt.Push(s);
        int ii = rt.Push(rt.Slot(rt.R(si), SC_PORTS));
        int n = Vec.Count(rt, rt.R(ii));
        for (int k = 0; k < n; k++) {
            long p = PortById(rt, Fx(Vec.Nth(rt, rt.R(ii), k)));
            if (Val.IsNil(p)) continue;
            int pi = rt.Push(p);
            if (CrossesAHeap(Fx(rt.Slot(rt.R(pi), PT_KIND)))
                && Fx(rt.Slot(rt.R(pi), PT_STATE)) != P_CLOSED) {
                rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(P_CLOSED));
                CloseSideEffects(rt, rt.R(pi));
            }
            rt.PopTo(pi);
        }
        rt.PopTo(bas);
    }

    // --- joining ------------------------------------------------------------

    public static long Join(Rt rt, long t) {
        if (!IsThread(rt, t)) return rt.ThrowStr("ClassCastException", "join wants a thread, got " + rt.Describe(t));
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
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_BINDINGS, rt.roots.shared.Singletons[Rt.SingBindings]);
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
        rt.roots.shared.Singletons[Rt.SingBindings] = rt.Slot(rt.R(ti), TH_BINDINGS);
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
            long fail = rt.Slot(rt.R(ti), TH_FAIL);
            if (Val.IsNil(fail)) {
                v = rt.Run(0);
            } else {
                // Raised HERE, in the thread it concerns, rather than in
                // whichever thread noticed the port had gone. `try` in this
                // thread catches it like any other error.
                rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_FAIL, Val.Nil);
                rt.thrown = fail;
                v = rt.Unwind() ? rt.Run(0) : Val.Nil;
            }
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

    /// Re-enter the scheduler after the host has answered.
    ///
    /// What a host calls when `status` came back 2. There is no separate resume
    /// state: the answer was already recorded by `HostContinue`/`HostDeliver`,
    /// and this only starts the loop again. That is why those two record rather
    /// than run -- a host calling one from inside a host function the runtime
    /// itself invoked would otherwise run the scheduler on top of itself.
    public static long Resume(Rt rt) {
        if (Val.IsNil(Sched(rt))) return Val.Nil;
        rt.status = 0;
        return Drive(rt);
    }

    static bool PendingEvents(Rt rt) {
        long s = Sched(rt);
        if (Val.IsNil(s)) return false;
        return Vec.Count(rt, rt.Slot(s, SC_EVENTS)) > Fx(rt.Slot(s, SC_EHEAD));
    }

    /// Is there anything only the HOST can supply? An event it has not drained,
    /// or a thread parked on a port whose other end is outside this heap.
    static bool NeedsHost(Rt rt) {
        if (PendingEvents(rt)) return true;
        long ts = rt.Slot(Sched(rt), SC_THREADS);
        int n = Vec.Count(rt, ts);
        for (int i = 0; i < n; i++) {
            long th = Vec.Nth(rt, ts, i);
            if (Val.IsNil(th) || th == Val.NotFound) continue;
            if (Fx(rt.Slot(th, TH_STATUS)) != ST_PARKED) continue;
            long on = rt.Slot(th, TH_PARK_ON);
            if (IsPort(rt, on) && CrossesAHeap(Fx(rt.Slot(on, PT_KIND)))) return true;
        }
        return false;
    }

    public static long Drive(Rt rt) {
        for (;;) {
            // What the collector left behind IS the lifetime rule: a flint end
            // that nothing refers to any more has been closed, whether or not
            // anybody said so (`doc/decisions/0006`).
            ReapPorts(rt);
            int i = Pick(rt);
            if (i >= 0) { RunOne(rt, i); continue; }
            // The entry function's value IS the answer, so once it has returned
            // and nothing else can run, the program is over -- whatever a
            // service thread may still be parked on. Asking "does anything need
            // the host?" first would keep a driver's reader alive for ever.
            if (MainFinished(rt)) {
                // Exit closes every flint end and leaves the events for one last
                // drain, so a host never has to guess whether more is coming.
                CloseAllFlintEnds(rt);
                if (PendingEvents(rt)) { rt.status = 2; return Val.Nil; }
                rt.status = 0;
                return MainResult(rt);
            }
            if (NeedsHost(rt)) { rt.status = 2; return Val.Nil; }
            rt.status = 0;
            // Nothing runnable, nothing the host can help with: the remaining
            // threads are waiting on each other. NAMED rather than hung.
            long ts = rt.Slot(Sched(rt), SC_THREADS);
            int n = Vec.Count(rt, ts);
            int stuck = 0;
            var detail = new System.Text.StringBuilder();
            for (int k = 0; k < n; k++) {
                long th = Vec.Nth(rt, ts, k);
                if (Val.IsNil(th) || th == Val.NotFound) continue;
                if (Fx(rt.Slot(th, TH_STATUS)) != ST_PARKED) continue;
                stuck++;
                long on = rt.Slot(th, TH_PARK_ON);
                string what;
                if (IsPort(rt, on)) {
                    long l = rt.Slot(on, PT_LABEL);
                    string lab = Str.IsString(rt, l) ? Str.Text(rt, l) : "";
                    what = "port " + Fx(rt.Slot(on, PT_ID))
                         + (lab.Length == 0 ? "" : " \"" + lab + "\"");
                } else if (IsThread(rt, on)) {
                    what = "thread " + Fx(rt.Slot(on, TH_ID));
                } else {
                    what = "something";
                }
                detail.Append("\n  thread ").Append(Fx(rt.Slot(th, TH_ID)))
                      .Append(" waiting on ").Append(what);
            }
            return rt.ThrowStr("IllegalStateException",
                "deadlock: " + stuck + " green thread(s) are parked and nothing can wake them"
                + detail);
        }
    }
}
