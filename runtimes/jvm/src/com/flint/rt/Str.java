package com.flint.rt;

import java.nio.charset.StandardCharsets;

import static com.flint.rt.Obj.*;
import static com._3sln.flint.kgen.rt.Interns.*;
import com._3sln.flint.kgen.rt.Hashtext;

/// Strings, ported from `runtime/src/strs.rs`.
///
/// Three tiers, and the first two are here (`DECISIONS.md#strings-and-matching`'s ropes are
/// the third and come with the rest of that file):
///
/// * five bytes or fewer live IN the value -- no allocation, and a comparison
///   is one 64-bit compare;
/// * up to `INTERN_MAX` bytes, a `TY_STR` on the heap and INTERNED, so two
///   equal strings are one object and `=` is still a bit compare;
/// * longer than that, a plain heap string, where `=` compares bytes.
///
/// The heap forms cache their hash at +8 and carry an ASCII flag in the header.
///
/// The ASCII flag is not a micro-optimisation. flint indexes strings by CODE
/// POINT, so a byte index and a character index coincide only for ASCII --
/// without the flag `subs` and `nth` walk, and splitting a string was
/// quadratic. Before it existed the word-frequency benchmark took 762 ms
/// instead of 62.
public final class Str {
    /// NO NAMESPACE, as the hash sees it.
    ///
    /// `com.flint.rt.Hash` STOOD HERE AND IS GONE. It held `hashDouble`,
    /// `hashBytes`, `hashSymbol` and `hashKeyword`, written out by hand, and
    /// its own header said the CLR copy had to be kept in step with it because
    /// `String.hashCode()` "would leave the two ports computing the same
    /// number by different routes". All four are generated from
    /// `kin/hashtext.kin` now, and this constant is the one thing the move
    /// cost: the generated signature takes bytes rather than a nullable array,
    /// because Rust had no `null` to take.
    static final byte[] NO_NS = new byte[0];

    private Str() {}


    /// A bare, UNINTERNED heap string. `of` is the canonical constructor.
    ///
    /// A LONG NON-ASCII STRING ARRIVES AS A TREE. The tier transitions fire on
    /// CONCATENATION, so a string that arrives whole from outside never became
    /// a rope however big it was -- and `strings-and-matching` then says, correctly, that "a
    /// flat string carries one total count, which does not locate code point
    /// k". Nothing covered a flat string that is not ASCII, and every reader in
    /// the language indexes by code point, so one non-ASCII character in a
    /// 115 KB document made the whole document quadratic.
    ///
    /// ASCII strings stay flat: for them a code-point index IS a byte index,
    /// so there is nothing to locate and a tree would be pure overhead.
    static long rawString(Rt rt, byte[] b) {
        boolean ascii = true;
        for (byte x : b) if ((x & 0x80) != 0) { ascii = false; break; }
        if (!ascii && b.length > FLAT_MAX) return indexedString(rt, b);
        return flatString(rt, b, ascii);
    }

    /// One contiguous run. The leaf tier.
    static long flatString(Rt rt, byte[] b, boolean ascii) {
        long a = rt.alloc(TY_STR, b.length);
        if (a == 0) return Val.NIL;
        rt.gc.sp.writeBytes(a + STR_DATA, b);
        setStrHash(rt.gc.sp, a, 0);
        setStrAscii(rt.gc.sp, a, ascii);
        return Val.heap(a);
    }

    /// A string that is GUARANTEED contiguous: inline, or one flat run.
    ///
    /// The difference from `of` is the whole of the tiering, and it has to be
    /// said out loud at the call site: `flatten` used to build its result with
    /// `of`, and once `of` started answering a TREE for a long non-ASCII input,
    /// flattening a rope produced another rope -- so every caller that
    /// flattened precisely to get contiguous bytes was handed something that
    /// was not.
    public static long contiguous(Rt rt, byte[] b) {
        if (b.length <= Val.INLINE_MAX) return Val.inlineStr(b);
        boolean ascii = true;
        for (byte x : b) if ((x & 0x80) != 0) { ascii = false; break; }
        return flatString(rt, b, ascii);
    }

