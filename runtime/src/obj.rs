//! Heap object layout.
//!
//! Every object starts with an 8-byte header:
//!
//! ```text
//!   +0 u32  [31:24] type  [23:21] age  [20] mark  [19] in-remembered-set
//!   +4 u32  len   -- meaning depends on the layout class
//! ```
//!
//! There are only three layout classes:
//!
//! * `Vals`  - `len` 64-bit `Value` slots follow the header. **Every** slot is a
//!   `Value`, including things that are morally integers (they are fixnums).
//!   That makes tracing one loop with no per-type knowledge, which is the single
//!   biggest source of GC bugs removed.
//! * `Str`   - a cached hash `u32` at +8, then `len` UTF-8 bytes at +16.
//! * `Raw`   - `len` opaque bytes at +8 (boxed i64, compiled regex programs).
//!
//! Sizes are always a multiple of 8.

use crate::mem::Space;
use crate::value::Value;

pub const HDR: u32 = 8;
pub const STR_DATA: u32 = 16;

// --- object types ----------------------------------------------------------
pub const TY_FREE: u8 = 0; // old-space free block; len = byte size
pub const TY_FWD: u8 = 1; // forwarded; len = new address
pub const TY_STR: u8 = 2;
pub const TY_BIGINT: u8 = 3; // a long outside fixnum range; raw 8 bytes
pub const TY_SYM: u8 = 4; // [ns, name, meta, hash]
pub const TY_KW: u8 = 5; // [ns, name, hash]  (only when not inline)
pub const TY_CONS: u8 = 6; // [first, rest, meta, count]
pub const TY_EMPTY_LIST: u8 = 7; // [meta]
pub const TY_LAZYSEQ: u8 = 8; // [thunk, seq, meta]
pub const TY_VEC: u8 = 9; // [cnt, shift, root, tail, meta]
pub const TY_NODE: u8 = 10; // len values
pub const TY_VECSEQ: u8 = 11; // [vec, i, meta]
pub const TY_STRSEQ: u8 = 12; // [str, byte-index, meta]
pub const TY_RANGE: u8 = 13; // [start, end, step, meta]
pub const TY_ARRAYMAP: u8 = 14; // [meta, k0,v0, ...]
pub const TY_HASHMAP: u8 = 15; // [cnt, root, has_nil, nil_val, meta]
pub const TY_BMNODE: u8 = 16; // [bitmap, ...entries]
pub const TY_ARRAYNODE: u8 = 17; // [count, 32 children]
pub const TY_COLLNODE: u8 = 18; // [hash, k0,v0, ...]
pub const TY_SET: u8 = 19; // [map, meta]
pub const TY_MAPENTRY: u8 = 20; // [k, v]
pub const TY_CLOSURE: u8 = 21; // [fnidx, ...upvals]
pub const TY_NATIVEFN: u8 = 22; // [id, name]
pub const TY_VAR: u8 = 23; // [sym, value, meta]
pub const TY_ATOM: u8 = 24; // [value, meta]
pub const TY_TVEC: u8 = 25; // [cnt, shift, root, tail, live]
pub const TY_TMAP: u8 = 26; // [cnt, root, has_nil, nil_val, live]
pub const TY_TSET: u8 = 27; // [tmap, live]
pub const TY_RECORD: u8 = 28; // [type, basis, ext, meta, ...fields]
pub const TY_REGEX: u8 = 29; // [source, prog(raw), ngroups]
pub const TY_REDUCED: u8 = 30; // [value]
pub const TY_EXINFO: u8 = 31; // [msg, data, cause]
pub const TY_MULTIFN: u8 = 32; // [name, dispatch, methods, default, prefers]
pub const TY_DELAY: u8 = 33; // [thunk, value]
pub const TY_VOLATILE: u8 = 34; // [value]
pub const TY_RAW: u8 = 35; // opaque bytes
pub const TY_ITERSEQ: u8 = 36; // [coll, cursor-state..., meta] generic
pub const TY_CHUNKSEQ: u8 = 37; // [node(array), off, rest, meta]
pub const TY_TYPE: u8 = 38; // [name, basis, protocols(map)]  runtime type object
// --- concurrency (runtime/src/conc.rs). Vals layout like everything else, so
// --- the collector traces a parked thread's saved stack with no new code.
pub const TY_THREAD: u8 = 39; // see conc::TH_*
pub const TY_PORT: u8 = 40; // see conc::PT_*
pub const TY_SCHED: u8 = 41; // see conc::SC_*
/// A rope node (`doc/decisions/0011`): [bytes, cp<<1|ascii, flat-cache, ...kids]
/// where every child is itself a string of any tier. The aggregates are stored
/// rather than derived, and they are RELATIVE -- a node knows the size of its own
/// subtree and never where it sits, because the same leaf appears at different
/// offsets in `(str a b)` and `(str b a)` and sharing is the point.
pub const TY_ROPE: u8 = 42;
/// An opaque value (`doc/decisions/0022`): `[label, hash, host-id]`.
///
/// Identity without structure -- flint's replacement for Clojure's `(Object.)`,
/// which it cannot have because it has no host classes. Two of them are `=`
/// only when they are the same object.
///
/// The hash is STORED rather than derived from the address, because the nursery
/// is a copying collector and objects move: an address-derived identity hash
/// would change under collection and a value used as a map key would become
/// unfindable by the key that put it there.
///
/// `host-id` is 0 for anything guest code minted and non-zero only for a value
/// the host made. Guest code cannot read it, and -- see 0022 -- authority is
/// never the type test but always the host recognising THIS object.
pub const TY_OPAQUE: u8 = 43;
/// A flat byte string: `Layout::Raw`, the bytes immediately after the header.
/// Like `TY_STR` and deliberately not it -- a byte string carries no UTF-8
/// semantics, so it has no code-point count and cannot be indexed by character.
pub const TY_BYTES: u8 = 44;
/// A byte rope: `[BB_BYTES, BB_FLAT, kids...]`. The same shallow B-tree as
/// `TY_ROPE` and simpler, because a node needs only its subtree's byte length
/// -- there is no code-point count to sum and no ASCII bit to AND.
pub const TY_BROPE: u8 = 45;
/// A transient byte string: `[TB_TREE, TB_TAIL, TB_FILL, TB_LIVE]`. The tail
/// is a `TY_BYTES` the transient owns and writes into, which is what makes
/// appending amortise to O(1) instead of copying the whole thing each time.
pub const TY_TBYTES: u8 = 46;
pub const TY_MAX: u8 = 47;

