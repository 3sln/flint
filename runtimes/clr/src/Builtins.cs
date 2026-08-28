using System.Text;

namespace Flint;

/// The builtins a flint image imports, resolved BY NAME.
///
/// By name and not by slot, because an image's native slots belong to whichever
/// module it was linked against -- that is what makes one image runnable on
/// several hosts at all (`doc/decisions/0023`).
///
/// A builtin that is absent is ABSENT, and reaching it names it. A stub
/// returning nil would let a program get a wrong answer quietly here and the
/// right one elsewhere, which is the drift `bin/conform-hosts` exists to catch.
public static class Builtins {
    public delegate object Fn(Vm vm, object[] args);

    private static readonly Dictionary<string, Fn> Table = new();
    public static Fn ByName(string name) => name != null && Table.TryGetValue(name, out var f) ? f : null;
    private static void Def(string name, Fn f) => Table[name] = f;
    private static object Arg(object[] a, int i) => i < a.Length ? a[i] : null;

    public static bool Identical(object a, object b) {
        if (ReferenceEquals(a, b)) return true;
        // Boxed primitives: identity on a value type is not a useful question,
        // so equal ones count as identical, which is what a NaN-boxed runtime
        // gives for free.
        if (a is long x && b is long y) return x == y;
        if (a is bool p && b is bool q) return p == q;
        return false;
    }

    public static bool Eq(object a, object b) {
        if (ReferenceEquals(a, b)) return true;
        if (a == null || b == null) return false;
        if (a is long la && b is long lb) return la == lb;
        if (a is double da && b is double db) return da == db;
        if (a is bool ba && b is bool bb) return ba == bb;
        if (a is string sa && b is string sb) return sa == sb;
        if (a is FlintMap ma && b is FlintMap mb) {
            if (ma.Count != mb.Count) return false;
            foreach (var e in ma) {
                object other = mb.Get(e.Key, Sentinel);
                if (ReferenceEquals(other, Sentinel) || !Eq(e.Value, other)) return false;
            }
            return true;
        }
        if (a is FlintSet qa && b is FlintSet qb) {
            if (qa.Count != qb.Count) return false;
            foreach (var x in qa) if (!qb.Contains(x)) return false;
            return true;
        }
        if (a is System.Collections.IEnumerable ea && b is System.Collections.IEnumerable eb
            && a is not string && b is not string) {
            var i = ea.GetEnumerator(); var j = eb.GetEnumerator();
            while (true) {
                bool mi = i.MoveNext(), mj = j.MoveNext();
                if (mi != mj) return false;
                if (!mi) return true;
                if (!Eq(i.Current, j.Current)) return false;
            }
        }
        return a.Equals(b);
    }
    private static readonly object Sentinel = new();

    /// Everything flint can walk. `nil` is an empty sequence, which is
    /// Clojure's rule and one the core library leans on.
    public static IEnumerable<object> Iterate(object v) {
        switch (v) {
            case null: return Array.Empty<object>();
            case LazySeq ls: return ls.Force();
            case FlintMap m: {
                var outl = new List<object>();
                foreach (var e in m) outl.Add(new Vec(new[] { e.Key, e.Value }));
                return outl;
            }
            case string s: {
                // By CODE POINT, not by char. .NET is UTF-16 and flint is
                // UTF-8, and `count`/`nth` past the BMP is exactly where two
                // hosts silently disagree (`doc/decisions/0010`).
                var outl = new List<object>();
                for (int i = 0; i < s.Length; ) {
                    int cp = char.ConvertToUtf32(s, i);
                    outl.Add(char.ConvertFromUtf32(cp));
                    i += char.IsSurrogatePair(s, i) ? 2 : 1;
                }
                return outl;
            }
            case System.Collections.IEnumerable e: {
                var outl = new List<object>();
                foreach (var o in e) outl.Add(o);
                return outl;
            }
            default: throw new FlintThrow(PrStr(v) + " is not seqable");
        }
    }

    public static object Get(object coll, object key, object dflt) {
        switch (coll) {
            case null: return dflt;
            case FlintMap m: return m.Get(key, dflt);
            case FlintSet s: return s.Contains(key) ? key : dflt;
            case IReadOnlyList<object> l when key is long i:
                return i >= 0 && i < l.Count ? l[(int) i] : dflt;
            default: return dflt;
        }
    }

