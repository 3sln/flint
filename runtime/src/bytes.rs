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
use crate::rope::FLAT_MAX;
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
/// The subtree's content hash, or NIL until asked. The mirror of `RP_HASH`,
/// and for the same reason: hashing a byte rope used to FLATTEN it, which
/// bought the caching by spending the sharing.
pub const BB_HASH: u32 = 3;
/// Children start here.
pub const BB_KIDS: u32 = 4;

impl Rt {


    /// A flat byte string of `n` bytes, uninitialised. The caller fills it
    /// before anything can collect, which is why this is not public.
    fn alloc_bytes(&mut self, n: u32) -> crate::mem::Addr {
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

    /// The bytes as a HOST vector, for host code outside the port -- the
    /// codec, the builtins that hand bytes to a caller. Inside the port
    /// everything goes through a sink; this is the one place that leaves.
    pub fn b_to_vec(&mut self, v: Value) -> alloc::vec::Vec<u8> {
        let s = self.sink_open();
        self.b_append(v, s);
        let out = self.sinks[s as usize].clone();
        self.sink_close(s);
        out
    }


    /// 0 for a leaf, otherwise the node's recorded depth.



    // ---------------------------------------------------------- the sink
    //
    // Hole 5's other half. See `Rt::sinks` for why a buffer the runtime owns
    // and an index that names it, rather than each target's own growable type.

    // ---------------------------------------------------------- the walk
    //
    // The sink's sibling. See `Rt::walks`.

    /// Start a walk at `v` and answer its index. `walk_close` releases it and
    /// every walk opened after it -- `mark`/`pop_to` again.
    pub fn walk_open(&mut self, v: Value) -> u32 {
        self.walks.push(alloc::vec![(v, 0u32)]);
        (self.walks.len() - 1) as u32
    }

    /// Release `w` and everything opened after it.
    pub fn walk_close(&mut self, w: u32) {
        self.walks.truncate(w as usize);
    }

    /// A COPY of `w`, so a caller can look ahead without consuming. `b_eq`
    /// peeks the other side's next leaf to see whether the two trees share it.
    pub fn walk_dup(&mut self, w: u32) -> u32 {
        let c = self.walks[w as usize].clone();
        self.walks.push(c);
        (self.walks.len() - 1) as u32
    }

    /// Replace `w`'s position with `src`'s. What a peek that paid off does.
    pub fn walk_take(&mut self, w: u32, src: u32) {
        let c = self.walks[src as usize].clone();
        self.walks[w as usize] = c;
    }

    /// The next LEAF, or NIL when the walk is finished.
    ///
    /// ONE WALK FOR BOTH TREES. A rope node and a byte-rope node put their
    /// children at the same offset and count them the same way; the only thing
    /// that differed was which tag says "this is a node". So this takes either,
    /// and `rope.rs` no longer carries a second copy of the same loop.
    pub fn walk_next(&mut self, w: u32) -> Value {
        loop {
            let top = match self.walks[w as usize].last() {
                None => return NIL,
                Some(t) => *t,
            };
            let (node, i) = top;
            if !self.is_brope(node) && !self.is_rope(node) {
                self.walks[w as usize].pop();
                return node;
            }
            let kids = len(&self.gc.sp, node.as_heap()) - BB_KIDS;
            if i >= kids {
                self.walks[w as usize].pop();
                continue;
            }
            if let Some(t) = self.walks[w as usize].last_mut() {
                t.1 = i + 1;
            }
            let k = self.slot(node, BB_KIDS + i);
            self.walks[w as usize].push((k, 0));
        }
    }

    /// Is there nothing left on it?
    pub fn walk_done(&self, w: u32) -> bool {
        self.walks[w as usize].is_empty()
    }

    /// Are `len` bytes at `a` the same as `len` bytes at `b`?
    ///
    /// A RUN COMPARISON rather than a byte loop, because this is the inner
    /// loop of `b_eq` over two 500 KB sections and the difference between one
    /// `memcmp` and half a million calls is the whole cost of the operation.
    pub fn run_eq(&self, a: crate::mem::Addr, b: crate::mem::Addr, len: u32) -> bool {
        self.gc.sp.bytes(a, len) == self.gc.sp.bytes(b, len)
    }

    /// Open a buffer and answer its index. `sink_close` releases it and every
    /// buffer opened after it, which is `pop_to`'s discipline exactly.
    pub fn sink_open(&mut self) -> u32 {
        self.sinks.push(alloc::vec::Vec::new());
        (self.sinks.len() - 1) as u32
    }

    /// Release `s` and everything opened after it.
    pub fn sink_close(&mut self, s: u32) {
        self.sinks.truncate(s as usize);
    }

    /// How many bytes are in it.
    pub fn sink_len(&self, s: u32) -> u32 {
        self.sinks[s as usize].len() as u32
    }

    /// One byte.
    pub fn sink_put(&mut self, s: u32, b: u32) {
        self.sinks[s as usize].push(b as u8);
    }

    /// A RUN OF HEAP BYTES, `len` of them from `addr`.
    ///
    /// The heap and the sinks are separate fields, so the read borrows one
    /// while the write borrows the other -- which is why this is a method on
    /// `Rt` rather than something a caller could assemble from `read_u8`.
    pub fn sink_put_run(&mut self, s: u32, addr: crate::mem::Addr, len: u32) {
        let src = self.gc.sp.bytes(addr, len);
        self.sinks[s as usize].extend_from_slice(src);
    }

    /// How many bytes a LEAF holds, whichever tier it is.
    ///
    /// An inline string keeps its length in the value, a flat string in its
    /// object header, and a byte leaf in the object's length. One question,
    /// three places to look, and a generated source cannot branch on which
    /// without knowing all three.
    pub fn leaf_len(&self, v: Value) -> u32 {
        if v.is_inline_str() {
            v.inline_len() as u32
        } else if v.is_heap() {
            len(&self.gc.sp, v.as_heap())
        } else {
            0
        }
    }

    /// Byte `i` of a LEAF, whichever tier it is.
    ///
    /// The companion to `leaf_len`, and what lets a walk read leaves without
    /// caring that one of the tiers has no address. Out of range answers zero
    /// rather than refusing: every caller here has already bounded `i` by
    /// `leaf_len`.
    pub fn leaf_byte(&self, v: Value, i: u32) -> u32 {
        if v.is_inline_str() {
            let mut b = crate::rt::sbuf();
            let bs = v.inline_bytes(&mut b);
            if (i as usize) < bs.len() { bs[i as usize] as u32 } else { 0 }
        } else if v.is_heap() {
            let base = if ty(&self.gc.sp, v.as_heap()) == crate::obj::TY_STR {
                v.as_heap() + crate::obj::STR_DATA
            } else {
                v.as_heap() + HDR
            };
            self.gc.sp.read_u8(base + i as crate::mem::Addr) as u32
        } else {
            0
        }
    }

    /// Append an INLINE string's bytes to the sink.
    ///
    /// An inline string keeps its bytes in the VALUE, not the heap, so there
    /// is no address to hand to `sink_put_run` -- and that is the whole of why
    /// a rope's leaves could not be walked by a generated source while a byte
    /// rope's could. Unpacking one is per-target work; putting the result
    /// somewhere is not.
    pub fn sink_put_inline(&mut self, s: u32, v: Value) {
        let mut b = crate::rt::sbuf();
        let src: alloc::vec::Vec<u8> = v.inline_bytes(&mut b).to_vec();
        self.sinks[s as usize].extend_from_slice(&src);
    }

    /// Copy `len` bytes from the sink at `from` INTO the heap at `addr`.
    ///
    /// The other direction from `sink_put_run`, and the only way bytes leave a
    /// sink without becoming a value: appending a whole byte string into a
    /// transient's open tail copies it in runs, and the tail is heap the
    /// transient already owns.
    pub fn sink_copy_out(&mut self, s: u32, from: u32, len: u32, addr: crate::mem::Addr) {
        let src: alloc::vec::Vec<u8> =
            self.sinks[s as usize][from as usize..(from + len) as usize].to_vec();
        self.gc.sp.bytes_mut(addr, len).copy_from_slice(&src);
    }

    /// The sink's contents as a byte string. The sink is left alone -- the
    /// caller closes it, because the caller opened it.
    /// The raw contents, for host code that wants an array rather than a
    /// value. Both ports already had one; Rust reached into `sinks` instead,
    /// which is the same thing spelled differently in one runtime.
    pub fn sink_array(&self, s: u32) -> &[u8] {
        &self.sinks[s as usize]
    }

    pub fn sink_bytes(&mut self, s: u32) -> Value {
        let n = self.sinks[s as usize].len() as u32;
        let a = self.alloc_bytes(n);
        if a == 0 {
            return NIL;
        }
        let src = &self.sinks[s as usize];
        self.gc.sp.bytes_mut(a + HDR, n).copy_from_slice(src);
        Value::heap(a)
    }

    /// The sink's contents as a CONTIGUOUS string -- inline or one flat run,
    /// never a tree.
    ///
    /// NOT the same as `sink_string`, and the difference is not cosmetic:
    /// `string` TIERS, so a big enough result comes back as a rope. A flatten
    /// that answered a rope would put a rope in `RP_FLAT`, and every caller
    /// that flattened in order to get contiguous bytes would be handed a tree
    /// -- which is what it was flattening to escape.
    pub fn sink_contiguous(&mut self, s: u32) -> Value {
        let owned: alloc::vec::Vec<u8> = self.sinks[s as usize].clone();
        let t = core::str::from_utf8(&owned).unwrap_or("");
        self.contiguous_string(t)
    }

    /// The sink's contents as a string. Invalid UTF-8 answers the empty
    /// string, which is what `rope_slice` did before this existed.
    pub fn sink_string(&mut self, s: u32) -> Value {
        let owned: alloc::vec::Vec<u8> = self.sinks[s as usize].clone();
        let t = core::str::from_utf8(&owned).unwrap_or("");
        self.string(t)
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
            assert_eq!(rt.b_at(acc, 0, NIL), Value::fixnum(0), "byte 0 after {i}");
            assert_eq!(rt.b_at(acc, n - 1, NIL), Value::fixnum(255), "last after {i}");
            assert_eq!(rt.b_at(acc, i * 512, NIL), Value::fixnum(0), "piece start after {i}");
        }
        let acc = rt.r(base);
        assert!(rt.b_depth(acc) <= 3, "depth {}", rt.b_depth(acc));
        rt.pop_to(base);
    }

