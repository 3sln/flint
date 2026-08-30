package com.flint.rt;

import java.util.ArrayList;

/// Everything the collector must find, ported from `runtime/src/gc.rs`.
///
/// ## The decision everything else rests on
///
/// `doc/decisions/0001`: values live in explicit arrays the collector walks,
/// never in host locals. On wasm that was forced -- wasm locals are not
/// scannable. Here it is a CHOICE, and the same one, because it is what makes
/// the collector identical across the three runtimes rather than merely
/// equivalent.
///
/// * the VALUE STACK is the interpreter's operands and locals;
/// * the SHADOW STACK is what Java code roots across an allocation, the
///   analogue of the Rust's `push`/`r`/`pop_to`;
/// * globals, consts and singletons are the image's.
public final class Roots {
    /// The interpreter's operand stack. `stackTop` is the live prefix; nothing
    /// above it is scanned, which is what lets a frame drop its operands by
    /// moving one integer.
    public long[] stack = new long[1024];
    public int stackTop = 0;

    /// Values held across an allocation by Java code. A `Value` in a Java local
    /// does not survive a collection -- the collector cannot see it and cannot
    /// update it -- so anything live across an `alloc` goes here.
    public long[] shadow = new long[256];
    public int shadowTop = 0;

    /// The intern tables. WEAK, and scanned by the collector rather than
    /// traced: an entry whose value died is dropped, which is what lets every
    /// short string and keyword be interned without the table being a leak.
    public final Interns[] interns = Interns.tables();

    public long[] globals = new long[0];
    public long[] consts = new long[0];
    /// Sized AND FILLED WITH NIL at construction.
    ///
    /// Both halves matter. Sized, because `SING_BINDINGS` is read before any
    /// image has been loaded and an empty array would be an index error rather
    /// than an absent binding. Filled, because a zero-filled array of values is
    /// NOT a nil-filled one: 0 is the bit pattern of `+0.0`, so an unset slot
    /// read back as the DOUBLE ZERO and `dyn-bindings` answered a number where
    /// a map was expected. The failure surfaced three frames away as "assoc
    /// onto a double", which is why the slot is written rather than left.
    public long[] singletons = newSingletons();

    static long[] newSingletons() {
        long[] s = new long[Rt.SING_COUNT];
        java.util.Arrays.fill(s, Val.NIL);
        return s;
    }

    /// Old objects holding a young pointer. The generational invariant: an old
    /// object pointing at a young one MUST be in here, or the young one is
    /// never traced, dies, and leaves a stale pointer in something still live.
    public final ArrayList<Long> remembered = new ArrayList<>();

    public void vpush(long v) {
        if (stackTop == stack.length) {
            long[] bigger = new long[stack.length * 2];
            System.arraycopy(stack, 0, bigger, 0, stackTop);
            stack = bigger;
        }
        stack[stackTop++] = v;
    }

    public long vpop() { return stack[--stackTop]; }

    /// Root `v` and return its index. Read it back with `r(i)` AFTER any
    /// allocation -- that is the whole discipline, and reading the Java local
    /// instead is the bug this exists to prevent.
    public int push(long v) {
        if (shadowTop == shadow.length) {
            long[] bigger = new long[shadow.length * 2];
            System.arraycopy(shadow, 0, bigger, 0, shadowTop);
            shadow = bigger;
        }
        shadow[shadowTop] = v;
        return shadowTop++;
    }

    public long r(int i) { return shadow[i]; }
    public void setR(int i, long v) { shadow[i] = v; }
    public int mark() { return shadowTop; }
    public void popTo(int n) { shadowTop = n; }

    /// Every root, for the collector to read and REWRITE.
    ///
    /// One loop in one place. Skipping an array would not fail here -- it would
    /// collect those objects out from under the program and fail somewhere
    /// else, later, as a corrupted value in code that did nothing wrong.
    interface Visitor { long visit(long v); }

    void forEach(Visitor f) {
        for (int i = 0; i < stackTop; i++) stack[i] = f.visit(stack[i]);
        for (int i = 0; i < shadowTop; i++) shadow[i] = f.visit(shadow[i]);
        for (int i = 0; i < globals.length; i++) globals[i] = f.visit(globals[i]);
        for (int i = 0; i < consts.length; i++) consts[i] = f.visit(consts[i]);
        for (int i = 0; i < singletons.length; i++) singletons[i] = f.visit(singletons[i]);
    }
}