/// Every type tag must be distinct. This list exists because they were not:
/// `TY_THREAD`/`TY_PORT`/`TY_SCHED` were first numbered 33..35, which silently
/// aliased `TY_DELAY`/`TY_VOLATILE`/`TY_RAW` -- so the scheduler object was laid
/// out as *raw bytes*, `size_of` said 16 instead of 56, and the next allocation
/// landed inside it. Nothing failed loudly; the scheduler simply read garbage.
const _: () = {
    let tags = [
        TY_FREE, TY_FWD, TY_STR, TY_BIGINT, TY_SYM, TY_KW, TY_CONS, TY_EMPTY_LIST,
        TY_LAZYSEQ, TY_VEC, TY_NODE, TY_VECSEQ, TY_STRSEQ, TY_RANGE, TY_ARRAYMAP,
        TY_HASHMAP, TY_BMNODE, TY_ARRAYNODE, TY_COLLNODE, TY_SET, TY_MAPENTRY,
        TY_CLOSURE, TY_NATIVEFN, TY_VAR, TY_ATOM, TY_TVEC, TY_TMAP, TY_TSET,
        TY_RECORD, TY_REGEX, TY_REDUCED, TY_EXINFO, TY_MULTIFN, TY_DELAY,
        TY_VOLATILE, TY_RAW, TY_ITERSEQ, TY_CHUNKSEQ, TY_TYPE, TY_THREAD, TY_PORT,
        TY_SCHED, TY_ROPE, TY_OPAQUE, TY_BYTES, TY_BROPE, TY_TBYTES,
    ];
    let mut i = 0;
    while i < tags.len() {
        assert!(tags[i] < TY_MAX, "a type tag is >= TY_MAX");
        let mut j = i + 1;
        while j < tags.len() {
            assert!(tags[i] != tags[j], "two object types share a tag");
            j += 1;
        }
        i += 1;
    }
};

