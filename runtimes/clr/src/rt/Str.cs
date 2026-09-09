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
            // `b[end]`, NOT `b[end - 1]` -- see the Java copy for the character
            // this used to split, and for why `count` stayed right while every
            // indexed read of that character did not.
            while (end > start && end < b.Length && (b[end] & 0xC0) == 0x80) end--;
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
    static long RopeFromRoots(Rt rt, int bas, int n) { return global::_3sln.Flint.Kgen.Rt.Ropenode.RopeFromRoots(rt, bas, n); }

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
    /// Clojure.
    ///
    /// This used to ask for CONTIGUOUS BYTES and count the non-continuations
    /// among them, which walks a rope into a fresh host array on every call.
    /// It did not flatten -- the RP_FLAT slot stayed nil, so nothing was
    /// cached and the walk was paid again each time. A rope of 180,900
    /// characters, counted 20,000 times: 728ms before, 2ms after, same
    /// answer.
    ///
    /// The same generated caller reached an O(1) slot read on native the
    /// whole time, because native's `char_count` is `s_count`. Two functions
    /// for one meaning, and the ports had the worse one.
    ///
    /// No gas moved: nothing on that walk charged, so `count` cost the same
    /// gas on every runtime and only the wall clock differed.

    static bool IsAscii(Rt rt, long v) => SAscii(rt, v);

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

    /// The byte offset of code point `k` in a rope, BY DESCENDING its per-node
    /// code-point counts.
    ///
    /// `doc/decisions/0011` designed those counts for exactly this and nothing
    /// used them: every indexing path flattened first, so the counts were
    /// computed, stored, traced by the collector, and thrown away before the
    /// one question they answer.

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
        if (IsRope(rt, v)) {
            // ASCII INCLUDED, and it used not to be -- see the JVM's
            // `cpBytesAt` for why and for the measurement. Native has descended
            // for every rope since `0011`; flattening here turns an O(log n)
            // descent into an O(n) copy and caches the flat form.
            //
            // PAST THE END NEEDS NO CHECK HERE -- see the Java and Rust copies.
            int at2 = SAscii(rt, v) ? i : RopeByteOfCp(rt, v, i);
            int sk = rt.SinkOpen();
            int w2 = RopeBytesAt(rt, v, at2, sk);
            byte[] got = rt.SinkArray(sk);
            for (int j = 0; j < w2; j++) outb[j] = got[j];
            rt.SinkClose(sk);
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
                at += Utf8Width(rt, b[at]);
            }
            rt.ChargeBytes(at);
        }
        if (at >= b.Length) return -1;
        int w = Utf8Width(rt, b[at]);
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
            int sk0 = rt.SinkOpen();
            global::_3sln.Flint.Kgen.Rt.Ropeflat.SAppend(rt, v, sk0);
            byte[] got = rt.SinkArray(sk0);
            rt.SinkClose(sk0);
            ms.Write(got, 0, got.Length);
            return ms.ToArray();
        }
        long a = Val.AsHeap(v);
        return rt.gc.sp.Bytes(a + Obj.StrData, Obj.Len(rt.gc.sp, a));
    }

    public static string Text(Rt rt, long v) => Encoding.UTF8.GetString(Bytes(rt, v));


    /// Is `v` a string -- any of the three tiers? GENERATED, from
    /// `kin/ropemeas.kin`. A delegator rather than a copy -- see the Java one.
    public static bool IsString(Rt rt, long v) =>
        global::_3sln.Flint.Kgen.Rt.Ropemeas.IsString(rt, v);

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

    static int HashKeywordOf(Rt rt, string ns, string name) {
        return Hash.HashKeyword(ns == null ? null : System.Text.Encoding.UTF8.GetBytes(ns),
                                System.Text.Encoding.UTF8.GetBytes(name));
    }

    /// Two strings in UTF-16 CODE UNIT order, across all three tiers.
    /// `Eq.Compare` did this inline; it is a name now because the generated
    /// `compare` needs one, and because native was reading a rope's SLOTS as
    /// UTF-8 where this materialises.
    public static int CompareUtf16(Rt rt, long a, long b) {
        return Eq.Utf16Cmp(Text(rt, a), Text(rt, b));
    }

    /// The hash of a STRING value, cached in the object for a heap string.
    ///
    /// The cache is `StrHash`, which this port WROTE during interning and
    /// never read: every `Hash` of a heap string rehashed its whole content,
    /// where native reads the slot. A long string used as a map key paid its
    /// length per lookup.
    public static int StringHash(Rt rt, long v) {
        if (Val.IsInlineStr(v)) return Hash.HashString(Val.InlineBytes(v));
        long a = Val.AsHeap(v);
        int cached = Obj.StrHash(rt.gc.sp, a);
        if (cached != 0) return cached;
        int h = Hash.HashString(Bytes(rt, v));
        if (h == 0) h = 1;
        Obj.SetStrHash(rt.gc.sp, a, h);
        return h;
    }

    /// The hash of a KEYWORD, read from slot 2 where it was stored when the
    /// keyword was built.
    public static int KeywordHash(Rt rt, long v) {
        if (Val.IsInlineKw(v)) return Hash.HashKeyword(null, Val.InlineBytes(v));
        return (int) Val.AsFixnum(rt.Slot(v, 2));
    }

    static long BuildKeyword(Rt rt, string ns, string name) {
        int bas = rt.Mark();
        int nsi = rt.Push(ns == null ? Val.Nil : Of(rt, ns));
        int nmi = rt.Push(Of(rt, name));
        long a = rt.Alloc(Obj.TyKw, 3);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, 0, rt.R(nsi));
        rt.SetSlot(a, 1, rt.R(nmi));
        // THE HASH, and it used to be Nil. Native has stored it here since the
        // slot existed and reads it back in `HashValue`; this port left the
        // slot empty and recomputed from the ns and name bytes on EVERY hash.
        // A keyword is the commonest map key there is.
        rt.SetSlot(a, 2, Val.Fixnum(HashKeywordOf(rt, ns, name)));
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
        rt.SetSlot(a, 2, Val.Nil);  // meta
        // THE HASH, and it used to be Nil -- the same empty cache the keyword
        // slot had. `SymbolHash` reads it; this port recomputed from the ns
        // and name bytes on every hash instead.
        rt.SetSlot(a, 3, Val.Fixnum(Hash.HashSymbol(
            ns == null ? null : System.Text.Encoding.UTF8.GetBytes(ns),
            System.Text.Encoding.UTF8.GetBytes(name))));
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

    public static bool IsRope(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Ropecat.IsRope(rt, v);

    static int RopeKids(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Ropecat.RopeKids(rt, v);

    /// A slice that SHARES its interior: a child wholly inside the range comes
    /// back unchanged, and only the two edge children are cut.
    public static long RopeSlice(Rt rt, long v, int from, int to) { return global::_3sln.Flint.Kgen.Rt.Ropeslice.RopeSlice(rt, v, from, to); }

    public static int Pow31Public(Rt rt, int n) => global::_3sln.Flint.Kgen.Rt.Bytehash.Pow31(rt, n);


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
    /// A node over the `n` values ALREADY ROOTED at `bas`. The caller pushes
    /// and the caller pops -- see the Rust and Java copies, which say the same.
    static long RopeNode(Rt rt, int bas, int n) { return global::_3sln.Flint.Kgen.Rt.Ropenode.RopeNode(rt, bas, n); }

    /// `str` of two strings. O(1) once the pieces are big enough to matter.
    public static long Concat(Rt rt, long a, long b) { return global::_3sln.Flint.Kgen.Rt.Ropecat.SConcat(rt, a, b); }

    /// How many levels of node sit above the leaves. A leaf is 0.
    static int RopeHeight(Rt rt, long v) => global::_3sln.Flint.Kgen.Rt.Ropecat.RopeHeight(rt, v);

    /// Wrap `v` in single-kid nodes until it stands `h` levels tall, which is
    /// what keeps every leaf at the SAME depth.
    static long RopeLift(Rt rt, long v, int h) { return global::_3sln.Flint.Kgen.Rt.Ropenode.RopeLift(rt, v, h); }

    /// Append `b` into the rightmost subtree of `a` that has room, rebuilding
    /// the spine above it. Nil when the right spine is full at every level.
    static long RopeAppend(Rt rt, long a, long b) { return global::_3sln.Flint.Kgen.Rt.Ropecat.RopeAppend(rt, a, b); }

    /// Copy the range out into a fresh string -- the DECODE half.
    public static long SCopyRange(Rt rt, long v, int from, int to) {
        int bas = rt.Mark();
        int vi = rt.Push(v);
        int s = rt.SinkOpen();
        global::_3sln.Flint.Kgen.Rt.Ropeflat.SAppendRange(rt, rt.R(vi), from, to, s);
        long outv = rt.SinkString(s);
        rt.SinkClose(s);
        rt.PopTo(bas);
        return outv;
    }

    static void SAppendRange(Rt rt, long v, int from, int to, int s) {
        global::_3sln.Flint.Kgen.Rt.Ropeflat.SAppendRange(rt, v, from, to, s);
    }

    /// The empty string, interned -- see the Rust copy.
    public static long SEmpty(Rt rt) { return Of(rt, ""); }

    public static long CopyConcat(Rt rt, long a, long b) {
        // Copying is work, charged at the same rate everywhere.
        rt.ChargeBytes(SBytes(rt, a) + SBytes(rt, b));
        byte[] x = Bytes(rt, a), y = Bytes(rt, b);
        byte[] both = new byte[x.Length + y.Length];
        System.Array.Copy(x, 0, both, 0, x.Length);
        System.Array.Copy(y, 0, both, x.Length, y.Length);
        return Of(rt, Encoding.UTF8.GetString(both));
    }

    /// Walk the leaves in order, appending their bytes.

    /// Contiguous bytes for a string of any tier. Materialises a rope ONCE and
    /// remembers it: `0011`'s rule is to count the flattens rather than hope
    /// about them, because a rope that flattens on every `index-of` passes
    /// every correctness test and is slower than the flat string it replaced.
    public static long Flatten(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Ropeflat.SFlatten(rt, v); }
    public static int RopeHash(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Ropeflat.RopeHash(rt, v); }
    public static bool TreeEq(Rt rt, long a, long b) { return global::_3sln.Flint.Kgen.Rt.Ropeeq.TreeEq(rt, a, b); }
    static int Utf8Width(Rt rt, int b0) { return global::_3sln.Flint.Kgen.Rt.Ropecp.Utf8Width(rt, b0); }
    public static int SBytes(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Ropemeas.SBytes(rt, v); }
    public static int SCount(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Ropemeas.SCount(rt, v); }
    public static bool SAscii(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Ropemeas.SAscii(rt, v); }
    public static int RopeByteOfCp(Rt rt, long v, int k) { return global::_3sln.Flint.Kgen.Rt.Ropecp.RopeByteOfCp(rt, v, k); }
    static int RopeBytesAt(Rt rt, long v, int at, int s) { return global::_3sln.Flint.Kgen.Rt.Ropecp.RopeBytesAt(rt, v, at, s); }

    /// The shortest decimal that reads back as `d`, as characters in `c`.
    ///
    /// The whole of what this runtime still decides about printing a double.
    /// Everything above it -- when to use an exponent, how to spell one, what
    /// to call an infinity -- is generated from `kin/dblstr.kin` and shared
    /// with the other two runtimes, which is why this port no longer has a
    /// `FmtDouble` of its own to disagree with them.
    internal static void F64Digits(Rt rt, int c, double d) {
        string s = d.ToString("R", System.Globalization.CultureInfo.InvariantCulture);
        for (int i = 0; i < s.Length; i++) rt.CpsPut(c, s[i]);
    }

    /// Byte length. NOT the code-point count -- see the class comment.
}
