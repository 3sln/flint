namespace Flint.Rt;

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
    // splint:begin splint/hash.splint
    const int C1 = unchecked((int) 0xcc9e2d51);
    const int C2 = 0x1b873593;
    public const int Seed = 0;
    static int MixK1(int k1) {
        unchecked {
            return ((int) BitOperations.RotateLeft((uint) (k1 * C1), 15)) * C2;
        }
    }
    static int MixH1(int h1, int k1) {
        unchecked {
            return (((int) BitOperations.RotateLeft((uint) (h1 ^ k1), 13)) * 5) + unchecked((int) 0xe6546b64);
        }
    }
    static int Fmix(int h1, int len) {
        unchecked {
            h1 ^= len;
            h1 ^= (int)((uint) h1 >> 16);
            h1 *= unchecked((int) 0x85ebca6b);
            h1 ^= (int)((uint) h1 >> 13);
            h1 *= unchecked((int) 0xc2b2ae35);
            return h1 ^ ((int)((uint) h1 >> 16));
        }
    }
    public static int HashInt(int input) {
        unchecked {
            if (input == 0) {
                return 0;
            }
            return Fmix(MixH1(Seed, MixK1(input)), 4);
        }
    }
    public static int HashLong(long input) {
        unchecked {
            if (input == 0) {
                return 0;
            }
            int low = (int) input;
            int high = (int)((ulong) input >> 32);
            int h1 = MixH1(Seed, MixK1(low));
            int h2 = MixH1(h1, MixK1(high));
            return Fmix(h2, 8);
        }
    }
    public static int HashCombine(int seed, int h) {
        unchecked {
            return seed ^ (((h + unchecked((int) 0x9e3779b9)) + (seed << 6)) + (seed >> 2));
        }
    }
    public static int MixCollHash(int hash, int count) {
        unchecked {
            return Fmix(MixH1(Seed, MixK1(hash)), count);
        }
    }
    public static int OrderedStep(int acc, int itemHash) {
        unchecked {
            return (acc * 31) + itemHash;
        }
    }
    public static int UnorderedStep(int acc, int itemHash) {
        unchecked {
            return acc + itemHash;
        }
    }
    public const int HashTrue = 1231;
    public const int HashFalse = 1237;

    // splint:end splint/hash.splint

    public static int HashDouble(double d) {
        if (d == 0.0) return 0;   // both 0.0 and -0.0, matching Numbers.hasheq
        long bits = System.BitConverter.DoubleToInt64Bits(d);
        return (int) (bits ^ (long)((ulong) bits >> 32));
    }

    /// The UTF-16 code units of a UTF-8 byte string, without materialising a
    /// host string. A code point above 0xFFFF becomes a surrogate PAIR, which
    /// is the whole reason this cannot just walk code points.
    static int[] Utf16(byte[] b) {
        int[] outu = new int[b.Length + 1];
        int n = 0, i = 0;
        while (i < b.Length) {
            int c = b[i] & 0xFF, cp;
            if (c < 0x80) { cp = c; i += 1; }
            else if ((c & 0xE0) == 0xC0) { cp = ((c & 0x1F) << 6) | (b[i+1] & 0x3F); i += 2; }
            else if ((c & 0xF0) == 0xE0) {
                cp = ((c & 0x0F) << 12) | ((b[i+1] & 0x3F) << 6) | (b[i+2] & 0x3F); i += 3;
            } else {
                cp = ((c & 0x07) << 18) | ((b[i+1] & 0x3F) << 12)
                   | ((b[i+2] & 0x3F) << 6) | (b[i+3] & 0x3F); i += 4;
            }
            if (n + 2 > outu.Length) System.Array.Resize(ref outu, outu.Length * 2);
            if (cp < 0x10000) outu[n++] = cp;
            else {
                int v = cp - 0x10000;
                outu[n++] = 0xD800 + (v >> 10);
                outu[n++] = 0xDC00 + (v & 0x3FF);
            }
        }
        System.Array.Resize(ref outu, n);
        return outu;
    }

    /// `java.lang.String.hashCode()`: s[0]*31^(n-1) + ... over UTF-16 units.
    public static int JavaStringHash(byte[] b) {
        int h = 0;
        unchecked { foreach (int u in Utf16(b)) h = h * 31 + u; }
        return h;
    }

    /// `Murmur3.hashUnencodedChars`: two UTF-16 units per murmur word.
    public static int HashUnencodedChars(byte[] b) {
        int[] us = Utf16(b);
        int h1 = Seed;
        for (int i = 0; i + 1 < us.Length; i += 2) {
            h1 = MixH1(h1, MixK1(us[i] | (us[i + 1] << 16)));
        }
        if ((us.Length & 1) != 0) h1 ^= MixK1(us[us.Length - 1]);
        unchecked { return Fmix(h1, 2 * us.Length); }
    }

    public static int HashString(byte[] b) => HashInt(JavaStringHash(b));

    /// `ns` is the RAW Java string hash here, not the murmur'd one. That
    /// asymmetry is real, and the Rust records that it was found by solving for
    /// it against `'foo/bar`.
    public static int HashSymbol(byte[] ns, byte[] name) =>
        HashCombine(HashUnencodedChars(name), ns == null ? 0 : JavaStringHash(ns));

    public static int HashKeyword(byte[] ns, byte[] name) {
        unchecked { return HashSymbol(ns, name) + unchecked((int) 0x9e3779b9); }
    }

    /// Exposed for the test: a surrogate pair must count as TWO units, and a
    /// port that got that wrong would still hash stably and still be wrong.
    public static int[] Utf16Test(byte[] b) => Utf16(b);
}
