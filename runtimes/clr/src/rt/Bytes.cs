namespace Flint.Rt;


/// Byte strings, ported from `runtime/src/bytes.rs` (`doc/decisions/0024`).
///
/// FLAT is a contiguous `Obj.TyBytes`; ROPE is a shallow B-tree of byte pieces
/// with structure sharing, so concatenation is a tree join and a slice of a
/// large range shares subtrees.
///
/// ## Why this is not a vector of integers
///
/// Because that was the obvious port and it is wrong by an order of magnitude.
/// A flint vector holds NaN-boxed 64-bit values, so a byte would cost eight
/// bytes plus trie overhead: the 574 KB wasm module this exists to let flint
/// read would become 4.6 MB of payload. A byte string holds a byte in a byte.
///
/// ## The three numbers
///
/// Below `FLAT_MAX` a tree costs more in metadata than the copy it saves;
/// `FANOUT` keeps depth low because depth is what random access pays; and the
/// tail of a transient is exactly `FLAT_MAX`, so a promoted tail is the
/// smallest piece that makes a concat build a node instead of copying.
public static class Bytes {

    public const int FLAT_MAX = 1024;
    public const int FANOUT = 16;

    /// Total byte length of this node's subtree.
    public const int BB_BYTES = 0;
    /// A cached flattening, or NIL. Materialising a rope repeatedly is the
    /// failure `0011` names -- count the flattens, do not hope about them.
    public const int BB_FLAT = 1;
    /// The subtree's depth: 1 for a node whose children are all leaves, and one
    /// more per level. EVERY CHILD OF A NODE HAS THE SAME DEPTH, which is what
    /// makes this a B-tree rather than a spine.
    public const int BB_DEPTH = 2;
    /// The subtree's content hash, or nil until asked -- the mirror of
    /// `RP_HASH`, and for the same reason.
    public const int BB_HASH = 3;
    public const int BB_KIDS = 4;

        public static bool IsBytes(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Bytecore.IsBytes(rt, v); }

        public static int Count(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Bytecore.BCount(rt, v); }

    public static long Of(Rt rt, byte[] b) {
        long a = rt.Alloc(Obj.TyBytes, b.Length);
        if (a == 0) return Val.Nil;
        rt.gc.sp.WriteBytes(a + Obj.Hdr, b);
        return Val.Heap(a);
    }

    static void Append(Rt rt, long v, List<byte[]> outv) {
        if (!Val.IsHeap(v)) return;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        if (t == Obj.TyBytes) {
            outv.Add(rt.gc.sp.Bytes(Val.AsHeap(v) + Obj.Hdr, Obj.Len(rt.gc.sp, Val.AsHeap(v))));
        } else if (t == Obj.TyBrope) {
            long flat = rt.Slot(v, BB_FLAT);
            if (!Val.IsNil(flat)) { Append(rt, flat, outv); return; }
            int n = Obj.Len(rt.gc.sp, Val.AsHeap(v)) - BB_KIDS;
            for (int i = 0; i < n; i++) Append(rt, rt.Slot(v, BB_KIDS + i), outv);
        }
    }

    public static byte[] ToArray(Rt rt, long v) {
        List<byte[]> parts = new List<byte[]>();
        Append(rt, v, parts);
        int total = 0;
        foreach (byte[] p in parts) total += p.Length;
        byte[] outb = new byte[total];
        int at = 0;
        foreach (byte[] p in parts) { System.Array.Copy(p, 0, outb, at, p.Length); at += p.Length; }
        return outb;
    }

    /// The byte at `i`, or -1. Descends rather than flattening, which is why
    /// depth is what random access pays and why `FANOUT` is 16.
    public static int At(Rt rt, long v, int i) {
        long cur = v;
        int idx = i;
        for (;;) {
            if (!Val.IsHeap(cur)) return -1;
            int t = Obj.Ty(rt.gc.sp, Val.AsHeap(cur));
            if (t == Obj.TyBytes) {
                int leafN = Obj.Len(rt.gc.sp, Val.AsHeap(cur));
                return (idx < 0 || idx >= leafN) ? -1 : rt.gc.sp.ReadU8(Val.AsHeap(cur) + Obj.Hdr + idx);
            }
            if (t != Obj.TyBrope) return -1;
            int n = Obj.Len(rt.gc.sp, Val.AsHeap(cur)) - BB_KIDS;
            int pos = 0;
            long next = Val.Nil;
            for (int k = 0; k < n; k++) {
                long child = rt.Slot(cur, BB_KIDS + k);
                int cn = Count(rt, child);
                if (idx < pos + cn) { next = child; idx -= pos; break; }
                pos += cn;
            }
            if (Val.IsNil(next)) return -1;
            cur = next;
        }
    }

