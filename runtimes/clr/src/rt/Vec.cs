namespace Flint.Rt;

/// Persistent vectors, ported from `runtime/src/vector.rs`.
///
/// A 32-way trie with a TAIL: the last up-to-32 elements live in a flat node
/// hanging off the vector, so appending is O(1) amortised and does not touch
/// the trie until the tail fills. That is what makes `conj` cheap and why
/// `TailOff` appears in every access path.
///
/// `TY_VEC` is `[cnt, shift, root, tail, meta]`; `TY_NODE` is `len` values.
public static class Vec {
    public const int Bits = 5;
    public const int Width = 1 << Bits;   // 32
    public const int Mask = Width - 1;

    public const int VCnt = 0, VShift = 1, VRoot = 2, VTail = 3, VMeta = 4;

    public static int Count(Rt rt, long v) => (int) Val.AsFixnum(rt.Slot(v, VCnt));
    static int Shift(Rt rt, long v) => (int) Val.AsFixnum(rt.Slot(v, VShift));
    static long Root(Rt rt, long v) => rt.Slot(v, VRoot);
    static long Tail(Rt rt, long v) => rt.Slot(v, VTail);

    /// Where the tail starts. Below 32 elements the whole vector IS the tail.
    static int TailOff(Rt rt, long v) {
        int c = Count(rt, v);
        return c < Width ? 0 : ((c - 1) >> Bits) << Bits;
    }

    static long NodeGet(Rt rt, long node, int i) => rt.Slot(node, i);
    static void NodeSet(Rt rt, long node, int i, long v) => rt.SetSlot(Val.AsHeap(node), i, v);

    static long NewNode(Rt rt, int n) {
        long a = rt.Alloc(Obj.TyNode, n);
        return a == 0 ? Val.Nil : Val.Heap(a);
    }

    /// Copy `src`'s first `n` slots into a fresh node of `n` slots.
    static long NodeClone(Rt rt, long src, int n) {
        int bas = rt.Mark();
        int si = rt.Push(src);
        long outv = NewNode(rt, System.Math.Max(n, 1));
        if (Val.IsNil(outv)) { rt.PopTo(bas); return Val.Nil; }
        int oi = rt.Push(outv);
        if (!Val.IsNil(rt.R(si))) {
            int have = Obj.Len(rt.gc.sp, Val.AsHeap(rt.R(si)));
            for (int i = 0; i < System.Math.Min(n, have); i++) {
                // Read the source through the shadow stack: `SetSlot` cannot
                // collect, but reading `src` from a host local across the
                // `NewNode` above would already have been stale.
                NodeSet(rt, rt.R(oi), i, NodeGet(rt, rt.R(si), i));
            }
        }
        long r = rt.R(oi);
        rt.PopTo(bas);
        return r;
    }

