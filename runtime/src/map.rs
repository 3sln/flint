//! Maps: a small insertion-ordered array-map, and a **CHAMP** hash-array mapped
//! trie above it.
//!
//! ## Why CHAMP and not Clojure's HAMT
//!
//! The brief asks to take the good work that never landed in canonical Clojure.
//! The clearest example is Steindorfer & Vinju's CHAMP (OOPSLA 2015), which
//! ClojureDart's map work also draws on. A CHAMP node carries **two** bitmaps —
//! `datamap` for entries stored inline and `nodemap` for sub-nodes — with
//! entries packed at the front of the node and sub-nodes packed at the back:
//!
//! ```text
//!   TY_BMNODE  [edit, datamap, nodemap, k0,v0, k1,v1, ..., nodeN..node0]
//!   TY_COLLNODE[edit, hash, k0,v0, ...]                 -- full hash collision
//! ```
//!
//! Three things fall out of that, and all three matter here:
//!
//! * **Nodes are smaller and denser.** Clojure's `BitmapIndexedNode` stores a
//!   `null` key beside a sub-node pointer, wasting a slot per child, and
//!   promotes to a 32-wide `ArrayNode` at 16 children. CHAMP needs neither.
//! * **The representation is canonical.** Clojure's HAMT can represent the same
//!   map two ways depending on insertion and deletion history, because deleting
//!   does not un-inline a node that has shrunk to one entry. CHAMP always
//!   collapses. Equal maps therefore have identical structure, which is what
//!   makes `=` on maps able to short-circuit structurally.
//! * **Iteration does not have to test each slot's type.** Entries are exactly
//!   the first `2*popcount(datamap)` slots.
//!
//! ## Where the map is an array-map
//!
//! Up to `ARRAY_MAP_MAX` entries a map is a flat `[meta, hash, k,v, ...]` array,
//! as in Clojure. For a compiler -- the workload on our own critical path --
//! most maps are AST nodes with a handful of keys, and a linear scan over
//! bit-comparable keywords beats descending a trie. `assoc` past the threshold
//! promotes to CHAMP.

use crate::hash;
use crate::obj::*;
use crate::mem::Addr;
use crate::rt::Rt;
use crate::value::{Value, NIL, NOT_FOUND};

pub const ARRAY_MAP_MAX: u32 = 8;

// array-map layout
pub const AM_META: u32 = 0;
pub const AM_HASH: u32 = 1;
pub const AM_BASE: u32 = 2;

// hash-map layout
pub const HM_CNT: u32 = 0;
pub const HM_ROOT: u32 = 1;
pub const HM_META: u32 = 2;
pub const HM_HASH: u32 = 3;

// CHAMP bitmap node layout
const BN_EDIT: u32 = 0;
const BN_DATAMAP: u32 = 1;
const BN_NODEMAP: u32 = 2;
const BN_BASE: u32 = 3;

// collision node layout
const CN_EDIT: u32 = 0;
const CN_HASH: u32 = 1;
const CN_BASE: u32 = 2;

pub const HASH_BITS: u32 = 5;
pub const HASH_WIDTH: u32 = 32;

#[inline]
fn mask(h: u32, shift: u32) -> u32 {
    (h >> shift) & 0x1f
}
#[inline]
fn bitpos(h: u32, shift: u32) -> u32 {
    1u32 << mask(h, shift)
}
#[inline]
fn index_of(bitmap: u32, bit: u32) -> u32 {
    (bitmap & (bit - 1)).count_ones()
}

impl Rt {
    // --- node primitives ---------------------------------------------------


    // kin:begin kin/champ.kin
    #[inline]
    fn bn_datamap(&self, n: Value) -> u32 {
        return self.slot(n, BN_DATAMAP).as_fixnum() as u32;
    }
    #[inline]
    fn bn_nodemap(&self, n: Value) -> u32 {
        return self.slot(n, BN_NODEMAP).as_fixnum() as u32;
    }
    #[inline]
    fn bn_key(&self, n: Value, i: u32) -> Value {
        return self.slot(n, BN_BASE + (2 * i));
    }
    #[inline]
    fn bn_val(&self, n: Value, i: u32) -> Value {
        return self.slot(n, BN_BASE + ((2 * i) + 1));
    }
    #[inline]
    fn bn_set_key(&mut self, n: Value, i: u32, v: Value) {
        self.set(n, BN_BASE + (2 * i), v);
    }
    #[inline]
    fn bn_set_val(&mut self, n: Value, i: u32, v: Value) {
        self.set(n, BN_BASE + ((2 * i) + 1), v);
    }
    /// Sub-nodes live at the END, in DESCENDING bit order.
    #[inline]
    fn bn_node(&self, n: Value, j: u32) -> Value {
        return self.slot(n, (self.olen(n) - 1) - j);
    }
    #[inline]
    fn bn_set_node(&mut self, n: Value, j: u32, v: Value) {
        self.set(n, (self.olen(n) - 1) - j, v);
    }

    // kin:end kin/champ.kin

    // kin:begin kin/collnode.kin
    fn cn_new(&mut self, h: u32, npairs: u32, edit: Value) -> Value {
        // `edit` is rooted across the allocation and read back after it:
        // `alloc` collects, and a host local does not survive that.
        let e: usize = self.push(edit);
        let a: Addr = self.alloc(TY_COLLNODE, CN_BASE + (2 * npairs));
        let ed: Value = self.r(e);
        self.pop_to(e);
        if a == 0 {
            return NIL;
        }
        self.set_slot(a, CN_EDIT, ed);
        self.set_slot(a, CN_HASH, Value::fixnum(h as i64));
        return Value::heap(a);
    }
    #[inline]
    fn cn_count(&self, n: Value) -> u32 {
        return (self.olen(n) - CN_BASE) / 2;
    }
    fn cn_hash(&self, n: Value) -> u32 {
        return self.slot(n, CN_HASH).as_fixnum() as u32;
    }
    #[inline]
    fn cn_key(&self, n: Value, i: u32) -> Value {
        return self.slot(n, CN_BASE + (2 * i));
    }
    #[inline]
    fn cn_val(&self, n: Value, i: u32) -> Value {
        return self.slot(n, (CN_BASE + (2 * i)) + 1);
    }
    /// A BITMAP NODE, allocated and stamped with its two maps.
    pub fn bn_new(&mut self, datamap: u32, nodemap: u32, edit: Value) -> Value {
        let ne: u32 = datamap.count_ones();
        let nn: u32 = nodemap.count_ones();
        let e: usize = self.push(edit);
        let a: Addr = self.alloc(TY_BMNODE, (BN_BASE + (2 * ne)) + nn);
        let ed: Value = self.r(e);
        self.pop_to(e);
        if a == 0 {
            return NIL;
        }
        self.set_slot(a, BN_EDIT, ed);
        self.set_slot(a, BN_DATAMAP, Value::fixnum(datamap as i64));
        self.set_slot(a, BN_NODEMAP, Value::fixnum(nodemap as i64));
        return Value::heap(a);
    }
    /// A COLLISION NODE copied with one value replaced.
    pub fn cn_copy_set_val(&mut self, n: Value, i: u32, val: Value, edit: Value) -> Value {
        let cnt: u32 = self.cn_count(n);
        let base: usize = self.mark();
        let ni: usize = self.push(n);
        let vi: usize = self.push(val);
        let ei: usize = self.push(edit);
        let out: Value = self.cn_new(self.cn_hash(self.r(ni)), cnt, self.r(ei));
        if out.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let oi: usize = self.push(out);
        for k in 0..cnt {
            self.set(self.r(oi), CN_BASE + (2 * k), self.cn_key(self.r(ni), k));
            self.set(self.r(oi), (CN_BASE + (2 * k)) + 1, self.cn_val(self.r(ni), k));
        }
        self.set(self.r(oi), (CN_BASE + (2 * i)) + 1, self.r(vi));
        let res: Value = self.r(oi);
        self.pop_to(base);
        return res;
    }

    // kin:end kin/collnode.kin

    #[inline]
    // kin:begin kin/nodeclass.kin
    #[inline]
    fn is_bmnode(&self, n: Value) -> bool {
        return ty(&self.gc.sp, n.as_heap()) == TY_BMNODE;
    }
    /// EMPTY / ONE / MORE, the CHAMP size predicate that drives collapsing.
    fn node_size_class(&self, n: Value) -> u32 {
        // A collision node always holds at least two pairs.
        if !self.is_bmnode(n) {
            return 2;
        }
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        if nm != 0 {
            return 2;
        }
        let c: u32 = dm.count_ones();
        if c == 0 {
            return 0;
        }
        if c == 1 {
            return 1;
        }
        return 2;
    }

    // kin:end kin/nodeclass.kin

    // --- lookup ------------------------------------------------------------

