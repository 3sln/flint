package com.flint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/// A flint program image, read (`runtime/src/image.rs`).
///
///     "FLINTIMG" u32 version
///     u32 nnatives   ; (name-const, table-slot) pairs
///     u32 nconsts    ; tagged constants, in dependency order
///     u32 nfns       ; function table
///     u32 nvars      ; var name constant indices
///     u32 codelen    ; bytecode
///     u32 entry      ; fn index called with the argument vector
///     u32 ninit      ; fn indices to run first, in order
///
/// **The bytecode is the portable artifact** (`doc/decisions/0010`): a new host
/// needs this loader, a loop over the opcodes and the builtins -- not a
/// compiler. The reader, the analyzer and the whole core library are already
/// portable and compile to exactly this.
public final class Img {
    public static final int VERSION = 3;

    /// Image flags, a trailing u32. Bit 0 is `:optimize [perf]` -- what the
    /// compiler DECIDED, not what it was asked.
    ///
    /// It has to be a flag and not "does the image carry compiled arities",
    /// because on wasm the arity table IS the answer and here it never can be:
    /// this port emits bytecode at LOAD time from the same image, so an image
    /// meant for it carries the preference and an empty table.
    public static final int FLAG_PERF = 1;

    // Constant tags, from `image.rs`.
    static final int K_NIL = 0, K_TRUE = 1, K_FALSE = 2, K_INT = 3, K_DOUBLE = 4,
            K_STRING = 5, K_KEYWORD = 6, K_SYMBOL = 7, K_VECTOR = 8, K_LIST = 9,
            K_MAP = 10, K_SET = 11, K_FN = 12, K_NATIVE = 13;
    static final long NO_CONST = 0xFFFF_FFFFL;

    public final Object[] consts;
    public final FnDef[] fns;
    public final byte[] code;
    public final int entry;
    public final int[] init;
    /// Native import index to builtin NAME. Slots belong to the module an
    /// image was linked against and mean nothing here, so a host resolves by
    /// name -- which is what makes an image portable between hosts at all.
    public final String[] nativeNames;
    public final String[] varNames;
    /// What the compiler decided. `Vm` reads `FLAG_PERF` and turns its own
    /// AOT on, so one `:optimize` reaches all three runtimes.
    public final int flags;

    public static final class Arity {
        public final int argc, nlocals, code, len;
        public final boolean variadic;
        Arity(int argc, boolean variadic, int nlocals, int code, int len) {
            this.argc = argc; this.variadic = variadic;
            this.nlocals = nlocals; this.code = code; this.len = len;
        }
    }

    public static final class FnDef {
        public final String name;
        public final Arity[] arities;
        public final int nupvals;
        FnDef(String name, Arity[] arities, int nupvals) {
            this.name = name; this.arities = arities; this.nupvals = nupvals;
        }
        /// Exact arity wins; otherwise the widest variadic that fits. Same rule
        /// as the Rust runtime, and it has to be: a program that dispatches
        /// differently per host is not one program.
        public Arity select(int argc) {
            Arity best = null;
            for (Arity a : arities) {
                if (!a.variadic && a.argc == argc) return a;
                if (a.variadic && argc >= a.argc && (best == null || best.argc < a.argc)) best = a;
            }
            return best;
        }
    }

    private static final class R {
        final byte[] b; int i;
        R(byte[] b, int i) { this.b = b; this.i = i; }
        int u8() { return b[i++] & 0xFF; }
        int u16() { return u8() | (u8() << 8); }
        long u32() { return (long) u16() | ((long) u16() << 16); }
        long u64() { return u32() | (u32() << 32); }
        byte[] bytes(int n) {
            byte[] out = new byte[n];
            System.arraycopy(b, i, out, 0, n);
            i += n;
            return out;
        }
        String str() { return new String(bytes((int) u32()), StandardCharsets.UTF_8); }
    }

    private Img(Object[] consts, FnDef[] fns, byte[] code, int entry, int[] init,
                String[] nativeNames, String[] varNames, int flags) {
        this.consts = consts; this.fns = fns; this.code = code; this.entry = entry;
        this.init = init; this.nativeNames = nativeNames; this.varNames = varNames;
        this.flags = flags;
    }

