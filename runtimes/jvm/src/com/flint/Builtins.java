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
    /// The dynamic bindings in force on THIS thread. See `flint/dyn-get`.
    private static final ThreadLocal<Object> DYN = new ThreadLocal<>();

    /// The name an `^int` annotation reports itself by. Copied from
    /// `runtime/src/builtins.rs::b_check_tag`, so the two messages match word
    /// for word -- a conformance run diffs the TEXT of an error, not its kind.
    private static String typeName(long code) {
        return switch ((int) code) {
            case 1 -> "int";     case 2 -> "float";   case 3 -> "number";
            case 4 -> "string";  case 5 -> "keyword"; case 6 -> "symbol";
            case 7 -> "boolean"; case 8 -> "vector";  case 9 -> "map";
            case 10 -> "set";    case 11 -> "seq";    case 12 -> "fn";
            case 13 -> "nil";    default -> "sequential";
        };
    }

    /// The closed set protocol dispatch runs on (`doc/decisions/0005`), copied
    /// from `runtime/src/builtins.rs::b_kind`.
    ///
    /// Note "list", not "seq": a cons and a lazy seq answer `:list` while a
    /// VECTOR answers `:vector`, even though both are sequential. And a byte
    /// string is "other" here, as it is there -- `kind` names what a protocol
    /// may be extended over, and inventing a name would extend that set.
    private static String kindOf(Object v) {
        if (v == null) return "nil";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Long || v instanceof Double) return "number";
        if (v instanceof String) return "string";
        if (v instanceof Kw) return "keyword";
        if (v instanceof Sym) return "symbol";
        if (v instanceof java.util.List) return "vector";
        if (v instanceof FlintMap) return "map";
        if (v instanceof java.util.Set) return "set";
        if (v instanceof Seq || v instanceof Cons || v instanceof LazySeq) return "list";
        if (v instanceof Vm.Closure || v instanceof Img.NativeRef) return "fn";
        if (v instanceof Atom) return "atom";
        if (v instanceof Pike.Regex) return "regex";
        return "other";
    }

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
    private static Bytes asBytes(Object v) {
        if (v instanceof Bytes b) return b;
        throw Vm.err(prStr(v) + " is not a byte string");
    }

    /// Everything after the first element, without forcing what follows.
    static Object restOf(Object v) {
        Object cur = v;
        while (true) {
            if (cur == null) return Seq.of(new ArrayList<>());
            if (cur instanceof LazySeq ls) { cur = ls.step(); continue; }
            if (cur instanceof Cons c) return c.tail == null ? Seq.of(new ArrayList<>()) : c.tail;
            return Seq.of(tail(cur));
        }
    }

    /// Is there a first element? Steps at most one lazy cell.
    static boolean isEmptySeq(Object v) {
        Object cur = v;
        while (true) {
            if (cur == null) return true;
            if (cur instanceof LazySeq ls) { cur = ls.step(); continue; }
            if (cur instanceof Cons) return false;
            if (cur instanceof Collection<?> c) return c.isEmpty();
            if (cur instanceof Map<?, ?> m) return m.isEmpty();
            if (cur instanceof String s) return s.isEmpty();
            return false;
        }
    }

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
        if (v == null) return new ArrayList<>();
        // Cons cells and lazy seqs are walked ITERATIVELY. Recursing here is
        // what overflowed the stack: a lazy chain is as deep as it is long.
        if (v instanceof Cons || v instanceof LazySeq) {
            List<Object> out = new ArrayList<>();
            Object cur = v;
            while (true) {
                if (cur == null) return out;
                if (cur instanceof LazySeq ls) { cur = ls.step(); continue; }
                if (cur instanceof Cons c) { out.add(c.head); cur = c.tail; continue; }
                for (Object o : iterate(cur)) out.add(o);
                return out;
            }
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(o);
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            List<Object> out = new ArrayList<>(m.size());
            // `Arrays.asList`, not `List.of`: a map entry can hold a nil
            // value, and `List.of` rejects nulls. This is the third place that
            // has bitten -- flint values are nullable and the immutable-list
            // factories are not.
            for (var e : m.entrySet()) {
                out.add(java.util.Arrays.asList(e.getKey(), e.getValue()));
            }
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
        throw Vm.err(String.valueOf(v) + " is not seqable");
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
        if (v instanceof Ex e) return e.toString();
        if (v instanceof Bytes b) return b.toString();
        if (v instanceof java.util.Set<?> s) return join(s, "#{", "}", readable);
        if (v instanceof LazySeq || v instanceof Cons) {
            return join(iterate(v), "(", ")", readable);
        }
        if (v instanceof Seq q) return join(q, "(", ")", readable);
        if (v instanceof List<?> l) return join(l, "[", "]", readable);
        return v.toString();
    }

    private static String join(Iterable<?> c, String open, String close, boolean readable) {
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
            // `seq?`, NOT `seqable?`. A vector, map, set and string are all
            // seqABLE and none of them IS a seq -- `(seq? [1 2])` is false in
            // Clojure and in `runtime/src/seqs.rs::is_seq`, which lists only
            // cons, the empty list, lazy seqs, and the vector/string/range
            // views. Conflating the two is what stopped the flint compiler
            // running here: `analyze-untagged` tests `seq?` before `vector?`,
            // so an argument vector `[& clauses]` was analyzed as a CALL, and
            // the analyzer descended into its own head forever.
            case 11 -> v instanceof Seq || v instanceof LazySeq || v instanceof Cons; // seq
            case 12 -> v instanceof Vm.Closure || v instanceof Fn;         // fn
            case 13 -> v == null;                                          // nil
            // `sequential?`: vectors and seqs, NOT sets or maps. `Collection`
            // covers Set, so the old test called `#{1}` sequential -- and
            // Clojure says false, because a set has no order to be sequential
            // in.
            default -> v instanceof List || v instanceof Cons
                       || v instanceof LazySeq;                             // sequential
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
            if (v instanceof LazySeq ls) return (long) ls.force().size();
            if (v instanceof Bytes b) return (long) b.count();
            // Anything else walkable, so a shape this list has not learned
            // about yet is counted rather than refused.
            if (v instanceof Iterable<?> it) {
                long n = 0;
                for (Object ignored : it) n++;
                return n;
            }
            // Naming the TYPE, not just the printed form: "[] cannot be
            // counted" says nothing, and an empty something is exactly the
            // case where the printed form is least informative.
            throw Vm.err(prStr(v) + " (" + v.getClass().getSimpleName()
                + ") cannot be counted");
        });
        def("get", (vm, a) -> get(arg(a, 0), arg(a, 1), arg(a, 2)));
        def("nth", (vm, a) -> {
            List<Object> xs = new ArrayList<>();
            for (Object o : iterate(arg(a, 0))) xs.add(o);
            int i = (int) Vm.num(arg(a, 1));
            if (i < 0 || i >= xs.size()) {
                if (a.length > 2) return a[2];
                throw Vm.err("index " + i + " out of bounds");
            }
            return xs.get(i);
        });
        def("conj", (vm, a) -> {
            Object coll = arg(a, 0);
            if (coll instanceof FlintMap fm) {
                // Onto a map, an entry is a two-element pair.
                for (int i = 1; i < a.length; i++) {
                    List<Object> pair = new ArrayList<>();
                    for (Object o : iterate(a[i])) pair.add(o);
                    if (pair.size() != 2) {
                        throw Vm.err("conj onto a map wants a [k v] pair, got " + prStr(a[i]));
                    }
                    fm = fm.assoc(pair.get(0), pair.get(1));
                }
                return fm;
            }
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
            throw Vm.err("cannot conj onto " + prStr(coll));
        });
        // On a VECTOR, `assoc` replaces the element at an index and answers a
        // vector. Falling through to the map branch answered `{1 :B}` for
        // `(assoc [:a :b :c] 1 :B)` -- the right value under the right key, and
        // the wrong kind of collection, which then failed several calls later
        // as "index out of bounds" on something that was no longer indexed.
        //
        // An index equal to the count APPENDS, which is Clojure's rule and what
        // makes `(assoc v (count v) x)` a legal way to grow one.
        def("assoc", (vm, a) -> {
            Object target = arg(a, 0);
            if (target instanceof List<?> && !(target instanceof Seq)) {
                List<Object> out = new ArrayList<>((List<Object>) target);
                for (int i = 1; i + 1 < a.length; i += 2) {
                    int idx = (int) Vm.num(a[i]);
                    if (idx < 0 || idx > out.size()) {
                        throw Vm.err("index " + idx + " out of bounds for a vector of "
                                     + out.size());
                    }
                    if (idx == out.size()) out.add(a[i + 1]); else out.set(idx, a[i + 1]);
                }
                return out;
            }
            FlintMap out = target instanceof FlintMap fm ? fm
                : target instanceof Map<?, ?> m ? FlintMap.of(m)
                : FlintMap.empty();
            for (int i = 1; i + 1 < a.length; i += 2) out = out.assoc(a[i], a[i + 1]);
            return out;
        });
        // `seq` answers the SAME sequence or nil -- it does not realise it.
        // Returning a materialised copy makes an infinite sequence a hang.
        // `seq` returns a SEQ, which is what `seq?` then answers true for.
        // Handing back the vector itself reads correctly and prints correctly
        // and is still wrong: `(seq? (seq [1 2]))` was false, and code that
        // calls `seq` to get something it can test is the code that notices.
        // A vector's seq is a VIEW of it, not a copy -- `Seq` wraps the list.
        def("seq", (vm, a) -> {
            Object v = arg(a, 0);
            if (isEmptySeq(v)) return null;
            if (v instanceof Seq || v instanceof Cons || v instanceof LazySeq) return v;
            List<Object> xs = new ArrayList<>();
            for (Object o : iterate(v)) xs.add(o);
            return xs.isEmpty() ? null : Seq.of(xs);
        });
        def("first", (vm, a) -> {
            // Without walking the whole thing: a lazy sequence's first element
            // must not force the rest of it.
            Object cur = arg(a, 0);
            while (true) {
                if (cur == null) return null;
                if (cur instanceof LazySeq ls) { cur = ls.step(); continue; }
                if (cur instanceof Cons c) return c.head;
                for (Object o : iterate(cur)) return o;
                return null;
            }
        });
        // The tail, UNFORCED. Materialising here is not merely slow: flint
        // has infinite lazy sequences, and walking one to build a list does
        // not end. That is what `(rest (iterate f x))` did before this.
        def("rest", (vm, a) -> restOf(arg(a, 0)));
        // `next` is `rest`, then ONE step to see whether anything is there.
        // One step, not the whole sequence.
        def("next", (vm, a) -> {
            Object r = restOf(arg(a, 0));
            return isEmptySeq(r) ? null : r;
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
            throw Vm.err("cannot take the name of " + prStr(v));
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
        // --- type predicates ------------------------------------------------
        //
        // Each is `isType` with its code from `flint.types/code`, so the
        // predicate and the `TYPE_P` opcode cannot answer differently. Writing
        // them out separately is how two spellings of one question drift.
        def("nil?", (vm, a) -> isType(arg(a, 0), 13));
        def("int?", (vm, a) -> isType(arg(a, 0), 1));
        def("float?", (vm, a) -> isType(arg(a, 0), 2));
        def("number?", (vm, a) -> isType(arg(a, 0), 3));
        def("string?", (vm, a) -> isType(arg(a, 0), 4));
        def("keyword?", (vm, a) -> isType(arg(a, 0), 5));
        def("symbol?", (vm, a) -> isType(arg(a, 0), 6));
        def("boolean?", (vm, a) -> isType(arg(a, 0), 7));
        def("vector?", (vm, a) -> isType(arg(a, 0), 8));
        def("map?", (vm, a) -> isType(arg(a, 0), 9));
        def("set?", (vm, a) -> isType(arg(a, 0), 10));
        def("seq?", (vm, a) -> isType(arg(a, 0), 11));
        def("fn?", (vm, a) -> isType(arg(a, 0), 12));
        def("sequential?", (vm, a) -> isType(arg(a, 0), 14));
        def("flint/map-entry?", (vm, a) -> Boolean.FALSE);
        def("bytes?", (vm, a) -> arg(a, 0) instanceof Bytes);
        def("flint/volatile?", (vm, a) -> arg(a, 0) instanceof Volatile);
        def("flint/delay?", (vm, a) -> arg(a, 0) instanceof LazySeq);
        def("flint/realized?", (vm, a) -> Boolean.TRUE);

        // --- bit operations -------------------------------------------------
        //
        // On i64, and NOT checked: bit operations are defined to wrap, which is
        // the one place flint's arithmetic does.
        def("bit-and", (vm, a) -> Vm.num(arg(a, 0)) & Vm.num(arg(a, 1)));
        def("bit-or", (vm, a) -> Vm.num(arg(a, 0)) | Vm.num(arg(a, 1)));
        def("bit-xor", (vm, a) -> Vm.num(arg(a, 0)) ^ Vm.num(arg(a, 1)));
        def("bit-not", (vm, a) -> ~Vm.num(arg(a, 0)));
        def("bit-shift-left", (vm, a) -> Vm.num(arg(a, 0)) << Vm.num(arg(a, 1)));
        def("bit-shift-right", (vm, a) -> Vm.num(arg(a, 0)) >> Vm.num(arg(a, 1)));
        def("unsigned-bit-shift-right", (vm, a) -> Vm.num(arg(a, 0)) >>> Vm.num(arg(a, 1)));
        def("bit-test", (vm, a) -> ((Vm.num(arg(a, 0)) >> Vm.num(arg(a, 1))) & 1) != 0);
        def("flint/unchecked-add", (vm, a) -> Vm.num(arg(a, 0)) + Vm.num(arg(a, 1)));
        def("flint/unchecked-sub", (vm, a) -> Vm.num(arg(a, 0)) - Vm.num(arg(a, 1)));
        def("flint/unchecked-mul", (vm, a) -> Vm.num(arg(a, 0)) * Vm.num(arg(a, 1)));

        // --- maths ----------------------------------------------------------
        //
        // Straight onto `java.lang.Math`, which is IEEE 754 and so is flint's.
        // `doc/decisions/0010` lists float printing as a silent divergence; the
        // OPERATIONS are the part that is safe to lean on.
        def("flint/sqrt", (vm, a) -> Math.sqrt(toD(arg(a, 0))));
        def("flint/cbrt", (vm, a) -> Math.cbrt(toD(arg(a, 0))));
        def("flint/exp", (vm, a) -> Math.exp(toD(arg(a, 0))));
        def("flint/expm1", (vm, a) -> Math.expm1(toD(arg(a, 0))));
        def("flint/log", (vm, a) -> Math.log(toD(arg(a, 0))));
        def("flint/log10", (vm, a) -> Math.log10(toD(arg(a, 0))));
        def("flint/log1p", (vm, a) -> Math.log1p(toD(arg(a, 0))));
        def("flint/sin", (vm, a) -> Math.sin(toD(arg(a, 0))));
        def("flint/cos", (vm, a) -> Math.cos(toD(arg(a, 0))));
        def("flint/tan", (vm, a) -> Math.tan(toD(arg(a, 0))));
        def("flint/asin", (vm, a) -> Math.asin(toD(arg(a, 0))));
        def("flint/acos", (vm, a) -> Math.acos(toD(arg(a, 0))));
        def("flint/atan", (vm, a) -> Math.atan(toD(arg(a, 0))));
        def("flint/sinh", (vm, a) -> Math.sinh(toD(arg(a, 0))));
        def("flint/cosh", (vm, a) -> Math.cosh(toD(arg(a, 0))));
        def("flint/tanh", (vm, a) -> Math.tanh(toD(arg(a, 0))));
        def("flint/floor", (vm, a) -> Math.floor(toD(arg(a, 0))));
        def("flint/ceil", (vm, a) -> Math.ceil(toD(arg(a, 0))));
        def("flint/rint", (vm, a) -> Math.rint(toD(arg(a, 0))));
        def("flint/trunc", (vm, a) -> (double) (long) toD(arg(a, 0)));
        def("flint/pow", (vm, a) -> Math.pow(toD(arg(a, 0)), toD(arg(a, 1))));
        def("flint/atan2", (vm, a) -> Math.atan2(toD(arg(a, 0)), toD(arg(a, 1))));
        def("flint/hypot", (vm, a) -> Math.hypot(toD(arg(a, 0)), toD(arg(a, 1))));
        def("flint/signum", (vm, a) -> Math.signum(toD(arg(a, 0))));
        def("flint/fabs", (vm, a) -> Math.abs(toD(arg(a, 0))));
        def("flint/copy-sign", (vm, a) -> Math.copySign(toD(arg(a, 0)), toD(arg(a, 1))));
        def("flint/double-bits", (vm, a) -> Double.doubleToRawLongBits(toD(arg(a, 0))));

        // --- strings ----------------------------------------------------------
        //
        // By CODE POINT throughout. The JVM is UTF-16 and flint is UTF-8, and
        // `subs` and indexing on anything past the BMP is exactly where two
        // hosts silently disagree (`doc/decisions/0010`).
        def("flint/subs", (vm, a) -> {
            String s = str(arg(a, 0));
            int n = s.codePointCount(0, s.length());
            int from = (int) Vm.num(arg(a, 1));
            int to = a.length > 2 ? (int) Vm.num(a[2]) : n;
            if (from < 0 || to > n || from > to) {
                throw Vm.err("substring [" + from + " " + to + ") out of " + n);
            }
            int bi = s.offsetByCodePoints(0, from);
            int ei = s.offsetByCodePoints(0, to);
            return s.substring(bi, ei);
        });
        def("flint/code-point-at", (vm, a) -> {
            String s = str(arg(a, 0));
            int i = (int) Vm.num(arg(a, 1));
            int n = s.codePointCount(0, s.length());
            if (i < 0 || i >= n) throw Vm.err("index " + i + " out of " + n);
            return (long) s.codePointAt(s.offsetByCodePoints(0, i));
        });
        def("flint/from-code-point", (vm, a) ->
            new String(Character.toChars((int) Vm.num(arg(a, 0)))));
        def("flint/str-join", (vm, a) -> {
            StringBuilder b = new StringBuilder();
            for (Object o : iterate(arg(a, 0))) b.append(str(o));
            return b.toString();
        });
        def("flint/str-index-of", (vm, a) -> {
            String s = str(arg(a, 0)), needle = str(arg(a, 1));
            int from = a.length > 2 ? (int) Vm.num(a[2]) : 0;
            int bi = from <= 0 ? 0 : s.offsetByCodePoints(0, Math.min(from, s.codePointCount(0, s.length())));
            int at = s.indexOf(needle, bi);
            return at < 0 ? null : (long) s.codePointCount(0, at);
        });
        def("flint/str->num", (vm, a) -> {
            String s = str(arg(a, 0)).trim();
            try {
                if (s.contains(".") || s.contains("e") || s.contains("E")) return Double.parseDouble(s);
                return Long.parseLong(s);
            } catch (NumberFormatException e) { return null; }
        });
        def("flint/to-long", (vm, a) -> {
            Object v = arg(a, 0);
            if (v instanceof Long l) return l;
            if (v instanceof Double d) return (long) (double) d;
            throw Vm.err(prStr(v) + " is not a number");
        });
        // The BYTES, as a vector, not how many there are. `flint.image/utf8` is
        // `(flint.rt/str-bytes s)` and then counts the result, so returning the
        // count made the compiler's own image writer count a number -- and the
        // error surfaced several frames away as "14 (Long) cannot be counted",
        // 14 being the length of whatever string it was on.
        def("flint/str-bytes", (vm, a) -> {
            byte[] bs = str(arg(a, 0)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            List<Object> out = new ArrayList<>(bs.length);
            for (byte b : bs) out.add((long) (b & 0xFF));
            return out;
        });

        // --- byte strings (`doc/decisions/0024`) -----------------------------
        def("flint/str->b", (vm, a) -> Bytes.of(str(arg(a, 0))));
        def("flint/b->str", (vm, a) -> asBytes(arg(a, 0)).text());
        def("flint/b-count", (vm, a) -> (long) asBytes(arg(a, 0)).count());
        def("flint/b-at", (vm, a) -> asBytes(arg(a, 0)).at((int) Vm.num(arg(a, 1))));
        def("flint/b-concat", (vm, a) -> asBytes(arg(a, 0)).concat(asBytes(arg(a, 1))));
        def("flint/b-slice", (vm, a) ->
            asBytes(arg(a, 0)).slice((int) Vm.num(arg(a, 1)), (int) Vm.num(arg(a, 2))));
        def("flint/b->vec", (vm, a) -> asBytes(arg(a, 0)).toVec());
        def("flint/vec->b", (vm, a) -> {
            Bytes.T t = new Bytes.T();
            for (Object o : iterate(arg(a, 0))) t.conj(Vm.num(o));
            return t.persistent();
        });
        // Always 0: this representation is flat, so it IS depth zero. The
        // builtin exists to observe flint's rope shape, and answering
        // something plausible instead would be inventing one.
        def("flint/b-depth", (vm, a) -> 0L);
        def("flint/b-transient", (vm, a) -> {
            Bytes.T t = new Bytes.T();
            if (arg(a, 0) != null) t.append(asBytes(arg(a, 0)));
            return t;
        });
        def("flint/b-conj!", (vm, a) -> {
            if (arg(a, 0) instanceof Bytes.T t) { t.conj(Vm.num(arg(a, 1))); return t; }
            throw Vm.err("b-conj! wants a byte transient");
        });
        def("flint/b-append!", (vm, a) -> {
            if (arg(a, 0) instanceof Bytes.T t) { t.append(asBytes(arg(a, 1))); return t; }
            throw Vm.err("b-append! wants a byte transient");
        });
        def("flint/b-tcount", (vm, a) -> {
            if (arg(a, 0) instanceof Bytes.T t) return (long) t.count();
            throw Vm.err("b-tcount wants a byte transient");
        });
        def("flint/b-persistent!", (vm, a) -> {
            if (arg(a, 0) instanceof Bytes.T t) return t.persistent();
            throw Vm.err("b-persistent! wants a byte transient");
        });

        // --- the rest ---------------------------------------------------------
        def("with-meta", (vm, a) -> arg(a, 0));   // metadata is carried, not read
        // ONE argument: a flat sequence of key, value, key, value. Not
        // varargs -- reading it that way made every map literal come back
        // EMPTY, so the reader parsed 97 KB of spec into `{}` and the compiler
        // then failed to resolve the first symbol it saw.
        def("flint/array-map", (vm, a) -> {
            FlintMap m = FlintMap.empty();
            Object k = null;
            boolean haveKey = false;
            for (Object o : iterate(arg(a, 0))) {
                if (haveKey) { m = m.assoc(k, o); haveKey = false; }
                else { k = o; haveKey = true; }
            }
            return m;
        });
        def("dissoc!", (vm, a) -> {
            if (arg(a, 0) instanceof Transient t && t.map != null) {
                for (int i = 1; i < a.length; i++) {
                    Object k = a[i];
                    t.map.keySet().removeIf(existing -> eq(existing, k));
                }
                return t;
            }
            throw Vm.err("dissoc! wants a transient map");
        });
        def("flint/volatile", (vm, a) -> new Volatile(arg(a, 0)));
        def("flint/delay", (vm, a) -> new LazySeq(vm, arg(a, 0)));
        def("flint/capabilities", (vm, a) -> FlintMap.empty());
        def("flint/opaque", (vm, a) -> arg(a, 0));

        // --- the type barrier ------------------------------------------------
        //
        // `check-tag` is what an `^int` annotation compiles to. It returns the
        // value so it can be used as an expression, and throws naming what the
        // author wrote, so the error lands at the annotation rather than
        // several frames inside the number tower.
        def("flint/check-tag", (vm, a) -> {
            Object v = arg(a, 0);
            long want = Vm.num(arg(a, 1));
            if (!(Boolean) isType(v, (int) want)) {
                String where = str(arg(a, 2));
                throw Vm.err(where.isEmpty()
                             ? "a value declared ^" + typeName(want) + " is not one"
                             : where + " is declared ^" + typeName(want) + ", and it is not");
            }
            return v;
        });
        def("flint/kind", (vm, a) -> Kw.of(null, kindOf(arg(a, 0))));
        def("flint/upper-case", (vm, a) -> str(arg(a, 0)).toUpperCase(java.util.Locale.ROOT));
        def("flint/lower-case", (vm, a) -> str(arg(a, 0)).toLowerCase(java.util.Locale.ROOT));
        def("flint/bits->double", (vm, a) -> Double.longBitsToDouble(Vm.num(arg(a, 0))));
        def("flint/bytes->str", (vm, a) -> {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            for (Object o : iterate(arg(a, 0))) b.write((int) (Vm.num(o) & 0xFF));
            return b.toString(java.nio.charset.StandardCharsets.UTF_8);
        });
        // No collector statistics exist for a host with its own GC.
        def("flint/gc-stats", (vm, a) -> FlintMap.empty());

        // --- regular expressions (`doc/decisions/0012`) ----------------------
        //
        // The PATTERN is parsed by `flint.nfa`, in cljc, which already runs
        // here. Only the Pike VM is ported. Handing the pattern to
        // `java.util.regex` instead would be less code and a different
        // language: `.` and newline, empty-match advancement and group
        // numbering all differ, and a conformance run compares answers.
        def("flint/re-compile", (vm, a) -> Pike.compile(str(arg(a, 0)), iterate(arg(a, 1))));
        def("flint/re-run", (vm, a) -> {
            if (!(arg(a, 0) instanceof Pike.Regex re)) throw Vm.err("not a compiled pattern");
            // 0 searches from `from`; another entry point matches exactly at it.
            // Both are entries into ONE program, so there is no second program
            // to keep in step with the first.
            int from = a.length > 2 ? (int) Vm.num(a[2]) : 0;
            int entry = a.length > 3 ? (int) Vm.num(a[3]) : 0;
            // The fifth argument asks for a match reaching the END, which is
            // `re-matches` and cannot be had by checking the span afterwards.
            boolean full = a.length > 4 && Vm.num(a[4]) != 0;
            return Pike.run(re, str(arg(a, 1)), from, entry, full);
        });
        def("flint/re-find-all", (vm, a) -> {
            if (!(arg(a, 0) instanceof Pike.Regex re)) throw Vm.err("not a compiled pattern");
            return Pike.findAll(re, str(arg(a, 1)), a.length > 2 ? Vm.num(a[2]) : 0);
        });

        // --- dynamic bindings ------------------------------------------------
        //
        // Per THREAD. flint's are per green thread, saved and restored by its
        // scheduler across a park; this port has no green threads, so a real OS
        // thread is the unit and a ThreadLocal map is the whole mechanism.
        // `binding` is a stack discipline either way -- compiled code reads the
        // map, pushes a new one, and puts the old one back in a finally -- so
        // the port only has to hold the current map.
        //
        // A spawned thread starts EMPTY rather than inheriting, which is where
        // this differs from flint: there a spawn takes a snapshot. Threads on
        // this port are created by the host, not by flint, so there is no spawn
        // site at which to take one.
        def("flint/dyn-get", (vm, a) -> {
            Object b = DYN.get();
            return b == null ? arg(a, 1) : get(b, arg(a, 0), arg(a, 1));
        });
        def("flint/dyn-bindings", (vm, a) -> {
            Object b = DYN.get();
            return b == null ? FlintMap.empty() : b;
        });
        def("flint/dyn-set-bindings", (vm, a) -> { DYN.set(arg(a, 0)); return arg(a, 0); });

        // The class hierarchy flint reports without having one: `Throwable`
        // catches everything, `Error` catches what is named `...Error`, and
        // `Exception` catches the rest. Copied from
        // `runtime/src/err.rs::ex_matches` -- guessing here would make a
        // `(catch Exception ...)` silently swallow an Error, or not catch at all.
        def("flint/ex-matches?", (vm, a) -> {
            String k = str(get(arg(a, 0), Kw.of(null, "kind"), null));
            String want = str(arg(a, 1));
            boolean isError = k.endsWith("Error");
            return switch (want) {
                case "Throwable" -> Boolean.TRUE;
                case "Exception", "RuntimeException" -> !isError;
                case "Error" -> isError;
                default -> k.equals(want);
            };
        });


        def("cons", (vm, a) -> new Cons(arg(a, 0), arg(a, 1)));
        def("rem", (vm, a) -> {
            long y = Vm.num(arg(a, 1));
            if (y == 0) throw Vm.err("divide by zero");
            return Vm.num(arg(a, 0)) % y;
        });
        def("quot", (vm, a) -> {
            long y = Vm.num(arg(a, 1));
            if (y == 0) throw Vm.err("divide by zero");
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
            throw Vm.err("persistent! wants a transient");
        });
        def("conj!", (vm, a) -> {
            if (arg(a, 0) instanceof Transient t) {
                if (t.list != null) { for (int i = 1; i < a.length; i++) t.list.add(a[i]); return t; }
                if (t.set != null) { for (int i = 1; i < a.length; i++) t.set.add(a[i]); return t; }
                if (t.map != null) {
                    // Onto a MAP, an entry is a two-element pair, as in
                    // Clojure. `(conj! m [k v])`.
                    for (int i = 1; i < a.length; i++) {
                        List<Object> pair = new ArrayList<>();
                        for (Object o : iterate(a[i])) pair.add(o);
                        if (pair.size() != 2) {
                            throw Vm.err("conj! onto a map wants a [k v] pair, got "
                                + prStr(a[i]));
                        }
                        t.map.put(pair.get(0), pair.get(1));
                    }
                    return t;
                }
            }
            throw Vm.err("conj! wants a transient collection, got " + prStr(arg(a, 0)));
        });
        def("disj!", (vm, a) -> {
            if (arg(a, 0) instanceof Transient t && t.set != null) {
                for (int i = 1; i < a.length; i++) {
                    Object x = a[i];
                    t.set.removeIf(y -> eq(x, y));
                }
                return t;
            }
            throw Vm.err("disj! wants a transient set");
        });
        def("assoc!", (vm, a) -> {
            if (arg(a, 0) instanceof Transient t && t.map != null) {
                for (int i = 1; i + 1 < a.length; i += 2) t.map.put(a[i], a[i + 1]);
                return t;
            }
            throw Vm.err("assoc! wants a transient map");
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
                if (j == 0) throw Vm.err("divide by zero");
                if (i % j == 0) return i / j;
                return (double) i / (double) j;
            }
            double d = toD(y);
            if (d == 0.0) throw Vm.err("divide by zero");
            return toD(x) / d;
        });

        // Errors are DATA: `ex-info` builds a value and `throw` carries it, so
        // `catch` binds what was thrown rather than a rendering of it.
        def("ex-info", (vm, a) -> new Ex("ExceptionInfo", arg(a, 0),
            arg(a, 1) == null ? FlintMap.empty() : arg(a, 1)));
        def("ex-message", (vm, a) -> arg(a, 0) instanceof Ex e ? e.message : null);
        def("ex-data", (vm, a) -> arg(a, 0) instanceof Ex e ? e.data : null);
        def("flint/ex-kind", (vm, a) -> arg(a, 0) instanceof Ex e ? e.kind : null);

        def("hash", (vm, a) -> (long) Hash.of(arg(a, 0)));

        def("atom", (vm, a) -> new Atom(arg(a, 0)));
        // Atoms AND volatiles, as the Rust runtime does (`TY_ATOM |
        // TY_VOLATILE`): a volatile is an atom without the atomicity, and the
        // core library derefs both through the same builtin.
        def("deref", (vm, a) -> {
            if (arg(a, 0) instanceof Atom at) return at.deref();
            if (arg(a, 0) instanceof Volatile v) return v.deref();
            if (arg(a, 0) instanceof LazySeq ls) return ls.force();
            throw Vm.err("cannot deref " + prStr(arg(a, 0)));
        });
        def("reset!", (vm, a) -> {
            if (arg(a, 0) instanceof Atom at) return at.reset(arg(a, 1));
            if (arg(a, 0) instanceof Volatile v) return v.reset(arg(a, 1));
            throw Vm.err("cannot reset! " + prStr(arg(a, 0)));
        });
        /// The primitive `swap!` is built from. `swap!` itself lives in
        /// `lib/clojure/core.cljc` as a retry loop, so every host gets the same
        /// semantics from the same source rather than from three
        /// implementations that agree by inspection.
        def("compare-and-set!", (vm, a) -> {
            if (arg(a, 0) instanceof Atom at) {
                return at.compareAndSet(arg(a, 1), arg(a, 2));
            }
            throw Vm.err("compare-and-set! wants an atom");
        });
        // The ARITY decides which argument is which: one is the name, two are
        // (ns, name). Reading argument 0 as the namespace regardless made
        // `(keyword "key0")` produce `:key0/` -- a keyword with an empty name,
        // which printed almost right and matched nothing.
        def("flint/keyword2", (vm, a) -> a.length == 1
            ? Kw.of(null, str(a[0]))
            : Kw.of(arg(a, 0) == null ? null : str(arg(a, 0)), str(arg(a, 1))));
        def("flint/symbol2", (vm, a) -> a.length == 1
            ? Sym.of(null, str(a[0]))
            : Sym.of(arg(a, 0) == null ? null : str(arg(a, 0)), str(arg(a, 1))));
        def("contains?", (vm, a) -> {
            Object c = arg(a, 0), k = arg(a, 1);
            if (c == null) return Boolean.FALSE;
            if (c instanceof Map<?, ?> m) {
                if (m instanceof FlintMap fm) return fm.containsKey(k);
                for (Object existing : m.keySet()) if (eq(existing, k)) return true;
                return Boolean.FALSE;
            }
            if (c instanceof java.util.Set<?> st) return containsValue(st, k);
            if (c instanceof List<?> l && k instanceof Long i) return i >= 0 && i < l.size();
            return Boolean.FALSE;
        });
        def("dissoc", (vm, a) -> {
            FlintMap out = FlintMap.empty();
            if (arg(a, 0) instanceof Map<?, ?> m) {
                outer:
                for (var e : m.entrySet()) {
                    for (int i = 1; i < a.length; i++) if (eq(e.getKey(), a[i])) continue outer;
                    out = out.assoc(e.getKey(), e.getValue());
                }
            }
            return out;
        });
        def("disj", (vm, a) -> {
            LinkedHashSet<Object> out = new LinkedHashSet<>();
            if (arg(a, 0) instanceof java.util.Set<?> s) {
                outer:
                for (Object o : s) {
                    for (int i = 1; i < a.length; i++) if (eq(o, a[i])) continue outer;
                    out.add(o);
                }
            }
            return out;
        });
        def("empty", (vm, a) -> {
            Object c = arg(a, 0);
            if (c instanceof Map) return FlintMap.empty();
            if (c instanceof java.util.Set) return new LinkedHashSet<>();
            if (c instanceof Seq) return Seq.of(new ArrayList<>());
            return new ArrayList<>();
        });
        def("peek", (vm, a) -> {
            Object c = arg(a, 0);
            if (c instanceof Seq q) return q.isEmpty() ? null : q.get(0);
            if (c instanceof List<?> l) return l.isEmpty() ? null : l.get(l.size() - 1);
            return null;
        });
        def("pop", (vm, a) -> {
            Object c = arg(a, 0);
            if (c instanceof Seq q) return Seq.of(tail(q));
            if (c instanceof List<?> l) {
                if (l.isEmpty()) throw Vm.err("cannot pop an empty vector");
                return new ArrayList<Object>(l.subList(0, l.size() - 1));
            }
            throw Vm.err("cannot pop " + prStr(c));
        });
        def("compare", (vm, a) -> (long) compareValues(arg(a, 0), arg(a, 1)));

        def("flint/lazy-seq", (vm, a) -> new LazySeq(vm, arg(a, 0)));
        def("flint/range3", (vm, a) -> {
            long start = Vm.num(arg(a, 0)), end = Vm.num(arg(a, 1)), step = Vm.num(arg(a, 2));
            if (step == 0) throw Vm.err("range step of zero");
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

    /// Total order over the shapes flint compares. Numbers by value, strings
    /// and identifiers by text, and `nil` before everything.
    static int compareValues(Object x, Object y) {
        if (x == null && y == null) return 0;
        if (x == null) return -1;
        if (y == null) return 1;
        if ((x instanceof Long || x instanceof Double)
            && (y instanceof Long || y instanceof Double)) {
            return Double.compare(toD(x), toD(y));
        }
        if (x instanceof String a && y instanceof String b) return a.compareTo(b);
        if (x instanceof Boolean a && y instanceof Boolean b) return Boolean.compare(a, b);
        if (x instanceof Kw a && y instanceof Kw b) return a.toString().compareTo(b.toString());
        if (x instanceof Sym a && y instanceof Sym b) return a.toString().compareTo(b.toString());
        throw Vm.err("cannot compare " + prStr(x) + " with " + prStr(y));
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
