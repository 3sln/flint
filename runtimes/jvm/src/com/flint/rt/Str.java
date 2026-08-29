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
/// * anything longer is a `TY_STR` on the heap, with its hash cached at +8 and
///   an ASCII flag in the header.
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

    /// Build a string. Interning is not ported yet -- it needs the weak tables
    /// -- so this allocates every time, which is correct and slower.
    public static long of(Rt rt, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= Val.INLINE_MAX) return Val.inlineStr(b);
        long a = rt.alloc(TY_STR, b.length);
        if (a == 0) return Val.NIL;
        rt.gc.sp.writeBytes(a + STR_DATA, b);
        setStrHash(rt.gc.sp, a, 0);
        boolean ascii = true;
        for (byte x : b) if ((x & 0x80) != 0) { ascii = false; break; }
        setStrAscii(rt.gc.sp, a, ascii);
        return Val.heap(a);
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
    /// NOT INTERNED YET. The Rust interns through a weak table so that two
    /// spellings of one keyword are the same object -- which makes `=` a
    /// pointer compare and keeps map lookups cheap. Until those tables are
    /// ported this allocates each time, which is CORRECT (`=` on keywords
    /// compares namespace and name, not identity) and slower and uses more
    /// heap. The rooting below is the part that must not be simplified: both
    /// strings are live across the `alloc` that can move them, and the Rust
    /// records that `symbol` and `keyword` had the same four lines and the same
    /// bug when they were not.
    public static long keyword(Rt rt, String ns, String name) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (ns == null && nb.length > 0 && nb.length <= Val.INLINE_MAX) return Val.inlineKw(nb);
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

    /// A symbol: `[ns, name, meta, hash]`. Same rooting discipline, same
    /// missing interning.
    public static long symbol(Rt rt, String ns, String name) {
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

    /// Byte length. NOT the code-point count -- see the class comment.
    public static int byteLen(Rt rt, long v) {
        return Val.isInlineStr(v) ? Val.inlineLen(v) : len(rt.gc.sp, Val.asHeap(v));
    }
}
