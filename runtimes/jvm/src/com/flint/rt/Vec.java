package com.flint.rt;

import static com.flint.rt.Obj.*;

/// Persistent vectors, ported from `runtime/src/vector.rs`.
///
/// A 32-way trie with a TAIL: the last up-to-32 elements live in a flat node
/// hanging off the vector, so appending is O(1) amortised and does not touch
/// the trie until the tail fills. That is what makes `conj` cheap and why
/// `tailOff` appears in every access path.
///
/// `TY_VEC` is `[cnt, shift, root, tail, meta]`; `TY_NODE` is `len` values.
public final class Vec {
    private Vec() {}

    public static final int BITS = 5;
    public static final int WIDTH = 1 << BITS;   // 32
    public static final int MASK = WIDTH - 1;

    public static final int V_CNT = 0, V_SHIFT = 1, V_ROOT = 2, V_TAIL = 3, V_META = 4;

    public static int count(Rt rt, long v) { return (int) Val.asFixnum(rt.slot(v, V_CNT)); }
    static int shift(Rt rt, long v) { return (int) Val.asFixnum(rt.slot(v, V_SHIFT)); }
    static long root(Rt rt, long v) { return rt.slot(v, V_ROOT); }
    static long tail(Rt rt, long v) { return rt.slot(v, V_TAIL); }

    /// Where the tail starts. Below 32 elements the whole vector IS the tail.
    static int tailOff(Rt rt, long v) {
        int c = count(rt, v);
        return c < WIDTH ? 0 : ((c - 1) >> BITS) << BITS;
    }

    static long nodeGet(Rt rt, long node, int i) { return rt.slot(node, i); }
    static void nodeSet(Rt rt, long node, int i, long v) { rt.setSlot(Val.asHeap(node), i, v); }

    static long newNode(Rt rt, int n) {
        long a = rt.alloc(TY_NODE, n);
        return a == 0 ? Val.NIL : Val.heap(a);
    }

    /// Copy `src`'s first `n` slots into a fresh node of `n` slots.
    static long nodeClone(Rt rt, long src, int n) {
        int base = rt.mark();
        int si = rt.push(src);
        long out = newNode(rt, Math.max(n, 1));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        if (!Val.isNil(rt.r(si))) {
            int have = len(rt.gc.sp, Val.asHeap(rt.r(si)));
            for (int i = 0; i < Math.min(n, have); i++) {
                // Read the source through the shadow stack: `setSlot` cannot
                // collect, but reading `src` from a Java local across the
                // `newNode` above would already have been stale.
                nodeSet(rt, rt.r(oi), i, nodeGet(rt, rt.r(si), i));
            }
        }
        long r = rt.r(oi);
        rt.popTo(base);
        return r;
    }