    /// A balanced tree over `INDEX_LEAF`-sized pieces, split on code-point
    /// boundaries.
    ///
    /// The node array IS the sparse code-point index: each node carries its
    /// subtree's code-point count, so locating code point `k` is a descent plus
    /// a scan bounded by one leaf. Both are properties of the string, which is
    /// what makes the cost PREDICTABLE -- it does not depend on what was
    /// indexed before, and it does not change because another executor
    /// collected.
    static long indexedString(Rt rt, byte[] b) {
        int base = rt.mark();
        int n = 0, start = 0;
        while (start < b.length) {
            // Split on a CHARACTER boundary at or before the limit: a leaf that
            // ended mid-code-point would make every count downstream wrong.
            int end = Math.min(start + INDEX_LEAF, b.length);
            // `b[end]`, NOT `b[end - 1]`. The question is whether the leaf ENDS
            // on a boundary, and that is a fact about the byte that would BEGIN
            // the next one. Asking about `b[end - 1]` backs up off a boundary
            // that was already good: a two-byte character at 126-127 left this
            // cutting at 127, between the lead byte and its continuation, so
            // every indexed read of that character answered a replacement
            // character while `count` stayed right -- because an orphan lead
            // still counts as one code point and its orphan continuations
            // count as none. The end of the string is a boundary by definition.
            while (end > start && end < b.length && (b[end] & 0xC0) == 0x80) end--;
            // Valid UTF-8 backs up at most three bytes, so this can only fire on
            // malformed input. Taking the whole chunk keeps the loop finite.
            if (end == start) end = Math.min(start + INDEX_LEAF, b.length);
            byte[] piece = java.util.Arrays.copyOfRange(b, start, end);
            boolean pa = true;
            for (byte x : piece) if ((x & 0x80) != 0) { pa = false; break; }
            long leaf = flatString(rt, piece, pa);
            if (Val.isNil(leaf)) { rt.popTo(base); return Val.NIL; }
            rt.push(leaf);
            n++;
            start = end;
        }
        long v = ropeFromRoots(rt, base, n);
        rt.popTo(base);
        return v;
    }

    /// A balanced tree over `n` leaves sitting on the shadow stack from `base`.
    ///
    /// Built bottom-up in `FANOUT` groups, so the result is balanced by
    /// construction rather than by rebalancing afterwards -- which matters
    /// because the depth is what every index pays.
    static long ropeFromRoots(Rt rt, int base, int n) { return com._3sln.flint.kgen.rt.Ropenode.ropeFromRoots(rt, base, n); }

    /// The CANONICAL value for a string.
    ///
    /// Three tiers (`DECISIONS.md#strings-and-matching`): inline up to 5 bytes, interned up to
    /// `INTERN_MAX`, and plain heap beyond. Inline is canonical by
    /// construction and interning is guaranteed in its range, so two strings in
    /// either range are `=` exactly when they are bit-equal. Past the range a
    /// string is still correct, just not canonical, and `eq` compares bytes.
    public static long of(Rt rt, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= Val.INLINE_MAX) return Val.inlineStr(b);
        if (b.length > Interns.INTERN_MAX) return rawString(rt, b);
        // THE SAME WALK THE VALUE HASH USES; see the note in the Rust's
        // `intern_string`. An interned string carries its hash in the header,
        // set here, so changing `stringHash` alone left two bases in play.
        int h = Hashtext.hashBytes(b);
        long found = probe(rt, Interns.STR, h,
            v -> Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_STR
                 && sameBytes(bytes(rt, v), b));
        if (found != Val.NOT_FOUND) return found;

