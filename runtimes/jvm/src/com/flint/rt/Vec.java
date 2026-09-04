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

    /// The CACHED HASH, `nil` until first asked for.
    ///
    /// The native runtime has had this slot since vectors were written; the
    /// ports allocated a five-slot header and had nowhere to put it, so every
    /// `hash` of a vector walked all of it, every time. The file next door
    /// already makes the argument -- "a rope used as a map key must not rehash
    /// every lookup" -- and then a vector used as a map key did exactly that.
    ///
    /// It is also a LAYOUT divergence, which is the more serious half: the same
    /// value was six slots on one runtime and five on two, and no gate could
    /// see it. The snapshot check compares the two ports against each other
    /// and they agreed with each other while both disagreed with native.
    public static final int V_HASH = 5;

    public static int count(Rt rt, long v) { return (int) Val.asFixnum(rt.slot(v, V_CNT)); }
    static int shift(Rt rt, long v) { return (int) Val.asFixnum(rt.slot(v, V_SHIFT)); }
    static long root(Rt rt, long v) { return rt.slot(v, V_ROOT); }
    static long tail(Rt rt, long v) { return rt.slot(v, V_TAIL); }

    /// Where the tail starts. Below 32 elements the whole vector IS the tail.
    static int tailOff(Rt rt, long v) {
        int c = count(rt, v);
        return c < WIDTH ? 0 : ((c - 1) >> BITS) << BITS;
    }

    /// A NODE carries its OWNERSHIP TOKEN in slot 0 and its elements from 1.
    ///
    /// That extra slot is what makes transients possible: a node whose token is
    /// this transient's is owned by it and is written IN PLACE, and any other
    /// node is copied once and thereafter owned. Without it every `conj!` would
    /// copy, which is the entire cost transients exist to avoid.
    ///
    /// It is also why `nodeLen` subtracts one and every accessor adds one. The
    /// port carried plain nodes for a while and read perfectly; it was only not
    /// the Rust's layout, and an object of a different LENGTH is exactly the
    /// kind of divergence a snapshot would carry silently between runtimes.
    static long nodeGet(Rt rt, long node, int i) { return rt.slot(node, i + 1); }
    static void nodeSet(Rt rt, long node, int i, long v) { rt.setSlot(Val.asHeap(node), i + 1, v); }
    static int nodeLen(Rt rt, long node) { return len(rt.gc.sp, Val.asHeap(node)) - 1; }
    static long nodeEdit(Rt rt, long node) { return rt.slot(node, 0); }

    static long newNode(Rt rt, int n, long edit) {
        int e = rt.push(edit);
        long a = rt.alloc(TY_NODE, n + 1);
        long ed = rt.r(e);
        rt.popTo(e);
        if (a == 0) return Val.NIL;
        rt.setSlot(a, 0, ed);
        return Val.heap(a);
    }

    /// Copy `src`'s first `n` slots into a fresh node of `n` slots, owned by
    /// `edit` (NIL for a persistent node, which nothing owns).
    static long nodeClone(Rt rt, long src, int n, long edit) {
        int base = rt.mark();
        int si = rt.push(src);
        int ei = rt.push(edit);
        long out = newNode(rt, Math.max(n, 1), rt.r(ei));
        if (Val.isNil(out)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(out);
        if (!Val.isNil(rt.r(si))) {
            int have = nodeLen(rt, rt.r(si));
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
        long a = rt.alloc(TY_VEC, 6);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, V_CNT, Val.fixnum(cnt));
        rt.setSlot(a, V_SHIFT, Val.fixnum(shift));
        rt.setSlot(a, V_ROOT, rt.r(ri));
        rt.setSlot(a, V_TAIL, rt.r(ti));
        rt.setSlot(a, V_META, rt.r(mi));
        // Not carried from any source vector: `newVec` is called with new
        // contents every time, and a hash copied from the old one would be
        // wrong rather than merely stale.
        rt.setSlot(a, V_HASH, Val.NIL);
        rt.popTo(base);
        return Val.heap(a);
    }

    public static long empty(Rt rt) {
        long sg = rt.roots.shared.singletons[Rt.SING_EMPTY_VEC];
        if (!Val.isNil(sg)) return sg;
        return newEmpty(rt);
    }

    /// The one allocation `initSingletons` makes. An empty vector is THREE
    /// objects -- a root node, a tail node and the header -- so building one
    /// per `into []` was the most expensive of the three empties.
    static long newEmpty(Rt rt) {
        int base = rt.mark();
        long root = newNode(rt, WIDTH, Val.NIL);
        int ri = rt.push(root);
        long tail = newNode(rt, 0, Val.NIL);
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

    static long newPath(Rt rt, int level, long node, long edit) {
        if (level == 0) return node;
        int base = rt.mark();
        int ni = rt.push(node);
        int ei = rt.push(edit);
        long child = newPath(rt, level - BITS, rt.r(ni), rt.r(ei));
        int ci = rt.push(child);
        long parent = newNode(rt, WIDTH, rt.r(ei));
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
        long ret = nodeClone(rt, rt.r(pi), WIDTH, Val.NIL);
        if (Val.isNil(ret)) { rt.popTo(base); return Val.NIL; }
        int ri = rt.push(ret);
        int subidx = ((cnt - 1) >>> level) & MASK;
        long insert;
        if (level == BITS) {
            insert = rt.r(ti);
        } else {
            long child = nodeGet(rt, rt.r(pi), subidx);
            insert = Val.isNil(child)
                ? newPath(rt, level - BITS, rt.r(ti), Val.NIL)
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
            long newtail = nodeClone(rt, tail(rt, rt.r(vi)), tailLen + 1, Val.NIL);
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
                long nr = newNode(rt, WIDTH, Val.NIL);
                int nri = rt.push(nr);
                nodeSet(rt, rt.r(nri), 0, root(rt, rt.r(vi)));
                long path = newPath(rt, sh, rt.r(tn), Val.NIL);
                nodeSet(rt, rt.r(nri), 1, path);
                newroot = rt.r(nri);
                newshift = sh + BITS;
            } else {
                newroot = pushTail(rt, cnt, sh, root(rt, rt.r(vi)), rt.r(tn));
                newshift = sh;
            }
            int nri2 = rt.push(newroot);
            long newtail = newNode(rt, 1, Val.NIL);
            int ntl = rt.push(newtail);
            nodeSet(rt, rt.r(ntl), 0, rt.r(xi));
            out = newVec(rt, cnt + 1, newshift, rt.r(nri2), rt.r(ntl), rt.slot(rt.r(vi), V_META));
        }
        rt.popTo(base);
        return out;
    }

    static long doAssoc(Rt rt, int level, long node, int i, long val) {
        int base = rt.mark();
        int n = rt.push(node), v = rt.push(val);
        long ret = nodeClone(rt, rt.r(n), WIDTH, Val.NIL);
        if (Val.isNil(ret)) { rt.popTo(base); return Val.NIL; }
        int ri = rt.push(ret);
        if (level == 0) {
            nodeSet(rt, rt.r(ri), i & MASK, rt.r(v));
        } else {
            int subidx = (i >>> level) & MASK;
            long child = nodeGet(rt, rt.r(n), subidx);
            long nc = doAssoc(rt, level - BITS, child, i, rt.r(v));
            nodeSet(rt, rt.r(ri), subidx, nc);
        }
        long out = rt.r(ri);
        rt.popTo(base);
        return out;
    }

    /// `assoc` at an index. `i == count` APPENDS, which is Clojure's rule and
    /// the only index past the end that is legal.
    public static long assoc(Rt rt, long v, int i, long x) {
        int cnt = count(rt, v);
        if (i == cnt) return conj(rt, v, x);
        int base = rt.mark();
        int vi = rt.push(v), xi = rt.push(x);
        long out;
        if (i >= tailOff(rt, v)) {
            long tail = tail(rt, rt.r(vi));
            int tl = nodeLen(rt, tail);
            int nti = rt.push(nodeClone(rt, tail, tl, Val.NIL));
            nodeSet(rt, rt.r(nti), i - tailOff(rt, rt.r(vi)), rt.r(xi));
            long vv = rt.r(vi);
            out = newVec(rt, cnt, shift(rt, vv), root(rt, vv), rt.r(nti), rt.slot(vv, V_META));
        } else {
            long vv = rt.r(vi);
            int sh = shift(rt, vv);
            long nr = doAssoc(rt, sh, root(rt, vv), i, rt.r(xi));
            int nri = rt.push(nr);
            vv = rt.r(vi);
            out = newVec(rt, cnt, sh, rt.r(nri), tail(rt, vv), rt.slot(vv, V_META));
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

    // -----------------------------------------------------------------------
    // TRANSIENTS. `TY_TVEC [cnt, shift, root, tail, edit]`.
    //
    // `edit` is a freshly allocated object used purely for its IDENTITY. A node
    // whose token is that same object is owned by this transient and is mutated
    // in place; any other node is copied once and thereafter owned.
    // `persistent!` clears `edit`, so a stale handle fails loudly instead of
    // quietly mutating a value somebody else is now holding.

    public static final int T_CNT = 0, T_SHIFT = 1, T_ROOT = 2, T_TAIL = 3, T_EDIT = 4;

    /// A fresh identity for a transient's ownership token. Its TYPE is
    /// irrelevant and its contents are never read -- only `==` on the address.
    public static long newEditToken(Rt rt) {
        long a = rt.alloc(TY_VOLATILE, 1);
        return a == 0 ? Val.NIL : Val.heap(a);
    }

    public static boolean isTransient(Rt rt, long v) {
        return Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_TVEC;
    }

    public static int tcount(Rt rt, long t) { return (int) Val.asFixnum(rt.slot(t, T_CNT)); }
    static int tshift(Rt rt, long t) { return (int) Val.asFixnum(rt.slot(t, T_SHIFT)); }
    static int ttailOff(Rt rt, long t) {
        int c = tcount(rt, t);
        return c < WIDTH ? 0 : ((c - 1) >> BITS) << BITS;
    }
    public static boolean alive(Rt rt, long t) { return !Val.isNil(rt.slot(t, T_EDIT)); }

    /// `transient`: O(1). Nothing is copied until the first write reaching a
    /// node this transient does not already own.
    public static long transientOf(Rt rt, long v) {
        int base = rt.mark();
        int vi = rt.push(v);
        int ei = rt.push(newEditToken(rt));
        // The tail is widened to a full 32 UP FRONT so `conj!` can write in
        // place instead of copying it one longer every time.
        long tail = nodeClone(rt, tail(rt, rt.r(vi)), WIDTH, rt.r(ei));
        int ti = rt.push(tail);
        int ri = rt.push(root(rt, rt.r(vi)));
        long a = rt.alloc(TY_TVEC, 5);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        long vv = rt.r(vi);
        int cnt = count(rt, vv), shift = shift(rt, vv);
        rt.setSlot(a, T_CNT, Val.fixnum(cnt));
        rt.setSlot(a, T_SHIFT, Val.fixnum(shift));
        rt.setSlot(a, T_ROOT, rt.r(ri));
        rt.setSlot(a, T_TAIL, rt.r(ti));
        rt.setSlot(a, T_EDIT, rt.r(ei));
        rt.popTo(base);
        return Val.heap(a);
    }

    /// `node` if this transient already owns it, else an owned copy.
    static long ensureEditable(Rt rt, long node, long edit) {
        return nodeEdit(rt, node) == edit ? node : nodeClone(rt, node, WIDTH, edit);
    }

    static long tPushTail(Rt rt, int cnt, int level, long parent, long tailnode, long edit) {
        int base = rt.mark();
        int e = rt.push(edit);
        int t = rt.push(tailnode);
        int p = rt.push(parent);
        long ret = ensureEditable(rt, rt.r(p), rt.r(e));
        int ri = rt.push(ret);
        int subidx = ((cnt - 1) >>> level) & MASK;
        long insert;
        if (level == BITS) {
            insert = rt.r(t);
        } else {
            long child = nodeGet(rt, rt.r(ri), subidx);
            insert = Val.isNil(child)
                ? newPath(rt, level - BITS, rt.r(t), rt.r(e))
                : tPushTail(rt, cnt, level - BITS, child, rt.r(t), rt.r(e));
        }
        int ii = rt.push(insert);
        nodeSet(rt, rt.r(ri), subidx, rt.r(ii));
        long out = rt.r(ri);
        rt.popTo(base);
        return out;
    }

    public static long tconj(Rt rt, long t, long x) {
        int base = rt.mark();
        int ti = rt.push(t);
        int xi = rt.push(x);
        int cnt = tcount(rt, t);
        if (cnt - ttailOff(rt, t) < WIDTH) {
            // Room in the working tail: written IN PLACE. Nothing is allocated
            // on this path, which is why `t` cannot have moved and is returned
            // as it came in.
            nodeSet(rt, rt.slot(t, T_TAIL), cnt & MASK, rt.r(xi));
            rt.setSlot(Val.asHeap(t), T_CNT, Val.fixnum(cnt + 1));
            rt.popTo(base);
            return t;
        }
        // Tail full: fold it into the trie and start a fresh one.
        int ei = rt.push(rt.slot(t, T_EDIT));
        int tn = rt.push(rt.slot(rt.r(ti), T_TAIL));
        int nt = rt.push(newNode(rt, WIDTH, rt.r(ei)));
        nodeSet(rt, rt.r(nt), 0, rt.r(xi));
        int sh = tshift(rt, rt.r(ti));
        boolean overflow = (cnt >>> BITS) > (1 << sh);
        long newroot;
        int newshift;
        if (overflow) {
            int nri = rt.push(newNode(rt, WIDTH, rt.r(ei)));
            nodeSet(rt, rt.r(nri), 0, rt.slot(rt.r(ti), T_ROOT));
            long path = newPath(rt, sh, rt.r(tn), rt.r(ei));
            nodeSet(rt, rt.r(nri), 1, path);
            newroot = rt.r(nri);
            newshift = sh + BITS;
        } else {
            newroot = tPushTail(rt, cnt, sh, rt.slot(rt.r(ti), T_ROOT), rt.r(tn), rt.r(ei));
            newshift = sh;
        }
        int nri2 = rt.push(newroot);
        long tv = rt.r(ti);
        rt.setSlot(Val.asHeap(tv), T_ROOT, rt.r(nri2));
        rt.setSlot(Val.asHeap(tv), T_SHIFT, Val.fixnum(newshift));
        rt.setSlot(Val.asHeap(tv), T_TAIL, rt.r(nt));
        rt.setSlot(Val.asHeap(tv), T_CNT, Val.fixnum(cnt + 1));
        rt.popTo(base);
        return tv;
    }

    static long tArrayFor(Rt rt, long t, int i) {
        if (i >= ttailOff(rt, t)) return rt.slot(t, T_TAIL);
        long node = rt.slot(t, T_ROOT);
        int level = tshift(rt, t);
        while (level > 0) {
            node = nodeGet(rt, node, (i >>> level) & MASK);
            level -= BITS;
        }
        return node;
    }

    public static long tnth(Rt rt, long t, int i) {
        if (i < 0 || i >= tcount(rt, t)) return Val.NOT_FOUND;
        return nodeGet(rt, tArrayFor(rt, t, i), i & MASK);
    }

    static long tDoAssoc(Rt rt, int level, long node, int i, long val, long edit) {
        int base = rt.mark();
        int e = rt.push(edit);
        int v = rt.push(val);
        int ni = rt.push(node);
        long ret = ensureEditable(rt, rt.r(ni), rt.r(e));
        int ri = rt.push(ret);
        if (level == 0) {
            nodeSet(rt, rt.r(ri), i & MASK, rt.r(v));
        } else {
            int subidx = (i >>> level) & MASK;
            long child = nodeGet(rt, rt.r(ri), subidx);
            long nc = tDoAssoc(rt, level - BITS, child, i, rt.r(v), rt.r(e));
            nodeSet(rt, rt.r(ri), subidx, nc);
        }
        long out = rt.r(ri);
        rt.popTo(base);
        return out;
    }

    public static long tassoc(Rt rt, long t, int i, long x) {
        int cnt = tcount(rt, t);
        if (i == cnt) return tconj(rt, t, x);
        int base = rt.mark();
        int ti = rt.push(t);
        int xi = rt.push(x);
        if (i >= ttailOff(rt, t)) {
            nodeSet(rt, rt.slot(t, T_TAIL), i & MASK, rt.r(xi));
        } else {
            long nr = tDoAssoc(rt, tshift(rt, t), rt.slot(t, T_ROOT), i,
                               rt.r(xi), rt.slot(t, T_EDIT));
            rt.setSlot(Val.asHeap(rt.r(ti)), T_ROOT, nr);
        }
        long out = rt.r(ti);
        rt.popTo(base);
        return out;
    }

    public static long tpersistent(Rt rt, long t) {
        int base = rt.mark();
        int ti = rt.push(t);
        int cnt = tcount(rt, t), shift = tshift(rt, t), tailOff = ttailOff(rt, t);
        // Trim the 32-wide working tail down to what is actually used.
        int tr = rt.push(nodeClone(rt, rt.slot(t, T_TAIL), cnt - tailOff, Val.NIL));
        int ri = rt.push(rt.slot(rt.r(ti), T_ROOT));
        // Invalidate the handle: using it afterwards is a bug, not a silent
        // mutation of a value somebody else now owns.
        rt.setSlot(Val.asHeap(rt.r(ti)), T_EDIT, Val.NIL);
        long out = newVec(rt, cnt, shift, rt.r(ri), rt.r(tr), Val.NIL);
        rt.popTo(base);
        return out;
    }
}
