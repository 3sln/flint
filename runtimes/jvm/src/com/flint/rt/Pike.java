package com.flint.rt;

import static com.flint.rt.Obj.*;
import java.util.ArrayList;
import static com._3sln.flint.kgen.rt.Pike.*;

/// The Pike VM, ported from `runtime/src/pike.rs` (`DECISIONS.md#matching-over-ropes`).
///
/// One left-to-right pass carrying a list of live threads, consuming each
/// character exactly once and never rewinding.
///
/// LINEAR BY CONSTRUCTION. Threads are deduplicated by program counter, so a
/// position holds at most one thread per instruction however many ways the
/// pattern could have reached it -- which is why `(a+)+b` is linear here and
/// exponential in a backtracker.
///
/// LEFTMOST-FIRST (Perl) semantics: `addThread` follows SPLIT's preferred
/// branch first and the dedup set keeps whichever arrived first, so an earlier
/// alternative wins exactly as a backtracker's would. A thread reaching MATCH
/// cuts every LOWER-priority thread; the ones already carried forward have
/// higher priority and may still beat it.
///
/// The pattern is compiled to a program by flint's own library, in Clojure, and
/// arrives here as a vector of words. That split is why this file is the same
/// on every runtime: there is no host regex engine anywhere in it, and so no
/// way for two hosts to disagree about what a pattern means.
public final class Pike {
    private Pike() {}

    public static final int OP_CHAR = 0, OP_ANY = 1, OP_SPLIT = 2, OP_JMP = 3,
        OP_SAVE = 4, OP_MATCH = 5, OP_BOL = 6, OP_EOL = 7, OP_WORDB = 8,
        OP_NWORDB = 9, OP_CLASS = 10;

    static final int CL_ONE = 0, CL_RANGE = 1, CL_PRED = 2;

    /// Header words of a compiled program: instruction count, class-table
    /// length, group count. Then the code, then the class table.
    public static final int PROG_HDR = 3;

    /// `TY_REGEX` slots.
    public static final int RX_SOURCE = 0, RX_PROG = 1, RX_NGROUPS = 2;


    /// GENERATED (`kin/pike.kin`). This port's signature is the one the
    /// generated function took -- the program whole, indexed from a base --
    /// because it is the shape every target can spell.
    static boolean classHit(int[] prog, int classBase, int off, int v) {
        return com._3sln.flint.kgen.rt.Pike.classHit(prog, classBase, off, v);
    }

    // `Thread` IS GONE. A thread is a flat row in the arena now: `pc` then
    // its capture slots. The object existed to own a slot array per thread,
    // and the per-thread allocation went with it.

    /// Add `pc` and everything reachable from it WITHOUT consuming a character.
    ///
    /// The dedup set is what makes this linear, and the ORDER is what makes it
    /// leftmost-first: SPLIT's preferred branch is followed first, and whoever
    /// arrives first at an instruction keeps it.
    // `addThread` IS GENERATED (`kin/pike.kin`) and takes the arena rather
    // than a list and a struct. Nothing here calls it directly any more --
    // the generated `runOver` does.

    /// GENERATED (`kin/pike.kin`). Asked once per live thread per character,
    /// so the program stays indexed rather than sliced or copied.
    static boolean consumes(int[] prog, int codeBase, int classBase, int pc, int v) {
        return com._3sln.flint.kgen.rt.Pike.consumes(prog, codeBase, classBase, pc, v);
    }

    /// The simulator proper: no `Rt`, so it can be run many times over ONE
    /// decoding of the subject. That is what `reFindAll` needs, and giving it
    /// `reRun` in a loop was quadratic -- each call decoded the whole subject
    /// again.
    /// GENERATED (`kin/pike.kin`). This is the arena the simulator works in;
    /// the simulation itself is one source for all three runtimes now.
    ///
    ///     [ list A ][ list B ][ scratch ][ start ][ best ]
    ///
    /// A list is `ninstrs` rows of `1 + nslots` -- `pc` then its capture slots
    /// -- and that bound is exact because `seen` admits each pc at most once
    /// per character. The old shape was a growable list of a `Thread` object
    /// holding its own slot array, which is two things kin has neither of and
    /// neither of which turned out to be needed.
    static int[] runOver(int[] prog, int ninstrs, int nslots, int[] cps,
                         int from, int entry, boolean full) {
        int width = nslots + 1;
        int aAt = 0, bAt = ninstrs * width;
        int scratchAt = 2 * ninstrs * width;
        int startAt = scratchAt + ninstrs * nslots;
        int bestAt = startAt + nslots;
        int[] mem = new int[bestAt + nslots];
        java.util.Arrays.fill(mem, -1);
        boolean[] seen = new boolean[ninstrs];
        boolean hit = com._3sln.flint.kgen.rt.Pike.runOver(
            prog, ninstrs, nslots, cps, cps.length, from, entry, full,
            mem, aAt, bAt, scratchAt, startAt, bestAt, seen);
        return hit ? java.util.Arrays.copyOfRange(mem, bestAt, bestAt + nslots) : null;
    }


