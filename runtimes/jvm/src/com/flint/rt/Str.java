package com.flint.rt;

import java.nio.charset.StandardCharsets;

import static com.flint.rt.Obj.*;

/// Strings, ported from `runtime/src/strs.rs`.
///
/// Three tiers, and the first two are here (`doc/decisions/0011`'s ropes are
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
    private Str() {}

    public static boolean isString(Rt rt, long v) {
        return Val.isInlineStr(v) || (Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_STR);
    }

    /// A bare, UNINTERNED heap string. `of` is the canonical constructor.
    static long rawString(Rt rt, byte[] b) {
        long a = rt.alloc(TY_STR, b.length);
        if (a == 0) return Val.NIL;
        rt.gc.sp.writeBytes(a + STR_DATA, b);
        setStrHash(rt.gc.sp, a, 0);
        boolean ascii = true;
        for (byte x : b) if ((x & 0x80) != 0) { ascii = false; break; }
        setStrAscii(rt.gc.sp, a, ascii);
        return Val.heap(a);
    }

    /// The CANONICAL value for a string.
    ///
    /// Three tiers (`doc/decisions/0011`): inline up to 5 bytes, interned up to
    /// `INTERN_MAX`, and plain heap beyond. Inline is canonical by
    /// construction and interning is guaranteed in its range, so two strings in
    /// either range are `=` exactly when they are bit-equal. Past the range a
    /// string is still correct, just not canonical, and `eq` compares bytes.
    public static long of(Rt rt, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= Val.INLINE_MAX) return Val.inlineStr(b);
        if (b.length > Interns.INTERN_MAX) return rawString(rt, b);
        int h = Hash.hashString(b);
        Interns t = rt.roots.interns[Interns.STR];
        long found = t.lookup(h, v -> Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_STR
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
        Interns t = rt.roots.interns[table];
        long again = t.lookup(h, matches);
        if (again != Val.NOT_FOUND) return again;   // somebody got there first
        int idx = t.slot;
        if (t.needsGrow()) {
            t.grow();
            // `grow` invalidates the index, so re-probe for a slot.
            t.lookup(h, x -> false);
            idx = t.slot;
        }
        t.insertAt(idx, h, v);
        return v;
    }

    public static byte[] bytes(Rt rt, long v) {
        if (Val.isInlineStr(v)) return Val.inlineBytes(v);
        long a = Val.asHeap(v);
        return rt.gc.sp.bytes(a + STR_DATA, len(rt.gc.sp, a));
    }

    public static String text(Rt rt, long v) {
        return new String(bytes(rt, v), StandardCharsets.UTF_8);
    }

    /// A keyword. Inline when it has no namespace and fits in five bytes,
    /// which is most of them; otherwise `TY_KW` `[ns, name, hash]`.
    ///
    /// INTERNED through the weak table, so two spellings of one keyword are the
    /// same object -- which is what makes `=` a pointer compare and keeps map
    /// lookups cheap. The rooting in `buildKeyword` is the part that must not
    /// be simplified: both strings are live across the `alloc` that can move
    /// them, and the Rust records that `symbol` and `keyword` had the same four
    /// lines and the same bug when they were not.
    public static long keyword(Rt rt, String ns, String name) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (ns == null && nb.length > 0 && nb.length <= Val.INLINE_MAX) return Val.inlineKw(nb);
        byte[] nsb = ns == null ? null : ns.getBytes(StandardCharsets.UTF_8);
        int h = Hash.hashKeyword(nsb, nb);
        Interns.Match matches = v -> Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_KW
                                     && sameName(rt, v, nsb, nb);
        long found = rt.roots.interns[Interns.KW].lookup(h, matches);
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

    static long buildKeyword(Rt rt, String ns, String name) {
        int base = rt.mark();
        int nsi = rt.push(ns == null ? Val.NIL : of(rt, ns));
        int nmi = rt.push(of(rt, name));
        long a = rt.alloc(TY_KW, 3);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, 0, rt.r(nsi));
        rt.setSlot(a, 1, rt.r(nmi));
        rt.setSlot(a, 2, Val.NIL);
        rt.popTo(base);
        return Val.heap(a);
    }

    /// A symbol: `[ns, name, meta, hash]`. Interned like a keyword, and for the
    /// same reason: `=` on two symbols compares their slots, so two copies of
    /// one symbol would compare unequal while printing identically.
    public static long symbol(Rt rt, String ns, String name) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        byte[] nsb = ns == null ? null : ns.getBytes(StandardCharsets.UTF_8);
        int h = Hash.hashSymbol(nsb, nb);
        Interns.Match matches = v -> Val.isHeap(v) && ty(rt.gc.sp, Val.asHeap(v)) == TY_SYM
                                     && sameName(rt, v, nsb, nb);
        long found = rt.roots.interns[Interns.SYM].lookup(h, matches);
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
        rt.setSlot(a, 2, Val.NIL);
        rt.setSlot(a, 3, Val.NIL);
        rt.popTo(base);
        return Val.heap(a);
    }

    /// The number of CODE POINTS, which is what `count` on a string means in
    /// Clojure. For an ASCII string that equals the byte length, which is what
    /// the header flag is for; otherwise the bytes are walked.
    public static int charLen(Rt rt, long v) {
        byte[] b = bytes(rt, v);
        if (isAscii(rt, v)) return b.length;
        int n = 0;
        for (byte x : b) if ((x & 0xC0) != 0x80) n++;   // count non-continuations
        return n;
    }

    static boolean isAscii(Rt rt, long v) {
        if (Val.isInlineStr(v)) {
            for (byte x : Val.inlineBytes(v)) if ((x & 0x80) != 0) return false;
            return true;
        }
        return strIsAscii(rt.gc.sp, Val.asHeap(v));
    }

    /// The code point at index `i`, as a single-character string. NOT a char
    /// type: flint has no char, and `doc/decisions/0010` counts that among the
    /// documented divergences rather than a gap.
    public static long nth(Rt rt, long v, int i) {
        byte[] b = bytes(rt, v);
        if (isAscii(rt, v)) {
            if (i < 0 || i >= b.length) return Val.NOT_FOUND;
            return Val.inlineStr(new byte[]{ b[i] });
        }
        // A byte index and a character index coincide only for ASCII, so this
        // walks. That is the cost the header flag exists to avoid on the
        // common path, not a shortcut being taken here.
        int at = 0, seen = 0;
        while (at < b.length) {
            int size = 1;
            int c = b[at] & 0xFF;
            if ((c & 0xE0) == 0xC0) size = 2;
            else if ((c & 0xF0) == 0xE0) size = 3;
            else if ((c & 0xF8) == 0xF0) size = 4;
            if (seen == i) {
                byte[] one = new byte[size];
                System.arraycopy(b, at, one, 0, size);
                return of(rt, new String(one, StandardCharsets.UTF_8));
            }
            at += size;
            seen++;
        }
        return Val.NOT_FOUND;
    }

    /// Byte length. NOT the code-point count -- see the class comment.
    public static int byteLen(Rt rt, long v) {
        return Val.isInlineStr(v) ? Val.inlineLen(v) : len(rt.gc.sp, Val.asHeap(v));
    }
}
