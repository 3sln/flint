using System.Text;

namespace Flint.Rt;

/// Strings, ported from `runtime/src/strs.rs`.
///
/// Three tiers, and the first two are here (`doc/decisions/0011`'s ropes are
/// the third and come with the rest of that file):
///
/// * five bytes or fewer live IN the value -- no allocation, and a comparison
///   is one 64-bit compare;
/// * anything longer is a `TY_STR` on the heap, with its hash cached at +8 and
///   an ASCII flag in the header.
///
/// The ASCII flag is not a micro-optimisation. flint indexes strings by CODE
/// POINT, so a byte index and a character index coincide only for ASCII --
/// without the flag `subs` and `nth` walk, and splitting a string was
/// quadratic. Before it existed the word-frequency benchmark took 762 ms
/// instead of 62.
public static class Str {
    public static bool IsString(Rt rt, long v) {
        if (Val.IsInlineStr(v)) return true;
        if (!Val.IsHeap(v)) return false;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        return t == Obj.TyStr || t == Obj.TyRope;
    }

    /// A bare, UNINTERNED heap string. `Of` is the canonical constructor.
    static long RawString(Rt rt, byte[] b) {
        long a = rt.Alloc(Obj.TyStr, b.Length);
        if (a == 0) return Val.Nil;
        rt.gc.sp.WriteBytes(a + Obj.StrData, b);
        Obj.SetStrHash(rt.gc.sp, a, 0);
        bool ascii = true;
        foreach (byte x in b) if ((x & 0x80) != 0) { ascii = false; break; }
        Obj.SetStrAscii(rt.gc.sp, a, ascii);
        return Val.Heap(a);
    }

    /// The CANONICAL value for a string.
    ///
    /// Three tiers (`doc/decisions/0011`): inline up to 5 bytes, interned up to
    /// `INTERN_MAX`, and plain heap beyond. Inline is canonical by construction
    /// and interning is guaranteed in its range, so two strings in either range
    /// are `=` exactly when they are bit-equal. Past the range a string is
    /// still correct, just not canonical, and `eq` compares bytes.
    public static long Of(Rt rt, string s) {
        byte[] b = Encoding.UTF8.GetBytes(s);
        if (b.Length <= Val.InlineMax) return Val.InlineStr(b);
        if (b.Length > Interns.InternMax) return RawString(rt, b);
        int h = Hash.HashString(b);
        Interns.Match matches = v => Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyStr
                                     && SameBytes(Bytes(rt, v), b);
        long found = Probe(rt, Interns.STR, h, matches);
        if (found != Val.NotFound) return found;

        // Allocated OUTSIDE the probe, which keeps interning off the allocation
        // path -- and the table is re-probed below, because the allocation can
        // COLLECT and the collector rewrites this very table.
        long v2 = RawString(rt, b);
        if (Val.IsNil(v2)) return v2;
        Obj.SetStrHash(rt.gc.sp, Val.AsHeap(v2), h == 0 ? 1 : h);
        return Publish(rt, Interns.STR, h, v2, matches);
    }

    public static bool SameBytes(byte[] x, byte[] y) {
        if (x.Length != y.Length) return false;
        for (int i = 0; i < x.Length; i++) if (x[i] != y[i]) return false;
        return true;
    }

    /// Publish a freshly built value, or take the one already there.
    ///
    /// The RE-PROBE is not an optimisation. Two interned copies of one string
    /// is a CORRECTNESS bug rather than a wasted allocation: `eq` reads "both
    /// interned and not bit-equal" as NOT EQUAL, so the copies would compare
    /// unequal while reading identically -- and symbol equality is slot
    /// equality on those same strings, so it would spread.
    static long Publish(Rt rt, int table, int h, long v, Interns.Match matches) {
        // ROOTED across the lock. `LockIntern` can PARK -- that is the point of
        // it, so a thread waiting for the lock still reaches a safepoint -- and
        // parking means another executor may collect and move `v` while this
        // thread is stopped.
        int bas = rt.Mark();
        int vi = rt.Push(v);
        rt.roots.shared.par.LockIntern(table);
        try {
            Interns t = rt.roots.shared.interns[table];
            long again = t.Lookup(h, matches);
            if (again != Val.NotFound) return again;   // somebody got there first
            int idx = t.slot;
            if (t.NeedsGrow()) {
                t.Grow();
                t.Lookup(h, x => false);   // `Grow` invalidates the index
                idx = t.slot;
            }
            t.InsertAt(idx, h, rt.R(vi));
            return rt.R(vi);
        } finally {
            rt.roots.shared.par.UnlockIntern(table);
            rt.PopTo(bas);
        }
    }

