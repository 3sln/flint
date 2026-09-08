namespace Flint.Rt;


/// The number tower, ported from `runtime/src/num.rs`: 64-bit integers and
/// IEEE doubles. That is all of it.
///
/// An integer is a fixnum when it fits in 48 bits and a heap-boxed `i64`
/// otherwise. The representation is CANONICAL -- an integer in fixnum range is
/// always a fixnum -- so integer equality never has to consider the two forms
/// being different objects with the same value.
///
/// ## Where this differs from Clojure, deliberately
///
/// * No BigInt, no Ratio, no BigDecimal. `+`/`-`/`*` throw on 64-bit overflow
///   exactly as Clojure's do, but there is no `+'` to promote to.
/// * `(/ 1 2)` is `0.5`, not `1/2`. Clojure would produce a Ratio. This is the
///   single most visible numeric divergence and it is in the README. `quot`
///   and `rem` are exact and behave as Clojure's.
///
/// ## Why this is a module and not four lines inside `Builtins`
///
/// It already was four lines inside `Builtins`, and they read every argument
/// as a fixnum. On integers that is right; on a DOUBLE it reads the mantissa
/// bits as an integer, so `(+ 1.5 2.5)` came back `0` and the conformance
/// program's `:float` row was `[0 0 0]` where the native runtime said
/// `[4.0 6.0 0.25]`. The promotion rule is not per-builtin, so it does not
/// belong in each one.
public static class Num {

    /// Canonical integer: fixnum when it fits, boxed otherwise.
    /// TOWARD ZERO. The CLR has `Math.Ceiling` and `Math.Floor` and no
    /// `Truncate` on the path these took, so `Quot` and `Rem` each spelled the
    /// branch out; it is a name now because the generated arm needs one.
    public static double Trunc(double d) { return d < 0 ? System.Math.Ceiling(d) : System.Math.Floor(d); }

    public static long Integer(Rt rt, long n) {
        if (n >= -(1L << 47) && n < (1L << 47)) return Val.Fixnum(n);
        long a = rt.Alloc(Obj.TyBigint, 8);
        if (a == 0) return Val.Nil;
        rt.gc.sp.WriteU64(a + Obj.Hdr, n);
        return Val.Heap(a);
    }

    /// Is `v` an integer -- a fixnum or a BIGINT? GENERATED, from
    /// `kin/numkind.kin`. A delegator rather than a copy -- see the Java one.
    public static bool IsInt(Rt rt, long v) =>
        global::_3sln.Flint.Kgen.Rt.Numkind.IsInt(rt, v);
    public static bool IsFloat(long v) { return Val.IsDouble(v); }
    /// Is `v` a number -- either integer tier, or a double? GENERATED, from
    /// `kin/numkind.kin`.
    public static bool IsNumber(Rt rt, long v) =>
        global::_3sln.Flint.Kgen.Rt.Numkind.IsNumber(rt, v);

    /// The integer value, or `null` if this is not an integer. Boxed because
    /// "not an integer" and "the integer 0" must be distinguishable, and that
    /// distinction is what drives every promotion below.
    /// The integer value, or 0 when `v` is not an integer -- see the Java copy.
    public static long I64Of(Rt rt, long v) {
        long? n = AsI64(rt, v);
        return n.HasValue ? n.Value : 0L;
    }

    public static long? AsI64(Rt rt, long v) {
        if (Val.IsFixnum(v)) return Val.AsFixnum(v);
        if (Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyBigint) {
            return rt.gc.sp.ReadU64(Val.AsHeap(v) + Obj.Hdr);
        }
        return null;
    }

    public static double F64(Rt rt, long v) {
        if (Val.IsDouble(v)) return Val.AsDouble(v);
        long? n = AsI64(rt, v);
        return n == null ? Double.NaN : (double) n.Value;
    }

