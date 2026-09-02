package com.flint.rt;

/// The bytecode, from `runtime/src/vm.rs`. One list, three runtimes.
public final class Op {
    private Op() {}
    // 0x00, 0x10, 0x1D and 0x1F..0x22 are RETIRED, not free. `nop`,
    // `jump-if-true`, `list`, the two `*-keep` jumps, `pop-n` and
    // `set-local-keep` were implemented in all three runtimes and emitted by
    // nothing (`doc/decisions/0038`). The numbers are not reused, so an image
    // built before this cannot be silently misread as something else.
    public static final int CONST = 0x01, NIL = 0x02, TRUE = 0x03, FALSE = 0x04,
        INT = 0x05, LOCAL = 0x06, LOCAL_W = 0x07, SET_LOCAL = 0x08, UPVAL = 0x09,
        VAR = 0x0A, SET_VAR = 0x0B, POP = 0x0C, DUP = 0x0D, JUMP = 0x0E,
        JUMP_IF_FALSE = 0x0F, CALL = 0x11, TAIL_CALL = 0x12,
        RETURN = 0x13, CLOSURE = 0x14, NATIVE = 0x15, THROW = 0x16, TRY = 0x17,
        POP_HANDLER = 0x18, RETHROW = 0x19, VECTOR = 0x1A, MAP = 0x1B, SET = 0x1C,
        APPLY = 0x1E, SELF = 0x23, ADD_INT = 0x24,
        SUB_INT = 0x25, MUL_INT = 0x26, LT_INT = 0x27, LE_INT = 0x28, GT_INT = 0x29,
        GE_INT = 0x2A, EQ_INT = 0x2B, TYPE_P = 0x2C,
        // The wide STORE, counterpart of LOCAL_W. Its absence silently
        // truncated a local index to a byte, so binding local 256 wrote
        // local 0 -- on every runtime identically, which is why the
        // cross-runtime suite never saw it.
        SET_LOCAL_W = 0x2D;
}
