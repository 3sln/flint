namespace Flint.Rt;

using _3sln.Flint.Kgen.Rt;


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

    // A SET IS A MAP WHOSE VALUES ARE ITS KEYS -- see the Java copy.
    public static bool IsSet(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Setcore.IsSet(rt, v); }
    static long NewSet(Rt rt, long m, long meta) { return global::_3sln.Flint.Kgen.Rt.Setcore.NewSet(rt, m, meta); }
    public static int Count(Rt rt, long s) { return global::_3sln.Flint.Kgen.Rt.Setcore.SetCount(rt, s); }
    public static bool Contains(Rt rt, long s, long x) { return global::_3sln.Flint.Kgen.Rt.Setcore.SetContains(rt, s, x); }
    public static long Get(Rt rt, long s, long x, long notFound) { return global::_3sln.Flint.Kgen.Rt.Setcore.SetGet(rt, s, x, notFound); }
    public static long Conj(Rt rt, long s, long x) { return global::_3sln.Flint.Kgen.Rt.Setcore.SetConj(rt, s, x); }
    public static long Disj(Rt rt, long s, long x) { return global::_3sln.Flint.Kgen.Rt.Setcore.SetDisj(rt, s, x); }

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

    /// GENERATED -- see `kin/collvec.kin` and the JVM copy.
    public static long ElementVector(Rt rt, long s) {
        return global::_3sln.Flint.Kgen.Rt.Collvec.SetElementVector(rt, s);
    }

    // --- transients ---------------------------------------------------------
    //
    // `TY_TSET [tmap, edit]`. A set is a map from element to itself, so its
    // transient is a transient MAP with a wrapper -- one trie, one transient.

    public const int TS_MAP = 0, TS_EDIT = 1;

    public static bool IsTransient(Rt rt, long v) =>
        Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyTset;

    // `TransientOf`, `TConj`, `TDisj`, `TCount`, `TGet` and `TPersistent` are
    // GENERATED, from `kin/maptrans.kin`.

    // `set-eq` USED TO LIVE HERE, hand-written once per runtime and declared to
    // kin as a linked form. It was never a primitive: a count comparison, two slot
    // reads, and a delegation to `map-eq` -- and `set-count` and `map-eq` were
    // already generated, so the three copies were three spellings of a call.
    // `kin/setcore.kin` has it now, and this was the last linked form in the set
    // surface, so the link namespace is gone with it.

    /// A set's hash, GENERATED -- see `kin/collhash.kin` and the JVM copy.
    public static int Hash(Rt rt, long s) {
        return global::_3sln.Flint.Kgen.Rt.Collhash.HashSet(rt, s);
    }
}
