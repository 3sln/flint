package com.flint.rt;

/// Heap object layout, ported verbatim from `runtime/src/obj.rs`.
///
/// Every object starts with an 8-byte header:
///
/// <pre>
///   +0 u32  [31:24] type  [23:21] age  [20] mark  [19] in-remembered-set
///   +4 u32  len   -- meaning depends on the layout class
/// </pre>
///
/// Three layout classes and no more, which is what makes tracing one loop with
/// no per-type knowledge -- the single biggest source of GC bugs removed:
///
/// <ul>
///   <li>{@code Vals} -- `len` 64-bit values follow the header. EVERY slot is a
///       value, including things that are morally integers (they are fixnums).
///   <li>{@code Str} -- a cached hash at +8, then `len` UTF-8 bytes at +16.
///   <li>{@code Raw} -- `len` opaque bytes at +8.
/// </ul>
public final class Obj {
    private Obj() {}

    public static final long HDR = 8;
    public static final long STR_DATA = 16;

    public static final int TY_FREE = 0;
    public static final int TY_FWD = 1;
    public static final int TY_STR = 2;
    public static final int TY_BIGINT = 3;
    public static final int TY_SYM = 4;
    public static final int TY_KW = 5;
    public static final int TY_CONS = 6;
    public static final int TY_EMPTY_LIST = 7;
    public static final int TY_LAZYSEQ = 8;
    public static final int TY_VEC = 9;
    public static final int TY_NODE = 10;
    public static final int TY_VECSEQ = 11;
    public static final int TY_STRSEQ = 12;
    public static final int TY_RANGE = 13;
    public static final int TY_ARRAYMAP = 14;
    public static final int TY_HASHMAP = 15;
    public static final int TY_BMNODE = 16;
    public static final int TY_ARRAYNODE = 17;
    public static final int TY_COLLNODE = 18;
    public static final int TY_SET = 19;
    public static final int TY_MAPENTRY = 20;
    public static final int TY_CLOSURE = 21;
    public static final int TY_NATIVEFN = 22;   // [id, name]
    public static final int TY_VAR = 23;
    public static final int TY_ATOM = 24;
    public static final int TY_TVEC = 25;       // [cnt, shift, root, tail, live]
    public static final int TY_TMAP = 26;       // [cnt, root, hasNil, nilVal, live]
    public static final int TY_TSET = 27;       // [tmap, live]
    public static final int TY_RECORD = 28;     // [type, basis, ext, meta, ...fields]
    public static final int TY_REGEX = 29;      // [source, prog(raw), ngroups]
    public static final int TY_REDUCED = 30;    // [value]
    public static final int TY_EXINFO = 31;     // [msg, data, cause]
    public static final int TY_MULTIFN = 32;    // [name, dispatch, methods, default, prefers]
    public static final int TY_DELAY = 33;      // [thunk, value]
    public static final int TY_VOLATILE = 34;   // [value]
    public static final int TY_RAW = 35;        // opaque bytes
    public static final int TY_ITERSEQ = 36;
    public static final int TY_CHUNKSEQ = 37;   // [node(array), off, rest, meta]
    public static final int TY_TYPE = 38;       // [name, basis, protocols]
    public static final int TY_THREAD = 39;
    public static final int TY_PORT = 40;
    public static final int TY_SCHED = 41;
    public static final int TY_ROPE = 42;
    /// A host-minted reference (`doc/decisions/0022`). Guest code can mint one
    /// only with id 0 and there is deliberately no builtin that reads an id
    /// back, so an id is a thing the HOST wrote and only the host can read.
    /// That is what lets a snapshot preserve identities without granting any.
    public static final int TY_OPAQUE = 43;
    public static final int TY_BYTES = 44;
    public static final int TY_BROPE = 45;
    public static final int TY_TBYTES = 46;
    public static final int TY_MAX = 47;

    public static final int VALS = 0, STR = 1, RAW = 2;

    public static int layoutOf(int ty) {
        if (ty == TY_STR) return STR;
        if (ty == TY_BIGINT || ty == TY_RAW || ty == TY_BYTES || ty == TY_FREE || ty == TY_FWD) return RAW;
        return VALS;
    }

