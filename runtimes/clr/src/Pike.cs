using System;
using System.Collections.Generic;

namespace Flint;

/// The Pike VM (`doc/decisions/0012`), ported from `runtime/src/pike.rs`.
///
/// One left-to-right pass carrying a list of live threads, consuming each
/// character exactly once and never rewinding. Linear by construction: threads
/// are deduplicated by program counter, so a position holds at most one thread
/// per instruction however many ways the pattern could have reached it -- which
/// is why `(a+)+b` is linear here and exponential in a backtracker.
///
/// Leftmost-first (Perl) semantics: `AddThread` follows SPLIT's preferred
/// branch first and the dedup set keeps whichever arrived first, so an earlier
/// alternative wins exactly as a backtracker's would.
///
/// This port does NOT parse a pattern. `flint.nfa` does that, in cljc, and
/// hands over a finished program as a vector of integers -- which is why one
/// engine's semantics reach every host instead of each host bolting on its own.
/// `System.Text.RegularExpressions` would be less code and a different
/// language: `.` and newline, empty-match advancement and group numbering all
/// differ, and a conformance run compares answers.
///
/// Positions are CODE POINT indices, not UTF-16 offsets.
public static class Pike {
    private const int OP_CHAR = 0, OP_ANY = 1, OP_SPLIT = 2, OP_JMP = 3,
        OP_SAVE = 4, OP_MATCH = 5, OP_BOL = 6, OP_EOL = 7, OP_WORDB = 8,
        OP_NWORDB = 9, OP_CLASS = 10;
    private const int CL_ONE = 0, CL_RANGE = 1;
    /// Header words: instruction count, class-table length, group count.
    private const int PROG_HDR = 3;

    /// A compiled pattern. `flint/kind` calls it `:regex`.
    public sealed class Regex {
        public readonly string Source;
        internal readonly int[] Prog;
        public readonly long NGroups;
        internal Regex(string source, int[] prog, long ngroups) {
            Source = source; Prog = prog; NGroups = ngroups;
        }
        public override string ToString() => "#\"" + Source + "\"";
    }

    private static bool WordCp(int v) =>
        (v >= 48 && v <= 57) || (v >= 65 && v <= 90) || (v >= 97 && v <= 122) || v == 95;
    private static bool SpaceCp(int v) =>
        v == 32 || v == 9 || v == 10 || v == 13 || v == 12 || v == 11;
    private static bool PredHit(int code, int v) => code switch {
        0 => v >= 48 && v <= 57,
        1 => !(v >= 48 && v <= 57),
        2 => WordCp(v),
        3 => !WordCp(v),
        4 => SpaceCp(v),
        _ => !SpaceCp(v),
    };
    private static bool ClassHit(int[] prog, int classBase, int off, int v) {
        int n = prog[classBase + off];
        for (int k = 0; k < n; k++) {
            int b = classBase + off + 1 + k * 3;
            bool hit = prog[b] switch {
                CL_ONE => v == prog[b + 1],
                CL_RANGE => v >= prog[b + 1] && v <= prog[b + 2],
                _ => PredHit(prog[b + 1], v),
            };
            if (hit) return true;
        }
        return false;
    }

    private readonly struct Thread {
        public readonly int Pc; public readonly int[] Saved;
        public Thread(int pc, int[] saved) { Pc = pc; Saved = saved; }
    }

