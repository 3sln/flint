namespace Flint.Rt;

/// The builtins, ported from `runtime/src/builtins.rs`.
///
/// A builtin reads its arguments STRAIGHT OFF the value stack -- `at` is the
/// first, `argc` how many -- rather than being handed an array. That is not a
/// micro-optimisation: an array would be a second place values live, and the
/// collector would have to be taught about it. Everything live is in the value
/// stack, and this keeps that true with no second mechanism.
public static class Builtins {
    public delegate long Fn(Rt rt, int at, int argc);

    /// `:tag` and `:form` on a tagged literal (`doc/decisions/0034`). Shared
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

    static readonly Dictionary<string, Fn> Table = new();

    public static Fn ByName(string n) => Table.TryGetValue(n, out var f) ? f : null;
    static void Def(string n, Fn f) => Table[n] = f;

    /// flint's integers OVERFLOW rather than wrap. The JVM has `Math.*Exact`;
    /// .NET has `checked`, and `doc/decisions/0010` names silent wrapping as
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
        Def("flint/add", (rt, at, n) => {
            long acc = Val.Fixnum(0);
            for (int i = 0; i < n; i++) acc = Num.Add(rt, acc, rt.VAt(at + i));
            return acc;
        });
        Def("+", (rt, at, n) => ByName("flint/add")(rt, at, n));
        Def("flint/sub", (rt, at, n) => {
            if (n == 1) return Num.Neg(rt, rt.VAt(at));
            long acc = rt.VAt(at);
            for (int i = 1; i < n; i++) acc = Num.Sub(rt, acc, rt.VAt(at + i));
            return acc;
        });
        Def("-", (rt, at, n) => ByName("flint/sub")(rt, at, n));
        Def("flint/mul", (rt, at, n) => {
            long acc = Val.Fixnum(1);
            for (int i = 0; i < n; i++) acc = Num.Mul(rt, acc, rt.VAt(at + i));
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

        Def("quot", (rt, at, n) => Num.Quot(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("rem", (rt, at, n) => Num.Rem(rt, rt.VAt(at), rt.VAt(at + 1)));
        // `/` on two integers that do not divide evenly is a DOUBLE here, not a
        // Ratio: flint has no rational type, and `doc/decisions/0010` counts
        // this among the documented divergences from Clojure rather than a bug.
        Def("flint/div", (rt, at, n) => {
            if (n == 1) return Num.Div(rt, Val.Fixnum(1), rt.VAt(at));
            long acc = rt.VAt(at);
            for (int i = 1; i < n; i++) acc = Num.Div(rt, acc, rt.VAt(at + i));
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
        // `doc/decisions/0011` exists for: copying makes it quadratic, and the
        // compiler builds its whole output this way.
        Def("flint/str2", (rt, at, n) => Str.Concat(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("flint/num->str", (rt, at, n) => {
            long v = rt.VAt(at);
            return Str.Of(rt, Num.IsInt(rt, v)
                ? Num.AsI64(rt, v).Value.ToString(System.Globalization.CultureInfo.InvariantCulture)
                : FmtDouble(Val.AsDouble(v)));
        });

        /// The CLOSED SET protocol dispatch runs on (`doc/decisions/0005`).
        /// Small on purpose: three string tiers and eight seq representations
        /// answer with ONE keyword each. It was MISSING from this port, so no
        /// program using a protocol could run here -- found by the language
        /// suite in `test/common`, which is what that suite is for.
        Def("flint/kind", (rt, at, n) => {
            long v = rt.VAt(at);
            string k;
            if (Val.IsNil(v)) k = "nil";
            else if (v == Val.True || v == Val.False) k = "boolean";
            else if (Val.IsDouble(v) || Val.IsFixnum(v)) k = "number";
            else if (Val.IsInlineStr(v)) k = "string";
            else if (Val.IsInlineKw(v)) k = "keyword";
            else if (!Val.IsHeap(v)) k = "other";
            else switch (Obj.Ty(rt.gc.sp, Val.AsHeap(v))) {
                case Obj.TyStr: case Obj.TyRope: k = "string"; break;
                case Obj.TyKw: k = "keyword"; break;
                case Obj.TySym: k = "symbol"; break;
                case Obj.TyBigint: k = "number"; break;
                case Obj.TyVec: case Obj.TyMapentry: k = "vector"; break;
                case Obj.TyArraymap: case Obj.TyHashmap: k = "map"; break;
                case Obj.TySet: k = "set"; break;
                case Obj.TyCons: case Obj.TyEmptyList: case Obj.TyLazyseq:
                case Obj.TyVecseq: case Obj.TyStrseq: case Obj.TyRange:
                case Obj.TyIterseq: case Obj.TyChunkseq: k = "list"; break;
                case Obj.TyClosure: case Obj.TyNativefn: case Obj.TyMultifn: k = "fn"; break;
                case Obj.TyPort: k = "port"; break;
                case Obj.TyThread: k = "thread"; break;
                case Obj.TyAtom: k = "atom"; break;
                case Obj.TyVar: k = "var"; break;
                case Obj.TyRegex: k = "regex"; break;
                case Obj.TyExinfo: k = "exception"; break;
                case Obj.TyTagged: k = "tagged"; break;
                default: k = "other"; break;
            }
            return Str.Keyword(rt, null, k);
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

        Def("flint/opaque?", (rt, at, n) => Val.False);
        Def("flint/opaque-label", (rt, at, n) => Val.Nil);
        /// The metadata slot, or nil. This was a STUB answering nil, which is
        /// indistinguishable from "no metadata" and so passed every test that
        /// did not set any.
        Def("meta", (rt, at, n) => {
            long v = rt.VAt(at);
            int idx = rt.MetaSlot(v);
            return idx < 0 ? Val.Nil : rt.Slot(v, idx);
        });

        Def("count", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Val.IsNil(v)) return Val.Fixnum(0);
            if (rt.IsHeapTy(v, Obj.TyVec)) return Val.Fixnum(Vec.Count(rt, v));
            if (rt.IsHeapTy(v, Obj.TyTagged)) return Val.Fixnum(2);
            if (Str.IsString(rt, v)) return Val.Fixnum(Str.SCount(rt, v));
            if (Maps.IsMap(rt, v)) return Val.Fixnum(Maps.Count(rt, v));
            if (Sets.IsSet(rt, v)) return Val.Fixnum(Sets.Count(rt, v));
            if (Maps.IsTransient(rt, v)) return Val.Fixnum(Maps.TCount(rt, v));
            if (Sets.IsTransient(rt, v)) return Val.Fixnum(Sets.TCount(rt, v));
            if (Vec.IsTransient(rt, v)) return Val.Fixnum(Vec.TCount(rt, v));
            if (Bytes.IsBytes(rt, v)) return Val.Fixnum(Bytes.Count(rt, v));
            if (rt.IsSeq(v)) return Val.Fixnum(Seqs.Count(rt, v));
            return rt.ThrowStr("UnsupportedOperationException", "count over " + rt.Describe(v) + " needs more of the data structures");
        });
        Def("nth", (rt, at, n) => {
            long v = rt.VAt(at);
            int i = (int) Val.AsFixnum(rt.VAt(at + 1));
            long got = Val.NotFound;
            if (rt.IsHeapTy(v, Obj.TyVec)) {
                got = Vec.Nth(rt, v, i);
            } else if (Str.IsString(rt, v)) {
                got = Str.Nth(rt, v, i);
            } else if (rt.IsHeapTy(v, Obj.TyMapentry)) {
                if (i == 0 || i == 1) got = rt.Slot(v, i);
            } else if (Val.IsNil(v)) {
                got = Val.NotFound;
            } else if (rt.IsSeq(v)) {
                // O(n), as Clojure's `nth` on a seq is. Walking rather than
                // refusing, because `nth` over a seq is ordinary code and the
                // cost is the caller's to know about.
                int bas = rt.Mark();
                int sq = rt.Push(Seqs.Seq(rt, v));
                for (int k = 0; k < i && !Val.IsNil(rt.R(sq)); k++) rt.SetR(sq, Seqs.Next(rt, rt.R(sq)));
                if (!Val.IsNil(rt.R(sq))) got = Seqs.First(rt, rt.R(sq));
                rt.PopTo(bas);
            } else {
                return rt.ThrowStr("UnsupportedOperationException", 
                    "nth over " + rt.Describe(v) + " needs more of the data structures");
            }
            if (got != Val.NotFound) return got;
            if (n > 2) return rt.VAt(at + 2);
            return rt.ThrowStr("IndexOutOfBoundsException", "index " + i + " out of range");
        });
        Def("conj", (rt, at, n) => {
            long v = rt.VAt(at);
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
            if (Maps.IsMap(rt, v)) {
                // `conj` onto a map takes an ENTRY or a two-element vector.
                int bas = rt.Mark();
                int ai = rt.Push(v);
                for (int i = 1; i < n; i++) {
                    long e = rt.VAt(at + i);
                    rt.SetR(ai, Maps.Assoc(rt, rt.R(ai), Seqs.First(rt, e), Seqs.First(rt, Seqs.Rest(rt, e))));
                }
                long outc = rt.R(ai);
                rt.PopTo(bas);
                return outc;
            }
            // `conj` on a SEQ prepends, where on a vector it appends. That
            // asymmetry is Clojure's and is about where the collection is cheap
            // to grow, not about consistency.
            if (Val.IsNil(v) || rt.IsSeq(v)) {
                long acc = Val.IsNil(v) ? Seqs.EmptyList(rt) : v;
                for (int i = 1; i < n; i++) acc = Seqs.Cons(rt, rt.VAt(at + i), acc);
                return acc;
            }
            return rt.ThrowStr("UnsupportedOperationException", "conj onto " + rt.Describe(v) + " needs more of the data structures");
        });

        Def("seq", (rt, at, n) => Seqs.Seq(rt, rt.VAt(at)));
        Def("first", (rt, at, n) => Seqs.First(rt, rt.VAt(at)));
        Def("next", (rt, at, n) => Seqs.Next(rt, rt.VAt(at)));
        Def("rest", (rt, at, n) => Seqs.Rest(rt, rt.VAt(at)));
        Def("cons", (rt, at, n) => Seqs.Cons(rt, rt.VAt(at), rt.VAt(at + 1)));

        // Transients. A transient is a MUTABLE handle on a persistent value,
        // and the whole contract is that the persistent one it came from is
        // untouched -- so `persistent!` invalidates the handle rather than
        // leaving two owners of the same nodes.
        Def("transient", (rt, at, n) => {
            long v = rt.VAt(at);
            if (rt.IsHeapTy(v, Obj.TyVec)) return Vec.TransientOf(rt, v);
            if (Maps.IsMap(rt, v)) return Maps.TransientOf(rt, v);
            if (Sets.IsSet(rt, v)) return Sets.TransientOf(rt, v);
            return rt.ThrowStr("ClassCastException", rt.Describe(v) + " is not transientable");
        });
        Def("persistent!", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Vec.IsTransient(rt, v)) {
                if (!Vec.Alive(rt, v))
                    return rt.ThrowStr("IllegalStateException", "persistent! called twice on one transient");
                return Vec.TPersistent(rt, v);
            }
            if (Maps.IsTransient(rt, v)) return Maps.TPersistent(rt, v);
            if (Sets.IsTransient(rt, v)) return Sets.TPersistent(rt, v);
            if (Bytes.IsTransient(rt, v)) return Bytes.Persistent(rt, v);
            return rt.ThrowStr("ClassCastException", rt.Describe(v) + " is not a transient");
        });
        Def("conj!", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Vec.IsTransient(rt, v)) {
                if (!Vec.Alive(rt, v))
                    return rt.ThrowStr("IllegalStateException", "conj! on a transient already made persistent");
                long acc = v;
                for (int i = 1; i < n; i++) acc = Vec.TConj(rt, acc, rt.VAt(at + i));
                return acc;
            }
            if (Sets.IsTransient(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Sets.TConj(rt, acc, rt.VAt(at + i));
                return acc;
            }
            if (Maps.IsTransient(rt, v)) {
                // `conj!` onto a map takes an ENTRY or a two-element vector.
                long acc = v;
                for (int i = 1; i < n; i++) {
                    long e = rt.VAt(at + i);
                    acc = Maps.TAssoc(rt, acc, Seqs.First(rt, e), Seqs.First(rt, Seqs.Rest(rt, e)));
                }
                return acc;
            }
            return rt.ThrowStr("ClassCastException", rt.Describe(v) + " is not a transient");
        });
        Def("assoc!", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Vec.IsTransient(rt, v)) {
                if (!Vec.Alive(rt, v))
                    return rt.ThrowStr("IllegalStateException", "assoc! on a transient already made persistent");
                long acc = v;
                for (int i = 1; i + 1 < n; i += 2) {
                    acc = Vec.TAssoc(rt, acc, (int) Val.AsFixnum(rt.VAt(at + i)), rt.VAt(at + i + 1));
                }
                return acc;
            }
            if (Maps.IsTransient(rt, v)) {
                long acc = v;
                for (int i = 1; i + 1 < n; i += 2) acc = Maps.TAssoc(rt, acc, rt.VAt(at + i), rt.VAt(at + i + 1));
                return acc;
            }
            return rt.ThrowStr("ClassCastException", rt.Describe(v) + " is not a transient");
        });
        Def("dissoc!", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Maps.IsTransient(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Maps.TDissoc(rt, acc, rt.VAt(at + i));
                return acc;
            }
            if (Sets.IsTransient(rt, v)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Sets.TDisj(rt, acc, rt.VAt(at + i));
                return acc;
            }
            return rt.ThrowStr("ClassCastException", rt.Describe(v) + " is not a transient");
        });

        // Maps.
        Def("get", (rt, at, n) => {
            long dflt = n > 2 ? rt.VAt(at + 2) : Val.Nil;
            long coll = rt.VAt(at);
            if (Val.IsNil(coll)) return dflt;
            if (Maps.IsMap(rt, coll)) return Maps.Get(rt, coll, rt.VAt(at + 1), dflt);
            // A tagged literal reads like a two-key map (`0034`).
            if (rt.IsHeapTy(coll, Obj.TyTagged)) return TaggedGet(rt, coll, rt.VAt(at + 1), dflt);
            if (Sets.IsSet(rt, coll)) return Sets.Get(rt, coll, rt.VAt(at + 1), dflt);
            if (Maps.IsTransient(rt, coll)) return Maps.TGet(rt, coll, rt.VAt(at + 1), dflt);
            if (Sets.IsTransient(rt, coll)) return Sets.TGet(rt, coll, rt.VAt(at + 1), dflt);
            if (Vec.IsTransient(rt, coll)) {
                long k2 = rt.VAt(at + 1);
                if (!Val.IsFixnum(k2)) return dflt;
                long got2 = Vec.TNth(rt, coll, (int) Val.AsFixnum(k2));
                return got2 == Val.NotFound ? dflt : got2;
            }
            if (rt.IsHeapTy(coll, Obj.TyVec)) {
                long k = rt.VAt(at + 1);
                if (!Val.IsFixnum(k)) return dflt;
                long got = Vec.Nth(rt, coll, (int) Val.AsFixnum(k));
                return got == Val.NotFound ? dflt : got;
            }
            return rt.ThrowStr("UnsupportedOperationException", "get over " + rt.Describe(coll) + " needs sets ported");
        });
        Def("assoc", (rt, at, n) => {
            long acc = rt.VAt(at);
            if (Val.IsNil(acc)) acc = Maps.Empty(rt);
            if (Maps.IsMap(rt, acc)) {
                int bas = rt.Mark();
                int ai = rt.Push(acc);
                for (int i = 1; i + 1 < n; i += 2) {
                    long nm = Maps.Assoc(rt, rt.R(ai), rt.VAt(at + i), rt.VAt(at + i + 1));
                    rt.SetR(ai, nm);
                }
                long outv = rt.R(ai);
                rt.PopTo(bas);
                return outv;
            }
            // Two slots and nowhere for a third, so `assoc` on either key keeps
            // the type and anything else is refused, NAMING the key
            // (`doc/decisions/0034`).
            if (rt.IsHeapTy(acc, Obj.TyTagged)) {
                int bas = rt.Mark();
                int ai = rt.Push(acc);
                for (int i = 1; i + 1 < n; i += 2) {
                    long k = rt.VAt(at + i), v = rt.VAt(at + i + 1);
                    long cur = rt.R(ai);
                    if (k == Str.Keyword(rt, null, "tag")) {
                        if (!rt.IsHeapTy(v, Obj.TySym)) {
                            rt.PopTo(bas);
                            return rt.ThrowStr("IllegalArgumentException",
                                "a tagged literal's :tag must be a symbol");
                        }
                        rt.SetR(ai, rt.NewTagged(v, rt.Slot(cur, 1)));
                    } else if (k == Str.Keyword(rt, null, "form")) {
                        rt.SetR(ai, rt.NewTagged(rt.Slot(cur, 0), v));
                    } else {
                        rt.PopTo(bas);
                        return rt.ThrowStr("IllegalArgumentException",
                            "a tagged literal has :tag and :form and nothing else, so it "
                            + "cannot take :" + Str.Text(rt, KwName(rt, k)));
                    }
                }
                long outt = rt.R(ai);
                rt.PopTo(bas);
                return outt;
            }
            if (rt.IsHeapTy(acc, Obj.TyVec)) {
                int bas = rt.Mark();
                int ai = rt.Push(acc);
                for (int i = 1; i + 1 < n; i += 2) {
                    rt.SetR(ai, Vec.Assoc(rt, rt.R(ai),
                                          (int) Val.AsFixnum(rt.VAt(at + i)), rt.VAt(at + i + 1)));
                }
                long outv = rt.R(ai);
                rt.PopTo(bas);
                return outv;
            }
            return rt.ThrowStr("UnsupportedOperationException", "assoc onto " + rt.Describe(acc) + " needs more of the data structures");
        });
        Def("dissoc", (rt, at, n) => {
            long acc = rt.VAt(at);
            if (Val.IsNil(acc)) return Val.Nil;
            int bas = rt.Mark();
            int ai = rt.Push(acc);
            for (int i = 1; i < n; i++) {
                long nm = Maps.Dissoc(rt, rt.R(ai), rt.VAt(at + i));
                rt.SetR(ai, nm);
            }
            long o = rt.R(ai);
            rt.PopTo(bas);
            return o;
        });
        Def("contains?", (rt, at, n) => {
            long coll = rt.VAt(at);
            if (Val.IsNil(coll)) return Val.False;
            if (Maps.IsMap(rt, coll)) return Val.Bool(Maps.Contains(rt, coll, rt.VAt(at + 1)));
            if (Sets.IsSet(rt, coll)) return Val.Bool(Sets.Contains(rt, coll, rt.VAt(at + 1)));
            // The TRANSIENT forms too. A transient is a handle on the same
            // trie, so every reader that works on the persistent value works on
            // it -- and the library reaches for exactly that while building.
            if (Maps.IsTransient(rt, coll))
                return Val.Bool(Maps.TGet(rt, coll, rt.VAt(at + 1), Val.NotFound) != Val.NotFound);
            if (Sets.IsTransient(rt, coll))
                return Val.Bool(Sets.TGet(rt, coll, rt.VAt(at + 1), Val.NotFound) != Val.NotFound);
            if (Vec.IsTransient(rt, coll)) {
                long k3 = rt.VAt(at + 1);
                return Val.Bool(Val.IsFixnum(k3) && Val.AsFixnum(k3) >= 0
                                && Val.AsFixnum(k3) < Vec.TCount(rt, coll));
            }
            if (rt.IsHeapTy(coll, Obj.TyVec)) {
                long k = rt.VAt(at + 1);
                return Val.Bool(Val.IsFixnum(k) && Val.AsFixnum(k) >= 0
                                && Val.AsFixnum(k) < Vec.Count(rt, coll));
            }
            return rt.ThrowStr("UnsupportedOperationException", "contains? over " + rt.Describe(coll) + " needs sets ported");
        });
        Def("hash", (rt, at, n) => Val.Fixnum(Flint.Rt.Eq.HashValue(rt, rt.VAt(at))));

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
        Def("flint/signum", (rt, at, n) => MathOne(rt, rt.VAt(at), x => (double) System.Math.Sign(x)));
        Def("flint/fabs", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Abs(x)));
        Def("flint/pow", (rt, at, n) => MathTwo(rt, rt.VAt(at), rt.VAt(at + 1), (x, y) => System.Math.Pow(x, y)));
        Def("flint/atan2", (rt, at, n) => MathTwo(rt, rt.VAt(at), rt.VAt(at + 1), (x, y) => System.Math.Atan2(x, y)));
        Def("flint/hypot", (rt, at, n) => MathTwo(rt, rt.VAt(at), rt.VAt(at + 1), (x, y) => (double) System.Math.Sqrt(x*x + y*y)));
        Def("flint/trunc", (rt, at, n) => MathOne(rt, rt.VAt(at), x => System.Math.Truncate(x)));
        Def("flint/copy-sign", (rt, at, n) =>
            MathTwo(rt, rt.VAt(at), rt.VAt(at + 1), (x, y) => System.Math.CopySign(System.Math.Abs(x), y)));
        Def("flint/to-long", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Num.IsInt(rt, v)) return v;
            if (!Val.IsDouble(v)) return rt.ThrowStr("IllegalArgumentException", "not a number: " + rt.Describe(v));
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
        Def("flint/volatile", (rt, at, n) => NewCell(rt, Obj.TyVolatile, rt.VAt(at)));
        Def("deref", (rt, at, n) => {
            long v = rt.VAt(at);
            if (rt.IsHeapTy(v, Obj.TyAtom) || rt.IsHeapTy(v, Obj.TyVolatile)) return rt.Slot(v, 0);
            if (rt.IsHeapTy(v, Obj.TyDelay)) {
                long thunk = rt.Slot(v, 0);
                if (Val.IsNil(thunk)) return rt.Slot(v, 1);
                int bas = rt.Mark();
                int di = rt.Push(v);
                long r = rt.Invoke(thunk, System.Array.Empty<long>());
                int ri = rt.Push(r);
                long d = rt.R(di);
                rt.SetSlot(Val.AsHeap(d), 0, Val.Nil);   // forced: drop the thunk
                rt.SetSlot(Val.AsHeap(d), 1, rt.R(ri));
                long outv = rt.R(ri);
                rt.PopTo(bas);
                return outv;
            }
            return rt.ThrowStr("ClassCastException", "cannot deref " + rt.Describe(v));
        });
        Def("reset!", (rt, at, n) => {
            long a = rt.VAt(at);
            if (!rt.IsHeapTy(a, Obj.TyAtom) && !rt.IsHeapTy(a, Obj.TyVolatile))
                return rt.ThrowStr("ClassCastException", "not an atom: " + rt.Describe(a));
            rt.SetSlot(Val.AsHeap(a), 0, rt.VAt(at + 1));
            return rt.VAt(at + 1);
        });
        Def("compare-and-set!", (rt, at, n) => {
            long a = rt.VAt(at);
            if (!rt.IsHeapTy(a, Obj.TyAtom) && !rt.IsHeapTy(a, Obj.TyVolatile))
                return rt.ThrowStr("ClassCastException", "not an atom: " + rt.Describe(a));
            if (rt.Slot(a, 0) != rt.VAt(at + 1)) return Val.False;
            rt.SetSlot(Val.AsHeap(a), 0, rt.VAt(at + 2));
            return Val.True;
        });

        // --- dynamic bindings -------------------------------------------------
        //
        // A MAP in a singleton slot, not a per-var stack. `binding` swaps the
        // whole map and restores it, so a park in the middle carries the
        // bindings with the thread rather than leaving them behind.
        Def("flint/dyn-get", (rt, at, n) => {
            long binds = rt.roots.shared.Singletons[Rt.SingBindings];
            if (Val.IsNil(binds)) return rt.VAt(at + 1);
            return Maps.Get(rt, binds, rt.VAt(at), rt.VAt(at + 1));
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
        Def("peek", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Val.IsNil(v)) return Val.Nil;
            // A VECTOR peeks at its LAST element and a seq at its FIRST. That
            // asymmetry is Clojure's, and it is the same one `conj` has: each
            // takes the end that is cheap.
            if (rt.IsHeapTy(v, Obj.TyVec)) {
                int c = Vec.Count(rt, v);
                return c == 0 ? Val.Nil : Vec.Nth(rt, v, c - 1);
            }
            return Seqs.First(rt, v);
        });
        Def("pop", (rt, at, n) => {
            long v = rt.VAt(at);
            if (rt.IsHeapTy(v, Obj.TyVec)) {
                int c = Vec.Count(rt, v);
                if (c == 0) return rt.ThrowStr("IllegalStateException", "cannot pop an empty vector");
                int bas = rt.Mark();
                int ai = rt.Push(Vec.Empty(rt));
                int vi = rt.Push(v);
                for (int i = 0; i < c - 1; i++)
                    rt.SetR(ai, Vec.Conj(rt, rt.R(ai), Vec.Nth(rt, rt.R(vi), i)));
                long outv = rt.R(ai);
                rt.PopTo(bas);
                return outv;
            }
            if (Val.IsNil(v)) return rt.ThrowStr("IllegalStateException", "cannot pop nil");
            return Seqs.Rest(rt, v);
        });
        Def("empty", (rt, at, n) => {
            long v = rt.VAt(at);
            if (rt.IsHeapTy(v, Obj.TyVec)) return Vec.Empty(rt);
            if (Maps.IsMap(rt, v)) return Maps.Empty(rt);
            if (Sets.IsSet(rt, v)) return Sets.Empty(rt);
            if (rt.IsSeq(v)) return Seqs.EmptyList(rt);
            return Val.Nil;
        });

        // --- strings ----------------------------------------------------------
        Def("flint/subs", (rt, at, n) => {
            string s = Str.Text(rt, rt.VAt(at));
            var si = new System.Globalization.StringInfo(s);
            int len = CodePointCount(s);
            int start = (int) Val.AsFixnum(rt.VAt(at + 1));
            int end = n > 2 ? (int) Val.AsFixnum(rt.VAt(at + 2)) : len;
            if (start < 0 || end > len || start > end)
                return rt.ThrowStr("IndexOutOfBoundsException", "subs " + start + ".." + end + " of " + len);
            // By CODE POINT, not by char: a .NET `string` is UTF-16, so slicing
            // it by index would cut a surrogate pair in half.
            int bs = OffsetByCodePoints(s, start), be = OffsetByCodePoints(s, end);
            return Str.Of(rt, s.Substring(bs, be - bs));
        });
        Def("flint/str->num", (rt, at, n) => {
            string s = Str.Text(rt, rt.VAt(at)).Trim();
            var inv = System.Globalization.CultureInfo.InvariantCulture;
            if (s.IndexOf('.') < 0 && s.IndexOf('e') < 0 && s.IndexOf('E') < 0) {
                return long.TryParse(s, System.Globalization.NumberStyles.Integer, inv, out long l)
                    ? Num.Integer(rt, l) : Val.Nil;
            }
            return double.TryParse(s, System.Globalization.NumberStyles.Float, inv, out double d)
                ? Val.OfDouble(d) : Val.Nil;   // nil, not a throw: this is a PARSE attempt
        });
        Def("flint/str-index-of", (rt, at, n) => {
            string h = Str.Text(rt, rt.VAt(at));
            string needle = Str.Text(rt, rt.VAt(at + 1));
            int from = n > 2 ? (int) Val.AsFixnum(rt.VAt(at + 2)) : 0;
            int at16 = from <= 0 ? 0 : OffsetByCodePoints(h, System.Math.Min(from, CodePointCount(h)));
            int i = h.IndexOf(needle, at16, System.StringComparison.Ordinal);
            return i < 0 ? Val.Nil : Val.Fixnum(CodePointCount(h.Substring(0, i)));
        });
        Def("flint/str-join", (rt, at, n) => {
            var sb = new System.Text.StringBuilder();
            int bas = rt.Mark();
            int s = rt.Push(Seqs.Seq(rt, rt.VAt(at)));
            while (!Val.IsNil(rt.R(s))) {
                sb.Append(Str.Text(rt, Seqs.First(rt, rt.R(s))));
                rt.SetR(s, Seqs.Next(rt, rt.R(s)));
            }
            rt.PopTo(bas);
            return Str.Of(rt, sb.ToString());
        });
        Def("flint/upper-case", (rt, at, n) =>
            Str.Of(rt, Str.Text(rt, rt.VAt(at)).ToUpperInvariant()));
        Def("flint/lower-case", (rt, at, n) =>
            Str.Of(rt, Str.Text(rt, rt.VAt(at)).ToLowerInvariant()));
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
            byte[] b = Str.Bytes(rt, rt.VAt(at));
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
            for (int i = 0; i < c; i++) b[i] = (byte) Val.AsFixnum(Vec.Nth(rt, v, i));
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
            Val.Fixnum(Flint.Rt.Eq.Compare(rt, rt.VAt(at), rt.VAt(at + 1))));

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
            if (rt.IsHeapTy(v, Obj.TyLazyseq)) return Val.Bool(Val.IsNil(rt.Slot(v, Seqs.LsThunk)));
            return Val.True;
        });

        // --- opaque values (`doc/decisions/0022`) -----------------------------
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
        /// `doc/decisions/0031`.
        Def("flint/array-map", (rt, at, n) => {
            int bas = rt.Mark();
            int si = rt.Push(Seqs.Seq(rt, rt.VAt(at)));
            int valsAt = rt.Mark();
            int count = 0;
            while (!Val.IsNil(rt.R(si))) {
                rt.Push(Seqs.First(rt, rt.R(si)));
                count++;
                rt.SetR(si, Seqs.Next(rt, rt.R(si)));
            }
            if (count % 2 != 0) {
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalArgumentException", "array-map needs an even number of forms");
            }
            int pairs = count / 2;
            long a = rt.Alloc(Obj.TyArraymap, Maps.AM_BASE + 2 * pairs);
            if (a == 0) { rt.PopTo(bas); return Val.Nil; }
            rt.SetSlot(a, Maps.AM_META, Val.Nil);
            rt.SetSlot(a, Maps.AM_HASH, Val.Nil);
            for (int i = 0; i < 2 * pairs; i++) rt.SetSlot(a, Maps.AM_BASE + i, rt.R(valsAt + i));
            rt.PopTo(bas);
            return Val.Heap(a);
        });

        // --- unchecked arithmetic ---------------------------------------------
        //
        // WRAPS rather than throwing, which is the point of asking for it:
        // `hash` and the bit-mixing in `map.rs` are made of wrapping
        // arithmetic, and the checked forms refuse the very operations those
        // are. `unchecked` is the .NET spelling of "I meant this".
        Def("flint/unchecked-add", (rt, at, n) => {
            unchecked { return Num.Integer(rt, Val.AsFixnum(rt.VAt(at)) + Val.AsFixnum(rt.VAt(at + 1))); }
        });
        Def("flint/unchecked-sub", (rt, at, n) => {
            unchecked { return Num.Integer(rt, Val.AsFixnum(rt.VAt(at)) - Val.AsFixnum(rt.VAt(at + 1))); }
        });
        Def("flint/unchecked-mul", (rt, at, n) => {
            unchecked { return Num.Integer(rt, Val.AsFixnum(rt.VAt(at)) * Val.AsFixnum(rt.VAt(at + 1))); }
        });

        // --- byte strings (`doc/decisions/0024`) ------------------------------
        Def("flint/b-count", (rt, at, n) => Val.Fixnum(Bytes.Count(rt, rt.VAt(at))));
        Def("flint/b-at", (rt, at, n) => {
            int b = Bytes.At(rt, rt.VAt(at), (int) Val.AsFixnum(rt.VAt(at + 1)));
            return b < 0 ? Val.Nil : Val.Fixnum(b);
        });
        Def("flint/b-concat", (rt, at, n) => Bytes.Concat(rt, rt.VAt(at), rt.VAt(at + 1)));
        Def("flint/b-slice", (rt, at, n) => Bytes.Slice(rt, rt.VAt(at),
            (int) Val.AsFixnum(rt.VAt(at + 1)),
            n > 2 ? (int) Val.AsFixnum(rt.VAt(at + 2)) : Bytes.Count(rt, rt.VAt(at))));
        Def("flint/b-depth", (rt, at, n) => Val.Fixnum(Bytes.Depth(rt, rt.VAt(at))));
        Def("flint/str->b", (rt, at, n) => Bytes.Of(rt, Str.Bytes(rt, rt.VAt(at))));
        Def("flint/b->str", (rt, at, n) =>
            Str.Of(rt, System.Text.Encoding.UTF8.GetString(Bytes.ToArray(rt, rt.VAt(at)))));
        Def("flint/vec->b", (rt, at, n) => {
            long v = rt.VAt(at);
            int c = Vec.Count(rt, v);
            byte[] b = new byte[c];
            for (int i = 0; i < c; i++) b[i] = (byte) Val.AsFixnum(Vec.Nth(rt, v, i));
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
            // the point (`doc/decisions/0022`).
            int bas = rt.Mark();
            int ni = rt.Push(name);
            int vi = rt.Push(Vec.Empty(rt));
            for (int i = 1; i < n; i++) rt.SetR(vi, Vec.Conj(rt, rt.R(vi), rt.VAt(at + i)));
            long nm = rt.R(ni), args = rt.R(vi);
            rt.PopTo(bas);
            return Conc.PortOpen(rt, nm, args);
        });
        /// The port's format, as the guest wants to remember it. METADATA, not
        /// behaviour: the runtime stores it and answers `port-format` with it
        /// and does nothing else, which is why `open` no longer takes it.
        Def("flint/set-port-format", (rt, at, n) => {
            long p = rt.VAt(at), f = rt.VAt(at + 1);
            if (!Conc.IsPort(rt, p))
                return rt.ThrowStr("ClassCastException", "set-port-format wants a port");
            rt.SetSlot(Val.AsHeap(p), Conc.PT_FORMAT, f);
            return f;
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
        Def("flint/port-format", (rt, at, n) => {
            long p = rt.VAt(at);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "port-format wants a port");
            return rt.Slot(p, Conc.PT_FORMAT);
        });
        Def("flint/port-opts", (rt, at, n) => {
            long p = rt.VAt(at);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "port-opts wants a port");
            return rt.Slot(p, Conc.PT_OPTS);
        });
        Def("flint/set-port-opts", (rt, at, n) => {
            long p = rt.VAt(at), o = rt.VAt(at + 1);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "set-port-opts wants a port");
            rt.SetSlot(Val.AsHeap(p), Conc.PT_OPTS, o);
            return o;
        });
        Def("flint/set-port-binary", (rt, at, n) => {
            long p = rt.VAt(at), v = rt.VAt(at + 1);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "set-port-binary wants a port");
            bool on = !(Val.IsNil(v) || v == Val.False);
            rt.SetSlot(Val.AsHeap(p), Conc.PT_BINARY, Val.Fixnum(on ? 1 : 0));
            return v;
        });
        /// Any port whose messages CROSS A HEAP, which is what the name is
        /// really asking: a host port and a global port both carry bytes and
        /// both need a codec, and `flint.port/send` branches on exactly that.
        Def("flint/port-host?", (rt, at, n) => {
            long p = rt.VAt(at);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "port-host? wants a port");
            return Val.Bool(Conc.CrossesAHeap(Val.AsFixnum(rt.Slot(p, Conc.PT_KIND))));
        });
        Def("flint/port-state", (rt, at, n) => {
            long p = rt.VAt(at);
            if (!Conc.IsPort(rt, p)) return rt.ThrowStr("ClassCastException", "port-state wants a port");
            // THE QUERY IS THE TRUTH (`doc/decisions/0006`), so it resolves the
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
            int si = rt.Push(Seqs.Seq(rt, rt.VAt(at + 1)));
            int count = 0;
            while (!Val.IsNil(rt.R(si))) {
                rt.Push(Seqs.First(rt, rt.R(si)));
                count++;
                rt.SetR(si, Seqs.Next(rt, rt.R(si)));
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

        // Lazy sequences and ranges.
        Def("flint/lazy-seq", (rt, at, n) => Seqs.LazySeq(rt, rt.VAt(at)));
        Def("flint/range3", (rt, at, n) =>
            Seqs.Range(rt, rt.VAt(at), rt.VAt(at + 1), rt.VAt(at + 2)));

        // Sets.
        Def("disj", (rt, at, n) => {
            long acc = rt.VAt(at);
            if (Val.IsNil(acc)) return Val.Nil;
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
    static string FmtDouble(double d) {
        var inv = System.Globalization.CultureInfo.InvariantCulture;
        if (d == System.Math.Floor(d) && !double.IsInfinity(d) && System.Math.Abs(d) < 1e15) {
            return ((long) d).ToString(inv) + ".0";
        }
        return d.ToString("R", inv);
    }

    /// Equality lives in `Eq` now, because maps need it and it needs maps --
    /// a map's `=` compares entries and an entry's key can be a map. One
    /// implementation, not two that drift.
    static bool Eq(Rt rt, long a, long b) => Flint.Rt.Eq.Equal(rt, a, b);

    /// A CHAIN, as Clojure's comparisons are: `(< 1 2 3)` is one call, not two.
    static long Cmp(Rt rt, int at, int n, int want, bool orEqual) {
        for (int i = 0; i + 1 < n; i++) {
            int c = Num.Cmp(rt, rt.VAt(at + i), rt.VAt(at + i + 1));
            if (!(c == want || (orEqual && c == 0))) return Val.False;
        }
        return Val.True;
    }
}