        public static int Depth(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Bytecore.BDepth(rt, v); }

    /// A leaf joining a deeper node is PROMOTED rather than sitting beside
    /// subtrees: a node's children must all be the same depth.
    static long WrapTo(Rt rt, long v, int d) { return global::_3sln.Flint.Kgen.Rt.Bytenode.BWrapTo(rt, v, d); }

    /// A node over the `n` values ALREADY ROOTED at `bas`. The caller pushes
    /// and the caller pops -- see the Rust and Java copies, which say the same.
    static long Node(Rt rt, int bas, int n) { return global::_3sln.Flint.Kgen.Rt.Bytenode.BNode(rt, bas, n); }

    static long CopyConcat(Rt rt, long a, long b) {
        byte[] x = ToArray(rt, a), y = ToArray(rt, b);
        byte[] both = new byte[x.Length + y.Length];
        System.Array.Copy(x, 0, both, 0, x.Length);
        System.Array.Copy(y, 0, both, x.Length, y.Length);
        return Of(rt, both);
    }

    public static long Concat(Rt rt, long a, long b) {
        if (Count(rt, a) == 0) return b;
        if (Count(rt, b) == 0) return a;
        if (Count(rt, a) + Count(rt, b) <= FLAT_MAX) {
            // Below the threshold a tree costs more in metadata than the copy
            // saves. This tier must not be skipped -- and it is also what makes
            // incremental building quadratic, which is what the transient is for.
            return CopyConcat(rt, a, b);
        }
        int bas = rt.Mark();
        int ai = rt.Push(a), bi = rt.Push(b);
        long outv;
        // First: a small `b` is merged into the RIGHTMOST LEAF rather than
        // given a leaf of its own. Without this, appending a byte at a time
        // makes one heap object per byte and rebuilds the spine each time.
        // Merging bounds the leaf count at `total / FLAT_MAX`.
        if (Count(rt, rt.R(bi)) <= FLAT_MAX / 2) {
            long merged = MergeRight(rt, rt.R(ai), rt.R(bi));
            if (!Val.IsNil(merged)) { rt.PopTo(bas); return merged; }
        }
        // Then: push `b` down the RIGHT SPINE into the deepest node with room.
        // Absorbing at the TOP instead builds a left spine -- the top fills
        // after sixteen joins, wraps, and depth grows by one every sixteen.
        // Twenty thousand joins was depth 1,250, and the recursive walk ran the
        // shadow stack off the end. Descending first keeps it a B-tree: twenty
        // thousand leaves is depth four.
        long absorbed = Absorb(rt, rt.R(ai), rt.R(bi));
        if (!Val.IsNil(absorbed)) { rt.PopTo(bas); return absorbed; }
        // Neither side had room, so a new level. BOTH sides are promoted to the
        // same depth first: a node reads its depth off child zero, so pairing a
        // deep node with a bare leaf makes the node claim a depth one child
        // does not have, and later appends descend into the wrong place.
        int d = System.Math.Max(Depth(rt, rt.R(ai)), Depth(rt, rt.R(bi)));
        int pa = rt.Push(WrapTo(rt, rt.R(ai), d));
        int pb = rt.Push(WrapTo(rt, rt.R(bi), d));
        outv = Node(rt, pa, 2);
        rt.PopTo(bas);
        return outv;
    }