#[derive(PartialEq, Eq, Clone, Copy, Debug)]
pub enum Layout {
    Vals,
    Str,
    Raw,
}

#[inline]
pub fn layout_of(ty: u8) -> Layout {
    match ty {
        TY_STR => Layout::Str,
        TY_BIGINT | TY_RAW | TY_BYTES | TY_FREE | TY_FWD => Layout::Raw,
        _ => Layout::Vals,
    }
}

#[inline(always)]
pub fn align8(n: u32) -> u32 {
    (n + 7) & !7
}

/// Byte size of an object in the heap, always a multiple of 8.
#[inline]
pub fn size_of(sp: &Space, addr: u32) -> u32 {
    let w0 = sp.read_u32(addr);
    let ty = (w0 >> 24) as u8;
    let len = sp.read_u32(addr + 4);
    // Two special cases, then `size_for`. This used to carry its own copy of
    // the layout match, and a type added to `layout_of` but not to that copy
    // was sized as `HDR + len * 8` -- a 513-byte `TY_BYTES` measured as 4 112.
    // The collector then walked from-space with the wrong stride, and the
    // symptom was `forward: N is not the start of a from-space object` about
    // an object that was plainly fine. Deriving it removes the second table.
    match ty {
        TY_FREE => len,
        TY_FWD => HDR,
        _ => size_for(ty, len),
    }
}

/// `size_of` and `size_for` must agree for every type, since one measures a
/// live object and the other reserves room for a new one. They are the same
/// function now, and this asserts the layouts they share are total.
#[cfg(test)]
mod layout_tests {
    use super::*;

    #[test]
    fn every_type_has_a_layout_and_one_size() {
        for t in 0..TY_MAX {
            if t == TY_FREE || t == TY_FWD {
                continue;
            }
            for len in [0u32, 1, 7, 8, 9, 513, 1024] {
                let s = size_for(t, len);
                assert!(s >= HDR, "type {t} len {len} sized {s}");
                assert_eq!(s % 8, 0, "type {t} len {len} sized {s}, not 8-aligned");
                match layout_of(t) {
                    Layout::Raw => assert_eq!(s, align8(HDR + len), "raw type {t}"),
                    Layout::Str => assert_eq!(s, align8(STR_DATA + len), "str type {t}"),
                    Layout::Vals => assert_eq!(s, HDR + len * 8, "vals type {t}"),
                }
            }
        }
    }
}

#[inline]
pub fn size_for(ty: u8, len: u32) -> u32 {
    match layout_of(ty) {
        Layout::Vals => HDR + len * 8,
        Layout::Str => align8(STR_DATA + len),
        Layout::Raw => align8(HDR + len),
    }
}

// --- header fields ---------------------------------------------------------

#[inline(always)]
pub fn ty(sp: &Space, a: u32) -> u8 {
    (sp.read_u32(a) >> 24) as u8
}
#[inline(always)]
pub fn len(sp: &Space, a: u32) -> u32 {
    sp.read_u32(a + 4)
}
#[inline(always)]
pub fn set_len(sp: &Space, a: u32, n: u32) {
    sp.write_u32(a + 4, n)
}
#[inline(always)]
pub fn write_header(sp: &Space, a: u32, ty: u8, len: u32) {
    sp.write_u32(a, (ty as u32) << 24);
    sp.write_u32(a + 4, len);
}
#[inline(always)]
pub fn age(sp: &Space, a: u32) -> u32 {
    (sp.read_u32(a) >> 21) & 7
}
#[inline(always)]
pub fn set_age(sp: &Space, a: u32, age: u32) {
    let w = sp.read_u32(a);
    sp.write_u32(a, (w & !(7 << 21)) | ((age & 7) << 21));
}
#[inline(always)]
pub fn marked(sp: &Space, a: u32) -> bool {
    sp.read_u32(a) & (1 << 20) != 0
}
#[inline(always)]
pub fn set_marked(sp: &Space, a: u32, m: bool) {
    let w = sp.read_u32(a);
    sp.write_u32(a, if m { w | (1 << 20) } else { w & !(1 << 20) });
}
/// Strings record whether they are pure ASCII, because if they are, a code
/// point index IS a byte index. Without this, `subs` and `nth` are O(n) and
/// splitting a string is quadratic -- which is exactly what the `words`
/// benchmark showed.
/// Rope slots. A node is `[byte-len, (code-points << 1) | ascii, flat, kids..]`.
pub const RP_BYTES: u32 = 0;
pub const RP_CPS: u32 = 1;
/// The flattened form, once something has asked for contiguous bytes. `nil`
/// until then. This is the cache `doc/decisions/0011` calls the important part
/// of the design -- and the thing whose hit rate has to be COUNTED, because a
/// rope that flattens on every operation passes every correctness test and is
/// slower than the flat string it replaced.
pub const RP_FLAT: u32 = 2;
pub const RP_KIDS: u32 = 3;

