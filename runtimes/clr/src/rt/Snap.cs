namespace Flint.Rt;

/// VM snapshots: capture, restore, export, import. Ported verbatim from
/// `runtime/src/snap.rs` (`DECISIONS.md#snapshots`), and a line-for-line mirror of
/// the JVM's `Snap.java`.
///
/// TWO FORMATS, because they answer two different questions and neither one
/// can be made to answer the other's:
///
/// * `Capture`/`Restore` is a MEMCPY. It copies raw bytes of the heap plus the
///   host-side state and interprets LATER, which is what lets it capture a heap
///   that is already corrupt -- the one case a traversal cannot. It is the
///   post-mortem instrument. It also copies dead objects and unused reserve,
///   and pins the restore to identical addresses.
///
/// * `ExportLive`/`ImportLive` TRAVERSES. It is relocatable and dense, which is
///   what shelving needs: rehydrate in another process, another heap, at
///   whatever addresses that heap hands out.
///
/// The objection `snapshots` raises to a traversal -- that one which misses an edge
/// yields a snapshot missing an object, and then the CAPTURE is what needs
/// debugging -- is fatal to a bespoke traversal and not to this one, because
/// **the collector decides what is live and this only enumerates what
/// survived**.
public static class Snap {
    /// "FLSN". Bumped whenever the layout below changes.
    public const int Magic = 0x464C_534E;
    public const int Version = 3;

    /// Why a restore was refused. The call answers a boolean because that is
    /// what the host ABI can carry; this says WHICH of the two checks failed.
    public const int RefuseNone = 0;
    public const int RefuseLayout = 1;
    public const int RefuseImage = 2;
    public static int Refused = RefuseNone;

    /// "FLSX". A different magic from `Magic`, deliberately: the two are not
    /// interchangeable and a reader should not have to guess.
    public const int MagicLive = 0x464C_5358;
    public const int VersionLive = 1;

    /// How a value is written when it may point at the heap. One byte, so the
    /// encoding is unambiguous rather than clever: a NaN-boxed value uses the
    /// very bits an in-band tag would want.
    const int VLiteral = 0;
    const int VRef = 1;

    /// `main` should report "this sandbox was shelved", not "here is your
    /// answer". 0 is a normal return and 2 is "I need the host"
    /// (`DECISIONS.md#threads-and-ports`), so this takes the next free code.
    public const int StatusShelved = 3;

    // -----------------------------------------------------------------------

    sealed class W {
        public byte[] b = new byte[4096];
        public int n;

        void Need(int k) {
            if (n + k > b.Length) {
                int cap = b.Length;
                while (cap < n + k) cap *= 2;
                System.Array.Resize(ref b, cap);
            }
        }
        public void U8(int v) { Need(1); b[n++] = (byte) v; }
        public void U32(int v) {
            Need(4);
            b[n++] = (byte) v; b[n++] = (byte) (v >> 8);
            b[n++] = (byte) (v >> 16); b[n++] = (byte) (v >> 24);
        }
        public void U64(long v) { U32((int) v); U32((int) ((ulong) v >> 32)); }
        /// An ADDRESS, at whatever width an address is. Its own method so the
        /// format has ONE place that decides, rather than a `u32` per field
        /// that would have to be found and changed together.
        public void Addr(long v) => U64(v);
        public void Raw(byte[] src) { Need(src.Length); System.Array.Copy(src, 0, b, n, src.Length); n += src.Length; }
        public void Vals(long[] xs, int len) { U32(len); for (int i = 0; i < len; i++) U64(xs[i]); }
        public byte[] Done() { byte[] o = new byte[n]; System.Array.Copy(b, o, n); return o; }
    }