    /// Lookup for a key whose `=` cannot allocate. No rooting, because nothing
    /// here can move: this is the shape `get` almost always has.
    // kin:begin kin/find.kin
    fn node_find_scalar(&mut self, n: Value, mut shift: u32, h: u32, key: Value) -> Value {
        // No rooting anywhere in here: the key is a scalar, so `eq` cannot
        // allocate, so nothing can move while this walks.
        let mut node: Value;
        node = n;
        let mut out: Value;
        out = NOT_FOUND;
        loop {
            if !self.is_bmnode(node) {
                if self.cn_hash(node) != h {
                    break;
                }
                let cnt: u32 = self.cn_count(node);
                for i in 0..cnt {
                    if self.eq(self.cn_key(node, i), key) {
                        out = self.cn_val(node, i);
                        break;
                    }
                }
                break;
            }
            let bit: u32 = bitpos(h, shift);
            let dm: u32 = self.bn_datamap(node);
            if (dm & bit) != 0 {
                let i: u32 = index_of(dm, bit);
                if self.eq(self.bn_key(node, i), key) {
                    out = self.bn_val(node, i);
                }
                break;
            }
            let nm: u32 = self.bn_nodemap(node);
            if (nm & bit) == 0 {
                break;
            }
            node = self.bn_node(node, index_of(nm, bit));
            shift += HASH_BITS;
        }
        return out;
    }
    fn node_find(&mut self, n: Value, mut shift: u32, h: u32, key: Value) -> Value {
        if !self.eq_may_alloc(key) {
            return self.node_find_scalar(n, shift, h, key);
        }
        // The node being walked and the key are rooted: `eq` on a compound
        // key allocates (it seqs both sides), so a collection can happen in
        // the middle of a lookup and move everything this walk is holding.
        let base: usize = self.mark();
        let ni: usize = self.push(n);
        let ki: usize = self.push(key);
        let mut out: Value;
        out = NOT_FOUND;
        loop {
            if !self.is_bmnode(self.r(ni)) {
                if self.cn_hash(self.r(ni)) != h {
                    break;
                }
                let cnt: u32 = self.cn_count(self.r(ni));
                for i in 0..cnt {
                    let kk: usize = self.push(self.cn_key(self.r(ni), i));
                    let same: bool = self.eq(self.r(kk), self.r(ki));
                    self.pop_to(kk);
                    if same {
                        out = self.cn_val(self.r(ni), i);
                        break;
                    }
                }
                break;
            }
            let bit: u32 = bitpos(h, shift);
            let dm: u32 = self.bn_datamap(self.r(ni));
            if (dm & bit) != 0 {
                let i: u32 = index_of(dm, bit);
                let kk: usize = self.push(self.bn_key(self.r(ni), i));
                let same: bool = self.eq(self.r(kk), self.r(ki));
                self.pop_to(kk);
                if same {
                    out = self.bn_val(self.r(ni), i);
                }
                break;
            }
            let nm: u32 = self.bn_nodemap(self.r(ni));
            if (nm & bit) == 0 {
                break;
            }
            self.set_r(ni, self.bn_node(self.r(ni), index_of(nm, bit)));
            shift += HASH_BITS;
        }
        self.pop_to(base);
        return out;
    }

    // kin:end kin/find.kin

    // --- construction of a two-entry subtree --------------------------------

    // kin:begin kin/merge.kin
    fn merge_two(&mut self, shift: u32, k0: Value, v0: Value, h0: u32, k1: Value, v1: Value, h1: u32, edit: Value) -> Value {
        let mk: usize = self.mark();
        let ik0: usize = self.push(k0);
        let iv0: usize = self.push(v0);
        let ik1: usize = self.push(k1);
        let iv1: usize = self.push(v1);
        let ie: usize = self.push(edit);
        // Declared, not initialised: every branch below assigns it exactly
        // once, which Rust treats as the initialisation rather than as a
        // mutation -- so no `mut`, and no dead store to warn about.
        let res: Value;
        if shift >= 32 {
            // Two keys with the SAME 32-bit hash: a collision node, which
            // is the only place equal hashes are stored side by side.
            res = self.cn_new(h0, 2, self.r(ie));
            if !res.is_nil() {
                self.set(res, CN_BASE, self.r(ik0));
                self.set(res, CN_BASE + 1, self.r(iv0));
                self.set(res, CN_BASE + 2, self.r(ik1));
                self.set(res, CN_BASE + 3, self.r(iv1));
            }
        } else {
            let m0: u32 = mask(h0, shift);
            let m1: u32 = mask(h1, shift);
            if m0 != m1 {
                let dm: u32 = (1 << m0) | (1 << m1);
                res = self.bn_new(dm, 0, self.r(ie));
                if !res.is_nil() {
                    // In BIT ORDER, not argument order: `index-of` derives
                    // a slot from the bitmap, so the pair with the lower
                    // mask must land first or every later lookup is off
                    // by one.
                    if m0 < m1 {
                        self.bn_set_key(res, 0, self.r(ik0));
                        self.bn_set_val(res, 0, self.r(iv0));
                        self.bn_set_key(res, 1, self.r(ik1));
                        self.bn_set_val(res, 1, self.r(iv1));
                    } else {
                        self.bn_set_key(res, 0, self.r(ik1));
                        self.bn_set_val(res, 0, self.r(iv1));
                        self.bn_set_key(res, 1, self.r(ik0));
                        self.bn_set_val(res, 1, self.r(iv0));
                    }
                }
            } else {
                let sub: Value = self.merge_two(shift + HASH_BITS, self.r(ik0), self.r(iv0), h0, self.r(ik1), self.r(iv1), h1, self.r(ie));
                let si: usize = self.push(sub);
                res = self.bn_new(0, 1 << m0, self.r(ie));
                if !res.is_nil() {
                    self.bn_set_node(res, 0, self.r(si));
                }
            }
        }
        self.pop_to(mk);
        return res;
    }

    // kin:end kin/merge.kin

    // --- structural copies --------------------------------------------------

