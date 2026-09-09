namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Vecnode;
using static _3sln.Flint.Kgen.Rt.Vecread;
using static _3sln.Flint.Kgen.Rt.Vecwrite;
using static _3sln.Flint.Kgen.Rt.Vecassoc;
using static _3sln.Flint.Kgen.Rt.Vectrans;
using static _3sln.Flint.Kgen.Rt.Vectwrite;

/// Persistent vectors, ported from `runtime/src/vector.rs`.
///
/// A 32-way trie with a TAIL: the last up-to-32 elements live in a flat node
/// hanging off the vector, so appending is O(1) amortised and does not touch
/// the trie until the tail fills. That is what makes `conj` cheap and why
/// `TailOff` appears in every access path.
///
/// `TY_VEC` is `[cnt, shift, root, tail, meta]`; `TY_NODE` is `len` values.
public static class Vec {
    public const int BITS = 5;
    public const int WIDTH = 1 << BITS;   // 32
    public const int MASK = WIDTH - 1;

    public const int V_CNT = 0, V_SHIFT = 1, V_ROOT = 2, V_TAIL = 3, V_META = 4;

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
    public const int V_HASH = 5;

    // @kin:link:ns: flint.rt.vector
    // @kin:link:form:vec-count: {:template "Vec.Count({0}, {1})"}
    public static int Count(Rt rt, long v) => (int) Val.AsFixnum(rt.Slot(v, V_CNT));



    public static long Empty(Rt rt) {
        long sg = rt.roots.shared.Singletons[Rt.SingEmptyVec];
        if (!Val.IsNil(sg)) return sg;
        return NewEmpty(rt);
    }

    internal static long NewEmpty(Rt rt) {
        int bas = rt.Mark();
        long root = NewNode(rt, WIDTH, Val.Nil);
        int ri = rt.Push(root);
        long tail = NewNode(rt, 0, Val.Nil);
        int ti = rt.Push(tail);
        long outv = NewVec(rt, 0, BITS, rt.R(ri), rt.R(ti), Val.Nil);
        rt.PopTo(bas);
        return outv;
    }

    /// The leaf array holding index `i`.
    /// `conj`, under the name 53 call sites in this runtime already use.
    ///
    /// The body is GENERATED, as `Vecwrite.VecConj`, and Rust's callers say
    /// `vec_conj` directly. Renaming these would be 53 edits here and 53 more
    /// on the JVM, in files that have nothing to do with vectors, for a naming
    /// win -- which is the trade `doc/goals/kin-port.md` already recorded
    /// against the `champ_*` wrappers and answered with "worth doing LAST".
    public static long Conj(Rt rt, long v, long x) { return VecConj(rt, v, x); }
    /// `assoc` and `pop`, likewise delegating to their generated bodies.
    public static long Assoc(Rt rt, long v, int i, long x) { return VecAssoc(rt, v, i, x); }
    public static long Pop(Rt rt, long v) { return VecPop(rt, v); }
    public static long Nth(Rt rt, long v, int i, long dflt) { return VecNth(rt, v, i, dflt); }
    public static long TNth(Rt rt, long t, int i, long dflt) { return TvecNth(rt, t, i, dflt); }

    /// The TRANSIENT surface, under the names its callers already use.
    public static bool IsTransient(Rt rt, long v) { return IsTransientVector(rt, v); }
    public static int TCount(Rt rt, long t) { return TvecCount(rt, t); }
    public static bool Alive(Rt rt, long t) { return TvecAlive(rt, t); }
    public static long TransientOf(Rt rt, long v) { return VecTransient(rt, v); }
    public static long TConj(Rt rt, long t, long x) { return TvecConj(rt, t, x); }
    public static long TAssoc(Rt rt, long t, int i, long x) { return TvecAssoc(rt, t, i, x); }
    public static long TPop(Rt rt, long t) { return TvecPop(rt, t); }
    public static long TPersistent(Rt rt, long t) { return TvecPersistent(rt, t); }









    /// A MAP ENTRY AS A TWO-ELEMENT VECTOR. It was a private helper in
    /// `Builtins` here and lives on `Vec` in the native runtime; `assoc` and
    /// `conj` both reach it, and generated code needs one home.
    public static long MapEntryAsVec(Rt rt, long e) {
        int bas = rt.Mark();
        rt.Push(rt.Slot(e, 0));
        rt.Push(rt.Slot(e, 1));
        long outv = FromRoots(rt, bas, 2);
        rt.PopTo(bas);
        return outv;
    }

    public static long FromRoots(Rt rt, int bas, int n) {
        int mk = rt.Mark();
        int vi = rt.Push(Empty(rt));
        for (int i = 0; i < n; i++) {
            long nv = VecConj(rt, rt.R(vi), rt.R(bas + i));
            rt.SetR(vi, nv);
        }
        long outv = rt.R(vi);
        rt.PopTo(mk);
        return outv;
    }

    // -----------------------------------------------------------------------
    // TRANSIENTS. `TY_TVEC [cnt, shift, root, tail, edit]`.
    //
    // `edit` is a freshly allocated object used purely for its IDENTITY. A node
    // whose token is that same object is owned by this transient and is mutated
    // in place; any other node is copied once and thereafter owned.
    // `persistent!` clears `edit`, so a stale handle fails loudly instead of
    // quietly mutating a value somebody else is now holding.

    public const int T_CNT = 0, T_SHIFT = 1, T_ROOT = 2, T_TAIL = 3, T_EDIT = 4;













}
