package com.flint.rt;

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
public final class Interns {
    /// Which table. The indices are the Rust's, in its order.
    public static final int STR = 0, KW = 1, SYM = 2, PORT = 3;
    public static final int COUNT = 4;

    /// Longest string GUARANTEED to be interned. Past this a string is still
    /// correct, just not canonical -- so `eq` on long strings compares bytes.
    public static final int INTERN_MAX = 32;

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

    public static Interns[] tables() {
        return new Interns[]{ new Interns(1024), new Interns(1024), new Interns(512), new Interns(4) };
    }

    /// The candidate test a probe runs against each occupant. A functional
    /// interface here IS the closure Rust passes as `FnMut` -- one idea, two
    /// spellings.
    ///
    /// It lives OUTSIDE the generated region deliberately: kin has no form
    /// for a callback type, and a region that swallowed it silently deleted
    /// it the first time this file was carved.
    public interface Match { boolean test(long v); }

    /// What a rebuild maps each surviving entry through. Also outside the
    /// region, and for the same reason.
    public interface Refresh { long apply(long v); }

    // kin:begin kin/interns.kin
    int mask() {
        return this.values.length - 1;
    }
    public void insertAt(int idx, int hash, long v) {
        this.hashes[idx] = hash;
        this.values[idx] = v;
        this.count += 1;
    }
    public boolean needsGrow() {
        return (this.count * 4) >= (this.values.length * 3);
    }
    /// Insert with no probe for equality: the caller already knows this hash
    /// and value are not present. Used only by a rebuild, where every entry
    /// came out of a table that had already established that.
    void rawInsert(int h, long v) {
        int m = this.mask();
        int i = h & m;
        while (this.values[i] != 0) {
            i = (i + 1) & m;
        }
        this.hashes[i] = h;
        this.values[i] = v;
        this.count += 1;
    }

    // kin:end kin/interns.kin

    public void grow() {
        int[] oh = hashes; long[] ov = values;
        hashes = new int[ov.length * 2];
        values = new long[ov.length * 2];
        count = 0;
        for (int i = 0; i < ov.length; i++) if (ov[i] != 0) rawInsert(oh[i], ov[i]);
    }

    /// The found value, or NOT_FOUND with `slot` left at where to insert.
    public int slot;

    public long lookup(int hash, Match eq) {
        int m = mask();
        int i = hash & m;
        for (;;) {
            long v = values[i];
            if (v == 0) { slot = i; return Val.NOT_FOUND; }
            if (hashes[i] == hash && eq.test(v)) { slot = i; return v; }
            i = (i + 1) & m;
        }
    }

    public void refresh(Refresh f) {
        int[] oh = hashes; long[] ov = values;
        hashes = new int[ov.length];
        values = new long[ov.length];
        count = 0;
        for (int i = 0; i < ov.length; i++) {
            if (ov[i] == 0) continue;
            long nv = f.apply(ov[i]);
            if (nv != Val.NOT_FOUND) rawInsert(oh[i], nv);
        }
    }
}
