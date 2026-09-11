namespace Flint.Rt;

using _3sln.Flint.Kgen.Rt;

/// The builtins, ported from `runtime/src/builtins.rs`.
///
/// A builtin reads its arguments STRAIGHT OFF the value stack -- `at` is the
/// first, `argc` how many -- rather than being handed an array. That is not a
/// micro-optimisation: an array would be a second place values live, and the
/// collector would have to be taught about it. Everything live is in the value
/// stack, and this keeps that true with no second mechanism.
public static class Builtins {
    public delegate long Fn(Rt rt, int at, int argc);

    /// `:tag` and `:form` on a tagged literal (`DECISIONS.md#tagged-literals`). Shared
    /// with the keyword-apply path, or the same lookup by two spellings
    /// disagrees.
    /// A keyword's name, for a message that has to say WHICH key.
    static long KwName(Rt rt, long v) {
        if (Val.IsInlineKw(v)) return Val.InlineStr(Val.InlineBytes(v));
        if (rt.IsHeapTy(v, Obj.TyKw) || rt.IsHeapTy(v, Obj.TySym)) return rt.Slot(v, 1);
        return v;
    }

    internal static long TaggedGet(Rt rt, long t, long k, long dflt) {
        if (k == Str.Keyword(rt, null, "tag")) return rt.Slot(t, 0);
        if (k == Str.Keyword(rt, null, "form")) return rt.Slot(t, 1);
        return dflt;
    }

    /// The annotation names, indexed by the code `check-tag` is handed. The
    /// codes are `flint.types/code` and `test/types.clj` asserts the tables
    /// agree; they are integers rather than keywords because this is on the
    /// write path of every annotated binding.
    static readonly string[] TagNames = {
        "int", "float", "number", "string", "keyword", "symbol", "boolean",
        "vector", "map", "set", "seq", "fn", "nil", "sequential",
    };

    static readonly string[] GcStatKeys = {
        "minor", "major", "bytes-allocated", "bytes-copied", "bytes-promoted",
        "young-used", "old-live", "old-capacity",
    };

    static readonly Dictionary<string, Fn> Table = new();

    /// A MAP ENTRY as a real two-element vector, for the operations Clojure
    /// gives vector semantics: `conj` appends, `assoc` replaces.
    public static Fn ByName(string n) => Table.TryGetValue(n, out var f) ? f : null;
    static void Def(string n, Fn f) => Table[n] = f;

    /// flint's integers OVERFLOW rather than wrap. The JVM has `Math.*Exact`;
    /// .NET has `checked`, and `DECISIONS.md#other-hosts` names silent wrapping as
    /// one of the ways two hosts quietly disagree -- so every one of these is
    /// checked.
    static long AddExact(long a, long b) { checked { return a + b; } }
    static long SubExact(long a, long b) { checked { return a - b; } }
    static long MulExact(long a, long b) { checked { return a * b; } }
    static long NegExact(long a) { checked { return -a; } }

