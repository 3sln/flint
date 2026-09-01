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

    /// A NODE carries its OWNERSHIP TOKEN in slot 0 and its elements from 1.
    ///
    /// That extra slot is what makes transients possible: a node whose token is
    /// this transient's is owned by it and is written IN PLACE, and any other
    /// node is copied once and thereafter owned. Without it every `conj!` would
    /// copy, which is the entire cost transients exist to avoid.
    ///
    /// It is also why `NodeLen` subtracts one and every accessor adds one. The
    /// port carried plain nodes for a while and read perfectly; it was only not
    /// the Rust's layout, and an object of a different LENGTH is exactly the
    /// kind of divergence a snapshot would carry silently between runtimes.
    static long NodeGet(Rt rt, long node, int i) => rt.Slot(node, i + 1);
    static void NodeSet(Rt rt, long node, int i, long v) => rt.SetSlot(Val.AsHeap(node), i + 1, v);
    static int NodeLen(Rt rt, long node) => Obj.Len(rt.gc.sp, Val.AsHeap(node)) - 1;
    static long NodeEdit(Rt rt, long node) => rt.Slot(node, 0);

    static long NewNode(Rt rt, int n, long edit) {
        int e = rt.Push(edit);
        long a = rt.Alloc(Obj.TyNode, n + 1);
        long ed = rt.R(e);
        rt.PopTo(e);
        if (a == 0) return Val.Nil;
        rt.SetSlot(a, 0, ed);
        return Val.Heap(a);
    }

    /// Copy `src`'s first `n` slots into a fresh node of `n` slots, owned by
    /// `edit` (NIL for a persistent node, which nothing owns).
    static long NodeClone(Rt rt, long src, int n, long edit) {
        int bas = rt.Mark();
        int si = rt.Push(src);
        int ei = rt.Push(edit);
        long outv = NewNode(rt, System.Math.Max(n, 1), rt.R(ei));
        if (Val.IsNil(outv)) { rt.PopTo(bas); return Val.Nil; }
        int oi = rt.Push(outv);
        if (!Val.IsNil(rt.R(si))) {
            int have = NodeLen(rt, rt.R(si));
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
        long sg = rt.roots.shared.Singletons[Rt.SingEmptyVec];
        if (!Val.IsNil(sg)) return sg;
        return NewEmpty(rt);
    }

    internal static long NewEmpty(Rt rt) {
        int bas = rt.Mark();
        long root = NewNode(rt, Width, Val.Nil);
        int ri = rt.Push(root);
        long tail = NewNode(rt, 0, Val.Nil);
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

    static long NewPath(Rt rt, int level, long node, long edit) {
        if (level == 0) return node;
        int bas = rt.Mark();
        int ni = rt.Push(node);
        int ei = rt.Push(edit);
        long child = NewPath(rt, level - Bits, rt.R(ni), rt.R(ei));
        int ci = rt.Push(child);
        long parent = NewNode(rt, Width, rt.R(ei));
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
        long ret = NodeClone(rt, rt.R(pi), Width, Val.Nil);
        if (Val.IsNil(ret)) { rt.PopTo(bas); return Val.Nil; }
        int ri = rt.Push(ret);
        int subidx = (int)((uint)(cnt - 1) >> level) & Mask;
        long insert;
        if (level == Bits) {
            insert = rt.R(ti);
        } else {
            long child = NodeGet(rt, rt.R(pi), subidx);
            insert = Val.IsNil(child)
                ? NewPath(rt, level - Bits, rt.R(ti), Val.Nil)
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
            long newtail = NodeClone(rt, Tail(rt, rt.R(vi)), tailLen + 1, Val.Nil);
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
                long nr = NewNode(rt, Width, Val.Nil);
                int nri = rt.Push(nr);
                NodeSet(rt, rt.R(nri), 0, Root(rt, rt.R(vi)));
                long path = NewPath(rt, sh, rt.R(tn), Val.Nil);
                NodeSet(rt, rt.R(nri), 1, path);
                newroot = rt.R(nri);
                newshift = sh + Bits;
            } else {
                newroot = PushTail(rt, cnt, sh, Root(rt, rt.R(vi)), rt.R(tn));
                newshift = sh;
            }
            int nri2 = rt.Push(newroot);
            long newtail = NewNode(rt, 1, Val.Nil);
            int ntl = rt.Push(newtail);
            NodeSet(rt, rt.R(ntl), 0, rt.R(xi));
            outv = NewVec(rt, cnt + 1, newshift, rt.R(nri2), rt.R(ntl), rt.Slot(rt.R(vi), VMeta));
        }
        rt.PopTo(bas);
        return outv;
    }

    static long DoAssoc(Rt rt, int level, long node, int i, long val) {
        int bas = rt.Mark();
        int n = rt.Push(node), v = rt.Push(val);
        long ret = NodeClone(rt, rt.R(n), Width, Val.Nil);
        if (Val.IsNil(ret)) { rt.PopTo(bas); return Val.Nil; }
        int ri = rt.Push(ret);
        if (level == 0) {
            NodeSet(rt, rt.R(ri), i & Mask, rt.R(v));
        } else {
            int subidx = (int)((uint) i >> level) & Mask;
            long child = NodeGet(rt, rt.R(n), subidx);
            long nc = DoAssoc(rt, level - Bits, child, i, rt.R(v));
            NodeSet(rt, rt.R(ri), subidx, nc);
        }
        long outv = rt.R(ri);
        rt.PopTo(bas);
        return outv;
    }

    /// `assoc` at an index. `i == count` APPENDS, which is Clojure's rule and
    /// the only index past the end that is legal.
    public static long Assoc(Rt rt, long v, int i, long x) {
        int cnt = Count(rt, v);
        if (i == cnt) return Conj(rt, v, x);
        int bas = rt.Mark();
        int vi = rt.Push(v), xi = rt.Push(x);
        long outv;
        if (i >= TailOff(rt, v)) {
            long tl0 = Tail(rt, rt.R(vi));
            int tl = NodeLen(rt, tl0);
            int nti = rt.Push(NodeClone(rt, tl0, tl, Val.Nil));
            NodeSet(rt, rt.R(nti), i - TailOff(rt, rt.R(vi)), rt.R(xi));
            long vv = rt.R(vi);
            outv = NewVec(rt, cnt, Shift(rt, vv), Root(rt, vv), rt.R(nti), rt.Slot(vv, VMeta));
        } else {
            long vv = rt.R(vi);
            int sh = Shift(rt, vv);
            long nr = DoAssoc(rt, sh, Root(rt, vv), i, rt.R(xi));
            int nri = rt.Push(nr);
            vv = rt.R(vi);
            outv = NewVec(rt, cnt, sh, rt.R(nri), Tail(rt, vv), rt.Slot(vv, VMeta));
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

    // -----------------------------------------------------------------------
    // TRANSIENTS. `TY_TVEC [cnt, shift, root, tail, edit]`.
    //
    // `edit` is a freshly allocated object used purely for its IDENTITY. A node
    // whose token is that same object is owned by this transient and is mutated
    // in place; any other node is copied once and thereafter owned.
    // `persistent!` clears `edit`, so a stale handle fails loudly instead of
    // quietly mutating a value somebody else is now holding.

    public const int TCnt = 0, TShift = 1, TRoot = 2, TTail = 3, TEdit = 4;

    /// A fresh identity for a transient's ownership token. Its TYPE is
    /// irrelevant and its contents are never read -- only `==` on the address.
    public static long NewEditToken(Rt rt) {
        long a = rt.Alloc(Obj.TyVolatile, 1);
        return a == 0 ? Val.Nil : Val.Heap(a);
    }

    public static bool IsTransient(Rt rt, long v) =>
        Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyTvec;

    public static int TCount(Rt rt, long t) => (int) Val.AsFixnum(rt.Slot(t, TCnt));
    static int TShiftOf(Rt rt, long t) => (int) Val.AsFixnum(rt.Slot(t, TShift));
    static int TTailOff(Rt rt, long t) {
        int c = TCount(rt, t);
        return c < Width ? 0 : ((c - 1) >> Bits) << Bits;
    }
    public static bool Alive(Rt rt, long t) => !Val.IsNil(rt.Slot(t, TEdit));

    /// `transient`: O(1). Nothing is copied until the first write reaching a
    /// node this transient does not already own.
    public static long TransientOf(Rt rt, long v) {
        int bas = rt.Mark();
        int vi = rt.Push(v);
        int ei = rt.Push(NewEditToken(rt));
        // The tail is widened to a full 32 UP FRONT so `conj!` can write in
        // place instead of copying it one longer every time.
        long tail = NodeClone(rt, Tail(rt, rt.R(vi)), Width, rt.R(ei));
        int ti = rt.Push(tail);
        int ri = rt.Push(Root(rt, rt.R(vi)));
        long a = rt.Alloc(Obj.TyTvec, 5);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        long vv = rt.R(vi);
        int cnt = Count(rt, vv), shift = Shift(rt, vv);
        rt.SetSlot(a, TCnt, Val.Fixnum(cnt));
        rt.SetSlot(a, TShift, Val.Fixnum(shift));
        rt.SetSlot(a, TRoot, rt.R(ri));
        rt.SetSlot(a, TTail, rt.R(ti));
        rt.SetSlot(a, TEdit, rt.R(ei));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// `node` if this transient already owns it, else an owned copy.
    static long EnsureEditable(Rt rt, long node, long edit) =>
        NodeEdit(rt, node) == edit ? node : NodeClone(rt, node, Width, edit);

    static long TPushTail(Rt rt, int cnt, int level, long parent, long tailnode, long edit) {
        int bas = rt.Mark();
        int e = rt.Push(edit);
        int t = rt.Push(tailnode);
        int p = rt.Push(parent);
        long ret = EnsureEditable(rt, rt.R(p), rt.R(e));
        int ri = rt.Push(ret);
        int subidx = (int)((uint)(cnt - 1) >> level) & Mask;
        long insert;
        if (level == Bits) {
            insert = rt.R(t);
        } else {
            long child = NodeGet(rt, rt.R(ri), subidx);
            insert = Val.IsNil(child)
                ? NewPath(rt, level - Bits, rt.R(t), rt.R(e))
                : TPushTail(rt, cnt, level - Bits, child, rt.R(t), rt.R(e));
        }
        int ii = rt.Push(insert);
        NodeSet(rt, rt.R(ri), subidx, rt.R(ii));
        long outv = rt.R(ri);
        rt.PopTo(bas);
        return outv;
    }

    public static long TConj(Rt rt, long t, long x) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int xi = rt.Push(x);
        int cnt = TCount(rt, t);
        if (cnt - TTailOff(rt, t) < Width) {
            // Room in the working tail: written IN PLACE. Nothing is allocated
            // on this path, which is why `t` cannot have moved and is returned
            // as it came in.
            NodeSet(rt, rt.Slot(t, TTail), cnt & Mask, rt.R(xi));
            rt.SetSlot(Val.AsHeap(t), TCnt, Val.Fixnum(cnt + 1));
            rt.PopTo(bas);
            return t;
        }
        // Tail full: fold it into the trie and start a fresh one.
        int ei = rt.Push(rt.Slot(t, TEdit));
        int tn = rt.Push(rt.Slot(rt.R(ti), TTail));
        int nt = rt.Push(NewNode(rt, Width, rt.R(ei)));
        NodeSet(rt, rt.R(nt), 0, rt.R(xi));
        int sh = TShiftOf(rt, rt.R(ti));
        bool overflow = ((int)((uint) cnt >> Bits)) > (1 << sh);
        long newroot;
        int newshift;
        if (overflow) {
            int nri = rt.Push(NewNode(rt, Width, rt.R(ei)));
            NodeSet(rt, rt.R(nri), 0, rt.Slot(rt.R(ti), TRoot));
            long path = NewPath(rt, sh, rt.R(tn), rt.R(ei));
            NodeSet(rt, rt.R(nri), 1, path);
            newroot = rt.R(nri);
            newshift = sh + Bits;
        } else {
            newroot = TPushTail(rt, cnt, sh, rt.Slot(rt.R(ti), TRoot), rt.R(tn), rt.R(ei));
            newshift = sh;
        }
        int nri2 = rt.Push(newroot);
        long tv = rt.R(ti);
        rt.SetSlot(Val.AsHeap(tv), TRoot, rt.R(nri2));
        rt.SetSlot(Val.AsHeap(tv), TShift, Val.Fixnum(newshift));
        rt.SetSlot(Val.AsHeap(tv), TTail, rt.R(nt));
        rt.SetSlot(Val.AsHeap(tv), TCnt, Val.Fixnum(cnt + 1));
        rt.PopTo(bas);
        return tv;
    }

    static long TArrayFor(Rt rt, long t, int i) {
        if (i >= TTailOff(rt, t)) return rt.Slot(t, TTail);
        long node = rt.Slot(t, TRoot);
        int level = TShiftOf(rt, t);
        while (level > 0) {
            node = NodeGet(rt, node, (int)((uint) i >> level) & Mask);
            level -= Bits;
        }
        return node;
    }

    public static long TNth(Rt rt, long t, int i) {
        if (i < 0 || i >= TCount(rt, t)) return Val.NotFound;
        return NodeGet(rt, TArrayFor(rt, t, i), i & Mask);
    }

    static long TDoAssoc(Rt rt, int level, long node, int i, long val, long edit) {
        int bas = rt.Mark();
        int e = rt.Push(edit);
        int v = rt.Push(val);
        int ni = rt.Push(node);
        long ret = EnsureEditable(rt, rt.R(ni), rt.R(e));
        int ri = rt.Push(ret);
        if (level == 0) {
            NodeSet(rt, rt.R(ri), i & Mask, rt.R(v));
        } else {
            int subidx = (int)((uint) i >> level) & Mask;
            long child = NodeGet(rt, rt.R(ri), subidx);
            long nc = TDoAssoc(rt, level - Bits, child, i, rt.R(v), rt.R(e));
            NodeSet(rt, rt.R(ri), subidx, nc);
        }
        long outv = rt.R(ri);
        rt.PopTo(bas);
        return outv;
    }

    public static long TAssoc(Rt rt, long t, int i, long x) {
        int cnt = TCount(rt, t);
        if (i == cnt) return TConj(rt, t, x);
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int xi = rt.Push(x);
        if (i >= TTailOff(rt, t)) {
            NodeSet(rt, rt.Slot(t, TTail), i & Mask, rt.R(xi));
        } else {
            long nr = TDoAssoc(rt, TShiftOf(rt, t), rt.Slot(t, TRoot), i,
                               rt.R(xi), rt.Slot(t, TEdit));
            rt.SetSlot(Val.AsHeap(rt.R(ti)), TRoot, nr);
        }
        long outv = rt.R(ti);
        rt.PopTo(bas);
        return outv;
    }

    public static long TPersistent(Rt rt, long t) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int cnt = TCount(rt, t), shift = TShiftOf(rt, t), tailOff = TTailOff(rt, t);
        // Trim the 32-wide working tail down to what is actually used.
        int tr = rt.Push(NodeClone(rt, rt.Slot(t, TTail), cnt - tailOff, Val.Nil));
        int ri = rt.Push(rt.Slot(rt.R(ti), TRoot));
        // Invalidate the handle: using it afterwards is a bug, not a silent
        // mutation of a value somebody else now owns.
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TEdit, Val.Nil);
        long outv = NewVec(rt, cnt, shift, rt.R(ri), rt.R(tr), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }
}
