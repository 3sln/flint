//! `PersistentVector`: a 32-way trie with a tail, and its transient.
//!
//! The shape is Clojure's, because Clojure's is right: a tail buffer turns
//! `conj` into a 32-element array copy amortised to O(1), and `nth` into at most
//! `depth` indexed loads. What differs is bookkeeping — a node here is a normal
//! GC object whose slot 0 is the transient ownership token (`nil` when
//! persistent), rather than a Java array plus an `AtomicReference`.
//!
//! ```text
//!   TY_VEC   [cnt, shift, root, tail, meta]
//!   TY_NODE  [edit, e0, e1, ... ]        -- internal nodes are always 32 wide
//! ```

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, NIL};

pub const BITS: u32 = 5;
pub const WIDTH: u32 = 1 << BITS; // 32
pub const MASK: u32 = WIDTH - 1;

// --- node helpers ----------------------------------------------------------

impl Rt {
    /// A trie node with `n` child slots, all nil.
    pub fn new_node(&mut self, n: u32, edit: Value) -> Value {
        let e = self.push(edit);
        let a = self.alloc(TY_NODE, n + 1);
        let edit = self.r(e);
        self.pop_to(e);
        if a == 0 {
            return NIL;
        }
        self.gc.set_slot(a, 0, edit);
        Value::heap(a)
    }
    #[inline]
    pub fn node_len(&self, n: Value) -> u32 {
        len(&self.gc.sp, n.as_heap()) - 1
    }
    #[inline]
    pub fn node_get(&self, n: Value, i: u32) -> Value {
        slot(&self.gc.sp, n.as_heap(), i + 1)
    }
    #[inline]
    pub fn node_set(&mut self, n: Value, i: u32, v: Value) {
        self.gc.set_slot(n.as_heap(), i + 1, v);
    }
    #[inline]
    pub fn node_edit(&self, n: Value) -> Value {
        slot(&self.gc.sp, n.as_heap(), 0)
    }

    /// Copy a node, optionally resizing. Used for every persistent update.
    pub fn node_clone(&mut self, n: Value, newlen: u32, edit: Value) -> Value {
        let old = self.push(n);
        let e = self.push(edit);
        let fresh = self.new_node(newlen, self.r(e));
        if fresh.is_nil() {
            self.pop_to(old);
            return NIL;
        }
        let f = self.push(fresh);
        let src = self.r(old);
        let copy = core::cmp::min(newlen, self.node_len(src));
        for i in 0..copy {
            let v = self.node_get(self.r(old), i);
            self.node_set(self.r(f), i, v);
        }
        let out = self.r(f);
        self.pop_to(old);
        out
    }
}

// --- vector ----------------------------------------------------------------

pub const V_CNT: u32 = 0;
pub const V_SHIFT: u32 = 1;
pub const V_ROOT: u32 = 2;
pub const V_TAIL: u32 = 3;
pub const V_META: u32 = 4;

impl Rt {
    pub(crate) fn init_vector(&mut self) {
        let root = self.new_node(WIDTH, NIL);
        let r = self.push(root);
        let tail = self.new_node(0, NIL);
        let t = self.push(tail);
        let a = self.alloc(TY_VEC, 5);
        let (root, tail) = (self.r(r), self.r(t));
        self.pop_to(r);
        self.gc.set_slot(a, V_CNT, Value::fixnum(0));
        self.gc.set_slot(a, V_SHIFT, Value::fixnum(BITS as i64));
        self.gc.set_slot(a, V_ROOT, root);
        self.gc.set_slot(a, V_TAIL, tail);
        self.gc.set_slot(a, V_META, NIL);
        self.roots.singletons[crate::rt::SING_EMPTY_VEC] = Value::heap(a);
    }

