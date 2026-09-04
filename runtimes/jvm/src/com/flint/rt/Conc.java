package com.flint.rt;

import flint.rt.Mapcore;

import static com.flint.rt.Obj.*;
import static flint.rt.Interns.*;

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
public final class Conc {
    private Conc() {}

    // --- thread ------------------------------------------------------------

    public static final int TH_STATUS = 0, TH_PARK_ON = 1, TH_RESULT = 2;
    /// Saved value stack: a Vals object, so tracing it is the ordinary walk.
    public static final int TH_STACK = 3;
    /// Saved frames, as RAW bytes. A frame holds only indices -- its closure is
    /// `stack[retTo]`, never a copy -- so there is nothing in here for the
    /// collector to find, and a raw blob is the honest encoding.
    public static final int TH_FRAMES = 4;
    /// Bytes per saved frame. Five interpreter words plus the two the AOT entry
    /// needs, so the record is the same width in every build and a snapshot and
    /// a thread save are interchangeable between them.
    public static final int FRAME_REC = 28;
    public static final int TH_HANDLERS = 5, TH_BINDINGS = 6, TH_ID = 7, TH_ENTRY = 8;
    public static final int TH_PENDING = 9, TH_TOKEN = 10, TH_FAIL = 11, TH_LEN = 12;

    public static final int ST_NEW = 0, ST_RUNNABLE = 1, ST_PARKED = 2,
                            ST_DONE = 3, ST_FAILED = 4;

    /// The wake key for a COURTESY yield, distinct from parking on a port.
    ///
    /// The distinction is load-bearing: a park REWINDS so the call re-executes
    /// and finds its value, while a yield must NOT -- re-executing a `yield`
    /// yields again, forever, and the scheduler reports it made no progress.
    public static final long PARK_YIELD = Val.fixnum(0);

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
    public static final int PT_ID = 0, PT_STATE = 1, PT_CAP = 2, PT_INBOX = 3,
        PT_READ = 4, PT_BYTES = 5, PT_PEER = 6, PT_LABEL = 7, PT_KIND = 8,
        PT_WRITE = 9, PT_RING = 10, PT_LEN = 11;

    /// How many messages a bridge end's ring holds. Its `PT_CAP` bounds BYTES,
    /// which is the bound that matters for memory; this bounds the count so the
    /// ring can be one fixed allocation.
    public static final long RING_MESSAGES = 64;

    /// A port's STATE. `P_PENDING` is an `open` the host has not answered yet
    /// and `P_REFUSED` is one it declined -- distinct from `P_CLOSED`, because
    /// "you may not have this" and "this is finished" are different answers.
    ///
    /// THE NUMBERS ARE THE RUST'S, and they are an ABI: `flint_port_state`
    /// answers one of them to a host that reads it as a number. A port that
    /// merely spelled the same six names in a different order would agree with
    /// the native runtime on every transcript that renders them and disagree
    /// with every host that reads them.
    public static final int P_PENDING = 0, P_OPEN = 1, P_CLOSED = 2, P_REFUSED = 3,
                            P_HALF = 4, P_ORPHANED = 5;
    /// Two kinds, and no third (`doc/decisions/0027`). `K_CHANNEL` joins two
    /// green threads inside one sandbox and passes values by reference;
    /// `K_BRIDGE` is a HANDLE on a port the host owns, carrying the host's id
    /// and encoded messages.
    ///
    /// There is no "host port". `open` used to manufacture a PAIR of ends here,
    /// keep one and offer the other up as the host's, which made the confined
    /// thing the author of its own authority.
    public static final int K_CHANNEL = 0, K_BRIDGE = 1;

    /// A channel's default buffer, in MESSAGES.
    public static final long DEFAULT_CAP = 16;

    /// How much a bridge will buffer before a send parks, in BYTES.
    public static final long DEFAULT_BRIDGE_CAP = 1 << 20;

    /// What the host is told about, drained through `drainEvents`.
    /// `EV_RETAIN` says this sandbox now holds the host's port `a`, pushed
    /// exactly once per port per sandbox on the miss that mints the handle;
    /// `EV_RELEASE` says it no longer does. One per retain, so the host's count
    /// is of HOLDERS (`doc/decisions/0027`).
    public static final int EV_OPEN = 1, EV_MESSAGE = 2, EV_CLOSED = 3,
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
    public static final int W_GEN = 0, W_THREAD = 1, W_KIND = 2, W_PORT = 3,
                            W_NEXT = 4, W_LEN = 5;
    public static final int WK_OPEN = 1, WK_SEND = 2, WK_RECEIVE = 3, WK_JOIN = 4,
    /// Parked on `hostRequest`, which is `WK_OPEN` generalised: the answer is a
    /// VALUE rather than necessarily a port (`doc/decisions/0036` step 7).
                            WK_REQUEST = 5;

    // --- scheduler ---------------------------------------------------------

    public static final int SC_THREADS = 0, SC_CURRENT = 1, SC_NEXTID = 2,
        SC_EVENTS = 3, SC_EHEAD = 4, SC_PORTS = 5, SC_PAIRS = 6,
        SC_WAITERS = 7, SC_WFREE = 8, SC_SYSTEM = 9,
        /// Host ids of every BRIDGE this sandbox holds a handle for -- ids, not
        /// references, so the list pins nothing. This is the walk that turns a
        /// collection into a release (`doc/decisions/0027`).
        SC_BRIDGES = 10, SC_LEN = 11;

    /// Instructions a thread runs before the scheduler takes the slice back.
    /// Preemptive, so a thread with no `yield` in it cannot starve the others.
    public static final long SLICE = 4096;

    static long fx(long v) { return Val.asFixnum(v); }

    static long newObj(Rt rt, int ty, int len) {
        long a = rt.alloc(ty, len);
        return a == 0 ? Val.NIL : Val.heap(a);
    }

    public static boolean isThread(Rt rt, long v) {
        return Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_THREAD;
    }
    public static boolean isPort(Rt rt, long v) {
        return Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_PORT;
    }

    public static long sched(Rt rt) { return rt.roots.shared.singletons[Rt.SING_SCHED]; }

