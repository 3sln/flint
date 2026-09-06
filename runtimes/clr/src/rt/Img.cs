using System.Text;

namespace Flint.Rt;

using _3sln.Flint.Kgen.Rt;

/// The image loader, ported from `runtime/src/image.rs`.
///
///   "FLINTIMG" u32 version
///   u32 nnatives   ; (name-const, table-slot) pairs, FIRST and fixed width
///   u32 nconsts    ; tagged entries, in dependency order
///   u32 nfns       ; function table
///   u32 nvars      ; var name constant indices
///   u32 codelen    ; bytecode
///   u32 entry      ; fn index called with the argument vector
///   u32 ninit      ; fn indices to run first, in order
///   u32 naot       ; compiled arities (skipped here)
///   u32 flags      ; what the compiler decided
///
/// Constants may reference EARLIER constants by index, which is why the writer
/// emits them in dependency order and this builds them in one pass.
public static class Img {
    public const int Version = 3;
    public const int FlagPerf = 1;

    const int KNil = 0, KTrue = 1, KFalse = 2, KInt = 3, KDouble = 4,
        KString = 5, KKeyword = 6, KSymbol = 7, KVector = 8, KList = 9,
        KMap = 10, KSet = 11, KFn = 12, KNative = 13,
        /// 17, not 14: the image's tags and the wire codec's share a numbering
        /// space, and 14/15/16 are bytes, port and sentinel (`0025`, `0034`).
        KTagged = 17;
    const long NoConst = 0xFFFF_FFFFL;

    public sealed class Loaded {
        public string[] nativeNames;
        public int entry;
        public int[] init;
        public int[] varNames;
        public int flags;
    }

    sealed class R {
        readonly byte[] b;
        public int i;
        public R(byte[] b, int i) { this.b = b; this.i = i; }
        public int U8() => b[i++] & 0xFF;
        public int U16() { int lo = U8(); return lo | (U8() << 8); }
        public long U32() { long lo = U16(); return lo | ((long) U16() << 16); }
        public long U64() { long lo = U32(); return lo | (U32() << 32); }
        public long I64() => U64();
        public byte[] Bytes(int n) { byte[] o = new byte[n]; System.Array.Copy(b, i, o, 0, n); i += n; return o; }
    }

    /// Read `bytes` into `rt`. Returns null if it is not a flint image or is a
    /// version this runtime does not speak -- refused by name rather than read
    /// as a plausible heap that means something else.
    public static Loaded Load(Rt rt, byte[] bytes) {
        R r = new R(bytes, 0);
        foreach (byte c in Encoding.ASCII.GetBytes("FLINTIMG")) {
            if (r.U8() != (c & 0xFF)) return null;
        }
        // Over the WHOLE image, before anything is interpreted. FNV-1a: a
        // snapshot only has to DETECT a different program, not resist one, and
        // an incremental pass over a few hundred KB costs nothing next to the
        // load it precedes.
        long h = unchecked((long) 0xcbf29ce484222325UL);
        foreach (byte b in bytes) {
            h ^= (b & 0xFFL);
            h = unchecked(h * 0x100000001b3L);
        }
        rt.fingerprint = h;
        if (r.U32() != Version) return null;

        Loaded outl = new Loaded();

        int nnat = (int) r.U32();
        int[] nativeNameConst = new int[nnat];
        for (int i = 0; i < nnat; i++) {
            nativeNameConst[i] = (int) r.U32();
            r.U32();   // the table slot: the linked module's, meaningless here
        }

        int nconsts = (int) r.U32();
        long[] consts = new long[nconsts];
        rt.consts = consts;
        rt.roots.shared.Consts = consts;
        for (int i = 0; i < nconsts; i++) consts[i] = ReadConst(rt, r, consts, i);

        int nfns = (int) r.U32();
        Rt.FnDef[] fns = new Rt.FnDef[nfns];
        for (int i = 0; i < nfns; i++) {
            int fname = (int) r.U32();     // the name constant, for diagnostics
            int nupvals = r.U8();
            int na = r.U8();
            Rt.Arity[] arities = new Rt.Arity[na];
            for (int k = 0; k < na; k++) {
                int argc = r.U8();
                int flags = r.U8();
                int nlocals = r.U16();
                int code = (int) r.U32();
                int len = (int) r.U32();
                r.U32();                   // the compiled-arity index: a wasm concern
                arities[k] = new Rt.Arity(argc, (flags & 1) != 0, nlocals, code, len);
            }
            fns[i] = new Rt.FnDef(arities, nupvals, fname);
        }
        rt.fns = fns;

        int nvars = (int) r.U32();
        outl.varNames = new int[nvars];
        for (int i = 0; i < nvars; i++) outl.varNames[i] = (int) r.U32();
        rt.roots.shared.Globals = new long[nvars];
        System.Array.Fill(rt.roots.shared.Globals, Val.Nil);

        int codelen = (int) r.U32();
        rt.code = r.Bytes(codelen);
        outl.entry = (int) r.U32();
        int ninit = (int) r.U32();
        outl.init = new int[ninit];
        for (int i = 0; i < ninit; i++) outl.init[i] = (int) r.U32();

        // Compiled arities: skipped, but skipped EXACTLY, because the flags
        // word is behind them.
        long naot = r.U32();
        for (long k = 0; k < naot; k++) {
            r.U32(); r.U32();
            long np = r.U32();
            for (long j = 0; j < np; j++) { r.U32(); r.U32(); }
        }
        outl.flags = (int) r.U32();

        // Resolved BY NAME. A slot in an image belongs to the module it was
        // linked against; re-resolving here is what makes an image portable.
        // A missing builtin is left null rather than refused: an image imports
        // every builtin its namespaces mention, and a program that never calls
        // the missing one runs fine. The failure names it if it is reached.
        outl.nativeNames = new string[nnat];
        rt.natives = new Builtins.Fn[nnat];
        rt.nativeNames = outl.nativeNames;
        for (int i = 0; i < nnat; i++) {
            outl.nativeNames[i] = Str.Text(rt, consts[nativeNameConst[i]]);
            rt.natives[i] = Builtins.ByName(outl.nativeNames[i]);
        }
        return outl;
    }