    pub fn is_vector(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_VEC
    }
    #[inline]
    pub fn vec_count(&self, v: Value) -> u32 {
        slot(&self.gc.sp, v.as_heap(), V_CNT).as_fixnum() as u32
    }
    #[inline]
    fn vec_shift(&self, v: Value) -> u32 {
        slot(&self.gc.sp, v.as_heap(), V_SHIFT).as_fixnum() as u32
    }
    #[inline]
    fn vec_root(&self, v: Value) -> Value {
        slot(&self.gc.sp, v.as_heap(), V_ROOT)
    }
    #[inline]
    fn vec_tail(&self, v: Value) -> Value {
        slot(&self.gc.sp, v.as_heap(), V_TAIL)
    }
    #[inline]
    fn tail_off(&self, v: Value) -> u32 {
        let c = self.vec_count(v);
        if c < WIDTH {
            0
        } else {
            ((c - 1) >> BITS) << BITS
        }
    }

    fn new_vec(&mut self, cnt: u32, shift: u32, root: Value, tail: Value, meta: Value) -> Value {
        let base = self.mark();
        let r = self.push(root);
        let t = self.push(tail);
        let m = self.push(meta);
        let a = self.alloc(TY_VEC, 5);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (root, tail, meta) = (self.r(r), self.r(t), self.r(m));
        self.pop_to(base);
        self.gc.set_slot(a, V_CNT, Value::fixnum(cnt as i64));
        self.gc.set_slot(a, V_SHIFT, Value::fixnum(shift as i64));
        self.gc.set_slot(a, V_ROOT, root);
        self.gc.set_slot(a, V_TAIL, tail);
        self.gc.set_slot(a, V_META, meta);
        Value::heap(a)
    }

    /// The 32-element leaf array containing index `i`.
    fn array_for(&self, v: Value, i: u32) -> Value {
        if i >= self.tail_off(v) {
            return self.vec_tail(v);
        }
        let mut node = self.vec_root(v);
        let mut level = self.vec_shift(v);
        while level > 0 {
            node = self.node_get(node, (i >> level) & MASK);
            level -= BITS;
        }
        node
    }

    pub fn vec_nth(&self, v: Value, i: u32) -> Option<Value> {
        if i >= self.vec_count(v) {
            return None;
        }
        let arr = self.array_for(v, i);
        Some(self.node_get(arr, i & MASK))
    }

    fn new_path(&mut self, level: u32, node: Value, edit: Value) -> Value {
        if level == 0 {
            return node;
        }
        let base = self.mark();
        let n = self.push(node);
        let e = self.push(edit);
        let child = self.new_path(level - BITS, self.r(n), self.r(e));
        let c = self.push(child);
        let parent = self.new_node(WIDTH, self.r(e));
        if parent.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let p = self.push(parent);
        let child = self.r(c);
        self.node_set(self.r(p), 0, child);
        let out = self.r(p);
        self.pop_to(base);
        out
    }

    /// Push `tailnode` into the trie at `level`, copying the spine.
    fn push_tail(&mut self, cnt: u32, level: u32, parent: Value, tailnode: Value, edit: Value) -> Value {
        let base = self.mark();
        let p = self.push(parent);
        let t = self.push(tailnode);
        let e = self.push(edit);
        let subidx = ((cnt - 1) >> level) & MASK;
        let ret = self.node_clone(self.r(p), WIDTH, self.r(e));
        if ret.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let r = self.push(ret);
        let to_insert = if level == BITS {
            self.r(t)
        } else {
            let child = self.node_get(self.r(p), subidx);
            if child.is_nil() {
                self.new_path(level - BITS, self.r(t), self.r(e))
            } else {
                self.push_tail(cnt, level - BITS, child, self.r(t), self.r(e))
            }
        };
        self.node_set(self.r(r), subidx, to_insert);
        let out = self.r(r);
        self.pop_to(base);
        out
    }

