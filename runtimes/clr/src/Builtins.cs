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
        // Cons cells and lazy seqs are walked ITERATIVELY. Recursing here is
        // what overflows the stack: a lazy chain is as deep as it is long.
        if (v is Cons || v is LazySeq) {
            var outl = new List<object>();
            object cur = v;
            while (true) {
                if (cur == null) return outl;
                if (cur is LazySeq l) { cur = l.Step(); continue; }
                if (cur is Cons c) { outl.Add(c.Head); cur = c.Tail; continue; }
                foreach (var o in Iterate(cur)) outl.Add(o);
                return outl;
            }
        }
        switch (v) {
            case null: return Array.Empty<object>();
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
            case LazySeq: case Cons: return Join(Iterate(v), "(", ")", readable);
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
        11 => v is Seq or LazySeq or Cons or Vec or FlintSet or FlintMap or string,
        12 => v is Vm.Closure or Fn or Img.NativeRef,
        13 => v is null,
        _ => v is Vec or Seq or LazySeq or Cons,
    };

    /// The first element, without walking what follows.
    internal static object FirstOf(object v) {
        object cur = v;
        while (true) {
            if (cur == null) return null;
            if (cur is LazySeq l) { cur = l.Step(); continue; }
            if (cur is Cons c) return c.Head;
            foreach (var o in Iterate(cur)) return o;
            return null;
        }
    }

    /// Everything after the first element, without forcing what follows.
    internal static object RestOf(object v) {
        object cur = v;
        while (true) {
            if (cur == null) return new Seq(new List<object>());
            if (cur is LazySeq l) { cur = l.Step(); continue; }
            if (cur is Cons c) return c.Tail ?? new Seq(new List<object>());
            return new Seq(Tail(cur));
        }
    }

    /// Is there a first element? Steps at most one lazy cell.
    internal static bool IsEmptySeq(object v) {
        object cur = v;
        while (true) {
            if (cur == null) return true;
            if (cur is LazySeq l) { cur = l.Step(); continue; }
            if (cur is Cons) return false;
            if (cur is FlintMap m) return m.Count == 0;
            if (cur is FlintSet st) return st.Count == 0;
            if (cur is IReadOnlyList<object> lst) return lst.Count == 0;
            if (cur is string s) return s.Length == 0;
            return false;
        }
    }

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
            if (coll is FlintMap fm) {
                for (int i = 1; i < a.Length; i++) {
                    var pair = new List<object>(Iterate(a[i]));
                    if (pair.Count != 2)
                        throw new FlintThrow("conj onto a map wants a [k v] pair, got " + PrStr(a[i]));
                    fm = fm.Assoc(pair[0], pair[1]);
                }
                return fm;
            }
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
        // `seq` answers the SAME sequence or nil -- it does not realise it,
        // because an infinite one cannot be realised.
        Def("seq", (vm, a) => IsEmptySeq(Arg(a, 0)) ? null : Arg(a, 0));
        Def("first", (vm, a) => FirstOf(Arg(a, 0)));
        // The tail, UNFORCED.
        Def("rest", (vm, a) => RestOf(Arg(a, 0)));
        // `rest`, then ONE step to see whether anything is there.
        Def("next", (vm, a) => { var r = RestOf(Arg(a, 0)); return IsEmptySeq(r) ? null : r; });
        Def("cons", (vm, a) => new Cons(Arg(a, 0), Arg(a, 1)));
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
        Def("deref", (vm, a) => Arg(a, 0) switch {
            Atom at => at.Deref(),
            Volatile vo => vo.Deref(),
            LazySeq ls => ls.Force(),
            _ => throw new FlintThrow("cannot deref " + PrStr(Arg(a, 0))),
        });
        Def("reset!", (vm, a) => Arg(a, 0) switch {
            Atom at => at.Reset(Arg(a, 1)),
            // `vreset!` is `reset!` in core.cljc, not a builtin of its own, so
            // this is the only place a volatile can be written.
            Volatile vo => vo.Reset(Arg(a, 1)),
            var v => throw new FlintThrow("cannot reset! " + PrStr(v)),
        });
        Def("compare-and-set!", (vm, a) => Arg(a, 0) is Atom at
            ? at.CompareAndSet(Arg(a, 1), Arg(a, 2))
            : throw new FlintThrow("compare-and-set! wants an atom"));

        Def("transient", (vm, a) => Transient.Of(Arg(a, 0)));
        Def("persistent!", (vm, a) => Arg(a, 0) is Transient t ? t.Persistent() : throw new FlintThrow("persistent! wants a transient"));
        Def("conj!", (vm, a) => {
            if (Arg(a, 0) is Transient t) {
                if (t.List != null) { for (int i = 1; i < a.Length; i++) t.List.Add(a[i]); return t; }
                if (t.Map != null) {
                    for (int i = 1; i < a.Length; i++) {
                        var pair = new List<object>(Iterate(a[i]));
                        if (pair.Count != 2)
                            throw new FlintThrow("conj! onto a map wants a [k v] pair");
                        t.Map.Add(new KeyValuePair<object, object>(pair[0], pair[1]));
                    }
                    return t;
                }
            }
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

        // ONE argument: a flat sequence of key, value, key, value. Reading it
        // as varargs makes every map literal come back EMPTY.
        Def("flint/array-map", (vm, a) => {
            var m = FlintMap.Empty;
            object k = null; bool haveKey = false;
            foreach (var o in Iterate(Arg(a, 0))) {
                if (haveKey) { m = m.Assoc(k, o); haveKey = false; }
                else { k = o; haveKey = true; }
            }
            return m;
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

        // --- maths ------------------------------------------------------------
        //
        // Every one of these takes and returns a double, as flint's do. Passing
        // an integer through `ToD` and back is what makes `(Math/sqrt 4)`
        // answer 2.0 rather than 2, which is the answer Clojure gives.
        Def("flint/sqrt",  (vm, a) => Math.Sqrt(ToD(Arg(a, 0))));
        Def("flint/cbrt",  (vm, a) => Math.Cbrt(ToD(Arg(a, 0))));
        Def("flint/exp",   (vm, a) => Math.Exp(ToD(Arg(a, 0))));
        Def("flint/expm1", (vm, a) => Math.Exp(ToD(Arg(a, 0))) - 1.0);
        Def("flint/log",   (vm, a) => Math.Log(ToD(Arg(a, 0))));
        Def("flint/log10", (vm, a) => Math.Log10(ToD(Arg(a, 0))));
        Def("flint/log1p", (vm, a) => Math.Log(1.0 + ToD(Arg(a, 0))));
        Def("flint/sin",   (vm, a) => Math.Sin(ToD(Arg(a, 0))));
        Def("flint/cos",   (vm, a) => Math.Cos(ToD(Arg(a, 0))));
        Def("flint/tan",   (vm, a) => Math.Tan(ToD(Arg(a, 0))));
        Def("flint/asin",  (vm, a) => Math.Asin(ToD(Arg(a, 0))));
        Def("flint/acos",  (vm, a) => Math.Acos(ToD(Arg(a, 0))));
        Def("flint/atan",  (vm, a) => Math.Atan(ToD(Arg(a, 0))));
        Def("flint/sinh",  (vm, a) => Math.Sinh(ToD(Arg(a, 0))));
        Def("flint/cosh",  (vm, a) => Math.Cosh(ToD(Arg(a, 0))));
        Def("flint/tanh",  (vm, a) => Math.Tanh(ToD(Arg(a, 0))));
        Def("flint/floor", (vm, a) => Math.Floor(ToD(Arg(a, 0))));
        Def("flint/ceil",  (vm, a) => Math.Ceiling(ToD(Arg(a, 0))));
        // Half-to-EVEN, which is what Java's `rint` and IEEE 754 both mean.
        // `Math.Round`'s default is the same; saying so explicitly is what
        // keeps it from drifting if the default ever is not.
        Def("flint/rint",  (vm, a) => Math.Round(ToD(Arg(a, 0)), MidpointRounding.ToEven));
        Def("flint/trunc", (vm, a) => (double) (long) ToD(Arg(a, 0)));
        Def("flint/pow",   (vm, a) => Math.Pow(ToD(Arg(a, 0)), ToD(Arg(a, 1))));
        Def("flint/atan2", (vm, a) => Math.Atan2(ToD(Arg(a, 0)), ToD(Arg(a, 1))));
        Def("flint/hypot", (vm, a) => {
            // Not `sqrt(x*x + y*y)`: that overflows for large operands and
            // underflows for small ones, which is the whole reason `hypot`
            // exists as its own function.
            double x = Math.Abs(ToD(Arg(a, 0))), y = Math.Abs(ToD(Arg(a, 1)));
            if (double.IsInfinity(x) || double.IsInfinity(y)) return double.PositiveInfinity;
            double hi = Math.Max(x, y), lo = Math.Min(x, y);
            if (hi == 0.0) return 0.0;
            double r = lo / hi;
            return hi * Math.Sqrt(1.0 + r * r);
        });
        Def("flint/signum", (vm, a) => {
            double d = ToD(Arg(a, 0));
            return double.IsNaN(d) ? double.NaN : (double) Math.Sign(d);
        });
        Def("flint/fabs", (vm, a) => Math.Abs(ToD(Arg(a, 0))));
        Def("flint/copy-sign", (vm, a) => Math.CopySign(ToD(Arg(a, 0)), ToD(Arg(a, 1))));
        Def("flint/double-bits", (vm, a) => BitConverter.DoubleToInt64Bits(ToD(Arg(a, 0))));
        Def("flint/bits->double", (vm, a) => BitConverter.Int64BitsToDouble(Vm.Num(Arg(a, 0))));

        // Wrapping rather than throwing, which is the whole point of them.
        Def("flint/unchecked-add", (vm, a) => unchecked(Vm.Num(Arg(a, 0)) + Vm.Num(Arg(a, 1))));
        Def("flint/unchecked-sub", (vm, a) => unchecked(Vm.Num(Arg(a, 0)) - Vm.Num(Arg(a, 1))));
        Def("flint/unchecked-mul", (vm, a) => unchecked(Vm.Num(Arg(a, 0)) * Vm.Num(Arg(a, 1))));

        // --- strings, by CODE POINT ---------------------------------------
        //
        // The CLR is UTF-16 and flint is UTF-8, so `subs` and indexing on
        // anything past the BMP is exactly where two hosts silently disagree
        // (`doc/decisions/0010`). Counting and slicing by code point is what
        // makes them agree; counting by `string.Length` would not.
        Def("flint/subs", (vm, a) => {
            string s0 = Str(Arg(a, 0));
            int n = CodePointCount(s0);
            int from = (int) Vm.Num(Arg(a, 1));
            int to = a.Length > 2 ? (int) Vm.Num(a[2]) : n;
            if (from < 0 || to > n || from > to)
                throw new FlintThrow($"substring [{from} {to}) out of {n}");
            int bi = OffsetByCodePoints(s0, from), ei = OffsetByCodePoints(s0, to);
            return s0.Substring(bi, ei - bi);
        });
        Def("flint/code-point-at", (vm, a) => {
            string s0 = Str(Arg(a, 0));
            int i = (int) Vm.Num(Arg(a, 1));
            int n = CodePointCount(s0);
            if (i < 0 || i >= n) throw new FlintThrow($"index {i} out of {n}");
            return (long) char.ConvertToUtf32(s0, OffsetByCodePoints(s0, i));
        });
        Def("flint/from-code-point", (vm, a) => char.ConvertFromUtf32((int) Vm.Num(Arg(a, 0))));
        Def("flint/str-join", (vm, a) => {
            var b = new StringBuilder();
            foreach (var o in Iterate(Arg(a, 0))) b.Append(Str(o));
            return b.ToString();
        });
        Def("flint/str-index-of", (vm, a) => {
            string s0 = Str(Arg(a, 0)), needle = Str(Arg(a, 1));
            int from = a.Length > 2 ? (int) Vm.Num(a[2]) : 0;
            int bi = from <= 0 ? 0 : OffsetByCodePoints(s0, Math.Min(from, CodePointCount(s0)));
            // Ordinal: a linguistic comparison would find "a" inside "A" under
            // some cultures, and flint's answer does not depend on a locale.
            int at = s0.IndexOf(needle, bi, StringComparison.Ordinal);
            return at < 0 ? null : (object) (long) CodePointCount(s0.Substring(0, at));
        });
        Def("flint/upper-case", (vm, a) => Str(Arg(a, 0)).ToUpperInvariant());
        Def("flint/lower-case", (vm, a) => Str(Arg(a, 0)).ToLowerInvariant());
        Def("flint/str->num", (vm, a) => {
            string s0 = Str(Arg(a, 0)).Trim();
            if (s0.Contains('.') || s0.Contains('e') || s0.Contains('E'))
                return double.TryParse(s0, System.Globalization.NumberStyles.Float,
                                       System.Globalization.CultureInfo.InvariantCulture,
                                       out double d) ? d : null;
            return long.TryParse(s0, System.Globalization.NumberStyles.Integer,
                                 System.Globalization.CultureInfo.InvariantCulture,
                                 out long l) ? l : (object) null;
        });
        Def("flint/to-long", (vm, a) => Arg(a, 0) switch {
            long l => l,
            double d => (long) d,
            var v => throw new FlintThrow(PrStr(v) + " is not a number"),
        });
        Def("flint/str-bytes", (vm, a) => (long) new UTF8Encoding(false).GetByteCount(Str(Arg(a, 0))));
        Def("flint/bytes->str", (vm, a) => {
            var t = new List<byte>();
            foreach (var o in Iterate(Arg(a, 0))) t.Add((byte) Vm.Num(o));
            return new UTF8Encoding(false).GetString(t.ToArray());
        });

        // --- byte strings (`doc/decisions/0024`) ---------------------------
        Def("flint/str->b", (vm, a) => Bytes.Of(Str(Arg(a, 0))));
        Def("flint/b->str", (vm, a) => AsBytes(Arg(a, 0)).Text());
        Def("flint/b-count", (vm, a) => (long) AsBytes(Arg(a, 0)).Count);
        Def("flint/b-at", (vm, a) => AsBytes(Arg(a, 0)).At((int) Vm.Num(Arg(a, 1))));
        Def("flint/b-concat", (vm, a) => AsBytes(Arg(a, 0)).Concat(AsBytes(Arg(a, 1))));
        Def("flint/b-slice", (vm, a) =>
            AsBytes(Arg(a, 0)).Slice((int) Vm.Num(Arg(a, 1)), (int) Vm.Num(Arg(a, 2))));
        Def("flint/b->vec", (vm, a) => AsBytes(Arg(a, 0)).ToVec());
        Def("flint/vec->b", (vm, a) => {
            var t = new Bytes.T();
            foreach (var o in Iterate(Arg(a, 0))) t.Conj(Vm.Num(o));
            return t.Persistent();
        });
        // Always 0: this representation is flat, so it IS depth zero. The
        // builtin exists to observe flint's rope shape, and answering
        // something plausible instead would be inventing one.
        Def("flint/b-depth", (vm, a) => 0L);
        Def("flint/b-transient", (vm, a) => {
            var t = new Bytes.T();
            if (Arg(a, 0) != null) t.Append(AsBytes(Arg(a, 0)));
            return t;
        });
        Def("flint/b-conj!", (vm, a) => Arg(a, 0) is Bytes.T t
            ? Also(t, () => t.Conj(Vm.Num(Arg(a, 1))))
            : throw new FlintThrow("b-conj! wants a byte transient"));
        Def("flint/b-append!", (vm, a) => Arg(a, 0) is Bytes.T t
            ? Also(t, () => t.Append(AsBytes(Arg(a, 1))))
            : throw new FlintThrow("b-append! wants a byte transient"));
        Def("flint/b-tcount", (vm, a) => Arg(a, 0) is Bytes.T t
            ? (long) t.Count
            : throw new FlintThrow("b-tcount wants a byte transient"));
        Def("flint/b-persistent!", (vm, a) => Arg(a, 0) is Bytes.T t
            ? t.Persistent()
            : throw new FlintThrow("b-persistent! wants a byte transient"));

        // --- volatiles and delays ------------------------------------------
        Def("flint/volatile", (vm, a) => new Volatile(Arg(a, 0)));
        Def("flint/volatile?", (vm, a) => Arg(a, 0) is Volatile);
        // A delay IS a one-shot thunk, which is what LazySeq already is here.
        Def("flint/delay", (vm, a) => new LazySeq(vm, Arg(a, 0)));
        Def("flint/delay?", (vm, a) => Arg(a, 0) is LazySeq);
        // TRUE, always: this port forces on deref rather than tracking the
        // state, so the honest answer to "would deref block" is no.
        Def("flint/realized?", (vm, a) => true);

        // --- the type barrier ----------------------------------------------
        //
        // `check-tag` is what an `^int` annotation compiles to. It returns the
        // value so it can be used as an expression, and throws with the name
        // the author wrote so the error lands at the annotation rather than
        // several frames inside the number tower.
        Def("flint/check-tag", (vm, a) => {
            object v = Arg(a, 0);
            long want = Vm.Num(Arg(a, 1));
            if (!(bool) IsType(v, (int) want)) {
                throw new FlintThrow($"{Str(Arg(a, 2))} is not {TypeName(want)}: {PrStr(v)}");
            }
            return v;
        });
        Def("flint/kind", (vm, a) => Kw.Of(null, KindOf(Arg(a, 0))));

        // --- dynamic bindings ----------------------------------------------
        //
        // Per THREAD. flint's are per green thread, saved and restored by its
        // scheduler across a park; this port has no green threads, so a real
        // OS thread is the unit and a [ThreadStatic] map is the whole
        // mechanism. `binding` is a stack discipline either way -- the
        // compiled code reads the map, pushes a new one, and puts the old one
        // back in a finally -- so the port only has to hold the current map.
        //
        // A spawned thread starts EMPTY rather than inheriting, which is where
        // this differs from flint: there a spawn takes a snapshot. Threads on
        // this port are created by the host, not by flint, so there is no
        // spawn site at which to take one.
        Def("flint/dyn-get", (vm, a) =>
            DynBindings == null ? Arg(a, 1) : Get(DynBindings, Arg(a, 0), Arg(a, 1)));
        Def("flint/dyn-bindings", (vm, a) => DynBindings ?? (object) FlintMap.Empty);
        Def("flint/dyn-set-bindings", (vm, a) => DynBindings = Arg(a, 0));

        // The class hierarchy flint reports without having one: `Throwable`
        // catches everything, `Error` catches what is named `...Error`, and
        // `Exception` catches the rest. Copied from
        // `runtime/src/err.rs::ex_matches` -- guessing here would make a
        // `(catch Exception ...)` silently swallow an Error, or not catch at
        // all.
        Def("flint/ex-matches?", (vm, a) => {
            string k = Str(Get(Arg(a, 0), Kw.Of(null, "kind"), null));
            string want = Str(Arg(a, 1));
            bool isError = k.EndsWith("Error", StringComparison.Ordinal);
            return want switch {
                "Throwable" => true,
                "Exception" or "RuntimeException" => !isError,
                "Error" => isError,
                _ => k == want,
            };
        });

        // Carried, not interpreted. Returning something plausible would be
        // inventing semantics; these are the honest answers until they are
        // ported.
        Def("meta", (vm, a) => null);
        Def("flint/opaque", (vm, a) => Arg(a, 0));
        Def("flint/opaque?", (vm, a) => false);
        Def("flint/opaque-label", (vm, a) => null);
        Def("flint/map-entry?", (vm, a) => false);
        // No capabilities are granted to a conformance run, and no collector
        // statistics exist for a host with its own GC.
        Def("flint/capabilities", (vm, a) => FlintMap.Empty);
        Def("flint/gc-stats", (vm, a) => FlintMap.Empty);
    }

    /// The dynamic bindings in force on THIS thread. See `flint/dyn-get`.
    [ThreadStatic] private static object DynBindings;

    /// The name an `^int` annotation reports itself by. Copied from
    /// `runtime/src/builtins.rs::b_check_tag`, so the two messages match word
    /// for word -- a conformance run diffs the TEXT of an error, not its kind.
    private static string TypeName(long code) => code switch {
        1 => "int", 2 => "float", 3 => "number", 4 => "string",
        5 => "keyword", 6 => "symbol", 7 => "boolean", 8 => "vector",
        9 => "map", 10 => "set", 11 => "seq", 12 => "fn", 13 => "nil",
        _ => "sequential",
    };

    /// The closed set protocol dispatch runs on (`doc/decisions/0005`), copied
    /// from `runtime/src/builtins.rs::b_kind`.
    ///
    /// Note "list", not "seq": a cons and a lazy seq answer `:list` while a
    /// VECTOR answers `:vector`, even though both are sequential. And a byte
    /// string is "other" here, as it is there -- `kind` names what a protocol
    /// may be extended over, and inventing a name would extend that set.
    private static string KindOf(object v) => v switch {
        null => "nil",
        bool => "boolean",
        long or double => "number",
        string => "string",
        Kw => "keyword",
        Sym => "symbol",
        Vec => "vector",
        FlintMap => "map",
        FlintSet => "set",
        Seq or Cons or LazySeq => "list",
        Vm.Closure or Fn or Img.NativeRef => "fn",
        Atom => "atom",
        _ => "other",
    };

    /// Run `f` for its effect and answer `v`. C# expression lambdas cannot hold
    /// a statement, and a block lambda would need a type annotation at every
    /// one of these sites.
    private static object Also(object v, Action f) { f(); return v; }

    private static Bytes AsBytes(object v) =>
        v as Bytes ?? throw new FlintThrow(PrStr(v) + " is not a byte string");

    /// Code points, not UTF-16 units. A surrogate pair is ONE.
    private static int CodePointCount(string s) {
        int n = 0;
        for (int i = 0; i < s.Length; i++) {
            if (char.IsHighSurrogate(s[i]) && i + 1 < s.Length && char.IsLowSurrogate(s[i + 1])) i++;
            n++;
        }
        return n;
    }

    /// The UTF-16 index `n` code points into `s`.
    private static int OffsetByCodePoints(string s, int n) {
        int i = 0;
        while (n > 0 && i < s.Length) {
            if (char.IsHighSurrogate(s[i]) && i + 1 < s.Length && char.IsLowSurrogate(s[i + 1])) i++;
            i++; n--;
        }
        return i;
    }
}
