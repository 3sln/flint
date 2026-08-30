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
        // Arithmetic goes through `Num`, which owns the PROMOTION RULE:
        // integers stay integers and overflow rather than wrap, and any double
        // in the operands makes the whole expression a double. These read
        // every argument as a fixnum once, which silently read a double's
        // MANTISSA as an integer -- `(+ 1.5 2.5)` came back 0 and agreed with
        // nothing.
        def("flint/add", (rt, at, n) -> {
            long acc = Val.fixnum(0);
            for (int i = 0; i < n; i++) acc = Num.add(rt, acc, rt.vat(at + i));
            return acc;
        });
        def("+", (rt, at, n) -> byName("flint/add").apply(rt, at, n));
        def("flint/sub", (rt, at, n) -> {
            if (n == 1) return Num.neg(rt, rt.vat(at));
            long acc = rt.vat(at);
            for (int i = 1; i < n; i++) acc = Num.sub(rt, acc, rt.vat(at + i));
            return acc;
        });
        def("-", (rt, at, n) -> byName("flint/sub").apply(rt, at, n));
        def("flint/mul", (rt, at, n) -> {
            long acc = Val.fixnum(1);
            for (int i = 0; i < n; i++) acc = Num.mul(rt, acc, rt.vat(at + i));
            return acc;
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
        def("flint/num-eq", (rt, at, n) -> {
            for (int i = 1; i < n; i++) if (!Num.numEq(rt, rt.vat(at), rt.vat(at + i))) return Val.FALSE;
            return Val.TRUE;
        });
        def("==", (rt, at, n) -> byName("flint/num-eq").apply(rt, at, n));
        def("inc", (rt, at, n) -> Num.add(rt, rt.vat(at), Val.fixnum(1)));
        def("dec", (rt, at, n) -> Num.sub(rt, rt.vat(at), Val.fixnum(1)));

        def("identical?", (rt, at, n) -> Val.bool(rt.vat(at) == rt.vat(at + 1)));
        def("nil?", (rt, at, n) -> Val.bool(Val.isNil(rt.vat(at))));
        def("not", (rt, at, n) -> Val.bool(!Val.truthy(rt.vat(at))));
        def("true?", (rt, at, n) -> Val.bool(rt.vat(at) == Val.TRUE));
        def("false?", (rt, at, n) -> Val.bool(rt.vat(at) == Val.FALSE));
        def("boolean", (rt, at, n) -> Val.bool(Val.truthy(rt.vat(at))));
        def("number?", (rt, at, n) -> Val.bool(Num.isNumber(rt, rt.vat(at))));
        def("int?", (rt, at, n) -> Val.bool(Num.isInt(rt, rt.vat(at))));
        def("float?", (rt, at, n) -> Val.bool(Num.isFloat(rt.vat(at))));
        def("double", (rt, at, n) -> Val.ofDouble(Num.f64(rt, rt.vat(at))));
        def("long", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Num.isInt(rt, v)) return v;
            // TRUNCATES toward zero, as Clojure's `long` does on a double.
            return Num.integer(rt, (long) Num.f64(rt, v));
        });
        def("zero?", (rt, at, n) -> Val.bool(Num.numEq(rt, rt.vat(at), Val.fixnum(0))));
        def("pos?", (rt, at, n) -> Val.bool(Num.cmp(rt, rt.vat(at), Val.fixnum(0)) > 0));
        def("neg?", (rt, at, n) -> Val.bool(Num.cmp(rt, rt.vat(at), Val.fixnum(0)) < 0));

        def("quot", (rt, at, n) -> Num.quot(rt, rt.vat(at), rt.vat(at + 1)));
        def("rem", (rt, at, n) -> Num.rem(rt, rt.vat(at), rt.vat(at + 1)));
        // `/` on two integers that do not divide evenly is a DOUBLE here, not a
        // Ratio: flint has no rational type, and `doc/decisions/0010` counts
        // this among the documented divergences from Clojure rather than a bug.
        def("flint/div", (rt, at, n) -> {
            if (n == 1) return Num.div(rt, Val.fixnum(1), rt.vat(at));
            long acc = rt.vat(at);
            for (int i = 1; i < n; i++) acc = Num.div(rt, acc, rt.vat(at + i));
            return acc;
        });
        def("/", (rt, at, n) -> byName("flint/div").apply(rt, at, n));

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
            return Str.of(rt, Num.isInt(rt, v) ? Long.toString(Num.asI64(rt, v))
                                              : fmtDouble(Val.asDouble(v)));
        });

        def("flint/opaque?", (rt, at, n) -> Val.FALSE);
        def("flint/opaque-label", (rt, at, n) -> Val.NIL);
        def("meta", (rt, at, n) -> Val.NIL);

        def("count", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Val.isNil(v)) return Val.fixnum(0);
            if (rt.isHeapTy(v, TY_VEC)) return Val.fixnum(Vec.count(rt, v));
            if (Str.isString(rt, v)) return Val.fixnum(Str.charLen(rt, v));
            if (Maps.isMap(rt, v)) return Val.fixnum(Maps.count(rt, v));
            if (Sets.isSet(rt, v)) return Val.fixnum(Sets.count(rt, v));
            if (rt.isSeq(v)) return Val.fixnum(Seqs.count(rt, v));
            throw new UnsupportedOperationException("count over " + rt.describe(v) + " needs more of the data structures");
        });
        def("nth", (rt, at, n) -> {
            long v = rt.vat(at);
            int i = (int) Val.asFixnum(rt.vat(at + 1));
            long got = Val.NOT_FOUND;
            if (rt.isHeapTy(v, TY_VEC)) {
                got = Vec.nth(rt, v, i);
            } else if (Str.isString(rt, v)) {
                got = Str.nth(rt, v, i);
            } else if (rt.isHeapTy(v, TY_MAPENTRY)) {
                if (i == 0 || i == 1) got = rt.slot(v, i);
            } else if (Val.isNil(v)) {
                got = Val.NOT_FOUND;
            } else if (rt.isSeq(v)) {
                // O(n), as Clojure's `nth` on a seq is. Walking rather than
                // refusing, because `nth` over a seq is ordinary code and the
                // cost is the caller's to know about.
                int base = rt.mark();
                int s = rt.push(Seqs.seq(rt, v));
                for (int k = 0; k < i && !Val.isNil(rt.r(s)); k++) {
                    rt.setR(s, Seqs.next(rt, rt.r(s)));
                }
                if (!Val.isNil(rt.r(s))) got = Seqs.first(rt, rt.r(s));
                rt.popTo(base);
            } else {
                throw new UnsupportedOperationException("nth over " + rt.describe(v) + " needs more of the data structures");
            }
            if (got != Val.NOT_FOUND) return got;
            if (n > 2) return rt.vat(at + 2);
            throw new IndexOutOfBoundsException("index " + i + " out of range");
        });
        def("conj", (rt, at, n) -> {
            long v = rt.vat(at);
            if (rt.isHeapTy(v, TY_VEC)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Vec.conj(rt, acc, rt.vat(at + i));
                return acc;
            }
            if (Sets.isSet(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Sets.conj(rt, acc, rt.vat(at + i));
                return acc;
            }
            if (Maps.isMap(rt, v)) {
                // `conj` onto a map takes an ENTRY or a two-element vector.
                int base = rt.mark();
                int ai = rt.push(v);
                for (int i = 1; i < n; i++) {
                    long e = rt.vat(at + i);
                    rt.setR(ai, Maps.assoc(rt, rt.r(ai), Seqs.first(rt, e), Seqs.first(rt, Seqs.rest(rt, e))));
                }
                long out = rt.r(ai);
                rt.popTo(base);
                return out;
            }
            // `conj` on a SEQ prepends, where on a vector it appends. That
            // asymmetry is Clojure's and is about where the collection is cheap
            // to grow, not about consistency.
            if (Val.isNil(v) || rt.isSeq(v)) {
                long acc = Val.isNil(v) ? Seqs.emptyList(rt) : v;
                for (int i = 1; i < n; i++) acc = Seqs.cons(rt, rt.vat(at + i), acc);
                return acc;
            }
            throw new UnsupportedOperationException("conj onto " + rt.describe(v) + " needs more of the data structures");
        });

        def("seq", (rt, at, n) -> Seqs.seq(rt, rt.vat(at)));
        def("first", (rt, at, n) -> Seqs.first(rt, rt.vat(at)));
        def("next", (rt, at, n) -> Seqs.next(rt, rt.vat(at)));
        def("rest", (rt, at, n) -> Seqs.rest(rt, rt.vat(at)));
        def("cons", (rt, at, n) -> Seqs.cons(rt, rt.vat(at), rt.vat(at + 1)));

        // Transients. A transient is a MUTABLE handle on a persistent value,
        // and the whole contract is that the persistent one it came from is
        // untouched -- so `persistent!` invalidates the handle rather than
        // leaving two owners of the same nodes.
        def("transient", (rt, at, n) -> {
            long v = rt.vat(at);
            if (rt.isHeapTy(v, TY_VEC)) return Vec.transientOf(rt, v);
            throw new UnsupportedOperationException(
                "transient of " + rt.describe(v) + " needs maps and sets ported");
        });
        def("persistent!", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Vec.isTransient(rt, v)) {
                if (!Vec.alive(rt, v)) {
                    throw new IllegalStateException("persistent! called twice on one transient");
                }
                return Vec.tpersistent(rt, v);
            }
            throw new UnsupportedOperationException(
                "persistent! of " + rt.describe(v) + " needs maps and sets ported");
        });
        def("conj!", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Vec.isTransient(rt, v)) {
                if (!Vec.alive(rt, v)) {
                    throw new IllegalStateException("conj! on a transient already made persistent");
                }
                long acc = v;
                for (int i = 1; i < n; i++) acc = Vec.tconj(rt, acc, rt.vat(at + i));
                return acc;
            }
            throw new UnsupportedOperationException(
                "conj! onto " + rt.describe(v) + " needs maps and sets ported");
        });
        def("assoc!", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Vec.isTransient(rt, v)) {
                if (!Vec.alive(rt, v)) {
                    throw new IllegalStateException("assoc! on a transient already made persistent");
                }
                long acc = v;
                for (int i = 1; i + 1 < n; i += 2) {
                    acc = Vec.tassoc(rt, acc, (int) Val.asFixnum(rt.vat(at + i)), rt.vat(at + i + 1));
                }
                return acc;
            }
            throw new UnsupportedOperationException(
                "assoc! onto " + rt.describe(v) + " needs maps and sets ported");
        });

        // Maps.
        def("get", (rt, at, n) -> {
            long dflt = n > 2 ? rt.vat(at + 2) : Val.NIL;
            long coll = rt.vat(at);
            if (Val.isNil(coll)) return dflt;
            if (Maps.isMap(rt, coll)) return Maps.get(rt, coll, rt.vat(at + 1), dflt);
            if (Sets.isSet(rt, coll)) return Sets.get(rt, coll, rt.vat(at + 1), dflt);
            if (rt.isHeapTy(coll, TY_VEC)) {
                long k = rt.vat(at + 1);
                if (!Val.isFixnum(k)) return dflt;
                long got = Vec.nth(rt, coll, (int) Val.asFixnum(k));
                return got == Val.NOT_FOUND ? dflt : got;
            }
            throw new UnsupportedOperationException("get over " + rt.describe(coll) + " needs sets ported");
        });
        def("assoc", (rt, at, n) -> {
            long acc = rt.vat(at);
            if (Val.isNil(acc)) acc = Maps.empty(rt);
            if (Maps.isMap(rt, acc)) {
                int base = rt.mark();
                int ai = rt.push(acc);
                for (int i = 1; i + 1 < n; i += 2) {
                    long nm = Maps.assoc(rt, rt.r(ai), rt.vat(at + i), rt.vat(at + i + 1));
                    rt.setR(ai, nm);
                }
                long out = rt.r(ai);
                rt.popTo(base);
                return out;
            }
            if (rt.isHeapTy(acc, TY_VEC)) {
                int base = rt.mark();
                int ai = rt.push(acc);
                for (int i = 1; i + 1 < n; i += 2) {
                    rt.setR(ai, Vec.assoc(rt, rt.r(ai),
                                          (int) Val.asFixnum(rt.vat(at + i)), rt.vat(at + i + 1)));
                }
                long out = rt.r(ai);
                rt.popTo(base);
                return out;
            }
            throw new UnsupportedOperationException("assoc onto " + rt.describe(acc) + " needs more of the data structures");
        });
        def("dissoc", (rt, at, n) -> {
            long acc = rt.vat(at);
            if (Val.isNil(acc)) return Val.NIL;
            int base = rt.mark();
            int ai = rt.push(acc);
            for (int i = 1; i < n; i++) {
                long nm = Maps.dissoc(rt, rt.r(ai), rt.vat(at + i));
                rt.setR(ai, nm);
            }
            long out = rt.r(ai);
            rt.popTo(base);
            return out;
        });
        def("contains?", (rt, at, n) -> {
            long coll = rt.vat(at);
            if (Val.isNil(coll)) return Val.FALSE;
            if (Maps.isMap(rt, coll)) return Val.bool(Maps.contains(rt, coll, rt.vat(at + 1)));
            if (Sets.isSet(rt, coll)) return Val.bool(Sets.contains(rt, coll, rt.vat(at + 1)));
            if (rt.isHeapTy(coll, TY_VEC)) {
                long k = rt.vat(at + 1);
                return Val.bool(Val.isFixnum(k) && Val.asFixnum(k) >= 0
                                && Val.asFixnum(k) < Vec.count(rt, coll));
            }
            throw new UnsupportedOperationException("contains? over " + rt.describe(coll) + " needs sets ported");
        });
        def("hash", (rt, at, n) -> Val.fixnum(Eq.hashValue(rt, rt.vat(at))));

        // `apply`: spread the trailing seq onto the argument list.
        //
        // The spread arguments go on the SHADOW stack, not into a host array:
        // `first` and `next` allocate on a lazy seq, so a host array would hold
        // addresses across a collection that moves them.
        def("flint/apply", (rt, at, n) -> {
            int base = rt.mark();
            int fi = rt.push(rt.vat(at));
            int si = rt.push(Seqs.seq(rt, rt.vat(at + 1)));
            int count = 0;
            while (!Val.isNil(rt.r(si))) {
                rt.push(Seqs.first(rt, rt.r(si)));
                count++;
                rt.setR(si, Seqs.next(rt, rt.r(si)));
            }
            long[] argv = new long[count];
            for (int i = 0; i < count; i++) argv[i] = rt.r(si + 1 + i);
            long f = rt.r(fi);
            long out = rt.invoke(f, argv);
            rt.popTo(base);
            return out;
        });

        def("flint/keyword2", (rt, at, n) -> {
            long ns = n == 1 ? Val.NIL : rt.vat(at);
            long nm = n == 1 ? rt.vat(at) : rt.vat(at + 1);
            return Str.keyword(rt, Val.isNil(ns) ? null : nameOf(rt, ns), nameOf(rt, nm));
        });
        def("flint/symbol2", (rt, at, n) -> {
            long ns = n == 1 ? Val.NIL : rt.vat(at);
            long nm = n == 1 ? rt.vat(at) : rt.vat(at + 1);
            return Str.symbol(rt, Val.isNil(ns) ? null : nameOf(rt, ns), nameOf(rt, nm));
        });

        // Lazy sequences and ranges.
        def("flint/lazy-seq", (rt, at, n) -> Seqs.lazySeq(rt, rt.vat(at)));
        def("flint/range3", (rt, at, n) ->
            Seqs.range(rt, rt.vat(at), rt.vat(at + 1), rt.vat(at + 2)));

        // Sets.
        def("disj", (rt, at, n) -> {
            long acc = rt.vat(at);
            if (Val.isNil(acc)) return Val.NIL;
            int base = rt.mark();
            int ai = rt.push(acc);
            for (int i = 1; i < n; i++) rt.setR(ai, Sets.disj(rt, rt.r(ai), rt.vat(at + i)));
            long out = rt.r(ai);
            rt.popTo(base);
            return out;
        });

        def("=", (rt, at, n) -> {
            for (int i = 1; i < n; i++) if (!eq(rt, rt.vat(at), rt.vat(at + i))) return Val.FALSE;
            return Val.TRUE;
        });
    }

    /// The NAME of a string, keyword or symbol, as a host string. `keyword`
    /// and `symbol` accept any of the three, which is what lets
    /// `(keyword (name x))` round-trip.
    static String nameOf(Rt rt, long v) {
        if (Val.isInlineKw(v)) return new String(Val.inlineBytes(v), java.nio.charset.StandardCharsets.UTF_8);
        if (rt.isHeapTy(v, TY_KW) || rt.isHeapTy(v, TY_SYM)) return Str.text(rt, rt.slot(v, 1));
        return Str.text(rt, v);
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

    /// Equality lives in `Eq` now, because maps need it and it needs maps --
    /// a map's `=` compares entries and an entry's key can be a map. One
    /// implementation, not two that drift.
    static boolean eq(Rt rt, long a, long b) { return Eq.eq(rt, a, b); }

    /// A CHAIN, as Clojure's comparisons are: `(< 1 2 3)` is one call, not two.
    private static long cmp(Rt rt, int at, int n, int want, boolean orEqual) {
        for (int i = 0; i + 1 < n; i++) {
            int c = Num.cmp(rt, rt.vat(at + i), rt.vat(at + i + 1));
            if (!(c == want || (orEqual && c == 0))) return Val.FALSE;
        }
        return Val.TRUE;
    }
}