    /// Probe a table under its lock. Nothing needs rooting: no allocation
    /// happens, and the lock can only park before anything is live.
    static long Probe(Rt rt, int table, int h, Interns.Match matches) {
        rt.roots.shared.par.LockIntern(table);
        try {
            return rt.roots.shared.interns[table].Lookup(h, matches);
        } finally {
            rt.roots.shared.par.UnlockIntern(table);
        }
    }

    /// The number of CODE POINTS, which is what `count` on a string means in
    /// Clojure. For an ASCII string that equals the byte length, which is what
    /// the header flag is for; otherwise the bytes are walked.
    public static int CharLen(Rt rt, long v) {
        byte[] b = Bytes(rt, v);
        if (IsAscii(rt, v)) return b.Length;
        int n = 0;
        foreach (byte x in b) if ((x & 0xC0) != 0x80) n++;   // non-continuations
        return n;
    }

    static bool IsAscii(Rt rt, long v) {
        if (Val.IsInlineStr(v)) {
            foreach (byte x in Val.InlineBytes(v)) if ((x & 0x80) != 0) return false;
            return true;
        }
        if (IsRope(rt, v)) return (Val.AsFixnum(rt.Slot(v, RP_CPS)) & 1) != 0;
        return Obj.StrIsAscii(rt.gc.sp, Val.AsHeap(v));
    }

    /// The code point at index `i`, as a single-character string. NOT a char
    /// type: flint has no char, and `doc/decisions/0010` counts that among the
    /// documented divergences rather than a gap.
    public static long Nth(Rt rt, long v, int i) {
        if (i < 0) return Val.NotFound;
        byte[] b = Bytes(rt, v);
        if (IsAscii(rt, v)) {
            if (i >= b.Length) return Val.NotFound;
            return Val.InlineStr(new byte[]{ b[i] });
        }
        int at = ByteOfCp(rt, v, b, i);
        if (at < 0) return Val.NotFound;
        int size = Utf8Width(b[at]);
        var one = new byte[size];
        System.Array.Copy(b, at, one, 0, size);
        return Val.InlineStr(one);
    }

    /// How many bytes the code point starting with `b0` occupies.
    static int Utf8Width(byte b0) {
        int c = b0 & 0xFF;
        if (c < 0x80) return 1;
        if (c < 0xE0) return 2;
        if (c < 0xF0) return 3;
        return 4;
    }

    /// The byte offset of code point `i`, RESUMING FROM THE CURSOR when it can.
    ///
    /// A byte index and a code-point index coincide only for ASCII, so a
    /// non-ASCII string has to be walked -- and every reader in the language
    /// walks a string with `nth` in a loop, which made every reader O(n^2).
    /// One `ä` in a 115 KB EDN document was the difference between 119 ms and
    /// 5 375 ms, quadrupling each time the input doubled.
    ///
    /// The cursor makes SEQUENTIAL indexing O(1) amortised, which is the access
    /// pattern that was quadratic. Random access is unchanged.
    ///
    /// Keyed on the COLLECTION COUNT as well as the value, because a copying
    /// collector moves objects and can put a different one where this was --
    /// comparing addresses alone would be a memo that is silently wrong rather
    /// than merely stale.
    static int ByteOfCp(Rt rt, long v, byte[] b, int i) {
        long epoch = rt.gc.minors + rt.gc.majors;
        int cp = 0, at = 0;
        if (rt.cursorBits == v && rt.cursorEpoch == epoch && rt.cursorCp <= i) {
            cp = rt.cursorCp;
            at = rt.cursorByte;
        }
        while (cp < i) {
            if (at >= b.Length) return -1;
            at += Utf8Width(b[at]);
            cp++;
        }
        if (at >= b.Length) return -1;
        rt.cursorBits = v;
        rt.cursorEpoch = epoch;
        rt.cursorCp = cp;
        rt.cursorByte = at;
        return at;
    }

