package com.flint.rt;

import com._3sln.flint.kgen.rt.Mapwrite;

import com._3sln.flint.kgen.rt.Mapread;

import com._3sln.flint.kgen.rt.Mapcore;

import static com.flint.rt.Obj.*;

/// Sets, ported from `runtime/src/set.rs`: a map from element to ITSELF.
///
/// `TY_SET [map, meta, hash]`.
///
/// Storing the element as its own value costs a slot per element compared with
/// a set-specific CHAMP node that stores only keys. It buys `get` returning the
/// STORED element -- which is what Clojure does, and what makes sets usable for
/// canonicalisation -- and one implementation of the trie instead of two. The
/// cost is named rather than hidden.
public final class Sets {
    private Sets() {}

    public static final int S_MAP = 0, S_META = 1, S_HASH = 2;

    // A SET IS A MAP WHOSE VALUES ARE ITS KEYS, generated from
    // `kin/setcore.kin`.
    public static boolean isSet(Rt rt, long v) { return com._3sln.flint.kgen.rt.Setcore.isSet(rt, v); }
    static long newSet(Rt rt, long m, long meta) { return com._3sln.flint.kgen.rt.Setcore.newSet(rt, m, meta); }
    public static int count(Rt rt, long s) { return com._3sln.flint.kgen.rt.Setcore.setCount(rt, s); }
    public static boolean contains(Rt rt, long s, long x) { return com._3sln.flint.kgen.rt.Setcore.setContains(rt, s, x); }
    public static long get(Rt rt, long s, long x, long notFound) { return com._3sln.flint.kgen.rt.Setcore.setGet(rt, s, x, notFound); }
    public static long conj(Rt rt, long s, long x) { return com._3sln.flint.kgen.rt.Setcore.setConj(rt, s, x); }
    public static long disj(Rt rt, long s, long x) { return com._3sln.flint.kgen.rt.Setcore.setDisj(rt, s, x); }

    public static long empty(Rt rt) {
        long sg = rt.roots.shared.singletons[Rt.SING_EMPTY_SET];
        if (!Val.isNil(sg)) return sg;
        return newEmpty(rt);
    }

    static long newEmpty(Rt rt) {
        int base = rt.mark();
        int mi = rt.push(Maps.empty(rt));
        long out = newSet(rt, rt.r(mi), Val.NIL);
        rt.popTo(base);
        return out;
    }

    /// GENERATED -- see `kin/collvec.kin`. This walked the trie with
    /// `entries`, which pushes every key AND value onto the shadow stack
    /// before the vector is built; the walk conjes as it goes and holds
    /// O(depth).
    public static long elementVector(Rt rt, long s) {
        return com._3sln.flint.kgen.rt.Collvec.setElementVector(rt, s);
    }

    // --- transients ---------------------------------------------------------
    //
    // `TY_TSET [tmap, edit]`. A set is a map from element to itself, so its
    // transient is a transient MAP with a wrapper -- one trie, one transient.

    public static final int TS_MAP = 0, TS_EDIT = 1;


    // `transientOf`, `tconj`, `tdisj`, `tcount`, `tget` and `tpersistent` are
    // GENERATED, from `kin/maptrans.kin`.

    // `set-eq` USED TO LIVE HERE, hand-written once per runtime and declared to
    // kin as a linked form. It was never a primitive: a count comparison, two slot
    // reads, and a delegation to `map-eq` -- and `set-count` and `map-eq` were
    // already generated, so the three copies were three spellings of a call.
    // `kin/setcore.kin` has it now, and this was the last linked form in the set
    // surface, so the link namespace is gone with it.

    /// A set's hash, GENERATED -- see `kin/collhash.kin` and `Maps.hash`.
    public static int hash(Rt rt, long s) {
        return com._3sln.flint.kgen.rt.Collhash.hashSet(rt, s);
    }
}
