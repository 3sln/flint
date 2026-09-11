namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Hash;

using System.Numerics;

/// Hashing, ported from `runtime/src/hash.rs` and bit-compatible with JVM
/// Clojure's `hash`.
///
/// This is worth the trouble: `hash` is OBSERVABLE from Clojure code, so a
/// program that ports to flint should get the same numbers it got before. The
/// Rust's formulas were derived by solving against real Clojure values rather
/// than from memory, and its tests pin every one of them.
///
///   nil                0
///   true / false       1231 / 1237
///   long               Murmur3.hashLong
///   double             (int)(bits ^ (bits >>> 32)), and -0.0 hashes as 0.0
///   string             Murmur3.hashInt(javaStringHashCode(s))
///   symbol             hashCombine(hashUnencodedChars(name), rawHash(ns))
///   keyword            symbolHash + 0x9e3779b9
///   sequential         hashOrdered      (31*h + hash(x), then mixCollHash)
///   set                hashUnordered    (sum of hashes, then mixCollHash)
///   map                hashUnordered over entries; an entry hashes as [k v]
///
/// `javaStringHashCode` and `hashUnencodedChars` both run over **UTF-16 code
/// units**, not bytes and not code points. flint strings are UTF-8, so the walk
/// below re-derives the UTF-16 view on the fly. Getting this wrong is invisible
/// until the first astral-plane character.
///
/// Every multiply and add here is UNCHECKED. Murmur3 relies on wrapping, and
/// .NET's default is unchecked already -- saying so is for the reader, and
/// because the arithmetic elsewhere in this runtime is deliberately checked.
public static class Hash {

    public static int HashDouble(double d) {
        if (d == 0.0) return 0;   // both 0.0 and -0.0, matching Numbers.hasheq
        long bits = System.BitConverter.DoubleToInt64Bits(d);
        return (int) (bits ^ (long)((ulong) bits >> 32));
    }

    /// The UTF-16 code units of a UTF-8 byte string, without materialising a
    /// host string. A code point above 0xFFFF becomes a surrogate PAIR, which
    /// is the whole reason this cannot just walk code points.



    // `HashString` -- the UTF-16 walk -- was here and is gone; see
    // `Str.StringHash`. `JavaStringHash` stays for the symbol hash.

    /// `HashInt(h*31 + byte ...)`. The string hash is defined over BYTES;
    /// see `Str.StringHash`.
    public static int HashBytes(byte[] b) {
        int h = 0;
        foreach (byte x in b) h = unchecked(h * 31 + x);
        return HashInt(h);
    }

    /// `ns` is the RAW Java string hash here, not the murmur'd one. That
    /// asymmetry is real, and the Rust records that it was found by solving for
    /// it against `'foo/bar`.
    /// A symbol's hash, over BYTES like every other string hash here. It used
    /// to combine murmur over the name's UTF-16 units with the RAW 31-walk over
    /// the namespace's -- an asymmetry fitted to observed Clojure output, and
    /// reached by decoding UTF-8 into UTF-16 on every call.
    public static int HashSymbol(byte[] ns, byte[] name) =>
        HashCombine(HashBytes(name), ns == null ? 0 : HashBytes(ns));

    public static int HashKeyword(byte[] ns, byte[] name) {
        unchecked { return HashSymbol(ns, name) + unchecked((int) 0x9e3779b9); }
    }

}
