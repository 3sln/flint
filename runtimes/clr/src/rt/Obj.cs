namespace Flint.Rt;

/// Heap object layout, ported verbatim from `runtime/src/obj.rs`.
///
/// Every object starts with an 8-byte header:
///
///   +0 u32  [31:24] type  [23:21] age  [20] mark  [19] in-remembered-set
///   +4 u32  len   -- meaning depends on the layout class
///
/// Three layout classes and no more, which is what makes tracing one loop with
/// no per-type knowledge -- the single biggest source of GC bugs removed.
public static class Obj {
    public const long Hdr = 8;
    public const long StrData = 16;

    public const int TyFree = 0, TyFwd = 1, TyStr = 2, TyBigint = 3, TySym = 4,
                     TyKw = 5, TyCons = 6, TyEmptyList = 7, TyLazyseq = 8,
                     TyVec = 9, TyNode = 10, TyVecseq = 11, TyStrseq = 12,
                     TyRange = 13, TyArraymap = 14, TyHashmap = 15, TyBmnode = 16,
                     TyArraynode = 17, TyCollnode = 18, TySet = 19, TyMapentry = 20,
                     TyClosure = 21, TyRaw = 35;

    public const int Vals = 0, Str = 1, Raw = 2;

    public static int LayoutOf(int ty) {
        if (ty == TyStr) return Str;
        if (ty == TyBigint || ty == TyRaw || ty == TyFree || ty == TyFwd) return Raw;
        return Vals;
    }

    public static long Align8(long n) => (n + 7) & ~7L;

    public static long SizeFor(int ty, int len) => LayoutOf(ty) switch {
        Vals => Hdr + (long) len * 8,
        Str => Align8(StrData + len),
        _ => Align8(Hdr + len),
    };

    public static long SizeOf(Space sp, long addr) {
        int w0 = sp.ReadU32(addr);
        int ty = (int)((uint) w0 >> 24);
        int len = sp.ReadU32(addr + 4);
        // Two special cases, then `SizeFor`. Deriving it removes a second
        // table: a type added to `LayoutOf` and not to a copy of this match was
        // sized wrongly, and the collector then walked with the wrong stride.
        if (ty == TyFree) return (uint) len;
        if (ty == TyFwd) return Hdr;
        return SizeFor(ty, len);
    }

    public static int Ty(Space sp, long a) => (int)((uint) sp.ReadU32(a) >> 24);
    public static int Len(Space sp, long a) => sp.ReadU32(a + 4);

    public static void WriteHeader(Space sp, long a, int ty, int len) {
        sp.WriteU32(a, ty << 24);
        sp.WriteU32(a + 4, len);
    }

    public static int Age(Space sp, long a) => ((int)((uint) sp.ReadU32(a) >> 21)) & 7;

    public static void SetAge(Space sp, long a, int age) {
        int w = sp.ReadU32(a);
        sp.WriteU32(a, (w & ~(7 << 21)) | ((age & 7) << 21));
    }

    public static bool Marked(Space sp, long a) => (sp.ReadU32(a) & (1 << 20)) != 0;

    public static void SetMarked(Space sp, long a, bool m) {
        int w = sp.ReadU32(a);
        sp.WriteU32(a, m ? (w | (1 << 20)) : (w & ~(1 << 20)));
    }

    public static bool InRemset(Space sp, long a) => (sp.ReadU32(a) & (1 << 19)) != 0;

    public static void SetInRemset(Space sp, long a, bool m) {
        int w = sp.ReadU32(a);
        sp.WriteU32(a, m ? (w | (1 << 19)) : (w & ~(1 << 19)));
    }

    public static long SlotAddr(long a, int i) => a + Hdr + (long) i * 8;
    public static long Slot(Space sp, long a, int i) => sp.ReadU64(SlotAddr(a, i));
    public static void SetSlotRaw(Space sp, long a, int i, long v) => sp.WriteU64(SlotAddr(a, i), v);

    /// A forwarding pointer, packed into the header.
    ///
    /// The new address does not fit in `len`: an address is 48 bits and `len`
    /// is 32. It needs no wider header -- a `TyFwd` has no age, no mark and is
    /// in no remembered set, so its low 24 bits are free. 24 + 32 = 56 against
    /// the 48 an address can hold. The pair lives together so the packing
    /// cannot drift.
    public static void SetForward(Space sp, long a, long dest) {
        sp.WriteU32(a, (TyFwd << 24) | (int)((dest >> 32) & 0x00FF_FFFFL));
        sp.WriteU32(a + 4, (int) dest);
    }

    public static long ForwardTarget(Space sp, long a) {
        long hi = sp.ReadU32(a) & 0x00FF_FFFFL;
        return (hi << 32) | (uint) sp.ReadU32(a + 4);
    }
}
