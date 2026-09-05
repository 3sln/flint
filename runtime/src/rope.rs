//! Rope strings (`doc/decisions/0011` §2).
//!
//! Three tiers, one interface. **Inline** lives in the value word and allocates
//! nothing. **Flat** is a contiguous `TY_STR`. **Rope** is a shallow B-tree of
//! string pieces with structure sharing, so `str` is a tree join instead of a
//! copy and `subs` of a large range shares subtrees instead of copying them.
//!
//! ## The three numbers, written down rather than left to emerge
//!
//! * `FLAT_MAX` (1024 bytes) — a concatenation whose result fits copies into a
//!   flat string. Below this a tree costs more in metadata than it saves, and
//!   most strings a program touches are below it.
//! * `FANOUT` (16) — how many children a node holds. Depth is what random
//!   access pays for; at 16 a megabyte is five levels, not twenty.
//! * `SLICE_MIN` (256 bytes) — a `subs` smaller than this COPIES. That is not a
//!   performance choice, it is the retention fix: `(subs big 0 3)` must not keep
//!   `big` alive.
//!
//! ## What is stored, and why it is relative
//!
//! Each node carries the byte length and code-point count OF ITS OWN SUBTREE,
//! plus an ASCII bit. Never an absolute offset: the same leaf appears in
//! `(str a b)` and `(str b a)` at two different positions, and sharing is the
//! whole point, so a node that recorded where it sat would be correct in at most
//! one rope. The absolute position is accumulated during descent.
//!
//! Composing a node never rescans bytes: the code-point count is a SUM and the
//! ASCII bit is an AND. The only scan is at leaf construction, bounded by the
//! leaf.

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, NIL};

/// A concatenation at or below this copies into a flat string instead of
/// building a node.
pub const FLAT_MAX: u32 = 1024;
/// Children per internal node.
pub const FANOUT: u32 = 16;

/// LEAF SIZE FOR A STRING THAT ARRIVES NON-ASCII AND WHOLE.
///
/// A tree's node array IS a sparse code-point index: locating code point `k`
/// costs a descent plus a scan bounded by the leaf, and both are properties of
/// the STRING rather than of what was indexed before it. That is the whole
/// reason to prefer it to a cursor -- the cost is predictable, and it does not
/// change because another executor happened to collect.
///
/// Smaller than `FLAT_MAX` because the bound is what is being bought: a
/// 1024-byte leaf makes a walk cost ~512 byte-steps per character, 128 makes it
/// ~64, at eight times the node count on a string nobody concatenated.
pub const INDEX_LEAF: u32 = 128;
/// A slice smaller than this copies rather than sharing, so a small `subs`
/// cannot retain a large parent.
pub const SLICE_MIN: u32 = 256;

/// How many times a rope has been materialised into contiguous bytes, and how
/// many of those hit the cache. `doc/decisions/0011`: *"count the flattens, do
/// not hope about them"* -- a rope that flattens on every `index-of` passes
/// every correctness test and is slower than the flat string it replaced.
#[cfg(feature = "diagnostics")]
pub static mut FLATTENS: [u64; 4] = [0; 4];
#[cfg(feature = "diagnostics")]
pub const F_CALLS: usize = 0;
#[cfg(feature = "diagnostics")]
pub const F_MATERIALISED: usize = 1;
#[cfg(feature = "diagnostics")]
pub const F_BYTES: usize = 2;
/// Ropes that reached `as_str` without having been flattened. `as_str` borrows
/// and so cannot materialise; it returns `None`, and a caller that reads that as
/// "not a string" writes zero bytes and truncates in silence -- which is exactly
/// what the port drain did. Nothing should ever reach it, and this is how that
/// claim stays true rather than merely being made.
#[cfg(feature = "diagnostics")]
pub const F_UNFLAT_ASSTR: usize = 3;

impl Rt {
    #[inline]

    /// Byte length of any string, all three tiers, O(1).
    pub fn s_bytes(&self, v: Value) -> u32 {
        if v.is_inline_str() {
            v.inline_len() as u32
        } else if self.is_rope(v) {
            self.slot(v, RP_BYTES).as_fixnum() as u32
        } else {
            len(&self.gc.sp, v.as_heap())
        }
    }

