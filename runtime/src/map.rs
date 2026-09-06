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
// THE CONSUMING LINE. `map.rs`'s CHAMP functions live in the generated
// subtree now -- `kgen/rt/champ.rs` and seven neighbours -- and every
// one of them is an `impl Rt` method, so nothing here needs importing to call
// them. What DOES change is the other direction: the node-layout constants
// and the three bit helpers below are read by the generated code, so they are
// `pub(crate)` rather than module-private.
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
pub(crate) const BN_EDIT: u32 = 0;
pub(crate) const BN_DATAMAP: u32 = 1;
pub(crate) const BN_NODEMAP: u32 = 2;
pub(crate) const BN_BASE: u32 = 3;

// collision node layout
pub(crate) const CN_EDIT: u32 = 0;
pub(crate) const CN_HASH: u32 = 1;
pub(crate) const CN_BASE: u32 = 2;

pub const HASH_BITS: u32 = 5;
pub const HASH_WIDTH: u32 = 32;

#[inline]
pub(crate) fn mask(h: u32, shift: u32) -> u32 {
    (h >> shift) & 0x1f
}
#[inline]
pub(crate) fn bitpos(h: u32, shift: u32) -> u32 {
    1u32 << mask(h, shift)
}
#[inline]
pub(crate) fn index_of(bitmap: u32, bit: u32) -> u32 {
    (bitmap & (bit - 1)).count_ones()
}

impl Rt {
    // --- node primitives ---------------------------------------------------




    #[inline]

    // --- lookup ------------------------------------------------------------

    /// Lookup for a key whose `=` cannot allocate. No rooting, because nothing
    /// here can move: this is the shape `get` almost always has.

    // --- construction of a two-entry subtree --------------------------------


    // --- structural copies --------------------------------------------------





    // --- the map objects ----------------------------------------------------

