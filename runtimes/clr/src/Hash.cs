namespace Flint;

/// Hashing, bit-compatible with JVM Clojure's `hash`, `runtime/src/hash.rs` and
/// the JVM port.
///
/// Ported rather than re-derived, and pinned against the same vectors those
/// assert. Two hosts that hash differently do not merely print maps in a
/// different order: `doc/decisions/0010` points out that content-addressed
/// artifacts would then hash differently per host, which breaks a property that
/// is depended on rather than merely noticed.
///
/// One piece comes free here as it does on the JVM: these formulas run over
/// UTF-16 CODE UNITS, which is what a .NET `string` already is. The wasm
/// runtime re-derives that view from UTF-8, and getting it wrong there is
/// invisible until the first astral-plane character.
public static class Hash {
    private const int C1 = unchecked((int) 0xcc9e2d51);
    private const int C2 = 0x1b873593;
    private const int Seed = 0;

    private static int RotL(int x, int n) => (int) (((uint) x << n) | ((uint) x >> (32 - n)));

    private static int MixK1(int k1) => unchecked(RotL(unchecked(k1 * C1), 15) * C2);

    private static int MixH1(int h1, int k1) =>
        unchecked(RotL(h1 ^ k1, 13) * 5 + unchecked((int) 0xe6546b64));

    private static int Fmix(int h1, int len) {
        unchecked {
            h1 ^= len;
            h1 ^= (int) ((uint) h1 >> 16);
            h1 *= unchecked((int) 0x85ebca6b);
            h1 ^= (int) ((uint) h1 >> 13);
            h1 *= unchecked((int) 0xc2b2ae35);
            return h1 ^ (int) ((uint) h1 >> 16);
        }
    }

    public static int HashInt(int input) => input == 0 ? 0 : Fmix(MixH1(Seed, MixK1(input)), 4);

    public static int HashLong(long input) {
        if (input == 0) return 0;
        unchecked {
            int low = (int) input;
            int high = (int) ((ulong) input >> 32);
            int h1 = MixH1(Seed, MixK1(low));
            h1 = MixH1(h1, MixK1(high));
            return Fmix(h1, 8);
        }
    }

    public static int HashDouble(double d) {
        // Both 0.0 and -0.0 hash as 0, matching `Numbers.hasheq`.
        if (d == 0.0) return 0;
        long bits = BitConverter.DoubleToInt64Bits(d);
        return unchecked((int) (bits ^ (long) ((ulong) bits >> 32)));
    }

    /// `boost::hash_combine`, as Clojure's `Util.hashCombine`.
    public static int HashCombine(int seed, int h) =>
        unchecked(seed ^ (h + unchecked((int) 0x9e3779b9) + (seed << 6) + (seed >> 2)));

    /// `java.lang.String.hashCode()`: s[0]*31^(n-1) + ... over UTF-16 units.
    /// .NET's own `GetHashCode` is randomised per process and cannot be used.
    public static int JavaStringHash(string s) {
        int h = 0;
        unchecked { foreach (char c in s) h = h * 31 + c; }
        return h;
    }

    /// Murmur3 over UTF-16 code units, taken two at a time.
    public static int HashUnencodedChars(string s) {
        int h1 = Seed;
        int n = s.Length;
        for (int i = 1; i < n; i += 2) {
            int k1 = unchecked(s[i - 1] | (s[i] << 16));
            h1 = MixH1(h1, MixK1(k1));
        }
        if ((n & 1) == 1) h1 ^= MixK1(s[n - 1]);
        return Fmix(h1, unchecked(2 * n));
    }

    public static int HashString(string s) => HashInt(JavaStringHash(s));

    /// The `ns` is the RAW string hash here, not the murmur'd one. That
    /// asymmetry is real; `hash.rs` records that it was solved against
    /// `'foo/bar` rather than assumed.
    public static int HashSymbol(string ns, string name) =>
        HashCombine(HashUnencodedChars(name), ns == null ? 0 : JavaStringHash(ns));

    public static int HashKeyword(string ns, string name) =>
        unchecked(HashSymbol(ns, name) + unchecked((int) 0x9e3779b9));

    public static int MixCollHash(int hash, int count) =>
        Fmix(MixH1(Seed, MixK1(hash)), count);

    /// Order matters: a vector and a seq with the same elements hash alike, and
    /// differently from a set holding them.
    public static int HashOrdered(System.Collections.IEnumerable xs) {
        int acc = 1, n = 0;
        unchecked { foreach (object x in xs) { acc = acc * 31 + Of(x); n++; } }
        return MixCollHash(acc, n);
    }

    /// Order does NOT matter: a sum, so a set hashes the same however built.
    public static int HashUnordered(System.Collections.IEnumerable xs) {
        int acc = 0, n = 0;
        unchecked { foreach (object x in xs) { acc += Of(x); n++; } }
        return MixCollHash(acc, n);
    }

    public static int Of(object v) {
        switch (v) {
            case null: return 0;
            case bool b: return b ? 1231 : 1237;
            case long l: return HashLong(l);
            case double d: return HashDouble(d);
            case string s: return HashString(s);
            case Kw k: return HashKeyword(k.Ns, k.Name);
            case Sym s2: return HashSymbol(s2.Ns, s2.Name);
            case FlintMap m: {
                int acc = 0, n = 0;
                unchecked {
                    foreach (var e in m) {
                        // An entry hashes as the two-element vector `[k v]`.
                        acc += HashOrdered(new object[] { e.Key, e.Value });
                        n++;
                    }
                }
                return MixCollHash(acc, n);
            }
            case FlintSet st: return HashUnordered(st);
            case Seq q: return HashOrdered(q);
            case System.Collections.IEnumerable e2: return HashOrdered(e2);
            default: return v.GetHashCode();
        }
    }
}
