package com.flint.rt;

import com._3sln.flint.kgen.rt.Mapcore;

import static com.flint.rt.Obj.*;
import static com._3sln.flint.kgen.rt.Seqcore.*;
import static com._3sln.flint.kgen.rt.Seqwalk.*;
import static com._3sln.flint.kgen.rt.Seqs.*;

/// Seqs, ported from `runtime/src/seqs.rs`.
///
/// <pre>
///   TY_CONS       [first, rest, meta, count]   count = fixnum, or nil if unknown
///   TY_EMPTY_LIST [meta]
///   TY_LAZYSEQ    [thunk, seq, meta]           thunk becomes nil once forced
///   TY_VECSEQ     [vec, index, meta]
///   TY_RANGE      [start, end, step, meta]
/// </pre>
///
/// `seq` over a vector is a VECSEQ -- a cursor, not a copy -- so walking one
/// allocates a small object per step rather than materialising anything.
public final class Seqs {

    /// `first` and `next`, under the names their callers already use. The
    /// bodies are GENERATED, as `Seqwalk`; some thirty call sites across this
    /// runtime say `Seqs.first`, in files that have nothing to do with seqs.
    public static long first(Rt rt, long v) { return com._3sln.flint.kgen.rt.Seqwalk.first(rt, v); }
    public static long next(Rt rt, long v) { return com._3sln.flint.kgen.rt.Seqwalk.next(rt, v); }
    public static long rest(Rt rt, long v) { return com._3sln.flint.kgen.rt.Seqwalk.rest(rt, v); }
    public static long force(Rt rt, long ls) { return com._3sln.flint.kgen.rt.Seqwalk.force(rt, ls); }

    /// `cons`, under the name its callers already use. The body is GENERATED,
    /// as `Seqcore.cons`; renaming ~20 call sites across this runtime for a
    /// naming win is the trade `doc/goals/kin-port.md` answered with "LAST".
    public static long cons(Rt rt, long head, long tail) { return com._3sln.flint.kgen.rt.Seqcore.cons(rt, head, tail); }
    private Seqs() {}

    public static final int C_FIRST = 0, C_REST = 1, C_META = 2, C_COUNT = 3;



    /// The ONE empty list, not a fresh one.
    ///
    /// This allocated on every call while `initSingletons` wrote
    /// `SING_EMPTY_LIST` and nothing ever read it. `Rt`'s own comment records
    /// the same defect being fixed for maps, vectors and sets, with the
    /// numbers: the same program billed 143,247 steps here against 137,207
    /// native, because it allocated 346,928 bytes against 299,024. `Seqs` was
    /// missed. A budget that fits on one runtime has to fit on the others.
    public static long emptyList(Rt rt) {
        return rt.roots.shared.singletons[Rt.SING_EMPTY_LIST];
    }

    /// A lazy seq's slots. Outside the generated region, because kin has no
    /// form for a constant declaration and a region takes everything in its
    /// span -- which is how this was silently deleted the first time.
    public static final int LS_THUNK = 0, LS_SEQ = 1;



    /// `seq`: nil for an empty collection, otherwise a seq object.
    ///
    /// NIL rather than an empty seq is the whole convention -- `(seq [])` is
    /// nil, and every `while (s)` loop in the library depends on it.
    public static long seq(Rt rt, long v) {
        if (Val.isNil(v)) return Val.NIL;
        if (Str.isString(rt, v)) return Str.charLen(rt, v) == 0 ? Val.NIL : strseq(rt, v, 0);
        if (!Val.isHeap(v)) return Val.NIL;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        switch (t) {
            case TY_EMPTY_LIST: return Val.NIL;
            case TY_CONS: case TY_VECSEQ: return v;
            case TY_VEC: return Vec.count(rt, v) == 0 ? Val.NIL : vecseq(rt, v, 0);
            // A vecseq over the ENTRY ITSELF, not over a vector copied out
            // of it. This used to call an `entryAsVec` helper -- now deleted,
            // since nothing else wanted it -- which built a two-element vector
            // on every `seq` of a map entry, which is every entry of every map
            // walked. The native runtime never did: it rides the same vecseq
            // and reads the entry directly, and `first` and `next` below know
            // the tag. That tag check is the whole cost of not allocating.
            //
            // MEASURED at 8 400 steps on a 200-entry walk -- 60 531 against
            // 52 131, 13.9%, or 42 steps per entry.
            //
            // It was tried once before and reverted, because it broke table
            // equality. That turned out to be three ROOTING bugs in `Eq` that
            // this only changed the allocation timing of; they are fixed and
            // `conform` runs every suite under GC stress now.
            case TY_MAPENTRY: return vecseq(rt, v, 0);
            // Iterating a table hands back REFS, one per row, materialising
            // nothing -- the point of the ref type, not a detail of it. It
            // rides on `vecseq` because a table is indexed and counted exactly
            // as a vector is; only `first` differs (`doc/decisions/0026`).
            case Obj.TY_TABLE:
                return Table.tableCount(rt, v) == 0 ? Val.NIL : vecseq(rt, v, 0);
            // A ref materialises HERE and only here: `seq`, `=` and `hash` all
            // want the whole row and each is O(columns) anyway. `get` and
            // `(:name row)` never come through.
            case Obj.TY_TABLEREF: return seq(rt, Table.refToMap(rt, v));
            case TY_STRSEQ: return v;
            case TY_RANGE: return rangeEmpty(rt, v) ? Val.NIL : v;
            case TY_LAZYSEQ: {
                int base = rt.mark();
                int fi = rt.push(force(rt, v));
                long out = Val.isNil(rt.r(fi)) ? Val.NIL : seq(rt, rt.r(fi));
                rt.popTo(base);
                return out;
            }
            case TY_SET: {
                if (Sets.count(rt, v) == 0) return Val.NIL;
                int base = rt.mark();
                int ev = rt.push(Sets.elementVector(rt, v));
                long out = vecseq(rt, rt.r(ev), 0);
                rt.popTo(base);
                return out;
            }
            case TY_ARRAYMAP:
            case TY_HASHMAP: {
                if (Mapcore.mapCount(rt, v) == 0) return Val.NIL;
                int base = rt.mark();
                int ev = rt.push(Maps.entryVector(rt, v));
                long out = vecseq(rt, rt.r(ev), 0);
                rt.popTo(base);
                return out;
            }
            default:
                return rt.throwStr("UnsupportedOperationException", 
                    "seq over " + rt.describe(v) + " needs more of the data structures");
        }
    }





    public static int count(Rt rt, long v) { return com._3sln.flint.kgen.rt.Seqwalk.seqCount(rt, v); }

    public static long fromRoots(Rt rt, int base, int n) { return com._3sln.flint.kgen.rt.Seqcore.listFromRoots(rt, base, n); }
}
