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
        if (Sets.isSet(rt, a) && Sets.isSet(rt, b)) return Sets.eq(rt, a, b);
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
            case TY_SET: return Sets.hash(rt, v);
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

    /// Clojure's `compare`: -1, 0 or 1, and a THROW for values that have no
    /// ordering. Refusing is the right answer -- a `sort` over mixed types
    /// silently ordered by type tag would be stable, plausible and wrong.
    public static int compare(Rt rt, long a, long b) {
        if (a == b && !Val.isDouble(a)) return 0;
        if (Val.isNil(a)) return -1;
        if (Val.isNil(b)) return 1;
        if (Num.isNumber(rt, a) && Num.isNumber(rt, b)) return Num.cmp(rt, a, b);
        boolean ba = a == Val.TRUE || a == Val.FALSE, bb = b == Val.TRUE || b == Val.FALSE;
        if (ba && bb) return (a == Val.TRUE ? 1 : 0) - (b == Val.TRUE ? 1 : 0);
        if (Str.isString(rt, a) && Str.isString(rt, b)) {
            return utf16Cmp(Str.text(rt, a), Str.text(rt, b));
        }
        boolean ka = Val.isInlineKw(a) || rt.isHeapTy(a, TY_KW);
        boolean kb = Val.isInlineKw(b) || rt.isHeapTy(b, TY_KW);
        if (ka && kb) return cmpNamed(rt, a, b);
        if (rt.isHeapTy(a, TY_SYM) && rt.isHeapTy(b, TY_SYM)) return cmpNamed(rt, a, b);
        if (rt.isSequential(a) && rt.isSequential(b)) return cmpSequential(rt, a, b);
        throw new ClassCastException(
            "cannot compare " + rt.describe(a) + " with " + rt.describe(b));
    }

    /// By UTF-16 CODE UNIT, as `String.compareTo` is -- not by code point.
    /// The two orders differ above U+FFFF, and Clojure's is the UTF-16 one.
    static int utf16Cmp(String x, String y) {
        int n = Math.min(x.length(), y.length());
        for (int i = 0; i < n; i++) {
            int d = x.charAt(i) - y.charAt(i);
            if (d != 0) return d < 0 ? -1 : 1;
        }
        return Integer.compare(x.length(), y.length());
    }

    /// Namespace first, then name -- and a value WITHOUT a namespace sorts
    /// before one with, which is Clojure's rule and not alphabetical order.
    static int cmpNamed(Rt rt, long a, long b) {
        long na = nsOf(rt, a), nb = nsOf(rt, b);
        if (Val.isNil(na) && !Val.isNil(nb)) return -1;
        if (!Val.isNil(na) && Val.isNil(nb)) return 1;
        if (!Val.isNil(na)) {
            int c = compare(rt, na, nb);
            if (c != 0) return c;
        }
        return compare(rt, nameOf(rt, a), nameOf(rt, b));
    }

    static long nsOf(Rt rt, long v) {
        if (Val.isInlineKw(v)) return Val.NIL;
        return rt.slot(v, 0);
    }
    static long nameOf(Rt rt, long v) {
        if (Val.isInlineKw(v)) return Val.inlineStr(Val.inlineBytes(v));
        return rt.slot(v, 1);
    }

    /// Length first is WRONG for sequences: `[1 2]` is less than `[1 3]`, and
    /// both are less than `[1 2 3]`. So shorter-is-less only decides a tie.
    static int cmpSequential(Rt rt, long a, long b) {
        int base = rt.mark();
        int x = rt.push(Seqs.seq(rt, a)), y = rt.push(Seqs.seq(rt, b));
        int out = 0;
        for (;;) {
            boolean ex = Val.isNil(rt.r(x)), ey = Val.isNil(rt.r(y));
            if (ex || ey) { out = ex && ey ? 0 : (ex ? -1 : 1); break; }
            int c = compare(rt, Seqs.first(rt, rt.r(x)), Seqs.first(rt, rt.r(y)));
            if (c != 0) { out = c; break; }
            long nx = Seqs.next(rt, rt.r(x)), ny = Seqs.next(rt, rt.r(y));
            rt.setR(x, nx); rt.setR(y, ny);
        }
        rt.popTo(base);
        return out;
    }

    static byte[] nsBytes(Rt rt, long v) {
        long ns = rt.slot(v, 0);
        return Val.isNil(ns) ? null : Str.bytes(rt, ns);
    }
}
