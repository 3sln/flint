package com.flint.rt;

import static com.flint.rt.Obj.*;

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
    static final int BN_EDIT = 0, BN_DATAMAP = 1, BN_NODEMAP = 2, BN_BASE = 3;
    // collision node layout
    static final int CN_EDIT = 0, CN_HASH = 1, CN_BASE = 2;

    public static final int HASH_BITS = 5, HASH_WIDTH = 32;

    static int mask(int h, int shift) { return (h >>> shift) & 0x1f; }
    static int bitpos(int h, int shift) { return 1 << mask(h, shift); }
    static int indexOf(int bitmap, int bit) { return Integer.bitCount(bitmap & (bit - 1)); }

    static int olen(Rt rt, long v) { return len(rt.gc.sp, Val.asHeap(v)); }

    // --- node primitives ----------------------------------------------------

    static long bnNew(Rt rt, int datamap, int nodemap, long edit) {
        int ne = Integer.bitCount(datamap), nn = Integer.bitCount(nodemap);
        int e = rt.push(edit);
        long a = rt.alloc(TY_BMNODE, BN_BASE + 2 * ne + nn);
        long ed = rt.r(e);
        rt.popTo(e);
        if (a == 0) return Val.NIL;
        rt.setSlot(a, BN_EDIT, ed);
        rt.setSlot(a, BN_DATAMAP, Val.fixnum(datamap & 0xFFFFFFFFL));
        rt.setSlot(a, BN_NODEMAP, Val.fixnum(nodemap & 0xFFFFFFFFL));
        return Val.heap(a);
    }

    static int bnDatamap(Rt rt, long n) { return (int) Val.asFixnum(rt.slot(n, BN_DATAMAP)); }
    static int bnNodemap(Rt rt, long n) { return (int) Val.asFixnum(rt.slot(n, BN_NODEMAP)); }
    static long bnKey(Rt rt, long n, int i) { return rt.slot(n, BN_BASE + 2 * i); }
    static long bnVal(Rt rt, long n, int i) { return rt.slot(n, BN_BASE + 2 * i + 1); }
    static void bnSetKey(Rt rt, long n, int i, long v) { rt.setSlot(Val.asHeap(n), BN_BASE + 2 * i, v); }
    static void bnSetVal(Rt rt, long n, int i, long v) { rt.setSlot(Val.asHeap(n), BN_BASE + 2 * i + 1, v); }
    /// Sub-nodes live at the END, in DESCENDING bit order.
    static long bnNode(Rt rt, long n, int j) { return rt.slot(n, olen(rt, n) - 1 - j); }
    static void bnSetNode(Rt rt, long n, int j, long v) {
        rt.setSlot(Val.asHeap(n), olen(rt, n) - 1 - j, v);
    }

    static long cnNew(Rt rt, int h, int npairs, long edit) {
        int e = rt.push(edit);
        long a = rt.alloc(TY_COLLNODE, CN_BASE + 2 * npairs);
        long ed = rt.r(e);
        rt.popTo(e);
        if (a == 0) return Val.NIL;
        rt.setSlot(a, CN_EDIT, ed);
        rt.setSlot(a, CN_HASH, Val.fixnum(h & 0xFFFFFFFFL));
        return Val.heap(a);
    }
    static int cnCount(Rt rt, long n) { return (olen(rt, n) - CN_BASE) / 2; }
    static int cnHash(Rt rt, long n) { return (int) Val.asFixnum(rt.slot(n, CN_HASH)); }
    static long cnKey(Rt rt, long n, int i) { return rt.slot(n, CN_BASE + 2 * i); }
    static long cnVal(Rt rt, long n, int i) { return rt.slot(n, CN_BASE + 2 * i + 1); }
    static void cnSet(Rt rt, long n, int i, long v) { rt.setSlot(Val.asHeap(n), i, v); }

    static boolean isBmnode(Rt rt, long n) { return ty(rt.gc.sp, Val.asHeap(n)) == TY_BMNODE; }

    /// EMPTY / ONE / MORE, the CHAMP size predicate that drives collapsing.
    static int nodeSizeClass(Rt rt, long n) {
        if (!isBmnode(rt, n)) return 2;   // a collision node always has two pairs
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        if (nm != 0) return 2;
        int c = Integer.bitCount(dm);
        return c == 0 ? 0 : c == 1 ? 1 : 2;
    }

    // --- lookup -------------------------------------------------------------

    static long nodeFind(Rt rt, long n, int shift, int h, long key) {
        // The node being walked and the key are rooted: `eq` on a compound key
        // allocates (it seqs both sides), so a collection can happen in the
        // middle of a lookup and move everything this walk is holding.
        int base = rt.mark();
        int ni = rt.push(n);
        int ki = rt.push(key);
        long out = Val.NOT_FOUND;
        for (;;) {
            if (!isBmnode(rt, rt.r(ni))) {
                if (cnHash(rt, rt.r(ni)) != h) break;
                int cnt = cnCount(rt, rt.r(ni));
                for (int i = 0; i < cnt; i++) {
                    if (Eq.eq(rt, cnKey(rt, rt.r(ni), i), rt.r(ki))) {
                        out = cnVal(rt, rt.r(ni), i);
                        break;
                    }
                }
                break;
            }
            int bit = bitpos(h, shift);
            int dm = bnDatamap(rt, rt.r(ni));
            if ((dm & bit) != 0) {
                int i = indexOf(dm, bit);
                if (Eq.eq(rt, bnKey(rt, rt.r(ni), i), rt.r(ki))) out = bnVal(rt, rt.r(ni), i);
                break;
            }
            int nm = bnNodemap(rt, rt.r(ni));
            if ((nm & bit) == 0) break;
            rt.setR(ni, bnNode(rt, rt.r(ni), indexOf(nm, bit)));
            shift += HASH_BITS;
        }
        rt.popTo(base);
        return out;
    }

    // --- structural copies ---------------------------------------------------

    static long mergeTwo(Rt rt, int shift, long k0, long v0, int h0,
                         long k1, long v1, int h1, long edit) {
        int base = rt.mark();
        int ik0 = rt.push(k0), iv0 = rt.push(v0), ik1 = rt.push(k1), iv1 = rt.push(v1);
        int ie = rt.push(edit);
        long out;
        if (shift >= 32) {
            // Two keys with the SAME 32-bit hash: a collision node, which is
            // the only place equal hashes are stored side by side.
            out = cnNew(rt, h0, 2, rt.r(ie));
            if (!Val.isNil(out)) {
                cnSet(rt, out, CN_BASE, rt.r(ik0));
                cnSet(rt, out, CN_BASE + 1, rt.r(iv0));
                cnSet(rt, out, CN_BASE + 2, rt.r(ik1));
                cnSet(rt, out, CN_BASE + 3, rt.r(iv1));
            }
        } else {
            int m0 = mask(h0, shift), m1 = mask(h1, shift);
            if (m0 != m1) {
                int dm = (1 << m0) | (1 << m1);
                out = bnNew(rt, dm, 0, rt.r(ie));
                if (!Val.isNil(out)) {
                    // In BIT ORDER, not argument order: `indexOf` derives a
                    // slot from the bitmap, so the pair with the lower mask
                    // must land first or every later lookup is off by one.
                    if (m0 < m1) {
                        bnSetKey(rt, out, 0, rt.r(ik0)); bnSetVal(rt, out, 0, rt.r(iv0));
                        bnSetKey(rt, out, 1, rt.r(ik1)); bnSetVal(rt, out, 1, rt.r(iv1));
                    } else {
                        bnSetKey(rt, out, 0, rt.r(ik1)); bnSetVal(rt, out, 0, rt.r(iv1));
                        bnSetKey(rt, out, 1, rt.r(ik0)); bnSetVal(rt, out, 1, rt.r(iv0));
                    }
                }
            } else {
                long sub = mergeTwo(rt, shift + HASH_BITS, rt.r(ik0), rt.r(iv0), h0,
                                    rt.r(ik1), rt.r(iv1), h1, rt.r(ie));
                int si = rt.push(sub);
                out = bnNew(rt, 0, 1 << m0, rt.r(ie));
                if (!Val.isNil(out)) bnSetNode(rt, out, 0, rt.r(si));
            }
        }
        rt.popTo(base);
        return out;
    }

    static long bnCopyInsertEntry(Rt rt, long n, int bit, long key, long val, long edit) {
        int base = rt.mark();
        int ni = rt.push(n), ki = rt.push(key), vi = rt.push(val), ei = rt.push(edit);
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        int ne = Integer.bitCount(dm), nn = Integer.bitCount(nm);
        int at = indexOf(dm, bit);
        long out = bnNew(rt, dm | bit, nm, rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        for (int k = 0; k < ne; k++) {
            int d = k < at ? k : k + 1;
            bnSetKey(rt, rt.r(oi), d, bnKey(rt, rt.r(ni), k));
            bnSetVal(rt, rt.r(oi), d, bnVal(rt, rt.r(ni), k));
        }
        bnSetKey(rt, rt.r(oi), at, rt.r(ki));
        bnSetVal(rt, rt.r(oi), at, rt.r(vi));
        for (int j = 0; j < nn; j++) bnSetNode(rt, rt.r(oi), j, bnNode(rt, rt.r(ni), j));
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    static long bnCopyRemoveEntry(Rt rt, long n, int bit, long edit) {
        int base = rt.mark();
        int ni = rt.push(n), ei = rt.push(edit);
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        int ne = Integer.bitCount(dm), nn = Integer.bitCount(nm);
        int at = indexOf(dm, bit);
        long out = bnNew(rt, dm ^ bit, nm, rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        for (int k = 0; k < ne; k++) {
            if (k == at) continue;
            int d = k < at ? k : k - 1;
            bnSetKey(rt, rt.r(oi), d, bnKey(rt, rt.r(ni), k));
            bnSetVal(rt, rt.r(oi), d, bnVal(rt, rt.r(ni), k));
        }
        for (int j = 0; j < nn; j++) bnSetNode(rt, rt.r(oi), j, bnNode(rt, rt.r(ni), j));
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    static long bnCopySetValue(Rt rt, long n, int at, long val, long edit) {
        int base = rt.mark();
        int ni = rt.push(n), vi = rt.push(val), ei = rt.push(edit);
        // Owned by this transient? Then write through.
        if (!Val.isNil(rt.r(ei)) && rt.slot(rt.r(ni), BN_EDIT) == rt.r(ei)) {
            long nn0 = rt.r(ni);
            bnSetVal(rt, nn0, at, rt.r(vi));
            rt.popTo(base);
            return nn0;
        }
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        long out = bnNew(rt, dm, nm, rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        for (int k = 0; k < Integer.bitCount(dm); k++) {
            bnSetKey(rt, rt.r(oi), k, bnKey(rt, rt.r(ni), k));
            bnSetVal(rt, rt.r(oi), k, bnVal(rt, rt.r(ni), k));
        }
        for (int j = 0; j < Integer.bitCount(nm); j++) bnSetNode(rt, rt.r(oi), j, bnNode(rt, rt.r(ni), j));
        bnSetVal(rt, rt.r(oi), at, rt.r(vi));
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    static long bnCopySetNode(Rt rt, long n, int at, long sub, long edit) {
        int base = rt.mark();
        int ni = rt.push(n), si = rt.push(sub), ei = rt.push(edit);
        if (!Val.isNil(rt.r(ei)) && rt.slot(rt.r(ni), BN_EDIT) == rt.r(ei)) {
            long nn0 = rt.r(ni);
            bnSetNode(rt, nn0, at, rt.r(si));
            rt.popTo(base);
            return nn0;
        }
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        long out = bnNew(rt, dm, nm, rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        for (int k = 0; k < Integer.bitCount(dm); k++) {
            bnSetKey(rt, rt.r(oi), k, bnKey(rt, rt.r(ni), k));
            bnSetVal(rt, rt.r(oi), k, bnVal(rt, rt.r(ni), k));
        }
        for (int j = 0; j < Integer.bitCount(nm); j++) bnSetNode(rt, rt.r(oi), j, bnNode(rt, rt.r(ni), j));
        bnSetNode(rt, rt.r(oi), at, rt.r(si));
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    /// An inline entry becomes a sub-node: 2 entry slots out, 1 node slot in.
    static long bnInlineToNode(Rt rt, long n, int bit, long sub, long edit) {
        int base = rt.mark();
        int ni = rt.push(n), si = rt.push(sub), ei = rt.push(edit);
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        int ne = Integer.bitCount(dm), nn = Integer.bitCount(nm);
        int atEntry = indexOf(dm, bit);
        int atNode = indexOf(nm, bit);   // == the index in the NEW nodemap too
        long out = bnNew(rt, dm ^ bit, nm | bit, rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        for (int k = 0; k < ne; k++) {
            if (k == atEntry) continue;
            int d = k < atEntry ? k : k - 1;
            bnSetKey(rt, rt.r(oi), d, bnKey(rt, rt.r(ni), k));
            bnSetVal(rt, rt.r(oi), d, bnVal(rt, rt.r(ni), k));
        }
        for (int j = 0; j < nn; j++) {
            int d = j < atNode ? j : j + 1;
            bnSetNode(rt, rt.r(oi), d, bnNode(rt, rt.r(ni), j));
        }
        bnSetNode(rt, rt.r(oi), atNode, rt.r(si));
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    /// A sub-node has shrunk to one entry and folds back inline. THIS is the
    /// step Clojure's HAMT omits, and the reason CHAMP's form is canonical.
    static long bnNodeToInline(Rt rt, long n, int bit, long key, long val, long edit) {
        int base = rt.mark();
        int ni = rt.push(n), ki = rt.push(key), vi = rt.push(val), ei = rt.push(edit);
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        int ne = Integer.bitCount(dm), nn = Integer.bitCount(nm);
        int atEntry = indexOf(dm, bit), atNode = indexOf(nm, bit);
        long out = bnNew(rt, dm | bit, nm ^ bit, rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        for (int k = 0; k < ne; k++) {
            int d = k < atEntry ? k : k + 1;
            bnSetKey(rt, rt.r(oi), d, bnKey(rt, rt.r(ni), k));
            bnSetVal(rt, rt.r(oi), d, bnVal(rt, rt.r(ni), k));
        }
        bnSetKey(rt, rt.r(oi), atEntry, rt.r(ki));
        bnSetVal(rt, rt.r(oi), atEntry, rt.r(vi));
        for (int j = 0; j < nn; j++) {
            if (j == atNode) continue;
            int d = j < atNode ? j : j - 1;
            bnSetNode(rt, rt.r(oi), d, bnNode(rt, rt.r(ni), j));
        }
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    // --- assoc / dissoc on nodes ---------------------------------------------

    /// Returns the new node. `rt.champAdded` says whether the count grew.
    static long nodeAssoc(Rt rt, long n, int shift, int h, long key, long val, long edit) {
        int base = rt.mark();
        int ni = rt.push(n), ki = rt.push(key), vi = rt.push(val), ei = rt.push(edit);
        long out;
        if (!isBmnode(rt, n)) {
            out = collAssoc(rt, rt.r(ni), h, rt.r(ki), rt.r(vi), rt.r(ei), shift);
            rt.popTo(base);
            return out;
        }
        int bit = bitpos(h, shift);
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        if ((dm & bit) != 0) {
            int at = indexOf(dm, bit);
            int k0i = rt.push(bnKey(rt, rt.r(ni), at));
            if (Eq.eq(rt, rt.r(k0i), rt.r(ki))) {
                rt.champAdded = false;
                long v0 = bnVal(rt, rt.r(ni), at);
                out = v0 == rt.r(vi) ? rt.r(ni)
                    : bnCopySetValue(rt, rt.r(ni), at, rt.r(vi), rt.r(ei));
            } else {
                rt.champAdded = true;
                int v0i = rt.push(bnVal(rt, rt.r(ni), at));
                int h0 = Eq.hashValue(rt, rt.r(k0i));
                long sub = mergeTwo(rt, shift + HASH_BITS, rt.r(k0i), rt.r(v0i), h0,
                                    rt.r(ki), rt.r(vi), h, rt.r(ei));
                int si = rt.push(sub);
                out = bnInlineToNode(rt, rt.r(ni), bit, rt.r(si), rt.r(ei));
            }
        } else if ((nm & bit) != 0) {
            int at = indexOf(nm, bit);
            // Rooted, because the comparison below is what decides whether this
            // node changed. `nodeAssoc` allocates, a collection can move `sub`,
            // and a stale address that happened to match the new one would drop
            // the whole subtree's update on the floor -- a key silently missing
            // from a map whose count says it is there.
            int subi = rt.push(bnNode(rt, rt.r(ni), at));
            long newsub = nodeAssoc(rt, rt.r(subi), shift + HASH_BITS, h,
                                    rt.r(ki), rt.r(vi), rt.r(ei));
            out = newsub == rt.r(subi) ? rt.r(ni)
                : bnCopySetNode(rt, rt.r(ni), at, newsub, rt.r(ei));
        } else {
            rt.champAdded = true;
            out = bnCopyInsertEntry(rt, rt.r(ni), bit, rt.r(ki), rt.r(vi), rt.r(ei));
        }
        rt.popTo(base);
        return out;
    }

    static long collAssoc(Rt rt, long n, int h, long key, long val, long edit, int shift) {
        int nh = cnHash(rt, n);
        if (nh != h) {
            // Different hash at this depth: wrap in a bitmap node and retry.
            int base = rt.mark();
            int ni = rt.push(n), ki = rt.push(key), vi = rt.push(val), ei = rt.push(edit);
            int wi = rt.push(bnNew(rt, 0, bitpos(nh, shift), rt.r(ei)));
            bnSetNode(rt, rt.r(wi), 0, rt.r(ni));
            long out = nodeAssoc(rt, rt.r(wi), shift, h, rt.r(ki), rt.r(vi), rt.r(ei));
            rt.popTo(base);
            return out;
        }
        int scan = rt.mark();
        int sni = rt.push(n), ski = rt.push(key);
        int cnt = cnCount(rt, rt.r(sni));
        int hit = -1;
        for (int i = 0; i < cnt; i++) {
            if (Eq.eq(rt, cnKey(rt, rt.r(sni), i), rt.r(ski))) { hit = i; break; }
        }
        long nn = rt.r(sni), kk = rt.r(ski);
        rt.popTo(scan);
        if (hit >= 0) {
            rt.champAdded = false;
            int base = rt.mark();
            int ni = rt.push(nn), vi = rt.push(val), ei = rt.push(edit);
            long out = cnCopySetVal(rt, rt.r(ni), hit, rt.r(vi), rt.r(ei));
            rt.popTo(base);
            return out;
        }
        rt.champAdded = true;
        int base = rt.mark();
        int ni = rt.push(nn), ki = rt.push(kk), vi = rt.push(val), ei = rt.push(edit);
        long out = cnNew(rt, h, cnt + 1, rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        for (int i = 0; i < cnt; i++) {
            cnSet(rt, rt.r(oi), CN_BASE + 2 * i, cnKey(rt, rt.r(ni), i));
            cnSet(rt, rt.r(oi), CN_BASE + 2 * i + 1, cnVal(rt, rt.r(ni), i));
        }
        cnSet(rt, rt.r(oi), CN_BASE + 2 * cnt, rt.r(ki));
        cnSet(rt, rt.r(oi), CN_BASE + 2 * cnt + 1, rt.r(vi));
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    static long cnCopySetVal(Rt rt, long n, int i, long val, long edit) {
        int cnt = cnCount(rt, n);
        int base = rt.mark();
        int ni = rt.push(n), vi = rt.push(val), ei = rt.push(edit);
        long out = cnNew(rt, cnHash(rt, rt.r(ni)), cnt, rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        for (int k = 0; k < cnt; k++) {
            cnSet(rt, rt.r(oi), CN_BASE + 2 * k, cnKey(rt, rt.r(ni), k));
            cnSet(rt, rt.r(oi), CN_BASE + 2 * k + 1, cnVal(rt, rt.r(ni), k));
        }
        cnSet(rt, rt.r(oi), CN_BASE + 2 * i + 1, rt.r(vi));
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    static long nodeDissoc(Rt rt, long n, int shift, int h, long key, long edit) {
        int base = rt.mark();
        int ni = rt.push(n), ki = rt.push(key), ei = rt.push(edit);
        long out;
        if (!isBmnode(rt, n)) {
            out = collDissoc(rt, rt.r(ni), rt.r(ki), rt.r(ei));
            rt.popTo(base);
            return out;
        }
        int bit = bitpos(h, shift);
        int dm = bnDatamap(rt, n), nm = bnNodemap(rt, n);
        if ((dm & bit) != 0) {
            int at = indexOf(dm, bit);
            boolean same0 = Eq.eq(rt, bnKey(rt, rt.r(ni), at), rt.r(ki));
            if (!same0) {
                rt.champAdded = false;
                out = rt.r(ni);
            } else {
                rt.champAdded = true;   // "changed"
                if (Integer.bitCount(dm) == 2 && nm == 0) {
                    // Collapse to a single-entry node so the parent can inline it.
                    int other = 1 - at;
                    int oki = rt.push(bnKey(rt, rt.r(ni), other));
                    int ovi = rt.push(bnVal(rt, rt.r(ni), other));
                    int oh = Eq.hashValue(rt, rt.r(oki));
                    int newdm = shift == 0 ? (dm ^ bit) : bitpos(oh, 0);
                    long nn0 = bnNew(rt, newdm, 0, rt.r(ei));
                    if (!Val.isNil(nn0)) {
                        bnSetKey(rt, nn0, 0, rt.r(oki));
                        bnSetVal(rt, nn0, 0, rt.r(ovi));
                    }
                    out = nn0;
                } else {
                    out = bnCopyRemoveEntry(rt, rt.r(ni), bit, rt.r(ei));
                }
            }
        } else if ((nm & bit) != 0) {
            int at = indexOf(nm, bit);
            int subi = rt.push(bnNode(rt, rt.r(ni), at));
            long newsub = nodeDissoc(rt, rt.r(subi), shift + HASH_BITS, h, rt.r(ki), rt.r(ei));
            if (newsub == rt.r(subi)) {
                out = rt.r(ni);
            } else if (nodeSizeClass(rt, newsub) == 1) {
                int si = rt.push(newsub);
                if (dm == 0 && Integer.bitCount(nm) == 1) {
                    // This node has nothing else: replace it with the child.
                    out = rt.r(si);
                } else {
                    int ki2 = rt.push(bnKey(rt, rt.r(si), 0));
                    int vi2 = rt.push(bnVal(rt, rt.r(si), 0));
                    out = bnNodeToInline(rt, rt.r(ni), bit, rt.r(ki2), rt.r(vi2), rt.r(ei));
                }
            } else {
                out = bnCopySetNode(rt, rt.r(ni), at, newsub, rt.r(ei));
            }
        } else {
            rt.champAdded = false;
            out = rt.r(ni);
        }
        rt.popTo(base);
        return out;
    }

    static long collDissoc(Rt rt, long n, long key, long edit) {
        int scan = rt.mark();
        int sni = rt.push(n), ski = rt.push(key);
        int cnt = cnCount(rt, rt.r(sni));
        int found = -1;
        for (int i = 0; i < cnt; i++) {
            if (Eq.eq(rt, cnKey(rt, rt.r(sni), i), rt.r(ski))) { found = i; break; }
        }
        long nn = rt.r(sni);
        rt.popTo(scan);
        if (found < 0) { rt.champAdded = false; return nn; }
        rt.champAdded = true;
        int base = rt.mark();
        int ni = rt.push(nn), ei = rt.push(edit);
        long out;
        if (cnt == 2) {
            // Down to one pair: become a single-entry bitmap node so the parent
            // can fold it back inline.
            int other = 1 - found;
            int ki = rt.push(cnKey(rt, rt.r(ni), other));
            int vi = rt.push(cnVal(rt, rt.r(ni), other));
            int kh = Eq.hashValue(rt, rt.r(ki));
            out = bnNew(rt, bitpos(kh, 0), 0, rt.r(ei));
            if (!Val.isNil(out)) {
                bnSetKey(rt, out, 0, rt.r(ki));
                bnSetVal(rt, out, 0, rt.r(vi));
            }
        } else {
            long o = cnNew(rt, cnHash(rt, rt.r(ni)), cnt - 1, rt.r(ei));
            if (Val.isNil(o)) { rt.popTo(base); return Val.NIL; }
            int oi = rt.push(o);
            int d = 0;
            for (int i = 0; i < cnt; i++) {
                if (i == found) continue;
                cnSet(rt, rt.r(oi), CN_BASE + 2 * d, cnKey(rt, rt.r(ni), i));
                cnSet(rt, rt.r(oi), CN_BASE + 2 * d + 1, cnVal(rt, rt.r(ni), i));
                d++;
            }
            out = rt.r(oi);
        }
        rt.popTo(base);
        return out;
    }

    // --- the map objects -----------------------------------------------------

    public static boolean isMap(Rt rt, long v) {
        if (!Val.isHeap(v)) return false;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        // A ROW REF is a map: `get`, keyword lookup, `count`, `keys`, `vals`
        // and `=` against a map all work, so code that does not know it has a
        // table keeps working (`doc/decisions/0026`).
        return t == TY_ARRAYMAP || t == TY_HASHMAP || t == Obj.TY_TABLEREF;
    }
    public static boolean isArrayMap(Rt rt, long v) {
        return Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_ARRAYMAP;
    }

    public static int count(Rt rt, long m) {
        int t = ty(rt.gc.sp, Val.asHeap(m));
        // A ROW REF counts its COLUMNS. `isMap` says true for one, so anything
        // that asks "is this a map?" and then calls a map internal lands here
        // -- and reading a ref as an array-map is not a wrong answer, it is
        // garbage that becomes a bogus pointer later (`doc/decisions/0026`).
        if (t == Obj.TY_TABLEREF) return Table.schemaLen(rt, rt.slot(m, Table.RF_SCHEMA));
        if (t == TY_ARRAYMAP) return (olen(rt, m) - AM_BASE) / 2;
        if (t == TY_HASHMAP) return (int) Val.asFixnum(rt.slot(m, HM_CNT));
        return 0;
    }

    static long newArrayMap(Rt rt, int n) {
        long a = rt.alloc(TY_ARRAYMAP, AM_BASE + 2 * n);
        if (a == 0) return Val.NIL;
        rt.setSlot(a, AM_META, Val.NIL);
        rt.setSlot(a, AM_HASH, Val.NIL);
        return Val.heap(a);
    }

    /// The SINGLETON empty map. Allocating a fresh one per call is what made
    /// this port bill more gas than the Rust runtime for the same program.
    public static long empty(Rt rt) {
        long s = rt.roots.shared.singletons[Rt.SING_EMPTY_MAP];
        return Val.isNil(s) ? newArrayMap(rt, 0) : s;
    }

    /// The one allocation `initSingletons` makes.
    static long newEmpty(Rt rt) { return newArrayMap(rt, 0); }

    static long newHashMap(Rt rt, int cnt, long root, long meta) {
        int base = rt.mark();
        int ri = rt.push(root), mi = rt.push(meta);
        long a = rt.alloc(TY_HASHMAP, 4);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, HM_CNT, Val.fixnum(cnt));
        rt.setSlot(a, HM_ROOT, rt.r(ri));
        rt.setSlot(a, HM_META, rt.r(mi));
        rt.setSlot(a, HM_HASH, Val.NIL);
        rt.popTo(base);
        return Val.heap(a);
    }

    static long amKey(Rt rt, long m, int i) { return rt.slot(m, AM_BASE + 2 * i); }
    static long amVal(Rt rt, long m, int i) { return rt.slot(m, AM_BASE + 2 * i + 1); }
    static void amSet(Rt rt, long m, int i, long v) { rt.setSlot(Val.asHeap(m), i, v); }

    static int amIndexOf(Rt rt, long m, long k) {
        int base = rt.mark();
        int mi = rt.push(m), ki = rt.push(k);
        int n = count(rt, rt.r(mi));
        int out = -1;
        for (int i = 0; i < n; i++) {
            if (Eq.eq(rt, amKey(rt, rt.r(mi), i), rt.r(ki))) { out = i; break; }
        }
        rt.popTo(base);
        return out;
    }

    public static long get(Rt rt, long m, long k, long notFound) {
        // A ROW REF answers HERE rather than at every call site. `isMap` says
        // true for one, so every path that asks "is this a map?" and then calls
        // this would otherwise read a table row as an array-map and find
        // nothing -- which is what `(:name row)` did.
        if (Val.isHeap(m) && ty(rt.gc.sp, Val.asHeap(m)) == Obj.TY_TABLEREF)
            return Table.refGet(rt, m, k, notFound);
        if (!Val.isHeap(m)) return notFound;
        int t = ty(rt.gc.sp, Val.asHeap(m));
        if (t == TY_ARRAYMAP) {
            int i = amIndexOf(rt, m, k);
            return i < 0 ? notFound : amVal(rt, m, i);
        }
        if (t == TY_HASHMAP) {
            int base = rt.mark();
            int mi = rt.push(m), ki = rt.push(k);
            // Hash FIRST, then read the root: hashing a compound key can
            // allocate, and an address read before that would be stale.
            int h = Eq.hashValue(rt, rt.r(ki));
            long root = rt.slot(rt.r(mi), HM_ROOT);
            long r = Val.isNil(root) ? Val.NOT_FOUND : nodeFind(rt, root, 0, h, rt.r(ki));
            rt.popTo(base);
            return r == Val.NOT_FOUND ? notFound : r;
        }
        return notFound;
    }

    public static boolean contains(Rt rt, long m, long k) {
        return get(rt, m, k, Val.NOT_FOUND) != Val.NOT_FOUND;
    }

    static long promote(Rt rt, long m) {
        int base = rt.mark();
        int mi = rt.push(m);
        int n = count(rt, m);
        int ri = rt.push(bnNew(rt, 0, 0, Val.NIL));
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            int ki = rt.push(amKey(rt, rt.r(mi), i));
            int vi = rt.push(amVal(rt, rt.r(mi), i));
            int h = Eq.hashValue(rt, rt.r(ki));
            long nr = nodeAssoc(rt, rt.r(ri), 0, h, rt.r(ki), rt.r(vi), Val.NIL);
            rt.setR(ri, nr);
            rt.popTo(ki);
            if (rt.champAdded) cnt++;
        }
        long out = newHashMap(rt, cnt, rt.r(ri), Val.NIL);
        rt.popTo(base);
        return out;
    }

    public static long assoc(Rt rt, long m, long k, long v) {
        int base = rt.mark();
        int mi = rt.push(m), ki = rt.push(k), vi = rt.push(v);
        long out;
        int t = ty(rt.gc.sp, Val.asHeap(m));
        if (t == TY_ARRAYMAP) {
            int n = count(rt, m);
            int i = amIndexOf(rt, rt.r(mi), rt.r(ki));
            if (i >= 0) {
                long old = amVal(rt, rt.r(mi), i);
                if (old == rt.r(vi)) {
                    out = rt.r(mi);
                } else {
                    int ni = rt.push(newArrayMap(rt, n));
                    for (int j = 0; j < n; j++) {
                        amSet(rt, rt.r(ni), AM_BASE + 2 * j, amKey(rt, rt.r(mi), j));
                        amSet(rt, rt.r(ni), AM_BASE + 2 * j + 1, amVal(rt, rt.r(mi), j));
                    }
                    amSet(rt, rt.r(ni), AM_BASE + 2 * i + 1, rt.r(vi));
                    amSet(rt, rt.r(ni), AM_META, rt.slot(rt.r(mi), AM_META));
                    out = rt.r(ni);
                }
            } else if (n < ARRAY_MAP_MAX) {
                int ni = rt.push(newArrayMap(rt, n + 1));
                for (int j = 0; j < n; j++) {
                    amSet(rt, rt.r(ni), AM_BASE + 2 * j, amKey(rt, rt.r(mi), j));
                    amSet(rt, rt.r(ni), AM_BASE + 2 * j + 1, amVal(rt, rt.r(mi), j));
                }
                amSet(rt, rt.r(ni), AM_BASE + 2 * n, rt.r(ki));
                amSet(rt, rt.r(ni), AM_BASE + 2 * n + 1, rt.r(vi));
                amSet(rt, rt.r(ni), AM_META, rt.slot(rt.r(mi), AM_META));
                out = rt.r(ni);
            } else {
                int pi = rt.push(promote(rt, rt.r(mi)));
                out = assoc(rt, rt.r(pi), rt.r(ki), rt.r(vi));
            }
        } else if (t == TY_HASHMAP) {
            int cnt = count(rt, m);
            int h = Eq.hashValue(rt, rt.r(ki));
            int ri = rt.push(rt.slot(rt.r(mi), HM_ROOT));
            rt.champAdded = false;
            long nr = nodeAssoc(rt, rt.r(ri), 0, h, rt.r(ki), rt.r(vi), Val.NIL);
            if (nr == rt.r(ri)) {
                out = rt.r(mi);
            } else {
                int nri = rt.push(nr);
                boolean added = rt.champAdded;
                long meta = rt.slot(rt.r(mi), HM_META);
                out = newHashMap(rt, cnt + (added ? 1 : 0), rt.r(nri), meta);
            }
        } else {
            out = Val.NIL;
        }
        rt.popTo(base);
        return out;
    }

    public static long dissoc(Rt rt, long m, long k) {
        int base = rt.mark();
        int mi = rt.push(m), ki = rt.push(k);
        long out;
        int t = ty(rt.gc.sp, Val.asHeap(m));
        if (t == TY_ARRAYMAP) {
            int i = amIndexOf(rt, rt.r(mi), rt.r(ki));
            if (i < 0) {
                out = rt.r(mi);
            } else {
                int n = count(rt, rt.r(mi));
                int ni = rt.push(newArrayMap(rt, n - 1));
                int d = 0;
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    amSet(rt, rt.r(ni), AM_BASE + 2 * d, amKey(rt, rt.r(mi), j));
                    amSet(rt, rt.r(ni), AM_BASE + 2 * d + 1, amVal(rt, rt.r(mi), j));
                    d++;
                }
                amSet(rt, rt.r(ni), AM_META, rt.slot(rt.r(mi), AM_META));
                out = rt.r(ni);
            }
        } else if (t == TY_HASHMAP) {
            int h = Eq.hashValue(rt, rt.r(ki));
            int ri = rt.push(rt.slot(rt.r(mi), HM_ROOT));
            rt.champAdded = false;
            long nr = nodeDissoc(rt, rt.r(ri), 0, h, rt.r(ki), Val.NIL);
            if (!rt.champAdded || nr == rt.r(ri)) {
                out = rt.r(mi);
            } else {
                int cnt = count(rt, rt.r(mi)) - 1;
                int nri = rt.push(nr);
                long meta = rt.slot(rt.r(mi), HM_META);
                out = cnt == 0 ? empty(rt) : newHashMap(rt, cnt, rt.r(nri), meta);
            }
        } else {
            out = Val.NIL;
        }
        rt.popTo(base);
        return out;
    }

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
            int n = count(rt, m);
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
        rt.setSlot(a, TM_CNT, Val.fixnum(count(rt, h)));
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

    /// `[k, v]`, the object a map's `seq` yields. A `TY_MAPENTRY` and not a
    /// vector, so `key`/`val` are O(1) and the entry can still be read as a
    /// two-element sequential -- which is what makes `(into {} (map ...))` and
    /// destructuring `[[k v] ...]` both work over the same object.
    public static long entry(Rt rt, long k, long v) {
        int base = rt.mark();
        int ki = rt.push(k), vi = rt.push(v);
        long a = rt.alloc(TY_MAPENTRY, 2);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, 0, rt.r(ki));
        rt.setSlot(a, 1, rt.r(vi));
        rt.popTo(base);
        return Val.heap(a);
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
        if (!rt.chargeChecked(count(rt, m), "seq of a map")) return Val.NIL;
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
            long e = entry(rt, rt.r(at + 2 * i), rt.r(at + 2 * i + 1));
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
        if (Val.isHeap(a) && ty(rt.gc.sp, Val.asHeap(a)) == Obj.TY_TABLEREF)
            a = Table.refToMap(rt, a);
        if (Val.isHeap(b) && ty(rt.gc.sp, Val.asHeap(b)) == Obj.TY_TABLEREF) {
            int mi = rt.push(a);
            b = Table.refToMap(rt, b);
            a = rt.r(mi);
            rt.popTo(mi);
        }
        if (count(rt, a) != count(rt, b)) return false;
        int base = rt.mark();
        int ai = rt.push(a), bi = rt.push(b);
        int at = rt.mark();
        int n = entries(rt, rt.r(ai), at);
        boolean ok = true;
        for (int i = 0; i < n && ok; i++) {
            long k = rt.r(at + 2 * i), v = rt.r(at + 2 * i + 1);
            long got = get(rt, rt.r(bi), k, Val.NOT_FOUND);
            ok = got != Val.NOT_FOUND && Eq.eq(rt, v, got);
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
            acc = Hash.unorderedStep(acc, Hash.mixCollHash(
                Hash.orderedStep(Hash.orderedStep(1, kh), vh), 2));
        }
        rt.popTo(base);
        return Hash.mixCollHash(acc, n);
    }
}