    /// The CODE POINT at index `i`, or -1 when `i` is past the end.
    ///
    /// Separate from `Nth` so the reader does not build a one-character string
    /// per character just to ask what it is.
    public static int CodePointAt(Rt rt, long v, int i) {
        if (i < 0) return -1;
        byte[] b = Bytes(rt, v);
        if (IsAscii(rt, v)) return i >= b.Length ? -1 : (b[i] & 0xFF);
        int at = ByteOfCp(rt, v, b, i);
        if (at < 0) return -1;
        return char.ConvertToUtf32(Encoding.UTF8.GetString(b, at, Utf8Width(b[at])), 0);
    }

    public static byte[] Bytes(Rt rt, long v) {
        if (Val.IsInlineStr(v)) return Val.InlineBytes(v);
        if (IsRope(rt, v)) {
            var ms = new System.IO.MemoryStream(SBytes(rt, v));
            AppendBytes(rt, v, ms);
            return ms.ToArray();
        }
        long a = Val.AsHeap(v);
        return rt.gc.sp.Bytes(a + Obj.StrData, Obj.Len(rt.gc.sp, a));
    }

    public static string Text(Rt rt, long v) => Encoding.UTF8.GetString(Bytes(rt, v));

    /// A keyword. Inline when it has no namespace and fits in five bytes,
    /// which is most of them; otherwise `TY_KW` `[ns, name, hash]`.
    ///
    /// INTERNED through the weak table, so two spellings of one keyword are the
    /// same object -- which is what makes `=` a pointer compare and keeps map
    /// lookups cheap. The rooting in `BuildKeyword` must not be simplified:
    /// both strings are live across the `Alloc` that can move them.
    public static long Keyword(Rt rt, string ns, string name) {
        byte[] nb = Encoding.UTF8.GetBytes(name);
        if (ns == null && nb.Length > 0 && nb.Length <= Val.InlineMax) return Val.InlineKw(nb);
        byte[] nsb = ns == null ? null : Encoding.UTF8.GetBytes(ns);
        int h = Hash.HashKeyword(nsb, nb);
        Interns.Match matches = v => Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyKw
                                     && SameName(rt, v, nsb, nb);
        long found = Probe(rt, Interns.KW, h, matches);
        if (found != Val.NotFound) return found;
        long built = BuildKeyword(rt, ns, name);
        if (Val.IsNil(built)) return built;
        return Publish(rt, Interns.KW, h, built, matches);
    }

    /// True when `v`'s namespace and name are exactly these bytes. Compares
    /// CONTENT and not identity, because the strings inside a keyword may
    /// themselves be inline, interned or plain.
    static bool SameName(Rt rt, long v, byte[] nsb, byte[] nb) {
        long vns = rt.Slot(v, 0);
        if ((nsb == null) != Val.IsNil(vns)) return false;
        if (nsb != null && !SameBytes(Bytes(rt, vns), nsb)) return false;
        return SameBytes(Bytes(rt, rt.Slot(v, 1)), nb);
    }

