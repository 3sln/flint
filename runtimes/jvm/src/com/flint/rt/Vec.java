package com.flint.rt;

import static com.flint.rt.Obj.*;
import static flint.rt.Vecnode.*;
import static flint.rt.Vecread.*;
import static flint.rt.Vecwrite.*;
import static flint.rt.Vecassoc.*;

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
    static long root(Rt rt, long v) { return rt.slot(v, V_ROOT); }
    static long tail(Rt rt, long v) { return rt.slot(v, V_TAIL); }

    /// A NODE carries its OWNERSHIP TOKEN in slot 0 and its elements from 1.
    ///
    /// That extra slot is what makes transients possible: a node whose token is
    /// this transient's is owned by it and is written IN PLACE, and any other
    /// node is copied once and thereafter owned. Without it every `conj!` would
    /// copy, which is the entire cost transients exist to avoid.
    ///
    /// `nodeGet`, `nodeSet`, `nodeLen`, `nodeEdit` and `newNode` are GENERATED
    /// now, from `kin/vecnode.kin`, and reached through the static import at
    /// the top of this file. They were five one-line functions written three
    /// times; the offset-by-one that every one of them carries is the kind of
    /// thing that only has to be got right once.


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

    /// `conj`, under the name 53 call sites in this runtime already use.
    ///
    /// The body is GENERATED, as `Vecwrite.vecConj`, and Rust's callers say
    /// `vec_conj` directly. Renaming these would be 53 edits here and 53 more
    /// on the CLR, in files that have nothing to do with vectors, for a naming
    /// win -- which is the trade `doc/goals/kin-port.md` already recorded
    /// against the `champ_*` wrappers and answered with "worth doing LAST".
    public static long conj(Rt rt, long v, long x) { return vecConj(rt, v, x); }
    /// `assoc` and `pop`, likewise delegating to their generated bodies.
    public static long assoc(Rt rt, long v, int i, long x) { return vecAssoc(rt, v, i, x); }
    public static long pop(Rt rt, long v) { return vecPop(rt, v); }

    public static long nth(Rt rt, long v, int i) {
        if (i < 0 || i >= count(rt, v)) return Val.NOT_FOUND;
        return nodeGet(rt, arrayFor(rt, v, i), i & MASK);
    }








    public static long fromRoots(Rt rt, int base, int n) {
        int mk = rt.mark();
        int vi = rt.push(empty(rt));
        for (int i = 0; i < n; i++) {
            long nv = vecConj(rt, rt.r(vi), rt.r(base + i));
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
        int cnt = count(rt, vv), shift = vecShift(rt, vv);
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

    /// `pop!`: the transient one shorter, IN PLACE.
    ///
    /// `clojure.core` had `(defn pop! [t] (flint.rt/pop t))` -- the persistent
    /// pop -- so on a transient vector it fell through to the seq branch and
    /// answered `()`. There was no builtin and, on either port, no
    /// implementation for one to call.
    public static long tpop(Rt rt, long t) {
        int cnt = tcount(rt, t);
        if (cnt == 0) return Val.NIL;   // the caller raises
        if (cnt == 1) {
            rt.setSlot(Val.asHeap(t), T_CNT, Val.fixnum(0));
            return t;
        }
        if (((cnt - 1) & MASK) > 0) {
            rt.setSlot(Val.asHeap(t), T_CNT, Val.fixnum(cnt - 1));
            return t;
        }
        int base = rt.mark();
        int ti = rt.push(t);
        long newtail = tArrayFor(rt, t, cnt - 2);
        int nt = rt.push(newtail);
        long tv = rt.r(ti);
        int sh = tshift(rt, tv);
        long newroot = popTail(rt, sh, rt.slot(tv, T_ROOT), cnt);
        int newshift = sh;
        if (Val.isNil(newroot)) {
            newroot = newNode(rt, WIDTH, rt.slot(rt.r(ti), T_EDIT));
        }
        int nri = rt.push(newroot);
        if (newshift > BITS && Val.isNil(nodeGet(rt, rt.r(nri), 1))) {
            rt.setR(nri, nodeGet(rt, rt.r(nri), 0));
            newshift -= BITS;
        }
        tv = rt.r(ti);
        long a = Val.asHeap(tv);
        rt.setSlot(a, T_ROOT, rt.r(nri));
        rt.setSlot(a, T_TAIL, rt.r(nt));
        rt.setSlot(a, T_SHIFT, Val.fixnum(newshift));
        rt.setSlot(a, T_CNT, Val.fixnum(cnt - 1));
        rt.popTo(base);
        return tv;
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