    static long NewVec(Rt rt, int cnt, int shift, long root, long tail, long meta) {
        int bas = rt.Mark();
        int ri = rt.Push(root);
        int ti = rt.Push(tail);
        int mi = rt.Push(meta);
        long a = rt.Alloc(Obj.TyVec, 5);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, VCnt, Val.Fixnum(cnt));
        rt.SetSlot(a, VShift, Val.Fixnum(shift));
        rt.SetSlot(a, VRoot, rt.R(ri));
        rt.SetSlot(a, VTail, rt.R(ti));
        rt.SetSlot(a, VMeta, rt.R(mi));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    public static long Empty(Rt rt) {
        int bas = rt.Mark();
        long root = NewNode(rt, Width);
        int ri = rt.Push(root);
        long tail = NewNode(rt, 0);
        int ti = rt.Push(tail);
        long outv = NewVec(rt, 0, Bits, rt.R(ri), rt.R(ti), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }

    /// The leaf array holding index `i`.
    static long ArrayFor(Rt rt, long v, int i) {
        if (i >= TailOff(rt, v)) return Tail(rt, v);
        long node = Root(rt, v);
        int level = Shift(rt, v);
        while (level > 0) {
            node = NodeGet(rt, node, (int)((uint) i >> level) & Mask);
            level -= Bits;
        }
        return node;
    }

    /// `nth`, or NotFound when out of range -- distinguishable from a `nil`
    /// that is genuinely stored there.
    public static long Nth(Rt rt, long v, int i) {
        if (i < 0 || i >= Count(rt, v)) return Val.NotFound;
        return NodeGet(rt, ArrayFor(rt, v, i), i & Mask);
    }

    static long NewPath(Rt rt, int level, long node) {
        if (level == 0) return node;
        int bas = rt.Mark();
        int ni = rt.Push(node);
        long child = NewPath(rt, level - Bits, rt.R(ni));
        int ci = rt.Push(child);
        long parent = NewNode(rt, Width);
        if (Val.IsNil(parent)) { rt.PopTo(bas); return Val.Nil; }
        int pi = rt.Push(parent);
        NodeSet(rt, rt.R(pi), 0, rt.R(ci));
        long outv = rt.R(pi);
        rt.PopTo(bas);
        return outv;
    }

    /// Push `tailnode` into the trie at `level`, copying the spine.
    static long PushTail(Rt rt, int cnt, int level, long parent, long tailnode) {
        int bas = rt.Mark();
        int pi = rt.Push(parent);
        int ti = rt.Push(tailnode);
        long ret = NodeClone(rt, rt.R(pi), Width);
        if (Val.IsNil(ret)) { rt.PopTo(bas); return Val.Nil; }
        int ri = rt.Push(ret);
        int subidx = (int)((uint)(cnt - 1) >> level) & Mask;
        long insert;
        if (level == Bits) {
            insert = rt.R(ti);
        } else {
            long child = NodeGet(rt, rt.R(pi), subidx);
            insert = Val.IsNil(child)
                ? NewPath(rt, level - Bits, rt.R(ti))
                : PushTail(rt, cnt, level - Bits, child, rt.R(ti));
        }
        int ii = rt.Push(insert);
        NodeSet(rt, rt.R(ri), subidx, rt.R(ii));
        long outv = rt.R(ri);
        rt.PopTo(bas);
        return outv;
    }

    public static long Conj(Rt rt, long v, long x) {
        int bas = rt.Mark();
        int vi = rt.Push(v);
        int xi = rt.Push(x);
        int cnt = Count(rt, v);
        int tailLen = cnt - TailOff(rt, v);
        long outv;
        if (tailLen < Width) {
            // Room in the tail: copy it one longer. This is the common case and
            // the reason `conj` is O(1) amortised.
            long newtail = NodeClone(rt, Tail(rt, rt.R(vi)), tailLen + 1);
            int nt = rt.Push(newtail);
            NodeSet(rt, rt.R(nt), tailLen, rt.R(xi));
            long vv = rt.R(vi);
            outv = NewVec(rt, cnt + 1, Shift(rt, vv), Root(rt, vv), rt.R(nt), rt.Slot(vv, VMeta));
        } else {
            // The tail is full: it becomes a leaf in the trie.
            long vv = rt.R(vi);
            int sh = Shift(rt, vv);
            int tn = rt.Push(Tail(rt, vv));
            bool overflow = ((int)((uint) cnt >> Bits)) > (1 << sh);
            long newroot;
            int newshift;
            if (overflow) {
                long nr = NewNode(rt, Width);
                int nri = rt.Push(nr);
                NodeSet(rt, rt.R(nri), 0, Root(rt, rt.R(vi)));
                long path = NewPath(rt, sh, rt.R(tn));
                NodeSet(rt, rt.R(nri), 1, path);
                newroot = rt.R(nri);
                newshift = sh + Bits;
            } else {
                newroot = PushTail(rt, cnt, sh, Root(rt, rt.R(vi)), rt.R(tn));
                newshift = sh;
            }
            int nri2 = rt.Push(newroot);
            long newtail = NewNode(rt, 1);
            int ntl = rt.Push(newtail);
            NodeSet(rt, rt.R(ntl), 0, rt.R(xi));
            outv = NewVec(rt, cnt + 1, newshift, rt.R(nri2), rt.R(ntl), rt.Slot(rt.R(vi), VMeta));
        }
        rt.PopTo(bas);
        return outv;
    }

    /// Build a vector from `n` values already rooted at `bas` on the shadow
    /// stack. What the `VECTOR` opcode uses.
    public static long FromRoots(Rt rt, int bas, int n) {
        int mk = rt.Mark();
        int vi = rt.Push(Empty(rt));
        for (int i = 0; i < n; i++) {
            long nv = Conj(rt, rt.R(vi), rt.R(bas + i));
            rt.SetR(vi, nv);
        }
        long outv = rt.R(vi);
        rt.PopTo(mk);
        return outv;
    }
}
