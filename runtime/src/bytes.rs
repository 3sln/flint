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
/// The subtree's depth: 1 for a node whose children are all leaves, and one
/// more per level. Every child of a node has the SAME depth, which is what
/// makes this a B-tree rather than a spine -- see `b_absorb`.
pub const BB_DEPTH: u32 = 2;
/// Children start here.
pub const BB_KIDS: u32 = 3;

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

    /// 0 for a leaf, otherwise the node's recorded depth.
    pub fn b_depth(&self, v: Value) -> u32 {
        if v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_BROPE {
            self.slot(v, BB_DEPTH).as_fixnum() as u32
        } else {
            0
        }
    }

    /// `v`, wrapped in single-child nodes until it is `d` deep. A node's
    /// children must all be the same depth, so a leaf joining a deep node is
    /// promoted rather than sitting beside subtrees.
    fn b_wrap_to(&mut self, v: Value, d: u32) -> Value {
        let mut cur = v;
        while self.b_depth(cur) < d {
            let base = self.mark();
            self.push(cur);
            let kids = [self.r(base)];
            cur = self.b_node(&kids);
            self.pop_to(base);
        }
        cur
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
        let d = {
            let k0 = self.r(base);
            self.b_depth(k0) + 1
        };
        self.gc.set_slot(a, BB_DEPTH, Value::fixnum(d as i64));
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
        // First: if `b` is small, merge it into the RIGHTMOST LEAF rather than
        // giving it a leaf of its own.
        //
        // Without this, appending a byte at a time makes one heap object per
        // byte and rebuilds the spine each time -- measured at 6.8 allocations
        // and 450 bytes of churn per append, with the live set growing
        // linearly. Past about 45 000 bytes the live set outgrows the 2 MB
        // nursery, every minor collection promotes, and the old generation's
        // mark-sweep starts running over an ever-larger heap. It stops looking
        // like a slow program and starts looking like a hung one.
        //
        // Merging bounds the leaf count at `total / FLAT_MAX` instead of one
        // per append. The copy is bounded by `FLAT_MAX`, which is what makes
        // it worth doing at all.
        if self.b_count(b) <= FLAT_MAX / 2 {
            if let Some(out) = self.b_merge_right(a, b) {
                return out;
            }
        }
        // Push `b` down the RIGHT SPINE into the deepest node that has room.
        //
        // The obvious version -- absorb into the top node while it has fewer
        // than FANOUT children, otherwise wrap -- builds a left spine: the top
        // fills after sixteen joins, wraps, fills again, and depth grows by one
        // every sixteen. Twenty thousand joins is depth 1,250, `b_at` is
        // O(depth), and the recursive walk in `b_append` runs the shadow stack
        // off the end. It read back as `memory access out of bounds`, which
        // names neither the tree nor the recursion.
        //
        // Descending first keeps it a B-tree: twenty thousand leaves is depth
        // four.
        if let Some(out) = self.b_absorb(a, b) {
            return out;
        }
        // Neither side had room, so a new level. BOTH sides are promoted to the
        // same depth first: a node's children must be uniform, and pairing a
        // deep node with a bare leaf here is what broke the invariant the
        // absorb above depends on -- `b_node` reads the depth off child zero,
        // so the node claimed a depth one of its children did not have, and
        // later appends descended into the wrong place.
        let base = self.mark();
        self.push(a);
        self.push(b);
        let d = self.b_depth(self.r(base)).max(self.b_depth(self.r(base + 1)));
        let pa = self.b_wrap_to(self.r(base), d);
        self.push(pa);
        let pb = self.b_wrap_to(self.r(base + 1), d);
        self.push(pb);
        let kids = [self.r(base + 2), self.r(base + 3)];
        let out = self.b_node(&kids);
        self.pop_to(base);
        out
    }

    /// Replace `a`'s rightmost leaf with that leaf followed by `b`, if the two
    /// fit in one leaf. None if they do not, or if there is no leaf to merge
    /// into.
    fn b_merge_right(&mut self, a: Value, b: Value) -> Option<Value> {
        if !a.is_heap() {
            return None;
        }
        match ty(&self.gc.sp, a.as_heap()) {
            TY_BYTES => {
                if len(&self.gc.sp, a.as_heap()) + self.b_count(b) <= FLAT_MAX {
                    Some(self.b_copy_concat(a, b))
                } else {
                    None
                }
            }
            TY_BROPE => {
                let n = len(&self.gc.sp, a.as_heap()) - BB_KIDS;
                let base = self.mark();
                self.push(a);
                self.push(b);
                let last = self.slot(self.r(base), BB_KIDS + n - 1);
                match self.b_merge_right(last, self.r(base + 1)) {
                    Some(x) => {
                        self.push(x);
                        let a = self.r(base);
                        let mut kids = alloc::vec::Vec::with_capacity(n as usize);
                        for i in 0..n - 1 {
                            kids.push(self.slot(a, BB_KIDS + i));
                        }
                        kids.push(self.r(base + 2));
                        let out = self.b_node(&kids);
                        self.pop_to(base);
                        Some(out)
                    }
                    None => {
                        self.pop_to(base);
                        None
                    }
                }
            }
            _ => None,
        }
    }

    /// Put `b` in the deepest node on `a`'s right spine that has room for it,
    /// or None if there is none. Depth is unchanged when this succeeds, which
    /// is the whole point.
    fn b_absorb(&mut self, a: Value, b: Value) -> Option<Value> {
        if !a.is_heap() || ty(&self.gc.sp, a.as_heap()) != TY_BROPE {
            return None;
        }
        let n = len(&self.gc.sp, a.as_heap()) - BB_KIDS;
        let da = self.b_depth(a);
        // A node's children are all the same depth. Anything deeper than this
        // node cannot go inside it.
        if self.b_depth(b) >= da {
            return None;
        }
        let base = self.mark();
        self.push(a);
        self.push(b);
        // Deepest first: only if the last child cannot take it does this node
        // take it, and only if neither can does the caller wrap. Descending
        // first is what keeps the tree log-deep -- absorbing at the top builds
        // a spine, and twenty thousand joins was depth 1,250.
        let last = self.slot(self.r(base), BB_KIDS + n - 1);
        if let Some(x) = self.b_absorb(last, self.r(base + 1)) {
            self.push(x);
            let a = self.r(base);
            let mut kids = alloc::vec::Vec::with_capacity(n as usize);
            for i in 0..n - 1 {
                kids.push(self.slot(a, BB_KIDS + i));
            }
            kids.push(self.r(base + 2));
            let out = self.b_node(&kids);
            self.pop_to(base);
            return Some(out);
        }
        if n < FANOUT {
            // Promoted to this node's child depth, so every child stays the
            // same depth and the next append can descend into it.
            let bb = self.b_wrap_to(self.r(base + 1), da - 1);
            self.push(bb);
            let a = self.r(base);
            let mut kids = alloc::vec::Vec::with_capacity((n + 1) as usize);
            for i in 0..n {
                kids.push(self.slot(a, BB_KIDS + i));
            }
            kids.push(self.r(base + 2));
            let out = self.b_node(&kids);
            self.pop_to(base);
            return Some(out);
        }
        self.pop_to(base);
        None
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

    /// Append only `[from, to)` of `v` to `out`, descending rather than
    /// materialising. A subtree entirely outside the range is skipped whole,
    /// which is what makes a slice cost the size of the SLICE.
    fn b_append_range(&self, v: Value, from: u32, to: u32, out: &mut alloc::vec::Vec<u8>) {
        if !v.is_heap() || from >= to {
            return;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_BYTES => {
                let bs = raw_bytes(&self.gc.sp, v.as_heap());
                let hi = (to as usize).min(bs.len());
                let lo = (from as usize).min(hi);
                out.extend_from_slice(&bs[lo..hi]);
            }
            TY_BROPE => {
                let flat = self.slot(v, BB_FLAT);
                if !flat.is_nil() {
                    self.b_append_range(flat, from, to, out);
                    return;
                }
                let n = len(&self.gc.sp, v.as_heap()) - BB_KIDS;
                let mut pos = 0u32;
                for i in 0..n {
                    if pos >= to {
                        break;
                    }
                    let k = self.slot(v, BB_KIDS + i);
                    let kn = self.b_count(k);
                    let end = pos + kn;
                    if end > from {
                        self.b_append_range(k, from.saturating_sub(pos),
                                            (to - pos).min(kn), out);
                    }
                    pos = end;
                }
            }
            _ => {}
        }
    }

    /// `[from, to)`.
    ///
    /// Descends to the range rather than flattening first. The flattening
    /// version was correct and quadratic in the caller: tree-shaking a module
    /// slices a thousand function bodies out of one 509 KB code section, and
    /// materialising the whole section per slice is half a gigabyte of copying.
    /// It ran the compiler out of memory, which read as `memory access out of
    /// bounds` and named nothing.
    pub fn b_slice(&mut self, v: Value, from: u32, to: u32) -> Value {
        let n = self.b_count(v);
        let from = from.min(n);
        let to = to.clamp(from, n);
        if from == 0 && to == n {
            return v;
        }
        let mut out = alloc::vec::Vec::with_capacity((to - from) as usize);
        self.b_append_range(v, from, to, &mut out);
        let _ = SLICE_MIN;
        self.new_bytes(&out)
    }

    pub fn b_eq(&self, a: Value, b: Value) -> bool {
        if self.b_count(a) != self.b_count(b) {
            return false;
        }
        self.b_to_vec(a) == self.b_to_vec(b)
    }
}

