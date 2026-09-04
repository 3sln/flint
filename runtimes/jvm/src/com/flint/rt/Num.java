package com.flint.rt;

import static com.flint.rt.Obj.*;

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
public final class Num {
    private Num() {}

    /// Canonical integer: fixnum when it fits, boxed otherwise.
    public static long integer(Rt rt, long n) {
        if (n >= -(1L << 47) && n < (1L << 47)) return Val.fixnum(n);
        long a = rt.alloc(TY_BIGINT, 8);
        if (a == 0) return Val.NIL;
        rt.gc.sp.writeU64(a + HDR, n);
        return Val.heap(a);
    }

    public static boolean isInt(Rt rt, long v) {
        return Val.isFixnum(v) || (Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_BIGINT);
    }
    public static boolean isFloat(long v) { return Val.isDouble(v); }
    public static boolean isNumber(Rt rt, long v) { return Val.isDouble(v) || isInt(rt, v); }

    /// The integer value, or `null` if this is not an integer. Boxed because
    /// "not an integer" and "the integer 0" must be distinguishable, and that
    /// distinction is what drives every promotion below.
    public static Long asI64(Rt rt, long v) {
        if (Val.isFixnum(v)) return Val.asFixnum(v);
        if (Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_BIGINT) {
            return rt.gc.sp.readU64(Val.asHeap(v) + HDR);
        }
        return null;
    }

    public static double f64(Rt rt, long v) {
        if (Val.isDouble(v)) return Val.asDouble(v);
        Long n = asI64(rt, v);
        return n == null ? Double.NaN : (double) n;
    }

    // A failing arithmetic builtin SETS `thrown` and returns nil, exactly as
    // the Rust does. Throwing a host `ArithmeticException` here would leave
    // flint's `try` with nothing to catch: the failure would never enter the
    // flint machinery at all, and `(try (/ 1 0) (catch ...))` could not work
    // however correct the opcode handling was.
    static long overflow(Rt rt) {
        return rt.throwStr("ArithmeticException", "integer overflow");
    }
    static long notNumber(Rt rt, long a, long b) {
        return rt.throwStr("ClassCastException",
            "not a number: " + rt.describe(a) + " and " + rt.describe(b));
    }
    static long divByZero(Rt rt) {
        return rt.throwStr("ArithmeticException", "Divide by zero");
    }

    public static long add(Rt rt, long a, long b) {
        Long x = asI64(rt, a), y = asI64(rt, b);
        if (x != null && y != null) {
            try { return integer(rt, Math.addExact(x, y)); }
            catch (ArithmeticException e) { return overflow(rt); }
        }
        if (isNumber(rt, a) && isNumber(rt, b)) return Val.ofDouble(f64(rt, a) + f64(rt, b));
        return notNumber(rt, a, b);
    }

    public static long sub(Rt rt, long a, long b) {
        Long x = asI64(rt, a), y = asI64(rt, b);
        if (x != null && y != null) {
            try { return integer(rt, Math.subtractExact(x, y)); }
            catch (ArithmeticException e) { return overflow(rt); }
        }
        if (isNumber(rt, a) && isNumber(rt, b)) return Val.ofDouble(f64(rt, a) - f64(rt, b));
        return notNumber(rt, a, b);
    }

    public static long mul(Rt rt, long a, long b) {
        Long x = asI64(rt, a), y = asI64(rt, b);
        if (x != null && y != null) {
            try { return integer(rt, Math.multiplyExact(x, y)); }
            catch (ArithmeticException e) { return overflow(rt); }
        }
        if (isNumber(rt, a) && isNumber(rt, b)) return Val.ofDouble(f64(rt, a) * f64(rt, b));
        return notNumber(rt, a, b);
    }

    /// `/`. See the class note: integer division that does not divide evenly
    /// yields a DOUBLE here, where Clojure would yield a Ratio.
    public static long div(Rt rt, long a, long b) {
        Long x = asI64(rt, a), y = asI64(rt, b);
        if (x != null && y != null) {
            if (y == 0) return divByZero(rt);
            if (x % y == 0) return integer(rt, x / y);
            return Val.ofDouble((double) x / (double) y);
        }
        if (isNumber(rt, a) && isNumber(rt, b)) return Val.ofDouble(f64(rt, a) / f64(rt, b));
        return notNumber(rt, a, b);
    }

    public static long quot(Rt rt, long a, long b) {
        Long x = asI64(rt, a), y = asI64(rt, b);
        if (x != null && y != null) {
            if (y == 0) return divByZero(rt);
            return integer(rt, x / y);
        }
        if (isNumber(rt, a) && isNumber(rt, b)) {
            double q = f64(rt, a) / f64(rt, b);
            return Val.ofDouble(q < 0 ? Math.ceil(q) : Math.floor(q));
        }
        return notNumber(rt, a, b);
    }

    public static long rem(Rt rt, long a, long b) {
        Long x = asI64(rt, a), y = asI64(rt, b);
        if (x != null && y != null) {
            if (y == 0) return divByZero(rt);
            return integer(rt, x % y);
        }
        if (isNumber(rt, a) && isNumber(rt, b)) {
            double p = f64(rt, a), q = f64(rt, b), t = p / q;
            return Val.ofDouble(p - (t < 0 ? Math.ceil(t) : Math.floor(t)) * q);
        }
        return notNumber(rt, a, b);
    }

    public static long neg(Rt rt, long a) {
        Long x = asI64(rt, a);
        if (x != null) {
            try { return integer(rt, Math.negateExact(x)); }
            catch (ArithmeticException e) { return overflow(rt); }
        }
        if (Val.isDouble(a)) return Val.ofDouble(-Val.asDouble(a));
        return notNumber(rt, a, a);
    }

    /// Numeric equality (`==`): compares ACROSS int and float, unlike `=`.
    public static boolean numEq(Rt rt, long a, long b) {
        Long x = asI64(rt, a), y = asI64(rt, b);
        if (x != null && y != null) return x.longValue() == y.longValue();
        if (isNumber(rt, a) && isNumber(rt, b)) return f64(rt, a) == f64(rt, b);
        return false;
    }

    /// -1, 0 or 1. NaN sorts as EQUAL to everything, matching `Double.compare`'s
    /// use inside Clojure's `compare`.
    public static int cmp(Rt rt, long a, long b) {
        Long x = asI64(rt, a), y = asI64(rt, b);
        if (x != null && y != null) return Long.compare(x, y);
        double p = f64(rt, a), q = f64(rt, b);
        return p < q ? -1 : p > q ? 1 : 0;
    }

    public static int hash(Rt rt, long v) {
        if (Val.isDouble(v)) return Hash.hashDouble(Val.asDouble(v));
        Long n = asI64(rt, v);
        return com._3sln.flint.kgen.rt.Hash.hashLong(n == null ? 0 : n);
    }
}
