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

        Def("flint/str2", (rt, at, n) =>
            Str.Of(rt, Str.Text(rt, rt.VAt(at)) + Str.Text(rt, rt.VAt(at + 1))));
        Def("flint/num->str", (rt, at, n) => {
            long v = rt.VAt(at);
            return Str.Of(rt, Num.IsInt(rt, v)
                ? Num.AsI64(rt, v).Value.ToString(System.Globalization.CultureInfo.InvariantCulture)
                : FmtDouble(Val.AsDouble(v)));
        });

        Def("flint/opaque?", (rt, at, n) => Val.False);
        Def("flint/opaque-label", (rt, at, n) => Val.Nil);
        Def("meta", (rt, at, n) => Val.Nil);

        Def("count", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Val.IsNil(v)) return Val.Fixnum(0);
            if (rt.IsHeapTy(v, Obj.TyVec)) return Val.Fixnum(Vec.Count(rt, v));
            if (Str.IsString(rt, v)) return Val.Fixnum(Str.CharLen(rt, v));
            if (Maps.IsMap(rt, v)) return Val.Fixnum(Maps.Count(rt, v));
            if (Sets.IsSet(rt, v)) return Val.Fixnum(Sets.Count(rt, v));
            if (rt.IsSeq(v)) return Val.Fixnum(Seqs.Count(rt, v));
            throw new System.NotSupportedException("count over " + rt.Describe(v) + " needs more of the data structures");
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
                throw new System.NotSupportedException(
                    "nth over " + rt.Describe(v) + " needs more of the data structures");
            }
            if (got != Val.NotFound) return got;
            if (n > 2) return rt.VAt(at + 2);
            throw new System.IndexOutOfRangeException("index " + i + " out of range");
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
            throw new System.NotSupportedException("conj onto " + rt.Describe(v) + " needs more of the data structures");
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
            throw new System.NotSupportedException("transient of " + rt.Describe(v) + " needs maps and sets ported");
        });
        Def("persistent!", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Vec.IsTransient(rt, v)) {
                if (!Vec.Alive(rt, v))
                    throw new System.InvalidOperationException("persistent! called twice on one transient");
                return Vec.TPersistent(rt, v);
            }
            throw new System.NotSupportedException("persistent! of " + rt.Describe(v) + " needs maps and sets ported");
        });
        Def("conj!", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Vec.IsTransient(rt, v)) {
                if (!Vec.Alive(rt, v))
                    throw new System.InvalidOperationException("conj! on a transient already made persistent");
                long acc = v;
                for (int i = 1; i < n; i++) acc = Vec.TConj(rt, acc, rt.VAt(at + i));
                return acc;
            }
            throw new System.NotSupportedException("conj! onto " + rt.Describe(v) + " needs maps and sets ported");
        });
        Def("assoc!", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Vec.IsTransient(rt, v)) {
                if (!Vec.Alive(rt, v))
                    throw new System.InvalidOperationException("assoc! on a transient already made persistent");
                long acc = v;
                for (int i = 1; i + 1 < n; i += 2) {
                    acc = Vec.TAssoc(rt, acc, (int) Val.AsFixnum(rt.VAt(at + i)), rt.VAt(at + i + 1));
                }
                return acc;
            }
            throw new System.NotSupportedException("assoc! onto " + rt.Describe(v) + " needs maps and sets ported");
        });

        // Maps.
        Def("get", (rt, at, n) => {
            long dflt = n > 2 ? rt.VAt(at + 2) : Val.Nil;
            long coll = rt.VAt(at);
            if (Val.IsNil(coll)) return dflt;
            if (Maps.IsMap(rt, coll)) return Maps.Get(rt, coll, rt.VAt(at + 1), dflt);
            if (Sets.IsSet(rt, coll)) return Sets.Get(rt, coll, rt.VAt(at + 1), dflt);
            if (rt.IsHeapTy(coll, Obj.TyVec)) {
                long k = rt.VAt(at + 1);
                if (!Val.IsFixnum(k)) return dflt;
                long got = Vec.Nth(rt, coll, (int) Val.AsFixnum(k));
                return got == Val.NotFound ? dflt : got;
            }
            throw new System.NotSupportedException("get over " + rt.Describe(coll) + " needs sets ported");
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
            throw new System.NotSupportedException("assoc onto " + rt.Describe(acc) + " needs more of the data structures");
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
            if (rt.IsHeapTy(coll, Obj.TyVec)) {
                long k = rt.VAt(at + 1);
                return Val.Bool(Val.IsFixnum(k) && Val.AsFixnum(k) >= 0
                                && Val.AsFixnum(k) < Vec.Count(rt, coll));
            }
            throw new System.NotSupportedException("contains? over " + rt.Describe(coll) + " needs sets ported");
        });
        Def("hash", (rt, at, n) => Val.Fixnum(Flint.Rt.Eq.HashValue(rt, rt.VAt(at))));

        // Exceptions. `ex-info` is `[msg, data, cause]`, and `throw` is an
        // OPCODE rather than a builtin -- these are what a handler reads.
        Def("ex-info", (rt, at, n) => {
            int bas = rt.Mark();
            int mi = rt.Push(rt.VAt(at));
            int di = rt.Push(n > 1 ? rt.VAt(at + 1) : Val.Nil);
            int ci = rt.Push(n > 2 ? rt.VAt(at + 2) : Val.Nil);
            long a = rt.Alloc(Obj.TyExinfo, 3);
            if (a == 0) { rt.PopTo(bas); return Val.Nil; }
            rt.SetSlot(a, 0, rt.R(mi));
            rt.SetSlot(a, 1, rt.R(di));
            rt.SetSlot(a, 2, rt.R(ci));
            rt.PopTo(bas);
            return Val.Heap(a);
        });
        Def("ex-message", (rt, at, n) =>
            rt.IsHeapTy(rt.VAt(at), Obj.TyExinfo) ? rt.Slot(rt.VAt(at), 0) : Val.Nil);
        Def("ex-data", (rt, at, n) =>
            rt.IsHeapTy(rt.VAt(at), Obj.TyExinfo) ? rt.Slot(rt.VAt(at), 1) : Val.Nil);
        Def("ex-cause", (rt, at, n) =>
            rt.IsHeapTy(rt.VAt(at), Obj.TyExinfo) ? rt.Slot(rt.VAt(at), 2) : Val.Nil);

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