    /// Build a `TY_REGEX` from a program the shared cljc compiler emitted.
    ///
    /// `words` is a vector of fixnums: `[ninstrs, nclasses, ngroups, code...,
    /// classes...]`. It is copied into a `TY_RAW` blob ONCE, so the hot loop
    /// reads plain words rather than walking a persistent vector per
    /// instruction.
    public static long compile(Rt rt, long source, long words) {
        int n = Vec.count(rt, words);
        if (n < PROG_HDR) return rt.throwStr("IllegalArgumentException", "regex: malformed program");
        com._3sln.flint.kgen.rt.Pikegas.chargeCompile(rt, n);
        int[] raw = new int[n];
        for (int k = 0; k < n; k++) raw[k] = (int) Val.asFixnum(Vec.nth(rt, words, k, Val.NOT_FOUND));
        int base = rt.mark();
        int si = rt.push(source);
        long blob = rt.alloc(TY_RAW, raw.length * 4);
        if (blob == 0) { rt.popTo(base); return Val.NIL; }
        for (int k = 0; k < raw.length; k++) rt.gc.sp.writeU32(blob + HDR + (long) k * 4, raw[k]);
        int bi = rt.push(Val.heap(blob));
        long a = rt.alloc(TY_REGEX, 3);
        if (a == 0) { rt.popTo(base); return Val.NIL; }
        rt.setSlot(a, RX_SOURCE, rt.r(si));
        rt.setSlot(a, RX_PROG, rt.r(bi));
        rt.setSlot(a, RX_NGROUPS, Val.fixnum(raw[2]));
        rt.popTo(base);
        return Val.heap(a);
    }

    static int[] progOf(Rt rt, long re) {
        long blob = rt.slot(re, RX_PROG);
        int n = len(rt.gc.sp, Val.asHeap(blob)) / 4;
        int[] prog = new int[n];
        long a = Val.asHeap(blob) + HDR;
        for (int k = 0; k < n; k++) prog[k] = rt.gc.sp.readU32(a + (long) k * 4);
        return prog;
    }

    static long slotsVector(Rt rt, int[] slots) {
        int base = rt.mark();
        int vi = rt.push(Vec.empty(rt));
        for (int x : slots) rt.setR(vi, Vec.conj(rt, rt.r(vi), Val.fixnum(x)));
        long out = rt.r(vi);
        rt.popTo(base);
        return out;
    }

    /// Match at `from`, returning `[s0 e0 s1 e1 ...]` or nil. Positions are
    /// CODE-POINT indices and `-1` marks a group that did not participate.
    public static long run(Rt rt, long re, long s, long from, int entry, boolean full) {
        if (!rt.isHeapTy(re, TY_REGEX)) return rt.throwStr("ClassCastException", "not a compiled pattern");
        if (!Str.isString(rt, s)) return rt.throwStr("ClassCastException", "re-run wants a string");
        com._3sln.flint.kgen.rt.Pikegas.chargeSubject(rt, s);
        int[] prog = progOf(rt, re);
        int ninstrs = prog[0];
        int nslots = (prog[2] + 1) * 2;
        int[] cps = rt.cpsTake(com._3sln.flint.kgen.rt.Codepoints.codePoints(rt, s));
        // CLAMPED BEFORE THE NARROWING, and that order is the whole of the
        // fix (`DECISIONS.md#the-pike-vm-is-the-last-triplicate`). `from` is whatever integer a guest program passed, and
        // `(int) Math.max(from, 0)` on 2^31 is -2147483648 -- which passes
        // `runOver`'s `from > cps.length` guard and then indexes `cps`
        // negatively, so guest code could throw an ArrayIndexOutOfBounds out
        // of the host. Native holds it in an `i64` and answers nil.
        long start = Math.max(from, 0);
        if (start > cps.length) return Val.NIL;
        int[] best = runOver(prog, ninstrs, nslots, cps, (int) start, entry, full);
        return best == null ? Val.NIL : slotsVector(rt, best);
    }

    /// EVERY match, in ONE left-to-right pass.
    ///
    /// This is what `split`, `re-seq` and `replace` actually want, and giving
    /// them `run` in a loop was quadratic: each call decoded the whole subject
    /// again. Returns a flat vector, `nslots` entries per match, back to back.
    public static long findAll(Rt rt, long re, long s, long limit) {
        if (!rt.isHeapTy(re, TY_REGEX)) return rt.throwStr("ClassCastException", "not a compiled pattern");
        if (!Str.isString(rt, s)) return rt.throwStr("ClassCastException", "re-find-all wants a string");
        com._3sln.flint.kgen.rt.Pikegas.chargeSubject(rt, s);
        int[] prog = progOf(rt, re);
        int ninstrs = prog[0];
        int nslots = (prog[2] + 1) * 2;
        int[] cps = rt.cpsTake(com._3sln.flint.kgen.rt.Codepoints.codePoints(rt, s));
        ArrayList<Integer> found = new ArrayList<>();
        int at = 0;
        long count = 0;
        while (at <= cps.length) {
            if (limit > 0 && count >= limit) break;
            int[] slots = runOver(prog, ninstrs, nslots, cps, at, 0, false);
            if (slots == null) break;
            int st = slots[0], en = slots[1];
            for (int x : slots) found.add(x);
            count++;
            // An EMPTY match must still advance, or this never ends.
            at = en > st ? en : en + 1;
        }
        int[] flat = new int[found.size()];
        for (int i = 0; i < flat.length; i++) flat[i] = found.get(i);
        return slotsVector(rt, flat);
    }
}
