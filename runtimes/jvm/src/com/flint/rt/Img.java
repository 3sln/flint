package com.flint.rt;

import com._3sln.flint.kgen.rt.Mapwrite;

import static com.flint.rt.Obj.*;

/// The image loader, ported from `runtime/src/image.rs`.
///
/// <pre>
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
/// </pre>
///
/// Constants may reference EARLIER constants by index, which is why the writer
/// emits them in dependency order and this builds them in one pass.
public final class Img {
    private Img() {}

    public static final int VERSION = 3;
    public static final int FLAG_PERF = 1;

    static final int K_NIL = 0, K_TRUE = 1, K_FALSE = 2, K_INT = 3, K_DOUBLE = 4,
        K_STRING = 5, K_KEYWORD = 6, K_SYMBOL = 7, K_VECTOR = 8, K_LIST = 9,
        K_MAP = 10, K_SET = 11, K_FN = 12, K_NATIVE = 13,
        /// 17, not 14: the image's tags and the wire codec's share a numbering
        /// space, and 14/15/16 are bytes, port and sentinel (`0025`, `0034`).
        K_TAGGED = 17;
    static final long NO_CONST = 0xFFFF_FFFFL;

    public static final class Loaded {
        public String[] nativeNames;
        public int entry;
        public int[] init;
        public int[] varNames;
        public int flags;
    }

    static final class R {
        final byte[] b; int i;
        R(byte[] b, int i) { this.b = b; this.i = i; }
        int u8() { return b[i++] & 0xFF; }
        int u16() { return u8() | (u8() << 8); }
        long u32() { return (long) u16() | ((long) u16() << 16); }
        long u64() { return u32() | (u32() << 32); }
        long i64() { return u64(); }
        byte[] bytes(int n) { byte[] o = new byte[n]; System.arraycopy(b, i, o, 0, n); i += n; return o; }
    }

