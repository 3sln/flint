//! NaN-boxed 64-bit values.
//!
//! A `Value` is a `u64`. Every IEEE-754 double is stored as its own bit pattern
//! *except* for the negative-NaN range `0xFFF9_..` upwards, which we steal for
//! tags. Any NaN produced by arithmetic is canonicalised to the positive quiet
//! NaN `0x7FF8_0000_0000_0000`, which is not in the stolen range, so no
//! observable double is lost (NaN payloads are not observable in Clojure).
//!
//! ```text
//!   bits[63:48]   meaning
//!   ---------------------------------------------------------------
//!   < 0xFFF9      an IEEE-754 double, stored verbatim
//!     0xFFF9      HEAP    payload[31:0]  = byte offset into the GC heap
//!     0xFFFA      FIXNUM  payload[47:0]  = 48-bit two's complement integer
//!     0xFFFB      SPECIAL payload        = nil / false / true / sentinels
//!     0xFFFC      STR     inline UTF-8 string, payload[47:40] = len (0..=5),
//!                                              payload[39:0]  = bytes, LE
//!     0xFFFD      KW      inline keyword, no namespace, same shape as STR
//!     0xFFFE      -       reserved
//!     0xFFFF      -       reserved
//! ```
//!
//! Chars are *not* a separate type: a char is a one-character string, and every
//! character of Unicode is at most 4 UTF-8 bytes, so every char is inline.
//! Inline form is canonical: a string of 5 bytes or fewer is *always* inline,
//! so `=` on short strings is a single 64-bit compare.

use core::fmt;

#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Value(pub u64);

pub const TAG_HEAP: u64 = 0xFFF9;
pub const TAG_FIXNUM: u64 = 0xFFFA;
pub const TAG_SPECIAL: u64 = 0xFFFB;
pub const TAG_STR: u64 = 0xFFFC;
pub const TAG_KW: u64 = 0xFFFD;
pub const TAG_MIN_BOXED: u64 = TAG_HEAP;

/// THE PAYLOAD FIELD: the 48 bits under the tag. Both ports have named this
/// since they were written (`Val.PAYLOAD`, `Val.Payload`); this runtime
/// inlined `0x0000_FFFF_FFFF_FFFF` at each use instead, which is how `heap`
/// below came to omit the mask altogether without looking out of place.
pub const PAYLOAD: u64 = 0x0000_FFFF_FFFF_FFFF;

pub const CANONICAL_NAN: u64 = 0x7FF8_0000_0000_0000;

/// Longest inline (immediate) string, in UTF-8 bytes.
pub const INLINE_MAX: usize = 5;

const SPECIAL_NIL: u64 = 0;
const SPECIAL_FALSE: u64 = 1;
const SPECIAL_TRUE: u64 = 2;
/// Returned by lookups that must distinguish "absent" from "present and nil".
const SPECIAL_NOT_FOUND: u64 = 3;
/// A green thread asked to park. It travels in `Rt::thrown` so that the check
/// the VM already makes after every native call catches it -- parking costs the
/// interpreter's fast path nothing at all (`DECISIONS.md#threads-and-ports`).
const SPECIAL_PARK: u64 = 4;
/// An allocation failed. Travels in `Rt::thrown` for the same reason `PARK`
/// does: the check the VM already makes finds it, and the *cold* path turns it
/// into a real error. Building the error where the allocation failed would make
/// the error builder reachable from `alloc`, which every program pays for.
const SPECIAL_OOM: u64 = 5;
/// A vacant port ring slot; see `EMPTY`.
const SPECIAL_EMPTY: u64 = 6;

pub const NIL: Value = Value((TAG_SPECIAL << 48) | SPECIAL_NIL);
pub const FALSE: Value = Value((TAG_SPECIAL << 48) | SPECIAL_FALSE);
pub const TRUE: Value = Value((TAG_SPECIAL << 48) | SPECIAL_TRUE);
pub const NOT_FOUND: Value = Value((TAG_SPECIAL << 48) | SPECIAL_NOT_FOUND);
pub const PARK: Value = Value((TAG_SPECIAL << 48) | SPECIAL_PARK);
pub const OOM: Value = Value((TAG_SPECIAL << 48) | SPECIAL_OOM);

/// A port ring slot with nothing in it.
///
/// A port's inbox is one array, and a slot's own word carries whether it is
/// vacant -- so claiming a slot and filling it are ONE compare-and-swap from
/// `EMPTY` to the message, and a reader takes it with a compare-and-swap back.
/// There is no instant at which a slot is spoken for and not yet written.
///
/// It has to be a value no program can produce, or a message equal to the
/// marker would read as an empty slot. `nil` is a perfectly good message, so
/// the marker lives here with `NOT_FOUND` and `PARK`: in the special tag
/// space, which guest code has no way to construct.
pub const EMPTY: Value = Value((TAG_SPECIAL << 48) | SPECIAL_EMPTY);

