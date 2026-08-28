package com.flint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/// The builtins a flint image imports, resolved BY NAME.
///
/// By name and not by slot, because an image's native slots belong to whichever
/// module it was linked against -- that is what makes one image runnable on
/// several hosts at all (`doc/decisions/0023`).
///
/// This is a SUBSET, and deliberately an honest one: a builtin that is absent
/// is absent, and reaching it names it. The alternative -- a stub returning
/// nil -- would let a program get a wrong answer quietly on this host and the
/// right one elsewhere, which is exactly the drift `doc/decisions/0010` says
/// the conformance suite exists to catch.
public final class Builtins {
    private Builtins() {}

    @FunctionalInterface
    public interface Fn {
        Object apply(Vm vm, Object[] args);
    }

    private static final Map<String, Fn> TABLE = new LinkedHashMap<>();

    public static Fn byName(String name) { return TABLE.get(name); }
    public static java.util.Set<String> names() { return TABLE.keySet(); }

    private static void def(String name, Fn f) { TABLE.put(name, f); }

    private static Object arg(Object[] a, int i) { return i < a.length ? a[i] : null; }

    /// Everything after the first element.
    private static List<Object> tail(Object v) {
        List<Object> xs = new ArrayList<>();
        boolean skip = true;
        for (Object o : iterate(v)) { if (skip) { skip = false; continue; } xs.add(o); }
        return xs;
    }

    // --- equality and hashing ----------------------------------------------

