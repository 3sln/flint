//! Seqs: cons, the empty list, lazy seqs, and the views over vectors, strings
//! and ranges.
//!
//! ```text
//!   TY_CONS       [first, rest, meta, count]   count = fixnum, or nil if unknown
//!   TY_EMPTY_LIST [meta]
//!   TY_LAZYSEQ    [thunk, seq, meta]           thunk becomes nil once forced
//!   TY_VECSEQ     [vec, index, meta]
//!   TY_STRSEQ     [str, byte-index, meta]      yields one-character strings
//!   TY_RANGE      [start, end, step, meta]
//! ```
//!
//! `seq` over a map or a set materialises the entries into a vector once and
//! then walks that. It is O(n) at the first `seq` and O(1) per step thereafter.
//! This is a real trade: the alternative is a stateful trie cursor object per
//! seq. Bulk operations (`reduce`, `into`, `merge`, `count`) walk the trie
//! directly and never build the vector, so the materialising path is only hit by
//! code that genuinely asks for a sequence view.

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, NIL};

pub const C_FIRST: u32 = 0;
pub const C_REST: u32 = 1;
pub const C_META: u32 = 2;
pub const C_COUNT: u32 = 3;

pub const LS_THUNK: u32 = 0;
pub const LS_SEQ: u32 = 1;

impl Rt {
    pub fn cons(&mut self, head: Value, tail: Value) -> Value {
        let base = self.mark();
        let h = self.push(head);
        let t = self.push(tail);
        let a = self.alloc(TY_CONS, 4);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (head, tail) = (self.r(h), self.r(t));
        self.pop_to(base);
        let cnt = match self.count_hint(tail) {
            Some(n) => Value::fixnum(n as i64 + 1),
            None => NIL,
        };
        self.gc.set_slot(a, C_FIRST, head);
        self.gc.set_slot(a, C_REST, tail);
        self.gc.set_slot(a, C_META, NIL);
        self.gc.set_slot(a, C_COUNT, cnt);
        Value::heap(a)
    }

    /// A count if it is known without walking, else `None`.
    fn count_hint(&self, v: Value) -> Option<u32> {
        if v.is_nil() {
            return Some(0);
        }
        if !v.is_heap() {
            return None;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_EMPTY_LIST => Some(0),
            TY_CONS => {
                let c = slot(&self.gc.sp, v.as_heap(), C_COUNT);
                if c.is_fixnum() {
                    Some(c.as_fixnum() as u32)
                } else {
                    None
                }
            }
            TY_VEC => Some(self.vec_count(v)),
            _ => None,
        }
    }

    pub fn is_seq(&self, v: Value) -> bool {
        v.is_heap()
            && matches!(
                ty(&self.gc.sp, v.as_heap()),
                TY_CONS | TY_EMPTY_LIST | TY_LAZYSEQ | TY_VECSEQ | TY_STRSEQ | TY_RANGE
            )
    }

    /// True for anything `seq` accepts.
    pub fn is_seqable(&self, v: Value) -> bool {
        v.is_nil()
            || self.is_string(v)
            || (v.is_heap()
                && matches!(
                    ty(&self.gc.sp, v.as_heap()),
                    TY_CONS
                        | TY_EMPTY_LIST
                        | TY_LAZYSEQ
                        | TY_VECSEQ
                        | TY_STRSEQ
                        | TY_RANGE
                        | TY_VEC
                        | TY_MAPENTRY
                        | TY_ARRAYMAP
                        | TY_HASHMAP
                        | TY_SET
                ))
    }

    fn vecseq(&mut self, v: Value, i: u32) -> Value {
        let base = self.mark();
        let vi = self.push(v);
        let a = self.alloc(TY_VECSEQ, 3);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let v = self.r(vi);
        self.pop_to(base);
        self.gc.set_slot(a, 0, v);
        self.gc.set_slot(a, 1, Value::fixnum(i as i64));
        self.gc.set_slot(a, 2, NIL);
        Value::heap(a)
    }

    fn strseq(&mut self, s: Value, i: u32) -> Value {
        let base = self.mark();
        let si = self.push(s);
        let a = self.alloc(TY_STRSEQ, 3);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let s = self.r(si);
        self.pop_to(base);
        self.gc.set_slot(a, 0, s);
        self.gc.set_slot(a, 1, Value::fixnum(i as i64));
        self.gc.set_slot(a, 2, NIL);
        Value::heap(a)
    }

    pub fn range(&mut self, start: Value, end: Value, step: Value) -> Value {
        let base = self.mark();
        let s = self.push(start);
        let e = self.push(end);
        let st = self.push(step);
        let a = self.alloc(TY_RANGE, 4);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (start, end, step) = (self.r(s), self.r(e), self.r(st));
        self.pop_to(base);
        self.gc.set_slot(a, 0, start);
        self.gc.set_slot(a, 1, end);
        self.gc.set_slot(a, 2, step);
        self.gc.set_slot(a, 3, NIL);
        Value::heap(a)
    }

