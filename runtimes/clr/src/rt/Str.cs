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

    /// Build a string. Interning is not ported yet -- it needs the weak tables
    /// -- so this allocates every time, which is correct and slower.
    public static long Of(Rt rt, string s) {
        byte[] b = Encoding.UTF8.GetBytes(s);
        if (b.Length <= Val.InlineMax) return Val.InlineStr(b);
        long a = rt.Alloc(Obj.TyStr, b.Length);
        if (a == 0) return Val.Nil;
        rt.gc.sp.WriteBytes(a + Obj.StrData, b);
        Obj.SetStrHash(rt.gc.sp, a, 0);
        bool ascii = true;
        foreach (byte x in b) if ((x & 0x80) != 0) { ascii = false; break; }
        Obj.SetStrAscii(rt.gc.sp, a, ascii);
        return Val.Heap(a);
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
    /// NOT INTERNED YET. The Rust interns through a weak table so that two
    /// spellings of one keyword are the same object -- which makes `=` a
    /// pointer compare and keeps map lookups cheap. Until those tables are
    /// ported this allocates each time, which is CORRECT (`=` on keywords
    /// compares namespace and name, not identity) and slower and uses more
    /// heap. The rooting below is the part that must not be simplified: both
    /// strings are live across the `Alloc` that can move them, and the Rust
    /// records that `symbol` and `keyword` had the same four lines and the same
    /// bug when they were not.
    public static long Keyword(Rt rt, string ns, string name) {
        byte[] nb = Encoding.UTF8.GetBytes(name);
        if (ns == null && nb.Length > 0 && nb.Length <= Val.InlineMax) return Val.InlineKw(nb);
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

    /// A symbol: `[ns, name, meta, hash]`. Same rooting discipline, same
    /// missing interning.
    public static long Symbol(Rt rt, string ns, string name) {
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
