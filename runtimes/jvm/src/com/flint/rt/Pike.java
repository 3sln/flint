package com.flint.rt;

import static com.flint.rt.Obj.*;
import java.util.ArrayList;
import static flint.rt.Pike.*;

/// The Pike VM, ported from `runtime/src/pike.rs` (`doc/decisions/0012`).
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


    static boolean classHit(int[] prog, int classBase, int off, int v) {
        int n = prog[classBase + off];
        for (int k = 0; k < n; k++) {
            int b = classBase + off + 1 + k * 3;
            boolean hit;
            switch (prog[b]) {
                case CL_ONE: hit = v == prog[b + 1]; break;
                case CL_RANGE: hit = v >= prog[b + 1] && v <= prog[b + 2]; break;
                default: hit = predHit(prog[b + 1], v);
            }
            if (hit) return true;
        }
        return false;
    }

    static final class Thread {
        final int pc;
        final int[] saved;
        Thread(int pc, int[] saved) { this.pc = pc; this.saved = saved; }
    }

    /// Add `pc` and everything reachable from it WITHOUT consuming a character.
    ///
    /// The dedup set is what makes this linear, and the ORDER is what makes it
    /// leftmost-first: SPLIT's preferred branch is followed first, and whoever
    /// arrives first at an instruction keeps it.
    static void addThread(int[] prog, int codeBase, int classBase, int[] cps, int i,
                          ArrayList<Thread> list, boolean[] seen, int pc, int[] saved) {
        if (seen[pc]) return;
        seen[pc] = true;
        int b = codeBase + pc * 3;
        int op = prog[b], a = prog[b + 1], c = prog[b + 2];
        switch (op) {
            case OP_JMP:
                addThread(prog, codeBase, classBase, cps, i, list, seen, a, saved);
                break;
            case OP_SPLIT:
                addThread(prog, codeBase, classBase, cps, i, list, seen, a, saved);
                addThread(prog, codeBase, classBase, cps, i, list, seen, c, saved);
                break;
            case OP_SAVE: {
                int[] s2 = saved.clone();
                if (a < s2.length) s2[a] = i;
                addThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, s2);
                break;
            }
            case OP_BOL:
                if (i == 0) addThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                break;
            case OP_EOL:
                if (i == cps.length) addThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                break;
            case OP_WORDB:
            case OP_NWORDB: {
                boolean before = i > 0 && wordCp(cps[i - 1]);
                boolean after = i < cps.length && wordCp(cps[i]);
                boolean at = before != after;
                if ((op == OP_WORDB) == at) {
                    addThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                }
                break;
            }
            default:
                list.add(new Thread(pc, saved));
        }
    }

    static boolean consumes(int[] prog, int codeBase, int classBase, int pc, int v) {
        int b = codeBase + pc * 3;
        switch (prog[b]) {
            case OP_CHAR: return v == prog[b + 1];
            // NOT a newline. Java's `.` excludes it without DOTALL, and the
            // backtracker this replaced matched it -- the divergence is closed
            // here rather than left to the host.
            case OP_ANY: return v != 10;
            case OP_CLASS: {
                boolean hit = classHit(prog, classBase, prog[b + 1], v);
                return prog[b + 2] == 1 ? !hit : hit;
            }
            default: return false;
        }
    }

    /// The simulator proper: no `Rt`, so it can be run many times over ONE
    /// decoding of the subject. That is what `reFindAll` needs, and giving it
    /// `reRun` in a loop was quadratic -- each call decoded the whole subject
    /// again.
    static int[] runOver(int[] prog, int ninstrs, int nslots, int[] cps,
                         int from, int entry, boolean full) {
        int codeBase = PROG_HDR;
        int classBase = PROG_HDR + ninstrs * 3;
        if (from > cps.length) return null;
        ArrayList<Thread> clist = new ArrayList<>();
        ArrayList<Thread> nlist = new ArrayList<>();
        boolean[] seen = new boolean[ninstrs];
        int[] start = new int[nslots];
        java.util.Arrays.fill(start, -1);
        addThread(prog, codeBase, classBase, cps, from, clist, seen, entry, start);
        int[] best = null;
        int i = from;
        for (;;) {
            if (clist.isEmpty()) break;
            boolean have = i < cps.length;
            int v = have ? cps[i] : 0;
            nlist.clear();
            java.util.Arrays.fill(seen, false);
            for (Thread t : clist) {
                int op = prog[codeBase + t.pc * 3];
                if (op == OP_MATCH) {
                    // A FULL match must reach the end. Rejecting rather than
                    // cutting is what lets a LOWER-priority alternative win --
                    // `(a|ab)` against "ab" is `ab`, which a backtracker gets
                    // by backtracking against the anchor and this gets by
                    // carrying both threads.
                    if (!full || i == cps.length) { best = t.saved; break; }
                    continue;
                }
                if (have && consumes(prog, codeBase, classBase, t.pc, v)) {
                    addThread(prog, codeBase, classBase, cps, i + 1, nlist, seen, t.pc + 1, t.saved);
                }
            }
            ArrayList<Thread> swap = clist; clist = nlist; nlist = swap;
            if (i >= cps.length) break;
            i++;
        }
        return best;
    }

    /// The code points of a string, in order.
    static int[] codePoints(Rt rt, long s) {
        byte[] b = Str.bytes(rt, s);
        int[] out = new int[b.length];
        int n = 0, i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF, cp;
            if (c < 0x80) { cp = c; i += 1; }
            else if ((c & 0xE0) == 0xC0) { cp = ((c & 0x1F) << 6) | (b[i+1] & 0x3F); i += 2; }
            else if ((c & 0xF0) == 0xE0) {
                cp = ((c & 0x0F) << 12) | ((b[i+1] & 0x3F) << 6) | (b[i+2] & 0x3F); i += 3;
            } else {
                cp = ((c & 0x07) << 18) | ((b[i+1] & 0x3F) << 12)
                   | ((b[i+2] & 0x3F) << 6) | (b[i+3] & 0x3F); i += 4;
            }
            out[n++] = cp;
        }
        int[] exact = new int[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
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
        int[] prog = progOf(rt, re);
        int ninstrs = prog[0];
        int nslots = (prog[2] + 1) * 2;
        int[] cps = codePoints(rt, s);
        int[] best = runOver(prog, ninstrs, nslots, cps, (int) Math.max(from, 0), entry, full);
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
        int[] prog = progOf(rt, re);
        int ninstrs = prog[0];
        int nslots = (prog[2] + 1) * 2;
        int[] cps = codePoints(rt, s);
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
