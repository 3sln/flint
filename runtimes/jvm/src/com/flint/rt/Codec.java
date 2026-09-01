package com.flint.rt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/// Values across a boundary (`doc/decisions/0025`), ported from
/// `runtime/src/codec.rs`.
///
/// Only the ENCODER is here. Encoding is what the guest side needs: `open`
/// forwards its arguments to the host as one encoded value, and the host
/// decodes them. A guest-callable decoder would be the dangerous half -- it
/// would let a program turn arbitrary bytes into a PORT or an identity, which
/// is exactly the integer-to-capability conversion the sandbox forbids -- so
/// its absence here is deliberate rather than unfinished.
///
/// `K_SENTINEL` is why this exists at all. An opaque value crosses as its host
/// id plus its label, and guest code cannot mint that id (`flint/opaque` gives
/// 0), so a host recognises its own grants and nothing else. That is the whole
/// of the capability check, and it lives with the host rather than in here.
public final class Codec {

    public static final int K_NIL = 0, K_TRUE = 1, K_FALSE = 2, K_INT = 3,
        K_DOUBLE = 4, K_STRING = 5, K_KEYWORD = 6, K_SYMBOL = 7, K_VECTOR = 8,
        K_LIST = 9, K_MAP = 10, K_SET = 11, K_BYTES = 14, K_PORT = 15,
        K_SENTINEL = 16,
        /// A tagged literal (`doc/decisions/0034`): the tag symbol, then the
        /// form. 17 here and 17 in the image's constant tags, because the two
        /// SHARE a numbering space.
        K_TAGGED = 17,
        /// A table (`doc/decisions/0026`), COLUMNAR: the schema, the row count,
        /// then each column's values in full before the next one starts.
        /// Row-major would be a vector of maps with extra steps and would lose
        /// exactly what the type is for.
        K_TABLE = 18;

    /// `-1` means the namespace is ABSENT, which is not the same as empty.
    static final int NO_NS = -1;

    /// What went wrong, so a caller can say it in flint's terms.
    public static final class Refused extends RuntimeException {
        public Refused(String m) { super(m); }
    }

    public static byte[] encode(Rt rt, long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeInto(rt, v, out, 0);
        return out.toByteArray();
    }

    static void u32(ByteArrayOutputStream o, int n) {
        o.write(n); o.write(n >> 8); o.write(n >> 16); o.write(n >> 24);
    }

    static void u64(ByteArrayOutputStream o, long n) {
        u32(o, (int) n); u32(o, (int) (n >>> 32));
    }

    static void str(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        u32(o, b.length);
        o.write(b, 0, b.length);
    }

