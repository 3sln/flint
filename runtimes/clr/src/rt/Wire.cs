namespace Flint.Rt;

using System.Text;

/// The wire WRITER (`DECISIONS.md#the-codec-is-guest-code`).
///
/// An opaque handle around a transient byte string. The guest appends through
/// the `wire-*` primitives and has no way to name the buffer, so it cannot
/// write a `K_PORT` tag followed by an id it does not hold -- which is the
/// safety rule `structured-ports` states as "flint is given no way to turn an
/// integer into a port", kept by making the encoding primitives the only door.
///
/// The Rust copy is `Rt::wire_*` in `runtime/src/codec.rs` and the JVM's is
/// `Wire.java`; all three must agree byte for byte.
public static class Wire {
    /// `WR_BUF` is a transient byte string nothing answers; `WR_LIVE` goes
    /// false once the bytes have been taken, so a second use is refused rather
    /// than growing bytes somebody already sent.
    /// `WR_NEED` is HOW MANY VALUES ARE STILL DUE, innermost last -- the
    /// writer's own idea of where it is. A streaming encoder that enforces
    /// validity must not take the guest's word for it: four raw bytes at a
    /// value position are read by the far side as a tag and its payload, and
    /// `0000000f` is `K_PORT` followed by an id -- a capability forged out of
    /// an integer, sitting exactly where a real one would.
    public const int WR_BUF = 0, WR_LIVE = 1, WR_NEED = 2, WR_LEN = 3;

    // THE STATE MACHINE IS `kin/wire.kin`, generated into all three runtimes.
    // It lived here, in `codec.rs` and in `Wire.java` as three hand-written
    // copies, and a marker bug had to be repaired three times before that was
    // obviously the wrong shape. What stays in this file is the slot LAYOUT
    // and the byte-payload helpers -- appending a `byte[]` is not a body the
    // three languages can share.
    public static bool IsWriter(Rt rt, long v) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.IsWriter(rt, v);
    public static long Writer(Rt rt) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireWriter(rt);
    public static bool ExpectValue(Rt rt, long w, long opens) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireExpectValue(rt, w, opens);
    public static bool OpenTable(Rt rt, long w, long ncols) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireOpenTable(rt, w, ncols);
    public static bool ExpectRowcount(Rt rt, long w, long nrows) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireExpectRowcount(rt, w, nrows);
    public static long Finish(Rt rt, long w) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireFinish(rt, w);
    public static bool IsReader(Rt rt, long v) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.IsReader(rt, v);
    public static bool MayMint(Rt rt, long r) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireMayMint(rt, r);
    public static long Reader(Rt rt, long bytes, bool live) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireReader(rt, bytes, live);
    public static int Left(Rt rt, long r) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireLeft(rt, r);

    public static bool Complete(Rt rt, long w) =>
        global::_3sln.Flint.Kgen.Rt.Wirecore.WireComplete(rt, w);

    /// Append one byte. False when `w` is not a live writer, so every primitive
    /// refuses in one shape.
    public static bool Put(Rt rt, long w, int b) {
        if (!IsWriter(rt, w) || rt.Slot(w, WR_LIVE) != Val.True) return false;
        int bas = rt.Mark();
        int wi = rt.Push(w);
        long buf = rt.Slot(rt.R(wi), WR_BUF);
        long nb = Bytes.Conj(rt, buf, b & 0xff);
        rt.SetSlot(Val.AsHeap(rt.R(wi)), WR_BUF, nb);
        rt.PopTo(bas);
        return true;
    }

    public static bool Raw(Rt rt, long w, byte[] bs) {
        if (!IsWriter(rt, w) || rt.Slot(w, WR_LIVE) != Val.True) return false;
        int bas = rt.Mark();
        int wi = rt.Push(w);
        foreach (byte b in bs) {
            long buf = rt.Slot(rt.R(wi), WR_BUF);
            long nb = Bytes.Conj(rt, buf, b & 0xff);
            rt.SetSlot(Val.AsHeap(rt.R(wi)), WR_BUF, nb);
        }
        rt.PopTo(bas);
        return true;
    }

    /// LITTLE-ENDIAN, like the Rust `to_le_bytes` this mirrors.
    public static bool U32(Rt rt, long w, long v) =>
        Raw(rt, w, new byte[]{ (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) });

    public static bool U64(Rt rt, long w, long v) =>
        Raw(rt, w, new byte[]{
            (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24),
            (byte) (v >> 32), (byte) (v >> 40), (byte) (v >> 48), (byte) (v >> 56) });

    public static bool Text(Rt rt, long w, string s) {
        byte[] b = Encoding.UTF8.GetBytes(s);
        return U32(rt, w, b.Length) && Raw(rt, w, b);
    }

    // --- reading -----------------------------------------------------------

    /// `RD_LIVE` is the safety rule on this side: true only for bytes that
    /// arrived on a bridge, so only such a reader may mint a port or an opaque.
    public const int RD_BYTES = 0, RD_POS = 1, RD_LIVE = 2, RD_LEN = 3;

    /// The next `n` bytes, and the cursor moved. Null past the end.
    public static byte[] Take(Rt rt, long r, int n) {
        if (!IsReader(rt, r)) return null;
        int bas = rt.Mark();
        int ri = rt.Push(r);
        int pos = (int) Val.AsFixnum(rt.Slot(rt.R(ri), RD_POS));
        byte[] all = Bytes.ToArray(rt, rt.Slot(rt.R(ri), RD_BYTES));
        if (pos + n > all.Length) { rt.PopTo(bas); return null; }
        byte[] outb = new byte[n];
        System.Array.Copy(all, pos, outb, 0, n);
        rt.SetSlot(Val.AsHeap(rt.R(ri)), RD_POS, Val.Fixnum(pos + n));
        rt.PopTo(bas);
        return outb;
    }

    /// LITTLE-ENDIAN, matching the writer.
    public static long U32Of(byte[] b, int at) =>
        (b[at] & 0xffL) | ((b[at + 1] & 0xffL) << 8)
      | ((b[at + 2] & 0xffL) << 16) | ((b[at + 3] & 0xffL) << 24);

    public static long U64Of(byte[] b, int at) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xffL);
        return v;
    }
}