pub const FIXNUM_MAX: i64 = (1 << 47) - 1;
pub const FIXNUM_MIN: i64 = -(1 << 47);

impl Value {
    #[inline(always)]
    pub const fn bits(self) -> u64 {
        self.0
    }
    #[inline(always)]
    /// GENERATED (`kin/valtag.kin`) as `tag_of`. Not `const` any more, and
    /// nor are `is_fixnum`/`is_heap` below, which call it -- nothing in the
    /// crate needs any of the three in a const context.
    pub fn tag(self) -> u64 {
        crate::kgen::rt::valtag::tag_of(self) as u64
    }
    #[inline(always)]
    pub fn is_double(self) -> bool {
        self.tag() < TAG_MIN_BOXED
    }

    #[inline(always)]
    pub fn from_f64(d: f64) -> Value {
        let b = d.to_bits();
        // Only negative NaNs with a large payload collide with the tag range.
        if b >> 48 >= TAG_MIN_BOXED {
            Value(CANONICAL_NAN)
        } else {
            Value(b)
        }
    }
    #[inline(always)]
    pub fn as_f64(self) -> f64 {
        f64::from_bits(self.0)
    }

    #[inline(always)]
    pub fn is_fixnum(self) -> bool {
        self.tag() == TAG_FIXNUM
    }
    /// GENERATED (`kin/valtag.kin`) as `make_fixnum`. NOT `const` any more:
    /// the generated body is a plain fn, and nothing here builds a fixnum in
    /// a const context -- checked before relaxing it.
    #[inline(always)]
    pub fn fixnum(n: i64) -> Value {
        crate::kgen::rt::valtag::make_fixnum(n)
    }
    /// GENERATED as `fixnum_payload`. The sign extension is the point: the
    /// payload is 48 bits SIGNED, so bit 47 has to be propagated or every
    /// negative fixnum reads back as a large positive.
    #[inline(always)]
    pub fn as_fixnum(self) -> i64 {
        crate::kgen::rt::valtag::fixnum_payload(self)
    }
    /// GENERATED as `fits_fixnum`.
    #[inline(always)]
    pub fn fits_fixnum(n: i64) -> bool {
        crate::kgen::rt::valtag::fits_fixnum(n)
    }

    #[inline(always)]
    pub fn is_heap(self) -> bool {
        self.tag() == TAG_HEAP
    }
    #[inline(always)]
    /// GENERATED (`kin/valtag.kin`) as `make_heap`, which is what finally
    /// settles the mask: this runtime ORed the address in whole where both
    /// ports masked it, and one source cannot disagree with itself. At or
    /// above 2^48 an unmasked address lands in the TAG and answers a value of
    /// some other kind; unreachable while the heap is a wasm linear memory
    /// bounded at 2^32, and a divergence in the definition regardless.
    #[inline(always)]
    pub fn heap(off: crate::mem::Addr) -> Value {
        crate::kgen::rt::valtag::make_heap(off)
    }
    #[inline(always)]
    /// GENERATED as `heap_payload`. MASK to the payload, do not merely cast:
    /// `self.0 as u32` was right while an address was 32 bits, the cast doing
    /// the masking; with a wider address the tag comes back as part of the
    /// value.
    pub fn as_heap(self) -> crate::mem::Addr {
        crate::kgen::rt::valtag::heap_payload(self)
    }

    #[inline(always)]
    pub const fn is_nil(self) -> bool {
        self.0 == NIL.0
    }
    #[inline(always)]
    pub const fn is_true(self) -> bool {
        self.0 == TRUE.0
    }
    #[inline(always)]
    pub const fn is_false(self) -> bool {
        self.0 == FALSE.0
    }
    /// Clojure truthiness: everything except `nil` and `false`.
    #[inline(always)]
    pub const fn truthy(self) -> bool {
        self.0 != NIL.0 && self.0 != FALSE.0
    }
    #[inline(always)]
    pub const fn boolean(b: bool) -> Value {
        if b {
            TRUE
        } else {
            FALSE
        }
    }
    #[inline(always)]
    pub const fn is_bool(self) -> bool {
        self.0 == TRUE.0 || self.0 == FALSE.0
    }

    /// True for the inline (immediate) string representation.
    #[inline(always)]
    /// GENERATED (`kin/valtag.kin`) as `inline_string`.
    pub fn is_inline_str(self) -> bool {
        crate::kgen::rt::valtag::inline_string(self)
    }
    #[inline(always)]
    /// GENERATED as `inline_keyword`.
    pub fn is_inline_kw(self) -> bool {
        crate::kgen::rt::valtag::inline_keyword(self)
    }