    // kin:begin kin/copies.kin
    fn bn_copy_insert_entry(&mut self, n: Value, bit: u32, key: Value, val: Value, edit: Value) -> Value {
        let mk: usize = self.mark();
        let ni: usize = self.push(n);
        let ki: usize = self.push(key);
        let vi: usize = self.push(val);
        let ei: usize = self.push(edit);
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        let ne: u32 = dm.count_ones();
        let nn: u32 = nm.count_ones();
        let at: u32 = index_of(dm, bit);
        let res: Value = self.bn_new(dm | bit, nm, self.r(ei));
        if res.is_nil() {
            self.pop_to(mk);
            return NIL;
        }
        let oi: usize = self.push(res);
        // Everything at or after the insertion point shifts up one.
        // `index-of` derives a slot from the bitmap, so a pair landing
        // in the wrong order is a lookup that misses.
        for k in 0..ne {
            let d: u32;
            if k < at {
                d = k;
            } else {
                d = k + 1;
            }
            let ek: Value = self.bn_key(self.r(ni), k);
            self.bn_set_key(self.r(oi), d, ek);
            let ev: Value = self.bn_val(self.r(ni), k);
            self.bn_set_val(self.r(oi), d, ev);
        }
        let nk: Value = self.r(ki);
        self.bn_set_key(self.r(oi), at, nk);
        let nv: Value = self.r(vi);
        self.bn_set_val(self.r(oi), at, nv);
        for j in 0..nn {
            let sub: Value = self.bn_node(self.r(ni), j);
            self.bn_set_node(self.r(oi), j, sub);
        }
        let built: Value = self.r(oi);
        self.pop_to(mk);
        return built;
    }
    fn bn_copy_remove_entry(&mut self, n: Value, bit: u32, edit: Value) -> Value {
        let mk: usize = self.mark();
        let ni: usize = self.push(n);
        let ei: usize = self.push(edit);
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        let ne: u32 = dm.count_ones();
        let nn: u32 = nm.count_ones();
        let at: u32 = index_of(dm, bit);
        let res: Value = self.bn_new(dm ^ bit, nm, self.r(ei));
        if res.is_nil() {
            self.pop_to(mk);
            return NIL;
        }
        let oi: usize = self.push(res);
        // Everything after the hole shifts DOWN one, and the removed
        // slot is skipped rather than copied.
        for k in 0..ne {
            if k == at {
                continue;
            }
            let d: u32;
            if k < at {
                d = k;
            } else {
                d = k - 1;
            }
            let ek: Value = self.bn_key(self.r(ni), k);
            self.bn_set_key(self.r(oi), d, ek);
            let ev: Value = self.bn_val(self.r(ni), k);
            self.bn_set_val(self.r(oi), d, ev);
        }
        for j in 0..nn {
            let sub: Value = self.bn_node(self.r(ni), j);
            self.bn_set_node(self.r(oi), j, sub);
        }
        let built: Value = self.r(oi);
        self.pop_to(mk);
        return built;
    }
    fn bn_copy_set_value(&mut self, n: Value, at: u32, val: Value, edit: Value) -> Value {
        let mk: usize = self.mark();
        let ni: usize = self.push(n);
        let vi: usize = self.push(val);
        let ei: usize = self.push(edit);
        // Owned by this transient? Then write through rather than copy.
        if !self.r(ei).is_nil() && (self.slot(self.r(ni), BN_EDIT) == self.r(ei)) {
            let own: Value = self.r(ni);
            let nv: Value = self.r(vi);
            self.bn_set_val(own, at, nv);
            self.pop_to(mk);
            return own;
        }
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        let res: Value = self.bn_new(dm, nm, self.r(ei));
        if res.is_nil() {
            self.pop_to(mk);
            return NIL;
        }
        let oi: usize = self.push(res);
        let ne: u32 = dm.count_ones();
        let nn: u32 = nm.count_ones();
        for k in 0..ne {
            let ek: Value = self.bn_key(self.r(ni), k);
            self.bn_set_key(self.r(oi), k, ek);
            let ev: Value = self.bn_val(self.r(ni), k);
            self.bn_set_val(self.r(oi), k, ev);
        }
        for j in 0..nn {
            let sub: Value = self.bn_node(self.r(ni), j);
            self.bn_set_node(self.r(oi), j, sub);
        }
        let fresh: Value = self.r(vi);
        self.bn_set_val(self.r(oi), at, fresh);
        let built: Value = self.r(oi);
        self.pop_to(mk);
        return built;
    }
    fn bn_copy_set_node(&mut self, n: Value, at: u32, sub: Value, edit: Value) -> Value {
        let mk: usize = self.mark();
        let ni: usize = self.push(n);
        let si: usize = self.push(sub);
        let ei: usize = self.push(edit);
        if !self.r(ei).is_nil() && (self.slot(self.r(ni), BN_EDIT) == self.r(ei)) {
            let own: Value = self.r(ni);
            let os: Value = self.r(si);
            self.bn_set_node(own, at, os);
            self.pop_to(mk);
            return own;
        }
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        let res: Value = self.bn_new(dm, nm, self.r(ei));
        if res.is_nil() {
            self.pop_to(mk);
            return NIL;
        }
        let oi: usize = self.push(res);
        let ne: u32 = dm.count_ones();
        let nn: u32 = nm.count_ones();
        for k in 0..ne {
            let ek: Value = self.bn_key(self.r(ni), k);
            self.bn_set_key(self.r(oi), k, ek);
            let ev: Value = self.bn_val(self.r(ni), k);
            self.bn_set_val(self.r(oi), k, ev);
        }
        for j in 0..nn {
            let js: Value = self.bn_node(self.r(ni), j);
            self.bn_set_node(self.r(oi), j, js);
        }
        let fresh: Value = self.r(si);
        self.bn_set_node(self.r(oi), at, fresh);
        let built: Value = self.r(oi);
        self.pop_to(mk);
        return built;
    }
    /// An inline pair becomes a SUB-NODE: it leaves the entry half and joins
    /// the node half, so both bitmaps change and both halves reindex. The two
    /// positions are computed from the OLD bitmaps -- `at-node` is the index in
    /// the new nodemap as well, because the bit being added is the one being
    /// counted up to.
    fn bn_inline_to_node(&mut self, n: Value, bit: u32, sub: Value, edit: Value) -> Value {
        let mk: usize = self.mark();
        let ni: usize = self.push(n);
        let si: usize = self.push(sub);
        let ei: usize = self.push(edit);
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        let ne: u32 = dm.count_ones();
        let nn: u32 = nm.count_ones();
        let at_entry: u32 = index_of(dm, bit);
        let at_node: u32 = index_of(nm, bit);
        let res: Value = self.bn_new(dm ^ bit, nm | bit, self.r(ei));
        if res.is_nil() {
            self.pop_to(mk);
            return NIL;
        }
        let oi: usize = self.push(res);
        for k in 0..ne {
            if k == at_entry {
                continue;
            }
            let d: u32;
            if k < at_entry {
                d = k;
            } else {
                d = k - 1;
            }
            let ek: Value = self.bn_key(self.r(ni), k);
            self.bn_set_key(self.r(oi), d, ek);
            let ev: Value = self.bn_val(self.r(ni), k);
            self.bn_set_val(self.r(oi), d, ev);
        }
        for j in 0..nn {
            let e: u32;
            if j < at_node {
                e = j;
            } else {
                e = j + 1;
            }
            let js: Value = self.bn_node(self.r(ni), j);
            self.bn_set_node(self.r(oi), e, js);
        }
        let fresh: Value = self.r(si);
        self.bn_set_node(self.r(oi), at_node, fresh);
        let built: Value = self.r(oi);
        self.pop_to(mk);
        return built;
    }
    /// And the reverse: a sub-node collapses back to an inline pair.
    fn bn_node_to_inline(&mut self, n: Value, bit: u32, key: Value, val: Value, edit: Value) -> Value {
        let mk: usize = self.mark();
        let ni: usize = self.push(n);
        let ki: usize = self.push(key);
        let vi: usize = self.push(val);
        let ei: usize = self.push(edit);
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        let ne: u32 = dm.count_ones();
        let nn: u32 = nm.count_ones();
        let at_entry: u32 = index_of(dm, bit);
        let at_node: u32 = index_of(nm, bit);
        let res: Value = self.bn_new(dm | bit, nm ^ bit, self.r(ei));
        if res.is_nil() {
            self.pop_to(mk);
            return NIL;
        }
        let oi: usize = self.push(res);
        for k in 0..ne {
            let d: u32;
            if k < at_entry {
                d = k;
            } else {
                d = k + 1;
            }
            let ek: Value = self.bn_key(self.r(ni), k);
            self.bn_set_key(self.r(oi), d, ek);
            let ev: Value = self.bn_val(self.r(ni), k);
            self.bn_set_val(self.r(oi), d, ev);
        }
        let nk: Value = self.r(ki);
        self.bn_set_key(self.r(oi), at_entry, nk);
        let nv: Value = self.r(vi);
        self.bn_set_val(self.r(oi), at_entry, nv);
        for j in 0..nn {
            if j == at_node {
                continue;
            }
            let e: u32;
            if j < at_node {
                e = j;
            } else {
                e = j - 1;
            }
            let js: Value = self.bn_node(self.r(ni), j);
            self.bn_set_node(self.r(oi), e, js);
        }
        let built: Value = self.r(oi);
        self.pop_to(mk);
        return built;
    }

    // kin:end kin/copies.kin

    // kin:begin kin/nodeassoc.kin
    fn node_assoc(&mut self, n: Value, shift: u32, h: u32, key: Value, val: Value, edit: Value) -> Value {
        let base: usize = self.mark();
        let ni: usize = self.push(n);
        let ki: usize = self.push(key);
        let vi: usize = self.push(val);
        let ei: usize = self.push(edit);
        // A collision node has no bitmaps to consult, so it is not this
        // function's shape at all -- hand it straight over.
        // The result here is named `handed` and not `out`: C# forbids a
        // local in an inner scope that reuses an enclosing scope's name,
        // and `out` is declared at method scope just below.
        if !self.is_bmnode(n) {
            let handed: Value = self.coll_assoc(self.r(ni), h, self.r(ki), self.r(vi), self.r(ei), shift);
            self.pop_to(base);
            return handed;
        }
        let bit: u32 = bitpos(h, shift);
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        let out: Value;
        if (dm & bit) != 0 {
            let at: u32 = index_of(dm, bit);
            let k0i: usize = self.push(self.bn_key(self.r(ni), at));
            if self.eq(self.r(k0i), self.r(ki)) {
                // The key was already here, so the map's count does not
                // move however the value changes.
                self.champ_added = false;
                let v0: Value = self.bn_val(self.r(ni), at);
                if v0 == self.r(vi) {
                    out = self.r(ni);
                } else {
                    out = self.bn_copy_set_value(self.r(ni), at, self.r(vi), self.r(ei));
                }
            } else {
                // A different key in the same slot: the two are pushed
                // down into a sub-node, and the entry stops being an
                // entry at this level.
                self.champ_added = true;
                let v0i: usize = self.push(self.bn_val(self.r(ni), at));
                let h0: u32 = self.hash_value(self.r(k0i));
                let sub: Value = self.merge_two(shift + HASH_BITS, self.r(k0i), self.r(v0i), h0, self.r(ki), self.r(vi), h, self.r(ei));
                let si: usize = self.push(sub);
                out = self.bn_inline_to_node(self.r(ni), bit, self.r(si), self.r(ei));
            }
        } else if (nm & bit) != 0 {
            let at: u32 = index_of(nm, bit);
            // Rooted, because the comparison below is what decides
            // whether this node changed. `node_assoc` allocates, a
            // collection can move `sub`, and a stale address that
            // happened to match the new one would drop the whole
            // subtree's update on the floor -- a key silently missing
            // from a map whose count says it is there.
            let subi: usize = self.push(self.bn_node(self.r(ni), at));
            let newsub: Value = self.node_assoc(self.r(subi), shift + HASH_BITS, h, self.r(ki), self.r(vi), self.r(ei));
            if newsub == self.r(subi) {
                out = self.r(ni);
            } else {
                out = self.bn_copy_set_node(self.r(ni), at, newsub, self.r(ei));
            }
        } else {
            // An empty slot, which is the only branch that always
            // grows the map.
            self.champ_added = true;
            out = self.bn_copy_insert_entry(self.r(ni), bit, self.r(ki), self.r(vi), self.r(ei));
        }
        self.pop_to(base);
        return out;
    }