#[inline(always)]
pub fn str_is_ascii(sp: &Space, a: u32) -> bool {
    sp.read_u32(a) & (1 << 18) != 0
}
#[inline(always)]
pub fn set_str_ascii(sp: &Space, a: u32, v: bool) {
    let w = sp.read_u32(a);
    sp.write_u32(a, if v { w | (1 << 18) } else { w & !(1 << 18) });
}

#[inline(always)]
pub fn in_remset(sp: &Space, a: u32) -> bool {
    sp.read_u32(a) & (1 << 19) != 0
}
#[inline(always)]
pub fn set_in_remset(sp: &Space, a: u32, m: bool) {
    let w = sp.read_u32(a);
    sp.write_u32(a, if m { w | (1 << 19) } else { w & !(1 << 19) });
}

// --- slot access -----------------------------------------------------------

#[inline(always)]
pub fn slot_addr(a: u32, i: u32) -> u32 {
    a + HDR + i * 8
}
#[inline(always)]
pub fn slot(sp: &Space, a: u32, i: u32) -> Value {
    let out = Value(sp.read_u64(slot_addr(a, i)));
    // Reading a forwarded pointer outside the collector means the edge INTO
    // this object was never traced: the collector moved the target and nothing
    // updated this slot. Asserting it *here*, in the universal accessor, costs
    // one check in debug builds and catches the whole class -- rather than
    // needing a new check at each place a stale pointer happens to surface.
    // The collector itself reads forwarded pointers as a matter of course,
    // which is how it updates them, so it is excluded.
    #[cfg(debug_assertions)]
    {
        debug_assert!(
            sp.in_gc.get() || !(out.is_heap() && ty(sp, out.as_heap()) == TY_FWD),
            "a forwarded pointer was read from slot {} of a type-{} object",
            i,
            ty(sp, a)
        );
    }
    out
}
/// Raw slot store. Callers that may be writing into an *old* object must go
/// through `Gc::set_slot` so the write barrier runs.
#[inline(always)]
pub fn set_slot_raw(sp: &Space, a: u32, i: u32, v: Value) {
    sp.write_u64(slot_addr(a, i), v.0)
}

#[inline]
pub fn str_bytes<'a>(sp: &'a Space, a: u32) -> &'a [u8] {
    // A rope's `len` is its SLOT COUNT and its body is Values, so reading it
    // here returns the slots as bytes -- garbage that looks like a string. Two
    // callers did exactly that (`char_count` and `str_indexable`) and `count`
    // came back as the number of children.
    debug_assert!(ty(sp, a) == TY_STR, "str_bytes on a non-flat string");
    sp.bytes(a + STR_DATA, len(sp, a))
}
#[inline]
pub fn str_hash(sp: &Space, a: u32) -> u32 {
    sp.read_u32(a + HDR)
}
#[inline]
pub fn set_str_hash(sp: &Space, a: u32, h: u32) {
    sp.write_u32(a + HDR, h)
}
#[inline]
pub fn raw_bytes<'a>(sp: &'a Space, a: u32) -> &'a [u8] {
    sp.bytes(a + HDR, len(sp, a))
}