    public static long align8(long n) { return (n + 7) & ~7L; }

    public static long sizeFor(int ty, int len) {
        switch (layoutOf(ty)) {
            case VALS: return HDR + (long) len * 8;
            case STR: return align8(STR_DATA + len);
            default: return align8(HDR + len);
        }
    }

    public static long sizeOf(Space sp, long addr) {
        int w0 = sp.readU32(addr);
        int ty = w0 >>> 24;
        int len = sp.readU32(addr + 4);
        // Two special cases, then `sizeFor`. Deriving it removes a second
        // table: a type added to `layoutOf` and not to a copy of this match was
        // sized wrongly, the collector walked with the wrong stride, and the
        // symptom named an object that was plainly fine.
        if (ty == TY_FREE) return Integer.toUnsignedLong(len);
        if (ty == TY_FWD) return HDR;
        return sizeFor(ty, len);
    }

    public static int ty(Space sp, long a) { return sp.readU32(a) >>> 24; }
    public static int len(Space sp, long a) { return sp.readU32(a + 4); }

    public static void writeHeader(Space sp, long a, int ty, int len) {
        sp.writeU32(a, ty << 24);
        sp.writeU32(a + 4, len);
    }

    public static int age(Space sp, long a) { return (sp.readU32(a) >>> 21) & 7; }

    public static void setAge(Space sp, long a, int age) {
        int w = sp.readU32(a);
        sp.writeU32(a, (w & ~(7 << 21)) | ((age & 7) << 21));
    }

    public static boolean marked(Space sp, long a) { return (sp.readU32(a) & (1 << 20)) != 0; }

    public static void setMarked(Space sp, long a, boolean m) {
        int w = sp.readU32(a);
        sp.writeU32(a, m ? (w | (1 << 20)) : (w & ~(1 << 20)));
    }

    public static boolean inRemset(Space sp, long a) { return (sp.readU32(a) & (1 << 19)) != 0; }

    public static void setInRemset(Space sp, long a, boolean m) {
        int w = sp.readU32(a);
        sp.writeU32(a, m ? (w | (1 << 19)) : (w & ~(1 << 19)));
    }

    /// Is every byte of this string ASCII? A byte index is then a code-point
    /// index, which is what makes `subs` and `nth` O(1) instead of a walk.
    public static boolean strIsAscii(Space sp, long a) { return (sp.readU32(a) & (1 << 18)) != 0; }

    public static void setStrAscii(Space sp, long a, boolean v) {
        int w = sp.readU32(a);
        sp.writeU32(a, v ? (w | (1 << 18)) : (w & ~(1 << 18)));
    }

    public static int strHash(Space sp, long a) { return sp.readU32(a + HDR); }
    public static void setStrHash(Space sp, long a, int h) { sp.writeU32(a + HDR, h); }

    public static long slotAddr(long a, int i) { return a + HDR + (long) i * 8; }
    public static long slot(Space sp, long a, int i) { return sp.readU64(slotAddr(a, i)); }
    public static void setSlotRaw(Space sp, long a, int i, long v) { sp.writeU64(slotAddr(a, i), v); }

    /// A forwarding pointer, packed into the header.
    ///
    /// The new address does NOT fit in `len`: an address is 48 bits and `len`
    /// is 32. It needs no wider header either -- a `TY_FWD` has no age, no mark
    /// and is in no remembered set, so its whole low 24 bits are free. 24 + 32
    /// = 56 against the 48 an address can hold. The pair lives together so the
    /// packing cannot drift.
    public static void setForward(Space sp, long a, long dest) {
        sp.writeU32(a, (TY_FWD << 24) | (int) ((dest >>> 32) & 0x00FF_FFFFL));
        sp.writeU32(a + 4, (int) dest);
    }

    public static long forwardTarget(Space sp, long a) {
        long hi = sp.readU32(a) & 0x00FF_FFFFL;
        return (hi << 32) | Integer.toUnsignedLong(sp.readU32(a + 4));
    }
}
