namespace Flint.Rt;

using static flint.rt.Eq;


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



    public static bool Equal(Rt rt, long a, long b) {
        // Doubles FIRST: bit equality would wrongly make NaN equal to itself,
        // and would wrongly separate 0.0 from -0.0.
        if (Val.IsDouble(a) || Val.IsDouble(b))
            return Val.IsDouble(a) && Val.IsDouble(b) && Val.AsDouble(a) == Val.AsDouble(b);
        if (a == b) return true;
        if (Num.IsInt(rt, a) || Num.IsInt(rt, b)) {
            // Integers are CANONICAL, so the only way two are equal without
            // being bit-equal is two distinct boxes.
            long? x = Num.AsI64(rt, a), y = Num.AsI64(rt, b);
            return x.HasValue && y.HasValue && x.Value == y.Value;
        }
        if (!Val.IsHeap(a) || !Val.IsHeap(b)) {
            // Immediates are canonical: an inline string can only equal another
            // inline string, and that would have been bit equality. So a
            // boolean and a list are simply NOT EQUAL -- which is Clojure's
            // answer, and refusing here instead was a runtime error where a
            // `false` belonged.
            return false;
        }
        int ca = Category(rt, a), cb = Category(rt, b);
        if (ca != cb) return false;
        if (ca == CAT_SEQUENTIAL) return SeqEq(rt, a, b);
        if (ca == CAT_MAP) return Maps.Eq(rt, a, b);
        if (ca == CAT_SET) return Sets.Eq(rt, a, b);

        int ta = Obj.Ty(rt.gc.sp, Val.AsHeap(a)), tb = Obj.Ty(rt.gc.sp, Val.AsHeap(b));
        // A string is a string WHATEVER TIER it is in: `(str a b)` and a flat
        // string of the same bytes must be `=` and must hash alike, or a map
        // keyed by one is not found by the other (`doc/decisions/0011`).
        if ((ta == Obj.TyStr || ta == Obj.TyRope) && (tb == Obj.TyStr || tb == Obj.TyRope)) {
            // WALKED, not copied. This built the bytes of BOTH sides in full
            // before comparing one of them; `TreeEq` stops at the first
            // mismatch and short-circuits on NODE IDENTITY, which matters much
            // more now that `subs` shares (`doc/decisions/0011`).
            if (Str.SBytes(rt, a) != Str.SBytes(rt, b)) return false;
            return Str.TreeEq(rt, a, b);
        }
        if ((ta == Obj.TyBytes || ta == Obj.TyBrope) && (tb == Obj.TyBytes || tb == Obj.TyBrope))
            return Bytes.Eq(rt, a, b);
        // Two tagged literals are equal when both halves are, and never equal
        // to a two-key map -- the whole reason it is a type (`0034`).
        // A ROW REF is `=` to a map with the same entries, and hashes the same.
        // The opposite call to table-versus-vector below, and coherently so: a
        // table is a distinct kind of thing, a row IS a map seen cheaply.
        if (Table.isTableRef(rt, a) || Table.isTableRef(rt, b)) {
            int bas = rt.Mark();
            // BOTH operands are rooted before either is materialised.
            // `refToMap` allocates a map and assoc's every column into it, so
            // it collects -- and `0031` is that a value in a host local does
            // not survive an allocation. Reading `b` after materialising `a`
            // was reading the address `b` used to be at: measured on the
            // native runtime, one miscompare in four thousand comparisons of
            // EQUAL values, deterministically, and only when `b` is young
            // enough for the nursery to move it.
            int ai = rt.Push(a), bi = rt.Push(b);
            long ma = Table.isTableRef(rt, rt.R(ai)) ? Table.refToMap(rt, rt.R(ai)) : rt.R(ai);
            int mi = rt.Push(ma);
            long mb = Table.isTableRef(rt, rt.R(bi)) ? Table.refToMap(rt, rt.R(bi)) : rt.R(bi);
            int mj = rt.Push(mb);
            bool same = Equal(rt, rt.R(mi), rt.R(mj));
            rt.PopTo(bas);
            return same;
        }
        // A TABLE is NOT `=` to a vector of maps. Refusing that is what frees
        // `hash` to be columnar, and is why a table prints as its own literal.
        if (ta == Obj.TyTable || tb == Obj.TyTable) {
            if (ta != tb) return false;
            int na = Table.tableCount(rt, a), nb = Table.tableCount(rt, b);
            if (na != nb) return false;
            if (!Table.schemaEq(rt, rt.Slot(a, Table.TB_SCHEMA), rt.Slot(b, Table.TB_SCHEMA)))
                return false;
            // BOTH SIDES ROOTED: `tableRef` allocates and `a`/`b` are host
            // locals (`doc/decisions/0031`).
            int bas = rt.Mark();
            int ai = rt.Push(a);
            int bi = rt.Push(b);
            for (int i = 0; i < na; i++) {
                // BOTH REFS ROOTED -- the a-side ref was read into the
                // argument slot before the b-side `tableRef` allocated. Same
                // rule as the tables above, one level down. See the JVM.
                int ri = rt.Push(Table.tableRef(rt, rt.R(ai), i));
                int rj = rt.Push(Table.tableRef(rt, rt.R(bi), i));
                bool same = Equal(rt, rt.R(ri), rt.R(rj));
                rt.PopTo(ri);
                if (!same) { rt.PopTo(bas); return false; }
            }
            rt.PopTo(bas);
            return true;
        }
        if (ta == Obj.TyTagged || tb == Obj.TyTagged) {
            if (ta != tb) return false;
            return Equal(rt, rt.Slot(a, 0), rt.Slot(b, 0))
                && Equal(rt, rt.Slot(a, 1), rt.Slot(b, 1));
        }
        if (ta != tb) return false;
        if (ta == Obj.TyStr) {
            int la = Obj.Len(rt.gc.sp, Val.AsHeap(a)), lb = Obj.Len(rt.gc.sp, Val.AsHeap(b));
            if (la != lb) return false;
            // Both interned and not bit-equal means NOT EQUAL, with no need to
            // look at the bytes at all.
            if (la <= Interns.InternMax) return false;
            return SameBytes(Str.Bytes(rt, a), Str.Bytes(rt, b));
        }
        if (ta == Obj.TySym) {
            // By (ns, name), NOT by identity. `with-meta` makes a DISTINCT
            // object that must still be `=` -- and the analyser keys its
            // environment by symbols carrying source metadata, so treating
            // interning as identity here made every local look unbound.
            return rt.Slot(a, 0) == rt.Slot(b, 0) && rt.Slot(a, 1) == rt.Slot(b, 1);
        }
        if (ta == Obj.TyKw) {
            // A keyword carries no metadata slot, so interning IS identity for
            // it and `a == b` above already answered.
            return false;
        }
        if (ta == Obj.TyExinfo)
            return Equal(rt, rt.Slot(a, 0), rt.Slot(b, 0)) && Equal(rt, rt.Slot(a, 1), rt.Slot(b, 1));
        // Everything else is compared by IDENTITY: an atom, a var, a regex, a
        // function. That is Clojure's rule and not a gap.
        return false;
    }

    /// Elementwise, over the SEQUENTIAL abstraction -- so a vector and a list
    /// with the same elements are equal, which is what `Category` is for.
    static bool SeqEq(Rt rt, long a, long b) {
        int bas = rt.Mark();
        // `b` ROOTED FIRST -- `Seq` allocates, so holding `b` in a C# local
        // across `Seq(a)` leaves it pointing at a moved object. See the JVM.
        int bi = rt.Push(b);
        int x = rt.Push(Seqs.Seq(rt, a));
        int y = rt.Push(Seqs.Seq(rt, rt.R(bi)));
        bool ok = true;
        for (;;) {
            bool ex = Val.IsNil(rt.R(x)), ey = Val.IsNil(rt.R(y));
            if (ex || ey) { ok = ex && ey; break; }
            rt.ChargeWork(1);
            // BOTH sides ROOTED before the other is computed -- see the JVM's
            // `seqEq` for the failure this shape produces. `First` and `Next`
            // both allocate, so holding one side's result in a C# local across
            // the other side's call leaves it pointing at a moved object.
            int fx = rt.Push(Seqs.First(rt, rt.R(x)));
            int fy = rt.Push(Seqs.First(rt, rt.R(y)));
            bool same = Equal(rt, rt.R(fx), rt.R(fy));
            rt.PopTo(fx);
            if (!same) { ok = false; break; }
            int nxi = rt.Push(Seqs.Next(rt, rt.R(x)));
            long ny = Seqs.Next(rt, rt.R(y));
            rt.SetR(y, ny);
            rt.SetR(x, rt.R(nxi));
            rt.PopTo(nxi);
        }
        rt.PopTo(bas);
        return ok;
    }

    /// The hash of a value, agreeing with `eq` above and with Clojure's.
    public static int HashValue(Rt rt, long v) {
        if (Val.IsDouble(v)) return Hash.HashDouble(Val.AsDouble(v));
        if (Val.IsNil(v)) return 0;
        if (v == Val.True) return flint.rt.Hash.HashTrue;
        if (v == Val.False) return flint.rt.Hash.HashFalse;
        if (Val.IsFixnum(v)) return flint.rt.Hash.HashLong(Val.AsFixnum(v));
        if (Val.IsInlineStr(v)) return Hash.HashString(Val.InlineBytes(v));
        if (Val.IsInlineKw(v)) return Hash.HashKeyword(null, Val.InlineBytes(v));
        if (!Val.IsHeap(v)) return 0;
        switch (Obj.Ty(rt.gc.sp, Val.AsHeap(v))) {
            case Obj.TyStr: return Hash.HashString(Str.Bytes(rt, v));
            // WALKED and cached per node, not flattened. Flattening was here
            // for the caching, which is real; `RP_HASH` gives the same caching
            // without spending the tree (`doc/decisions/0011`).
            case Obj.TyRope: return Str.RopeHash(rt, v);
            // Byte strings hash by CONTENT across both tiers, walked and cached
            // per node rather than flattened (`doc/decisions/0011`).
            case Obj.TyBytes:
            case Obj.TyBrope: return Bytes.Hash(rt, v);
            case Obj.TyKw: return Hash.HashKeyword(NsBytes(rt, v), Str.Bytes(rt, rt.Slot(v, 1)));
            case Obj.TySym: return Hash.HashSymbol(NsBytes(rt, v), Str.Bytes(rt, rt.Slot(v, 1)));
            case Obj.TyVec: {
                // Cached in the vector's own header, as the native runtime has
                // always done. `HashValue` on the elements can allocate, so the
                // vector is ROOTED across the walk -- the write at the end would
                // otherwise land on a stale address, which is not a wrong hash
                // but a corrupted heap (`doc/decisions/0031`).
                long cached = rt.Slot(v, Vec.V_HASH);
                if (Val.IsFixnum(cached)) return (int) Val.AsFixnum(cached);
                int bas = rt.Mark();
                int vi = rt.Push(v);
                int n = Vec.Count(rt, rt.R(vi)), acc = 1;
                for (int i = 0; i < n; i++) {
                    acc = flint.rt.Hash.OrderedStep(acc, HashValue(rt, Vec.Nth(rt, rt.R(vi), i, Val.NotFound)));
                }
                int h = flint.rt.Hash.MixCollHash(acc, n);
                rt.SetSlot(Val.AsHeap(rt.R(vi)), Vec.V_HASH, Val.Fixnum(h));
                rt.PopTo(bas);
                return h;
            }
            case Obj.TyArraymap:
            case Obj.TyHashmap: return Maps.Hash(rt, v);
            // Both halves, so two equal tagged literals share a bucket.
            // A ROW REF hashes as the map it is, so it lands in the same
            // bucket as an equal map.
            case Obj.TyTableref: return HashValue(rt, Table.refToMap(rt, v));
            case Obj.TyTable: {
                int bas = rt.Mark();
                int vi = rt.Push(v);
                int n = Table.tableCount(rt, rt.R(vi)), acc = 1;
                for (int i = 0; i < n; i++) {
                    int ri = rt.Push(Table.tableRef(rt, rt.R(vi), i));
                    acc = acc * 31 + HashValue(rt, rt.R(ri));
                    rt.PopTo(ri);
                }
                rt.PopTo(bas);
                return flint.rt.Hash.HashInt(acc ^ n);
            }
            case Obj.TyTagged:
                return HashValue(rt, rt.Slot(v, 0)) * 31 + HashValue(rt, rt.Slot(v, 1));
            case Obj.TySet: return Sets.Hash(rt, v);
            default: {
                if (rt.IsSeq(v)) {
                    int bas = rt.Mark();
                    int s = rt.Push(Seqs.Seq(rt, v));
                    int acc = 1, n = 0;
                    while (!Val.IsNil(rt.R(s))) {
                        // A TICK: a seq's length is not known until it ends.
                        if (!rt.ChargeTick(n, 1, "hash")) { rt.PopTo(bas); return 0; }
                        acc = flint.rt.Hash.OrderedStep(acc, HashValue(rt, Seqs.First(rt, rt.R(s))));
                        n++;
                        long nx = Seqs.Next(rt, rt.R(s));
                        rt.SetR(s, nx);
                    }
                    rt.PopTo(bas);
                    return flint.rt.Hash.MixCollHash(acc, n);
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
