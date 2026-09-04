namespace Flint.Rt;

using static _3sln.Flint.Kgen.Rt.Pike;


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
public static class Pike {

    public const int OP_CHAR = 0, OP_ANY = 1, OP_SPLIT = 2, OP_JMP = 3,
        OP_SAVE = 4, OP_MATCH = 5, OP_BOL = 6, OP_EOL = 7, OP_WORDB = 8,
        OP_NWORDB = 9, OP_CLASS = 10;

    const int CL_ONE = 0, CL_RANGE = 1, CL_PRED = 2;

    /// Header words of a compiled program: instruction count, class-table
    /// length, group count. Then the code, then the class table.
    public const int PROG_HDR = 3;

    /// `Obj.TyRegex` slots.
    public const int RX_SOURCE = 0, RX_PROG = 1, RX_NGROUPS = 2;


    static bool ClassHit(int[] prog, int classBase, int off, int v) {
        int n = prog[classBase + off];
        for (int k = 0; k < n; k++) {
            int b = classBase + off + 1 + k * 3;
            bool hit;
            switch (prog[b]) {
                case CL_ONE: hit = v == prog[b + 1]; break;
                case CL_RANGE: hit = v >= prog[b + 1] && v <= prog[b + 2]; break;
                default: hit = PredHit(prog[b + 1], v); break;
            }
            if (hit) return true;
        }
        return false;
    }

    sealed class Thread {
        public readonly int pc;
        public readonly int[] saved;
        public Thread(int pc, int[] saved) { this.pc = pc; this.saved = saved; }
    }