    pub(crate) fn init_map(&mut self) {
        let a = self.alloc(TY_ARRAYMAP, AM_BASE);
        self.set_slot(a, AM_META, NIL);
        self.set_slot(a, AM_HASH, NIL);
        self.roots.shared.singletons[crate::rt::SING_EMPTY_MAP] = Value::heap(a);
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

    /// Every key and value onto the shadow stack from `at`, returning the
    /// PAIR COUNT. The shape both ports use to walk a map, mirrored here so
    /// the two approaches can be measured in one runtime rather than across
    /// two -- see `runtime/examples/iterbench.rs`.
    ///
    /// Not the shape Rust uses in anger: `map_for_each` visits in place and
    /// pushes nothing, where this pushes 2N roots before the caller reads any
    /// of them. Which of those is right is what the measurement is for.
    ///
    /// MEASURED, and the callback wins on both axes. 50 000 entries, 20 walks:
    ///
    ///     callback      4.673 ms   peak roots        8
    ///     buffer        8.089 ms   peak roots  100 001
    ///     buf/noslide   4.924 ms   peak roots  100 001
    ///
    /// 1.73x the time and 12 500x the peak roots; even with the slide removed
    /// it is 1.054x slower for the same 12 500x. The roots number is the one
    /// that decides it: the collector scans the shadow stack, so 100 001 live
    /// roots is 100 001 words to trace at every collection DURING the walk,
    /// and a walk is exactly when a program is most likely to allocate.
    ///
    /// Where allocation meets live roots -- building the entry vector, five
    /// times -- it is 29.715 ms against 31.656 ms, 1.065x.
    ///
    /// SO THE CLOSURE HOLE IS A REAL HOLE. `doc/goals/kin-port.md` lists five
    /// functions blocked on a callback argument, and the obvious workaround --
    /// hand the roots over as a `(base, n)` run, which is what `invoke-roots`
    /// and `list_from_roots` do elsewhere -- is measurably the wrong shape
    /// HERE, because those pass a handful of arguments and this would pass the
    /// whole map. `runtime/examples/iterbench.rs` is the measurement.
    #[cfg(feature = "bench")]
    pub fn map_entries(&mut self, m: Value, at: usize) -> u32 {
        if !m.is_heap() {
            return 0;
        }
        match ty(&self.gc.sp, m.as_heap()) {
            TY_ARRAYMAP => {
                let n = self.map_count(m);
                let mi = self.push(m);
                for i in 0..n {
                    let k = self.am_key(self.r(mi), i);
                    self.push(k);
                    let v = self.am_val(self.r(mi), i);
                    self.push(v);
                }
                // The map itself was pushed first; slide the pairs down over it.
                for i in 0..(2 * n as usize) {
                    let v = self.r(at + 1 + i);
                    self.set_r(at + i, v);
                }
                self.pop_to(at + 2 * n as usize);
                n
            }
            TY_HASHMAP => {
                let root = self.slot(m, HM_ROOT);
                if root.is_nil() {
                    return 0;
                }
                self.node_entries(root, at)
            }
            _ => 0,
        }
    }

    #[cfg(feature = "bench")]
    fn node_entries(&mut self, node: Value, at: usize) -> u32 {
        let ni = self.push(node);
        let mut wrote: u32 = 0;
        if !self.is_bmnode(self.r(ni)) {
            let cnt = self.cn_count(self.r(ni));
            for i in 0..cnt {
                let k = self.cn_key(self.r(ni), i);
                self.push(k);
                let v = self.cn_val(self.r(ni), i);
                self.push(v);
                wrote += 1;
            }
        } else {
            let ne = self.bn_datamap(self.r(ni)).count_ones();
            let nn = self.bn_nodemap(self.r(ni)).count_ones();
            for i in 0..ne {
                let k = self.bn_key(self.r(ni), i);
                self.push(k);
                let v = self.bn_val(self.r(ni), i);
                self.push(v);
                wrote += 1;
            }
            for j in 0..nn {
                let sub = self.bn_node(self.r(ni), j);
                let m = self.mark();
                wrote += self.node_entries(sub, m);
            }
        }
        for i in 0..(2 * wrote as usize) {
            let v = self.r(at + 1 + i);
            self.set_r(at + i, v);
        }
        self.pop_to(at + 2 * wrote as usize);
        wrote
    }

    /// The buffer shape WITHOUT the slide-down, for `iterbench`.
    ///
    /// The ports root each node handle and then slide the whole subtree down
    /// over it. Iteration allocates nothing, so no collection can happen
    /// mid-walk and the handle never needed rooting -- a host local is safe
    /// here, which is the one case `0031` does not cover. This is the buffer
    /// shape's honest best case, so that the comparison indicts the SHAPE and
    /// not my transcription of it.
    #[cfg(feature = "bench")]
    pub fn map_entries_flat(&mut self, m: Value, at: usize) -> u32 {
        if !m.is_heap() {
            return 0;
        }
        match ty(&self.gc.sp, m.as_heap()) {
            TY_ARRAYMAP => {
                let n = self.map_count(m);
                for i in 0..n {
                    let k = self.am_key(m, i);
                    self.push(k);
                    let v = self.am_val(m, i);
                    self.push(v);
                }
                n
            }
            TY_HASHMAP => {
                let root = self.slot(m, HM_ROOT);
                if root.is_nil() {
                    return 0;
                }
                self.node_entries_flat(root)
            }
            _ => 0,
        }
    }

    #[cfg(feature = "bench")]
    fn node_entries_flat(&mut self, node: Value) -> u32 {
        let mut wrote: u32 = 0;
        if !self.is_bmnode(node) {
            let cnt = self.cn_count(node);
            for i in 0..cnt {
                let k = self.cn_key(node, i);
                self.push(k);
                let v = self.cn_val(node, i);
                self.push(v);
                wrote += 1;
            }
        } else {
            let ne = self.bn_datamap(node).count_ones();
            let nn = self.bn_nodemap(node).count_ones();
            for i in 0..ne {
                let k = self.bn_key(node, i);
                self.push(k);
                let v = self.bn_val(node, i);
                self.push(v);
                wrote += 1;
            }
            for j in 0..nn {
                let sub = self.bn_node(node, j);
                wrote += self.node_entries_flat(sub);
            }
        }
        wrote
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
            let e = rt.vec_nth(rt.r(evi), i, crate::value::NIL);
            let (k, v) = (rt.slot(e, 0), rt.slot(e, 1));
            assert_eq!(v.as_fixnum(), k.as_fixnum() * 10);
            seen.push(k.as_fixnum());
        }
        seen.sort();
        assert_eq!(seen, (0..50i64).collect::<StdVec<_>>());
    }
}
