package com.flint;

import java.util.Collection;
import java.util.Map;

/// Hashing, bit-compatible with JVM Clojure's `hash` and with
/// `runtime/src/hash.rs`.
///
/// Ported rather than re-derived. Two hosts that hash differently do not merely
/// print maps in a different order -- `doc/decisions/0010` points out that
/// content-addressed artifacts would then hash differently per host, which
/// breaks a property that is depended on rather than merely noticed.
///
/// On the JVM one piece comes free that the Rust runtime has to build: these
/// formulas run over UTF-16 CODE UNITS, which is what a Java `String` already
/// is. The wasm runtime re-derives that view from UTF-8 on the fly, and getting
/// it wrong there is invisible until the first astral-plane character.
public final class Hash {
    private Hash() {}

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;
    static final int SEED = 0;

    private static int mixK1(int k1) {
        return Integer.rotateLeft(k1 * C1, 15) * C2;
    }
    private static int mixH1(int h1, int k1) {
        return Integer.rotateLeft(h1 ^ k1, 13) * 5 + 0xe6546b64;
    }
    private static int fmix(int h1, int len) {
        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        return h1 ^ (h1 >>> 16);
    }

    public static int hashInt(int input) {
        if (input == 0) return 0;
        return fmix(mixH1(SEED, mixK1(input)), 4);
    }

    public static int hashLong(long input) {
        if (input == 0) return 0;
        int low = (int) input;
        int high = (int) (input >>> 32);
        int h1 = mixH1(SEED, mixK1(low));
        h1 = mixH1(h1, mixK1(high));
        return fmix(h1, 8);
    }

    public static int hashDouble(double d) {
        // Both 0.0 and -0.0 hash as 0, matching `Numbers.hasheq`.
        if (d == 0.0) return 0;
        long bits = Double.doubleToRawLongBits(d);
        return (int) (bits ^ (bits >>> 32));
    }

    /// `boost::hash_combine`, as Clojure's `Util.hashCombine`.
    public static int hashCombine(int seed, int h) {
        return seed ^ (h + 0x9e3779b9 + (seed << 6) + (seed >> 2));
    }

    /// Murmur3 over UTF-16 code units, taken two at a time.
    public static int hashUnencodedChars(String s) {
        int h1 = SEED;
        int n = s.length();
        for (int i = 1; i < n; i += 2) {
            int k1 = s.charAt(i - 1) | (s.charAt(i) << 16);
            h1 = mixH1(h1, mixK1(k1));
        }
        if ((n & 1) == 1) h1 ^= mixK1(s.charAt(n - 1));
        return fmix(h1, 2 * n);
    }

    public static int hashString(String s) { return hashInt(s.hashCode()); }

    /// The `ns` here is the RAW Java string hash, not the murmur'd one. That
    /// asymmetry is real; `hash.rs` records that it was found by solving
    /// against `'foo/bar` rather than assumed.
    public static int hashSymbol(String ns, String name) {
        return hashCombine(hashUnencodedChars(name), ns == null ? 0 : ns.hashCode());
    }

    public static int hashKeyword(String ns, String name) {
        return hashSymbol(ns, name) + 0x9e3779b9;
    }

    public static int mixCollHash(int hash, int count) {
        return fmix(mixH1(SEED, mixK1(hash)), count);
    }

    /// Order matters: a vector and a seq with the same elements hash alike, and
    /// differently from a set holding them.
    public static int hashOrdered(Iterable<?> xs) {
        int acc = 1, n = 0;
        for (Object x : xs) { acc = acc * 31 + of(x); n++; }
        return mixCollHash(acc, n);
    }

    /// Order does NOT matter: a sum, so a set hashes the same however it was
    /// built.
    public static int hashUnordered(Iterable<?> xs) {
        int acc = 0, n = 0;
        for (Object x : xs) { acc += of(x); n++; }
        return mixCollHash(acc, n);
    }

    /// The hash of any flint value.
    public static int of(Object v) {
        if (v == null) return 0;
        if (v instanceof Boolean b) return b ? 1231 : 1237;
        if (v instanceof Long l) return hashLong(l);
        if (v instanceof Double d) return hashDouble(d);
        if (v instanceof String s) return hashString(s);
        if (v instanceof Kw k) return hashKeyword(k.ns, k.name);
        if (v instanceof Sym s) return hashSymbol(s.ns, s.name);
        if (v instanceof Map<?, ?> m) {
            int acc = 0, n = 0;
            for (var e : m.entrySet()) {
                // An entry hashes as the two-element vector `[k v]`.
                acc += hashOrdered(java.util.List.of(
                    e.getKey() == null ? NIL : e.getKey(),
                    e.getValue() == null ? NIL : e.getValue()));
                n++;
            }
            return mixCollHash(acc, n);
        }
        if (v instanceof java.util.Set<?> s) return hashUnordered(s);
        if (v instanceof LazySeq ls) return hashOrdered(ls.force());
        if (v instanceof Collection<?> c) return hashOrdered(c);
        return v.hashCode();
    }

    /// A stand-in so `List.of` can hold a nil, which it will not.
    private static final Object NIL = new Object() {
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "nil"; }
    };
}