    /// Create the scheduler on first use, enrolling whatever is running now as
    /// THREAD 0. Built here rather than at startup so a program that never
    /// spawns runs a straight line with no scheduler in it at all.
    public static long ensureSched(Rt rt) {
        long s = sched(rt);
        if (!Val.isNil(s)) return s;
        // The decoder's route to `installBridgePort`, set HERE and nowhere else.
        // See `Rt.bridgeHook`: reaching it directly from the codec put the whole
        // scheduler into every wasm module, including ones with no ports.
        rt.bridgeHook = Conc::installBridgePort2;
        int base = rt.mark();
        int si = rt.push(newObj(rt, TY_SCHED, SC_LEN));
        if (Val.isNil(rt.r(si))) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(Val.asHeap(rt.r(si)), SC_EVENTS, Vec.empty(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_EHEAD, Val.fixnum(0));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_PORTS, Vec.empty(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_PAIRS, Vec.empty(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_BRIDGES, Vec.empty(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_WAITERS, Vec.empty(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_WFREE, Val.fixnum(-1));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_NEXTID, Val.fixnum(1));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_CURRENT, Val.fixnum(0));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_SYSTEM, Val.NIL);
        // The running thread becomes thread 0. Its stack is the LIVE one, so it
        // has nothing saved until it parks.
        int ti = rt.push(newObj(rt, TY_THREAD, TH_LEN));
        if (Val.isNil(rt.r(ti))) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_STATUS, Val.fixnum(ST_RUNNABLE));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_ID, Val.fixnum(0));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_TOKEN, Val.fixnum(-1));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_BINDINGS, Maps.empty(rt));
        int tsi = rt.push(Vec.empty(rt));
        long ts = Vec.conj(rt, rt.r(tsi), rt.r(ti));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_THREADS, ts);
        long out = rt.r(si);
        rt.roots.shared.singletons[Rt.SING_SCHED] = out;
        rt.popTo(base);
        rt.schedInstalled = true;
        rt.setSliceEnd(rt.steps + SLICE);
        return out;
    }

    public static long currentThread(Rt rt) {
        long s = sched(rt);
        if (Val.isNil(s)) return Val.NIL;
        return Vec.nth(rt, rt.slot(s, SC_THREADS), (int) fx(rt.slot(s, SC_CURRENT)), Val.NOT_FOUND);
    }

    // --- saving and restoring a VM state ------------------------------------

    /// Save the live VM state into `th`, then leave the interpreter EMPTY.
    ///
    /// The stack becomes a `TY_NODE` -- an ordinary Vals object -- which is the
    /// whole trick: the collector traces it as it traces anything, and a parked
    /// thread holding the only reference to a value keeps that value alive with
    /// no new rule.
    public static void saveCurrentState(Rt rt, long th) {
        int base = rt.mark();
        int ti = rt.push(th);
        int n = rt.roots.stackTop;
        // UNBILLED: the size of a saved stack is a property of the calling
        // convention, not of the program. Billing it makes the same program
        // cost more interpreted than compiled, which breaks the AOT gas parity
        // the suite asserts (`doc/decisions/0009`).
        long a0 = rt.allocUnbilled(TY_NODE, n);
        long sv = a0 == 0 ? Val.NIL : Val.heap(a0);
        if (Val.isNil(sv)) { rt.popTo(base); return; }
        for (int i = 0; i < n; i++) rt.setSlot(Val.asHeap(sv), i, rt.roots.stack[i]);
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_STACK, sv);

        long a1 = rt.allocUnbilled(TY_RAW, rt.frames.size() * FRAME_REC);
        long fb = a1 == 0 ? Val.NIL : Val.heap(a1);
        if (!Val.isNil(fb)) {
            long a = Val.asHeap(fb) + HDR;
            for (int k = 0; k < rt.frames.size(); k++) {
                Frame f = rt.frames.get(k);
                long o = a + (long) k * FRAME_REC;
                rt.gc.sp.writeU32(o, f.fp);
                rt.gc.sp.writeU32(o + 4, f.ip);
                rt.gc.sp.writeU32(o + 8, f.end);
                rt.gc.sp.writeU32(o + 12, f.retTo);
                rt.gc.sp.writeU32(o + 16, f.handlers);
                // The two AOT words. This runtime compiles nothing, so they are
                // written as "none" rather than omitted -- the record has to be
                // the same width in every build.
                rt.gc.sp.writeU32(o + 20, -1);
                rt.gc.sp.writeU32(o + 24, -1);
            }
        }
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_FRAMES, fb);

        long hb = newObj(rt, TY_RAW, rt.handlers.size() * 16);
        if (!Val.isNil(hb)) {
            long a = Val.asHeap(hb) + HDR;
            for (int k = 0; k < rt.handlers.size(); k++) {
                Rt.Handler h = rt.handlers.get(k);
                long o = a + (long) k * 16;
                rt.gc.sp.writeU32(o, h.frame);
                rt.gc.sp.writeU32(o + 4, h.stackTop);
                rt.gc.sp.writeU32(o + 8, h.target);
                rt.gc.sp.writeU32(o + 12, h.shadow);
            }
        }
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_HANDLERS, hb);
        rt.popTo(base);

        rt.frames.clear();
        rt.handlers.clear();
        rt.roots.stackTop = 0;
    }

    static void restoreState(Rt rt, long th) {
        long sv = rt.slot(th, TH_STACK);
        rt.frames.clear();
        rt.handlers.clear();
        rt.roots.stackTop = 0;
        if (!Val.isNil(sv)) {
            int n = len(rt.gc.sp, Val.asHeap(sv));
            if (rt.roots.stack.length < n + 8) rt.roots.stack = new long[n + 8];
            for (int i = 0; i < n; i++) rt.roots.stack[i] = rt.slot(sv, i);
            rt.roots.stackTop = n;
        }
        long fb = rt.slot(th, TH_FRAMES);
        if (!Val.isNil(fb)) {
            int n = len(rt.gc.sp, Val.asHeap(fb)) / FRAME_REC;
            long a = Val.asHeap(fb) + HDR;
            for (int k = 0; k < n; k++) {
                long o = a + (long) k * FRAME_REC;
                Frame f = new Frame();
                f.fp = rt.gc.sp.readU32(o);
                f.ip = rt.gc.sp.readU32(o + 4);
                f.end = rt.gc.sp.readU32(o + 8);
                f.retTo = rt.gc.sp.readU32(o + 12);
                f.handlers = rt.gc.sp.readU32(o + 16);
                rt.frames.add(f);
            }
        }
        long hb = rt.slot(th, TH_HANDLERS);
        if (!Val.isNil(hb)) {
            int n = len(rt.gc.sp, Val.asHeap(hb)) / 16;
            long a = Val.asHeap(hb) + HDR;
            for (int k = 0; k < n; k++) {
                long o = a + (long) k * 16;
                Rt.Handler h = new Rt.Handler();
                h.frame = rt.gc.sp.readU32(o);
                h.stackTop = rt.gc.sp.readU32(o + 4);
                h.target = rt.gc.sp.readU32(o + 8);
                h.shadow = rt.gc.sp.readU32(o + 12);
                rt.handlers.add(h);
            }
        }
    }

    // --- parking ------------------------------------------------------------

    /// Signal a park. `parkOn` is the wake key; the PARK sentinel in `thrown`
    /// is what makes the interpreter unwind out to the scheduler.
    public static long park(Rt rt, long on) {
        rt.parkOn = on;
        rt.thrown = Val.PARK;
        return Val.NIL;
    }

    // --- spawning -----------------------------------------------------------

    /// A new green thread running `f` (no arguments).
    ///
    /// It INHERITS A SNAPSHOT of its spawner's dynamic bindings, which is what
    /// Clojure conveys to `future` and agents, and what somebody debugging at
    /// three in the morning will assume. A snapshot: later `binding` in the
    /// spawner does not reach the child.
    public static long spawn(Rt rt, long f) {
        // Rooted FIRST: `ensureSched` allocates, and `f` is a host local.
        int base = rt.mark();
        int fi = rt.push(f);
        ensureSched(rt);
        int ti = rt.push(newObj(rt, TY_THREAD, TH_LEN));
        if (Val.isNil(rt.r(ti))) { rt.popTo(base); return Val.NIL; }
        int si = rt.push(sched(rt));
        long id = fx(rt.slot(rt.r(si), SC_NEXTID));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_NEXTID, Val.fixnum(id + 1));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_STATUS, Val.fixnum(ST_NEW));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_ID, Val.fixnum(id));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_TOKEN, Val.fixnum(-1));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_ENTRY, rt.r(fi));
        long binds = rt.roots.shared.singletons[Rt.SING_BINDINGS];
        if (Val.isNil(binds)) binds = Maps.empty(rt);
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_BINDINGS, binds);
        int tsi = rt.push(rt.slot(rt.r(si), SC_THREADS));
        long nts = Vec.conj(rt, rt.r(tsi), rt.r(ti));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_THREADS, nts);
        long out = rt.r(ti);
        rt.popTo(base);
        return out;
    }

    // --- waiters ------------------------------------------------------------

    static long waiters(Rt rt) { return rt.slot(sched(rt), SC_WAITERS); }

    /// A waiter records WHICH THREAD is parked on WHICH PORT. Slots are reused
    /// through a free list rather than compacted, because a token is an index
    /// into this vector and compacting would invalidate every one held.
    static long newWaiter(Rt rt, long kind, long port) {
        int base = rt.mark();
        int pi = rt.push(port);
        int si = rt.push(sched(rt));
        int ti = rt.push(currentThread(rt));
        long free = fx(rt.slot(rt.r(si), SC_WFREE));
        long idx;
        int wi;
        if (free >= 0) {
            long w = Vec.nth(rt, rt.slot(rt.r(si), SC_WAITERS), (int) free, Val.NOT_FOUND);
            rt.setSlot(Val.asHeap(rt.r(si)), SC_WFREE, Val.fixnum(fx(rt.slot(w, W_NEXT))));
            idx = free;
            wi = rt.push(w);
        } else {
            long w = newObj(rt, TY_NODE, W_LEN);
            if (Val.isNil(w)) { rt.popTo(base); return -1; }
            wi = rt.push(w);
            rt.setSlot(Val.asHeap(rt.r(wi)), W_GEN, Val.fixnum(0));
            int wsi = rt.push(rt.slot(rt.r(si), SC_WAITERS));
            long nws = Vec.conj(rt, rt.r(wsi), rt.r(wi));
            idx = Vec.count(rt, nws) - 1;
            rt.setSlot(Val.asHeap(rt.r(si)), SC_WAITERS, nws);
        }
        rt.setSlot(Val.asHeap(rt.r(wi)), W_THREAD, rt.r(ti));
        rt.setSlot(Val.asHeap(rt.r(wi)), W_KIND, Val.fixnum(kind));
        rt.setSlot(Val.asHeap(rt.r(wi)), W_PORT, rt.r(pi));
        long gen = fx(rt.slot(rt.r(wi), W_GEN));
        rt.popTo(base);
        // 1-based, so that 0 is never a valid token: a host ABI where the zero
        // value means something is a trap waiting for an uninitialised variable.
        return (gen << 16) | (idx + 1);
    }

    static long waiterAt(Rt rt, long token) {
        if (token <= 0 || (token & 0xFFFF) == 0) return Val.NIL;
        int idx = (int) (token & 0xFFFF) - 1;
        long gen = token >> 16;
        long w = Vec.nth(rt, waiters(rt), idx, Val.NOT_FOUND);
        if (w == Val.NOT_FOUND || Val.isNil(w)) return Val.NIL;
        if (fx(rt.slot(w, W_GEN)) != gen || Val.isNil(rt.slot(w, W_THREAD))) return Val.NIL;
        return w;
    }

    /// Free a waiter slot and BUMP ITS GENERATION, so a token naming it can
    /// never be honoured twice.
    static void freeWaiter(Rt rt, long token) {
        long w = waiterAt(rt, token);
        if (Val.isNil(w)) return;
        long idx = (token & 0xFFFF) - 1;
        long gen = fx(rt.slot(w, W_GEN));
        rt.setSlot(Val.asHeap(w), W_GEN, Val.fixnum((gen + 1) & 0xFFFF));
        rt.setSlot(Val.asHeap(w), W_THREAD, Val.NIL);
        rt.setSlot(Val.asHeap(w), W_PORT, Val.NIL);
        long s = sched(rt);
        rt.setSlot(Val.asHeap(w), W_NEXT, rt.slot(s, SC_WFREE));
        rt.setSlot(Val.asHeap(s), SC_WFREE, Val.fixnum(idx));
    }

    /// How many green threads are parked with a token outstanding. A host that
    /// never answers leaks these; the deadlock report names them.
    public static int outstandingWaiters(Rt rt) {
        long s = sched(rt);
        if (Val.isNil(s)) return 0;
        long ws = waiters(rt);
        int n = Vec.count(rt, ws), c = 0;
        for (int i = 0; i < n; i++) {
            long w = Vec.nth(rt, ws, i, Val.NOT_FOUND);
            if (!Val.isNil(w) && w != Val.NOT_FOUND && !Val.isNil(rt.slot(w, W_THREAD))) c++;
        }
        return c;
    }

    /// Park until there is room in `p`'s ring.
    ///
    /// The re-check after registering is not belt and braces: `wakeOn` reaches
    /// only waiters ALREADY in the list, so a receive that drains the ring
    /// between the failed reservation and the registration would wake nobody
    /// and this thread would sleep with space in front of it.
    static long parkForSpace(Rt rt, long p) {
        int base = rt.mark();
        int pi = rt.push(p);
        long token = newWaiter(rt, WK_SEND, rt.r(pi));
        long th = currentThread(rt);
        if (!Val.isNil(th)) rt.setSlot(Val.asHeap(th), TH_TOKEN, Val.fixnum(token));
        long ring = fx(rt.slot(rt.r(pi), PT_RING));
        if (inboxCount(rt, rt.r(pi)) < ring) {
            freeWaiter(rt, token);
            if (!Val.isNil(th)) rt.setSlot(Val.asHeap(th), TH_TOKEN, Val.fixnum(-1));
            rt.popTo(base);
            // A yield rather than a park: the thread stays runnable and the
            // send runs again on its next turn.
            return park(rt, PARK_YIELD);
        }
        long pv = rt.r(pi);
        rt.popTo(base);
        return park(rt, pv);
    }

    static long parkOnPort(Rt rt, long kind, long port) {
        int base = rt.mark();
        int pi = rt.push(port);
        long token = newWaiter(rt, kind, rt.r(pi));
        long th = currentThread(rt);
        if (!Val.isNil(th)) rt.setSlot(Val.asHeap(th), TH_TOKEN, Val.fixnum(token));
        long pv = rt.r(pi);
        rt.popTo(base);
        return park(rt, pv);
    }

    static void wakeWaiter(Rt rt, long w) {
        long th = rt.slot(w, W_THREAD);
        if (Val.isNil(th)) return;
        rt.setSlot(Val.asHeap(th), TH_STATUS, Val.fixnum(ST_RUNNABLE));
        rt.setSlot(Val.asHeap(th), TH_PARK_ON, Val.NIL);
        long token = fx(rt.slot(th, TH_TOKEN));
        rt.setSlot(Val.asHeap(th), TH_TOKEN, Val.fixnum(-1));
        freeWaiter(rt, token);
    }

    /// Make every thread waiting on `p` runnable again.
    ///
    /// They RE-EXECUTE the call they parked in, which is what makes "wake"
    /// correct without anyone reasoning about who gets the value: whoever runs
    /// first takes it, and the others simply park again.
    public static void wakeOn(Rt rt, long p) {
        long ws = waiters(rt);
        int n = Vec.count(rt, ws);
        for (int i = 0; i < n; i++) {
            long w = Vec.nth(rt, ws, i, Val.NOT_FOUND);
            if (Val.isNil(w) || Val.isNil(rt.slot(w, W_THREAD))) continue;
            if (rt.slot(w, W_PORT) == p) wakeWaiter(rt, w);
        }
    }

    // --- channels -----------------------------------------------------------

    /// A coupled pair. What goes into one comes out of the other, both ways.
    public static long channel(Rt rt, long cap, long label) {
        ensureSched(rt);
        int base = rt.mark();
        int li = rt.push(label);
        int ai = rt.push(newPort(rt, cap, rt.r(li), K_CHANNEL, P_OPEN, -1));
        int bi = rt.push(newPort(rt, cap, rt.r(li), K_CHANNEL, P_OPEN, -1));
        linkPeers(rt, rt.r(ai), rt.r(bi));
        int vi = rt.push(Vec.empty(rt));
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(ai)));
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(bi)));
        long out = rt.r(vi);
        rt.popTo(base);
        return out;
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
    /// can act on: the host wants to know when it may let the port go, and that
    /// is when the last holder drops it. The matching `EV_RELEASE` comes from
    /// `reapPorts`, or promptly from `close` -- and that one always goes out,
    /// because a drop is never something the host asked for.
    ///
    /// NOT ROOTED, unlike the host end this replaces: a handle nothing refers
    /// to is precisely what a release is for. The system port is the exception
    /// and is rooted by living in `SC_SYSTEM`.
    public static long installBridgePort(Rt rt, long hostId, long label, boolean announce) {
        ensureSched(rt);
        if (hostId < 0) return Val.NIL;
        long existing = portById(rt, hostId);
        if (!Val.isNil(existing) && fx(rt.slot(existing, PT_KIND)) == K_BRIDGE) return existing;
        int base = rt.mark();
        int li = rt.push(label);
        int pi = rt.push(newPort(rt, DEFAULT_BRIDGE_CAP, rt.r(li), K_BRIDGE, P_OPEN, hostId));
        if (Val.isNil(rt.r(pi))) { rt.popTo(base); return Val.NIL; }
        // Recorded as HELD, which is what `reapPorts` walks to notice the drop.
        int si = rt.push(sched(rt));
        int bi = rt.push(rt.slot(rt.r(si), SC_BRIDGES));
        long nb = Vec.conj(rt, rt.r(bi), Val.fixnum(hostId));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_BRIDGES, nb);
        if (announce) pushEvent(rt, EV_RETAIN, hostId, 0, Val.NIL);
        long out = rt.r(pi);
        rt.popTo(base);
        return out;
    }

    /// Install the system port: the bridge a sandbox is DRIVEN over.
    ///
    /// A sandbox that is given one can ask for more ports on it; a sandbox that
    /// is not has no way to reach anything outside itself, which is the honest
    /// meaning of "no capabilities" and is the default.
    /// The hook's shape: a bridge arriving in a message carries no label.
    static long installBridgePort2(Rt rt, long hostId) {
        return installBridgePort(rt, hostId, Val.NIL, true);
    }

    public static long installSystemPort(Rt rt, long hostId, long label) {
        long p = installBridgePort(rt, hostId, label, false);
        if (Val.isNil(p)) return Val.NIL;
        int base = rt.mark();
        int pi = rt.push(p);
        int si = rt.push(sched(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_SYSTEM, rt.r(pi));
        long out = rt.r(pi);
        rt.popTo(base);
        return out;
    }

    /// The system port. NOT reachable from guest code, and that is the point.
    ///
    /// It is not a capability the sandbox holds, it is the TRANSPORT the
    /// sandbox is driven over: calls in arrive on it, and requests out -- for a
    /// capability, for another port -- leave on it. Handing it to guest code
    /// would make it ambient authority inside the sandbox, which is the thing
    /// `doc/decisions/0022` and `0027` both exist to prevent. There is no
    /// builtin that answers it; only the runtime looks it up.
    public static long systemPort(Rt rt) {
        long s = sched(rt);
        return Val.isNil(s) ? Val.NIL : rt.slot(s, SC_SYSTEM);
    }

    /// A port object. `id` is `-1` to mint one from this sandbox's counter,
    /// which is what a channel end does; a bridge handle passes the HOST's id
    /// instead, because that is the id that means the same thing on both sides.
    static long newPort(Rt rt, long cap, long label, long kind, long state, long id) {
        int base = rt.mark();
        int li = rt.push(label);
        int pi = rt.push(newObj(rt, TY_PORT, PT_LEN));
        if (Val.isNil(rt.r(pi))) { rt.popTo(base); return Val.NIL; }
        int si = rt.push(sched(rt));
        if (id < 0) {
            id = fx(rt.slot(rt.r(si), SC_NEXTID));
            rt.setSlot(Val.asHeap(rt.r(si)), SC_NEXTID, Val.fixnum(id + 1));
        }
        long p = Val.asHeap(rt.r(pi));
        rt.setSlot(p, PT_ID, Val.fixnum(id));
        rt.setSlot(p, PT_STATE, Val.fixnum(state));
        rt.setSlot(p, PT_CAP, Val.fixnum(cap));
        // The ring, allocated ONCE: a send must not allocate. ONE array: a
        // slot's own word says whether it is vacant.
        long ring = kind == K_CHANNEL ? Math.max(cap, 1) : RING_MESSAGES;
        rt.setSlot(p, PT_RING, Val.fixnum(ring));
        int sli = rt.push(newObj(rt, TY_NODE, (int) ring));
        for (int i = 0; i < ring; i++) rt.setSlot(Val.asHeap(rt.r(sli)), i, Val.EMPTY);
        rt.setSlot(Val.asHeap(rt.r(pi)), PT_INBOX, rt.r(sli));
        p = Val.asHeap(rt.r(pi));
        rt.setSlot(p, PT_READ, Val.fixnum(0));
        rt.setSlot(p, PT_WRITE, Val.fixnum(0));
        rt.setSlot(p, PT_BYTES, Val.fixnum(0));
        // PEERS ARE LINKED BY ID, never by object. When one end is collected
        // its object is gone, and a field holding the peer would keep it alive
        // -- which is exactly what `doc/decisions/0006` says must not happen:
        // an unreachable flint end MEANS the script is finished with it.
        rt.setSlot(p, PT_PEER, Val.fixnum(-1));
        rt.setSlot(p, PT_LABEL, rt.r(li));
        rt.setSlot(p, PT_KIND, Val.fixnum(kind));
        registerPort(rt, rt.r(pi));
        long out = rt.r(pi);
        rt.popTo(base);
        return out;
    }

    /// The registry. WEAK on purpose (`doc/decisions/0006`): the flint end of a
    /// port is ordinary reachable memory, and when the collector finds it
    /// unreachable that MEANS the script is finished with it. The scheduler
    /// keeps IDS, not references -- a strong list would pin every port for ever
    /// and there would be nothing to notice.
    static void registerPort(Rt rt, long p) {
        int id = (int) fx(rt.slot(p, PT_ID));
        int base = rt.mark();
        int pi = rt.push(p);
        Interns t = rt.roots.shared.interns[Interns.PORT];
        rt.roots.shared.par.lockIntern(Interns.PORT);
        try {
            t.lookup(id, v -> false);
            if (needsGrow(t)) { t.grow(); t.lookup(id, v -> false); }
            insertAt(t, t.slot, id, rt.r(pi));
        } finally {
            rt.roots.shared.par.unlockIntern(Interns.PORT);
        }
        int si = rt.push(sched(rt));
        int ii = rt.push(rt.slot(rt.r(si), SC_PORTS));
        long nids = Vec.conj(rt, rt.r(ii), Val.fixnum(id));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_PORTS, nids);
        rt.popTo(base);
    }

    /// Look a port up by id. A miss means the object has been collected --
    /// which, for a flint end, is what "the script is finished with it" looks
    /// like.
    public static long portById(Rt rt, long id) {
        if (id < 0) return Val.NIL;
        Interns t = rt.roots.shared.interns[Interns.PORT];
        rt.roots.shared.par.lockIntern(Interns.PORT);
        try {
            long v = t.lookup((int) id, x -> Val.isHeap(x)
                && ty(rt.gc.sp, Val.asHeap(x)) == TY_PORT
                && fx(rt.slot(x, PT_ID)) == id);
            return v == Val.NOT_FOUND ? Val.NIL : v;
        } finally {
            rt.roots.shared.par.unlockIntern(Interns.PORT);
        }
    }

    /// Link two ends. IDS ONLY, and the pairing is recorded in the scheduler as
    /// well, because when one end is collected its object is gone and the other
    /// end still has to be able to find out what happened to it.
    static void linkPeers(Rt rt, long a, long b) {
        long ida = fx(rt.slot(a, PT_ID)), idb = fx(rt.slot(b, PT_ID));
        rt.setSlot(Val.asHeap(a), PT_PEER, Val.fixnum(idb));
        rt.setSlot(Val.asHeap(b), PT_PEER, Val.fixnum(ida));
        int base = rt.mark();
        int si = rt.push(sched(rt));
        long[][] pairs = { {ida, idb}, {idb, ida} };
        for (long[] xy : pairs) {
            int pi = rt.push(rt.slot(rt.r(si), SC_PAIRS));
            int ei = rt.push(Vec.empty(rt));
            rt.setR(ei, Vec.conj(rt, rt.r(ei), Val.fixnum(xy[0])));
            rt.setR(ei, Vec.conj(rt, rt.r(ei), Val.fixnum(xy[1])));
            long np = Vec.conj(rt, rt.r(pi), rt.r(ei));
            rt.setSlot(Val.asHeap(rt.r(si)), SC_PAIRS, np);
            rt.popTo(pi);
        }
        rt.popTo(base);
    }

    /// The peer of a port that may itself be gone.
    static long peerOf(Rt rt, long p) { return portById(rt, fx(rt.slot(p, PT_PEER))); }

    /// The peer of an id whose OBJECT has been collected. Read from the
    /// scheduler's pair list, which is the only place that survives it.
    static long peerIdOfDead(Rt rt, long id) {
        long ps = rt.slot(sched(rt), SC_PAIRS);
        int n = Vec.count(rt, ps);
        for (int i = 0; i < n; i++) {
            long e = Vec.nth(rt, ps, i, Val.NOT_FOUND);
            if (fx(Vec.nth(rt, e, 0, Val.NOT_FOUND)) == id) return fx(Vec.nth(rt, e, 1, Val.NOT_FOUND));
        }
        return -1;
    }

    /// How many messages are in the ring, reservations included: a reserved
    /// slot is spoken for even before it is filled, and the bound this feeds is
    /// on occupancy.
    static int inboxCount(Rt rt, long p) {
        return (int) Math.max(0, cursor(rt, p, PT_WRITE) - cursor(rt, p, PT_READ));
    }

    static long cursor(Rt rt, long p, int which) {
        return fx(slotAtomic(rt, p, which));
    }

    /// One slot, read atomically. Cursors and sequence words are fixnums like
    /// any other slot -- the collector sees nothing unusual -- and the atomic
    /// operates on the TAGGED word, so a compare-and-swap compares tagged
    /// against tagged and never invents a value.
    static long slotAtomic(Rt rt, long o, int i) {
        return rt.gc.sp.atomicLoad(Obj.slotAddr(Val.asHeap(o), i));
    }

    /// Compare-and-swap a slot, AND run the write barrier when it lands. See
    /// the Rust: a ring in the old generation pointing at a young value is an
    /// edge the collector finds only through the remembered set.
    static boolean casSlotBarriered(Rt rt, long o, int i, long want, long next) {
        long obj = Val.asHeap(o);
        if (!rt.gc.sp.cas(Obj.slotAddr(obj, i), want, next)) return false;
        if (Val.isHeap(next) && rt.gc.isYoung(Val.asHeap(next)) && !rt.gc.isYoung(obj)) {
            rt.gc.remember(obj, rt.roots);
        }
        return true;
    }

    static boolean casSlot(Rt rt, long o, int i, long want, long next) {
        return rt.gc.sp.cas(Obj.slotAddr(Val.asHeap(o), i), want, next);
    }

    /// Put `v` in `p`'s ring. False means full.
    ///
    /// ONE compare-and-swap: the slot's own word is the lease, and swapping
    /// EMPTY for the message both claims the slot and fills it. Winning is the
    /// confirmation; losing means somebody took that slot and this sender looks
    /// at the next. Mirrors the Rust, including why there is no sequence word.
    static boolean enqueue(Rt rt, long p, long v) {
        long ring = fx(rt.slot(p, PT_RING));
        if (ring == 0) return false;
        long inbox = rt.slot(p, PT_INBOX);
        for (;;) {
            long w = cursor(rt, p, PT_WRITE);
            long r = cursor(rt, p, PT_READ);
            if (w - r >= ring) return false;
            int idx = (int) (w % ring);
            if (!casSlot(rt, p, PT_WRITE, Val.fixnum(w), Val.fixnum(w + 1))) continue;
            if (casSlotBarriered(rt, inbox, idx, Val.EMPTY, v)) return true;
        }
    }

    /// Take the next message, or NIL. The mirror image: swap the message out
    /// for EMPTY, freeing the slot in the step that takes the value.
    static long dequeue(Rt rt, long p) {
        long ring = fx(rt.slot(p, PT_RING));
        if (ring == 0) return Val.NIL;
        long inbox = rt.slot(p, PT_INBOX);
        for (;;) {
            long r = cursor(rt, p, PT_READ);
            if (r >= cursor(rt, p, PT_WRITE)) return Val.NIL;
            int idx = (int) (r % ring);
            long v = slotAtomic(rt, inbox, idx);
            if (v == Val.EMPTY) return Val.NIL;
            if (!casSlot(rt, p, PT_READ, Val.fixnum(r), Val.fixnum(r + 1))) continue;
            if (casSlotBarriered(rt, inbox, idx, v, Val.EMPTY)) return v;
        }
    }

    // --- what may cross a port ----------------------------------------------

    /// Null if `v` is data; otherwise WHY it cannot be sent. Functions are
    /// refused BY NAME, because "cannot send that" sends somebody hunting
    /// through a nested structure.
    public static String checkSendable(Rt rt, long v) {
        return checkSendableAt(rt, v, 0, CARRY_CROSSING);
    }

    /// The same, for a carrier that may convey IDENTITIES. See the Rust.
    public static String checkSendableVia(Rt rt, long v, int carry) {
        return checkSendableAt(rt, v, 0, carry);
    }

    /// What a carrying port can convey. A CHANNEL encodes nothing, so anything
    /// may go; a BRIDGE, whose encoding the runtime owns, carries identities
    /// that mean something on the far side.
    ///
    /// There used to be a third class, for a port whose codec ran in the
    /// SANDBOX. There is no such port any more: encoding happens at the bridge
    /// boundary, in the runtime, and a guest is never handed an encoder. The
    /// rule that class enforced is now enforced by the guest not having one.
    public static final int CARRY_LOCAL = 0, CARRY_CROSSING = 1;

    static String describeFn(Rt rt, long v) {
        int t = ty(rt.gc.sp, Val.asHeap(v));
        long name = Val.NIL;
        if (t == TY_CLOSURE) {
            int idx = (int) fx(rt.slot(v, 0));
            int namec = idx < rt.fns.length ? rt.fns[idx].name : -1;
            name = namec >= 0 && namec < rt.roots.shared.consts.length
                 ? rt.roots.shared.consts[namec] : Val.NIL;
        } else if (t == TY_NATIVEFN) {
            name = rt.slot(v, 1);
        }
        String n = Str.isString(rt, name) ? Str.text(rt, name) : "";
        return n.isEmpty() ? "an anonymous fn" : n;
    }

    static String checkSendableAt(Rt rt, long v, int depth, int carry) {
        if (depth > 64) return "value nested too deeply to send";
        if (!Val.isHeap(v)) return null;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        switch (t) {
            case TY_CLOSURE: case TY_NATIVEFN: case TY_MULTIFN:
                return "a port carries data only; " + describeFn(rt, v)
                     + " is a function, and a closure's meaning is its environment"
                     + " -- which does not travel";
            case TY_ATOM:   return "a port carries data only; this is an atom";
            case TY_VAR:    return "a port carries data only; this is a var";
            case TY_THREAD: return "a port carries data only; this is a thread";
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
            case TY_PORT:
                if (carry == CARRY_LOCAL) return null;
                if (crossesAHeap(fx(rt.slot(v, PT_KIND)))) return null;
                return "a channel endpoint cannot be sent to the host: both its ends"
                     + " live in this heap and the host has never been told it exists,"
                     + " so its id would name one of our objects from outside. A bridge"
                     + " can be sent, because its id is the host's own.";
            // An opaque value is identity and nothing else
            // (`doc/decisions/0022`), so there is nothing to serialise that
            // would still BE it. Anything a codec could write down is something
            // the receiver could write down too, and then it is mintable --
            // which is the entire property gone.
            case TY_OPAQUE:
                return null;
            case TY_STR: case TY_ROPE: case TY_SYM: case TY_KW:
            case TY_BIGINT: case TY_REGEX:
                return null;
            default: break;
        }
        int base = rt.mark();
        int vi = rt.push(v);
        String out = null;
        if (Mapcore.isMap(rt, rt.r(vi))) {
            // Materialised ON THE SHADOW STACK, not into a host list. The walk
            // below allocates, so anything held in a host `ArrayList<Long>`
            // across it comes back naming the address the object had before the
            // nursery flipped -- `doc/decisions/0031`, which is exactly the rule
            // a list of raw `long`s is invisible to.
            int at = rt.mark();
            int n = Maps.entries(rt, rt.r(vi));
            for (int i = 0; i < 2 * n; i++) {
                out = checkSendableAt(rt, rt.r(at + i), depth + 1, carry);
                if (out != null) break;
            }
            rt.popTo(at);
        } else if (Sets.isSet(rt, rt.r(vi))) {
            int ei = rt.push(Sets.elementVector(rt, rt.r(vi)));
            int n = Vec.count(rt, rt.r(ei));
            for (int i = 0; i < n; i++) {
                out = checkSendableAt(rt, Vec.nth(rt, rt.r(ei), i, Val.NOT_FOUND), depth + 1, carry);
                if (out != null) break;
            }
        } else if (rt.isSequential(rt.r(vi))) {
            int si = rt.push(Seqs.seq(rt, rt.r(vi)));
            while (!Val.isNil(rt.r(si))) {
                int fi = rt.push(Seqs.first(rt, rt.r(si)));
                out = checkSendableAt(rt, rt.r(fi), depth + 1, carry);
                rt.popTo(fi);
                if (out != null) break;
                rt.setR(si, Seqs.next(rt, rt.r(si)));
            }
        }
        rt.popTo(base);
        return out;
    }

    // --- the outbound event queue -------------------------------------------

    /// Append an outbound event. `payload` is a string (or byte string) whose
    /// bytes the host will read; the drain copies them into one contiguous
    /// buffer.
    static void pushEvent(Rt rt, long kind, long a, long b, long payload) {
        int base = rt.mark();
        int pi = rt.push(payload);
        int vi = rt.push(Vec.empty(rt));
        for (long x : new long[]{ kind, a, b }) {
            rt.setR(vi, Vec.conj(rt, rt.r(vi), Val.fixnum(x)));
        }
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(pi)));
        int si = rt.push(sched(rt));
        int ei = rt.push(rt.slot(rt.r(si), SC_EVENTS));
        long nevs = Vec.conj(rt, rt.r(ei), rt.r(vi));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_EVENTS, nevs);
        rt.popTo(base);
    }

    /// Does this KIND carry BYTES across a boundary, rather than values inside
    /// one heap? One predicate rather than a widening `==` at each of six
    /// sites, because the last time this was a set of scattered comparisons one
    /// of them was missed.
    public static boolean crossesAHeap(long kind) { return kind == K_BRIDGE; }

    static boolean needPort(Rt rt, long p, String what) {
        if (!isPort(rt, p)) {
            rt.throwStr("ClassCastException", what + " wants a port");
            return false;
        }
        return true;
    }

    public static long send(Rt rt, long p, long v) {
        if (!needPort(rt, p, "send")) return Val.NIL;
        // Never park against a peer that is gone: a script blocking for ever on
        // a host that has hung up is the same failure as a host leaking a
        // handle, seen from the other side.
        long st = fx(rt.slot(p, PT_STATE));
        if (st != P_OPEN) {
            String why = switch ((int) st) {
                case P_CLOSED -> "this end is closed";
                case P_HALF -> "the other end has closed, so nothing can receive this";
                case P_ORPHANED -> "the other end is gone, so nothing can ever receive this";
                case P_REFUSED -> "the host refused to open this";
                default -> "this port is not open yet";
            };
            return rt.throwStr("IllegalStateException", "send: " + why);
        }
        // Root FIRST, check second. `checkSendable` walks the value, and
        // walking a sequential value allocates -- so it can collect, and an
        // unrooted `p`/`v` comes back pointing into the abandoned semispace.
        // Nothing downstream can tell that from a live pointer.
        int base = rt.mark();
        int pi = rt.push(p), vi = rt.push(v);
        long kind = fx(rt.slot(rt.r(pi), PT_KIND));
        int carry = crossesAHeap(kind) ? CARRY_CROSSING : CARRY_LOCAL;
        String bad = checkSendableVia(rt, rt.r(vi), carry);
        if (bad != null) { rt.popTo(base); return rt.throwStr("IllegalArgumentException", bad); }
        if (crossesAHeap(kind)) {
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
                enc = Codec.encode(rt, rt.r(vi));
            } catch (Codec.Refused e) {
                rt.popTo(base);
                return rt.throwStr("IllegalArgumentException",
                    "send: this cannot cross a bridge: " + e.getMessage());
            }
            rt.setR(vi, Bytes.of(rt, enc));
            // Bound the queue in BYTES: back-pressure exists to bound memory,
            // and one 4 MB message is not one message's worth of it.
            //
            // On the handle itself. A bridge is ONE object here -- the far end
            // is the host's registry and is not in any heap -- so it is its own
            // accounting, where a host port used to need a second object to
            // carry the count.
            long len = enc.length;
            long cap = fx(rt.slot(rt.r(pi), PT_CAP));
            long queued = fx(rt.slot(rt.r(pi), PT_BYTES));
            if (queued > 0 && queued + len > cap) {
                long target = rt.r(pi);
                rt.popTo(base);
                return parkOnPort(rt, WK_SEND, target);
            }
            rt.setSlot(Val.asHeap(rt.r(pi)), PT_BYTES, Val.fixnum(queued + len));
            long id = fx(rt.slot(rt.r(pi), PT_ID));
            pushEvent(rt, EV_MESSAGE, id, len, rt.r(vi));
            rt.popTo(base);
            return Val.NIL;
        }
        // A channel end: bound in MESSAGES, since nothing is serialised and the
        // values are shared rather than copied.
        long peer = peerOf(rt, rt.r(pi));
        if (Val.isNil(peer)) {
            rt.popTo(base);
            return rt.throwStr("IllegalStateException",
                "the other end of this port is gone, so nothing can ever receive this");
        }
        int pei = rt.push(peer);
        if (fx(rt.slot(rt.r(pei), PT_STATE)) == P_CLOSED) {
            rt.popTo(base);
            return rt.throwStr("IllegalStateException", "the other end of this port is closed");
        }
        // TRY, then park -- rather than ask whether it is full and then put.
        // The reservation IS the question, in one atomic step, so nothing can
        // change between asking and acting.
        if (enqueue(rt, rt.r(pei), rt.r(vi))) {
            wakeOn(rt, rt.r(pei));
            rt.popTo(base);
            return Val.NIL;
        }
        // FULL: back-pressure. Park on the PEER, because that is what a receive
        // there frees; parking on this end would never be woken.
        long target = rt.r(pei);
        rt.popTo(base);
        return parkForSpace(rt, target);
    }

    /// Take from this port's inbox. Parks when empty.
    public static long receive(Rt rt, long p) {
        if (!needPort(rt, p, "receive")) return Val.NIL;
        int base = rt.mark();
        int pi = rt.push(p);
        if (inboxCount(rt, rt.r(pi)) > 0) {
            int vi = rt.push(dequeue(rt, rt.r(pi)));
            if (crossesAHeap(fx(rt.slot(rt.r(pi), PT_KIND)))) {
                // A bridge queues `[len value]`, and `len` is what
                // `hostDeliver` actually CHARGED -- the length of the encoded
                // message, which is what bounds the host's queue.
                //
                // It used to be recomputed from the value, `Str.byteLen` on
                // whatever came out of the ring. That was wrong twice: it
                // refunded the string's length where the encoded length had
                // been charged, and once a bridge carried VALUES it walked a
                // keyword as a string. On the Rust that was a segfault.
                long item = rt.r(vi);
                long n = fx(Vec.nth(rt, item, 0, Val.NOT_FOUND));
                rt.setR(vi, Vec.nth(rt, item, 1, Val.NOT_FOUND));
                long queued = fx(rt.slot(rt.r(pi), PT_BYTES));
                rt.setSlot(Val.asHeap(rt.r(pi)), PT_BYTES, Val.fixnum(queued > n ? queued - n : 0));
            }
            // Space freed: whoever was blocked sending here can try again.
            wakeOn(rt, rt.r(pi));
            long out = rt.r(vi);
            rt.popTo(base);
            return out;
        }
        long st = fx(rt.slot(rt.r(pi), PT_STATE));
        // Drained and finished cleanly: end of stream, a normal answer.
        if (st == P_CLOSED || st == P_HALF) { rt.popTo(base); return Val.NIL; }
        // Drained and the peer vanished: nobody said goodbye, so say so rather
        // than pretending the stream ended tidily -- and never park, because a
        // script blocked for ever on a host that hung up is the same failure as
        // a host leaking a handle, seen from the other side.
        if (st == P_ORPHANED) {
            rt.popTo(base);
            return rt.throwStr("IllegalStateException",
                "receive: the other end of this port is gone, so this can never complete");
        }
        // A BRIDGE has no peer OBJECT to ask about: the far end is the
        // host's registry and is not in any heap (`doc/decisions/0027`). Its own
        // state is the whole answer, and the states above have already covered
        // every way that can say "no more" -- so an empty buffer here means
        // "nothing yet", which is what parking is for.
        if (fx(rt.slot(rt.r(pi), PT_KIND)) != K_BRIDGE) {
            long peer = peerOf(rt, rt.r(pi));
            if (Val.isNil(peer)) {
                rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(P_ORPHANED));
                rt.popTo(base);
                return rt.throwStr("IllegalStateException",
                    "receive: the other end of this port is gone, so this can never complete");
            }
            long pst = fx(rt.slot(peer, PT_STATE));
            if (pst == P_CLOSED || pst == P_HALF || pst == P_ORPHANED) {
                rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(P_HALF));
                rt.popTo(base);
                return Val.NIL;
            }
        }
        long target = rt.r(pi);
        rt.popTo(base);
        return parkOnPort(rt, WK_RECEIVE, target);
    }

    public static long close(Rt rt, long p) {
        if (!needPort(rt, p, "close")) return Val.NIL;
        int base = rt.mark();
        int pi = rt.push(p);
        if (fx(rt.slot(rt.r(pi), PT_STATE)) != P_CLOSED) {
            rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(P_CLOSED));
            closeSideEffects(rt, rt.r(pi));
        }
        rt.popTo(base);
        return Val.NIL;
    }

    /// Everything that follows from an end closing, however it closed: tell the
    /// host if this was a bridge, and wake anybody parked on either side.
    static void closeSideEffects(Rt rt, long p) {
        int base = rt.mark();
        int pi = rt.push(p);
        if (crossesAHeap(fx(rt.slot(rt.r(pi), PT_KIND)))) {
            // A CLOSE IS A RELEASE, and it is the prompt one.
            //
            // Dropping the last reference and waiting for the collector gets
            // here too, via `reapPorts`, but that is the backstop rather than
            // the mechanism -- it is not prompt, and a host holding a socket
            // until then is a real cost. Closing says so now. The id leaves
            // `SC_BRIDGES` in the same breath, so the sweep does not send a
            // second release for a port already let go.
            long id = fx(rt.slot(rt.r(pi), PT_ID));
            pushEvent(rt, EV_CLOSED, id, 0, Val.NIL);
            forgetBridge(rt, id);
            pushEvent(rt, EV_RELEASE, id, 0, Val.NIL);
        }
        wakeOn(rt, rt.r(pi));
        // The peer becomes HALF-closed rather than closed: it may still drain
        // what is already in its buffer, and only then reads end-of-stream. The
        // channel is not freed until both ends are done.
        long peer = peerOf(rt, rt.r(pi));
        if (!Val.isNil(peer) && fx(rt.slot(peer, PT_STATE)) == P_OPEN) {
            rt.setSlot(Val.asHeap(peer), PT_STATE, Val.fixnum(P_HALF));
            wakeOn(rt, peer);
        }
        rt.popTo(base);
    }

    /// Drop `id` from the held list, so the sweep does not release it twice.
    static void forgetBridge(Rt rt, long id) {
        long s = sched(rt);
        if (Val.isNil(s)) return;
        int base = rt.mark();
        int si = rt.push(s);
        int bi = rt.push(rt.slot(rt.r(si), SC_BRIDGES));
        int n = Vec.count(rt, rt.r(bi));
        int ki = rt.push(Vec.empty(rt));
        for (int k = 0; k < n; k++) {
            long x = fx(Vec.nth(rt, rt.r(bi), k, Val.NOT_FOUND));
            if (x == id) continue;
            rt.setR(ki, Vec.conj(rt, rt.r(ki), Val.fixnum(x)));
        }
        rt.setSlot(Val.asHeap(rt.r(si)), SC_BRIDGES, rt.r(ki));
        rt.popTo(base);
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
    public static long portOpen(Rt rt, long name, long args) {
        ensureSched(rt);
        int base = rt.mark();
        int ni = rt.push(name), ai = rt.push(args);
        int ti = rt.push(currentThread(rt));
        long pending = rt.slot(rt.r(ti), TH_PENDING);
        if (!Val.isNil(pending)) {
            // Second time round: the host has answered.
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_PENDING, Val.NIL);
            // A GRANT left the handle here; a refusal left the sentinel below.
            // Only a refusal is a refusal -- the host may grant and then close
            // the port before this thread is next scheduled, and the port is
            // then `P_HALF` ("granted, and now finished"), not "you may not
            // have this".
            if (isPort(rt, pending)) { rt.popTo(base); return pending; }
            String n = Str.isString(rt, rt.r(ni)) ? Str.text(rt, rt.r(ni)) : "?";
            rt.popTo(base);
            return rt.throwStr("SecurityException", "the host refused to open \"" + n + "\"");
        }
        // NO SYSTEM PORT, NO ASKING. A sandbox given no transport has no way to
        // reach anything outside itself, and saying so here is more honest than
        // pushing an event nothing will ever drain -- that would park the thread
        // for ever and read as a hang rather than as a refusal.
        long sys = systemPort(rt);
        if (Val.isNil(sys)) {
            String n = Str.isString(rt, rt.r(ni)) ? Str.text(rt, rt.r(ni)) : "?";
            rt.popTo(base);
            return rt.throwStr("SecurityException",
                "this sandbox was given no system port, so it cannot ask for \"" + n + "\"");
        }
        int si = rt.push(sys);
        // The waiter hangs off the SYSTEM port, because that is the port the
        // request went out on and there is no other port yet -- the whole point
        // is that the answer is what creates one.
        long token = newWaiter(rt, WK_OPEN, rt.r(si));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_TOKEN, Val.fixnum(token));
        // Marked as awaiting an answer with a value that is NOT a port, so the
        // resume above can tell "granted" from "refused" by type rather than by
        // a state flag on an object that does not exist until granted.
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_PENDING, Val.fixnum(0));
        // THE ARGUMENTS, ENCODED, are the payload -- not a bare name string.
        // That is the whole of "the host does what it wants with them": one
        // value crosses, and anything an opaque value carries survives the trip
        // because the codec already knew how to write one down.
        int vi = rt.push(Vec.empty(rt));
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(ni)));
        int an = rt.isHeapTy(rt.r(ai), TY_VEC) ? Vec.count(rt, rt.r(ai)) : 0;
        for (int k = 0; k < an; k++) {
            rt.setR(vi, Vec.conj(rt, rt.r(vi), Vec.nth(rt, rt.r(ai), k, Val.NOT_FOUND)));
        }
        byte[] call;
        try {
            call = Codec.encode(rt, rt.r(vi));
        } catch (Codec.Refused e) {
            // A value the codec refuses is the PROGRAM's error, not the host's:
            // say so here rather than sending something the host cannot read.
            rt.popTo(base);
            return rt.throwStr("IllegalArgumentException",
                "open: this cannot be sent to the host: " + e.getMessage());
        }
        int payi = rt.push(Bytes.of(rt, call));
        long sysId = fx(rt.slot(rt.r(si), PT_ID));
        pushEvent(rt, EV_OPEN, token, sysId, rt.r(payi));
        long target = rt.r(si);
        rt.popTo(base);
        return park(rt, target);
    }

    /// Ask the host for something, and get a VALUE back.
    ///
    /// `portOpen` generalised (`doc/decisions/0036` step 7). The two differ in
    /// exactly one place -- what the answer may be -- and that difference is
    /// load-bearing, so they are two methods rather than one with a flag: a
    /// port is granted BY ID through `hostGrant` and never encoded, because
    /// encoding a handle the host is lending is the one thing the codec must
    /// not do. Everything else is the same park, waiter and system port.
    public static long hostRequest(Rt rt, long what, long args) {
        ensureSched(rt);
        int base = rt.mark();
        int ni = rt.push(what), ai = rt.push(args);
        int ti = rt.push(currentThread(rt));
        long pending = rt.slot(rt.r(ti), TH_PENDING);
        if (!Val.isNil(pending)) {
            // Second time round: the host has answered.
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_PENDING, Val.NIL);
            // A one-element VECTOR is an answer, holding whatever the host
            // sent. Anything else is a refusal.
            //
            // The wrapper is not decoration. An answer may be any value at all,
            // NIL included, so "answered" cannot be read off the value's type
            // the way `portOpen` reads it off `isPort` -- a host answering nil
            // and a host refusing would be the same bits.
            if (rt.isHeapTy(pending, TY_VEC)) {
                long v = Vec.count(rt, pending) > 0 ? Vec.nth(rt, pending, 0, Val.NOT_FOUND) : Val.NIL;
                rt.popTo(base);
                return v;
            }
            String n = Str.isString(rt, rt.r(ni)) ? Str.text(rt, rt.r(ni)) : "?";
            rt.popTo(base);
            return rt.throwStr("SecurityException",
                "the host refused the request \"" + n + "\"");
        }
        // NO SYSTEM PORT, NO ASKING -- the same honesty as `portOpen`: pushing
        // an event nothing will drain parks the thread for ever and reads as a
        // hang rather than as a refusal.
        long sys = systemPort(rt);
        if (Val.isNil(sys)) {
            String n = Str.isString(rt, rt.r(ni)) ? Str.text(rt, rt.r(ni)) : "?";
            rt.popTo(base);
            return rt.throwStr("SecurityException",
                "this sandbox was given no system port, so it cannot ask for \"" + n + "\"");
        }
        int si = rt.push(sys);
        long token = newWaiter(rt, WK_REQUEST, rt.r(si));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_TOKEN, Val.fixnum(token));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_PENDING, Val.fixnum(0));
        // `[what & args]`, encoded -- the same payload shape `portOpen` sends,
        // so a host that already routes one routes the other.
        int vi = rt.push(Vec.empty(rt));
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(ni)));
        int an = rt.isHeapTy(rt.r(ai), TY_VEC) ? Vec.count(rt, rt.r(ai)) : 0;
        for (int k = 0; k < an; k++) {
            rt.setR(vi, Vec.conj(rt, rt.r(vi), Vec.nth(rt, rt.r(ai), k, Val.NOT_FOUND)));
        }
        byte[] call;
        try {
            call = Codec.encode(rt, rt.r(vi));
        } catch (Codec.Refused e) {
            rt.popTo(base);
            return rt.throwStr("IllegalArgumentException",
                "request: this cannot be sent to the host: " + e.getMessage());
        }
        int payi = rt.push(Bytes.of(rt, call));
        long sysId = fx(rt.slot(rt.r(si), PT_ID));
        pushEvent(rt, EV_REQUEST, token, sysId, rt.r(payi));
        long target = rt.r(si);
        rt.popTo(base);
        return park(rt, target);
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
    public static boolean hostContinue(Rt rt, long token, boolean ok) {
        if (ok) {
            // A GRANT HAS TO NAME A PORT. There is no port to grant until the
            // host says which one -- that is what `0027` inverted -- so this
            // form can only ever mean a refusal, and a host that means to grant
            // calls `hostGrant`. Answering `true` here would have to invent a
            // port, which is exactly the construction the sandbox may not do and
            // the host must not be able to do by accident.
            return false;
        }
        long w = waiterAt(rt, token);
        if (Val.isNil(w)) return false;
        int base = rt.mark();
        int wi = rt.push(w);
        // The refusal is left on the thread as a non-port, which `portOpen`
        // reads on resume. Nothing else has to be cleaned up, because a refused
        // open allocated nothing in the first place.
        wakeWaiter(rt, rt.r(wi));
        rt.popTo(base);
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
    public static boolean hostGrant(Rt rt, long token, long hostPortId) {
        long w = waiterAt(rt, token);
        if (Val.isNil(w)) return false;
        int base = rt.mark();
        int wi = rt.push(w);
        long label = rt.slot(rt.r(wi), W_PORT);
        label = Val.isNil(label) ? Val.NIL : rt.slot(label, PT_LABEL);
        int li = rt.push(label);
        long p = installBridgePort(rt, hostPortId, rt.r(li), false);
        if (Val.isNil(p)) { rt.popTo(base); return false; }
        int pi = rt.push(p);
        long th = rt.slot(rt.r(wi), W_THREAD);
        if (!Val.isNil(th)) rt.setSlot(Val.asHeap(th), TH_PENDING, rt.r(pi));
        wakeWaiter(rt, rt.r(wi));
        rt.popTo(base);
        return true;
    }

    /// The host's answer to an `EV_REQUEST`, as encoded bytes.
    ///
    /// Decoded HERE, at the boundary, like every other thing crossing a bridge
    /// (`doc/decisions/0027`): the guest gets a value and never a codec.
    ///
    /// To REFUSE, call `hostContinue(token, false)` as with an open -- a
    /// refusal carries no value and needs no bytes.
    public static boolean hostAnswer(Rt rt, long token, byte[] bytes) {
        long w = waiterAt(rt, token);
        if (Val.isNil(w)) return false;
        int base = rt.mark();
        int wi = rt.push(w);
        long v;
        try {
            v = Codec.decode(rt, bytes);
        } catch (RuntimeException e) {
            rt.popTo(base);
            return false;
        }
        int vi = rt.push(v);
        // Wrapped, so that a host answering nil is distinguishable from a host
        // refusing. See `hostRequest`.
        int oi = rt.push(Vec.empty(rt));
        rt.setR(oi, Vec.conj(rt, rt.r(oi), rt.r(vi)));
        long th = rt.slot(rt.r(wi), W_THREAD);
        if (!Val.isNil(th)) rt.setSlot(Val.asHeap(th), TH_PENDING, rt.r(oi));
        wakeWaiter(rt, rt.r(wi));
        rt.popTo(base);
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
    ///
    /// The bytes are DECODED here, by the runtime, which is the mirror of
    /// `send` encoding them. The HOST wrote them, so the live tags are honoured
    /// -- `decode`, not `decodeGuest`. That is the whole asymmetry: an opaque
    /// the host issued arrives as itself, with the id it was given, and nothing
    /// the guest can write reaches this call.
    public static boolean hostDeliver(Rt rt, long hostPortId, byte[] bytes) {
        long host = portById(rt, hostPortId);
        if (Val.isNil(host)) return false;
        int base = rt.mark();
        int hi = rt.push(host);
        // A bridge is ONE object and the id is its own, so the lookup above has
        // already found the end to deliver into. There is no pair and no peer
        // hop: that indirection existed only because a host port kept its
        // bookkeeping on a second object (`doc/decisions/0027`).
        int pi = rt.push(rt.r(hi));
        // BACK-PRESSURE in bytes, CLAIMED ATOMICALLY: two host threads
        // delivering into one end would both read the same `queued`, both find
        // room, and both write -- and the bound that exists to cap memory would
        // be the one thing not enforced.
        long cap = fx(rt.slot(rt.r(pi), PT_CAP));
        long len = bytes.length;
        for (;;) {
            long pv = rt.r(pi);
            long queued = fx(slotAtomic(rt, pv, PT_BYTES));
            if (queued > 0 && queued + len > cap) { rt.popTo(base); return false; }
            if (casSlot(rt, pv, PT_BYTES, Val.fixnum(queued), Val.fixnum(queued + len))) break;
        }
        long v;
        try {
            v = Codec.decode(rt, bytes);
        } catch (RuntimeException e) {
            // Refused rather than delivered as anything else: a message the
            // format cannot read is the host's error, and turning it into a
            // string here would hand the guest something that silently was not
            // what was sent.
            giveBack(rt, rt.r(pi), len);
            rt.popTo(base);
            return false;
        }
        int vi = rt.push(v);
        // `[len value]`, because the refund has to be the number that was
        // CHARGED and nothing about a decoded value says what that was.
        {
            int m = rt.mark();
            int ei = rt.push(Vec.empty(rt));
            rt.setR(ei, Vec.conj(rt, rt.r(ei), Val.fixnum(len)));
            rt.setR(vi, Vec.conj(rt, rt.r(ei), rt.r(vi)));
            rt.popTo(m);
        }
        if (!enqueue(rt, rt.r(pi), rt.r(vi))) {
            // The ring is full though the byte bound had room: the guest has
            // not drained. Give the bytes back -- a message the guest never saw
            // must not go on counting against its bound -- and tell the host to
            // offer it again. Back-pressure, not an error.
            giveBack(rt, rt.r(pi), len);
            rt.popTo(base);
            return false;
        }
        wakeOn(rt, rt.r(pi));
        rt.popTo(base);
        return true;
    }

    /// Return bytes claimed against a port's bound for a message that was never
    /// delivered.
    static void giveBack(Rt rt, long p, long len) {
        for (;;) {
            long q = fx(slotAtomic(rt, p, PT_BYTES));
            long back = q > len ? q - len : 0;
            if (casSlot(rt, p, PT_BYTES, Val.fixnum(q), Val.fixnum(back))) return;
        }
    }

    /// The host lets go of its end.
    ///
    /// HALF-CLOSED, not closed: whatever the host already delivered is still
    /// there to be read, and only when that is drained does it read as end of
    /// stream. There is one object now, not a pair, so this is the state of the
    /// handle itself rather than of a second end standing in for it.
    public static void hostClosePort(Rt rt, long hostPortId) {
        long host = portById(rt, hostPortId);
        if (Val.isNil(host)) return;
        int base = rt.mark();
        int hi = rt.push(host);
        if (fx(rt.slot(rt.r(hi), PT_STATE)) == P_OPEN) {
            rt.setSlot(Val.asHeap(rt.r(hi)), PT_STATE, Val.fixnum(P_HALF));
        }
        wakeOn(rt, rt.r(hi));
        rt.popTo(base);
    }

    /// until then would be a notification wearing a query's clothes.
    public static long portStateNow(Rt rt, long p) {
        long st = fx(rt.slot(p, PT_STATE));
        if (st != P_OPEN) return st;
        long peerId = fx(rt.slot(p, PT_PEER));
        if (peerId < 0) return st;
        if (Val.isNil(portById(rt, peerId))) {
            rt.setSlot(Val.asHeap(p), PT_STATE, Val.fixnum(P_ORPHANED));
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
    public static long hostPortState(Rt rt, long hostPortId) {
        long p = portById(rt, hostPortId);
        // Never heard of, or the handle has been collected. Either way a host
        // treats it as done, which is the case a missed `:closed` event would
        // otherwise leak.
        if (Val.isNil(p)) return 255;
        return portStateNow(rt, p);
    }

    /// Serialise every pending event into one contiguous buffer and hand it
    /// over. One call per pump: the boundary crossing is tens of nanoseconds,
    /// the marshalling is the cost, so everything pending goes at once.
    ///
    /// Layout: `count` records of five little-endian `u32`s --
    /// `kind, a, b, payload-offset, payload-len` -- followed by the payload
    /// bytes, all offsets relative to the start of the buffer.
    /// What `drainEvents` hands back: the COUNT and the buffer.
    ///
    /// The Rust signature is `drain_events(&mut Vec<u8>) -> u32` -- it fills a
    /// buffer and answers how many records it wrote. Java has no out-parameter,
    /// so the pair travels together rather than the count being inferred from
    /// the first payload offset, which is derivable but is not what the wire
    /// says.
    public record Events(int count, byte[] bytes) {}

    public static Events drainEvents(Rt rt) {
        long s = sched(rt);
        if (Val.isNil(s)) return new Events(0, new byte[0]);
        int base = rt.mark();
        int si = rt.push(s);
        int ei = rt.push(rt.slot(rt.r(si), SC_EVENTS));
        int n = Vec.count(rt, rt.r(ei));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = new byte[n * 20];
        out.write(header, 0, header.length);
        for (int i = 0; i < n; i++) {
            long e = Vec.nth(rt, rt.r(ei), i, Val.NOT_FOUND);
            long kind = fx(Vec.nth(rt, e, 0, Val.NOT_FOUND));
            long a = fx(Vec.nth(rt, e, 1, Val.NOT_FOUND));
            long b = fx(Vec.nth(rt, e, 2, Val.NOT_FOUND));
            long payload = Vec.nth(rt, e, 3, Val.NOT_FOUND);
            int off = out.size();   // the header is already in `out`
            int plen;
            if (Bytes.isBytes(rt, payload)) {
                byte[] bs = Bytes.toArray(rt, payload);
                out.write(bs, 0, bs.length);
                plen = bs.length;
            } else if (rt.isHeapTy(payload, TY_VEC)) {
                int m = Vec.count(rt, payload);
                for (int k = 0; k < m; k++) out.write((int) fx(Vec.nth(rt, payload, k, Val.NOT_FOUND)) & 0xFF);
                plen = m;
            } else if (Str.isString(rt, payload)) {
                byte[] bs = Str.text(rt, payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(bs, 0, bs.length);
                plen = bs.length;
            } else {
                plen = 0;
            }
            int rec = i * 20;
            le32(header, rec, kind);
            le32(header, rec + 4, a);
            le32(header, rec + 8, b);
            le32(header, rec + 12, off);
            le32(header, rec + 16, plen);
            // A message leaving the queue frees its bytes against the bound.
            if (kind == EV_MESSAGE) {
                long port = portById(rt, a);
                if (!Val.isNil(port)) {
                    long queued = fx(rt.slot(port, PT_BYTES));
                    rt.setSlot(Val.asHeap(port), PT_BYTES, Val.fixnum(queued > b ? queued - b : 0));
                    wakeOn(rt, port);
                }
            }
        }
        rt.setSlot(Val.asHeap(rt.r(si)), SC_EVENTS, Vec.empty(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_EHEAD, Val.fixnum(0));
        rt.popTo(base);
        byte[] buf = out.toByteArray();
        System.arraycopy(header, 0, buf, 0, header.length);
        return new Events(n, buf);
    }

    static void le32(byte[] b, int at, long v) {
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
    public static void reapPorts(Rt rt) {
        long s = sched(rt);
        if (Val.isNil(s)) return;
        int base = rt.mark();
        int si = rt.push(s);
        // --- bridges: a collection is a RELEASE -----------------------------
        //
        // The handle is ordinary memory and is not rooted, so the collector
        // finding it unreachable IS this sandbox letting the port go. One
        // release per retain, which is what makes the host's count a count of
        // holders rather than of arrivals (`doc/decisions/0027`).
        int bi = rt.push(rt.slot(rt.r(si), SC_BRIDGES));
        int bn = Vec.count(rt, rt.r(bi));
        int hi = rt.push(Vec.empty(rt));
        for (int k = 0; k < bn; k++) {
            long id = fx(Vec.nth(rt, rt.r(bi), k, Val.NOT_FOUND));
            if (Val.isNil(portById(rt, id))) {
                // CLOSED as well as released. `doc/decisions/0006`: an end the
                // collector finds unreachable IS the script having called
                // `close`, so the host hears the same pair either way.
                pushEvent(rt, EV_CLOSED, id, 0, Val.NIL);
                pushEvent(rt, EV_RELEASE, id, 0, Val.NIL);
                continue;
            }
            rt.setR(hi, Vec.conj(rt, rt.r(hi), Val.fixnum(id)));
        }
        rt.setSlot(Val.asHeap(rt.r(si)), SC_BRIDGES, rt.r(hi));

        // --- channels: a collected end orphans its peer ----------------------
        int ii = rt.push(rt.slot(rt.r(si), SC_PORTS));
        int n = Vec.count(rt, rt.r(ii));
        int li = rt.push(Vec.empty(rt));
        for (int k = 0; k < n; k++) {
            long id = fx(Vec.nth(rt, rt.r(ii), k, Val.NOT_FOUND));
            long p = portById(rt, id);
            if (!Val.isNil(p)) {
                rt.setR(li, Vec.conj(rt, rt.r(li), Val.fixnum(id)));
                continue;
            }
            // This end has been collected. Tell whoever is affected.
            long peer = portById(rt, peerIdOfDead(rt, id));
            if (Val.isNil(peer)) continue;
            int pi = rt.push(peer);
            long pst = fx(rt.slot(rt.r(pi), PT_STATE));
            if (pst != P_CLOSED && pst != P_ORPHANED) {
                // Its peer vanished WITHOUT closing, which is not the same as a
                // tidy close and should not read like one.
                rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(P_ORPHANED));
            }
            failWaitersOn(rt, rt.r(pi),
                "the other end of this port is unreachable, so this can never complete");
            wakeOn(rt, rt.r(pi));
            rt.popTo(pi);
        }
        rt.setSlot(Val.asHeap(rt.r(si)), SC_PORTS, rt.r(li));
        rt.popTo(base);
    }

    /// Wake everything parked on `p` with an ERROR instead of a value. Used when
    /// the peer end has been collected: that receive can never succeed, and a
    /// hang is the worst possible way to say so.
    static void failWaitersOn(Rt rt, long p, String msg) {
        int base = rt.mark();
        int pi = rt.push(p);
        int wsi = rt.push(waiters(rt));
        int n = Vec.count(rt, rt.r(wsi));
        for (int i = 0; i < n; i++) {
            long w = Vec.nth(rt, rt.r(wsi), i, Val.NOT_FOUND);
            if (w == Val.NOT_FOUND || Val.isNil(w) || Val.isNil(rt.slot(w, W_THREAD))) continue;
            if (rt.slot(w, W_PORT) != rt.r(pi)) continue;
            int ti = rt.push(rt.slot(w, W_THREAD));
            wakeWaiter(rt, w);
            long e = rt.makeError("IllegalStateException", msg);
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_FAIL, e);
            rt.popTo(ti);
        }
        rt.popTo(base);
    }

    /// Program exit: close every flint end so a host is never left guessing
    /// whether more is coming, and leave the events for the final drain.
    /// Program exit: close and release every bridge, so a host is never left
    /// holding a reference for a sandbox that has finished, and leave the
    /// events for the final drain.
    public static void closeAllBridges(Rt rt) {
        long s = sched(rt);
        if (Val.isNil(s)) return;
        int base = rt.mark();
        int si = rt.push(s);
        int ii = rt.push(rt.slot(rt.r(si), SC_PORTS));
        int n = Vec.count(rt, rt.r(ii));
        for (int k = 0; k < n; k++) {
            long p = portById(rt, fx(Vec.nth(rt, rt.r(ii), k, Val.NOT_FOUND)));
            if (Val.isNil(p)) continue;
            int pi = rt.push(p);
            if (crossesAHeap(fx(rt.slot(rt.r(pi), PT_KIND)))
                && fx(rt.slot(rt.r(pi), PT_STATE)) != P_CLOSED) {
                rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(P_CLOSED));
                closeSideEffects(rt, rt.r(pi));
            }
            rt.popTo(pi);
        }
        rt.popTo(base);
    }

    // --- joining ------------------------------------------------------------

    public static long join(Rt rt, long t) {
        if (!isThread(rt, t)) return rt.throwStr("ClassCastException", "join wants a thread, got " + rt.describe(t));
        long st = fx(rt.slot(t, TH_STATUS));
        if (st == ST_DONE) return rt.slot(t, TH_RESULT);
        if (st == ST_FAILED) {
            // The thread's failure becomes the joiner's, which is what makes a
            // silent dead thread impossible to ignore.
            rt.thrown = rt.slot(t, TH_RESULT);
            return Val.NIL;
        }
        // Parked ON THE THREAD OBJECT, which `settle` wakes when it finishes.
        // A thread is a wake key like any port -- that is why `wakeOn` takes a
        // value rather than a port.
        return parkOnPort(rt, WK_JOIN, t);
    }

    public static long state(Rt rt, long t) {
        if (!isThread(rt, t)) return Val.NIL;
        switch ((int) fx(rt.slot(t, TH_STATUS))) {
            case ST_NEW:
            case ST_RUNNABLE: return Str.keyword(rt, null, "runnable");
            case ST_PARKED: return Str.keyword(rt, null, "parked");
            case ST_DONE: return Str.keyword(rt, null, "done");
            default: return Str.keyword(rt, null, "failed");
        }
    }

    // --- the scheduler ------------------------------------------------------

    /// Record the outcome of the thread that was running, and take it off.
    static void settle(Rt rt, long result) {
        long th = currentThread(rt);
        if (Val.isNil(th)) return;
        int base = rt.mark();
        int ti = rt.push(th);
        // Dynamic bindings travel WITH the thread.
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_BINDINGS, rt.roots.shared.singletons[Rt.SING_BINDINGS]);
        if (!Val.isNil(rt.parkOn)) {
            long on = rt.parkOn;
            rt.parkOn = Val.NIL;
            // AND the sentinel. A park travels as `thrown = PARK` so that every
            // frame between the builtin and here unwinds; this is where it stops
            // being in flight. Leaving it set made the NEXT thread's clean
            // finish read as a failure -- it ran fine, returned a value, and
            // `settle` recorded the sentinel as its result.
            rt.thrown = Val.NIL;
            if (on == PARK_YIELD) {
                // A COURTESY yield: still runnable, and it must NOT rewind.
                // Re-executing the `yield` yields again, for ever.
                rt.setSlot(Val.asHeap(rt.r(ti)), TH_STATUS, Val.fixnum(ST_RUNNABLE));
                rt.setSlot(Val.asHeap(rt.r(ti)), TH_PARK_ON, Val.NIL);
            } else {
                int oi = rt.push(on);
                rt.setSlot(Val.asHeap(rt.r(ti)), TH_STATUS, Val.fixnum(ST_PARKED));
                rt.setSlot(Val.asHeap(rt.r(ti)), TH_PARK_ON, rt.r(oi));
            }
            saveCurrentState(rt, rt.r(ti));
        } else if (!Val.isNil(rt.thrown)) {
            int ei = rt.push(rt.thrown);
            rt.thrown = Val.NIL;
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_STATUS, Val.fixnum(ST_FAILED));
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_RESULT, rt.r(ei));
            wakeOn(rt, rt.r(ti));
            rt.frames.clear(); rt.handlers.clear(); rt.roots.stackTop = 0;
        } else {
            int ri = rt.push(result);
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_STATUS, Val.fixnum(ST_DONE));
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_RESULT, rt.r(ri));
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_STACK, Val.NIL);
            wakeOn(rt, rt.r(ti));
            rt.frames.clear(); rt.handlers.clear(); rt.roots.stackTop = 0;
        }
        rt.popTo(base);
    }

    /// Round-robin from just after the current thread. DETERMINISTIC by
    /// construction: no randomness, no clock, no host-order dependence. That is
    /// what lets three runtimes agree on an interleaving.
    static int pick(Rt rt) {
        long s = sched(rt);
        long ts = rt.slot(s, SC_THREADS);
        int n = Vec.count(rt, ts);
        if (n == 0) return -1;
        int cur = (int) fx(rt.slot(s, SC_CURRENT));
        for (int k = 1; k <= n; k++) {
            int i = (cur + k) % n;
            long th = Vec.nth(rt, ts, i, Val.NOT_FOUND);
            if (Val.isNil(th) || th == Val.NOT_FOUND) continue;
            long st = fx(rt.slot(th, TH_STATUS));
            if (st == ST_NEW || st == ST_RUNNABLE) return i;
        }
        return -1;
    }

    static void runOne(Rt rt, int i) {
        long s = sched(rt);
        rt.setSlot(Val.asHeap(s), SC_CURRENT, Val.fixnum(i));
        long th = Vec.nth(rt, rt.slot(s, SC_THREADS), i, Val.NOT_FOUND);
        if (Val.isNil(th) || th == Val.NOT_FOUND) return;
        int base = rt.mark();
        int ti = rt.push(th);
        long st = fx(rt.slot(rt.r(ti), TH_STATUS));
        rt.roots.shared.singletons[Rt.SING_BINDINGS] = rt.slot(rt.r(ti), TH_BINDINGS);
        rt.setSliceEnd(rt.steps + SLICE);
        long v;
        if (st == ST_NEW) {
            rt.frames.clear(); rt.handlers.clear(); rt.roots.stackTop = 0;
            long f = rt.slot(rt.r(ti), TH_ENTRY);
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_STATUS, Val.fixnum(ST_RUNNABLE));
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_ENTRY, Val.NIL);
            v = runEntry(rt, f);
        } else {
            restoreState(rt, rt.r(ti));
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_STACK, Val.NIL);
            long fail = rt.slot(rt.r(ti), TH_FAIL);
            if (Val.isNil(fail)) {
                v = rt.run(0);
            } else {
                // Raised HERE, in the thread it concerns, rather than in
                // whichever thread noticed the port had gone. `try` in this
                // thread catches it like any other error.
                rt.setSlot(Val.asHeap(rt.r(ti)), TH_FAIL, Val.NIL);
                rt.thrown = fail;
                v = rt.unwind() ? rt.run(0) : Val.NIL;
            }
        }
        rt.popTo(base);
        settle(rt, v);
    }

    static long runEntry(Rt rt, long f) {
        int calleeAt = rt.roots.stackTop;
        rt.vpush(f);
        if (!rt.enter(f, calleeAt, 0)) { rt.roots.stackTop = calleeAt; return Val.NIL; }
        return rt.run(0);
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
    static boolean mainFinished(Rt rt) {
        long th = Vec.nth(rt, rt.slot(sched(rt), SC_THREADS), 0, Val.NOT_FOUND);
        if (Val.isNil(th) || th == Val.NOT_FOUND) return true;
        long st = fx(rt.slot(th, TH_STATUS));
        return st == ST_DONE || st == ST_FAILED;
    }

    static long mainResult(Rt rt) {
        long th = Vec.nth(rt, rt.slot(sched(rt), SC_THREADS), 0, Val.NOT_FOUND);
        long r = (Val.isNil(th) || th == Val.NOT_FOUND) ? Val.NIL : rt.slot(th, TH_RESULT);
        return r;
    }

    /// The scheduler hook: settle whatever just stopped, then drive.
    public static long scheduler(Rt rt, long first) {
        settle(rt, first);
        return drive(rt);
    }

    /// Re-enter the scheduler after the host has answered.
    ///
    /// What a host calls when `status` came back 2. There is no separate resume
    /// state: the answer was already recorded by `hostContinue`/`hostDeliver`,
    /// and this only starts the loop again. That is why those two record rather
    /// than run -- a host calling one from inside a host function the runtime
    /// itself invoked would otherwise run the scheduler on top of itself.
    public static long resume(Rt rt) {
        if (Val.isNil(sched(rt))) return Val.NIL;
        rt.status = 0;
        return drive(rt);
    }

    static boolean pendingEvents(Rt rt) {
        long s = sched(rt);
        if (Val.isNil(s)) return false;
        return Vec.count(rt, rt.slot(s, SC_EVENTS)) > fx(rt.slot(s, SC_EHEAD));
    }

    /// Is there anything only the HOST can supply? An event it has not drained,
    /// or a thread parked on a port whose other end is outside this heap.
    static boolean needsHost(Rt rt) {
        if (pendingEvents(rt)) return true;
        long ts = rt.slot(sched(rt), SC_THREADS);
        int n = Vec.count(rt, ts);
        for (int i = 0; i < n; i++) {
            long th = Vec.nth(rt, ts, i, Val.NOT_FOUND);
            if (Val.isNil(th) || th == Val.NOT_FOUND) continue;
            if (fx(rt.slot(th, TH_STATUS)) != ST_PARKED) continue;
            long on = rt.slot(th, TH_PARK_ON);
            if (isPort(rt, on) && crossesAHeap(fx(rt.slot(on, PT_KIND)))) return true;
        }
        return false;
    }

    public static long drive(Rt rt) {
        for (;;) {
            // What the collector left behind IS the lifetime rule: a flint end
            // that nothing refers to any more has been closed, whether or not
            // anybody said so (`doc/decisions/0006`).
            reapPorts(rt);
            int i = pick(rt);
            if (i >= 0) { runOne(rt, i); continue; }
            // The entry function's value IS the answer, so once it has returned
            // and nothing else can run, the program is over -- whatever a
            // service thread may still be parked on. Asking "does anything need
            // the host?" first would keep a driver's reader alive for ever.
            if (mainFinished(rt)) {
                // Exit closes every flint end and leaves the events for one last
                // drain, so a host never has to guess whether more is coming.
                closeAllBridges(rt);
                if (pendingEvents(rt)) { rt.status = 2; return Val.NIL; }
                rt.status = 0;
                return mainResult(rt);
            }
            if (needsHost(rt)) { rt.status = 2; return Val.NIL; }
            rt.status = 0;
            // Nothing runnable, nothing the host can help with: the remaining
            // threads are waiting on each other. NAMED rather than hung.
            long ts = rt.slot(sched(rt), SC_THREADS);
            int n = Vec.count(rt, ts);
            int stuck = 0;
            StringBuilder detail = new StringBuilder();
            for (int k = 0; k < n; k++) {
                long th = Vec.nth(rt, ts, k, Val.NOT_FOUND);
                if (Val.isNil(th) || th == Val.NOT_FOUND) continue;
                if (fx(rt.slot(th, TH_STATUS)) != ST_PARKED) continue;
                stuck++;
                long on = rt.slot(th, TH_PARK_ON);
                String what;
                if (isPort(rt, on)) {
                    long l = rt.slot(on, PT_LABEL);
                    String lab = Str.isString(rt, l) ? Str.text(rt, l) : "";
                    what = "port " + fx(rt.slot(on, PT_ID))
                         + (lab.isEmpty() ? "" : " \"" + lab + "\"");
                } else if (isThread(rt, on)) {
                    what = "thread " + fx(rt.slot(on, TH_ID));
                } else {
                    what = "something";
                }
                detail.append("\n  thread ").append(fx(rt.slot(th, TH_ID)))
                      .append(" waiting on ").append(what);
            }
            return rt.throwStr("IllegalStateException",
                "deadlock: " + stuck + " green thread(s) are parked and nothing can wake them"
                + detail);
        }
    }
}
