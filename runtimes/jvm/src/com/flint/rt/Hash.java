package com.flint.rt;
import static flint.rt.Hash.*;

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
/// units**, not bytes and not code points. flint strings are UTF-8, so the
/// walk below re-derives the UTF-16 view on the fly. Getting this wrong is
/// invisible until the first astral-plane character.
///
/// A note on the host: this file is the one place where being ON the JVM is a
/// temptation rather than a help. `String.hashCode()` would give the right
/// answer for `javaStringHash` -- and the CLR has no such method, so using it
/// would leave the two ports computing the same number by different routes.
/// The arithmetic is written out on both.
public final class Hash {
    private Hash() {}


    public static int hashDouble(double d) {
        if (d == 0.0) return 0;   // both 0.0 and -0.0, matching Numbers.hasheq
        long bits = Double.doubleToLongBits(d);
        return (int) (bits ^ (bits >>> 32));
    }

    /// The UTF-16 code units of a UTF-8 byte string, without materialising a
    /// host string. A code point above 0xFFFF becomes a surrogate PAIR, which
    /// is the whole reason this cannot just walk code points.
    static int[] utf16(byte[] b) {
        int[] out = new int[b.length + 1];
        int n = 0, i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF, cp;
            if (c < 0x80) { cp = c; i += 1; }
            else if ((c & 0xE0) == 0xC0) { cp = ((c & 0x1F) << 6) | (b[i+1] & 0x3F); i += 2; }
            else if ((c & 0xF0) == 0xE0) {
                cp = ((c & 0x0F) << 12) | ((b[i+1] & 0x3F) << 6) | (b[i+2] & 0x3F); i += 3;
            } else {
                cp = ((c & 0x07) << 18) | ((b[i+1] & 0x3F) << 12)
                   | ((b[i+2] & 0x3F) << 6) | (b[i+3] & 0x3F); i += 4;
            }
            if (n + 2 > out.length) {
                int[] bigger = new int[out.length * 2];
                System.arraycopy(out, 0, bigger, 0, n);
                out = bigger;
            }
            if (cp < 0x10000) out[n++] = cp;
            else {
                int v = cp - 0x10000;
                out[n++] = 0xD800 + (v >> 10);
                out[n++] = 0xDC00 + (v & 0x3FF);
            }
        }
        int[] exact = new int[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }

    /// `java.lang.String.hashCode()`: s[0]*31^(n-1) + ... over UTF-16 units.
    public static int javaStringHash(byte[] b) {
        int h = 0;
        for (int u : utf16(b)) h = h * 31 + u;
        return h;
    }

    /// `Murmur3.hashUnencodedChars`: two UTF-16 units per murmur word.
    public static int hashUnencodedChars(byte[] b) {
        int[] us = utf16(b);
        int h1 = SEED;
        for (int i = 0; i + 1 < us.length; i += 2) {
            h1 = mixH1(h1, mixK1(us[i] | (us[i + 1] << 16)));
        }
        if ((us.length & 1) != 0) h1 ^= mixK1(us[us.length - 1]);
        return fmix(h1, 2 * us.length);
    }

    public static int hashString(byte[] b) { return hashInt(javaStringHash(b)); }

    /// `ns` is the RAW Java string hash here, not the murmur'd one. That
    /// asymmetry is real, and the Rust records that it was found by solving for
    /// it against `'foo/bar`.
    public static int hashSymbol(byte[] ns, byte[] name) {
        return hashCombine(hashUnencodedChars(name), ns == null ? 0 : javaStringHash(ns));
    }

    public static int hashKeyword(byte[] ns, byte[] name) {
        return hashSymbol(ns, name) + 0x9e3779b9;
    }

    /// Exposed for the test: a surrogate pair must count as TWO units, and a
    /// port that got that wrong would still hash stably and still be wrong.
    public static int[] utf16Test(byte[] b) { return utf16(b); }

}