    fn range_empty(&self, v: Value) -> bool {
        let (s, e, st) = (self.slot(v, 0), self.slot(v, 1), self.slot(v, 2));
        if e.is_nil() {
            return false; // unbounded
        }
        let (s, e, st) = (self.num_f64(s), self.num_f64(e), self.num_f64(st));
        if st > 0.0 {
            s >= e
        } else if st < 0.0 {
            s <= e
        } else {
            true
        }
    }

    /// `seq`: nil for an empty collection, otherwise a seq object.
    pub fn seq(&mut self, v: Value) -> Value {
        if v.is_nil() {
            return NIL;
        }
        if self.is_string(v) {
            return if self.str_len(v) == 0 { NIL } else { self.strseq(v, 0) };
        }
        if !v.is_heap() {
            return NIL;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_EMPTY_LIST => NIL,
            TY_CONS | TY_VECSEQ | TY_STRSEQ => v,
            TY_RANGE => {
                if self.range_empty(v) {
                    NIL
                } else {
                    v
                }
            }
            TY_LAZYSEQ => {
                let s = self.force(v);
                if s.is_nil() {
                    NIL
                } else {
                    self.seq(s)
                }
            }
            TY_VEC => {
                if self.vec_count(v) == 0 {
                    NIL
                } else {
                    self.vecseq(v, 0)
                }
            }
            TY_MAPENTRY => self.vecseq(v, 0),
            TY_ARRAYMAP | TY_HASHMAP => {
                let ents = self.map_entry_vector(v);
                if ents.is_nil() || self.vec_count(ents) == 0 {
                    NIL
                } else {
                    self.vecseq(ents, 0)
                }
            }
            TY_SET => {
                let ents = self.set_element_vector(v);
                if ents.is_nil() || self.vec_count(ents) == 0 {
                    NIL
                } else {
                    self.vecseq(ents, 0)
                }
            }
            _ => NIL,
        }
    }

    pub fn first(&mut self, v: Value) -> Value {
        let s = self.seq(v);
        if s.is_nil() {
            return NIL;
        }
        match ty(&self.gc.sp, s.as_heap()) {
            TY_CONS => self.slot(s, C_FIRST),
            TY_VECSEQ => {
                let (vec, i) = (self.slot(s, 0), self.slot(s, 1).as_fixnum() as u32);
                if self.ty(vec) == TY_MAPENTRY {
                    self.slot(vec, i)
                } else {
                    self.vec_nth(vec, i).unwrap_or(NIL)
                }
            }
            TY_STRSEQ => {
                let (st, i) = (self.slot(s, 0), self.slot(s, 1).as_fixnum() as u32);
                self.char_at_byte(st, i)
            }
            TY_RANGE => self.slot(s, 0),
            _ => NIL,
        }
    }

    /// `rest`: always a seq-able, never nil (Clojure returns `()`).
    pub fn rest(&mut self, v: Value) -> Value {
        let n = self.next(v);
        if n.is_nil() {
            self.empty_list()
        } else {
            n
        }
    }

    /// `next`: nil when exhausted.
    pub fn next(&mut self, v: Value) -> Value {
        let s = self.seq(v);
        if s.is_nil() {
            return NIL;
        }
        match ty(&self.gc.sp, s.as_heap()) {
            TY_CONS => {
                let r = self.slot(s, C_REST);
                self.seq(r)
            }
            TY_VECSEQ => {
                let (vec, i) = (self.slot(s, 0), self.slot(s, 1).as_fixnum() as u32);
                let n = if self.ty(vec) == TY_MAPENTRY { 2 } else { self.vec_count(vec) };
                if i + 1 >= n {
                    NIL
                } else {
                    self.vecseq(vec, i + 1)
                }
            }
            TY_STRSEQ => {
                let (st, i) = (self.slot(s, 0), self.slot(s, 1).as_fixnum() as u32);
                let w = self.char_width_at(st, i);
                if i + w >= self.str_len(st) {
                    NIL
                } else {
                    self.strseq(st, i + w)
                }
            }
            TY_RANGE => {
                let (st, e, step) = (self.slot(s, 0), self.slot(s, 1), self.slot(s, 2));
                let nstart = self.num_add(st, step);
                let r = self.range(nstart, e, step);
                self.seq(r)
            }
            _ => NIL,
        }
    }

