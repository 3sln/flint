package com.flint.rt;

import java.util.ArrayList;
import java.util.Arrays;

/// VM snapshots: capture, restore, export, import. Ported verbatim from
/// `runtime/src/snap.rs` (`doc/decisions/0015`).
///
/// TWO FORMATS, because they answer two different questions and neither one
/// can be made to answer the other's:
///
/// * `capture`/`restore` is a MEMCPY. It copies raw bytes of the heap plus the
///   host-side state and interprets LATER, which is what lets it capture a heap
///   that is already corrupt -- the one case a traversal cannot. It is the
///   post-mortem instrument. It also copies dead objects and unused reserve,
///   and pins the restore to identical addresses.
///
/// * `exportLive`/`importLive` TRAVERSES. It is relocatable and dense, which is
///   what shelving needs: rehydrate in another process, another heap, at
///   whatever addresses that heap hands out.
///
/// The objection `0015` raises to a traversal -- that one which misses an edge
/// yields a snapshot missing an object, and then the CAPTURE is what needs
/// debugging -- is fatal to a bespoke traversal and not to this one, because
/// **the collector decides what is live and this only enumerates what
/// survived**. A missed edge here would be a collector bug that loses objects
/// in ordinary running, which the suite already checks from several directions.
///
/// ## Why this file is not "the JVM's snapshot"
///
/// It writes the same fields in the same order at the same widths as the Rust.
/// That is not tidiness: the goal is that the three runtimes be verbatim
/// mirrors, and a snapshot is where a divergence would show up as silent
/// nonsense rather than as a failure -- a field read at the wrong offset is
/// still a number.
public final class Snap {
    /// "FLSN". Bumped whenever the layout below changes.
    public static final int MAGIC = 0x464C_534E;
    public static final int VERSION = 3;

    /// Why a restore was refused. The call answers a boolean because that is
    /// what the host ABI can carry; this says WHICH of the two checks failed,
    /// so a message can name a cause instead of "no".
    public static final int REFUSE_NONE = 0;
    public static final int REFUSE_LAYOUT = 1;
    public static final int REFUSE_IMAGE = 2;
    public static int refused = REFUSE_NONE;

    /// "FLSX". A different magic from `MAGIC`, deliberately: the two are not
    /// interchangeable and a reader should not have to guess.
    public static final int MAGIC_LIVE = 0x464C_5358;
    public static final int VERSION_LIVE = 1;

    /// How a value is written when it may point at the heap. One byte, so the
    /// encoding is unambiguous rather than clever: a NaN-boxed value uses the
    /// very bits an in-band tag would want.
    private static final int V_LITERAL = 0;
    private static final int V_REF = 1;

    /// `main` should report "this sandbox was shelved", not "here is your
    /// answer". 0 is a normal return and 2 is "I need the host"
    /// (`doc/decisions/0005`), so this takes the next free code rather than
    /// overloading either.
    public static final int STATUS_SHELVED = 3;

    // -----------------------------------------------------------------------

    static final class W {
        byte[] b = new byte[4096];
        int n = 0;

        void need(int k) {
            if (n + k > b.length) {
                int cap = b.length;
                while (cap < n + k) cap *= 2;
                b = Arrays.copyOf(b, cap);
            }
        }
        void u8(int v) { need(1); b[n++] = (byte) v; }
        void u32(int v) {
            need(4);
            b[n++] = (byte) v; b[n++] = (byte) (v >>> 8);
            b[n++] = (byte) (v >>> 16); b[n++] = (byte) (v >>> 24);
        }
        void u64(long v) { u32((int) v); u32((int) (v >>> 32)); }
        /// An ADDRESS, at whatever width an address is. Its own method so the
        /// format has ONE place that decides, rather than a `u32` per field
        /// that would have to be found and changed together.
        void addr(long v) { u64(v); }
        void raw(byte[] src) { need(src.length); System.arraycopy(src, 0, b, n, src.length); n += src.length; }
        void vals(long[] xs, int len) { u32(len); for (int i = 0; i < len; i++) u64(xs[i]); }
        byte[] done() { return Arrays.copyOf(b, n); }
    }

    static final class R {
        final byte[] b;
        int i;
        R(byte[] b, int i) { this.b = b; this.i = i; }
        int u8() { return b[i++] & 0xFF; }
        int u32() {
            int v = (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
            i += 4;
            return v;
        }
        long u64() { return (u32() & 0xFFFFFFFFL) | ((long) u32() << 32); }
        long addr() { return u64(); }
        int usz() { return u32(); }
        long[] vals() {
            int n = usz();
            long[] out = new long[n];
            for (int k = 0; k < n; k++) out[k] = u64();
            return out;
        }
    }

