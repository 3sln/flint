namespace Flint.Rt;

using _3sln.Flint.Kgen.Rt;

using static _3sln.Flint.Kgen.Rt.Assoc;
using static _3sln.Flint.Kgen.Rt.Mapcore;
using static _3sln.Flint.Kgen.Rt.Mapread;
using static _3sln.Flint.Kgen.Rt.Mapwrite;
using static _3sln.Flint.Kgen.Rt.Champ;
using static _3sln.Flint.Kgen.Rt.Collnode;
using static _3sln.Flint.Kgen.Rt.Dissoc;
using static _3sln.Flint.Kgen.Rt.Find;
using static _3sln.Flint.Kgen.Rt.Interns;
using static _3sln.Flint.Kgen.Rt.Nodeclass;


/// Maps: a small insertion-ordered array-map, and a **CHAMP** hash-array
/// mapped trie above it. Ported from `runtime/src/map.rs`.
///
/// ## Why CHAMP and not Clojure's HAMT
///
/// Steindorfer &amp; Vinju's CHAMP (OOPSLA 2015). A CHAMP node carries TWO
/// bitmaps -- `datamap` for entries stored inline and `nodemap` for sub-nodes
/// -- with entries packed at the front and sub-nodes packed at the back:
///
///   Obj.TyBmnode  [edit, datamap, nodemap, k0,v0, k1,v1, ..., nodeN..node0]
///   Obj.TyCollnode[edit, hash, k0,v0, ...]                 -- full hash collision
///
/// Three things fall outv, and all three matter:
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
public static class Maps {

    public const int ARRAY_MAP_MAX = 8;

    // array-map layout
    public const int AM_META = 0, AM_HASH = 1, AM_BASE = 2;
    // hash-map layout
    public const int HM_CNT = 0, HM_ROOT = 1, HM_META = 2, HM_HASH = 3;
    // CHAMP bitmap node layout
    // `internal` because the CHAMP functions that read them are generated
    // into namespace `flint.rt` now. Assembly scope is enough; Java has to
    // say `public` for the same move.
    internal const int BN_EDIT = 0, BN_DATAMAP = 1, BN_NODEMAP = 2, BN_BASE = 3;
    // collision node layout
    internal const int CN_EDIT = 0, CN_HASH = 1, CN_BASE = 2;

    public const int HASH_BITS = 5, HASH_WIDTH = 32;

    internal static int Mask(int h, int shift) { return (h >>> shift) & 0x1f; }
    internal static int Bitpos(int h, int shift) { return 1 << Mask(h, shift); }
    internal static int IndexOf(int bitmap, int bit) { return System.Numerics.BitOperations.PopCount((uint)(bitmap & (bit - 1))); }

    internal static int Olen(Rt rt, long v) { return Obj.Len(rt.gc.sp, Val.AsHeap(v)); }

    // --- node primitives ----------------------------------------------------





    // --- lookup -------------------------------------------------------------


    // --- structural copies ---------------------------------------------------






    // --- the map objects -----------------------------------------------------




    /// The SINGLETON empty map: allocating a fresh one per call is what made
    /// this port bill more gas than the Rust runtime for the same program.
    public static long Empty(Rt rt) {
        long sg = rt.roots.shared.Singletons[Rt.SingEmptyMap];
        return Val.IsNil(sg) ? NewArrayMap(rt, 0) : sg;
    }

    internal static long NewEmpty(Rt rt) { return NewArrayMap(rt, 0); }


