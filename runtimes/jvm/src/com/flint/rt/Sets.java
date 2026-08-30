package com.flint.rt;

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
        int base = rt.mark();
        int mi = rt.push(Maps.empty(rt));
        long out = newSet(rt, rt.r(mi), Val.NIL);
        rt.popTo(base);
        return out;
    }

    public static int count(Rt rt, long s) { return Maps.count(rt, rt.slot(s, S_MAP)); }

    public static boolean contains(Rt rt, long s, long x) {
        return Maps.contains(rt, rt.slot(s, S_MAP), x);
    }

    /// `get` on a set returns the STORED element, not the probe. That is what
    /// makes a set usable for canonicalisation -- `(get s x)` hands back the
    /// copy the set is holding, which may not be the object passed in.
    public static long get(Rt rt, long s, long x, long notFound) {
        return Maps.get(rt, rt.slot(s, S_MAP), x, notFound);
    }

    public static long conj(Rt rt, long s, long x) {
        int base = rt.mark();
        int si = rt.push(s), xi = rt.push(x);
        long nm = Maps.assoc(rt, rt.slot(rt.r(si), S_MAP), rt.r(xi), rt.r(xi));
        int ni = rt.push(nm);
        long out = newSet(rt, rt.r(ni), rt.slot(rt.r(si), S_META));
        rt.popTo(base);
        return out;
    }

    public static long disj(Rt rt, long s, long x) {
        int base = rt.mark();
        int si = rt.push(s), xi = rt.push(x);
        long nm = Maps.dissoc(rt, rt.slot(rt.r(si), S_MAP), rt.r(xi));
        int ni = rt.push(nm);
        long out = newSet(rt, rt.r(ni), rt.slot(rt.r(si), S_META));
        rt.popTo(base);
        return out;
    }

    /// The elements as a vector, which is what `seq` walks.
    public static long elementVector(Rt rt, long s) {
        int base = rt.mark();
        int si = rt.push(s);
        int at = rt.mark();
        int n = Maps.entries(rt, rt.slot(rt.r(si), S_MAP), at);
        int ai = rt.push(Vec.empty(rt));
        // The KEY of each pair; the value is the same object.
        for (int i = 0; i < n; i++) rt.setR(ai, Vec.conj(rt, rt.r(ai), rt.r(at + 2 * i)));
        long out = rt.r(ai);
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
        int n = Maps.entries(rt, rt.slot(rt.r(si), S_MAP), at);
        int acc = 0;
        for (int i = 0; i < n; i++) acc = Hash.unorderedStep(acc, Eq.hashValue(rt, rt.r(at + 2 * i)));
        rt.popTo(base);
        return Hash.mixCollHash(acc, n);
    }
}
