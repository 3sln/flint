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

use crate::mem::{Addr, Space};
use crate::value::Value;

pub const HDR: Addr = 8;
pub const STR_DATA: Addr = 16;

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
pub const TY_CLOSURE: u8 = 21; // [fnidx, ...upvals, meta]
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
/// A rope node (`DECISIONS.md#strings-and-matching`): [bytes, cp<<1|ascii, flat-cache, ...kids]
/// where every child is itself a string of any tier. The aggregates are stored
/// rather than derived, and they are RELATIVE -- a node knows the size of its own
/// subtree and never where it sits, because the same leaf appears at different
/// offsets in `(str a b)` and `(str b a)` and sharing is the point.
pub const TY_ROPE: u8 = 42;
/// An opaque value (`DECISIONS.md#opaque-values`): `[label, hash, host-id]`.
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
/// A tagged literal: `[tag, form]`, where `tag` is a namespaced SYMBOL.
///
/// Its own type rather than a two-key map (`DECISIONS.md#tagged-literals`). A map is
/// ambiguous with a map in every format that has tags -- a codec meeting one
/// cannot tell a tagged literal from a map that happens to have those keys --
/// and it loses the namespace when the key has to become a string.
///
/// It still ANSWERS the map protocols on `:tag` and `:form`, so nothing that
/// treats one as a map has to learn a new way to read it.
pub const TY_TAGGED: u8 = 47;
/// A SCHEMA: `[names, types, index]` -- a vector of column names, a vector of
/// column types, and a map from name to position. One per table, shared by
/// every chunk (`DECISIONS.md#tables`).
pub const TY_SCHEMA: u8 = 48;
/// A TABLE: `[schema, chunks, count]`.
///
/// `chunks` is an ordinary flint VECTOR, which is already a 32-way
/// path-copying trie -- so the "B-tree keyed by row index" `tables` asks for is
/// the vector we have, with chunks as its elements. `get` descends it and
/// `assoc` gets its path copy for free; no new tree is written.
pub const TY_TABLE: u8 = 49;
/// A ROW REF: `[schema, chunk, row-in-chunk]`.
///
/// What iterating a table yields. It holds the CHUNK and not the table, so
/// keeping one row out of a million retains one chunk. It cannot dangle,
/// because chunks are persistent.
pub const TY_TABLEREF: u8 = 50;
/// A TRANSIENT TABLE: `[schema, chunks, count, open, live]`.
///
/// `open` is a chunk allocated at full width that rows are written INTO, in
/// place, until it fills -- at which point it is collapsed, sealed and conj'd
/// onto `chunks`. Appending through the persistent path copies the chunk per
/// row, which is 256 copies per chunk and 49 MB to build 20 000 rows; measured
/// against 3.25 MB for the bulk path (`DECISIONS.md#tables` step 7).
///
/// Like every transient it is NOT a value: no `eq`, no `hash`, no `kind`. It is
/// a building site, and `persistent!` is what turns it back into a table.
pub const TY_TTABLE: u8 = 51;
/// A WIRE WRITER: `[WR_BUF, WR_LIVE]`.
///
/// An OPAQUE handle around a transient byte string, and opaque is the whole
/// point (`DECISIONS.md#the-codec-is-guest-code`). The guest appends through
/// the `wire-*` primitives and cannot reach the buffer, so it cannot write a
/// `K_PORT` tag followed by an id it does not hold. A transient byte string
/// handed to the guest directly would give exactly that away, which is why this
/// is a type of its own rather than `TY_TBYTES` under another name.
///
/// Like every transient it is NOT a value: no `eq`, no `hash`, no `kind`.
pub const TY_WRITER: u8 = 52;

/// A WIRE READER: `[RD_BYTES, RD_POS, RD_LIVE]`.
///
/// The counterpart to `TY_WRITER`, and NOT its mirror image. A writer must be
/// opaque because a guest that can write raw bytes can forge a `K_PORT` tag; a
/// reader has no such problem, because reading bytes a guest already has tells
/// it nothing it did not know. So the reader may hand out plain integers and
/// strings freely, and only two of its operations are guarded.
///
/// `RD_LIVE` is that guard: whether this reader MAY MINT a port or an opaque.
/// It is true only for a reader the runtime made over bytes that arrived on a
/// bridge. A guest constructing one over its own bytes always gets false, so
/// the rule `decode_guest` enforced by refusing tags is now a flag on the
/// reader, set in one place.
pub const TY_READER: u8 = 53;

