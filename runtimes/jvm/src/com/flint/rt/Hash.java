package com.flint.rt;
import static com._3sln.flint.kgen.rt.Hash.*;

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



    // `hashString` -- the UTF-16 walk -- was here and is gone. A string's
    // hash is `hashBytes` now, at every tier; see `Str.stringHash`.
    // `javaStringHash` STAYS: a symbol's hash still combines it with the
    // murmur of the name.

    /// `hashInt(h*31 + byte ...)`, which is what a rope's walk produces once
    /// finalised. The string hash is defined over BYTES; see `Str.stringHash`.
    public static int hashBytes(byte[] b) {
        int h = 0;
        for (byte x : b) h = h * 31 + (x & 0xFF);
        return hashInt(h);
    }

    /// `ns` is the RAW Java string hash here, not the murmur'd one. That
    /// asymmetry is real, and the Rust records that it was found by solving for
    /// it against `'foo/bar`.
    /// A symbol's hash, over BYTES like every other string hash here.
    ///
    /// It used to be `hashCombine(hashUnencodedChars(name), javaStringHash(ns))`
    /// -- murmur over the name's UTF-16 units, combined with the RAW 31-walk
    /// over the namespace's. That asymmetry was fitted to observed Clojure
    /// output, not derived. Reaching it meant DECODING UTF-8 and allocating an
    /// int array of UTF-16 units, on the hot path, because keywords are what
    /// map keys are made of.
    public static int hashSymbol(byte[] ns, byte[] name) {
        return hashCombine(hashBytes(name), ns == null ? 0 : hashBytes(ns));
    }

    public static int hashKeyword(byte[] ns, byte[] name) {
        return hashSymbol(ns, name) + 0x9e3779b9;
    }


}
