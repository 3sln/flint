package com.flint.rt;

import static com.flint.rt.Obj.*;
import java.util.ArrayList;

/// Byte strings, ported from `runtime/src/bytes.rs` (`doc/decisions/0024`).
///
/// FLAT is a contiguous `TY_BYTES`; ROPE is a shallow B-tree of byte pieces
/// with structure sharing, so concatenation is a tree join and a slice of a
/// large range shares subtrees.
///
/// ## Why this is not a vector of integers
///
/// Because that was the obvious port and it is wrong by an order of magnitude.
/// A flint vector holds NaN-boxed 64-bit values, so a byte would cost eight
/// bytes plus trie overhead: the 574 KB wasm module this exists to let flint
/// read would become 4.6 MB of payload. A byte string holds a byte in a byte.
///
/// ## The three numbers
///
/// Below `FLAT_MAX` a tree costs more in metadata than the copy it saves;
/// `FANOUT` keeps depth low because depth is what random access pays; and the
/// tail of a transient is exactly `FLAT_MAX`, so a promoted tail is the
/// smallest piece that makes a concat build a node instead of copying.
public final class Bytes {
    private Bytes() {}

    public static final int FLAT_MAX = 1024;
    public static final int FANOUT = 16;

    /// Total byte length of this node's subtree.
    public static final int BB_BYTES = 0;
    /// A cached flattening, or NIL. Materialising a rope repeatedly is the
    /// failure `0011` names -- count the flattens, do not hope about them.
    public static final int BB_FLAT = 1;
    /// The subtree's depth: 1 for a node whose children are all leaves, and one
    /// more per level. EVERY CHILD OF A NODE HAS THE SAME DEPTH, which is what
    /// makes this a B-tree rather than a spine.
    public static final int BB_DEPTH = 2;
    /// The subtree's content hash, or nil until asked -- the mirror of
    /// `RP_HASH`, and for the same reason.
    public static final int BB_HASH = 3;
    public static final int BB_KIDS = 4;

        public static boolean isBytes(Rt rt, long v) { return com._3sln.flint.kgen.rt.Bytecore.isBytes(rt, v); }

        public static int count(Rt rt, long v) { return com._3sln.flint.kgen.rt.Bytecore.bCount(rt, v); }

    public static long of(Rt rt, byte[] b) {
        long a = rt.alloc(TY_BYTES, b.length);
        if (a == 0) return Val.NIL;
        rt.gc.sp.writeBytes(a + HDR, b);
        return Val.heap(a);
    }


    /// The bytes as a HOST array, for host code outside the port. Inside it
    /// everything goes through a sink; this is the one place that leaves.
    public static byte[] toArray(Rt rt, long v) {
        int s = rt.sinkOpen();
        com._3sln.flint.kgen.rt.Byteflat.bAppend(rt, v, s);
        byte[] out = rt.sinkArray(s);
        rt.sinkClose(s);
        return out;
    }

    /// The byte at `i`, or -1. Descends rather than flattening, which is why
    /// depth is what random access pays and why `FANOUT` is 16.
    public static long at(Rt rt, long v, int i, long dflt) { return com._3sln.flint.kgen.rt.Byteat.bAt(rt, v, i, dflt); }

        public static int depth(Rt rt, long v) { return com._3sln.flint.kgen.rt.Bytecore.bDepth(rt, v); }

    /// A leaf joining a deeper node is PROMOTED rather than sitting beside
    /// subtrees: a node's children must all be the same depth.
    static long wrapTo(Rt rt, long v, int d) { return com._3sln.flint.kgen.rt.Bytenode.bWrapTo(rt, v, d); }

    /// A node over the `n` values ALREADY ROOTED at `base`. The caller pushes
    /// and the caller pops -- see the Rust and C# copies, which say the same.
    static long node(Rt rt, int base, int n) { return com._3sln.flint.kgen.rt.Bytenode.bNode(rt, base, n); }