    static void encodeInto(Rt rt, long v, ByteArrayOutputStream out, int depth) {
        if (depth > 128) throw new Refused("value nested too deeply to encode");
        if (Val.isNil(v)) { out.write(K_NIL); return; }
        if (v == Val.TRUE) { out.write(K_TRUE); return; }
        if (v == Val.FALSE) { out.write(K_FALSE); return; }
        if (Num.isInt(rt, v)) {
            Long i = Num.asI64(rt, v);
            if (i != null) { out.write(K_INT); u64(out, i); return; }
        }
        if (Val.isDouble(v)) {
            out.write(K_DOUBLE);
            u64(out, Double.doubleToRawLongBits(Val.asDouble(v)));
            return;
        }
        if (Str.isString(rt, v)) { out.write(K_STRING); str(out, Str.text(rt, v)); return; }
        boolean inlineKw = Val.isInlineKw(v);
        boolean kw = inlineKw || rt.isHeapTy(v, Obj.TY_KW);
        boolean sym = rt.isHeapTy(v, Obj.TY_SYM);
        if (kw || sym) {
            long nsv = inlineKw ? Val.NIL : rt.slot(v, 0);
            long namev = inlineKw ? Val.inlineStr(Val.inlineBytes(v)) : rt.slot(v, 1);
            out.write(kw ? K_KEYWORD : K_SYMBOL);
            if (Val.isNil(nsv)) u32(out, NO_NS); else str(out, Str.text(rt, nsv));
            str(out, Str.text(rt, namev));
            return;
        }
        if (Bytes.isBytes(rt, v)) {
            byte[] b = Bytes.toArray(rt, v);
            out.write(K_BYTES);
            u32(out, b.length);
            out.write(b, 0, b.length);
            return;
        }
        if (!Val.isHeap(v)) throw new Refused("this value cannot cross a boundary");
        int t = Obj.ty(rt.gc.sp, Val.asHeap(v));
        switch (t) {
            case Obj.TY_PORT:
                out.write(K_PORT);
                u32(out, (int) Val.asFixnum(rt.slot(v, Conc.PT_ID)));
                return;
            case Obj.TY_OPAQUE:
                out.write(K_SENTINEL);
                u64(out, rt.opaqueHostId(v));
                str(out, Str.isString(rt, rt.opaqueLabel(v)) ? Str.text(rt, rt.opaqueLabel(v)) : "");
                return;
            case Obj.TY_TAGGED: {
                out.write(K_TAGGED);
                encodeInto(rt, rt.slot(v, 0), out, depth + 1);
                encodeInto(rt, rt.slot(v, 1), out, depth + 1);
                return;
            }
            case Obj.TY_TABLE: {
                out.write(K_TABLE);
                int base = rt.mark();
                int vi = rt.push(v);
                int si = rt.push(rt.slot(rt.r(vi), Table.TB_SCHEMA));
                int ncols = Table.schemaLen(rt, rt.r(si));
                int nrows = Table.tableCount(rt, rt.r(vi));
                u32(out, ncols);
                for (int c = 0; c < ncols; c++) {
                    encodeInto(rt, Table.schemaNameAt(rt, rt.r(si), c), out, depth + 1);
                    encodeInto(rt, Table.schemaTypeAt(rt, rt.r(si), c), out, depth + 1);
                }
                u32(out, nrows);
                // COLUMN BY COLUMN, each in full: a receiver reading one field
                // reads one run.
                for (int c = 0; c < ncols; c++) {
                    long nm = Table.schemaNameAt(rt, rt.r(si), c);
                    int ci = rt.push(Table.tableColumn(rt, rt.r(vi), nm));
                    for (int i = 0; i < nrows; i++)
                        encodeInto(rt, Vec.nth(rt, rt.r(ci), i), out, depth + 1);
                    rt.popTo(ci);
                }
                rt.popTo(base);
                return;
            }
            case Obj.TY_CLOSURE: case Obj.TY_NATIVEFN: case Obj.TY_MULTIFN:
                throw new Refused("a function cannot cross a boundary: its meaning is its "
                                  + "environment, and that does not travel");
            case Obj.TY_ATOM: throw new Refused("an atom cannot cross a boundary");
            case Obj.TY_VAR: throw new Refused("a var cannot cross a boundary");
            case Obj.TY_THREAD: throw new Refused("a thread cannot cross a boundary");
            default: encodeCollection(rt, v, out, depth); return;
        }
    }

    /// The items are gathered ONTO THE SHADOW STACK first, then encoded.
    ///
    /// Two reasons, and it used to be only the second. Encoding allocates --
    /// flattening a rope does -- so the items have to be rooted before the
    /// encode loop. And the WALK is not allocation-free either: `next` on a
    /// lazy seq forces the tail, which runs arbitrary flint code and can
    /// collect, so anything gathered into a host list has already gone stale.
    static void encodeCollection(Rt rt, long v, ByteArrayOutputStream out, int depth) {
        if (Maps.isMap(rt, v)) {
            int at = rt.mark();
            int n = Maps.entries(rt, v, at);
            out.write(K_MAP);
            u32(out, n);
            for (int i = 0; i < 2 * n; i++) encodeInto(rt, rt.r(at + i), out, depth + 1);
            rt.popTo(at);
            return;
        }
        int tag;
        if (Sets.isSet(rt, v)) tag = K_SET;
        else if (rt.isHeapTy(v, Obj.TY_VEC)) tag = K_VECTOR;
        else if (rt.isSeq(v)) tag = K_LIST;
        else throw new Refused("this value cannot cross a boundary (object type "
                               + Obj.ty(rt.gc.sp, Val.asHeap(v)) + ")");
        int base = rt.mark();
        int si = rt.push(Seqs.seq(rt, v));
        int n = 0;
        while (!Val.isNil(rt.r(si))) {
            rt.push(Seqs.first(rt, rt.r(si)));
            n++;
            rt.setR(si, Seqs.next(rt, rt.r(si)));
        }
        out.write(tag);
        u32(out, n);
        for (int i = 0; i < n; i++) encodeInto(rt, rt.r(si + 1 + i), out, depth + 1);
        rt.popTo(base);
    }

    private Codec() {}
}
