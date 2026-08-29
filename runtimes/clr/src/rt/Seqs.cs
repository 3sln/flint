namespace Flint.Rt;

/// Seqs, ported from `runtime/src/seqs.rs`.
///
///   TY_CONS       [first, rest, meta, count]   count = fixnum, or nil if unknown
///   TY_EMPTY_LIST [meta]
///   TY_LAZYSEQ    [thunk, seq, meta]           thunk becomes nil once forced
///   TY_VECSEQ     [vec, index, meta]
///   TY_RANGE      [start, end, step, meta]
///
/// `seq` over a vector is a VECSEQ -- a cursor, not a copy -- so walking one
/// allocates a small object per step rather than materialising anything.
public static class Seqs {
    public const int CFirst = 0, CRest = 1, CMeta = 2, CCount = 3;

    /// Rooted across the allocation and read back afterwards. `cons` is the
    /// function the Rust's comment singles out as getting this right, and the
    /// reason is that both arguments are live across an `Alloc` that can move
    /// them.
    public static long Cons(Rt rt, long head, long tail) {
        int bas = rt.Mark();
        int h = rt.Push(head);
        int t = rt.Push(tail);
        long a = rt.Alloc(Obj.TyCons, 4);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        long cnt = CountHint(rt, rt.R(t));
        rt.SetSlot(a, CFirst, rt.R(h));
        rt.SetSlot(a, CRest, rt.R(t));
        rt.SetSlot(a, CMeta, Val.Nil);
        rt.SetSlot(a, CCount, cnt);
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// A count if it is known WITHOUT walking, else nil. Keeping it on the cons
    /// is what makes `count` O(1) on a list built by consing.
    static long CountHint(Rt rt, long v) {
        if (Val.IsNil(v)) return Val.Fixnum(1);
        if (!Val.IsHeap(v)) return Val.Nil;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        if (t == Obj.TyEmptyList) return Val.Fixnum(1);
        if (t == Obj.TyCons) {
            long c = rt.Slot(v, CCount);
            return Val.IsFixnum(c) ? Val.Fixnum(Val.AsFixnum(c) + 1) : Val.Nil;
        }
        if (t == Obj.TyVec) return Val.Fixnum(Vec.Count(rt, v) + 1);
        return Val.Nil;
    }

    public static long EmptyList(Rt rt) {
        long a = rt.Alloc(Obj.TyEmptyList, 1);
        if (a == 0) return Val.Nil;
        rt.SetSlot(a, 0, Val.Nil);
        return Val.Heap(a);
    }

    static long Vecseq(Rt rt, long v, int i) {
        int bas = rt.Mark();
        int vi = rt.Push(v);
        long a = rt.Alloc(Obj.TyVecseq, 3);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, 0, rt.R(vi));
        rt.SetSlot(a, 1, Val.Fixnum(i));
        rt.SetSlot(a, 2, Val.Nil);
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// `seq`: nil for an empty collection, otherwise a seq object.
    ///
    /// NIL rather than an empty seq is the whole convention -- `(seq [])` is
    /// nil, and every `while (s)` loop in the library depends on it.
    public static long Seq(Rt rt, long v) {
        if (Val.IsNil(v)) return Val.Nil;
        if (!Val.IsHeap(v)) return Val.Nil;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        switch (t) {
            case Obj.TyEmptyList: return Val.Nil;
            case Obj.TyCons:
            case Obj.TyVecseq: return v;
            case Obj.TyVec: return Vec.Count(rt, v) == 0 ? Val.Nil : Vecseq(rt, v, 0);
            default:
                throw new System.NotSupportedException(
                    "seq over object type " + t + " needs more of the data structures");
        }
    }

    public static long First(Rt rt, long v) {
        long s = Seq(rt, v);
        if (Val.IsNil(s)) return Val.Nil;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(s));
        if (t == Obj.TyCons) return rt.Slot(s, CFirst);
        if (t == Obj.TyVecseq) return Vec.Nth(rt, rt.Slot(s, 0), (int) Val.AsFixnum(rt.Slot(s, 1)));
        throw new System.NotSupportedException("first over object type " + t);
    }

    /// `next`: the rest, or NIL when there is none. `rest` differs -- it gives
    /// an empty seq rather than nil -- and conflating them is a classic bug.
    public static long Next(Rt rt, long v) {
        long s = Seq(rt, v);
        if (Val.IsNil(s)) return Val.Nil;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(s));
        if (t == Obj.TyCons) return Seq(rt, rt.Slot(s, CRest));
        if (t == Obj.TyVecseq) {
            long vec = rt.Slot(s, 0);
            int i = (int) Val.AsFixnum(rt.Slot(s, 1)) + 1;
            return i >= Vec.Count(rt, vec) ? Val.Nil : Vecseq(rt, vec, i);
        }
        throw new System.NotSupportedException("next over object type " + t);
    }

    public static long Rest(Rt rt, long v) {
        long n = Next(rt, v);
        return Val.IsNil(n) ? EmptyList(rt) : n;
    }

    public static int Count(Rt rt, long v) {
        int n = 0;
        long s = Seq(rt, v);
        while (!Val.IsNil(s)) { n++; s = Next(rt, s); }
        return n;
    }

    /// A list from `n` values rooted at `bas`. What a variadic arity folds its
    /// surplus arguments into -- and it must be a SEQ, not a vector:
    /// `clojure.core/list` is `[& xs] xs`, so a vector here makes `(list 1 2)`
    /// print as `[1 2]`, the right elements in the wrong shape.
    public static long FromRoots(Rt rt, int bas, int n) {
        int mk = rt.Mark();
        int acc = rt.Push(EmptyList(rt));
        for (int i = n - 1; i >= 0; i--) {
            long c = Cons(rt, rt.R(bas + i), rt.R(acc));
            rt.SetR(acc, c);
        }
        long outv = rt.R(acc);
        rt.PopTo(mk);
        return outv;
    }
}
