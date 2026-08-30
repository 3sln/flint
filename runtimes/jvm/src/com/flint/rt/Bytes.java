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
    public static final int BB_KIDS = 3;

    public static boolean isBytes(Rt rt, long v) {
        if (!Val.isHeap(v)) return false;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        return t == TY_BYTES || t == TY_BROPE;
    }

    public static int count(Rt rt, long v) {
        if (!Val.isHeap(v)) return 0;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        if (t == TY_BYTES) return len(rt.gc.sp, Val.asHeap(v));
        if (t == TY_BROPE) return (int) Val.asFixnum(rt.slot(v, BB_BYTES));
        return 0;
    }

    public static long of(Rt rt, byte[] b) {
        long a = rt.alloc(TY_BYTES, b.length);
        if (a == 0) return Val.NIL;
        rt.gc.sp.writeBytes(a + HDR, b);
        return Val.heap(a);
    }

    static void append(Rt rt, long v, ArrayList<byte[]> out) {
        if (!Val.isHeap(v)) return;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        if (t == TY_BYTES) {
            out.add(rt.gc.sp.bytes(Val.asHeap(v) + HDR, len(rt.gc.sp, Val.asHeap(v))));
        } else if (t == TY_BROPE) {
            long flat = rt.slot(v, BB_FLAT);
            if (!Val.isNil(flat)) { append(rt, flat, out); return; }
            int n = len(rt.gc.sp, Val.asHeap(v)) - BB_KIDS;
            for (int i = 0; i < n; i++) append(rt, rt.slot(v, BB_KIDS + i), out);
        }
    }

    public static byte[] toArray(Rt rt, long v) {
        ArrayList<byte[]> parts = new ArrayList<>();
        append(rt, v, parts);
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] outb = new byte[total];
        int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, outb, at, p.length); at += p.length; }
        return outb;
    }

    /// The byte at `i`, or -1. Descends rather than flattening, which is why
    /// depth is what random access pays and why `FANOUT` is 16.
    public static int at(Rt rt, long v, int i) {
        long cur = v;
        int idx = i;
        for (;;) {
            if (!Val.isHeap(cur)) return -1;
            int t = ty(rt.gc.sp, Val.asHeap(cur));
            if (t == TY_BYTES) {
                int n = len(rt.gc.sp, Val.asHeap(cur));
                return (idx < 0 || idx >= n) ? -1 : rt.gc.sp.readU8(Val.asHeap(cur) + HDR + idx);
            }
            if (t != TY_BROPE) return -1;
            int n = len(rt.gc.sp, Val.asHeap(cur)) - BB_KIDS;
            int pos = 0;
            long next = Val.NIL;
            for (int k = 0; k < n; k++) {
                long child = rt.slot(cur, BB_KIDS + k);
                int cn = count(rt, child);
                if (idx < pos + cn) { next = child; idx -= pos; break; }
                pos += cn;
            }
            if (Val.isNil(next)) return -1;
            cur = next;
        }
    }

    public static int depth(Rt rt, long v) {
        if (Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_BROPE) {
            return (int) Val.asFixnum(rt.slot(v, BB_DEPTH));
        }
        return 0;
    }

    /// A leaf joining a deeper node is PROMOTED rather than sitting beside
    /// subtrees: a node's children must all be the same depth.
    static long wrapTo(Rt rt, long v, int d) {
        int base = rt.mark();
        int ci = rt.push(v);
        while (depth(rt, rt.r(ci)) < d) {
            long[] kids = { rt.r(ci) };
            rt.setR(ci, node(rt, kids));
        }
        long outv = rt.r(ci);
        rt.popTo(base);
        return outv;
    }

    static long node(Rt rt, long[] kids) {
        int total = 0;
        for (long k : kids) total += count(rt, k);
        int base = rt.mark();
        for (long k : kids) rt.push(k);
        long a = rt.alloc(TY_BROPE, BB_KIDS + kids.length);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, BB_BYTES, Val.fixnum(total));
        rt.setSlot(a, BB_FLAT, Val.NIL);
        rt.setSlot(a, BB_DEPTH, Val.fixnum(depth(rt, rt.r(base)) + 1));
        for (int i = 0; i < kids.length; i++) rt.setSlot(a, BB_KIDS + i, rt.r(base + i));
        rt.popTo(base);
        return Val.heap(a);
    }

    static long copyConcat(Rt rt, long a, long b) {
        byte[] x = toArray(rt, a), y = toArray(rt, b);
        byte[] both = new byte[x.length + y.length];
        System.arraycopy(x, 0, both, 0, x.length);
        System.arraycopy(y, 0, both, x.length, y.length);
        return of(rt, both);
    }

    public static long concat(Rt rt, long a, long b) {
        if (count(rt, a) == 0) return b;
        if (count(rt, b) == 0) return a;
        if (count(rt, a) + count(rt, b) <= FLAT_MAX) {
            // Below the threshold a tree costs more in metadata than the copy
            // saves. This tier must not be skipped -- and it is also what makes
            // incremental building quadratic, which is what the transient is for.
            return copyConcat(rt, a, b);
        }
        int base = rt.mark();
        int ai = rt.push(a), bi = rt.push(b);
        long outv;
        // First: a small `b` is merged into the RIGHTMOST LEAF rather than
        // given a leaf of its own. Without this, appending a byte at a time
        // makes one heap object per byte and rebuilds the spine each time.
        // Merging bounds the leaf count at `total / FLAT_MAX`.
        if (count(rt, rt.r(bi)) <= FLAT_MAX / 2) {
            long merged = mergeRight(rt, rt.r(ai), rt.r(bi));
            if (!Val.isNil(merged)) { rt.popTo(base); return merged; }
        }
        // Then: push `b` down the RIGHT SPINE into the deepest node with room.
        // Absorbing at the TOP instead builds a left spine -- the top fills
        // after sixteen joins, wraps, and depth grows by one every sixteen.
        // Twenty thousand joins was depth 1,250, and the recursive walk ran the
        // shadow stack off the end. Descending first keeps it a B-tree: twenty
        // thousand leaves is depth four.
        long absorbed = absorb(rt, rt.r(ai), rt.r(bi));
        if (!Val.isNil(absorbed)) { rt.popTo(base); return absorbed; }
        // Neither side had room, so a new level. BOTH sides are promoted to the
        // same depth first: a node reads its depth off child zero, so pairing a
        // deep node with a bare leaf makes the node claim a depth one child
        // does not have, and later appends descend into the wrong place.
        int d = Math.max(depth(rt, rt.r(ai)), depth(rt, rt.r(bi)));
        int pa = rt.push(wrapTo(rt, rt.r(ai), d));
        int pb = rt.push(wrapTo(rt, rt.r(bi), d));
        outv = node(rt, new long[]{ rt.r(pa), rt.r(pb) });
        rt.popTo(base);
        return outv;
    }

    /// `a`'s rightmost leaf followed by `b`, if the two fit in one leaf. NIL if
    /// they do not, or if there is no leaf to merge into.
    static long mergeRight(Rt rt, long a, long b) {
        if (!Val.isHeap(a)) return Val.NIL;
        int t = ty(rt.gc.sp, Val.asHeap(a));
        if (t == TY_BYTES) {
            return len(rt.gc.sp, Val.asHeap(a)) + count(rt, b) <= FLAT_MAX
                 ? copyConcat(rt, a, b) : Val.NIL;
        }
        if (t != TY_BROPE) return Val.NIL;
        int n = len(rt.gc.sp, Val.asHeap(a)) - BB_KIDS;
        int base = rt.mark();
        int ai = rt.push(a), bi = rt.push(b);
        long merged = mergeRight(rt, rt.slot(rt.r(ai), BB_KIDS + n - 1), rt.r(bi));
        if (Val.isNil(merged)) { rt.popTo(base); return Val.NIL; }
        int mi = rt.push(merged);
        long[] kids = new long[n];
        for (int i = 0; i < n - 1; i++) kids[i] = rt.slot(rt.r(ai), BB_KIDS + i);
        kids[n - 1] = rt.r(mi);
        long outv = node(rt, kids);
        rt.popTo(base);
        return outv;
    }

    /// Put `b` in the deepest node on `a`'s right spine that has room, or NIL.
    /// Depth is UNCHANGED when this succeeds, which is the whole point.
    static long absorb(Rt rt, long a, long b) {
        if (!Val.isHeap(a) || ty(rt.gc.sp, Val.asHeap(a)) != TY_BROPE) return Val.NIL;
        int n = len(rt.gc.sp, Val.asHeap(a)) - BB_KIDS;
        int da = depth(rt, a);
        // A node's children are all the same depth, so anything as deep as this
        // node cannot go inside it.
        if (depth(rt, b) >= da) return Val.NIL;
        int base = rt.mark();
        int ai = rt.push(a), bi = rt.push(b);
        // Deepest FIRST: only if the last child cannot take it does this node
        // take it, and only if neither can does the caller wrap.
        long down = absorb(rt, rt.slot(rt.r(ai), BB_KIDS + n - 1), rt.r(bi));
        if (!Val.isNil(down)) {
            int di = rt.push(down);
            long[] kids = new long[n];
            for (int i = 0; i < n - 1; i++) kids[i] = rt.slot(rt.r(ai), BB_KIDS + i);
            kids[n - 1] = rt.r(di);
            long outv = node(rt, kids);
            rt.popTo(base);
            return outv;
        }
        if (n < FANOUT) {
            // Promoted to this node's child depth, so every child stays the
            // same depth and the next append can descend into it.
            int wi = rt.push(wrapTo(rt, rt.r(bi), da - 1));
            long[] kids = new long[n + 1];
            for (int i = 0; i < n; i++) kids[i] = rt.slot(rt.r(ai), BB_KIDS + i);
            kids[n] = rt.r(wi);
            long outv = node(rt, kids);
            rt.popTo(base);
            return outv;
        }
        rt.popTo(base);
        return Val.NIL;
    }

    /// A contiguous copy, CACHED on the node so a second walk is free.
    public static long flatten(Rt rt, long v) {
        if (!Val.isHeap(v)) return v;
        if (ty(rt.gc.sp, Val.asHeap(v)) == TY_BYTES) return v;
        long cached = rt.slot(v, BB_FLAT);
        if (!Val.isNil(cached)) return cached;
        byte[] all = toArray(rt, v);
        int base = rt.mark();
        int vi = rt.push(v);
        long flat = of(rt, all);
        if (Val.isHeap(flat)) rt.setSlot(Val.asHeap(rt.r(vi)), BB_FLAT, flat);
        rt.popTo(base);
        return flat;
    }

    /// Append only `[from, to)`, descending rather than materialising. A
    /// subtree entirely outside the range is skipped whole, which is what makes
    /// a slice cost the size of the SLICE. Flattening first was correct and
    /// quadratic in the caller: tree-shaking slices a thousand function bodies
    /// out of one 509 KB code section, and materialising it per slice is half a
    /// gigabyte of copying.
    static void appendRange(Rt rt, long v, int from, int to, ArrayList<byte[]> out) {
        if (!Val.isHeap(v) || from >= to) return;
        int t = ty(rt.gc.sp, Val.asHeap(v));
        if (t == TY_BYTES) {
            int n = len(rt.gc.sp, Val.asHeap(v));
            int hi = Math.min(to, n), lo = Math.min(from, hi);
            if (hi > lo) out.add(rt.gc.sp.bytes(Val.asHeap(v) + HDR + lo, hi - lo));
            return;
        }
        if (t != TY_BROPE) return;
        long flat = rt.slot(v, BB_FLAT);
        if (!Val.isNil(flat)) { appendRange(rt, flat, from, to, out); return; }
        int n = len(rt.gc.sp, Val.asHeap(v)) - BB_KIDS;
        int pos = 0;
        for (int i = 0; i < n && pos < to; i++) {
            long k = rt.slot(v, BB_KIDS + i);
            int kn = count(rt, k);
            if (pos + kn > from) {
                appendRange(rt, k, Math.max(from - pos, 0), Math.min(to - pos, kn), out);
            }
            pos += kn;
        }
    }

    public static long slice(Rt rt, long v, int from, int to) {
        int n = count(rt, v);
        int lo = Math.min(Math.max(from, 0), n);
        int hi = Math.min(Math.max(to, lo), n);
        if (lo == 0 && hi == n) return v;
        ArrayList<byte[]> parts = new ArrayList<>();
        appendRange(rt, v, lo, hi, parts);
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] outb = new byte[total];
        int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, outb, at, p.length); at += p.length; }
        return of(rt, outb);
    }

    public static boolean eq(Rt rt, long a, long b) {
        if (count(rt, a) != count(rt, b)) return false;
        return java.util.Arrays.equals(toArray(rt, a), toArray(rt, b));
    }

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

    public static boolean isTransient(Rt rt, long v) {
        return Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_TBYTES;
    }

    public static long transientOf(Rt rt, long v) {
        int base = rt.mark();
        int vi = rt.push(v);
        long tail = rt.alloc(TY_BYTES, TAIL_CAP);
        if (tail == 0) { rt.popTo(base); return Val.NIL; }
        int ti = rt.push(Val.heap(tail));
        long a = rt.alloc(TY_TBYTES, 4);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, TB_TREE, rt.r(vi));
        rt.setSlot(a, TB_TAIL, rt.r(ti));
        rt.setSlot(a, TB_FILL, Val.fixnum(0));
        rt.setSlot(a, TB_LIVE, Val.TRUE);
        rt.popTo(base);
        return Val.heap(a);
    }

    static boolean live(Rt rt, long t) {
        return isTransient(rt, t) && rt.slot(t, TB_LIVE) == Val.TRUE;
    }

    /// Fold the full tail into the tree and start a fresh one. A FULL tail is
    /// handed over WHOLE rather than copied -- it is exactly the leaf the tree
    /// wants.
    static boolean flush(Rt rt, long t, int fill) {
        int base = rt.mark();
        int ti = rt.push(t);
        int tl = rt.push(rt.slot(rt.r(ti), TB_TAIL));
        long piece;
        if (fill == TAIL_CAP) {
            piece = rt.r(tl);
        } else {
            piece = of(rt, rt.gc.sp.bytes(Val.asHeap(rt.r(tl)) + HDR, fill));
        }
        int pi = rt.push(piece);
        int tr = rt.push(rt.slot(rt.r(ti), TB_TREE));
        long joined = Val.isNil(rt.r(tr)) ? rt.r(pi) : concat(rt, rt.r(tr), rt.r(pi));
        int ji = rt.push(joined);
        long fresh = rt.alloc(TY_BYTES, TAIL_CAP);
        if (fresh == 0) { rt.popTo(base); return false; }
        long tv = rt.r(ti);
        rt.setSlot(Val.asHeap(tv), TB_TREE, rt.r(ji));
        rt.setSlot(Val.asHeap(tv), TB_TAIL, Val.heap(fresh));
        rt.setSlot(Val.asHeap(tv), TB_FILL, Val.fixnum(0));
        rt.popTo(base);
        return true;
    }

    /// Append one byte: no allocation and no copy until the tail fills.
    ///
    /// `t` is ROOTED across the flush, and that is not caution. `flush`
    /// allocates, allocating can collect, and the nursery is a COPYING
    /// collector -- so a value held in a host local across it comes back
    /// holding the address the object had BEFORE the flip.
    public static long conj(Rt rt, long t, int b) {
        if (!live(rt, t)) {
            return rt.throwStr("IllegalStateException", "this transient byte string is no longer usable");
        }
        int fill = (int) Val.asFixnum(rt.slot(t, TB_FILL));
        if (fill == TAIL_CAP) {
            int base = rt.mark();
            int ti = rt.push(t);
            boolean ok = flush(rt, rt.r(ti), fill);
            long tv = rt.r(ti);
            rt.popTo(base);
            if (!ok) return Val.NIL;
            return conj(rt, tv, b);
        }
        long tail = rt.slot(t, TB_TAIL);
        rt.gc.sp.writeU8(Val.asHeap(tail) + HDR + fill, b & 0xFF);
        rt.setSlot(Val.asHeap(t), TB_FILL, Val.fixnum(fill + 1));
        return t;
    }

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

    public static int tcount(Rt rt, long t) {
        if (!isTransient(rt, t)) return 0;
        return count(rt, rt.slot(t, TB_TREE)) + (int) Val.asFixnum(rt.slot(t, TB_FILL));
    }

    public static long persistent(Rt rt, long t) {
        if (!live(rt, t)) {
            return rt.throwStr("IllegalStateException", "this transient byte string is no longer usable");
        }
        int fill = (int) Val.asFixnum(rt.slot(t, TB_FILL));
        int base = rt.mark();
        int ti = rt.push(t);
        if (fill > 0 && !flush(rt, rt.r(ti), fill)) { rt.popTo(base); return Val.NIL; }
        long tv = rt.r(ti);
        rt.setSlot(Val.asHeap(tv), TB_LIVE, Val.FALSE);
        long outv = rt.slot(tv, TB_TREE);
        rt.popTo(base);
        return Val.isNil(outv) ? of(rt, new byte[0]) : outv;
    }
}