    static long newVec(Rt rt, int cnt, int shift, long root, long tail, long meta) {
        int base = rt.mark();
        int ri = rt.push(root);
        int ti = rt.push(tail);
        int mi = rt.push(meta);
        long a = rt.alloc(TY_VEC, 5);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, V_CNT, Val.fixnum(cnt));
        rt.setSlot(a, V_SHIFT, Val.fixnum(shift));
        rt.setSlot(a, V_ROOT, rt.r(ri));
        rt.setSlot(a, V_TAIL, rt.r(ti));
        rt.setSlot(a, V_META, rt.r(mi));
        rt.popTo(base);
        return Val.heap(a);
    }

    public static long empty(Rt rt) {
        int base = rt.mark();
        long root = newNode(rt, WIDTH);
        int ri = rt.push(root);
        long tail = newNode(rt, 0);
        int ti = rt.push(tail);
        long out = newVec(rt, 0, BITS, rt.r(ri), rt.r(ti), Val.NIL);
        rt.popTo(base);
        return out;
    }

    /// The leaf array holding index `i`.
    static long arrayFor(Rt rt, long v, int i) {
        if (i >= tailOff(rt, v)) return tail(rt, v);
        long node = root(rt, v);
        int level = shift(rt, v);
        while (level > 0) {
            node = nodeGet(rt, node, (i >>> level) & MASK);
            level -= BITS;
        }
        return node;
    }

    /// `nth`, or NOT_FOUND when out of range -- distinguishable from a `nil`
    /// that is genuinely stored there.
    public static long nth(Rt rt, long v, int i) {
        if (i < 0 || i >= count(rt, v)) return Val.NOT_FOUND;
        return nodeGet(rt, arrayFor(rt, v, i), i & MASK);
    }

    static long newPath(Rt rt, int level, long node) {
        if (level == 0) return node;
        int base = rt.mark();
        int ni = rt.push(node);
        long child = newPath(rt, level - BITS, rt.r(ni));
        int ci = rt.push(child);
        long parent = newNode(rt, WIDTH);
        if (Val.isNil(parent)) { rt.popTo(base); return Val.NIL; }
        int pi = rt.push(parent);
        nodeSet(rt, rt.r(pi), 0, rt.r(ci));
        long out = rt.r(pi);
        rt.popTo(base);
        return out;
    }

    /// Push `tailnode` into the trie at `level`, copying the spine.
    static long pushTail(Rt rt, int cnt, int level, long parent, long tailnode) {
        int base = rt.mark();
        int pi = rt.push(parent);
        int ti = rt.push(tailnode);
        long ret = nodeClone(rt, rt.r(pi), WIDTH);
        if (Val.isNil(ret)) { rt.popTo(base); return Val.NIL; }
        int ri = rt.push(ret);
        int subidx = ((cnt - 1) >>> level) & MASK;
        long insert;
        if (level == BITS) {
            insert = rt.r(ti);
        } else {
            long child = nodeGet(rt, rt.r(pi), subidx);
            insert = Val.isNil(child)
                ? newPath(rt, level - BITS, rt.r(ti))
                : pushTail(rt, cnt, level - BITS, child, rt.r(ti));
        }
        int ii = rt.push(insert);
        nodeSet(rt, rt.r(ri), subidx, rt.r(ii));
        long out = rt.r(ri);
        rt.popTo(base);
        return out;
    }

    public static long conj(Rt rt, long v, long x) {
        int base = rt.mark();
        int vi = rt.push(v);
        int xi = rt.push(x);
        int cnt = count(rt, v);
        int tailLen = cnt - tailOff(rt, v);
        long out;
        if (tailLen < WIDTH) {
            // Room in the tail: copy it one longer. This is the common case and
            // the reason `conj` is O(1) amortised.
            long newtail = nodeClone(rt, tail(rt, rt.r(vi)), tailLen + 1);
            int nt = rt.push(newtail);
            nodeSet(rt, rt.r(nt), tailLen, rt.r(xi));
            long vv = rt.r(vi);
            out = newVec(rt, cnt + 1, shift(rt, vv), root(rt, vv), rt.r(nt), rt.slot(vv, V_META));
        } else {
            // The tail is full: it becomes a leaf in the trie.
            long vv = rt.r(vi);
            int sh = shift(rt, vv);
            int tn = rt.push(tail(rt, vv));
            boolean overflow = (cnt >>> BITS) > (1 << sh);
            long newroot;
            int newshift;
            if (overflow) {
                long nr = newNode(rt, WIDTH);
                int nri = rt.push(nr);
                nodeSet(rt, rt.r(nri), 0, root(rt, rt.r(vi)));
                long path = newPath(rt, sh, rt.r(tn));
                nodeSet(rt, rt.r(nri), 1, path);
                newroot = rt.r(nri);
                newshift = sh + BITS;
            } else {
                newroot = pushTail(rt, cnt, sh, root(rt, rt.r(vi)), rt.r(tn));
                newshift = sh;
            }
            int nri2 = rt.push(newroot);
            long newtail = newNode(rt, 1);
            int ntl = rt.push(newtail);
            nodeSet(rt, rt.r(ntl), 0, rt.r(xi));
            out = newVec(rt, cnt + 1, newshift, rt.r(nri2), rt.r(ntl), rt.slot(rt.r(vi), V_META));
        }
        rt.popTo(base);
        return out;
    }

    /// Build a vector from `n` values already rooted at `base` on the shadow
    /// stack. What the `VECTOR` opcode uses.
    public static long fromRoots(Rt rt, int base, int n) {
        int mk = rt.mark();
        int vi = rt.push(empty(rt));
        for (int i = 0; i < n; i++) {
            long nv = conj(rt, rt.r(vi), rt.r(base + i));
            rt.setR(vi, nv);
        }
        long out = rt.r(vi);
        rt.popTo(mk);
        return out;
    }
}
