package com.flint.rt;

import static com.flint.rt.Obj.*;

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

    public static final int PT_ID = 0, PT_STATE = 1, PT_CAP = 2, PT_INBOX = 3,
        PT_HEAD = 4, PT_BYTES = 5, PT_PEER = 6, PT_LABEL = 7, PT_KIND = 8,
        PT_ROOT = 9, PT_FORMAT = 10, PT_OPTS = 11, PT_BINARY = 12,
        PT_PRESENTED = 13, PT_LEN = 14;

    /// A port's STATE. `P_PENDING` is an `open` the host has not answered yet
    /// and `P_REFUSED` is one it declined -- distinct from `P_CLOSED`, because
    /// "you may not have this" and "this is finished" are different answers.
    public static final int P_PENDING = 0, P_OPEN = 1, P_CLOSED = 2, P_HALF = 3,
                            P_ORPHANED = 4, P_REFUSED = 5;
    public static final int K_CHANNEL = 0, K_FLINT = 1, K_HOST = 2, K_GLOBAL = 3;

    /// A channel's default buffer, in MESSAGES.
    public static final long DEFAULT_CAP = 16;

    /// How much a host port will buffer before a send parks.
    public static final long DEFAULT_HOST_CAP = 1 << 20;

    /// What capability an `open` presented, for the host's grant table.
    ///
    /// `PRESENTED_NONE` and `PRESENTED_UNKNOWN` are DIFFERENT and collapsing
    /// them is a security hole rather than a simplification: presenting nothing
    /// is not the same as presenting something the host never issued. The first
    /// version of this reported both as 0, so a guest-minted `(opaque "fs")`
    /// was accepted -- the host saw "no capability offered" and fell back to
    /// allowing it.
    public static final long PRESENTED_NONE = 0;
    public static final long PRESENTED_UNKNOWN = 0xFFFF_FFFFL;

    /// What the host is told about, drained through `drainEvents`.
    public static final int EV_OPEN = 1, EV_MESSAGE = 2, EV_CLOSED = 3;

    // --- waiter ------------------------------------------------------------

    // Everything that parks parks the same way -- `open`, a send to a full
    // port, a receive on an empty one -- through one table with one token type.
    // A token is `(generation << 16) | (index + 1)`: a bare index is reusable,
    // so a late or duplicated reply from the host would resume whatever now
    // occupies that slot -- a wrong thread woken with a stranger's value, and
    // unfindable in production. The generation makes that a rejection instead.
    public static final int W_GEN = 0, W_THREAD = 1, W_KIND = 2, W_PORT = 3,
                            W_NEXT = 4, W_LEN = 5;
    public static final int WK_OPEN = 1, WK_SEND = 2, WK_RECEIVE = 3, WK_JOIN = 4;

    // --- scheduler ---------------------------------------------------------

    public static final int SC_THREADS = 0, SC_CURRENT = 1, SC_NEXTID = 2,
        SC_EVENTS = 3, SC_EHEAD = 4, SC_PORTS = 5, SC_PAIRS = 6,
        SC_WAITERS = 7, SC_WFREE = 8, SC_SYSTEM = 9, SC_LEN = 10;

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
        int base = rt.mark();
        int si = rt.push(newObj(rt, TY_SCHED, SC_LEN));
        if (Val.isNil(rt.r(si))) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(Val.asHeap(rt.r(si)), SC_EVENTS, Vec.empty(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_EHEAD, Val.fixnum(0));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_PORTS, Vec.empty(rt));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_PAIRS, Vec.empty(rt));
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
        return Vec.nth(rt, rt.slot(s, SC_THREADS), (int) fx(rt.slot(s, SC_CURRENT)));
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
        long sv = newObj(rt, TY_NODE, n);
        if (Val.isNil(sv)) { rt.popTo(base); return; }
        for (int i = 0; i < n; i++) rt.setSlot(Val.asHeap(sv), i, rt.roots.stack[i]);
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_STACK, sv);

        long fb = newObj(rt, TY_RAW, rt.frames.size() * FRAME_REC);
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
            long w = Vec.nth(rt, rt.slot(rt.r(si), SC_WAITERS), (int) free);
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
        long w = Vec.nth(rt, waiters(rt), idx);
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
            long w = Vec.nth(rt, ws, i);
            if (!Val.isNil(w) && w != Val.NOT_FOUND && !Val.isNil(rt.slot(w, W_THREAD))) c++;
        }
        return c;
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
            long w = Vec.nth(rt, ws, i);
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
        int ai = rt.push(newPort(rt, cap, rt.r(li), K_CHANNEL, P_OPEN, Val.NIL));
        int bi = rt.push(newPort(rt, cap, rt.r(li), K_CHANNEL, P_OPEN, Val.NIL));
        linkPeers(rt, rt.r(ai), rt.r(bi));
        int vi = rt.push(Vec.empty(rt));
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(ai)));
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(bi)));
        long out = rt.r(vi);
        rt.popTo(base);
        return out;
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
    public static long installGlobalPort(Rt rt, long hostId, long label, long format) {
        ensureSched(rt);
        int base = rt.mark();
        int li = rt.push(label), fi = rt.push(format);
        int pi = rt.push(newPort(rt, DEFAULT_HOST_CAP, rt.r(li), K_GLOBAL, P_OPEN, rt.r(fi)));
        if (Val.isNil(rt.r(pi))) { rt.popTo(base); return Val.NIL; }
        // The HOST's id replaces the one `newPort` minted from this sandbox's
        // counter. A sandbox-local id would mean something different in every
        // other sandbox, which is the coupling `0027` removes.
        rt.setSlot(Val.asHeap(rt.r(pi)), PT_ID, Val.fixnum(hostId));
        // Rooted for as long as the host says it exists: the host holds the
        // other end, so this one cannot be reclaimed just because the guest
        // dropped its last reference.
        long slot = rootPort(rt, rt.r(pi));
        rt.setSlot(Val.asHeap(rt.r(pi)), PT_ROOT, Val.fixnum(slot));
        registerPort(rt, rt.r(pi));
        long out = rt.r(pi);
        rt.popTo(base);
        return out;
    }

    /// The system port: the one a sandbox is given at construction, if it is
    /// given one at all.
    public static long installSystemPort(Rt rt, long hostId, long label, long format) {
        long p = installGlobalPort(rt, hostId, label, format);
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

    /// A port of any kind. The id comes from the scheduler so that every port
    /// in a sandbox has a distinct one, which is what the registry is keyed by.
    static long newPort(Rt rt, long cap, long label, long kind, long state, long format) {
        int base = rt.mark();
        int li = rt.push(label), fi = rt.push(format);
        int pi = rt.push(newObj(rt, TY_PORT, PT_LEN));
        if (Val.isNil(rt.r(pi))) { rt.popTo(base); return Val.NIL; }
        int si = rt.push(sched(rt));
        long id = fx(rt.slot(rt.r(si), SC_NEXTID));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_NEXTID, Val.fixnum(id + 1));
        long p = Val.asHeap(rt.r(pi));
        rt.setSlot(p, PT_ID, Val.fixnum(id));
        rt.setSlot(p, PT_STATE, Val.fixnum(state));
        rt.setSlot(p, PT_CAP, Val.fixnum(cap));
        rt.setSlot(p, PT_INBOX, Vec.empty(rt));
        rt.setSlot(p, PT_HEAD, Val.fixnum(0));
        rt.setSlot(p, PT_BYTES, Val.fixnum(0));
        // PEERS ARE LINKED BY ID, never by object. When one end is collected
        // its object is gone, and a field holding the peer would keep it alive
        // -- which is exactly what `doc/decisions/0006` says must not happen:
        // an unreachable flint end MEANS the script is finished with it.
        rt.setSlot(p, PT_PEER, Val.fixnum(-1));
        rt.setSlot(p, PT_LABEL, rt.r(li));
        rt.setSlot(p, PT_KIND, Val.fixnum(kind));
        rt.setSlot(p, PT_ROOT, Val.fixnum(-1));
        rt.setSlot(p, PT_FORMAT, rt.r(fi));
        rt.setSlot(p, PT_OPTS, Maps.empty(rt));
        rt.setSlot(p, PT_BINARY, Val.fixnum(0));
        rt.setSlot(p, PT_PRESENTED, Val.fixnum(-1));
        registerPort(rt, rt.r(pi));
        if (kind == K_HOST) {
            long slot = rootPort(rt, rt.r(pi));
            rt.setSlot(Val.asHeap(rt.r(pi)), PT_ROOT, Val.fixnum(slot));
        }
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
            if (t.needsGrow()) { t.grow(); t.lookup(id, v -> false); }
            t.insertAt(t.slot, id, rt.r(pi));
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

    /// A HOST end must outlive every flint reference to it, so it goes in
    /// `singletons`, which the collector already traces. Returns the slot.
    static long rootPort(Rt rt, long p) {
        long[] sg = rt.roots.shared.singletons;
        for (int i = Rt.SING_COUNT; i < sg.length; i++) {
            if (Val.isNil(sg[i])) { sg[i] = p; return i; }
        }
        long[] bigger = java.util.Arrays.copyOf(sg, Math.max(sg.length * 2, Rt.SING_COUNT + 8));
        java.util.Arrays.fill(bigger, sg.length, bigger.length, Val.NIL);
        bigger[sg.length] = p;
        rt.roots.shared.singletons = bigger;
        return sg.length;
    }

    static void unrootPort(Rt rt, long p) {
        long slot = fx(rt.slot(p, PT_ROOT));
        long[] sg = rt.roots.shared.singletons;
        if (slot >= 0 && slot < sg.length) {
            sg[(int) slot] = Val.NIL;
            rt.setSlot(Val.asHeap(p), PT_ROOT, Val.fixnum(-1));
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
            long e = Vec.nth(rt, ps, i);
            if (fx(Vec.nth(rt, e, 0)) == id) return fx(Vec.nth(rt, e, 1));
        }
        return -1;
    }

    static int inboxCount(Rt rt, long p) {
        return Vec.count(rt, rt.slot(p, PT_INBOX)) - (int) fx(rt.slot(p, PT_HEAD));
    }

    static void enqueue(Rt rt, long p, long v) {
        int base = rt.mark();
        int pi = rt.push(p), vi = rt.push(v);
        int ibi = rt.push(rt.slot(rt.r(pi), PT_INBOX));
        long nib = Vec.conj(rt, rt.r(ibi), rt.r(vi));
        rt.setSlot(Val.asHeap(rt.r(pi)), PT_INBOX, nib);
        rt.popTo(base);
    }

    static long dequeue(Rt rt, long p) {
        int head = (int) fx(rt.slot(p, PT_HEAD));
        long ib = rt.slot(p, PT_INBOX);
        long v = Vec.nth(rt, ib, head);
        if (head + 1 >= Vec.count(rt, ib)) {
            // Drained: drop the backing vector so nothing stays reachable.
            rt.setSlot(Val.asHeap(p), PT_INBOX, Vec.empty(rt));
            rt.setSlot(Val.asHeap(p), PT_HEAD, Val.fixnum(0));
        } else {
            rt.setSlot(Val.asHeap(p), PT_HEAD, Val.fixnum(head + 1));
        }
        return v == Val.NOT_FOUND ? Val.NIL : v;
    }

    // --- what may cross a port ----------------------------------------------

    /// Null if `v` is data; otherwise WHY it cannot be sent. Functions are
    /// refused BY NAME, because "cannot send that" sends somebody hunting
    /// through a nested structure.
    public static String checkSendable(Rt rt, long v) { return checkSendableAt(rt, v, 0); }

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

    static String checkSendableAt(Rt rt, long v, int depth) {
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
            // Ports are not transferable and cannot be sent
            // (`doc/decisions/0006`). No ownership transfer, no capability
            // leaking through a message, and a wire format that never has to
            // represent a port. The cost -- a capability cannot be delegated at
            // run time -- is in the README.
            case TY_PORT:
                return "a port cannot be sent through a port: only data crosses."
                     + " A capability cannot be delegated at run time.";
            // An opaque value is identity and nothing else
            // (`doc/decisions/0022`), so there is nothing to serialise that
            // would still BE it. Anything a codec could write down is something
            // the receiver could write down too, and then it is mintable --
            // which is the entire property gone.
            case TY_OPAQUE:
                return "an opaque value cannot be sent through a port:"
                     + " it is identity, and identity does not serialise.";
            case TY_STR: case TY_ROPE: case TY_SYM: case TY_KW:
            case TY_BIGINT: case TY_REGEX:
                return null;
            default: break;
        }
        int base = rt.mark();
        int vi = rt.push(v);
        String out = null;
        if (Maps.isMap(rt, rt.r(vi))) {
            // Materialised ON THE SHADOW STACK, not into a host list. The walk
            // below allocates, so anything held in a host `ArrayList<Long>`
            // across it comes back naming the address the object had before the
            // nursery flipped -- `doc/decisions/0031`, which is exactly the rule
            // a list of raw `long`s is invisible to.
            int at = rt.mark();
            int n = Maps.entries(rt, rt.r(vi), at);
            for (int i = 0; i < 2 * n; i++) {
                out = checkSendableAt(rt, rt.r(at + i), depth + 1);
                if (out != null) break;
            }
            rt.popTo(at);
        } else if (Sets.isSet(rt, rt.r(vi))) {
            int ei = rt.push(Sets.elementVector(rt, rt.r(vi)));
            int n = Vec.count(rt, rt.r(ei));
            for (int i = 0; i < n; i++) {
                out = checkSendableAt(rt, Vec.nth(rt, rt.r(ei), i), depth + 1);
                if (out != null) break;
            }
        } else if (rt.isSequential(rt.r(vi))) {
            int si = rt.push(Seqs.seq(rt, rt.r(vi)));
            while (!Val.isNil(rt.r(si))) {
                int fi = rt.push(Seqs.first(rt, rt.r(si)));
                out = checkSendableAt(rt, rt.r(fi), depth + 1);
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
    /// one heap? True for a host port and for a global port, and the two paths
    /// are the same path -- a host port is just a global port whose far end is
    /// the host. One predicate rather than a widening `==` at each of six
    /// sites, because the last time this was a set of scattered comparisons one
    /// of them was missed.
    public static boolean crossesAHeap(long kind) { return kind == K_FLINT || kind == K_GLOBAL; }

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
                case P_REFUSED -> "the host refused this capability";
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
        String bad = checkSendable(rt, rt.r(vi));
        if (bad != null) { rt.popTo(base); return rt.throwStr("IllegalArgumentException", bad); }
        long kind = fx(rt.slot(rt.r(pi), PT_KIND));
        if (crossesAHeap(kind)) {
            // Bound the host's queue in BYTES: back-pressure exists to bound
            // memory, and one 4 MB message is not one message's worth of it.
            boolean binary = fx(rt.slot(rt.r(pi), PT_BINARY)) == 1;
            boolean encoded = binary
                ? (Bytes.isBytes(rt, rt.r(vi)) || rt.isHeapTy(rt.r(vi), TY_VEC))
                : Str.isString(rt, rt.r(vi));
            if (!encoded) {
                rt.popTo(base);
                return rt.throwStr("IllegalArgumentException",
                    "a host port carries bytes; flint.port/send encodes for you, so this is a "
                    + "raw send of something that is not already encoded (a string, or a vector "
                    + "of 0..255, or a byte string, on a binary port)");
            }
            // The host reads contiguous bytes, so the rope stops here. This is
            // the boundary `doc/decisions/0011` means by "flatten before
            // matching": the tree is an internal representation and nothing
            // outside the module has to know about it.
            if (!binary) rt.setR(vi, Str.flatten(rt, rt.r(vi)));
            // Whose bookkeeping the back-pressure lives on.
            //
            // A HOST port is a pair: two objects in this heap, and the far end
            // carries the id the host knows and the byte count. A GLOBAL port
            // is ONE object -- the far end is the host's registry and is not in
            // any heap (`doc/decisions/0027`) -- so it is its own accounting.
            int hi;
            if (kind == K_GLOBAL) {
                hi = rt.push(rt.r(pi));
            } else {
                long host = peerOf(rt, rt.r(pi));
                if (Val.isNil(host)) {
                    rt.popTo(base);
                    return rt.throwStr("IllegalStateException", "the host has closed this port");
                }
                hi = rt.push(host);
            }
            long len = binary
                ? (Bytes.isBytes(rt, rt.r(vi)) ? Bytes.count(rt, rt.r(vi)) : Vec.count(rt, rt.r(vi)))
                : Str.byteLen(rt, rt.r(vi));
            long cap = fx(rt.slot(rt.r(hi), PT_CAP));
            long queued = fx(rt.slot(rt.r(hi), PT_BYTES));
            if (queued > 0 && queued + len > cap) {
                long target = rt.r(hi);
                rt.popTo(base);
                return parkOnPort(rt, WK_SEND, target);
            }
            rt.setSlot(Val.asHeap(rt.r(hi)), PT_BYTES, Val.fixnum(queued + len));
            long id = fx(rt.slot(rt.r(hi), PT_ID));
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
        long cap = fx(rt.slot(rt.r(pei), PT_CAP));
        if (inboxCount(rt, rt.r(pei)) >= cap) {
            // FULL: park on the PEER, because that is what a receive there
            // frees. Parking on this end would never be woken.
            long target = rt.r(pei);
            rt.popTo(base);
            return parkOnPort(rt, WK_SEND, target);
        }
        enqueue(rt, rt.r(pei), rt.r(vi));
        wakeOn(rt, rt.r(pei));
        rt.popTo(base);
        return Val.NIL;
    }

    /// Take from this port's inbox. Parks when empty.
    public static long receive(Rt rt, long p) {
        if (!needPort(rt, p, "receive")) return Val.NIL;
        int base = rt.mark();
        int pi = rt.push(p);
        if (inboxCount(rt, rt.r(pi)) > 0) {
            int vi = rt.push(dequeue(rt, rt.r(pi)));
            if (crossesAHeap(fx(rt.slot(rt.r(pi), PT_KIND)))) {
                // Room again for the host to deliver the next wave.
                long n = Bytes.isBytes(rt, rt.r(vi)) ? Bytes.count(rt, rt.r(vi))
                       : rt.isHeapTy(rt.r(vi), TY_VEC) ? Vec.count(rt, rt.r(vi))
                       : Str.byteLen(rt, rt.r(vi));
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
        // A GLOBAL port has no peer OBJECT to ask about: the far end is the
        // host's registry and is not in any heap (`doc/decisions/0027`). Its own
        // state is the whole answer, and the states above have already covered
        // every way that can say "no more" -- so an empty buffer here means
        // "nothing yet", which is what parking is for.
        if (fx(rt.slot(rt.r(pi), PT_KIND)) != K_GLOBAL) {
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
    /// host if it is the peer, and wake anybody parked on either side.
    static void closeSideEffects(Rt rt, long p) {
        int base = rt.mark();
        int pi = rt.push(p);
        if (crossesAHeap(fx(rt.slot(rt.r(pi), PT_KIND)))) {
            long host = peerOf(rt, rt.r(pi));
            if (!Val.isNil(host)) {
                int hi = rt.push(host);
                long id = fx(rt.slot(rt.r(hi), PT_ID));
                rt.setSlot(Val.asHeap(rt.r(hi)), PT_STATE, Val.fixnum(P_CLOSED));
                pushEvent(rt, EV_CLOSED, id, 0, Val.NIL);
                rt.popTo(hi);
            }
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

    // --- opening a capability -----------------------------------------------

    /// Ask the host for a capability. Blocking from the program's point of
    /// view; from the scheduler's, the thread stops being runnable.
    ///
    /// The RUNTIME creates the pair -- the host never holds two ends and never
    /// hands one back. It is told the token to answer with and the id of the
    /// end it will hold.
    public static long portOpen(Rt rt, long name, long format) {
        return portOpenWith(rt, name, format, Val.NIL);
    }

    public static long portOpenWith(Rt rt, long name, long format, long cap) {
        // Read the presented id FIRST, before anything allocates. It is a
        // fixnum from then on, so it needs no rooting -- cheaper and safer than
        // carrying `cap` through the shadow stack for one field.
        //
        // Three cases, and collapsing the last two is a SECURITY HOLE rather
        // than a simplification: presenting NOTHING is not the same as
        // presenting something the host never issued. The first version
        // reported both as 0, so a guest-minted `(opaque "fs")` was accepted --
        // the host saw "no capability offered" and fell back to allowing it.
        long presented;
        if (Val.isNil(cap)) {
            presented = PRESENTED_NONE;
        } else {
            long id = rt.opaqueHostId(cap);
            presented = id == 0 ? PRESENTED_UNKNOWN : id;
        }
        ensureSched(rt);
        int base = rt.mark();
        int ni = rt.push(name), fi = rt.push(format);
        int ti = rt.push(currentThread(rt));
        long pending = rt.slot(rt.r(ti), TH_PENDING);
        if (!Val.isNil(pending)) {
            // Second time round: the host has answered.
            rt.setSlot(Val.asHeap(rt.r(ti)), TH_PENDING, Val.NIL);
            // ONLY A REFUSAL IS A REFUSAL. The host may answer and then close
            // the port before this thread is next scheduled, and the port is
            // then `P_HALF` -- "granted, and now finished", which is not the
            // same as "you may not have this". Reading only `P_OPEN` as success
            // told a guest its capability had been REFUSED when it had in fact
            // been given one, and `SecurityException` is the last error anybody
            // wants to be wrong about.
            if (fx(rt.slot(pending, PT_STATE)) != P_REFUSED) { rt.popTo(base); return pending; }
            String n = Str.isString(rt, rt.r(ni)) ? Str.text(rt, rt.r(ni)) : "?";
            rt.popTo(base);
            return rt.throwStr("SecurityException",
                "the host refused the capability \"" + n + "\"");
        }
        int ei = rt.push(newPort(rt, DEFAULT_HOST_CAP, rt.r(ni), K_FLINT, P_PENDING, rt.r(fi)));
        int hi = rt.push(newPort(rt, DEFAULT_HOST_CAP, rt.r(ni), K_HOST, P_PENDING, rt.r(fi)));
        linkPeers(rt, rt.r(ei), rt.r(hi));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_PENDING, rt.r(ei));
        long token = newWaiter(rt, WK_OPEN, rt.r(ei));
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_TOKEN, Val.fixnum(token));
        long hostId = fx(rt.slot(rt.r(hi), PT_ID));
        // The presented capability's host id travels WITH THE PORT, for the
        // host to look up in its own grant table when it sees the open-request.
        // The runtime records the claim and never judges it: only the host
        // holds a grant table (`doc/decisions/0022`).
        rt.setSlot(Val.asHeap(rt.r(hi)), PT_PRESENTED, Val.fixnum(presented));
        pushEvent(rt, EV_OPEN, token, hostId, rt.r(ni));
        long target = rt.r(ei);
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
        long w = waiterAt(rt, token);
        if (Val.isNil(w)) return false;
        int base = rt.mark();
        int wi = rt.push(w);
        if (fx(rt.slot(rt.r(wi), W_KIND)) == WK_OPEN) {
            int pi = rt.push(rt.slot(rt.r(wi), W_PORT));
            long state = ok ? P_OPEN : P_REFUSED;
            rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(state));
            long host = peerOf(rt, rt.r(pi));
            if (!Val.isNil(host)) {
                rt.setSlot(Val.asHeap(host), PT_STATE, Val.fixnum(state));
                // Refused: the host never gets a handle, so nothing needs to
                // keep this end alive.
                if (!ok) unrootPort(rt, host);
            }
            rt.popTo(pi);
        }
        wakeWaiter(rt, rt.r(wi));
        rt.popTo(base);
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
    public static boolean hostDeliver(Rt rt, long hostPortId, byte[] bytes) {
        long host = portById(rt, hostPortId);
        if (Val.isNil(host)) return false;
        int base = rt.mark();
        int hi = rt.push(host);
        // A GLOBAL port is ONE object and the id is its own, so the lookup has
        // already found the end to deliver into. A HOST port is a pair, and the
        // id belongs to the far end, so the delivery goes to its peer.
        int pi;
        if (fx(rt.slot(rt.r(hi), PT_KIND)) == K_GLOBAL) {
            pi = rt.push(rt.r(hi));
        } else {
            long flint = peerOf(rt, rt.r(hi));
            if (Val.isNil(flint)) { rt.popTo(base); return false; }
            pi = rt.push(flint);
        }
        long queued = fx(rt.slot(rt.r(pi), PT_BYTES));
        long cap = fx(rt.slot(rt.r(pi), PT_CAP));
        if (queued > 0 && queued + bytes.length > cap) { rt.popTo(base); return false; }
        rt.setSlot(Val.asHeap(rt.r(pi)), PT_BYTES, Val.fixnum(queued + bytes.length));
        // One object, not one boxed fixnum per byte, on a binary port: a vector
        // would cost a 32-way trie and an allocation per 32 bytes for data the
        // codec immediately walks back into bytes.
        long v = fx(rt.slot(rt.r(pi), PT_BINARY)) == 1
               ? Bytes.of(rt, bytes)
               : Str.of(rt, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        int vi = rt.push(v);
        enqueue(rt, rt.r(pi), rt.r(vi));
        wakeOn(rt, rt.r(pi));
        rt.popTo(base);
        return true;
    }

    /// The host lets go of its end. The port may now be collected.
    public static void hostClosePort(Rt rt, long hostPortId) {
        long host = portById(rt, hostPortId);
        if (Val.isNil(host)) return;
        int base = rt.mark();
        int hi = rt.push(host);
        rt.setSlot(Val.asHeap(rt.r(hi)), PT_STATE, Val.fixnum(P_CLOSED));
        long flint = peerOf(rt, rt.r(hi));
        if (!Val.isNil(flint)) {
            int fi = rt.push(flint);
            // Half-closed: whatever the host already delivered is still there
            // to be read, and only then does it read as end of stream.
            if (fx(rt.slot(rt.r(fi), PT_STATE)) == P_OPEN) {
                rt.setSlot(Val.asHeap(rt.r(fi)), PT_STATE, Val.fixnum(P_HALF));
            }
            wakeOn(rt, rt.r(fi));
            rt.popTo(fi);
        }
        unrootPort(rt, rt.r(hi));
        rt.popTo(base);
    }

    /// This end's state, RESOLVED rather than remembered.
    ///
    /// A port whose peer has been collected is orphaned whether or not the
    /// scheduler has got round to noticing, and a query that answered `:open`
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

    /// The host id of the capability presented when this port was opened, or 0.
    ///
    /// The runtime records the claim and answers questions about it; it never
    /// judges it. Only the host holds a grant table (`doc/decisions/0022`).
    public static long presentedCapability(Rt rt, long hostPortId) {
        long p = portById(rt, hostPortId);
        if (Val.isNil(p)) return 0;
        long v = rt.slot(p, PT_PRESENTED);
        return Val.isFixnum(v) ? Val.asFixnum(v) : 0;
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
        long host = portById(rt, hostPortId);
        if (Val.isNil(host)) return 255;
        long flint = peerOf(rt, host);
        // The runtime end has been collected: as good as closed, and this is
        // exactly the case a missed event would have lost.
        if (Val.isNil(flint)) return P_CLOSED;
        return portStateNow(rt, flint);
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
            long e = Vec.nth(rt, rt.r(ei), i);
            long kind = fx(Vec.nth(rt, e, 0));
            long a = fx(Vec.nth(rt, e, 1));
            long b = fx(Vec.nth(rt, e, 2));
            long payload = Vec.nth(rt, e, 3);
            int off = out.size();   // the header is already in `out`
            int plen;
            if (Bytes.isBytes(rt, payload)) {
                byte[] bs = Bytes.toArray(rt, payload);
                out.write(bs, 0, bs.length);
                plen = bs.length;
            } else if (rt.isHeapTy(payload, TY_VEC)) {
                int m = Vec.count(rt, payload);
                for (int k = 0; k < m; k++) out.write((int) fx(Vec.nth(rt, payload, k)) & 0xFF);
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
        int ii = rt.push(rt.slot(rt.r(si), SC_PORTS));
        int n = Vec.count(rt, rt.r(ii));
        int li = rt.push(Vec.empty(rt));
        for (int k = 0; k < n; k++) {
            long id = fx(Vec.nth(rt, rt.r(ii), k));
            long p = portById(rt, id);
            if (!Val.isNil(p)) {
                rt.setR(li, Vec.conj(rt, rt.r(li), Val.fixnum(id)));
                continue;
            }
            // This end has been collected. Tell whoever is affected.
            long peer = portById(rt, peerIdOfDead(rt, id));
            if (Val.isNil(peer)) continue;
            int pi = rt.push(peer);
            long pkind = fx(rt.slot(rt.r(pi), PT_KIND));
            long pst = fx(rt.slot(rt.r(pi), PT_STATE));
            if (pst != P_CLOSED && pst != P_ORPHANED) {
                // Its peer vanished WITHOUT closing, which is not the same as a
                // tidy close and should not read like one.
                rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(P_ORPHANED));
                if (pkind == K_HOST) {
                    pushEvent(rt, EV_CLOSED, fx(rt.slot(rt.r(pi), PT_ID)), 0, Val.NIL);
                }
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
            long w = Vec.nth(rt, rt.r(wsi), i);
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
    public static void closeAllFlintEnds(Rt rt) {
        long s = sched(rt);
        if (Val.isNil(s)) return;
        int base = rt.mark();
        int si = rt.push(s);
        int ii = rt.push(rt.slot(rt.r(si), SC_PORTS));
        int n = Vec.count(rt, rt.r(ii));
        for (int k = 0; k < n; k++) {
            long p = portById(rt, fx(Vec.nth(rt, rt.r(ii), k)));
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
            long th = Vec.nth(rt, ts, i);
            if (Val.isNil(th) || th == Val.NOT_FOUND) continue;
            long st = fx(rt.slot(th, TH_STATUS));
            if (st == ST_NEW || st == ST_RUNNABLE) return i;
        }
        return -1;
    }

    static void runOne(Rt rt, int i) {
        long s = sched(rt);
        rt.setSlot(Val.asHeap(s), SC_CURRENT, Val.fixnum(i));
        long th = Vec.nth(rt, rt.slot(s, SC_THREADS), i);
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
        long th = Vec.nth(rt, rt.slot(sched(rt), SC_THREADS), 0);
        if (Val.isNil(th) || th == Val.NOT_FOUND) return true;
        long st = fx(rt.slot(th, TH_STATUS));
        return st == ST_DONE || st == ST_FAILED;
    }

    static long mainResult(Rt rt) {
        long th = Vec.nth(rt, rt.slot(sched(rt), SC_THREADS), 0);
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
            long th = Vec.nth(rt, ts, i);
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
                closeAllFlintEnds(rt);
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
                long th = Vec.nth(rt, ts, k);
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
