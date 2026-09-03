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
        return Val.Nil;   // a lazy seq or a range: walking it to count would force it
    }

    /// The ONE empty list, not a fresh one. See `Seqs.java` for why this
    /// allocated for so long, and what it cost.
    public static long EmptyList(Rt rt) {
        return rt.roots.shared.Singletons[Rt.SingEmptyList];
    }

    /// A lazy seq's slots. Outside the generated region, for the same reason
    /// as in `Seqs.java`.
    public const int LsThunk = 0, LsSeq = 1;

    // kin:begin kin/seqs.kin
    static long Vecseq(Rt rt, long v, int i) {
        int mk = rt.Mark();
        int vi = rt.Push(v);
        long a = rt.Alloc(Obj.TyVecseq, 3);
        if (a == 0) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        long vv = rt.R(vi);
        rt.SetSlot(a, 0, vv);
        rt.SetSlot(a, 1, Val.Fixnum(i));
        rt.SetSlot(a, 2, Val.Nil);
        rt.PopTo(mk);
        return Val.Heap(a);
    }
    static long Strseq(Rt rt, long s, int i) {
        int mk = rt.Mark();
        int si = rt.Push(s);
        long a = rt.Alloc(Obj.TyStrseq, 3);
        if (a == 0) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        long sv = rt.R(si);
        rt.SetSlot(a, 0, sv);
        rt.SetSlot(a, 1, Val.Fixnum(i));
        rt.SetSlot(a, 2, Val.Nil);
        rt.PopTo(mk);
        return Val.Heap(a);
    }
    public static long LazySeq(Rt rt, long thunk) {
        int mk = rt.Mark();
        int t = rt.Push(thunk);
        long a = rt.Alloc(Obj.TyLazyseq, 3);
        if (a == 0) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        long tv = rt.R(t);
        rt.SetSlot(a, LsThunk, tv);
        rt.SetSlot(a, LsSeq, Val.Nil);
        rt.SetSlot(a, 2, Val.Nil);
        rt.PopTo(mk);
        return Val.Heap(a);
    }
    public static long Range(Rt rt, long start, long end, long step) {
        int mk = rt.Mark();
        int s = rt.Push(start);
        int e = rt.Push(end);
        int st = rt.Push(step);
        long a = rt.Alloc(Obj.TyRange, 4);
        if (a == 0) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        long sv = rt.R(s);
        rt.SetSlot(a, 0, sv);
        long ev = rt.R(e);
        rt.SetSlot(a, 1, ev);
        long stv = rt.R(st);
        rt.SetSlot(a, 2, stv);
        rt.SetSlot(a, 3, Val.Nil);
        rt.PopTo(mk);
        return Val.Heap(a);
    }
    static bool RangeEmpty(Rt rt, long v) {
        long e = rt.Slot(v, 1);
        // An absent end is an UNBOUNDED range, which is never empty.
        if (Val.IsNil(e)) {
            return false;
        }
        double s = Num.F64(rt, rt.Slot(v, 0));
        double en = Num.F64(rt, e);
        double st = Num.F64(rt, rt.Slot(v, 2));
        if (st > 0.0) {
            return s >= en;
        }
        if (st < 0.0) {
            return s <= en;
        }
        // A zero step never advances. Empty rather than infinite, which
        // is what Clojure does and is the answer that terminates.
        return true;
    }

    // kin:end kin/seqs.kin

    public static long Force(Rt rt, long ls) {
        long thunk = rt.Slot(ls, LsThunk);
        if (Val.IsNil(thunk)) return rt.Slot(ls, LsSeq);
        int bas = rt.Mark();
        int li = rt.Push(ls);
        int vi = rt.Push(rt.Call(thunk, System.Array.Empty<long>()));
        while (Val.IsHeap(rt.R(vi)) && Obj.Ty(rt.gc.sp, Val.AsHeap(rt.R(vi))) == Obj.TyLazyseq) {
            long t2 = rt.Slot(rt.R(vi), LsThunk);
            if (Val.IsNil(t2)) { rt.SetR(vi, rt.Slot(rt.R(vi), LsSeq)); break; }
            rt.SetR(vi, rt.Call(t2, System.Array.Empty<long>()));
        }
        long cur = rt.R(vi);
        long l = rt.R(li);
        rt.PopTo(bas);
        rt.SetSlot(Val.AsHeap(l), LsThunk, Val.Nil);
        rt.SetSlot(Val.AsHeap(l), LsSeq, cur);
        return cur;
    }

    /// `seq`: nil for an empty collection, otherwise a seq object.
    ///
    /// NIL rather than an empty seq is the whole convention -- `(seq [])` is
    /// nil, and every `while (s)` loop in the library depends on it.
    public static long Seq(Rt rt, long v) {
        if (Val.IsNil(v)) return Val.Nil;
        if (Str.IsString(rt, v)) return Str.CharLen(rt, v) == 0 ? Val.Nil : Strseq(rt, v, 0);
        if (!Val.IsHeap(v)) return Val.Nil;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        switch (t) {
            case Obj.TyEmptyList: return Val.Nil;
            case Obj.TyCons:
            case Obj.TyVecseq: return v;
            case Obj.TyVec: return Vec.Count(rt, v) == 0 ? Val.Nil : Vecseq(rt, v, 0);
            case Obj.TyMapentry: return Vecseq(rt, EntryAsVec(rt, v), 0);
            // Iterating a table hands back REFS, materialising nothing. It
            // rides on `Vecseq` because a table is indexed and counted exactly
            // as a vector is; only `First` differs (`doc/decisions/0026`).
            case Obj.TyTable:
                return Table.tableCount(rt, v) == 0 ? Val.Nil : Vecseq(rt, v, 0);
            // A ref materialises HERE and only here: `seq`, `=` and `hash` all
            // want the whole row and each is O(columns) anyway.
            case Obj.TyTableref: return Seq(rt, Table.refToMap(rt, v));
            case Obj.TyStrseq: return v;
            case Obj.TyRange: return RangeEmpty(rt, v) ? Val.Nil : v;
            case Obj.TyLazyseq: {
                int bas = rt.Mark();
                int fi = rt.Push(Force(rt, v));
                long outv = Val.IsNil(rt.R(fi)) ? Val.Nil : Seq(rt, rt.R(fi));
                rt.PopTo(bas);
                return outv;
            }
            case Obj.TySet: {
                if (Sets.Count(rt, v) == 0) return Val.Nil;
                int bas = rt.Mark();
                int ev = rt.Push(Sets.ElementVector(rt, v));
                long outv = Vecseq(rt, rt.R(ev), 0);
                rt.PopTo(bas);
                return outv;
            }
            case Obj.TyArraymap:
            case Obj.TyHashmap: {
                if (Maps.Count(rt, v) == 0) return Val.Nil;
                int bas = rt.Mark();
                int ev = rt.Push(Maps.EntryVector(rt, v));
                long outv = Vecseq(rt, rt.R(ev), 0);
                rt.PopTo(bas);
                return outv;
            }
            default:
                return rt.ThrowStr("UnsupportedOperationException", 
                    "seq over " + rt.Describe(v) + " needs more of the data structures");
        }
    }

    /// A map entry read as the two-element vector `[k v]`. Clojure's entries
    /// ARE sequential, which is what lets `(first {:a 1})` destructure.
    static long EntryAsVec(Rt rt, long e) {
        int bas = rt.Mark();
        int ei = rt.Push(e);
        int vi = rt.Push(Vec.Empty(rt));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.Slot(rt.R(ei), 0)));
        rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.Slot(rt.R(ei), 1)));
        long outv = rt.R(vi);
        rt.PopTo(bas);
        return outv;
    }

    public static long First(Rt rt, long v) {
        long s = Seq(rt, v);
        if (Val.IsNil(s)) return Val.Nil;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(s));
        if (t == Obj.TyCons) return rt.Slot(s, CFirst);
        if (t == Obj.TyVecseq) {
            long coll = rt.Slot(s, 0);
            int i = (int) Val.AsFixnum(rt.Slot(s, 1));
            // A TABLE rides on `TyVecseq`; only this differs, and it differs by
            // handing back a ref (`doc/decisions/0026`).
            if (Table.isTable(rt, coll)) return Table.tableRef(rt, coll, i);
            return Vec.Nth(rt, coll, i);
        }
        if (t == Obj.TyStrseq) return Str.Nth(rt, rt.Slot(s, 0), (int) Val.AsFixnum(rt.Slot(s, 1)));
        if (t == Obj.TyRange) return rt.Slot(s, 0);
        return rt.ThrowStr("UnsupportedOperationException", "first over " + rt.Describe(v));
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
            int cnt = Table.isTable(rt, vec) ? Table.tableCount(rt, vec) : Vec.Count(rt, vec);
            return i >= cnt ? Val.Nil : Vecseq(rt, vec, i);
        }
        if (t == Obj.TyStrseq) {
            long str = rt.Slot(s, 0);
            int i = (int) Val.AsFixnum(rt.Slot(s, 1)) + 1;
            return i >= Str.CharLen(rt, str) ? Val.Nil : Strseq(rt, str, i);
        }
        if (t == Obj.TyRange) {
            // A fresh range, not a mutated cursor: a range IS a persistent
            // value, so walking one must not disturb anything else holding it.
            int bas = rt.Mark();
            int ri = rt.Push(s);
            int ni = rt.Push(Num.Add(rt, rt.Slot(rt.R(ri), 0), rt.Slot(rt.R(ri), 2)));
            long nr = Range(rt, rt.R(ni), rt.Slot(rt.R(ri), 1), rt.Slot(rt.R(ri), 2));
            int nri = rt.Push(nr);
            long outv = RangeEmpty(rt, rt.R(nri)) ? Val.Nil : rt.R(nri);
            rt.PopTo(bas);
            return outv;
        }
        return rt.ThrowStr("UnsupportedOperationException", "next over " + rt.Describe(v));
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
