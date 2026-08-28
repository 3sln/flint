using System;
using System.Collections.Generic;
using System.Text;

namespace Flint;

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
public sealed class Bytes {
    public readonly byte[] Raw;
    public Bytes(byte[] raw) => Raw = raw;

    public static Bytes Of(string s) => new(new UTF8Encoding(false).GetBytes(s));
    public string Text() => new UTF8Encoding(false).GetString(Raw);
    public int Count => Raw.Length;

    public Bytes Concat(Bytes other) {
        var outb = new byte[Raw.Length + other.Raw.Length];
        Array.Copy(Raw, 0, outb, 0, Raw.Length);
        Array.Copy(other.Raw, 0, outb, Raw.Length, other.Raw.Length);
        return new Bytes(outb);
    }

    public Bytes Slice(int from, int to) {
        if (from < 0 || to > Raw.Length || from > to)
            throw new FlintThrow($"byte slice [{from} {to}) out of {Raw.Length}");
        var outb = new byte[to - from];
        Array.Copy(Raw, from, outb, 0, to - from);
        return new Bytes(outb);
    }

    /// Unsigned, as flint's is: a byte is 0..255, not -128..127.
    public long At(int i) {
        if (i < 0 || i >= Raw.Length) throw new FlintThrow($"byte index {i} out of {Raw.Length}");
        return Raw[i];
    }

    public List<object> ToVec() {
        var outv = new List<object>(Raw.Length);
        foreach (byte b in Raw) outv.Add((long) b);
        return outv;
    }

    public override bool Equals(object o) {
        if (o is not Bytes b || b.Raw.Length != Raw.Length) return false;
        for (int i = 0; i < Raw.Length; i++) if (Raw[i] != b.Raw[i]) return false;
        return true;
    }
    public override int GetHashCode() {
        int h = 1;
        foreach (byte b in Raw) h = h * 31 + b;
        return h;
    }
    public override string ToString() => $"#bytes[{Raw.Length}]";

    /// A byte string being built. flint's transient exists so that appending is
    /// not N copies; this one uses a growable buffer for the same reason.
    public sealed class T {
        private byte[] _buf = new byte[32];
        private int _len;
        private void Room(int n) {
            if (_len + n > _buf.Length) Array.Resize(ref _buf, Math.Max(_buf.Length * 2, _len + n));
        }
        public void Conj(long b) { Room(1); _buf[_len++] = (byte) b; }
        public void Append(Bytes b) { Room(b.Raw.Length); Array.Copy(b.Raw, 0, _buf, _len, b.Raw.Length); _len += b.Raw.Length; }
        public int Count => _len;
        public Bytes Persistent() { var outb = new byte[_len]; Array.Copy(_buf, outb, _len); return new Bytes(outb); }
    }
}

/// A `volatile!`: an atom without the atomicity, for a value only one thread
/// ever touches. flint's core library uses them where a transient would be
/// overkill and an atom's retry would be waste.
public sealed class Volatile {
    private object _value;
    public Volatile(object v) => _value = v;
    public object Deref() => _value;
    public object Reset(object v) { _value = v; return v; }
    public override string ToString() => $"#volatile[{Builtins.PrStr(_value)}]";
}
