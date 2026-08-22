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
pub const TY_MAX: u8 = 42;

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
        TY_SCHED,
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
        TY_BIGINT | TY_RAW | TY_FREE | TY_FWD => Layout::Raw,
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
    match ty {
        TY_FREE => len,
        TY_FWD => HDR,
        TY_STR => align8(STR_DATA + len),
        TY_BIGINT | TY_RAW => align8(HDR + len),
        _ => HDR + len * 8,
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
    Value(sp.read_u64(slot_addr(a, i)))
}
/// Raw slot store. Callers that may be writing into an *old* object must go
/// through `Gc::set_slot` so the write barrier runs.
#[inline(always)]
pub fn set_slot_raw(sp: &Space, a: u32, i: u32, v: Value) {
    sp.write_u64(slot_addr(a, i), v.0)
}

#[inline]
pub fn str_bytes<'a>(sp: &'a Space, a: u32) -> &'a [u8] {
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