    // kin:end kin/nodeassoc.kin

    // kin:begin kin/collassoc.kin
    fn coll_assoc(&mut self, n: Value, h: u32, key: Value, val: Value, edit: Value, shift: u32) -> Value {
        let nh: u32 = self.cn_hash(n);
        if nh != h {
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
            let wbase: usize = self.mark();
            let wni: usize = self.push(n);
            let wki: usize = self.push(key);
            let wvi: usize = self.push(val);
            let wei: usize = self.push(edit);
            let wrapper: Value = self.bn_new(0, bitpos(nh, shift), self.r(wei));
            let wi: usize = self.push(wrapper);
            self.bn_set_node(self.r(wi), 0, self.r(wni));
            let wrapped: Value = self.node_assoc(self.r(wi), shift, h, self.r(wki), self.r(wvi), self.r(wei));
            self.pop_to(wbase);
            return wrapped;
        }
        // THE SCAN. `hit` is left at `cnt` when nothing matched -- one past
        // the last valid index, so it cannot collide with an answer.
        let scan: usize = self.mark();
        let sni: usize = self.push(n);
        let ski: usize = self.push(key);
        let cnt: u32 = self.cn_count(self.r(sni));
        let mut hit: u32;
        hit = cnt;
        for i in 0..cnt {
            // The key is rooted across `eq`, which allocates when either
            // side is a row ref.
            let kk: usize = self.push(self.cn_key(self.r(sni), i));
            let same: bool = self.eq(self.r(kk), self.r(ski));
            self.pop_to(kk);
            if same {
                hit = i;
                break;
            }
        }
        let nn: Value = self.r(sni);
        let kk2: Value = self.r(ski);
        self.pop_to(scan);
        if hit != cnt {
            // The key was already here, so the map's count does not move.
            self.champ_added = false;
            let rbase: usize = self.mark();
            let rni: usize = self.push(nn);
            let rvi: usize = self.push(val);
            let rei: usize = self.push(edit);
            let replaced: Value = self.cn_copy_set_val(self.r(rni), hit, self.r(rvi), self.r(rei));
            self.pop_to(rbase);
            return replaced;
        }
        self.champ_added = true;
        let gbase: usize = self.mark();
        let gni: usize = self.push(nn);
        let gki: usize = self.push(kk2);
        let gvi: usize = self.push(val);
        let gei: usize = self.push(edit);
        let fresh: Value = self.cn_new(h, cnt + 1, self.r(gei));
        if fresh.is_nil() {
            self.pop_to(gbase);
            return NIL;
        }
        let oi: usize = self.push(fresh);
        for i in 0..cnt {
            let ek: Value = self.cn_key(self.r(gni), i);
            let ev: Value = self.cn_val(self.r(gni), i);
            self.set(self.r(oi), CN_BASE + (2 * i), ek);
            self.set(self.r(oi), (CN_BASE + (2 * i)) + 1, ev);
        }
        self.set(self.r(oi), CN_BASE + (2 * cnt), self.r(gki));
        self.set(self.r(oi), (CN_BASE + (2 * cnt)) + 1, self.r(gvi));
        let grown: Value = self.r(oi);
        self.pop_to(gbase);
        return grown;
    }

    // kin:end kin/collassoc.kin


    // kin:begin kin/dissoc.kin
    fn node_dissoc(&mut self, n: Value, shift: u32, h: u32, key: Value, edit: Value) -> Value {
        let base: usize = self.mark();
        let ni: usize = self.push(n);
        let ki: usize = self.push(key);
        let ei: usize = self.push(edit);
        // A collision node has no bitmaps, so it is not this function's
        // shape at all -- hand it over.
        if !self.is_bmnode(n) {
            let handed: Value = self.coll_dissoc(self.r(ni), self.r(ki), self.r(ei));
            self.pop_to(base);
            return handed;
        }
        let bit: u32 = bitpos(h, shift);
        let dm: u32 = self.bn_datamap(n);
        let nm: u32 = self.bn_nodemap(n);
        let out: Value;
        if (dm & bit) != 0 {
            let at: u32 = index_of(dm, bit);
            let k0i: usize = self.push(self.bn_key(self.r(ni), at));
            let same0: bool = self.eq(self.r(k0i), self.r(ki));
            self.pop_to(k0i);
            if !same0 {
                self.champ_added = false;
                out = self.r(ni);
            } else {
                // `champ_added` reads as CHANGED on this path.
                self.champ_added = true;
                if (dm.count_ones() == 2) && (nm == 0) {
                    // One entry would be left, which a CHAMP never
                    // stores as a node: collapse to a single-entry node
                    // so the parent can fold it back inline.
                    let other: u32 = 1 - at;
                    let oki: usize = self.push(self.bn_key(self.r(ni), other));
                    let ovi: usize = self.push(self.bn_val(self.r(ni), other));
                    let oh: u32 = self.hash_value(self.r(oki));
                    let newdm: u32;
                    if shift == 0 {
                        newdm = dm ^ bit;
                    } else {
                        newdm = bitpos(oh, 0);
                    }
                    let nn: Value = self.bn_new(newdm, 0, self.r(ei));
                    if !nn.is_nil() {
                        self.bn_set_key(nn, 0, self.r(oki));
                        self.bn_set_val(nn, 0, self.r(ovi));
                    }
                    out = nn;
                } else {
                    out = self.bn_copy_remove_entry(self.r(ni), bit, self.r(ei));
                }
            }
        } else if (nm & bit) != 0 {
            let at: u32 = index_of(nm, bit);
            let subi: usize = self.push(self.bn_node(self.r(ni), at));
            let newsub: Value = self.node_dissoc(self.r(subi), shift + HASH_BITS, h, self.r(ki), self.r(ei));
            if newsub == self.r(subi) {
                out = self.r(ni);
            } else if self.node_size_class(newsub) == 1 {
                let si: usize = self.push(newsub);
                if (dm == 0) && (nm.count_ones() == 1) {
                    // Nothing else lives here, so this node IS the
                    // child now.
                    out = self.r(si);
                } else {
                    let ki2: usize = self.push(self.bn_key(self.r(si), 0));
                    let vi2: usize = self.push(self.bn_val(self.r(si), 0));
                    out = self.bn_node_to_inline(self.r(ni), bit, self.r(ki2), self.r(vi2), self.r(ei));
                }
            } else {
                out = self.bn_copy_set_node(self.r(ni), at, newsub, self.r(ei));
            }
        } else {
            self.champ_added = false;
            out = self.r(ni);
        }
        self.pop_to(base);
        return out;
    }
    fn coll_dissoc(&mut self, n: Value, key: Value, edit: Value) -> Value {
        let scan: usize = self.mark();
        let sni: usize = self.push(n);
        let ski: usize = self.push(key);
        let cnt: u32 = self.cn_count(self.r(sni));
        let mut found: u32;
        found = cnt;
        for i in 0..cnt {
            // The key is rooted across `eq`, which allocates when either
            // side is a row ref.
            let kk: usize = self.push(self.cn_key(self.r(sni), i));
            let same: bool = self.eq(self.r(kk), self.r(ski));
            self.pop_to(kk);
            if same {
                found = i;
                break;
            }
        }
        let nn: Value = self.r(sni);
        self.pop_to(scan);
        if found == cnt {
            self.champ_added = false;
            return nn;
        }
        self.champ_added = true;
        let dbase: usize = self.mark();
        let dni: usize = self.push(nn);
        let dei: usize = self.push(edit);
        let res: Value;
        if cnt == 2 {
            // Down to one pair: become a single-entry bitmap node so
            // the parent can fold it back inline.
            let other: u32 = 1 - found;
            let cki: usize = self.push(self.cn_key(self.r(dni), other));
            let cvi: usize = self.push(self.cn_val(self.r(dni), other));
            let kh: u32 = self.hash_value(self.r(cki));
            let made: Value = self.bn_new(bitpos(kh, 0), 0, self.r(dei));
            if !made.is_nil() {
                self.bn_set_key(made, 0, self.r(cki));
                self.bn_set_val(made, 0, self.r(cvi));
            }
            res = made;
        } else {
            let o: Value = self.cn_new(self.cn_hash(self.r(dni)), cnt - 1, self.r(dei));
            if o.is_nil() {
                self.pop_to(dbase);
                return NIL;
            }
            let oi: usize = self.push(o);
            let mut d: u32;
            d = 0;
            for i in 0..cnt {
                if i == found {
                    continue;
                }
                let ek: Value = self.cn_key(self.r(dni), i);
                let ev: Value = self.cn_val(self.r(dni), i);
                self.set(self.r(oi), CN_BASE + (2 * d), ek);
                self.set(self.r(oi), (CN_BASE + (2 * d)) + 1, ev);
                d += 1;
            }
            res = self.r(oi);
        }
        self.pop_to(dbase);
        return res;
    }