    public static string PrStr(object v) => Print(v, true);
    public static string Str(object v) => v == null ? "" : Print(v, false);

    private static string Print(object v, bool readable) {
        switch (v) {
            case null: return "nil";
            case bool b: return b ? "true" : "false";
            case string s:
                return readable ? "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"" : s;
            case double d:
                // A whole double prints with its `.0`. Float printing is a
                // classic silent divergence between hosts.
                return d == Math.Floor(d) && !double.IsInfinity(d)
                    ? d.ToString("0.0###############", System.Globalization.CultureInfo.InvariantCulture)
                    : d.ToString("R", System.Globalization.CultureInfo.InvariantCulture);
            case long l: return l.ToString(System.Globalization.CultureInfo.InvariantCulture);
            case FlintMap m: {
                var b2 = new StringBuilder("{");
                bool first = true;
                foreach (var e in m) {
                    if (!first) b2.Append(", ");
                    first = false;
                    b2.Append(Print(e.Key, readable)).Append(' ').Append(Print(e.Value, readable));
                }
                return b2.Append('}').ToString();
            }
            case FlintSet st: return Join(st, "#{", "}", readable);
            case LazySeq ls: return Join(ls.Force(), "(", ")", readable);
            case Seq q: return Join(q, "(", ")", readable);
            case Vec vv: return Join(vv, "[", "]", readable);
            default: return v.ToString();
        }
    }

    private static string Join(IEnumerable<object> c, string open, string close, bool readable) {
        var b = new StringBuilder(open);
        bool first = true;
        foreach (var o in c) {
            if (!first) b.Append(' ');
            first = false;
            b.Append(Print(o, readable));
        }
        return b.Append(close).ToString();
    }

    /// The codes `flint.types/code` assigns, which `runtime/src/vm.rs::type_p`
    /// dispatches on. Copied from that table rather than inferred -- inferring
    /// them is how the JVM port first went wrong, making `int?` false for every
    /// integer and every type annotation in the program vacuous.
    public static object IsType(object v, int code) => code switch {
        1 => v is long,
        2 => v is double,
        3 => v is long or double,
        4 => v is string,
        5 => v is Kw,
        6 => v is Sym,
        7 => v is bool,
        8 => v is Vec,
        9 => v is FlintMap,
        10 => v is FlintSet,
        11 => v is Seq or LazySeq or Vec or FlintSet or FlintMap or string,
        12 => v is Vm.Closure or Fn or Img.NativeRef,
        13 => v is null,
        _ => v is Vec or Seq or LazySeq,
    };

    private static List<object> Tail(object v) {
        var xs = new List<object>();
        bool skip = true;
        foreach (var o in Iterate(v)) { if (skip) { skip = false; continue; } xs.Add(o); }
        return xs;
    }

    private static double ToD(object o) => o is double d ? d : Vm.Num(o);

    private static object Arith(object[] a, char op) {
        if (a.Length == 0) return op == '*' ? 1L : 0L;
        bool anyDouble = false;
        foreach (var o in a) if (o is double) anyDouble = true;
        if (anyDouble) {
            double acc = ToD(a[0]);
            if (a.Length == 1 && op == '-') return -acc;
            for (int i = 1; i < a.Length; i++) {
                double x = ToD(a[i]);
                acc = op == '+' ? acc + x : op == '-' ? acc - x : acc * x;
            }
            return acc;
        }
        long l = Vm.Num(a[0]);
        if (a.Length == 1 && op == '-') return checked(-l);
        for (int i = 1; i < a.Length; i++) {
            long x = Vm.Num(a[i]);
            // Checked, because flint's integers throw on overflow and .NET's
            // wrap unless told otherwise.
            checked { l = op == '+' ? l + x : op == '-' ? l - x : l * x; }
        }
        return l;
    }

    private static object Compare(object[] a, char op) {
        for (int i = 0; i + 1 < a.Length; i++) {
            double x = ToD(a[i]), y = ToD(a[i + 1]);
            bool ok = op switch { '<' => x < y, 'l' => x <= y, '>' => x > y, _ => x >= y };
            if (!ok) return false;
        }
        return true;
    }