    // -----------------------------------------------------------------------
    // The verbatim format.

    /// The whole VM state as bytes. No interpretation of the heap at all.
    public static byte[] capture(Rt rt) {
        W w = new W();
        w.u32(MAGIC);
        w.u32(VERSION);
        // The image this state belongs to. NOT the image itself: a snapshot
        // carries the heap and the VM state and no code, which is what keeps it
        // small and is the whole reason it can be moved.
        w.u64(rt.fingerprint);

        // --- allocation geometry, so a restore lands at the SAME addresses.
        // Every pointer in the heap is an absolute offset; restoring elsewhere
        // would mean rewriting them, which is a traversal, which is the thing
        // this format exists to avoid.
        Gc g = rt.gc;
        w.addr(g.sp.inUse);
        w.addr(g.sp.reserved);
        w.addr(g.youngBase);
        w.addr(g.half);
        w.addr(g.from);
        w.addr(g.to);
        w.addr(g.toBump);
        w.addr(g.bump);
        w.addr(g.fromEnd);
        w.addr(g.oldCapacity);
        w.addr(g.oldLive);
        w.addr(g.maxHeap);
        w.u32(g.collecting ? 1 : 0);
        w.u32(g.oom ? 1 : 0);
        w.u32(g.stress ? 1 : 0);
        w.u32(g.oldChunks.size());
        for (long[] c : g.oldChunks) { w.addr(c[0]); w.addr(c[1]); }
        w.u32(g.freeLists.length);
        for (long v : g.freeLists) w.addr(v);
        // The remembered set as a LIST. The per-object FLAGS travel in the heap
        // bytes below, in each object's header. Both are captured and neither
        // is derived, because an investigation turns on their disagreeing.
        w.u32(rt.roots.remembered.size());
        for (long a : rt.roots.remembered) w.addr(a);
        w.u64(g.minors);
        w.u64(g.majors);
        w.u64(g.bytesAllocated);
        w.u64(g.bytesCopied);
        w.u64(g.bytesPromoted);
        w.u64(g.peakLive);

        // --- roots
        Roots r = rt.roots;
        w.u32(r.stackTop);
        w.vals(r.stack, r.stackTop);
        w.vals(r.shadow, r.shadowTop);
        w.vals(r.globals, r.globals.length);
        w.vals(r.consts, r.consts.length);
        w.vals(r.singletons, r.singletons.length);
        // The intern tables. This runtime does not carry them yet, so the count
        // is 0 -- the SECTION is here, in position, so that adding them later
        // does not move any field that follows.
        w.u32(0);

        // --- interpreter state
        writeVmState(w, rt);
        w.u64(rt.thrown);
        w.u64(rt.parkOn);
        writeTail(w, rt);

        // --- the heap itself, verbatim, REGION BY REGION. No interpretation.
        //
        // Not one contiguous range: an old chunk can sit far above `inUse`, and
        // a capture that assumed contiguity would silently miss it -- exactly
        // the "answers some questions confidently wrong" failure this design
        // exists to prevent.
        ArrayList<long[]> regions = new ArrayList<>();
        regions.add(new long[]{g.youngBase, g.half * 2});
        for (long[] c : g.oldChunks) regions.add(new long[]{c[0], c[1]});
        w.u32(regions.size());
        for (long[] reg : regions) {
            w.addr(reg[0]);
            w.addr(reg[1]);
            w.raw(g.sp.bytes(reg[0], (int) reg[1]));
        }
        return w.done();
    }

    /// The fields that are neither heap nor roots, in the order both formats
    /// write them. Shared so the two cannot drift apart -- they did in the
    /// Rust once, and a frame read at the wrong offset is still a number.
    private static void writeVmState(W w, Rt rt) {
        w.u32(rt.frames.size());
        for (Frame f : rt.frames) {
            w.u32(f.fp); w.u32(f.ip); w.u32(f.end); w.u32(f.retTo); w.u32(f.handlers);
        }
        w.u32(rt.handlers.size());
        for (Rt.Handler h : rt.handlers) {
            w.u32(h.frame); w.u32(h.stackTop); w.u32(h.target); w.u32(h.shadow);
        }
    }