    static long BuildKeyword(Rt rt, string ns, string name) {
        int bas = rt.Mark();
        int nsi = rt.Push(ns == null ? Val.Nil : Of(rt, ns));
        int nmi = rt.Push(Of(rt, name));
        long a = rt.Alloc(Obj.TyKw, 3);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, 0, rt.R(nsi));
        rt.SetSlot(a, 1, rt.R(nmi));
        rt.SetSlot(a, 2, Val.Nil);
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// A symbol: `[ns, name, meta, hash]`. Interned like a keyword, and for the
    /// same reason: `=` on two symbols compares their slots, so two copies of
    /// one symbol would compare unequal while printing identically.
    public static long Symbol(Rt rt, string ns, string name) {
        byte[] nb = Encoding.UTF8.GetBytes(name);
        byte[] nsb = ns == null ? null : Encoding.UTF8.GetBytes(ns);
        int h = Hash.HashSymbol(nsb, nb);
        Interns.Match matches = v => Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TySym
                                     && SameName(rt, v, nsb, nb);
        long found = Probe(rt, Interns.SYM, h, matches);
        if (found != Val.NotFound) return found;
        long built = BuildSymbol(rt, ns, name);
        if (Val.IsNil(built)) return built;
        return Publish(rt, Interns.SYM, h, built, matches);
    }

    static long BuildSymbol(Rt rt, string ns, string name) {
        int bas = rt.Mark();
        int nsi = rt.Push(ns == null ? Val.Nil : Of(rt, ns));
        int nmi = rt.Push(Of(rt, name));
        long a = rt.Alloc(Obj.TySym, 4);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, 0, rt.R(nsi));
        rt.SetSlot(a, 1, rt.R(nmi));
        rt.SetSlot(a, 2, Val.Nil);
        rt.SetSlot(a, 3, Val.Nil);
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    // --- ropes (`doc/decisions/0011`) ---------------------------------------
    //
    // The THIRD tier: a shallow tree of string pieces, so `str` of two large
    // strings is a tree join rather than a copy. A node carries its subtree's
    // byte length, its CODE-POINT COUNT and an ASCII bit, all summed from its
    // children -- so `count` on a rope is O(1) and does not walk.
    //
    // Packing the count and the ASCII bit into ONE slot is not thrift: a slot
    // is a NaN-boxed value, and two would make every node 8 bytes bigger for
    // one bit.

    public const int RP_BYTES = 0, RP_CPS = 1, RP_FLAT = 2, RP_KIDS = 3;
    public const int FLAT_MAX = 1024, FANOUT = 16;

    public static bool IsRope(Rt rt, long v) =>
        Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyRope;

    /// Byte length of any string, ALL THREE TIERS, O(1).
    public static int SBytes(Rt rt, long v) {
        if (Val.IsInlineStr(v)) return Val.InlineLen(v);
        if (IsRope(rt, v)) return (int) Val.AsFixnum(rt.Slot(v, RP_BYTES));
        return Obj.Len(rt.gc.sp, Val.AsHeap(v));
    }

    public static int SCount(Rt rt, long v) {
        if (IsRope(rt, v)) return (int) (Val.AsFixnum(rt.Slot(v, RP_CPS)) >> 1);
        return CharLen(rt, v);
    }

    public static bool SAscii(Rt rt, long v) {
        if (IsRope(rt, v)) return (Val.AsFixnum(rt.Slot(v, RP_CPS)) & 1) != 0;
        return IsAscii(rt, v);
    }

    static int RopeKids(Rt rt, long v) => Obj.Len(rt.gc.sp, Val.AsHeap(v)) - RP_KIDS;

