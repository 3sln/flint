namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Interns;
using _3sln.Flint.Kgen.Rt;


/// Green threads, ports, and the scheduler. Ported from `runtime/src/conc.rs`
/// (`DECISIONS.md#threads-and-ports`).
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
    /// (`DECISIONS.md#ports-are-the-hosts`). A bridge handle is ordinary memory -- a handle
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
    /// Two kinds, and no third (`DECISIONS.md#ports-are-the-hosts`). `K_CHANNEL` joins two
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
    /// is of HOLDERS (`DECISIONS.md#ports-are-the-hosts`).
    public const int EV_OPEN = 1, EV_MESSAGE = 2, EV_CLOSED = 3,
                     EV_RETAIN = 4, EV_RELEASE = 5,
    /// The guest is asking the host for SOMETHING, and the answer is an
    /// ordinary value rather than a port (`DECISIONS.md#workspace-capabilities` step 7).
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
    /// VALUE rather than necessarily a port (`DECISIONS.md#workspace-capabilities` step 7).
                     WK_REQUEST = 5;

    // --- scheduler ---------------------------------------------------------

    public const int SC_THREADS = 0, SC_CURRENT = 1, SC_NEXTID = 2,
        SC_EVENTS = 3, SC_EHEAD = 4, SC_PORTS = 5, SC_PAIRS = 6,
        SC_WAITERS = 7, SC_WFREE = 8, SC_SYSTEM = 9,
        /// Host ids of every BRIDGE this sandbox holds a handle for -- ids, not
        /// references, so the list pins nothing. This is the walk that turns a
        /// collection into a release (`DECISIONS.md#ports-are-the-hosts`).
        SC_BRIDGES = 10, SC_LEN = 11;

    /// Instructions a thread runs before the scheduler takes the slice back.
    /// Preemptive, so a thread with no `yield` in it cannot starve the others.
    public const long SLICE = 4096;

    static long Fx(long v) { return Val.AsFixnum(v); }

    /// `public` because the generated tree calls it -- see the Java copy.
    public static long NewObj(Rt rt, int ty, int len) {
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
    /// Install the scheduler as the singleton the collector already traces.
    public static void InstallSched(Rt rt, long s) {
        rt.roots.shared.Singletons[Rt.SingSched] = s;
    }

    /// Is a program actually running? An empty frame stack means thread 0
    /// represents no stack at all.
    public static bool SomethingRunning(Rt rt) {
        return rt.frames.Count != 0;
    }

    /// Make the scheduler if there is not one. The OBJECT is generated, from
    /// `kin/schedmake.kin`.
    public static long EnsureSched(Rt rt) {
        long s = Sched(rt);
        if (!Val.IsNil(s)) return s;
        // The decoder's route to `InstallBridgePort`, set HERE and nowhere else.
        rt.bridgeHook = (r, id) => InstallBridgePort(r, id, Val.Nil, true);
        long outv = global::_3sln.Flint.Kgen.Rt.Schedmake.NewSchedAt(rt);
        if (Val.IsNil(outv)) return Val.Nil;
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
        // cost more interpreted than compiled (`DECISIONS.md#resource-limits`).
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

        // UNBILLED, like the stack and frame buffers above -- see
        // `runtime/src/conc.rs` for the reasoning.
        long a2 = rt.AllocUnbilled(Obj.TyRaw, rt.handlers.Count * 16);
        long hb = a2 == 0 ? Val.Nil : Val.Heap(a2);
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

    public static void RestoreState(Rt rt, long th) {
        rt.restores++;
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
    /// GENERATED (`kin/sched.kin`) -- see the note on the JVM's `park`.
    public static long Park(Rt rt, long on) {
        return global::_3sln.Flint.Kgen.Rt.Sched.Park(rt, on);
    }

    // --- spawning -----------------------------------------------------------

    /// A new green thread running `f` (no arguments).
    ///
    /// It INHERITS A SNAPSHOT of its spawner's dynamic bindings, which is what
    /// Clojure conveys to `future` and agents, and what somebody debugging at
    /// three in the morning will assume. A snapshot: later `binding` in the
    /// spawner does not reach the child.
    /// GENERATED, from `kin/portmake.kin`.
    public static long Spawn(Rt rt, long f) {
        return global::_3sln.Flint.Kgen.Rt.Portmake.SpawnAt(rt, f);
    }

    // --- waiters ------------------------------------------------------------

    static long Waiters(Rt rt) { return rt.Slot(Sched(rt), SC_WAITERS); }

    /// A waiter records WHICH THREAD is parked on WHICH PORT. Slots are reused
    /// through a free list rather than compacted, because a token names an
    /// index into this vector and compacting would invalidate every one held.
    /// GENERATED (`kin/sched.kin`) -- see the note on the JVM's `newWaiter`.
    static long NewWaiter(Rt rt, long kind, long port) {
        return global::_3sln.Flint.Kgen.Rt.Sched.NewWaiter(rt, kind, port);
    }

    /// GENERATED (`kin/sched.kin`) -- see the note on the JVM's `waiterAt`.
    static long WaiterAt(Rt rt, long token) {
        return global::_3sln.Flint.Kgen.Rt.Sched.WaiterAt(rt, token);
    }

    /// GENERATED (`kin/sched.kin`). The generated one answers the waiter it
    /// freed; this port's callers do not need it.
    static void FreeWaiter(Rt rt, long token) {
        global::_3sln.Flint.Kgen.Rt.Sched.FreeWaiter(rt, token);
    }

    /// GENERATED (`kin/sched.kin`). How many green threads are parked with a
    /// token outstanding. A host that never answers leaks these; the deadlock
    /// report names them.
    public static int OutstandingWaiters(Rt rt) {
        return global::_3sln.Flint.Kgen.Rt.Sched.OutstandingWaiters(rt);
    }

    /// Park until there is room in `p`'s ring.
    ///
    /// The re-check after registering is not belt and braces: `WakeOn` reaches
    /// only waiters ALREADY in the list, so a receive that drains the ring
    /// between the failed reservation and the registration would wake nobody.
    /// GENERATED (`kin/portpark.kin`). Park until there is room in `p`'s
    /// ring -- and look AGAIN after registering, because `WakeOn` reaches
    /// only waiters already in the list.
    static long ParkForSpace(Rt rt, long p) {
        return global::_3sln.Flint.Kgen.Rt.Portpark.ParkForRoom(rt, p);
    }

    /// GENERATED (`kin/sched.kin`) -- see the note on the JVM's `parkOnPort`.
    static long ParkOnPort(Rt rt, long kind, long port) {
        return global::_3sln.Flint.Kgen.Rt.Sched.ParkOnPort(rt, kind, port);
    }

    /// GENERATED (`kin/sched.kin`) -- see the note on the JVM's `wakeWaiter`.
    static void WakeWaiter(Rt rt, long w) {
        global::_3sln.Flint.Kgen.Rt.Sched.WakeWaiter(rt, w);
    }

    /// Make every thread waiting on `p` runnable again.
    ///
    /// They RE-EXECUTE the call they parked in, which is what makes "wake"
    /// correct without anyone reasoning about who gets the value: whoever runs
    /// first takes it, and the others simply park again.
    /// GENERATED (`kin/sched.kin`) -- see the note on the JVM's `wakeOn`.
    public static void WakeOn(Rt rt, long p) {
        global::_3sln.Flint.Kgen.Rt.Sched.WakeOn(rt, p);
    }

    // --- channels -----------------------------------------------------------

    /// A coupled pair. What goes into one comes out of the other, both ways.
    /// GENERATED, from `kin/portmake.kin`.
    public static long Channel(Rt rt, long cap, long label) {
        return global::_3sln.Flint.Kgen.Rt.Portmake.ChannelAt(rt, cap, label);
    }

    /// The handle in THIS sandbox for the host's port `hostId`, minting one if
    /// this sandbox does not hold it yet (`DECISIONS.md#ports-are-the-hosts`).
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
    /// GENERATED, from `kin/portinstall.kin`.
    public static long InstallBridgePort(Rt rt, long hostId, long label, bool announce) {
        return global::_3sln.Flint.Kgen.Rt.Portinstall.InstallBridgeAt(rt, hostId, label, announce);
    }

    /// Install the system port: the bridge a sandbox is DRIVEN over.
    ///
    /// A sandbox that is given one can ask for more ports on it; a sandbox that
    /// is not has no way to reach anything outside itself, which is the honest
    /// meaning of "no capabilities" and is the default.
    /// GENERATED, from `kin/portinstall.kin`.
    public static long InstallSystemPort(Rt rt, long hostId, long label) {
        return global::_3sln.Flint.Kgen.Rt.Portinstall.InstallSystemAt(rt, hostId, label);
    }

    /// The system port. NOT reachable from guest code, and that is the point.
    ///
    /// It is not a capability the sandbox holds, it is the TRANSPORT the
    /// sandbox is driven over: calls in arrive on it, and requests out -- for a
    /// capability, for another port -- leave on it. Handing it to guest code
    /// would make it ambient authority inside the sandbox, which is the thing
    /// `DECISIONS.md#opaque-values` and `ports-are-the-hosts` both exist to prevent. There is no
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
    /// GENERATED, from `kin/portmake.kin`.
    static long NewPort(Rt rt, long cap, long label, long kind, long state, long id) {
        return global::_3sln.Flint.Kgen.Rt.Portmake.NewPortAt(rt, cap, label, kind, state, id);
    }

    /// The registry. WEAK on purpose (`DECISIONS.md#host-abi`): the flint end of a
    /// port is ordinary reachable memory, and when the collector finds it
    /// unreachable that MEANS the script is finished with it. The scheduler
    /// keeps IDS, not references -- a strong list would pin every port for ever
    /// and there would be nothing to notice.
    public static void RegisterPort(Rt rt, long p) {
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
    /// GENERATED, from `kin/portmake.kin`.
    static void LinkPeers(Rt rt, long a, long b) {
        global::_3sln.Flint.Kgen.Rt.Portmake.LinkPeersAt(rt, a, b);
    }

    /// The peer of a port that may itself be gone.
    public static long PeerOf(Rt rt, long p) { return PortById(rt, Fx(rt.Slot(p, PT_PEER))); }

    /// The peer of an id whose OBJECT has been collected. Read from the
    /// scheduler's pair list, which is the only place that survives it.
    public static long PeerIdOfDead(Rt rt, long id) {
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
    /// GENERATED (`kin/portring.kin`).
    static int InboxCount(Rt rt, long p) {
        return global::_3sln.Flint.Kgen.Rt.Portring.RingInboxCount(rt, p);
    }

    /// GENERATED (`kin/portring.kin`).
    static long Cursor(Rt rt, long p, int which) =>
        global::_3sln.Flint.Kgen.Rt.Portring.RingCursor(rt, p, which);

    /// One slot, read atomically. Cursors and sequence words are fixnums like
    /// any other slot -- the collector sees nothing unusual -- and the atomic
    /// operates on the TAGGED word.
    public static long SlotAtomic(Rt rt, long o, int i) =>
        rt.gc.sp.AtomicLoad(Obj.SlotAddr(Val.AsHeap(o), i));

    /// Compare-and-swap a slot, AND run the write barrier when it lands. See
    /// the Rust: a ring in the old generation pointing at a young value is an
    /// edge the collector finds only through the remembered set.
    public static bool CasSlotBarriered(Rt rt, long o, int i, long want, long next) {
        long obj = Val.AsHeap(o);
        if (!rt.gc.sp.Cas(Obj.SlotAddr(obj, i), want, next)) return false;
        if (Val.IsHeap(next) && rt.gc.IsYoung(Val.AsHeap(next)) && !rt.gc.IsYoung(obj)) {
            rt.gc.Remember(obj, rt.roots);
        }
        return true;
    }

    public static bool CasSlot(Rt rt, long o, int i, long want, long next) =>
        rt.gc.sp.Cas(Obj.SlotAddr(Val.AsHeap(o), i), want, next);

    /// Put `v` in `p`'s ring. False means full.
    ///
    /// ONE compare-and-swap: the slot's own word is the lease, and swapping
    /// Empty for the message both claims the slot and fills it. Mirrors the
    /// Rust, including why there is no sequence word.
    /// GENERATED (`kin/portring.kin`) -- see the note on the JVM's `enqueue`.
    static bool Enqueue(Rt rt, long p, long v) {
        return global::_3sln.Flint.Kgen.Rt.Portring.RingEnqueue(rt, p, v);
    }

    /// Take the next message, or nil. The mirror image: swap the message out
    /// for Empty, freeing the slot in the step that takes the value.
    /// GENERATED (`kin/portring.kin`) -- see the note on the JVM's `enqueue`.
    static long Dequeue(Rt rt, long p) {
        return global::_3sln.Flint.Kgen.Rt.Portring.RingDequeue(rt, p);
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
            // `DECISIONS.md#host-abi` refused this outright -- "an endpoint cannot
            // be delegated at run time" -- and `structured-ports` REVERSES it: a capability
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
            // (`DECISIONS.md#opaque-values`), so there is nothing to serialise that
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
            // nursery flipped -- `DECISIONS.md#a-vec-of-values-is-not-a-root`, which is exactly the rule
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
            int si = rt.Push(global::_3sln.Flint.Kgen.Rt.Seqwalk.Seq(rt, rt.R(vi)));
            while (!Val.IsNil(rt.R(si))) {
                int fi = rt.Push(global::_3sln.Flint.Kgen.Rt.Seqwalk.First(rt, rt.R(si)));
                outs = CheckSendableAt(rt, rt.R(fi), depth + 1, carry);
                rt.PopTo(fi);
                if (outs != null) break;
                rt.SetR(si, global::_3sln.Flint.Kgen.Rt.Seqwalk.Next(rt, rt.R(si)));
            }
        }
        rt.PopTo(bas);
        return outs;
    }

    // --- the outbound event queue -------------------------------------------

    /// Append an outbound event. `payload` is a string (or byte string) whose
    /// bytes the host will read; the drain copies them into one contiguous
    /// buffer.
    /// GENERATED, from `kin/schedlists.kin`.
    public static void PushEvent(Rt rt, long kind, long a, long b, long payload) {
        global::_3sln.Flint.Kgen.Rt.Schedlists.PushEventAt(rt, kind, a, b, payload);
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
        // A WRITER IS AN ENCODING, NOT A VALUE, so it is not walked: there is
        // nothing in it to check, and `CheckSendable` would refuse the type it
        // does not know (`DECISIONS.md#the-codec-is-guest-code`).
        bool writer = Wire.IsWriter(rt, rt.R(vi));
        if (writer && !CrossesAHeap(kind)) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalArgumentException",
                "send: a wire writer is an encoding, and a channel carries values -- "
                + "send the value itself, or send this on a bridge");
        }
        int carry = CrossesAHeap(kind) ? CarryCrossing : CarryLocal;
        if (!writer) {
            string bad = CheckSendableVia(rt, rt.R(vi), carry);
            if (bad != null) { rt.PopTo(bas); return rt.ThrowStr("IllegalArgumentException", bad); }
        }
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
            // STRUCTURALLY COMPLETE, or it does not go.
            if (!writer) {
                rt.PopTo(bas);
                return rt.ThrowStr("ClassCastException",
                    "send: a bridge carries an encoding -- use `flint.port/send`, which "
                    + "writes one, rather than the builtin with a bare value");
            }
            if (!Wire.Complete(rt, rt.R(vi))) {
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalStateException",
                    "send: this encoding is unfinished -- a container was opened and "
                    + "not filled");
            }
            long enc = Wire.Finish(rt, rt.R(vi));
            if (Val.IsNil(enc)) {
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalStateException",
                    "send: this wire writer has already been sent");
            }
            rt.SetR(vi, enc);
            // Bound the queue in BYTES: back-pressure exists to bound memory,
            // and one 4 MB message is not one message's worth of it.
            //
            // On the handle itself. A bridge is ONE object here -- the far end
            // is the host's registry and is not in any heap -- so it is its own
            // accounting, where a host port used to need a second object to
            // carry the count.
            long len = Bytes.Count(rt, rt.R(vi));
            long cap = Fx(rt.Slot(rt.R(pi), PT_CAP));
            long queued = Fx(rt.Slot(rt.R(pi), PT_BYTES));
            // THE SAME PREDICATE THE HOST PATH USES -- see the Java copy.
            if (!global::_3sln.Flint.Kgen.Rt.Portbytes.FitsInBudget(rt, queued, len, cap)) {
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
    /// Receive on a BRIDGE as a live reader, for a guest that decodes itself.
    ///
    /// NIL for end of stream, exactly as `Receive` answers it.
    public static long ReceiveReader(Rt rt, long p) {
        long v = Receive(rt, p);
        if (Val.IsNil(v) || !Bytes.IsBytes(rt, v)) return v;
        int bas = rt.Mark();
        int vi = rt.Push(v);
        long outv = Wire.Reader(rt, rt.R(vi), true);
        rt.PopTo(bas);
        return outv;
    }

    /// GENERATED (`kin/portrecv.kin`). The read half of the port protocol.
    /// The `NeedPort` check and its message are a host string and stay here,
    /// which is also where they belong in the order: nothing is rooted yet
    /// when that test runs.
    public static long Receive(Rt rt, long p) {
        if (!NeedPort(rt, p, "receive")) return Val.Nil;
        return global::_3sln.Flint.Kgen.Rt.Portrecv.ReceiveAt(rt, p);
    }

    /// GENERATED (`kin/portpark.kin`). Close `p`, once. The `NeedPort`
    /// check and its message are a host string and stay here, which is also
    /// where they belong in the order: nothing is rooted yet when it runs.
    public static long Close(Rt rt, long p) {
        if (!NeedPort(rt, p, "close")) return Val.Nil;
        return global::_3sln.Flint.Kgen.Rt.Portpark.CloseAt(rt, p);
    }

    /// Everything that follows from an end closing, however it closed: tell the
    /// host if it is the peer, and wake anybody parked on either side.
    /// Drop `id` from the held list, so the sweep does not release it twice.
    /// GENERATED, from `kin/schedlists.kin`.
    public static void ForgetBridge(Rt rt, long id) {
        global::_3sln.Flint.Kgen.Rt.Schedlists.ForgetBridgeAt(rt, id);
    }

    /// GENERATED, from `kin/reapports.kin`. One line, as `ReapPorts` and
    /// `CloseAllBridges` are.
    public static void CloseSideEffects(Rt rt, long p) {
        global::_3sln.Flint.Kgen.Rt.Reapports.CloseEffects(rt, p);
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
    /// A capability is an OPAQUE VALUE (`DECISIONS.md#opaque-values`) and nothing more.
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
        // ENCODED BY THE GUEST (`DECISIONS.md#the-codec-is-guest-code`).
        // `flint.port/open` writes `[name ...args]` with `flint.wire` and hands
        // the writer in; this checks it is finished and takes its bytes.
        if (!Wire.Complete(rt, rt.R(ai))) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalStateException",
                "open: this encoding is unfinished -- a container was opened and not filled");
        }
        long pay = Wire.Finish(rt, rt.R(ai));
        if (Val.IsNil(pay)) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalStateException",
                "open: this encoding has already been sent");
        }
        int payi = rt.Push(pay);
        long sysId = Fx(rt.Slot(rt.R(si), PT_ID));
        PushEvent(rt, EV_OPEN, token, sysId, rt.R(payi));
        long target = rt.R(si);
        rt.PopTo(bas);
        return Park(rt, target);
    }

    /// Ask the host for something, and get a VALUE back.
    ///
    /// `PortOpen` generalised (`DECISIONS.md#workspace-capabilities` step 7). The two differ in
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
                // THE ANSWER'S BYTES, AS A LIVE READER -- the guest decodes it
                // (`DECISIONS.md#the-codec-is-guest-code`).
                long b = Vec.Count(rt, pending) > 0 ? Vec.Nth(rt, pending, 0, Val.NotFound) : Val.Nil;
                int bi = rt.Push(b);
                long outv = Wire.Reader(rt, rt.R(bi), true);
                rt.PopTo(bas);
                return outv;
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
        // `[what & args]`, ENCODED BY THE GUEST
        // (`DECISIONS.md#the-codec-is-guest-code`).
        if (!Wire.Complete(rt, rt.R(ai))) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalStateException",
                "request: this encoding is unfinished -- a container was opened and not filled");
        }
        long pay = Wire.Finish(rt, rt.R(ai));
        if (Val.IsNil(pay)) {
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalStateException",
                "request: this encoding has already been sent");
        }
        int payi = rt.Push(pay);
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
    /// GENERATED, from `kin/porthost.kin`.
    public static bool HostContinue(Rt rt, long token, bool ok) {
        return global::_3sln.Flint.Kgen.Rt.Porthost.HostContinueAt(rt, token, ok);
    }

    /// Grant an open: hand the waiting thread a handle on the host's port
    /// `hostPortId` (`DECISIONS.md#ports-are-the-hosts`).
    ///
    /// The id is the HOST's. It is the same id in every sandbox that holds this
    /// port, which is what makes a handle sendable between two of them at all,
    /// and it is the id the retain and release events name.
    ///
    /// If this sandbox already holds that port, the SAME handle comes back and
    /// no reference is taken -- granting a port twice is not two holders.
    /// GENERATED, from `kin/porthost.kin`.
    public static bool HostGrant(Rt rt, long token, long hostPortId) {
        return global::_3sln.Flint.Kgen.Rt.Porthost.HostGrantAt(rt, token, hostPortId);
    }

    /// The host's answer to an `EV_REQUEST`, as encoded bytes.
    ///
    /// Decoded HERE, at the boundary, like every other thing crossing a bridge
    /// (`DECISIONS.md#ports-are-the-hosts`): the guest gets a value and never a codec.
    ///
    /// To REFUSE, call `HostContinue(token, false)` as with an open -- a
    /// refusal carries no value and needs no bytes.
    public static bool HostAnswer(Rt rt, long token, byte[] bytes) {
        long w = WaiterAt(rt, token);
        if (Val.IsNil(w)) return false;
        int bas = rt.Mark();
        int wi = rt.Push(w);
        // NOT DECODED HERE (`DECISIONS.md#the-codec-is-guest-code`): the bytes
        // reach the parked thread as bytes and the GUEST reads them. The
        // wrapper stays -- an answer may be any value, nil included.
        long v = Bytes.Of(rt, bytes);
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

    /// Walk an encoding and MINT EVERY PORT IN IT, answering them as a vector.
    ///
    /// NIL when the encoding cannot be read. The WALK is `kin/wirescan.kin`;
    /// this is the `byte[]`-shaped door to it.
    static long ScanPorts(Rt rt, byte[] bytes) {
        int bas = rt.Mark();
        int bi = rt.Push(Bytes.Of(rt, bytes));
        // NOT LIVE: a cursor for the walk, never handed to a guest.
        int ri = rt.Push(Wire.Reader(rt, rt.R(bi), false));
        int ai = rt.Push(Vec.Empty(rt));
        bool ok = global::_3sln.Flint.Kgen.Rt.Wirescan.WireScanAt(rt, rt.R(ri), ai, 0);
        if (!ok || Wire.Left(rt, rt.R(ri)) != 0) {
            rt.PopTo(bas);
            return Val.Nil;
        }
        long outv = rt.R(ai);
        rt.PopTo(bas);
        return outv;
    }

    /// Install a bridge port by host id, or NIL if this sandbox has no ports.
    public static long MintBridgePort(Rt rt, long id) {
        if (rt.bridgeHook == null) return Val.Nil;
        return rt.bridgeHook(rt, id);
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
        // bookkeeping on a second object (`DECISIONS.md#ports-are-the-hosts`).
        int pi = rt.Push(rt.R(hi));
        // BACK-PRESSURE in bytes, CLAIMED ATOMICALLY: two host threads
        // delivering into one end would both read the same `queued`, both find
        // room, and both write -- and the bound that exists to cap memory would
        // be the one thing not enforced.
        long len = bytes.Length;
        if (!global::_3sln.Flint.Kgen.Rt.Portbytes.ClaimBytes(rt, rt.R(pi), len)) {
            rt.PopTo(bas);
            return false;
        }
        // SCANNED, NOT DECODED (`DECISIONS.md#the-codec-is-guest-code`). The
        // bytes go into the queue as bytes and the GUEST decodes them; what
        // must still happen here is the MINTING, because a port has to exist
        // before anything can be delivered on it.
        long ports = ScanPorts(rt, bytes);
        if (Val.IsNil(ports)) {
            // Refused rather than delivered as anything else: a message the
            // format cannot read is the host's error, and turning it into a
            // string here would hand the guest something that silently was not
            // what was sent.
            GiveBack(rt, rt.R(pi), len);
            rt.PopTo(bas);
            return false;
        }
        int pri = rt.Push(ports);
        int vi = rt.Push(Bytes.Of(rt, bytes));
        // `[len bytes ports]`. `len` because the refund has to be the number
        // that was CHARGED; `ports` because THE BRIDGE OWNS THE REFERENCE while
        // the message is in flight, and the intern table is weak on purpose.
        {
            int m = rt.Mark();
            int ei = rt.Push(Vec.Empty(rt));
            rt.SetR(ei, Vec.Conj(rt, rt.R(ei), Val.Fixnum(len)));
            rt.SetR(ei, Vec.Conj(rt, rt.R(ei), rt.R(vi)));
            rt.SetR(vi, Vec.Conj(rt, rt.R(ei), rt.R(pri)));
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
    /// GENERATED, from `kin/portbytes.kin`.
    static void GiveBack(Rt rt, long p, long len) {
        global::_3sln.Flint.Kgen.Rt.Portbytes.GiveBack(rt, p, len);
    }

    /// The host lets go of its end. The port may now be collected.
    /// HALF-CLOSED, not closed: whatever the host already delivered is still
    /// there to be read, and only when that is drained does it read as end of
    /// stream. There is one object now, not a pair, so this is the state of the
    /// handle itself rather than of a second end standing in for it.
    /// GENERATED, from `kin/porthost.kin`.
    public static void HostClosePort(Rt rt, long hostPortId) {
        global::_3sln.Flint.Kgen.Rt.Porthost.HostCloseAt(rt, hostPortId);
    }

    /// This end's state, RESOLVED rather than remembered.
    ///
    /// A port whose peer has been collected is orphaned whether or not the
    /// scheduler has got round to noticing, and a query that answered `:open`
    /// until then would be a notification wearing a query's clothes.
    /// GENERATED, from `kin/porthost.kin`.
    public static long PortStateNow(Rt rt, long p) {
        return global::_3sln.Flint.Kgen.Rt.Porthost.PortStateNowAt(rt, p);
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
    ///
    /// GENERATED (`kin/reapports.kin`). A collection is a RELEASE for a bridge
    /// and an ORPHANING for a channel, and this is the only place either is
    /// noticed -- which is why it was worth writing once rather than three
    /// times. The port that made it a candidate is recorded there: all three
    /// copies rebuilt both lists on every drive iteration whether or not
    /// anything had died, and allocation is billed, so the scheduler's own
    /// bookkeeping was charged to the program -- on the native runtime only,
    /// because this target's not-counting sentinel is `checkpoint == 0` where
    /// the native one's is `ulong.MaxValue`. The same waste, invisible here and
    /// expensive there, is exactly the drift one definition removes.
    public static void ReapPorts(Rt rt) {
        global::_3sln.Flint.Kgen.Rt.Reapports.ReapAll(rt);
    }

    /// Wake everything parked on `p` with an ERROR instead of a value. Used when
    /// the peer end has been collected: that receive can never succeed, and a
    /// hang is the worst possible way to say so.
    public static void FailWaitersOn(Rt rt, long p, string msg) {
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
    /// GENERATED, from `kin/reapports.kin`. One line here, as `ReapPorts` is.
    public static void CloseAllBridges(Rt rt) {
        global::_3sln.Flint.Kgen.Rt.Reapports.CloseBridges(rt);
    }

    // --- joining ------------------------------------------------------------

    /// GENERATED (`kin/threadjoin.kin`). Wait for `t` and answer its value.
    /// The self-join refusal was in NATIVE AND NOWHERE ELSE: this copy parked
    /// and let the deadlock reporter catch it a turn later, naming the
    /// symptom ("thread 0 waiting on thread 0") rather than the mistake.
    public static long Join(Rt rt, long t) {
        return global::_3sln.Flint.Kgen.Rt.Threadjoin.JoinAt(rt, t);
    }

    // `State` WAS HERE and is gone. It was DEAD -- nothing called it -- and it
    // disagreed with the live `flint/thread-state` builtin on two answers: nil
    // where that throws, `:runnable` where that says `:new`. See
    // `kin/threadjoin.drivers`.

    // --- the scheduler ------------------------------------------------------

    /// Record the outcome of the thread that was running, and take it off.
    // THE SCHEDULER'S PREDICATES ARE `kin/sched.kin`, generated into all three
    // runtimes -- see the note in `Conc.java`. `SchedPick` answers `-1` for
    // "nothing runnable", which is what the callers here already expected.
    static int Pick(Rt rt) =>
        // `-1` FOR NONE is what this port's callers already expected.
        (int) global::_3sln.Flint.Kgen.Rt.Sched.SchedPick(rt);

    static bool PendingEvents(Rt rt) =>
        global::_3sln.Flint.Kgen.Rt.Sched.SchedPendingEvents(rt);

    static bool NeedsHost(Rt rt) =>
        global::_3sln.Flint.Kgen.Rt.Sched.SchedNeedsHost(rt);

    /// GENERATED (`kin/settle.kin`). The end of every turn a thread takes,
    /// and the one place that decides which of three things just happened.
    /// Written three times before this; the park branch's clear of the PARK
    /// sentinel was in both ports and not in native, and the generated body
    /// keeps it.
    public static void Settle(Rt rt, long result) {
        global::_3sln.Flint.Kgen.Rt.Settle.SettleThread(rt, result);
    }

    /// Install a thread's dynamic bindings as the live ones -- see the note
    /// on the JVM's `installBindings`.
    /// The bindings live RIGHT NOW, which a spawn inherits.
    public static long CurrentBindings(Rt rt) {
        return rt.roots.shared.Singletons[Rt.SingBindings];
    }

    public static void InstallBindings(Rt rt, long binds) {
        rt.roots.shared.Singletons[Rt.SingBindings] = binds;
    }

    /// Give the thread about to run a fresh turn, from the CURRENT step count.
    public static void BeginSlice(Rt rt) { rt.SetSliceEnd(rt.steps + SLICE); }

    /// Empty the interpreter, for a thread with no state to restore.
    public static void ResetExecState(Rt rt) {
        rt.frames.Clear(); rt.handlers.Clear(); rt.roots.StackTop = 0;
    }

    /// GENERATED (`kin/sched.kin`) -- see the note on the JVM's `runOne`.
    public static void RunOne(Rt rt, int i) {
        global::_3sln.Flint.Kgen.Rt.Sched.SchedRunOne(rt, i);
    }

    public static long RunEntry(Rt rt, long f) {
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
    // `MainFinished` WAS HERE and is gone: dead, replaced by the generated
    // `SchedAllSettled`. `MainResult` is gone too -- see `SettledAnswer`.

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

    /// Spawn the control plane on the system port, once.
    ///
    /// Asked on EVERY drive rather than at install, because a host may install
    /// a system port after the first run -- and because the first native
    /// version did it at install time, inside an ABI call, where the
    /// initialisers ran in a context that could not report failure and it
    /// returned false in silence.
    public static void BootSystemThreadOnce(Rt rt) {
        if (rt.systemBooted) return;
        if (Val.IsNil(SystemPort(rt))) return;      // no door yet; asked again next drive
        rt.systemBooted = true;
        BootSystemThread(rt);
    }

    /// **No guest code runs here.** `flint.system/boot` is a thunk, so this
    /// takes its var's value and spawns it -- nothing is called. The native
    /// runtime's first version called a flint function to build a closure over
    /// the port, and that re-entered `Drive` from inside `Drive`: the nested
    /// scheduler ran, found the boot flag already set, and the outer call came
    /// back with nothing callable. The sandbox then tore itself down with no
    /// message ever served, and the only visible symptom was "the call was
    /// never answered".
    ///
    /// **Initialisers must have run**, because a var is nil until they have.
    ///
    /// Absent `flint.system` is NOT an error. A module built before this
    /// existed has no control plane, and a sandbox nothing can call is a
    /// coherent thing to be; failing here would make every old artifact
    /// unloadable.
    static void BootSystemThread(Rt rt) {
        if (!rt.EnsureStarted()) return;
        for (int i = 0; i < rt.varNames.Length; i++) {
            if (Str.Text(rt, rt.consts[rt.varNames[i]]) != "flint.system/boot") continue;
            long f = rt.roots.shared.Globals[i];
            if (Val.IsNil(f) || !rt.IsHeapTy(f, Obj.TyClosure)) return;
            Spawn(rt, f);
            return;
        }
    }

    /// THE ANSWER A SETTLED PROGRAM LEFT -- named to match native's
    /// `settled_answer`, which is what `kin/sched.kin` calls. It was
    /// `MainResult` here, from the model in which thread 0 was `main`.
    /// GENERATED (`kin/mainanswer.kin`). It returned `TH_RESULT`
    /// unconditionally, which for a FAILED thread is the error -- so a host
    /// saw status 0 and a value that happened to be an exception. Native put
    /// it back on `thrown`; this did not, and nothing compared them.
    public static long SettledAnswer(Rt rt) =>
        global::_3sln.Flint.Kgen.Rt.Mainanswer.MainAnswer(rt);

    /// NAME THE DEADLOCK rather than hang on it. THREE implementations on
    /// purpose: this builds a host string naming each stuck thread, and a
    /// diagnostic message is the wrong thing to force through a generator.
    public static void ReportDeadlock(Rt rt) {
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
            rt.ThrowStr("IllegalStateException",
                "deadlock: " + stuck + " green thread(s) are parked and nothing can wake them"
                + detail);
    }

    public static long Drive(Rt rt) =>
        global::_3sln.Flint.Kgen.Rt.Sched.SchedDrive(rt);
}