    /// `a`'s rightmost leaf followed by `b`, if the two fit in one leaf. NIL if
    /// they do not, or if there is no leaf to merge into.
    static long MergeRight(Rt rt, long a, long b) {
        if (!Val.IsHeap(a)) return Val.Nil;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(a));
        if (t == Obj.TyBytes) {
            return Obj.Len(rt.gc.sp, Val.AsHeap(a)) + Count(rt, b) <= FLAT_MAX
                 ? CopyConcat(rt, a, b) : Val.Nil;
        }
        if (t != Obj.TyBrope) return Val.Nil;
        int n = Obj.Len(rt.gc.sp, Val.AsHeap(a)) - BB_KIDS;
        int bas = rt.Mark();
        int ai = rt.Push(a), bi = rt.Push(b);
        long merged = MergeRight(rt, rt.Slot(rt.R(ai), BB_KIDS + n - 1), rt.R(bi));
        if (Val.IsNil(merged)) { rt.PopTo(bas); return Val.Nil; }
        int mi = rt.Push(merged);
        int kbase = rt.Mark();
        for (int i = 0; i < n - 1; i++) rt.Push(rt.Slot(rt.R(ai), BB_KIDS + i));
        rt.Push(rt.R(mi));
        long outv = Node(rt, kbase, n);
        rt.PopTo(bas);
        return outv;
    }

    /// Put `b` in the deepest node on `a`'s right spine that has room, or NIL.
    /// Depth is UNCHANGED when this succeeds, which is the whole point.
    static long Absorb(Rt rt, long a, long b) {
        if (!Val.IsHeap(a) || Obj.Ty(rt.gc.sp, Val.AsHeap(a)) != Obj.TyBrope) return Val.Nil;
        int n = Obj.Len(rt.gc.sp, Val.AsHeap(a)) - BB_KIDS;
        int da = Depth(rt, a);
        // A node's children are all the same depth, so anything as deep as this
        // node cannot go inside it.
        if (Depth(rt, b) >= da) return Val.Nil;
        int bas = rt.Mark();
        int ai = rt.Push(a), bi = rt.Push(b);
        // Deepest FIRST: only if the last child cannot take it does this node
        // take it, and only if neither can does the caller wrap.
        long down = Absorb(rt, rt.Slot(rt.R(ai), BB_KIDS + n - 1), rt.R(bi));
        if (!Val.IsNil(down)) {
            int di = rt.Push(down);
            int kbase = rt.Mark();
            for (int i = 0; i < n - 1; i++) rt.Push(rt.Slot(rt.R(ai), BB_KIDS + i));
            rt.Push(rt.R(di));
            long outv = Node(rt, kbase, n);
            rt.PopTo(bas);
            return outv;
        }
        if (n < FANOUT) {
            // Promoted to this node's child depth, so every child stays the
            // same depth and the next append can descend into it.
            int wi = rt.Push(WrapTo(rt, rt.R(bi), da - 1));
            int kbase = rt.Mark();
            for (int i = 0; i < n; i++) rt.Push(rt.Slot(rt.R(ai), BB_KIDS + i));
            rt.Push(rt.R(wi));
            long outv = Node(rt, kbase, n + 1);
            rt.PopTo(bas);
            return outv;
        }
        rt.PopTo(bas);
        return Val.Nil;
    }

    /// A contiguous copy, CACHED on the node so a second walk is free.
    public static long Flatten(Rt rt, long v) {
        if (!Val.IsHeap(v)) return v;
        if (Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyBytes) return v;
        long cached = rt.Slot(v, BB_FLAT);
        if (!Val.IsNil(cached)) return cached;
        byte[] all = ToArray(rt, v);
        int bas = rt.Mark();
        int vi = rt.Push(v);
        long flat = Of(rt, all);
        if (Val.IsHeap(flat)) rt.SetSlot(Val.AsHeap(rt.R(vi)), BB_FLAT, flat);
        rt.PopTo(bas);
        return flat;
    }

    /// Append only `[from, to)`, descending rather than materialising. A
    /// subtree entirely outside the range is skipped whole, which is what makes
    /// a slice cost the size of the SLICE. Flattening first was correct and
    /// quadratic in the caller: tree-shaking slices a thousand function bodies
    /// outv of one 509 KB code section, and materialising it per slice is half a
    /// gigabyte of copying.
    static void AppendRange(Rt rt, long v, int from, int to, List<byte[]> outv) {
        if (!Val.IsHeap(v) || from >= to) return;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        if (t == Obj.TyBytes) {
            int leafN = Obj.Len(rt.gc.sp, Val.AsHeap(v));
            int hi = System.Math.Min(to, leafN), lo = System.Math.Min(from, hi);
            if (hi > lo) outv.Add(rt.gc.sp.Bytes(Val.AsHeap(v) + Obj.Hdr + lo, hi - lo));
            return;
        }
        if (t != Obj.TyBrope) return;
        long flat = rt.Slot(v, BB_FLAT);
        if (!Val.IsNil(flat)) { AppendRange(rt, flat, from, to, outv); return; }
        int n = Obj.Len(rt.gc.sp, Val.AsHeap(v)) - BB_KIDS;
        int pos = 0;
        for (int i = 0; i < n && pos < to; i++) {
            long k = rt.Slot(v, BB_KIDS + i);
            int kn = Count(rt, k);
            if (pos + kn > from) {
                AppendRange(rt, k, System.Math.Max(from - pos, 0), System.Math.Min(to - pos, kn), outv);
            }
            pos += kn;
        }
    }

    public static bool IsBrope(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Bytecore.IsBrope(rt, v);

    /// SHARES, like `Str.RopeSlice`. This descended to the range and then
    /// COPIED it (`doc/decisions/0011`).
    public static long Slice(Rt rt, long v, int from, int to) {
        int n = Count(rt, v);
        int lo = System.Math.Min(System.Math.Max(from, 0), n);
        int hi = System.Math.Min(System.Math.Max(to, lo), n);
        if (lo == 0 && hi == n) return v;
        if (hi - lo < Str.SLICE_MIN || !IsBrope(rt, v)) {
            List<byte[]> parts = new List<byte[]>();
            AppendRange(rt, v, lo, hi, parts);
            int total = 0;
            foreach (byte[] p in parts) total += p.Length;
            byte[] outb = new byte[total];
            int at0 = 0;
            foreach (byte[] p in parts) { System.Array.Copy(p, 0, outb, at0, p.Length); at0 += p.Length; }
            return Of(rt, outb);
        }
        int bas = rt.Mark();
        int vi = rt.Push(v);
        int kids = Obj.Len(rt.gc.sp, Val.AsHeap(rt.R(vi))) - BB_KIDS;
        int outb2 = rt.Mark();
        int made = 0, at = 0;
        for (int i = 0; i < kids; i++) {
            long k = rt.Slot(rt.R(vi), BB_KIDS + i);
            int w = Count(rt, k);
            if (at + w > lo && at < hi) {
                int l2 = System.Math.Max(0, lo - at);
                int h2 = System.Math.Min(hi - at, w);
                long piece = (l2 == 0 && h2 == w) ? k : Slice(rt, k, l2, h2);
                if (piece == Val.Nil) { rt.PopTo(bas); return Val.Nil; }
                if (Count(rt, piece) > 0) { rt.Push(piece); made++; }
            }
            at += w;
            if (at >= hi) break;
        }
        long r = FromRoots(rt, outb2, made);
        rt.PopTo(bas);
        return r;
    }

    static long FromRoots(Rt rt, int bas, int n) {
        if (n == 0) return Of(rt, new byte[0]);
        if (n == 1) return rt.R(bas);
        int level = n, from = bas;
        while (true) {
            if (level == 1) return rt.R(from);
            int outb = rt.Mark();
            int made = 0, i = 0;
            while (i < level) {
                int take = System.Math.Min(Str.FANOUT, level - i);
                long nd = Node(rt, from + i, take);
                if (nd == Val.Nil) return Val.Nil;
                rt.Push(nd);
                made++;
                i += take;
            }
            from = outb;
            level = made;
        }
    }

    /// The mirror of `Str.RopeHash`, cached per node.
    public static int Hash(Rt rt, long v) {
        if (!IsBrope(rt, v)) {
            byte[] bs = ToArray(rt, v);
            int h0 = 0;
            foreach (byte b in bs) h0 = h0 * 31 + b;
            return h0;
        }
        long cached = rt.Slot(v, BB_HASH);
        if (Val.IsFixnum(cached)) return (int) Val.AsFixnum(cached);
        int bas = rt.Mark();
        int vi = rt.Push(v);
        int kids = Obj.Len(rt.gc.sp, Val.AsHeap(rt.R(vi))) - BB_KIDS;
        int h = 0;
        for (int i = 0; i < kids; i++) {
            long k = rt.Slot(rt.R(vi), BB_KIDS + i);
            int ki = rt.Push(k);
            h = h * Str.Pow31Public(Count(rt, rt.R(ki))) + Hash(rt, rt.R(ki));
            rt.PopTo(ki);
        }
        rt.SetSlot(Val.AsHeap(rt.R(vi)), BB_HASH, Val.Fixnum(h));
        rt.PopTo(bas);
        return h;
    }

    /// Content equality without materialising either side, short-circuiting on
    /// NODE IDENTITY -- which matters now that `Slice` shares.
    public static bool Eq(Rt rt, long a, long b) {
        if (a == b) return true;
        if (Count(rt, a) != Count(rt, b)) return false;
        var sa = new List<long[]>(); var sb = new List<long[]>();
        sa.Add(new long[]{a, 0}); sb.Add(new long[]{b, 0});
        byte[] la = System.Array.Empty<byte>(), lb = System.Array.Empty<byte>();
        int pa = 0, pb = 0;
        while (true) {
            if (pa == la.Length) {
                long nv = WalkNext(rt, sa);
                if (nv == Val.NotFound) break;
                if (pb == lb.Length && sb.Count > 0) {
                    var peek = new List<long[]>();
                    foreach (var e in sb) peek.Add(new long[]{e[0], e[1]});
                    long w = WalkNext(rt, peek);
                    if (w != Val.NotFound && w == nv) {
                        sb = peek; la = System.Array.Empty<byte>(); lb = System.Array.Empty<byte>();
                        pa = 0; pb = 0;
                        continue;
                    }
                }
                la = ToArray(rt, nv); pa = 0;
            }
            if (pb == lb.Length) {
                long nv = WalkNext(rt, sb);
                if (nv == Val.NotFound) break;
                lb = ToArray(rt, nv); pb = 0;
            }
            int n2 = System.Math.Min(la.Length - pa, lb.Length - pb);
            if (n2 == 0) continue;
            for (int i = 0; i < n2; i++) if (la[pa + i] != lb[pb + i]) return false;
            pa += n2; pb += n2;
        }
        return pa == la.Length && pb == lb.Length
            && WalkNext(rt, sa) == Val.NotFound && WalkNext(rt, sb) == Val.NotFound;
    }

    static long WalkNext(Rt rt, List<long[]> stack) {
        while (stack.Count > 0) {
            var top = stack[stack.Count - 1];
            long node = top[0];
            int i = (int) top[1];
            if (!IsBrope(rt, node)) { stack.RemoveAt(stack.Count - 1); return node; }
            int kids = Obj.Len(rt.gc.sp, Val.AsHeap(node)) - BB_KIDS;
            if (i >= kids) { stack.RemoveAt(stack.Count - 1); continue; }
            top[1] = i + 1;
            stack.Add(new long[]{rt.Slot(node, BB_KIDS + i), 0});
        }
        return Val.NotFound;
    }

    // --- the transient ------------------------------------------------------
    //
    // Why this exists, in one measurement. `FLAT_MAX` is 1024 bytes, and a
    // concatenation below it COPIES rather than building a node -- the tier
    // that stops a tree costing more in metadata than it saves. It also means
    // building a byte string one piece at a time re-copies everything so far on
    // every join, which is quadratic until the pieces outgrow the threshold.
    //
    // A transient fixes it the way a transient vector fixes `conj`: a tail
    // buffer the transient OWNS and writes into, promoted into the tree only
    // when it is full. Canonical Clojure has no reason to want this, because
    // its strings are flat and building one is a StringBuilder. flint's are
    // trees with a flat threshold, which is a shape that has this problem.

    public const int TB_TREE = 0, TB_TAIL = 1, TB_FILL = 2, TB_LIVE = 3;

    /// Exactly `FLAT_MAX`, so a promoted tail is the smallest piece that makes
    /// `concat` build a node instead of copying. A smaller tail would promote
    /// into the copying tier and reintroduce the quadratic it removes.
    public const int TAIL_CAP = FLAT_MAX;

        public static bool IsTransient(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Bytecore.IsTbytes(rt, v); }

    public static long TransientOf(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Bytetrans.BTransient(rt, v); }

        static bool Live(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Bytecore.TbLive(rt, t); }

    /// Fold the full tail into the tree and start a fresh one. A FULL tail is
    /// handed over WHOLE rather than copied -- it is exactly the leaf the tree
    /// wants.
    static bool Flush(Rt rt, long t, int fill) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int tl = rt.Push(rt.Slot(rt.R(ti), TB_TAIL));
        long piece;
        if (fill == TAIL_CAP) {
            piece = rt.R(tl);
        } else {
            piece = Of(rt, rt.gc.sp.Bytes(Val.AsHeap(rt.R(tl)) + Obj.Hdr, fill));
        }
        int pi = rt.Push(piece);
        int tr = rt.Push(rt.Slot(rt.R(ti), TB_TREE));
        long joined = Val.IsNil(rt.R(tr)) ? rt.R(pi) : Concat(rt, rt.R(tr), rt.R(pi));
        int ji = rt.Push(joined);
        long fresh = rt.Alloc(Obj.TyBytes, TAIL_CAP);
        if (fresh == 0) { rt.PopTo(bas); return false; }
        long tv = rt.R(ti);
        rt.SetSlot(Val.AsHeap(tv), TB_TREE, rt.R(ji));
        rt.SetSlot(Val.AsHeap(tv), TB_TAIL, Val.Heap(fresh));
        rt.SetSlot(Val.AsHeap(tv), TB_FILL, Val.Fixnum(0));
        rt.PopTo(bas);
        return true;
    }

    /// Append one byte: no allocation and no copy until the tail fills.
    ///
    /// `t` is ROOTED across the flush, and that is not caution. `flush`
    /// allocates, allocating can collect, and the nursery is a COPYING
    /// collector -- so a value held in a host local across it comes back
    /// holding the address the object had BEFORE the flip.
    public static long Conj(Rt rt, long t, int b) {
        if (!Live(rt, t)) {
            return rt.ThrowStr("IllegalStateException", "this transient byte string is no longer usable");
        }
        int fill = (int) Val.AsFixnum(rt.Slot(t, TB_FILL));
        if (fill == TAIL_CAP) {
            int bas = rt.Mark();
            int ti = rt.Push(t);
            bool ok = Flush(rt, rt.R(ti), fill);
            long tv = rt.R(ti);
            rt.PopTo(bas);
            if (!ok) return Val.Nil;
            return Conj(rt, tv, b);
        }
        long tail = rt.Slot(t, TB_TAIL);
        rt.gc.sp.WriteU8(Val.AsHeap(tail) + Obj.Hdr + fill, b & 0xFF);
        rt.SetSlot(Val.AsHeap(t), TB_FILL, Val.Fixnum(fill + 1));
        return t;
    }

    /// Append a whole byte string. BULK, because appending a 1 KB piece one
    /// byte at a time is the thing this type exists to stop doing.
    public static long AppendBytes(Rt rt, long t, long v) {
        if (!Live(rt, t)) {
            return rt.ThrowStr("IllegalStateException", "this transient byte string is no longer usable");
        }
        // The source is copied outv FIRST, so nothing holds a reference into the
        // heap while the loop below allocates.
        byte[] src = ToArray(rt, v);
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int i = 0;
        while (i < src.Length) {
            int fill = (int) Val.AsFixnum(rt.Slot(rt.R(ti), TB_FILL));
            if (fill == TAIL_CAP) {
                if (!Flush(rt, rt.R(ti), fill)) { rt.PopTo(bas); return Val.Nil; }
                continue;
            }
            int room = TAIL_CAP - fill;
            int n = System.Math.Min(room, src.Length - i);
            long tail = rt.Slot(rt.R(ti), TB_TAIL);
            byte[] chunk = new byte[n];
            System.Array.Copy(src, i, chunk, 0, n);
            rt.gc.sp.WriteBytes(Val.AsHeap(tail) + Obj.Hdr + fill, chunk);
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TB_FILL, Val.Fixnum(fill + n));
            i += n;
        }
        long outv = rt.R(ti);
        rt.PopTo(bas);
        return outv;
    }

        public static int Tcount(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Bytecore.BTcount(rt, t); }

    public static long Persistent(Rt rt, long t) {
        if (!Live(rt, t)) {
            return rt.ThrowStr("IllegalStateException", "this transient byte string is no longer usable");
        }
        int fill = (int) Val.AsFixnum(rt.Slot(t, TB_FILL));
        int bas = rt.Mark();
        int ti = rt.Push(t);
        if (fill > 0 && !Flush(rt, rt.R(ti), fill)) { rt.PopTo(bas); return Val.Nil; }
        long tv = rt.R(ti);
        rt.SetSlot(Val.AsHeap(tv), TB_LIVE, Val.False);
        long outv = rt.Slot(tv, TB_TREE);
        rt.PopTo(bas);
        return Val.IsNil(outv) ? Of(rt, new byte[0]) : outv;
    }
}
