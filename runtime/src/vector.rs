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

// --- transient vector ------------------------------------------------------
//
// TY_TVEC [cnt, shift, root, tail, edit]
//
// `edit` is a freshly allocated object used purely for its identity. A node
// whose slot 0 is that same object is owned by this transient and is mutated in
// place; any other node is copied once and thereafter owned. `persistent!`
// clears `edit`, so a stale handle fails loudly instead of corrupting a value
// somebody else is holding.

pub const T_CNT: u32 = 0;
pub const T_SHIFT: u32 = 1;
pub const T_ROOT: u32 = 2;
pub const T_TAIL: u32 = 3;
pub const T_EDIT: u32 = 4;

impl Rt {
    /// A fresh identity for a transient's ownership token.
    pub fn new_edit_token(&mut self) -> Value {
        let a = self.alloc(TY_VOLATILE, 1);
        if a == 0 { return NIL; }
        Value::heap(a)
    }

    pub fn is_transient_vector(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_TVEC
    }

    #[inline]
    pub fn tvec_count(&self, t: Value) -> u32 {
        slot(&self.gc.sp, t.as_heap(), T_CNT).as_fixnum() as u32
    }
    #[inline]
    fn tvec_shift(&self, t: Value) -> u32 {
        slot(&self.gc.sp, t.as_heap(), T_SHIFT).as_fixnum() as u32
    }
    #[inline]
    fn tvec_tail_off(&self, t: Value) -> u32 {
        let c = self.tvec_count(t);
        if c < WIDTH { 0 } else { ((c - 1) >> BITS) << BITS }
    }
    #[inline]
    pub fn tvec_alive(&self, t: Value) -> bool {
        !slot(&self.gc.sp, t.as_heap(), T_EDIT).is_nil()
    }

    /// `transient`: O(1). Nothing is copied until the first write that reaches
    /// a node this transient does not already own.
    pub fn vec_transient(&mut self, v: Value) -> Value {
        let base = self.mark();
        let vi = self.push(v);
        let edit = self.new_edit_token();
        let ei = self.push(edit);
        // The tail is widened to a full 32 up front so conj! can write in place.
        let oldtail = self.vec_tail(self.r(vi));
        let otl = self.node_len(oldtail);
        let tail = self.node_clone(oldtail, WIDTH, self.r(ei));
        let _ = otl;
        let ti = self.push(tail);
        let root = self.vec_root(self.r(vi));
        let ri = self.push(root);
        let a = self.alloc(TY_TVEC, 5);
        if a == 0 { self.pop_to(base); return NIL; }
        let v = self.r(vi);
        let cnt = self.vec_count(v);
        let shift = self.vec_shift(v);
        let (root, tail, edit) = (self.r(ri), self.r(ti), self.r(ei));
        self.pop_to(base);
        self.gc.set_slot(a, T_CNT, Value::fixnum(cnt as i64));
        self.gc.set_slot(a, T_SHIFT, Value::fixnum(shift as i64));
        self.gc.set_slot(a, T_ROOT, root);
        self.gc.set_slot(a, T_TAIL, tail);
        self.gc.set_slot(a, T_EDIT, edit);
        Value::heap(a)
    }

    /// Return `node` if this transient already owns it, else an owned copy.
    fn ensure_editable(&mut self, node: Value, edit: Value) -> Value {
        if self.node_edit(node) == edit {
            node
        } else {
            self.node_clone(node, WIDTH, edit)
        }
    }

    fn t_push_tail(&mut self, cnt: u32, level: u32, parent: Value, tailnode: Value, edit: Value) -> Value {
        let base = self.mark();
        let e = self.push(edit);
        let t = self.push(tailnode);
        let p = self.push(parent);
        let ret = self.ensure_editable(self.r(p), self.r(e));
        let r = self.push(ret);
        let subidx = ((cnt - 1) >> level) & MASK;
        let to_insert = if level == BITS {
            self.r(t)
        } else {
            let child = self.node_get(self.r(r), subidx);
            if child.is_nil() {
                self.new_path(level - BITS, self.r(t), self.r(e))
            } else {
                self.t_push_tail(cnt, level - BITS, child, self.r(t), self.r(e))
            }
        };
        self.node_set(self.r(r), subidx, to_insert);
        let out = self.r(r);
        self.pop_to(base);
        out
    }