    /// Code-point count of any string, all three tiers, O(1).
    pub fn s_count(&self, v: Value) -> u32 {
        if v.is_inline_str() {
            let mut b = crate::rt::sbuf();
            let bs = v.inline_bytes(&mut b);
            if bs.is_ascii() {
                bs.len() as u32
            } else {
                core::str::from_utf8(bs).map(|s| s.chars().count() as u32).unwrap_or(0)
            }
        } else if self.is_rope(v) {
            (self.slot(v, RP_CPS).as_fixnum() as u32) >> 1
        } else if str_is_ascii(&self.gc.sp, v.as_heap()) {
            len(&self.gc.sp, v.as_heap())
        } else {
            let b = str_bytes(&self.gc.sp, v.as_heap());
            core::str::from_utf8(b).map(|s| s.chars().count() as u32).unwrap_or(0)
        }
    }

    /// Is every byte below 0x80? All three tiers, O(1).
    pub fn s_ascii(&self, v: Value) -> bool {
        if v.is_inline_str() {
            let mut b = crate::rt::sbuf();
            v.inline_bytes(&mut b).is_ascii()
        } else if self.is_rope(v) {
            self.slot(v, RP_CPS).as_fixnum() & 1 != 0
        } else {
            str_is_ascii(&self.gc.sp, v.as_heap())
        }
    }

    /// The byte offset of code point `k`, by DESCENDING rather than flattening.
    ///
    /// `doc/decisions/0011` designed the per-node code-point counts for exactly
    /// this and nothing ever used them: every indexing path called `string_arg`
    /// first, which flattens, so the counts were computed, stored, traced by the
    /// collector, and then thrown away before the one question they answer.
    ///
    /// Returns `None` when `k` is past the end.
    pub fn rope_byte_of_cp(&mut self, v: Value, k: u32) -> Option<u32> {
        let mut node = v;
        let mut want = k;
        let mut byte = 0u32;
        loop {
            if !self.is_rope(node) {
                // A leaf: a flat or inline string. ASCII means the index IS the
                // offset; otherwise scan, bounded by the leaf.
                let n = self.s_count(node);
                if want >= n {
                    return None;
                }
                if self.s_ascii(node) {
                    return Some(byte + want);
                }
                let mut buf = crate::rt::sbuf();
                let b: &[u8] = if node.is_inline_str() {
                    node.inline_bytes(&mut buf)
                } else {
                    str_bytes(&self.gc.sp, node.as_heap())
                };
                let mut at = 0u32;
                for _ in 0..want {
                    at += crate::coll::utf8_width(b[at as usize]);
                }
                // CHARGED FOR THE SCAN, bounded by the leaf. Gas is meant to be
                // proportional to work (`doc/decisions/0009`) -- and it has to
                // be charged for a walk that is real, or `test/scaling.clj` is
                // measuring nothing and a quadratic reads as linear. That is
                // how the flat-string one hid.
                self.charge_bytes(at);
                return Some(byte + at);
            }
            let nkids = self.rope_kids(node);
            let mut i = 0;
            loop {
                if i >= nkids {
                    return None;
                }
                let kid = self.slot(node, RP_KIDS + i);
                let c = self.s_count(kid);
                if want < c {
                    node = kid;
                    break;
                }
                want -= c;
                byte += self.s_bytes(kid);
                i += 1;
            }
            // One step per node examined: the descent is work too, and a
            // fanout scan that cost nothing would let a pathological tree hide.
            self.charge_work(i as u64 + 1);
        }
    }

