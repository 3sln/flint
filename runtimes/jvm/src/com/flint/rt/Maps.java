package com.flint.rt;

import com._3sln.flint.kgen.rt.Mapread;

import static com.flint.rt.Obj.*;
import static com._3sln.flint.kgen.rt.Assoc.*;
import static com._3sln.flint.kgen.rt.Mapcore.*;
import static com._3sln.flint.kgen.rt.Mapread.*;
import static com._3sln.flint.kgen.rt.Mapwrite.*;
import static com._3sln.flint.kgen.rt.Champ.*;
import static com._3sln.flint.kgen.rt.Collnode.*;
import static com._3sln.flint.kgen.rt.Dissoc.*;
import static com._3sln.flint.kgen.rt.Find.*;
import static com._3sln.flint.kgen.rt.Interns.*;
import static com._3sln.flint.kgen.rt.Nodeclass.*;

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
    /// Materialised rather than a callback because kin cannot yet express a
    /// callback, and this file is going to kin. It is not free: the callback
    /// shape wins by 1.24x on a 200 000-entry `seq` and holds 8 roots where
    /// this holds 400 001 (`runtime/examples/iterbench.rs`, both shapes
    /// measured in one runtime). When kin grows closures this converges on
    /// `map_for_each` and the cost goes away.
    ///
    /// NOTHING HERE ALLOCATES, which is why no node handle is rooted. A value
    /// in a host local does not survive an allocation (`doc/decisions/0031`);
    /// it survives fine when there is none, and no collection can happen
    /// between the first push and the last. The earlier version rooted each
    /// node and then slid the whole subtree down over it, and that slide was
    /// about HALF this function's cost -- 1.97x against the callback with it,
    /// 1.31x without.
    ///
    /// Writes from `rt.mark()` upward, so a caller takes its own mark first
    /// and reads back from there.
    public static int entries(Rt rt, long m) {
        if (!Val.isHeap(m)) return 0;
        int t = ty(rt.gc.sp, Val.asHeap(m));
        if (t == TY_ARRAYMAP) {
            int n = mapCount(rt, m);
            for (int i = 0; i < n; i++) {
                rt.push(amKey(rt, m, i));
                rt.push(amVal(rt, m, i));
            }
            return n;
        }
        if (t == TY_HASHMAP) {
            long root = rt.slot(m, HM_ROOT);
            if (Val.isNil(root)) return 0;
            return nodeEntries(rt, root);
        }
        return 0;
    }

    static int nodeEntries(Rt rt, long node) {
        int wrote = 0;
        if (!isBmnode(rt, node)) {
            int cnt = cnCount(rt, node);
            for (int i = 0; i < cnt; i++) {
                rt.push(cnKey(rt, node, i));
                rt.push(cnVal(rt, node, i));
                wrote++;
            }
        } else {
            int ne = Integer.bitCount(bnDatamap(rt, node));
            int nn = Integer.bitCount(bnNodemap(rt, node));
            for (int i = 0; i < ne; i++) {
                rt.push(bnKey(rt, node, i));
                rt.push(bnVal(rt, node, i));
                wrote++;
            }
            for (int j = 0; j < nn; j++) {
                wrote += nodeEntries(rt, bnNode(rt, node, j));
            }
        }
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

    // `transientOf`, `tget`, `tassoc`, `tdissoc`, `tcount` and `tpersistent`
    // are GENERATED, from `kin/maptrans.kin`, and callers name `Maptrans`
    // directly. The bodies here rooted neither `notFound` nor the inner map
    // across a descent that allocates; the generated one does.

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
        int n = entries(rt, rt.r(mi));
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
        int n = entries(rt, rt.r(ai));
        boolean ok = true;
        for (int i = 0; i < n && ok; i++) {
            int m = rt.mark();
            int ki = rt.push(rt.r(at + 2 * i));
            int vi = rt.push(rt.r(at + 2 * i + 1));
            int oi = rt.push(mapGet(rt, rt.r(bi), rt.r(ki), Val.NOT_FOUND));
            ok = rt.r(oi) != Val.NOT_FOUND && com._3sln.flint.kgen.rt.Valeq.valEq(rt, rt.r(vi), rt.r(oi));
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
        int n = entries(rt, rt.r(mi));
        int acc = 0;
        for (int i = 0; i < n; i++) {
            int kh = Eq.hashValue(rt, rt.r(at + 2 * i));
            int vh = Eq.hashValue(rt, rt.r(at + 2 * i + 1));
            acc = com._3sln.flint.kgen.rt.Hash.unorderedStep(acc, com._3sln.flint.kgen.rt.Hash.mixCollHash(
                com._3sln.flint.kgen.rt.Hash.orderedStep(com._3sln.flint.kgen.rt.Hash.orderedStep(1, kh), vh), 2));
        }
        rt.popTo(base);
        return com._3sln.flint.kgen.rt.Hash.mixCollHash(acc, n);
    }
}
