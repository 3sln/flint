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

    /// What this executor shares with every other in the same sandbox: var
    /// slots, constants, singletons and the intern tables. One object, pointed
    /// at by all of them.
    public Shared shared = new Shared();

    /// The runtime these roots belong to. Needed only so that registering a new
    /// executor can flip every peer's `safepoints` flag in one place.
    public Rt owner;

    /// Old objects THIS EXECUTOR gave a young pointer to.
    ///
    /// PER-EXECUTOR, and that is the whole reason the write barrier is safe
    /// with several threads running. A shared list pushed to from the barrier
    /// would reallocate under another thread's push, and the barrier is far
    /// hotter than allocation -- locking it would cost more than it protects.
    ///
    /// The collector drains every executor's at a safepoint, which is the only
    /// time anything reads them.
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
        // This executor's own.
        for (int i = 0; i < stackTop; i++) stack[i] = f.visit(stack[i]);
        for (int i = 0; i < shadowTop; i++) shadow[i] = f.visit(shadow[i]);
        // The sandbox's.
        long[] g = shared.globals, c = shared.consts, sg = shared.singletons;
        for (int i = 0; i < g.length; i++) g[i] = f.visit(g[i]);
        for (int i = 0; i < c.length; i++) c[i] = f.visit(c[i]);
        for (int i = 0; i < sg.length; i++) sg[i] = f.visit(sg[i]);
        // Every OTHER executor in this sandbox (`doc/decisions/0028`). A
        // collection happens with all of them PARKED at a safepoint, so nothing
        // is mutating these while they are walked.
        //
        // Skipping one would not fail here. It would collect that thread's live
        // objects out from under it and fail somewhere else, later, as a
        // corrupted value in code that did nothing wrong -- which is why this is
        // one loop in one place rather than a rule to remember.
        for (Roots e : shared.others) {
            if (e == this) continue;
            // A parked executor's roots do not change. If they have, the
            // collector is walking a thread that is still RUNNING -- say so
            // here rather than as an index error four frames down.
            if (e.stackTop > e.stack.length) {
                throw new IllegalStateException(
                    "flint: scanning a RUNNING executor: stackTop " + e.stackTop
                    + " past len " + e.stack.length);
            }
            for (int i = 0; i < e.stackTop; i++) e.stack[i] = f.visit(e.stack[i]);
            for (int i = 0; i < e.shadowTop; i++) e.shadow[i] = f.visit(e.shadow[i]);
        }
    }

    /// Every executor's remembered set, drained together.
    ///
    /// Only correct during a collection, which is the only time every other
    /// executor is stopped. Draining one and not the rest would LOSE
    /// old-to-young edges another thread recorded, and a lost edge is a young
    /// object collected while an old one still points at it.
    ArrayList<Long> drainRemembered() {
        ArrayList<Long> out = new ArrayList<>(remembered);
        remembered.clear();
        for (Roots e : shared.others) {
            if (e == this) continue;
            out.addAll(e.remembered);
            e.remembered.clear();
        }
        return out;
    }
}
