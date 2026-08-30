namespace Flint.Rt;


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

    public static bool Equal(Rt rt, long a, long b) {
        if (a == b) return true;
        if (Val.IsFixnum(a) && Val.IsFixnum(b)) return Val.AsFixnum(a) == Val.AsFixnum(b);
        if (Val.IsDouble(a) && Val.IsDouble(b)) return Val.AsDouble(a) == Val.AsDouble(b);
        if (Str.IsString(rt, a) && Str.IsString(rt, b)) {
            return SameBytes(Str.Bytes(rt, a), Str.Bytes(rt, b));
        }
        bool ka = Val.IsInlineKw(a) || rt.IsHeapTy(a, Obj.TyKw);
        bool kb = Val.IsInlineKw(b) || rt.IsHeapTy(b, Obj.TyKw);
        if (ka || kb) {
            if (!(ka && kb)) return false;
            // Both inline, or both interned: identity IS equality, which is
            // the whole point of `doc/decisions/0011`'s tiers. A keyword short
            // enough to be inline is never on the heap, and one long enough to
            // be on the heap is interned, so the two forms never meet.
            return a == b;
        }
        // Symbols are interned too, so `a == b` above already answered it. Two
        // distinct symbol objects with the same name would be an interning bug
        // rather than a case to handle, and comparing slots here would HIDE it.
        if (rt.IsHeapTy(a, Obj.TySym) || rt.IsHeapTy(b, Obj.TySym)) return false;
        if (rt.IsHeapTy(a, Obj.TyVec) && rt.IsHeapTy(b, Obj.TyVec)) {
            int n = Vec.Count(rt, a);
            if (n != Vec.Count(rt, b)) return false;
            for (int i = 0; i < n; i++) {
                if (!Equal(rt, Vec.Nth(rt, a, i), Vec.Nth(rt, b, i))) return false;
            }
            return true;
        }
        if (Maps.IsMap(rt, a) && Maps.IsMap(rt, b)) return Maps.Eq(rt, a, b);
        if (Sets.IsSet(rt, a) && Sets.IsSet(rt, b)) return Sets.Eq(rt, a, b);
        // A vector and a seq holding the same elements ARE equal in Clojure:
        // `=` is over the sequential abstraction, not the concrete type.
        bool sa = rt.IsSequential(a);
        bool sb = rt.IsSequential(b);
        if (sa && sb) {
            int bas = rt.Mark();
            int x = rt.Push(Seqs.Seq(rt, a)), y = rt.Push(Seqs.Seq(rt, b));
            bool ok = true;
            for (;;) {
                bool ex = Val.IsNil(rt.R(x)), ey = Val.IsNil(rt.R(y));
                if (ex || ey) { ok = ex && ey; break; }
                if (!Equal(rt, Seqs.First(rt, rt.R(x)), Seqs.First(rt, rt.R(y)))) { ok = false; break; }
                long nx = Seqs.Next(rt, rt.R(x));
                long ny = Seqs.Next(rt, rt.R(y));
                rt.SetR(x, nx);
                rt.SetR(y, ny);
            }
            rt.PopTo(bas);
            return ok;
        }
        if (Val.IsHeap(a) || Val.IsHeap(b)) {
            if (Val.IsHeap(a) && Val.IsHeap(b)
                && Obj.Ty(rt.gc.sp, Val.AsHeap(a)) != Obj.Ty(rt.gc.sp, Val.AsHeap(b))) {
                return false;
            }
            throw new System.NotSupportedException(
                "= over " + rt.Describe(a) + " and " + rt.Describe(b) + " needs more of the data structures");
        }
        return false;
    }

    /// The hash of a value, agreeing with `eq` above and with Clojure's.
    public static int HashValue(Rt rt, long v) {
        if (Val.IsDouble(v)) return Hash.HashDouble(Val.AsDouble(v));
        if (Val.IsNil(v)) return 0;
        if (v == Val.True) return Hash.HashTrue;
        if (v == Val.False) return Hash.HashFalse;
        if (Val.IsFixnum(v)) return Hash.HashLong(Val.AsFixnum(v));
        if (Val.IsInlineStr(v)) return Hash.HashString(Val.InlineBytes(v));
        if (Val.IsInlineKw(v)) return Hash.HashKeyword(null, Val.InlineBytes(v));
        if (!Val.IsHeap(v)) return 0;
        switch (Obj.Ty(rt.gc.sp, Val.AsHeap(v))) {
            case Obj.TyStr: return Hash.HashString(Str.Bytes(rt, v));
            case Obj.TyKw: return Hash.HashKeyword(NsBytes(rt, v), Str.Bytes(rt, rt.Slot(v, 1)));
            case Obj.TySym: return Hash.HashSymbol(NsBytes(rt, v), Str.Bytes(rt, rt.Slot(v, 1)));
            case Obj.TyVec: {
                int n = Vec.Count(rt, v), acc = 1;
                for (int i = 0; i < n; i++) acc = Hash.OrderedStep(acc, HashValue(rt, Vec.Nth(rt, v, i)));
                return Hash.MixCollHash(acc, n);
            }
            case Obj.TyArraymap:
            case Obj.TyHashmap: return Maps.Hash(rt, v);
            case Obj.TySet: return Sets.Hash(rt, v);
            default: {
                if (rt.IsSeq(v)) {
                    int bas = rt.Mark();
                    int s = rt.Push(Seqs.Seq(rt, v));
                    int acc = 1, n = 0;
                    while (!Val.IsNil(rt.R(s))) {
                        acc = Hash.OrderedStep(acc, HashValue(rt, Seqs.First(rt, rt.R(s))));
                        n++;
                        long nx = Seqs.Next(rt, rt.R(s));
                        rt.SetR(s, nx);
                    }
                    rt.PopTo(bas);
                    return Hash.MixCollHash(acc, n);
                }
                return 0;
            }
        }
    }

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
        throw new System.InvalidCastException(
            "cannot compare " + rt.Describe(a) + " with " + rt.Describe(b));
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

    static long NsOf(Rt rt, long v) => Val.IsInlineKw(v) ? Val.Nil : rt.Slot(v, 0);
    static long NameOf(Rt rt, long v) =>
        Val.IsInlineKw(v) ? Val.InlineStr(Val.InlineBytes(v)) : rt.Slot(v, 1);

    /// Length first is WRONG for sequences: `[1 2]` is less than `[1 3]`, and
    /// both are less than `[1 2 3]`. So shorter-is-less only decides a tie.
    static int CmpSequential(Rt rt, long a, long b) {
        int bas = rt.Mark();
        int x = rt.Push(Seqs.Seq(rt, a)), y = rt.Push(Seqs.Seq(rt, b));
        int outv = 0;
        for (;;) {
            bool ex = Val.IsNil(rt.R(x)), ey = Val.IsNil(rt.R(y));
            if (ex || ey) { outv = ex && ey ? 0 : (ex ? -1 : 1); break; }
            int c = Compare(rt, Seqs.First(rt, rt.R(x)), Seqs.First(rt, rt.R(y)));
            if (c != 0) { outv = c; break; }
            long nx = Seqs.Next(rt, rt.R(x)), ny = Seqs.Next(rt, rt.R(y));
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