    /// Force a lazy seq. Requires the VM, so it is a no-op shell until the VM
    /// installs itself; see `vm::install_forcer`.
    pub fn force(&mut self, ls: Value) -> Value {
        let thunk = self.slot(ls, LS_THUNK);
        if thunk.is_nil() {
            return self.slot(ls, LS_SEQ);
        }
        let base = self.mark();
        let li = self.push(ls);
        let v = self.invoke(thunk, &[]);
        let vi = self.push(v);
        // Chained lazy seqs: keep forcing until we reach something concrete.
        let mut cur = self.r(vi);
        while cur.is_heap() && ty(&self.gc.sp, cur.as_heap()) == TY_LAZYSEQ {
            let t2 = self.slot(cur, LS_THUNK);
            if t2.is_nil() {
                cur = self.slot(cur, LS_SEQ);
                break;
            }
            self.set_r(vi, cur);
            let nv = self.invoke(t2, &[]);
            cur = nv;
        }
        let ls = self.r(li);
        self.pop_to(base);
        self.gc.set_slot(ls.as_heap(), LS_THUNK, NIL);
        self.gc.set_slot(ls.as_heap(), LS_SEQ, cur);
        cur
    }

    pub fn lazy_seq(&mut self, thunk: Value) -> Value {
        let base = self.mark();
        let t = self.push(thunk);
        let a = self.alloc(TY_LAZYSEQ, 3);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let thunk = self.r(t);
        self.pop_to(base);
        self.gc.set_slot(a, LS_THUNK, thunk);
        self.gc.set_slot(a, LS_SEQ, NIL);
        self.gc.set_slot(a, 2, NIL);
        Value::heap(a)
    }

    // --- string character access ------------------------------------------

    fn char_width_at(&self, s: Value, byte: u32) -> u32 {
        let mut buf = crate::rt::sbuf();
        let b0 = if s.is_inline_str() {
            s.inline_bytes(&mut buf)[byte as usize]
        } else {
            self.gc.sp.read_u8(s.as_heap() + STR_DATA + byte)
        };
        if b0 < 0x80 {
            1
        } else if b0 < 0xE0 {
            2
        } else if b0 < 0xF0 {
            3
        } else {
            4
        }
    }

    /// The one-character string at byte offset `byte`.
    pub fn char_at_byte(&mut self, s: Value, byte: u32) -> Value {
        let w = self.char_width_at(s, byte);
        let mut buf = crate::rt::sbuf();
        if s.is_inline_str() {
            let b = s.inline_bytes(&mut buf);
            Value::inline_str(&b[byte as usize..(byte + w) as usize])
        } else {
            let b = self.gc.sp.bytes(s.as_heap() + STR_DATA + byte, w);
            let mut tmp = [0u8; 4];
            tmp[..w as usize].copy_from_slice(b);
            Value::inline_str(&tmp[..w as usize])
        }
    }

    /// Walk a seq to its length. `count` on a counted collection does not use this.
    pub fn seq_count(&mut self, v: Value) -> u32 {
        if let Some(n) = self.count_hint(v) {
            return n;
        }
        let base = self.mark();
        let mut n = 0u32;
        let cur = self.seq(v);
        let ci = self.push(cur);
        while !self.r(ci).is_nil() {
            n += 1;
            let nx = self.next(self.r(ci));
            self.set_r(ci, nx);
        }
        self.pop_to(base);
        n
    }

