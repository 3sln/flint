namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Seqcore;
using static _3sln.Flint.Kgen.Rt.Seqwalk;

using _3sln.Flint.Kgen.Rt;

using static _3sln.Flint.Kgen.Rt.Seqs;

/// Seqs, ported from `runtime/src/seqs.rs`.
///
///   TY_CONS       [first, rest, meta, count]   count = fixnum, or nil if unknown
///   TY_EMPTY_LIST [meta]
///   TY_LAZYSEQ    [thunk, seq, meta]           thunk becomes nil once forced
///   TY_VECSEQ     [vec, index, meta]
///   TY_RANGE      [start, end, step, meta]
///
/// `seq` over a vector is a VECSEQ -- a cursor, not a copy -- so walking one
/// allocates a small object per step rather than materialising anything.
public static class Seqs {

    /// `First` and `Next`, under the names their callers already use -- the
    /// bodies are generated, as `Seqwalk`.
    public static long Force(Rt rt, long ls) { return _3sln.Flint.Kgen.Rt.Seqwalk.Force(rt, ls); }

    /// `Cons`, under the name its callers already use -- the body is
    /// generated, as `Seqcore.Cons`.
    public const int C_FIRST = 0, C_REST = 1, C_META = 2, C_COUNT = 3;



    /// The ONE empty list, not a fresh one. See `Seqs.java` for why this
    /// allocated for so long, and what it cost.
    public static long EmptyList(Rt rt) {
        return rt.roots.shared.Singletons[Rt.SingEmptyList];
    }

    /// A lazy seq's slots. Outside the generated region, for the same reason
    /// as in `Seqs.java`.
    public const int LS_THUNK = 0, LS_SEQ = 1;



    public static int Count(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Seqwalk.SeqCount(rt, v); }

    public static long FromRoots(Rt rt, int bas, int n) { return global::_3sln.Flint.Kgen.Rt.Seqcore.ListFromRoots(rt, bas, n); }
}
