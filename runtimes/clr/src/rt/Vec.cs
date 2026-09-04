namespace Flint.Rt;

using static flint.rt.Vecnode;

/// Persistent vectors, ported from `runtime/src/vector.rs`.
///
/// A 32-way trie with a TAIL: the last up-to-32 elements live in a flat node
/// hanging off the vector, so appending is O(1) amortised and does not touch
/// the trie until the tail fills. That is what makes `conj` cheap and why
/// `TailOff` appears in every access path.
///
/// `TY_VEC` is `[cnt, shift, root, tail, meta]`; `TY_NODE` is `len` values.
public static class Vec {
    public const int BITS = 5;
    public const int WIDTH = 1 << BITS;   // 32
    public const int MASK = WIDTH - 1;

    public const int V_CNT = 0, V_SHIFT = 1, V_ROOT = 2, V_TAIL = 3, V_META = 4;

    /// The CACHED HASH, `nil` until first asked for.
    ///
    /// The native runtime has had this slot since vectors were written; the
    /// ports allocated a five-slot header and had nowhere to put it, so every
    /// `hash` of a vector walked all of it, every time. The file next door
    /// already makes the argument -- "a rope used as a map key must not rehash
    /// every lookup" -- and then a vector used as a map key did exactly that.
    ///
    /// It is also a LAYOUT divergence, which is the more serious half: the same
    /// value was six slots on one runtime and five on two, and no gate could
    /// see it. The snapshot check compares the two ports against each other
    /// and they agreed with each other while both disagreed with native.
    public const int V_HASH = 5;

    public static int Count(Rt rt, long v) => (int) Val.AsFixnum(rt.Slot(v, V_CNT));
    static int Shift(Rt rt, long v) => (int) Val.AsFixnum(rt.Slot(v, V_SHIFT));
    static long Root(Rt rt, long v) => rt.Slot(v, V_ROOT);
    static long Tail(Rt rt, long v) => rt.Slot(v, V_TAIL);

