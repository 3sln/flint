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

    // @kin:link:ns: flint.rt.num
    // @kin:link:form:integer: {:template "Num.Integer({0}, {1})"}
    /// GENERATED (`kin/numint.kin`) as `MakeInteger` -- a different name on
    /// purpose, because `integer` is the FORM the annotation above binds and
    /// a generated function of that name would shadow it.
    ///
    /// `Val.FixnumMax` was declared two files away the whole time and this
    /// method inlined the bound anyway. That copy IS gated against the jvm's
    /// `FIXNUM_MAX` by `bin/check-port-consts`; neither is compared against
    /// native, which is port-versus-port by construction. See the Java copy.
    public static long Integer(Rt rt, long n) =>
        global::_3sln.Flint.Kgen.Rt.Numint.MakeInteger(rt, n);

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
    /// GENERATED as `IntegerValue`. The TOTAL form is the shared one: `AsI64`
    /// answers "the integer, or nothing", which is `long?` here and
    /// `Option<i64>` on native -- a nullable type kin has not got, so it
    /// stays hand-written.
    public static long I64Of(Rt rt, long v) =>
        global::_3sln.Flint.Kgen.Rt.Numint.IntegerValue(rt, v);

    public static long? AsI64(Rt rt, long v) {
        if (Val.IsFixnum(v)) return Val.AsFixnum(v);
        if (Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyBigint) {
            return rt.gc.sp.ReadU64(Val.AsHeap(v) + Obj.Hdr);
        }
        return null;
    }

    /// GENERATED as `NumberF64` (`kin/numf64.kin`). THE NOT-A-NUMBER ANSWER
    /// IS WHY. Each runtime wrote its own host's constant, and `double.NaN`
    /// here is FFF8000000000000 -- the NEGATIVE quiet NaN -- where
    /// `Double.NaN` on the jvm and `f64::NAN` on native are both 7FF8000000000000.
    ///
    /// Nothing caught it because `=` on two NaNs is false whichever bits they
    /// carry. The bits escape through `HashDouble`, the snapshot and the wire
    /// codec, which is a different program from the one that made them, and
    /// `bin/check-port-consts` compares the two ports' DECLARED constants --
    /// `Double.NaN` belongs to the host, not to this file.
    ///
    /// A delegator rather than a rename, for the reason `Integer` above is
    /// one: the generated code lands in a different CLASS here where native's
    /// lands as a method on `Rt`.
    public static double F64(Rt rt, long v) =>
        global::_3sln.Flint.Kgen.Rt.Numf64.NumberF64(rt, v);

    // A failing arithmetic builtin SETS `thrown` and returns nil, exactly as
    // the Rust does. Throwing a host exception here would leave flint's `try`
    // with nothing to catch: the failure would never enter the flint machinery
    // at all, and `(try (/ 1 0) (catch ...))` could not work however correct
    // the opcode handling was.
    public static long NotNumber(Rt rt, long a, long b) =>
        rt.ThrowStr("ClassCastException",
                    "not a number: " + rt.Describe(a) + " and " + rt.Describe(b));
    static long DivByZero(Rt rt) => rt.ThrowStr("ArithmeticException", "Divide by zero");

    /// flint's integers OVERFLOW rather than wrap; .NET's `checked` is the
    /// analogue of the JVM's `Math.*Exact`.

    /// `+`, `-` and `*`. GENERATED, from `kin/numarith.kin` -- delegators
    /// rather than copies, so hand-written code here keeps saying `Num.Add`
    /// while there is one body under it.
    ///
    /// The overflow guard went with them and stopped being a `checked` block
    /// caught as `OverflowException`. Three hosts had three idioms for that,
    /// which is three chances to decide an edge differently -- see `Quot`
    /// below, where this port raised a HOST exception a flint program could
    /// not catch.
    public static long Add(Rt rt, long a, long b) =>
        global::_3sln.Flint.Kgen.Rt.Numarith.NumAdd(rt, a, b);
    public static long Sub(Rt rt, long a, long b) =>
        global::_3sln.Flint.Kgen.Rt.Numarith.NumSub(rt, a, b);
    public static long Mul(Rt rt, long a, long b) =>
        global::_3sln.Flint.Kgen.Rt.Numarith.NumMul(rt, a, b);

    /// `/`. See the class note: integer division that does not divide evenly
    /// yields a DOUBLE here, where Clojure would yield a Ratio.
    // `Div`, `Quot`, `Rem` and `Neg` are GENERATED, from `kin/numdiv.kin`.
    // `(quot MIN -1)` overflows and this port raised a host
    // `OverflowException` -- not a flint value at all, so a program could not
    // catch it. Native panicked and the JVM answered MIN silently.

    /// Numeric equality (`==`): compares ACROSS int and float, unlike `=`.
    /// `==`, GENERATED from `kin/numarith.kin`.
    public static bool NumEq(Rt rt, long a, long b) =>
        global::_3sln.Flint.Kgen.Rt.Numarith.NumEq(rt, a, b);

    /// -1, 0 or 1. NaN sorts as EQUAL to everything, matching `Double.compare`'s
    /// use inside Clojure's `compare`.
    /// GENERATED as `NumberCmp`. It went with `F64` because it was the other
    /// caller of `AsI64`, and neither wanted the NULLABILITY -- both used it
    /// only to ask whether the value is an integer, which `IsInt` answers as
    /// a predicate. `AsI64` keeps its other callers and stays here.
    public static int Cmp(Rt rt, long a, long b) =>
        global::_3sln.Flint.Kgen.Rt.Numf64.NumberCmp(rt, a, b);

    /// Fully qualified because `Num` declares its own `Hash`, which shadows the
    /// `Hash` CLASS inside this file -- the same clash `Maps` has.
    /// A number's hash, GENERATED from `kin/numarith.kin`.
    public static int Hash(Rt rt, long v) =>
        global::_3sln.Flint.Kgen.Rt.Numarith.NumHash(rt, v);
}