    /// Build an inline string/keyword. `bytes.len()` must be <= INLINE_MAX.
    #[inline]
    fn inline_of(tag: u64, bytes: &[u8]) -> Value {
        debug_assert!(bytes.len() <= INLINE_MAX);
        let mut payload: u64 = 0;
        let mut i = 0;
        while i < bytes.len() {
            payload |= (bytes[i] as u64) << (8 * i);
            i += 1;
        }
        Value((tag << 48) | ((bytes.len() as u64) << 40) | payload)
    }

    #[inline]
    pub fn inline_str(bytes: &[u8]) -> Value {
        Self::inline_of(TAG_STR, bytes)
    }
    #[inline]
    pub fn inline_kw(bytes: &[u8]) -> Value {
        Self::inline_of(TAG_KW, bytes)
    }

    /// An inline KEYWORD as the inline STRING of its name.
    ///
    /// A TAG SWAP, not a rebuild. The payload and the length are already in
    /// the right places -- only the three bits saying which of the two this is
    /// differ -- and every runtime was going through `inline_str(inline_bytes
    /// (v))`, which on both ports allocates a byte array to copy eight bytes
    /// onto themselves.
    /// GENERATED (`kin/valtag.kin`). This spelled the payload mask out as a
    /// literal, as both ports did -- three copies of `PAYLOAD` with the
    /// constant declared in every one of the three files.
    #[inline(always)]
    pub fn kw_to_str(self) -> Value {
        crate::kgen::rt::valtag::kw_as_str(self)
    }

    /// Length in bytes of an inline string/keyword payload.
    /// GENERATED as `inline_length`, which answers the `I32` the ports use;
    /// this runtime wants a `usize` for indexing.
    #[inline(always)]
    pub fn inline_len(self) -> usize {
        crate::kgen::rt::valtag::inline_length(self) as usize
    }

    /// Copy the inline payload into `buf` and return the populated slice.
    #[inline]
    pub fn inline_bytes(self, buf: &mut [u8; INLINE_MAX]) -> &[u8] {
        let n = self.inline_len();
        let payload = self.0 & 0x0000_00FF_FFFF_FFFF;
        for i in 0..n {
            buf[i] = ((payload >> (8 * i)) & 0xFF) as u8;
        }
        &buf[..n]
    }

    /// Convenience alias so test code can say `Value::NIL_` without importing
    /// the free constant.
    pub const NIL_: Value = NIL;

    /// A character: a one-character string, always inline.
    #[inline]
    pub fn char_value(c: char) -> Value {
        let mut buf = [0u8; 4];
        Self::inline_str(c.encode_utf8(&mut buf).as_bytes())
    }
}

