namespace Flint.Rt;

using static flint.rt.Vecnode;
using static flint.rt.Vecread;
using static flint.rt.Vecwrite;
using static flint.rt.Vecassoc;

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
    static long Root(Rt rt, long v) => rt.Slot(v, V_ROOT);
    static long Tail(Rt rt, long v) => rt.Slot(v, V_TAIL);


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
    /// `conj`, under the name 53 call sites in this runtime already use.
    ///
    /// The body is GENERATED, as `Vecwrite.VecConj`, and Rust's callers say
    /// `vec_conj` directly. Renaming these would be 53 edits here and 53 more
    /// on the JVM, in files that have nothing to do with vectors, for a naming
    /// win -- which is the trade `doc/goals/kin-port.md` already recorded
    /// against the `champ_*` wrappers and answered with "worth doing LAST".
    public static long Conj(Rt rt, long v, long x) { return VecConj(rt, v, x); }
    /// `assoc` and `pop`, likewise delegating to their generated bodies.
    public static long Assoc(Rt rt, long v, int i, long x) { return VecAssoc(rt, v, i, x); }
    public static long Pop(Rt rt, long v) { return VecPop(rt, v); }

    public static long Nth(Rt rt, long v, int i) {
        if (i < 0 || i >= Count(rt, v)) return Val.NotFound;
        return NodeGet(rt, ArrayFor(rt, v, i), i & MASK);
    }








    public static long FromRoots(Rt rt, int bas, int n) {
        int mk = rt.Mark();
        int vi = rt.Push(Empty(rt));
        for (int i = 0; i < n; i++) {
            long nv = VecConj(rt, rt.R(vi), rt.R(bas + i));
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
        int cnt = Count(rt, vv), shift = VecShift(rt, vv);
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