    /// A node over `kids`, whose aggregates are SUMMED from them rather than
    /// derived from their bytes. That is what makes `count` O(1) on a tree.
    static long RopeNode(Rt rt, long[] kids) {
        int bytes = 0, cps = 0;
        bool ascii = true;
        foreach (long k in kids) {
            bytes += SBytes(rt, k);
            cps += SCount(rt, k);
            ascii &= SAscii(rt, k);
        }
        int bas = rt.Mark();
        foreach (long k in kids) rt.Push(k);
        long a = rt.Alloc(Obj.TyRope, RP_KIDS + kids.Length);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, RP_BYTES, Val.Fixnum(bytes));
        rt.SetSlot(a, RP_CPS, Val.Fixnum(((long) cps << 1) | (ascii ? 1L : 0L)));
        rt.SetSlot(a, RP_FLAT, Val.Nil);
        for (int i = 0; i < kids.Length; i++) rt.SetSlot(a, RP_KIDS + i, rt.R(bas + i));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// `str` of two strings. O(1) once the pieces are big enough to matter.
    public static long Concat(Rt rt, long a, long b) {
        if (SBytes(rt, a) == 0) return b;
        if (SBytes(rt, b) == 0) return a;
        if (SBytes(rt, a) + SBytes(rt, b) <= FLAT_MAX) {
            // Small enough that a tree would cost more than the copy. This is
            // the tier that must not be skipped.
            return CopyConcat(rt, a, b);
        }
        // Append into the RIGHT SPINE while there is room, so a thousand small
        // appends do not become a thousand nodes.
        if (IsRope(rt, a) && RopeKids(rt, a) < FANOUT) {
            int n = RopeKids(rt, a);
            int bas = rt.Mark();
            int ai = rt.Push(a);
            for (int i = 0; i < n; i++) rt.Push(rt.Slot(rt.R(ai), RP_KIDS + i));
            rt.Push(b);
            long[] kids = new long[n + 1];
            for (int i = 0; i <= n; i++) kids[i] = rt.R(bas + 1 + i);
            long outv = RopeNode(rt, kids);
            rt.PopTo(bas);
            return outv;
        }
        int bas2 = rt.Mark();
        int a2 = rt.Push(a), b2 = rt.Push(b);
        long outv2 = RopeNode(rt, new long[]{ rt.R(a2), rt.R(b2) });
        rt.PopTo(bas2);
        return outv2;
    }

    static long CopyConcat(Rt rt, long a, long b) {
        byte[] x = Bytes(rt, a), y = Bytes(rt, b);
        byte[] both = new byte[x.Length + y.Length];
        System.Array.Copy(x, 0, both, 0, x.Length);
        System.Array.Copy(y, 0, both, x.Length, y.Length);
        return Of(rt, Encoding.UTF8.GetString(both));
    }

    /// Walk the leaves in order, appending their bytes.
    static void AppendBytes(Rt rt, long v, System.IO.MemoryStream outv) {
        if (Val.IsInlineStr(v)) { byte[] ib = Val.InlineBytes(v); outv.Write(ib, 0, ib.Length); return; }
        if (!Val.IsHeap(v)) return;
        int t = Obj.Ty(rt.gc.sp, Val.AsHeap(v));
        if (t == Obj.TyStr) {
            byte[] sb = rt.gc.sp.Bytes(Val.AsHeap(v) + Obj.StrData, Obj.Len(rt.gc.sp, Val.AsHeap(v)));
            outv.Write(sb, 0, sb.Length);
        } else if (t == Obj.TyRope) {
            long cached = rt.Slot(v, RP_FLAT);
            if (!Val.IsNil(cached)) { AppendBytes(rt, cached, outv); return; }
            int n = RopeKids(rt, v);
            for (int i = 0; i < n; i++) AppendBytes(rt, rt.Slot(v, RP_KIDS + i), outv);
        }
    }

    /// Contiguous bytes for a string of any tier. Materialises a rope ONCE and
    /// remembers it: `0011`'s rule is to count the flattens rather than hope
    /// about them, because a rope that flattens on every `index-of` passes
    /// every correctness test and is slower than the flat string it replaced.
    public static long Flatten(Rt rt, long v) {
        if (!IsRope(rt, v)) return v;
        long cached = rt.Slot(v, RP_FLAT);
        if (!Val.IsNil(cached)) return cached;
        var ms = new System.IO.MemoryStream(SBytes(rt, v));
        int bas = rt.Mark();
        int vi = rt.Push(v);
        AppendBytes(rt, rt.R(vi), ms);
        long flat = Of(rt, Encoding.UTF8.GetString(ms.ToArray()));
        long vv = rt.R(vi);
        rt.PopTo(bas);
        if (Val.IsHeap(vv) && !Val.IsNil(flat)) rt.SetSlot(Val.AsHeap(vv), RP_FLAT, flat);
        return flat;
    }

    /// Byte length. NOT the code-point count -- see the class comment.
    public static int ByteLen(Rt rt, long v) => SBytes(rt, v);
}
