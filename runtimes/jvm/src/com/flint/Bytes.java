package com.flint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// A byte string (`doc/decisions/0024`).
///
/// flint's is a rope, so `b-concat` is O(1) and a long chain of appends does
/// not copy repeatedly. This one is FLAT, which is a deliberate difference in
/// cost and not in meaning: every operation here answers what flint's answers,
/// and `bin/conform-hosts` is what says so. The rope earns its keep at scale --
/// `test/bytes.clj` measures 0.2 MB against 43.5 MB on 200 000 bytes -- and
/// that is a reason to port it later, not a reason to get the semantics wrong
/// now.
///
/// `b-depth` exists to observe the rope's shape, so here it is always 0. That
/// is honest rather than convenient: a flat representation IS depth zero.
public final class Bytes {
    public final byte[] raw;
    public Bytes(byte[] raw) { this.raw = raw; }

    public static Bytes of(String s) { return new Bytes(s.getBytes(StandardCharsets.UTF_8)); }
    public String text() { return new String(raw, StandardCharsets.UTF_8); }
    public int count() { return raw.length; }

    public Bytes concat(Bytes other) {
        byte[] out = new byte[raw.length + other.raw.length];
        System.arraycopy(raw, 0, out, 0, raw.length);
        System.arraycopy(other.raw, 0, out, raw.length, other.raw.length);
        return new Bytes(out);
    }

    public Bytes slice(int from, int to) {
        if (from < 0 || to > raw.length || from > to) {
            throw Vm.err("byte slice [" + from + " " + to + ") out of " + raw.length);
        }
        byte[] out = new byte[to - from];
        System.arraycopy(raw, from, out, 0, to - from);
        return new Bytes(out);
    }

    /// Unsigned, as flint's is: a byte is 0..255, not -128..127.
    public long at(int i) {
        if (i < 0 || i >= raw.length) throw Vm.err("byte index " + i + " out of " + raw.length);
        return raw[i] & 0xFF;
    }

    public List<Object> toVec() {
        List<Object> out = new ArrayList<>(raw.length);
        for (byte b : raw) out.add((long) (b & 0xFF));
        return out;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Bytes b && java.util.Arrays.equals(raw, b.raw);
    }
    @Override public int hashCode() { return java.util.Arrays.hashCode(raw); }
    @Override public String toString() { return "#bytes[" + raw.length + "]"; }

    /// A byte string being built. flint's transient exists so that appending is
    /// not N copies; this one uses a growable buffer for the same reason.
    public static final class T {
        private byte[] buf = new byte[32];
        private int len = 0;
        private void room(int n) {
            if (len + n > buf.length) {
                int cap = Math.max(buf.length * 2, len + n);
                buf = java.util.Arrays.copyOf(buf, cap);
            }
        }
        public void conj(long b) { room(1); buf[len++] = (byte) b; }
        public void append(Bytes b) { room(b.raw.length); System.arraycopy(b.raw, 0, buf, len, b.raw.length); len += b.raw.length; }
        public int count() { return len; }
        public Bytes persistent() { return new Bytes(java.util.Arrays.copyOf(buf, len)); }
    }
}