    // kin:end kin/dissoc.kin

    // --- the map objects ----------------------------------------------------

    pub(crate) fn init_map(&mut self) {
        let a = self.alloc(TY_ARRAYMAP, AM_BASE);
        self.set_slot(a, AM_META, NIL);
        self.set_slot(a, AM_HASH, NIL);
        self.roots.shared.singletons[crate::rt::SING_EMPTY_MAP] = Value::heap(a);
    }

    /// A ROW REF counts, and that is deliberate (`doc/decisions/0026`): a table
    /// is not a vector of maps, but a row IS a map -- seen cheaply. Code that
    /// does not know it has a table keeps working only if the row half is
    /// honest about being one.
    pub fn is_map(&self, v: Value) -> bool {
        v.is_heap()
            && matches!(
                ty(&self.gc.sp, v.as_heap()),
                TY_ARRAYMAP | TY_HASHMAP | crate::obj::TY_TABLEREF
            )
    }
    pub fn is_array_map(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_ARRAYMAP
    }

    pub fn map_count(&self, m: Value) -> u32 {
        match ty(&self.gc.sp, m.as_heap()) {
            TY_ARRAYMAP => (self.olen(m) - AM_BASE) / 2,
            TY_HASHMAP => self.slot(m, HM_CNT).as_fixnum() as u32,
            _ => 0,
        }
    }

    fn new_array_map(&mut self, n: u32) -> Value {
        let a = self.alloc(TY_ARRAYMAP, AM_BASE + 2 * n);
        if a == 0 {
            return NIL;
        }
        self.set_slot(a, AM_META, NIL);
        self.set_slot(a, AM_HASH, NIL);
        Value::heap(a)
    }

    fn new_hash_map(&mut self, cnt: u32, root: Value, meta: Value) -> Value {
        let base = self.mark();
        let ri = self.push(root);
        let mi = self.push(meta);
        let a = self.alloc(TY_HASHMAP, 4);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (root, meta) = (self.r(ri), self.r(mi));
        self.pop_to(base);
        self.set_slot(a, HM_CNT, Value::fixnum(cnt as i64));
        self.set_slot(a, HM_ROOT, root);
        self.set_slot(a, HM_META, meta);
        self.set_slot(a, HM_HASH, NIL);
        Value::heap(a)
    }

    #[inline]
    fn am_key(&self, m: Value, i: u32) -> Value {
        self.slot(m, AM_BASE + 2 * i)
    }
    #[inline]
    fn am_val(&self, m: Value, i: u32) -> Value {
        self.slot(m, AM_BASE + 2 * i + 1)
    }

    fn am_index_of(&mut self, m: Value, k: Value) -> Option<u32> {
        if !self.eq_may_alloc(k) {
            let n = self.map_count(m);
            for i in 0..n {
                let kk = self.am_key(m, i);
                if self.eq(kk, k) {
                    return Some(i);
                }
            }
            return None;
        }
        let base = self.mark();
        let mi = self.push(m);
        let ki = self.push(k);
        let n = self.map_count(self.r(mi));
        let mut out = None;
        for i in 0..n {
            let kk = self.am_key(self.r(mi), i);
            let kki = self.push(kk);
            let same = self.eq(self.r(kki), self.r(ki));
            self.pop_to(kki);
            if same {
                out = Some(i);
                break;
            }
        }
        self.pop_to(base);
        out
    }

    pub fn map_get(&mut self, m: Value, k: Value, not_found: Value) -> Value {
        if !m.is_heap() {
            return not_found;
        }
        // A ROW REF answers here rather than at every call site. `is_map` says
        // true for one, so every path that asks "is this a map?" and then calls
        // this would otherwise read a table row as an array-map and find
        // nothing -- which is what `(:name row)` did.
        if ty(&self.gc.sp, m.as_heap()) == crate::obj::TY_TABLEREF {
            return self.ref_get(m, k, not_found);
        }
        if !self.eq_may_alloc(k) {
            return match ty(&self.gc.sp, m.as_heap()) {
                TY_ARRAYMAP => match self.am_index_of(m, k) {
                    Some(i) => self.am_val(m, i),
                    None => not_found,
                },
                TY_HASHMAP => {
                    let root = self.slot(m, HM_ROOT);
                    if root.is_nil() {
                        return not_found;
                    }
                    let h = self.hash_value(k);
                    let r = self.node_find_scalar(root, 0, h, k);
                    if r == NOT_FOUND {
                        not_found
                    } else {
                        r
                    }
                }
                _ => not_found,
            };
        }
        let base = self.mark();
        let mi = self.push(m);
        let ki = self.push(k);
        let nfi = self.push(not_found);
        let out = match ty(&self.gc.sp, m.as_heap()) {
            TY_ARRAYMAP => match self.am_index_of(self.r(mi), self.r(ki)) {
                Some(i) => self.am_val(self.r(mi), i),
                None => self.r(nfi),
            },
            TY_HASHMAP => {
                // Hash first, then read the root: hashing a compound key walks
                // it, which allocates, which can move the map.
                let h = self.hash_value(self.r(ki));
                let root = self.slot(self.r(mi), HM_ROOT);
                if root.is_nil() {
                    self.r(nfi)
                } else {
                    let r = self.node_find(root, 0, h, self.r(ki));
                    if r == NOT_FOUND {
                        self.r(nfi)
                    } else {
                        r
                    }
                }
            }
            _ => self.r(nfi),
        };
        self.pop_to(base);
        out
    }

    pub fn map_contains(&mut self, m: Value, k: Value) -> bool {
        self.map_get(m, k, NOT_FOUND) != NOT_FOUND
    }

    /// Convert an array-map to a hash-map, preserving contents.
    fn promote(&mut self, m: Value) -> Value {
        let base = self.mark();
        let mi = self.push(m);
        let n = self.map_count(m);
        let root = self.bn_new(0, 0, NIL);
        let ri = self.push(root);
        let mut cnt = 0u32;
        for i in 0..n {
            let k = self.am_key(self.r(mi), i);
            let ki = self.push(k);
            let v = self.am_val(self.r(mi), i);
            let vi = self.push(v);
            let h = self.hash_value(self.r(ki));
            let nr = self.node_assoc(self.r(ri), 0, h, self.r(ki), self.r(vi), NIL);
            self.set_r(ri, nr);
            self.pop_to(ki);
            if self.champ_added {
                cnt += 1;
            }
        }
        let root = self.r(ri);
        let out = self.new_hash_map(cnt, root, NIL);
        self.pop_to(base);
        out
    }

    pub fn map_assoc(&mut self, m: Value, k: Value, v: Value) -> Value {
        let base = self.mark();
        let mi = self.push(m);
        let ki = self.push(k);
        let vi = self.push(v);
        let out = match ty(&self.gc.sp, m.as_heap()) {
            TY_ARRAYMAP => {
                let n = self.map_count(m);
                match self.am_index_of(self.r(mi), self.r(ki)) {
                    Some(i) => {
                        let old = self.am_val(self.r(mi), i);
                        if old == self.r(vi) {
                            self.r(mi)
                        } else {
                            let nm = self.new_array_map(n);
                            let ni = self.push(nm);
                            for j in 0..n {
                                let (kk, vv) =
                                    (self.am_key(self.r(mi), j), self.am_val(self.r(mi), j));
                                self.set(self.r(ni), AM_BASE + 2 * j, kk);
                                self.set(self.r(ni), AM_BASE + 2 * j + 1, vv);
                            }
                            let vv = self.r(vi);
                            self.set(self.r(ni), AM_BASE + 2 * i + 1, vv);
                            let meta = self.slot(self.r(mi), AM_META);
                            self.set(self.r(ni), AM_META, meta);
                            self.r(ni)
                        }
                    }
                    None if n < ARRAY_MAP_MAX => {
                        let nm = self.new_array_map(n + 1);
                        let ni = self.push(nm);
                        for j in 0..n {
                            let (kk, vv) = (self.am_key(self.r(mi), j), self.am_val(self.r(mi), j));
                            self.set(self.r(ni), AM_BASE + 2 * j, kk);
                            self.set(self.r(ni), AM_BASE + 2 * j + 1, vv);
                        }
                        let (kk, vv) = (self.r(ki), self.r(vi));
                        self.set(self.r(ni), AM_BASE + 2 * n, kk);
                        self.set(self.r(ni), AM_BASE + 2 * n + 1, vv);
                        let meta = self.slot(self.r(mi), AM_META);
                        self.set(self.r(ni), AM_META, meta);
                        self.r(ni)
                    }
                    None => {
                        let promoted = self.promote(self.r(mi));
                        let pi = self.push(promoted);
                        let (k, v) = (self.r(ki), self.r(vi));
                        self.map_assoc(self.r(pi), k, v)
                    }
                }
            }
            TY_HASHMAP => {
                let cnt = self.map_count(m);
                // Hash first, then read the root: hashing a compound key can
                // allocate, and an address read before that would be stale.
                let h = self.hash_value(self.r(ki));
                let root = self.slot(self.r(mi), HM_ROOT);
                let ri = self.push(root);
                self.champ_added = false;
                let nr = self.node_assoc(self.r(ri), 0, h, self.r(ki), self.r(vi), NIL);
                if nr == self.r(ri) {
                    self.r(mi)
                } else {
                    let nri = self.push(nr);
                    let added = self.champ_added;
                    let meta = self.slot(self.r(mi), HM_META);
                    let nr = self.r(nri);
                    self.new_hash_map(cnt + added as u32, nr, meta)
                }
            }
            _ => NIL,
        };
        self.pop_to(base);
        out
    }