    // A failing arithmetic builtin SETS `thrown` and returns nil, exactly as
    // the Rust does. Throwing a host exception here would leave flint's `try`
    // with nothing to catch: the failure would never enter the flint machinery
    // at all, and `(try (/ 1 0) (catch ...))` could not work however correct
    // the opcode handling was.
    static long Overflow(Rt rt) => rt.ThrowStr("ArithmeticException", "integer overflow");
    public static long NotNumber(Rt rt, long a, long b) =>
        rt.ThrowStr("ClassCastException",
                    "not a number: " + rt.Describe(a) + " and " + rt.Describe(b));
    static long DivByZero(Rt rt) => rt.ThrowStr("ArithmeticException", "Divide by zero");

    /// flint's integers OVERFLOW rather than wrap; .NET's `checked` is the
    /// analogue of the JVM's `Math.*Exact`.
    static long AddExact(long a, long b) { checked { return a + b; } }
    static long SubExact(long a, long b) { checked { return a - b; } }
    static long MulExact(long a, long b) { checked { return a * b; } }
    static long NegExact(long a) { checked { return -a; } }

    public static long Add(Rt rt, long a, long b) {
        long? x = AsI64(rt, a), y = AsI64(rt, b);
        if (x != null && y != null) {
            try { return Integer(rt, AddExact(x.Value, y.Value)); }
            catch (System.OverflowException) { return Overflow(rt); }
        }
        if (IsNumber(rt, a) && IsNumber(rt, b)) return Val.OfDouble(F64(rt, a) + F64(rt, b));
        return NotNumber(rt, a, b);
    }

    public static long Sub(Rt rt, long a, long b) {
        long? x = AsI64(rt, a), y = AsI64(rt, b);
        if (x != null && y != null) {
            try { return Integer(rt, SubExact(x.Value, y.Value)); }
            catch (System.OverflowException) { return Overflow(rt); }
        }
        if (IsNumber(rt, a) && IsNumber(rt, b)) return Val.OfDouble(F64(rt, a) - F64(rt, b));
        return NotNumber(rt, a, b);
    }

    public static long Mul(Rt rt, long a, long b) {
        long? x = AsI64(rt, a), y = AsI64(rt, b);
        if (x != null && y != null) {
            try { return Integer(rt, MulExact(x.Value, y.Value)); }
            catch (System.OverflowException) { return Overflow(rt); }
        }
        if (IsNumber(rt, a) && IsNumber(rt, b)) return Val.OfDouble(F64(rt, a) * F64(rt, b));
        return NotNumber(rt, a, b);
    }

    /// `/`. See the class note: integer division that does not divide evenly
    /// yields a DOUBLE here, where Clojure would yield a Ratio.
    // `Div`, `Quot`, `Rem` and `Neg` are GENERATED, from `kin/numdiv.kin`.
    // `(quot MIN -1)` overflows and this port raised a host
    // `OverflowException` -- not a flint value at all, so a program could not
    // catch it. Native panicked and the JVM answered MIN silently.

    /// Numeric equality (`==`): compares ACROSS int and float, unlike `=`.
    public static bool NumEq(Rt rt, long a, long b) {
        long? x = AsI64(rt, a), y = AsI64(rt, b);
        if (x != null && y != null) return x.Value == y.Value;
        if (IsNumber(rt, a) && IsNumber(rt, b)) return F64(rt, a) == F64(rt, b);
        return false;
    }

    /// -1, 0 or 1. NaN sorts as EQUAL to everything, matching `Double.compare`'s
    /// use inside Clojure's `compare`.
    public static int Cmp(Rt rt, long a, long b) {
        long? x = AsI64(rt, a), y = AsI64(rt, b);
        if (x != null && y != null) return x.Value.CompareTo(y.Value);
        double p = F64(rt, a), q = F64(rt, b);
        return p < q ? -1 : p > q ? 1 : 0;
    }

    /// Fully qualified because `Num` declares its own `Hash`, which shadows the
    /// `Hash` CLASS inside this file -- the same clash `Maps` has.
    public static int Hash(Rt rt, long v) {
        if (Val.IsDouble(v)) return Flint.Rt.Hash.HashDouble(Val.AsDouble(v));
        long? n = AsI64(rt, v);
        return _3sln.Flint.Kgen.Rt.Hash.HashLong(n == null ? 0 : n.Value);
    }
}
