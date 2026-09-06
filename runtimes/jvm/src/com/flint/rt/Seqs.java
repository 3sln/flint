package com.flint.rt;

import com._3sln.flint.kgen.rt.Mapcore;

import static com.flint.rt.Obj.*;
import static com._3sln.flint.kgen.rt.Seqcore.*;
import static com._3sln.flint.kgen.rt.Seqwalk.*;
import static com._3sln.flint.kgen.rt.Seqs.*;

/// Seqs, ported from `runtime/src/seqs.rs`.
///
/// <pre>
///   TY_CONS       [first, rest, meta, count]   count = fixnum, or nil if unknown
///   TY_EMPTY_LIST [meta]
///   TY_LAZYSEQ    [thunk, seq, meta]           thunk becomes nil once forced
///   TY_VECSEQ     [vec, index, meta]
///   TY_RANGE      [start, end, step, meta]
/// </pre>
///
/// `seq` over a vector is a VECSEQ -- a cursor, not a copy -- so walking one
/// allocates a small object per step rather than materialising anything.
public final class Seqs {

    /// `first` and `next`, under the names their callers already use. The
    /// bodies are GENERATED, as `Seqwalk`; some thirty call sites across this
    /// runtime say `Seqs.first`, in files that have nothing to do with seqs.
    public static long force(Rt rt, long ls) { return com._3sln.flint.kgen.rt.Seqwalk.force(rt, ls); }

    /// `cons`, under the name its callers already use. The body is GENERATED,
    /// as `Seqcore.cons`; renaming ~20 call sites across this runtime for a
    /// naming win is the trade `doc/goals/kin-port.md` answered with "LAST".
    public static long cons(Rt rt, long head, long tail) { return com._3sln.flint.kgen.rt.Seqcore.cons(rt, head, tail); }
    private Seqs() {}

    public static final int C_FIRST = 0, C_REST = 1, C_META = 2, C_COUNT = 3;



    /// The ONE empty list, not a fresh one.
    ///
    /// This allocated on every call while `initSingletons` wrote
    /// `SING_EMPTY_LIST` and nothing ever read it. `Rt`'s own comment records
    /// the same defect being fixed for maps, vectors and sets, with the
    /// numbers: the same program billed 143,247 steps here against 137,207
    /// native, because it allocated 346,928 bytes against 299,024. `Seqs` was
    /// missed. A budget that fits on one runtime has to fit on the others.
    public static long emptyList(Rt rt) {
        return rt.roots.shared.singletons[Rt.SING_EMPTY_LIST];
    }

    /// A lazy seq's slots. Outside the generated region, because kin has no
    /// form for a constant declaration and a region takes everything in its
    /// span -- which is how this was silently deleted the first time.
    public static final int LS_THUNK = 0, LS_SEQ = 1;



    public static int count(Rt rt, long v) { return com._3sln.flint.kgen.rt.Seqwalk.seqCount(rt, v); }

    public static long fromRoots(Rt rt, int base, int n) { return com._3sln.flint.kgen.rt.Seqcore.listFromRoots(rt, base, n); }
}
