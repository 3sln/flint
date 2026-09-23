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
                     TyClosure = 21, TyNativefn = 22, TyVar = 23, TyAtom = 24,
                     TyTvec = 25, TyTmap = 26, TyTset = 27, TyRecord = 28,
                     TyRegex = 29, TyReduced = 30, TyExinfo = 31, TyMultifn = 32,
                     TyDelay = 33, TyVolatile = 34, TyRaw = 35, TyIterseq = 36,
                     TyChunkseq = 37, TyType = 38, TyThread = 39, TyPort = 40,
                     TySched = 41, TyRope = 42,
                     /// A host-minted reference (`DECISIONS.md#opaque-values`). Guest code
                     /// can mint one only with id 0 and no builtin reads an id
                     /// back, so an id is a thing the HOST wrote and only the host
                     /// can read. That is what lets a snapshot preserve identities
                     /// without granting any.
                     TyOpaque = 43,
                     TyBytes = 44, TyBrope = 45, TyTbytes = 46,
                     /// A tagged literal: `[tag, form]`, tag a namespaced
                     /// SYMBOL. Its own type rather than a two-key map
                     /// (`DECISIONS.md#tagged-literals`), because a map is ambiguous with
                     /// a map in every format that has tags and loses the
                     /// namespace wherever the key must become a string.
                     TyTagged = 47,
                     // `DECISIONS.md#tables`: a schema addresses columns by a
                     // stable id, a table carries a row offset so `slice`
                     // shares its chunks, a row ref holds the chunk and not the
                     // table, and the transient writes into an open chunk.
                     TySchema = 48, TyTable = 49, TyTableref = 50, TyTtable = 51,
                     // A WIRE WRITER: `[WR_BUF, WR_LIVE]`. An OPAQUE handle
                     // around a transient byte string, and opaque is the whole
                     // point (`DECISIONS.md#the-codec-is-guest-code`): the guest
                     // appends through the `wire-*` primitives and cannot reach
                     // the buffer, so it cannot write a `K_PORT` tag followed by
                     // an id it does not hold. Not a value: no eq, no hash, no
                     // kind.
                     TyWriter = 52,
                     // A WIRE READER: `[RD_BYTES, RD_POS, RD_LIVE]`. NOT the
                     // writer's mirror image -- a reader may hand out integers
                     // and strings freely, because reading bytes a guest holds
                     // tells it nothing new. `RD_LIVE` guards the two MINTING
                     // reads and is true only for bytes that arrived on a
                     // bridge: the rule `decode_guest` enforced by refusing
                     // tags, as a flag set in one place.
                     TyReader = 53,
                     TyMax = 54;

    /// The three layout classes. Prefixed `L` where the JVM writes `VALS`,
    /// `STR`, `RAW`: C#'s PascalCase would make the layout constant `Str`
    /// collide with the `Str` CLASS, which Java's casing kept apart. The port
    /// is a verbatim mirror of STRUCTURE, and this is one of the few places a
    /// host's own naming rules force a different spelling.
    public const int LVals = 0, LStr = 1, LRaw = 2;

    /// GENERATED (`kin/objsize.kin`). Which of the three shapes `ty` has.
    public static int LayoutOf(int ty) =>
        global::_3sln.Flint.Kgen.Rt.Objsize.LayoutOf(ty);

    public static long Align8(long n) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjAlign8(n);

    /// GENERATED (`kin/objsize.kin`). How many BYTES an object occupies --
    /// and so, after `>> 3`, what every allocation costs in gas.
    public static long SizeFor(int ty, int len) =>
        global::_3sln.Flint.Kgen.Rt.Objsize.SizeFor(ty, len);

    public static long SizeOf(Space sp, long addr) => global::_3sln.Flint.Kgen.Rt.Objsize.ObjSizeOf(sp, addr);

    /// GENERATED (`kin/objhdr.kin`). The header layer is rooted at the SPACE
    /// and not at the `Rt`, which is why it needed a `Space` tag in kin before
    /// it could be generated: native's collector reads these from `&mut self`
    /// methods of `Gc` and cannot borrow the whole `Rt` to do it. The three
    /// copies AGREED before the port, every shift and mask, so this is a
    /// consolidation and not a fix.
    public static int Ty(Space sp, long a) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjTy(sp, a);
    public static int Len(Space sp, long a) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjLen(sp, a);

    public static void WriteHeader(Space sp, long a, int ty, int len) =>
        global::_3sln.Flint.Kgen.Rt.Objhdr.ObjWriteHeader(sp, a, ty, len);

    public static int Age(Space sp, long a) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjAge(sp, a);

    public static void SetAge(Space sp, long a, int age) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjSetAge(sp, a, age);

    public static bool Marked(Space sp, long a) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjMarked(sp, a);

    public static void SetMarked(Space sp, long a, bool m) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjSetMarked(sp, a, m);

    public static bool InRemset(Space sp, long a) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjInRemset(sp, a);

    public static void SetInRemset(Space sp, long a, bool m) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjSetInRemset(sp, a, m);

    /// The ASCII flag is not a micro-optimisation. flint indexes strings by
    /// CODE POINT, so a byte index and a character index coincide only for
    /// ASCII -- without it `subs` and `nth` walk, and splitting a string was
    /// quadratic.
    public static bool StrIsAscii(Space sp, long a) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjStrAscii(sp, a);

    public static void SetStrAscii(Space sp, long a, bool v) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjSetStrAscii(sp, a, v);

    /// Cached at +8, inside the object, so a string carries its own hash and a
    /// map lookup does not walk the bytes twice.
    public static int StrHash(Space sp, long a) => sp.ReadU32(a + Hdr);
    public static void SetStrHash(Space sp, long a, int h) => sp.WriteU32(a + Hdr, h);

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
    public static void SetForward(Space sp, long a, long dest) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjSetForward(sp, a, dest);

    /// GENERATED. The low word is widened by `addr-of-u32`, which ZERO-extends
    /// on all three targets. `to-addr` is the trap and is not a hypothetical:
    /// put it there instead and this port reports -2147483647 and -1 where
    /// native stays correct, because Rust's `as Addr` from a `u32`
    /// zero-extends on its own and an `int` here does not.
    public static long ForwardTarget(Space sp, long a) => global::_3sln.Flint.Kgen.Rt.Objhdr.ObjForwardTarget(sp, a);
}
