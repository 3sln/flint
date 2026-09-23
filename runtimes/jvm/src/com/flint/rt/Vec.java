package com.flint.rt;

import static com.flint.rt.Obj.*;
import static com._3sln.flint.kgen.rt.Vecnode.*;
import static com._3sln.flint.kgen.rt.Vecread.*;
import static com._3sln.flint.kgen.rt.Vecwrite.*;
import static com._3sln.flint.kgen.rt.Vecassoc.*;
import static com._3sln.flint.kgen.rt.Vectrans.*;
import static com._3sln.flint.kgen.rt.Vectwrite.*;

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

    // `count` IS A SHIM NOW, the way `Maps.eq` is: `kin/vecread.kin` generates
    // it, and the hand-written callers in `Builtins`, `Conc` and `Pike` keep
    // the name they already spell.
    public static int count(Rt rt, long v) { return com._3sln.flint.kgen.rt.Vecread.vecCount(rt, v); }

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

    /// `conj`, under the name 23 call sites in this runtime already use
    /// (20 on the clr). This line said 53 until it was counted on
    /// 2026-09-22; the figure that bought "worth doing LAST" below was
    /// roughly twice the real bill.
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
    public static long nth(Rt rt, long v, int i, long dflt) { return vecNth(rt, v, i, dflt); }

    public static int tcount(Rt rt, long t) { return tvecCount(rt, t); }
    public static boolean alive(Rt rt, long t) { return tvecAlive(rt, t); }
    public static long transientOf(Rt rt, long v) { return vecTransient(rt, v); }
    public static long tconj(Rt rt, long t, long x) { return tvecConj(rt, t, x); }
    public static long tassoc(Rt rt, long t, int i, long x) { return tvecAssoc(rt, t, i, x); }
    public static long tpersistent(Rt rt, long t) { return tvecPersistent(rt, t); }









    /// A VECTOR OUT OF A RUN OF SHADOW-STACK ROOTS. The body is GENERATED,
    /// as `kin/vecroots.kin`; this keeps the name its callers already spell,
    /// the way `conj` and `assoc` above do.
    public static long fromRoots(Rt rt, int base, int n) { return com._3sln.flint.kgen.rt.Vecroots.vecFromRoots(rt, base, n); }

    // `mapEntryAsVec` HAS NO SHIM HERE, and that is not an oversight. A
    // generated module reaches it through `import static ...Vecroots.*`, and
    // a same-named static on `Vec` -- also star-imported there -- makes every
    // call AMBIGUOUS: javac refuses `Collwrite.java` outright. One name, one
    // home, and `Builtins` says the generated one.

    // -----------------------------------------------------------------------
    // TRANSIENTS. `TY_TVEC [cnt, shift, root, tail, edit]`.
    //
    // `edit` is a freshly allocated object used purely for its IDENTITY. A node
    // whose token is that same object is owned by this transient and is mutated
    // in place; any other node is copied once and thereafter owned.
    // `persistent!` clears `edit`, so a stale handle fails loudly instead of
    // quietly mutating a value somebody else is now holding.

    public static final int T_CNT = 0, T_SHIFT = 1, T_ROOT = 2, T_TAIL = 3, T_EDIT = 4;













}
