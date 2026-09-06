package com.flint.rt;

import com._3sln.flint.kgen.rt.Seqwalk;

import com._3sln.flint.kgen.rt.Mapwrite;

import com._3sln.flint.kgen.rt.Mapread;

import com._3sln.flint.kgen.rt.Mapcore;
import com._3sln.flint.kgen.rt.Maptrans;
import com._3sln.flint.kgen.rt.Transients;
import com._3sln.flint.kgen.rt.Collgen;
import com._3sln.flint.kgen.rt.Collread;

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

    /// A keyword's name, for a message that has to say WHICH key.
    static long kwName(Rt rt, long v) {
        if (Val.isInlineKw(v)) return Val.inlineStr(Val.inlineBytes(v));
        if (rt.isHeapTy(v, TY_KW) || rt.isHeapTy(v, TY_SYM)) return rt.slot(v, 1);
        return v;
    }

    /// `:tag` and `:form` on a tagged literal, which is how anyone reads one
    /// (`doc/decisions/0034`). Kept beside the `get` builtin because the
    /// keyword-apply path needs the same two keys.
    static long taggedGet(Rt rt, long t, long k, long dflt) {
        if (k == Str.keyword(rt, null, "tag")) return rt.slot(t, 0);
        if (k == Str.keyword(rt, null, "form")) return rt.slot(t, 1);
        return dflt;
    }

    private static final Map<String, Fn> TABLE = new HashMap<>();

    public static Fn byName(String n) { return TABLE.get(n); }
    static void def(String n, Fn f) { TABLE.put(n, f); }

    /// The annotation names, indexed by the code `check-tag` is handed. The
    /// codes are `flint.types/code` and `test/types.clj` asserts the tables
    /// agree; they are integers rather than keywords because this is on the
    /// write path of every annotated binding.
    static final String[] TAG_NAMES = {
        "int", "float", "number", "string", "keyword", "symbol", "boolean",
        "vector", "map", "set", "seq", "fn", "nil", "sequential",
    };

    static final String[] GC_STAT_KEYS = {
        "minor", "major", "bytes-allocated", "bytes-copied", "bytes-promoted",
        "young-used", "old-live", "old-capacity",
    };

    static long arg(Rt rt, int at, int i, int argc) {
        return i < argc ? rt.vat(at + i) : Val.NIL;
    }

    /// A MAP ENTRY as a real two-element vector, for the operations Clojure
    /// gives vector semantics: `conj` appends, `assoc` replaces.
    static long mapEntryAsVec(Rt rt, long e) {
        int base = rt.mark();
        rt.push(rt.slot(e, 0));
        rt.push(rt.slot(e, 1));
        long out = Vec.fromRoots(rt, base, 2);
        rt.popTo(base);
        return out;
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

        // A ROPE join, not a copy. `str` in a loop is what `doc/decisions/0011`
        // exists for: copying makes it quadratic, and the compiler builds its
        // whole output this way.
        def("flint/str2", (rt, at, n) -> Str.concat(rt, rt.vat(at), rt.vat(at + 1)));
        def("flint/num->str", (rt, at, n) -> {
            long v = rt.vat(at);
            return Str.of(rt, Num.isInt(rt, v) ? Long.toString(Num.asI64(rt, v))
                                              : fmtDouble(Val.asDouble(v)));
        });

        /// The CLOSED SET protocol dispatch runs on (`doc/decisions/0005`).
        ///
        /// Small on purpose: three string tiers and eight seq representations
        /// all answer with ONE keyword each, or `extend-protocol :string` would
        /// work for some strings and not others depending on how they were
        /// built. It was MISSING from this port entirely, which meant no
        /// program using a protocol could run here -- found by the language
        /// suite in `test/common`, which is what that suite is for.
        def("flint/kind", (rt, at, n) -> rt.kindOf(rt.vat(at)));

        // --- tables (`doc/decisions/0026`) -----------------------------------
        def("flint/schema", (rt, at, n) -> Table.newSchema(rt, rt.vat(at)));
        def("flint/table", (rt, at, n) -> {
            long s = rt.vat(at);
            if (!Table.isSchema(rt, s))
                return rt.throwStr("IllegalArgumentException",
                    "a table needs a schema; build one with `(schema [[:name :type] ...])`");
            return Table.newTable(rt, s, rt.vat(at + 1));
        });
        def("flint/table?", (rt, at, n) -> Val.bool(Table.isTable(rt, rt.vat(at))));
        def("flint/table-schema", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Table.isTable(rt, v)) return rt.slot(v, Table.TB_SCHEMA);
            if (Table.isTableRef(rt, v)) return rt.slot(v, Table.RF_SCHEMA);
            return Val.NIL;
        });
        def("flint/table-migrate", (rt, at, n) -> {
            long t = rt.vat(at), w = rt.vat(at + 1);
            if (!Table.isTable(rt, t))
                return rt.throwStr("IllegalArgumentException", "migrate wants a table");
            if (!Table.isSchema(rt, w))
                return rt.throwStr("IllegalArgumentException",
                    "migrate wants a schema; build one with `(schema [[:name :type] ...])`");
            return Table.tableMigrate(rt, t, w, rt.vat(at + 2));
        });
        def("flint/table-slice", (rt, at, n) -> {
            long t = rt.vat(at);
            if (!Table.isTable(rt, t))
                return rt.throwStr("IllegalArgumentException", "slice wants a table");
            return Table.tableSlice(rt, t, Val.asFixnum(rt.vat(at + 1)), Val.asFixnum(rt.vat(at + 2)));
        });
        def("flint/table-column", (rt, at, n) -> {
            long t = rt.vat(at);
            if (!Table.isTable(rt, t))
                return rt.throwStr("IllegalArgumentException", "column wants a table");
            return Table.tableColumn(rt, t, rt.vat(at + 1));
        });
        def("flint/table-reduce-column", (rt, at, n) -> {
            long t = rt.vat(at);
            if (!Table.isTable(rt, t))
                return rt.throwStr("IllegalArgumentException", "reduce-column wants a table");
            return Table.tableReduceColumn(rt, t, rt.vat(at + 1), rt.vat(at + 2), rt.vat(at + 3));
        });
        def("flint/schema-columns", (rt, at, n) -> {
            long v = rt.vat(at);
            return Table.isSchema(rt, v) ? rt.slot(v, Table.SC_NAMES) : Val.NIL;
        });
        def("flint/schema-types", (rt, at, n) -> {
            long v = rt.vat(at);
            return Table.isSchema(rt, v) ? rt.slot(v, Table.SC_TYPES) : Val.NIL;
        });

        def("flint/tagged-literal", (rt, at, n) -> {
            long t = rt.vat(at);
            if (!rt.isHeapTy(t, Obj.TY_SYM))
                return rt.throwStr("IllegalArgumentException",
                                   "a tagged literal's tag must be a symbol");
            return rt.newTagged(t, rt.vat(at + 1));
        });
        def("flint/tagged-literal?", (rt, at, n) ->
            Val.bool(rt.isHeapTy(rt.vat(at), Obj.TY_TAGGED)));


        def("flint/opaque?", (rt, at, n) -> Val.FALSE);
        def("flint/opaque-label", (rt, at, n) -> Val.NIL);
        /// The metadata slot, or nil. This was a STUB answering nil, which is
        /// indistinguishable from "no metadata" and so passed every test that
        /// did not set any.
        def("meta", (rt, at, n) -> {
            long v = rt.vat(at);
            int idx = rt.metaSlot(v);
            return idx < 0 ? Val.NIL : rt.slot(v, idx);
        });

        // GENERATED, from `kin/collgen.kin`. The hand-written body had no
        // arm for a TRANSIENT TABLE, so `(count (transient t))` threw here
        // and answered on wasm.
        def("count", (rt, at, n) -> Val.fixnum(Collgen.countOf(rt, rt.vat(at))));
        def("nth", (rt, at, n) -> {
            long v = rt.vat(at);
            int i = (int) Val.asFixnum(rt.vat(at + 1));
            long got = Val.NOT_FOUND;
            if (rt.isHeapTy(v, TY_VEC)) {
                got = Vec.nth(rt, v, i, Val.NOT_FOUND);
            } else if (Str.isString(rt, v)) {
                got = Str.nth(rt, v, i, Val.NOT_FOUND);
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
                if (!Val.isNil(rt.r(s))) got = Seqwalk.first(rt, rt.r(s));
                rt.popTo(base);
            } else {
                return rt.throwStr("UnsupportedOperationException",
"nth over " + rt.describe(v) + " needs more of the data structures");
            }
            if (got != Val.NOT_FOUND) return got;
            if (n > 2) return rt.vat(at + 2);
            return rt.throwStr("IndexOutOfBoundsException",
"index " + i + " out of range");
        });
        def("conj", (rt, at, n) -> {
            long v = rt.vat(at);
            // `conj` on a table APPENDS A ROW, which is what conj means on
            // every indexed collection here.
            if (Table.isTable(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Table.tableConj(rt, acc, rt.vat(at + i));
                return acc;
            }
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
            if (Mapcore.isMap(rt, v)) {
                // `conj` onto a map takes an ENTRY or a two-element vector.
                int base = rt.mark();
                int ai = rt.push(v);
                for (int i = 1; i < n; i++) {
                    long e = rt.vat(at + i);
                    rt.setR(ai, Mapwrite.mapAssoc(rt, rt.r(ai), Seqwalk.first(rt, e), Seqwalk.first(rt, Seqwalk.rest(rt, e))));
                }
                long out = rt.r(ai);
                rt.popTo(base);
                return out;
            }
            // `conj` on a SEQ prepends, where on a vector it appends. That
            // asymmetry is Clojure's and is about where the collection is cheap
            // to grow, not about consistency.
            // A MAP ENTRY conses, exactly as `coll.rs`'s default arm does.
            // It is sequential -- the type-test switch says so now -- and
            // `Seqs.seq` already knows how to walk one.
            // A MAP ENTRY appends, because it is a vector (Clojure). It is
            // ALSO sequential, so it would otherwise cons in the branch below
            // -- both readings exist and Clojure picks the vector one.
            if (rt.isHeapTy(v, TY_MAPENTRY)) {
                int mb = rt.mark();
                int vi = rt.push(mapEntryAsVec(rt, v));
                for (int i = 1; i < n; i++) rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.vat(at + i)));
                long out = rt.r(vi);
                rt.popTo(mb);
                return out;
            }
            if (Val.isNil(v) || rt.isSeq(v)) {
                long acc = Val.isNil(v) ? Seqs.emptyList(rt) : v;
                for (int i = 1; i < n; i++) acc = Seqs.cons(rt, rt.vat(at + i), acc);
                return acc;
            }
            return rt.throwStr("UnsupportedOperationException",
"conj onto " + rt.describe(v) + " needs more of the data structures");
        });

        def("seq", (rt, at, n) -> Seqs.seq(rt, rt.vat(at)));
        def("first", (rt, at, n) -> Seqwalk.first(rt, rt.vat(at)));
        def("next", (rt, at, n) -> Seqs.next(rt, rt.vat(at)));
        def("rest", (rt, at, n) -> Seqwalk.rest(rt, rt.vat(at)));
        def("cons", (rt, at, n) -> Seqs.cons(rt, rt.vat(at), rt.vat(at + 1)));

        // Transients. A transient is a MUTABLE handle on a persistent value,
        // and the whole contract is that the persistent one it came from is
        // untouched -- so `persistent!` invalidates the handle rather than
        // leaving two owners of the same nodes.
        // GENERATED, from `kin/transients.kin`. The arms are the same set the
        // hand-written body had; what moved is that all three runtimes now
        // choose between them in one place.
        def("transient", (rt, at, n) -> Transients.toTransient(rt, rt.vat(at)));
        def("persistent!", (rt, at, n) -> Transients.toPersistent(rt, rt.vat(at)));
        def("conj!", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Table.isTtable(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Table.ttableConj(rt, acc, rt.vat(at + i));
                return acc;
            }
            if (Vec.isTransient(rt, v)) {
                if (!Vec.alive(rt, v)) {
                    return rt.throwStr("IllegalStateException",
"conj! on a transient already made persistent");
                }
                long acc = v;
                for (int i = 1; i < n; i++) acc = Vec.tconj(rt, acc, rt.vat(at + i));
                return acc;
            }
            if (Sets.isTransient(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Maptrans.tsetConj(rt, acc, rt.vat(at + i));
                return acc;
            }
            if (Maps.isTransient(rt, v)) {
                // `conj!` onto a map takes an ENTRY or a two-element vector.
                //
                // THE ACCUMULATOR IS ROOTED. It was a bare Java local passed as
                // the first argument, and Java evaluates arguments LEFT TO
                // RIGHT -- so `acc` was read, and then `Seqs.rest` and
                // `Seqs.first` allocated (a map entry becomes a vector and a
                // seq over it), and `tassoc` wrote through a forwarded pointer.
                //
                // `doc/decisions/0031`: a value in a host local does not
                // survive an allocation. It surfaced as `(into {} m)` inside a
                // `mapv` over a few hundred elements answering "object type 1
                // is not a transient" -- type 1 being `TY_FWD` -- and only
                // under enough allocation to collect, which is why nothing here
                // had ever hit it.
                int base = rt.mark();
                int ai = rt.push(v);
                for (int i = 1; i < n; i++) {
                    int ei = rt.push(rt.vat(at + i));
                    long k = Seqwalk.first(rt, rt.r(ei));
                    int ki = rt.push(k);
                    long val = Seqwalk.first(rt, Seqwalk.rest(rt, rt.r(ei)));
                    int vi2 = rt.push(val);
                    rt.setR(ai, Maptrans.tmapAssoc(rt, rt.r(ai), rt.r(ki), rt.r(vi2)));
                    rt.popTo(ei);
                }
                long out = rt.r(ai);
                rt.popTo(base);
                return out;
            }
            return rt.throwStr("ClassCastException",
                "conj! wants a transient, got " + rt.describe(v));
        });
        def("assoc!", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Vec.isTransient(rt, v)) {
                if (!Vec.alive(rt, v)) {
                    return rt.throwStr("IllegalStateException",
"assoc! on a transient already made persistent");
                }
                long acc = v;
                for (int i = 1; i + 1 < n; i += 2) {
                    acc = Vec.tassoc(rt, acc, (int) Val.asFixnum(rt.vat(at + i)), rt.vat(at + i + 1));
                }
                return acc;
            }
            if (Maps.isTransient(rt, v)) {
                long acc = v;
                for (int i = 1; i + 1 < n; i += 2) acc = Maptrans.tmapAssoc(rt, acc, rt.vat(at + i), rt.vat(at + i + 1));
                return acc;
            }
            return rt.throwStr("ClassCastException",
                "assoc! wants a transient, got " + rt.describe(v));
        });
        def("dissoc!", (rt, at, n) -> {
            long v = rt.vat(at);
            if (Maps.isTransient(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Maptrans.tmapDissoc(rt, acc, rt.vat(at + i));
                return acc;
            }
            if (Sets.isTransient(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Maptrans.tsetDisj(rt, acc, rt.vat(at + i));
                return acc;
            }
            return rt.throwStr("ClassCastException",
                "disj! wants a transient, got " + rt.describe(v));
        });

        def("pop!", (rt, at, n) -> Transients.transientPop(rt, rt.vat(at)));

        // Maps.
        // GENERATED, from `kin/collread.kin`. The hand-written body had no
        // arm for a STRING, for BYTES, or for a ROPE OF BYTES, so `(get "abc"
        // 1)` threw here and answered on wasm. And it tested the key with
        // `isFixnum`, which is false of a BIGINT -- so a key too large for a
        // fixnum was a miss rather than an out-of-range index.
        def("get", (rt, at, n) -> Collread.collGet(rt, rt.vat(at), rt.vat(at + 1),
                                                   n > 2 ? rt.vat(at + 2) : Val.NIL));
        def("assoc", (rt, at, n) -> {
            long acc = rt.vat(at);
            if (Val.isNil(acc)) acc = Maps.empty(rt);
            // A table is indexed by ROW and its schema is CLOSED, so the
            // refusals live where the schema is (`doc/decisions/0026`).
            if (Table.isTable(rt, acc)) return Table.tableAssoc(rt, acc, rt.vat(at + 1), rt.vat(at + 2));
            // A ref is a VIEW: changing it produces an independent MAP, and
            // neither the chunk nor the table it came from moves.
            if (Table.isTableRef(rt, acc)) return Table.refAssoc(rt, acc, rt.vat(at + 1), rt.vat(at + 2));
            if (Mapcore.isMap(rt, acc)) {
                int base = rt.mark();
                int ai = rt.push(acc);
                for (int i = 1; i + 1 < n; i += 2) {
                    long nm = Mapwrite.mapAssoc(rt, rt.r(ai), rt.vat(at + i), rt.vat(at + i + 1));
                    rt.setR(ai, nm);
                }
                long out = rt.r(ai);
                rt.popTo(base);
                return out;
            }
            // Two slots and nowhere for a third, so `assoc` on either key keeps
            // the type and anything else is refused, NAMING the key
            // (`doc/decisions/0034`). Promoting to a map would lose the
            // taggedness silently.
            if (rt.isHeapTy(acc, Obj.TY_TAGGED)) {
                int base = rt.mark();
                int ai = rt.push(acc);
                for (int i = 1; i + 1 < n; i += 2) {
                    long k = rt.vat(at + i), v = rt.vat(at + i + 1);
                    long cur = rt.r(ai);
                    if (k == Str.keyword(rt, null, "tag")) {
                        if (!rt.isHeapTy(v, Obj.TY_SYM)) {
                            rt.popTo(base);
                            return rt.throwStr("IllegalArgumentException",
                                "a tagged literal's :tag must be a symbol");
                        }
                        rt.setR(ai, rt.newTagged(v, rt.slot(cur, 1)));
                    } else if (k == Str.keyword(rt, null, "form")) {
                        rt.setR(ai, rt.newTagged(rt.slot(cur, 0), v));
                    } else {
                        rt.popTo(base);
                        return rt.throwStr("IllegalArgumentException",
                            "a tagged literal has :tag and :form and nothing else, so it "
                            + "cannot take :" + Str.text(rt, kwName(rt, k)));
                    }
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
            // A MAP ENTRY is a vector, so it is associative and `assoc`
            // indexes it. Without this it would answer `associative?` true --
            // that is `(or map? vector?)` -- and then refuse.
            if (rt.isHeapTy(acc, TY_MAPENTRY)) {
                int ab = rt.mark();
                int vi = rt.push(mapEntryAsVec(rt, acc));
                for (int i = 1; i + 1 < n; i += 2) {
                    long k = rt.vat(at + i);
                    if (!Val.isFixnum(k)) {
                        rt.popTo(ab);
                        return rt.throwStr("IllegalArgumentException",
                                           "a map entry is indexed by 0 and 1");
                    }
                    rt.setR(vi, Vec.assoc(rt, rt.r(vi), (int) Val.asFixnum(k), rt.vat(at + i + 1)));
                }
                long out = rt.r(vi);
                rt.popTo(ab);
                return out;
            }
            return rt.throwStr("UnsupportedOperationException",
"assoc onto " + rt.describe(acc) + " needs more of the data structures");
        });
        def("dissoc", (rt, at, n) -> {
            long acc = rt.vat(at);
            if (Val.isNil(acc)) return Val.NIL;
            int base = rt.mark();
            int ai = rt.push(acc);
            for (int i = 1; i < n; i++) {
                long nm = Mapwrite.mapDissoc(rt, rt.r(ai), rt.vat(at + i));
                rt.setR(ai, nm);
            }
            long out = rt.r(ai);
            rt.popTo(base);
            return out;
        });
        // The hand-written body REFUSED anything it did not name, where wasm
        // answered `get` against NOT_FOUND -- so `(contains? "abc" 1)` threw
        // here and was true there. Clojure agrees with wasm on the string.
        def("contains?", (rt, at, n) ->
            Val.bool(Collread.collContains(rt, rt.vat(at), rt.vat(at + 1))));
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
            if (!Val.isDouble(v)) return rt.throwStr("IllegalArgumentException",
"not a number: " + rt.describe(v));
            double d = Val.asDouble(v);
            d = d < 0 ? Math.ceil(d) : Math.floor(d);
            if (!Double.isFinite(d) || d < -9.223372036854776e18 || d > 9.223372036854776e18) {
                return rt.throwStr("IllegalArgumentException",
"value out of long range");
            }
            return Num.integer(rt, (long) d);
        });

        /// The raw IEEE bits of a double, as an integer. What lets flint code
        /// print a double bit-exactly rather than through a formatter.
        def("flint/double-bits", (rt, at, n) -> {
            long v = rt.vat(at);
            if (!Val.isDouble(v)) return rt.throwStr("ClassCastException",
"not a double: " + rt.describe(v));
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
        // GENERATED, from `kin/atoms.kin`. The hand-written body here stored the
        // thunk's result WITHOUT asking whether it threw, so a delay whose
        // thunk failed cached nil forever; Rust checked and left it unforced,
        // so the next deref could try again. Both ports had the bug.
        def("deref", (rt, at, n) -> com._3sln.flint.kgen.rt.Atoms.deref(rt, rt.vat(at)));
        def("reset!", (rt, at, n) -> com._3sln.flint.kgen.rt.Atoms.resetAtom(rt, rt.vat(at), rt.vat(at + 1)));
        def("compare-and-set!", (rt, at, n) -> com._3sln.flint.kgen.rt.Atoms.compareAndSetAtom(rt, rt.vat(at), rt.vat(at + 1), rt.vat(at + 2)));

        // --- dynamic bindings -------------------------------------------------
        //
        // A MAP in a singleton slot, not a per-var stack. `binding` swaps the
        // whole map and restores it, so a park in the middle carries the
        // bindings with the thread rather than leaving them behind.
        def("flint/dyn-get", (rt, at, n) -> {
            long binds = rt.roots.shared.singletons[Rt.SING_BINDINGS];
            if (Val.isNil(binds)) return rt.vat(at + 1);
            return Mapread.mapGet(rt, binds, rt.vat(at), rt.vat(at + 1));
        });
        def("flint/dyn-bindings", (rt, at, n) -> {
            long b = rt.roots.shared.singletons[Rt.SING_BINDINGS];
            return Val.isNil(b) ? Maps.empty(rt) : b;
        });
        def("flint/dyn-set-bindings", (rt, at, n) -> {
            rt.roots.shared.singletons[Rt.SING_BINDINGS] = rt.vat(at);
            return rt.vat(at);
        });

        // --- vectors as stacks ------------------------------------------------
        def("peek", (rt, at, n) -> Collgen.peekOf(rt, rt.vat(at)));
        // The hand-written `pop` had no EMPTY LIST arm: `(pop ())` fell to
        // `rest` and answered `()` where wasm refused it.
        def("pop", (rt, at, n) -> Collgen.popOf(rt, rt.vat(at)));
        def("empty", (rt, at, n) -> Collgen.emptyOf(rt, rt.vat(at)));

        // --- strings ----------------------------------------------------------
        def("flint/subs", (rt, at, n) -> {
            long v0 = rt.vat(at);
            // A ROPE SLICES BY DESCENT AND SHARES ITS CHUNKS. This used to call
            // `Str.text`, which materialises the whole string into a Java
            // `String` -- the same defect the Rust runtime had, in a different
            // shape, and on the operation that most wants sharing
            // (`doc/decisions/0011`).
            if (Str.isRope(rt, v0)) {
                int cps = Str.sCount(rt, v0);
                int st = (int) Val.asFixnum(rt.vat(at + 1));
                int en = n > 2 ? (int) Val.asFixnum(rt.vat(at + 2)) : cps;
                if (st < 0 || en > cps || st > en) {
                    return rt.throwStr("IndexOutOfBoundsException",
                            "subs " + st + ".." + en + " of " + cps);
                }
                boolean ascii = Str.sAscii(rt, v0);
                // For ASCII a code point IS a byte; otherwise descend for the
                // byte offsets rather than scanning.
                int nb = Str.sBytes(rt, v0);
                int from = ascii ? st : Str.ropeByteOfCp(rt, v0, st);
                int to = (en == cps) ? nb : (ascii ? en : Str.ropeByteOfCp(rt, v0, en));
                // ABSENT IS `nb` NOW, not -1: a real offset is 0..nb-1, so the
                // byte length is free to mean "no such code point".
                if (from >= nb) {
                    return rt.throwStr("IndexOutOfBoundsException",
                            "subs " + st + ".." + en + " of " + cps);
                }
                return Str.ropeSlice(rt, v0, from, to);
            }
            String s = Str.text(rt, v0);
            int len = s.codePointCount(0, s.length());
            int start = (int) Val.asFixnum(rt.vat(at + 1));
            int end = n > 2 ? (int) Val.asFixnum(rt.vat(at + 2)) : len;
            if (start < 0 || end > len || start > end) {
                return rt.throwStr("IndexOutOfBoundsException",
"subs " + start + ".." + end + " of " + len);
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
            long ticks = 0;
            while (!Val.isNil(rt.r(s))) {
                // CHARGED AND CHECKED INSIDE THE LOOP: the length is not known
                // until the walk ends (`doc/decisions/0009`).
                if (!rt.chargeTick(ticks++, 1, "str-join")) { rt.popTo(base); return Val.NIL; }
                sb.append(Str.text(rt, Seqwalk.first(rt, rt.r(s))));
                rt.setR(s, Seqs.next(rt, rt.r(s)));
            }
            rt.popTo(base);
            rt.chargeBytes(sb.length());
            return Str.of(rt, sb.toString());
        });
        def("flint/upper-case", (rt, at, n) -> {
            long v = rt.vat(at);
            if (!rt.chargeChecked((Str.sBytes(rt, v) / 8) + 1, "case conversion")) return Val.NIL;
            return Str.of(rt, Str.text(rt, v).toUpperCase());
        });
        def("flint/lower-case", (rt, at, n) -> {
            long v = rt.vat(at);
            if (!rt.chargeChecked((Str.sBytes(rt, v) / 8) + 1, "case conversion")) return Val.NIL;
            return Str.of(rt, Str.text(rt, v).toLowerCase());
        });
        def("flint/code-point-at", (rt, at, n) -> {
            int i = (int) Val.asFixnum(rt.vat(at + 1));
            int c = Str.codePointAt(rt, rt.vat(at), i);
            if (c < 0) {
                return rt.throwStr("IndexOutOfBoundsException", "index " + i + " out of range");
            }
            return Val.fixnum(c);
        });
        def("flint/from-code-point", (rt, at, n) -> {
            long c = Val.asFixnum(rt.vat(at));
            if (c < 0 || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) {
                return rt.throwStr("IllegalArgumentException",
"not a code point: " + c);
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
                return rt.throwStr("ClassCastException",
"bytes->str wants a vector of bytes");
            }
            int c = Vec.count(rt, v);
            byte[] b = new byte[c];
            for (int i = 0; i < c; i++) b[i] = (byte) Val.asFixnum(Vec.nth(rt, v, i, Val.NOT_FOUND));
            return Str.of(rt, new String(b, java.nio.charset.StandardCharsets.UTF_8));
        });
        def("flint/bits->double", (rt, at, n) -> {
            long v = rt.vat(at);
            if (!Num.isInt(rt, v)) return rt.throwStr("ClassCastException",
"bits->double wants an integer");
            return Val.ofDouble(Double.longBitsToDouble(Num.asI64(rt, v)));
        });

        // --- the type predicates ----------------------------------------------
        //
        // Every one goes through `typeP`, whose numbers are the CONTRACT
        // between the compiler and every runtime. Answering them here
        // independently would be a second table to keep in step with the
        // first, and a port that renumbered one would compile and answer
        // wrongly.
        def("string?", (rt, at, n) -> Val.bool(rt.typeP(4, rt.vat(at))));
        def("keyword?", (rt, at, n) -> Val.bool(rt.typeP(5, rt.vat(at))));
        def("symbol?", (rt, at, n) -> Val.bool(rt.typeP(6, rt.vat(at))));
        def("boolean?", (rt, at, n) -> Val.bool(rt.typeP(7, rt.vat(at))));
        def("vector?", (rt, at, n) -> Val.bool(rt.typeP(8, rt.vat(at))));
        def("map?", (rt, at, n) -> Val.bool(rt.typeP(9, rt.vat(at))));
        def("set?", (rt, at, n) -> Val.bool(rt.typeP(10, rt.vat(at))));
        def("seq?", (rt, at, n) -> Val.bool(rt.typeP(11, rt.vat(at))));
        def("fn?", (rt, at, n) -> Val.bool(rt.typeP(12, rt.vat(at))));
        def("sequential?", (rt, at, n) -> Val.bool(rt.isSequential(rt.vat(at))));
        def("bytes?", (rt, at, n) -> Val.bool(rt.isHeapTy(rt.vat(at), TY_BYTES)
                                              || rt.isHeapTy(rt.vat(at), TY_BROPE)));
        def("flint/map-entry?", (rt, at, n) -> Val.bool(rt.isHeapTy(rt.vat(at), TY_MAPENTRY)));
        def("flint/volatile?", (rt, at, n) -> Val.bool(rt.isHeapTy(rt.vat(at), TY_VOLATILE)));
        def("flint/delay?", (rt, at, n) -> Val.bool(rt.isHeapTy(rt.vat(at), TY_DELAY)));

        def("compare", (rt, at, n) -> Val.fixnum(Eq.compare(rt, rt.vat(at), rt.vat(at + 1))));

        // --- metadata ---------------------------------------------------------
        def("with-meta", (rt, at, n) -> {
            long v = rt.vat(at);
            int idx = rt.metaSlot(v);
            if (idx < 0) return v;   // nothing carries metadata: hand it back
            int base = rt.mark();
            int vi = rt.push(v), mi = rt.push(rt.vat(at + 1));
            int t = ty(rt.gc.sp, Val.asHeap(rt.r(vi)));
            int ln = len(rt.gc.sp, Val.asHeap(rt.r(vi)));
            long a = rt.alloc(t, ln);
            if (a == 0) { rt.popTo(base); return Val.NIL; }
            for (int i = 0; i < ln; i++) rt.setSlot(a, i, rt.slot(rt.r(vi), i));
            rt.setSlot(a, idx, rt.r(mi));
            rt.popTo(base);
            return Val.heap(a);
        });

        // --- delays -----------------------------------------------------------
        def("flint/delay", (rt, at, n) -> newCell(rt, TY_DELAY, rt.vat(at)));
        def("flint/realized?", (rt, at, n) -> {
            long v = rt.vat(at);
            if (rt.isHeapTy(v, TY_DELAY)) return Val.bool(Val.isNil(rt.slot(v, 0)));
            if (rt.isHeapTy(v, TY_LAZYSEQ)) return Val.bool(Val.isNil(rt.slot(v, Seqs.LS_THUNK)));
            return Val.TRUE;
        });

        // --- opaque values (`doc/decisions/0022`) -----------------------------
        //
        // Guest code can mint one only with id 0 and there is deliberately no
        // builtin that reads an id back, so an id is a thing the HOST wrote and
        // only the host can read. That is the whole surface, and it is what
        // lets a snapshot preserve identities without granting any.
        def("flint/opaque", (rt, at, n) ->
            rt.newOpaque(n > 0 ? rt.vat(at) : Val.NIL, 0));   // host id 0: minted by the guest
        def("flint/ex-kind", (rt, at, n) -> rt.exKind(rt.vat(at)));
        def("flint/ex-matches?", (rt, at, n) -> rt.exMatches(rt.vat(at), rt.vat(at + 1)));

        /// `flint/array-map` takes ONE argument: a SEQUENCE of alternating keys
        /// and values. It is not varargs, and reading it as varargs is how a
        /// map literal came back EMPTY -- one argument, so the pairwise loop
        /// never ran. `(read-one "{:a 1}")` answered `{}`, and the compiler's
        /// reader is built on this.
        ///
        /// INSERTION ORDER is the point. `into {}` goes through a transient and
        /// a transient map does not preserve it; the reader needs source order
        /// to survive or the self-hosting fixpoint breaks.
        ///
        /// The values are ROOTED AS THEY ARE TAKEN, not gathered into a host
        /// array first. Both calls in the loop can collect -- `first` forces a
        /// lazy seq and `next` forces the tail, which runs arbitrary flint code
        /// -- so anything already gathered would go stale at the first
        /// collection and be written into the map as an address in a space that
        /// has been reused. That is `doc/decisions/0031`, and it needed a map
        /// big enough to span a collection, which is why it survived so long.
        def("flint/array-map", (rt, at, n) -> {
            int base = rt.mark();
            int si = rt.push(Seqs.seq(rt, rt.vat(at)));
            int valsAt = rt.mark();
            int count = 0;
            while (!Val.isNil(rt.r(si))) {
                rt.push(Seqwalk.first(rt, rt.r(si)));
                count++;
                rt.setR(si, Seqs.next(rt, rt.r(si)));
            }
            if (count % 2 != 0) {
                rt.popTo(base);
                return rt.throwStr("IllegalArgumentException",
"array-map needs an even number of forms");
            }
            int pairs = count / 2;
            long a = rt.alloc(TY_ARRAYMAP, Maps.AM_BASE + 2 * pairs);
            if (a == 0) { rt.popTo(base); return Val.NIL; }
            rt.setSlot(a, Maps.AM_META, Val.NIL);
            rt.setSlot(a, Maps.AM_HASH, Val.NIL);
            for (int i = 0; i < 2 * pairs; i++) {
                rt.setSlot(a, Maps.AM_BASE + i, rt.r(valsAt + i));
            }
            rt.popTo(base);
            return Val.heap(a);
        });

        // --- unchecked arithmetic ---------------------------------------------
        //
        // WRAPS rather than throwing, which is the whole point of asking for
        // it: `hash` and the bit-mixing in `map.rs` rely on wrapping, and the
        // checked forms would refuse the very operations those are made of.
        def("flint/unchecked-add", (rt, at, n) ->
            Num.integer(rt, Val.asFixnum(rt.vat(at)) + Val.asFixnum(rt.vat(at + 1))));
        def("flint/unchecked-sub", (rt, at, n) ->
            Num.integer(rt, Val.asFixnum(rt.vat(at)) - Val.asFixnum(rt.vat(at + 1))));
        def("flint/unchecked-mul", (rt, at, n) ->
            Num.integer(rt, Val.asFixnum(rt.vat(at)) * Val.asFixnum(rt.vat(at + 1))));

        // --- byte strings (`doc/decisions/0024`) ------------------------------
        //
        // Not a vector of integers: a flint vector holds NaN-boxed 64-bit
        // values, so a byte would cost eight bytes plus trie overhead. A byte
        // string holds a byte in a byte.
        def("flint/b-count", (rt, at, n) -> Val.fixnum(Bytes.count(rt, rt.vat(at))));
        def("flint/b-at", (rt, at, n) -> {
            // REFUSED past either end. `NOT_FOUND` as the default rather than
            // NIL, because this has to tell "no such byte" from a nil a caller
            // could legitimately have asked for.
            // NEGATIVE IS THE CALLER'S TO REJECT. `b-at`'s index is kin's `I32`,
            // whose contract is that the high bit is never set -- it is `u32`
            // in Rust, where -1 becomes huge and the range check catches it,
            // and `int` here, where -1 sails past a `>=` test and indexes
            // backwards out of the leaf. Rust's builtin has always screened it;
            // this one used to lean on `Bytes.at` doing it instead.
            long idx = Val.asFixnum(rt.vat(at + 1));
            long b = idx < 0 ? Val.NOT_FOUND
                             : Bytes.at(rt, rt.vat(at), (int) idx, Val.NOT_FOUND);
            return b == Val.NOT_FOUND
                ? rt.throwStr("IndexOutOfBoundsException", "byte index out of range")
                : b;
        });
        def("flint/b-concat", (rt, at, n) -> Bytes.concat(rt, rt.vat(at), rt.vat(at + 1)));
        def("flint/b-slice", (rt, at, n) -> {
            // REFUSED, not clamped. This used to lean on `Bytes.slice` doing
            // `Math.max(from, 0)`, so a negative bound quietly became a slice
            // from zero here and `IllegalArgumentException` on native. The
            // bounds are kin's `I32` now, whose contract is that the high bit
            // is never set, so screening them is the caller's job -- and the
            // caller is this.
            long f = Val.asFixnum(rt.vat(at + 1));
            long t = n > 2 ? Val.asFixnum(rt.vat(at + 2)) : Bytes.count(rt, rt.vat(at));
            if (!Val.isFixnum(rt.vat(at + 1)) || (n > 2 && !Val.isFixnum(rt.vat(at + 2)))
                || f < 0 || t < 0)
                return rt.throwStr("IllegalArgumentException", "b-slice wants two integers");
            return Bytes.slice(rt, rt.vat(at), (int) f, (int) t);
        });
        def("flint/b-depth", (rt, at, n) -> Val.fixnum(Bytes.depth(rt, rt.vat(at))));
        def("flint/str->b", (rt, at, n) -> Bytes.of(rt, Str.bytes(rt, rt.vat(at))));
        def("flint/b->str", (rt, at, n) -> Str.of(rt,
            new String(Bytes.toArray(rt, rt.vat(at)), java.nio.charset.StandardCharsets.UTF_8)));
        def("flint/vec->b", (rt, at, n) -> {
            long v = rt.vat(at);
            int c = Vec.count(rt, v);
            byte[] b = new byte[c];
            for (int i = 0; i < c; i++) b[i] = (byte) Val.asFixnum(Vec.nth(rt, v, i, Val.NOT_FOUND));
            return Bytes.of(rt, b);
        });
        def("flint/b->vec", (rt, at, n) -> {
            byte[] b = Bytes.toArray(rt, rt.vat(at));
            int base = rt.mark();
            int vi = rt.push(Vec.empty(rt));
            for (byte x : b) rt.setR(vi, Vec.conj(rt, rt.r(vi), Val.fixnum(x & 0xFF)));
            long out = rt.r(vi);
            rt.popTo(base);
            return out;
        });
        def("flint/b-transient", (rt, at, n) -> Bytes.transientOf(rt, rt.vat(at)));
        def("flint/b-conj!", (rt, at, n) ->
            Bytes.conj(rt, rt.vat(at), (int) Val.asFixnum(rt.vat(at + 1))));
        def("flint/b-append!", (rt, at, n) -> Bytes.appendBytes(rt, rt.vat(at), rt.vat(at + 1)));
        def("flint/b-tcount", (rt, at, n) -> Val.fixnum(Bytes.tcount(rt, rt.vat(at))));
        def("flint/b-persistent!", (rt, at, n) -> Bytes.persistent(rt, rt.vat(at)));

        // --- regex ------------------------------------------------------------
        //
        // The PATTERN is compiled to a program by flint's own library, in
        // Clojure; this runs it. That split is why the engine is the same on
        // every runtime -- there is no host regex anywhere in it, so there is
        // no way for two hosts to disagree about what a pattern means.
        def("flint/re-compile", (rt, at, n) -> Pike.compile(rt, rt.vat(at), rt.vat(at + 1)));
        def("flint/re-run", (rt, at, n) -> {
            long from = n > 2 ? Val.asFixnum(rt.vat(at + 2)) : 0;
            // 0 searches from `from`; 3 matches exactly at it. Both are entry
            // points into ONE program, so there is no second program to keep in
            // step with the first.
            int entry = n > 3 ? (int) Val.asFixnum(rt.vat(at + 3)) : 0;
            // The fifth argument asks for a match reaching the END, which is
            // `re-matches` and cannot be had by checking the span afterwards.
            boolean full = n > 4 && Val.asFixnum(rt.vat(at + 4)) != 0;
            return Pike.run(rt, rt.vat(at), rt.vat(at + 1), from, entry, full);
        });
        def("flint/re-find-all", (rt, at, n) ->
            Pike.findAll(rt, rt.vat(at), rt.vat(at + 1), n > 2 ? Val.asFixnum(rt.vat(at + 2)) : 0));

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
                return rt.throwStr("ClassCastException",
"thread-state wants a thread, got " + rt.describe(t));
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
            long b = rt.roots.shared.singletons[Rt.SING_BINDINGS];
            return Val.isNil(b) ? Maps.empty(rt) : b;
        });
        def("flint/set-bindings", (rt, at, n) -> {
            rt.roots.shared.singletons[Rt.SING_BINDINGS] = rt.vat(at);
            return rt.vat(at);
        });

        def("flint/channel", (rt, at, n) -> {
            long cap = n > 0 ? rt.vat(at) : Val.NIL;
            long label = n > 1 ? rt.vat(at + 1) : Val.NIL;
            long c = Val.isFixnum(cap) ? Val.asFixnum(cap) : Conc.DEFAULT_CAP;
            if (c < 1) return rt.throwStr("IllegalArgumentException",
                "a channel needs a buffer of at least 1");
            return Conc.channel(rt, c, label);
        });
        def("flint/open", (rt, at, n) -> {
            long name = rt.vat(at);
            if (!Str.isString(rt, name)) {
                return rt.throwStr("ClassCastException", "open wants a name (a string)");
            }
            // EVERY REMAINING ARGUMENT IS FORWARDED, and the runtime takes no
            // view of any of them. A capability is an opaque value like any
            // other and travels as one; nothing here knows the word, which is
            // the point (`doc/decisions/0022`).
            int base = rt.mark();
            int ni = rt.push(name);
            int vi = rt.push(Vec.empty(rt));
            for (int i = 1; i < n; i++) {
                rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.vat(at + i)));
            }
            long nm = rt.r(ni), args = rt.r(vi);
            rt.popTo(base);
            return Conc.portOpen(rt, nm, args);
        });
        def("flint/request", (rt, at, n) -> {
            long what = rt.vat(at);
            if (!Str.isString(rt, what)) {
                return rt.throwStr("ClassCastException", "request wants a name (a string)");
            }
            // Identical to `open` above, and deliberately so: same forwarding,
            // same no-view-of-the-arguments. What differs is what comes back
            // (`doc/decisions/0036` step 7).
            int base = rt.mark();
            int ni = rt.push(what);
            int vi = rt.push(Vec.empty(rt));
            for (int i = 1; i < n; i++) {
                rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.vat(at + i)));
            }
            long nm = rt.r(ni), args = rt.r(vi);
            rt.popTo(base);
            return Conc.hostRequest(rt, nm, args);
        });
        def("flint/port-send", (rt, at, n) -> Conc.send(rt, rt.vat(at), rt.vat(at + 1)));
        def("flint/port-receive", (rt, at, n) -> Conc.receive(rt, rt.vat(at)));
        def("flint/port-close", (rt, at, n) -> Conc.close(rt, rt.vat(at)));
        def("flint/port?", (rt, at, n) -> Val.bool(Conc.isPort(rt, rt.vat(at))));
        def("flint/port-id", (rt, at, n) -> {
            long p = rt.vat(at);
            if (!Conc.isPort(rt, p)) return rt.throwStr("ClassCastException", "port-id wants a port");
            return rt.slot(p, Conc.PT_ID);
        });
        def("flint/port-label", (rt, at, n) -> {
            long p = rt.vat(at);
            if (!Conc.isPort(rt, p)) return rt.throwStr("ClassCastException", "port-label wants a port");
            return rt.slot(p, Conc.PT_LABEL);
        });
        /// Does this port carry BYTES across a boundary? A bridge does and a
        /// channel does not, and that is the only distinction a guest can see
        /// -- it cannot see the encoding, because the runtime owns it.
        def("flint/port-bridge?", (rt, at, n) -> {
            long p = rt.vat(at);
            if (!Conc.isPort(rt, p)) return rt.throwStr("ClassCastException", "port-bridge? wants a port");
            return Val.bool(Conc.crossesAHeap(Val.asFixnum(rt.slot(p, Conc.PT_KIND))));
        });
        def("flint/port-state", (rt, at, n) -> {
            long p = rt.vat(at);
            if (!Conc.isPort(rt, p)) return rt.throwStr("ClassCastException", "port-state wants a port");
            // THE QUERY IS THE TRUTH (`doc/decisions/0006`), so it resolves the
            // peer rather than reporting a state that reaping has not caught up
            // with yet.
            switch ((int) Conc.portStateNow(rt, p)) {
                case Conc.P_PENDING: return Str.keyword(rt, null, "pending");
                case Conc.P_OPEN: return Str.keyword(rt, null, "open");
                case Conc.P_CLOSED: return Str.keyword(rt, null, "closed");
                case Conc.P_HALF: return Str.keyword(rt, null, "half-closed");
                case Conc.P_ORPHANED: return Str.keyword(rt, null, "orphaned");
                default: return Str.keyword(rt, null, "refused");
            }
        });

        // Exceptions. `ex-info` is `[msg, data, cause]`, and `throw` is an
        // OPCODE rather than a builtin -- these are what a handler reads.
        def("ex-info", (rt, at, n) -> {
            int base = rt.mark();
            int mi = rt.push(rt.vat(at));
            int di = rt.push(n > 1 ? rt.vat(at + 1) : Val.NIL);
            int ci = rt.push(n > 2 ? rt.vat(at + 2) : Val.NIL);
            long k = Str.of(rt, "ExceptionInfo");
            long out = Rt.exInfo(rt, k, rt.r(mi), rt.r(di), rt.r(ci));
            rt.popTo(base);
            return out;
        });
        def("ex-message", (rt, at, n) -> rt.exMessage(rt.vat(at)));
        def("ex-data", (rt, at, n) -> rt.exData(rt.vat(at)));

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
                rt.push(Seqwalk.first(rt, rt.r(si)));
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

        // --- the type-annotation barrier -----------------------------------
        //
        // `(let [^int x e] ...)` compiles to a bind of `check-tag(e, INT,
        // where)`. The check is what makes the annotation SOUND rather than a
        // hint: every read of `x` after it is known to be an int, so the reads
        // can be specialised, and the cost is one test at the write instead of
        // one at each read. An annotation the analyzer already proved emits no
        // call at all, so this runs only where something was genuinely unknown.
        //
        // Missing here for 210 commits, because `bin/check-builtins` was
        // looking for this file at its pre-`rt/` path and crashing instead of
        // reporting. `byName` answers null for a native it does not have, so
        // the symptom was not a refusal naming the missing builtin -- it was a
        // null dereference the first time an annotated binding ran.
        def("flint/check-tag", (rt, at, n) -> {
            long v = rt.vat(at);
            long code = Num.asI64(rt, rt.vat(at + 1));
            // An unknown code is a compiler that has drifted from this table.
            // Failing loudly beats passing everything: a silent `true` would
            // make every annotation vacuous and every specialisation built on
            // one unsound. The range is checked HERE because `typeP`'s default
            // arm is permissive -- it answers the `sequential` test -- and
            // leaning on it would turn a drifted code into a quiet yes.
            if (code < 1 || code > 14) {
                return rt.throwStr("IllegalArgumentException",
                                   "check-tag: unknown type code");
            }
            if (rt.typeP((int) code, v)) return v;
            String name = TAG_NAMES[(int) code - 1];
            long site = arg(rt, at, 2, n);
            String msg = Str.isString(rt, site)
                ? Str.text(rt, site) + " is declared ^" + name + ", and it is not"
                : "a value declared ^" + name + " is not one";
            return rt.throwStr("ClassCastException", msg);
        });

        // The GC's counters, as a map. Guest-reachable through
        // `flint.rt/gc-stats`, which `test/opaque.cljc` and `test/pause.cljc`
        // use to assert that a shape does not allocate.
        def("flint/gc-stats", (rt, at, n) -> {
            long[] vals = { rt.gc.minors, rt.gc.majors, rt.gc.bytesAllocated,
                            rt.gc.bytesCopied, rt.gc.bytesPromoted,
                            rt.gc.youngUsed(), rt.gc.oldLive, rt.gc.oldCapacity };
            int base = rt.mark();
            int mi = rt.push(Maps.empty(rt));
            for (int i = 0; i < GC_STAT_KEYS.length; i++) {
                // The keyword stays ROOTED across `integer` and `assoc`, both
                // of which allocate. A value in a host local does not survive
                // an allocation (`doc/decisions/0031`).
                int ki = rt.push(Str.keyword(rt, null, GC_STAT_KEYS[i]));
                long vv = Num.integer(rt, vals[i]);
                rt.setR(mi, Mapwrite.mapAssoc(rt, rt.r(mi), rt.r(ki), vv));
                rt.popTo(ki);
            }
            long out = rt.r(mi);
            rt.popTo(base);
            return out;
        });

        // Lazy sequences and ranges.
        def("flint/lazy-seq", (rt, at, n) -> com._3sln.flint.kgen.rt.Seqs.lazySeq(rt, rt.vat(at)));
        def("flint/range3", (rt, at, n) ->
            com._3sln.flint.kgen.rt.Seqs.range(rt, rt.vat(at), rt.vat(at + 1), rt.vat(at + 2)));

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
            return rt.throwStr("IllegalArgumentException",
"not a number: " + rt.describe(v));
        }
        return Val.ofDouble(f.apply(Num.f64(rt, v)));
    }

    static long mathTwo(Rt rt, long a, long b, D2 f) {
        if (!Num.isNumber(rt, a) || !Num.isNumber(rt, b)) {
            return rt.throwStr("IllegalArgumentException",

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
