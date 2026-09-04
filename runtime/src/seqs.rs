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
    pub fn seq(&mut self, v: Value) -> Value {
        if v.is_nil() {
            return NIL;
        }
        if self.is_string(v) {
            // CODE POINTS, matching the index the strseq now holds.
            return if self.char_count(v) == 0 { NIL } else { self.strseq(v, 0) };
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
            // Iterating a table hands back REFS, one per row, materialising
            // nothing -- which is the point of the ref type and not a detail of
            // it (`doc/decisions/0026`). It rides on `TY_VECSEQ` because a
            // table is indexed and counted exactly as a vector is; only `first`
            // differs, and it differs by calling `table_ref`.
            crate::obj::TY_TABLE => {
                if self.table_count(v) == 0 {
                    NIL
                } else {
                    self.vecseq(v, 0)
                }
            }
            // A ref materialises HERE and only here: `seq`, `=` and `hash` all
            // want the whole row, and each is O(columns) anyway. The paths that
            // must stay cheap -- `get` and `(:name row)` -- never come through.
            crate::obj::TY_TABLEREF => {
                let m = self.ref_to_map(v);
                self.seq(m)
            }
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
            // REFUSED, not nil. This answered NIL and both ports threw, so
            // `(seq (atom 1))` was nil here and an exception there -- a
            // reachable divergence, and the ports are the ones matching
            // Clojure, which refuses anything it cannot make an ISeq from.
            //
            // The KIND matches the ports exactly, which is what a `catch`
            // dispatches on. The MESSAGE does not: theirs names the value
            // through `describe`, and saying that here needs string building,
            // which is the capability `seq` is waiting on to be generated at
            // all.
            _ => self.throw_str(
                "UnsupportedOperationException",
                "seq over this type needs more of the data structures",
            ),
        }
    }





    // --- string character access ------------------------------------------
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
