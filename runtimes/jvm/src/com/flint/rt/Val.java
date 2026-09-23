package com.flint.rt;

/// NaN-boxed values, ported verbatim from `runtime/src/value.rs`.
///
/// ## Why this is static methods over `long` and not a class
///
/// A `Value` is sixty-four bits and nothing else. The previous JVM port used
/// `Object` for every value, which meant every integer was a boxed `Long` --
/// measured at 85 ns per iteration on a counting loop, with the AOT emitter
/// buying 1% because it boxed in exactly the same places the interpreter did.
/// Boxing is not a detail here; it was the ceiling.
///
/// So there is no `Value` object. A value IS a `long`, the operations are
/// static, and the JIT keeps it in a register.
///
/// ## The encoding
///
/// A double is itself. Everything else hides in the NaN space: the top sixteen
/// bits are a tag above `TAG_MIN_BOXED`, and the low forty-eight are the
/// payload. That is what gives a heap address 48 bits (256 TB) and a fixnum 48
/// bits of signed range.
public final class Val {
    private Val() {}

    public static final long TAG_HEAP = 0xFFF9L;
    public static final long TAG_FIXNUM = 0xFFFAL;
    public static final long TAG_SPECIAL = 0xFFFBL;
    public static final long TAG_STR = 0xFFFCL;
    public static final long TAG_KW = 0xFFFDL;
    public static final long TAG_MIN_BOXED = TAG_HEAP;

    public static final long PAYLOAD = 0x0000_FFFF_FFFF_FFFFL;

    /// The NaN an out-of-range double canonicalises to, named rather than
    /// derived. This was `Double.doubleToRawLongBits(Double.NaN)` and the clr
    /// wrote the same expression with ITS host's NaN -- which is
    /// `0xFFF8000000000000`, the NEGATIVE canonical NaN, where java's and
    /// native's is positive. Measured 2026-09-22: the two ports disagreed in
    /// the bits for every double that reaches this arm. Both are NaN, so `=`
    /// never noticed; hashing, snapshots and the wire codec would.
    public static final long CANONICAL_NAN = 0x7FF8_0000_0000_0000L;

    static final long SPECIAL_NIL = 0;
    static final long SPECIAL_FALSE = 1;
    static final long SPECIAL_TRUE = 2;
    static final long SPECIAL_NOT_FOUND = 3;
    static final long SPECIAL_PARK = 4;
    static final long SPECIAL_OOM = 5;
    static final long SPECIAL_EMPTY = 6;

    public static final long NIL = (TAG_SPECIAL << 48) | SPECIAL_NIL;
    public static final long FALSE = (TAG_SPECIAL << 48) | SPECIAL_FALSE;
    public static final long TRUE = (TAG_SPECIAL << 48) | SPECIAL_TRUE;
    public static final long NOT_FOUND = (TAG_SPECIAL << 48) | SPECIAL_NOT_FOUND;
    public static final long PARK = (TAG_SPECIAL << 48) | SPECIAL_PARK;
    public static final long OOM = (TAG_SPECIAL << 48) | SPECIAL_OOM;

    /// A port ring slot with nothing in it. A slot's own word carries whether it
    /// is vacant, so claiming and filling are ONE compare-and-swap. It must be a
    /// value no program can produce -- `nil` is a perfectly good message -- so
    /// it lives in the special tag space with NOT_FOUND and PARK.
    public static final long EMPTY = (TAG_SPECIAL << 48) | SPECIAL_EMPTY;

    /// The top sixteen bits. `>>> 48` and not `>> 48`: an arithmetic shift on a
    /// boxed value sign-extends and every tag comes back as -1.
    /// GENERATED (`kin/valtag.kin`) as `tagOf`.
    public static long tag(long v) {
        return com._3sln.flint.kgen.rt.Valtag.tagOf(v);
    }

    /// GENERATED (`kin/valtag.kin`) as `doubleTagged`.
    public static boolean isDouble(long v) { return com._3sln.flint.kgen.rt.Valtag.doubleTagged(v); }

    /// GENERATED as `doubleValue` -- a reinterpretation, not a conversion.
    public static double asDouble(long v) { return com._3sln.flint.kgen.rt.Valtag.doubleValue(v); }

    /// A double, unless it is a NaN that would collide with the tag space --
    /// in which case it is canonicalised, because a value that reads back as a
    /// heap pointer is worse than a NaN that lost its payload.
    /// GENERATED (`kin/valtag.kin`) as `makeDouble`.
    public static long ofDouble(double d) { return com._3sln.flint.kgen.rt.Valtag.makeDouble(d); }