    public static int CompareValues(object x, object y) {
        if (x == null && y == null) return 0;
        if (x == null) return -1;
        if (y == null) return 1;
        if ((x is long or double) && (y is long or double)) return ToD(x).CompareTo(ToD(y));
        if (x is string sx && y is string sy) return string.CompareOrdinal(sx, sy);
        if (x is bool bx && y is bool by) return bx.CompareTo(by);
        if (x is Kw kx && y is Kw ky) return string.CompareOrdinal(kx.ToString(), ky.ToString());
        if (x is Sym mx && y is Sym my) return string.CompareOrdinal(mx.ToString(), my.ToString());
        throw new FlintThrow($"cannot compare {PrStr(x)} with {PrStr(y)}");
    }

    static Builtins() {
        Def("=", (vm, a) => { for (int i = 1; i < a.Length; i++) if (!Eq(a[0], a[i])) return false; return true; });
        Def("identical?", (vm, a) => Identical(Arg(a, 0), Arg(a, 1)));
        Def("not", (vm, a) => !Vm.Truthy(Arg(a, 0)));

        Def("+", (vm, a) => Arith(a, '+'));   Def("flint/add", (vm, a) => Arith(a, '+'));
        Def("-", (vm, a) => Arith(a, '-'));   Def("flint/sub", (vm, a) => Arith(a, '-'));
        Def("*", (vm, a) => Arith(a, '*'));   Def("flint/mul", (vm, a) => Arith(a, '*'));
        Def("<", (vm, a) => Compare(a, '<')); Def("flint/lt", (vm, a) => Compare(a, '<'));
        Def("<=", (vm, a) => Compare(a, 'l')); Def("flint/le", (vm, a) => Compare(a, 'l'));
        Def(">", (vm, a) => Compare(a, '>')); Def("flint/gt", (vm, a) => Compare(a, '>'));
        Def(">=", (vm, a) => Compare(a, 'g')); Def("flint/ge", (vm, a) => Compare(a, 'g'));
        Def("inc", (vm, a) => checked(Vm.Num(Arg(a, 0)) + 1));
        Def("dec", (vm, a) => checked(Vm.Num(Arg(a, 0)) - 1));
        Def("quot", (vm, a) => { long y = Vm.Num(Arg(a, 1)); if (y == 0) throw new FlintThrow("divide by zero"); return Vm.Num(Arg(a, 0)) / y; });
        Def("rem", (vm, a) => { long y = Vm.Num(Arg(a, 1)); if (y == 0) throw new FlintThrow("divide by zero"); return Vm.Num(Arg(a, 0)) % y; });
        Def("flint/num-eq", (vm, a) => Arg(a, 0) is long i && Arg(a, 1) is long j ? i == j : ToD(Arg(a, 0)) == ToD(Arg(a, 1)));
        Def("flint/div", (vm, a) => {
            object x = Arg(a, 0), y = Arg(a, 1);
            if (x is long i && y is long j) {
                if (j == 0) throw new FlintThrow("divide by zero");
                // Integer division that does not divide exactly gives a double,
                // as in flint. Truncating here would be a wrong ANSWER on this
                // host and a right one elsewhere.
                return i % j == 0 ? i / j : (object) ((double) i / j);
            }
            double d = ToD(y);
            if (d == 0.0) throw new FlintThrow("divide by zero");
            return ToD(x) / d;
        });

        Def("str", (vm, a) => { var b = new StringBuilder(); foreach (var o in a) b.Append(Str(o)); return b.ToString(); });
        Def("flint/str2", (vm, a) => Str(Arg(a, 0)) + Str(Arg(a, 1)));
        Def("flint/num->str", (vm, a) => Str(Arg(a, 0)));
        Def("pr-str", (vm, a) => {
            var b = new StringBuilder();
            for (int i = 0; i < a.Length; i++) { if (i > 0) b.Append(' '); b.Append(PrStr(a[i])); }
            return b.ToString();
        });

        Def("count", (vm, a) => {
            switch (Arg(a, 0)) {
                case null: return 0L;
                case string s: {
                    long n = 0;
                    for (int i = 0; i < s.Length; i += char.IsSurrogatePair(s, i) ? 2 : 1) n++;
                    return n;
                }
                case FlintMap m: return (long) m.Count;
                case FlintSet st: return (long) st.Count;
                case IReadOnlyList<object> l: return (long) l.Count;
                default: throw new FlintThrow(PrStr(Arg(a, 0)) + " cannot be counted");
            }
        });
        Def("get", (vm, a) => Get(Arg(a, 0), Arg(a, 1), Arg(a, 2)));
        Def("nth", (vm, a) => {
            var xs = new List<object>(Iterate(Arg(a, 0)));
            int i = (int) Vm.Num(Arg(a, 1));
            if (i < 0 || i >= xs.Count) {
                if (a.Length > 2) return a[2];
                throw new FlintThrow($"index {i} out of bounds");
            }
            return xs[i];
        });
        Def("conj", (vm, a) => {
            object coll = Arg(a, 0);
            if (coll is FlintSet s) { for (int i = 1; i < a.Length; i++) s = s.Conj(a[i]); return s; }
            var v = coll switch {
                null => new Vec(), Vec vv => vv, Seq q => new Vec(q),
                _ => throw new FlintThrow("cannot conj onto " + PrStr(coll)),
            };
            for (int i = 1; i < a.Length; i++) v = v.Conj(a[i]);
            return v;
        });
        Def("assoc", (vm, a) => {
            var m = Arg(a, 0) as FlintMap ?? FlintMap.Empty;
            for (int i = 1; i + 1 < a.Length; i += 2) m = m.Assoc(a[i], a[i + 1]);
            return m;
        });
        Def("dissoc", (vm, a) => {
            var m = Arg(a, 0) as FlintMap ?? FlintMap.Empty;
            for (int i = 1; i < a.Length; i++) m = m.Dissoc(a[i]);
            return m;
        });
        Def("disj", (vm, a) => {
            var s = Arg(a, 0) as FlintSet ?? new FlintSet();
            for (int i = 1; i < a.Length; i++) s = s.Disj(a[i]);
            return s;
        });
        Def("contains?", (vm, a) => Arg(a, 0) switch {
            FlintMap m => m.ContainsKey(Arg(a, 1)),
            FlintSet s => s.Contains(Arg(a, 1)),
            IReadOnlyList<object> l when Arg(a, 1) is long i => i >= 0 && i < l.Count,
            _ => false,
        });
        Def("empty", (vm, a) => Arg(a, 0) switch {
            FlintMap => FlintMap.Empty, FlintSet => new FlintSet(),
            Seq => new Seq(new List<object>()), _ => new Vec(),
        });
        Def("peek", (vm, a) => Arg(a, 0) switch {
            Seq q => q.Count == 0 ? null : q[0],
            IReadOnlyList<object> l => l.Count == 0 ? null : l[l.Count - 1],
            _ => null,
        });
        Def("pop", (vm, a) => {
            switch (Arg(a, 0)) {
                case Seq q: return new Seq(Tail(q));
                case Vec v when v.Count > 0: {
                    var xs = new List<object>(v); xs.RemoveAt(xs.Count - 1); return new Vec(xs);
                }
                default: throw new FlintThrow("cannot pop " + PrStr(Arg(a, 0)));
            }
        });
        Def("seq", (vm, a) => { var xs = new List<object>(Iterate(Arg(a, 0))); return xs.Count == 0 ? null : new Seq(xs); });
        Def("first", (vm, a) => { foreach (var o in Iterate(Arg(a, 0))) return o; return null; });
        Def("rest", (vm, a) => new Seq(Tail(Arg(a, 0))));
        Def("next", (vm, a) => { var xs = Tail(Arg(a, 0)); return xs.Count == 0 ? null : new Seq(xs); });
        Def("cons", (vm, a) => { var xs = new List<object> { Arg(a, 0) }; xs.AddRange(Iterate(Arg(a, 1))); return new Seq(xs); });
        Def("hash", (vm, a) => (long) Hash.Of(Arg(a, 0)));
        Def("compare", (vm, a) => (long) CompareValues(Arg(a, 0), Arg(a, 1)));

        Def("name", (vm, a) => Arg(a, 0) switch {
            Kw k => k.Name, Sym s => s.Name, string s2 => s2,
            _ => throw new FlintThrow("cannot take the name of " + PrStr(Arg(a, 0))),
        });
        Def("namespace", (vm, a) => Arg(a, 0) switch { Kw k => k.Ns, Sym s => s.Ns, _ => null });
        // The ARITY decides which argument is which: one is the name, two are
        // (ns, name). Reading argument 0 as the namespace regardless gives a
        // keyword with an empty name -- it prints almost right and matches
        // nothing, which is how the JVM port first got this wrong.
        Def("flint/keyword2", (vm, a) => a.Length == 1
            ? Kw.Of(null, Str(a[0]))
            : Kw.Of(Arg(a, 0) == null ? null : Str(Arg(a, 0)), Str(Arg(a, 1))));
        Def("flint/symbol2", (vm, a) => a.Length == 1
            ? Sym.Of(null, Str(a[0]))
            : Sym.Of(Arg(a, 0) == null ? null : Str(Arg(a, 0)), Str(Arg(a, 1))));

        Def("atom", (vm, a) => new Atom(Arg(a, 0)));
        Def("deref", (vm, a) => Arg(a, 0) is Atom at ? at.Deref() : throw new FlintThrow("cannot deref " + PrStr(Arg(a, 0))));
        Def("reset!", (vm, a) => Arg(a, 0) is Atom at ? at.Reset(Arg(a, 1)) : throw new FlintThrow("cannot reset! " + PrStr(Arg(a, 0))));
        Def("compare-and-set!", (vm, a) => Arg(a, 0) is Atom at
            ? at.CompareAndSet(Arg(a, 1), Arg(a, 2))
            : throw new FlintThrow("compare-and-set! wants an atom"));

        Def("transient", (vm, a) => Transient.Of(Arg(a, 0)));
        Def("persistent!", (vm, a) => Arg(a, 0) is Transient t ? t.Persistent() : throw new FlintThrow("persistent! wants a transient"));
        Def("conj!", (vm, a) => {
            if (Arg(a, 0) is Transient t && t.List != null) { for (int i = 1; i < a.Length; i++) t.List.Add(a[i]); return t; }
            throw new FlintThrow("conj! wants a transient collection");
        });
        Def("assoc!", (vm, a) => {
            if (Arg(a, 0) is Transient t && t.Map != null) {
                for (int i = 1; i + 1 < a.Length; i += 2)
                    t.Map.Add(new KeyValuePair<object, object>(a[i], a[i + 1]));
                return t;
            }
            throw new FlintThrow("assoc! wants a transient map");
        });

        Def("flint/lazy-seq", (vm, a) => new LazySeq(vm, Arg(a, 0)));
        Def("flint/range3", (vm, a) => {
            long start = Vm.Num(Arg(a, 0)), end = Vm.Num(Arg(a, 1)), step = Vm.Num(Arg(a, 2));
            if (step == 0) throw new FlintThrow("range step of zero");
            var xs = new List<object>();
            for (long i = start; step > 0 ? i < end : i > end; i += step) xs.Add(i);
            return new Vec(xs);
        });
        Def("apply", (vm, a) => {
            var all = new List<object>();
            for (int i = 1; i < a.Length - 1; i++) all.Add(a[i]);
            all.AddRange(Iterate(a[a.Length - 1]));
            return vm.Call(a[0], all.ToArray());
        });
        Def("flint/apply", (vm, a) => {
            var all = new List<object>();
            for (int i = 1; i < a.Length - 1; i++) all.Add(a[i]);
            all.AddRange(Iterate(a[a.Length - 1]));
            return vm.Call(a[0], all.ToArray());
        });
        Def("throw", (vm, a) => throw new FlintThrow(Arg(a, 0)));
        Def("ex-info", (vm, a) => FlintMap.Empty
            .Assoc(Kw.Of(null, "message"), Arg(a, 0))
            .Assoc(Kw.Of(null, "data"), Arg(a, 1) ?? FlintMap.Empty));
        Def("ex-message", (vm, a) => Get(Arg(a, 0), Kw.Of(null, "message"), null));
        Def("ex-data", (vm, a) => Get(Arg(a, 0), Kw.Of(null, "data"), null));
        Def("flint/ex-kind", (vm, a) => Get(Arg(a, 0), Kw.Of(null, "kind"), null));

        // Carried, not interpreted. Returning something plausible would be
        // inventing semantics; these are the honest answers until they are
        // ported.
        Def("meta", (vm, a) => null);
        Def("flint/opaque?", (vm, a) => false);
        Def("flint/opaque-label", (vm, a) => null);
    }
}