    pub fn vec_conj(&mut self, v: Value, x: Value) -> Value {
        let base = self.mark();
        let vi = self.push(v);
        let xi = self.push(x);
        let cnt = self.vec_count(v);
        let tail_len = cnt - self.tail_off(v);
        let out = if tail_len < WIDTH {
            // Room in the tail: copy it one longer.
            let newtail = self.node_clone(self.vec_tail(self.r(vi)), tail_len + 1, NIL);
            let nt = self.push(newtail);
            let x = self.r(xi);
            self.node_set(self.r(nt), tail_len, x);
            let v = self.r(vi);
            let (shift, root, meta) = (self.vec_shift(v), self.vec_root(v), self.slot(v, V_META));
            let nt = self.r(nt);
            self.new_vec(cnt + 1, shift, root, nt, meta)
        } else {
            // Tail is full: it becomes a leaf in the trie.
            let v = self.r(vi);
            let shift = self.vec_shift(v);
            let tailnode = self.vec_tail(v);
            let tn = self.push(tailnode);
            let overflow = (cnt >> BITS) > (1u32 << shift);
            let (newroot, newshift) = if overflow {
                let nr = self.new_node(WIDTH, NIL);
                let nri = self.push(nr);
                let oldroot = self.vec_root(self.r(vi));
                self.node_set(self.r(nri), 0, oldroot);
                let path = self.new_path(shift, self.r(tn), NIL);
                self.node_set(self.r(nri), 1, path);
                (self.r(nri), shift + BITS)
            } else {
                let root = self.vec_root(self.r(vi));
                (self.push_tail(cnt, shift, root, self.r(tn), NIL), shift)
            };
            let nr = self.push(newroot);
            let newtail = self.new_node(1, NIL);
            let ntl = self.push(newtail);
            let x = self.r(xi);
            self.node_set(self.r(ntl), 0, x);
            let meta = self.slot(self.r(vi), V_META);
            let (nr, ntl) = (self.r(nr), self.r(ntl));
            self.new_vec(cnt + 1, newshift, nr, ntl, meta)
        };
        self.pop_to(base);
        out
    }

    fn do_assoc(&mut self, level: u32, node: Value, i: u32, val: Value) -> Value {
        let base = self.mark();
        let n = self.push(node);
        let v = self.push(val);
        let ret = self.node_clone(self.r(n), WIDTH, NIL);
        if ret.is_nil() {
            self.pop_to(base);
            return NIL;
        }
        let r = self.push(ret);
        if level == 0 {
            let val = self.r(v);
            self.node_set(self.r(r), i & MASK, val);
        } else {
            let subidx = (i >> level) & MASK;
            let child = self.node_get(self.r(n), subidx);
            let nc = self.do_assoc(level - BITS, child, i, self.r(v));
            self.node_set(self.r(r), subidx, nc);
        }
        let out = self.r(r);
        self.pop_to(base);
        out
    }

    pub fn vec_assoc(&mut self, v: Value, i: u32, x: Value) -> Value {
        let cnt = self.vec_count(v);
        if i == cnt {
            return self.vec_conj(v, x);
        }
        debug_assert!(i < cnt);
        let base = self.mark();
        let vi = self.push(v);
        let xi = self.push(x);
        let out = if i >= self.tail_off(v) {
            let tail = self.vec_tail(self.r(vi));
            let tl = self.node_len(tail);
            let nt = self.node_clone(tail, tl, NIL);
            let nti = self.push(nt);
            let x = self.r(xi);
            let v = self.r(vi);
            self.node_set(self.r(nti), i - self.tail_off(v), x);
            let v = self.r(vi);
            let (shift, root, meta) = (self.vec_shift(v), self.vec_root(v), self.slot(v, V_META));
            let nt = self.r(nti);
            self.new_vec(cnt, shift, root, nt, meta)
        } else {
            let v = self.r(vi);
            let shift = self.vec_shift(v);
            let root = self.vec_root(v);
            let nr = self.do_assoc(shift, root, i, self.r(xi));
            let nri = self.push(nr);
            let v = self.r(vi);
            let (tail, meta) = (self.vec_tail(v), self.slot(v, V_META));
            let nr = self.r(nri);
            self.new_vec(cnt, shift, nr, tail, meta)
        };
        self.pop_to(base);
        out
    }

    fn pop_tail(&mut self, level: u32, node: Value, cnt: u32) -> Value {
        let subidx = ((cnt - 2) >> level) & MASK;
        if level > BITS {
            let base = self.mark();
            let n = self.push(node);
            let child = self.node_get(node, subidx);
            let newchild = self.pop_tail(level - BITS, child, cnt);
            let out = if newchild.is_nil() && subidx == 0 {
                NIL
            } else {
                let nc = self.push(newchild);
                let ret = self.node_clone(self.r(n), WIDTH, NIL);
                let r = self.push(ret);
                let nc = self.r(nc);
                self.node_set(self.r(r), subidx, nc);
                self.r(r)
            };
            self.pop_to(base);
            out
        } else if subidx == 0 {
            NIL
        } else {
            let ret = self.node_clone(node, WIDTH, NIL);
            self.node_set(ret, subidx, NIL);
            ret
        }
    }

