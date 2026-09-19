package com.flint.rt;

import java.nio.charset.StandardCharsets;

/// The wire WRITER (`DECISIONS.md#the-codec-is-guest-code`).
///
/// An opaque handle around a transient byte string. The guest appends through
/// the `wire-*` primitives and has no way to name the buffer, so it cannot
/// write a `K_PORT` tag followed by an id it does not hold -- which is the
/// safety rule `structured-ports` states as "flint is given no way to turn an
/// integer into a port", kept by making the encoding primitives the only door.
///
/// The Rust copy is `Rt::wire_*` in `runtime/src/codec.rs`; this is the same
/// thing, and the two must agree byte for byte.
public final class Wire {
    /// `WR_BUF` is a `TY_TBYTES` nothing answers; `WR_LIVE` goes false once the
    /// bytes have been taken, so a second use is refused rather than growing
    /// bytes somebody already sent.
    /// `WR_NEED` is HOW MANY VALUES ARE STILL DUE, innermost last -- the
    /// writer's own idea of where it is. A streaming encoder that enforces
    /// validity must not take the guest's word for it: four raw bytes at a
    /// value position are read by the far side as a tag and its payload, and
    /// `0000000f` is `K_PORT` followed by an id. That is a capability forged
    /// out of an integer, indistinguishable on the reading side from a real one
    /// because it sits exactly where a real one would.
    public static final int WR_BUF = 0, WR_LIVE = 1, WR_NEED = 2, WR_LEN = 3;

    // THE STATE MACHINE IS `kin/wire.kin`, generated into all three runtimes.
    // It lived here, in `codec.rs` and in `Wire.cs` as three hand-written
    // copies, and a marker bug had to be repaired three times before that was
    // obviously the wrong shape. What stays in this file is the slot LAYOUT
    // above and the byte-payload helpers below -- appending a `byte[]` is not
    // a body the three languages can share.
    public static boolean isWriter(Rt rt, long v) {
        return com._3sln.flint.kgen.rt.Wirecore.isWriter(rt, v);
    }
    public static long writer(Rt rt) {
        return com._3sln.flint.kgen.rt.Wirecore.wireWriter(rt);
    }
    public static boolean expectValue(Rt rt, long w, long opens) {
        return com._3sln.flint.kgen.rt.Wirecore.wireExpectValue(rt, w, opens);
    }
    public static boolean openTable(Rt rt, long w, long ncols) {
        return com._3sln.flint.kgen.rt.Wirecore.wireOpenTable(rt, w, ncols);
    }
    public static boolean expectRowcount(Rt rt, long w, long nrows) {
        return com._3sln.flint.kgen.rt.Wirecore.wireExpectRowcount(rt, w, nrows);
    }
    public static boolean complete(Rt rt, long w) {
        return com._3sln.flint.kgen.rt.Wirecore.wireComplete(rt, w);
    }
    public static long finish(Rt rt, long w) {
        return com._3sln.flint.kgen.rt.Wirecore.wireFinish(rt, w);
    }
    public static boolean isReader(Rt rt, long v) {
        return com._3sln.flint.kgen.rt.Wirecore.isReader(rt, v);
    }
    public static boolean mayMint(Rt rt, long r) {
        return com._3sln.flint.kgen.rt.Wirecore.wireMayMint(rt, r);
    }
    public static long reader(Rt rt, long bytes, boolean live) {
        return com._3sln.flint.kgen.rt.Wirecore.wireReader(rt, bytes, live);
    }
    public static int left(Rt rt, long r) {
        return com._3sln.flint.kgen.rt.Wirecore.wireLeft(rt, r);
    }

    /// Append one byte. False when `w` is not a live writer, so every primitive
    /// refuses in one shape.
    public static boolean put(Rt rt, long w, int b) {
        if (!isWriter(rt, w) || rt.slot(w, WR_LIVE) != Val.TRUE) return false;
        int base = rt.mark();
        int wi = rt.push(w);
        long buf = rt.slot(rt.r(wi), WR_BUF);
        long nb = Bytes.conj(rt, buf, b & 0xff);
        rt.setSlot(Val.asHeap(rt.r(wi)), WR_BUF, nb);
        rt.popTo(base);
        return true;
    }

    public static boolean raw(Rt rt, long w, byte[] bs) {
        if (!isWriter(rt, w) || rt.slot(w, WR_LIVE) != Val.TRUE) return false;
        int base = rt.mark();
        int wi = rt.push(w);
        for (byte b : bs) {
            long buf = rt.slot(rt.r(wi), WR_BUF);
            long nb = Bytes.conj(rt, buf, b & 0xff);
            rt.setSlot(Val.asHeap(rt.r(wi)), WR_BUF, nb);
        }
        rt.popTo(base);
        return true;
    }

    /// LITTLE-ENDIAN, like the Rust `to_le_bytes` this mirrors.
    public static boolean u32(Rt rt, long w, long v) {
        return raw(rt, w, new byte[]{ (byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24) });
    }

    public static boolean u64(Rt rt, long w, long v) {
        return raw(rt, w, new byte[]{
            (byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24),
            (byte) (v >>> 32), (byte) (v >>> 40), (byte) (v >>> 48), (byte) (v >>> 56) });
    }

    public static boolean text(Rt rt, long w, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return u32(rt, w, b.length) && raw(rt, w, b);
    }

    // --- reading -----------------------------------------------------------

    /// `RD_LIVE` is the safety rule on this side: true only for bytes that
    /// arrived on a bridge, so only such a reader may mint a port or an opaque.
    public static final int RD_BYTES = 0, RD_POS = 1, RD_LIVE = 2, RD_LEN = 3;

    /// The next `n` bytes, and the cursor moved. Null past the end, which every
    /// read turns into a refusal rather than a wrong value.
    public static byte[] take(Rt rt, long r, int n) {
        if (!isReader(rt, r)) return null;
        int base = rt.mark();
        int ri = rt.push(r);
        int pos = (int) Val.asFixnum(rt.slot(rt.r(ri), RD_POS));
        byte[] all = Bytes.toArray(rt, rt.slot(rt.r(ri), RD_BYTES));
        if (pos + n > all.length) { rt.popTo(base); return null; }
        byte[] out = java.util.Arrays.copyOfRange(all, pos, pos + n);
        rt.setSlot(Val.asHeap(rt.r(ri)), RD_POS, Val.fixnum(pos + n));
        rt.popTo(base);
        return out;
    }

    /// LITTLE-ENDIAN, matching the writer.
    public static long u32of(byte[] b, int at) {
        return (b[at] & 0xffL) | ((b[at + 1] & 0xffL) << 8)
             | ((b[at + 2] & 0xffL) << 16) | ((b[at + 3] & 0xffL) << 24);
    }

    public static long u64of(byte[] b, int at) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xffL);
        return v;
    }
}
