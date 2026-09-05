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


    /// The bytes as a HOST array -- see the Java copy.
    public static byte[] ToArray(Rt rt, long v) {
        int s = rt.SinkOpen();
        global::_3sln.Flint.Kgen.Rt.Byteflat.BAppend(rt, v, s);
        byte[] outb = rt.SinkArray(s);
        rt.SinkClose(s);
        return outb;
    }

    /// The byte at `i`, or -1. Descends rather than flattening, which is why
    /// depth is what random access pays and why `FANOUT` is 16.
    public static long At(Rt rt, long v, int i, long dflt) { return global::_3sln.Flint.Kgen.Rt.Byteat.BAt(rt, v, i, dflt); }

        public static int Depth(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Bytecore.BDepth(rt, v); }

    /// A leaf joining a deeper node is PROMOTED rather than sitting beside
    /// subtrees: a node's children must all be the same depth.
    static long WrapTo(Rt rt, long v, int d) { return global::_3sln.Flint.Kgen.Rt.Bytenode.BWrapTo(rt, v, d); }

    /// A node over the `n` values ALREADY ROOTED at `bas`. The caller pushes
    /// and the caller pops -- see the Rust and Java copies, which say the same.
    static long Node(Rt rt, int bas, int n) { return global::_3sln.Flint.Kgen.Rt.Bytenode.BNode(rt, bas, n); }

    /// Copy the range out into a fresh leaf -- see the Java and Rust copies.
    public static long CopyRange(Rt rt, long v, int from, int to) { return global::_3sln.Flint.Kgen.Rt.Byteflat.BCopyRange(rt, v, from, to); }

    public static long CopyConcat(Rt rt, long a, long b) { return global::_3sln.Flint.Kgen.Rt.Byteflat.BCopyConcat(rt, a, b); }

    public static long Concat(Rt rt, long a, long b) { return global::_3sln.Flint.Kgen.Rt.Byteconcat.BConcat(rt, a, b); }

    /// `a`'s rightmost leaf followed by `b`, if the two fit in one leaf. NIL if
    /// they do not, or if there is no leaf to merge into.
    static long MergeRight(Rt rt, long a, long b) { return global::_3sln.Flint.Kgen.Rt.Byteconcat.BMergeRight(rt, a, b); }

    /// Put `b` in the deepest node on `a`'s right spine that has room, or NIL.
    /// Depth is UNCHANGED when this succeeds, which is the whole point.
    static long Absorb(Rt rt, long a, long b) { return global::_3sln.Flint.Kgen.Rt.Byteconcat.BAbsorb(rt, a, b); }

    /// A contiguous copy, CACHED on the node so a second walk is free.
    public static long Flatten(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Byteflat.BFlatten(rt, v); }

    /// Append only `[from, to)`, descending rather than materialising. A
    /// subtree entirely outside the range is skipped whole, which is what makes
    /// a slice cost the size of the SLICE. Flattening first was correct and
    /// quadratic in the caller: tree-shaking slices a thousand function bodies
    /// outv of one 509 KB code section, and materialising it per slice is half a
    /// gigabyte of copying.

    public static bool IsBrope(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Bytecore.IsBrope(rt, v);

    /// SHARES, like `Str.RopeSlice`. This descended to the range and then
    /// COPIED it (`doc/decisions/0011`).
    public static long Slice(Rt rt, long v, int from, int to) { return global::_3sln.Flint.Kgen.Rt.Byteslice.BSlice(rt, v, from, to); }

    static long FromRoots(Rt rt, int bas, int n) { return global::_3sln.Flint.Kgen.Rt.Bytefold.BFromRoots(rt, bas, n); }

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
    static bool Flush(Rt rt, long t, int fill) { return global::_3sln.Flint.Kgen.Rt.Bytetwrite.TbFlush(rt, t, fill); }

    /// Append one byte: no allocation and no copy until the tail fills.
    ///
    /// `t` is ROOTED across the flush, and that is not caution. `flush`
    /// allocates, allocating can collect, and the nursery is a COPYING
    /// collector -- so a value held in a host local across it comes back
    /// holding the address the object had BEFORE the flip.
    public static long Conj(Rt rt, long t, int b) { return global::_3sln.Flint.Kgen.Rt.Bytetwrite.BConj(rt, t, b); }

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

    public static long Persistent(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Bytetwrite.BPersistent(rt, t); }
}
