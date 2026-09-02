namespace Flint.Rt;

/// The bytecode, from `runtime/src/vm.rs`. One list, three runtimes.
public static class Op {
    // 0x00, 0x10, 0x1D, 0x1F..0x22 are RETIRED, not free (`doc/decisions/0038`).
    public const int Const = 0x01, Nil = 0x02, True = 0x03, False = 0x04,
        Int = 0x05, Local = 0x06, LocalW = 0x07, SetLocal = 0x08, Upval = 0x09,
        Var = 0x0A, SetVar = 0x0B, Pop = 0x0C, Dup = 0x0D, Jump = 0x0E,
        JumpIfFalse = 0x0F, Call = 0x11, TailCall = 0x12,
        Return = 0x13, Closure = 0x14, Native = 0x15, Throw = 0x16, Try = 0x17,
        PopHandler = 0x18, Rethrow = 0x19, Vector = 0x1A, Map = 0x1B, Set = 0x1C,
        Apply = 0x1E, Self = 0x23, AddInt = 0x24,
        SubInt = 0x25, MulInt = 0x26, LtInt = 0x27, LeInt = 0x28, GtInt = 0x29,
        GeInt = 0x2A, EqInt = 0x2B, TypeP = 0x2C,
        // The wide STORE, counterpart of LocalW. Its absence silently
        // truncated a local index to a byte, so binding local 256 wrote
        // local 0 -- on every runtime identically, which is why the
        // cross-runtime suite never saw it.
        SetLocalW = 0x2D;
}
