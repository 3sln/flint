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

    public static boolean isSet(Rt rt, long v) {
        return Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_SET;
    }

    static long newSet(Rt rt, long m, long meta) {
        int base = rt.mark();
        int mi = rt.push(m), mt = rt.push(meta);
        long a = rt.alloc(TY_SET, 3);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, S_MAP, rt.r(mi));
        rt.setSlot(a, S_META, rt.r(mt));
        rt.setSlot(a, S_HASH, Val.NIL);
        rt.popTo(base);
        return Val.heap(a);
    }

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

    public static int count(Rt rt, long s) { return Mapcore.mapCount(rt, rt.slot(s, S_MAP)); }

    public static boolean contains(Rt rt, long s, long x) {
        return Mapread.mapContains(rt, rt.slot(s, S_MAP), x);
    }

    /// `get` on a set returns the STORED element, not the probe. That is what
    /// makes a set usable for canonicalisation -- `(get s x)` hands back the
    /// copy the set is holding, which may not be the object passed in.
    public static long get(Rt rt, long s, long x, long notFound) {
        return Mapread.mapGet(rt, rt.slot(s, S_MAP), x, notFound);
    }

    public static long conj(Rt rt, long s, long x) {
        int base = rt.mark();
        int si = rt.push(s), xi = rt.push(x);
        long nm = Mapwrite.mapAssoc(rt, rt.slot(rt.r(si), S_MAP), rt.r(xi), rt.r(xi));
        int ni = rt.push(nm);
        long out = newSet(rt, rt.r(ni), rt.slot(rt.r(si), S_META));
        rt.popTo(base);
        return out;
    }

    public static long disj(Rt rt, long s, long x) {
        int base = rt.mark();
        int si = rt.push(s), xi = rt.push(x);
        long nm = Mapwrite.mapDissoc(rt, rt.slot(rt.r(si), S_MAP), rt.r(xi));
        int ni = rt.push(nm);
        long out = newSet(rt, rt.r(ni), rt.slot(rt.r(si), S_META));
        rt.popTo(base);
        return out;
    }

    /// The elements as a vector, which is what `seq` walks.
    public static long elementVector(Rt rt, long s) {
        if (!rt.chargeChecked(count(rt, s), "seq of a set")) return Val.NIL;
        int base = rt.mark();
        int si = rt.push(s);
        int at = rt.mark();
        int n = Maps.entries(rt, rt.slot(rt.r(si), S_MAP));
        int ai = rt.push(Vec.empty(rt));
        // The KEY of each pair; the value is the same object.
        for (int i = 0; i < n; i++) rt.setR(ai, Vec.conj(rt, rt.r(ai), rt.r(at + 2 * i)));
        long out = rt.r(ai);
        rt.popTo(base);
        return out;
    }

    // --- transients ---------------------------------------------------------
    //
    // `TY_TSET [tmap, edit]`. A set is a map from element to itself, so its
    // transient is a transient MAP with a wrapper -- one trie, one transient.

    public static final int TS_MAP = 0, TS_EDIT = 1;

    public static boolean isTransient(Rt rt, long v) {
        return Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_TSET;
    }

    public static long transientOf(Rt rt, long s) {
        int base = rt.mark();
        int ti = rt.push(Maps.transientOf(rt, rt.slot(s, S_MAP)));
        long a = rt.alloc(TY_TSET, 2);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, TS_MAP, rt.r(ti));
        rt.setSlot(a, TS_EDIT, rt.slot(rt.r(ti), Maps.TM_EDIT));
        rt.popTo(base);
        return Val.heap(a);
    }

    public static long tconj(Rt rt, long t, long x) {
        int base = rt.mark();
        int ti = rt.push(t), xi = rt.push(x);
        Maps.tassoc(rt, rt.slot(rt.r(ti), TS_MAP), rt.r(xi), rt.r(xi));
        long out = rt.r(ti);
        rt.popTo(base);
        return out;
    }

    public static long tdisj(Rt rt, long t, long x) {
        int base = rt.mark();
        int ti = rt.push(t), xi = rt.push(x);
        Maps.tdissoc(rt, rt.slot(rt.r(ti), TS_MAP), rt.r(xi));
        long out = rt.r(ti);
        rt.popTo(base);
        return out;
    }

    public static int tcount(Rt rt, long t) { return Maps.tcount(rt, rt.slot(t, TS_MAP)); }

    /// `get` on a transient set, which answers the STORED element for the same
    /// reason the persistent one does.
    public static long tget(Rt rt, long t, long x, long notFound) {
        return Maps.tget(rt, rt.slot(t, TS_MAP), x, notFound);
    }

    public static long tpersistent(Rt rt, long t) {
        int base = rt.mark();
        int ti = rt.push(t);
        int mi = rt.push(Maps.tpersistent(rt, rt.slot(rt.r(ti), TS_MAP)));
        rt.setSlot(Val.asHeap(rt.r(ti)), TS_EDIT, Val.NIL);
        long out = newSet(rt, rt.r(mi), Val.NIL);
        rt.popTo(base);
        return out;
    }

    public static boolean eq(Rt rt, long a, long b) {
        if (count(rt, a) != count(rt, b)) return false;
        return Maps.eq(rt, rt.slot(a, S_MAP), rt.slot(b, S_MAP));
    }

    /// UNORDERED, as Clojure hashes sets: the element hashes are summed.
    public static int hash(Rt rt, long s) {
        int base = rt.mark();
        int si = rt.push(s);
        int at = rt.mark();
        int n = Maps.entries(rt, rt.slot(rt.r(si), S_MAP));
        int acc = 0;
        for (int i = 0; i < n; i++) acc = com._3sln.flint.kgen.rt.Hash.unorderedStep(acc, Eq.hashValue(rt, rt.r(at + 2 * i)));
        rt.popTo(base);
        return com._3sln.flint.kgen.rt.Hash.mixCollHash(acc, n);
    }
}
