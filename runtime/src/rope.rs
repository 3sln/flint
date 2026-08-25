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
    pub fn is_rope(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_ROPE
    }

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

    /// A rope node over `kids`, whose aggregates are summed from them rather
    /// than derived from their bytes.
    fn rope_node(&mut self, kids: &[Value]) -> Value {
        let (mut bytes, mut cps, mut ascii) = (0u32, 0u32, true);
        for k in kids {
            bytes += self.s_bytes(*k);
            cps += self.s_count(*k);
            ascii &= self.s_ascii(*k);
        }
        let base = self.mark();
        for k in kids {
            self.push(*k);
        }
        let a = self.alloc(TY_ROPE, RP_KIDS + kids.len() as u32);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        self.gc.set_slot(a, RP_BYTES, Value::fixnum(bytes as i64));
        self.gc
            .set_slot(a, RP_CPS, Value::fixnum(((cps as i64) << 1) | ascii as i64));
        self.gc.set_slot(a, RP_FLAT, NIL);
        for (i, _) in kids.iter().enumerate() {
            let v = self.r(base + i);
            self.gc.set_slot(a, RP_KIDS + i as u32, v);
        }
        self.pop_to(base);
        Value::heap(a)
    }

    fn rope_kids(&self, v: Value) -> u32 {
        len(&self.gc.sp, v.as_heap()) - RP_KIDS
    }

    /// `str` of two strings. O(1) once the pieces are big enough to matter.
    pub fn s_concat(&mut self, a: Value, b: Value) -> Value {
        if self.s_bytes(a) == 0 {
            return b;
        }
        if self.s_bytes(b) == 0 {
            return a;
        }
        let total = self.s_bytes(a) + self.s_bytes(b);
        if total <= FLAT_MAX {
            // Small enough that a tree would cost more than the copy. This is
            // the tier that must not be skipped.
            return self.copy_concat(a, b);
        }
        // Append into the right spine while there is room, so a thousand small
        // appends do not become a thousand nodes.
        if self.is_rope(a) && self.rope_kids(a) < FANOUT {
            let n = self.rope_kids(a);
            let base = self.mark();
            for i in 0..n {
                let k = self.slot(a, RP_KIDS + i);
                self.push(k);
            }
            self.push(b);
            let kids: alloc::vec::Vec<Value> =
                (0..=n).map(|i| self.r(base + i as usize)).collect();
            let out = self.rope_node(&kids);
            self.pop_to(base);
            return out;
        }
        let base = self.mark();
        let ai = self.push(a);
        let bi = self.push(b);
        let kids = [self.r(ai), self.r(bi)];
        let out = self.rope_node(&kids);
        self.pop_to(base);
        out
    }

    fn copy_concat(&mut self, a: Value, b: Value) -> Value {
        // Charged where the bytes actually move. A tree join moves none, which
        // is what makes repeated concatenation linear in gas as well as in time
        // -- it was quadratic in both.
        let n = self.s_bytes(a) + self.s_bytes(b);
        self.charge_bytes(n);
        let mut out: alloc::vec::Vec<u8> =
            alloc::vec::Vec::with_capacity((self.s_bytes(a) + self.s_bytes(b)) as usize);
        let base = self.mark();
        let ai = self.push(a);
        let bi = self.push(b);
        self.append_bytes(self.r(ai), &mut out);
        self.append_bytes(self.r(bi), &mut out);
        self.pop_to(base);
        let s = core::str::from_utf8(&out).unwrap_or("");
        self.string(s)
    }

    /// Walk the leaves in order, appending their bytes. Never allocates in the
    /// flint heap, so a caller may hold raw references across it.
    pub fn append_bytes(&self, v: Value, out: &mut alloc::vec::Vec<u8>) {
        if v.is_inline_str() {
            let mut b = crate::rt::sbuf();
            out.extend_from_slice(v.inline_bytes(&mut b));
            return;
        }
        if !v.is_heap() {
            return;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_STR => out.extend_from_slice(str_bytes(&self.gc.sp, v.as_heap())),
            TY_ROPE => {
                let cached = self.slot(v, RP_FLAT);
                if !cached.is_nil() {
                    self.append_bytes(cached, out);
                    return;
                }
                let n = self.rope_kids(v);
                for i in 0..n {
                    let k = self.slot(v, RP_KIDS + i);
                    self.append_bytes(k, out);
                }
            }
            _ => {}
        }
    }

    /// Contiguous bytes for a string of any tier. Identity for inline and flat;
    /// materialises a rope ONCE and remembers it.
    pub fn flatten(&mut self, v: Value) -> Value {
        if !self.is_rope(v) {
            return v;
        }
        #[cfg(feature = "diagnostics")]
        unsafe {
            FLATTENS[F_CALLS] += 1;
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
        let n = self.s_bytes(v);
        self.charge_bytes(n);
        let mut out: alloc::vec::Vec<u8> = alloc::vec::Vec::with_capacity(n as usize);
        let base = self.mark();
        let vi = self.push(v);
        self.append_bytes(self.r(vi), &mut out);
        let s = core::str::from_utf8(&out).unwrap_or("");
        let flat = self.string(s);
        let v = self.r(vi);
        self.pop_to(base);
        if v.is_heap() && !flat.is_nil() {
            self.gc.set_slot(v.as_heap(), RP_FLAT, flat);
        }
        flat
    }
}