    /// The bytes of the code point at `byte`, from whichever leaf holds it.
    ///
    /// Takes the offset `rope_byte_of_cp` returned, so it walks the tree once
    /// more rather than being handed the leaf. That is deliberate: handing back
    /// a leaf would be handing back a `Value` the caller has to root, and the
    /// second descent is O(depth) against a leaf scan that dominates it.
    pub fn rope_bytes_at(&mut self, v: Value, byte: u32, out: &mut [u8; 4]) -> u32 {
        let mut node = v;
        let mut want = byte;
        loop {
            if !self.is_rope(node) {
                let mut buf = crate::rt::sbuf();
                let b: &[u8] = if node.is_inline_str() {
                    node.inline_bytes(&mut buf)
                } else {
                    str_bytes(&self.gc.sp, node.as_heap())
                };
                let w = crate::coll::utf8_width(b[want as usize]);
                out[..w as usize].copy_from_slice(&b[want as usize..(want + w) as usize]);
                return w;
            }
            let nkids = self.rope_kids(node);
            let mut i = 0;
            loop {
                if i >= nkids {
                    return 0;
                }
                let kid = self.slot(node, RP_KIDS + i);
                let n = self.s_bytes(kid);
                if want < n {
                    node = kid;
                    break;
                }
                want -= n;
                i += 1;
            }
        }
    }




    /// The next leaf of a tree walk, given a stack of `(node, next-child)`.
    /// Pushes down the left spine until it reaches something with bytes in it.

    /// Content equality over two string trees, WITHOUT materialising either.
    ///
    /// What it replaces copied both sides into Rust vectors in full and then
    /// compared them -- so two megabyte strings differing in their first byte
    /// cost two megabytes of copying to discover it.
    ///
    /// Two short circuits, and the first one matters much more since `subs`
    /// started SHARING:
    ///
    ///  * **A node is equal to itself.** Sliced strings share their interior,
    ///    so comparing two of them now meets the same leaf on both sides
    ///    repeatedly, and each meeting is a pointer comparison rather than a
    ///    byte scan.
    ///  * **A mismatch stops where it happens**, not after both sides are
    ///    copied.
    /// `tree_eq`'s own walk, and the reason it is not the runtime's.
    ///
    /// A byte tree's leaves are always heap objects, so `walk_next` on `Rt`
    /// serves `b_eq` and this file's node walk alike. A ROPE's leaf can be an
    /// INLINE STRING -- bytes in the value itself, not in the heap -- so
    /// comparing them needs somewhere to decode each side into, and that is a
    /// second capability rather than the walk. Until it lands this keeps its
    /// own stack, which is the honest version of "not ported yet".
    fn rope_walk_next(&self, stack: &mut alloc::vec::Vec<(Value, u32)>) -> Option<Value> {
        loop {
            let (node, i) = *stack.last()?;
            if !self.is_rope(node) {
                stack.pop();
                return Some(node);
            }
            let kids = self.rope_kids(node);
            if i >= kids {
                stack.pop();
                continue;
            }
            stack.last_mut().unwrap().1 = i + 1;
            let k = self.slot(node, RP_KIDS + i);
            stack.push((k, 0));
        }
    }

    pub fn tree_eq(&self, a: Value, b: Value) -> bool {
        if a.0 == b.0 {
            return true;
        }
        let mut sa: alloc::vec::Vec<(Value, u32)> = alloc::vec::Vec::new();
        let mut sb: alloc::vec::Vec<(Value, u32)> = alloc::vec::Vec::new();
        sa.push((a, 0));
        sb.push((b, 0));
        let (mut ba, mut bb) = (crate::rt::sbuf(), crate::rt::sbuf());
        let (mut la, mut lb): (&[u8], &[u8]) = (&[], &[]);
        let (mut pa, mut pb) = (0usize, 0usize);
        loop {
            if pa == la.len() {
                match self.rope_walk_next(&mut sa) {
                    None => break,
                    Some(v) => {
                        // IDENTITY, at the leaf: if the other side is also at a
                        // leaf boundary and holding the same object, neither
                        // needs reading.
                        if pb == lb.len() && !sb.is_empty() {
                            let mut peek = sb.clone();
                            if let Some(w) = self.rope_walk_next(&mut peek) {
                                if w.0 == v.0 {
                                    sb = peek;
                                    la = &[];
                                    lb = &[];
                                    pa = 0;
                                    pb = 0;
                                    continue;
                                }
                            }
                        }
                        la = self.leaf_bytes(v, &mut ba);
                        pa = 0;
                    }
                }
            }
            if pb == lb.len() {
                match self.rope_walk_next(&mut sb) {
                    None => break,
                    Some(v) => {
                        lb = self.leaf_bytes(v, &mut bb);
                        pb = 0;
                    }
                }
            }
            let n = (la.len() - pa).min(lb.len() - pb);
            if n == 0 {
                continue;
            }
            if la[pa..pa + n] != lb[pb..pb + n] {
                return false;
            }
            pa += n;
            pb += n;
        }
        // Both must have ended together. The caller checks the lengths first,
        // so reaching here with either side unfinished is not possible -- but
        // saying so costs one comparison and does not rely on that.
        pa == la.len() && pb == lb.len()
            && self.rope_walk_next(&mut sa).is_none()
            && self.rope_walk_next(&mut sb).is_none()
    }