    /// Copy the range out into a fresh leaf. The SINK half, still hand-written,
    /// reached from the generated tree half. `SLICE_MIN` is the retention fix --
    /// a three-byte slice must not keep a 509 KB section alive -- so this path
    /// exists to STOP sharing, deliberately.
    public static long copyRange(Rt rt, long v, int from, int to) { return com._3sln.flint.kgen.rt.Byteflat.bCopyRange(rt, v, from, to); }

    public static long copyConcat(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Byteflat.bCopyConcat(rt, a, b); }

    public static long concat(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Byteconcat.bConcat(rt, a, b); }

    /// `a`'s rightmost leaf followed by `b`, if the two fit in one leaf. NIL if
    /// they do not, or if there is no leaf to merge into.
    static long mergeRight(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Byteconcat.bMergeRight(rt, a, b); }

    /// Put `b` in the deepest node on `a`'s right spine that has room, or NIL.
    /// Depth is UNCHANGED when this succeeds, which is the whole point.
    static long absorb(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Byteconcat.bAbsorb(rt, a, b); }

    /// A contiguous copy, CACHED on the node so a second walk is free.
    public static long flatten(Rt rt, long v) { return com._3sln.flint.kgen.rt.Byteflat.bFlatten(rt, v); }

    /// Append only `[from, to)`, descending rather than materialising. A
    /// subtree entirely outside the range is skipped whole, which is what makes
    /// a slice cost the size of the SLICE. Flattening first was correct and
    /// quadratic in the caller: tree-shaking slices a thousand function bodies
    /// out of one 509 KB code section, and materialising it per slice is half a
    /// gigabyte of copying.

        static boolean isBrope(Rt rt, long v) { return com._3sln.flint.kgen.rt.Bytecore.isBrope(rt, v); }

    /// SHARES, like `Str.ropeSlice`. This descended to the range -- which is
    /// what stopped it being quadratic -- and then COPIED it, so slicing a
    /// 509 KB code section allocated a fresh 509 KB minus the trim
    /// (`doc/decisions/0011`).
    public static long slice(Rt rt, long v, int from, int to) { return com._3sln.flint.kgen.rt.Byteslice.bSlice(rt, v, from, to); }

    /// A balanced byte rope over `n` pieces on the shadow stack, the mirror of
    /// `Str.ropeFromRoots`.
    static long fromRoots(Rt rt, int base, int n) { return com._3sln.flint.kgen.rt.Bytefold.bFromRoots(rt, base, n); }

    /// The mirror of `Str.ropeHash`: cached per node, `h(A.B) = h(A)*31^|B| +
    /// h(B)`. Hashing a byte rope used to FLATTEN it, buying the caching by
    /// spending the sharing.
    public static int hash(Rt rt, long v) {
        if (!isBrope(rt, v)) {
            byte[] bs = toArray(rt, v);
            int h0 = 0;
            for (byte b : bs) h0 = h0 * 31 + (b & 0xFF);
            return h0;
        }
        long cached = rt.slot(v, BB_HASH);
        if (Val.isFixnum(cached)) return (int) Val.asFixnum(cached);
        int base = rt.mark();
        int vi = rt.push(v);
        int kids = len(rt.gc.sp, Val.asHeap(rt.r(vi))) - BB_KIDS;
        int h = 0;
        for (int i = 0; i < kids; i++) {
            long k = rt.slot(rt.r(vi), BB_KIDS + i);
            int ki = rt.push(k);
            h = h * Str.pow31(count(rt, rt.r(ki))) + hash(rt, rt.r(ki));
            rt.popTo(ki);
        }
        rt.setSlot(Val.asHeap(rt.r(vi)), BB_HASH, Val.fixnum(h));
        rt.popTo(base);
        return h;
    }

    /// Content equality WITHOUT materialising either side, short-circuiting on
    /// NODE IDENTITY. This built a byte array of both sides in full, so two
    /// 500 KB sections differing at byte 0 cost a megabyte to tell apart -- and
    /// now that `slice` SHARES, two slices of one section meet the same leaf
    /// over and over.
    public static boolean eq(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Byteeq.bEq(rt, a, b); }


