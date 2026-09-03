namespace Flint.Rt;


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
    const int BN_EDIT = 0, BN_DATAMAP = 1, BN_NODEMAP = 2, BN_BASE = 3;
    // collision node layout
    const int CN_EDIT = 0, CN_HASH = 1, CN_BASE = 2;

    public const int HASH_BITS = 5, HASH_WIDTH = 32;

    static int Mask(int h, int shift) { return (h >>> shift) & 0x1f; }
    static int Bitpos(int h, int shift) { return 1 << Mask(h, shift); }
    static int IndexOf(int bitmap, int bit) { return System.Numerics.BitOperations.PopCount((uint)(bitmap & (bit - 1))); }

    static int Olen(Rt rt, long v) { return Obj.Len(rt.gc.sp, Val.AsHeap(v)); }

    // --- node primitives ----------------------------------------------------


    // kin:begin kin/champ.kin
    static int BnDatamap(Rt rt, long n) {
        return (int) Val.AsFixnum(rt.Slot(n, BN_DATAMAP));
    }
    static int BnNodemap(Rt rt, long n) {
        return (int) Val.AsFixnum(rt.Slot(n, BN_NODEMAP));
    }
    static long BnKey(Rt rt, long n, int i) {
        return rt.Slot(n, BN_BASE + (2 * i));
    }
    static long BnVal(Rt rt, long n, int i) {
        return rt.Slot(n, BN_BASE + ((2 * i) + 1));
    }
    static void BnSetKey(Rt rt, long n, int i, long v) {
        rt.SetSlot(Val.AsHeap(n), BN_BASE + (2 * i), v);
    }
    static void BnSetVal(Rt rt, long n, int i, long v) {
        rt.SetSlot(Val.AsHeap(n), BN_BASE + ((2 * i) + 1), v);
    }
    /// Sub-nodes live at the END, in DESCENDING bit order.
    static long BnNode(Rt rt, long n, int j) {
        return rt.Slot(n, (Olen(rt, n) - 1) - j);
    }
    static void BnSetNode(Rt rt, long n, int j, long v) {
        rt.SetSlot(Val.AsHeap(n), (Olen(rt, n) - 1) - j, v);
    }

    // kin:end kin/champ.kin

    // kin:begin kin/collnode.kin
    static long CnNew(Rt rt, int h, int npairs, long edit) {
        // `edit` is rooted across the allocation and read back after it:
        // `alloc` collects, and a host local does not survive that.
        int e = rt.Push(edit);
        long a = rt.Alloc(Obj.TyCollnode, CN_BASE + (2 * npairs));
        long ed = rt.R(e);
        rt.PopTo(e);
        if (a == 0) {
            return Val.Nil;
        }
        rt.SetSlot(a, CN_EDIT, ed);
        rt.SetSlot(a, CN_HASH, Val.Fixnum(h & 0xFFFFFFFFL));
        return Val.Heap(a);
    }
    static int CnCount(Rt rt, long n) {
        return (Olen(rt, n) - CN_BASE) / 2;
    }
    static int CnHash(Rt rt, long n) {
        return (int) Val.AsFixnum(rt.Slot(n, CN_HASH));
    }
    static long CnKey(Rt rt, long n, int i) {
        return rt.Slot(n, CN_BASE + (2 * i));
    }
    static long CnVal(Rt rt, long n, int i) {
        return rt.Slot(n, (CN_BASE + (2 * i)) + 1);
    }
    /// A BITMAP NODE, allocated and stamped with its two maps.
    public static long BnNew(Rt rt, int datamap, int nodemap, long edit) {
        int ne = System.Numerics.BitOperations.PopCount((uint)(datamap));
        int nn = System.Numerics.BitOperations.PopCount((uint)(nodemap));
        int e = rt.Push(edit);
        long a = rt.Alloc(Obj.TyBmnode, (BN_BASE + (2 * ne)) + nn);
        long ed = rt.R(e);
        rt.PopTo(e);
        if (a == 0) {
            return Val.Nil;
        }
        rt.SetSlot(a, BN_EDIT, ed);
        rt.SetSlot(a, BN_DATAMAP, Val.Fixnum(datamap & 0xFFFFFFFFL));
        rt.SetSlot(a, BN_NODEMAP, Val.Fixnum(nodemap & 0xFFFFFFFFL));
        return Val.Heap(a);
    }
    /// A COLLISION NODE copied with one value replaced.
    public static long CnCopySetVal(Rt rt, long n, int i, long val, long edit) {
        int cnt = CnCount(rt, n);
        int @base = rt.Mark();
        int ni = rt.Push(n);
        int vi = rt.Push(val);
        int ei = rt.Push(edit);
        long @out = CnNew(rt, CnHash(rt, rt.R(ni)), cnt, rt.R(ei));
        if (Val.IsNil(@out)) {
            rt.PopTo(@base);
            return Val.Nil;
        }
        int oi = rt.Push(@out);
        for (int k = 0; k < cnt; k++) {
            rt.SetSlot(Val.AsHeap(rt.R(oi)), CN_BASE + (2 * k), CnKey(rt, rt.R(ni), k));
            rt.SetSlot(Val.AsHeap(rt.R(oi)), (CN_BASE + (2 * k)) + 1, CnVal(rt, rt.R(ni), k));
        }
        rt.SetSlot(Val.AsHeap(rt.R(oi)), (CN_BASE + (2 * i)) + 1, rt.R(vi));
        long res = rt.R(oi);
        rt.PopTo(@base);
        return res;
    }

    // kin:end kin/collnode.kin

    // kin:begin kin/nodeclass.kin
    static bool IsBmnode(Rt rt, long n) {
        return Obj.Ty(rt.gc.sp, Val.AsHeap(n)) == Obj.TyBmnode;
    }
    /// EMPTY / ONE / MORE, the CHAMP size predicate that drives collapsing.
    static int NodeSizeClass(Rt rt, long n) {
        // A collision node always holds at least two pairs.
        if (!IsBmnode(rt, n)) {
            return 2;
        }
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        if (nm != 0) {
            return 2;
        }
        int c = System.Numerics.BitOperations.PopCount((uint)(dm));
        if (c == 0) {
            return 0;
        }
        if (c == 1) {
            return 1;
        }
        return 2;
    }

    // kin:end kin/nodeclass.kin

    // --- lookup -------------------------------------------------------------

    // kin:begin kin/find.kin
    static long NodeFindScalar(Rt rt, long n, int shift, int h, long key) {
        // No rooting anywhere in here: the key is a scalar, so `eq` cannot
        // allocate, so nothing can move while this walks.
        long node;
        node = n;
        long @out;
        @out = Val.NotFound;
        for (;;) {
            if (!IsBmnode(rt, node)) {
                if (CnHash(rt, node) != h) {
                    break;
                }
                int cnt = CnCount(rt, node);
                for (int i = 0; i < cnt; i++) {
                    if (Flint.Rt.Eq.Equal(rt, CnKey(rt, node, i), key)) {
                        @out = CnVal(rt, node, i);
                        break;
                    }
                }
                break;
            }
            int bit = Bitpos(h, shift);
            int dm = BnDatamap(rt, node);
            if ((dm & bit) != 0) {
                int i = IndexOf(dm, bit);
                if (Flint.Rt.Eq.Equal(rt, BnKey(rt, node, i), key)) {
                    @out = BnVal(rt, node, i);
                }
                break;
            }
            int nm = BnNodemap(rt, node);
            if ((nm & bit) == 0) {
                break;
            }
            node = BnNode(rt, node, IndexOf(nm, bit));
            shift += HASH_BITS;
        }
        return @out;
    }
    static long NodeFind(Rt rt, long n, int shift, int h, long key) {
        if (!Flint.Rt.Eq.EqMayAlloc(rt, key)) {
            return NodeFindScalar(rt, n, shift, h, key);
        }
        // The node being walked and the key are rooted: `eq` on a compound
        // key allocates (it seqs both sides), so a collection can happen in
        // the middle of a lookup and move everything this walk is holding.
        int @base = rt.Mark();
        int ni = rt.Push(n);
        int ki = rt.Push(key);
        long @out;
        @out = Val.NotFound;
        for (;;) {
            if (!IsBmnode(rt, rt.R(ni))) {
                if (CnHash(rt, rt.R(ni)) != h) {
                    break;
                }
                int cnt = CnCount(rt, rt.R(ni));
                for (int i = 0; i < cnt; i++) {
                    int kk = rt.Push(CnKey(rt, rt.R(ni), i));
                    bool same = Flint.Rt.Eq.Equal(rt, rt.R(kk), rt.R(ki));
                    rt.PopTo(kk);
                    if (same) {
                        @out = CnVal(rt, rt.R(ni), i);
                        break;
                    }
                }
                break;
            }
            int bit = Bitpos(h, shift);
            int dm = BnDatamap(rt, rt.R(ni));
            if ((dm & bit) != 0) {
                int i = IndexOf(dm, bit);
                int kk = rt.Push(BnKey(rt, rt.R(ni), i));
                bool same = Flint.Rt.Eq.Equal(rt, rt.R(kk), rt.R(ki));
                rt.PopTo(kk);
                if (same) {
                    @out = BnVal(rt, rt.R(ni), i);
                }
                break;
            }
            int nm = BnNodemap(rt, rt.R(ni));
            if ((nm & bit) == 0) {
                break;
            }
            rt.SetR(ni, BnNode(rt, rt.R(ni), IndexOf(nm, bit)));
            shift += HASH_BITS;
        }
        rt.PopTo(@base);
        return @out;
    }

    // kin:end kin/find.kin

    // --- structural copies ---------------------------------------------------

    // kin:begin kin/merge.kin
    static long MergeTwo(Rt rt, int shift, long k0, long v0, int h0, long k1, long v1, int h1, long edit) {
        int mk = rt.Mark();
        int ik0 = rt.Push(k0);
        int iv0 = rt.Push(v0);
        int ik1 = rt.Push(k1);
        int iv1 = rt.Push(v1);
        int ie = rt.Push(edit);
        // Declared, not initialised: every branch below assigns it exactly
        // once, which Rust treats as the initialisation rather than as a
        // mutation -- so no `mut`, and no dead store to warn about.
        long res;
        if (shift >= 32) {
            // Two keys with the SAME 32-bit hash: a collision node, which
            // is the only place equal hashes are stored side by side.
            res = CnNew(rt, h0, 2, rt.R(ie));
            if (!Val.IsNil(res)) {
                rt.SetSlot(Val.AsHeap(res), CN_BASE, rt.R(ik0));
                rt.SetSlot(Val.AsHeap(res), CN_BASE + 1, rt.R(iv0));
                rt.SetSlot(Val.AsHeap(res), CN_BASE + 2, rt.R(ik1));
                rt.SetSlot(Val.AsHeap(res), CN_BASE + 3, rt.R(iv1));
            }
        } else {
            int m0 = Mask(h0, shift);
            int m1 = Mask(h1, shift);
            if (m0 != m1) {
                int dm = (1 << m0) | (1 << m1);
                res = BnNew(rt, dm, 0, rt.R(ie));
                if (!Val.IsNil(res)) {
                    // In BIT ORDER, not argument order: `index-of` derives
                    // a slot from the bitmap, so the pair with the lower
                    // mask must land first or every later lookup is off
                    // by one.
                    if (m0 < m1) {
                        BnSetKey(rt, res, 0, rt.R(ik0));
                        BnSetVal(rt, res, 0, rt.R(iv0));
                        BnSetKey(rt, res, 1, rt.R(ik1));
                        BnSetVal(rt, res, 1, rt.R(iv1));
                    } else {
                        BnSetKey(rt, res, 0, rt.R(ik1));
                        BnSetVal(rt, res, 0, rt.R(iv1));
                        BnSetKey(rt, res, 1, rt.R(ik0));
                        BnSetVal(rt, res, 1, rt.R(iv0));
                    }
                }
            } else {
                long sub = MergeTwo(rt, shift + HASH_BITS, rt.R(ik0), rt.R(iv0), h0, rt.R(ik1), rt.R(iv1), h1, rt.R(ie));
                int si = rt.Push(sub);
                res = BnNew(rt, 0, 1 << m0, rt.R(ie));
                if (!Val.IsNil(res)) {
                    BnSetNode(rt, res, 0, rt.R(si));
                }
            }
        }
        rt.PopTo(mk);
        return res;
    }

    // kin:end kin/merge.kin

    // kin:begin kin/copies.kin
    static long BnCopyInsertEntry(Rt rt, long n, int bit, long key, long val, long edit) {
        int mk = rt.Mark();
        int ni = rt.Push(n);
        int ki = rt.Push(key);
        int vi = rt.Push(val);
        int ei = rt.Push(edit);
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        int ne = System.Numerics.BitOperations.PopCount((uint)(dm));
        int nn = System.Numerics.BitOperations.PopCount((uint)(nm));
        int at = IndexOf(dm, bit);
        long res = BnNew(rt, dm | bit, nm, rt.R(ei));
        if (Val.IsNil(res)) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        int oi = rt.Push(res);
        // Everything at or after the insertion point shifts up one.
        // `index-of` derives a slot from the bitmap, so a pair landing
        // in the wrong order is a lookup that misses.
        for (int k = 0; k < ne; k++) {
            int d;
            if (k < at) {
                d = k;
            } else {
                d = k + 1;
            }
            long ek = BnKey(rt, rt.R(ni), k);
            BnSetKey(rt, rt.R(oi), d, ek);
            long ev = BnVal(rt, rt.R(ni), k);
            BnSetVal(rt, rt.R(oi), d, ev);
        }
        long nk = rt.R(ki);
        BnSetKey(rt, rt.R(oi), at, nk);
        long nv = rt.R(vi);
        BnSetVal(rt, rt.R(oi), at, nv);
        for (int j = 0; j < nn; j++) {
            long sub = BnNode(rt, rt.R(ni), j);
            BnSetNode(rt, rt.R(oi), j, sub);
        }
        long built = rt.R(oi);
        rt.PopTo(mk);
        return built;
    }
    static long BnCopyRemoveEntry(Rt rt, long n, int bit, long edit) {
        int mk = rt.Mark();
        int ni = rt.Push(n);
        int ei = rt.Push(edit);
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        int ne = System.Numerics.BitOperations.PopCount((uint)(dm));
        int nn = System.Numerics.BitOperations.PopCount((uint)(nm));
        int at = IndexOf(dm, bit);
        long res = BnNew(rt, dm ^ bit, nm, rt.R(ei));
        if (Val.IsNil(res)) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        int oi = rt.Push(res);
        // Everything after the hole shifts DOWN one, and the removed
        // slot is skipped rather than copied.
        for (int k = 0; k < ne; k++) {
            if (k == at) {
                continue;
            }
            int d;
            if (k < at) {
                d = k;
            } else {
                d = k - 1;
            }
            long ek = BnKey(rt, rt.R(ni), k);
            BnSetKey(rt, rt.R(oi), d, ek);
            long ev = BnVal(rt, rt.R(ni), k);
            BnSetVal(rt, rt.R(oi), d, ev);
        }
        for (int j = 0; j < nn; j++) {
            long sub = BnNode(rt, rt.R(ni), j);
            BnSetNode(rt, rt.R(oi), j, sub);
        }
        long built = rt.R(oi);
        rt.PopTo(mk);
        return built;
    }
    static long BnCopySetValue(Rt rt, long n, int at, long val, long edit) {
        int mk = rt.Mark();
        int ni = rt.Push(n);
        int vi = rt.Push(val);
        int ei = rt.Push(edit);
        // Owned by this transient? Then write through rather than copy.
        if (!Val.IsNil(rt.R(ei)) && (rt.Slot(rt.R(ni), BN_EDIT) == rt.R(ei))) {
            long own = rt.R(ni);
            long nv = rt.R(vi);
            BnSetVal(rt, own, at, nv);
            rt.PopTo(mk);
            return own;
        }
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        long res = BnNew(rt, dm, nm, rt.R(ei));
        if (Val.IsNil(res)) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        int oi = rt.Push(res);
        int ne = System.Numerics.BitOperations.PopCount((uint)(dm));
        int nn = System.Numerics.BitOperations.PopCount((uint)(nm));
        for (int k = 0; k < ne; k++) {
            long ek = BnKey(rt, rt.R(ni), k);
            BnSetKey(rt, rt.R(oi), k, ek);
            long ev = BnVal(rt, rt.R(ni), k);
            BnSetVal(rt, rt.R(oi), k, ev);
        }
        for (int j = 0; j < nn; j++) {
            long sub = BnNode(rt, rt.R(ni), j);
            BnSetNode(rt, rt.R(oi), j, sub);
        }
        long fresh = rt.R(vi);
        BnSetVal(rt, rt.R(oi), at, fresh);
        long built = rt.R(oi);
        rt.PopTo(mk);
        return built;
    }
    static long BnCopySetNode(Rt rt, long n, int at, long sub, long edit) {
        int mk = rt.Mark();
        int ni = rt.Push(n);
        int si = rt.Push(sub);
        int ei = rt.Push(edit);
        if (!Val.IsNil(rt.R(ei)) && (rt.Slot(rt.R(ni), BN_EDIT) == rt.R(ei))) {
            long own = rt.R(ni);
            long os = rt.R(si);
            BnSetNode(rt, own, at, os);
            rt.PopTo(mk);
            return own;
        }
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        long res = BnNew(rt, dm, nm, rt.R(ei));
        if (Val.IsNil(res)) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        int oi = rt.Push(res);
        int ne = System.Numerics.BitOperations.PopCount((uint)(dm));
        int nn = System.Numerics.BitOperations.PopCount((uint)(nm));
        for (int k = 0; k < ne; k++) {
            long ek = BnKey(rt, rt.R(ni), k);
            BnSetKey(rt, rt.R(oi), k, ek);
            long ev = BnVal(rt, rt.R(ni), k);
            BnSetVal(rt, rt.R(oi), k, ev);
        }
        for (int j = 0; j < nn; j++) {
            long js = BnNode(rt, rt.R(ni), j);
            BnSetNode(rt, rt.R(oi), j, js);
        }
        long fresh = rt.R(si);
        BnSetNode(rt, rt.R(oi), at, fresh);
        long built = rt.R(oi);
        rt.PopTo(mk);
        return built;
    }
    /// An inline pair becomes a SUB-NODE: it leaves the entry half and joins
    /// the node half, so both bitmaps change and both halves reindex. The two
    /// positions are computed from the OLD bitmaps -- `at-node` is the index in
    /// the new nodemap as well, because the bit being added is the one being
    /// counted up to.
    static long BnInlineToNode(Rt rt, long n, int bit, long sub, long edit) {
        int mk = rt.Mark();
        int ni = rt.Push(n);
        int si = rt.Push(sub);
        int ei = rt.Push(edit);
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        int ne = System.Numerics.BitOperations.PopCount((uint)(dm));
        int nn = System.Numerics.BitOperations.PopCount((uint)(nm));
        int atEntry = IndexOf(dm, bit);
        int atNode = IndexOf(nm, bit);
        long res = BnNew(rt, dm ^ bit, nm | bit, rt.R(ei));
        if (Val.IsNil(res)) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        int oi = rt.Push(res);
        for (int k = 0; k < ne; k++) {
            if (k == atEntry) {
                continue;
            }
            int d;
            if (k < atEntry) {
                d = k;
            } else {
                d = k - 1;
            }
            long ek = BnKey(rt, rt.R(ni), k);
            BnSetKey(rt, rt.R(oi), d, ek);
            long ev = BnVal(rt, rt.R(ni), k);
            BnSetVal(rt, rt.R(oi), d, ev);
        }
        for (int j = 0; j < nn; j++) {
            int e;
            if (j < atNode) {
                e = j;
            } else {
                e = j + 1;
            }
            long js = BnNode(rt, rt.R(ni), j);
            BnSetNode(rt, rt.R(oi), e, js);
        }
        long fresh = rt.R(si);
        BnSetNode(rt, rt.R(oi), atNode, fresh);
        long built = rt.R(oi);
        rt.PopTo(mk);
        return built;
    }
    /// And the reverse: a sub-node collapses back to an inline pair.
    static long BnNodeToInline(Rt rt, long n, int bit, long key, long val, long edit) {
        int mk = rt.Mark();
        int ni = rt.Push(n);
        int ki = rt.Push(key);
        int vi = rt.Push(val);
        int ei = rt.Push(edit);
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        int ne = System.Numerics.BitOperations.PopCount((uint)(dm));
        int nn = System.Numerics.BitOperations.PopCount((uint)(nm));
        int atEntry = IndexOf(dm, bit);
        int atNode = IndexOf(nm, bit);
        long res = BnNew(rt, dm | bit, nm ^ bit, rt.R(ei));
        if (Val.IsNil(res)) {
            rt.PopTo(mk);
            return Val.Nil;
        }
        int oi = rt.Push(res);
        for (int k = 0; k < ne; k++) {
            int d;
            if (k < atEntry) {
                d = k;
            } else {
                d = k + 1;
            }
            long ek = BnKey(rt, rt.R(ni), k);
            BnSetKey(rt, rt.R(oi), d, ek);
            long ev = BnVal(rt, rt.R(ni), k);
            BnSetVal(rt, rt.R(oi), d, ev);
        }
        long nk = rt.R(ki);
        BnSetKey(rt, rt.R(oi), atEntry, nk);
        long nv = rt.R(vi);
        BnSetVal(rt, rt.R(oi), atEntry, nv);
        for (int j = 0; j < nn; j++) {
            if (j == atNode) {
                continue;
            }
            int e;
            if (j < atNode) {
                e = j;
            } else {
                e = j - 1;
            }
            long js = BnNode(rt, rt.R(ni), j);
            BnSetNode(rt, rt.R(oi), e, js);
        }
        long built = rt.R(oi);
        rt.PopTo(mk);
        return built;
    }

    // kin:end kin/copies.kin

    // kin:begin kin/nodeassoc.kin
    static long NodeAssoc(Rt rt, long n, int shift, int h, long key, long val, long edit) {
        int @base = rt.Mark();
        int ni = rt.Push(n);
        int ki = rt.Push(key);
        int vi = rt.Push(val);
        int ei = rt.Push(edit);
        // A collision node has no bitmaps to consult, so it is not this
        // function's shape at all -- hand it straight over.
        // The result here is named `handed` and not `out`: C# forbids a
        // local in an inner scope that reuses an enclosing scope's name,
        // and `out` is declared at method scope just below.
        if (!IsBmnode(rt, n)) {
            long handed = CollAssoc(rt, rt.R(ni), h, rt.R(ki), rt.R(vi), rt.R(ei), shift);
            rt.PopTo(@base);
            return handed;
        }
        int bit = Bitpos(h, shift);
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        long @out;
        if ((dm & bit) != 0) {
            int at = IndexOf(dm, bit);
            int k0i = rt.Push(BnKey(rt, rt.R(ni), at));
            if (Flint.Rt.Eq.Equal(rt, rt.R(k0i), rt.R(ki))) {
                // The key was already here, so the map's count does not
                // move however the value changes.
                rt.champAdded = false;
                long v0 = BnVal(rt, rt.R(ni), at);
                if (v0 == rt.R(vi)) {
                    @out = rt.R(ni);
                } else {
                    @out = BnCopySetValue(rt, rt.R(ni), at, rt.R(vi), rt.R(ei));
                }
            } else {
                // A different key in the same slot: the two are pushed
                // down into a sub-node, and the entry stops being an
                // entry at this level.
                rt.champAdded = true;
                int v0i = rt.Push(BnVal(rt, rt.R(ni), at));
                int h0 = Flint.Rt.Eq.HashValue(rt, rt.R(k0i));
                long sub = MergeTwo(rt, shift + HASH_BITS, rt.R(k0i), rt.R(v0i), h0, rt.R(ki), rt.R(vi), h, rt.R(ei));
                int si = rt.Push(sub);
                @out = BnInlineToNode(rt, rt.R(ni), bit, rt.R(si), rt.R(ei));
            }
        } else if ((nm & bit) != 0) {
            int at = IndexOf(nm, bit);
            // Rooted, because the comparison below is what decides
            // whether this node changed. `node_assoc` allocates, a
            // collection can move `sub`, and a stale address that
            // happened to match the new one would drop the whole
            // subtree's update on the floor -- a key silently missing
            // from a map whose count says it is there.
            int subi = rt.Push(BnNode(rt, rt.R(ni), at));
            long newsub = NodeAssoc(rt, rt.R(subi), shift + HASH_BITS, h, rt.R(ki), rt.R(vi), rt.R(ei));
            if (newsub == rt.R(subi)) {
                @out = rt.R(ni);
            } else {
                @out = BnCopySetNode(rt, rt.R(ni), at, newsub, rt.R(ei));
            }
        } else {
            // An empty slot, which is the only branch that always
            // grows the map.
            rt.champAdded = true;
            @out = BnCopyInsertEntry(rt, rt.R(ni), bit, rt.R(ki), rt.R(vi), rt.R(ei));
        }
        rt.PopTo(@base);
        return @out;
    }

    // kin:end kin/nodeassoc.kin

    // kin:begin kin/collassoc.kin
    static long CollAssoc(Rt rt, long n, int h, long key, long val, long edit, int shift) {
        int nh = CnHash(rt, n);
        if (nh != h) {
            // A different hash at this depth: the node becomes a child of
            // a new bitmap node, and `node_assoc` takes it from there --
            // which is the only path by which a collision node acquires a
            // bitmap above it.
            // Each of the three blocks below names its own roots --
            // `wbase`/`wni`, `rbase`/`rni`, `gbase`/`gni` -- rather than
            // reusing `base`/`ni`. Rust and Java scope them per block and
            // would take the shorter names; C# refuses a name reused in an
            // enclosing scope (CS0136). One source has to satisfy the
            // strictest of the three, and this is what that costs.
            int wbase = rt.Mark();
            int wni = rt.Push(n);
            int wki = rt.Push(key);
            int wvi = rt.Push(val);
            int wei = rt.Push(edit);
            long wrapper = BnNew(rt, 0, Bitpos(nh, shift), rt.R(wei));
            int wi = rt.Push(wrapper);
            BnSetNode(rt, rt.R(wi), 0, rt.R(wni));
            long wrapped = NodeAssoc(rt, rt.R(wi), shift, h, rt.R(wki), rt.R(wvi), rt.R(wei));
            rt.PopTo(wbase);
            return wrapped;
        }
        // THE SCAN. `hit` is left at `cnt` when nothing matched -- one past
        // the last valid index, so it cannot collide with an answer.
        int scan = rt.Mark();
        int sni = rt.Push(n);
        int ski = rt.Push(key);
        int cnt = CnCount(rt, rt.R(sni));
        int hit;
        hit = cnt;
        for (int i = 0; i < cnt; i++) {
            // The key is rooted across `eq`, which allocates when either
            // side is a row ref.
            int kk = rt.Push(CnKey(rt, rt.R(sni), i));
            bool same = Flint.Rt.Eq.Equal(rt, rt.R(kk), rt.R(ski));
            rt.PopTo(kk);
            if (same) {
                hit = i;
                break;
            }
        }
        long nn = rt.R(sni);
        long kk2 = rt.R(ski);
        rt.PopTo(scan);
        if (hit != cnt) {
            // The key was already here, so the map's count does not move.
            rt.champAdded = false;
            int rbase = rt.Mark();
            int rni = rt.Push(nn);
            int rvi = rt.Push(val);
            int rei = rt.Push(edit);
            long replaced = CnCopySetVal(rt, rt.R(rni), hit, rt.R(rvi), rt.R(rei));
            rt.PopTo(rbase);
            return replaced;
        }
        rt.champAdded = true;
        int gbase = rt.Mark();
        int gni = rt.Push(nn);
        int gki = rt.Push(kk2);
        int gvi = rt.Push(val);
        int gei = rt.Push(edit);
        long fresh = CnNew(rt, h, cnt + 1, rt.R(gei));
        if (Val.IsNil(fresh)) {
            rt.PopTo(gbase);
            return Val.Nil;
        }
        int oi = rt.Push(fresh);
        for (int i = 0; i < cnt; i++) {
            long ek = CnKey(rt, rt.R(gni), i);
            long ev = CnVal(rt, rt.R(gni), i);
            rt.SetSlot(Val.AsHeap(rt.R(oi)), CN_BASE + (2 * i), ek);
            rt.SetSlot(Val.AsHeap(rt.R(oi)), (CN_BASE + (2 * i)) + 1, ev);
        }
        rt.SetSlot(Val.AsHeap(rt.R(oi)), CN_BASE + (2 * cnt), rt.R(gki));
        rt.SetSlot(Val.AsHeap(rt.R(oi)), (CN_BASE + (2 * cnt)) + 1, rt.R(gvi));
        long grown = rt.R(oi);
        rt.PopTo(gbase);
        return grown;
    }

    // kin:end kin/collassoc.kin


    // kin:begin kin/dissoc.kin
    static long NodeDissoc(Rt rt, long n, int shift, int h, long key, long edit) {
        int @base = rt.Mark();
        int ni = rt.Push(n);
        int ki = rt.Push(key);
        int ei = rt.Push(edit);
        // A collision node has no bitmaps, so it is not this function's
        // shape at all -- hand it over.
        if (!IsBmnode(rt, n)) {
            long handed = CollDissoc(rt, rt.R(ni), rt.R(ki), rt.R(ei));
            rt.PopTo(@base);
            return handed;
        }
        int bit = Bitpos(h, shift);
        int dm = BnDatamap(rt, n);
        int nm = BnNodemap(rt, n);
        long @out;
        if ((dm & bit) != 0) {
            int at = IndexOf(dm, bit);
            int k0i = rt.Push(BnKey(rt, rt.R(ni), at));
            bool same0 = Flint.Rt.Eq.Equal(rt, rt.R(k0i), rt.R(ki));
            rt.PopTo(k0i);
            if (!same0) {
                rt.champAdded = false;
                @out = rt.R(ni);
            } else {
                // `champ_added` reads as CHANGED on this path.
                rt.champAdded = true;
                if ((System.Numerics.BitOperations.PopCount((uint)(dm)) == 2) && (nm == 0)) {
                    // One entry would be left, which a CHAMP never
                    // stores as a node: collapse to a single-entry node
                    // so the parent can fold it back inline.
                    int other = 1 - at;
                    int oki = rt.Push(BnKey(rt, rt.R(ni), other));
                    int ovi = rt.Push(BnVal(rt, rt.R(ni), other));
                    int oh = Flint.Rt.Eq.HashValue(rt, rt.R(oki));
                    int newdm;
                    if (shift == 0) {
                        newdm = dm ^ bit;
                    } else {
                        newdm = Bitpos(oh, 0);
                    }
                    long nn = BnNew(rt, newdm, 0, rt.R(ei));
                    if (!Val.IsNil(nn)) {
                        BnSetKey(rt, nn, 0, rt.R(oki));
                        BnSetVal(rt, nn, 0, rt.R(ovi));
                    }
                    @out = nn;
                } else {
                    @out = BnCopyRemoveEntry(rt, rt.R(ni), bit, rt.R(ei));
                }
            }
        } else if ((nm & bit) != 0) {
            int at = IndexOf(nm, bit);
            int subi = rt.Push(BnNode(rt, rt.R(ni), at));
            long newsub = NodeDissoc(rt, rt.R(subi), shift + HASH_BITS, h, rt.R(ki), rt.R(ei));
            if (newsub == rt.R(subi)) {
                @out = rt.R(ni);
            } else if (NodeSizeClass(rt, newsub) == 1) {
                int si = rt.Push(newsub);
                if ((dm == 0) && (System.Numerics.BitOperations.PopCount((uint)(nm)) == 1)) {
                    // Nothing else lives here, so this node IS the
                    // child now.
                    @out = rt.R(si);
                } else {
                    int ki2 = rt.Push(BnKey(rt, rt.R(si), 0));
                    int vi2 = rt.Push(BnVal(rt, rt.R(si), 0));
                    @out = BnNodeToInline(rt, rt.R(ni), bit, rt.R(ki2), rt.R(vi2), rt.R(ei));
                }
            } else {
                @out = BnCopySetNode(rt, rt.R(ni), at, newsub, rt.R(ei));
            }
        } else {
            rt.champAdded = false;
            @out = rt.R(ni);
        }
        rt.PopTo(@base);
        return @out;
    }
    static long CollDissoc(Rt rt, long n, long key, long edit) {
        int scan = rt.Mark();
        int sni = rt.Push(n);
        int ski = rt.Push(key);
        int cnt = CnCount(rt, rt.R(sni));
        int found;
        found = cnt;
        for (int i = 0; i < cnt; i++) {
            // The key is rooted across `eq`, which allocates when either
            // side is a row ref.
            int kk = rt.Push(CnKey(rt, rt.R(sni), i));
            bool same = Flint.Rt.Eq.Equal(rt, rt.R(kk), rt.R(ski));
            rt.PopTo(kk);
            if (same) {
                found = i;
                break;
            }
        }
        long nn = rt.R(sni);
        rt.PopTo(scan);
        if (found == cnt) {
            rt.champAdded = false;
            return nn;
        }
        rt.champAdded = true;
        int dbase = rt.Mark();
        int dni = rt.Push(nn);
        int dei = rt.Push(edit);
        long res;
        if (cnt == 2) {
            // Down to one pair: become a single-entry bitmap node so
            // the parent can fold it back inline.
            int other = 1 - found;
            int cki = rt.Push(CnKey(rt, rt.R(dni), other));
            int cvi = rt.Push(CnVal(rt, rt.R(dni), other));
            int kh = Flint.Rt.Eq.HashValue(rt, rt.R(cki));
            long made = BnNew(rt, Bitpos(kh, 0), 0, rt.R(dei));
            if (!Val.IsNil(made)) {
                BnSetKey(rt, made, 0, rt.R(cki));
                BnSetVal(rt, made, 0, rt.R(cvi));
            }
            res = made;
        } else {
            long o = CnNew(rt, CnHash(rt, rt.R(dni)), cnt - 1, rt.R(dei));
            if (Val.IsNil(o)) {
                rt.PopTo(dbase);
                return Val.Nil;
            }
            int oi = rt.Push(o);
            int d;
            d = 0;
            for (int i = 0; i < cnt; i++) {
                if (i == found) {
                    continue;
                }
                long ek = CnKey(rt, rt.R(dni), i);
                long ev = CnVal(rt, rt.R(dni), i);
                rt.SetSlot(Val.AsHeap(rt.R(oi)), CN_BASE + (2 * d), ek);
                rt.SetSlot(Val.AsHeap(rt.R(oi)), (CN_BASE + (2 * d)) + 1, ev);
                d += 1;
            }
            res = rt.R(oi);
        }
        rt.PopTo(dbase);
        return res;
    }

    // kin:end kin/dissoc.kin

    // --- the map objects -----------------------------------------------------

    public static bool IsMap(Rt rt, long v) {
        if (!Val.IsHeap(v)) return false;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        // A ROW REF is a map: `get`, keyword lookup, `count`, `keys`, `vals`
        // and `=` against a map all work, so code that does not know it has a
        // table keeps working (`doc/decisions/0026`).
        return t == Obj.TyArraymap || t == Obj.TyHashmap || t == Obj.TyTableref;
    }
    public static bool IsArrayMap(Rt rt, long v) {
        return Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyArraymap;
    }

    public static int Count(Rt rt, long m) {
        // A ROW REF counts its COLUMNS -- `IsMap` says true for one, so map
        // internals get called on one directly (`doc/decisions/0026`).
        if (Val.IsHeap(m) && Obj.Ty(rt.gc.sp, Val.AsHeap(m)) == Obj.TyTableref)
            return Table.schemaLen(rt, rt.Slot(m, Table.RF_SCHEMA));
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(m));
        if (t == Obj.TyArraymap) return (Olen(rt, m) - AM_BASE) / 2;
        if (t == Obj.TyHashmap) return (int) Val.AsFixnum(rt.Slot(m, HM_CNT));
        return 0;
    }

    static long NewArrayMap(Rt rt, int n) {
        long a = rt.Alloc(Obj.TyArraymap, AM_BASE + 2 * n);
        if (a == 0) return Val.Nil;
        rt.SetSlot(a, AM_META, Val.Nil);
        rt.SetSlot(a, AM_HASH, Val.Nil);
        return Val.Heap(a);
    }

    /// The SINGLETON empty map: allocating a fresh one per call is what made
    /// this port bill more gas than the Rust runtime for the same program.
    public static long Empty(Rt rt) {
        long sg = rt.roots.shared.Singletons[Rt.SingEmptyMap];
        return Val.IsNil(sg) ? NewArrayMap(rt, 0) : sg;
    }

    internal static long NewEmpty(Rt rt) { return NewArrayMap(rt, 0); }

    static long NewHashMap(Rt rt, int cnt, long root, long meta) {
        int bas = rt.Mark();
        int ri = rt.Push(root), mi = rt.Push(meta);
        long a = rt.Alloc(Obj.TyHashmap, 4);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, HM_CNT, Val.Fixnum(cnt));
        rt.SetSlot(a, HM_ROOT, rt.R(ri));
        rt.SetSlot(a, HM_META, rt.R(mi));
        rt.SetSlot(a, HM_HASH, Val.Nil);
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    static long AmKey(Rt rt, long m, int i) { return rt.Slot(m, AM_BASE + 2 * i); }
    static long AmVal(Rt rt, long m, int i) { return rt.Slot(m, AM_BASE + 2 * i + 1); }
    static void AmSet(Rt rt, long m, int i, long v) { rt.SetSlot(Val.AsHeap(m), i, v); }

    static int AmIndexOf(Rt rt, long m, long k) {
        int bas = rt.Mark();
        int mi = rt.Push(m), ki = rt.Push(k);
        int n = Count(rt, rt.R(mi));
        int outv = -1;
        for (int i = 0; i < n; i++) {
            if (Flint.Rt.Eq.Equal(rt, AmKey(rt, rt.R(mi), i), rt.R(ki))) { outv = i; break; }
        }
        rt.PopTo(bas);
        return outv;
    }

    public static long Get(Rt rt, long m, long k, long notFound) {
        // A ROW REF answers HERE rather than at every call site: `IsMap` says
        // true for one, so every path that asks "is this a map?" and then calls
        // this would read a table row as an array-map and find nothing.
        if (Val.IsHeap(m) && Obj.Ty(rt.gc.sp, Val.AsHeap(m)) == Obj.TyTableref)
            return Table.refGet(rt, m, k, notFound);
        if (!Val.IsHeap(m)) return notFound;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(m));
        if (t == Obj.TyArraymap) {
            int i = AmIndexOf(rt, m, k);
            return i < 0 ? notFound : AmVal(rt, m, i);
        }
        if (t == Obj.TyHashmap) {
            int bas = rt.Mark();
            int mi = rt.Push(m), ki = rt.Push(k);
            // Hash FIRST, then read the root: hashing a compound key can
            // allocate, and an address read before that would be stale.
            int h = Flint.Rt.Eq.HashValue(rt, rt.R(ki));
            long root = rt.Slot(rt.R(mi), HM_ROOT);
            long r = Val.IsNil(root) ? Val.NotFound : NodeFind(rt, root, 0, h, rt.R(ki));
            rt.PopTo(bas);
            return r == Val.NotFound ? notFound : r;
        }
        return notFound;
    }

    public static bool Contains(Rt rt, long m, long k) {
        return Get(rt, m, k, Val.NotFound) != Val.NotFound;
    }

    static long Promote(Rt rt, long m) {
        int bas = rt.Mark();
        int mi = rt.Push(m);
        int n = Count(rt, m);
        int ri = rt.Push(BnNew(rt, 0, 0, Val.Nil));
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            int ki = rt.Push(AmKey(rt, rt.R(mi), i));
            int vi = rt.Push(AmVal(rt, rt.R(mi), i));
            int h = Flint.Rt.Eq.HashValue(rt, rt.R(ki));
            long nr = NodeAssoc(rt, rt.R(ri), 0, h, rt.R(ki), rt.R(vi), Val.Nil);
            rt.SetR(ri, nr);
            rt.PopTo(ki);
            if (rt.champAdded) cnt++;
        }
        long outv = NewHashMap(rt, cnt, rt.R(ri), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }

    public static long Assoc(Rt rt, long m, long k, long v) {
        int bas = rt.Mark();
        int mi = rt.Push(m), ki = rt.Push(k), vi = rt.Push(v);
        long outv;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(m));
        if (t == Obj.TyArraymap) {
            int n = Count(rt, m);
            int i = AmIndexOf(rt, rt.R(mi), rt.R(ki));
            if (i >= 0) {
                long old = AmVal(rt, rt.R(mi), i);
                if (old == rt.R(vi)) {
                    outv = rt.R(mi);
                } else {
                    int ni = rt.Push(NewArrayMap(rt, n));
                    for (int j = 0; j < n; j++) {
                        AmSet(rt, rt.R(ni), AM_BASE + 2 * j, AmKey(rt, rt.R(mi), j));
                        AmSet(rt, rt.R(ni), AM_BASE + 2 * j + 1, AmVal(rt, rt.R(mi), j));
                    }
                    AmSet(rt, rt.R(ni), AM_BASE + 2 * i + 1, rt.R(vi));
                    AmSet(rt, rt.R(ni), AM_META, rt.Slot(rt.R(mi), AM_META));
                    outv = rt.R(ni);
                }
            } else if (n < ARRAY_MAP_MAX) {
                int ni = rt.Push(NewArrayMap(rt, n + 1));
                for (int j = 0; j < n; j++) {
                    AmSet(rt, rt.R(ni), AM_BASE + 2 * j, AmKey(rt, rt.R(mi), j));
                    AmSet(rt, rt.R(ni), AM_BASE + 2 * j + 1, AmVal(rt, rt.R(mi), j));
                }
                AmSet(rt, rt.R(ni), AM_BASE + 2 * n, rt.R(ki));
                AmSet(rt, rt.R(ni), AM_BASE + 2 * n + 1, rt.R(vi));
                AmSet(rt, rt.R(ni), AM_META, rt.Slot(rt.R(mi), AM_META));
                outv = rt.R(ni);
            } else {
                int pi = rt.Push(Promote(rt, rt.R(mi)));
                outv = Assoc(rt, rt.R(pi), rt.R(ki), rt.R(vi));
            }
        } else if (t == Obj.TyHashmap) {
            int cnt = Count(rt, m);
            int h = Flint.Rt.Eq.HashValue(rt, rt.R(ki));
            int ri = rt.Push(rt.Slot(rt.R(mi), HM_ROOT));
            rt.champAdded = false;
            long nr = NodeAssoc(rt, rt.R(ri), 0, h, rt.R(ki), rt.R(vi), Val.Nil);
            if (nr == rt.R(ri)) {
                outv = rt.R(mi);
            } else {
                int nri = rt.Push(nr);
                bool added = rt.champAdded;
                long meta = rt.Slot(rt.R(mi), HM_META);
                outv = NewHashMap(rt, cnt + (added ? 1 : 0), rt.R(nri), meta);
            }
        } else {
            outv = Val.Nil;
        }
        rt.PopTo(bas);
        return outv;
    }

    public static long Dissoc(Rt rt, long m, long k) {
        // A ROW REF answers here, exactly as it does in `get`. `IsMap` says
        // true for one, and `dissoc`'s builtin guards on `IsMap`, so a ref
        // arrived here and fell through to the NIL at the bottom.
        // `(dissoc row :b)` was nil on all four runtimes while `assoc`,
        // `get`, `count` and `=` on the same ref all worked.
        //
        // It materialises rather than removing a column: the schema is CLOSED
        // (`doc/decisions/0026`), so there is no such thing as a row ref with
        // one column missing. `RefAssoc` already takes the same way out.
        if (Obj.Ty(rt.gc.sp, Val.AsHeap(m)) == Obj.TyTableref) {
            int rb = rt.Mark();
            int rki = rt.Push(k);
            int rmi = rt.Push(Table.refToMap(rt, m));
            long r = Dissoc(rt, rt.R(rmi), rt.R(rki));
            rt.PopTo(rb);
            return r;
        }
        int bas = rt.Mark();
        int mi = rt.Push(m), ki = rt.Push(k);
        long outv;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(m));
        if (t == Obj.TyArraymap) {
            int i = AmIndexOf(rt, rt.R(mi), rt.R(ki));
            if (i < 0) {
                outv = rt.R(mi);
            } else {
                int n = Count(rt, rt.R(mi));
                int ni = rt.Push(NewArrayMap(rt, n - 1));
                int d = 0;
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    AmSet(rt, rt.R(ni), AM_BASE + 2 * d, AmKey(rt, rt.R(mi), j));
                    AmSet(rt, rt.R(ni), AM_BASE + 2 * d + 1, AmVal(rt, rt.R(mi), j));
                    d++;
                }
                AmSet(rt, rt.R(ni), AM_META, rt.Slot(rt.R(mi), AM_META));
                outv = rt.R(ni);
            }
        } else if (t == Obj.TyHashmap) {
            int h = Flint.Rt.Eq.HashValue(rt, rt.R(ki));
            int ri = rt.Push(rt.Slot(rt.R(mi), HM_ROOT));
            rt.champAdded = false;
            long nr = NodeDissoc(rt, rt.R(ri), 0, h, rt.R(ki), Val.Nil);
            if (!rt.champAdded || nr == rt.R(ri)) {
                outv = rt.R(mi);
            } else {
                int cnt = Count(rt, rt.R(mi)) - 1;
                int nri = rt.Push(nr);
                long meta = rt.Slot(rt.R(mi), HM_META);
                outv = cnt == 0 ? Empty(rt) : NewHashMap(rt, cnt, rt.R(nri), meta);
            }
        } else {
            outv = Val.Nil;
        }
        rt.PopTo(bas);
        return outv;
    }

    // --- traversal -----------------------------------------------------------

    /// Every key and value, flattened into the shadow stack above `at`, as
    /// `k,v,k,v,...`. Returns the number of PAIRS.
    ///
    /// Materialised rather than a callback because the callers -- equality,
    /// hashing, `seq` -- all want to walk twice or in another order, and a
    /// callback that allocated mid-walk would need every node rooted anyway.
    public static int Entries(Rt rt, long m, int at) {
        if (!Val.IsHeap(m)) return 0;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(m));
        if (t == Obj.TyArraymap) {
            int n = Count(rt, m);
            int mi = rt.Push(m);
            for (int i = 0; i < n; i++) {
                rt.Push(AmKey(rt, rt.R(mi), i));
                rt.Push(AmVal(rt, rt.R(mi), i));
            }
            // The map itself was pushed first; slide the pairs down over it.
            for (int i = 0; i < 2 * n; i++) rt.SetR(at + i, rt.R(at + 1 + i));
            rt.PopTo(at + 2 * n);
            return n;
        }
        if (t == Obj.TyHashmap) {
            long root = rt.Slot(m, HM_ROOT);
            if (Val.IsNil(root)) return 0;
            return NodeEntries(rt, root, at);
        }
        return 0;
    }

    static int NodeEntries(Rt rt, long node, int at) {
        int ni = rt.Push(node);
        int wrote = 0;
        if (!IsBmnode(rt, rt.R(ni))) {
            int cnt = CnCount(rt, rt.R(ni));
            for (int i = 0; i < cnt; i++) {
                rt.Push(CnKey(rt, rt.R(ni), i));
                rt.Push(CnVal(rt, rt.R(ni), i));
                wrote++;
            }
        } else {
            int ne = System.Numerics.BitOperations.PopCount((uint)(BnDatamap(rt, rt.R(ni))));
            int nn = System.Numerics.BitOperations.PopCount((uint)(BnNodemap(rt, rt.R(ni))));
            for (int i = 0; i < ne; i++) {
                rt.Push(BnKey(rt, rt.R(ni), i));
                rt.Push(BnVal(rt, rt.R(ni), i));
                wrote++;
            }
            for (int j = 0; j < nn; j++) {
                wrote += NodeEntries(rt, BnNode(rt, rt.R(ni), j), rt.Mark());
            }
        }
        // Slide down over the node handle, which was pushed first.
        for (int i = 0; i < 2 * wrote; i++) rt.SetR(at + i, rt.R(at + 1 + i));
        rt.PopTo(at + 2 * wrote);
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

    public static long TransientOf(Rt rt, long m) {
        int bas = rt.Mark();
        int mi = rt.Push(m);
        // An array-map becomes a CHAMP FIRST: one transient implementation, and
        // the workload that uses transients is the one with many entries.
        long hm = IsArrayMap(rt, rt.R(mi)) ? Promote(rt, rt.R(mi)) : rt.R(mi);
        int hi = rt.Push(hm);
        int ei = rt.Push(Vec.NewEditToken(rt));
        long a = rt.Alloc(Obj.TyTmap, 3);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        long h = rt.R(hi);
        rt.SetSlot(a, TM_CNT, Val.Fixnum(Count(rt, h)));
        rt.SetSlot(a, TM_ROOT, rt.Slot(h, HM_ROOT));
        rt.SetSlot(a, TM_EDIT, rt.R(ei));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    public static long TGet(Rt rt, long t, long k, long notFound) {
        int bas = rt.Mark();
        int ti = rt.Push(t), ki = rt.Push(k);
        int h = Flint.Rt.Eq.HashValue(rt, rt.R(ki));
        long root = rt.Slot(rt.R(ti), TM_ROOT);
        long r = Val.IsNil(root) ? Val.NotFound : NodeFind(rt, root, 0, h, rt.R(ki));
        rt.PopTo(bas);
        return r == Val.NotFound ? notFound : r;
    }

    public static long TAssoc(Rt rt, long t, long k, long v) {
        if (Val.IsNil(rt.Slot(t, TM_EDIT)))
            return rt.ThrowStr("IllegalStateException", "transient used after persistent!");
        int bas = rt.Mark();
        int ti = rt.Push(t), ki = rt.Push(k), vi = rt.Push(v);
        int ei = rt.Push(rt.Slot(rt.R(ti), TM_EDIT));
        int h = Flint.Rt.Eq.HashValue(rt, rt.R(ki));
        int ri = rt.Push(rt.Slot(rt.R(ti), TM_ROOT));
        rt.champAdded = false;
        long nr = NodeAssoc(rt, rt.R(ri), 0, h, rt.R(ki), rt.R(vi), rt.R(ei));
        bool added = rt.champAdded;
        long tv = rt.R(ti);
        rt.SetSlot(Val.AsHeap(tv), TM_ROOT, nr);
        if (added) rt.SetSlot(Val.AsHeap(tv), TM_CNT, Val.Fixnum(Val.AsFixnum(rt.Slot(tv, TM_CNT)) + 1));
        rt.PopTo(bas);
        return tv;
    }

    public static long TDissoc(Rt rt, long t, long k) {
        if (Val.IsNil(rt.Slot(t, TM_EDIT)))
            return rt.ThrowStr("IllegalStateException", "transient used after persistent!");
        int bas = rt.Mark();
        int ti = rt.Push(t), ki = rt.Push(k);
        int ei = rt.Push(rt.Slot(rt.R(ti), TM_EDIT));
        int h = Flint.Rt.Eq.HashValue(rt, rt.R(ki));
        int ri = rt.Push(rt.Slot(rt.R(ti), TM_ROOT));
        rt.champAdded = false;
        long nr = NodeDissoc(rt, rt.R(ri), 0, h, rt.R(ki), rt.R(ei));
        bool removed = rt.champAdded;
        long tv = rt.R(ti);
        rt.SetSlot(Val.AsHeap(tv), TM_ROOT, nr);
        if (removed) rt.SetSlot(Val.AsHeap(tv), TM_CNT, Val.Fixnum(Val.AsFixnum(rt.Slot(tv, TM_CNT)) - 1));
        rt.PopTo(bas);
        return tv;
    }

    public static int TCount(Rt rt, long t) => (int) Val.AsFixnum(rt.Slot(t, TM_CNT));

    public static long TPersistent(Rt rt, long t) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int cnt = TCount(rt, rt.R(ti));
        int ri = rt.Push(rt.Slot(rt.R(ti), TM_ROOT));
        // Invalidate: using the handle afterwards is a bug, not a silent
        // mutation of a value somebody else now owns.
        rt.SetSlot(Val.AsHeap(rt.R(ti)), TM_EDIT, Val.Nil);
        long outv = cnt == 0 ? Empty(rt) : NewHashMap(rt, cnt, rt.R(ri), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }

    /// `[k, v]`, the object a map's `seq` yields. A `TY_MAPENTRY` and not a
    /// vector, so `key`/`val` are O(1) and the entry can still be read as a
    /// two-element sequential -- which is what makes `(into {} (map ...))` and
    /// destructuring `[[k v] ...]` both work over the same object.
    public static long Entry(Rt rt, long k, long v) {
        int bas = rt.Mark();
        int ki = rt.Push(k), vi = rt.Push(v);
        long a = rt.Alloc(Obj.TyMapentry, 2);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, 0, rt.R(ki));
        rt.SetSlot(a, 1, rt.R(vi));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// The entries as a VECTOR of map entries, which is what `seq` walks.
    ///
    /// Materialised rather than a lazy cursor over the trie: a cursor would
    /// have to hold a path of node addresses across allocations the consumer
    /// makes, and every one of those would need rooting.
    public static long EntryVector(Rt rt, long m) {
        // CHARGED UP FRONT: `n` is known, so this refuses rather than ticks.
        if (!rt.ChargeChecked(Count(rt, m), "seq of a map")) return Val.Nil;
        if (Val.IsHeap(m) && Obj.Ty(rt.gc.sp, Val.AsHeap(m)) == Obj.TyTableref)
            return EntryVector(rt, Table.refToMap(rt, m));
        int bas = rt.Mark();
        int mi = rt.Push(m);
        int at = rt.Mark();
        int n = Entries(rt, rt.R(mi), at);
        int ai = rt.Push(Vec.Empty(rt));
        for (int i = 0; i < n; i++) {
            long e = Entry(rt, rt.R(at + 2 * i), rt.R(at + 2 * i + 1));
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
        if (Count(rt, rt.R(ai)) != Count(rt, rt.R(bi))) { rt.PopTo(bas); return false; }
        int at = rt.Mark();
        int n = Entries(rt, rt.R(ai), at);
        bool ok = true;
        for (int i = 0; i < n && ok; i++) {
            int m = rt.Mark();
            int ki = rt.Push(rt.R(at + 2 * i));
            int vi = rt.Push(rt.R(at + 2 * i + 1));
            int oi = rt.Push(Get(rt, rt.R(bi), rt.R(ki), Val.NotFound));
            ok = rt.R(oi) != Val.NotFound && Flint.Rt.Eq.Equal(rt, rt.R(vi), rt.R(oi));
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
        int n = Entries(rt, rt.R(mi), at);
        int acc = 0;
        for (int i = 0; i < n; i++) {
            int kh = Flint.Rt.Eq.HashValue(rt, rt.R(at + 2 * i));
            int vh = Flint.Rt.Eq.HashValue(rt, rt.R(at + 2 * i + 1));
            acc = Flint.Rt.Hash.UnorderedStep(acc, Flint.Rt.Hash.MixCollHash(
                Flint.Rt.Hash.OrderedStep(Flint.Rt.Hash.OrderedStep(1, kh), vh), 2));
        }
        rt.PopTo(bas);
        return Flint.Rt.Hash.MixCollHash(acc, n);
    }
}