    /// A fixnum's payload is 48 bits, signed. Past this a value is a boxed
    /// bigint -- which still answers `int?`, which is why the specialised
    /// integer opcodes have to test rather than assume.
    public static final long FIXNUM_MAX = (1L << 47) - 1;
    public static final long FIXNUM_MIN = -(1L << 47);
    /// GENERATED (`kin/valtag.kin`).
    public static boolean fitsFixnum(long n) {
        return com._3sln.flint.kgen.rt.Valtag.fitsFixnum(n);
    }

    /// GENERATED as `fixnumTagged`.
    public static boolean isFixnum(long v) { return com._3sln.flint.kgen.rt.Valtag.fixnumTagged(v); }

    /// GENERATED as `makeFixnum`. A delegator rather than a rename: the
    /// generated code lands in a different class here, and `Val.fixnum` is
    /// the name every caller and every kin vocabulary word already uses.
    public static long fixnum(long n) {
        return com._3sln.flint.kgen.rt.Valtag.makeFixnum(n);
    }

    /// Sign-extended from 48 bits. A fixnum is a 48-bit signed integer; larger
    /// integers are `TY_BIGINT` on the heap.
    /// GENERATED as `fixnumPayload`.
    public static long asFixnum(long v) {
        return com._3sln.flint.kgen.rt.Valtag.fixnumPayload(v);
    }

    /// GENERATED as `heapTagged`.
    public static boolean isHeap(long v) { return com._3sln.flint.kgen.rt.Valtag.heapTagged(v); }

    /// GENERATED as `makeHeap`. This is the one the three runtimes had come
    /// apart on -- native ORed the address in whole where this masked it --
    /// and one source is what stops it happening again.
    public static long heap(long addr) {
        return com._3sln.flint.kgen.rt.Valtag.makeHeap(addr);
    }

    /// MASK, do not merely cast: with a 48-bit address the tag would otherwise
    /// come back as part of the answer.
    /// GENERATED as `heapPayload`.
    public static long asHeap(long v) {
        return com._3sln.flint.kgen.rt.Valtag.heapPayload(v);
    }

    /// A string of five bytes or fewer lives IN the value, not on the heap.
    ///
    /// Five, because the payload is 48 bits: 8 for the length and 40 for the
    /// bytes. That covers most keywords and short strings, which is most of
    /// them -- so a keyword comparison is a single 64-bit compare and allocates
    /// nothing, which is what makes maps keyed by keywords cheap.
    public static final int INLINE_MAX = 5;

    static long inlineOf(long tag, byte[] bytes) {
        long payload = 0;
        for (int i = 0; i < bytes.length; i++) payload |= (bytes[i] & 0xFFL) << (8 * i);
        return (tag << 48) | ((long) bytes.length << 40) | payload;
    }

    /// GENERATED (`kin/valtag.kin`) as `inlineString`.
    public static boolean isInlineStr(long v) { return com._3sln.flint.kgen.rt.Valtag.inlineString(v); }
    /// GENERATED as `inlineKeyword`.
    public static boolean isInlineKw(long v) { return com._3sln.flint.kgen.rt.Valtag.inlineKeyword(v); }

    /// An inline KEYWORD as the inline STRING of its name -- a TAG SWAP, not a
    /// rebuild. See the Rust copy: `inlineStr(inlineBytes(v))` allocates a byte
    /// array to copy eight bytes onto themselves.
    /// GENERATED as `kwToStr`. This spelled the payload mask out as a
    /// literal and so did the clr and native -- three copies, each with
    /// `PAYLOAD` declared in the same file.
    public static long kwToStr(long v) { return com._3sln.flint.kgen.rt.Valtag.kwAsStr(v); }

    public static long inlineStr(byte[] b) { return inlineOf(TAG_STR, b); }
    public static long inlineKw(byte[] b) { return inlineOf(TAG_KW, b); }

    /// GENERATED as `inlineLength`.
    public static int inlineLen(long v) { return (int) com._3sln.flint.kgen.rt.Valtag.inlineLength(v); }

    public static byte[] inlineBytes(long v) {
        int n = inlineLen(v);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) ((v >>> (8 * i)) & 0xFF);
        return out;
    }

    public static boolean isNil(long v) { return com._3sln.flint.kgen.rt.Valtag.isNil(v); }

    /// Is `v` either boolean? Rust has `is_bool` on the value; both ports had
    /// only `v == TRUE || v == FALSE` spelled out at each site.
    public static boolean isBool(long v) { return v == TRUE || v == FALSE; }

    /// Only `nil` and `false` are false. Zero, the empty string and the empty
    /// vector are all true.
    /// GENERATED. nil and false are the only false things -- a fixnum 0 is
    /// TRUE, which is the case a port gets wrong by testing for zero.
    public static boolean truthy(long v) { return com._3sln.flint.kgen.rt.Valtag.truthy(v); }

    public static long bool(boolean b) { return b ? TRUE : FALSE; }
}
