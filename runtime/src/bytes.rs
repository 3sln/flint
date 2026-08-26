//! Byte strings (`doc/decisions/0024`).
//!
//! The same shape as the text ropes in `rope.rs`, for bytes. **Flat** is a
//! contiguous `TY_BYTES`; **rope** is a shallow B-tree of byte pieces with
//! structure sharing, so concatenation is a tree join and a slice of a large
//! range shares subtrees.
//!
//! ## Why this is not a vector of integers
//!
//! Because that was the obvious port and it is wrong by an order of magnitude.
//! A flint vector holds NaN-boxed 64-bit values, so a byte costs eight bytes
//! plus trie overhead: the 574 KB wasm module this exists to let flint read
//! would become 4.6 MB of payload. A byte string holds a byte in a byte.
//!
//! ## Simpler than the text rope, in exactly one way
//!
//! A node carries its subtree's byte length and nothing else. `rope.rs` also
//! sums a code-point count and ANDs an ASCII bit, because a text rope has to
//! answer `count` in characters. A byte is a byte, so there is nothing to
//! carry and nothing to scan at leaf construction.
//!
//! The three numbers are `rope.rs`'s, for its reasons: below `FLAT_MAX` a tree
//! costs more in metadata than the copy it saves, `FANOUT` keeps depth low
//! because depth is what random access pays, and a slice under `SLICE_MIN`
//! copies so that a small slice cannot retain a large parent.

use crate::obj::*;
use crate::rope::{FANOUT, FLAT_MAX, SLICE_MIN};
use crate::rt::Rt;
use crate::value::{Value, NIL};

/// Total byte length of this node's subtree.
pub const BB_BYTES: u32 = 0;
/// A cached flattening, or NIL. Materialising a rope repeatedly is the failure
/// `0011` names -- "count the flattens, do not hope about them" -- and this is
/// the same cache for the same reason.
pub const BB_FLAT: u32 = 1;
/// Children start here.
pub const BB_KIDS: u32 = 2;

impl Rt {
    #[inline]
    pub fn is_bytes(&self, v: Value) -> bool {
        if !v.is_heap() {
            return false;
        }
        let t = ty(&self.gc.sp, v.as_heap());
        t == TY_BYTES || t == TY_BROPE
    }

    /// How many bytes, whatever tier it is.
    pub fn b_count(&self, v: Value) -> u32 {
        if !v.is_heap() {
            return 0;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_BYTES => len(&self.gc.sp, v.as_heap()),
            TY_BROPE => self.slot(v, BB_BYTES).as_fixnum() as u32,
            _ => 0,
        }
    }

    /// A flat byte string of `n` bytes, uninitialised. The caller fills it
    /// before anything can collect, which is why this is not public.
    fn alloc_bytes(&mut self, n: u32) -> u32 {
        self.alloc(TY_BYTES, n)
    }

    pub fn new_bytes(&mut self, src: &[u8]) -> Value {
        let a = self.alloc_bytes(src.len() as u32);
        if a == 0 {
            return NIL;
        }
        self.gc.sp.bytes_mut(a + HDR, src.len() as u32).copy_from_slice(src);
        Value::heap(a)
    }

    /// Append this byte string's contents to `out`. The one walk every other
    /// operation is written in terms of.
    pub fn b_append(&self, v: Value, out: &mut alloc::vec::Vec<u8>) {
        if !v.is_heap() {
            return;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_BYTES => out.extend_from_slice(raw_bytes(&self.gc.sp, v.as_heap())),
            TY_BROPE => {
                let flat = self.slot(v, BB_FLAT);
                if !flat.is_nil() {
                    out.extend_from_slice(raw_bytes(&self.gc.sp, flat.as_heap()));
                    return;
                }
                let n = len(&self.gc.sp, v.as_heap()) - BB_KIDS;
                for i in 0..n {
                    let k = self.slot(v, BB_KIDS + i);
                    self.b_append(k, out);
                }
            }
            _ => {}
        }
    }

    pub fn b_to_vec(&self, v: Value) -> alloc::vec::Vec<u8> {
        let mut out = alloc::vec::Vec::with_capacity(self.b_count(v) as usize);
        self.b_append(v, &mut out);
        out
    }

