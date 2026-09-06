package com.flint.rt;

import com._3sln.flint.kgen.rt.Seqwalk;

import static com.flint.rt.Obj.*;
import static com._3sln.flint.kgen.rt.Eq.*;

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

    /// The three things `=` dispatches on. A value's CATEGORY, not its type: a
    /// vector, a list and a map entry are all sequential and compare
    /// elementwise, which is what makes `(= [1 2] '(1 2))` true.
    public static final int CAT_SCALAR = 0, CAT_SEQUENTIAL = 1, CAT_MAP = 2, CAT_SET = 3;



    // `eq` and `seqEq` are GENERATED, from `kin/valeq.kin`, as
    // `Valeq.valEq` and `Valeq.seqEq`. The hand-written `eq` here tested for
    // a ROW REF *below* the category switch, where it could never run:
    // `category` puts a ref in CAT_MAP, so every ref reached `Maps.eq` first
    // and every non-map counterpart was refused by `ca != cb`. It worked only
    // because `Maps.eq` materialises refs itself, which native's does not.

    /// The hash of a value, agreeing with `eq` above and with Clojure's.
    // `hashValue` is GENERATED, from `kin/valhash.kin`, as
    // `Valhash.valueHash`. Two caches this port HAD and never used are live
    // now: `strHash` was written during interning and never read, and a
    // keyword's slot 2 was set to nil rather than to its hash -- so the two
    // commonest map keys there are each paid their content per lookup.

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
        // Sets `thrown` and answers 0, as the Rust does: `compare` returns an
        // int, so there is no failure value to hand back -- the pending throw
        // is the answer, and the interpreter unwinds on the way out.
        rt.throwStr("ClassCastException",
            "cannot compare " + rt.describe(a) + " with " + rt.describe(b));
        return 0;
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

    static long nsOf(Rt rt, long v) { return com._3sln.flint.kgen.rt.Names.nsOf(rt, v); }
    /// THE SECOND COPY IS GONE. `Eq` had its own `nameOf` that skipped the
    /// type check -- fine for the values it was handed, and one more place to
    /// keep in step.
    static long nameOf(Rt rt, long v) { return com._3sln.flint.kgen.rt.Names.nameOf(rt, v); }

    /// Length first is WRONG for sequences: `[1 2]` is less than `[1 3]`, and
    /// both are less than `[1 2 3]`. So shorter-is-less only decides a tie.
    static int cmpSequential(Rt rt, long a, long b) {
        int base = rt.mark();
        int x = rt.push(com._3sln.flint.kgen.rt.Seqwalk.seq(rt, a)), y = rt.push(com._3sln.flint.kgen.rt.Seqwalk.seq(rt, b));
        int out = 0;
        for (;;) {
            boolean ex = Val.isNil(rt.r(x)), ey = Val.isNil(rt.r(y));
            if (ex || ey) { out = ex && ey ? 0 : (ex ? -1 : 1); break; }
            int c = compare(rt, Seqwalk.first(rt, rt.r(x)), Seqwalk.first(rt, rt.r(y)));
            if (c != 0) { out = c; break; }
            long nx = com._3sln.flint.kgen.rt.Seqwalk.next(rt, rt.r(x)), ny = com._3sln.flint.kgen.rt.Seqwalk.next(rt, rt.r(y));
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