    sealed class R {
        public readonly byte[] b;
        public int i;
        public R(byte[] b, int i) { this.b = b; this.i = i; }
        public int U8() => b[i++] & 0xFF;
        public int U32() {
            int v = b[i] | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24);
            i += 4;
            return v;
        }
        public long U64() => (U32() & 0xFFFFFFFFL) | ((long) U32() << 32);
        public long Addr() => U64();
        public int Usz() => U32();
        public long[] Vals() {
            int n = Usz();
            long[] o = new long[n];
            for (int k = 0; k < n; k++) o[k] = U64();
            return o;
        }
    }

    // -----------------------------------------------------------------------
    // The verbatim format.

    /// The whole VM state as bytes. No interpretation of the heap at all.
    public static byte[] Capture(Rt rt) {
        W w = new W();
        w.U32(Magic);
        w.U32(Version);
        // The image this state belongs to. NOT the image itself: a snapshot
        // carries the heap and the VM state and no code, which is what keeps it
        // small and is the whole reason it can be moved.
        w.U64(rt.fingerprint);

        // --- allocation geometry, so a restore lands at the SAME addresses.
        // Every pointer in the heap is an absolute offset; restoring elsewhere
        // would mean rewriting them, which is a traversal, which is the thing
        // this format exists to avoid.
        Gc g = rt.gc;
        w.Addr(g.sp.InUse);
        w.Addr(g.sp.Reserved);
        w.Addr(g.youngBase);
        w.Addr(g.half);
        w.Addr(g.from);
        w.Addr(g.to);
        w.Addr(g.toBump);
        w.Addr(g.bump);
        w.Addr(g.fromEnd);
        w.Addr(g.oldCapacity);
        w.Addr(g.oldLive);
        w.Addr(g.maxHeap);
        w.U32(g.collecting ? 1 : 0);
        w.U32(g.oom ? 1 : 0);
        w.U32(g.stress ? 1 : 0);
        w.U32(g.oldChunks.Count);
        foreach (long[] c in g.oldChunks) { w.Addr(c[0]); w.Addr(c[1]); }
        w.U32(g.freeLists.Length);
        foreach (long v in g.freeLists) w.Addr(v);
        // The remembered set as a LIST. The per-object FLAGS travel in the heap
        // bytes below, in each object's header. Both are captured and neither
        // is derived, because an investigation turns on their disagreeing.
        w.U32(rt.roots.Remembered.Count);
        foreach (long a in rt.roots.Remembered) w.Addr(a);
        w.U64(g.minors);
        w.U64(g.majors);
        w.U64(g.bytesAllocated);
        w.U64(g.bytesCopied);
        w.U64(g.bytesPromoted);
        w.U64(g.peakLive);

        // --- roots
        Roots r = rt.roots;
        w.U32(r.StackTop);
        w.Vals(r.Stack, r.StackTop);
        w.Vals(r.Shadow, r.ShadowTop);
        w.Vals(r.shared.Globals, r.shared.Globals.Length);
        w.Vals(r.shared.Consts, r.shared.Consts.Length);
        w.Vals(r.shared.Singletons, r.shared.Singletons.Length);
        // The intern tables. This runtime does not carry them yet, so the count
        // is 0 -- the SECTION is here, in position, so adding them later does
        // not move any field that follows.
        w.U32(0);

        // --- interpreter state
        WriteVmState(w, rt);
        w.U64(rt.thrown);
        w.U64(rt.parkOn);
        WriteTail(w, rt);

        // --- the heap itself, verbatim, REGION BY REGION. No interpretation.
        //
        // Not one contiguous range: an old chunk can sit far above `InUse`, and
        // a capture that assumed contiguity would silently miss it -- exactly
        // the "answers some questions confidently wrong" failure this design
        // exists to prevent.
        List<long[]> regions = new List<long[]>();
        regions.Add(new long[]{g.youngBase, g.half * 2});
        foreach (long[] c in g.oldChunks) regions.Add(new long[]{c[0], c[1]});
        w.U32(regions.Count);
        foreach (long[] reg in regions) {
            w.Addr(reg[0]);
            w.Addr(reg[1]);
            w.Raw(g.sp.Bytes(reg[0], (int) reg[1]));
        }
        return w.Done();
    }

    /// The fields that are neither heap nor roots, in the order both formats
    /// write them. Shared so the two cannot drift apart -- a frame read at the
    /// wrong offset is still a number.
    static void WriteVmState(W w, Rt rt) {
        w.U32(rt.frames.Count);
        foreach (Frame f in rt.frames) {
            w.U32(f.Fp); w.U32(f.Ip); w.U32(f.End); w.U32(f.RetTo); w.U32(f.Handlers);
        }
        w.U32(rt.handlers.Count);
        foreach (Rt.Handler h in rt.handlers) {
            w.U32(h.frame); w.U32(h.stackTop); w.U32(h.target); w.U32(h.shadow);
        }
    }

    static void ReadVmState(R r, Rt rt) {
        int nf = r.Usz();
        rt.frames.Clear();
        for (int i = 0; i < nf; i++) {
            Frame f = new Frame();
            f.Fp = r.Usz(); f.Ip = r.U32(); f.End = r.U32(); f.RetTo = r.Usz(); f.Handlers = r.Usz();
            rt.frames.Add(f);
        }
        int nh = r.Usz();
        rt.handlers.Clear();
        for (int i = 0; i < nh; i++) {
            Rt.Handler h = new Rt.Handler();
            h.frame = r.Usz(); h.stackTop = r.Usz(); h.target = r.U32(); h.shadow = r.Usz();
            rt.handlers.Add(h);
        }
    }

    static void WriteTail(W w, Rt rt) {
        w.U64(rt.steps);
        w.U64(rt.gasLimit);
        w.U64(rt.sliceEnd);
        w.U64(rt.checkpoint);
        w.U32(rt.gasTrips);
        w.U32(rt.memTrips);
        w.U32(rt.status);
        w.U32(rt.champAdded ? 1 : 0);
    }

    static void ReadTail(R r, Rt rt) {
        rt.steps = r.U64();
        rt.gasLimit = r.U64();
        rt.sliceEnd = r.U64();
        rt.checkpoint = r.U64();
        rt.gasTrips = r.U32();
        rt.memTrips = r.U32();
        rt.status = r.U32();
        rt.champAdded = r.U32() != 0;
    }

    public static bool Restore(Rt rt, byte[] bytes) {
        Refused = RefuseNone;
        if (bytes == null || bytes.Length < 16) { Refused = RefuseLayout; return false; }
        R r = new R(bytes, 0);
        if (r.U32() != Magic || r.U32() != Version) { Refused = RefuseLayout; return false; }
        if (r.U64() != rt.fingerprint) { Refused = RefuseImage; return false; }

        long inUse = r.Addr();
        r.Addr();                       // reserved: this space's own is the bound
        Gc g = rt.gc;
        g.youngBase = r.Addr();
        g.half = r.Addr();
        g.from = r.Addr();
        g.to = r.Addr();
        g.toBump = r.Addr();
        g.bump = r.Addr();
        g.fromEnd = r.Addr();
        g.oldCapacity = r.Addr();
        g.oldLive = r.Addr();
        g.maxHeap = r.Addr();
        g.collecting = r.U32() != 0;
        g.oom = r.U32() != 0;
        g.stress = r.U32() != 0;
        int nch = r.Usz();
        g.oldChunks.Clear();
        for (int i = 0; i < nch; i++) {
            // Into LOCALS first, and in this order. C# fixes argument
            // evaluation left to right, but an array initialiser with two calls
            // that both advance the reader is exactly the shape that is a bug
            // in Java, so it is written out rather than relied on.
            long addr = r.Addr();
            long len = r.Addr();
            g.oldChunks.Add(new long[]{addr, len});
        }
        int nfl = r.Usz();
        for (int i = 0; i < nfl; i++) {
            long v = r.Addr();
            if (i < g.freeLists.Length) g.freeLists[i] = v;
        }
        int nrem = r.Usz();
        rt.roots.Remembered.Clear();
        for (int i = 0; i < nrem; i++) rt.roots.Remembered.Add(r.Addr());
        g.minors = r.U64();
        g.majors = r.U64();
        g.bytesAllocated = r.U64();
        g.bytesCopied = r.U64();
        g.bytesPromoted = r.U64();
        g.peakLive = r.U64();

        int stackTop = r.Usz();
        long[] stack = r.Vals();
        long[] shadow = r.Vals();
        long[] globals = r.Vals();
        long[] consts = r.Vals();
        long[] singletons = r.Vals();
        int nt = r.Usz();
        for (int i = 0; i < nt; i++) {      // no intern tables here yet
            int cap = r.Usz();
            r.Usz();
            for (int k = 0; k < cap; k++) { r.U32(); r.U64(); }
        }

        ReadVmState(r, rt);
        long thrown = r.U64();
        long parkOn = r.U64();
        ReadTail(r, rt);

        // Regions, blitted back to the SAME addresses. Relocating would mean
        // rewriting every pointer, which is a traversal, which is what this
        // format avoids -- `ImportLive` is the one that relocates.
        int nreg = r.Usz();
        long[][] plan = new long[nreg][];
        for (int i = 0; i < nreg; i++) {
            long addr = r.Addr();
            long len = r.Addr();
            if (r.i + len > bytes.Length) { Refused = RefuseLayout; return false; }
            plan[i] = new long[]{addr, len, r.i};
            r.i += (int) len;
        }
        // Refuse rather than write garbage: if this runtime's space does not
        // already cover a region, its addresses mean something else here.
        foreach (long[] p in plan) {
            if (p[0] + p[1] > g.sp.Reserved) { Refused = RefuseLayout; return false; }
        }
        g.sp.InUse = inUse;
        foreach (long[] p in plan) {
            byte[] chunk = new byte[p[1]];
            System.Array.Copy(bytes, (int) p[2], chunk, 0, (int) p[1]);
            g.sp.WriteBytes(p[0], chunk);
        }

        Roots rr = rt.roots;
        if (rr.Stack.Length < stack.Length + 8) rr.Stack = new long[stack.Length + 8];
        System.Array.Copy(stack, rr.Stack, stack.Length);
        rr.StackTop = stackTop;
        if (rr.Shadow.Length < shadow.Length + 8) rr.Shadow = new long[shadow.Length + 8];
        System.Array.Copy(shadow, rr.Shadow, shadow.Length);
        rr.ShadowTop = shadow.Length;
        rr.shared.Globals = globals;
        rr.shared.Consts = consts;
        rt.consts = consts;
        rr.shared.Singletons = singletons;
        rt.thrown = thrown;
        rt.parkOn = parkOn;
        // LAST, after the heap is in place. Identities are PRESERVED; whether
        // any of them still means anything is the host's grant table to answer.
        rt.restoredHostOpaques = CountHostOpaques(rt);
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
    /// The check belongs where `DECISIONS.md#opaque-values` always said it belongs --
    /// the GRANT TABLE, not possession.
    static int CountHostOpaques(Rt rt) {
        Space sp = rt.gc.sp;
        int n = 0;
        for (long a = rt.gc.from; (ulong) a < (ulong) rt.gc.bump; ) {
            long size = Obj.SizeOf(sp, a);
            if (size < 8) break;
            if (Obj.Ty(sp, a) == Obj.TyOpaque) n++;
            a += size;
        }
        foreach (long[] ch in rt.gc.oldChunks) {
            long end = ch[0] + ch[1];
            for (long a = ch[0]; (ulong) a < (ulong) end; ) {
                long size = Obj.SizeOf(sp, a);
                if (size < 8 || a + size > end) break;
                if (Obj.Ty(sp, a) != Obj.TyFree && Obj.Ty(sp, a) == Obj.TyOpaque) n++;
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
    static long[] LiveObjects(Rt rt) {
        Space sp = rt.gc.sp;
        List<long> outl = new List<long>();
        // The nursery is contiguous: a copying minor leaves exactly the
        // survivors between `from` and `bump`.
        for (long a = rt.gc.from; (ulong) a < (ulong) rt.gc.bump; ) {
            long size = Obj.SizeOf(sp, a);
            if (size == 0) break;
            outl.Add(a);
            a += size;
        }
        // Old space is swept, so the holes are `TY_FREE` and everything else is
        // live.
        foreach (long[] ch in rt.gc.oldChunks) {
            long end = ch[0] + ch[1];
            for (long a = ch[0]; (ulong) a < (ulong) end; ) {
                long size = Obj.SizeOf(sp, a);
                if (size == 0) break;
                if (Obj.Ty(sp, a) != Obj.TyFree) outl.Add(a);
                a += size;
            }
        }
        long[] arr = outl.ToArray();
        System.Array.Sort(arr);
        return arr;
    }

    static int IndexOf(long[] addrs, long a) {
        int i = System.Array.BinarySearch(addrs, a);
        return i < 0 ? -1 : i;
    }

    /// True unless the value pointed at something the collector did not keep.
    ///
    /// That is not a snapshot problem to paper over -- it means the walk and
    /// the collector disagree, which is exactly the failure this design is
    /// meant to make impossible. Refuse, loudly, rather than write a snapshot
    /// with a hole in it.
    static bool WriteValue(W w, long v, long[] ix) {
        if (Val.IsHeap(v)) {
            int i = IndexOf(ix, Val.AsHeap(v));
            if (i < 0) return false;
            w.U8(VRef);
            w.U32(i);
            return true;
        }
        w.U8(VLiteral);
        w.U64(v);
        return true;
    }

    static long ReadValue(R r, long[] map) {
        if (r.U8() == VRef) return Val.Heap(map[r.U32()]);
        return r.U64();
    }

    /// Export the live set. Null if the walk and the collector disagreed.
    public static byte[] ExportLive(Rt rt) {
        // The collector decides what is live. Everything below only enumerates.
        rt.gc.Major(rt.roots);

        long[] ix = LiveObjects(rt);
        Space sp = rt.gc.sp;
        W w = new W();
        w.U32(MagicLive);
        w.U32(VersionLive);
        w.U64(rt.fingerprint);

        bool ok = true;
        w.U32(ix.Length);
        foreach (long a in ix) {
            int t = Obj.Ty(sp, a);
            int n = Obj.Len(sp, a);
            w.U8(t);
            w.U32(n);
            if (Obj.LayoutOf(t) == Obj.LVals) {
                for (int i = 0; i < n; i++) ok &= WriteValue(w, Obj.Slot(sp, a, i), ix);
            } else {
                // Everything past the standard header, verbatim. A string keeps
                // eight bytes of its own header before its bytes and the
                // collector does not scan them, so copying them raw is what
                // preserves them.
                int body = (int) (Obj.SizeOf(sp, a) - Obj.Hdr);
                w.U32(body);
                w.Raw(sp.Bytes(a + Obj.Hdr, body));
            }
        }

        Roots r = rt.roots;
        w.U32(r.StackTop);
        w.U32(r.StackTop);
        for (int i = 0; i < r.StackTop; i++) ok &= WriteValue(w, r.Stack[i], ix);
        w.U32(r.ShadowTop);
        for (int i = 0; i < r.ShadowTop; i++) ok &= WriteValue(w, r.Shadow[i], ix);
        w.U32(r.shared.Globals.Length);
        foreach (long v in r.shared.Globals) ok &= WriteValue(w, v, ix);
        w.U32(r.shared.Consts.Length);
        foreach (long v in r.shared.Consts) ok &= WriteValue(w, v, ix);
        w.U32(r.shared.Singletons.Length);
        foreach (long v in r.shared.Singletons) ok &= WriteValue(w, v, ix);
        w.U32(0);                          // intern tables, as above

        WriteVmState(w, rt);
        ok &= WriteValue(w, rt.thrown, ix);
        ok &= WriteValue(w, rt.parkOn, ix);
        WriteTail(w, rt);
        return ok ? w.Done() : null;
    }

    /// Import a live-set export. Refuses a different layout or a different
    /// program exactly as `Restore` does, and for the same reason: every index
    /// in it means something only against the image it came from.
    public static bool ImportLive(Rt rt, byte[] bytes) {
        Refused = RefuseNone;
        if (bytes == null || bytes.Length < 16) { Refused = RefuseLayout; return false; }
        R r = new R(bytes, 0);
        if (r.U32() != MagicLive || r.U32() != VersionLive) { Refused = RefuseLayout; return false; }
        if (r.U64() != rt.fingerprint) { Refused = RefuseImage; return false; }

        // Nothing of the old state may be reachable while the new objects are
        // being built, or a collection in the middle would try to keep both.
        rt.frames.Clear();
        rt.handlers.Clear();
        rt.roots.StackTop = 0;
        rt.roots.ShadowTop = 0;
        System.Array.Fill(rt.roots.shared.Globals, Val.Nil);

        // Pass one: allocate every object, EMPTY.
        //
        // The addresses are held on the SHADOW STACK rather than in a host
        // array, because allocating can collect and a collection MOVES what it
        // has already built. That is not hypothetical: it is
        // `DECISIONS.md#a-vec-of-values-is-not-a-root`, and the shadow stack is what makes it a
        // non-question here.
        int n = r.Usz();
        int bas = rt.Mark();
        int[][] bodies = new int[n][];
        int at = r.i;
        for (int i = 0; i < n; i++) {
            int t = r.U8();
            int len = r.U32();
            long a = rt.Alloc(t, len);
            if (a == 0) { rt.PopTo(bas); Refused = RefuseLayout; return false; }
            rt.Push(Val.Heap(a));
            // Skip the body; pass two comes back once every address exists.
            if (Obj.LayoutOf(t) == Obj.LVals) {
                for (int k = 0; k < len; k++) { if (r.U8() == VRef) r.U32(); else r.U64(); }
            } else {
                // Into a LOCAL first. `r.i += r.U32()` reads `r.i` BEFORE the
                // call that advances it in Java (JLS 15.26.2), and the JVM port
                // landed four bytes short and read the next object's header as
                // a body length. C# evaluates the same expression correctly,
                // but the two ports stay written the same way so a reader
                // comparing them is not left wondering which is which.
                int body = r.U32();
                r.i += body;
            }
            bodies[i] = new int[]{t, len, at};
            at = r.i;
        }

        // Every address, now that they all exist and nothing more will move
        // them: the objects are all rooted, so the map is taken AFTER the last
        // allocation.
        long[] map = new long[n];
        for (int i = 0; i < n; i++) map[i] = Val.AsHeap(rt.R(bas + i));

        // Pass two: fill the bodies.
        for (int i = 0; i < n; i++) {
            long a = map[i];
            R rr = new R(bytes, bodies[i][2]);
            rr.U8();                       // the type and length again --
            rr.U32();                      // `at` points AT the record, not past
            int t = bodies[i][0], len = bodies[i][1];
            if (Obj.LayoutOf(t) == Obj.LVals) {
                for (int k = 0; k < len; k++) Obj.SetSlotRaw(rt.gc.sp, a, k, ReadValue(rr, map));
            } else {
                int body = rr.U32();
                byte[] chunk = new byte[body];
                System.Array.Copy(bytes, rr.i, chunk, 0, body);
                rt.gc.sp.WriteBytes(a + Obj.Hdr, chunk);
            }
        }
        // An old object may now point at a young one, and the write barrier was
        // bypassed ON PURPOSE above -- `SetSlotRaw` is what makes pass two a
        // fill rather than N barrier calls. Enrol every old object once, here.
        foreach (long a in map) if (!rt.gc.IsYoung(a)) rt.gc.Remember(a, rt.roots);

        r = new R(bytes, at);
        int stackTop = r.Usz();
        int ns = r.Usz();
        long[] stack = new long[ns];
        for (int i = 0; i < ns; i++) stack[i] = ReadValue(r, map);
        int nsh = r.Usz();
        long[] shadow = new long[nsh];
        for (int i = 0; i < nsh; i++) shadow[i] = ReadValue(r, map);
        int ng = r.Usz();
        long[] globals = new long[ng];
        for (int i = 0; i < ng; i++) globals[i] = ReadValue(r, map);
        int nc = r.Usz();
        long[] consts = new long[nc];
        for (int i = 0; i < nc; i++) consts[i] = ReadValue(r, map);
        int nsg = r.Usz();
        long[] singletons = new long[nsg];
        for (int i = 0; i < nsg; i++) singletons[i] = ReadValue(r, map);
        int nt = r.Usz();
        for (int i = 0; i < nt; i++) {
            int cap = r.Usz();
            r.Usz();
            for (int k = 0; k < cap; k++) { r.U32(); ReadValue(r, map); }
        }

        ReadVmState(r, rt);
        long thrown = ReadValue(r, map);
        long parkOn = ReadValue(r, map);
        ReadTail(r, rt);

        rt.PopTo(bas);
        Roots rr2 = rt.roots;
        rr2.Stack = new long[System.Math.Max(ns, stackTop) + 64];
        System.Array.Copy(stack, rr2.Stack, ns);
        rr2.StackTop = stackTop;
        rr2.Shadow = new long[System.Math.Max(nsh, 64)];
        System.Array.Copy(shadow, rr2.Shadow, nsh);
        rr2.ShadowTop = nsh;
        for (int i = 0; i < globals.Length && i < rr2.shared.Globals.Length; i++) rr2.shared.Globals[i] = globals[i];
        rr2.shared.Consts = consts;
        rt.consts = consts;
        rr2.shared.Singletons = singletons;
        rt.thrown = thrown;
        rt.parkOn = parkOn;
        // LAST, and for the same reason as `Restore`: the identities come back
        // intact so a host can rehydrate against them.
        rt.restoredHostOpaques = CountHostOpaques(rt);
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
    /// caller that wants the memory back drops the whole instance.
    public static void Halt(Rt rt) {
        rt.frames.Clear();
        rt.handlers.Clear();
        rt.roots.StackTop = 0;
        rt.roots.ShadowTop = 0;
        rt.parkOn = Val.Nil;
        rt.thrown = Val.Nil;
        rt.status = StatusShelved;
    }
}
