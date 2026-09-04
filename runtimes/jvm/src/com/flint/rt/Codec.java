package com.flint.rt;

import flint.rt.Mapwrite;

import flint.rt.Mapcore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/// Values across a boundary (`doc/decisions/0025`), ported from
/// `runtime/src/codec.rs`.
///
/// BOTH HALVES, because a bridge is full duplex: `send` encodes and
/// `hostDeliver` decodes, and both run in the RUNTIME
/// (`doc/decisions/0027`). Neither is reachable from a program -- there is no
/// builtin that encodes and none that decodes -- and that, rather than the
/// absence of the code, is what keeps a guest from turning arbitrary bytes into
/// a PORT or an identity. This file used to carry only the encoder and a
/// comment calling the omission deliberate; the omission meant a port on this
/// runtime handed the guest raw bytes where the native one handed it a value.
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
                        encodeInto(rt, Vec.nth(rt, rt.r(ci), i, Val.NOT_FOUND), out, depth + 1);
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
        if (Mapcore.isMap(rt, v)) {
            int at = rt.mark();
            int n = Maps.entries(rt, v);
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

    // --- decoding ----------------------------------------------------------
    //
    // The mirror of the encoder, and it lives HERE rather than being reachable
    // from a program: `hostDeliver` is its only caller (`doc/decisions/0027`).
    //
    // A bridge carries VALUES. The runtime encodes on the way out and decodes
    // on the way in, and the guest is handed neither half -- because a decoder
    // a guest could drive would be an encoder read backwards, and `K_PORT` and
    // `K_SENTINEL` carry their identity inline as integers a guest can write.
    // Such a guest could mint any host id it liked, and an opaque value's whole
    // meaning is that it cannot.
    //
    // `live` is that rule as one parameter. `decode` honours the identity tags
    // because the HOST wrote the bytes; `decodeGuest` refuses them. Nothing
    // calls the second yet, and it exists so that whoever adds a guest-reachable
    // decoder finds it rather than writing the unsafe one.

    /// A cursor over the bytes. Every read is bounds-checked, because the
    /// encoding is something outside this sandbox wrote.
    static final class Reader {
        final byte[] b;
        int i;
        Reader(byte[] b) { this.b = b; }

        int u8() {
            if (i >= b.length) throw new Refused("the encoding ends mid-value");
            return b[i++] & 0xff;
        }
        int u32() {
            if (i + 4 > b.length) throw new Refused("the encoding ends mid-value");
            int n = (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8)
                  | ((b[i + 2] & 0xff) << 16) | ((b[i + 3] & 0xff) << 24);
            i += 4;
            return n;
        }
        long u64() {
            long lo = u32() & 0xffffffffL, hi = u32() & 0xffffffffL;
            return lo | (hi << 32);
        }
        /// `null` means ABSENT, which is not the same as empty -- that is what
        /// distinguishes `:kw` from `:/kw`.
        String str() {
            int n = u32();
            if (n == NO_NS) return null;
            if (n < 0 || i + n > b.length) throw new Refused("the encoding ends mid-string");
            String s = new String(b, i, n, StandardCharsets.UTF_8);
            i += n;
            return s;
        }
        byte[] raw(int n) {
            if (n < 0 || i + n > b.length) throw new Refused("the encoding ends mid-bytes");
            byte[] out = java.util.Arrays.copyOfRange(b, i, i + n);
            i += n;
            return out;
        }
    }

    /// Decode a value the HOST produced. Live tags are honoured.
    public static long decode(Rt rt, byte[] bytes) {
        return decodeAt(rt, new Reader(bytes), true, 0);
    }

    /// Decode a value the GUEST produced, where the live tags are refused.
    ///
    /// This is the whole of `0025`'s safety rule, and it is one line: a guest
    /// that could decode arbitrary bytes into a port would have exactly the
    /// integer-to-port conversion the sandbox forbids.
    public static long decodeGuest(Rt rt, byte[] bytes) {
        return decodeAt(rt, new Reader(bytes), false, 0);
    }

    static long decodeAt(Rt rt, Reader r, boolean live, int depth) {
        if (depth > 128) throw new Refused("value nested too deeply to decode");
        int tag = r.u8();
        switch (tag) {
            case K_NIL: return Val.NIL;
            case K_TRUE: return Val.TRUE;
            case K_FALSE: return Val.FALSE;
            case K_INT: return Num.integer(rt, r.u64());
            case K_DOUBLE: return Val.ofDouble(Double.longBitsToDouble(r.u64()));
            case K_STRING: {
                String s = r.str();
                if (s == null) throw new Refused("a string cannot be absent");
                return Str.of(rt, s);
            }
            case K_KEYWORD: case K_SYMBOL: {
                String ns = r.str();
                String name = r.str();
                if (name == null) throw new Refused("a name cannot be absent");
                return tag == K_KEYWORD ? Str.keyword(rt, ns, name) : Str.symbol(rt, ns, name);
            }
            case K_BYTES: return Bytes.of(rt, r.raw(r.u32()));
            case K_TAGGED: {
                int base = rt.mark();
                int ti = rt.push(decodeAt(rt, r, live, depth + 1));
                int fi = rt.push(decodeAt(rt, r, live, depth + 1));
                long out = rt.newTagged(rt.r(ti), rt.r(fi));
                rt.popTo(base);
                return out;
            }
            case K_VECTOR: case K_LIST: case K_SET: {
                int n = r.u32();
                int base = rt.mark();
                for (int k = 0; k < n; k++) rt.push(decodeAt(rt, r, live, depth + 1));
                long out;
                if (tag == K_VECTOR) {
                    out = Vec.fromRoots(rt, base, n);
                } else if (tag == K_LIST) {
                    out = Seqs.fromRoots(rt, base, n);
                } else {
                    // No `fromRoots` for a set, so it is built by conj -- and
                    // the accumulator lives on the ROOT STACK, because
                    // `Sets.conj` allocates.
                    int acc = rt.push(rt.roots.shared.singletons[Rt.SING_EMPTY_SET]);
                    for (int k = 0; k < n; k++) {
                        rt.setR(acc, Sets.conj(rt, rt.r(acc), rt.r(base + k)));
                    }
                    out = rt.r(acc);
                }
                rt.popTo(base);
                return out;
            }
            case K_MAP: {
                int n = r.u32();
                int base = rt.mark();
                for (int k = 0; k < n * 2; k++) rt.push(decodeAt(rt, r, live, depth + 1));
                int acc = rt.push(rt.roots.shared.singletons[Rt.SING_EMPTY_MAP]);
                for (int k = 0; k < n; k++) {
                    rt.setR(acc, Mapwrite.mapAssoc(rt, rt.r(acc), rt.r(base + k * 2), rt.r(base + k * 2 + 1)));
                }
                long out = rt.r(acc);
                rt.popTo(base);
                return out;
            }
            case K_TABLE: {
                int ncols = r.u32();
                int base = rt.mark();
                // The schema pairs, then the columns. Built through the ordinary
                // constructors, so a table off the wire is checked exactly as
                // one built in the program is -- a decoder that skipped the
                // schema check would be a way to make a table that is not
                // closed.
                int pi = rt.push(Vec.empty(rt));
                for (int c = 0; c < ncols; c++) {
                    int ni = rt.push(decodeAt(rt, r, live, depth + 1));
                    int tyi = rt.push(decodeAt(rt, r, live, depth + 1));
                    int ei = rt.push(Vec.empty(rt));
                    rt.setR(ei, Vec.conj(rt, rt.r(ei), rt.r(ni)));
                    rt.setR(ei, Vec.conj(rt, rt.r(ei), rt.r(tyi)));
                    long pair = rt.r(ei);
                    rt.popTo(ni);
                    rt.setR(pi, Vec.conj(rt, rt.r(pi), pair));
                }
                int nrows = r.u32();
                int ci = rt.push(Vec.empty(rt));
                for (int c = 0; c < ncols; c++) {
                    int coli = rt.push(Vec.empty(rt));
                    for (int k = 0; k < nrows; k++) {
                        rt.setR(coli, Vec.conj(rt, rt.r(coli), decodeAt(rt, r, live, depth + 1)));
                    }
                    long col = rt.r(coli);
                    rt.popTo(coli);
                    rt.setR(ci, Vec.conj(rt, rt.r(ci), col));
                }
                long schema = Table.newSchema(rt, rt.r(pi));
                if (!Val.isNil(rt.thrown)) {
                    rt.popTo(base);
                    throw new Refused("a table arrived with a schema it cannot have");
                }
                int si = rt.push(schema);
                long out = Table.tableFromColumns(rt, rt.r(si), rt.r(ci), nrows);
                rt.popTo(base);
                if (!Val.isNil(rt.thrown)) {
                    throw new Refused("a table arrived that its own schema refuses");
                }
                return out;
            }
            case K_PORT: {
                if (!live) throw new Refused(IDENTITY_REFUSED);
                int id = r.u32();
                // INTERN OR MINT. A port the host names in a message is a port
                // it is handing to this sandbox, and that is how a capability
                // gets delegated (`doc/decisions/0027`). Arriving twice costs
                // nothing and counts once: the handle is interned by host id,
                // so the second arrival finds the first object.
                //
                // Through `bridgeHook` rather than straight to
                // `installBridgePort` -- see `Rt.bridgeHook` for why the Rust
                // needs the indirection and why this mirrors it.
                if (rt.bridgeHook == null) {
                    throw new Refused("port " + id + " arrived, but this sandbox has no ports");
                }
                long p = rt.bridgeHook.install(rt, id);
                if (Val.isNil(p)) throw new Refused("port " + id + " could not be installed here");
                return p;
            }
            case K_SENTINEL: {
                if (!live) throw new Refused(IDENTITY_REFUSED);
                long hostId = r.u64();
                String label = r.str();
                if (label == null) throw new Refused("a label cannot be absent");
                int base = rt.mark();
                int li = rt.push(Str.of(rt, label));
                long out = rt.newOpaque(rt.r(li), hostId);
                rt.popTo(base);
                return out;
            }
            default: throw new Refused("unknown tag " + tag + " in the encoding");
        }
    }

    static final String IDENTITY_REFUSED =
        "a port or a sentinel cannot be decoded here: they are identities, "
        + "and an identity is held rather than described";
}