    static string KindName(int t) => t switch {
        KVector => "vector", KList => "list", KMap => "map",
        KSet => "set", _ => "kind " + t,
    };

    static long ReadConst(Rt rt, R r, long[] consts, int self) {
        int tag = r.U8();
        switch (tag) {
            case KNil: return Val.Nil;
            case KTrue: return Val.True;
            case KFalse: return Val.False;
            // `Num.Integer`, NOT `Val.Fixnum`. A literal outside the fixnum
            // range has to be BOXED, and this truncated it instead: `2^62`
            // read back as 0, `long.MaxValue` as -1. Native has always called
            // `integer` here. An integer COMPUTED at runtime was fine, so the
            // bug only ever showed for a literal -- which is exactly the
            // value nobody thinks to test.
            case KInt: return Num.Integer(rt, r.I64());
            case KDouble: return Val.OfDouble(System.BitConverter.Int64BitsToDouble(r.U64()));
            case KString: {
                int n = (int) r.U32();
                return Str.Of(rt, Encoding.UTF8.GetString(r.Bytes(n)));
            }
            case KKeyword:
            case KSymbol: {
                // Into LOCALS, and in this order: two reads that each advance
                // the cursor, so the order they happen in is the format.
                long nsc = r.U32();
                long nmc = r.U32();
                string ns = nsc == NoConst ? null : Str.Text(rt, consts[(int) nsc]);
                string nm = Str.Text(rt, consts[(int) nmc]);
                return tag == KKeyword ? Str.Keyword(rt, ns, nm) : Str.Symbol(rt, ns, nm);
            }
            case KTagged: {
                long tagc = r.U32(), formc = r.U32();
                return rt.NewTagged(consts[(int) tagc], consts[(int) formc]);
            }
            case KNative: {
                long idx = r.U32();
                long namec = r.U32();
                int bas = rt.Mark();
                int ni = rt.Push(consts[(int) namec]);
                long a = rt.Alloc(Obj.TyNativefn, 2);
                if (a == 0) { rt.PopTo(bas); return Val.Nil; }
                rt.SetSlot(a, 0, Val.Fixnum(idx));
                rt.SetSlot(a, 1, rt.R(ni));
                rt.PopTo(bas);
                return Val.Heap(a);
            }
            case KVector:
            case KList:
            case KSet: {
                // The elements are EARLIER constants, by index: the writer
                // emits in dependency order so one pass suffices.
                int n = (int) r.U32();
                int bas = rt.Mark();
                for (int i = 0; i < n; i++) rt.Push(consts[(int) r.U32()]);
                long outv;
                if (tag == KVector) {
                    outv = Vec.FromRoots(rt, bas, n);
                } else if (tag == KList) {
                    outv = Seqs.FromRoots(rt, bas, n);
                } else {
                    int si = rt.Push(Sets.Empty(rt));
                    for (int i = 0; i < n; i++) rt.SetR(si, Sets.Conj(rt, rt.R(si), rt.R(bas + i)));
                    outv = rt.R(si);
                }
                rt.PopTo(bas);
                return outv;
            }
            case KMap: {
                int n = (int) r.U32();
                int bas = rt.Mark();
                for (int i = 0; i < 2 * n; i++) rt.Push(consts[(int) r.U32()]);
                int mi = rt.Push(Maps.Empty(rt));
                for (int i = 0; i < n; i++) {
                    rt.SetR(mi, Mapwrite.MapAssoc(rt, rt.R(mi), rt.R(bas + 2 * i), rt.R(bas + 2 * i + 1)));
                }
                long outv = rt.R(mi);
                rt.PopTo(bas);
                return outv;
            }
            case KFn: {
                long f = r.U32();
                // A closure with no upvalues: a top-level fn is a constant.
                return rt.MakeClosure((int) f, System.Array.Empty<long>());
            }
            default:
                throw new System.NotSupportedException(
                    "constant kind " + tag + " needs the data structures ported"
                    + " (" + KindName(tag) + ")");
        }
    }
}
