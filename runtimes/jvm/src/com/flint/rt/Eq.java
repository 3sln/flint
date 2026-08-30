package com.flint.rt;

import static com.flint.rt.Obj.*;

/// Structural equality and hashing, ported from `runtime/src/eq.rs`.
///
/// These two belong together because they must AGREE: two values that are `=`
/// must hash alike, or a map loses keys it is holding. That is why the same
/// file decides both, and why every case added to one is added to the other.
///
/// `eq` on a compound value CAN ALLOCATE -- it seqs both sides -- so a caller
/// holding heap addresses across a call to it must have them rooted. The map
/// code does; that requirement is the reason `nodeFind` roots its walk.
public final class Eq {
    private Eq() {}

    public static boolean eq(Rt rt, long a, long b) {
        if (a == b) return true;
        if (Val.isFixnum(a) && Val.isFixnum(b)) return Val.asFixnum(a) == Val.asFixnum(b);
        if (Val.isDouble(a) && Val.isDouble(b)) return Val.asDouble(a) == Val.asDouble(b);
        if (Str.isString(rt, a) && Str.isString(rt, b)) {
            return java.util.Arrays.equals(Str.bytes(rt, a), Str.bytes(rt, b));
        }
        boolean ka = Val.isInlineKw(a) || rt.isHeapTy(a, TY_KW);
        boolean kb = Val.isInlineKw(b) || rt.isHeapTy(b, TY_KW);
        if (ka || kb) {
            if (!(ka && kb)) return false;
            // Both inline, or both interned: identity IS equality, which is the
            // whole point of `doc/decisions/0011`'s tiers. A keyword short
            // enough to be inline is never on the heap, and one long enough to
            // be on the heap is interned, so the two forms never meet.
            return a == b;
        }
        // Symbols are interned too, so `a == b` above already answered it. Two
        // distinct symbol objects with the same name would be an interning bug
        // rather than a case to handle, and comparing slots here would HIDE it.
        if (rt.isHeapTy(a, TY_SYM) || rt.isHeapTy(b, TY_SYM)) return false;
        if (rt.isHeapTy(a, TY_VEC) && rt.isHeapTy(b, TY_VEC)) {
            int n = Vec.count(rt, a);
            if (n != Vec.count(rt, b)) return false;
            for (int i = 0; i < n; i++) {
                if (!eq(rt, Vec.nth(rt, a, i), Vec.nth(rt, b, i))) return false;
            }
            return true;
        }
        if (Maps.isMap(rt, a) && Maps.isMap(rt, b)) return Maps.eq(rt, a, b);
        // A vector and a seq holding the same elements ARE equal in Clojure:
        // `=` is over the sequential abstraction, not the concrete type.
        boolean sa = rt.isSequential(a);
        boolean sb = rt.isSequential(b);
        if (sa && sb) {
            int base = rt.mark();
            int x = rt.push(Seqs.seq(rt, a)), y = rt.push(Seqs.seq(rt, b));
            boolean ok = true;
            for (;;) {
                boolean ex = Val.isNil(rt.r(x)), ey = Val.isNil(rt.r(y));
                if (ex || ey) { ok = ex && ey; break; }
                if (!eq(rt, Seqs.first(rt, rt.r(x)), Seqs.first(rt, rt.r(y)))) { ok = false; break; }
                long nx = Seqs.next(rt, rt.r(x));
                long ny = Seqs.next(rt, rt.r(y));
                rt.setR(x, nx);
                rt.setR(y, ny);
            }
            rt.popTo(base);
            return ok;
        }
        if (Val.isHeap(a) || Val.isHeap(b)) {
            if (Val.isHeap(a) && Val.isHeap(b)
                && ty(rt.gc.sp, Val.asHeap(a)) != ty(rt.gc.sp, Val.asHeap(b))) {
                return false;
            }
            throw new UnsupportedOperationException(
                "= over " + rt.describe(a) + " and " + rt.describe(b) + " needs more of the data structures");
        }
        return false;
    }

    /// The hash of a value, agreeing with `eq` above and with Clojure's.
    public static int hashValue(Rt rt, long v) {
        if (Val.isDouble(v)) return Hash.hashDouble(Val.asDouble(v));
        if (Val.isNil(v)) return 0;
        if (v == Val.TRUE) return Hash.HASH_TRUE;
        if (v == Val.FALSE) return Hash.HASH_FALSE;
        if (Val.isFixnum(v)) return Hash.hashLong(Val.asFixnum(v));
        if (Val.isInlineStr(v)) return Hash.hashString(Val.inlineBytes(v));
        if (Val.isInlineKw(v)) return Hash.hashKeyword(null, Val.inlineBytes(v));
        if (!Val.isHeap(v)) return 0;
        switch (ty(rt.gc.sp, Val.asHeap(v))) {
            case TY_STR: return Hash.hashString(Str.bytes(rt, v));
            case TY_KW: return Hash.hashKeyword(nsBytes(rt, v), Str.bytes(rt, rt.slot(v, 1)));
            case TY_SYM: return Hash.hashSymbol(nsBytes(rt, v), Str.bytes(rt, rt.slot(v, 1)));
            case TY_VEC: {
                int n = Vec.count(rt, v), acc = 1;
                for (int i = 0; i < n; i++) acc = Hash.orderedStep(acc, hashValue(rt, Vec.nth(rt, v, i)));
                return Hash.mixCollHash(acc, n);
            }
            case TY_ARRAYMAP:
            case TY_HASHMAP: return Maps.hash(rt, v);
            default: {
                if (rt.isSeq(v)) {
                    int base = rt.mark();
                    int s = rt.push(Seqs.seq(rt, v));
                    int acc = 1, n = 0;
                    while (!Val.isNil(rt.r(s))) {
                        acc = Hash.orderedStep(acc, hashValue(rt, Seqs.first(rt, rt.r(s))));
                        n++;
                        long nx = Seqs.next(rt, rt.r(s));
                        rt.setR(s, nx);
                    }
                    rt.popTo(base);
                    return Hash.mixCollHash(acc, n);
                }
                return 0;
            }
        }
    }

    static byte[] nsBytes(Rt rt, long v) {
        long ns = rt.slot(v, 0);
        return Val.isNil(ns) ? null : Str.bytes(rt, ns);
    }
}
