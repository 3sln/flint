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
    /// A host-minted reference (`DECISIONS.md#opaque-values`). Guest code can mint one
    /// only with id 0 and there is deliberately no builtin that reads an id
    /// back, so an id is a thing the HOST wrote and only the host can read.
    /// That is what lets a snapshot preserve identities without granting any.
    public static final int TY_OPAQUE = 43;
    public static final int TY_BYTES = 44;
    public static final int TY_BROPE = 45;
    public static final int TY_TBYTES = 46;
    /// A tagged literal: `[tag, form]`, tag a namespaced SYMBOL. Its own type
    /// rather than a two-key map (`DECISIONS.md#tagged-literals`), because a map is
    /// ambiguous with a map in every format that has tags and loses the
    /// namespace wherever the key must become a string. It still ANSWERS the
    /// map protocols on `:tag` and `:form`.
    public static final int TY_TAGGED = 47;
    /// A SCHEMA: `[names, types, index, ids, width]` (`DECISIONS.md#tables`).
    /// A column is addressed in a chunk by a stable ID, not by its position,
    /// which is what makes dropping one a head-only edit.
    public static final int TY_SCHEMA = 48;
    /// A TABLE: `[schema, chunks, count, offset]`. `chunks` is an ordinary
    /// flint VECTOR, so the "B-tree keyed by row index" is the vector we have.
    /// `offset` is the first row's index within the first chunk, which is what
    /// lets `slice` SHARE every chunk it spans.
    public static final int TY_TABLE = 49;
    /// A ROW REF: `[schema, chunk, row]`. Holds the CHUNK and not the table, so
    /// keeping one row out of a million retains one chunk.
    public static final int TY_TABLEREF = 50;
    /// A TRANSIENT TABLE: `[schema, chunks, count, open, live]`.
    public static final int TY_TTABLE = 51;
    /// A WIRE WRITER: `[WR_BUF, WR_LIVE]`.
    ///
    /// An OPAQUE handle around a transient byte string, and opaque is the whole
    /// point (`DECISIONS.md#the-codec-is-guest-code`). The guest appends through
    /// the `wire-*` primitives and cannot reach the buffer, so it cannot write a
    /// `K_PORT` tag followed by an id it does not hold. A transient byte string
    /// handed to the guest directly would give exactly that away.
    ///
    /// Like every transient it is NOT a value: no `eq`, no `hash`, no `kind`.
    public static final int TY_WRITER = 52;
    /// A WIRE READER: `[RD_BYTES, RD_POS, RD_LIVE]`.
    ///
    /// NOT the writer's mirror image. A writer must be opaque because a guest
    /// that can write raw bytes can forge a `K_PORT` tag; a reader may hand out
    /// integers and strings freely, because reading bytes a guest already holds
    /// tells it nothing new. Only the two MINTING reads are guarded.
    ///
    /// `RD_LIVE` is that guard: true only for a reader the runtime made over
    /// bytes that arrived on a bridge. The rule `decode_guest` enforced by
    /// refusing tags is now a flag, set in one place.
    public static final int TY_READER = 53;
    public static final int TY_MAX = 54;

    public static final int VALS = 0, STR = 1, RAW = 2;

    /// GENERATED (`kin/objsize.kin`). Which of the three shapes `ty` has.
    public static int layoutOf(int ty) {
        return com._3sln.flint.kgen.rt.Objsize.layoutOf(ty);
    }

    public static long align8(long n) { return (n + 7) & ~7L; }

    /// GENERATED (`kin/objsize.kin`). How many BYTES an object occupies --
    /// and so, after `>> 3`, what every allocation costs in gas.
    public static long sizeFor(int ty, int len) {
        return com._3sln.flint.kgen.rt.Objsize.sizeFor(ty, len);
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

    /// GENERATED (`kin/objhdr.kin`). The header layer is rooted at the SPACE
    /// and not at the `Rt`, which is why it needed a `Space` tag in kin
    /// before it could be generated: native's collector reads these from
    /// `&mut self` methods of `Gc`, and cannot borrow the whole `Rt` to do
    /// it. The three copies AGREED before the port, every shift and mask, so
    /// this is a consolidation and not a fix.
    public static int ty(Space sp, long a) { return com._3sln.flint.kgen.rt.Objhdr.objTy(sp, a); }
    public static int len(Space sp, long a) { return com._3sln.flint.kgen.rt.Objhdr.objLen(sp, a); }

    public static void writeHeader(Space sp, long a, int ty, int len) {
        sp.writeU32(a, ty << 24);
        sp.writeU32(a + 4, len);
    }

    public static int age(Space sp, long a) { return com._3sln.flint.kgen.rt.Objhdr.objAge(sp, a); }

    public static void setAge(Space sp, long a, int age) {
        int w = sp.readU32(a);
        sp.writeU32(a, (w & ~(7 << 21)) | ((age & 7) << 21));
    }

    public static boolean marked(Space sp, long a) { return com._3sln.flint.kgen.rt.Objhdr.objMarked(sp, a); }

    public static void setMarked(Space sp, long a, boolean m) {
        int w = sp.readU32(a);
        sp.writeU32(a, m ? (w | (1 << 20)) : (w & ~(1 << 20)));
    }

    public static boolean inRemset(Space sp, long a) { return com._3sln.flint.kgen.rt.Objhdr.objInRemset(sp, a); }

    public static void setInRemset(Space sp, long a, boolean m) {
        int w = sp.readU32(a);
        sp.writeU32(a, m ? (w | (1 << 19)) : (w & ~(1 << 19)));
    }

    /// Is every byte of this string ASCII? A byte index is then a code-point
    /// index, which is what makes `subs` and `nth` O(1) instead of a walk.
    public static boolean strIsAscii(Space sp, long a) { return com._3sln.flint.kgen.rt.Objhdr.objStrAscii(sp, a); }

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
