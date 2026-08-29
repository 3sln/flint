package com.flint.rt;

import static com.flint.rt.Obj.*;

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
    private Seqs() {}

    public static final int C_FIRST = 0, C_REST = 1, C_META = 2, C_COUNT = 3;

    /// Rooted across the allocation and read back afterwards. `cons` is the
    /// function the Rust's comment singles out as getting this right, and the
    /// reason is that both arguments are live across an `alloc` that can move
    /// them.
    public static long cons(Rt rt, long head, long tail) {
        int base = rt.mark();
        int h = rt.push(head);
        int t = rt.push(tail);
        long a = rt.alloc(TY_CONS, 4);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        long cnt = countHint(rt, rt.r(t));
        rt.setSlot(a, C_FIRST, rt.r(h));
        rt.setSlot(a, C_REST, rt.r(t));
        rt.setSlot(a, C_META, Val.NIL);
        rt.setSlot(a, C_COUNT, cnt);
        rt.popTo(base);
        return Val.heap(a);
    }

    /// A count if it is known WITHOUT walking, else nil. Keeping it on the cons
    /// is what makes `count` O(1) on a list built by consing.
    static long countHint(Rt rt, long v) {
        if (Val.isNil(v)) return Val.fixnum(1);
        if (!Val.isHeap(v)) return Val.NIL;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        if (t == TY_EMPTY_LIST) return Val.fixnum(1);
        if (t == TY_CONS) {
            long c = rt.slot(v, C_COUNT);
            return Val.isFixnum(c) ? Val.fixnum(Val.asFixnum(c) + 1) : Val.NIL;
        }
        if (t == TY_VEC) return Val.fixnum(Vec.count(rt, v) + 1);
        return Val.NIL;
    }

    public static long emptyList(Rt rt) {
        long a = rt.alloc(TY_EMPTY_LIST, 1);
        if (a == 0) return Val.NIL;
        rt.setSlot(a, 0, Val.NIL);
        return Val.heap(a);
    }

    static long vecseq(Rt rt, long v, int i) {
        int base = rt.mark();
        int vi = rt.push(v);
        long a = rt.alloc(TY_VECSEQ, 3);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, 0, rt.r(vi));
        rt.setSlot(a, 1, Val.fixnum(i));
        rt.setSlot(a, 2, Val.NIL);
        rt.popTo(base);
        return Val.heap(a);
    }

    /// `seq`: nil for an empty collection, otherwise a seq object.
    ///
    /// NIL rather than an empty seq is the whole convention -- `(seq [])` is
    /// nil, and every `while (s)` loop in the library depends on it.
    public static long seq(Rt rt, long v) {
        if (Val.isNil(v)) return Val.NIL;
        if (!Val.isHeap(v)) return Val.NIL;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        switch (t) {
            case TY_EMPTY_LIST: return Val.NIL;
            case TY_CONS: case TY_VECSEQ: return v;
            case TY_VEC: return Vec.count(rt, v) == 0 ? Val.NIL : vecseq(rt, v, 0);
            default:
                throw new UnsupportedOperationException(
                    "seq over object type " + t + " needs more of the data structures");
        }
    }

    public static long first(Rt rt, long v) {
        long s = seq(rt, v);
        if (Val.isNil(s)) return Val.NIL;
        int t = ty(rt.gc.sp, Val.asHeap(s));
        if (t == TY_CONS) return rt.slot(s, C_FIRST);
        if (t == TY_VECSEQ) {
            return Vec.nth(rt, rt.slot(s, 0), (int) Val.asFixnum(rt.slot(s, 1)));
        }
        throw new UnsupportedOperationException("first over object type " + t);
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
            return i >= Vec.count(rt, vec) ? Val.NIL : vecseq(rt, vec, i);
        }
        throw new UnsupportedOperationException("next over object type " + t);
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