    /// Build a list from values already on the shadow stack, last-first.
    pub fn list_from_roots(&mut self, base: usize, n: usize) -> Value {
        let acc = self.empty_list();
        let ai = self.push(acc);
        for i in (0..n).rev() {
            let x = self.r(base + i);
            let c = self.cons(x, self.r(ai));
            self.set_r(ai, c);
        }
        let out = self.r(ai);
        self.pop_to(ai);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rt::sbuf;
    use alloc::vec::Vec as StdVec;

    fn ints(rt: &mut Rt, s: Value) -> StdVec<i64> {
        let base = rt.mark();
        let cur = rt.seq(s);
        let si = rt.push(cur);
        let mut out = StdVec::new();
        while !rt.r(si).is_nil() {
            out.push(rt.first(rt.r(si)).as_fixnum());
            let n = rt.next(rt.r(si));
            rt.set_r(si, n);
        }
        rt.pop_to(base);
        out
    }

    #[test]
    fn empty_list_is_seqable_but_seqs_to_nil() {
        let mut rt = Rt::new();
        let e = rt.empty_list();
        assert!(rt.is_seq(e));
        assert!(rt.seq(e).is_nil(), "an empty collection seqs to nil");
        assert!(rt.first(e).is_nil());
        assert_eq!(rt.rest(e), e, "rest of empty is empty, not nil");
        assert!(rt.next(e).is_nil(), "next of empty is nil, not empty");
    }

    #[test]
    fn cons_builds_a_counted_list() {
        let mut rt = Rt::new();
        let base = rt.mark();
        for i in 0..5 {
            rt.push(Value::fixnum(i));
        }
        let l = rt.list_from_roots(base, 5);
        rt.pop_to(base);
        let li = rt.push(l);
        let l0 = rt.r(li);
        assert_eq!(ints(&mut rt, l0), alloc::vec![0, 1, 2, 3, 4]);
        let l0 = rt.r(li);
        assert_eq!(rt.seq_count(l0), 5, "count is cached, not walked");
        let l0 = rt.r(li);
        let bigger = rt.cons(Value::fixnum(-1), l0);
        assert_eq!(rt.seq_count(bigger), 6);
    }

    #[test]
    fn seq_over_a_vector_walks_it_in_order() {
        let mut rt = Rt::new();
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..100 {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i));
            rt.set_r(vi, nv);
        }
        v = rt.r(vi);
        assert_eq!(ints(&mut rt, v), (0..100i64).collect::<StdVec<_>>());
    }

    #[test]
    fn seq_over_a_string_yields_one_character_strings() {
        let mut rt = Rt::new();
        // Deliberately multi-byte, so the byte/char distinction is exercised.
        let s = rt.string("aé日");
        let base = rt.mark();
        let cur = rt.seq(s);
        let si = rt.push(cur);
        let mut got = alloc::string::String::new();
        let mut n = 0;
        while !rt.r(si).is_nil() {
            let c = rt.first(rt.r(si));
            let mut b = sbuf();
            got.push_str(rt.as_str(c, &mut b).unwrap());
            n += 1;
            let nx = rt.next(rt.r(si));
            rt.set_r(si, nx);
        }
        rt.pop_to(base);
        assert_eq!(n, 3, "three characters, not six bytes");
        assert_eq!(got, "aé日");
        let empty = rt.string("");
        assert!(rt.seq(empty).is_nil());
    }

    #[test]
    fn ranges_are_walked_lazily_and_respect_step() {
        let mut rt = Rt::new();
        let r = rt.range(Value::fixnum(0), Value::fixnum(5), Value::fixnum(1));
        assert_eq!(ints(&mut rt, r), alloc::vec![0, 1, 2, 3, 4]);
        let r = rt.range(Value::fixnum(0), Value::fixnum(6), Value::fixnum(2));
        assert_eq!(ints(&mut rt, r), alloc::vec![0, 2, 4]);
        let r = rt.range(Value::fixnum(5), Value::fixnum(0), Value::fixnum(-2));
        assert_eq!(ints(&mut rt, r), alloc::vec![5, 3, 1]);
        let r = rt.range(Value::fixnum(3), Value::fixnum(3), Value::fixnum(1));
        assert!(rt.seq(r).is_nil(), "an empty range seqs to nil");
    }

    #[test]
    fn seq_over_a_map_visits_every_entry_once() {
        let mut rt = Rt::new();
        let mut m = rt.empty_map();
        let mi = rt.push(m);
        for i in 0..50i64 {
            let nm = rt.map_assoc(rt.r(mi), Value::fixnum(i), Value::fixnum(i * 3));
            rt.set_r(mi, nm);
        }
        m = rt.r(mi);
        let base = rt.mark();
        let cur = rt.seq(m);
        let si = rt.push(cur);
        let mut keys = StdVec::new();
        while !rt.r(si).is_nil() {
            let e = rt.first(rt.r(si));
            let (k, v) = (rt.slot(e, 0), rt.slot(e, 1));
            assert_eq!(v.as_fixnum(), k.as_fixnum() * 3);
            keys.push(k.as_fixnum());
            let n = rt.next(rt.r(si));
            rt.set_r(si, n);
        }
        rt.pop_to(base);
        keys.sort();
        assert_eq!(keys, (0..50i64).collect::<StdVec<_>>());
    }

    #[test]
    fn seqs_survive_collection_mid_walk() {
        let mut rt = Rt::with_heap(64 * 1024, 64 * 1024 * 1024);
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..5000 {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i));
            rt.set_r(vi, nv);
        }
        v = rt.r(vi);
        let base = rt.mark();
        let cur = rt.seq(v);
        let si = rt.push(cur);
        let mut n = 0i64;
        while !rt.r(si).is_nil() {
            assert_eq!(rt.first(rt.r(si)).as_fixnum(), n);
            n += 1;
            // Allocate hard enough to force collections while the seq is live.
            let _ = rt.string(&alloc::format!("garbage {n}"));
            let nx = rt.next(rt.r(si));
            rt.set_r(si, nx);
        }
        rt.pop_to(base);
        assert_eq!(n, 5000);
    }
}