    static void AmSet(Rt rt, long m, int i, long v) { rt.SetSlot(Val.AsHeap(m), i, v); }







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
    /// Writes from `rt.Mark()` upward, so a caller takes its own mark first
    /// and reads back from there.
    public static int Entries(Rt rt, long m) {
        if (!Val.IsHeap(m)) return 0;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(m));
        if (t == Obj.TyArraymap) {
            int n = MapCount(rt, m);
            for (int i = 0; i < n; i++) {
                rt.Push(AmKey(rt, m, i));
                rt.Push(AmVal(rt, m, i));
            }
            return n;
        }
        if (t == Obj.TyHashmap) {
            long root = rt.Slot(m, HM_ROOT);
            if (Val.IsNil(root)) return 0;
            return NodeEntries(rt, root);
        }
        return 0;
    }

    static int NodeEntries(Rt rt, long node) {
        int wrote = 0;
        if (!IsBmnode(rt, node)) {
            int cnt = CnCount(rt, node);
            for (int i = 0; i < cnt; i++) {
                rt.Push(CnKey(rt, node, i));
                rt.Push(CnVal(rt, node, i));
                wrote++;
            }
        } else {
            int ne = System.Numerics.BitOperations.PopCount((uint)(BnDatamap(rt, node)));
            int nn = System.Numerics.BitOperations.PopCount((uint)(BnNodemap(rt, node)));
            for (int i = 0; i < ne; i++) {
                rt.Push(BnKey(rt, node, i));
                rt.Push(BnVal(rt, node, i));
                wrote++;
            }
            for (int j = 0; j < nn; j++) {
                wrote += NodeEntries(rt, BnNode(rt, node, j));
            }
        }
        return wrote;
    }

    // --- transients ---------------------------------------------------------
    //
    // `TY_TMAP [cnt, root, edit]`. The trie is the SAME CHAMP: a node whose
    // ownership token matches this transient's is written in place, and any
    // other is copied once and thereafter owned. That is the whole difference,
    // and it is why `NodeAssoc` already takes an `edit` argument -- the
    // persistent path passes NIL, which owns nothing and so copies everything.

    public const int TM_CNT = 0, TM_ROOT = 1, TM_EDIT = 2;

    public static bool IsTransient(Rt rt, long v) =>
        Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyTmap;

    // `TransientOf`, `TGet`, `TAssoc`, `TDissoc`, `TCount` and `TPersistent`
    // are GENERATED, from `kin/maptrans.kin`, and callers name `Maptrans`
    // directly. The bodies here rooted neither `notFound` nor the inner map
    // across a descent that allocates; the generated one does.

    /// The entries as a VECTOR of map entries, which is what `seq` walks.
    ///
    /// Materialised rather than a lazy cursor over the trie: a cursor would
    /// have to hold a path of node addresses across allocations the consumer
    /// makes, and every one of those would need rooting.
    public static long EntryVector(Rt rt, long m) {
        // CHARGED UP FRONT: `n` is known, so this refuses rather than ticks.
        if (!rt.ChargeChecked(MapCount(rt, m), "seq of a map")) return Val.Nil;
        if (Val.IsHeap(m) && Obj.Ty(rt.gc.sp, Val.AsHeap(m)) == Obj.TyTableref)
            return EntryVector(rt, Table.refToMap(rt, m));
        int bas = rt.Mark();
        int mi = rt.Push(m);
        int at = rt.Mark();
        int n = Entries(rt, rt.R(mi));
        int ai = rt.Push(Vec.Empty(rt));
        for (int i = 0; i < n; i++) {
            long e = MapEntry(rt, rt.R(at + 2 * i), rt.R(at + 2 * i + 1));
            int ei = rt.Push(e);
            rt.SetR(ai, Vec.Conj(rt, rt.R(ai), rt.R(ei)));
            rt.PopTo(ei);
        }
        long outv = rt.R(ai);
        rt.PopTo(bas);
        return outv;
    }

    /// Structural equality. Same count, and every key in `a` present in `b`
    /// with an equal value -- ORDER-INDEPENDENT, which is what a map's `=`
    /// means and why it cannot just compare slots.
    public static bool Eq(Rt rt, long a, long b) {
        // A ROW REF materialises here rather than being read as an array-map.
        // BOTH operands are rooted before either is materialised, and the
        // key and value are rooted before the lookup rather than read across
        // it. `refToMap`, `Get` and `Equal` all allocate, and `0031` is that
        // a value in a host local does not survive an allocation.
        //
        // Rust's `map_eq` already did this and carried the reason in a
        // comment; the ports read `v` across `Get` and `b` across
        // `refToMap(a)`. Measured: 18 miscompares in 4,000 comparisons of
        // EQUAL values on the JVM while the native runtime scored 0.
        int bas = rt.Mark();
        int ai = rt.Push(a), bi = rt.Push(b);
        if (Val.IsHeap(rt.R(ai)) && Obj.Ty(rt.gc.sp, Val.AsHeap(rt.R(ai))) == Obj.TyTableref)
            rt.SetR(ai, Table.refToMap(rt, rt.R(ai)));
        if (Val.IsHeap(rt.R(bi)) && Obj.Ty(rt.gc.sp, Val.AsHeap(rt.R(bi))) == Obj.TyTableref)
            rt.SetR(bi, Table.refToMap(rt, rt.R(bi)));
        if (MapCount(rt, rt.R(ai)) != MapCount(rt, rt.R(bi))) { rt.PopTo(bas); return false; }
        int at = rt.Mark();
        int n = Entries(rt, rt.R(ai));
        bool ok = true;
        for (int i = 0; i < n && ok; i++) {
            int m = rt.Mark();
            int ki = rt.Push(rt.R(at + 2 * i));
            int vi = rt.Push(rt.R(at + 2 * i + 1));
            int oi = rt.Push(MapGet(rt, rt.R(bi), rt.R(ki), Val.NotFound));
            ok = rt.R(oi) != Val.NotFound && global::_3sln.Flint.Kgen.Rt.Valeq.ValEq(rt, rt.R(vi), rt.R(oi));
            rt.PopTo(m);
        }
        rt.PopTo(bas);
        return ok;
    }

    /// UNORDERED, as Clojure hashes maps: the entries are summed, so the hash
    /// does not depend on iteration order. An entry hashes as the vector `[k v]`.
    public static int Hash(Rt rt, long m) {
        int bas = rt.Mark();
        int mi = rt.Push(m);
        int at = rt.Mark();
        int n = Entries(rt, rt.R(mi));
        int acc = 0;
        for (int i = 0; i < n; i++) {
            int kh = global::_3sln.Flint.Kgen.Rt.Valhash.HashValue(rt, rt.R(at + 2 * i));
            int vh = global::_3sln.Flint.Kgen.Rt.Valhash.HashValue(rt, rt.R(at + 2 * i + 1));
            acc = _3sln.Flint.Kgen.Rt.Hash.UnorderedStep(acc, _3sln.Flint.Kgen.Rt.Hash.MixCollHash(
                _3sln.Flint.Kgen.Rt.Hash.OrderedStep(_3sln.Flint.Kgen.Rt.Hash.OrderedStep(1, kh), vh), 2));
        }
        rt.PopTo(bas);
        return _3sln.Flint.Kgen.Rt.Hash.MixCollHash(acc, n);
    }
}
