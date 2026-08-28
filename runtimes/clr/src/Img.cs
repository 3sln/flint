using System.Text;

namespace Flint;

/// A flint program image, read (`runtime/src/image.rs`).
///
/// **The bytecode is the portable artifact** (`doc/decisions/0010`): a new host
/// needs this loader, a loop over the opcodes and the builtins -- not a
/// compiler. The reader, the analyzer and the whole core library are already
/// portable and compile to exactly this.
public sealed class Img {
    public const int Version = 3;

    /// Image flags, a trailing u32. Bit 0 is `:optimize [perf]` -- what the
    /// compiler DECIDED, not what it was asked.
    ///
    /// A flag rather than "does the image carry compiled arities": on wasm the
    /// arity table IS the answer, and here it never can be, because this port
    /// emits its own IL at LOAD time from the same bytecode. An image meant for
    /// it carries the preference and an empty table.
    public const int FlagPerf = 1;

    private const int KNil = 0, KTrue = 1, KFalse = 2, KInt = 3, KDouble = 4,
        KString = 5, KKeyword = 6, KSymbol = 7, KVector = 8, KList = 9,
        KMap = 10, KSet = 11, KFn = 12, KNative = 13;
    private const long NoConst = 0xFFFF_FFFFL;

    public readonly object[] Consts;
    public readonly FnDef[] Fns;
    public readonly byte[] Code;
    public readonly int Entry;
    public readonly int[] Init;
    /// Native import index to builtin NAME. Slots belong to the module an image
    /// was linked against and mean nothing here, so a host resolves by name --
    /// which is what makes an image portable between hosts at all.
    public readonly string[] NativeNames;
    public readonly string[] VarNames;
    /// What the compiler decided. `Vm` reads `FlagPerf` and turns its own AOT
    /// on, so one `:optimize` reaches all three runtimes.
    public readonly int Flags;

    public sealed class Arity {
        public readonly int Argc, Nlocals, Code, Len;
        public readonly bool Variadic;
        public Arity(int argc, bool variadic, int nlocals, int code, int len) {
            Argc = argc; Variadic = variadic; Nlocals = nlocals; Code = code; Len = len;
        }
    }

    public sealed class FnDef {
        public readonly string Name;
        public readonly Arity[] Arities;
        public readonly int Nupvals;
        public FnDef(string name, Arity[] arities, int nupvals) {
            Name = name; Arities = arities; Nupvals = nupvals;
        }
        /// Exact arity wins; otherwise the widest variadic that fits. Same rule
        /// as every other runtime, and it has to be: a program that dispatches
        /// differently per host is not one program.
        public Arity Select(int argc) {
            Arity best = null;
            foreach (var a in Arities) {
                if (!a.Variadic && a.Argc == argc) return a;
                if (a.Variadic && argc >= a.Argc && (best == null || best.Argc < a.Argc)) best = a;
            }
            return best;
        }
    }

    private sealed class R {
        private readonly byte[] _b; private int _i;
        public R(byte[] b) { _b = b; _i = 0; }
        public int U8() => _b[_i++];
        public int U16() => U8() | (U8() << 8);
        public long U32() => (long) U16() | ((long) U16() << 16);
        public long U64() => U32() | (U32() << 32);
        public byte[] Bytes(int n) { var o = new byte[n]; Array.Copy(_b, _i, o, 0, n); _i += n; return o; }
        public string Str() => Encoding.UTF8.GetString(Bytes((int) U32()));
    }

    private Img(object[] consts, FnDef[] fns, byte[] code, int entry, int[] init,
                string[] nativeNames, string[] varNames, int flags) {
        Consts = consts; Fns = fns; Code = code; Entry = entry; Init = init;
        NativeNames = nativeNames; VarNames = varNames; Flags = flags;
    }