    pub fn vec_pop(&mut self, v: Value) -> Value {
        let cnt = self.vec_count(v);
        if cnt == 0 {
            return NIL; // caller raises; an empty vector cannot be popped
        }
        if cnt == 1 {
            return self.empty_vec();
        }
        let base = self.mark();
        let vi = self.push(v);
        let out = if cnt - 1 > self.tail_off(v) {
            let tail = self.vec_tail(v);
            let newlen = self.node_len(tail) - 1;
            let nt = self.node_clone(tail, newlen, NIL);
            let nti = self.push(nt);
            let v = self.r(vi);
            let (shift, root, meta) = (self.vec_shift(v), self.vec_root(v), self.slot(v, V_META));
            let nt = self.r(nti);
            self.new_vec(cnt - 1, shift, root, nt, meta)
        } else {
            let newtail = self.array_for(v, cnt - 2);
            let nt = self.push(newtail);
            let v = self.r(vi);
            let shift = self.vec_shift(v);
            let root = self.vec_root(v);
            let mut newroot = self.pop_tail(shift, root, cnt);
            let mut newshift = shift;
            if newroot.is_nil() {
                newroot = self.new_node(WIDTH, NIL);
            }
            let nri = self.push(newroot);
            if newshift > BITS {
                let second = self.node_get(self.r(nri), 1);
                if second.is_nil() {
                    let first = self.node_get(self.r(nri), 0);
                    self.set_r(nri, first);
                    newshift -= BITS;
                }
            }
            let meta = self.slot(self.r(vi), V_META);
            let (nr, nt) = (self.r(nri), self.r(nt));
            self.new_vec(cnt - 1, newshift, nr, nt, meta)
        };
        self.pop_to(base);
        out
    }

