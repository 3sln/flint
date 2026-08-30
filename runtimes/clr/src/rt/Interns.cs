namespace Flint.Rt;

/// The intern tables, ported from `runtime/src/gc.rs`.
///
/// Open-addressed, linear-probed, and **WEAK**: an entry whose value is no
/// longer reachable is dropped by the collector rather than keeping it alive.
/// That is what lets every short string and every keyword be interned without
/// the table becoming a leak.
///
/// ## Why interning is correctness here, not a saving
///
/// `doc/decisions/0011`'s rule is that a value in the interned range has ONE
/// representation. `=` on two heap keywords is then a pointer compare, and
/// symbol equality is slot equality on interned strings. Two interned copies of
/// one string is therefore a CORRECTNESS bug and not a wasted allocation: `eq`
/// would read them as unequal while they read identically, and because symbols
/// compare by their slots it would spread.
///
/// That is why publishing RE-PROBES under the lock rather than inserting what
/// it built: another executor may have published the same content while this
/// one was building it, and theirs is the canonical one.
///
/// ## Cleared in place, never compacted
///
/// A slot's POSITION is part of the structure -- linear probing walks forward
/// from the hash -- so dropping an entry from the middle would move every later
/// one out from under its probe sequence. The symptom would be a symbol that
/// exists and cannot be found. `refresh` rebuilds the whole table instead.
public sealed class Interns {
    /// Which table. The indices are the Rust's, in its order.
    public const int STR = 0, KW = 1, SYM = 2, PORT = 3;
    public const int COUNT = 4;

    /// Longest string GUARANTEED to be interned. Past this a string is still
    /// correct, just not canonical -- so `eq` on long strings compares bytes.
    public const int InternMax = 32;

    /// `(hash, value)` pairs. A value of 0 is an empty slot, which is why no
    /// real value may be 0 -- and none is: 0 is the double `+0.0` only as a
    /// bit pattern nothing produces for an interned object.
    int[] hashes;
    long[] values;
    int count;

    public Interns(int capPow2) {
        hashes = new int[capPow2];
        values = new long[capPow2];
    }

    public static Interns[] Tables() {
        return new Interns[]{ new Interns(1024), new Interns(1024), new Interns(512), new Interns(4) };
    }

    int Mask() { return values.Length - 1; }

    public delegate bool Match(long v);

    /// The found value, or NOT_FOUND with `slot` left at where to insert.
    public int slot;

    public long Lookup(int hash, Match eq) {
        int m = Mask();
        int i = hash & m;
        for (;;) {
            long v = values[i];
            if (v == 0) { slot = i; return Val.NotFound; }
            if (hashes[i] == hash && eq(v)) { slot = i; return v; }
            i = (i + 1) & m;
        }
    }

    public void InsertAt(int idx, int hash, long v) {
        hashes[idx] = hash;
        values[idx] = v;
        count++;
    }

    public bool NeedsGrow() { return count * 4 >= values.Length * 3; }

    public void Grow() {
        int[] oh = hashes; long[] ov = values;
        hashes = new int[ov.Length * 2];
        values = new long[ov.Length * 2];
        count = 0;
        for (int i = 0; i < ov.Length; i++) if (ov[i] != 0) RawInsert(oh[i], ov[i]);
    }

    void RawInsert(int h, long v) {
        int m = Mask();
        int i = h & m;
        while (values[i] != 0) i = (i + 1) & m;
        hashes[i] = h;
        values[i] = v;
        count++;
    }

    /// What the collector calls. `f` answers the value's new address, or
    /// NOT_FOUND if it died -- in which case the entry goes.
    public delegate long Rehome(long v);

    public void Refresh(Rehome f) {
        int[] oh = hashes; long[] ov = values;
        hashes = new int[ov.Length];
        values = new long[ov.Length];
        count = 0;
        for (int i = 0; i < ov.Length; i++) {
            if (ov[i] == 0) continue;
            long nv = f(ov[i]);
            if (nv != Val.NotFound) RawInsert(oh[i], nv);
        }
    }
}
