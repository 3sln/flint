package com.flint.rt;

import flint.rt.Mapread;

import static com.flint.rt.Obj.*;
import static flint.rt.Assoc.*;
import static flint.rt.Mapcore.*;
import static flint.rt.Mapread.*;
import static flint.rt.Mapwrite.*;
import static flint.rt.Champ.*;
import static flint.rt.Collnode.*;
import static flint.rt.Dissoc.*;
import static flint.rt.Find.*;
import static flint.rt.Interns.*;
import static flint.rt.Nodeclass.*;

/// Maps: a small insertion-ordered array-map, and a **CHAMP** hash-array
/// mapped trie above it. Ported from `runtime/src/map.rs`.
///
/// ## Why CHAMP and not Clojure's HAMT
///
/// Steindorfer &amp; Vinju's CHAMP (OOPSLA 2015). A CHAMP node carries TWO
/// bitmaps -- `datamap` for entries stored inline and `nodemap` for sub-nodes
/// -- with entries packed at the front and sub-nodes packed at the back:
///
///   TY_BMNODE  [edit, datamap, nodemap, k0,v0, k1,v1, ..., nodeN..node0]
///   TY_COLLNODE[edit, hash, k0,v0, ...]                 -- full hash collision
///
/// Three things fall out, and all three matter:
///
/// * Nodes are smaller and denser. Clojure's `BitmapIndexedNode` stores a null
///   key beside a sub-node pointer, wasting a slot per child, and promotes to a
///   32-wide `ArrayNode` at 16 children. CHAMP needs neither.
/// * The representation is CANONICAL. Clojure's HAMT can represent the same map
///   two ways depending on insertion and deletion history, because deleting
///   does not un-inline a node that has shrunk to one entry. CHAMP always
///   collapses, so equal maps have identical structure.
/// * Iteration does not test each slot's type: entries are exactly the first
///   `2*popcount(datamap)` slots.
///
/// ## Where the map is an array-map
///
/// Up to `ARRAY_MAP_MAX` entries a map is a flat `[meta, hash, k,v, ...]`, as
/// in Clojure. For a compiler -- the workload on flint's own critical path --
/// most maps are AST nodes with a handful of keys, and a linear scan over
/// bit-comparable keywords beats descending a trie. `assoc` past the threshold
/// promotes to CHAMP.
public final class Maps {
    private Maps() {}

    public static final int ARRAY_MAP_MAX = 8;

    // array-map layout
    public static final int AM_META = 0, AM_HASH = 1, AM_BASE = 2;
    // hash-map layout
    public static final int HM_CNT = 0, HM_ROOT = 1, HM_META = 2, HM_HASH = 3;
    // CHAMP bitmap node layout
    // PUBLIC because the CHAMP functions that read them are generated into
    // package `flint.rt` now. Rust widens the same constants to
    // `pub(crate)` and C# to `internal`; on the JVM the module boundary is a
    // package boundary, so this is what it costs.
    public static final int BN_EDIT = 0, BN_DATAMAP = 1, BN_NODEMAP = 2, BN_BASE = 3;
    // collision node layout
    public static final int CN_EDIT = 0, CN_HASH = 1, CN_BASE = 2;

    public static final int HASH_BITS = 5, HASH_WIDTH = 32;

    public static int mask(int h, int shift) { return (h >>> shift) & 0x1f; }
    public static int bitpos(int h, int shift) { return 1 << mask(h, shift); }
    public static int indexOf(int bitmap, int bit) { return Integer.bitCount(bitmap & (bit - 1)); }

    public static int olen(Rt rt, long v) { return len(rt.gc.sp, Val.asHeap(v)); }

    // --- node primitives ----------------------------------------------------





    // --- lookup -------------------------------------------------------------


    // --- structural copies ---------------------------------------------------






    // --- the map objects -----------------------------------------------------




    /// The SINGLETON empty map. Allocating a fresh one per call is what made
    /// this port bill more gas than the Rust runtime for the same program.
    public static long empty(Rt rt) {
        long s = rt.roots.shared.singletons[Rt.SING_EMPTY_MAP];
        return Val.isNil(s) ? newArrayMap(rt, 0) : s;
    }