    /// The shape a wasm output builder actually has: hundreds of pieces of
    /// VARYING size, some larger than the tail, appended through a transient
    /// until the result is a few hundred kilobytes. The guest failed somewhere
    /// between 550 and 600 of them, and a Rust panic in wasm is just
    /// `unreachable` -- natively it says what it was.
    #[test]
    fn many_pieces_of_mixed_size_through_a_transient() {
        let mut rt = Rt::new();
        let base = rt.mark();
        let empty = rt.new_bytes(&[]);
        let t = rt.b_transient(empty);
        rt.push(t);
        let mut expect = 0u32;
        for i in 0..900u32 {
            // 1 byte to ~4 KB, so pieces straddle FLAT_MAX and TAIL_CAP.
            let n = 1 + (i * 37) % 4096;
            let piece: alloc::vec::Vec<u8> = (0..n).map(|k| (k % 256) as u8).collect();
            // A length prefix by BYTE and then the payload in bulk, which is
            // exactly what the wasm writer does for every section and body.
            let tv = rt.r(base);
            let out = rt.b_conj(tv, n & 0xff);
            rt.set_r(base, out);
            let p = rt.new_bytes(&piece);
            rt.push(p);
            let (tv, pv) = (rt.r(base), rt.r(base + 1));
            let out = rt.b_append_bytes(tv, pv);
            rt.set_r(base, out);
            rt.pop_to(base + 1);
            expect += n + 1;
            assert_eq!(rt.b_tcount(rt.r(base)), expect, "after {i} pieces");
        }
        let done = rt.b_persistent(rt.r(base));
        rt.set_r(base, done);
        let acc = rt.r(base);
        assert_eq!(rt.b_count(acc), expect);
        // Every byte read back, against what was written. A count that is
        // right while the CONTENT is wrong is the failure a size check misses.
        let mut pos = 0u32;
        for i in 0..900u32 {
            let n = 1 + (i * 37) % 4096;
            assert_eq!(rt.b_at(acc, pos, NIL), Value::fixnum((n & 0xff) as i64), "prefix of piece {i}");
            assert_eq!(rt.b_at(acc, pos + 1, NIL), Value::fixnum(0), "first byte of piece {i}");
            assert_eq!(rt.b_at(acc, pos + n, NIL), Value::fixnum(((n - 1) % 256) as i64), "last byte of piece {i}");
            pos += n + 1;
        }
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
        assert_eq!(rt.b_at(acc, 2048, NIL), Value::fixnum(0));
        assert_eq!(rt.b_at(acc, 64 * 2048 - 1, NIL), Value::fixnum(((2047u32) % 256) as i64));
    }
}