        // Allocated OUTSIDE the probe, which is what keeps interning off the
        // allocation path -- and the table is re-probed below, because the
        // allocation can COLLECT and the collector rewrites this very table.
        long v = rawString(rt, b);
        if (Val.isNil(v)) return v;
        setStrHash(rt.gc.sp, Val.asHeap(v), h == 0 ? 1 : h);
        return publish(rt, Interns.STR, h, v,
                       x -> Val.isHeap(x) && ty(rt.gc.sp, Val.asHeap(x)) == TY_STR
                            && sameBytes(bytes(rt, x), b));
    }

    static boolean sameBytes(byte[] x, byte[] y) {
        return java.util.Arrays.equals(x, y);
    }

    /// Publish a freshly built value, or take the one that was already there.
    ///
    /// The RE-PROBE is not an optimisation. Two interned copies of one string
    /// is a CORRECTNESS bug rather than a wasted allocation: `eq` reads "both
    /// interned and not bit-equal" as NOT EQUAL, so the copies would compare
    /// unequal while reading identically -- and symbol equality is slot
    /// equality on those same strings, so it would spread.
    ///
    /// This exists once rather than three times on purpose. The Rust records
    /// that `string`, `keyword` and `symbol` had the same four lines and the
    /// same rooting bug before: a protocol with three copies is a protocol with
    /// three chances to diverge.
    static long publish(Rt rt, int table, int h, long v, Interns.Match matches) {
        // ROOTED across the lock. `lockIntern` can PARK -- that is the whole
        // point of it, so a thread waiting for the lock still reaches a
        // safepoint -- and parking means another executor may collect and move
        // `v` while this thread is stopped. A value in a host local does not
        // survive that.
        int base = rt.mark();
        int vi = rt.push(v);
        rt.roots.shared.par.lockIntern(table);
        try {
            Interns t = rt.roots.shared.interns[table];
            long again = t.lookup(h, matches);
            if (again != Val.NOT_FOUND) return again;   // somebody got there first
            int idx = t.slot;
            if (needsGrow(t)) {
                t.grow();
                // `grow` invalidates the index, so re-probe for a slot.
                t.lookup(h, x -> false);
                idx = t.slot;
            }
            insertAt(t, idx, h, rt.r(vi));
            return rt.r(vi);
        } finally {
            rt.roots.shared.par.unlockIntern(table);
            rt.popTo(base);
        }
    }

    /// Probe a table under its lock. Nothing needs rooting: no allocation
    /// happens, and the lock can only park before anything is live.
    static long probe(Rt rt, int table, int h, Interns.Match matches) {
        rt.roots.shared.par.lockIntern(table);
        try {
            return rt.roots.shared.interns[table].lookup(h, matches);
        } finally {
            rt.roots.shared.par.unlockIntern(table);
        }
    }

    public static byte[] bytes(Rt rt, long v) {
        if (Val.isInlineStr(v)) return Val.inlineBytes(v);
        if (isRope(rt, v)) {
            int sk = rt.sinkOpen();
            com._3sln.flint.kgen.rt.Ropeflat.sAppend(rt, v, sk);
            byte[] out = rt.sinkArray(sk);
            rt.sinkClose(sk);
            return out;
        }
        long a = Val.asHeap(v);
        return rt.gc.sp.bytes(a + STR_DATA, len(rt.gc.sp, a));
    }

    public static String text(Rt rt, long v) {
        return new String(bytes(rt, v), StandardCharsets.UTF_8);
    }


    /// Is `v` a string -- any of the three tiers? GENERATED, from
    /// `kin/ropemeas.kin`, which is also where the tier questions next to it
    /// are answered. A delegator rather than a copy: sixteen call sites in
    /// this runtime say `Str.isString`, and the generated tree says
    /// `isString`, and there is one body under both.
    public static boolean isString(Rt rt, long v) {
        return com._3sln.flint.kgen.rt.Ropemeas.isString(rt, v);
    }

    public static long keyword(Rt rt, String ns, String name) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (ns == null && nb.length > 0 && nb.length <= Val.INLINE_MAX) return Val.inlineKw(nb);
        byte[] nsb = ns == null ? null : ns.getBytes(StandardCharsets.UTF_8);
        // NO_NS, NOT `null`: the generated `hashKeyword` takes one `byte[]`
        // on every runtime, and an absent namespace is an EMPTY one --
        // `hashBytes` of no bytes is `hashInt(0)`, which is the 0 the old
        // `ns == null ? 0` supplied. `nsb` stays nullable because
        // `sameName` distinguishes an absent namespace from an empty one,
        // which the HASH deliberately does not.
        int h = Hashtext.hashKeyword(nsb == null ? NO_NS : nsb, nb);
        Interns.Match matches = v -> Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_KW
                                     && sameName(rt, v, nsb, nb);
        long found = probe(rt, Interns.KW, h, matches);
        if (found != Val.NOT_FOUND) return found;
        long built = buildKeyword(rt, ns, name);
        if (Val.isNil(built)) return built;
        return publish(rt, Interns.KW, h, built, matches);
    }

    /// True when `v`'s namespace and name are exactly these bytes. Compares
    /// CONTENT and not identity, because the strings inside a keyword may
    /// themselves be inline, interned or plain.
    static boolean sameName(Rt rt, long v, byte[] nsb, byte[] nb) {
        long vns = rt.slot(v, 0);
        if ((nsb == null) != Val.isNil(vns)) return false;
        if (nsb != null && !sameBytes(bytes(rt, vns), nsb)) return false;
        return sameBytes(bytes(rt, rt.slot(v, 1)), nb);
    }

    static int hashKeywordOf(Rt rt, String ns, String name) {
        return Hashtext.hashKeyword(ns == null ? NO_NS : ns.getBytes(StandardCharsets.UTF_8),
                                    name.getBytes(StandardCharsets.UTF_8));
    }

    /// GENERATED NOW, from `kin/ropecmp.kin`. This flattened both operands --
    /// `text` opens a sink and appends the whole rope -- and then compared
    /// UTF-16 code units to reproduce `String.compareTo`. UTF-8 byte order is
    /// already code point order, and `strings-and-matching` lists comparison among the
    /// operations that must WALK.

    /// The hash of a STRING value, cached in the object for a heap string.
    ///
    /// The cache is `strHash`, which this port WROTE during interning and
    /// never read: every `hash` of a heap string rehashed its whole content,
    /// where native reads the slot. A long string used as a map key paid its
    /// length per lookup.
    // @kin:link:ns: flint.rt.strs
    // @kin:link:form:string-hash: {:template "Str.stringHash({0}, {1})"}
    /// A 31-WALK OVER UTF-8 BYTES; see `Rt::string_hash` in the Rust for why
    /// it is not over UTF-16 units any more. In short: a tree already walked
    /// bytes, so the two tiers disagreed for non-ASCII past FLAT_MAX; bytes
    /// are what `pow31` composes for per-node caching; and Clojure's hash
    /// NUMBERS are not a contract it offers.
    public static int stringHash(Rt rt, long v) {
        if (Val.isInlineStr(v)) return Hashtext.hashBytes(Val.inlineBytes(v));
        long a = Val.asHeap(v);
        int cached = Obj.strHash(rt.gc.sp, a);
        if (cached != 0) return cached;
        int h = Hashtext.hashBytes(bytes(rt, v));
        if (h == 0) h = 1;
        Obj.setStrHash(rt.gc.sp, a, h);
        return h;
    }

    /// The hash of a KEYWORD, read from slot 2 where it was stored when the
    /// keyword was built.
    // @kin:link:form:keyword-hash: {:template "Str.keywordHash({0}, {1})"}
    public static int keywordHash(Rt rt, long v) {
        if (Val.isInlineKw(v)) return Hashtext.hashKeyword(NO_NS, Val.inlineBytes(v));
        return (int) Val.asFixnum(rt.slot(v, 2));
    }

    static long buildKeyword(Rt rt, String ns, String name) {
        int base = rt.mark();
        int nsi = rt.push(ns == null ? Val.NIL : of(rt, ns));
        int nmi = rt.push(of(rt, name));
        long a = rt.alloc(TY_KW, 3);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, 0, rt.r(nsi));
        rt.setSlot(a, 1, rt.r(nmi));
        // THE HASH, and it used to be NIL. Native has stored it here since the
        // slot existed and reads it back in `hashValue`; this port left the
        // slot empty and recomputed from the ns and name bytes on EVERY hash.
        // A keyword is the commonest map key there is.
        rt.setSlot(a, 2, Val.fixnum(hashKeywordOf(rt, ns, name)));
        rt.popTo(base);
        return Val.heap(a);
    }

    /// A symbol: `[ns, name, meta, hash]`. Interned like a keyword, and for the
    /// same reason: `=` on two symbols compares their slots, so two copies of
    /// one symbol would compare unequal while printing identically.
    public static long symbol(Rt rt, String ns, String name) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        byte[] nsb = ns == null ? null : ns.getBytes(StandardCharsets.UTF_8);
        int h = Hashtext.hashSymbol(nsb == null ? NO_NS : nsb, nb);
        Interns.Match matches = v -> Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_SYM
                                     && sameName(rt, v, nsb, nb);
        long found = probe(rt, Interns.SYM, h, matches);
        if (found != Val.NOT_FOUND) return found;
        long built = buildSymbol(rt, ns, name);
        if (Val.isNil(built)) return built;
        return publish(rt, Interns.SYM, h, built, matches);
    }

    static long buildSymbol(Rt rt, String ns, String name) {
        int base = rt.mark();
        int nsi = rt.push(ns == null ? Val.NIL : of(rt, ns));
        int nmi = rt.push(of(rt, name));
        long a = rt.alloc(TY_SYM, 4);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, 0, rt.r(nsi));
        rt.setSlot(a, 1, rt.r(nmi));
        rt.setSlot(a, 2, Val.NIL);  // meta
        // THE HASH, and it used to be NIL -- the same empty cache the keyword
        // slot had. `symbolHash` reads it; this port recomputed from the ns
        // and name bytes on every hash instead.
        rt.setSlot(a, 3, Val.fixnum(Hashtext.hashSymbol(
            ns == null ? NO_NS : ns.getBytes(StandardCharsets.UTF_8),
            name.getBytes(StandardCharsets.UTF_8))));
        rt.popTo(base);
        return Val.heap(a);
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

    static boolean isAscii(Rt rt, long v) { return sAscii(rt, v); }

    /// The code point at index `i`, as a single-character string. NOT a char
    /// type: flint has no char, and `DECISIONS.md#other-hosts` counts that among the
    /// documented divergences rather than a gap.
    /// The one-character string at code point `i`, or `dflt` past the end.
    ///
    /// A DEFAULT rather than a fixed `NOT_FOUND`, matching `Vec.nth`,
    /// `Maps.get` and the native runtime's `char_at`. Absence is an argument
    /// in this runtime now, not a sentinel each caller has to know about.
    // @kin:link:form:char-at: {:template "Str.nth({0}, {1}, {2}, {3})"}
    public static long nth(Rt rt, long v, int i, long dflt) {
        byte[] out = new byte[4];
        int w = cpBytesAt(rt, v, i, out);
        if (w < 0) return dflt;
        byte[] one = new byte[w];
        System.arraycopy(out, 0, one, 0, w);
        return Val.inlineStr(one);
    }

    /// The bytes of the code point at index `i`, or -1.
    ///
    /// WHICH MECHANISM, AND WHY -- `strings-and-matching` calls them complementary and this is
    /// the line where that is acted on. ASCII: flatten once and index by byte,
    /// which is O(1) forever after; descending instead costs O(depth) EVERY
    /// time and made a linear operation superlinear. Non-ASCII: descend, and
    /// never flatten, because there is no byte index to have and a flat run
    /// leaves nothing but the scan that was the quadratic.
    ///
    /// This replaced a one-entry cursor. The cursor had to be invalidated by
    /// the collector, so the same walk cost different GAS depending on when a
    /// collection happened -- and under parallel executors that collection
    /// belongs to another thread. `DECISIONS.md#resource-limits` says gas is
    /// deterministic; a memo keyed on collector state is not.
    static int cpBytesAt(Rt rt, long v, int i, byte[] out) {
        if (i < 0) return -1;
        if (isRope(rt, v)) {
            // ASCII INCLUDED, and it used not to be. For ASCII a code-point
            // index IS a byte index, so the descent is this same walk with the
            // lookup skipped -- while FLATTENING turns an O(log n) descent into
            // an O(n) copy AND caches the flat form, undoing the tree for every
            // later read. Native has descended for every rope since `strings-and-matching`; the
            // ports kept the older policy, so `(nth s i)` on a large ASCII rope
            // materialised it here and not there. Measured: RP_FLAT went from
            // nil to non-nil across one `nth` on a 2,700-character rope.
            //
            // PAST THE END NEEDS NO CHECK HERE: `ropeByteOfCp` answers the
            // byte length when there is no such code point, and `ropeBytesAt`
            // answers 0 for an offset at or past the end.
            int at = sAscii(rt, v) ? i : ropeByteOfCp(rt, v, i);
            int sk = rt.sinkOpen();
            int w = ropeBytesAt(rt, v, at, sk);
            byte[] got = rt.sinkArray(sk);
            for (int j = 0; j < w; j++) out[j] = got[j];
            rt.sinkClose(sk);
            return w == 0 ? -1 : w;
        }
        byte[] b = bytes(rt, flatten(rt, v));
        int at;
        if (isAscii(rt, flatten(rt, v))) {
            at = i;
        } else {
            // A flat non-ASCII run, bounded by `INDEX_LEAF`: anything longer
            // arrives as a tree, so this scan is O(INDEX_LEAF), not O(n).
            at = 0;
            for (int k = 0; k < i; k++) {
                if (at >= b.length) return -1;
                at += utf8Width(rt, b[at] & 0xFF);
            }
            rt.chargeBytes(at);
        }
        if (at >= b.length) return -1;
        int w = utf8Width(rt, b[at] & 0xFF);
        System.arraycopy(b, at, out, 0, w);
        return w;
    }

    /// The CODE POINT at index `i`, or -1 when `i` is past the end.
    ///
    /// Separate from `nth` so the reader does not build a one-character string
    /// per character just to ask what it is.
    public static int codePointAt(Rt rt, long v, int i) {
        byte[] out = new byte[4];
        int w = cpBytesAt(rt, v, i, out);
        if (w < 0) return -1;
        return new String(out, 0, w, StandardCharsets.UTF_8).codePointAt(0);
    }


    // --- ropes (`DECISIONS.md#strings-and-matching`) ---------------------------------------
    //
    // The THIRD tier: a shallow tree of string pieces, so `str` of two large
    // strings is a tree join rather than a copy. A node carries its subtree's
    // byte length, its CODE-POINT COUNT and an ASCII bit, all summed from its
    // children -- so `count` on a rope is O(1) and does not walk.
    //
    // Packing the count and the ASCII bit into ONE slot is the Rust's, and it
    // is not thrift: a slot is a NaN-boxed value, and two of them would make
    // every node 8 bytes bigger for one bit.

    /// `RP_HASH` is the subtree's content hash, or nil until asked. A flat
    /// string caches its hash in the `Str` header; a rope had nowhere to put
    /// one, so hashing a rope FLATTENED it -- which bought the caching by
    /// spending the sharing the tree exists for. Clojure's string hash
    /// composes, `h(A.B) = h(A)*31^|B| + h(B)`, so a node combines its
    /// children's (`DECISIONS.md#strings-and-matching`).
    public static final int RP_BYTES = 0, RP_CPS = 1, RP_FLAT = 2, RP_HASH = 3, RP_KIDS = 4;
    public static final int FLAT_MAX = 1024, FANOUT = 16;
    /// What may be COPIED to avoid a new leaf; see `runtime/src/rope.rs`.
    public static final int MERGE_MAX = 256;
    /// A slice smaller than this COPIES rather than sharing, so a small `subs`
    /// cannot retain a large parent. Not a performance choice -- the retention
    /// fix (`DECISIONS.md#strings-and-matching`).
    public static final int SLICE_MIN = 256;

    /// LEAF SIZE FOR A STRING THAT ARRIVES NON-ASCII AND WHOLE. See
    /// `indexedString`: the tree's node array is the sparse code-point index,
    /// and this is the bound on the scan inside one leaf.
    public static final int INDEX_LEAF = 128;

    public static boolean isRope(Rt rt, long v) { return com._3sln.flint.kgen.rt.Ropemeas.isRope(rt, v); }

    static int ropeKids(Rt rt, long v) { return com._3sln.flint.kgen.rt.Ropecat.ropeKids(rt, v); }

    /// A node over `kids`, whose aggregates are SUMMED from them rather than
    /// derived from their bytes. That is what makes `count` O(1) on a tree.
    /// A node over the `n` values ALREADY ROOTED at `base`. The caller pushes
    /// and the caller pops -- see the Rust and C# copies, which say the same.
    static long ropeNode(Rt rt, int base, int n) { return com._3sln.flint.kgen.rt.Ropenode.ropeNode(rt, base, n); }

    /// A slice that SHARES its interior.
    ///
    /// A child wholly inside the range is returned UNCHANGED -- no copy, no
    /// allocation, the same object -- and only the two edge children are cut.
    /// Slicing the middle out of a megabyte touches a handful of nodes.
    ///
    /// Both `subs` paths used to copy every byte and one flattened first, which
    /// spends the sharing that is half the point of a rope on the operation
    /// that most wants it (`DECISIONS.md#strings-and-matching`).
    public static long ropeSlice(Rt rt, long v, int from, int to) { return com._3sln.flint.kgen.rt.Ropeslice.ropeSlice(rt, v, from, to); }

    /// `31^n`, by squaring: the multiplier that lets two cached hashes join.
    public static int pow31(Rt rt, int n) { return com._3sln.flint.kgen.rt.Bytehash.pow31(rt, n); }

    private static java.util.ArrayDeque<long[]> copyStack(java.util.ArrayDeque<long[]> s) {
        java.util.ArrayDeque<long[]> out = new java.util.ArrayDeque<>();
        for (long[] e : s) out.addLast(new long[]{e[0], e[1]});
        return out;
    }

    /// The next leaf of a tree walk, or `NOT_FOUND` when the walk is done.
    private static long walkNext(Rt rt, java.util.ArrayDeque<long[]> stack) {
        while (!stack.isEmpty()) {
            long[] top = stack.peek();
            long node = top[0];
            int i = (int) top[1];
            if (!isRope(rt, node)) { stack.pop(); return node; }
            int kids = ropeKids(rt, node);
            if (i >= kids) { stack.pop(); continue; }
            top[1] = i + 1;
            stack.push(new long[]{rt.slot(node, RP_KIDS + i), 0});
        }
        return Val.NOT_FOUND;
    }

    /// `str` of two strings. O(1) once the pieces are big enough to matter.
    public static long concat(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Ropecat.sConcat(rt, a, b); }

    /// How many levels of node sit above the leaves. A leaf is 0.
    static int ropeHeight(Rt rt, long v) { return com._3sln.flint.kgen.rt.Ropecat.ropeHeight(rt, v); }

    /// Wrap `v` in single-kid nodes until it stands `h` levels tall, which is
    /// what keeps every leaf at the SAME depth.
    static long ropeLift(Rt rt, long v, int h) { return com._3sln.flint.kgen.rt.Ropenode.ropeLift(rt, v, h); }

    /// Append `b` into the rightmost subtree of `a` that has room, rebuilding
    /// the spine above it. NIL when the right spine is full at every level,
    /// which is the only time the caller adds one.
    static long ropeAppend(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Ropecat.ropeAppend(rt, a, b); }

    /// Copy the range out into a fresh string -- the DECODE half, see the Rust copy.
    // @kin:link:form:s-copy-range: {:template "Str.sCopyRange({0}, {1}, {2}, {3})"}
    public static long sCopyRange(Rt rt, long v, int from, int to) {
        int base = rt.mark();
        int vi = rt.push(v);
        int s = rt.sinkOpen();
        com._3sln.flint.kgen.rt.Ropeflat.sAppendRange(rt, rt.r(vi), from, to, s);
        long out = rt.sinkString(s);
        rt.sinkClose(s);
        rt.popTo(base);
        return out;
    }

    static void sAppendRange(Rt rt, long v, int from, int to, int s) {
        com._3sln.flint.kgen.rt.Ropeflat.sAppendRange(rt, v, from, to, s);
    }

    /// The empty string, interned -- see the Rust copy.
    // @kin:link:form:s-empty: {:template "Str.sEmpty({0})"}
    public static long sEmpty(Rt rt) { return of(rt, ""); }

    // @kin:link:form:s-concat-copy: {:template "Str.copyConcat({0}, {1}, {2})"}
    public static long copyConcat(Rt rt, long a, long b) {
        byte[] x = bytes(rt, a), y = bytes(rt, b);
        // Copying is work, and it is charged at the same rate everywhere.
        rt.chargeBytes(x.length + y.length);
        byte[] both = new byte[x.length + y.length];
        System.arraycopy(x, 0, both, 0, x.length);
        System.arraycopy(y, 0, both, x.length, y.length);
        return of(rt, new String(both, StandardCharsets.UTF_8));
    }


    /// Contiguous bytes for a string of any tier. Identity for inline and flat;
    /// materialises a rope ONCE and remembers it. `strings-and-matching`: count the flattens,
    /// do not hope about them -- a rope that flattens on every `index-of`
    /// passes every correctness test and is slower than the flat string it
    /// replaced.
    public static long flatten(Rt rt, long v) { return com._3sln.flint.kgen.rt.Ropeflat.sFlatten(rt, v); }
    public static int ropeHash(Rt rt, long v) { return com._3sln.flint.kgen.rt.Ropeflat.ropeHash(rt, v); }
    public static boolean treeEq(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Ropeeq.treeEq(rt, a, b); }
    static int utf8Width(Rt rt, int b0) { return com._3sln.flint.kgen.rt.Ropecp.utf8Width(rt, b0); }
    public static int sBytes(Rt rt, long v) { return com._3sln.flint.kgen.rt.Ropemeas.sBytes(rt, v); }
    public static int sCount(Rt rt, long v) { return com._3sln.flint.kgen.rt.Ropemeas.sCount(rt, v); }
    public static boolean sAscii(Rt rt, long v) { return com._3sln.flint.kgen.rt.Ropemeas.sAscii(rt, v); }
    public static int ropeByteOfCp(Rt rt, long v, int k) { return com._3sln.flint.kgen.rt.Ropecp.ropeByteOfCp(rt, v, k); }
    static int ropeBytesAt(Rt rt, long v, int at, int s) { return com._3sln.flint.kgen.rt.Ropecp.ropeBytesAt(rt, v, at, s); }

    /// The shortest decimal that reads back as `d`, as characters in `c`.
    ///
    /// The whole of what this runtime still decides about printing a double.
    /// Everything above it -- when to use an exponent, how to spell one, what
    /// to call an infinity -- is generated from `kin/dblstr.kin` and shared
    /// with the other two runtimes, which is why this port no longer has a
    /// `fmtDouble` of its own to disagree with them.
    /// A validated decimal as a double, over the code-point range `from`..`to`.
    ///
    /// The other half of the hole `f64Digits` opens. `strnum.kin` has already
    /// decided this run is a number and which characters it may contain, so
    /// the parse cannot fail -- and this port no longer decides for itself,
    /// which it used to do by asking whether the text contained a `.` or an
    /// `e` and handing the rest to `Double.parseDouble`.
    public static double f64OfStr(Rt rt, long v, int from, int to) {
        String s = text(rt, v);
        return Double.parseDouble(s.substring(s.offsetByCodePoints(0, from),
                                              s.offsetByCodePoints(0, to)));
    }

    public static void f64Digits(Rt rt, int c, double d) {
        String s = Double.toString(d);
        for (int i = 0; i < s.length(); i++) rt.cpsPut(c, s.charAt(i));
    }

    /// Byte length. NOT the code-point count -- see the class comment.
}