    static Builtins() {
        // Arithmetic goes through `Num`, which owns the PROMOTION RULE:
        // integers stay integers and overflow rather than wrap, and any double
        // in the operands makes the whole expression a double. These read every
        // argument as a fixnum once, which silently read a double's MANTISSA as
        // an integer -- `(+ 1.5 2.5)` came back 0 and agreed with nothing.
        // FROM THE FIRST ARGUMENT, and STOPPING when one fails. Starting from
        // the identity and folding every argument into it gives the same answer
        // for valid input and a WRONG MESSAGE for invalid: `(* [1] 2)` failed on
        // `(1, [1])`, kept going with the nil that failure returned, and
        // reported "not a number: nil and an integer" -- naming an operand the
        // program never wrote. Continuing after a throw is also work done inside
        // a runtime that is already unwinding.
        Def("flint/add", (rt, at, n) => {
            if (n == 0) return Val.Fixnum(0);
            long acc = rt.VAt(at);
            for (int i = 1; i < n; i++) {
                acc = Num.Add(rt, acc, rt.VAt(at + i));
                if (!Val.IsNil(rt.thrown)) return Val.Nil;
            }
            return acc;
        });
        Def("+", (rt, at, n) => ByName("flint/add")(rt, at, n));
        Def("flint/sub", (rt, at, n) => {
            if (n == 1) return global::_3sln.Flint.Kgen.Rt.Numdiv.NumNeg(rt, rt.VAt(at));
            long acc = rt.VAt(at);
            for (int i = 1; i < n; i++) acc = Num.Sub(rt, acc, rt.VAt(at + i));
            return acc;
        });
        Def("-", (rt, at, n) => ByName("flint/sub")(rt, at, n));
        Def("flint/mul", (rt, at, n) => {
            if (n == 0) return Val.Fixnum(1);
            long acc = rt.VAt(at);
            for (int i = 1; i < n; i++) {
                acc = Num.Mul(rt, acc, rt.VAt(at + i));
                if (!Val.IsNil(rt.thrown)) return Val.Nil;
            }
            return acc;
        });
        Def("*", (rt, at, n) => ByName("flint/mul")(rt, at, n));
        Def("flint/lt", (rt, at, n) => Cmp(rt, at, n, -1, false));
        Def("<", (rt, at, n) => Cmp(rt, at, n, -1, false));
        Def("flint/le", (rt, at, n) => Cmp(rt, at, n, -1, true));
        Def("<=", (rt, at, n) => Cmp(rt, at, n, -1, true));
        Def("flint/gt", (rt, at, n) => Cmp(rt, at, n, 1, false));
        Def(">", (rt, at, n) => Cmp(rt, at, n, 1, false));
        Def("flint/ge", (rt, at, n) => Cmp(rt, at, n, 1, true));
        Def(">=", (rt, at, n) => Cmp(rt, at, n, 1, true));
        Def("flint/num-eq", (rt, at, n) => {
            for (int i = 1; i < n; i++) if (!Num.NumEq(rt, rt.VAt(at), rt.VAt(at + i))) return Val.False;
            return Val.True;
        });
        Def("==", (rt, at, n) => ByName("flint/num-eq")(rt, at, n));
        Def("inc", (rt, at, n) => Num.Add(rt, rt.VAt(at), Val.Fixnum(1)));
        Def("dec", (rt, at, n) => Num.Sub(rt, rt.VAt(at), Val.Fixnum(1)));

        Def("identical?", (rt, at, n) => Val.Bool(rt.VAt(at) == rt.VAt(at + 1)));
        Def("nil?", (rt, at, n) => Val.Bool(Val.IsNil(rt.VAt(at))));
        Def("not", (rt, at, n) => Val.Bool(!Val.Truthy(rt.VAt(at))));
        Def("true?", (rt, at, n) => Val.Bool(rt.VAt(at) == Val.True));
        Def("false?", (rt, at, n) => Val.Bool(rt.VAt(at) == Val.False));
        Def("boolean", (rt, at, n) => Val.Bool(Val.Truthy(rt.VAt(at))));
        Def("number?", (rt, at, n) => Val.Bool(Num.IsNumber(rt, rt.VAt(at))));
        Def("int?", (rt, at, n) => Val.Bool(Num.IsInt(rt, rt.VAt(at))));
        Def("float?", (rt, at, n) => Val.Bool(Num.IsFloat(rt.VAt(at))));
        Def("double", (rt, at, n) => Val.OfDouble(Num.F64(rt, rt.VAt(at))));
        Def("long", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Num.IsInt(rt, v)) return v;
            // TRUNCATES toward zero, as Clojure's `long` does on a double.
            return Num.Integer(rt, (long) Num.F64(rt, v));
        });
        Def("zero?", (rt, at, n) => Val.Bool(Num.NumEq(rt, rt.VAt(at), Val.Fixnum(0))));
        Def("pos?", (rt, at, n) => Val.Bool(Num.Cmp(rt, rt.VAt(at), Val.Fixnum(0)) > 0));
        Def("neg?", (rt, at, n) => Val.Bool(Num.Cmp(rt, rt.VAt(at), Val.Fixnum(0)) < 0));

        Def("quot", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Numdiv.NumQuot(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("rem", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Numdiv.NumRem(rt, rt.VAt(at), rt.VAt(at + 1)));
        // `/` on two integers that do not divide evenly is a DOUBLE here, not a
        // Ratio: flint has no rational type, and `DECISIONS.md#other-hosts` counts
        // this among the documented divergences from Clojure rather than a bug.
        Def("flint/div", (rt, at, n) => {
            if (n == 1) return global::_3sln.Flint.Kgen.Rt.Numdiv.NumDiv(rt, Val.Fixnum(1), rt.VAt(at));
            long acc = rt.VAt(at);
            for (int i = 1; i < n; i++) acc = global::_3sln.Flint.Kgen.Rt.Numdiv.NumDiv(rt, acc, rt.VAt(at + i));
            return acc;
        });
        Def("/", (rt, at, n) => ByName("flint/div")(rt, at, n));

        Def("bit-and", (rt, at, n) => Val.Fixnum(Val.AsFixnum(rt.VAt(at)) & Val.AsFixnum(rt.VAt(at + 1))));
        Def("bit-or", (rt, at, n) => Val.Fixnum(Val.AsFixnum(rt.VAt(at)) | Val.AsFixnum(rt.VAt(at + 1))));
        Def("bit-xor", (rt, at, n) => Val.Fixnum(Val.AsFixnum(rt.VAt(at)) ^ Val.AsFixnum(rt.VAt(at + 1))));
        Def("bit-not", (rt, at, n) => Val.Fixnum(~Val.AsFixnum(rt.VAt(at))));
        Def("bit-shift-left", (rt, at, n) =>
            Val.Fixnum(Val.AsFixnum(rt.VAt(at)) << (int) Val.AsFixnum(rt.VAt(at + 1))));
        Def("bit-shift-right", (rt, at, n) =>
            Val.Fixnum(Val.AsFixnum(rt.VAt(at)) >> (int) Val.AsFixnum(rt.VAt(at + 1))));
        Def("unsigned-bit-shift-right", (rt, at, n) =>
            Val.Fixnum((long)((ulong) Val.AsFixnum(rt.VAt(at)) >> (int) Val.AsFixnum(rt.VAt(at + 1)))));
        Def("bit-test", (rt, at, n) =>
            Val.Bool(((Val.AsFixnum(rt.VAt(at)) >> (int) Val.AsFixnum(rt.VAt(at + 1))) & 1) != 0));

        Def("name", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Val.IsInlineKw(v)) return Val.InlineStr(Val.InlineBytes(v));
            if (rt.IsHeapTy(v, Obj.TyKw) || rt.IsHeapTy(v, Obj.TySym)) return rt.Slot(v, 1);
            return v;   // a string names itself
        });
        Def("namespace", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Val.IsInlineKw(v)) return Val.Nil;
            if (rt.IsHeapTy(v, Obj.TyKw) || rt.IsHeapTy(v, Obj.TySym)) return rt.Slot(v, 0);
            return Val.Nil;
        });

        // A ROPE join, not a copy. `str` in a loop is what
        // `DECISIONS.md#strings-and-matching` exists for: copying makes it quadratic, and the
        // compiler builds its whole output this way.
        Def("flint/str2", (rt, at, n) => Str.Concat(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("flint/num->str", (rt, at, n) =>
            global::_3sln.Flint.Kgen.Rt.Dblstr.NumToStr(rt, rt.VAt(at)));

        /// The CLOSED SET protocol dispatch runs on (`DECISIONS.md#threads-and-ports`).
        /// Small on purpose: three string tiers and eight seq representations
        /// answer with ONE keyword each. It was MISSING from this port, so no
        /// program using a protocol could run here -- found by the language
        /// suite in `test/common`, which is what that suite is for.
        Def("flint/kind", (rt, at, n) => rt.KindOf(rt.VAt(at)));

        // --- tables (`DECISIONS.md#tables`) -----------------------------------
        Def("flint/schema", (rt, at, n) => Flint.Rt.Table.newSchema(rt, rt.VAt(at)));
        Def("flint/table", (rt, at, n) => {
            long s2 = rt.VAt(at);
            if (!Flint.Rt.Table.isSchema(rt, s2))
                return rt.ThrowStr("IllegalArgumentException",
                    "a table needs a schema; build one with `(schema [[:name :type] ...])`");
            return Flint.Rt.Table.newTable(rt, s2, rt.VAt(at + 1));
        });
        // The vector a map or set already has -- see the Rust copy.
        Def("flint/coll-vec", (rt, at, n) => {
            long v = rt.VAt(at);
            // The CONCRETE type, not `map?` -- see the Rust copy.
            if (!Val.IsHeap(v)) return Val.Nil;
            int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
            if (t == Obj.TyArraymap || t == Obj.TyHashmap) return Maps.EntryVector(rt, v);
            if (t == Obj.TySet) return Sets.ElementVector(rt, v);
            return Val.Nil;
        });
        Def("flint/table?", (rt, at, n) => Val.Bool(Flint.Rt.Table.isTable(rt, rt.VAt(at))));
        Def("flint/table-schema", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Flint.Rt.Table.isTable(rt, v)) return rt.Slot(v, Flint.Rt.Table.TB_SCHEMA);
            if (Flint.Rt.Table.isTableRef(rt, v)) return rt.Slot(v, Flint.Rt.Table.RF_SCHEMA);
            return Val.Nil;
        });
        Def("flint/schema-columns", (rt, at, n) => {
            long v = rt.VAt(at);
            return Flint.Rt.Table.isSchema(rt, v) ? rt.Slot(v, Flint.Rt.Table.SC_NAMES) : Val.Nil;
        });
        Def("flint/schema-types", (rt, at, n) => {
            long v = rt.VAt(at);
            return Flint.Rt.Table.isSchema(rt, v) ? rt.Slot(v, Flint.Rt.Table.SC_TYPES) : Val.Nil;
        });
        Def("flint/table-migrate", (rt, at, n) => {
            long t = rt.VAt(at), w = rt.VAt(at + 1);
            if (!Flint.Rt.Table.isTable(rt, t))
                return rt.ThrowStr("IllegalArgumentException", "migrate wants a table");
            if (!Flint.Rt.Table.isSchema(rt, w))
                return rt.ThrowStr("IllegalArgumentException",
                    "migrate wants a schema; build one with `(schema [[:name :type] ...])`");
            return Flint.Rt.Table.tableMigrate(rt, t, w, rt.VAt(at + 2));
        });
        Def("flint/table-slice", (rt, at, n) => {
            long t = rt.VAt(at);
            if (!Flint.Rt.Table.isTable(rt, t))
                return rt.ThrowStr("IllegalArgumentException", "slice wants a table");
            return Flint.Rt.Table.tableSlice(rt, t, Val.AsFixnum(rt.VAt(at + 1)), Val.AsFixnum(rt.VAt(at + 2)));
        });
        Def("flint/table-column", (rt, at, n) => {
            long t = rt.VAt(at);
            if (!Flint.Rt.Table.isTable(rt, t))
                return rt.ThrowStr("IllegalArgumentException", "column wants a table");
            return Flint.Rt.Table.tableColumn(rt, t, rt.VAt(at + 1));
        });
        Def("flint/table-reduce-column", (rt, at, n) => {
            long t = rt.VAt(at);
            if (!Flint.Rt.Table.isTable(rt, t))
                return rt.ThrowStr("IllegalArgumentException", "reduce-column wants a table");
            return Flint.Rt.Table.tableReduceColumn(rt, t, rt.VAt(at + 1), rt.VAt(at + 2), rt.VAt(at + 3));
        });

        Def("flint/tagged-literal", (rt, at, n) => {
            long t = rt.VAt(at);
            if (!rt.IsHeapTy(t, Obj.TySym))
                return rt.ThrowStr("IllegalArgumentException",
                                   "a tagged literal's tag must be a symbol");
            return rt.NewTagged(t, rt.VAt(at + 1));
        });
        Def("flint/tagged-literal?", (rt, at, n) =>
            Val.Bool(rt.IsHeapTy(rt.VAt(at), Obj.TyTagged)));


        // NOT STUBS. These answered `false` and `nil` for everything, including
        // for an opaque value this same file had just minted -- so `opaque-values` held
        // on native and was decoration here: `(opaque? (opaque))` was false and
        // a label was never readable. `Opaque` is GENERATED and both ports
        // already carried it; nothing called it.
        Def("flint/opaque?", (rt, at, n) => Val.Bool(rt.IsOpaque(rt.VAt(at))));
        Def("flint/opaque-label", (rt, at, n) => rt.OpaqueLabel(rt.VAt(at)));
        /// The metadata slot, or nil. This was a STUB answering nil, which is
        /// indistinguishable from "no metadata" and so passed every test that
        /// did not set any.
        Def("meta", (rt, at, n) => {
            long v = rt.VAt(at);
            int idx = rt.MetaSlot(v);
            return idx < 0 ? Val.Nil : rt.Slot(v, idx);
        });

        // GENERATED, from `kin/collgen.kin`. The hand-written body had no
        // arm for a TRANSIENT TABLE, so `(count (transient t))` threw here
        // and answered on wasm.
        Def("count", (rt, at, n) => Val.Fixnum(Collgen.CountOf(rt, rt.VAt(at))));
        // The hand-written body read the index with `AsFixnum` and no check,
        // so a keyword index was garbage rather than a refusal and a BIGINT
        // index was a different number. It also had no arm for BYTES, which
        // `count` and `get` both have -- so `(nth bs 1)` said the index was
        // out of range on a byte string plainly long enough.
        Def("nth", (rt, at, n) => Collwrite.CollNth(rt, rt.VAt(at), rt.VAt(at + 1),
                                                    n > 2 ? rt.VAt(at + 2) : Val.NotFound));
        Def("conj", (rt, at, n) => {
            long v = rt.VAt(at);
            // `conj` on a table APPENDS A ROW.
            if (Flint.Rt.Table.isTable(rt, v)) {
                long tacc = v;
                for (int i = 1; i < n; i++) tacc = Flint.Rt.Table.tableConj(rt, tacc, rt.VAt(at + i));
                return tacc;
            }
            if (rt.IsHeapTy(v, Obj.TyVec)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Vec.Conj(rt, acc, rt.VAt(at + i));
                return acc;
            }
            if (Sets.IsSet(rt, v)) {
                long acc2 = v;
                for (int i = 1; i < n; i++) acc2 = Sets.Conj(rt, acc2, rt.VAt(at + i));
                return acc2;
            }
            if (Mapcore.IsMap(rt, v)) {
                // `conj` onto a map takes an ENTRY, a two-element vector, or
                // ANOTHER MAP, which merges. Anything else is refused.
                //
                // It used to take `first` and `first (rest ..)` of whatever
                // arrived, which answers `nil` for both halves of a value that
                // is neither -- so `(conj {:a 1} 7)` produced `{:a 1, nil nil}`
                // and `(conj {:a 1} {:b 2})` produced `{:a 1, [:b 2] nil}`,
                // silently, where native and Clojure throw and merge.
                int bas = rt.Mark();
                int ai = rt.Push(v);
                for (int i = 1; i < n; i++) {
                    long e = rt.VAt(at + i);
                    if (rt.IsHeapTy(e, Obj.TyMapentry) || rt.IsHeapTy(e, Obj.TyVec)) {
                        rt.SetR(ai, Mapwrite.MapAssoc(rt, rt.R(ai),
                                                      rt.SlotOrNth(e, 0), rt.SlotOrNth(e, 1)));
                    } else if (Mapcore.IsMap(rt, e)) {
                        int ei = rt.Push(global::_3sln.Flint.Kgen.Rt.Seqwalk.Seq(rt, e));
                        while (!Val.IsNil(rt.R(ei))) {
                            long ent = global::_3sln.Flint.Kgen.Rt.Seqwalk.First(rt, rt.R(ei));
                            rt.SetR(ai, Mapwrite.MapAssoc(rt, rt.R(ai),
                                                          rt.SlotOrNth(ent, 0), rt.SlotOrNth(ent, 1)));
                            rt.SetR(ei, global::_3sln.Flint.Kgen.Rt.Seqwalk.Next(rt, rt.R(ei)));
                        }
                    } else {
                        rt.PopTo(bas);
                        return rt.ThrowStr("IllegalArgumentException",
                                           "conj on a map wants a map entry");
                    }
                }
                long outc = rt.R(ai);
                rt.PopTo(bas);
                return outc;
            }
            // `conj` on a SEQ prepends, where on a vector it appends. That
            // asymmetry is Clojure's and is about where the collection is cheap
            // to grow, not about consistency.
            // A MAP ENTRY conses, exactly as `coll.rs`'s default arm does.
            // It is sequential -- the type-test switch says so now -- and
            // `Seqs.Seq` already knows how to walk one.
            // A MAP ENTRY appends, because it is a vector (Clojure). It is
            // ALSO sequential, so it would otherwise cons in the branch below
            // -- both readings exist and Clojure picks the vector one.
            if (rt.IsHeapTy(v, Obj.TyMapentry)) {
                int mb = rt.Mark();
                int vi = rt.Push(Vec.MapEntryAsVec(rt, v));
                for (int i = 1; i < n; i++) rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.VAt(at + i)));
                long o2 = rt.R(vi);
                rt.PopTo(mb);
                return o2;
            }
            if (Val.IsNil(v) || rt.IsSeq(v)) {
                long acc = Val.IsNil(v) ? Seqs.EmptyList(rt) : v;
                for (int i = 1; i < n; i++) acc = Seqs.Cons(rt, rt.VAt(at + i), acc);
                return acc;
            }
            // A NON-HEAP VALUE IS NOT A COLLECTION, and never will be, so it is
            // a TYPE ERROR and not a missing feature. The message below means
            // "this port has not got that data structure yet", which is true of
            // a map or a set and nonsense about an integer. Clojure throws
            // `ClassCastException` and native does now.
            if (!Val.IsHeap(v)) {
                return rt.ThrowStr("ClassCastException", "cannot conj onto " + rt.Describe(v));
            }
            return rt.ThrowStr("UnsupportedOperationException", "conj onto " + rt.Describe(v) + " needs more of the data structures");
        });

        Def("seq", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Seqwalk.Seq(rt, rt.VAt(at)));
        Def("first", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Seqwalk.First(rt, rt.VAt(at)));
        Def("next", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Seqwalk.Next(rt, rt.VAt(at)));
        Def("rest", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Seqwalk.Rest(rt, rt.VAt(at)));
        Def("cons", (rt, at, n) => Seqs.Cons(rt, rt.VAt(at), rt.VAt(at + 1)));

        // Transients. A transient is a MUTABLE handle on a persistent value,
        // and the whole contract is that the persistent one it came from is
        // untouched -- so `persistent!` invalidates the handle rather than
        // leaving two owners of the same nodes.
        // GENERATED, from `kin/transients.kin`. The arms are the same set the
        // hand-written body had; what moved is that all three runtimes now
        // choose between them in one place.
        Def("transient", (rt, at, n) => Transients.ToTransient(rt, rt.VAt(at)));
        Def("persistent!", (rt, at, n) => Transients.ToPersistent(rt, rt.VAt(at)));
        // GENERATED, from `kin/transients.kin`. Fifty lines of hand-written
        // arms duplicating `TransientConj`, and the duplicate had drifted:
        // `(into {} [7])` answered `{nil nil}` because nothing checked that
        // what arrived was an entry.
        //
        // Not deletable until the generated function carried the aliveness
        // check this copy had -- for a while the duplicate was the only
        // correct copy, native answering `#<unprintable>` for `conj!` on a
        // spent handle. Both guards live in `transients.kin` now.
        Def("conj!", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Transients.TransientConj(rt, rt.VAt(at), rt.VAt(at + 1)));
        // GENERATED, from `kin/transients.kin`. A hand-written duplicate of
        // `TransientAssoc` that had drifted both ways -- it carried an
        // aliveness check the generated one lacked, and neither had the upper
        // bound check that `(assoc! (transient [1 2]) 5 :d)` needs.
        Def("assoc!", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Transients.TransientAssoc(rt, rt.VAt(at), rt.VAt(at + 1), rt.VAt(at + 2)));
        // GENERATED, from `kin/transients.kin`; the hand-written copy named
        // `disj!` in a refusal that `dissoc!` reaches too.
        Def("dissoc!", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Transients.TransientDissoc(rt, rt.VAt(at), rt.VAt(at + 1)));

        // Maps.
        // GENERATED, from `kin/collread.kin`. The hand-written body had no
        // arm for a STRING, for BYTES, or for a ROPE OF BYTES, so `(get "abc"
        // 1)` threw here and answered on wasm. And it tested the key with
        // `IsFixnum`, which is false of a BIGINT -- so a key too large for a
        // fixnum was a miss rather than an out-of-range index.
        Def("get", (rt, at, n) => Collread.CollGet(rt, rt.VAt(at), rt.VAt(at + 1),
                                                   n > 2 ? rt.VAt(at + 2) : Val.Nil));
        // GENERATED, from `kin/collwrite.kin`. The hand-written body returned
        // after ONE pair for a table or a ref, so `(assoc t 0 r 1 r2)` dropped
        // the second -- the loop only wrapped the map arm. One step, folded.
        Def("assoc", (rt, at, n) => {
            int bas = rt.Mark();
            int ai = rt.Push(rt.VAt(at));
            for (int i = 1; i + 1 < n; i += 2) {
                long nm = Collwrite.CollAssocGen(rt, rt.R(ai), rt.VAt(at + i), rt.VAt(at + i + 1));
                rt.SetR(ai, nm);
            }
            long outv = rt.R(ai);
            rt.PopTo(bas);
            return outv;
        });
        Def("dissoc", (rt, at, n) => {
            long acc = rt.VAt(at);
            if (Val.IsNil(acc)) return Val.Nil;
            // A NON-MAP IS A TYPE ERROR, not a fall-through. Three runtimes
            // each answered something different for `(dissoc [1 2] 0)`.
            if (!Mapcore.IsMap(rt, acc)) {
                return rt.ThrowStr("ClassCastException", "cannot dissoc from " + rt.Describe(acc));
            }
            int bas = rt.Mark();
            int ai = rt.Push(acc);
            for (int i = 1; i < n; i++) {
                long nm = Mapwrite.MapDissoc(rt, rt.R(ai), rt.VAt(at + i));
                rt.SetR(ai, nm);
            }
            long o = rt.R(ai);
            rt.PopTo(bas);
            return o;
        });
        // The hand-written body REFUSED anything it did not name, where wasm
        // answered `Get` against NotFound -- so `(contains? "abc" 1)` threw
        // here and was true there. Clojure agrees with wasm on the string.
        Def("contains?", (rt, at, n) =>
            Val.Bool(Collread.CollContains(rt, rt.VAt(at), rt.VAt(at + 1))));
        Def("hash", (rt, at, n) => Val.Fixnum(global::_3sln.Flint.Kgen.Rt.Valhash.HashValue(rt, rt.VAt(at))));

        // The math builtins. `fmath.rs` implements these itself because wasm
        // has no libm; on a host they go to the platform, which is where the
        // IEEE results come from in the first place.
        Def("flint/sqrt", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Sqrt(x)));
        Def("flint/cbrt", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Cbrt(x)));
        Def("flint/exp", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Exp(x)));
        Def("flint/expm1", (rt, at, n) => MathOne(rt, rt.VAt(at), x => (System.Math.Exp(x) - 1.0)));
        Def("flint/log", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Log(x)));
        Def("flint/log10", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Log10(x)));
        Def("flint/log1p", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Log(1.0 + x)));
        Def("flint/sin", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Sin(x)));
        Def("flint/cos", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Cos(x)));
        Def("flint/tan", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Tan(x)));
        Def("flint/asin", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Asin(x)));
        Def("flint/acos", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Acos(x)));
        Def("flint/atan", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Atan(x)));
        Def("flint/sinh", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Sinh(x)));
        Def("flint/cosh", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Cosh(x)));
        Def("flint/tanh", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Tanh(x)));
        Def("flint/floor", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Floor(x)));
        Def("flint/ceil", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Ceiling(x)));
        Def("flint/rint", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Round(x)));
        // NOT `System.Math.Sign`, which answers an INT and so cannot return
        // -0.0 for -0.0 -- it collapses both zeros to 0 -- and which THROWS on
        // NaN where every other runtime here answers NaN. `Math.signum` on the
        // JVM returns the argument itself for a zero and for a NaN, which is
        // what this spells out.
        Def("flint/signum", (rt, at, n) => MathOne(rt, rt.VAt(at),
            x => double.IsNaN(x) || x == 0.0 ? x : (x > 0.0 ? 1.0 : -1.0)));
        Def("flint/fabs", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Abs(x)));
        Def("flint/pow", (rt, at, n) => MathTwo(rt, rt.VAt(at), rt.VAt(at + 1), (x, y) => System.Math.Pow(x, y)));
        Def("flint/atan2", (rt, at, n) => MathTwo(rt, rt.VAt(at), rt.VAt(at + 1), (x, y) => System.Math.Atan2(x, y)));
        // NOT `Sqrt(x*x + y*y)`, which OVERFLOWS before it takes the root:
        // `hypot(1e300, 1e300)` is 1.4142135623730952E300 and that spelling
        // answers Infinity, while two subnormal legs underflow to 0.0. Avoiding
        // the intermediate is the entire reason the function exists.
        Def("flint/hypot", (rt, at, n) => MathTwo(rt, rt.VAt(at), rt.VAt(at + 1), (x, y) => double.Hypot(x, y)));
        Def("flint/trunc", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Truncate(x)));
        Def("flint/copy-sign", (rt, at, n) =>
            MathTwo(rt, rt.VAt(at), rt.VAt(at + 1), (x, y) => System.Math.CopySign(System.Math.Abs(x), y)));
        Def("flint/to-long", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Num.IsInt(rt, v)) return v;
            // `ClassCastException`, as Clojure throws for `(long "x")` and as
            // native throws -- this port said `IllegalArgumentException`, and
            // the class is the part a `catch` selects on.
            if (!Val.IsDouble(v)) return rt.ThrowStr("ClassCastException", "not a number: " + rt.Describe(v));
            double d = System.Math.Truncate(Val.AsDouble(v));
            if (!double.IsFinite(d) || d < -9.223372036854776e18 || d > 9.223372036854776e18)
                return rt.ThrowStr("IllegalArgumentException", "value out of long range");
            return Num.Integer(rt, (long) d);
        });

        // --- atoms, volatiles and delays --------------------------------------
        //
        // One slot each, and `deref` reads it. There is no lock: a sandbox's
        // threads are GREEN, so only one runs at a time and a compare-and-set
        // cannot be interrupted between the compare and the set. That is a
        // property of the scheduler and not of this code, and it is why it can
        // be written this plainly.
        Def("atom", (rt, at, n) => NewCell(rt, Obj.TyAtom, rt.VAt(at)));
        Def("flint/volatile", (rt, at, n) =>
            global::_3sln.Flint.Kgen.Rt.Mapmake.NewVolatile(rt, rt.VAt(at)));
        // GENERATED, from `kin/atoms.kin` -- see the Java copy for the delay
        // bug both ports carried.
        Def("deref", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Atoms.Deref(rt, rt.VAt(at)));
        Def("reset!", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Atoms.ResetAtom(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("compare-and-set!", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Atoms.CompareAndSetAtom(rt, rt.VAt(at), rt.VAt(at + 1), rt.VAt(at + 2)));

        // --- dynamic bindings -------------------------------------------------
        //
        // A MAP in a singleton slot, not a per-var stack. `binding` swaps the
        // whole map and restores it, so a park in the middle carries the
        // bindings with the thread rather than leaving them behind.
        Def("flint/dyn-get", (rt, at, n) => {
            long binds = rt.roots.shared.Singletons[Rt.SingBindings];
            if (Val.IsNil(binds)) return rt.VAt(at + 1);
            return Mapread.MapGet(rt, binds, rt.VAt(at), rt.VAt(at + 1));
        });
        Def("flint/dyn-bindings", (rt, at, n) => {
            long b = rt.roots.shared.Singletons[Rt.SingBindings];
            return Val.IsNil(b) ? Maps.Empty(rt) : b;
        });
        Def("flint/dyn-set-bindings", (rt, at, n) => {
            rt.roots.shared.Singletons[Rt.SingBindings] = rt.VAt(at);
            return rt.VAt(at);
        });

        // --- vectors as stacks ------------------------------------------------
        Def("peek", (rt, at, n) => Collgen.PeekOf(rt, rt.VAt(at)));
        // The hand-written `pop` had no EMPTY LIST arm: `(pop ())` fell to
        // `Rest` and answered `()` where wasm refused it.
        Def("pop", (rt, at, n) => Collgen.PopOf(rt, rt.VAt(at)));
        Def("pop!", (rt, at, n) => Transients.TransientPop(rt, rt.VAt(at)));
        Def("empty", (rt, at, n) => Collgen.EmptyOf(rt, rt.VAt(at)));

        // --- strings ----------------------------------------------------------
        Def("flint/subs", (rt, at, n) => {
            long v0 = rt.VAt(at);
            // A ROPE SLICES BY DESCENT AND SHARES ITS CHUNKS. This called
            // `Str.Text`, which materialises the whole string into a CLR
            // `string` -- the same defect the Rust runtime had in a different
            // shape, on the operation that most wants sharing.
            if (Str.IsRope(rt, v0)) {
                int cps = Str.SCount(rt, v0);
                int st = (int) Val.AsFixnum(rt.VAt(at + 1));
                int en = n > 2 ? (int) Val.AsFixnum(rt.VAt(at + 2)) : cps;
                if (st < 0 || en > cps || st > en)
                    return rt.ThrowStr("IndexOutOfBoundsException",
                        "subs " + st + ".." + en + " of " + cps);
                bool ascii = Str.SAscii(rt, v0);
                // THE SLICE HAS A PRICE, and this port charged nothing for it.
                // Native bounds the non-ASCII descent before it starts, and a
                // program that sliced strings therefore billed less here than
                // there -- 56 steps over the gas meter's two arms, and more
                // through `re-seq`, which calls `subs` once per match and once
                // per group. Same formula, same path, same refusal.
                if (!ascii && !rt.ChargeChecked((en - st) / 8 + 1, "subs")) return Val.Nil;
                int nb = Str.SBytes(rt, v0);
                int from = ascii ? st : Str.RopeByteOfCp(rt, v0, st);
                int to = (en == cps) ? nb : (ascii ? en : Str.RopeByteOfCp(rt, v0, en));
                // ABSENT IS `nb` NOW, not -1: a real offset is 0..nb-1, so
                // the byte length is free to mean "no such code point".
                if (from >= nb)
                    return rt.ThrowStr("IndexOutOfBoundsException",
                        "subs " + st + ".." + en + " of " + cps);
                return Str.RopeSlice(rt, v0, from, to);
            }
            string s = Str.Text(rt, v0);
            var si = new System.Globalization.StringInfo(s);
            int len = CodePointCount(s);
            int start = (int) Val.AsFixnum(rt.VAt(at + 1));
            int end = n > 2 ? (int) Val.AsFixnum(rt.VAt(at + 2)) : len;
            // THE SLICE, NOT THE SOURCE, and before the bounds check, which is
            // where native charges it. Charging the whole string per call makes
            // the counter quadratic for splitting even when the copying is not.
            int nbf = Str.SBytes(rt, v0);
            int took = n > 2 ? System.Math.Max(end - start, 0)
                             : System.Math.Max(nbf - System.Math.Max(start, 0), 0);
            rt.ChargeBytes(System.Math.Min(took, nbf));
            if (start < 0 || end > len || start > end)
                return rt.ThrowStr("IndexOutOfBoundsException", "subs " + start + ".." + end + " of " + len);
            // By CODE POINT, not by char: a .NET `string` is UTF-16, so slicing
            // it by index would cut a surrogate pair in half.
            int bs = OffsetByCodePoints(s, start), be = OffsetByCodePoints(s, end);
            return Str.Of(rt, s.Substring(bs, be - bs));
        });
        Def("flint/str->num", (rt, at, n) =>
            global::_3sln.Flint.Kgen.Rt.Strnum.StrToNum(rt, rt.VAt(at)));
        // ONE CALL -- see the note in the JVM port. What was here also
        // allocated `h.Substring(0, i)` on every successful search purely to
        // count code points, which is gone with the rest of it.
        Def("flint/str-index-of", (rt, at, n) => {
            long from = n > 2 ? Val.AsFixnum(rt.VAt(at + 2)) : 0;
            int skip = from <= 0 ? 0 : (int) System.Math.Min(from, int.MaxValue);
            return global::_3sln.Flint.Kgen.Rt.Ropefind.SIndexOf(rt, rt.VAt(at), rt.VAt(at + 1), skip);
        });
        Def("flint/str-join", (rt, at, n) => {
            var sb = new System.Text.StringBuilder();
            int bas = rt.Mark();
            int s = rt.Push(global::_3sln.Flint.Kgen.Rt.Seqwalk.Seq(rt, rt.VAt(at)));
            long ticks = 0;
            while (!Val.IsNil(rt.R(s))) {
                // CHARGED AND CHECKED INSIDE THE LOOP: the length is not known
                // until the walk ends (`DECISIONS.md#resource-limits`).
                if (!rt.ChargeTick(ticks++, 1, "str-join")) { rt.PopTo(bas); return Val.Nil; }
                sb.Append(Str.Text(rt, global::_3sln.Flint.Kgen.Rt.Seqwalk.First(rt, rt.R(s))));
                rt.SetR(s, global::_3sln.Flint.Kgen.Rt.Seqwalk.Next(rt, rt.R(s)));
            }
            rt.PopTo(bas);
            rt.ChargeBytes(sb.Length);
            return Str.Of(rt, sb.ToString());
        });
        // GENERATED, from `kin/casemap.kin` -- see the Java copy.
        Def("flint/upper-case", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Casechange.ChangeCase(rt, rt.VAt(at), true));
        // GENERATED, from `kin/casemap.kin` -- see the Java copy.
        Def("flint/lower-case", (rt, at, n) => global::_3sln.Flint.Kgen.Rt.Casechange.ChangeCase(rt, rt.VAt(at), false));
        Def("flint/code-point-at", (rt, at, n) => {
            int i = (int) Val.AsFixnum(rt.VAt(at + 1));
            int c = Str.CodePointAt(rt, rt.VAt(at), i);
            if (c < 0)
                return rt.ThrowStr("IndexOutOfBoundsException", "index " + i + " out of range");
            return Val.Fixnum(c);
        });
        Def("flint/from-code-point", (rt, at, n) => {
            long c = Val.AsFixnum(rt.VAt(at));
            if (c < 0 || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF))
                return rt.ThrowStr("IllegalArgumentException", "not a code point: " + c);
            return Str.Of(rt, char.ConvertFromUtf32((int) c));
        });
        Def("flint/str-bytes", (rt, at, n) => {
            // REFUSED BEFORE THE VECTOR IS BUILT, not billed after it. This
            // builds one element per byte, and billing afterwards is how the
            // worst of these ran 11 937 109 steps past an exhausted budget on
            // native before it was bounded. This port was not bounded at all.
            byte[] b = Str.Bytes(rt, rt.VAt(at));
            if (!rt.ChargeChecked(b.Length, "str-bytes")) return Val.Nil;
            int bas = rt.Mark();
            int vi = rt.Push(Vec.Empty(rt));
            foreach (byte x in b) rt.SetR(vi, Vec.Conj(rt, rt.R(vi), Val.Fixnum(x & 0xFF)));
            long outv = rt.R(vi);
            rt.PopTo(bas);
            return outv;
        });
        Def("flint/bytes->str", (rt, at, n) => {
            long v = rt.VAt(at);
            if (!rt.IsHeapTy(v, Obj.TyVec))
                return rt.ThrowStr("ClassCastException", "bytes->str wants a vector of bytes");
            int c = Vec.Count(rt, v);
            byte[] b = new byte[c];
            for (int i = 0; i < c; i++) b[i] = (byte) Val.AsFixnum(Vec.Nth(rt, v, i, Val.NotFound));
            return Str.Of(rt, System.Text.Encoding.UTF8.GetString(b));
        });
        Def("flint/bits->double", (rt, at, n) => {
            long v = rt.VAt(at);
            if (!Num.IsInt(rt, v)) return rt.ThrowStr("ClassCastException", "bits->double wants an integer");
            return Val.OfDouble(System.BitConverter.Int64BitsToDouble(Num.AsI64(rt, v).Value));
        });

        // --- the type predicates ----------------------------------------------
        //
        // Every one goes through `TypeP`, whose numbers are the CONTRACT
        // between the compiler and every runtime. Answering them here
        // independently would be a second table to keep in step with the first.
        Def("string?", (rt, at, n) => Val.Bool(rt.TypeP(4, rt.VAt(at))));
        Def("keyword?", (rt, at, n) => Val.Bool(rt.TypeP(5, rt.VAt(at))));
        Def("symbol?", (rt, at, n) => Val.Bool(rt.TypeP(6, rt.VAt(at))));
        Def("boolean?", (rt, at, n) => Val.Bool(rt.TypeP(7, rt.VAt(at))));
        Def("vector?", (rt, at, n) => Val.Bool(rt.TypeP(8, rt.VAt(at))));
        Def("map?", (rt, at, n) => Val.Bool(rt.TypeP(9, rt.VAt(at))));
        Def("set?", (rt, at, n) => Val.Bool(rt.TypeP(10, rt.VAt(at))));
        Def("seq?", (rt, at, n) => Val.Bool(rt.TypeP(11, rt.VAt(at))));
        Def("fn?", (rt, at, n) => Val.Bool(rt.TypeP(12, rt.VAt(at))));
        Def("sequential?", (rt, at, n) => Val.Bool(rt.IsSequential(rt.VAt(at))));
        Def("bytes?", (rt, at, n) => Val.Bool(rt.IsHeapTy(rt.VAt(at), Obj.TyBytes)
                                              || rt.IsHeapTy(rt.VAt(at), Obj.TyBrope)));
        Def("flint/map-entry?", (rt, at, n) => Val.Bool(rt.IsHeapTy(rt.VAt(at), Obj.TyMapentry)));
        Def("flint/volatile?", (rt, at, n) => Val.Bool(rt.IsHeapTy(rt.VAt(at), Obj.TyVolatile)));
        Def("flint/delay?", (rt, at, n) => Val.Bool(rt.IsHeapTy(rt.VAt(at), Obj.TyDelay)));

        Def("compare", (rt, at, n) =>
            Val.Fixnum(global::_3sln.Flint.Kgen.Rt.Valcmp.ValCmp(rt, rt.VAt(at), rt.VAt(at + 1))));

        // --- metadata ---------------------------------------------------------
        Def("with-meta", (rt, at, n) => {
            long v = rt.VAt(at);
            int idx = rt.MetaSlot(v);
            if (idx < 0) return v;   // nothing carries metadata: hand it back
            int bas = rt.Mark();
            int vi = rt.Push(v), mi = rt.Push(rt.VAt(at + 1));
            int t = Obj.Ty(rt.gc.sp, Val.AsHeap(rt.R(vi)));
            int ln = Obj.Len(rt.gc.sp, Val.AsHeap(rt.R(vi)));
            long a = rt.Alloc(t, ln);
            if (a == 0) { rt.PopTo(bas); return Val.Nil; }
            for (int i = 0; i < ln; i++) rt.SetSlot(a, i, rt.Slot(rt.R(vi), i));
            rt.SetSlot(a, idx, rt.R(mi));
            rt.PopTo(bas);
            return Val.Heap(a);
        });

        // --- delays -----------------------------------------------------------
        Def("flint/delay", (rt, at, n) => NewCell(rt, Obj.TyDelay, rt.VAt(at)));
        Def("flint/realized?", (rt, at, n) => {
            long v = rt.VAt(at);
            if (rt.IsHeapTy(v, Obj.TyDelay)) return Val.Bool(Val.IsNil(rt.Slot(v, 0)));
            if (rt.IsHeapTy(v, Obj.TyLazyseq)) return Val.Bool(Val.IsNil(rt.Slot(v, Seqs.LS_THUNK)));
            return Val.True;
        });

        // --- opaque values (`DECISIONS.md#opaque-values`) -----------------------------
        //
        // Guest code can mint one only with id 0 and no builtin reads an id
        // back, so an id is a thing the HOST wrote and only the host can read.
        Def("flint/opaque", (rt, at, n) =>
            rt.NewOpaque(n > 0 ? rt.VAt(at) : Val.Nil, 0));   // host id 0: minted by the guest
        Def("flint/ex-kind", (rt, at, n) => rt.ExKind(rt.VAt(at)));
        Def("flint/ex-matches?", (rt, at, n) => rt.ExMatches(rt.VAt(at), rt.VAt(at + 1)));

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
        /// lazy seq and `next` forces the tail -- so anything already gathered
        /// would go stale at the first collection. That is
        /// `DECISIONS.md#a-vec-of-values-is-not-a-root`.
        // GENERATED, from `kin/mapmake.kin`. This port already said it line
        // for line, which is why generating it found nothing -- the value is
        // that the next change to it lands in one place.
        Def("flint/array-map", (rt, at, n) =>
            global::_3sln.Flint.Kgen.Rt.Mapmake.OrderedMap(rt, rt.VAt(at)));
        // `AsFixnum` IS NOT `I64Of`. It is the low bits of the VALUE WORD,
        // sign-extended, so for a BIGINT it sign-extends the heap address and
        // does arithmetic on a pointer. `unchecked-add` of `long.MaxValue` and
        // 1 answered a small positive number rather than wrapping -- not an
        // overflow, not an error, a different number with nothing to say it
        // was wrong. Every operand too big for a fixnum went through it, which
        // is the range `unchecked-*` exists to be used in.
        Def("flint/unchecked-add", (rt, at, n) => UncheckedOp(rt, at, 0));
        Def("flint/unchecked-sub", (rt, at, n) => UncheckedOp(rt, at, 1));
        Def("flint/unchecked-mul", (rt, at, n) => UncheckedOp(rt, at, 2));

        // --- byte strings (`DECISIONS.md#no-runtime-linking`) ------------------------------
        Def("flint/b-count", (rt, at, n) => Val.Fixnum(Bytes.Count(rt, rt.VAt(at))));
        Def("flint/b-at", (rt, at, n) => {
            // REFUSED past either end -- see the Java copy.
            // Negative is the caller's to reject -- see the Java copy.
            long idx = Val.AsFixnum(rt.VAt(at + 1));
            long b = idx < 0 ? Val.NotFound
                             : Bytes.At(rt, rt.VAt(at), (int) idx, Val.NotFound);
            return b == Val.NotFound
                ? rt.ThrowStr("IndexOutOfBoundsException", "byte index out of range")
                : b;
        });
        Def("flint/b-concat", (rt, at, n) => Bytes.Concat(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("flint/b-slice", (rt, at, n) => {
            // Refused, not clamped -- see the Java copy.
            long f = Val.AsFixnum(rt.VAt(at + 1));
            long t = n > 2 ? Val.AsFixnum(rt.VAt(at + 2)) : Bytes.Count(rt, rt.VAt(at));
            if (!Val.IsFixnum(rt.VAt(at + 1)) || (n > 2 && !Val.IsFixnum(rt.VAt(at + 2)))
                || f < 0 || t < 0)
                return rt.ThrowStr("IllegalArgumentException", "b-slice wants two integers");
            return Bytes.Slice(rt, rt.VAt(at), (int) f, (int) t);
        });
        Def("flint/b-depth", (rt, at, n) => Val.Fixnum(Bytes.Depth(rt, rt.VAt(at))));
        Def("flint/str->b", (rt, at, n) => Bytes.Of(rt, Str.Bytes(rt, rt.VAt(at))));
        Def("flint/b->str", (rt, at, n) => {
            // CHARGED UP FRONT, as native now is: this walks the byte tree and
            // decodes UTF-8, both O(n), and charged for neither.
            long v = rt.VAt(at);
            if (!rt.ChargeChecked((Bytes.Count(rt, v) / 8) + 1, "b->str")) return Val.Nil;
            // STRICT, and this is the whole point of the line. The default
            // `Encoding.UTF8` is LENIENT: it replaces every byte it cannot
            // decode with U+FFFD and returns a string. Native refuses those
            // bytes, so the same byte string became an error on one runtime
            // and a string full of replacement characters on the other two --
            // silent data loss, and not round-trippable back through `str->b`.
            //
            // A byte string is arbitrary bytes by definition, so this is the
            // conversion most likely to be handed something that is not text,
            // and the answer has to mean one thing on all four.
            try {
                return Str.Of(rt, new System.Text.UTF8Encoding(false, true)
                    .GetString(Bytes.ToArray(rt, v)));
            } catch (System.ArgumentException) {
                return rt.ThrowStr("IllegalArgumentException", "those bytes are not UTF-8");
            }
        });
        Def("flint/vec->b", (rt, at, n) => {
            long v = rt.VAt(at);
            int c = Vec.Count(rt, v);
            byte[] b = new byte[c];
            for (int i = 0; i < c; i++) b[i] = (byte) Val.AsFixnum(Vec.Nth(rt, v, i, Val.NotFound));
            return Bytes.Of(rt, b);
        });
        Def("flint/b->vec", (rt, at, n) => {
            byte[] b = Bytes.ToArray(rt, rt.VAt(at));
            int bas = rt.Mark();
            int vi = rt.Push(Vec.Empty(rt));
            foreach (byte x in b) rt.SetR(vi, Vec.Conj(rt, rt.R(vi), Val.Fixnum(x & 0xFF)));
            long outv = rt.R(vi);
            rt.PopTo(bas);
            return outv;
        });
        Def("flint/b-transient", (rt, at, n) => Bytes.TransientOf(rt, rt.VAt(at)));
        Def("flint/b-conj!", (rt, at, n) =>
            Bytes.Conj(rt, rt.VAt(at), (int) Val.AsFixnum(rt.VAt(at + 1))));
        Def("flint/b-append!", (rt, at, n) => Bytes.AppendBytes(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("flint/b-tcount", (rt, at, n) => Val.Fixnum(Bytes.Tcount(rt, rt.VAt(at))));
        Def("flint/b-persistent!", (rt, at, n) => Bytes.Persistent(rt, rt.VAt(at)));

        // --- regex ------------------------------------------------------------
        //
        // The PATTERN is compiled to a program by flint's own library, in
        // Clojure; this runs it. That split is why the engine is the same on
        // every runtime -- there is no host regex anywhere in it, so there is
        // no way for two hosts to disagree about what a pattern means.
        Def("flint/re-compile", (rt, at, n) => Pike.Compile(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("flint/re-run", (rt, at, n) => {
            long from = n > 2 ? Val.AsFixnum(rt.VAt(at + 2)) : 0;
            // 0 searches from `from`; 3 matches exactly at it. Both are entry
            // points into ONE program, so there is no second program to keep in
            // step with the first.
            int entry = n > 3 ? (int) Val.AsFixnum(rt.VAt(at + 3)) : 0;
            // The fifth argument asks for a match reaching the END, which is
            // `re-matches` and cannot be had by checking the span afterwards.
            bool full = n > 4 && Val.AsFixnum(rt.VAt(at + 4)) != 0;
            return Pike.Run(rt, rt.VAt(at), rt.VAt(at + 1), from, entry, full);
        });
        Def("flint/re-find-all", (rt, at, n) =>
            Pike.FindAll(rt, rt.VAt(at), rt.VAt(at + 1), n > 2 ? Val.AsFixnum(rt.VAt(at + 2)) : 0));

        // --- green threads and ports ------------------------------------------
        //
        // Every one of these calls `EnsureSched` first. The scheduler is built
        // on first use rather than at startup, so a program that never mentions
        // `spawn` never has one -- and the interpreter runs a loop with no slice
        // counter in it at all.
        Def("flint/spawn", (rt, at, n) => Conc.Spawn(rt, rt.VAt(at)));
        Def("flint/yield", (rt, at, n) => {
            Conc.EnsureSched(rt);
            return Conc.Park(rt, Conc.PARK_YIELD);
        });
        Def("flint/self", (rt, at, n) => { Conc.EnsureSched(rt); return Conc.CurrentThread(rt); });
        Def("flint/thread?", (rt, at, n) => Val.Bool(Conc.IsThread(rt, rt.VAt(at))));
        Def("flint/thread-id", (rt, at, n) => rt.Slot(rt.VAt(at), Conc.TH_ID));
        Def("flint/thread-result", (rt, at, n) => rt.Slot(rt.VAt(at), Conc.TH_RESULT));
        Def("flint/thread-state", (rt, at, n) => {
            long t = rt.VAt(at);
            if (!Conc.IsThread(rt, t))
                return rt.ThrowStr("ClassCastException", "thread-state wants a thread, got " + rt.Describe(t));
            switch ((int) Val.AsFixnum(rt.Slot(t, Conc.TH_STATUS))) {
                case Conc.ST_NEW: return Str.Keyword(rt, null, "new");
                case Conc.ST_RUNNABLE: return Str.Keyword(rt, null, "runnable");
                case Conc.ST_PARKED: return Str.Keyword(rt, null, "parked");
                case Conc.ST_DONE: return Str.Keyword(rt, null, "done");
                default: return Str.Keyword(rt, null, "failed");
            }
        });
        Def("flint/thread-join", (rt, at, n) => {
            Conc.EnsureSched(rt);
            return Conc.Join(rt, rt.VAt(at));
        });
        Def("flint/bindings", (rt, at, n) => {
            long b = rt.roots.shared.Singletons[Rt.SingBindings];
            return Val.IsNil(b) ? Maps.Empty(rt) : b;
        });
        Def("flint/set-bindings", (rt, at, n) => {
            rt.roots.shared.Singletons[Rt.SingBindings] = rt.VAt(at);
            return rt.VAt(at);
        });

        Def("flint/channel", (rt, at, n) => {
            long cap = n > 0 ? rt.VAt(at) : Val.Nil;
            long label = n > 1 ? rt.VAt(at + 1) : Val.Nil;
            long c = Val.IsFixnum(cap) ? Val.AsFixnum(cap) : Conc.DEFAULT_CAP;
            if (c < 1) return rt.ThrowStr("IllegalArgumentException",
                "a channel needs a buffer of at least 1");
            return Conc.Channel(rt, c, label);
        });
        Def("flint/open", (rt, at, n) => {
            long name = rt.VAt(at);
            if (!Str.IsString(rt, name))
                return rt.ThrowStr("ClassCastException", "open wants a name (a string)");
            // EVERY REMAINING ARGUMENT IS FORWARDED, and the runtime takes no
            // view of any of them. A capability is an opaque value like any
            // other and travels as one; nothing here knows the word, which is
            // the point (`DECISIONS.md#opaque-values`).
            int bas = rt.Mark();
            int ni = rt.Push(name);
            int vi = rt.Push(Vec.Empty(rt));
            for (int i = 1; i < n; i++) rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.VAt(at + i)));
            long nm = rt.R(ni), args = rt.R(vi);
            rt.PopTo(bas);
            return Conc.PortOpen(rt, nm, args);
        });
        Def("flint/request", (rt, at, n) => {
            long what = rt.VAt(at);
            if (!Str.IsString(rt, what))
                return rt.ThrowStr("ClassCastException", "request wants a name (a string)");
            // Identical to `open` above, and deliberately so: same forwarding,
            // same no-view-of-the-arguments. What differs is what comes back
            // (`DECISIONS.md#workspace-capabilities` step 7).
            int bas = rt.Mark();
            int ni = rt.Push(what);
            int vi = rt.Push(Vec.Empty(rt));
            for (int i = 1; i < n; i++) rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.VAt(at + i)));
            long nm = rt.R(ni), args = rt.R(vi);
            rt.PopTo(bas);
            return Conc.HostRequest(rt, nm, args);
        });
        Def("flint/port-send", (rt, at, n) => Conc.Send(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("flint/port-receive", (rt, at, n) => Conc.Receive(rt, rt.VAt(at)));
        Def("flint/port-close", (rt, at, n) => Conc.Close(rt, rt.VAt(at)));
        Def("flint/port?", (rt, at, n) => Val.Bool(Conc.IsPort(rt, rt.VAt(at))));
        Def("flint/port-id", (rt, at, n) => {
            long p = rt.VAt(at);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "port-id wants a port");
            return rt.Slot(p, Conc.PT_ID);
        });
        Def("flint/port-label", (rt, at, n) => {
            long p = rt.VAt(at);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "port-label wants a port");
            return rt.Slot(p, Conc.PT_LABEL);
        });
        /// Does this port carry BYTES across a boundary? A bridge does and a
        /// channel does not, and that is the only distinction a guest can see
        /// -- it cannot see the encoding, because the runtime owns it.
        Def("flint/port-bridge?", (rt, at, n) => {
            long p = rt.VAt(at);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "port-bridge? wants a port");
            return Val.Bool(Conc.CrossesAHeap(Val.AsFixnum(rt.Slot(p, Conc.PT_KIND))));
        });
        Def("flint/port-state", (rt, at, n) => {
            long p = rt.VAt(at);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "port-state wants a port");
            // THE QUERY IS THE TRUTH (`DECISIONS.md#host-abi`), so it resolves the
            // peer rather than reporting a state that reaping has not caught up
            // with yet.
            switch ((int) Conc.PortStateNow(rt, p)) {
                case Conc.P_PENDING: return Str.Keyword(rt, null, "pending");
                case Conc.P_OPEN: return Str.Keyword(rt, null, "open");
                case Conc.P_CLOSED: return Str.Keyword(rt, null, "closed");
                case Conc.P_HALF: return Str.Keyword(rt, null, "half-closed");
                case Conc.P_ORPHANED: return Str.Keyword(rt, null, "orphaned");
                default: return Str.Keyword(rt, null, "refused");
            }
        });

        /// The raw IEEE bits of a double, as an integer. What lets flint code
        /// print a double bit-exactly rather than through a formatter.
        Def("flint/double-bits", (rt, at, n) => {
            long v = rt.VAt(at);
            if (!Val.IsDouble(v)) return rt.ThrowStr("ClassCastException", "not a double: " + rt.Describe(v));
            return Num.Integer(rt, System.BitConverter.DoubleToInt64Bits(Val.AsDouble(v)));
        });

        // Exceptions. `ex-info` is `[msg, data, cause]`, and `throw` is an
        // OPCODE rather than a builtin -- these are what a handler reads.
        Def("ex-info", (rt, at, n) => {
            int bas = rt.Mark();
            int mi = rt.Push(rt.VAt(at));
            int di = rt.Push(n > 1 ? rt.VAt(at + 1) : Val.Nil);
            int ci = rt.Push(n > 2 ? rt.VAt(at + 2) : Val.Nil);
            long k = Str.Of(rt, "ExceptionInfo");
            long outv = Rt.ExInfo(rt, k, rt.R(mi), rt.R(di), rt.R(ci));
            rt.PopTo(bas);
            return outv;
        });
        Def("ex-message", (rt, at, n) => rt.ExMessage(rt.VAt(at)));
        Def("ex-data", (rt, at, n) => rt.ExData(rt.VAt(at)));

        // `apply`: spread the trailing seq onto the argument list.
        //
        // The spread arguments go on the SHADOW stack, not into a host array:
        // `first` and `next` allocate on a lazy seq, so a host array would hold
        // addresses across a collection that moves them.
        Def("flint/apply", (rt, at, n) => {
            int bas = rt.Mark();
            int fi = rt.Push(rt.VAt(at));
            int si = rt.Push(global::_3sln.Flint.Kgen.Rt.Seqwalk.Seq(rt, rt.VAt(at + 1)));
            int count = 0;
            while (!Val.IsNil(rt.R(si))) {
                rt.Push(global::_3sln.Flint.Kgen.Rt.Seqwalk.First(rt, rt.R(si)));
                count++;
                rt.SetR(si, global::_3sln.Flint.Kgen.Rt.Seqwalk.Next(rt, rt.R(si)));
            }
            long[] argv = new long[count];
            for (int i = 0; i < count; i++) argv[i] = rt.R(si + 1 + i);
            long f = rt.R(fi);
            long outv = rt.Invoke(f, argv);
            rt.PopTo(bas);
            return outv;
        });

        Def("flint/keyword2", (rt, at, n) => {
            long ns = n == 1 ? Val.Nil : rt.VAt(at);
            long nm = n == 1 ? rt.VAt(at) : rt.VAt(at + 1);
            return Str.Keyword(rt, Val.IsNil(ns) ? null : NameOf(rt, ns), NameOf(rt, nm));
        });
        Def("flint/symbol2", (rt, at, n) => {
            long ns = n == 1 ? Val.Nil : rt.VAt(at);
            long nm = n == 1 ? rt.VAt(at) : rt.VAt(at + 1);
            return Str.Symbol(rt, Val.IsNil(ns) ? null : NameOf(rt, ns), NameOf(rt, nm));
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
        // reporting. `ByName` answers null for a native it does not have, so
        // the symptom was not a refusal naming the missing builtin -- it was a
        // null dereference the first time an annotated binding ran.
        Def("flint/check-tag", (rt, at, n) => {
            long v = rt.VAt(at);
            long? code = Num.AsI64(rt, rt.VAt(at + 1));
            // An unknown code is a compiler that has drifted from this table.
            // Failing loudly beats passing everything: a silent `true` would
            // make every annotation vacuous and every specialisation built on
            // one unsound. The range is checked HERE because `TypeP`'s default
            // arm is permissive -- it answers the `sequential` test -- and
            // leaning on it would turn a drifted code into a quiet yes.
            if (code == null || code < 1 || code > 14) {
                return rt.ThrowStr("IllegalArgumentException",
                                   "check-tag: unknown type code");
            }
            if (rt.TypeP((int) code, v)) return v;
            string name = TagNames[(int) code - 1];
            long site = n > 2 ? rt.VAt(at + 2) : Val.Nil;
            string msg = Str.IsString(rt, site)
                ? Str.Text(rt, site) + " is declared ^" + name + ", and it is not"
                : "a value declared ^" + name + " is not one";
            return rt.ThrowStr("ClassCastException", msg);
        });

        // The GC's counters, as a map. Guest-reachable through
        // `flint.rt/gc-stats`, which `test/opaque.cljc` and `test/pause.cljc`
        // use to assert that a shape does not allocate.
        Def("flint/gc-stats", (rt, at, n) => {
            long[] vals = { rt.gc.minors, rt.gc.majors, rt.gc.bytesAllocated,
                            rt.gc.bytesCopied, rt.gc.bytesPromoted,
                            rt.gc.YoungUsed(), rt.gc.oldLive, rt.gc.oldCapacity };
            int b = rt.Mark();
            int mi = rt.Push(Maps.Empty(rt));
            for (int i = 0; i < GcStatKeys.Length; i++) {
                // The keyword stays ROOTED across `Integer` and `Assoc`, both
                // of which allocate. A value in a host local does not survive
                // an allocation (`DECISIONS.md#a-vec-of-values-is-not-a-root`).
                int ki = rt.Push(Str.Keyword(rt, null, GcStatKeys[i]));
                long vv = Num.Integer(rt, vals[i]);
                rt.SetR(mi, Mapwrite.MapAssoc(rt, rt.R(mi), rt.R(ki), vv));
                rt.PopTo(ki);
            }
            long outv = rt.R(mi);
            rt.PopTo(b);
            return outv;
        });

        // Lazy sequences and ranges.
        Def("flint/lazy-seq", (rt, at, n) => _3sln.Flint.Kgen.Rt.Seqs.LazySeq(rt, rt.VAt(at)));
        Def("flint/range3", (rt, at, n) =>
            _3sln.Flint.Kgen.Rt.Seqs.Range(rt, rt.VAt(at), rt.VAt(at + 1), rt.VAt(at + 2)));

        // Sets.
        Def("disj", (rt, at, n) => {
            long acc = rt.VAt(at);
            if (Val.IsNil(acc)) return Val.Nil;
            if (!Sets.IsSet(rt, acc)) {
                return rt.ThrowStr("ClassCastException", "cannot disj from " + rt.Describe(acc));
            }
            int bas = rt.Mark();
            int ai = rt.Push(acc);
            for (int i = 1; i < n; i++) rt.SetR(ai, Sets.Disj(rt, rt.R(ai), rt.VAt(at + i)));
            long outv = rt.R(ai);
            rt.PopTo(bas);
            return outv;
        });

        Def("=", (rt, at, n) => {
            for (int i = 1; i < n; i++) if (!Eq(rt, rt.VAt(at), rt.VAt(at + i))) return Val.False;
            return Val.True;
        });
    }

    /// A one-slot cell: an atom or a volatile. Same shape, different type tag
    /// -- the difference is what the LIBRARY allows, not what the runtime does.
    static long NewCell(Rt rt, int ty, long v) {
        int bas = rt.Mark();
        int vi = rt.Push(v);
        long a = rt.Alloc(ty, 2);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, 0, rt.R(vi));
        rt.SetSlot(a, 1, Val.Nil);
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// .NET has no `codePointCount`/`offsetByCodePoints`, so they are written
    /// out. Both count SURROGATE PAIRS as one, which is the whole point: flint
    /// indexes strings by code point and a UTF-16 index would cut one in half.
    static int CodePointCount(string s) {
        int n = 0;
        for (int i = 0; i < s.Length; i++) { if (!char.IsLowSurrogate(s[i])) n++; }
        return n;
    }

    static int OffsetByCodePoints(string s, int cp) {
        int i = 0, seen = 0;
        while (i < s.Length && seen < cp) {
            i += char.IsHighSurrogate(s[i]) && i + 1 < s.Length ? 2 : 1;
            seen++;
        }
        return i;
    }

    /// Every unary math builtin has the same shape: refuse a non-number by
    /// NAME, else compute in double. Written once so a new one cannot get the
    /// refusal wrong.
    static long MathOne(Rt rt, long v, System.Func<double, double> f) {
        if (!Num.IsNumber(rt, v))
            return rt.ThrowStr("IllegalArgumentException", "not a number: " + rt.Describe(v));
        return Val.OfDouble(f(Num.F64(rt, v)));
    }

    static long MathTwo(Rt rt, long a, long b, System.Func<double, double, double> f) {
        if (!Num.IsNumber(rt, a) || !Num.IsNumber(rt, b))
            return rt.ThrowStr("IllegalArgumentException", 
                "not a number: " + rt.Describe(a) + " and " + rt.Describe(b));
        return Val.OfDouble(f(Num.F64(rt, a), Num.F64(rt, b)));
    }

    /// The NAME of a string, keyword or symbol, as a host string. `keyword`
    /// and `symbol` accept any of the three, which is what lets
    /// `(keyword (name x))` round-trip.
    static string NameOf(Rt rt, long v) {
        if (Val.IsInlineKw(v)) return System.Text.Encoding.UTF8.GetString(Val.InlineBytes(v));
        if (rt.IsHeapTy(v, Obj.TyKw) || rt.IsHeapTy(v, Obj.TySym)) return Str.Text(rt, rt.Slot(v, 1));
        return Str.Text(rt, v);
    }

    /// Clojure prints a double with a trailing `.0`. The shapes agree for
    /// everything the conformance set covers, and a divergence here would show
    /// up as a differing STRING rather than a differing number, which is the
    /// easy kind to catch.
    /// Equality lives in `Eq` now, because maps need it and it needs maps --
    /// a map's `=` compares entries and an entry's key can be a map. One
    /// implementation, not two that drift.
    /// `unchecked-add`, `-sub` and `-mul`, over the WHOLE integer range.
    ///
    /// Mirrors native's `unchecked2`: both operands as `i64` when both are
    /// integers -- fixnum or bigint -- and wrapping arithmetic on those; two
    /// numbers that are not both integers promote to double; anything else is
    /// refused. The `unchecked` block is what makes the wrap a wrap here.
    static long UncheckedOp(Rt rt, int at, int which) {
        long x = rt.VAt(at), y = rt.VAt(at + 1);
        if (Num.IsInt(rt, x) && Num.IsInt(rt, y)) {
            long p = Num.I64Of(rt, x), q = Num.I64Of(rt, y);
            unchecked {
                long r = which == 0 ? p + q : which == 1 ? p - q : p * q;
                return Num.Integer(rt, r);
            }
        }
        if (Num.IsNumber(rt, x) && Num.IsNumber(rt, y)) {
            double p = Num.F64(rt, x), q = Num.F64(rt, y);
            return Val.OfDouble(which == 0 ? p + q : which == 1 ? p - q : p * q);
        }
        return Num.NotNumber(rt, x, y);
    }

    static bool Eq(Rt rt, long a, long b) => global::_3sln.Flint.Kgen.Rt.Valeq.ValEq(rt, a, b);

    /// A CHAIN, as Clojure's comparisons are: `(< 1 2 3)` is one call, not two.
    static long Cmp(Rt rt, int at, int n, int want, bool orEqual) {
        for (int i = 0; i + 1 < n; i++) {
            int c = Num.Cmp(rt, rt.VAt(at + i), rt.VAt(at + i + 1));
            if (!(c == want || (orEqual && c == 0))) return Val.False;
        }
        return Val.True;
    }
}
