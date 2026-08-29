package com.flint.rt;

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