    /// The one allocation `initSingletons` makes.
    static long newEmpty(Rt rt) { return newArrayMap(rt, 0); }


    static void amSet(Rt rt, long m, int i, long v) { rt.setSlot(Val.asHeap(m), i, v); }







    // --- traversal -----------------------------------------------------------

    /// Every key and value, flattened into the shadow stack above `at`, as
    /// `k,v,k,v,...`. Returns the number of PAIRS.
    ///
    /// Materialised rather than a callback because the callers -- equality,
    /// hashing, `seq` -- all want to walk twice or in another order, and a
    /// callback that allocated mid-walk would need every node rooted anyway.
    public static int entries(Rt rt, long m, int at) {
        if (!Val.isHeap(m)) return 0;
        int t = ty(rt.gc.sp, Val.asHeap(m));
        if (t == TY_ARRAYMAP) {
            int n = mapCount(rt, m);
            int mi = rt.push(m);
            for (int i = 0; i < n; i++) {
                rt.push(amKey(rt, rt.r(mi), i));
                rt.push(amVal(rt, rt.r(mi), i));
            }
            // The map itself was pushed first; slide the pairs down over it.
            for (int i = 0; i < 2 * n; i++) rt.setR(at + i, rt.r(at + 1 + i));
            rt.popTo(at + 2 * n);
            return n;
        }
        if (t == TY_HASHMAP) {
            long root = rt.slot(m, HM_ROOT);
            if (Val.isNil(root)) return 0;
            return nodeEntries(rt, root, at);
        }
        return 0;
    }

    static int nodeEntries(Rt rt, long node, int at) {
        int ni = rt.push(node);
        int wrote = 0;
        if (!isBmnode(rt, rt.r(ni))) {
            int cnt = cnCount(rt, rt.r(ni));
            for (int i = 0; i < cnt; i++) {
                rt.push(cnKey(rt, rt.r(ni), i));
                rt.push(cnVal(rt, rt.r(ni), i));
                wrote++;
            }
        } else {
            int ne = Integer.bitCount(bnDatamap(rt, rt.r(ni)));
            int nn = Integer.bitCount(bnNodemap(rt, rt.r(ni)));
            for (int i = 0; i < ne; i++) {
                rt.push(bnKey(rt, rt.r(ni), i));
                rt.push(bnVal(rt, rt.r(ni), i));
                wrote++;
            }
            for (int j = 0; j < nn; j++) {
                wrote += nodeEntries(rt, bnNode(rt, rt.r(ni), j), rt.mark());
            }
        }
        // Slide down over the node handle, which was pushed first.
        for (int i = 0; i < 2 * wrote; i++) rt.setR(at + i, rt.r(at + 1 + i));
        rt.popTo(at + 2 * wrote);
        return wrote;
    }

    // --- transients ---------------------------------------------------------
    //
    // `TY_TMAP [cnt, root, edit]`. The trie is the SAME CHAMP: a node whose
    // ownership token matches this transient's is written in place, and any
    // other is copied once and thereafter owned. That is the whole difference,
    // and it is why `nodeAssoc` already takes an `edit` argument -- the
    // persistent path passes NIL, which owns nothing and so copies everything.

    public static final int TM_CNT = 0, TM_ROOT = 1, TM_EDIT = 2;

    public static boolean isTransient(Rt rt, long v) {
        return Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_TMAP;
    }

    public static long transientOf(Rt rt, long m) {
        int base = rt.mark();
        int mi = rt.push(m);
        // An array-map becomes a CHAMP FIRST: one transient implementation, and
        // the workload that uses transients is the one with many entries.
        long hm = isArrayMap(rt, rt.r(mi)) ? promote(rt, rt.r(mi)) : rt.r(mi);
        int hi = rt.push(hm);
        int ei = rt.push(Vec.newEditToken(rt));
        long a = rt.alloc(TY_TMAP, 3);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        long h = rt.r(hi);
        rt.setSlot(a, TM_CNT, Val.fixnum(mapCount(rt, h)));
        rt.setSlot(a, TM_ROOT, rt.slot(h, HM_ROOT));
        rt.setSlot(a, TM_EDIT, rt.r(ei));
        rt.popTo(base);
        return Val.heap(a);
    }

