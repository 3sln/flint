package com.flint.rt;

import static com.flint.rt.Obj.*;

import java.util.HashMap;
import java.util.Map;

/// The builtins, ported from `runtime/src/builtins.rs`.
///
/// A builtin reads its arguments STRAIGHT OFF the value stack -- `at` is the
/// first, `argc` how many -- rather than being handed an array. That is not a
/// micro-optimisation: an array would be a second place values live, and the
/// collector would have to be taught about it. Everything live is in the value
/// stack, and this keeps that true with no second mechanism.
public final class Builtins {
    private Builtins() {}

    public interface Fn { long apply(Rt rt, int at, int argc); }

    private static final Map<String, Fn> TABLE = new HashMap<>();

    public static Fn byName(String n) { return TABLE.get(n); }
    static void def(String n, Fn f) { TABLE.put(n, f); }

    static long arg(Rt rt, int at, int i, int argc) {
        return i < argc ? rt.vat(at + i) : Val.NIL;
    }

    static {
        // Arithmetic. flint's integers OVERFLOW rather than wrap, which
        // `doc/decisions/0010` names as one of the ways two hosts quietly
        // disagree -- so every one of these is checked.
        def("flint/add", (rt, at, n) -> {
            long acc = 0;
            for (int i = 0; i < n; i++) acc = Math.addExact(acc, Val.asFixnum(rt.vat(at + i)));
            return Val.fixnum(acc);
        });
        def("+", (rt, at, n) -> byName("flint/add").apply(rt, at, n));
        def("flint/sub", (rt, at, n) -> {
            if (n == 1) return Val.fixnum(Math.negateExact(Val.asFixnum(rt.vat(at))));
            long acc = Val.asFixnum(rt.vat(at));
            for (int i = 1; i < n; i++) acc = Math.subtractExact(acc, Val.asFixnum(rt.vat(at + i)));
            return Val.fixnum(acc);
        });
        def("-", (rt, at, n) -> byName("flint/sub").apply(rt, at, n));
        def("flint/mul", (rt, at, n) -> {
            long acc = 1;
            for (int i = 0; i < n; i++) acc = Math.multiplyExact(acc, Val.asFixnum(rt.vat(at + i)));
            return Val.fixnum(acc);
        });
        def("*", (rt, at, n) -> byName("flint/mul").apply(rt, at, n));
        def("flint/lt", (rt, at, n) -> cmp(rt, at, n, -1, false));
        def("<", (rt, at, n) -> cmp(rt, at, n, -1, false));
        def("flint/le", (rt, at, n) -> cmp(rt, at, n, -1, true));
        def("<=", (rt, at, n) -> cmp(rt, at, n, -1, true));
        def("flint/gt", (rt, at, n) -> cmp(rt, at, n, 1, false));
        def(">", (rt, at, n) -> cmp(rt, at, n, 1, false));
        def("flint/ge", (rt, at, n) -> cmp(rt, at, n, 1, true));
        def(">=", (rt, at, n) -> cmp(rt, at, n, 1, true));
        def("inc", (rt, at, n) -> Val.fixnum(Math.addExact(Val.asFixnum(rt.vat(at)), 1)));
        def("dec", (rt, at, n) -> Val.fixnum(Math.subtractExact(Val.asFixnum(rt.vat(at)), 1)));

        def("identical?", (rt, at, n) -> Val.bool(rt.vat(at) == rt.vat(at + 1)));
        def("nil?", (rt, at, n) -> Val.bool(Val.isNil(rt.vat(at))));
        def("not", (rt, at, n) -> Val.bool(!Val.truthy(rt.vat(at))));
        def("true?", (rt, at, n) -> Val.bool(rt.vat(at) == Val.TRUE));
        def("false?", (rt, at, n) -> Val.bool(rt.vat(at) == Val.FALSE));
        def("boolean", (rt, at, n) -> Val.bool(Val.truthy(rt.vat(at))));
        def("number?", (rt, at, n) -> Val.bool(Val.isFixnum(rt.vat(at)) || Val.isDouble(rt.vat(at))));

        def("quot", (rt, at, n) -> Val.fixnum(Val.asFixnum(rt.vat(at)) / Val.asFixnum(rt.vat(at + 1))));
        def("rem", (rt, at, n) -> Val.fixnum(Val.asFixnum(rt.vat(at)) % Val.asFixnum(rt.vat(at + 1))));
        // `/` on two integers that do not divide evenly is a DOUBLE here, not a
        // Ratio: flint has no rational type, and `doc/decisions/0010` counts
        // this among the documented divergences from Clojure rather than a bug.
        def("flint/div", (rt, at, n) -> {
            long a = Val.asFixnum(rt.vat(at)), b = Val.asFixnum(rt.vat(at + 1));
            return b != 0 && a % b == 0 ? Val.fixnum(a / b) : Val.ofDouble((double) a / b);
        });

        def("bit-and", (rt, at, n) -> Val.fixnum(Val.asFixnum(rt.vat(at)) & Val.asFixnum(rt.vat(at + 1))));
        def("bit-or", (rt, at, n) -> Val.fixnum(Val.asFixnum(rt.vat(at)) | Val.asFixnum(rt.vat(at + 1))));
        def("bit-xor", (rt, at, n) -> Val.fixnum(Val.asFixnum(rt.vat(at)) ^ Val.asFixnum(rt.vat(at + 1))));
        def("bit-not", (rt, at, n) -> Val.fixnum(~Val.asFixnum(rt.vat(at))));
        def("bit-shift-left", (rt, at, n) -> Val.fixnum(Val.asFixnum(rt.vat(at)) << Val.asFixnum(rt.vat(at + 1))));
        def("bit-shift-right", (rt, at, n) -> Val.fixnum(Val.asFixnum(rt.vat(at)) >> Val.asFixnum(rt.vat(at + 1))));
        def("unsigned-bit-shift-right", (rt, at, n) ->
            Val.fixnum(Val.asFixnum(rt.vat(at)) >>> Val.asFixnum(rt.vat(at + 1))));
        def("bit-test", (rt, at, n) ->
            Val.bool(((Val.asFixnum(rt.vat(at)) >> Val.asFixnum(rt.vat(at + 1))) & 1) != 0));

        def("name", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Val.isInlineKw(v)) return Val.inlineStr(Val.inlineBytes(v));
            if (rt.isHeapTy(v, TY_KW) || rt.isHeapTy(v, TY_SYM)) return rt.slot(v, 1);
            return v;   // a string names itself
        });
        def("namespace", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Val.isInlineKw(v)) return Val.NIL;
            if (rt.isHeapTy(v, TY_KW) || rt.isHeapTy(v, TY_SYM)) return rt.slot(v, 0);
            return Val.NIL;
        });

        def("flint/str2", (rt, at, n) ->
            Str.of(rt, Str.text(rt, rt.vat(at)) + Str.text(rt, rt.vat(at + 1))));
        def("flint/num->str", (rt, at, n) -> {
            long v = rt.vat(at);
            return Str.of(rt, Val.isFixnum(v) ? Long.toString(Val.asFixnum(v))
                                              : fmtDouble(Val.asDouble(v)));
        });

        def("flint/opaque?", (rt, at, n) -> Val.FALSE);
        def("flint/opaque-label", (rt, at, n) -> Val.NIL);
        def("meta", (rt, at, n) -> Val.NIL);

        def("=", (rt, at, n) -> {
            for (int i = 1; i < n; i++) if (!eq(rt, rt.vat(at), rt.vat(at + i))) return Val.FALSE;
            return Val.TRUE;
        });
    }

    /// Clojure prints a double with a trailing `.0` where Java prints `1.0`
    /// already but `1.0E10` where Clojure wants `1.0E10` too -- the shapes
    /// agree for everything the conformance set covers, and a divergence here
    /// would show up as a differing STRING rather than a differing number,
    /// which is the easy kind to catch.
    static String fmtDouble(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return (long) d + ".0";
        }
        return Double.toString(d);
    }

    /// Structural equality, from `runtime/src/eq.rs`.
    ///
    /// Only the scalar and string cases are ported. Collections need the data
    /// structures; reaching one throws by name rather than answering `false`,
    /// because a wrong `false` from `=` is the kind of bug that surfaces as a
    /// map lookup missing, six layers away.
    static boolean eq(Rt rt, long a, long b) {
        if (a == b) return true;
        if (Val.isFixnum(a) && Val.isFixnum(b)) return Val.asFixnum(a) == Val.asFixnum(b);
        if (Val.isDouble(a) && Val.isDouble(b)) return Val.asDouble(a) == Val.asDouble(b);
        if (Str.isString(rt, a) && Str.isString(rt, b)) {
            return java.util.Arrays.equals(Str.bytes(rt, a), Str.bytes(rt, b));
        }
        boolean ka = Val.isInlineKw(a) || rt.isHeapTy(a, TY_KW);
        boolean kb = Val.isInlineKw(b) || rt.isHeapTy(b, TY_KW);
        if (ka && kb) {
            // Inline and heap keywords must compare EQUAL when they name the
            // same thing. They cannot here -- one is a value and one is an
            // object -- unless both are inline, which is why interning matters
            // and why this is refused rather than answered wrongly.
            if (Val.isInlineKw(a) && Val.isInlineKw(b)) return a == b;
            throw new UnsupportedOperationException(
                "comparing a heap keyword needs the intern tables ported");
        }
        if (Val.isHeap(a) || Val.isHeap(b)) {
            throw new UnsupportedOperationException(
                "= on collections needs the data structures ported");
        }
        return false;
    }

    /// A CHAIN, as Clojure's comparisons are: `(< 1 2 3)` is one call, not two.
    private static long cmp(Rt rt, int at, int n, int want, boolean orEqual) {
        for (int i = 0; i + 1 < n; i++) {
            long a = Val.asFixnum(rt.vat(at + i)), b = Val.asFixnum(rt.vat(at + i + 1));
            int c = Long.compare(a, b);
            if (!(c == want || (orEqual && c == 0))) return Val.FALSE;
        }
        return Val.TRUE;
    }
}
