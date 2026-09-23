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
    /// TOWARD ZERO. Java has `Math.ceil` and `Math.floor` and no `trunc`, so
    /// `quot` and `rem` each spelled the branch out; it is a name now because
    /// the generated arm needs one.
    public static double trunc(double d) { return d < 0 ? Math.ceil(d) : Math.floor(d); }

    // @kin:link:ns: flint.rt.num
    // @kin:link:form:integer: {:template "Num.integer({0}, {1})"}
    /// GENERATED (`kin/numint.kin`). The BOUND is why: this file inlined
    /// `n >= -(1L << 47) && n < (1L << 47)` and so did the clr -- while
    /// `Val.FIXNUM_MAX` sat two files away, already declared here and on both
    /// other runtimes. `bin/check-port-consts` does compare this constant
    /// against the clr's `Val.FixnumMax`; what it cannot compare is either of
    /// them against NATIVE, being port-versus-port by construction. And
    /// nothing at all checks that `Num.integer` uses the constant rather than
    /// writing the bound out again, which is what it did.
    ///
    /// A DELEGATOR RATHER THAN A RENAME, because the generated code lands in
    /// a different CLASS here where native's lands as a method on `Rt`: 20
    /// call sites say `Num.integer` on this side and 20 more on the clr, and
    /// that asymmetry is structural rather than neglect.
    public static long integer(Rt rt, long n) {
        return com._3sln.flint.kgen.rt.Numint.makeInteger(rt, n);
    }

    /// Is `v` an integer -- a fixnum or a BIGINT? GENERATED, from
    /// `kin/numkind.kin`. A delegator rather than a copy: hand-written code
    /// here says `Num.isInt`, the generated tree says `isInt`, and there is
    /// one body under both.
    public static boolean isInt(Rt rt, long v) {
        return com._3sln.flint.kgen.rt.Numkind.isInt(rt, v);
    }
    public static boolean isFloat(long v) { return Val.isDouble(v); }
    /// Is `v` a number -- either integer tier, or a double? GENERATED, from
    /// `kin/numkind.kin`.
    public static boolean isNumber(Rt rt, long v) {
        return com._3sln.flint.kgen.rt.Numkind.isNumber(rt, v);
    }

    /// The integer value, or `null` if this is not an integer. Boxed because
    /// "not an integer" and "the integer 0" must be distinguishable, and that
    /// distinction is what drives every promotion below.
    /// The integer value, or 0 when `v` is not an integer.
    ///
    /// The TOTAL form, for callers that have already asked `isInt`. They were
    /// reaching for `Val.asFixnum` instead, which reads the tagged payload and
    /// is simply the wrong bits for a BIGINT -- `Table.tableAssoc` did exactly
    /// that with the row index it then put in an error message.
    /// GENERATED (`kin/numint.kin`). The TOTAL form is the shared one:
    /// `asI64` answers "the integer, or nothing", which is a boxed `Long`
    /// here and `Option<i64>` on native -- a nullable type kin has not got,
    /// so it stays hand-written below.
    public static long i64Of(Rt rt, long v) {
        return com._3sln.flint.kgen.rt.Numint.integerValue(rt, v);
    }

    public static Long asI64(Rt rt, long v) {
        if (Val.isFixnum(v)) return Val.asFixnum(v);
        if (Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_BIGINT) {
            return rt.gc.sp.readU64(Val.asHeap(v) + HDR);
        }
        return null;
    }

    /// GENERATED as `numberF64` (`kin/numf64.kin`). THE NOT-A-NUMBER ANSWER
    /// IS WHY. Each runtime wrote its own host's constant, and `double.NaN`
    /// on .NET is FFF8000000000000 -- the NEGATIVE quiet NaN -- where
    /// `Double.NaN` here and `f64::NAN` on native are both 7FF8000000000000.
    ///
    /// Nothing caught it because `=` on two NaNs is false whichever bits they
    /// carry. The bits escape through `hashDouble`, the snapshot and the wire
    /// codec, which is a different program from the one that made them, and
    /// `bin/check-port-consts` compares the two ports' DECLARED constants --
    /// `Double.NaN` belongs to the host, not to this file.
    ///
    /// A delegator rather than a rename, for the reason `integer` above is
    /// one: the generated code lands in a different CLASS here where native's
    /// lands as a method on `Rt`.
    public static double f64(Rt rt, long v) {
        return com._3sln.flint.kgen.rt.Numf64.numberF64(rt, v);
    }

    // A failing arithmetic builtin SETS `thrown` and returns nil, exactly as
    // the Rust does. Throwing a host `ArithmeticException` here would leave
    // flint's `try` with nothing to catch: the failure would never enter the
    // flint machinery at all, and `(try (/ 1 0) (catch ...))` could not work
    // however correct the opcode handling was.
    public static long notNumber(Rt rt, long a, long b) {
        return rt.throwStr("ClassCastException",
            "not a number: " + rt.describe(a) + " and " + rt.describe(b));
    }
    static long divByZero(Rt rt) {
        return rt.throwStr("ArithmeticException", "Divide by zero");
    }

    /// `+`, `-` and `*`. GENERATED, from `kin/numarith.kin` -- delegators
    /// rather than copies, for the reason `isInt` above is one: hand-written
    /// code here says `Num.add`, the generated tree says `numAdd`, and there
    /// is one body under both.
    ///
    /// The overflow guard went with them and stopped being `Math.addExact`
    /// in a `try`. Three hosts had three idioms for that, which is three
    /// chances to decide an edge differently -- see `quot` below, where they
    /// did.
    public static long add(Rt rt, long a, long b) {
        return com._3sln.flint.kgen.rt.Numarith.numAdd(rt, a, b);
    }
    public static long sub(Rt rt, long a, long b) {
        return com._3sln.flint.kgen.rt.Numarith.numSub(rt, a, b);
    }
    public static long mul(Rt rt, long a, long b) {
        return com._3sln.flint.kgen.rt.Numarith.numMul(rt, a, b);
    }

    /// `/`. See the class note: integer division that does not divide evenly
    /// yields a DOUBLE here, where Clojure would yield a Ratio.
    // `div`, `quot`, `rem` and `neg` are GENERATED, from `kin/numdiv.kin`.
    // `(quot MIN -1)` overflows and this port answered MIN SILENTLY, because
    // Java's `/` wraps there rather than throwing. Native panicked and the
    // CLR raised a host `OverflowException`; three runtimes, three answers.

    /// Numeric equality (`==`): compares ACROSS int and float, unlike `=`.
    /// `==`, GENERATED from `kin/numarith.kin`. Numeric equality compares
    /// ACROSS the integer/double divide, where `=` does not.
    public static boolean numEq(Rt rt, long a, long b) {
        return com._3sln.flint.kgen.rt.Numarith.numEq(rt, a, b);
    }

    /// -1, 0 or 1. NaN sorts as EQUAL to everything, matching `Double.compare`'s
    /// use inside Clojure's `compare`.
    /// GENERATED as `numberCmp`. It went with `f64` because it was the other
    /// caller of `asI64`, and neither wanted the NULLABILITY -- both used it
    /// only to ask whether the value is an integer, which `isInt` answers as
    /// a predicate. `asI64` keeps its other callers and stays here.
    public static int cmp(Rt rt, long a, long b) {
        return com._3sln.flint.kgen.rt.Numf64.numberCmp(rt, a, b);
    }

    /// A number's hash, GENERATED from `kin/numarith.kin`.
    public static int hash(Rt rt, long v) {
        return com._3sln.flint.kgen.rt.Numarith.numHash(rt, v);
    }
}