pub const TY_MAX: u8 = 54;

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
        TY_SCHED, TY_ROPE, TY_OPAQUE, TY_BYTES, TY_BROPE, TY_TBYTES, TY_TAGGED,
        TY_SCHEMA, TY_TABLE, TY_TABLEREF, TY_TTABLE, TY_WRITER, TY_READER,
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
/// GENERATED (`kin/objsize.kin`). Which of the three shapes `ty` has.
pub fn layout_of(ty: u8) -> Layout {
    crate::kgen::rt::objsize::layout_of(ty)
}

#[inline(always)]
pub fn align8(n: Addr) -> Addr {
    crate::kgen::rt::objhdr::obj_align8(n)
}

/// Byte size of an object in the heap, always a multiple of 8.
#[inline]
/// A byte SIZE, but `Addr`-wide: it is almost always added to an address, and
/// a size that had to be cast at every such site would be a cast per line.
pub fn size_of(sp: &Space, addr: Addr) -> Addr {
    crate::kgen::rt::objsize::obj_size_of(sp, addr)
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
                    Layout::Raw => assert_eq!(s, align8(HDR + len as Addr), "raw type {t}"),
                    Layout::Str => assert_eq!(s, align8(STR_DATA + len as Addr), "str type {t}"),
                    Layout::Vals => assert_eq!(s, HDR + len as Addr * 8, "vals type {t}"),
                }
            }
        }
    }
}

#[inline]
/// GENERATED (`kin/objsize.kin`). How many BYTES an object occupies -- and
/// so, after `>> 3`, what every allocation in flint costs in gas.
pub fn size_for(ty: u8, len: u32) -> Addr {
    crate::kgen::rt::objsize::size_for(ty, len)
}

// --- header fields ---------------------------------------------------------

#[inline(always)]
/// GENERATED (`kin/objhdr.kin`). The header layer is rooted at the SPACE and
/// not at the `Rt`, which is why it needed a `Space` tag in kin before it
/// could be generated at all: the collector reads these from `&mut self`
/// methods of `Gc`, and `Gc` is a field of `Rt`.
///
/// The three copies AGREED before the port -- every shift and mask, compared
/// across all three runtimes -- so this is a consolidation and not a fix.
pub fn ty(sp: &Space, a: Addr) -> u8 {
    crate::kgen::rt::objhdr::obj_ty(sp, a) as u8
}
#[inline(always)]
pub fn len(sp: &Space, a: Addr) -> u32 {
    crate::kgen::rt::objhdr::obj_len(sp, a)
}
#[inline(always)]
/// Stamp a forwarding pointer, and read one back.
///
/// A forwarded object's new address does NOT fit in `len` any more: an address
/// is 48 bits and `len` is 32. It does not need a wider header either -- word 0
/// is `[31:24] type [23:21] age [20] mark [19] in-remset`, and a `TY_FWD` has
/// no age, no mark and is in no remembered set, so its whole low 24 bits are
/// free. 24 + 32 = 56 bits, against the 48 an address can hold.
///
/// The two live together so the packing cannot drift; splitting them is how a
/// reader ends up with half the address.
pub fn set_forward(sp: &Space, a: Addr, dest: Addr) {
    crate::kgen::rt::objhdr::obj_set_forward(sp, a, dest)
}

#[inline]
/// GENERATED. The low word is widened by `addr-of-u32`, which ZERO-extends
/// on all three targets. `to-addr` is the trap and is not a hypothetical: put
/// it here instead and the drivers report -2147483647 and -1 on both ports
/// while this one stays correct, because Rust's `as Addr` from a `u32`
/// zero-extends on its own and an `int` on the ports does not.
pub fn forward_target(sp: &Space, a: Addr) -> Addr {
    crate::kgen::rt::objhdr::obj_forward_target(sp, a)
}