    /// Value equality across the shapes flint uses.
    ///
    /// Numbers compare by VALUE within the integer tower, so `(= 1 1)` holds
    /// whichever way each side was produced -- but an integer and a double are
    /// not `=`, matching flint rather than Java's `Long.equals(Double)` which
    /// is false for a different reason and would agree by accident.
    public static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Long && b instanceof Long) return ((Long) a).longValue() == (Long) b;
        if (a instanceof Double && b instanceof Double) return ((Double) a).doubleValue() == (Double) b;
        if (a instanceof Collection<?> ca && b instanceof Collection<?> cb) {
            if (ca.size() != cb.size()) return false;
            if (a instanceof java.util.Set && b instanceof java.util.Set) {
                for (Object x : ca) if (!containsValue(cb, x)) return false;
                return true;
            }
            var i = ca.iterator(); var j = cb.iterator();
            while (i.hasNext()) if (!eq(i.next(), j.next())) return false;
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) return false;
            for (var e : ma.entrySet()) {
                Object other = mb.get(e.getKey());
                if (other == null && !mb.containsKey(e.getKey())) return false;
                if (!eq(e.getValue(), other)) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean containsValue(Collection<?> c, Object x) {
        for (Object y : c) if (eq(x, y)) return true;
        return false;
    }

    // --- sequences ----------------------------------------------------------

    /// Everything flint can walk, as a Java iterable. `nil` is an empty
    /// sequence, which is Clojure's rule and one the core library leans on.
    public static Iterable<Object> iterate(Object v) {
        if (v == null) return List.of();
        if (v instanceof LazySeq ls) return ls.force();
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(o);
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            List<Object> out = new ArrayList<>(m.size());
            for (var e : m.entrySet()) out.add(List.of(e.getKey(), e.getValue()));
            return out;
        }
        if (v instanceof String s) {
            List<Object> out = new ArrayList<>(s.length());
            // By CODE POINT, not by char. The JVM is UTF-16 and flint is
            // UTF-8, and `count`/`nth` on anything outside the BMP is exactly
            // where two hosts silently disagree (`doc/decisions/0010`).
            s.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
            return out;
        }
        throw new Vm.Thrown(String.valueOf(v) + " is not seqable");
    }

    public static Object get(Object coll, Object key, Object dflt) {
        if (coll == null) return dflt;
        if (coll instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) if (eq(e.getKey(), key)) return e.getValue();
            return dflt;
        }
        if (coll instanceof List<?> l && key instanceof Long i) {
            int idx = i.intValue();
            return idx >= 0 && idx < l.size() ? l.get(idx) : dflt;
        }
        if (coll instanceof java.util.Set<?> s) return containsValue(s, key) ? key : dflt;
        return dflt;
    }

    // --- printing -----------------------------------------------------------

    /// `pr-str`: the READER's form, so a string is quoted.
    public static String prStr(Object v) { return print(v, true); }
    /// `str`: the human form, so a string is itself.
    public static String str(Object v) { return v == null ? "" : print(v, false); }

    private static String print(Object v, boolean readable) {
        if (v == null) return "nil";
        if (v instanceof String s) return readable ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : s;
        if (v instanceof Double d) {
            // flint prints a whole double with a trailing `.0`, and so must
            // this: float printing is a classic silent divergence.
            return d == Math.floor(d) && !d.isInfinite() ? String.format("%.1f", d) : d.toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) b.append(", ");
                first = false;
                b.append(print(e.getKey(), readable)).append(' ').append(print(e.getValue(), readable));
            }
            return b.append('}').toString();
        }
        if (v instanceof java.util.Set<?> s) return join(s, "#{", "}", readable);
        if (v instanceof LazySeq ls) return join(ls.force(), "(", ")", readable);
        if (v instanceof Seq q) return join(q, "(", ")", readable);
        if (v instanceof List<?> l) return join(l, "[", "]", readable);
        return v.toString();
    }

    private static String join(Collection<?> c, String open, String close, boolean readable) {
        StringBuilder b = new StringBuilder(open);
        boolean first = true;
        for (Object o : c) {
            if (!first) b.append(' ');
            first = false;
            b.append(print(o, readable));
        }
        return b.append(close).toString();
    }

    // --- type codes (`flint.types/code`) ------------------------------------

    /// The codes `flint.types/code` assigns, which `runtime/src/vm.rs::type_p`
    /// dispatches on.
    ///
    /// Copied from the table rather than inferred, because inferring them is
    /// how this first went wrong: a guessed ordering made `int?` answer false
    /// for every integer, and flint's own `str` then printed a perfectly good
    /// number as `#<unprintable>`. A wrong code here does not fail here -- it
    /// makes every type annotation in the program vacuous, which
    /// `flint.types` calls the worst possible failure for a feature whose
    /// whole value is that it is sound.
    public static Boolean isType(Object v, int code) {
        return switch (code) {
            case 1 -> v instanceof Long;                                   // int
            case 2 -> v instanceof Double;                                 // float
            case 3 -> v instanceof Long || v instanceof Double;            // number
            case 4 -> v instanceof String;                                 // string
            case 5 -> v instanceof Kw;                                     // keyword
            case 6 -> v instanceof Sym;                                    // symbol
            case 7 -> v instanceof Boolean;                                // boolean
            case 8 -> v instanceof List && !(v instanceof Seq);             // vector
            case 9 -> v instanceof Map;                                    // map
            case 10 -> v instanceof java.util.Set;                         // set
            case 11 -> v instanceof Seq || v instanceof LazySeq
                       || v instanceof Collection || v instanceof String;   // seq
            case 12 -> v instanceof Vm.Closure || v instanceof Fn;         // fn
            case 13 -> v == null;                                          // nil
            default -> v instanceof List || v instanceof Collection;       // sequential
        };
    }

    static {
        def("=", (vm, a) -> {
            for (int i = 1; i < a.length; i++) if (!eq(a[0], a[i])) return Boolean.FALSE;
            return Boolean.TRUE;
        });
        def("identical?", (vm, a) -> arg(a, 0) == arg(a, 1));
        def("not", (vm, a) -> !Vm.truthy(arg(a, 0)));

        def("+", (vm, a) -> arith(a, '+'));
        def("-", (vm, a) -> arith(a, '-'));
        def("*", (vm, a) -> arith(a, '*'));
        def("<", (vm, a) -> compare(a, '<'));
        def("<=", (vm, a) -> compare(a, 'l'));
        def(">", (vm, a) -> compare(a, '>'));
        def(">=", (vm, a) -> compare(a, 'g'));
        def("inc", (vm, a) -> Math.addExact(Vm.num(arg(a, 0)), 1));
        def("dec", (vm, a) -> Math.subtractExact(Vm.num(arg(a, 0)), 1));

        def("str", (vm, a) -> {
            StringBuilder b = new StringBuilder();
            for (Object o : a) b.append(str(o));
            return b.toString();
        });
        def("pr-str", (vm, a) -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) b.append(' ');
                b.append(prStr(a[i]));
            }
            return b.toString();
        });

        def("count", (vm, a) -> {
            Object v = arg(a, 0);
            if (v == null) return 0L;
            if (v instanceof Collection<?> c) return (long) c.size();
            if (v instanceof Map<?, ?> m) return (long) m.size();
            if (v instanceof String s) return (long) s.codePointCount(0, s.length());
            throw new Vm.Thrown(String.valueOf(v) + " cannot be counted");
        });
        def("get", (vm, a) -> get(arg(a, 0), arg(a, 1), arg(a, 2)));
        def("nth", (vm, a) -> {
            List<Object> xs = new ArrayList<>();
            for (Object o : iterate(arg(a, 0))) xs.add(o);
            int i = (int) Vm.num(arg(a, 1));
            if (i < 0 || i >= xs.size()) {
                if (a.length > 2) return a[2];
                throw new Vm.Thrown("index " + i + " out of bounds");
            }
            return xs.get(i);
        });
        def("conj", (vm, a) -> {
            Object coll = arg(a, 0);
            if (coll == null || coll instanceof List<?>) {
                List<Object> out = new ArrayList<>();
                if (coll != null) out.addAll((List<?>) coll);
                for (int i = 1; i < a.length; i++) out.add(a[i]);
                return out;
            }
            if (coll instanceof java.util.Set<?> s) {
                LinkedHashSet<Object> out = new LinkedHashSet<>(s);
                for (int i = 1; i < a.length; i++) out.add(a[i]);
                return out;
            }
            throw new Vm.Thrown("cannot conj onto " + prStr(coll));
        });
        def("assoc", (vm, a) -> {
            Map<Object, Object> out = new LinkedHashMap<>();
            if (arg(a, 0) instanceof Map<?, ?> m) out.putAll(m);
            for (int i = 1; i + 1 < a.length; i += 2) out.put(a[i], a[i + 1]);
            return out;
        });
        def("seq", (vm, a) -> {
            List<Object> xs = new ArrayList<>();
            for (Object o : iterate(arg(a, 0))) xs.add(o);
            return xs.isEmpty() ? null : Seq.of(xs);
        });
        def("first", (vm, a) -> {
            for (Object o : iterate(arg(a, 0))) return o;
            return null;
        });
        def("rest", (vm, a) -> Seq.of(tail(arg(a, 0))));
        def("next", (vm, a) -> {
            List<Object> xs = tail(arg(a, 0));
            return xs.isEmpty() ? null : Seq.of(xs);
        });
        def("apply", (vm, a) -> {
            List<Object> all = new ArrayList<>();
            for (int i = 1; i < a.length - 1; i++) all.add(a[i]);
            for (Object o : iterate(a[a.length - 1])) all.add(o);
            return vm.call(a[0], all.toArray());
        });
        def("throw", (vm, a) -> { throw new Vm.Thrown(arg(a, 0)); });

        // --- the `flint/...` builtins the compiler emits directly ----------
        //
        // Namespaced, and mostly ARITY-SPECIALISED: the compiler knows it is
        // adding exactly two things, so it emits `flint/add` rather than a
        // variadic `+`. Naming them separately is what lets the wasm runtime
        // skip the argument-count dance, and a port has to carry the same
        // names or the same program does not run.
        def("flint/add", (vm, a) -> arith(a, '+'));
        def("flint/sub", (vm, a) -> arith(a, '-'));
        def("flint/mul", (vm, a) -> arith(a, '*'));
        def("flint/lt", (vm, a) -> compare(a, '<'));
        def("flint/le", (vm, a) -> compare(a, 'l'));
        def("flint/gt", (vm, a) -> compare(a, '>'));
        def("flint/ge", (vm, a) -> compare(a, 'g'));
        def("flint/str2", (vm, a) -> str(arg(a, 0)) + str(arg(a, 1)));
        def("flint/num->str", (vm, a) -> str(arg(a, 0)));

        def("name", (vm, a) -> {
            Object v = arg(a, 0);
            if (v instanceof Kw k) return k.name;
            if (v instanceof Sym s) return s.name;
            if (v instanceof String s) return s;
            throw new Vm.Thrown("cannot take the name of " + prStr(v));
        });
        def("namespace", (vm, a) -> {
            Object v = arg(a, 0);
            if (v instanceof Kw k) return k.ns;
            if (v instanceof Sym s) return s.ns;
            return null;
        });

        // Metadata and opaque values are carried, not interpreted. A port that
        // returned something plausible here would be inventing semantics; nil
        // and false are the honest answers until metadata is ported.
        def("meta", (vm, a) -> null);
        def("flint/opaque?", (vm, a) -> Boolean.FALSE);
        def("flint/opaque-label", (vm, a) -> null);

        def("cons", (vm, a) -> {
            List<Object> out = new ArrayList<>();
            out.add(arg(a, 0));
            for (Object o : iterate(arg(a, 1))) out.add(o);
            return Seq.of(out);
        });
        def("rem", (vm, a) -> {
            long y = Vm.num(arg(a, 1));
            if (y == 0) throw new Vm.Thrown("divide by zero");
            return Vm.num(arg(a, 0)) % y;
        });
        def("quot", (vm, a) -> {
            long y = Vm.num(arg(a, 1));
            if (y == 0) throw new Vm.Thrown("divide by zero");
            return Vm.num(arg(a, 0)) / y;
        });
        // `=` on numbers specifically. Separate from `=` because the compiler
        // knows both sides are numbers and can skip the general dispatch.
        def("flint/num-eq", (vm, a) -> {
            Object x = arg(a, 0), y = arg(a, 1);
            if (x instanceof Long i && y instanceof Long j) return i.longValue() == j.longValue();
            return toD(x) == toD(y);
        });

        def("transient", (vm, a) -> Transient.of(arg(a, 0)));
        def("persistent!", (vm, a) -> {
            if (arg(a, 0) instanceof Transient t) return t.persistent();
            throw new Vm.Thrown("persistent! wants a transient");
        });
        def("conj!", (vm, a) -> {
            if (arg(a, 0) instanceof Transient t && t.list != null) {
                for (int i = 1; i < a.length; i++) t.list.add(a[i]);
                return t;
            }
            throw new Vm.Thrown("conj! wants a transient collection");
        });
        def("assoc!", (vm, a) -> {
            if (arg(a, 0) instanceof Transient t && t.map != null) {
                for (int i = 1; i + 1 < a.length; i += 2) t.map.put(a[i], a[i + 1]);
                return t;
            }
            throw new Vm.Thrown("assoc! wants a transient map");
        });

        // `flint/apply` is the compiler's own: it knows the argument count, so
        // it does not go through the variadic `apply`.
        def("flint/apply", (vm, a) -> {
            List<Object> all = new ArrayList<>();
            for (int i = 1; i < a.length - 1; i++) all.add(a[i]);
            for (Object o : iterate(a[a.length - 1])) all.add(o);
            return vm.call(a[0], all.toArray());
        });
        def("flint/div", (vm, a) -> {
            Object x = arg(a, 0), y = arg(a, 1);
            // Integer division that does not divide exactly gives a double, as
            // in flint. Returning a truncated integer here would be a wrong
            // ANSWER on this host and a right one elsewhere.
            if (x instanceof Long i && y instanceof Long j) {
                if (j == 0) throw new Vm.Thrown("divide by zero");
                if (i % j == 0) return i / j;
                return (double) i / (double) j;
            }
            double d = toD(y);
            if (d == 0.0) throw new Vm.Thrown("divide by zero");
            return toD(x) / d;
        });

        // Errors are DATA: `ex-info` builds a value and `throw` carries it, so
        // `catch` binds what was thrown rather than a rendering of it.
        def("ex-info", (vm, a) -> {
            Map<Object, Object> m = new LinkedHashMap<>();
            m.put(Kw.of(null, "message"), arg(a, 0));
            m.put(Kw.of(null, "data"), arg(a, 1) == null ? new LinkedHashMap<>() : arg(a, 1));
            return m;
        });
        def("ex-message", (vm, a) -> get(arg(a, 0), Kw.of(null, "message"), null));
        def("ex-data", (vm, a) -> get(arg(a, 0), Kw.of(null, "data"), null));
        def("flint/ex-kind", (vm, a) -> get(arg(a, 0), Kw.of(null, "kind"), null));

        def("flint/lazy-seq", (vm, a) -> new LazySeq(vm, arg(a, 0)));
        def("flint/range3", (vm, a) -> {
            long start = Vm.num(arg(a, 0)), end = Vm.num(arg(a, 1)), step = Vm.num(arg(a, 2));
            if (step == 0) throw new Vm.Thrown("range step of zero");
            List<Object> out = new ArrayList<>();
            for (long i = start; step > 0 ? i < end : i > end; i += step) out.add(i);
            return out;
        });
    }

    private static Object arith(Object[] a, char op) {
        if (a.length == 0) return op == '*' ? 1L : 0L;
        // Doubles are contagious, as in Clojure: any double makes the whole
        // expression a double.
        boolean anyDouble = false;
        for (Object o : a) if (o instanceof Double) anyDouble = true;
        if (anyDouble) {
            double acc = toD(a[0]);
            if (a.length == 1 && op == '-') return -acc;
            for (int i = 1; i < a.length; i++) {
                double x = toD(a[i]);
                acc = op == '+' ? acc + x : op == '-' ? acc - x : acc * x;
            }
            return acc;
        }
        long acc = Vm.num(a[0]);
        if (a.length == 1 && op == '-') return Math.negateExact(acc);
        for (int i = 1; i < a.length; i++) {
            long x = Vm.num(a[i]);
            // Checked, because flint's integers throw on overflow and the JVM's
            // wrap silently -- named in `0010` as a way two hosts disagree.
            acc = op == '+' ? Math.addExact(acc, x)
                : op == '-' ? Math.subtractExact(acc, x)
                : Math.multiplyExact(acc, x);
        }
        return acc;
    }

    private static double toD(Object o) {
        if (o instanceof Double d) return d;
        return Vm.num(o);
    }

    private static Object compare(Object[] a, char op) {
        for (int i = 0; i + 1 < a.length; i++) {
            double x = toD(a[i]), y = toD(a[i + 1]);
            boolean ok = switch (op) {
                case '<' -> x < y;
                case 'l' -> x <= y;
                case '>' -> x > y;
                default -> x >= y;
            };
            if (!ok) return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }
}
