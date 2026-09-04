namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Interns;
using _3sln.Flint.Kgen.Rt;


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
    /// `PT_ROOT`, `PT_FORMAT`, `PT_OPTS` and `PT_BINARY` are GONE
    /// (`doc/decisions/0027`). A bridge handle is ordinary memory -- a handle
    /// nothing refers to is precisely what a release is for, so rooting it would
    /// defeat the count -- and a bridge is always the runtime's wire format, so
    /// there is no format, no options and no binary flag to remember.
    public const int PT_ID = 0, PT_STATE = 1, PT_CAP = 2, PT_INBOX = 3,
        PT_READ = 4, PT_BYTES = 5, PT_PEER = 6, PT_LABEL = 7, PT_KIND = 8,
        PT_WRITE = 9, PT_RING = 10, PT_LEN = 11;

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
    /// Two kinds, and no third (`doc/decisions/0027`). `K_CHANNEL` joins two
    /// green threads inside one sandbox and passes values by reference;
    /// `K_BRIDGE` is a HANDLE on a port the host owns, carrying the host's id
    /// and encoded messages.
    ///
    /// There is no "host port". `open` used to manufacture a PAIR of ends here,
    /// keep one and offer the other up as the host's, which made the confined
    /// thing the author of its own authority.
    public const int K_CHANNEL = 0, K_BRIDGE = 1;

    /// A channel's default buffer, in MESSAGES.
    public const long DEFAULT_CAP = 16;

    /// How much a bridge will buffer before a send parks, in BYTES.
    public const long DEFAULT_BRIDGE_CAP = 1 << 20;

    /// What the host is told about, drained through `DrainEvents`.
    /// `EV_RETAIN` says this sandbox now holds the host's port `a`, pushed
    /// exactly once per port per sandbox on the miss that mints the handle;
    /// `EV_RELEASE` says it no longer does. One per retain, so the host's count
    /// is of HOLDERS (`doc/decisions/0027`).
    public const int EV_OPEN = 1, EV_MESSAGE = 2, EV_CLOSED = 3,
                     EV_RETAIN = 4, EV_RELEASE = 5,
    /// The guest is asking the host for SOMETHING, and the answer is an
    /// ordinary value rather than a port (`doc/decisions/0036` step 7).
    /// `EV_OPEN` is the special case whose answer is a port, and it stays: a
    /// port is granted by id and never encoded.
                     EV_REQUEST = 6;

    // --- waiter ------------------------------------------------------------

    // Everything that parks parks the same way -- `open`, a send to a full
    // port, a receive on an empty one -- through one table with one token type.
    // A token is `(generation << 16) | (index + 1)`: a bare index is reusable,
    // so a late or duplicated reply from the host would resume whatever now
    // occupies that slot -- a wrong thread woken with a stranger's value, and
    // unfindable in production. The generation makes that a rejection instead.
    public const int W_GEN = 0, W_THREAD = 1, W_KIND = 2, W_PORT = 3,
                     W_NEXT = 4, W_LEN = 5;
    public const int WK_OPEN = 1, WK_SEND = 2, WK_RECEIVE = 3, WK_JOIN = 4,
    /// Parked on `HostRequest`, which is `WK_OPEN` generalised: the answer is a
    /// VALUE rather than necessarily a port (`doc/decisions/0036` step 7).
                     WK_REQUEST = 5;

    // --- scheduler ---------------------------------------------------------

    public const int SC_THREADS = 0, SC_CURRENT = 1, SC_NEXTID = 2,
        SC_EVENTS = 3, SC_EHEAD = 4, SC_PORTS = 5, SC_PAIRS = 6,
        SC_WAITERS = 7, SC_WFREE = 8, SC_SYSTEM = 9,
        /// Host ids of every BRIDGE this sandbox holds a handle for -- ids, not
        /// references, so the list pins nothing. This is the walk that turns a
        /// collection into a release (`doc/decisions/0027`).
        SC_BRIDGES = 10, SC_LEN = 11;

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
        // The decoder's route to `InstallBridgePort`, set HERE and nowhere else.
        // See `Rt.bridgeHook`: reaching it directly from the codec put the whole
        // scheduler into every wasm module, including ones with no ports.
        rt.bridgeHook = (r, id) => InstallBridgePort(r, id, Val.Nil, true);
        int bas = rt.Mark();
        int si = rt.Push(NewObj(rt, Obj.TySched, SC_LEN));
        if (Val.IsNil(rt.R(si))) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_EVENTS, Vec.Empty(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_EHEAD, Val.Fixnum(0));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_PORTS, Vec.Empty(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_PAIRS, Vec.Empty(rt));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_BRIDGES, Vec.Empty(rt));
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
        return Vec.Nth(rt, rt.Slot(s, SC_THREADS), (int) Fx(rt.Slot(s, SC_CURRENT)), Val.NotFound);
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
        // UNBILLED: the size of a saved stack is a property of the calling
        // convention, not of the program. Billing it makes the same program
        // cost more interpreted than compiled (`doc/decisions/0009`).
        long a0 = rt.AllocUnbilled(Obj.TyNode, n);
        long sv = a0 == 0 ? Val.Nil : Val.Heap(a0);
        if (Val.IsNil(sv)) { rt.PopTo(bas); return; }
        for (int i = 0; i < n; i++) rt.SetSlot(Val.AsHeap(sv), i, rt.roots.Stack[i]);
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_STACK, sv);

        long a1 = rt.AllocUnbilled(Obj.TyRaw, rt.frames.Count * FRAME_REC);
        long fb = a1 == 0 ? Val.Nil : Val.Heap(a1);
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
            long w = Vec.Nth(rt, rt.Slot(rt.R(si), SC_WAITERS), (int) free, Val.NotFound);
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
        long w = Vec.Nth(rt, Waiters(rt), idx, Val.NotFound);
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
            long w = Vec.Nth(rt, ws, i, Val.NotFound);
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
            long w = Vec.Nth(rt, ws, i, Val.NotFound);
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
        int ai = rt.Push(NewPort(rt, cap, rt.R(li), K_CHANNEL, P_OPEN, -1));
        int bi = rt.Push(NewPort(rt, cap, rt.R(li), K_CHANNEL, P_OPEN, -1));
        LinkPeers(rt, rt.R(ai), rt.R(bi));
        int vi = rt.Push(Vec.Empty(rt));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(ai)));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(bi)));
        long outv = rt.R(vi);
        rt.PopTo(bas);
        return outv;
    }

    /// The handle in THIS sandbox for the host's port `hostId`, minting one if
    /// this sandbox does not hold it yet (`doc/decisions/0027`).
    ///
    /// THIS IS THE REFERENCE COUNT, and it is a count of HOLDERS. The weak
    /// intern table is what makes that possible: one handle object per host id
    /// per sandbox, so a port that arrives in two messages -- or is handed in
    /// twice, or arrives having already been handed in -- is the same object
    /// both times. `=` says yes, a map keyed by it hits, and the host is told
    /// exactly once that this sandbox took a reference.
    ///
    /// `announce` says whether to PUSH `EV_RETAIN`, and it is false for a host
    /// that installed or granted the port itself: that host already knows, and
    /// an event it does not need is traffic queued before the program has even
    /// started -- which makes the first run come back "the host is needed" when
    /// nothing is parked. True only for the DECODER, where a port arriving
    /// inside a message is the one case the host could not have known about.
    ///
    /// Either way it is one increment per sandbox, on the MISS that mints the
    /// handle. Counting arrivals instead would make the number mean "how many
    /// references" rather than "how many holders", which is not a number anyone
    /// can act on. The matching `EV_RELEASE` comes from `ReapPorts`, or promptly
    /// from `Close` -- and that one always goes out, because a drop is never
    /// something the host asked for.
    ///
    /// NOT ROOTED, unlike the host end this replaces: a handle nothing refers
    /// to is precisely what a release is for. The system port is the exception
    /// and is rooted by living in `SC_SYSTEM`.
    public static long InstallBridgePort(Rt rt, long hostId, long label, bool announce) {
        EnsureSched(rt);
        if (hostId < 0) return Val.Nil;
        long existing = PortById(rt, hostId);
        if (!Val.IsNil(existing) && Fx(rt.Slot(existing, PT_KIND)) == K_BRIDGE) return existing;
        int bas = rt.Mark();
        int li = rt.Push(label);
        int pi = rt.Push(NewPort(rt, DEFAULT_BRIDGE_CAP, rt.R(li), K_BRIDGE, P_OPEN, hostId));
        if (Val.IsNil(rt.R(pi))) { rt.PopTo(bas); return Val.Nil; }
        // Recorded as HELD, which is what `ReapPorts` walks to notice the drop.
        int si = rt.Push(Sched(rt));
        int bi = rt.Push(rt.Slot(rt.R(si), SC_BRIDGES));
        long nb = Vec.Conj(rt, rt.R(bi), Val.Fixnum(hostId));
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_BRIDGES, nb);
        if (announce) PushEvent(rt, EV_RETAIN, hostId, 0, Val.Nil);
        long outv = rt.R(pi);
        rt.PopTo(bas);
        return outv;
    }

    /// Install the system port: the bridge a sandbox is DRIVEN over.
    ///
    /// A sandbox that is given one can ask for more ports on it; a sandbox that
    /// is not has no way to reach anything outside itself, which is the honest
    /// meaning of "no capabilities" and is the default.
    public static long InstallSystemPort(Rt rt, long hostId, long label) {
        long p = InstallBridgePort(rt, hostId, label, false);
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
    /// A port object. `id` is `-1` to mint one from this sandbox's counter,
    /// which is what a channel end does; a bridge handle passes the HOST's id
    /// instead, because that is the id that means the same thing on both sides.
    static long NewPort(Rt rt, long cap, long label, long kind, long state, long id) {
        int bas = rt.Mark();
        int li = rt.Push(label);
        int pi = rt.Push(NewObj(rt, Obj.TyPort, PT_LEN));
        if (Val.IsNil(rt.R(pi))) { rt.PopTo(bas); return Val.Nil; }
        int si = rt.Push(Sched(rt));
        if (id < 0) {
            id = Fx(rt.Slot(rt.R(si), SC_NEXTID));
            rt.SetSlot(Val.AsHeap(rt.R(si)), SC_NEXTID, Val.Fixnum(id + 1));
        }
        long p = Val.AsHeap(rt.R(pi));
        rt.SetSlot(p, PT_ID, Val.Fixnum(id));
        rt.SetSlot(p, PT_STATE, Val.Fixnum(state));
        rt.SetSlot(p, PT_CAP, Val.Fixnum(cap));
        // The ring, allocated ONCE: a send must not allocate. ONE array: a
        // slot's own word says whether it is vacant.
        long ring = kind == K_CHANNEL ? System.Math.Max(cap, 1) : RingMessages;
        rt.SetSlot(p, PT_RING, Val.Fixnum(ring));
        int sli = rt.Push(NewObj(rt, Obj.TyNode, (int) ring));
        for (int i = 0; i < ring; i++) rt.SetSlot(Val.AsHeap(rt.R(sli)), i, Val.Empty);
        rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_INBOX, rt.R(sli));
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
        RegisterPort(rt, rt.R(pi));
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
            if (NeedsGrow(t)) { t.Grow(); t.Lookup(id, v => false); }
            InsertAt(t, t.slot, id, rt.R(pi));
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
            long e = Vec.Nth(rt, ps, i, Val.NotFound);
            if (Fx(Vec.Nth(rt, e, 0, Val.NotFound)) == id) return Fx(Vec.Nth(rt, e, 1, Val.NotFound));
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
        return CheckSendableAt(rt, v, 0, CarryCrossing);
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
    /// There used to be a third class, for a port whose codec ran in the
    /// SANDBOX. There is no such port any more: encoding happens at the bridge
    /// boundary, in the runtime, and a guest is never handed an encoder. The
    /// rule that class enforced is now enforced by the guest not having one.
    public const int CarryLocal = 0, CarryCrossing = 1;

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
                if (CrossesAHeap(Fx(rt.Slot(v, PT_KIND)))) return null;
                return "a channel endpoint cannot be sent to the host: both its ends"
                     + " live in this heap and the host has never been told it exists,"
                     + " so its id would name one of our objects from outside. A bridge"
                     + " can be sent, because its id is the host's own.";
            // An opaque value is identity and nothing else
            // (`doc/decisions/0022`), so there is nothing to serialise that
            // would still BE it. Anything a codec could write down is something
            // the receiver could write down too, and then it is mintable --
            // which is the entire property gone.
            case Obj.TyOpaque:
                return null;
            case Obj.TyStr: case Obj.TyRope: case Obj.TySym: case Obj.TyKw:
            case Obj.TyBigint: case Obj.TyRegex:
                return null;
            default: break;
        }
        int bas = rt.Mark();
        int vi = rt.Push(v);
        string outs = null;
        if (Mapcore.IsMap(rt, rt.R(vi))) {
            // Materialised ON THE SHADOW STACK, not into a host list. The walk
            // below allocates, so anything held in a host `List<long>` across
            // it comes back naming the address the object had before the
            // nursery flipped -- `doc/decisions/0031`, which is exactly the rule
            // a list of raw `long`s is invisible to.
            int at = rt.Mark();
            int en = Maps.Entries(rt, rt.R(vi));
            for (int i = 0; i < 2 * en; i++) {
                outs = CheckSendableAt(rt, rt.R(at + i), depth + 1, carry);
                if (outs != null) break;
            }
            rt.PopTo(at);
        } else if (Sets.IsSet(rt, rt.R(vi))) {
            int ei = rt.Push(Sets.ElementVector(rt, rt.R(vi)));
            int en = Vec.Count(rt, rt.R(ei));
            for (int i = 0; i < en; i++) {
                outs = CheckSendableAt(rt, Vec.Nth(rt, rt.R(ei), i, Val.NotFound), depth + 1, carry);
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
    /// one heap? One predicate rather than a widening `==` at each of six
    /// sites, because the last time this was a set of scattered comparisons one
    /// of them was missed.
    public static bool CrossesAHeap(long kind) { return kind == K_BRIDGE; }

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
        int carry = CrossesAHeap(kind) ? CarryCrossing : CarryLocal;
        string bad = CheckSendableVia(rt, rt.R(vi), carry);
        if (bad != null) { rt.PopTo(bas); return rt.ThrowStr("IllegalArgumentException", bad); }
        if (CrossesAHeap(kind)) {
            // ENCODING HAPPENS HERE, ALWAYS, AND ONLY HERE.
            //
            // A bridge carries bytes and the runtime is what writes them. The
            // guest hands over a VALUE and is handed one back; it never sees an
            // encoding, has no encoder, and cannot choose one. That is the
            // safety rule `Codec` states: a decoder reachable from the guest
            // would be an encoder read backwards, and since `K_PORT` and
            // `K_SENTINEL` carry their identity inline as integers a guest can
            // write, such a guest could mint any host id it liked. An opaque
            // value's whole meaning is that it cannot.
            byte[] enc;
            try {
                enc = Codec.Encode(rt, rt.R(vi));
            } catch (Codec.Refused e) {
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalArgumentException",
                    "send: this cannot cross a bridge: " + e.Message);
            }
            rt.SetR(vi, Bytes.Of(rt, enc));
            // Bound the queue in BYTES: back-pressure exists to bound memory,
            // and one 4 MB message is not one message's worth of it.
            //
            // On the handle itself. A bridge is ONE object here -- the far end
            // is the host's registry and is not in any heap -- so it is its own
            // accounting, where a host port used to need a second object to
            // carry the count.
            long len = enc.Length;
            long cap = Fx(rt.Slot(rt.R(pi), PT_CAP));
            long queued = Fx(rt.Slot(rt.R(pi), PT_BYTES));
            if (queued > 0 && queued + len > cap) {
                long tgt = rt.R(pi);
                rt.PopTo(bas);
                return ParkOnPort(rt, WK_SEND, tgt);
            }
            rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_BYTES, Val.Fixnum(queued + len));
            long hid = Fx(rt.Slot(rt.R(pi), PT_ID));
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
                // A bridge queues `[len value]`, and `len` is what
                // `HostDeliver` actually CHARGED -- the length of the encoded
                // message, which is what bounds the host's queue.
                //
                // It used to be recomputed from the value, `Str.ByteLen` on
                // whatever came out of the ring. That was wrong twice: it
                // refunded the string's length where the encoded length had
                // been charged, and once a bridge carried VALUES it walked a
                // keyword as a string. On the Rust that was a segfault.
                long item = rt.R(vi);
                long n = Fx(Vec.Nth(rt, item, 0, Val.NotFound));
                rt.SetR(vi, Vec.Nth(rt, item, 1, Val.NotFound));
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
        // A BRIDGE has no peer OBJECT to ask about: the far end is the
        // host's registry and is not in any heap (`doc/decisions/0027`). Its own
        // state is the whole answer, and the states above have already covered
        // every way that can say "no more" -- so an empty buffer here means
        // "nothing yet", which is what parking is for.
        if (Fx(rt.Slot(rt.R(pi), PT_KIND)) != K_BRIDGE) {
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
    /// Drop `id` from the held list, so the sweep does not release it twice.
    static void ForgetBridge(Rt rt, long id) {
        long s = Sched(rt);
        if (Val.IsNil(s)) return;
        int bas = rt.Mark();
        int si = rt.Push(s);
        int bi = rt.Push(rt.Slot(rt.R(si), SC_BRIDGES));
        int n = Vec.Count(rt, rt.R(bi));
        int ki = rt.Push(Vec.Empty(rt));
        for (int k = 0; k < n; k++) {
            long x = Fx(Vec.Nth(rt, rt.R(bi), k, Val.NotFound));
            if (x == id) continue;
            rt.SetR(ki, Vec.Conj(rt, rt.R(ki), Val.Fixnum(x)));
        }
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_BRIDGES, rt.R(ki));
        rt.PopTo(bas);
    }

    static void CloseSideEffects(Rt rt, long p) {
        int bas = rt.Mark();
        int pi = rt.Push(p);
        if (CrossesAHeap(Fx(rt.Slot(rt.R(pi), PT_KIND)))) {
            // A CLOSE IS A RELEASE, and it is the prompt one.
            //
            // Dropping the last reference and waiting for the collector gets
            // here too, via `ReapPorts`, but that is the backstop rather than
            // the mechanism -- it is not prompt, and a host holding a socket
            // until then is a real cost. Closing says so now. The id leaves
            // `SC_BRIDGES` in the same breath, so the sweep does not send a
            // second release for a port already let go.
            long id = Fx(rt.Slot(rt.R(pi), PT_ID));
            PushEvent(rt, EV_CLOSED, id, 0, Val.Nil);
            ForgetBridge(rt, id);
            PushEvent(rt, EV_RELEASE, id, 0, Val.Nil);
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
            // A GRANT left the handle here; a refusal left the sentinel below.
            // Only a refusal is a refusal -- the host may grant and then close
            // the port before this thread is next scheduled, and the port is
            // then `P_HALF` ("granted, and now finished"), not "you may not
            // have this".
            if (IsPort(rt, pending)) { rt.PopTo(bas); return pending; }
            string nm2 = Str.IsString(rt, rt.R(ni)) ? Str.Text(rt, rt.R(ni)) : "?";
            rt.PopTo(bas);
            return rt.ThrowStr("SecurityException", "the host refused to open \"" + nm2 + "\"");
        }
        // NO SYSTEM PORT, NO ASKING. A sandbox given no transport has no way to
        // reach anything outside itself, and saying so here is more honest than
        // pushing an event nothing will ever drain -- that would park the thread
        // for ever and read as a hang rather than as a refusal.
        long sys = SystemPort(rt);
        if (Val.IsNil(sys)) {
            string nm3 = Str.IsString(rt, rt.R(ni)) ? Str.Text(rt, rt.R(ni)) : "?";
            rt.PopTo(bas);
            return rt.ThrowStr("SecurityException",
                "this sandbox was given no system port, so it cannot ask for \"" + nm3 + "\"");
        }
        int si = rt.Push(sys);
        // The waiter hangs off the SYSTEM port, because that is the port the
        // request went out on and there is no other port yet -- the whole point
        // is that the answer is what creates one.
        long token = NewWaiter(rt, WK_OPEN, rt.R(si));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_TOKEN, Val.Fixnum(token));
        // Marked as awaiting an answer with a value that is NOT a port, so the
        // resume above can tell "granted" from "refused" by type rather than by
        // a state flag on an object that does not exist until granted.
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_PENDING, Val.Fixnum(0));
        // THE ARGUMENTS, ENCODED, are the payload -- not a bare name string.
        // That is the whole of "the host does what it wants with them": one
        // value crosses, and anything an opaque value carries survives the trip
        // because the codec already knew how to write one down.
        int vi = rt.Push(Vec.Empty(rt));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(ni)));
        int an = rt.IsHeapTy(rt.R(ai), Obj.TyVec) ? Vec.Count(rt, rt.R(ai)) : 0;
        for (int k = 0; k < an; k++) {
            rt.SetR(vi, Vec.Conj(rt, rt.R(vi), Vec.Nth(rt, rt.R(ai), k, Val.NotFound)));
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
        int payi = rt.Push(Bytes.Of(rt, call));
        long sysId = Fx(rt.Slot(rt.R(si), PT_ID));
        PushEvent(rt, EV_OPEN, token, sysId, rt.R(payi));
        long target = rt.R(si);
        rt.PopTo(bas);
        return Park(rt, target);
    }

    /// Ask the host for something, and get a VALUE back.
    ///
    /// `PortOpen` generalised (`doc/decisions/0036` step 7). The two differ in
    /// exactly one place -- what the answer may be -- and that difference is
    /// load-bearing, so they are two methods rather than one with a flag: a
    /// port is granted BY ID through `HostGrant` and never encoded, because
    /// encoding a handle the host is lending is the one thing the codec must
    /// not do. Everything else is the same park, waiter and system port.
    public static long HostRequest(Rt rt, long what, long args) {
        EnsureSched(rt);
        int bas = rt.Mark();
        int ni = rt.Push(what), ai = rt.Push(args);
        int ti = rt.Push(CurrentThread(rt));
        long pending = rt.Slot(rt.R(ti), TH_PENDING);
        if (!Val.IsNil(pending)) {
            // Second time round: the host has answered.
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_PENDING, Val.Nil);
            // A one-element VECTOR is an answer, holding whatever the host
            // sent. Anything else is a refusal.
            //
            // The wrapper is not decoration. An answer may be any value at all,
            // NIL included, so "answered" cannot be read off the value's type
            // the way `PortOpen` reads it off `IsPort` -- a host answering nil
            // and a host refusing would be the same bits.
            if (rt.IsHeapTy(pending, Obj.TyVec)) {
                long v = Vec.Count(rt, pending) > 0 ? Vec.Nth(rt, pending, 0, Val.NotFound) : Val.Nil;
                rt.PopTo(bas);
                return v;
            }
            string nm2 = Str.IsString(rt, rt.R(ni)) ? Str.Text(rt, rt.R(ni)) : "?";
            rt.PopTo(bas);
            return rt.ThrowStr("SecurityException",
                "the host refused the request \"" + nm2 + "\"");
        }
        // NO SYSTEM PORT, NO ASKING -- the same honesty as `PortOpen`: pushing
        // an event nothing will drain parks the thread for ever and reads as a
        // hang rather than as a refusal.
        long sys = SystemPort(rt);
        if (Val.IsNil(sys)) {
            string nm3 = Str.IsString(rt, rt.R(ni)) ? Str.Text(rt, rt.R(ni)) : "?";
            rt.PopTo(bas);
            return rt.ThrowStr("SecurityException",
                "this sandbox was given no system port, so it cannot ask for \"" + nm3 + "\"");
        }
        int si = rt.Push(sys);
        long token = NewWaiter(rt, WK_REQUEST, rt.R(si));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_TOKEN, Val.Fixnum(token));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TH_PENDING, Val.Fixnum(0));
        // `[what & args]`, encoded -- the same payload shape `PortOpen` sends,
        // so a host that already routes one routes the other.
        int vi = rt.Push(Vec.Empty(rt));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.R(ni)));
        int an = rt.IsHeapTy(rt.R(ai), Obj.TyVec) ? Vec.Count(rt, rt.R(ai)) : 0;
        for (int k = 0; k < an; k++) {
            rt.SetR(vi, Vec.Conj(rt, rt.R(vi), Vec.Nth(rt, rt.R(ai), k, Val.NotFound)));
        }
        byte[] call;
        try {
            call = Codec.Encode(rt, rt.R(vi));
        } catch (Codec.Refused e) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalArgumentException",
                "request: this cannot be sent to the host: " + e.Message);
        }
        int payi = rt.Push(Bytes.Of(rt, call));
        long sysId = Fx(rt.Slot(rt.R(si), PT_ID));
        PushEvent(rt, EV_REQUEST, token, sysId, rt.R(payi));
        long target = rt.R(si);
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
        if (ok) {
            // A GRANT HAS TO NAME A PORT. There is no port to grant until the
            // host says which one -- that is what `0027` inverted -- so this
            // form can only ever mean a refusal, and a host that means to grant
            // calls `HostGrant`. Answering `true` here would have to invent a
            // port, which is exactly the construction the sandbox may not do and
            // the host must not be able to do by accident.
            return false;
        }
        long w = WaiterAt(rt, token);
        if (Val.IsNil(w)) return false;
        int bas = rt.Mark();
        int wi = rt.Push(w);
        // The refusal is left on the thread as a non-port, which `PortOpen`
        // reads on resume. Nothing else has to be cleaned up, because a refused
        // open allocated nothing in the first place.
        WakeWaiter(rt, rt.R(wi));
        rt.PopTo(bas);
        return true;
    }

    /// Grant an open: hand the waiting thread a handle on the host's port
    /// `hostPortId` (`doc/decisions/0027`).
    ///
    /// The id is the HOST's. It is the same id in every sandbox that holds this
    /// port, which is what makes a handle sendable between two of them at all,
    /// and it is the id the retain and release events name.
    ///
    /// If this sandbox already holds that port, the SAME handle comes back and
    /// no reference is taken -- granting a port twice is not two holders.
    public static bool HostGrant(Rt rt, long token, long hostPortId) {
        long w = WaiterAt(rt, token);
        if (Val.IsNil(w)) return false;
        int bas = rt.Mark();
        int wi = rt.Push(w);
        long label = rt.Slot(rt.R(wi), W_PORT);
        label = Val.IsNil(label) ? Val.Nil : rt.Slot(label, PT_LABEL);
        int li = rt.Push(label);
        long p = InstallBridgePort(rt, hostPortId, rt.R(li), false);
        if (Val.IsNil(p)) { rt.PopTo(bas); return false; }
        int pi = rt.Push(p);
        long th = rt.Slot(rt.R(wi), W_THREAD);
        if (!Val.IsNil(th)) rt.SetSlot(Val.AsHeap(th), TH_PENDING, rt.R(pi));
        WakeWaiter(rt, rt.R(wi));
        rt.PopTo(bas);
        return true;
    }

    /// The host's answer to an `EV_REQUEST`, as encoded bytes.
    ///
    /// Decoded HERE, at the boundary, like every other thing crossing a bridge
    /// (`doc/decisions/0027`): the guest gets a value and never a codec.
    ///
    /// To REFUSE, call `HostContinue(token, false)` as with an open -- a
    /// refusal carries no value and needs no bytes.
    public static bool HostAnswer(Rt rt, long token, byte[] bytes) {
        long w = WaiterAt(rt, token);
        if (Val.IsNil(w)) return false;
        int bas = rt.Mark();
        int wi = rt.Push(w);
        long v;
        try {
            v = Codec.Decode(rt, bytes);
        } catch (Exception) {
            rt.PopTo(bas);
            return false;
        }
        int vi = rt.Push(v);
        // Wrapped, so that a host answering nil is distinguishable from a host
        // refusing. See `HostRequest`.
        int oi = rt.Push(Vec.Empty(rt));
        rt.SetR(oi, Vec.Conj(rt, rt.R(oi), rt.R(vi)));
        long th = rt.Slot(rt.R(wi), W_THREAD);
        if (!Val.IsNil(th)) rt.SetSlot(Val.AsHeap(th), TH_PENDING, rt.R(oi));
        WakeWaiter(rt, rt.R(wi));
        rt.PopTo(bas);
        return true;
    }

    /// Put a message into a bridge from the host's side. Wakes a parked
    /// receiver; it does not run anything.
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
        // A bridge is ONE object and the id is its own, so the lookup above has
        // already found the end to deliver into. There is no pair and no peer
        // hop: that indirection existed only because a host port kept its
        // bookkeeping on a second object (`doc/decisions/0027`).
        int pi = rt.Push(rt.R(hi));
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
        long v;
        try {
            v = Codec.Decode(rt, bytes);
        } catch (System.Exception) {
            // Refused rather than delivered as anything else: a message the
            // format cannot read is the host's error, and turning it into a
            // string here would hand the guest something that silently was not
            // what was sent.
            GiveBack(rt, rt.R(pi), len);
            rt.PopTo(bas);
            return false;
        }
        int vi = rt.Push(v);
        // `[len value]`, because the refund has to be the number that was
        // CHARGED and nothing about a decoded value says what that was.
        {
            int m = rt.Mark();
            int ei = rt.Push(Vec.Empty(rt));
            rt.SetR(ei, Vec.Conj(rt, rt.R(ei), Val.Fixnum(len)));
            rt.SetR(vi, Vec.Conj(rt, rt.R(ei), rt.R(vi)));
            rt.PopTo(m);
        }
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
    /// HALF-CLOSED, not closed: whatever the host already delivered is still
    /// there to be read, and only when that is drained does it read as end of
    /// stream. There is one object now, not a pair, so this is the state of the
    /// handle itself rather than of a second end standing in for it.
    public static void HostClosePort(Rt rt, long hostPortId) {
        long p = PortById(rt, hostPortId);
        if (Val.IsNil(p)) return;
        int bas = rt.Mark();
        int hi = rt.Push(p);
        if (Fx(rt.Slot(rt.R(hi), PT_STATE)) == P_OPEN) {
            rt.SetSlot(Val.AsHeap(rt.R(hi)), PT_STATE, Val.Fixnum(P_HALF));
        }
        WakeOn(rt, rt.R(hi));
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
        long p = PortById(rt, hostPortId);
        // Never heard of, or the handle has been collected. Either way a host
        // treats it as done, which is the case a missed `:closed` event would
        // otherwise leak.
        if (Val.IsNil(p)) return 255;
        return PortStateNow(rt, p);
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
            long e = Vec.Nth(rt, rt.R(ei), i, Val.NotFound);
            long kind = Fx(Vec.Nth(rt, e, 0, Val.NotFound));
            long a = Fx(Vec.Nth(rt, e, 1, Val.NotFound));
            long b = Fx(Vec.Nth(rt, e, 2, Val.NotFound));
            long payload = Vec.Nth(rt, e, 3, Val.NotFound);
            int off = (int) outb.Length;   // the header is already in `outb`
            int plen;
            if (Bytes.IsBytes(rt, payload)) {
                byte[] bs = Bytes.ToArray(rt, payload);
                outb.Write(bs, 0, bs.Length);
                plen = bs.Length;
            } else if (rt.IsHeapTy(payload, Obj.TyVec)) {
                int m = Vec.Count(rt, payload);
                for (int k = 0; k < m; k++) outb.WriteByte((byte) Fx(Vec.Nth(rt, payload, k, Val.NotFound)));
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
        // --- bridges: a collection is a RELEASE -----------------------------
        //
        // The handle is ordinary memory and is not rooted, so the collector
        // finding it unreachable IS this sandbox letting the port go. One
        // release per retain, which is what makes the host's count a count of
        // holders rather than of arrivals (`doc/decisions/0027`).
        int bri = rt.Push(rt.Slot(rt.R(si), SC_BRIDGES));
        int brn = Vec.Count(rt, rt.R(bri));
        int hli = rt.Push(Vec.Empty(rt));
        for (int k = 0; k < brn; k++) {
            long bid = Fx(Vec.Nth(rt, rt.R(bri), k, Val.NotFound));
            if (Val.IsNil(PortById(rt, bid))) {
                // CLOSED as well as released. `doc/decisions/0006`: an end the
                // collector finds unreachable IS the script having called
                // `Close`, so the host hears the same pair either way.
                PushEvent(rt, EV_CLOSED, bid, 0, Val.Nil);
                PushEvent(rt, EV_RELEASE, bid, 0, Val.Nil);
                continue;
            }
            rt.SetR(hli, Vec.Conj(rt, rt.R(hli), Val.Fixnum(bid)));
        }
        rt.SetSlot(Val.AsHeap(rt.R(si)), SC_BRIDGES, rt.R(hli));

        // --- channels: a collected end orphans its peer ----------------------
        int ii = rt.Push(rt.Slot(rt.R(si), SC_PORTS));
        int n = Vec.Count(rt, rt.R(ii));
        int li = rt.Push(Vec.Empty(rt));
        for (int k = 0; k < n; k++) {
            long id = Fx(Vec.Nth(rt, rt.R(ii), k, Val.NotFound));
            long p = PortById(rt, id);
            if (!Val.IsNil(p)) {
                rt.SetR(li, Vec.Conj(rt, rt.R(li), Val.Fixnum(id)));
                continue;
            }
            // This end has been collected. Tell whoever is affected.
            long peer = PortById(rt, PeerIdOfDead(rt, id));
            if (Val.IsNil(peer)) continue;
            int pi = rt.Push(peer);
            long pst = Fx(rt.Slot(rt.R(pi), PT_STATE));
            if (pst != P_CLOSED && pst != P_ORPHANED) {
                // Its peer vanished WITHOUT closing, which is not the same as a
                // tidy close and should not read like one.
                rt.SetSlot(Val.AsHeap(rt.R(pi)), PT_STATE, Val.Fixnum(P_ORPHANED));
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
            long w = Vec.Nth(rt, rt.R(wsi), i, Val.NotFound);
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
    /// Program exit: close and release every bridge, so a host is never left
    /// holding a reference for a sandbox that has finished, and leave the
    /// events for the final drain.
    public static void CloseAllBridges(Rt rt) {
        long s = Sched(rt);
        if (Val.IsNil(s)) return;
        int bas = rt.Mark();
        int si = rt.Push(s);
        int ii = rt.Push(rt.Slot(rt.R(si), SC_PORTS));
        int n = Vec.Count(rt, rt.R(ii));
        for (int k = 0; k < n; k++) {
            long p = PortById(rt, Fx(Vec.Nth(rt, rt.R(ii), k, Val.NotFound)));
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
            long th = Vec.Nth(rt, ts, i, Val.NotFound);
            if (Val.IsNil(th) || th == Val.NotFound) continue;
            long st = Fx(rt.Slot(th, TH_STATUS));
            if (st == ST_NEW || st == ST_RUNNABLE) return i;
        }
        return -1;
    }

    static void RunOne(Rt rt, int i) {
        long s = Sched(rt);
        rt.SetSlot(Val.AsHeap(s), SC_CURRENT, Val.Fixnum(i));
        long th = Vec.Nth(rt, rt.Slot(s, SC_THREADS), i, Val.NotFound);
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
        long th = Vec.Nth(rt, rt.Slot(Sched(rt), SC_THREADS), 0, Val.NotFound);
        if (Val.IsNil(th) || th == Val.NotFound) return true;
        long st = Fx(rt.Slot(th, TH_STATUS));
        return st == ST_DONE || st == ST_FAILED;
    }

    static long MainResult(Rt rt) {
        long th = Vec.Nth(rt, rt.Slot(Sched(rt), SC_THREADS), 0, Val.NotFound);
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
            long th = Vec.Nth(rt, ts, i, Val.NotFound);
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
                CloseAllBridges(rt);
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
                long th = Vec.Nth(rt, ts, k, Val.NotFound);
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