    pub fn map_dissoc(&mut self, m: Value, k: Value) -> Value {
        // A ROW REF answers here, exactly as it does in `map_get`. `is_map`
        // says true for one, and `dissoc`'s builtin guards on `is_map`, so a
        // ref arrived here and fell into the `_` arm below -- which returns
        // NIL. `(dissoc row :b)` was nil on all four runtimes while `assoc`,
        // `get`, `count` and `=` on the same ref all worked.
        //
        // It materialises rather than removing a column: the schema is CLOSED
        // (`doc/decisions/0026`), so there is no such thing as a row ref with
        // one column missing. `ref_assoc` already takes the same way out.
        if ty(&self.gc.sp, m.as_heap()) == crate::obj::TY_TABLEREF {
            let base = self.mark();
            let ki = self.push(k);
            let mm = self.ref_to_map(m);
            let mi = self.push(mm);
            let (mv, kv) = (self.r(mi), self.r(ki));
            let out = self.map_dissoc(mv, kv);
            self.pop_to(base);
            return out;
        }
        let base = self.mark();
        let mi = self.push(m);
        let ki = self.push(k);
        let out = match ty(&self.gc.sp, m.as_heap()) {
            TY_ARRAYMAP => match self.am_index_of(self.r(mi), self.r(ki)) {
                None => self.r(mi),
                Some(i) => {
                    let n = self.map_count(self.r(mi));
                    let nm = self.new_array_map(n - 1);
                    let ni = self.push(nm);
                    let mut d = 0;
                    for j in 0..n {
                        if j == i {
                            continue;
                        }
                        let (kk, vv) = (self.am_key(self.r(mi), j), self.am_val(self.r(mi), j));
                        self.set(self.r(ni), AM_BASE + 2 * d, kk);
                        self.set(self.r(ni), AM_BASE + 2 * d + 1, vv);
                        d += 1;
                    }
                    let meta = self.slot(self.r(mi), AM_META);
                    self.set(self.r(ni), AM_META, meta);
                    self.r(ni)
                }
            },
            TY_HASHMAP => {
                let h = self.hash_value(self.r(ki));
                let root = self.slot(self.r(mi), HM_ROOT);
                let ri = self.push(root);
                self.champ_added = false;
                let nr = self.node_dissoc(self.r(ri), 0, h, self.r(ki), NIL);
                if !self.champ_added || nr == self.r(ri) {
                    self.r(mi)
                } else {
                    let cnt = self.map_count(self.r(mi)) - 1;
                    let nri = self.push(nr);
                    let meta = self.slot(self.r(mi), HM_META);
                    let nr = self.r(nri);
                    if cnt == 0 {
                        self.empty_map()
                    } else {
                        self.new_hash_map(cnt, nr, meta)
                    }
                }
            }
            _ => NIL,
        };
        self.pop_to(base);
        out
    }

    // --- traversal ----------------------------------------------------------

    /// Walk every entry, calling `f(rt, key, val, state)`. Used by `reduce`,
    /// `seq` materialisation, equality and hashing: none of them build a seq.
    pub fn map_for_each<S>(&mut self, m: Value, state: &mut S, f: &mut dyn FnMut(&mut Rt, Value, Value, &mut S)) {
        if !m.is_heap() {
            return;
        }
        match ty(&self.gc.sp, m.as_heap()) {
            TY_ARRAYMAP => {
                let base = self.mark();
                let mi = self.push(m);
                let n = self.map_count(m);
                self.charge_work(n as u64);
                for i in 0..n {
                    let (k, v) = (self.am_key(self.r(mi), i), self.am_val(self.r(mi), i));
                    f(self, k, v, state);
                }
                self.pop_to(base);
            }
            TY_HASHMAP => {
                let root = self.slot(m, HM_ROOT);
                self.node_for_each(root, state, f);
            }
            _ => {}
        }
    }

    fn node_for_each<S>(&mut self, n: Value, state: &mut S, f: &mut dyn FnMut(&mut Rt, Value, Value, &mut S)) {
        if n.is_nil() {
            return;
        }
        let base = self.mark();
        let ni = self.push(n);
        if self.is_bmnode(n) {
            let ne = self.bn_datamap(n).count_ones();
            let nn = self.bn_nodemap(n).count_ones();
            for i in 0..ne {
                // Every bulk walk over a map or set comes through here --
                // equality, hashing, `seq`, `reduce` -- so charging once is
                // enough to make all of them proportional.
                self.charge_work(1);
                let (k, v) = (self.bn_key(self.r(ni), i), self.bn_val(self.r(ni), i));
                f(self, k, v, state);
            }
            for j in 0..nn {
                let sub = self.bn_node(self.r(ni), j);
                self.node_for_each(sub, state, f);
            }
        } else {
            let cnt = self.cn_count(n);
            for i in 0..cnt {
                self.charge_work(1);
                let (k, v) = (self.cn_key(self.r(ni), i), self.cn_val(self.r(ni), i));
                f(self, k, v, state);
            }
        }
        self.pop_to(base);
    }

    pub fn map_entry(&mut self, k: Value, v: Value) -> Value {
        let base = self.mark();
        let ki = self.push(k);
        let vi = self.push(v);
        let a = self.alloc(TY_MAPENTRY, 2);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (k, v) = (self.r(ki), self.r(vi));
        self.pop_to(base);
        self.set_slot(a, 0, k);
        self.set_slot(a, 1, v);
        Value::heap(a)
    }

    /// Materialise the entries as a vector of map entries, for `seq`.
    pub fn map_entry_vector(&mut self, m: Value) -> Value {
        // `seq`, `keys` and `vals` all come through here, and it built an entry
        // per key for the price of one builtin dispatch: 6 499 574 steps past an
        // exhausted budget on 200 000 entries.
        if !self.charge_checked(self.map_count(m) as u64, "seq of a map") {
            return crate::value::NIL;
        }
        let base = self.mark();
        let acc = self.empty_vec();
        let ai = self.push(acc);
        let mut st = ai;
        self.map_for_each(m, &mut st, &mut |rt, k, v, ai| {
            let e = rt.map_entry(k, v);
            let nv = rt.vec_conj(rt.r(*ai), e);
            rt.set_r(*ai, nv);
        });
        let out = self.r(ai);
        self.pop_to(base);
        out
    }

    pub fn map_eq(&mut self, a: Value, b: Value) -> bool {
        if self.map_count(a) != self.map_count(b) {
            return false;
        }
        let base = self.mark();
        let ai = self.push(a);
        let bi = self.push(b);
        let mut st = (bi, true);
        let av = self.r(ai);
        self.map_for_each(av, &mut st, &mut |rt, k, v, st| {
            if !st.1 {
                return;
            }
            // `map_get` allocates, so the value handed to this callback has to
            // be rooted before the lookup, not read across it.
            let m = rt.mark();
            let ki = rt.push(k);
            let vi = rt.push(v);
            let other = rt.map_get(rt.r(st.0), rt.r(ki), NOT_FOUND);
            let oi = rt.push(other);
            let same = other != NOT_FOUND && rt.eq(rt.r(vi), rt.r(oi));
            rt.pop_to(m);
            if !same {
                st.1 = false;
            }
        });
        self.pop_to(base);
        st.1
    }

