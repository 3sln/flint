using System.Text;

namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Interns;

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
    /// A LONG NON-ASCII STRING ARRIVES AS A TREE. The tier transitions fire on
    /// CONCATENATION, so a string that arrives whole from outside never became
    /// a rope however big it was -- and `0011` then says, correctly, that "a
    /// flat string carries one total count, which does not locate code point
    /// k". Nothing covered a flat string that is not ASCII, and every reader in
    /// the language indexes by code point, so one non-ASCII character in a
    /// 115 KB document made the whole document quadratic.
    ///
    /// ASCII strings stay flat: for them a code-point index IS a byte index.
    static long RawString(Rt rt, byte[] b) {
        bool ascii = true;
        foreach (byte x in b) if ((x & 0x80) != 0) { ascii = false; break; }
        if (!ascii && b.Length > FLAT_MAX) return IndexedString(rt, b);
        return FlatString(rt, b, ascii);
    }

    /// One contiguous run. The leaf tier.
    static long FlatString(Rt rt, byte[] b, bool ascii) {
        long a = rt.Alloc(Obj.TyStr, b.Length);
        if (a == 0) return Val.Nil;
        rt.gc.sp.WriteBytes(a + Obj.StrData, b);
        Obj.SetStrHash(rt.gc.sp, a, 0);
        Obj.SetStrAscii(rt.gc.sp, a, ascii);
        return Val.Heap(a);
    }

    /// A string that is GUARANTEED contiguous: inline, or one flat run.
    ///
    /// The difference from `Of` is the whole of the tiering, and it has to be
    /// said out loud at the call site: `Flatten` used to build its result with
    /// `Of`, and once `Of` started answering a TREE for a long non-ASCII input,
    /// flattening a rope produced another rope.
    public static long Contiguous(Rt rt, byte[] b) {
        if (b.Length <= Val.InlineMax) return Val.InlineStr(b);
        bool ascii = true;
        foreach (byte x in b) if ((x & 0x80) != 0) { ascii = false; break; }
        return FlatString(rt, b, ascii);
    }

    /// A balanced tree over `INDEX_LEAF`-sized pieces, split on code-point
    /// boundaries. The node array IS the sparse code-point index.
    static long IndexedString(Rt rt, byte[] b) {
        int bas = rt.Mark();
        int n = 0, start = 0;
        while (start < b.Length) {
            // Split on a CHARACTER boundary at or before the limit: a leaf that
            // ended mid-code-point would make every count downstream wrong.
            int end = System.Math.Min(start + INDEX_LEAF, b.Length);
            while (end > start && (b[end - 1] & 0xC0) == 0x80) end--;
            if (end == start) end = System.Math.Min(start + INDEX_LEAF, b.Length);
            var piece = new byte[end - start];
            System.Array.Copy(b, start, piece, 0, end - start);
            bool pa = true;
            foreach (byte x in piece) if ((x & 0x80) != 0) { pa = false; break; }
            long leaf = FlatString(rt, piece, pa);
            if (Val.IsNil(leaf)) { rt.PopTo(bas); return Val.Nil; }
            rt.Push(leaf);
            n++;
            start = end;
        }
        long v = RopeFromRoots(rt, bas, n);
        rt.PopTo(bas);
        return v;
    }

    /// A balanced tree over `n` leaves on the shadow stack from `bas`, built
    /// bottom-up in `FANOUT` groups so it is balanced by construction.
    static long RopeFromRoots(Rt rt, int bas, int n) {
        if (n == 0) return Of(rt, "");
        if (n == 1) return rt.R(bas);
        int level = n, from = bas;
        for (;;) {
            if (level == 1) return rt.R(from);
            int outAt = rt.Mark();
            int made = 0, i = 0;
            while (i < level) {
                int take = System.Math.Min(FANOUT, level - i);
                long[] kids = new long[take];
                for (int k = 0; k < take; k++) kids[k] = rt.R(from + i + k);
                long node = RopeNode(rt, kids);
                if (Val.IsNil(node)) return Val.Nil;
                rt.Push(node);
                made++;
                i += take;
            }
            from = outAt;
            level = made;
        }
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
            if (NeedsGrow(t)) {
                t.Grow();
                t.Lookup(h, x => false);   // `Grow` invalidates the index
                idx = t.slot;
            }
            InsertAt(t, idx, h, rt.R(vi));
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
    /// The character at code point `i`, or `dflt`. See the JVM's `nth`.
    public static long Nth(Rt rt, long v, int i, long dflt) {
        var outb = new byte[4];
        int w = CpBytesAt(rt, v, i, outb);
        if (w < 0) return dflt;
        var one = new byte[w];
        System.Array.Copy(outb, 0, one, 0, w);
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

    /// The byte offset of code point `k` in a rope, BY DESCENDING its per-node
    /// code-point counts.
    ///
    /// `doc/decisions/0011` designed those counts for exactly this and nothing
    /// used them: every indexing path flattened first, so the counts were
    /// computed, stored, traced by the collector, and thrown away before the
    /// one question they answer.
    public static int RopeByteOfCpPublic(Rt rt, long v, int k) => RopeByteOfCp(rt, v, k);

    static int RopeByteOfCp(Rt rt, long v, int k) {
        long node = v;
        int want = k, byteAt = 0;
        for (;;) {
            if (!IsRope(rt, node)) {
                int n = SCount(rt, node);
                if (want >= n) return -1;
                if (SAscii(rt, node)) return byteAt + want;
                byte[] b = Bytes(rt, node);
                int at = 0;
                for (int c2 = 0; c2 < want; c2++) at += Utf8Width(b[at]);
                rt.ChargeBytes(at);
                return byteAt + at;
            }
            int nk = RopeKids(rt, node), i = 0;
            for (;;) {
                if (i >= nk) return -1;
                long kid = rt.Slot(node, RP_KIDS + i);
                int c = SCount(rt, kid);
                if (want < c) { node = kid; break; }
                want -= c;
                byteAt += SBytes(rt, kid);
                i++;
            }
            rt.ChargeWork(i + 1);
        }
    }

    /// The bytes of the code point at byte offset `byteAt`, from whichever leaf
    /// holds it. Returns the width, with the bytes written into `outb`.
    static int RopeBytesAt(Rt rt, long v, int byteAt, byte[] outb) {
        long node = v;
        int want = byteAt;
        for (;;) {
            if (!IsRope(rt, node)) {
                byte[] b = Bytes(rt, node);
                int w = Utf8Width(b[want]);
                System.Array.Copy(b, want, outb, 0, w);
                return w;
            }
            int nk = RopeKids(rt, node), i = 0;
            for (;;) {
                if (i >= nk) return 0;
                long kid = rt.Slot(node, RP_KIDS + i);
                int n = SBytes(rt, kid);
                if (want < n) { node = kid; break; }
                want -= n;
                i++;
            }
        }
    }

    /// The bytes of the code point at index `i`, or -1.
    ///
    /// WHICH MECHANISM, AND WHY -- `0011` calls them complementary and this is
    /// the line where that is acted on. ASCII: flatten once and index by byte,
    /// which is O(1) forever after; descending instead costs O(depth) EVERY
    /// time and made a linear operation superlinear. Non-ASCII: descend, and
    /// never flatten, because there is no byte index to have and a flat run
    /// leaves nothing but the scan that was the quadratic.
    ///
    /// This replaced a one-entry cursor. The cursor had to be invalidated by
    /// the collector, so the same walk cost different GAS depending on when a
    /// collection happened -- and under parallel executors that collection
    /// belongs to another thread. `doc/decisions/0009` says gas is
    /// deterministic; a memo keyed on collector state is not.
    static int CpBytesAt(Rt rt, long v, int i, byte[] outb) {
        if (i < 0) return -1;
        if (IsRope(rt, v) && !SAscii(rt, v)) {
            int at2 = RopeByteOfCp(rt, v, i);
            if (at2 < 0) return -1;
            int w2 = RopeBytesAt(rt, v, at2, outb);
            return w2 == 0 ? -1 : w2;
        }
        long flat = Flatten(rt, v);
        byte[] b = Bytes(rt, flat);
        int at;
        if (IsAscii(rt, flat)) {
            at = i;
        } else {
            // A flat non-ASCII run, bounded by `INDEX_LEAF`: anything longer
            // arrives as a tree, so this scan is O(INDEX_LEAF), not O(n).
            at = 0;
            for (int k = 0; k < i; k++) {
                if (at >= b.Length) return -1;
                at += Utf8Width(b[at]);
            }
            rt.ChargeBytes(at);
        }
        if (at >= b.Length) return -1;
        int w = Utf8Width(b[at]);
        System.Array.Copy(b, at, outb, 0, w);
        return w;
    }

    /// The CODE POINT at index `i`, or -1 when `i` is past the end.
    ///
    /// Separate from `Nth` so the reader does not build a one-character string
    /// per character just to ask what it is.
    public static int CodePointAt(Rt rt, long v, int i) {
        var outb = new byte[4];
        int w = CpBytesAt(rt, v, i, outb);
        if (w < 0) return -1;
        return char.ConvertToUtf32(Encoding.UTF8.GetString(outb, 0, w), 0);
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

    // `RP_HASH` is the subtree's content hash, or nil until asked. Hashing a
    // rope used to FLATTEN it to reach the flat string's cached hash, which
    // bought the caching by spending the sharing (`doc/decisions/0011`).
    public const int RP_BYTES = 0, RP_CPS = 1, RP_FLAT = 2, RP_HASH = 3, RP_KIDS = 4;
    public const int FLAT_MAX = 1024, FANOUT = 16;
    /// A slice smaller than this COPIES rather than sharing, so a small `subs`
    /// cannot retain a large parent -- the retention fix, not a performance
    /// choice (`doc/decisions/0011`).
    public const int SLICE_MIN = 256;

    /// LEAF SIZE FOR A STRING THAT ARRIVES NON-ASCII AND WHOLE. See
    /// `IndexedString`: the tree's node array is the sparse code-point index,
    /// and this is the bound on the scan inside one leaf.
    public const int INDEX_LEAF = 128;

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

    /// Bytes `[from, to)` appended WITHOUT materialising the tree.
    static void AppendRange(Rt rt, long v, int from, int to, System.IO.MemoryStream outv) {
        if (from >= to) return;
        if (!IsRope(rt, v)) {
            byte[] bs = Bytes(rt, v);
            int hi = System.Math.Min(to, bs.Length), lo = System.Math.Min(from, hi);
            outv.Write(bs, lo, hi - lo);
            return;
        }
        int n = RopeKids(rt, v), at = 0;
        for (int i = 0; i < n; i++) {
            long k = rt.Slot(v, RP_KIDS + i);
            int w = SBytes(rt, k);
            if (at + w > from && at < to) AppendRange(rt, k, System.Math.Max(0, from - at), to - at, outv);
            at += w;
            if (at >= to) return;
        }
    }

    /// A slice that SHARES its interior: a child wholly inside the range comes
    /// back unchanged, and only the two edge children are cut.
    public static long RopeSlice(Rt rt, long v, int from, int to) {
        if (from >= to) return Of(rt, "");
        int n = SBytes(rt, v);
        if (to > n) to = n;
        if (from == 0 && to == n) return v;
        if (to - from < SLICE_MIN || !IsRope(rt, v)) {
            var ms = new System.IO.MemoryStream();
            AppendRange(rt, v, from, to, ms);
            return Of(rt, System.Text.Encoding.UTF8.GetString(ms.ToArray()));
        }
        int bas = rt.Mark();
        int vi = rt.Push(v);
        int kids = RopeKids(rt, rt.R(vi));
        int outb = rt.Mark();
        int made = 0, at = 0;
        for (int i = 0; i < kids; i++) {
            long k = rt.Slot(rt.R(vi), RP_KIDS + i);
            int w = SBytes(rt, k);
            if (at + w > from && at < to) {
                int lo = System.Math.Max(0, from - at);
                int hi = System.Math.Min(to - at, w);
                long piece = (lo == 0 && hi == w) ? k : RopeSlice(rt, k, lo, hi);
                if (piece == Val.Nil) { rt.PopTo(bas); return Val.Nil; }
                if (SBytes(rt, piece) > 0) { rt.Push(piece); made++; }
            }
            at += w;
            if (at >= to) break;
        }
        long r = RopeFromRoots(rt, outb, made);
        rt.PopTo(bas);
        return r;
    }

    public static int Pow31Public(int n) => Pow31(n);

    static int Pow31(int n) {
        int b = 31, acc = 1;
        while (n > 0) {
            if ((n & 1) == 1) acc *= b;
            b *= b;
            n >>= 1;
        }
        return acc;
    }

    /// The content hash of a string tree without materialising it, cached per
    /// node. `h(A.B) = h(A)*31^|B| + h(B)`, over BYTES so it agrees with the
    /// flat hash.
    public static int RopeHash(Rt rt, long v) {
        if (!IsRope(rt, v)) {
            byte[] bs = Bytes(rt, v);
            int h0 = 0;
            foreach (byte b in bs) h0 = h0 * 31 + b;
            return h0;
        }
        long cached = rt.Slot(v, RP_HASH);
        if (Val.IsFixnum(cached)) return (int) Val.AsFixnum(cached);
        int bas = rt.Mark();
        int vi = rt.Push(v);
        int kids = RopeKids(rt, rt.R(vi));
        int h = 0;
        for (int i = 0; i < kids; i++) {
            long k = rt.Slot(rt.R(vi), RP_KIDS + i);
            int ki = rt.Push(k);
            int kh = RopeHash(rt, rt.R(ki));
            int kb = SBytes(rt, rt.R(ki));
            h = h * Pow31(kb) + kh;
            rt.PopTo(ki);
        }
        rt.SetSlot(Val.AsHeap(rt.R(vi)), RP_HASH, Val.Fixnum(h));
        rt.PopTo(bas);
        return h;
    }

    /// Content equality over two trees without materialising either: stops at
    /// the first mismatch, and short-circuits on NODE IDENTITY.
    public static bool TreeEq(Rt rt, long a, long b) {
        if (a == b) return true;
        var sa = new System.Collections.Generic.List<long[]>();
        var sb = new System.Collections.Generic.List<long[]>();
        sa.Add(new long[]{a, 0});
        sb.Add(new long[]{b, 0});
        byte[] la = System.Array.Empty<byte>(), lb = System.Array.Empty<byte>();
        int pa = 0, pb = 0;
        while (true) {
            if (pa == la.Length) {
                long nv = WalkNext(rt, sa);
                if (nv == Val.NotFound) break;
                if (pb == lb.Length && sb.Count > 0) {
                    var peek = CopyStack(sb);
                    long w = WalkNext(rt, peek);
                    if (w != Val.NotFound && w == nv) {
                        sb = peek; la = System.Array.Empty<byte>(); lb = System.Array.Empty<byte>();
                        pa = 0; pb = 0;
                        continue;
                    }
                }
                la = Bytes(rt, nv); pa = 0;
            }
            if (pb == lb.Length) {
                long nv = WalkNext(rt, sb);
                if (nv == Val.NotFound) break;
                lb = Bytes(rt, nv); pb = 0;
            }
            int n = System.Math.Min(la.Length - pa, lb.Length - pb);
            if (n == 0) continue;
            for (int i = 0; i < n; i++) if (la[pa + i] != lb[pb + i]) return false;
            pa += n; pb += n;
        }
        return pa == la.Length && pb == lb.Length
            && WalkNext(rt, sa) == Val.NotFound && WalkNext(rt, sb) == Val.NotFound;
    }

    static System.Collections.Generic.List<long[]> CopyStack(System.Collections.Generic.List<long[]> s) {
        var o = new System.Collections.Generic.List<long[]>();
        foreach (var e in s) o.Add(new long[]{e[0], e[1]});
        return o;
    }

    static long WalkNext(Rt rt, System.Collections.Generic.List<long[]> stack) {
        while (stack.Count > 0) {
            var top = stack[stack.Count - 1];
            long node = top[0];
            int i = (int) top[1];
            if (!IsRope(rt, node)) { stack.RemoveAt(stack.Count - 1); return node; }
            int kids = RopeKids(rt, node);
            if (i >= kids) { stack.RemoveAt(stack.Count - 1); continue; }
            top[1] = i + 1;
            stack.Add(new long[]{rt.Slot(node, RP_KIDS + i), 0});
        }
        return Val.NotFound;
    }

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
        rt.SetSlot(a, RP_HASH, Val.Nil);
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
        // Append down the RIGHT SPINE, adding a level only when every node on it
        // is full. This used to widen the root while it had room and otherwise
        // make `[a, b]` -- which put the whole old tree back as kid 0 and grew
        // the depth by one every FANOUT appends, so depth was O(n) rather than
        // O(log n). It never showed because nothing indexed a rope: every path
        // flattened first.
        long appended = RopeAppend(rt, a, b);
        if (!Val.IsNil(appended)) return appended;
        int bas2 = rt.Mark();
        int a2 = rt.Push(a), b2 = rt.Push(b);
        // A new level: `b` is lifted to stand as tall as the old root.
        int h2 = RopeHeight(rt, rt.R(a2));
        int l2 = rt.Push(RopeLift(rt, rt.R(b2), h2));
        long outv2 = RopeNode(rt, new long[]{ rt.R(a2), rt.R(l2) });
        rt.PopTo(bas2);
        return outv2;
    }

    /// How many levels of node sit above the leaves. A leaf is 0.
    static int RopeHeight(Rt rt, long v) =>
        IsRope(rt, v) ? 1 + RopeHeight(rt, rt.Slot(v, RP_KIDS)) : 0;

    /// Wrap `v` in single-kid nodes until it stands `h` levels tall, which is
    /// what keeps every leaf at the SAME depth.
    static long RopeLift(Rt rt, long v, int h) {
        int bas = rt.Mark();
        int vi = rt.Push(v);
        for (int i = 0; i < h; i++) {
            long n = RopeNode(rt, new long[]{ rt.R(vi) });
            if (Val.IsNil(n)) { rt.PopTo(bas); return Val.Nil; }
            rt.SetR(vi, n);
        }
        long outv = rt.R(vi);
        rt.PopTo(bas);
        return outv;
    }

    /// Append `b` into the rightmost subtree of `a` that has room, rebuilding
    /// the spine above it. Nil when the right spine is full at every level.
    static long RopeAppend(Rt rt, long a, long b) {
        if (!IsRope(rt, a)) return Val.Nil;
        int n = RopeKids(rt, a);
        int bas = rt.Mark();
        int ai = rt.Push(a), bi = rt.Push(b);
        int li = rt.Push(rt.Slot(rt.R(ai), RP_KIDS + n - 1));
        // Deepest first: room further down costs no depth at all.
        long deeper = IsRope(rt, rt.R(li)) ? RopeAppend(rt, rt.R(li), rt.R(bi)) : Val.Nil;
        long outv;
        if (!Val.IsNil(deeper)) {
            int ni = rt.Push(deeper);
            long[] kids = new long[n];
            for (int i = 0; i < n - 1; i++) kids[i] = rt.Slot(rt.R(ai), RP_KIDS + i);
            kids[n - 1] = rt.R(ni);
            outv = RopeNode(rt, kids);
        } else if (n < FANOUT) {
            // Full below, room here: `b` joins as a sibling, LIFTED to the
            // height its siblings stand at.
            int h = RopeHeight(rt, rt.R(li));
            long lifted = RopeLift(rt, rt.R(bi), h);
            if (Val.IsNil(lifted)) { rt.PopTo(bas); return Val.Nil; }
            int ni = rt.Push(lifted);
            long[] kids = new long[n + 1];
            for (int i = 0; i < n; i++) kids[i] = rt.Slot(rt.R(ai), RP_KIDS + i);
            kids[n] = rt.R(ni);
            outv = RopeNode(rt, kids);
        } else {
            outv = Val.Nil;
        }
        rt.PopTo(bas);
        return outv;
    }

    static long CopyConcat(Rt rt, long a, long b) {
        // Copying is work, charged at the same rate everywhere.
        rt.ChargeBytes(SBytes(rt, a) + SBytes(rt, b));
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
        // Flattening copies the whole tree, and REFUSES when the budget cannot
        // cover it: `n` is known, so the charge goes up front.
        if (!rt.ChargeChecked((SBytes(rt, v) / 8) + 1, "flatten")) return Val.Nil;
        var ms = new System.IO.MemoryStream(SBytes(rt, v));
        int bas = rt.Mark();
        int vi = rt.Push(v);
        AppendBytes(rt, rt.R(vi), ms);
        // CONTIGUOUS, not `Of`: `Of` tiers, and a flatten that produced a tree
        // would put a tree in `RP_FLAT`.
        long flat = Contiguous(rt, ms.ToArray());
        long vv = rt.R(vi);
        rt.PopTo(bas);
        if (Val.IsHeap(vv) && !Val.IsNil(flat)) rt.SetSlot(Val.AsHeap(vv), RP_FLAT, flat);
        return flat;
    }

    /// Byte length. NOT the code-point count -- see the class comment.
    public static int ByteLen(Rt rt, long v) => SBytes(rt, v);
}