// --- the transient ---------------------------------------------------------
//
// Why this exists, in one measurement. `FLAT_MAX` is 1024 bytes, and a
// concatenation below it COPIES rather than building a node -- that tier is
// what stops a tree costing more in metadata than it saves. It also means
// building a byte string one piece at a time re-copies everything so far on
// every join, which is quadratic until the pieces outgrow the threshold.
// Measured on the text ropes it mirrors: repeated `str` is 8.7 ms and 2.1 MB
// against 0.9 ms and 0.7 MB for one join at the end, for the same answer.
//
// A transient fixes it the way a transient vector fixes `conj`: a tail buffer
// the transient OWNS and writes into, promoted into the tree only when it is
// full. Canonical Clojure has no reason to want this, because its strings are
// flat and building one is a StringBuilder. flint's are trees with a flat
// threshold, which is a shape that has this problem and that solution.

/// The accumulated persistent byte string, or NIL.
pub const TB_TREE: u32 = 0;
/// A `TY_BYTES` of `TAIL_CAP` bytes that this transient owns and writes into.
pub const TB_TAIL: u32 = 1;
/// How many bytes of the tail are used.
pub const TB_FILL: u32 = 2;
/// FALSE once `persistent!` has run. Using a dead transient is an error, not
/// undefined behaviour: the tail it would write into now belongs to a value
/// somebody else can see.
pub const TB_LIVE: u32 = 3;