    /// Add `pc` and everything reachable from it WITHOUT consuming a character.
    ///
    /// The dedup set is what makes this linear, and the ORDER is what makes it
    /// leftmost-first: SPLIT's preferred branch is followed first, and whoever
    /// arrives first at an instruction keeps it.
    static void AddThread(int[] prog, int codeBase, int classBase, int[] cps, int i,
                          List<Thread> list, bool[] seen, int pc, int[] saved) {
        if (seen[pc]) return;
        seen[pc] = true;
        int b = codeBase + pc * 3;
        int op = prog[b], a = prog[b + 1], c = prog[b + 2];
        switch (op) {
            case OP_JMP:
                AddThread(prog, codeBase, classBase, cps, i, list, seen, a, saved);
                break;
            case OP_SPLIT:
                AddThread(prog, codeBase, classBase, cps, i, list, seen, a, saved);
                AddThread(prog, codeBase, classBase, cps, i, list, seen, c, saved);
                break;
            case OP_SAVE: {
                int[] s2 = (int[]) saved.Clone();
                if (a < s2.Length) s2[a] = i;
                AddThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, s2);
                break;
            }
            case OP_BOL:
                if (i == 0) AddThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                break;
            case OP_EOL:
                if (i == cps.Length) AddThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                break;
            case OP_WORDB:
            case OP_NWORDB: {
                bool before = i > 0 && WordCp(cps[i - 1]);
                bool after = i < cps.Length && WordCp(cps[i]);
                bool at = before != after;
                if ((op == OP_WORDB) == at) {
                    AddThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                }
                break;
            }
            default:
                list.Add(new Thread(pc, saved));
                break;
        }
    }

    static bool Consumes(int[] prog, int codeBase, int classBase, int pc, int v) {
        int b = codeBase + pc * 3;
        switch (prog[b]) {
            case OP_CHAR: return v == prog[b + 1];
            // NOT a newline. Java's `.` excludes it without DOTALL, and the
            // backtracker this replaced matched it -- the divergence is closed
            // here rather than left to the host.
            case OP_ANY: return v != 10;
            case OP_CLASS: {
                bool hit = ClassHit(prog, classBase, prog[b + 1], v);
                return prog[b + 2] == 1 ? !hit : hit;
            }
            default: return false;
        }
    }

    /// The simulator proper: no `Rt`, so it can be run many times over ONE
    /// decoding of the subject. That is what `reFindAll` needs, and giving it
    /// `reRun` in a loop was quadratic -- each call decoded the whole subject
    /// again.
    static int[] RunOver(int[] prog, int ninstrs, int nslots, int[] cps,
                         int from, int entry, bool full) {
        int codeBase = PROG_HDR;
        int classBase = PROG_HDR + ninstrs * 3;
        if (from > cps.Length) return null;
        List<Thread> clist = new List<Thread>();
        List<Thread> nlist = new List<Thread>();
        bool[] seen = new bool[ninstrs];
        int[] start = new int[nslots];
        System.Array.Fill(start, -1);
        AddThread(prog, codeBase, classBase, cps, from, clist, seen, entry, start);
        int[] best = null;
        int i = from;
        for (;;) {
            if (clist.Count == 0) break;
            bool have = i < cps.Length;
            int v = have ? cps[i] : 0;
            nlist.Clear();
            System.Array.Fill(seen, false);
            foreach (Thread t in clist) {
                int op = prog[codeBase + t.pc * 3];
                if (op == OP_MATCH) {
                    // A FULL match must reach the end. Rejecting rather than
                    // cutting is what lets a LOWER-priority alternative win --
                    // `(a|ab)` against "ab" is `ab`, which a backtracker gets
                    // by backtracking against the anchor and this gets by
                    // carrying both threads.
                    if (!full || i == cps.Length) { best = t.saved; break; }
                    continue;
                }
                if (have && Consumes(prog, codeBase, classBase, t.pc, v)) {
                    AddThread(prog, codeBase, classBase, cps, i + 1, nlist, seen, t.pc + 1, t.saved);
                }
            }
            List<Thread> swap = clist; clist = nlist; nlist = swap;
            if (i >= cps.Length) break;
            i++;
        }
        return best;
    }

    /// The code points of a string, in order.
    static int[] CodePoints(Rt rt, long s) {
        byte[] b = Str.Bytes(rt, s);
        int[] outv = new int[b.Length];
        int n = 0, i = 0;
        while (i < b.Length) {
            int c = b[i] & 0xFF, cp;
            if (c < 0x80) { cp = c; i += 1; }
            else if ((c & 0xE0) == 0xC0) { cp = ((c & 0x1F) << 6) | (b[i+1] & 0x3F); i += 2; }
            else if ((c & 0xF0) == 0xE0) {
                cp = ((c & 0x0F) << 12) | ((b[i+1] & 0x3F) << 6) | (b[i+2] & 0x3F); i += 3;
            } else {
                cp = ((c & 0x07) << 18) | ((b[i+1] & 0x3F) << 12)
                   | ((b[i+2] & 0x3F) << 6) | (b[i+3] & 0x3F); i += 4;
            }
            outv[n++] = cp;
        }
        int[] exact = new int[n];
        System.Array.Copy(outv, 0, exact, 0, n);
        return exact;
    }

    /// Build a `Obj.TyRegex` from a program the shared cljc compiler emitted.
    ///
    /// `words` is a vector of fixnums: `[ninstrs, nclasses, ngroups, code...,
    /// classes...]`. It is copied into a `Obj.TyRaw` blob ONCE, so the hot loop
    /// reads plain words rather than walking a persistent vector per
    /// instruction.
    public static long Compile(Rt rt, long source, long words) {
        int n = Vec.Count(rt, words);
        if (n < PROG_HDR) return rt.ThrowStr("IllegalArgumentException", "regex: malformed program");
        int[] raw = new int[n];
        for (int k = 0; k < n; k++) raw[k] = (int) Val.AsFixnum(Vec.Nth(rt, words, k, Val.NotFound));
        int bas = rt.Mark();
        int si = rt.Push(source);
        long blob = rt.Alloc(Obj.TyRaw, raw.Length * 4);
        if (blob == 0) { rt.PopTo(bas); return Val.Nil; }
        for (int k = 0; k < raw.Length; k++) rt.gc.sp.WriteU32(blob + Obj.Hdr + (long) k * 4, raw[k]);
        int bi = rt.Push(Val.Heap(blob));
        long a = rt.Alloc(Obj.TyRegex, 3);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, RX_SOURCE, rt.R(si));
        rt.SetSlot(a, RX_PROG, rt.R(bi));
        rt.SetSlot(a, RX_NGROUPS, Val.Fixnum(raw[2]));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    static int[] ProgOf(Rt rt, long re) {
        long blob = rt.Slot(re, RX_PROG);
        int n = Obj.Len(rt.gc.sp, Val.AsHeap(blob)) / 4;
        int[] prog = new int[n];
        long a = Val.AsHeap(blob) + Obj.Hdr;
        for (int k = 0; k < n; k++) prog[k] = rt.gc.sp.ReadU32(a + (long) k * 4);
        return prog;
    }

    static long SlotsVector(Rt rt, int[] slots) {
        int bas = rt.Mark();
        int vi = rt.Push(Vec.Empty(rt));
        foreach (int x in slots) rt.SetR(vi, Vec.Conj(rt, rt.R(vi), Val.Fixnum(x)));
        long outv = rt.R(vi);
        rt.PopTo(bas);
        return outv;
    }

    /// Match at `from`, returning `[s0 e0 s1 e1 ...]` or nil. Positions are
    /// CODE-POINT indices and `-1` marks a group that did not participate.
    public static long Run(Rt rt, long re, long s, long from, int entry, bool full) {
        if (!rt.IsHeapTy(re, Obj.TyRegex)) return rt.ThrowStr("ClassCastException", "not a compiled pattern");
        if (!Str.IsString(rt, s)) return rt.ThrowStr("ClassCastException", "re-run wants a string");
        int[] prog = ProgOf(rt, re);
        int ninstrs = prog[0];
        int nslots = (prog[2] + 1) * 2;
        int[] cps = CodePoints(rt, s);
        int[] best = RunOver(prog, ninstrs, nslots, cps, (int) System.Math.Max(from, 0), entry, full);
        return best == null ? Val.Nil : SlotsVector(rt, best);
    }

    /// EVERY match, in ONE left-to-right pass.
    ///
    /// This is what `split`, `re-seq` and `replace` actually want, and giving
    /// them `run` in a loop was quadratic: each call decoded the whole subject
    /// again. Returns a flat vector, `nslots` entries per match, back to back.
    public static long FindAll(Rt rt, long re, long s, long limit) {
        if (!rt.IsHeapTy(re, Obj.TyRegex)) return rt.ThrowStr("ClassCastException", "not a compiled pattern");
        if (!Str.IsString(rt, s)) return rt.ThrowStr("ClassCastException", "re-find-all wants a string");
        int[] prog = ProgOf(rt, re);
        int ninstrs = prog[0];
        int nslots = (prog[2] + 1) * 2;
        int[] cps = CodePoints(rt, s);
        List<int> found = new List<int>();
        int at = 0;
        long count = 0;
        while (at <= cps.Length) {
            if (limit > 0 && count >= limit) break;
            int[] slots = RunOver(prog, ninstrs, nslots, cps, at, 0, false);
            if (slots == null) break;
            int st = slots[0], en = slots[1];
            foreach (int x in slots) found.Add(x);
            count++;
            // An EMPTY match must still advance, or this never ends.
            at = en > st ? en : en + 1;
        }
        int[] flat = new int[found.Count];
        for (int i = 0; i < flat.Length; i++) flat[i] = found[i];
        return SlotsVector(rt, flat);
    }
}