    // --- the transient ------------------------------------------------------
    //
    // Why this exists, in one measurement. `FLAT_MAX` is 1024 bytes, and a
    // concatenation below it COPIES rather than building a node -- the tier
    // that stops a tree costing more in metadata than it saves. It also means
    // building a byte string one piece at a time re-copies everything so far on
    // every join, which is quadratic until the pieces outgrow the threshold.
    //
    // A transient fixes it the way a transient vector fixes `conj`: a tail
    // buffer the transient OWNS and writes into, promoted into the tree only
    // when it is full. Canonical Clojure has no reason to want this, because
    // its strings are flat and building one is a StringBuilder. flint's are
    // trees with a flat threshold, which is a shape that has this problem.

    public static final int TB_TREE = 0, TB_TAIL = 1, TB_FILL = 2, TB_LIVE = 3;

    /// Exactly `FLAT_MAX`, so a promoted tail is the smallest piece that makes
    /// `concat` build a node instead of copying. A smaller tail would promote
    /// into the copying tier and reintroduce the quadratic it removes.
    public static final int TAIL_CAP = FLAT_MAX;

        public static boolean isTransient(Rt rt, long v) { return com._3sln.flint.kgen.rt.Bytecore.isTbytes(rt, v); }

    public static long transientOf(Rt rt, long v) { return com._3sln.flint.kgen.rt.Bytetrans.bTransient(rt, v); }

        static boolean live(Rt rt, long t) { return com._3sln.flint.kgen.rt.Bytecore.tbLive(rt, t); }

    /// Fold the full tail into the tree and start a fresh one. A FULL tail is
    /// handed over WHOLE rather than copied -- it is exactly the leaf the tree
    /// wants.
    static boolean flush(Rt rt, long t, int fill) { return com._3sln.flint.kgen.rt.Bytetwrite.tbFlush(rt, t, fill); }

    /// Append one byte: no allocation and no copy until the tail fills.
    ///
    /// `t` is ROOTED across the flush, and that is not caution. `flush`
    /// allocates, allocating can collect, and the nursery is a COPYING
    /// collector -- so a value held in a host local across it comes back
    /// holding the address the object had BEFORE the flip.
    public static long conj(Rt rt, long t, int b) { return com._3sln.flint.kgen.rt.Bytetwrite.bConj(rt, t, b); }

    /// Append a whole byte string. BULK, because appending a 1 KB piece one
    /// byte at a time is the thing this type exists to stop doing.
    public static long appendBytes(Rt rt, long t, long v) {
        if (!live(rt, t)) {
            return rt.throwStr("IllegalStateException", "this transient byte string is no longer usable");
        }
        // The source is copied out FIRST, so nothing holds a reference into the
        // heap while the loop below allocates.
        byte[] src = toArray(rt, v);
        int base = rt.mark();
        int ti = rt.push(t);
        int i = 0;
        while (i < src.length) {
            int fill = (int) Val.asFixnum(rt.slot(rt.r(ti), TB_FILL));
            if (fill == TAIL_CAP) {
                if (!flush(rt, rt.r(ti), fill)) { rt.popTo(base); return Val.NIL; }
                continue;
            }
            int room = TAIL_CAP - fill;
            int n = Math.min(room, src.length - i);
            long tail = rt.slot(rt.r(ti), TB_TAIL);
            byte[] chunk = new byte[n];
            System.arraycopy(src, i, chunk, 0, n);
            rt.gc.sp.writeBytes(Val.asHeap(tail) + HDR + fill, chunk);
            rt.setSlot(Val.asHeap(rt.r(ti)), TB_FILL, Val.fixnum(fill + n));
            i += n;
        }
        long outv = rt.r(ti);
        rt.popTo(base);
        return outv;
    }

        public static int tcount(Rt rt, long t) { return com._3sln.flint.kgen.rt.Bytecore.bTcount(rt, t); }

    public static long persistent(Rt rt, long t) { return com._3sln.flint.kgen.rt.Bytetwrite.bPersistent(rt, t); }
}
