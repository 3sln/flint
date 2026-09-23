package com.flint.rt;

import com._3sln.flint.kgen.rt.Seqwalk;

import static com.flint.rt.Obj.*;
import static com._3sln.flint.kgen.rt.Eq.*;

/// Structural equality and hashing, ported from `runtime/src/eq.rs`.
///
/// These two belong together because they must AGREE: two values that are `=`
/// must hash alike, or a map loses keys it is holding. That is why the same
/// file decides both, and why every case added to one is added to the other.
///
/// `eq` on a compound value CAN ALLOCATE -- it seqs both sides -- so a caller
/// holding heap addresses across a call to it must have them rooted. The map
/// code does; that requirement is the reason `nodeFind` roots its walk.
public final class Eq {
    private Eq() {}

    /// The three things `=` dispatches on. A value's CATEGORY, not its type: a
    /// vector, a list and a map entry are all sequential and compare
    /// elementwise, which is what makes `(= [1 2] '(1 2))` true.
    public static final int CAT_SCALAR = 0, CAT_SEQUENTIAL = 1, CAT_MAP = 2, CAT_SET = 3;



    // `eq` and `seqEq` are GENERATED, from `kin/valeq.kin`, as
    // `Valeq.valEq` and `Valeq.seqEq`. The hand-written `eq` here tested for
    // a ROW REF *below* the category switch, where it could never run:
    // `category` puts a ref in CAT_MAP, so every ref reached `Maps.eq` first
    // and every non-map counterpart was refused by `ca != cb`. It worked only
    // because `Maps.eq` materialises refs itself, which native's does not.

    /// The hash of a value, agreeing with `eq` above and with Clojure's.
    // `hashValue` is GENERATED, from `kin/valhash.kin`, as
    // `Valhash.valueHash`. Two caches this port HAD and never used are live
    // now: `strHash` was written during interning and never read, and a
    // keyword's slot 2 was set to nil rather than to its hash -- so the two
    // commonest map keys there are each paid their content per lookup.

    /// Clojure's `compare`: -1, 0 or 1, and a THROW for values that have no
    /// ordering. Refusing is the right answer -- a `sort` over mixed types
    /// silently ordered by type tag would be stable, plausible and wrong.

    // `compare`, `cmpNamed` and `cmpSequential` are GENERATED, and `str-cmp`
    // is too now, from `kin/ropecmp.kin`. The UTF-16 comparison that used to
    // live here went with it: it ordered by code UNIT to match
    // `String.compareTo`, which differs from code point order only above
    // U+FFFF, and reaching it meant materialising both ropes first.

    /// THE SECOND COPY IS GONE. `Eq` had its own `nameOf` that skipped the
    /// type check -- fine for the values it was handed, and one more place to
    /// keep in step.
    static long nameOf(Rt rt, long v) { return com._3sln.flint.kgen.rt.Names.nameOf(rt, v); }

}