    private static void readVmState(R r, Rt rt) {
        int nf = r.usz();
        rt.frames.clear();
        for (int i = 0; i < nf; i++) {
            Frame f = new Frame();
            f.fp = r.usz(); f.ip = r.u32(); f.end = r.u32(); f.retTo = r.usz(); f.handlers = r.usz();
            rt.frames.add(f);
        }
        int nh = r.usz();
        rt.handlers.clear();
        for (int i = 0; i < nh; i++) {
            Rt.Handler h = new Rt.Handler();
            h.frame = r.usz(); h.stackTop = r.usz(); h.target = r.u32(); h.shadow = r.usz();
            rt.handlers.add(h);
        }
    }

    private static void writeTail(W w, Rt rt) {
        w.u64(rt.steps);
        w.u64(rt.gasLimit);
        w.u64(rt.sliceEnd);
        w.u64(rt.checkpoint);
        w.u32(rt.gasTrips);
        w.u32(rt.memTrips);
        w.u32(rt.status);
        w.u32(rt.champAdded ? 1 : 0);
    }

    private static void readTail(R r, Rt rt) {
        rt.steps = r.u64();
        rt.gasLimit = r.u64();
        rt.sliceEnd = r.u64();
        rt.checkpoint = r.u64();
        rt.gasTrips = r.u32();
        rt.memTrips = r.u32();
        rt.status = r.u32();
        rt.champAdded = r.u32() != 0;
    }

    public static boolean restore(Rt rt, byte[] bytes) {
        refused = REFUSE_NONE;
        if (bytes.length < 16) { refused = REFUSE_LAYOUT; return false; }
        R r = new R(bytes, 0);
        if (r.u32() != MAGIC || r.u32() != VERSION) { refused = REFUSE_LAYOUT; return false; }
        if (r.u64() != rt.fingerprint) { refused = REFUSE_IMAGE; return false; }

        long inUse = r.addr();
        r.addr();                       // reserved: this space's own is the bound
        Gc g = rt.gc;
        g.youngBase = r.addr();
        g.half = r.addr();
        g.from = r.addr();
        g.to = r.addr();
        g.toBump = r.addr();
        g.bump = r.addr();
        g.fromEnd = r.addr();
        g.oldCapacity = r.addr();
        g.oldLive = r.addr();
        g.maxHeap = r.addr();
        g.collecting = r.u32() != 0;
        g.oom = r.u32() != 0;
        g.stress = r.u32() != 0;
        int nch = r.usz();
        g.oldChunks.clear();
        for (int i = 0; i < nch; i++) g.oldChunks.add(new long[]{r.addr(), r.addr()});
        int nfl = r.usz();
        for (int i = 0; i < nfl; i++) {
            long v = r.addr();
            if (i < g.freeLists.length) g.freeLists[i] = v;
        }
        int nrem = r.usz();
        rt.roots.remembered.clear();
        for (int i = 0; i < nrem; i++) rt.roots.remembered.add(r.addr());
        g.minors = r.u64();
        g.majors = r.u64();
        g.bytesAllocated = r.u64();
        g.bytesCopied = r.u64();
        g.bytesPromoted = r.u64();
        g.peakLive = r.u64();

        int stackTop = r.usz();
        long[] stack = r.vals();
        long[] shadow = r.vals();
        long[] globals = r.vals();
        long[] consts = r.vals();
        long[] singletons = r.vals();
        int nt = r.usz();
        for (int i = 0; i < nt; i++) {      // no intern tables here yet
            int cap = r.usz();
            r.usz();
            for (int k = 0; k < cap; k++) { r.u32(); r.u64(); }
        }

        readVmState(r, rt);
        long thrown = r.u64();
        long parkOn = r.u64();
        readTail(r, rt);

        // Regions, blitted back to the SAME addresses. Relocating would mean
        // rewriting every pointer, which is a traversal, which is what this
        // format avoids -- `importLive` is the one that relocates.
        int nreg = r.usz();
        long[][] plan = new long[nreg][];
        for (int i = 0; i < nreg; i++) {
            long addr = r.addr();
            long len = r.addr();
            if (r.i + len > bytes.length) { refused = REFUSE_LAYOUT; return false; }
            plan[i] = new long[]{addr, len, r.i};
            r.i += (int) len;
        }
        // Refuse rather than write garbage: if this runtime's space does not
        // already cover a region, its addresses mean something else here.
        for (long[] p : plan) {
            if (p[0] + p[1] > g.sp.reserved) { refused = REFUSE_LAYOUT; return false; }
        }
        g.sp.inUse = inUse;
        for (long[] p : plan) {
            g.sp.writeBytes(p[0], Arrays.copyOfRange(bytes, (int) p[2], (int) (p[2] + p[1])));
        }

        Roots rr = rt.roots;
        if (rr.stack.length < stack.length + 8) rr.stack = new long[stack.length + 8];
        System.arraycopy(stack, 0, rr.stack, 0, stack.length);
        rr.stackTop = stackTop;
        if (rr.shadow.length < shadow.length + 8) rr.shadow = new long[shadow.length + 8];
        System.arraycopy(shadow, 0, rr.shadow, 0, shadow.length);
        rr.shadowTop = shadow.length;
        rr.globals = globals;
        rr.consts = consts;
        rt.consts = consts;
        rr.singletons = singletons;
        rt.thrown = thrown;
        rt.parkOn = parkOn;
        // LAST, after the heap is in place. Identities are PRESERVED; whether
        // any of them still means anything is the host's grant table to answer.
        rt.restoredCapabilities = countHostOpaques(rt);
        return true;
    }