    /// Follow every zero-width step from `pc`, adding the threads that actually
    /// consume. Recursive, as the Rust is: the dedup set bounds the depth by the
    /// instruction count.
    private static void AddThread(int[] prog, int codeBase, int classBase, int[] cps,
                                  int i, List<Thread> list, bool[] seen, int pc, int[] saved) {
        if (seen[pc]) return;
        seen[pc] = true;
        int b = codeBase + pc * 3;
        int op = prog[b], a = prog[b + 1], c = prog[b + 2];
        switch (op) {
            case OP_JMP: AddThread(prog, codeBase, classBase, cps, i, list, seen, a, saved); return;
            case OP_SPLIT:
                AddThread(prog, codeBase, classBase, cps, i, list, seen, a, saved);
                AddThread(prog, codeBase, classBase, cps, i, list, seen, c, saved);
                return;
            case OP_SAVE: {
                var s2 = (int[]) saved.Clone();
                if (a < s2.Length) s2[a] = i;
                AddThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, s2);
                return;
            }
            case OP_BOL:
                if (i == 0) AddThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                return;
            case OP_EOL:
                if (i == cps.Length) AddThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                return;
            case OP_WORDB:
            case OP_NWORDB: {
                bool before = i > 0 && WordCp(cps[i - 1]);
                bool after = i < cps.Length && WordCp(cps[i]);
                bool at = before != after;
                if ((op == OP_WORDB) == at)
                    AddThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                return;
            }
            default: list.Add(new Thread(pc, saved)); return;
        }
    }

    private static bool Consumes(int[] prog, int codeBase, int classBase, int pc, int v) {
        int b = codeBase + pc * 3;
        switch (prog[b]) {
            case OP_CHAR: return v == prog[b + 1];
            // NOT a newline, matching flint and Java without DOTALL. An engine
            // defaulting the other way is one of the divergences this port
            // exists to avoid.
            case OP_ANY: return v != 10;
            case OP_CLASS: {
                bool hit = ClassHit(prog, classBase, prog[b + 1], v);
                return prog[b + 2] == 1 ? !hit : hit;
            }
            default: return false;
        }
    }

    /// The simulator proper. Returns the slot vector of the best match, or null.
    private static int[] RunOver(int[] prog, int[] cps, int from, int entry, bool full) {
        int ninstrs = prog[0];
        int nslots = (prog[2] + 1) * 2;
        int codeBase = PROG_HDR, classBase = PROG_HDR + ninstrs * 3;
        if (from > cps.Length) return null;

        var clist = new List<Thread>();
        var nlist = new List<Thread>();
        var seen = new bool[ninstrs];
        var start = new int[nslots];
        Array.Fill(start, -1);
        AddThread(prog, codeBase, classBase, cps, from, clist, seen, entry, start);

        int[] best = null;
        int i = from;
        while (clist.Count > 0) {
            bool have = i < cps.Length;
            int v = have ? cps[i] : 0;
            nlist.Clear();
            Array.Fill(seen, false);
            foreach (var t in clist) {
                int op = prog[codeBase + t.Pc * 3];
                if (op == OP_MATCH) {
                    // A full match must reach the end. REJECTING rather than
                    // cutting is what lets a lower-priority alternative win:
                    // `(a|ab)` against "ab" is `ab`, which a backtracker gets by
                    // backtracking and this gets by carrying both threads.
                    if (!full || i == cps.Length) { best = t.Saved; break; }
                    continue;
                }
                if (have && Consumes(prog, codeBase, classBase, t.Pc, v))
                    AddThread(prog, codeBase, classBase, cps, i + 1, nlist, seen, t.Pc + 1, t.Saved);
            }
            (clist, nlist) = (nlist, clist);
            if (i >= cps.Length) break;
            i++;
        }
        return best;
    }

    /// The code points of `s`, in order. A surrogate pair is ONE.
    private static int[] CodePoints(string s) {
        var outv = new List<int>(s.Length);
        for (int i = 0; i < s.Length; ) {
            if (char.IsHighSurrogate(s[i]) && i + 1 < s.Length && char.IsLowSurrogate(s[i + 1])) {
                outv.Add(char.ConvertToUtf32(s[i], s[i + 1]));
                i += 2;
            } else {
                outv.Add(s[i]);
                i++;
            }
        }
        return outv.ToArray();
    }

    /// `[ninstrs, nclasses, ngroups, code..., classes...]` from `flint.nfa`.
    public static Regex Compile(string source, IEnumerable<object> words) {
        var xs = new List<object>(words);
        if (xs.Count < PROG_HDR) throw new FlintThrow("regex: malformed program");
        var prog = new int[xs.Count];
        for (int i = 0; i < prog.Length; i++) prog[i] = (int) Vm.Num(xs[i]);
        return new Regex(source, prog, prog[2]);
    }

    /// Match at `from`, answering `[s0 e0 s1 e1 ...]` or null. Positions are
    /// code-point indices and -1 marks a group that did not participate.
    public static object Run(Regex re, string s, int from, int entry, bool full) {
        int[] slots = RunOver(re.Prog, CodePoints(s), Math.Max(0, from), entry, full);
        return slots == null ? null : Boxed(slots);
    }

    /// EVERY match, in ONE left-to-right pass, as a flat vector of `nslots`
    /// entries per match.
    ///
    /// This is what `split`, `re-seq` and `replace` actually want. Giving them
    /// `re-run` in a loop is quadratic -- each call decodes the whole subject
    /// again -- and that made the Pike VM four times SLOWER than the
    /// backtracker it replaced until this existed.
    public static object FindAll(Regex re, string s, long limit) {
        int[] cps = CodePoints(s);
        var found = new List<object>();
        int at = 0;
        long count = 0;
        while (at <= cps.Length) {
            if (limit > 0 && count >= limit) break;
            int[] slots = RunOver(re.Prog, cps, at, 0, false);
            if (slots == null) break;
            foreach (int x in slots) found.Add((long) x);
            count++;
            // An empty match must still advance, or this never ends.
            at = slots[1] > slots[0] ? slots[1] : slots[1] + 1;
        }
        return new Vec(found);
    }

    private static Vec Boxed(int[] slots) {
        var outv = new List<object>(slots.Length);
        foreach (int x in slots) outv.Add((long) x);
        return new Vec(outv);
    }
}