    pub fn tvec_conj(&mut self, t: Value, x: Value) -> Value {
        debug_assert!(self.tvec_alive(t));
        let base = self.mark();
        let ti = self.push(t);
        let xi = self.push(x);
        let cnt = self.tvec_count(t);
        if cnt - self.tvec_tail_off(t) < WIDTH {
            let tail = slot(&self.gc.sp, t.as_heap(), T_TAIL);
            let x = self.r(xi);
            self.node_set(tail, cnt & MASK, x);
            self.gc.set_slot(t.as_heap(), T_CNT, Value::fixnum(cnt as i64 + 1));
            self.pop_to(base);
            let _ = ti;
            return t; // nothing allocated on this path, so `t` cannot have moved
        }
        // Tail full: fold it into the trie and start a fresh one.
        let edit = slot(&self.gc.sp, t.as_heap(), T_EDIT);
        let ei = self.push(edit);
        let tailnode = slot(&self.gc.sp, self.r(ti).as_heap(), T_TAIL);
        let tn = self.push(tailnode);
        let newtail = self.new_node(WIDTH, self.r(ei));
        let nt = self.push(newtail);
        let x = self.r(xi);
        self.node_set(self.r(nt), 0, x);
        let tv = self.r(ti);
        let shift = self.tvec_shift(tv);
        let overflow = (cnt >> BITS) > (1u32 << shift);
        let (newroot, newshift) = if overflow {
            let nr = self.new_node(WIDTH, self.r(ei));
            let nri = self.push(nr);
            let oldroot = slot(&self.gc.sp, self.r(ti).as_heap(), T_ROOT);
            self.node_set(self.r(nri), 0, oldroot);
            let path = self.new_path(shift, self.r(tn), self.r(ei));
            self.node_set(self.r(nri), 1, path);
            (self.r(nri), shift + BITS)
        } else {
            let root = slot(&self.gc.sp, self.r(ti).as_heap(), T_ROOT);
            (self.t_push_tail(cnt, shift, root, self.r(tn), self.r(ei)), shift)
        };
        let (tv, newtail) = (self.r(ti), self.r(nt));
        let a = tv.as_heap();
        self.gc.set_slot(a, T_ROOT, newroot);
        self.gc.set_slot(a, T_SHIFT, Value::fixnum(newshift as i64));
        self.gc.set_slot(a, T_TAIL, newtail);
        self.gc.set_slot(a, T_CNT, Value::fixnum(cnt as i64 + 1));
        self.pop_to(base);
        tv
    }

    fn t_array_for(&self, t: Value, i: u32) -> Value {
        if i >= self.tvec_tail_off(t) {
            return slot(&self.gc.sp, t.as_heap(), T_TAIL);
        }
        let mut node = slot(&self.gc.sp, t.as_heap(), T_ROOT);
        let mut level = self.tvec_shift(t);
        while level > 0 {
            node = self.node_get(node, (i >> level) & MASK);
            level -= BITS;
        }
        node
    }

    pub fn tvec_nth(&self, t: Value, i: u32) -> Option<Value> {
        if i >= self.tvec_count(t) { return None; }
        let arr = self.t_array_for(t, i);
        Some(self.node_get(arr, i & MASK))
    }

    fn t_do_assoc(&mut self, level: u32, node: Value, i: u32, val: Value, edit: Value) -> Value {
        let base = self.mark();
        let e = self.push(edit);
        let v = self.push(val);
        let n = self.push(node);
        let ret = self.ensure_editable(self.r(n), self.r(e));
        let r = self.push(ret);
        if level == 0 {
            let val = self.r(v);
            self.node_set(self.r(r), i & MASK, val);
        } else {
            let subidx = (i >> level) & MASK;
            let child = self.node_get(self.r(r), subidx);
            let nc = self.t_do_assoc(level - BITS, child, i, self.r(v), self.r(e));
            self.node_set(self.r(r), subidx, nc);
        }
        let out = self.r(r);
        self.pop_to(base);
        out
    }