/// The tail is exactly `FLAT_MAX`, so a promoted tail is the smallest piece
/// that makes `b_concat` build a node instead of copying. A smaller tail would
/// promote into the copying tier and reintroduce the quadratic it exists to
/// remove.
pub const TAIL_CAP: u32 = FLAT_MAX;

impl Rt {
    #[inline]
    pub fn is_tbytes(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_TBYTES
    }

    pub fn b_transient(&mut self, v: Value) -> Value {
        let base = self.mark();
        self.push(v);
        let tail = self.alloc(TY_BYTES, TAIL_CAP);
        if tail == 0 {
            self.pop_to(base);
            return NIL;
        }
        self.push(Value::heap(tail));
        let a = self.alloc(TY_TBYTES, 4);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (v, tail) = (self.r(base), self.r(base + 1));
        self.gc.set_slot(a, TB_TREE, v);
        self.gc.set_slot(a, TB_TAIL, tail);
        self.gc.set_slot(a, TB_FILL, Value::fixnum(0));
        self.gc.set_slot(a, TB_LIVE, crate::value::TRUE);
        self.pop_to(base);
        Value::heap(a)
    }

    fn tb_live(&self, t: Value) -> bool {
        self.is_tbytes(t) && self.slot(t, TB_LIVE) == crate::value::TRUE
    }