    /// Count the host-minted opaque values an import brought back, and leave
    /// every one of them ALONE.
    ///
    /// This used to zero the host id, on the argument that "an imported
    /// snapshot grants nothing". That is the wrong PLACE to enforce it, and it
    /// makes shelving useless: a sandbox holding a file handle comes back
    /// holding a handle to nothing, and no host can put it right because the
    /// identity it would rehydrate against has been erased.
    ///
    /// The check belongs where `doc/decisions/0022` always said it belongs --
    /// the GRANT TABLE, not possession. A host that no longer honours id 7
    /// refuses it exactly as it refuses a forgery.
    ///
    /// The count is still taken, because a test reads it to know the sweep saw
    /// anything at all: a zero would otherwise pass every assertion for the
    /// wrong reason.
    static int countHostOpaques(Rt rt) {
        Space sp = rt.gc.sp;
        int n = 0;
        for (long a = rt.gc.from; Long.compareUnsigned(a, rt.gc.bump) < 0; ) {
            long size = Obj.sizeOf(sp, a);
            if (size < 8) break;
            if (Obj.ty(sp, a) == Obj.TY_OPAQUE) n++;
            a += size;
        }
        for (long[] ch : rt.gc.oldChunks) {
            long end = ch[0] + ch[1];
            for (long a = ch[0]; Long.compareUnsigned(a, end) < 0; ) {
                long size = Obj.sizeOf(sp, a);
                if (size < 8 || a + size > end) break;
                if (Obj.ty(sp, a) != Obj.TY_FREE && Obj.ty(sp, a) == Obj.TY_OPAQUE) n++;
                a += size;
            }
        }
        return n;
    }

    // -----------------------------------------------------------------------
    // The live-set format: relocatable, and no dead objects in it.

