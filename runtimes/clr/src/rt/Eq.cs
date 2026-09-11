namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Eq;


/// Structural equality and hashing, ported from `runtime/src/eq.rs`.
///
/// These two belong together because they must AGREE: two values that are `=`
/// must hash alike, or a map loses keys it is holding. That is why the same
/// file decides both, and why every case added to one is added to the other.
///
/// The method is `Equal` here where the JVM writes `eq`: C# forbids a member
/// with the same name as its enclosing type, so `Eq.Eq` is not sayable. Second
/// place the host's own rules force a different spelling -- `Obj.LVals` was the
/// first -- and worth naming so the next reader does not read it as drift.
///
/// `eq` on a compound value CAN ALLOCATE -- it seqs both sides -- so a caller
/// holding heap addresses across a call to it must have them rooted. The map
/// code does; that requirement is the reason `nodeFind` roots its walk.
public static class Eq {

    /// The three things `=` dispatches on. A value's CATEGORY, not its type: a
    /// vector, a list and a map entry are all sequential and compare
    /// elementwise, which is what makes `(= [1 2] '(1 2))` true.
    public const int CAT_SCALAR = 0, CAT_SEQUENTIAL = 1, CAT_MAP = 2, CAT_SET = 3;



    // `Equal` and `SeqEq` are GENERATED, from `kin/valeq.kin`, as
    // `Valeq.ValEq` and `Valeq.SeqEq`. The hand-written `Equal` tested for a
    // ROW REF *below* the category switch, where it could never run:
    // `Category` puts a ref in CAT_MAP, so every ref reached `Maps.Eq` first
    // and every non-map counterpart was refused by `ca != cb`. It worked only
    // because `Maps.Eq` materialises refs itself, which native's does not.

    /// The hash of a value, agreeing with `eq` above and with Clojure's.
    // `HashValue` is GENERATED, from `kin/valhash.kin`, as
    // `Valhash.ValueHash`. Two caches this port HAD and never used are live
    // now: `StrHash` was written during interning and never read, and a
    // keyword's slot 2 was set to nil rather than to its hash -- so the two
    // commonest map keys there are each paid their content per lookup.

    static bool SameBytes(byte[] x, byte[] y) {
        if (x.Length != y.Length) return false;
        for (int i = 0; i < x.Length; i++) if (x[i] != y[i]) return false;
        return true;
    }

    /// Clojure's `compare`: -1, 0 or 1, and a THROW for values that have no
    /// ordering. Refusing is the right answer -- a `sort` over mixed types
    /// silently ordered by type tag would be stable, plausible and wrong.

    // `Compare`, `CmpNamed` and `CmpSequential` are GENERATED, from
    // `kin/valcmp.kin`, and `str-cmp` from `kin/ropecmp.kin`.
    // the generated arm reaches it by -- native was reading a rope's SLOTS as
    // UTF-8 where this port materialised and was right.

    static long NsOf(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Names.NsOf(rt, v);
    static long NameOf(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Names.NameOf(rt, v);

    /// Length first is WRONG for sequences: `[1 2]` is less than `[1 3]`, and
    /// both are less than `[1 2 3]`. So shorter-is-less only decides a tie.
    static byte[] NsBytes(Rt rt, long v) {
        long ns = rt.Slot(v, 0);
        return Val.IsNil(ns) ? null : Str.Bytes(rt, ns);
    }
}