    /// Where the tail starts. Below 32 elements the whole vector IS the tail.
    static int TailOff(Rt rt, long v) {
        int c = Count(rt, v);
        return c < WIDTH ? 0 : ((c - 1) >> BITS) << BITS;
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
    /// A NODE carries its OWNERSHIP TOKEN in slot 0 and its elements from 1,
    /// which is why `NodeLen` subtracts one and every accessor adds one.
    ///
    /// `NodeGet`, `NodeSet`, `NodeLen`, `NodeEdit` and `NewNode` are GENERATED
    /// now, from `kin/vecnode.kin`, and reached through the static import at
    /// the top of this file.
    static long NodeClone(Rt rt, long src, int n, long edit) {
        int bas = rt.Mark();
        int si = rt.Push(src);
        int ei = rt.Push(edit);
        // `n`, NOT `Math.Max(n, 1)`. The floor made the ports allocate a
        // two-slot node where native allocates one, for the tail of a vector
        // produced by persisting an EMPTY transient -- `cnt - tailOff` is 0
        // there, and it is the only caller that can ask for zero.
        //
        // Measured, not read: a probe on this line fired exactly once, on
        // `(persistent! (transient []))`, and never across the whole
        // conformance suite -- because the suite contained no transient at
        // all. `NewEmpty` right above already builds a zero-length tail, so
        // the two ways of reaching an empty vector disagreed with each other
        // on the same runtime as well as with native.
        long outv = NewNode(rt, n, rt.R(ei));
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
        long a = rt.Alloc(Obj.TyVec, 6);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, V_CNT, Val.Fixnum(cnt));
        rt.SetSlot(a, V_SHIFT, Val.Fixnum(shift));
        rt.SetSlot(a, V_ROOT, rt.R(ri));
        rt.SetSlot(a, V_TAIL, rt.R(ti));
        rt.SetSlot(a, V_META, rt.R(mi));
        // Not carried from any source vector: `NewVec` is called with new
        // contents every time, and a hash copied from the old one would be
        // wrong rather than merely stale.
        rt.SetSlot(a, V_HASH, Val.Nil);
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
        long root = NewNode(rt, WIDTH, Val.Nil);
        int ri = rt.Push(root);
        long tail = NewNode(rt, 0, Val.Nil);
        int ti = rt.Push(tail);
        long outv = NewVec(rt, 0, BITS, rt.R(ri), rt.R(ti), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }

    /// The leaf array holding index `i`.
    static long ArrayFor(Rt rt, long v, int i) {
        if (i >= TailOff(rt, v)) return Tail(rt, v);
        long node = Root(rt, v);
        int level = Shift(rt, v);
        while (level > 0) {
            node = NodeGet(rt, node, (int)((uint) i >> level) & MASK);
            level -= BITS;
        }
        return node;
    }

    /// `nth`, or NotFound when out of range -- distinguishable from a `nil`
    /// that is genuinely stored there.
    public static long Nth(Rt rt, long v, int i) {
        if (i < 0 || i >= Count(rt, v)) return Val.NotFound;
        return NodeGet(rt, ArrayFor(rt, v, i), i & MASK);
    }

    static long NewPath(Rt rt, int level, long node, long edit) {
        if (level == 0) return node;
        int bas = rt.Mark();
        int ni = rt.Push(node);
        int ei = rt.Push(edit);
        long child = NewPath(rt, level - BITS, rt.R(ni), rt.R(ei));
        int ci = rt.Push(child);
        long parent = NewNode(rt, WIDTH, rt.R(ei));
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
        long ret = NodeClone(rt, rt.R(pi), WIDTH, Val.Nil);
        if (Val.IsNil(ret)) { rt.PopTo(bas); return Val.Nil; }
        int ri = rt.Push(ret);
        int subidx = (int)((uint)(cnt - 1) >> level) & MASK;
        long insert;
        if (level == BITS) {
            insert = rt.R(ti);
        } else {
            long child = NodeGet(rt, rt.R(pi), subidx);
            insert = Val.IsNil(child)
                ? NewPath(rt, level - BITS, rt.R(ti), Val.Nil)
                : PushTail(rt, cnt, level - BITS, child, rt.R(ti));
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
        if (tailLen < WIDTH) {
            // Room in the tail: copy it one longer. This is the common case and
            // the reason `conj` is O(1) amortised.
            long newtail = NodeClone(rt, Tail(rt, rt.R(vi)), tailLen + 1, Val.Nil);
            int nt = rt.Push(newtail);
            NodeSet(rt, rt.R(nt), tailLen, rt.R(xi));
            long vv = rt.R(vi);
            outv = NewVec(rt, cnt + 1, Shift(rt, vv), Root(rt, vv), rt.R(nt), rt.Slot(vv, V_META));
        } else {
            // The tail is full: it becomes a leaf in the trie.
            long vv = rt.R(vi);
            int sh = Shift(rt, vv);
            int tn = rt.Push(Tail(rt, vv));
            bool overflow = ((int)((uint) cnt >> BITS)) > (1 << sh);
            long newroot;
            int newshift;
            if (overflow) {
                long nr = NewNode(rt, WIDTH, Val.Nil);
                int nri = rt.Push(nr);
                NodeSet(rt, rt.R(nri), 0, Root(rt, rt.R(vi)));
                long path = NewPath(rt, sh, rt.R(tn), Val.Nil);
                NodeSet(rt, rt.R(nri), 1, path);
                newroot = rt.R(nri);
                newshift = sh + BITS;
            } else {
                newroot = PushTail(rt, cnt, sh, Root(rt, rt.R(vi)), rt.R(tn));
                newshift = sh;
            }
            int nri2 = rt.Push(newroot);
            long newtail = NewNode(rt, 1, Val.Nil);
            int ntl = rt.Push(newtail);
            NodeSet(rt, rt.R(ntl), 0, rt.R(xi));
            outv = NewVec(rt, cnt + 1, newshift, rt.R(nri2), rt.R(ntl), rt.Slot(rt.R(vi), V_META));
        }
        rt.PopTo(bas);
        return outv;
    }

    static long DoAssoc(Rt rt, int level, long node, int i, long val) {
        int bas = rt.Mark();
        int n = rt.Push(node), v = rt.Push(val);
        long ret = NodeClone(rt, rt.R(n), WIDTH, Val.Nil);
        if (Val.IsNil(ret)) { rt.PopTo(bas); return Val.Nil; }
        int ri = rt.Push(ret);
        if (level == 0) {
            NodeSet(rt, rt.R(ri), i & MASK, rt.R(v));
        } else {
            int subidx = (int)((uint) i >> level) & MASK;
            long child = NodeGet(rt, rt.R(n), subidx);
            long nc = DoAssoc(rt, level - BITS, child, i, rt.R(v));
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
            outv = NewVec(rt, cnt, Shift(rt, vv), Root(rt, vv), rt.R(nti), rt.Slot(vv, V_META));
        } else {
            long vv = rt.R(vi);
            int sh = Shift(rt, vv);
            long nr = DoAssoc(rt, sh, Root(rt, vv), i, rt.R(xi));
            int nri = rt.Push(nr);
            vv = rt.R(vi);
            outv = NewVec(rt, cnt, sh, rt.R(nri), Tail(rt, vv), rt.Slot(vv, V_META));
        }
        rt.PopTo(bas);
        return outv;
    }

    /// Build a vector from `n` values already rooted at `bas` on the shadow
    /// stack. What the `VECTOR` opcode uses.
    /// Unwind one leaf out of the trie: the node `cnt - 2` lives under, with
    /// the emptying branch removed, or Nil when the branch disappears.
    ///
    /// Ported late. Neither port had this, nor `Pop`, nor `TPop` -- the `pop`
    /// builtin REBUILT the vector with a conj loop, which is O(n) where this
    /// is O(log n), and the transient pop had no implementation at all.
    static long PopTail(Rt rt, int level, long node, int cnt) {
        int subidx = (int)(((uint)(cnt - 2)) >> level) & MASK;
        if (level > BITS) {
            int bas = rt.Mark();
            int ni = rt.Push(node);
            long child = NodeGet(rt, node, subidx);
            long newchild = PopTail(rt, level - BITS, child, cnt);
            long outv;
            if (Val.IsNil(newchild) && subidx == 0) {
                outv = Val.Nil;
            } else {
                int nc = rt.Push(newchild);
                long ret = NodeClone(rt, rt.R(ni), WIDTH, Val.Nil);
                int ri = rt.Push(ret);
                NodeSet(rt, rt.R(ri), subidx, rt.R(nc));
                outv = rt.R(ri);
            }
            rt.PopTo(bas);
            return outv;
        }
        if (subidx == 0) return Val.Nil;
        long ret2 = NodeClone(rt, node, WIDTH, Val.Nil);
        NodeSet(rt, ret2, subidx, Val.Nil);
        return ret2;
    }

    /// `pop`: the vector one shorter. Nil when it is empty -- the caller
    /// raises, because an empty vector cannot be popped.
    public static long Pop(Rt rt, long v) {
        int cnt = Count(rt, v);
        if (cnt == 0) return Val.Nil;
        if (cnt == 1) return Empty(rt);
        int bas = rt.Mark();
        int vi = rt.Push(v);
        long outv;
        if (cnt - 1 > TailOff(rt, v)) {
            // Still inside the tail: copy it one shorter and keep the trie.
            long tl = Tail(rt, v);
            int newlen = NodeLen(rt, tl) - 1;
            long nt = NodeClone(rt, tl, newlen, Val.Nil);
            int nti = rt.Push(nt);
            long vv = rt.R(vi);
            outv = NewVec(rt, cnt - 1, Shift(rt, vv), Root(rt, vv), rt.R(nti),
                          rt.Slot(vv, V_META));
        } else {
            // The tail is emptying: pull the previous leaf back out.
            long newtail = ArrayFor(rt, v, cnt - 2);
            int nt = rt.Push(newtail);
            long vv = rt.R(vi);
            int sh = Shift(rt, vv);
            long newroot = PopTail(rt, sh, Root(rt, vv), cnt);
            int newshift = sh;
            if (Val.IsNil(newroot)) newroot = NewNode(rt, WIDTH, Val.Nil);
            int nri = rt.Push(newroot);
            if (newshift > BITS && Val.IsNil(NodeGet(rt, rt.R(nri), 1))) {
                rt.SetR(nri, NodeGet(rt, rt.R(nri), 0));
                newshift -= BITS;
            }
            outv = NewVec(rt, cnt - 1, newshift, rt.R(nri), rt.R(nt),
                          rt.Slot(rt.R(vi), V_META));
        }
        rt.PopTo(bas);
        return outv;
    }

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

    public const int T_CNT = 0, T_SHIFT = 1, T_ROOT = 2, T_TAIL = 3, T_EDIT = 4;

    /// A fresh identity for a transient's ownership token. Its TYPE is
    /// irrelevant and its contents are never read -- only `==` on the address.
    public static long NewEditToken(Rt rt) {
        long a = rt.Alloc(Obj.TyVolatile, 1);
        return a == 0 ? Val.Nil : Val.Heap(a);
    }

    public static bool IsTransient(Rt rt, long v) =>
        Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyTvec;

    public static int TCount(Rt rt, long t) => (int) Val.AsFixnum(rt.Slot(t, T_CNT));
    static int TShiftOf(Rt rt, long t) => (int) Val.AsFixnum(rt.Slot(t, T_SHIFT));
    static int TTailOff(Rt rt, long t) {
        int c = TCount(rt, t);
        return c < WIDTH ? 0 : ((c - 1) >> BITS) << BITS;
    }
    public static bool Alive(Rt rt, long t) => !Val.IsNil(rt.Slot(t, T_EDIT));

    /// `transient`: O(1). Nothing is copied until the first write reaching a
    /// node this transient does not already own.
    public static long TransientOf(Rt rt, long v) {
        int bas = rt.Mark();
        int vi = rt.Push(v);
        int ei = rt.Push(NewEditToken(rt));
        // The tail is widened to a full 32 UP FRONT so `conj!` can write in
        // place instead of copying it one longer every time.
        long tail = NodeClone(rt, Tail(rt, rt.R(vi)), WIDTH, rt.R(ei));
        int ti = rt.Push(tail);
        int ri = rt.Push(Root(rt, rt.R(vi)));
        long a = rt.Alloc(Obj.TyTvec, 5);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        long vv = rt.R(vi);
        int cnt = Count(rt, vv), shift = Shift(rt, vv);
        rt.SetSlot(a, T_CNT, Val.Fixnum(cnt));
        rt.SetSlot(a, T_SHIFT, Val.Fixnum(shift));
        rt.SetSlot(a, T_ROOT, rt.R(ri));
        rt.SetSlot(a, T_TAIL, rt.R(ti));
        rt.SetSlot(a, T_EDIT, rt.R(ei));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// `node` if this transient already owns it, else an owned copy.
    static long EnsureEditable(Rt rt, long node, long edit) =>
        NodeEdit(rt, node) == edit ? node : NodeClone(rt, node, WIDTH, edit);

    static long TPushTail(Rt rt, int cnt, int level, long parent, long tailnode, long edit) {
        int bas = rt.Mark();
        int e = rt.Push(edit);
        int t = rt.Push(tailnode);
        int p = rt.Push(parent);
        long ret = EnsureEditable(rt, rt.R(p), rt.R(e));
        int ri = rt.Push(ret);
        int subidx = (int)((uint)(cnt - 1) >> level) & MASK;
        long insert;
        if (level == BITS) {
            insert = rt.R(t);
        } else {
            long child = NodeGet(rt, rt.R(ri), subidx);
            insert = Val.IsNil(child)
                ? NewPath(rt, level - BITS, rt.R(t), rt.R(e))
                : TPushTail(rt, cnt, level - BITS, child, rt.R(t), rt.R(e));
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
        if (cnt - TTailOff(rt, t) < WIDTH) {
            // Room in the working tail: written IN PLACE. Nothing is allocated
            // on this path, which is why `t` cannot have moved and is returned
            // as it came in.
            NodeSet(rt, rt.Slot(t, T_TAIL), cnt & MASK, rt.R(xi));
            rt.SetSlot(Val.AsHeap(t), T_CNT, Val.Fixnum(cnt + 1));
            rt.PopTo(bas);
            return t;
        }
        // Tail full: fold it into the trie and start a fresh one.
        int ei = rt.Push(rt.Slot(t, T_EDIT));
        int tn = rt.Push(rt.Slot(rt.R(ti), T_TAIL));
        int nt = rt.Push(NewNode(rt, WIDTH, rt.R(ei)));
        NodeSet(rt, rt.R(nt), 0, rt.R(xi));
        int sh = TShiftOf(rt, rt.R(ti));
        bool overflow = ((int)((uint) cnt >> BITS)) > (1 << sh);
        long newroot;
        int newshift;
        if (overflow) {
            int nri = rt.Push(NewNode(rt, WIDTH, rt.R(ei)));
            NodeSet(rt, rt.R(nri), 0, rt.Slot(rt.R(ti), T_ROOT));
            long path = NewPath(rt, sh, rt.R(tn), rt.R(ei));
            NodeSet(rt, rt.R(nri), 1, path);
            newroot = rt.R(nri);
            newshift = sh + BITS;
        } else {
            newroot = TPushTail(rt, cnt, sh, rt.Slot(rt.R(ti), T_ROOT), rt.R(tn), rt.R(ei));
            newshift = sh;
        }
        int nri2 = rt.Push(newroot);
        long tv = rt.R(ti);
        rt.SetSlot(Val.AsHeap(tv), T_ROOT, rt.R(nri2));
        rt.SetSlot(Val.AsHeap(tv), T_SHIFT, Val.Fixnum(newshift));
        rt.SetSlot(Val.AsHeap(tv), T_TAIL, rt.R(nt));
        rt.SetSlot(Val.AsHeap(tv), T_CNT, Val.Fixnum(cnt + 1));
        rt.PopTo(bas);
        return tv;
    }

    static long TArrayFor(Rt rt, long t, int i) {
        if (i >= TTailOff(rt, t)) return rt.Slot(t, T_TAIL);
        long node = rt.Slot(t, T_ROOT);
        int level = TShiftOf(rt, t);
        while (level > 0) {
            node = NodeGet(rt, node, (int)((uint) i >> level) & MASK);
            level -= BITS;
        }
        return node;
    }

    public static long TNth(Rt rt, long t, int i) {
        if (i < 0 || i >= TCount(rt, t)) return Val.NotFound;
        return NodeGet(rt, TArrayFor(rt, t, i), i & MASK);
    }

    static long TDoAssoc(Rt rt, int level, long node, int i, long val, long edit) {
        int bas = rt.Mark();
        int e = rt.Push(edit);
        int v = rt.Push(val);
        int ni = rt.Push(node);
        long ret = EnsureEditable(rt, rt.R(ni), rt.R(e));
        int ri = rt.Push(ret);
        if (level == 0) {
            NodeSet(rt, rt.R(ri), i & MASK, rt.R(v));
        } else {
            int subidx = (int)((uint) i >> level) & MASK;
            long child = NodeGet(rt, rt.R(ri), subidx);
            long nc = TDoAssoc(rt, level - BITS, child, i, rt.R(v), rt.R(e));
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
            NodeSet(rt, rt.Slot(t, T_TAIL), i & MASK, rt.R(xi));
        } else {
            long nr = TDoAssoc(rt, TShiftOf(rt, t), rt.Slot(t, T_ROOT), i,
                               rt.R(xi), rt.Slot(t, T_EDIT));
            rt.SetSlot(Val.AsHeap(rt.R(ti)), T_ROOT, nr);
        }
        long outv = rt.R(ti);
        rt.PopTo(bas);
        return outv;
    }

    /// `pop!`: the transient one shorter, IN PLACE.
    ///
    /// `clojure.core` had `(defn pop! [t] (flint.rt/pop t))` -- the persistent
    /// pop -- so on a transient vector it fell through to the seq branch and
    /// answered `()`. There was no builtin and, on either port, no
    /// implementation for one to call.
    public static long TPop(Rt rt, long t) {
        int cnt = TCount(rt, t);
        if (cnt == 0) return Val.Nil;   // the caller raises
        if (cnt == 1) {
            rt.SetSlot(Val.AsHeap(t), T_CNT, Val.Fixnum(0));
            return t;
        }
        if (((cnt - 1) & MASK) > 0) {
            rt.SetSlot(Val.AsHeap(t), T_CNT, Val.Fixnum(cnt - 1));
            return t;
        }
        int bas = rt.Mark();
        int ti = rt.Push(t);
        long newtail = TArrayFor(rt, t, cnt - 2);
        int nt = rt.Push(newtail);
        long tv = rt.R(ti);
        int sh = TShiftOf(rt, tv);
        long newroot = PopTail(rt, sh, rt.Slot(tv, T_ROOT), cnt);
        int newshift = sh;
        if (Val.IsNil(newroot)) {
            newroot = NewNode(rt, WIDTH, rt.Slot(rt.R(ti), T_EDIT));
        }
        int nri = rt.Push(newroot);
        if (newshift > BITS && Val.IsNil(NodeGet(rt, rt.R(nri), 1))) {
            rt.SetR(nri, NodeGet(rt, rt.R(nri), 0));
            newshift -= BITS;
        }
        tv = rt.R(ti);
        long a = Val.AsHeap(tv);
        rt.SetSlot(a, T_ROOT, rt.R(nri));
        rt.SetSlot(a, T_TAIL, rt.R(nt));
        rt.SetSlot(a, T_SHIFT, Val.Fixnum(newshift));
        rt.SetSlot(a, T_CNT, Val.Fixnum(cnt - 1));
        rt.PopTo(bas);
        return tv;
    }

    public static long TPersistent(Rt rt, long t) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int cnt = TCount(rt, t), shift = TShiftOf(rt, t), tailOff = TTailOff(rt, t);
        // Trim the 32-wide working tail down to what is actually used.
        int tr = rt.Push(NodeClone(rt, rt.Slot(t, T_TAIL), cnt - tailOff, Val.Nil));
        int ri = rt.Push(rt.Slot(rt.R(ti), T_ROOT));
        // Invalidate the handle: using it afterwards is a bug, not a silent
        // mutation of a value somebody else now owns.
        rt.SetSlot(Val.AsHeap(rt.R(ti)), T_EDIT, Val.Nil);
        long outv = NewVec(rt, cnt, shift, rt.R(ri), rt.R(tr), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }
}