    public static Img Read(byte[] bytes) {
        var r = new R(bytes);
        foreach (char c in "FLINTIMG")
            if (r.U8() != (byte) c) throw new ArgumentException("not a flint image");
        long version = r.U32();
        if (version != Version)
            throw new ArgumentException($"this image is version {version} and this runtime reads {Version}");

        int nnatives = (int) r.U32();
        var nativeNameConst = new int[nnatives];
        for (int i = 0; i < nnatives; i++) {
            nativeNameConst[i] = (int) r.U32();
            r.U32(); // the table slot: the linked module's, and meaningless here
        }

        int nconsts = (int) r.U32();
        var consts = new object[nconsts];
        for (int i = 0; i < nconsts; i++) consts[i] = ReadConst(r, consts);

        int nfns = (int) r.U32();
        var fns = new FnDef[nfns];
        for (int i = 0; i < nfns; i++) {
            long nameIdx = r.U32();
            int nupvals = r.U8();
            int na = r.U8();
            var arities = new Arity[na];
            for (int j = 0; j < na; j++) {
                int argc = r.U8();
                int flags = r.U8();
                int nlocals = r.U16();
                int codeAt = (int) r.U32();
                int len = (int) r.U32();
                r.U32(); // the compiled-arity index: a wasm concern
                arities[j] = new Arity(argc, (flags & 1) != 0, nlocals, codeAt, len);
            }
            string name = nameIdx == NoConst ? null : Convert.ToString(consts[(int) nameIdx]);
            fns[i] = new FnDef(name, arities, nupvals);
        }

        int nvars = (int) r.U32();
        var varNames = new string[nvars];
        for (int i = 0; i < nvars; i++) {
            long k = r.U32();
            varNames[i] = k == NoConst ? null : Convert.ToString(consts[(int) k]);
        }

        int codelen = (int) r.U32();
        byte[] code = r.Bytes(codelen);
        int entry = (int) r.U32();
        int ninit = (int) r.U32();
        var init = new int[ninit];
        for (int i = 0; i < ninit; i++) init[i] = (int) r.U32();

        // The compiled-arity table (`doc/decisions/0013`). This port emits its
        // own IL and has no use for wasm table slots, so the table is SKIPPED
        // rather than read -- exactly, because the flags word is behind it.
        long naot = r.U32();
        for (long k = 0; k < naot; k++) {
            r.U32(); r.U32();                       // slot, depth
            long np = r.U32();
            for (long j = 0; j < np; j++) { r.U32(); r.U32(); }
        }
        int imageFlags = (int) r.U32();

        var nativeNames = new string[nnatives];
        for (int i = 0; i < nnatives; i++)
            nativeNames[i] = Convert.ToString(consts[nativeNameConst[i]]);
        return new Img(consts, fns, code, entry, init, nativeNames, varNames, imageFlags);
    }

    private static object ReadConst(R r, object[] built) {
        int tag = r.U8();
        switch (tag) {
            case KNil: return null;
            case KTrue: return true;
            case KFalse: return false;
            case KInt: return r.U64();
            case KDouble: return BitConverter.Int64BitsToDouble(r.U64());
            case KString: return r.Str();
            case KKeyword: {
                long ns = r.U32();
                string name = Convert.ToString(built[(int) r.U32()]);
                return Kw.Of(ns == NoConst ? null : Convert.ToString(built[(int) ns]), name);
            }
            case KSymbol: {
                long ns = r.U32();
                string name = Convert.ToString(built[(int) r.U32()]);
                return Sym.Of(ns == NoConst ? null : Convert.ToString(built[(int) ns]), name);
            }
            case KVector: case KList: case KSet: {
                int n = (int) r.U32();
                var xs = new List<object>(n);
                for (int i = 0; i < n; i++) xs.Add(built[(int) r.U32()]);
                if (tag == KSet) return new FlintSet(xs);
                return tag == KVector ? (object) new Vec(xs) : new Seq(xs);
            }
            case KMap: {
                int n = (int) r.U32();
                var m = FlintMap.Empty;
                for (int i = 0; i < n; i++) {
                    object k = built[(int) r.U32()];
                    m = m.Assoc(k, built[(int) r.U32()]);
                }
                return m;
            }
            case KFn: return new FnRef((int) r.U32());
            case KNative: {
                // TWO fields: the import index and the constant holding its
                // NAME. Reading one leaves the stream four bytes short, and the
                // next tag read is whatever the name's first character was.
                int idx = (int) r.U32();
                int namec = (int) r.U32();
                return new NativeRef(idx, Convert.ToString(built[namec]));
            }
            default: throw new ArgumentException("unknown constant tag " + tag);
        }
    }

    public sealed record FnRef(int Index);
    public sealed record NativeRef(int Index, string Name);
}