pub fn write_header(sp: &Space, a: Addr, ty: u8, len: u32) {
    crate::kgen::rt::objhdr::obj_write_header(sp, a, ty as u32, len)
}
#[inline(always)]
pub fn age(sp: &Space, a: Addr) -> u32 {
    crate::kgen::rt::objhdr::obj_age(sp, a)
}
#[inline(always)]
pub fn set_age(sp: &Space, a: Addr, age: u32) {
    crate::kgen::rt::objhdr::obj_set_age(sp, a, age)
}
#[inline(always)]
pub fn marked(sp: &Space, a: Addr) -> bool {
    crate::kgen::rt::objhdr::obj_marked(sp, a)
}
#[inline(always)]
pub fn set_marked(sp: &Space, a: Addr, m: bool) {
    crate::kgen::rt::objhdr::obj_set_marked(sp, a, m)
}
/// Strings record whether they are pure ASCII, because if they are, a code
/// point index IS a byte index. Without this, `subs` and `nth` are O(n) and
/// splitting a string is quadratic -- which is exactly what the `words`
/// benchmark showed.
/// Rope slots. A node is `[byte-len, (code-points << 1) | ascii, flat, kids..]`.
pub const RP_BYTES: u32 = 0;
pub const RP_CPS: u32 = 1;
/// The flattened form, once something has asked for contiguous bytes. `nil`
/// until then. This is the cache `DECISIONS.md#strings-and-matching` calls the important part
/// of the design -- and the thing whose hit rate has to be COUNTED, because a
/// rope that flattens on every operation passes every correctness test and is
/// slower than the flat string it replaced.
pub const RP_FLAT: u32 = 2;
/// The subtree's content hash, computed once and reused -- `nil` until asked.
///
/// A flat string caches its hash in the `Str` header; a rope had nowhere to put
/// one, so hashing a rope FLATTENED it and hashed the result. That is why the
/// flatten was there, and it worked: a rope used as a map key paid once. It
/// also spent the sharing, which is the thing the tree exists for.
///
/// A per-node hash removes the trade. Clojure's string hash is `h = 31h + c`,
/// which COMPOSES: `h(A·B) = h(A)·31^|B| + h(B)`. So a node combines its
/// children's cached hashes, and a subtree that has already been hashed -- which
/// after a sharing `subs` is most of them -- costs one slot read.
pub const RP_HASH: u32 = 3;
pub const RP_KIDS: u32 = 4;

#[inline(always)]
pub fn str_is_ascii(sp: &Space, a: Addr) -> bool {
    crate::kgen::rt::objhdr::obj_str_ascii(sp, a)
}
#[inline(always)]
pub fn set_str_ascii(sp: &Space, a: Addr, v: bool) {
    crate::kgen::rt::objhdr::obj_set_str_ascii(sp, a, v)
}

#[inline(always)]
pub fn in_remset(sp: &Space, a: Addr) -> bool {
    crate::kgen::rt::objhdr::obj_in_remset(sp, a)
}
#[inline(always)]
pub fn set_in_remset(sp: &Space, a: Addr, m: bool) {
    crate::kgen::rt::objhdr::obj_set_in_remset(sp, a, m)
}

// --- slot access -----------------------------------------------------------

#[inline(always)]
pub fn slot_addr(a: Addr, i: u32) -> Addr {
    a + HDR + i as Addr * 8
}
#[inline(always)]
pub fn slot(sp: &Space, a: Addr, i: u32) -> Value {
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
pub fn set_slot_raw(sp: &Space, a: Addr, i: u32, v: Value) {
    sp.write_u64(slot_addr(a, i), v.0)
}

#[inline]
pub fn str_bytes<'a>(sp: &'a Space, a: Addr) -> &'a [u8] {
    // A rope's `len` is its SLOT COUNT and its body is Values, so reading it
    // here returns the slots as bytes -- garbage that looks like a string. Two
    // callers did exactly that (`char_count` and `str_indexable`) and `count`
    // came back as the number of children.
    debug_assert!(ty(sp, a) == TY_STR, "str_bytes on a non-flat string");
    sp.bytes(a + STR_DATA, len(sp, a))
}
#[inline]
pub fn str_hash(sp: &Space, a: Addr) -> u32 {
    sp.read_u32(a + HDR)
}
#[inline]
pub fn set_str_hash(sp: &Space, a: Addr, h: u32) {
    sp.write_u32(a + HDR, h)
}
#[inline]
pub fn raw_bytes<'a>(sp: &'a Space, a: Addr) -> &'a [u8] {
    sp.bytes(a + HDR, len(sp, a))
}
