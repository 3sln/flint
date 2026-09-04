package com.flint.rt;

import flint.rt.Mapcore;

import static com.flint.rt.Obj.*;
import static flint.rt.Seqcore.*;
import static flint.rt.Seqs.*;

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

    /// `cons`, under the name its callers already use. The body is GENERATED,
    /// as `Seqcore.cons`; renaming ~20 call sites across this runtime for a
    /// naming win is the trade `doc/goals/kin-port.md` answered with "LAST".
    public static long cons(Rt rt, long head, long tail) { return flint.rt.Seqcore.cons(rt, head, tail); }
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


    public static long force(Rt rt, long ls) {
        long thunk = rt.slot(ls, LS_THUNK);
        if (Val.isNil(thunk)) return rt.slot(ls, LS_SEQ);
        int base = rt.mark();
        int li = rt.push(ls);
        int vi = rt.push(rt.call(thunk, new long[0]));
        while (Val.isHeap(rt.r(vi)) && ty(rt.gc.sp, Val.asHeap(rt.r(vi))) == TY_LAZYSEQ) {
            long t2 = rt.slot(rt.r(vi), LS_THUNK);
            if (Val.isNil(t2)) { rt.setR(vi, rt.slot(rt.r(vi), LS_SEQ)); break; }
            rt.setR(vi, rt.call(t2, new long[0]));
        }
        long cur = rt.r(vi);
        long l = rt.r(li);
        rt.popTo(base);
        rt.setSlot(Val.asHeap(l), LS_THUNK, Val.NIL);
        rt.setSlot(Val.asHeap(l), LS_SEQ, cur);
        return cur;
    }

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


    public static long first(Rt rt, long v) {
        long s = seq(rt, v);
        if (Val.isNil(s)) return Val.NIL;
        int t = ty(rt.gc.sp, Val.asHeap(s));
        if (t == TY_CONS) return rt.slot(s, C_FIRST);
        if (t == TY_VECSEQ) {
            long coll = rt.slot(s, 0);
            int i = (int) Val.asFixnum(rt.slot(s, 1));
            // A TABLE rides on `TY_VECSEQ`; only `first` differs, and it
            // differs by handing back a ref (`doc/decisions/0026`).
            if (rt.isHeapTy(coll, TY_MAPENTRY)) return rt.slot(coll, i);
            if (Table.isTable(rt, coll)) return Table.tableRef(rt, coll, i);
            // NIL, not NOT_FOUND, matching the native runtime. A vecseq's
            // index is always inside its collection -- `seq` starts at 0 with
            // a non-empty one and `next` bounds-checks -- so this default is
            // unreachable, and it was answering a sentinel the caller would
            // have had to know about.
            return Vec.nth(rt, coll, i, Val.NIL);
        }
        if (t == TY_STRSEQ) return Str.nth(rt, rt.slot(s, 0), (int) Val.asFixnum(rt.slot(s, 1)), Val.NIL);
        if (t == TY_RANGE) return rt.slot(s, 0);
        // NIL, matching the native runtime, and UNREACHABLE either way: `seq`
        // answers nil or a cons, vecseq, strseq or range, and every one of
        // those is handled above. Probed over twelve conformance suites and a
        // deliberately adversarial set of non-seqs -- zero hits on both.
        //
        // The ports raised here. The diagnostic is not lost in practice, and
        // keeping it would mean `^:throws` on `first`, which turns its Rust
        // signature into `Result<Value, String>` at every call site for an arm
        // nothing can reach.
        return Val.NIL;
    }

    /// `next`: the rest, or NIL when there is none. `rest` differs -- it gives
    /// an empty seq rather than nil -- and conflating them is a classic bug.
    public static long next(Rt rt, long v) {
        long s = seq(rt, v);
        if (Val.isNil(s)) return Val.NIL;
        int t = ty(rt.gc.sp, Val.asHeap(s));
        if (t == TY_CONS) return seq(rt, rt.slot(s, C_REST));
        if (t == TY_VECSEQ) {
            long vec = rt.slot(s, 0);
            int i = (int) Val.asFixnum(rt.slot(s, 1)) + 1;
            int n = rt.isHeapTy(vec, TY_MAPENTRY) ? 2
                  : Table.isTable(rt, vec) ? Table.tableCount(rt, vec)
                  : Vec.count(rt, vec);
            return i >= n ? Val.NIL : vecseq(rt, vec, i);
        }
        if (t == TY_STRSEQ) {
            long str = rt.slot(s, 0);
            int i = (int) Val.asFixnum(rt.slot(s, 1)) + 1;
            return i >= Str.charLen(rt, str) ? Val.NIL : strseq(rt, str, i);
        }
        if (t == TY_RANGE) {
            // A fresh range, not a mutated cursor: a range IS a persistent
            // value, so walking one must not disturb anything else holding it.
            int base = rt.mark();
            int ri = rt.push(s);
            int ni = rt.push(Num.add(rt, rt.slot(rt.r(ri), 0), rt.slot(rt.r(ri), 2)));
            long nr = range(rt, rt.r(ni), rt.slot(rt.r(ri), 1), rt.slot(rt.r(ri), 2));
            int nri = rt.push(nr);
            long out = rangeEmpty(rt, rt.r(nri)) ? Val.NIL : rt.r(nri);
            rt.popTo(base);
            return out;
        }
        return rt.throwStr("UnsupportedOperationException", "next over " + rt.describe(v));
    }

    public static long rest(Rt rt, long v) {
        long n = next(rt, v);
        return Val.isNil(n) ? emptyList(rt) : n;
    }

    public static int count(Rt rt, long v) {
        int n = 0;
        long s = seq(rt, v);
        while (!Val.isNil(s)) { n++; s = next(rt, s); }
        return n;
    }

    /// A list from `n` values rooted at `base`. What a variadic arity folds its
    /// surplus arguments into -- and it must be a SEQ, not a vector:
    /// `clojure.core/list` is `[& xs] xs`, so a vector here makes `(list 1 2)`
    /// print as `[1 2]`, the right elements in the wrong shape.
    public static long fromRoots(Rt rt, int base, int n) {
        int mk = rt.mark();
        int acc = rt.push(emptyList(rt));
        for (int i = n - 1; i >= 0; i--) {
            long c = cons(rt, rt.r(base + i), rt.r(acc));
            rt.setR(acc, c);
        }
        long out = rt.r(acc);
        rt.popTo(mk);
        return out;
    }
}
