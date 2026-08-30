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

        // The math builtins. `fmath.rs` implements these itself because wasm
        // has no libm; on a host they go to the platform, which is where the
        // IEEE results come from in the first place. `hosted` compares them
        // against the native runtime, so a divergence in the last bit shows up
        // as a differing STRING rather than passing silently.
        def("flint/sqrt", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.sqrt(x)));
        def("flint/cbrt", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.cbrt(x)));
        def("flint/exp", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.exp(x)));
        def("flint/expm1", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.expm1(x)));
        def("flint/log", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.log(x)));
        def("flint/log10", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.log10(x)));
        def("flint/log1p", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.log1p(x)));
        def("flint/sin", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.sin(x)));
        def("flint/cos", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.cos(x)));
        def("flint/tan", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.tan(x)));
        def("flint/asin", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.asin(x)));
        def("flint/acos", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.acos(x)));
        def("flint/atan", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.atan(x)));
        def("flint/sinh", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.sinh(x)));
        def("flint/cosh", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.cosh(x)));
        def("flint/tanh", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.tanh(x)));
        def("flint/floor", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.floor(x)));
        def("flint/ceil", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.ceil(x)));
        def("flint/rint", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.rint(x)));
        def("flint/signum", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.signum(x)));
        def("flint/fabs", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> Math.abs(x)));
        def("flint/pow", (rt, at, n) -> mathTwo(rt, rt.vat(at), rt.vat(at + 1), (x, y) -> Math.pow(x, y)));
        def("flint/atan2", (rt, at, n) -> mathTwo(rt, rt.vat(at), rt.vat(at + 1), (x, y) -> Math.atan2(x, y)));
        def("flint/hypot", (rt, at, n) -> mathTwo(rt, rt.vat(at), rt.vat(at + 1), (x, y) -> Math.hypot(x, y)));
        def("flint/trunc", (rt, at, n) -> mathOne(rt, rt.vat(at), x -> x < 0 ? Math.ceil(x) : Math.floor(x)));
        def("flint/copy-sign", (rt, at, n) ->
            mathTwo(rt, rt.vat(at), rt.vat(at + 1), (x, y) -> Math.copySign(Math.abs(x), y)));
        def("flint/to-long", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Num.isInt(rt, v)) return v;
            if (!Val.isDouble(v)) throw new IllegalArgumentException("not a number: " + rt.describe(v));
            double d = Val.asDouble(v);
            d = d < 0 ? Math.ceil(d) : Math.floor(d);
            if (!Double.isFinite(d) || d < -9.223372036854776e18 || d > 9.223372036854776e18) {
                throw new IllegalArgumentException("value out of long range");
            }
            return Num.integer(rt, (long) d);
        });

        /// The raw IEEE bits of a double, as an integer. What lets flint code
        /// print a double bit-exactly rather than through a formatter.
        def("flint/double-bits", (rt, at, n) -> {
            long v = rt.vat(at);
            if (!Val.isDouble(v)) throw new ClassCastException("not a double: " + rt.describe(v));
            return Num.integer(rt, Double.doubleToRawLongBits(Val.asDouble(v)));
        });

        // --- atoms, volatiles and delays --------------------------------------
        //
        // One slot each, and `deref` reads it. There is no lock: a sandbox's
        // threads are GREEN, so only one runs at a time and a compare-and-set
        // cannot be interrupted between the compare and the set. That is a
        // property of the scheduler and not of this code, and it is why it can
        // be written this plainly.
        def("atom", (rt, at, n) -> newCell(rt, TY_ATOM, rt.vat(at)));
        def("flint/volatile", (rt, at, n) -> newCell(rt, TY_VOLATILE, rt.vat(at)));
        def("deref", (rt, at, n) -> {
            long v = rt.vat(at);
            if (rt.isHeapTy(v, TY_ATOM) || rt.isHeapTy(v, TY_VOLATILE)) return rt.slot(v, 0);
            if (rt.isHeapTy(v, TY_DELAY)) {
                long thunk = rt.slot(v, 0);
                if (Val.isNil(thunk)) return rt.slot(v, 1);
                int base = rt.mark();
                int di = rt.push(v);
                long r = rt.invoke(thunk, new long[0]);
                int ri = rt.push(r);
                long d = rt.r(di);
                rt.setSlot(Val.asHeap(d), 0, Val.NIL);   // forced: drop the thunk
                rt.setSlot(Val.asHeap(d), 1, rt.r(ri));
                long out = rt.r(ri);
                rt.popTo(base);
                return out;
            }
            throw new ClassCastException("cannot deref " + rt.describe(v));
        });
        def("reset!", (rt, at, n) -> {
            long a = rt.vat(at);
            if (!rt.isHeapTy(a, TY_ATOM) && !rt.isHeapTy(a, TY_VOLATILE)) {
                throw new ClassCastException("not an atom: " + rt.describe(a));
            }
            rt.setSlot(Val.asHeap(a), 0, rt.vat(at + 1));
            return rt.vat(at + 1);
        });
        def("compare-and-set!", (rt, at, n) -> {
            long a = rt.vat(at);
            if (!rt.isHeapTy(a, TY_ATOM) && !rt.isHeapTy(a, TY_VOLATILE)) {
                throw new ClassCastException("not an atom: " + rt.describe(a));
            }
            if (rt.slot(a, 0) != rt.vat(at + 1)) return Val.FALSE;
            rt.setSlot(Val.asHeap(a), 0, rt.vat(at + 2));
            return Val.TRUE;
        });

        // --- dynamic bindings -------------------------------------------------
        //
        // A MAP in a singleton slot, not a per-var stack. `binding` swaps the
        // whole map and restores it, so a park in the middle carries the
        // bindings with the thread rather than leaving them behind.
        def("flint/dyn-get", (rt, at, n) -> {
            long binds = rt.roots.singletons[Rt.SING_BINDINGS];
            if (Val.isNil(binds)) return rt.vat(at + 1);
            return Maps.get(rt, binds, rt.vat(at), rt.vat(at + 1));
        });
        def("flint/dyn-bindings", (rt, at, n) -> {
            long b = rt.roots.singletons[Rt.SING_BINDINGS];
            return Val.isNil(b) ? Maps.empty(rt) : b;
        });
        def("flint/dyn-set-bindings", (rt, at, n) -> {
            rt.roots.singletons[Rt.SING_BINDINGS] = rt.vat(at);
            return rt.vat(at);
        });

        // --- vectors as stacks ------------------------------------------------
        def("peek", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Val.isNil(v)) return Val.NIL;
            // A VECTOR peeks at its LAST element and a seq at its FIRST. That
            // asymmetry is Clojure's, and it is the same one `conj` has: each
            // takes the end that is cheap.
            if (rt.isHeapTy(v, TY_VEC)) {
                int c = Vec.count(rt, v);
                return c == 0 ? Val.NIL : Vec.nth(rt, v, c - 1);
            }
            return Seqs.first(rt, v);
        });
        def("pop", (rt, at, n) -> {
            long v = rt.vat(at);
            if (rt.isHeapTy(v, TY_VEC)) {
                int c = Vec.count(rt, v);
                if (c == 0) throw new IllegalStateException("cannot pop an empty vector");
                int base = rt.mark();
                int ai = rt.push(Vec.empty(rt));
                int vi = rt.push(v);
                for (int i = 0; i < c - 1; i++) {
                    rt.setR(ai, Vec.conj(rt, rt.r(ai), Vec.nth(rt, rt.r(vi), i)));
                }
                long out = rt.r(ai);
                rt.popTo(base);
                return out;
            }
            if (Val.isNil(v)) throw new IllegalStateException("cannot pop nil");
            return Seqs.rest(rt, v);
        });
        def("empty", (rt, at, n) -> {
            long v = rt.vat(at);
            if (rt.isHeapTy(v, TY_VEC)) return Vec.empty(rt);
            if (Maps.isMap(rt, v)) return Maps.empty(rt);
            if (Sets.isSet(rt, v)) return Sets.empty(rt);
            if (rt.isSeq(v)) return Seqs.emptyList(rt);
            return Val.NIL;
        });

        // --- strings ----------------------------------------------------------
        def("flint/subs", (rt, at, n) -> {
            String s = Str.text(rt, rt.vat(at));
            int len = s.codePointCount(0, s.length());
            int start = (int) Val.asFixnum(rt.vat(at + 1));
            int end = n > 2 ? (int) Val.asFixnum(rt.vat(at + 2)) : len;
            if (start < 0 || end > len || start > end) {
                throw new IndexOutOfBoundsException("subs " + start + ".." + end + " of " + len);
            }
            // By CODE POINT, not by char: a Java `String` is UTF-16, so slicing
            // it by index would cut a surrogate pair in half.
            int bs = s.offsetByCodePoints(0, start);
            int be = s.offsetByCodePoints(0, end);
            return Str.of(rt, s.substring(bs, be));
        });
        def("flint/str->num", (rt, at, n) -> {
            String s = Str.text(rt, rt.vat(at)).trim();
            try {
                if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                    return Num.integer(rt, Long.parseLong(s));
                }
                return Val.ofDouble(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                return Val.NIL;   // nil, not a throw: `str->num` is a PARSE attempt
            }
        });
        def("flint/str-index-of", (rt, at, n) -> {
            String h = Str.text(rt, rt.vat(at));
            String needle = Str.text(rt, rt.vat(at + 1));
            int from = n > 2 ? (int) Val.asFixnum(rt.vat(at + 2)) : 0;
            int at16 = from <= 0 ? 0 : h.offsetByCodePoints(0, Math.min(from, h.codePointCount(0, h.length())));
            int i = h.indexOf(needle, at16);
            return i < 0 ? Val.NIL : Val.fixnum(h.codePointCount(0, i));
        });
        def("flint/str-join", (rt, at, n) -> {
            StringBuilder sb = new StringBuilder();
            int base = rt.mark();
            int s = rt.push(Seqs.seq(rt, rt.vat(at)));
            while (!Val.isNil(rt.r(s))) {
                sb.append(Str.text(rt, Seqs.first(rt, rt.r(s))));
                rt.setR(s, Seqs.next(rt, rt.r(s)));
            }
            rt.popTo(base);
            return Str.of(rt, sb.toString());
        });
        def("flint/upper-case", (rt, at, n) -> Str.of(rt, Str.text(rt, rt.vat(at)).toUpperCase()));
        def("flint/lower-case", (rt, at, n) -> Str.of(rt, Str.text(rt, rt.vat(at)).toLowerCase()));
        def("flint/code-point-at", (rt, at, n) -> {
            String s = Str.text(rt, rt.vat(at));
            int i = (int) Val.asFixnum(rt.vat(at + 1));
            if (i < 0 || i >= s.codePointCount(0, s.length())) {
                throw new IndexOutOfBoundsException("index " + i + " out of range");
            }
            return Val.fixnum(s.codePointAt(s.offsetByCodePoints(0, i)));
        });
        def("flint/from-code-point", (rt, at, n) -> {
            long c = Val.asFixnum(rt.vat(at));
            if (c < 0 || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) {
                throw new IllegalArgumentException("not a code point: " + c);
            }
            return Str.of(rt, new String(Character.toChars((int) c)));
        });
        def("flint/str-bytes", (rt, at, n) -> {
            byte[] b = Str.bytes(rt, rt.vat(at));
            int base = rt.mark();
            int vi = rt.push(Vec.empty(rt));
            for (byte x : b) rt.setR(vi, Vec.conj(rt, rt.r(vi), Val.fixnum(x & 0xFF)));
            long out = rt.r(vi);
            rt.popTo(base);
            return out;
        });
        def("flint/bytes->str", (rt, at, n) -> {
            long v = rt.vat(at);
            if (!rt.isHeapTy(v, TY_VEC)) {
                throw new ClassCastException("bytes->str wants a vector of bytes");
            }
            int c = Vec.count(rt, v);
            byte[] b = new byte[c];
            for (int i = 0; i < c; i++) b[i] = (byte) Val.asFixnum(Vec.nth(rt, v, i));
            return Str.of(rt, new String(b, java.nio.charset.StandardCharsets.UTF_8));
        });
        def("flint/bits->double", (rt, at, n) -> {
            long v = rt.vat(at);
            if (!Num.isInt(rt, v)) throw new ClassCastException("bits->double wants an integer");
            return Val.ofDouble(Double.longBitsToDouble(Num.asI64(rt, v)));
        });

        // --- green threads and ports ------------------------------------------
        //
        // Every one of these calls `ensureSched` first. The scheduler is built
        // on first use rather than at startup, so a program that never mentions
        // `spawn` never has one -- and the interpreter runs a loop with no slice
        // counter in it at all.
        def("flint/spawn", (rt, at, n) -> Conc.spawn(rt, rt.vat(at)));
        def("flint/yield", (rt, at, n) -> {
            Conc.ensureSched(rt);
            return Conc.park(rt, Conc.PARK_YIELD);
        });
        def("flint/self", (rt, at, n) -> { Conc.ensureSched(rt); return Conc.currentThread(rt); });
        def("flint/thread?", (rt, at, n) -> Val.bool(Conc.isThread(rt, rt.vat(at))));
        def("flint/thread-id", (rt, at, n) -> rt.slot(rt.vat(at), Conc.TH_ID));
        def("flint/thread-result", (rt, at, n) -> rt.slot(rt.vat(at), Conc.TH_RESULT));
        def("flint/thread-state", (rt, at, n) -> {
            long t = rt.vat(at);
            if (!Conc.isThread(rt, t)) {
                throw new ClassCastException("thread-state wants a thread, got " + rt.describe(t));
            }
            switch ((int) Val.asFixnum(rt.slot(t, Conc.TH_STATUS))) {
                case Conc.ST_NEW: return Str.keyword(rt, null, "new");
                case Conc.ST_RUNNABLE: return Str.keyword(rt, null, "runnable");
                case Conc.ST_PARKED: return Str.keyword(rt, null, "parked");
                case Conc.ST_DONE: return Str.keyword(rt, null, "done");
                default: return Str.keyword(rt, null, "failed");
            }
        });
        def("flint/thread-join", (rt, at, n) -> {
            Conc.ensureSched(rt);
            return Conc.join(rt, rt.vat(at));
        });
        def("flint/bindings", (rt, at, n) -> {
            long b = rt.roots.singletons[Rt.SING_BINDINGS];
            return Val.isNil(b) ? Maps.empty(rt) : b;
        });
        def("flint/set-bindings", (rt, at, n) -> {
            rt.roots.singletons[Rt.SING_BINDINGS] = rt.vat(at);
            return rt.vat(at);
        });

        def("flint/channel", (rt, at, n) -> {
            long cap = n > 0 ? rt.vat(at) : Val.NIL;
            long label = n > 1 ? rt.vat(at + 1) : Val.NIL;
            long c = Val.isFixnum(cap) ? Val.asFixnum(cap) : 32;
            if (c < 1) throw new IllegalArgumentException("a channel needs a buffer of at least 1");
            return Conc.channel(rt, c, label);
        });
        def("flint/port-send", (rt, at, n) -> Conc.send(rt, rt.vat(at), rt.vat(at + 1)));
        def("flint/port-receive", (rt, at, n) -> Conc.receive(rt, rt.vat(at)));
        def("flint/port-close", (rt, at, n) -> Conc.close(rt, rt.vat(at)));
        def("flint/port?", (rt, at, n) -> Val.bool(Conc.isPort(rt, rt.vat(at))));
        def("flint/port-id", (rt, at, n) -> rt.slot(rt.vat(at), Conc.PT_ID));
        def("flint/port-label", (rt, at, n) -> rt.slot(rt.vat(at), Conc.PT_LABEL));
        def("flint/port-format", (rt, at, n) -> rt.slot(rt.vat(at), Conc.PT_FORMAT));
        def("flint/port-opts", (rt, at, n) -> {
            long o = rt.slot(rt.vat(at), Conc.PT_OPTS);
            return Val.isNil(o) ? Maps.empty(rt) : o;
        });
        def("flint/set-port-opts", (rt, at, n) -> {
            rt.setSlot(Val.asHeap(rt.vat(at)), Conc.PT_OPTS, rt.vat(at + 1));
            return rt.vat(at + 1);
        });
        def("flint/set-port-binary", (rt, at, n) -> {
            rt.setSlot(Val.asHeap(rt.vat(at)), Conc.PT_BINARY, rt.vat(at + 1));
            return rt.vat(at + 1);
        });
        /// A CHANNEL end is never a host port. This runtime carries no host
        /// ports yet, so the honest answer is false rather than a refusal --
        /// asking is how library code decides whether to serialise.
        def("flint/port-host?", (rt, at, n) ->
            Val.bool(Val.asFixnum(rt.slot(rt.vat(at), Conc.PT_KIND)) != Conc.K_CHANNEL));
        def("flint/port-state", (rt, at, n) -> {
            switch ((int) Val.asFixnum(rt.slot(rt.vat(at), Conc.PT_STATE))) {
                case Conc.P_OPEN: return Str.keyword(rt, null, "open");
                case Conc.P_CLOSED: return Str.keyword(rt, null, "closed");
                case Conc.P_HALF: return Str.keyword(rt, null, "half");
                default: return Str.keyword(rt, null, "orphaned");
            }
        });

        // Exceptions. `ex-info` is `[msg, data, cause]`, and `throw` is an
        // OPCODE rather than a builtin -- these are what a handler reads.
        def("ex-info", (rt, at, n) -> {
            int base = rt.mark();
            int mi = rt.push(rt.vat(at));
            int di = rt.push(n > 1 ? rt.vat(at + 1) : Val.NIL);
            int ci = rt.push(n > 2 ? rt.vat(at + 2) : Val.NIL);
            long a = rt.alloc(TY_EXINFO, 3);
            if (a == 0) { rt.popTo(base); return Val.NIL; }
            rt.setSlot(a, 0, rt.r(mi));
            rt.setSlot(a, 1, rt.r(di));
            rt.setSlot(a, 2, rt.r(ci));
            rt.popTo(base);
            return Val.heap(a);
        });
        def("ex-message", (rt, at, n) ->
            rt.isHeapTy(rt.vat(at), TY_EXINFO) ? rt.slot(rt.vat(at), 0) : Val.NIL);
        def("ex-data", (rt, at, n) ->
            rt.isHeapTy(rt.vat(at), TY_EXINFO) ? rt.slot(rt.vat(at), 1) : Val.NIL);
        def("ex-cause", (rt, at, n) ->
            rt.isHeapTy(rt.vat(at), TY_EXINFO) ? rt.slot(rt.vat(at), 2) : Val.NIL);

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

    /// A one-slot cell: an atom or a volatile. Same shape, different type tag
    /// -- the difference is what the LIBRARY allows, not what the runtime does.
    static long newCell(Rt rt, int ty, long v) {
        int base = rt.mark();
        int vi = rt.push(v);
        long a = rt.alloc(ty, 2);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, 0, rt.r(vi));
        rt.setSlot(a, 1, Val.NIL);
        rt.popTo(base);
        return Val.heap(a);
    }

    interface D1 { double apply(double x); }
    interface D2 { double apply(double x, double y); }

    /// Every unary math builtin has the same shape: refuse a non-number by
    /// NAME, else compute in double. Written once so a new one cannot get the
    /// refusal wrong.
    static long mathOne(Rt rt, long v, D1 f) {
        if (!Num.isNumber(rt, v)) {
            throw new IllegalArgumentException("not a number: " + rt.describe(v));
        }
        return Val.ofDouble(f.apply(Num.f64(rt, v)));
    }

    static long mathTwo(Rt rt, long a, long b, D2 f) {
        if (!Num.isNumber(rt, a) || !Num.isNumber(rt, b)) {
            throw new IllegalArgumentException(
                "not a number: " + rt.describe(a) + " and " + rt.describe(b));
        }
        return Val.ofDouble(f.apply(Num.f64(rt, a), Num.f64(rt, b)));
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