    public static Img read(byte[] bytes) {
        R r = new R(bytes, 0);
        for (byte c : "FLINTIMG".getBytes(StandardCharsets.US_ASCII)) {
            if (r.u8() != (c & 0xFF)) throw new IllegalArgumentException("not a flint image");
        }
        long version = r.u32();
        if (version != VERSION) {
            throw new IllegalArgumentException(
                "this image is version " + version + " and this runtime reads " + VERSION);
        }

        int nnatives = (int) r.u32();
        int[] nativeNameConst = new int[nnatives];
        for (int i = 0; i < nnatives; i++) {
            nativeNameConst[i] = (int) r.u32();
            r.u32(); // the table slot: the linked module's, and meaningless here
        }

        int nconsts = (int) r.u32();
        // Read the constant BYTES first and build in a second pass, because a
        // constant may reference an earlier one by index.
        Object[] consts = new Object[nconsts];
        for (int i = 0; i < nconsts; i++) consts[i] = readConst(r, consts);

        int nfns = (int) r.u32();
        FnDef[] fns = new FnDef[nfns];
        for (int i = 0; i < nfns; i++) {
            long nameIdx = r.u32();
            int nupvals = r.u8();
            int na = r.u8();
            Arity[] arities = new Arity[na];
            for (int j = 0; j < na; j++) {
                int argc = r.u8();
                int flags = r.u8();
                int nlocals = r.u16();
                int codeAt = (int) r.u32();
                int len = (int) r.u32();
                r.u32(); // the compiled-arity index: a wasm concern
                arities[j] = new Arity(argc, (flags & 1) != 0, nlocals, codeAt, len);
            }
            String name = nameIdx == NO_CONST ? null : String.valueOf(consts[(int) nameIdx]);
            fns[i] = new FnDef(name, arities, nupvals);
        }

        int nvars = (int) r.u32();
        String[] varNames = new String[nvars];
        for (int i = 0; i < nvars; i++) {
            long k = r.u32();
            varNames[i] = k == NO_CONST ? null : String.valueOf(consts[(int) k]);
        }

        int codelen = (int) r.u32();
        byte[] code = r.bytes(codelen);
        int entry = (int) r.u32();
        int ninit = (int) r.u32();
        int[] init = new int[ninit];
        for (int i = 0; i < ninit; i++) init[i] = (int) r.u32();

        // The compiled-arity table (`doc/decisions/0013`). This port emits its
        // own bytecode and has no use for wasm table slots, so the table is
        // SKIPPED rather than read -- but it has to be skipped exactly, because
        // the flags word is behind it.
        long naot = r.u32();
        for (long k = 0; k < naot; k++) {
            r.u32(); r.u32();                       // slot, depth
            long np = r.u32();
            for (long j = 0; j < np; j++) { r.u32(); r.u32(); }
        }
        int flags = (int) r.u32();

        String[] nativeNames = new String[nnatives];
        for (int i = 0; i < nnatives; i++) {
            nativeNames[i] = String.valueOf(consts[nativeNameConst[i]]);
        }
        return new Img(consts, fns, code, entry, init, nativeNames, varNames, flags);
    }

    private static Object readConst(R r, Object[] built) {
        int tag = r.u8();
        switch (tag) {
            case K_NIL: return null;
            case K_TRUE: return Boolean.TRUE;
            case K_FALSE: return Boolean.FALSE;
            case K_INT: return r.u64();
            case K_DOUBLE: return Double.longBitsToDouble(r.u64());
            case K_STRING: return r.str();
            case K_KEYWORD: {
                long ns = r.u32();
                String name = String.valueOf(built[(int) r.u32()]);
                return Kw.of(ns == NO_CONST ? null : String.valueOf(built[(int) ns]), name);
            }
            case K_SYMBOL: {
                long ns = r.u32();
                String name = String.valueOf(built[(int) r.u32()]);
                return Sym.of(ns == NO_CONST ? null : String.valueOf(built[(int) ns]), name);
            }
            case K_VECTOR: case K_LIST: case K_SET: {
                int n = (int) r.u32();
                List<Object> xs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) xs.add(built[(int) r.u32()]);
                if (tag == K_SET) return new LinkedHashSet<>(xs);
                // Unmodifiable rather than `List.copyOf`, which rejects nulls.
                return tag == K_VECTOR
                    ? java.util.Collections.unmodifiableList(xs)
                    : Seq.of(xs);
            }
            case K_MAP: {
                int n = (int) r.u32();
                FlintMap m = FlintMap.empty();
                for (int i = 0; i < n; i++) {
                    Object k = built[(int) r.u32()];
                    m = m.assoc(k, built[(int) r.u32()]);
                }
                return m;
            }
            case K_FN: return new FnRef((int) r.u32());
            case K_NATIVE: {
                // TWO fields: the native import index and the constant holding
                // its NAME. Reading one leaves the stream four bytes short, and
                // the next tag byte read is whatever the name's first character
                // happened to be -- which is how this first failed, with
                // "unknown constant tag 65" for an 'A'.
                int idx = (int) r.u32();
                int namec = (int) r.u32();
                return new NativeRef(idx, String.valueOf(built[namec]));
            }
            default: throw new IllegalArgumentException("unknown constant tag " + tag);
        }
    }

    /// A function constant, by index into the function table.
    public record FnRef(int index) {}
    /// A builtin as a first-class value: its native import index, and its name
    /// so a host can resolve it without the linked module's slot table.
    public record NativeRef(int index, String name) {}
}
