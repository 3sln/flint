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
        Def("flint/add", (rt, at, n) => {
            long acc = 0;
            for (int i = 0; i < n; i++) acc = AddExact(acc, Val.AsFixnum(rt.VAt(at + i)));
            return Val.Fixnum(acc);
        });
        Def("+", (rt, at, n) => ByName("flint/add")(rt, at, n));
        Def("flint/sub", (rt, at, n) => {
            if (n == 1) return Val.Fixnum(NegExact(Val.AsFixnum(rt.VAt(at))));
            long acc = Val.AsFixnum(rt.VAt(at));
            for (int i = 1; i < n; i++) acc = SubExact(acc, Val.AsFixnum(rt.VAt(at + i)));
            return Val.Fixnum(acc);
        });
        Def("-", (rt, at, n) => ByName("flint/sub")(rt, at, n));
        Def("flint/mul", (rt, at, n) => {
            long acc = 1;
            for (int i = 0; i < n; i++) acc = MulExact(acc, Val.AsFixnum(rt.VAt(at + i)));
            return Val.Fixnum(acc);
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
        Def("inc", (rt, at, n) => Val.Fixnum(AddExact(Val.AsFixnum(rt.VAt(at)), 1)));
        Def("dec", (rt, at, n) => Val.Fixnum(SubExact(Val.AsFixnum(rt.VAt(at)), 1)));

        Def("identical?", (rt, at, n) => Val.Bool(rt.VAt(at) == rt.VAt(at + 1)));
        Def("nil?", (rt, at, n) => Val.Bool(Val.IsNil(rt.VAt(at))));
        Def("not", (rt, at, n) => Val.Bool(!Val.Truthy(rt.VAt(at))));
        Def("true?", (rt, at, n) => Val.Bool(rt.VAt(at) == Val.True));
        Def("false?", (rt, at, n) => Val.Bool(rt.VAt(at) == Val.False));
        Def("boolean", (rt, at, n) => Val.Bool(Val.Truthy(rt.VAt(at))));
        Def("number?", (rt, at, n) => Val.Bool(Val.IsFixnum(rt.VAt(at)) || Val.IsDouble(rt.VAt(at))));

        Def("quot", (rt, at, n) => Val.Fixnum(Val.AsFixnum(rt.VAt(at)) / Val.AsFixnum(rt.VAt(at + 1))));
        Def("rem", (rt, at, n) => Val.Fixnum(Val.AsFixnum(rt.VAt(at)) % Val.AsFixnum(rt.VAt(at + 1))));
        // `/` on two integers that do not divide evenly is a DOUBLE here, not a
        // Ratio: flint has no rational type, and `doc/decisions/0010` counts
        // this among the documented divergences from Clojure rather than a bug.
        Def("flint/div", (rt, at, n) => {
            long a = Val.AsFixnum(rt.VAt(at)), b = Val.AsFixnum(rt.VAt(at + 1));
            return b != 0 && a % b == 0 ? Val.Fixnum(a / b) : Val.OfDouble((double) a / b);
        });

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
            return Str.Of(rt, Val.IsFixnum(v)
                ? Val.AsFixnum(v).ToString(System.Globalization.CultureInfo.InvariantCulture)
                : FmtDouble(Val.AsDouble(v)));
        });

        Def("flint/opaque?", (rt, at, n) => Val.False);
        Def("flint/opaque-label", (rt, at, n) => Val.Nil);
        Def("meta", (rt, at, n) => Val.Nil);

        Def("count", (rt, at, n) => {
            long v = rt.VAt(at);
            if (Val.IsNil(v)) return Val.Fixnum(0);
            if (rt.IsHeapTy(v, Obj.TyVec)) return Val.Fixnum(Vec.Count(rt, v));
            if (Str.IsString(rt, v)) return Val.Fixnum(Str.ByteLen(rt, v));
            if (rt.IsSeq(v)) return Val.Fixnum(Seqs.Count(rt, v));
            throw new System.NotSupportedException("count on this needs more of the data structures");
        });
        Def("nth", (rt, at, n) => {
            long v = rt.VAt(at);
            int i = (int) Val.AsFixnum(rt.VAt(at + 1));
            if (rt.IsHeapTy(v, Obj.TyVec)) {
                long got = Vec.Nth(rt, v, i);
                if (got != Val.NotFound) return got;
                if (n > 2) return rt.VAt(at + 2);
                throw new System.IndexOutOfRangeException("index " + i + " out of range");
            }
            throw new System.NotSupportedException("nth on this needs more of the data structures");
        });
        Def("conj", (rt, at, n) => {
            long v = rt.VAt(at);
            if (rt.IsHeapTy(v, Obj.TyVec)) {
                long acc = v;
                for (int i = 1; i < n; i++) acc = Vec.Conj(rt, acc, rt.VAt(at + i));
                return acc;
            }
            // `conj` on a SEQ prepends, where on a vector it appends. That
            // asymmetry is Clojure's and is about where the collection is cheap
            // to grow, not about consistency.
            if (Val.IsNil(v) || rt.IsSeq(v)) {
                long acc = Val.IsNil(v) ? Seqs.EmptyList(rt) : v;
                for (int i = 1; i < n; i++) acc = Seqs.Cons(rt, rt.VAt(at + i), acc);
                return acc;
            }
            throw new System.NotSupportedException("conj on this needs more of the data structures");
        });

        Def("seq", (rt, at, n) => Seqs.Seq(rt, rt.VAt(at)));
        Def("first", (rt, at, n) => Seqs.First(rt, rt.VAt(at)));
        Def("next", (rt, at, n) => Seqs.Next(rt, rt.VAt(at)));
        Def("rest", (rt, at, n) => Seqs.Rest(rt, rt.VAt(at)));
        Def("cons", (rt, at, n) => Seqs.Cons(rt, rt.VAt(at), rt.VAt(at + 1)));

        Def("=", (rt, at, n) => {
            for (int i = 1; i < n; i++) if (!Eq(rt, rt.VAt(at), rt.VAt(at + i))) return Val.False;
            return Val.True;
        });
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

    /// Structural equality, from `runtime/src/eq.rs`.
    ///
    /// Only the scalar, string and vector cases are ported. The rest need the
    /// data structures; reaching one throws by name rather than answering
    /// `false`, because a wrong `false` from `=` is the kind of bug that
    /// surfaces as a map lookup missing, six layers away.
    static bool Eq(Rt rt, long a, long b) {
        if (a == b) return true;
        if (Val.IsFixnum(a) && Val.IsFixnum(b)) return Val.AsFixnum(a) == Val.AsFixnum(b);
        if (Val.IsDouble(a) && Val.IsDouble(b)) return Val.AsDouble(a) == Val.AsDouble(b);
        if (Str.IsString(rt, a) && Str.IsString(rt, b)) {
            byte[] xa = Str.Bytes(rt, a), xb = Str.Bytes(rt, b);
            if (xa.Length != xb.Length) return false;
            for (int i = 0; i < xa.Length; i++) if (xa[i] != xb[i]) return false;
            return true;
        }
        bool ka = Val.IsInlineKw(a) || rt.IsHeapTy(a, Obj.TyKw);
        bool kb = Val.IsInlineKw(b) || rt.IsHeapTy(b, Obj.TyKw);
        if (ka && kb) {
            // Inline and heap keywords must compare EQUAL when they name the
            // same thing. They cannot here -- one is a value and one is an
            // object -- unless both are inline, which is why interning matters
            // and why this is refused rather than answered wrongly.
            if (Val.IsInlineKw(a) && Val.IsInlineKw(b)) return a == b;
            throw new System.NotSupportedException(
                "comparing a heap keyword needs the intern tables ported");
        }
        if (rt.IsHeapTy(a, Obj.TyVec) && rt.IsHeapTy(b, Obj.TyVec)) {
            int n = Vec.Count(rt, a);
            if (n != Vec.Count(rt, b)) return false;
            for (int i = 0; i < n; i++) {
                if (!Eq(rt, Vec.Nth(rt, a, i), Vec.Nth(rt, b, i))) return false;
            }
            return true;
        }
        if (Val.IsHeap(a) || Val.IsHeap(b)) {
            throw new System.NotSupportedException(
                "= on this collection needs more of the data structures ported");
        }
        return false;
    }

    /// A CHAIN, as Clojure's comparisons are: `(< 1 2 3)` is one call, not two.
    static long Cmp(Rt rt, int at, int n, int want, bool orEqual) {
        for (int i = 0; i + 1 < n; i++) {
            long a = Val.AsFixnum(rt.VAt(at + i)), b = Val.AsFixnum(rt.VAt(at + i + 1));
            int c = a.CompareTo(b);
            if (!(c == want || (orEqual && c == 0))) return Val.False;
        }
        return Val.True;
    }
}
