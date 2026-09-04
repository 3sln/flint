namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Seqcore;
using static _3sln.Flint.Kgen.Rt.Seqwalk;

using _3sln.Flint.Kgen.Rt;

using static _3sln.Flint.Kgen.Rt.Seqs;

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

    /// `First` and `Next`, under the names their callers already use -- the
    /// bodies are generated, as `Seqwalk`.
    public static long First(Rt rt, long v) { return _3sln.Flint.Kgen.Rt.Seqwalk.First(rt, v); }
    public static long Next(Rt rt, long v) { return _3sln.Flint.Kgen.Rt.Seqwalk.Next(rt, v); }

    /// `Cons`, under the name its callers already use -- the body is
    /// generated, as `Seqcore.Cons`.
    public static long Cons(Rt rt, long head, long tail) { return _3sln.Flint.Kgen.Rt.Seqcore.Cons(rt, head, tail); }
    public const int C_FIRST = 0, C_REST = 1, C_META = 2, C_COUNT = 3;



    /// The ONE empty list, not a fresh one. See `Seqs.java` for why this
    /// allocated for so long, and what it cost.
    public static long EmptyList(Rt rt) {
        return rt.roots.shared.Singletons[Rt.SingEmptyList];
    }

    /// A lazy seq's slots. Outside the generated region, for the same reason
    /// as in `Seqs.java`.
    public const int LsThunk = 0, LsSeq = 1;


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
            // A vecseq over the ENTRY ITSELF -- see the JVM's `seq`.
            case Obj.TyMapentry: return Vecseq(rt, v, 0);
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
                if (Mapcore.MapCount(rt, v) == 0) return Val.Nil;
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