impl fmt::Debug for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.is_double() {
            write!(f, "d{}", self.as_f64())
        } else {
            match self.tag() {
                TAG_FIXNUM => write!(f, "{}", self.as_fixnum()),
                TAG_HEAP => write!(f, "#<heap {:#x}>", self.as_heap()),
                TAG_SPECIAL => match self.0 & 0xFF {
                    SPECIAL_NIL => write!(f, "nil"),
                    SPECIAL_TRUE => write!(f, "true"),
                    SPECIAL_FALSE => write!(f, "false"),
                    _ => write!(f, "#<sentinel {}>", self.0 & 0xFF),
                },
                TAG_STR | TAG_KW => {
                    let mut buf = [0u8; INLINE_MAX];
                    let n = self.inline_len();
                    let b = self.inline_bytes(&mut buf);
                    let s = core::str::from_utf8(b).unwrap_or("?");
                    if self.tag() == TAG_KW {
                        write!(f, ":{}", s)
                    } else {
                        write!(f, "\"{}\"({})", s, n)
                    }
                }
                _ => write!(f, "#<bad {:#x}>", self.0),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mem::Addr;

    #[test]
    fn doubles_roundtrip() {
        for d in [
            0.0,
            -0.0,
            1.0,
            -1.0,
            0.1,
            -0.1,
            f64::MAX,
            f64::MIN,
            f64::MIN_POSITIVE,
            f64::INFINITY,
            f64::NEG_INFINITY,
            1e308,
            -1e308,
            5e-324,
        ] {
            let v = Value::from_f64(d);
            assert!(v.is_double(), "{d} should stay a double");
            assert_eq!(v.as_f64().to_bits(), d.to_bits(), "roundtrip {d}");
        }
        // NaN is preserved as *a* NaN, canonicalised.
        let v = Value::from_f64(f64::NAN);
        assert!(v.is_double() && v.as_f64().is_nan());
        // Even a hostile negative NaN stays a NaN and never aliases a tag.
        let hostile = f64::from_bits(0xFFFF_FFFF_FFFF_FFFF);
        let v = Value::from_f64(hostile);
        assert!(v.is_double() && v.as_f64().is_nan());
        assert_eq!(v.bits(), CANONICAL_NAN);
    }

    #[test]
    fn no_double_aliases_a_tag() {
        // Exhaustively: which 16-bit prefixes can a real double have?
        // Everything below TAG_MIN_BOXED. Confirm the stolen range is only NaN.
        for hi in TAG_MIN_BOXED..=0xFFFF {
            let bits = hi << 48;
            let d = f64::from_bits(bits);
            assert!(d.is_nan(), "{hi:#x} must be NaN to be stealable");
        }
    }

    #[test]
    fn fixnums() {
        for n in [
            0i64,
            1,
            -1,
            42,
            -42,
            FIXNUM_MAX,
            FIXNUM_MIN,
            FIXNUM_MAX - 1,
            FIXNUM_MIN + 1,
            1 << 30,
            -(1 << 30),
        ] {
            assert!(Value::fits_fixnum(n));
            let v = Value::fixnum(n);
            assert!(v.is_fixnum() && !v.is_double(), "{n}");
            assert_eq!(v.as_fixnum(), n);
        }
        assert!(!Value::fits_fixnum(FIXNUM_MAX + 1));
        assert!(!Value::fits_fixnum(FIXNUM_MIN - 1));
        assert!(!Value::fits_fixnum(i64::MAX));
    }

    #[test]
    fn specials() {
        assert!(NIL.is_nil() && !NIL.truthy());
        assert!(FALSE.is_false() && !FALSE.truthy());
        assert!(TRUE.is_true() && TRUE.truthy());
        assert!(Value::fixnum(0).truthy(), "0 is truthy in Clojure");
        assert!(Value::from_f64(0.0).truthy());
        assert!(Value::inline_str(b"").truthy(), "\"\" is truthy");
        assert_ne!(NIL, FALSE);
        assert_ne!(NOT_FOUND, NIL);
    }

    #[test]
    fn inline_strings() {
        let mut buf = [0u8; INLINE_MAX];
        for s in ["", "a", "ab", "abc", "abcd", "abcde", "é", "日本", "€ab"] {
            if s.len() > INLINE_MAX {
                continue;
            }
            let v = Value::inline_str(s.as_bytes());
            assert!(v.is_inline_str() && !v.is_double(), "{s}");
            assert_eq!(v.inline_len(), s.len());
            assert_eq!(core::str::from_utf8(v.inline_bytes(&mut buf)).unwrap(), s);
        }
        // Inline is canonical, so equality is a bit compare.
        assert_eq!(Value::inline_str(b"abc"), Value::inline_str(b"abc"));
        assert_ne!(Value::inline_str(b"abc"), Value::inline_str(b"abd"));
        assert_ne!(Value::inline_str(b"ab"), Value::inline_kw(b"ab"));
    }

    #[test]
    fn every_char_is_inline() {
        let mut buf = [0u8; INLINE_MAX];
        for cp in (0u32..0x11000).chain(0x10FF00..0x110000) {
            if let Some(c) = char::from_u32(cp) {
                let v = Value::char_value(c);
                assert!(v.is_inline_str(), "U+{cp:04X} must be inline");
                let s = core::str::from_utf8(v.inline_bytes(&mut buf)).unwrap();
                assert_eq!(s.chars().next().unwrap(), c);
                assert_eq!(s.chars().count(), 1);
            }
        }
    }

    /// A heap reference round-trips across the WHOLE payload, not just the
    /// first 4 GB.
    ///
    /// `0x0000_FFFF_FFFF_FFFF` is the largest address the encoding can carry:
    /// `TAG_HEAP` occupies bits 63..48 and everything below is payload. The
    /// address type used to be `u32`, which capped flint's heap at 4 GB on
    /// every runtime -- a limit inherited from wasm32 rather than one the
    /// representation ever had. This asserts the range is really there, and
    /// that the tag is not read back as part of the address.
    #[test]
    fn heap_refs() {
        for off in [8 as Addr, 16, 0x1000, u32::MAX as Addr,
                    0x1_0000_0000, 0x0000_FFFF_FFFF_FFF8] {
            let v = Value::heap(off);
            assert!(v.is_heap() && !v.is_double(), "{off:#x} did not encode as a heap ref");
            assert_eq!(v.as_heap(), off, "{off:#x} did not survive the round trip");
        }
    }
}