    pub fn tvec_assoc(&mut self, t: Value, i: u32, x: Value) -> Value {
        let cnt = self.tvec_count(t);
        if i == cnt { return self.tvec_conj(t, x); }
        debug_assert!(i < cnt);
        let base = self.mark();
        let ti = self.push(t);
        let xi = self.push(x);
        if i >= self.tvec_tail_off(t) {
            let tail = slot(&self.gc.sp, t.as_heap(), T_TAIL);
            let x = self.r(xi);
            self.node_set(tail, i & MASK, x);
        } else {
            let edit = slot(&self.gc.sp, t.as_heap(), T_EDIT);
            let root = slot(&self.gc.sp, t.as_heap(), T_ROOT);
            let shift = self.tvec_shift(t);
            let nr = self.t_do_assoc(shift, root, i, self.r(xi), edit);
            let tv = self.r(ti);
            self.gc.set_slot(tv.as_heap(), T_ROOT, nr);
        }
        let out = self.r(ti);
        self.pop_to(base);
        out
    }

    pub fn tvec_pop(&mut self, t: Value) -> Value {
        let cnt = self.tvec_count(t);
        debug_assert!(cnt > 0);
        let a = t.as_heap();
        if cnt == 1 {
            self.gc.set_slot(a, T_CNT, Value::fixnum(0));
            return t;
        }
        if (cnt - 1) & MASK > 0 {
            self.gc.set_slot(a, T_CNT, Value::fixnum(cnt as i64 - 1));
            return t;
        }
        // The tail is emptying: pull the previous leaf back out of the trie.
        let base = self.mark();
        let ti = self.push(t);
        let newtail = self.t_array_for(t, cnt - 2);
        let nt = self.push(newtail);
        let tv = self.r(ti);
        let shift = self.tvec_shift(tv);
        let root = slot(&self.gc.sp, tv.as_heap(), T_ROOT);
        let mut newroot = self.pop_tail(shift, root, cnt);
        let mut newshift = shift;
        if newroot.is_nil() {
            let edit = slot(&self.gc.sp, self.r(ti).as_heap(), T_EDIT);
            newroot = self.new_node(WIDTH, edit);
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
        let tv = self.r(ti);
        let a = tv.as_heap();
        let (nr, ntv) = (self.r(nri), self.r(nt));
        self.gc.set_slot(a, T_ROOT, nr);
        self.gc.set_slot(a, T_TAIL, ntv);
        self.gc.set_slot(a, T_SHIFT, Value::fixnum(newshift as i64));
        self.gc.set_slot(a, T_CNT, Value::fixnum(cnt as i64 - 1));
        self.pop_to(base);
        tv
    }

    pub fn tvec_persistent(&mut self, t: Value) -> Value {
        debug_assert!(self.tvec_alive(t));
        let base = self.mark();
        let ti = self.push(t);
        let cnt = self.tvec_count(t);
        let shift = self.tvec_shift(t);
        let tail_off = self.tvec_tail_off(t);
        let tail = slot(&self.gc.sp, t.as_heap(), T_TAIL);
        // Trim the 32-wide working tail down to what is actually used.
        let trimmed = self.node_clone(tail, cnt - tail_off, NIL);
        let tr = self.push(trimmed);
        let tv = self.r(ti);
        let root = slot(&self.gc.sp, tv.as_heap(), T_ROOT);
        let ri = self.push(root);
        // Invalidate the handle: using it afterwards is a bug, not a silent
        // mutation of a value somebody else now owns.
        self.gc.set_slot(tv.as_heap(), T_EDIT, NIL);
        let (root, trimmed) = (self.r(ri), self.r(tr));
        let out = self.new_vec(cnt, shift, root, trimmed, NIL);
        self.pop_to(base);
        out
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

#[cfg(test)]
mod transient_tests {
    use super::*;
    use alloc::vec::Vec as StdVec;

    fn tv_to_vec(rt: &Rt, t: Value) -> StdVec<i64> {
        (0..rt.tvec_count(t)).map(|i| rt.tvec_nth(t, i).unwrap().as_fixnum()).collect()
    }
    fn to_vec(rt: &Rt, v: Value) -> StdVec<i64> {
        (0..rt.vec_count(v)).map(|i| rt.vec_nth(v, i).unwrap().as_fixnum()).collect()
    }

    #[test]
    fn transient_conj_then_persistent_round_trips() {
        let mut rt = Rt::new();
        for n in [0u32, 1, 31, 32, 33, 1024, 1025, 5000] {
            let e = rt.empty_vec();
            let t = rt.vec_transient(e);
            let ti = rt.push(t);
            for i in 0..n {
                let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i as i64));
                rt.set_r(ti, nt);
            }
            assert_eq!(rt.tvec_count(rt.r(ti)), n);
            let v = rt.tvec_persistent(rt.r(ti));
            assert_eq!(rt.vec_count(v), n, "n={n}");
            assert_eq!(to_vec(&rt, v), (0..n as i64).collect::<StdVec<_>>(), "n={n}");
            rt.pop_to(ti);
        }
    }

    #[test]
    fn transient_does_not_disturb_the_source_vector() {
        let mut rt = Rt::new();
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..100 {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i));
            rt.set_r(vi, nv);
        }
        let t = rt.vec_transient(rt.r(vi));
        let ti = rt.push(t);
        for i in 0..100 {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(1000 + i));
            rt.set_r(ti, nt);
        }
        let nt = rt.tvec_assoc(rt.r(ti), 0, Value::fixnum(-5));
        rt.set_r(ti, nt);
        v = rt.r(vi);
        assert_eq!(rt.vec_count(v), 100, "source count unchanged");
        assert_eq!(to_vec(&rt, v), (0..100i64).collect::<StdVec<_>>(), "source contents unchanged");
        let out = rt.tvec_persistent(rt.r(ti));
        assert_eq!(rt.vec_count(out), 200);
        assert_eq!(rt.vec_nth(out, 0).unwrap().as_fixnum(), -5);
        assert_eq!(rt.vec_nth(out, 199).unwrap().as_fixnum(), 1099);
    }

    #[test]
    fn transient_assoc_and_pop() {
        let mut rt = Rt::new();
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        for i in 0..2000 {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i));
            rt.set_r(ti, nt);
        }
        for i in (0..2000).step_by(37) {
            let nt = rt.tvec_assoc(rt.r(ti), i as u32, Value::fixnum(-i));
            rt.set_r(ti, nt);
        }
        for i in (0..2000).step_by(37) {
            assert_eq!(rt.tvec_nth(rt.r(ti), i as u32).unwrap().as_fixnum(), -i);
        }
        for _ in 0..500 {
            let nt = rt.tvec_pop(rt.r(ti));
            rt.set_r(ti, nt);
        }
        assert_eq!(rt.tvec_count(rt.r(ti)), 1500);
        let v = rt.tvec_persistent(rt.r(ti));
        assert_eq!(rt.vec_count(v), 1500);
        assert_eq!(rt.vec_nth(v, 1499).unwrap().as_fixnum(), 1499);
        assert_eq!(rt.vec_nth(v, 37).unwrap().as_fixnum(), -37);
    }

    #[test]
    fn persistent_invalidates_the_handle() {
        let mut rt = Rt::new();
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(1));
        rt.set_r(ti, nt);
        assert!(rt.tvec_alive(rt.r(ti)));
        let _ = rt.tvec_persistent(rt.r(ti));
        assert!(!rt.tvec_alive(rt.r(ti)), "a used-up transient must be detectably dead");
    }

    #[test]
    fn persistent_result_is_independent_of_further_transient_use() {
        // The classic transient bug: `persistent!` hands back a value that a
        // still-live handle can mutate. It must not.
        let mut rt = Rt::new();
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        for i in 0..40 {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i));
            rt.set_r(ti, nt);
        }
        let v = rt.tvec_persistent(rt.r(ti));
        let vi = rt.push(v);
        // Re-transient the *result* and keep going; the first result must not move.
        let t2 = rt.vec_transient(rt.r(vi));
        let t2i = rt.push(t2);
        for i in 0..40 {
            let nt = rt.tvec_conj(rt.r(t2i), Value::fixnum(100 + i));
            rt.set_r(t2i, nt);
        }
        let nt = rt.tvec_assoc(rt.r(t2i), 0, Value::fixnum(-99));
        rt.set_r(t2i, nt);
        let _ = rt.tvec_persistent(rt.r(t2i));
        assert_eq!(to_vec(&rt, rt.r(vi)), (0..40i64).collect::<StdVec<_>>(),
                   "the earlier persistent result was mutated through a later transient");
    }

    #[test]
    fn transients_survive_collection_at_every_allocation() {
        let mut rt = Rt::new();
        rt.gc.stress = true;
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        for i in 0..300 {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i));
            rt.set_r(ti, nt);
        }
        assert_eq!(tv_to_vec(&rt, rt.r(ti)), (0..300i64).collect::<StdVec<_>>());
        let v = rt.tvec_persistent(rt.r(ti));
        let vi = rt.push(v);
        rt.collect();
        assert_eq!(to_vec(&rt, rt.r(vi)), (0..300i64).collect::<StdVec<_>>());
    }
}