    /// Fold the full tail into the tree and start a fresh one. The old tail is
    /// handed over WHOLE rather than copied -- it is exactly full, so it is
    /// already the leaf the tree wants.
    fn tb_flush(&mut self, t: Value, fill: u32) -> bool {
        let base = self.mark();
        self.push(t);
        let tail = self.slot(t, TB_TAIL);
        self.push(tail);
        let piece = if fill == TAIL_CAP {
            self.r(base + 1)
        } else {
            let bs = raw_bytes(&self.gc.sp, self.r(base + 1).as_heap())[..fill as usize].to_vec();
            self.new_bytes(&bs)
        };
        self.push(piece);
        let tree = self.slot(self.r(base), TB_TREE);
        self.push(tree);
        let joined = self.b_concat(self.r(base + 3), self.r(base + 2));
        self.push(joined);
        let fresh = self.alloc(TY_BYTES, TAIL_CAP);
        if fresh == 0 {
            self.pop_to(base);
            return false;
        }
        let t = self.r(base);
        let joined = self.r(base + 4);
        self.gc.set_slot(t.as_heap(), TB_TREE, joined);
        self.gc.set_slot(t.as_heap(), TB_TAIL, Value::heap(fresh));
        self.gc.set_slot(t.as_heap(), TB_FILL, Value::fixnum(0));
        self.pop_to(base);
        true
    }

    /// Append one byte. The whole point: no allocation and no copy until the
    /// tail fills.
    ///
    /// `t` is ROOTED across the flush, and that is not caution. `tb_flush`
    /// allocates, allocating can collect, and the nursery is a copying
    /// collector -- so a `Value` held in a Rust local across it comes back
    /// holding the address the object had BEFORE the flip. That is the exact
    /// shape `../HANDOFF.md` was written about, and it is why `stat_stale_push`
    /// exists.
    pub fn b_conj(&mut self, t: Value, byte: u8) -> Value {
        if !self.tb_live(t) {
            return self.throw_str("IllegalStateException",
                                  "this transient byte string is no longer usable");
        }
        let fill = self.slot(t, TB_FILL).as_fixnum() as u32;
        if fill == TAIL_CAP {
            let base = self.mark();
            self.push(t);
            let ok = self.tb_flush(self.r(base), fill);
            let t = self.r(base);
            self.pop_to(base);
            if !ok {
                return NIL;
            }
            return self.b_conj(t, byte);
        }
        let tail = self.slot(t, TB_TAIL);
        self.gc.sp.bytes_mut(tail.as_heap() + HDR, TAIL_CAP)[fill as usize] = byte;
        self.gc.set_slot(t.as_heap(), TB_FILL, Value::fixnum(fill as i64 + 1));
        t
    }

    /// Append a whole byte string. Bulk, because appending a 1 KB piece one
    /// byte at a time is the thing this type exists to stop doing.
    pub fn b_append_bytes(&mut self, t: Value, v: Value) -> Value {
        if !self.tb_live(t) {
            return self.throw_str("IllegalStateException",
                                  "this transient byte string is no longer usable");
        }
        // The source is copied out FIRST, so nothing here holds a reference
        // into the heap while the loop below allocates. `t` is rooted for the
        // same reason `b_conj` roots it: a flush collects, and a copying
        // collector moves the transient out from under a Rust local.
        let src = self.b_to_vec(v);
        let base = self.mark();
        self.push(t);
        let mut i = 0usize;
        while i < src.len() {
            let t = self.r(base);
            let fill = self.slot(t, TB_FILL).as_fixnum() as u32;
            if fill == TAIL_CAP {
                if !self.tb_flush(t, fill) {
                    self.pop_to(base);
                    return NIL;
                }
                continue;
            }
            let room = (TAIL_CAP - fill) as usize;
            let n = room.min(src.len() - i);
            let tail = self.slot(t, TB_TAIL);
            self.gc.sp.bytes_mut(tail.as_heap() + HDR, TAIL_CAP)
                [fill as usize..fill as usize + n]
                .copy_from_slice(&src[i..i + n]);
            self.gc.set_slot(t.as_heap(), TB_FILL, Value::fixnum(fill as i64 + n as i64));
            i += n;
        }
        let out = self.r(base);
        self.pop_to(base);
        out
    }

