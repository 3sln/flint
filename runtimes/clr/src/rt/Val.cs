namespace Flint.Rt;

/// NaN-boxed values, ported verbatim from `runtime/src/value.rs`.
///
/// Static methods over `long` and not a struct with fields, for the reason the
/// JVM's `Val.java` gives at length: the previous port used `object` for every
/// value, so every integer was boxed, and that was the ceiling rather than a
/// detail. A value IS a `long` and the JIT keeps it in a register.
public static class Val {
    public const long TagHeap = 0xFFF9L;
    public const long TagFixnum = 0xFFFAL;
    public const long TagSpecial = 0xFFFBL;
    public const long TagStr = 0xFFFCL;
    public const long TagKw = 0xFFFDL;
    public const long TagMinBoxed = TagHeap;

    public const long Payload = 0x0000_FFFF_FFFF_FFFFL;

    const long SpecialNil = 0, SpecialFalse = 1, SpecialTrue = 2;
    const long SpecialNotFound = 3, SpecialPark = 4, SpecialOom = 5, SpecialEmpty = 6;

    public const long Nil = (TagSpecial << 48) | SpecialNil;
    public const long False = (TagSpecial << 48) | SpecialFalse;
    public const long True = (TagSpecial << 48) | SpecialTrue;
    public const long NotFound = (TagSpecial << 48) | SpecialNotFound;
    public const long Park = (TagSpecial << 48) | SpecialPark;
    public const long Oom = (TagSpecial << 48) | SpecialOom;

    /// A port ring slot with nothing in it. A slot's own word carries whether it
    /// is vacant, so claiming and filling are ONE compare-and-swap. It must be a
    /// value no program can produce -- `nil` is a perfectly good message -- so
    /// it lives in the special tag space with NOT_FOUND and PARK.
    public const long Empty = (TagSpecial << 48) | SpecialEmpty;

    /// Logical shift, not arithmetic: a signed shift sign-extends and every tag
    /// on a boxed value comes back as -1.
    /// GENERATED (`kin/valtag.kin`) as `TagOf`.
    public static long Tag(long v) => global::_3sln.Flint.Kgen.Rt.Valtag.TagOf(v);

    public static bool IsDouble(long v) => Tag(v) < TagMinBoxed;
    public static double AsDouble(long v) => System.BitConverter.Int64BitsToDouble(v);

    public static long OfDouble(double d) {
        long b = System.BitConverter.DoubleToInt64Bits(d);
        return ((ulong)b >> 48) >= (ulong)TagMinBoxed
            ? System.BitConverter.DoubleToInt64Bits(double.NaN) : b;
    }

    /// A fixnum's payload is 48 bits, signed. Past this a value is a boxed
    /// bigint -- which still answers `int?`, which is why the specialised
    /// integer opcodes have to test rather than assume.
    public const long FixnumMax = (1L << 47) - 1;
    public const long FixnumMin = -(1L << 47);
    /// GENERATED (`kin/valtag.kin`).
    public static bool FitsFixnum(long n) => global::_3sln.Flint.Kgen.Rt.Valtag.FitsFixnum(n);

    public static bool IsFixnum(long v) => Tag(v) == TagFixnum;
    /// GENERATED as `MakeFixnum` -- see the Java copy for why it delegates
    /// rather than being renamed.
    public static long Fixnum(long n) => global::_3sln.Flint.Kgen.Rt.Valtag.MakeFixnum(n);
    /// Sign-extended from 48 bits; larger integers are `TY_BIGINT` on the heap.
    /// GENERATED as `FixnumPayload`.
    public static long AsFixnum(long v) => global::_3sln.Flint.Kgen.Rt.Valtag.FixnumPayload(v);

    public static bool IsHeap(long v) => Tag(v) == TagHeap;
    /// GENERATED as `MakeHeap` -- the one the three runtimes had come apart
    /// on, native ORing the address in whole where this masked it.
    public static long Heap(long addr) => global::_3sln.Flint.Kgen.Rt.Valtag.MakeHeap(addr);
    /// MASK, not merely a cast: a 48-bit address would otherwise come back
    /// carrying its tag.
    /// GENERATED as `HeapPayload`.
    public static long AsHeap(long v) => global::_3sln.Flint.Kgen.Rt.Valtag.HeapPayload(v);

    /// Five, because the payload is 48 bits: 8 for the length and 40 for the
    /// bytes. That covers most keywords and short strings -- so a keyword
    /// comparison is a single 64-bit compare and allocates nothing, which is
    /// what makes maps keyed by keywords cheap.
    public const int InlineMax = 5;

    static long InlineOf(long tag, byte[] bytes) {
        long payload = 0;
        for (int i = 0; i < bytes.Length; i++) payload |= (bytes[i] & 0xFFL) << (8 * i);
        return (tag << 48) | ((long) bytes.Length << 40) | payload;
    }

    public static bool IsInlineStr(long v) => Tag(v) == TagStr;
    public static bool IsInlineKw(long v) => Tag(v) == TagKw;

    /// An inline KEYWORD as the inline STRING of its name -- a tag swap. See
    /// the Rust copy.
    public static long KwToStr(long v) => (v & 0x0000FFFFFFFFFFFFL) | (TagStr << 48);

    public static long InlineStr(byte[] b) => InlineOf(TagStr, b);
    public static long InlineKw(byte[] b) => InlineOf(TagKw, b);

    public static int InlineLen(long v) => (int) (((ulong)v >> 40) & 0xFF);

    public static byte[] InlineBytes(long v) {
        int n = InlineLen(v);
        byte[] outb = new byte[n];
        for (int i = 0; i < n; i++) outb[i] = (byte) (((ulong)v >> (8 * i)) & 0xFF);
        return outb;
    }

    public static bool IsNil(long v) => v == Nil;
    public static bool IsTrue(long v) => v == True;

    /// Is `v` either boolean? See the Java copy.
    public static bool IsBool(long v) => v == True || v == False;
    public static bool IsFalse(long v) => v == False;
    /// Only `nil` and `false` are false.
    public static bool Truthy(long v) => v != Nil && v != False;
    public static long Bool(bool b) => b ? True : False;
}
