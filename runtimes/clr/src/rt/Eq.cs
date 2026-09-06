namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Eq;


/// Structural equality and hashing, ported from `runtime/src/eq.rs`.
///
/// These two belong together because they must AGREE: two values that are `=`
/// must hash alike, or a map loses keys it is holding. That is why the same
/// file decides both, and why every case added to one is added to the other.
///
/// The method is `Equal` here where the JVM writes `eq`: C# forbids a member
/// with the same name as its enclosing type, so `Eq.Eq` is not sayable. Second
/// place the host's own rules force a different spelling -- `Obj.LVals` was the
/// first -- and worth naming so the next reader does not read it as drift.
///
/// `eq` on a compound value CAN ALLOCATE -- it seqs both sides -- so a caller
/// holding heap addresses across a call to it must have them rooted. The map
/// code does; that requirement is the reason `nodeFind` roots its walk.
public static class Eq {

    /// The three things `=` dispatches on. A value's CATEGORY, not its type: a
    /// vector, a list and a map entry are all sequential and compare
    /// elementwise, which is what makes `(= [1 2] '(1 2))` true.
    public const int CAT_SCALAR = 0, CAT_SEQUENTIAL = 1, CAT_MAP = 2, CAT_SET = 3;



    // `Equal` and `SeqEq` are GENERATED, from `kin/valeq.kin`, as
    // `Valeq.ValEq` and `Valeq.SeqEq`. The hand-written `Equal` tested for a
    // ROW REF *below* the category switch, where it could never run:
    // `Category` puts a ref in CAT_MAP, so every ref reached `Maps.Eq` first
    // and every non-map counterpart was refused by `ca != cb`. It worked only
    // because `Maps.Eq` materialises refs itself, which native's does not.

    /// The hash of a value, agreeing with `eq` above and with Clojure's.
    // `HashValue` is GENERATED, from `kin/valhash.kin`, as
    // `Valhash.ValueHash`. Two caches this port HAD and never used are live
    // now: `StrHash` was written during interning and never read, and a
    // keyword's slot 2 was set to nil rather than to its hash -- so the two
    // commonest map keys there are each paid their content per lookup.

    static bool SameBytes(byte[] x, byte[] y) {
        if (x.Length != y.Length) return false;
        for (int i = 0; i < x.Length; i++) if (x[i] != y[i]) return false;
        return true;
    }

    /// Clojure's `compare`: -1, 0 or 1, and a THROW for values that have no
    /// ordering. Refusing is the right answer -- a `sort` over mixed types
    /// silently ordered by type tag would be stable, plausible and wrong.
    public static int Compare(Rt rt, long a, long b) {
        if (a == b && !Val.IsDouble(a)) return 0;
        if (Val.IsNil(a)) return -1;
        if (Val.IsNil(b)) return 1;
        if (Num.IsNumber(rt, a) && Num.IsNumber(rt, b)) return Num.Cmp(rt, a, b);
        bool ba = a == Val.True || a == Val.False, bb = b == Val.True || b == Val.False;
        if (ba && bb) return (a == Val.True ? 1 : 0) - (b == Val.True ? 1 : 0);
        if (Str.IsString(rt, a) && Str.IsString(rt, b))
            return Utf16Cmp(Str.Text(rt, a), Str.Text(rt, b));
        bool ka = Val.IsInlineKw(a) || rt.IsHeapTy(a, Obj.TyKw);
        bool kb = Val.IsInlineKw(b) || rt.IsHeapTy(b, Obj.TyKw);
        if (ka && kb) return CmpNamed(rt, a, b);
        if (rt.IsHeapTy(a, Obj.TySym) && rt.IsHeapTy(b, Obj.TySym)) return CmpNamed(rt, a, b);
        if (rt.IsSequential(a) && rt.IsSequential(b)) return CmpSequential(rt, a, b);
        // Sets `thrown` and answers 0, as the Rust does: `Compare` returns an
        // int, so there is no failure value to hand back -- the pending throw
        // is the answer, and the interpreter unwinds on the way out.
        rt.ThrowStr("ClassCastException",
            "cannot compare " + rt.Describe(a) + " with " + rt.Describe(b));
        return 0;
    }

    /// By UTF-16 CODE UNIT, as Java's `compareTo` is -- not by code point. The
    /// two orders differ above U+FFFF, and Clojure's is the UTF-16 one.
    /// `string.CompareOrdinal` is that comparison; the loop says so out loud
    /// because the culture-sensitive default would be a silent divergence.
    static int Utf16Cmp(string x, string y) {
        int n = System.Math.Min(x.Length, y.Length);
        for (int i = 0; i < n; i++) {
            int d = x[i] - y[i];
            if (d != 0) return d < 0 ? -1 : 1;
        }
        return x.Length.CompareTo(y.Length);
    }

    /// Namespace first, then name -- and a value WITHOUT a namespace sorts
    /// before one with, which is Clojure's rule and not alphabetical order.
    static int CmpNamed(Rt rt, long a, long b) {
        long na = NsOf(rt, a), nb = NsOf(rt, b);
        if (Val.IsNil(na) && !Val.IsNil(nb)) return -1;
        if (!Val.IsNil(na) && Val.IsNil(nb)) return 1;
        if (!Val.IsNil(na)) {
            int c = Compare(rt, na, nb);
            if (c != 0) return c;
        }
        return Compare(rt, NameOf(rt, a), NameOf(rt, b));
    }

    // THE SECOND COPIES ARE GONE. `Eq` had its own `NameOf` and `NsOf` that
    // skipped the type check -- fine for the values it was handed, and two
    // more places to keep in step.
    static long NsOf(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Names.NsOf(rt, v);
    static long NameOf(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Names.NameOf(rt, v);

    /// Length first is WRONG for sequences: `[1 2]` is less than `[1 3]`, and
    /// both are less than `[1 2 3]`. So shorter-is-less only decides a tie.
    static int CmpSequential(Rt rt, long a, long b) {
        int bas = rt.Mark();
        int x = rt.Push(global::_3sln.Flint.Kgen.Rt.Seqwalk.Seq(rt, a)), y = rt.Push(global::_3sln.Flint.Kgen.Rt.Seqwalk.Seq(rt, b));
        int outv = 0;
        for (;;) {
            bool ex = Val.IsNil(rt.R(x)), ey = Val.IsNil(rt.R(y));
            if (ex || ey) { outv = ex && ey ? 0 : (ex ? -1 : 1); break; }
            int c = Compare(rt, global::_3sln.Flint.Kgen.Rt.Seqwalk.First(rt, rt.R(x)), global::_3sln.Flint.Kgen.Rt.Seqwalk.First(rt, rt.R(y)));
            if (c != 0) { outv = c; break; }
            long nx = global::_3sln.Flint.Kgen.Rt.Seqwalk.Next(rt, rt.R(x)), ny = global::_3sln.Flint.Kgen.Rt.Seqwalk.Next(rt, rt.R(y));
            rt.SetR(x, nx); rt.SetR(y, ny);
        }
        rt.PopTo(bas);
        return outv;
    }

    static byte[] NsBytes(Rt rt, long v) {
        long ns = rt.Slot(v, 0);
        return Val.IsNil(ns) ? null : Str.Bytes(rt, ns);
    }
}