    public static long tget(Rt rt, long t, long k, long notFound) {
        int base = rt.mark();
        int ti = rt.push(t), ki = rt.push(k);
        int h = Eq.hashValue(rt, rt.r(ki));
        long root = rt.slot(rt.r(ti), TM_ROOT);
        long r = Val.isNil(root) ? Val.NOT_FOUND : nodeFind(rt, root, 0, h, rt.r(ki));
        rt.popTo(base);
        return r == Val.NOT_FOUND ? notFound : r;
    }

    public static long tassoc(Rt rt, long t, long k, long v) {
        if (Val.isNil(rt.slot(t, TM_EDIT))) {
            return rt.throwStr("IllegalStateException", "transient used after persistent!");
        }
        int base = rt.mark();
        int ti = rt.push(t), ki = rt.push(k), vi = rt.push(v);
        int ei = rt.push(rt.slot(rt.r(ti), TM_EDIT));
        int h = Eq.hashValue(rt, rt.r(ki));
        int ri = rt.push(rt.slot(rt.r(ti), TM_ROOT));
        rt.champAdded = false;
        long nr = nodeAssoc(rt, rt.r(ri), 0, h, rt.r(ki), rt.r(vi), rt.r(ei));
        boolean added = rt.champAdded;
        long tv = rt.r(ti);
        rt.setSlot(Val.asHeap(tv), TM_ROOT, nr);
        if (added) {
            rt.setSlot(Val.asHeap(tv), TM_CNT, Val.fixnum(Val.asFixnum(rt.slot(tv, TM_CNT)) + 1));
        }
        rt.popTo(base);
        return tv;
    }

    public static long tdissoc(Rt rt, long t, long k) {
        if (Val.isNil(rt.slot(t, TM_EDIT))) {
            return rt.throwStr("IllegalStateException", "transient used after persistent!");
        }
        int base = rt.mark();
        int ti = rt.push(t), ki = rt.push(k);
        int ei = rt.push(rt.slot(rt.r(ti), TM_EDIT));
        int h = Eq.hashValue(rt, rt.r(ki));
        int ri = rt.push(rt.slot(rt.r(ti), TM_ROOT));
        rt.champAdded = false;
        long nr = nodeDissoc(rt, rt.r(ri), 0, h, rt.r(ki), rt.r(ei));
        boolean removed = rt.champAdded;
        long tv = rt.r(ti);
        rt.setSlot(Val.asHeap(tv), TM_ROOT, nr);
        if (removed) {
            rt.setSlot(Val.asHeap(tv), TM_CNT, Val.fixnum(Val.asFixnum(rt.slot(tv, TM_CNT)) - 1));
        }
        rt.popTo(base);
        return tv;
    }

    public static int tcount(Rt rt, long t) { return (int) Val.asFixnum(rt.slot(t, TM_CNT)); }

    public static long tpersistent(Rt rt, long t) {
        int base = rt.mark();
        int ti = rt.push(t);
        int cnt = tcount(rt, rt.r(ti));
        int ri = rt.push(rt.slot(rt.r(ti), TM_ROOT));
        // Invalidate: using the handle afterwards is a bug, not a silent
        // mutation of a value somebody else now owns.
        rt.setSlot(Val.asHeap(rt.r(ti)), TM_EDIT, Val.NIL);
        long out = cnt == 0 ? empty(rt) : newHashMap(rt, cnt, rt.r(ri), Val.NIL);
        rt.popTo(base);
        return out;
    }


