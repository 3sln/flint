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

    // @kin:link:ns: flint.rt.maps
    // @kin:link:form:hash-mask: {:template "mask({0}, {1})"}
    public static int mask(int h, int shift) { return (h >>> shift) & 0x1f; }
    // @kin:link:form:bitpos: {:template "bitpos({0}, {1})"}
    public static int bitpos(int h, int shift) { return 1 << mask(h, shift); }
    // @kin:link:form:index-of: {:template "indexOf({0}, {1})"}
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

    /// GENERATED -- see `kin/collvec.kin`. This walked the trie with
    /// `entries`, which pushes every key AND value onto the shadow stack
    /// before the vector is built; the walk conjes as it goes and holds
    /// O(depth).
    public static long entryVector(Rt rt, long m) {
        return com._3sln.flint.kgen.rt.Collvec.mapEntryVector(rt, m);
    }

    /// Map equality, GENERATED -- see `kin/mapeq.kin`.
    ///
    /// This used to walk every entry of `a` and do a full `mapGet` descent
    /// into `b`, which never looked at `b`'s structure at all: two maps
    /// sharing every node but one still paid for every entry. Measured here at
    /// 20,000 entries, 840us either way. The CHAMP-aware walk prunes a shared
    /// subtree on pointer equality and answers the same case in 1.2us.
    public static boolean eq(Rt rt, long a, long b) {
        return com._3sln.flint.kgen.rt.Mapeq.mapEq(rt, a, b);
    }

    /// A map's hash, GENERATED -- see `kin/collhash.kin`.
    ///
    /// This walked the map through `entries`, which pushes every key AND value
    /// onto the shadow stack first. A collection hash is an unordered SUM, and
    /// a sum is associative, so the trie can be walked recursively instead and
    /// the roots stay O(depth).
    public static int hash(Rt rt, long m) {
        return com._3sln.flint.kgen.rt.Collhash.hashMap(rt, m);
    }
}
