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

    /// Copy the range out into a fresh string.
    ///
    /// The UTF-8 DECODE is the whole of what is left here. The walk that
    /// gathers the bytes is generated -- `s_append_range` -- and everything
    /// this adds is `sink_string`, which is host work in all three runtimes.
    /// `SLICE_MIN` exists to force this path for a small range -- a three-byte
    /// slice must not keep a 509 KB section alive -- so this is policy rather
    /// than a fallback.
    pub(crate) fn s_copy_range(&mut self, v: Value, from: u32, to: u32) -> Value {
        let base = self.mark();
        let vi = self.push(v);
        let s = self.sink_open();
        self.s_append_range(self.r(vi), from, to, s);
        let out = self.sink_string(s);
        self.sink_close(s);
        self.pop_to(base);
        out
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