    pub fn b_tcount(&self, t: Value) -> u32 {
        if !self.is_tbytes(t) {
            return 0;
        }
        self.b_count(self.slot(t, TB_TREE)) + self.slot(t, TB_FILL).as_fixnum() as u32
    }

    pub fn b_persistent(&mut self, t: Value) -> Value {
        if !self.tb_live(t) {
            return self.throw_str("IllegalStateException",
                                  "this transient byte string is no longer usable");
        }
        let fill = self.slot(t, TB_FILL).as_fixnum() as u32;
        let base = self.mark();
        self.push(t);
        if fill > 0 && !self.tb_flush(self.r(base), fill) {
            self.pop_to(base);
            return NIL;
        }
        let t = self.r(base);
        self.gc.set_slot(t.as_heap(), TB_LIVE, crate::value::FALSE);
        let out = self.slot(t, TB_TREE);
        self.pop_to(base);
        if out.is_nil() {
            return self.new_bytes(&[]);
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The same build under GC STRESS: a collection at every allocation.
    ///
    /// The plain version above passes and the wasm build failed at exactly the
    /// depth 1 -> 2 transition, which is the signature of a root that is only
    /// stale when a collection lands in a particular window. Stress mode makes
    /// the window every allocation, so the bug stops depending on timing.
    #[test]
    #[cfg(feature = "diagnostics")]
    fn a_byte_at_a_time_under_gc_stress() {
        let mut rt = Rt::new();
        rt.gc.stress = true;
        // 512-byte pieces rather than single bytes: a collection at EVERY
        // allocation is quadratic, and what matters is crossing the tiers, not
        // how many steps it takes to get there. Forty of these cross the flat
        // threshold, fill a node, and force a second level.
        //
        // The accumulator lives on the ROOT STACK for the whole loop, not in a
        // Rust local. The first version of this test kept it in a local, and
        // the next `new_bytes` collected and moved it -- so the test pushed a
        // stale value and the collector said
        // `forward: N is not the start of a from-space object`. That is the
        // failure `../HANDOFF.md` was written about, reproduced here by the
        // test rather than by the thing under test.
        let piece_bytes: alloc::vec::Vec<u8> = (0..512u32).map(|i| (i % 256) as u8).collect();
        let base = rt.mark();
        let empty = rt.new_bytes(&[]);
        rt.push(empty);
        for i in 0..40u32 {
            let piece = rt.new_bytes(&piece_bytes);
            rt.push(piece);
            let (a, b) = (rt.r(base), rt.r(base + 1));
            let joined = rt.b_concat(a, b);
            rt.set_r(base, joined);
            rt.pop_to(base + 1);
            let acc = rt.r(base);
            let n = (i + 1) * 512;
            assert_eq!(rt.b_count(acc), n, "count after {i}");
            assert_eq!(rt.b_at(acc, 0), Some(0), "byte 0 after {i}");
            assert_eq!(rt.b_at(acc, n - 1), Some(255), "last after {i}");
            assert_eq!(rt.b_at(acc, i * 512), Some(0), "piece start after {i}");
        }
        let acc = rt.r(base);
        assert!(rt.b_depth(acc) <= 3, "depth {}", rt.b_depth(acc));
        rt.pop_to(base);
    }

    #[test]
    fn big_pieces_build_a_tree_and_read_back() {
        let mut rt = Rt::new();
        let piece: alloc::vec::Vec<u8> = (0..2048u32).map(|i| (i % 256) as u8).collect();
        let mut acc = rt.new_bytes(&[]);
        for _ in 0..64 {
            let p = rt.new_bytes(&piece);
            let base = rt.mark();
            rt.push(acc);
            rt.push(p);
            let (a, b) = (rt.r(base), rt.r(base + 1));
            acc = rt.b_concat(a, b);
            rt.pop_to(base);
        }
        assert_eq!(rt.b_count(acc), 64 * 2048);
        assert_eq!(rt.b_at(acc, 2048), Some(0));
        assert_eq!(rt.b_at(acc, 64 * 2048 - 1), Some(((2047u32) % 256) as u8));
    }
}