    /// Every live object, in ADDRESS order after a collection.
    ///
    /// Address order rather than discovery order because it is derived from the
    /// heap rather than from a walk this file wrote -- one less thing that can
    /// be subtly wrong and still look plausible.
    static long[] liveObjects(Rt rt) {
        Space sp = rt.gc.sp;
        ArrayList<Long> out = new ArrayList<>();
        // The nursery is contiguous: a copying minor leaves exactly the
        // survivors between `from` and `bump`.
        for (long a = rt.gc.from; Long.compareUnsigned(a, rt.gc.bump) < 0; ) {
            long size = Obj.sizeOf(sp, a);
            if (size == 0) break;
            out.add(a);
            a += size;
        }
        // Old space is swept, so the holes are `TY_FREE` and everything else is
        // live.
        for (long[] ch : rt.gc.oldChunks) {
            long end = ch[0] + ch[1];
            for (long a = ch[0]; Long.compareUnsigned(a, end) < 0; ) {
                long size = Obj.sizeOf(sp, a);
                if (size == 0) break;
                if (Obj.ty(sp, a) != Obj.TY_FREE) out.add(a);
                a += size;
            }
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        Arrays.sort(arr);
        return arr;
    }

    private static int indexOf(long[] addrs, long a) {
        int i = Arrays.binarySearch(addrs, a);
        return i < 0 ? -1 : i;
    }

    /// True unless the value pointed at something the collector did not keep.
    ///
    /// That is not a snapshot problem to paper over -- it means the walk and
    /// the collector disagree, which is exactly the failure this design is
    /// meant to make impossible. Refuse, loudly, rather than write a snapshot
    /// with a hole in it.
    private static boolean writeValue(W w, long v, long[] ix) {
        if (Val.isHeap(v)) {
            int i = indexOf(ix, Val.asHeap(v));
            if (i < 0) return false;
            w.u8(V_REF);
            w.u32(i);
            return true;
        }
        w.u8(V_LITERAL);
        w.u64(v);
        return true;
    }

    private static long readValue(R r, long[] map) {
        if (r.u8() == V_REF) return Val.heap(map[r.u32()]);
        return r.u64();
    }

    /// Export the live set. False if the walk and the collector disagreed.
    public static byte[] exportLive(Rt rt) {
        // The collector decides what is live. Everything below only enumerates.
        rt.gc.major(rt.roots);

        long[] ix = liveObjects(rt);
        Space sp = rt.gc.sp;
        W w = new W();
        w.u32(MAGIC_LIVE);
        w.u32(VERSION_LIVE);
        w.u64(rt.fingerprint);

        boolean ok = true;
        w.u32(ix.length);
        for (long a : ix) {
            int t = Obj.ty(sp, a);
            int n = Obj.len(sp, a);
            w.u8(t);
            w.u32(n);
            if (Obj.layoutOf(t) == Obj.VALS) {
                for (int i = 0; i < n; i++) ok &= writeValue(w, Obj.slot(sp, a, i), ix);
            } else {
                // Everything past the standard header, verbatim. A string keeps
                // eight bytes of its own header before its bytes and the
                // collector does not scan them, so copying them raw is what
                // preserves them.
                int body = (int) (Obj.sizeOf(sp, a) - Obj.HDR);
                w.u32(body);
                w.raw(sp.bytes(a + Obj.HDR, body));
            }
        }

        Roots r = rt.roots;
        w.u32(r.stackTop);
        w.u32(r.stackTop);
        for (int i = 0; i < r.stackTop; i++) ok &= writeValue(w, r.stack[i], ix);
        w.u32(r.shadowTop);
        for (int i = 0; i < r.shadowTop; i++) ok &= writeValue(w, r.shadow[i], ix);
        w.u32(r.globals.length);
        for (long v : r.globals) ok &= writeValue(w, v, ix);
        w.u32(r.consts.length);
        for (long v : r.consts) ok &= writeValue(w, v, ix);
        w.u32(r.singletons.length);
        for (long v : r.singletons) ok &= writeValue(w, v, ix);
        w.u32(0);                          // intern tables, as above

        writeVmState(w, rt);
        ok &= writeValue(w, rt.thrown, ix);
        ok &= writeValue(w, rt.parkOn, ix);
        writeTail(w, rt);
        return ok ? w.done() : null;
    }

    /// Import a live-set export. Refuses a different layout or a different
    /// program exactly as `restore` does, and for the same reason: every index
    /// in it means something only against the image it came from.
    public static boolean importLive(Rt rt, byte[] bytes) {
        refused = REFUSE_NONE;
        if (bytes == null || bytes.length < 16) { refused = REFUSE_LAYOUT; return false; }
        R r = new R(bytes, 0);
        if (r.u32() != MAGIC_LIVE || r.u32() != VERSION_LIVE) { refused = REFUSE_LAYOUT; return false; }
        if (r.u64() != rt.fingerprint) { refused = REFUSE_IMAGE; return false; }

        // Nothing of the old state may be reachable while the new objects are
        // being built, or a collection in the middle would try to keep both.
        rt.frames.clear();
        rt.handlers.clear();
        rt.roots.stackTop = 0;
        rt.roots.shadowTop = 0;
        Arrays.fill(rt.roots.globals, Val.NIL);

        // Pass one: allocate every object, EMPTY.
        //
        // The addresses are held on the SHADOW STACK rather than in a Java
        // array, because allocating can collect and a collection MOVES what it
        // has already built. That is not hypothetical: it is
        // `doc/decisions/0031`, and the shadow stack is what makes it a
        // non-question here.
        int n = r.usz();
        int base = rt.mark();
        int[][] bodies = new int[n][];
        int at = r.i;
        for (int i = 0; i < n; i++) {
            int t = r.u8();
            int len = r.u32();
            long a = rt.alloc(t, len);
            if (a == 0) { rt.popTo(base); refused = REFUSE_LAYOUT; return false; }
            rt.push(Val.heap(a));
            // Skip the body; pass two comes back once every address exists.
            if (Obj.layoutOf(t) == Obj.VALS) {
                for (int k = 0; k < len; k++) { if (r.u8() == V_REF) r.u32(); else r.u64(); }
            } else {
                // Into a LOCAL first. `r.i += r.u32()` saves `r.i` BEFORE
                // evaluating the call that advances it (JLS 15.26.2), so the
                // reader lands four bytes short and reads the next object's
                // header as a body length. Same trap as the value stack's
                // `stack[sp++] = f(...)`, and just as quiet: the numbers stay
                // plausible right up to an allocation for 171 MB.
                int body = r.u32();
                r.i += body;
            }
            bodies[i] = new int[]{t, len, at};
            at = r.i;
        }

        // Every address, now that they all exist and nothing more will move
        // them: the objects are all rooted, so the map is taken AFTER the last
        // allocation.
        long[] map = new long[n];
        for (int i = 0; i < n; i++) map[i] = Val.asHeap(rt.r(base + i));

        // Pass two: fill the bodies.
        for (int i = 0; i < n; i++) {
            long a = map[i];
            R rr = new R(bytes, bodies[i][2]);
            rr.u8();                       // the type and length again --
            rr.u32();                      // `at` points AT the record, not past
            int t = bodies[i][0], len = bodies[i][1];
            if (Obj.layoutOf(t) == Obj.VALS) {
                for (int k = 0; k < len; k++) Obj.setSlotRaw(rt.gc.sp, a, k, readValue(rr, map));
            } else {
                int body = rr.u32();
                rt.gc.sp.writeBytes(a + Obj.HDR, Arrays.copyOfRange(bytes, rr.i, rr.i + body));
            }
        }
        // An old object may now point at a young one, and the write barrier was
        // bypassed ON PURPOSE above -- `setSlotRaw` is what makes pass two a
        // fill rather than N barrier calls. Enrol every old object once, here.
        for (long a : map) if (!rt.gc.isYoung(a)) rt.gc.remember(a, rt.roots);

        r = new R(bytes, at);
        int stackTop = r.usz();
        int ns = r.usz();
        long[] stack = new long[ns];
        for (int i = 0; i < ns; i++) stack[i] = readValue(r, map);
        int nsh = r.usz();
        long[] shadow = new long[nsh];
        for (int i = 0; i < nsh; i++) shadow[i] = readValue(r, map);
        int ng = r.usz();
        long[] globals = new long[ng];
        for (int i = 0; i < ng; i++) globals[i] = readValue(r, map);
        int nc = r.usz();
        long[] consts = new long[nc];
        for (int i = 0; i < nc; i++) consts[i] = readValue(r, map);
        int nsg = r.usz();
        long[] singletons = new long[nsg];
        for (int i = 0; i < nsg; i++) singletons[i] = readValue(r, map);
        int nt = r.usz();
        for (int i = 0; i < nt; i++) {
            int cap = r.usz();
            r.usz();
            for (int k = 0; k < cap; k++) { r.u32(); readValue(r, map); }
        }

        readVmState(r, rt);
        long thrown = readValue(r, map);
        long parkOn = readValue(r, map);
        readTail(r, rt);

        rt.popTo(base);
        Roots rr = rt.roots;
        rr.stack = Arrays.copyOf(stack, Math.max(ns, stackTop) + 64);
        rr.stackTop = stackTop;
        rr.shadow = Arrays.copyOf(shadow, Math.max(nsh, 64));
        rr.shadowTop = nsh;
        for (int i = 0; i < globals.length && i < rr.globals.length; i++) rr.globals[i] = globals[i];
        rr.consts = consts;
        rt.consts = consts;
        rr.singletons = singletons;
        rt.thrown = thrown;
        rt.parkOn = parkOn;
        // LAST, and for the same reason as `restore`: the identities come back
        // intact so a host can rehydrate against them.
        rt.restoredCapabilities = countHostOpaques(rt);
        return true;
    }

    /// Leave the sandbox with nothing runnable.
    ///
    /// Not a flag the interpreter has to consult: there is simply nothing left
    /// to run. The frame stack IS the continuation here -- that is what makes
    /// green threads and snapshots cheap in the first place -- so dropping it
    /// is what "stopped" means, and no loop needs a new condition in it.
    ///
    /// The heap is deliberately left alone. It has just been exported, and a
    /// caller that wants the memory back drops the whole instance; a caller
    /// that wants to look at what it shelved still can.
    public static void halt(Rt rt) {
        rt.frames.clear();
        rt.handlers.clear();
        rt.roots.stackTop = 0;
        rt.roots.shadowTop = 0;
        rt.parkOn = Val.NIL;
        rt.thrown = Val.NIL;
        rt.status = STATUS_SHELVED;
    }
}