    /// The content hash of a string tree, WITHOUT materialising it.
    ///
    /// Cached per node, so a subtree already hashed costs a slot read. After a
    /// sharing `subs` most of the tree is a subtree of something else, so this
    /// is the case that matters rather than a corner.
    pub fn rope_hash(&mut self, v: Value) -> u32 {
        if !self.is_rope(v) {
            let mut b = crate::rt::sbuf();
            let bs: alloc::vec::Vec<u8> = self.leaf_bytes(v, &mut b).to_vec();
            let mut h: u32 = 0;
            for c in bs {
                h = h.wrapping_mul(31).wrapping_add(c as u32);
            }
            return h;
        }
        let cached = self.slot(v, RP_HASH);
        if cached.is_fixnum() {
            return cached.as_fixnum() as u32;
        }
        let base = self.mark();
        let vi = self.push(v);
        let kids = self.rope_kids(self.r(vi));
        let mut h: u32 = 0;
        for i in 0..kids {
            let k = self.slot(self.r(vi), RP_KIDS + i);
            let ki = self.push(k);
            let kh = self.rope_hash(self.r(ki));
            let kb = self.s_bytes(self.r(ki));
            // h(A·B) = h(A)·31^|B| + h(B). The bytes, not the code points:
            // this must agree with the flat hash, which steps per BYTE.
            h = h.wrapping_mul(self.pow31(kb)).wrapping_add(kh);
            self.pop_to(ki);
        }
        let vv = self.r(vi);
        self.set_slot(vv.as_heap(), RP_HASH, Value::fixnum(h as i64));
        self.pop_to(base);
        h
    }







    /// Copy the range out into a fresh string.
    ///
    /// The SINK half: it needs a growable byte buffer AND a UTF-8 decode, so it
    /// stays hand-written and the generated tree half reaches it here.
    /// `SLICE_MIN` exists to force this path for a small range -- a three-byte
    /// slice must not keep a 509 KB section alive -- so this is policy rather
    /// than a fallback.
    pub(crate) fn s_copy_range(&mut self, v: Value, from: u32, to: u32) -> Value {
        let base = self.mark();
        let vi = self.push(v);
        let mut out: alloc::vec::Vec<u8> = alloc::vec::Vec::with_capacity((to - from) as usize);
        self.append_range(self.r(vi), from, to, &mut out);
        self.pop_to(base);
        let t = core::str::from_utf8(&out).unwrap_or("");
        self.string(t)
    }

    /// The empty string. INTERNED rather than allocated, unlike `b_empty`'s
    /// byte leaf: an empty string is an inline value here, so there is nothing
    /// to allocate and the generated half cannot build one itself.
    pub(crate) fn s_empty(&mut self) -> Value {
        self.string("")
    }

    /// `pub(crate)` because the generated rope half calls it: flattening two
    /// strings into one leaf needs a byte sink, so it stays hand-written and
    /// the boundary between the halves is a module boundary.
    pub(crate) fn copy_concat(&mut self, a: Value, b: Value) -> Value {
        // Charged where the bytes actually move. A tree join moves none, which
        // is what makes repeated concatenation linear in gas as well as in time
        // -- it was quadratic in both.
        let n = self.s_bytes(a) + self.s_bytes(b);
        self.charge_bytes(n);
        let base = self.mark();
        let ai = self.push(a);
        let bi = self.push(b);
        let sk = self.sink_open();
        let av = self.r(ai);
        self.s_append(av, sk);
        let bv = self.r(bi);
        self.s_append(bv, sk);
        let out = self.sink_string(sk);
        self.sink_close(sk);
        self.pop_to(base);
        out
    }