    /// Read `bytes` into `rt`. Returns null if it is not a flint image or is a
    /// version this runtime does not speak -- refused by name rather than read
    /// as a plausible heap that means something else.
    public static Loaded load(Rt rt, byte[] bytes) {
        R r = new R(bytes, 0);
        for (byte c : "FLINTIMG".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
            if (r.u8() != (c & 0xFF)) return null;
        }
        // Over the WHOLE image, before anything is interpreted. FNV-1a: a
        // snapshot only has to DETECT a different program, not resist one, and
        // an incremental pass over a few hundred KB costs nothing next to the
        // load it precedes.
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xFFL);
            h *= 0x100000001b3L;
        }
        rt.fingerprint = h;
        if (r.u32() != VERSION) return null;

        Loaded out = new Loaded();

        int nnat = (int) r.u32();
        int[] nativeNameConst = new int[nnat];
        for (int i = 0; i < nnat; i++) {
            nativeNameConst[i] = (int) r.u32();
            r.u32();   // the table slot: the linked module's, meaningless here
        }

        int nconsts = (int) r.u32();
        long[] consts = new long[nconsts];
        rt.consts = consts;
        rt.roots.shared.consts = consts;
        for (int i = 0; i < nconsts; i++) {
            consts[i] = readConst(rt, r, consts, i);
        }

        int nfns = (int) r.u32();
        Rt.FnDef[] fns = new Rt.FnDef[nfns];
        for (int i = 0; i < nfns; i++) {
            int fname = (int) r.u32();     // the name constant, for diagnostics
            int nupvals = r.u8();
            int na = r.u8();
            Rt.Arity[] arities = new Rt.Arity[na];
            for (int k = 0; k < na; k++) {
                int argc = r.u8();
                int flags = r.u8();
                int nlocals = r.u16();
                int code = (int) r.u32();
                int len = (int) r.u32();
                r.u32();                   // the compiled-arity index: a wasm concern
                arities[k] = new Rt.Arity(argc, (flags & 1) != 0, nlocals, code, len);
            }
            fns[i] = new Rt.FnDef(arities, nupvals, fname);
        }
        rt.fns = fns;

        int nvars = (int) r.u32();
        out.varNames = new int[nvars];
        for (int i = 0; i < nvars; i++) out.varNames[i] = (int) r.u32();
        rt.roots.shared.globals = new long[nvars];
        java.util.Arrays.fill(rt.roots.shared.globals, Val.NIL);

        int codelen = (int) r.u32();
        rt.code = r.bytes(codelen);
        out.entry = (int) r.u32();
        int ninit = (int) r.u32();
        out.init = new int[ninit];
        for (int i = 0; i < ninit; i++) out.init[i] = (int) r.u32();

        // Compiled arities: skipped, but skipped EXACTLY, because the flags
        // word is behind them.
        long naot = r.u32();
        for (long k = 0; k < naot; k++) {
            r.u32(); r.u32();
            long np = r.u32();
            for (long j = 0; j < np; j++) { r.u32(); r.u32(); }
        }
        out.flags = (int) r.u32();

        // Resolved BY NAME. A slot in an image belongs to the module it was
        // linked against; re-resolving here is what makes an image portable.
        // A missing builtin is left null rather than refused: an image imports
        // every builtin its namespaces mention, and a program that never calls
        // the missing one runs fine. The failure names it if it is reached.
        out.nativeNames = new String[nnat];
        rt.natives = new Builtins.Fn[nnat];
        rt.nativeNames = out.nativeNames;
        for (int i = 0; i < nnat; i++) {
            out.nativeNames[i] = Str.text(rt, consts[nativeNameConst[i]]);
            rt.natives[i] = Builtins.byName(out.nativeNames[i]);
        }
        return out;
    }

    static String kindName(int t) {
        return switch (t) {
            case K_VECTOR -> "vector"; case K_LIST -> "list"; case K_MAP -> "map";
            case K_SET -> "set"; default -> "kind " + t;
        };
    }

    private static long readConst(Rt rt, R r, long[] consts, int self) {
        int tag = r.u8();
        switch (tag) {
            case K_NIL: return Val.NIL;
            case K_TRUE: return Val.TRUE;
            case K_FALSE: return Val.FALSE;
            // `Num.integer`, NOT `Val.fixnum`. A literal outside the fixnum
            // range has to be BOXED, and this truncated it instead: `2^62`
            // read back as 0, `Long.MAX_VALUE` as -1. Native has always
            // called `integer` here. An integer COMPUTED at runtime was fine,
            // so the bug only ever showed for a literal -- which is exactly
            // the value nobody thinks to test.
            case K_INT: return Num.integer(rt, r.i64());
            case K_DOUBLE: return Val.ofDouble(Double.longBitsToDouble(r.u64()));
            case K_STRING: {
                int n = (int) r.u32();
                return Str.of(rt, new String(r.bytes(n), java.nio.charset.StandardCharsets.UTF_8));
            }
            case K_KEYWORD:
            case K_SYMBOL: {
                long nsc = r.u32(), nmc = r.u32();
                String ns = nsc == NO_CONST ? null : Str.text(rt, consts[(int) nsc]);
                String nm = Str.text(rt, consts[(int) nmc]);
                return tag == K_KEYWORD ? Str.keyword(rt, ns, nm) : Str.symbol(rt, ns, nm);
            }
            case K_TAGGED: {
                long tagc = r.u32(), formc = r.u32();
                return rt.newTagged(consts[(int) tagc], consts[(int) formc]);
            }
            case K_NATIVE: {
                long idx = r.u32(), namec = r.u32();
                int base = rt.mark();
                int ni = rt.push(consts[(int) namec]);
                long a = rt.alloc(TY_NATIVEFN, 2);
                if (a == 0) { rt.popTo(base); return Val.NIL; }
                rt.setSlot(a, 0, Val.fixnum(idx));
                rt.setSlot(a, 1, rt.r(ni));
                rt.popTo(base);
                return Val.heap(a);
            }
            case K_VECTOR:
            case K_LIST:
            case K_SET: {
                // The elements are EARLIER constants, by index: the writer
                // emits in dependency order so one pass suffices.
                int n = (int) r.u32();
                int base = rt.mark();
                for (int i = 0; i < n; i++) rt.push(consts[(int) r.u32()]);
                long out;
                if (tag == K_VECTOR) {
                    out = Vec.fromRoots(rt, base, n);
                } else if (tag == K_LIST) {
                    out = Seqs.fromRoots(rt, base, n);
                } else {
                    int si = rt.push(Sets.empty(rt));
                    for (int i = 0; i < n; i++) rt.setR(si, Sets.conj(rt, rt.r(si), rt.r(base + i)));
                    out = rt.r(si);
                }
                rt.popTo(base);
                return out;
            }
            case K_MAP: {
                int n = (int) r.u32();
                int base = rt.mark();
                for (int i = 0; i < 2 * n; i++) rt.push(consts[(int) r.u32()]);
                int mi = rt.push(Maps.empty(rt));
                for (int i = 0; i < n; i++) {
                    rt.setR(mi, Mapwrite.mapAssoc(rt, rt.r(mi), rt.r(base + 2 * i), rt.r(base + 2 * i + 1)));
                }
                long out = rt.r(mi);
                rt.popTo(base);
                return out;
            }
            case K_FN: {
                long f = r.u32();
                // A closure with no upvalues: a top-level fn is a constant.
                return rt.makeClosure((int) f, new long[0]);
            }
            default:
                throw new UnsupportedOperationException(
                    "constant kind " + tag + " needs the data structures ported"
                    + " (" + kindName(tag) + ")");
        }
    }
}