    pub fn hash_map_hash(&mut self, m: Value) -> u32 {
        let slot_idx = if self.is_array_map(m) { AM_HASH } else { HM_HASH };
        let cached = self.slot(m, slot_idx);
        if cached.is_fixnum() {
            return cached.as_fixnum() as i32 as u32;
        }
        let base = self.mark();
        let mi = self.push(m);
        let mut st = (0u32, 0u32);
        let mv = self.r(mi);
        self.map_for_each(mv, &mut st, &mut |rt, k, v, st| {
            let mk = rt.mark();
            let ki = rt.push(k);
            let vi = rt.push(v);
            let hk = rt.hash_value(rt.r(ki));
            let hv = rt.hash_value(rt.r(vi));
            rt.pop_to(mk);
            let e = hash::mix_coll_hash(
                hash::ordered_step(hash::ordered_step(1, hk), hv),
                2,
            );
            st.0 = hash::unordered_step(st.0, e);
            st.1 += 1;
        });
        let h = hash::mix_coll_hash(st.0, st.1);
        let mv = self.r(mi);
        self.set(mv, slot_idx, Value::fixnum(h as i32 as i64));
        self.pop_to(base);
        h
    }

    pub fn hash_map(&mut self, m: Value) -> u32 {
        self.hash_map_hash(m)
    }

    // --- the trie, exposed for transients ----------------------------------