    /// Build a vector from a slice of values already on the shadow stack.
    /// `base` is the shadow index of the first element.
    pub fn vec_from_roots(&mut self, base: usize, n: usize) -> Value {
        let mut v = self.empty_vec();
        let vi = self.push(v);
        for i in 0..n {
            let x = self.r(base + i);
            let nv = self.vec_conj(self.r(vi), x);
            self.set_r(vi, nv);
        }
        v = self.r(vi);
        self.pop_to(vi);
        v
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::vec::Vec as StdVec;

    fn build(rt: &mut Rt, n: u32) -> Value {
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..n {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i as i64));
            rt.set_r(vi, nv);
        }
        v = rt.r(vi);
        rt.pop_to(vi);
        v
    }

    fn to_vec(rt: &Rt, v: Value) -> StdVec<i64> {
        (0..rt.vec_count(v)).map(|i| rt.vec_nth(v, i).unwrap().as_fixnum()).collect()
    }

    #[test]
    fn empty_vector_is_a_shared_singleton() {
        let mut rt = Rt::new();
        assert_eq!(rt.vec_count(rt.empty_vec()), 0);
        assert!(rt.is_vector(rt.empty_vec()));
        assert_eq!(rt.vec_nth(rt.empty_vec(), 0), None);
    }

    #[test]
    fn conj_and_nth_across_every_depth_boundary() {
        let mut rt = Rt::new();
        // 32^1, 32^2 and a bit past 32^3 exercise every shift transition.
        for n in [0u32, 1, 31, 32, 33, 1023, 1024, 1025, 32768, 33000] {
            let v = build(&mut rt, n);
            assert_eq!(rt.vec_count(v), n, "count at n={n}");
            for i in 0..n {
                assert_eq!(rt.vec_nth(v, i).map(|x| x.as_fixnum()), Some(i as i64), "nth {i} of {n}");
            }
            assert_eq!(rt.vec_nth(v, n), None, "out of range at n={n}");
        }
    }

    #[test]
    fn conj_is_persistent() {
        let mut rt = Rt::new();
        let a = build(&mut rt, 40);
        let ai = rt.push(a);
        let b = rt.vec_conj(rt.r(ai), Value::fixnum(999));
        assert_eq!(rt.vec_count(rt.r(ai)), 40, "the original is untouched");
        assert_eq!(rt.vec_count(b), 41);
        assert_eq!(rt.vec_nth(b, 40).unwrap().as_fixnum(), 999);
        assert_eq!(rt.vec_nth(rt.r(ai), 39).unwrap().as_fixnum(), 39);
    }

    #[test]
    fn assoc_replaces_without_disturbing_the_original() {
        let mut rt = Rt::new();
        for n in [1u32, 32, 33, 1025, 5000] {
            let a = build(&mut rt, n);
            let ai = rt.push(a);
            for i in [0u32, n / 2, n - 1] {
                let b = rt.vec_assoc(rt.r(ai), i, Value::fixnum(-1));
                assert_eq!(rt.vec_nth(b, i).unwrap().as_fixnum(), -1, "n={n} i={i}");
                assert_eq!(rt.vec_count(b), n);
                assert_eq!(rt.vec_nth(rt.r(ai), i).unwrap().as_fixnum(), i as i64, "original intact");
                // every other index unchanged
                for j in [0u32, n / 3, n - 1] {
                    if j != i {
                        assert_eq!(rt.vec_nth(b, j).unwrap().as_fixnum(), j as i64);
                    }
                }
            }
            rt.pop_to(ai);
        }
    }

    #[test]
    fn assoc_at_count_appends() {
        let mut rt = Rt::new();
        let a = build(&mut rt, 5);
        let b = rt.vec_assoc(a, 5, Value::fixnum(5));
        assert_eq!(to_vec(&rt, b), alloc::vec![0, 1, 2, 3, 4, 5]);
    }

    #[test]
    fn pop_unwinds_the_trie_exactly() {
        let mut rt = Rt::new();
        let n = 2100u32;
        let mut v = build(&mut rt, n);
        let vi = rt.push(v);
        for k in (0..n).rev() {
            let nv = rt.vec_pop(rt.r(vi));
            rt.set_r(vi, nv);
            assert_eq!(rt.vec_count(rt.r(vi)), k, "after popping down to {k}");
            if k > 0 {
                assert_eq!(rt.vec_nth(rt.r(vi), k - 1).unwrap().as_fixnum(), (k - 1) as i64);
            }
        }
        v = rt.r(vi);
        assert_eq!(rt.vec_count(v), 0);
    }

    #[test]
    fn pop_is_persistent() {
        let mut rt = Rt::new();
        let a = build(&mut rt, 100);
        let ai = rt.push(a);
        let b = rt.vec_pop(rt.r(ai));
        assert_eq!(rt.vec_count(rt.r(ai)), 100);
        assert_eq!(rt.vec_count(b), 99);
    }

    #[test]
    fn survives_collection_at_every_allocation() {
        let mut rt = Rt::new();
        rt.gc.stress = true;
        let v = build(&mut rt, 400);
        let vi = rt.push(v);
        rt.collect();
        assert_eq!(to_vec(&rt, rt.r(vi)), (0..400i64).collect::<StdVec<_>>());
        let b = rt.vec_assoc(rt.r(vi), 200, Value::fixnum(-7));
        let bi = rt.push(b);
        rt.collect();
        assert_eq!(rt.vec_nth(rt.r(bi), 200).unwrap().as_fixnum(), -7);
        assert_eq!(rt.vec_nth(rt.r(vi), 200).unwrap().as_fixnum(), 200);
    }

    #[test]
    fn deep_vectors_hold_heap_values() {
        let mut rt = Rt::new();
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..2000 {
            let s = rt.string(&alloc::format!("element number {i}"));
            let nv = rt.vec_conj(rt.r(vi), s);
            rt.set_r(vi, nv);
        }
        rt.collect();
        v = rt.r(vi);
        let mut b = crate::rt::sbuf();
        for i in [0u32, 999, 1999] {
            let s = rt.vec_nth(v, i).unwrap();
            assert_eq!(rt.as_str(s, &mut b), Some(alloc::format!("element number {i}").as_str()));
            let mut b2 = crate::rt::sbuf();
            let _ = &mut b2;
        }
    }
}