    /// The entries as a VECTOR of map entries, which is what `seq` walks.
    ///
    /// Materialised rather than a lazy cursor over the trie: a cursor would
    /// have to hold a path of node addresses across allocations the consumer
    /// makes, and every one of those would need rooting. The Rust does the same
    /// and for the same reason.
    public static long entryVector(Rt rt, long m) {
        // CHARGED UP FRONT: `n` is known, so this refuses rather than ticks.
        // `seq`, `keys` and `vals` all come through here and build an entry per
        // key (`doc/decisions/0009`).
        if (!rt.chargeChecked(mapCount(rt, m), "seq of a map")) return Val.NIL;
        // Same choke point as `count`: a ref materialises here rather than
        // being read as an array-map.
        if (Val.isHeap(m) && ty(rt.gc.sp, Val.asHeap(m)) == Obj.TY_TABLEREF)
            return entryVector(rt, Table.refToMap(rt, m));
        int base = rt.mark();
        int mi = rt.push(m);
        int at = rt.mark();
        int n = entries(rt, rt.r(mi), at);
        int ai = rt.push(Vec.empty(rt));
        for (int i = 0; i < n; i++) {
            long e = mapEntry(rt, rt.r(at + 2 * i), rt.r(at + 2 * i + 1));
            int ei = rt.push(e);
            rt.setR(ai, Vec.conj(rt, rt.r(ai), rt.r(ei)));
            rt.popTo(ei);
        }
        long out = rt.r(ai);
        rt.popTo(base);
        return out;
    }

    /// Structural equality. Same count, and every key in `a` present in `b`
    /// with an equal value -- ORDER-INDEPENDENT, which is what a map's `=`
    /// means and why it cannot just compare slots.
    public static boolean eq(Rt rt, long a, long b) {
        // A ROW REF materialises here rather than being read as an array-map:
        // `category` puts one in `CAT_MAP`, so this is where a map compared
        // against a row arrives.
        // BOTH operands are rooted before either is materialised, and the
        // key and value are rooted before the lookup rather than read across
        // it. `refToMap`, `get` and `eq` all allocate, and `0031` is that a
        // value in a host local does not survive an allocation.
        //
        // Rust's `map_eq` already did this and carried the reason in a
        // comment; the ports read `v` across `get` and `b` across
        // `refToMap(a)`. Measured: 18 miscompares in 4,000 comparisons of
        // EQUAL values on the JVM while the native runtime scored 0.
        int base = rt.mark();
        int ai = rt.push(a), bi = rt.push(b);
        if (Val.isHeap(rt.r(ai)) && ty(rt.gc.sp, Val.asHeap(rt.r(ai))) == Obj.TY_TABLEREF)
            rt.setR(ai, Table.refToMap(rt, rt.r(ai)));
        if (Val.isHeap(rt.r(bi)) && ty(rt.gc.sp, Val.asHeap(rt.r(bi))) == Obj.TY_TABLEREF)
            rt.setR(bi, Table.refToMap(rt, rt.r(bi)));
        if (mapCount(rt, rt.r(ai)) != mapCount(rt, rt.r(bi))) { rt.popTo(base); return false; }
        int at = rt.mark();
        int n = entries(rt, rt.r(ai), at);
        boolean ok = true;
        for (int i = 0; i < n && ok; i++) {
            int m = rt.mark();
            int ki = rt.push(rt.r(at + 2 * i));
            int vi = rt.push(rt.r(at + 2 * i + 1));
            int oi = rt.push(mapGet(rt, rt.r(bi), rt.r(ki), Val.NOT_FOUND));
            ok = rt.r(oi) != Val.NOT_FOUND && Eq.eq(rt, rt.r(vi), rt.r(oi));
            rt.popTo(m);
        }
        rt.popTo(base);
        return ok;
    }

    /// UNORDERED, as Clojure hashes maps: the entries are summed, so the hash
    /// does not depend on iteration order. An entry hashes as the vector `[k v]`.
    public static int hash(Rt rt, long m) {
        int base = rt.mark();
        int mi = rt.push(m);
        int at = rt.mark();
        int n = entries(rt, rt.r(mi), at);
        int acc = 0;
        for (int i = 0; i < n; i++) {
            int kh = Eq.hashValue(rt, rt.r(at + 2 * i));
            int vh = Eq.hashValue(rt, rt.r(at + 2 * i + 1));
            acc = flint.rt.Hash.unorderedStep(acc, flint.rt.Hash.mixCollHash(
                flint.rt.Hash.orderedStep(flint.rt.Hash.orderedStep(1, kh), vh), 2));
        }
        rt.popTo(base);
        return flint.rt.Hash.mixCollHash(acc, n);
    }
}