    /// Walk the leaves in order, appending their bytes. Never allocates in the
    /// flint heap, so a caller may hold raw references across it.
    /// The bytes in `[from, to)` only, skipping subtrees that fall outside.
    ///
    /// The point of the tree, applied to slicing: a `subs` near the end of a
    /// large string touches the leaves it needs and the spine above them, not
    /// every byte before it. `append_bytes` collects everything, which is what
    /// makes it the wrong primitive for `subs` however cheap each step is.
    pub fn append_range(&self, v: Value, from: u32, to: u32, out: &mut alloc::vec::Vec<u8>) {
        if from >= to {
            return;
        }
        if v.is_inline_str() {
            let mut b = crate::rt::sbuf();
            let bs = v.inline_bytes(&mut b);
            let hi = (to as usize).min(bs.len());
            let lo = (from as usize).min(hi);
            out.extend_from_slice(&bs[lo..hi]);
            return;
        }
        if !v.is_heap() {
            return;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_STR => {
                let bs = str_bytes(&self.gc.sp, v.as_heap());
                let hi = (to as usize).min(bs.len());
                let lo = (from as usize).min(hi);
                out.extend_from_slice(&bs[lo..hi]);
            }
            TY_ROPE => {
                let n = self.rope_kids(v);
                let mut at = 0u32;
                for i in 0..n {
                    let k = self.slot(v, RP_KIDS + i);
                    let w = self.s_bytes(k);
                    // Wholly before the range, or wholly after it: skip. The
                    // "after" case is what stops a slice near the start from
                    // walking the whole tail.
                    if at + w > from && at < to {
                        self.append_range(k, from.saturating_sub(at), to - at, out);
                    }
                    at += w;
                    if at >= to {
                        return;
                    }
                }
            }
            _ => {}
        }
    }


    /// The string's bytes as a HOST vector, for host code outside the port --
    /// the printer and `str_bytes`'s builtin. Inside, everything goes through a
    /// sink; this is the door, as `b_to_vec` is for byte strings.
    pub fn s_to_vec(&mut self, v: Value) -> alloc::vec::Vec<u8> {
        let s = self.sink_open();
        self.s_append(v, s);
        let out = self.sinks[s as usize].clone();
        self.sink_close(s);
        out
    }

    /// Contiguous bytes for a string of any tier, COUNTED.
    ///
    /// The flattening itself is generated -- `s_flatten` -- and this is the
    /// diagnostics wrapper around it. `bin/check-flattens` gates on these
    /// counters: every materialisation site has to justify itself, and a
    /// counter that stopped incrementing would report zero sites and pass.
    /// So the count stays hand-written and Rust-only, because `--diagnostics`
    /// is a Rust build and the ports have no equivalent to keep in step with.
    pub fn flatten(&mut self, v: Value) -> Value {
        #[cfg(feature = "diagnostics")]
        unsafe {
            FLATTENS[F_CALLS] += 1;
        }
        if !self.is_rope(v) {
            return v;
        }
        let cached = self.slot(v, RP_FLAT);
        if !cached.is_nil() {
            return cached;
        }
        #[cfg(feature = "diagnostics")]
        unsafe {
            FLATTENS[F_MATERIALISED] += 1;
            FLATTENS[F_BYTES] += self.s_bytes(v) as u64;
        }
        // CHARGED FOR WHAT IT MATERIALISES, before doing it. `str-index-of`,
        // `subs` and `str-bytes` all reach bytes through here, so a rope
        // flattened at the door is work none of their own charges can see --
        // and without this one a search ran 48 190 steps past an exhausted
        // budget. The gas gate found that within a minute of it being dropped.
        let n = self.s_bytes(v);
        if !self.charge_checked((n as u64 / 8) + 1, "flatten") {
            return crate::value::NIL;
        }
        self.s_flatten(v)
    }
}
