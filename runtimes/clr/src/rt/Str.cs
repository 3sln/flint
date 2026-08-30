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
    public static bool IsString(Rt rt, long v) =>
        Val.IsInlineStr(v) || (Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyStr);

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
        Interns t = rt.roots.interns[Interns.STR];
        Interns.Match matches = v => Val.IsHeap(v) && Obj.Ty(rt.gc.sp, Val.AsHeap(v)) == Obj.TyStr
                                     && SameBytes(Bytes(rt, v), b);
        long found = t.Lookup(h, matches);
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
        Interns t = rt.roots.interns[table];
        long again = t.Lookup(h, matches);
        if (again != Val.NotFound) return again;   // somebody got there first
        int idx = t.slot;
        if (t.NeedsGrow()) {
            t.Grow();
            t.Lookup(h, x => false);   // `Grow` invalidates the index
            idx = t.slot;
        }
        t.InsertAt(idx, h, v);
        return v;
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
        return Obj.StrIsAscii(rt.gc.sp, Val.AsHeap(v));
    }

    /// The code point at index `i`, as a single-character string. NOT a char
    /// type: flint has no char, and `doc/decisions/0010` counts that among the
    /// documented divergences rather than a gap.
    public static long Nth(Rt rt, long v, int i) {
        byte[] b = Bytes(rt, v);
        if (IsAscii(rt, v)) {
            if (i < 0 || i >= b.Length) return Val.NotFound;
            return Val.InlineStr(new byte[]{ b[i] });
        }
        // A byte index and a character index coincide only for ASCII, so this
        // walks. That is the cost the header flag exists to avoid on the common
        // path, not a shortcut being taken here.
        int at = 0, seen = 0;
        while (at < b.Length) {
            int size = 1;
            int c = b[at] & 0xFF;
            if ((c & 0xE0) == 0xC0) size = 2;
            else if ((c & 0xF0) == 0xE0) size = 3;
            else if ((c & 0xF8) == 0xF0) size = 4;
            if (seen == i) {
                byte[] one = new byte[size];
                System.Array.Copy(b, at, one, 0, size);
                return Of(rt, Encoding.UTF8.GetString(one));
            }
            at += size;
            seen++;
        }
        return Val.NotFound;
    }

    public static byte[] Bytes(Rt rt, long v) {
        if (Val.IsInlineStr(v)) return Val.InlineBytes(v);
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
        long found = rt.roots.interns[Interns.KW].Lookup(h, matches);
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
        long found = rt.roots.interns[Interns.SYM].Lookup(h, matches);
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

    /// Byte length. NOT the code-point count -- see the class comment.
    public static int ByteLen(Rt rt, long v) =>
        Val.IsInlineStr(v) ? Val.InlineLen(v) : Obj.Len(rt.gc.sp, Val.AsHeap(v));
}
