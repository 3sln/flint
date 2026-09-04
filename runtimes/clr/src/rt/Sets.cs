namespace Flint.Rt;

using flint.rt;


/// Sets, ported from `runtime/src/set.rs`: a map from element to ITSELF.
///
/// `TY_SET [map, meta, hash]`.
///
/// Storing the element as its own value costs a slot per element compared with
/// a set-specific CHAMP node that stores only keys. It buys `get` returning the
/// STORED element -- which is what Clojure does, and what makes sets usable for
/// canonicalisation -- and one implementation of the trie instead of two. The
/// cost is named rather than hidden.
public static class Sets {

    public const int S_MAP = 0, S_META = 1, S_HASH = 2;

    public static bool IsSet(Rt rt, long v) {
        return Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TySet;
    }

    static long NewSet(Rt rt, long m, long meta) {
        int bas = rt.Mark();
        int mi = rt.Push(m), mt = rt.Push(meta);
        long a = rt.Alloc(Obj.TySet, 3);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, S_MAP, rt.R(mi));
        rt.SetSlot(a, S_META, rt.R(mt));
        rt.SetSlot(a, S_HASH, Val.Nil);
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    public static long Empty(Rt rt) {
        long sg = rt.roots.shared.Singletons[Rt.SingEmptySet];
        if (!Val.IsNil(sg)) return sg;
        return NewEmpty(rt);
    }

    internal static long NewEmpty(Rt rt) {
        int bas = rt.Mark();
        int mi = rt.Push(Maps.Empty(rt));
        long outv = NewSet(rt, rt.R(mi), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }

    public static int Count(Rt rt, long s) { return Mapcore.MapCount(rt, rt.Slot(s, S_MAP)); }

    public static bool Contains(Rt rt, long s, long x) {
        return Mapread.MapContains(rt, rt.Slot(s, S_MAP), x);
    }

    /// `get` on a set returns the STORED element, not the probe. That is what
    /// makes a set usable for canonicalisation -- `(get s x)` hands back the
    /// copy the set is holding, which may not be the object passed in.
    public static long Get(Rt rt, long s, long x, long notFound) {
        return Mapread.MapGet(rt, rt.Slot(s, S_MAP), x, notFound);
    }

    public static long Conj(Rt rt, long s, long x) {
        int bas = rt.Mark();
        int si = rt.Push(s), xi = rt.Push(x);
        long nm = Mapwrite.MapAssoc(rt, rt.Slot(rt.R(si), S_MAP), rt.R(xi), rt.R(xi));
        int ni = rt.Push(nm);
        long outv = NewSet(rt, rt.R(ni), rt.Slot(rt.R(si), S_META));
        rt.PopTo(bas);
        return outv;
    }

    public static long Disj(Rt rt, long s, long x) {
        int bas = rt.Mark();
        int si = rt.Push(s), xi = rt.Push(x);
        long nm = Mapwrite.MapDissoc(rt, rt.Slot(rt.R(si), S_MAP), rt.R(xi));
        int ni = rt.Push(nm);
        long outv = NewSet(rt, rt.R(ni), rt.Slot(rt.R(si), S_META));
        rt.PopTo(bas);
        return outv;
    }

    /// The elements as a vector, which is what `seq` walks.
    public static long ElementVector(Rt rt, long s) {
        if (!rt.ChargeChecked(Count(rt, s), "seq of a set")) return Val.Nil;
        int bas = rt.Mark();
        int si = rt.Push(s);
        int at = rt.Mark();
        int n = Maps.Entries(rt, rt.Slot(rt.R(si), S_MAP));
        int ai = rt.Push(Vec.Empty(rt));
        // The KEY of each pair; the value is the same object.
        for (int i = 0; i < n; i++) rt.SetR(ai, Vec.Conj(rt, rt.R(ai), rt.R(at + 2 * i)));
        long outv = rt.R(ai);
        rt.PopTo(bas);
        return outv;
    }

    // --- transients ---------------------------------------------------------
    //
    // `TY_TSET [tmap, edit]`. A set is a map from element to itself, so its
    // transient is a transient MAP with a wrapper -- one trie, one transient.

    public const int TS_MAP = 0, TS_EDIT = 1;

    public static bool IsTransient(Rt rt, long v) =>
        Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyTset;

    public static long TransientOf(Rt rt, long s) {
        int bas = rt.Mark();
        int ti = rt.Push(Maps.TransientOf(rt, rt.Slot(s, S_MAP)));
        long a = rt.Alloc(Obj.TyTset, 2);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, TS_MAP, rt.R(ti));
        rt.SetSlot(a, TS_EDIT, rt.Slot(rt.R(ti), Maps.TM_EDIT));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    public static long TConj(Rt rt, long t, long x) {
        int bas = rt.Mark();
        int ti = rt.Push(t), xi = rt.Push(x);
        Maps.TAssoc(rt, rt.Slot(rt.R(ti), TS_MAP), rt.R(xi), rt.R(xi));
        long outv = rt.R(ti);
        rt.PopTo(bas);
        return outv;
    }

    public static long TDisj(Rt rt, long t, long x) {
        int bas = rt.Mark();
        int ti = rt.Push(t), xi = rt.Push(x);
        Maps.TDissoc(rt, rt.Slot(rt.R(ti), TS_MAP), rt.R(xi));
        long outv = rt.R(ti);
        rt.PopTo(bas);
        return outv;
    }

    public static int TCount(Rt rt, long t) => Maps.TCount(rt, rt.Slot(t, TS_MAP));

    /// `get` on a transient set, which answers the STORED element for the same
    /// reason the persistent one does.
    public static long TGet(Rt rt, long t, long x, long notFound) =>
        Maps.TGet(rt, rt.Slot(t, TS_MAP), x, notFound);

    public static long TPersistent(Rt rt, long t) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int mi = rt.Push(Maps.TPersistent(rt, rt.Slot(rt.R(ti), TS_MAP)));
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TS_EDIT, Val.Nil);
        long outv = NewSet(rt, rt.R(mi), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }

    public static bool Eq(Rt rt, long a, long b) {
        if (Count(rt, a) != Count(rt, b)) return false;
        return Maps.Eq(rt, rt.Slot(a, S_MAP), rt.Slot(b, S_MAP));
    }

    /// UNORDERED, as Clojure hashes sets: the element hashes are summed.
    public static int Hash(Rt rt, long s) {
        int bas = rt.Mark();
        int si = rt.Push(s);
        int at = rt.Mark();
        int n = Maps.Entries(rt, rt.Slot(rt.R(si), S_MAP));
        int acc = 0;
        for (int i = 0; i < n; i++) acc = flint.rt.Hash.UnorderedStep(acc, Flint.Rt.Eq.HashValue(rt, rt.R(at + 2 * i)));
        rt.PopTo(bas);
        return flint.rt.Hash.MixCollHash(acc, n);
    }
}
