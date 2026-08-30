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

    public static final int P_OPEN = 0, P_CLOSED = 1, P_HALF = 2, P_ORPHANED = 3;
    public static final int K_CHANNEL = 0, K_FLINT = 1, K_HOST = 2, K_GLOBAL = 3;

    // --- waiter ------------------------------------------------------------

    public static final int W_THREAD = 1, W_KIND = 2, W_PORT = 3, W_NEXT = 4, W_LEN = 5;
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

    public static long sched(Rt rt) { return rt.roots.singletons[Rt.SING_SCHED]; }

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
        rt.roots.singletons[Rt.SING_SCHED] = out;
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
        long binds = rt.roots.singletons[Rt.SING_BINDINGS];
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
        long free = fx(rt.slot(rt.r(si), SC_WFREE));
        long token;
        int wi;
        if (free >= 0) {
            long w = Vec.nth(rt, rt.slot(rt.r(si), SC_WAITERS), (int) free);
            rt.setSlot(Val.asHeap(rt.r(si)), SC_WFREE, rt.slot(w, W_NEXT));
            token = free;
            wi = rt.push(w);
        } else {
            long w = newObj(rt, TY_NODE, W_LEN);
            if (Val.isNil(w)) { rt.popTo(base); return -1; }
            wi = rt.push(w);
            int wsi = rt.push(rt.slot(rt.r(si), SC_WAITERS));
            token = Vec.count(rt, rt.r(wsi));
            long nws = Vec.conj(rt, rt.r(wsi), rt.r(wi));
            rt.setSlot(Val.asHeap(rt.r(si)), SC_WAITERS, nws);
        }
        rt.setSlot(Val.asHeap(rt.r(wi)), W_KIND, Val.fixnum(kind));
        rt.setSlot(Val.asHeap(rt.r(wi)), W_PORT, rt.r(pi));
        rt.setSlot(Val.asHeap(rt.r(wi)), W_NEXT, Val.fixnum(-1));
        rt.setSlot(Val.asHeap(rt.r(wi)), W_THREAD, currentThread(rt));
        rt.popTo(base);
        return token;
    }

    static void freeWaiter(Rt rt, long token) {
        if (token < 0) return;
        long s = sched(rt);
        long w = Vec.nth(rt, rt.slot(s, SC_WAITERS), (int) token);
        if (w == Val.NOT_FOUND || Val.isNil(w)) return;
        rt.setSlot(Val.asHeap(w), W_THREAD, Val.NIL);
        rt.setSlot(Val.asHeap(w), W_PORT, Val.NIL);
        rt.setSlot(Val.asHeap(w), W_NEXT, rt.slot(s, SC_WFREE));
        rt.setSlot(Val.asHeap(s), SC_WFREE, Val.fixnum(token));
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

    /// A pair of ends. Each is a port whose peer is the other; sending on one
    /// arrives on the other's inbox.
    public static long channel(Rt rt, long cap, long label) {
        int base = rt.mark();
        ensureSched(rt);
        int li = rt.push(label);
        int si = rt.push(sched(rt));
        long id = fx(rt.slot(rt.r(si), SC_NEXTID));
        rt.setSlot(Val.asHeap(rt.r(si)), SC_NEXTID, Val.fixnum(id + 2));
        int ai = rt.push(newPort(rt, id, cap, rt.r(li)));
        int bi = rt.push(newPort(rt, id + 1, cap, rt.r(li)));
        rt.setSlot(Val.asHeap(rt.r(ai)), PT_PEER, rt.r(bi));
        rt.setSlot(Val.asHeap(rt.r(bi)), PT_PEER, rt.r(ai));
        int vi = rt.push(Vec.empty(rt));
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(ai)));
        rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(bi)));
        long out = rt.r(vi);
        rt.popTo(base);
        return out;
    }

    static long newPort(Rt rt, long id, long cap, long label) {
        int base = rt.mark();
        int li = rt.push(label);
        int pi = rt.push(newObj(rt, TY_PORT, PT_LEN));
        if (Val.isNil(rt.r(pi))) { rt.popTo(base); return Val.NIL; }
        long p = Val.asHeap(rt.r(pi));
        rt.setSlot(p, PT_ID, Val.fixnum(id));
        rt.setSlot(p, PT_STATE, Val.fixnum(P_OPEN));
        rt.setSlot(p, PT_CAP, Val.fixnum(cap));
        rt.setSlot(p, PT_INBOX, Vec.empty(rt));
        rt.setSlot(p, PT_HEAD, Val.fixnum(0));
        rt.setSlot(p, PT_BYTES, Val.fixnum(0));
        rt.setSlot(p, PT_PEER, Val.NIL);
        rt.setSlot(p, PT_LABEL, rt.r(li));
        rt.setSlot(p, PT_KIND, Val.fixnum(K_CHANNEL));
        rt.setSlot(p, PT_ROOT, Val.NIL);
        rt.setSlot(p, PT_FORMAT, Val.NIL);
        rt.setSlot(p, PT_OPTS, Val.NIL);
        rt.setSlot(p, PT_BINARY, Val.FALSE);
        rt.setSlot(p, PT_PRESENTED, Val.fixnum(-1));
        long out = rt.r(pi);
        rt.popTo(base);
        return out;
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

    /// Send `v` to the PEER's inbox. Parks when the peer is full.
    public static long send(Rt rt, long p, long v) {
        if (!isPort(rt, p)) throw new ClassCastException("send wants a port, got " + rt.describe(p));
        long st = fx(rt.slot(p, PT_STATE));
        if (st == P_CLOSED || st == P_ORPHANED) {
            throw new IllegalStateException("send: this port is closed");
        }
        long peer = rt.slot(p, PT_PEER);
        if (Val.isNil(peer)) throw new IllegalStateException("send: the other end is gone");
        long cap = fx(rt.slot(peer, PT_CAP));
        if (cap > 0 && inboxCount(rt, peer) >= cap) {
            // FULL: park on the PEER, because that is what a receive there
            // frees. Parking on this end would never be woken.
            return parkOnPort(rt, WK_SEND, peer);
        }
        int base = rt.mark();
        int pi = rt.push(peer);
        enqueue(rt, rt.r(pi), v);
        wakeOn(rt, rt.r(pi));
        rt.popTo(base);
        return Val.NIL;
    }

    /// Take from this port's inbox. Parks when empty.
    public static long receive(Rt rt, long p) {
        if (!isPort(rt, p)) throw new ClassCastException("receive wants a port, got " + rt.describe(p));
        int base = rt.mark();
        int pi = rt.push(p);
        if (inboxCount(rt, rt.r(pi)) > 0) {
            int vi = rt.push(dequeue(rt, rt.r(pi)));
            // Space freed: whoever was blocked sending here can try again.
            wakeOn(rt, rt.r(pi));
            long out = rt.r(vi);
            rt.popTo(base);
            return out;
        }
        long st = fx(rt.slot(rt.r(pi), PT_STATE));
        // Drained and finished cleanly: end of stream, a normal answer.
        if (st == P_CLOSED || st == P_HALF) { rt.popTo(base); return Val.NIL; }
        long peer = rt.slot(rt.r(pi), PT_PEER);
        if (Val.isNil(peer)) {
            rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(P_ORPHANED));
            rt.popTo(base);
            // Never park: a script blocked for ever on an end that hung up is
            // the same failure as a leaked handle, seen from the other side.
            throw new IllegalStateException(
                "receive: the other end of this port is gone, so this can never complete");
        }
        long pst = fx(rt.slot(peer, PT_STATE));
        if (pst == P_CLOSED || pst == P_HALF || pst == P_ORPHANED) {
            rt.setSlot(Val.asHeap(rt.r(pi)), PT_STATE, Val.fixnum(P_HALF));
            rt.popTo(base);
            return Val.NIL;
        }
        long target = rt.r(pi);
        rt.popTo(base);
        return parkOnPort(rt, WK_RECEIVE, target);
    }

    public static long close(Rt rt, long p) {
        if (!isPort(rt, p)) return Val.NIL;
        rt.setSlot(Val.asHeap(p), PT_STATE, Val.fixnum(P_CLOSED));
        long peer = rt.slot(p, PT_PEER);
        if (!Val.isNil(peer)) wakeOn(rt, peer);
        wakeOn(rt, p);
        return Val.NIL;
    }

    // --- joining ------------------------------------------------------------

    public static long join(Rt rt, long t) {
        if (!isThread(rt, t)) throw new ClassCastException("join wants a thread, got " + rt.describe(t));
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
        rt.setSlot(Val.asHeap(rt.r(ti)), TH_BINDINGS, rt.roots.singletons[Rt.SING_BINDINGS]);
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
        rt.roots.singletons[Rt.SING_BINDINGS] = rt.slot(rt.r(ti), TH_BINDINGS);
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
            v = rt.run(0);
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

    public static long drive(Rt rt) {
        for (;;) {
            int i = pick(rt);
            if (i >= 0) { runOne(rt, i); continue; }
            if (mainFinished(rt)) { rt.status = 0; return mainResult(rt); }
            // Nothing runnable and the entry has not returned: the remaining
            // threads are waiting on each other. Named rather than hung.
            long s = sched(rt);
            long ts = rt.slot(s, SC_THREADS);
            int n = Vec.count(rt, ts);
            int stuck = 0;
            for (int k = 0; k < n; k++) {
                long th = Vec.nth(rt, ts, k);
                if (!Val.isNil(th) && th != Val.NOT_FOUND
                    && fx(rt.slot(th, TH_STATUS)) == ST_PARKED) stuck++;
            }
            rt.status = 0;
            throw new IllegalStateException(
                "every thread is parked: " + stuck + " waiting, and nothing can wake them");
        }
    }
}