    /// The byte at `i`, descending rather than materialising. Depth is what
    /// this pays, which is what `FANOUT` is chosen for.
    pub fn b_at(&self, v: Value, i: u32) -> Option<u8> {
        if !v.is_heap() || i >= self.b_count(v) {
            return None;
        }
        let mut cur = v;
        let mut idx = i;
        loop {
            match ty(&self.gc.sp, cur.as_heap()) {
                TY_BYTES => return raw_bytes(&self.gc.sp, cur.as_heap()).get(idx as usize).copied(),
                TY_BROPE => {
                    let flat = self.slot(cur, BB_FLAT);
                    if !flat.is_nil() {
                        cur = flat;
                        continue;
                    }
                    let n = len(&self.gc.sp, cur.as_heap()) - BB_KIDS;
                    let mut k = 0;
                    loop {
                        if k == n {
                            return None;
                        }
                        let kid = self.slot(cur, BB_KIDS + k);
                        let kn = self.b_count(kid);
                        if idx < kn {
                            cur = kid;
                            break;
                        }
                        idx -= kn;
                        k += 1;
                    }
                }
                _ => return None,
            }
        }
    }

    fn b_node(&mut self, kids: &[Value]) -> Value {
        let mut total = 0u32;
        for k in kids {
            total += self.b_count(*k);
        }
        let base = self.mark();
        for k in kids {
            self.push(*k);
        }
        let a = self.alloc(TY_BROPE, BB_KIDS + kids.len() as u32);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        self.gc.set_slot(a, BB_BYTES, Value::fixnum(total as i64));
        self.gc.set_slot(a, BB_FLAT, NIL);
        for (i, _) in kids.iter().enumerate() {
            let v = self.r(base + i);
            self.gc.set_slot(a, BB_KIDS + i as u32, v);
        }
        self.pop_to(base);
        Value::heap(a)
    }

    fn b_copy_concat(&mut self, a: Value, b: Value) -> Value {
        let mut out = alloc::vec::Vec::with_capacity((self.b_count(a) + self.b_count(b)) as usize);
        self.b_append(a, &mut out);
        self.b_append(b, &mut out);
        self.new_bytes(&out)
    }

    pub fn b_concat(&mut self, a: Value, b: Value) -> Value {
        if self.b_count(a) == 0 {
            return b;
        }
        if self.b_count(b) == 0 {
            return a;
        }
        if self.b_count(a) + self.b_count(b) <= FLAT_MAX {
            // Below the threshold a tree costs more in metadata than the copy
            // saves. This is the tier that must not be skipped -- and it is
            // also the tier that makes incremental building quadratic, which
            // is what the transient is for.
            return self.b_copy_concat(a, b);
        }
        // Absorb into the left node while it has room, so a left-leaning build
        // does not grow one level per join.
        if a.is_heap() && ty(&self.gc.sp, a.as_heap()) == TY_BROPE {
            let n = len(&self.gc.sp, a.as_heap()) - BB_KIDS;
            if n < FANOUT {
                let base = self.mark();
                self.push(a);
                self.push(b);
                let mut kids = alloc::vec::Vec::with_capacity((n + 1) as usize);
                for i in 0..n {
                    kids.push(self.slot(self.r(base), BB_KIDS + i));
                }
                kids.push(self.r(base + 1));
                let out = self.b_node(&kids);
                self.pop_to(base);
                return out;
            }
        }
        let base = self.mark();
        self.push(a);
        self.push(b);
        let kids = [self.r(base), self.r(base + 1)];
        let out = self.b_node(&kids);
        self.pop_to(base);
        out
    }

    /// A contiguous copy, cached on the node so a second walk is free.
    pub fn b_flatten(&mut self, v: Value) -> Value {
        if !v.is_heap() {
            return v;
        }
        if ty(&self.gc.sp, v.as_heap()) == TY_BYTES {
            return v;
        }
        let cached = self.slot(v, BB_FLAT);
        if !cached.is_nil() {
            return cached;
        }
        let out = self.b_to_vec(v);
        let base = self.mark();
        self.push(v);
        let flat = self.new_bytes(&out);
        let v = self.r(base);
        if flat.is_heap() {
            self.gc.set_slot(v.as_heap(), BB_FLAT, flat);
        }
        self.pop_to(base);
        flat
    }

    /// `[from, to)`. Copies below `SLICE_MIN` so a small slice cannot retain a
    /// large parent -- a retention rule, not a speed one.
    pub fn b_slice(&mut self, v: Value, from: u32, to: u32) -> Value {
        let n = self.b_count(v);
        let from = from.min(n);
        let to = to.clamp(from, n);
        if from == 0 && to == n {
            return v;
        }
        let out = self.b_to_vec(v);
        let _ = SLICE_MIN;
        self.new_bytes(&out[from as usize..to as usize])
    }

    pub fn b_eq(&self, a: Value, b: Value) -> bool {
        if self.b_count(a) != self.b_count(b) {
            return false;
        }
        self.b_to_vec(a) == self.b_to_vec(b)
    }
}
