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

    static final long SPECIAL_NIL = 0;
    static final long SPECIAL_FALSE = 1;
    static final long SPECIAL_TRUE = 2;
    static final long SPECIAL_NOT_FOUND = 3;
    static final long SPECIAL_PARK = 4;
    static final long SPECIAL_OOM = 5;

    public static final long NIL = (TAG_SPECIAL << 48) | SPECIAL_NIL;
    public static final long FALSE = (TAG_SPECIAL << 48) | SPECIAL_FALSE;
    public static final long TRUE = (TAG_SPECIAL << 48) | SPECIAL_TRUE;
    public static final long NOT_FOUND = (TAG_SPECIAL << 48) | SPECIAL_NOT_FOUND;
    public static final long PARK = (TAG_SPECIAL << 48) | SPECIAL_PARK;
    public static final long OOM = (TAG_SPECIAL << 48) | SPECIAL_OOM;

    /// The top sixteen bits. `>>> 48` and not `>> 48`: an arithmetic shift on a
    /// boxed value sign-extends and every tag comes back as -1.
    public static long tag(long v) { return v >>> 48; }

    public static boolean isDouble(long v) { return tag(v) < TAG_MIN_BOXED; }

    public static double asDouble(long v) { return Double.longBitsToDouble(v); }

    /// A double, unless it is a NaN that would collide with the tag space --
    /// in which case it is canonicalised, because a value that reads back as a
    /// heap pointer is worse than a NaN that lost its payload.
    public static long ofDouble(double d) {
        long b = Double.doubleToRawLongBits(d);
        return (b >>> 48) >= TAG_MIN_BOXED ? Double.doubleToRawLongBits(Double.NaN) : b;
    }

    public static boolean isFixnum(long v) { return tag(v) == TAG_FIXNUM; }

    public static long fixnum(long n) { return (TAG_FIXNUM << 48) | (n & PAYLOAD); }

    /// Sign-extended from 48 bits. A fixnum is a 48-bit signed integer; larger
    /// integers are `TY_BIGINT` on the heap.
    public static long asFixnum(long v) { return (v << 16) >> 16; }

    public static boolean isHeap(long v) { return tag(v) == TAG_HEAP; }

    public static long heap(long addr) { return (TAG_HEAP << 48) | (addr & PAYLOAD); }

    /// MASK, do not merely cast: with a 48-bit address the tag would otherwise
    /// come back as part of the answer.
    public static long asHeap(long v) { return v & PAYLOAD; }

    public static boolean isNil(long v) { return v == NIL; }
    public static boolean isTrue(long v) { return v == TRUE; }
    public static boolean isFalse(long v) { return v == FALSE; }

    /// Only `nil` and `false` are false. Zero, the empty string and the empty
    /// vector are all true.
    public static boolean truthy(long v) { return v != NIL && v != FALSE; }

    public static long bool(boolean b) { return b ? TRUE : FALSE; }
}