    pub fn champ_find(&mut self, root: Value, h: u32, key: Value) -> Value {
        self.node_find(root, 0, h, key)
    }
    pub fn champ_assoc(&mut self, root: Value, h: u32, k: Value, v: Value, edit: Value) -> Value {
        self.node_assoc(root, 0, h, k, v, edit)
    }
    pub fn champ_dissoc(&mut self, root: Value, h: u32, k: Value, edit: Value) -> Value {
        self.node_dissoc(root, 0, h, k, edit)
    }
    pub fn champ_wrap(&mut self, cnt: u32, root: Value) -> Value {
        self.new_hash_map(cnt, root, NIL)
    }
    pub fn array_map_to_hash(&mut self, m: Value) -> Value {
        self.promote(m)
    }
    pub fn champ_empty_root(&mut self) -> Value {
        self.bn_new(0, 0, NIL)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::value::TRUE;
    use alloc::vec::Vec as StdVec;

    fn kw(rt: &mut Rt, s: &str) -> Value {
        rt.keyword(None, s)
    }

    fn build_int_map(rt: &mut Rt, n: i64) -> Value {
        let m = rt.empty_map();
        let mi = rt.push(m);
        for i in 0..n {
            let nm = rt.map_assoc(rt.r(mi), Value::fixnum(i), Value::fixnum(i * 10));
            rt.set_r(mi, nm);
        }
        let out = rt.r(mi);
        rt.pop_to(mi);
        out
    }

    #[test]
    fn empty_map_basics() {
        let mut rt = Rt::new();
        let m = rt.empty_map();
        assert!(rt.is_map(m));
        assert_eq!(rt.map_count(m), 0);
        let k = kw(&mut rt, "a");
        assert_eq!(rt.map_get(m, k, NIL), NIL);
        assert!(!rt.map_contains(m, k));
    }

    #[test]
    fn small_maps_stay_array_maps_and_keep_insertion_order() {
        let mut rt = Rt::new();
        let m = rt.empty_map();
        let mi = rt.push(m);
        for (i, name) in ["a", "b", "c", "d", "e", "f", "g", "h"].iter().enumerate() {
            let k = kw(&mut rt, name);
            let nm = rt.map_assoc(rt.r(mi), k, Value::fixnum(i as i64));
            rt.set_r(mi, nm);
            assert!(rt.is_array_map(rt.r(mi)), "still an array map at {}", i + 1);
        }
        assert_eq!(rt.map_count(rt.r(mi)), 8);
        // Insertion order is preserved, which is what map literals rely on.
        let mut got = StdVec::new();
        let mut st = &mut got;
        rt.map_for_each(rt.r(mi), &mut st, &mut |rt, _k, v, st| {
            let _ = rt;
            st.push(v.as_fixnum())
        });
        assert_eq!(got, alloc::vec![0, 1, 2, 3, 4, 5, 6, 7]);
    }

    #[test]
    fn promotion_to_champ_preserves_everything() {
        let mut rt = Rt::new();
        let m = rt.empty_map();
        let mi = rt.push(m);
        for i in 0..(ARRAY_MAP_MAX as i64 + 1) {
            let nm = rt.map_assoc(rt.r(mi), Value::fixnum(i), Value::fixnum(i * 10));
            rt.set_r(mi, nm);
        }
        assert!(!rt.is_array_map(rt.r(mi)), "should have promoted");
        assert_eq!(rt.map_count(rt.r(mi)), ARRAY_MAP_MAX + 1);
        for i in 0..(ARRAY_MAP_MAX as i64 + 1) {
            let got = rt.map_get(rt.r(mi), Value::fixnum(i), NIL);
            assert_eq!(got.as_fixnum(), i * 10, "key {i} after promotion");
        }
    }

    #[test]
    fn many_keys_round_trip() {
        let mut rt = Rt::new();
        let n = 20000i64;
        let m = build_int_map(&mut rt, n);
        let mi = rt.push(m);
        assert_eq!(rt.map_count(rt.r(mi)), n as u32);
        for i in 0..n {
            let got = rt.map_get(rt.r(mi), Value::fixnum(i), NOT_FOUND);
            assert_eq!(got.as_fixnum(), i * 10, "key {i}");
        }
        assert_eq!(rt.map_get(rt.r(mi), Value::fixnum(-1), NOT_FOUND), NOT_FOUND);
        assert_eq!(rt.map_get(rt.r(mi), Value::fixnum(n), NOT_FOUND), NOT_FOUND);
    }

    #[test]
    fn assoc_is_persistent() {
        let mut rt = Rt::new();
        let a = build_int_map(&mut rt, 1000);
        let ai = rt.push(a);
        let b = rt.map_assoc(rt.r(ai), Value::fixnum(500), Value::fixnum(-1));
        let bi = rt.push(b);
        assert_eq!(rt.map_get(rt.r(ai), Value::fixnum(500), NIL).as_fixnum(), 5000);
        assert_eq!(rt.map_get(rt.r(bi), Value::fixnum(500), NIL).as_fixnum(), -1);
        assert_eq!(rt.map_count(rt.r(ai)), 1000);
        assert_eq!(rt.map_count(rt.r(bi)), 1000);
    }

    #[test]
    fn assoc_with_an_identical_value_returns_the_same_map() {
        let mut rt = Rt::new();
        let a = build_int_map(&mut rt, 100);
        let ai = rt.push(a);
        let b = rt.map_assoc(rt.r(ai), Value::fixnum(50), Value::fixnum(500));
        assert_eq!(b, rt.r(ai), "no structural change means no allocation");
    }

    #[test]
    fn dissoc_removes_and_collapses() {
        let mut rt = Rt::new();
        for n in [9i64, 100, 5000] {
            let m = build_int_map(&mut rt, n);
            let mi = rt.push(m);
            for i in 0..n {
                let nm = rt.map_dissoc(rt.r(mi), Value::fixnum(i));
                rt.set_r(mi, nm);
                assert_eq!(rt.map_count(rt.r(mi)), (n - i - 1) as u32, "n={n} after removing {i}");
                assert_eq!(rt.map_get(rt.r(mi), Value::fixnum(i), NOT_FOUND), NOT_FOUND);
                if i + 1 < n {
                    let probe = i + 1;
                    assert_eq!(
                        rt.map_get(rt.r(mi), Value::fixnum(probe), NOT_FOUND).as_fixnum(),
                        probe * 10,
                        "n={n} key {probe} still present after removing {i}"
                    );
                }
            }
            assert_eq!(rt.map_count(rt.r(mi)), 0);
            rt.pop_to(mi);
        }
    }

    #[test]
    fn dissoc_of_an_absent_key_is_identity() {
        let mut rt = Rt::new();
        let m = build_int_map(&mut rt, 100);
        let mi = rt.push(m);
        let n = rt.map_dissoc(rt.r(mi), Value::fixnum(-5));
        assert_eq!(n, rt.r(mi));
    }

    /// CHAMP's headline property: the representation is canonical, so a map
    /// built in any order -- or built up and torn down -- has *identical*
    /// structure. Clojure's HAMT does not guarantee this, because it never
    /// un-inlines a node that has shrunk back to a single entry.
    #[test]
    fn representation_is_canonical_regardless_of_history() {
        let mut rt = Rt::new();
        let n = 400i64;

        // forwards
        let a = build_int_map(&mut rt, n);
        let ai = rt.push(a);

        // backwards
        let m = rt.empty_map();
        let bi = rt.push(m);
        for i in (0..n).rev() {
            let nm = rt.map_assoc(rt.r(bi), Value::fixnum(i), Value::fixnum(i * 10));
            rt.set_r(bi, nm);
        }

        // forwards, but with 200 extra keys added and then removed again
        let m = rt.empty_map();
        let ci = rt.push(m);
        for i in 0..n {
            let nm = rt.map_assoc(rt.r(ci), Value::fixnum(i), Value::fixnum(i * 10));
            rt.set_r(ci, nm);
        }
        for i in n..(n + 200) {
            let nm = rt.map_assoc(rt.r(ci), Value::fixnum(i), Value::fixnum(0));
            rt.set_r(ci, nm);
        }
        for i in n..(n + 200) {
            let nm = rt.map_dissoc(rt.r(ci), Value::fixnum(i));
            rt.set_r(ci, nm);
        }

        let shape = |rt: &Rt, m: Value| -> StdVec<(u32, u32, u32)> {
            fn walk(rt: &Rt, n: Value, out: &mut StdVec<(u32, u32, u32)>) {
                if !rt.is_bmnode(n) {
                    out.push((0xffff_ffff, rt.cn_count(n), 0));
                    return;
                }
                let (dm, nm) = (rt.bn_datamap(n), rt.bn_nodemap(n));
                out.push((dm, nm, rt.olen(n)));
                for j in 0..nm.count_ones() {
                    walk(rt, rt.bn_node(n, j), out);
                }
            }
            let mut out = StdVec::new();
            walk(rt, rt.slot(m, HM_ROOT), &mut out);
            out
        };

        let sa = shape(&rt, rt.r(ai));
        let sb = shape(&rt, rt.r(bi));
        let sc = shape(&rt, rt.r(ci));
        assert_eq!(sa, sb, "insertion order changed the structure");
        assert_eq!(sa, sc, "add-then-remove left the structure denormalised");
        assert!(sa.len() > 4, "the test is only meaningful on a real trie: {}", sa.len());
    }

    /// "Aa" and "BB" have the same java String.hashCode, and so the same flint
    /// hash. Four-character combinations give 4-way collisions. This is the
    /// only way to exercise collision nodes deliberately.
    #[test]
    fn full_hash_collisions_go_to_collision_nodes() {
        let mut rt = Rt::new();
        let colliding = ["AaAa", "AaBB", "BBAa", "BBBB"];
        let h0 = {
            let v = rt.string(colliding[0]);
            rt.hash_value(v)
        };
        for s in &colliding[1..] {
            let v = rt.string(s);
            assert_eq!(rt.hash_value(v), h0, "{s} must collide with AaAa");
        }
        let m = rt.empty_map();
        let mi = rt.push(m);
        // Pad past the array-map threshold so we are really in the trie.
        for i in 0..10i64 {
            let nm = rt.map_assoc(rt.r(mi), Value::fixnum(i), Value::fixnum(i));
            rt.set_r(mi, nm);
        }
        for (i, s) in colliding.iter().enumerate() {
            let k = rt.string(s);
            let nm = rt.map_assoc(rt.r(mi), k, Value::fixnum(100 + i as i64));
            rt.set_r(mi, nm);
        }
        assert_eq!(rt.map_count(rt.r(mi)), 14);
        for (i, s) in colliding.iter().enumerate() {
            let k = rt.string(s);
            assert_eq!(
                rt.map_get(rt.r(mi), k, NOT_FOUND).as_fixnum(),
                100 + i as i64,
                "collision key {s}"
            );
        }
        // Removing them one at a time must collapse the collision node cleanly.
        for (i, s) in colliding.iter().enumerate() {
            let k = rt.string(s);
            let nm = rt.map_dissoc(rt.r(mi), k);
            rt.set_r(mi, nm);
            assert_eq!(rt.map_count(rt.r(mi)), 13 - i as u32);
            for s2 in &colliding[i + 1..] {
                let k2 = rt.string(s2);
                assert!(rt.map_contains(rt.r(mi), k2), "{s2} lost while removing {s}");
            }
        }
        assert_eq!(rt.map_count(rt.r(mi)), 10);
    }

    #[test]
    fn heterogeneous_keys() {
        let mut rt = Rt::new();
        let m = rt.empty_map();
        let mi = rt.push(m);
        let keys: StdVec<Value> = {
            let a = kw(&mut rt, "kw");
            let b = rt.string("a string key");
            let c = rt.symbol(None, "sym");
            let d = Value::fixnum(42);
            let e = Value::from_f64(1.5);
            let f = TRUE;
            let g = NIL;
            let h = rt.integer(1 << 50);
            alloc::vec![a, b, c, d, e, f, g, h]
        };
        for (i, k) in keys.iter().enumerate() {
            let nm = rt.map_assoc(rt.r(mi), *k, Value::fixnum(i as i64));
            rt.set_r(mi, nm);
        }
        // Push past the array-map threshold so the trie handles them.
        for i in 0..20i64 {
            let k = rt.string(&alloc::format!("filler-{i}"));
            let nm = rt.map_assoc(rt.r(mi), k, Value::fixnum(-1));
            rt.set_r(mi, nm);
        }
        for (i, k) in keys.iter().enumerate() {
            assert_eq!(
                rt.map_get(rt.r(mi), *k, NOT_FOUND).as_fixnum(),
                i as i64,
                "key #{i} {:?}",
                k
            );
        }
        assert!(rt.map_contains(rt.r(mi), NIL), "nil is a perfectly good key");
    }

    #[test]
    fn equality_and_hash_match_clojure() {
        let mut rt = Rt::new();
        let empty = rt.empty_map();
        assert_eq!(rt.hash_value(empty) as i32, -15128758, "{{}}");

        let a = kw(&mut rt, "a");
        let m1 = rt.map_assoc(empty, a, Value::fixnum(1));
        let m1i = rt.push(m1);
        assert_eq!(rt.hash_value(rt.r(m1i)) as i32, 1772842048, "{{:a 1}}");

        let b = kw(&mut rt, "b");
        let m2 = rt.map_assoc(rt.r(m1i), b, Value::fixnum(2));
        let m2i = rt.push(m2);
        assert_eq!(rt.hash_value(rt.r(m2i)) as i32, 161871944, "{{:a 1 :b 2}}");

        // Order does not matter for equality or hash.
        let n = rt.map_assoc(empty, b, Value::fixnum(2));
        let ni = rt.push(n);
        let a = kw(&mut rt, "a");
        let n = rt.map_assoc(rt.r(ni), a, Value::fixnum(1));
        let ni2 = rt.push(n);
        assert!(rt.eq(rt.r(m2i), rt.r(ni2)));
        assert_eq!(rt.hash_value(rt.r(ni2)), rt.hash_value(rt.r(m2i)));

        // A big map equals itself rebuilt in reverse, and differs by one entry.
        let p = build_int_map(&mut rt, 500);
        let pi = rt.push(p);
        let q = rt.empty_map();
        let qi = rt.push(q);
        for i in (0..500i64).rev() {
            let nm = rt.map_assoc(rt.r(qi), Value::fixnum(i), Value::fixnum(i * 10));
            rt.set_r(qi, nm);
        }
        assert!(rt.eq(rt.r(pi), rt.r(qi)));
        let q2 = rt.map_assoc(rt.r(qi), Value::fixnum(499), Value::fixnum(0));
        assert!(!rt.eq(rt.r(pi), q2));
        let q3 = rt.map_dissoc(rt.r(qi), Value::fixnum(0));
        assert!(!rt.eq(rt.r(pi), q3), "differing counts are not equal");
    }

    #[cfg(feature = "diagnostics")]
    #[test]
    fn survives_collection_at_every_allocation() {
        let mut rt = Rt::new();
        rt.gc.stress = true;
        let m = rt.empty_map();
        let mi = rt.push(m);
        for i in 0..300i64 {
            let k = rt.string(&alloc::format!("key number {i}"));
            let ki = rt.push(k);
            let v = rt.string(&alloc::format!("value number {i}"));
            let k = rt.r(ki);
            rt.pop_to(ki);
            let nm = rt.map_assoc(rt.r(mi), k, v);
            rt.set_r(mi, nm);
        }
        rt.collect();
        assert_eq!(rt.map_count(rt.r(mi)), 300);
        let mut b = crate::rt::sbuf();
        for i in 0..300i64 {
            let k = rt.string(&alloc::format!("key number {i}"));
            let got = rt.map_get(rt.r(mi), k, NOT_FOUND);
            assert_ne!(got, NOT_FOUND, "lost key {i}");
            assert_eq!(
                rt.as_str(got, &mut b),
                Some(alloc::format!("value number {i}").as_str())
            );
            let mut b2 = crate::rt::sbuf();
            let _ = &mut b2;
        }
    }

    #[test]
    fn entry_vector_and_seq_view() {
        let mut rt = Rt::new();
        let m = build_int_map(&mut rt, 50);
        let mi = rt.push(m);
        let ev = rt.map_entry_vector(rt.r(mi));
        assert_eq!(rt.vec_count(ev), 50);
        let evi = rt.push(ev);
        let mut seen = StdVec::new();
        for i in 0..50 {
            let e = rt.vec_nth(rt.r(evi), i).unwrap();
            let (k, v) = (rt.slot(e, 0), rt.slot(e, 1));
            assert_eq!(v.as_fixnum(), k.as_fixnum() * 10);
            seen.push(k.as_fixnum());
        }
        seen.sort();
        assert_eq!(seen, (0..50i64).collect::<StdVec<_>>());
    }
}
