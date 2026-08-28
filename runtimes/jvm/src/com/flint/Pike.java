package com.flint;

import java.util.ArrayList;
import java.util.List;

/// The Pike VM (`doc/decisions/0012`), ported from `runtime/src/pike.rs`.
///
/// One left-to-right pass carrying a list of live threads, consuming each
/// character exactly once and never rewinding. Linear by construction: threads
/// are deduplicated by program counter, so a position holds at most one thread
/// per instruction however many ways the pattern could have reached it -- which
/// is why `(a+)+b` is linear here and exponential in a backtracker.
///
/// Leftmost-first (Perl) semantics: `addThread` follows SPLIT's preferred
/// branch first and the dedup set keeps whichever arrived first, so an earlier
/// alternative wins exactly as a backtracker's would.
///
/// This port does NOT parse a pattern. `flint.nfa` does that, in cljc, and
/// hands over a finished program as a vector of integers -- which is why one
/// engine's semantics reach every host instead of each host bolting on its own.
/// A host regex engine would differ in exactly the places that matter: `.` and
/// newline, empty-match advancement, group numbering.
///
/// Positions are CODE POINT indices, not UTF-16 offsets. The JVM counts
/// differently from flint everywhere else too, and a match span is the value a
/// caller slices with.
public final class Pike {
    private static final int OP_CHAR = 0, OP_ANY = 1, OP_SPLIT = 2, OP_JMP = 3,
            OP_SAVE = 4, OP_MATCH = 5, OP_BOL = 6, OP_EOL = 7, OP_WORDB = 8,
            OP_NWORDB = 9, OP_CLASS = 10;
    private static final int CL_ONE = 0, CL_RANGE = 1;
    /// Header words: instruction count, class-table length, group count.
    private static final int PROG_HDR = 3;

    /// A compiled pattern. `flint/kind` calls it `:regex`.
    public static final class Regex {
        public final String source;
        final int[] prog;
        public final long ngroups;
        Regex(String source, int[] prog, long ngroups) {
            this.source = source; this.prog = prog; this.ngroups = ngroups;
        }
        @Override public String toString() { return "#\"" + source + "\""; }
    }

    private static boolean wordCp(int v) {
        return (v >= 48 && v <= 57) || (v >= 65 && v <= 90) || (v >= 97 && v <= 122) || v == 95;
    }
    private static boolean spaceCp(int v) {
        return v == 32 || v == 9 || v == 10 || v == 13 || v == 12 || v == 11;
    }
    private static boolean predHit(int code, int v) {
        return switch (code) {
            case 0 -> v >= 48 && v <= 57;
            case 1 -> !(v >= 48 && v <= 57);
            case 2 -> wordCp(v);
            case 3 -> !wordCp(v);
            case 4 -> spaceCp(v);
            default -> !spaceCp(v);
        };
    }
    private static boolean classHit(int[] prog, int classBase, int off, int v) {
        int n = prog[classBase + off];
        for (int k = 0; k < n; k++) {
            int b = classBase + off + 1 + k * 3;
            boolean hit = switch (prog[b]) {
                case CL_ONE -> v == prog[b + 1];
                case CL_RANGE -> v >= prog[b + 1] && v <= prog[b + 2];
                default -> predHit(prog[b + 1], v);
            };
            if (hit) return true;
        }
        return false;
    }

    private static final class Thread {
        final int pc; final int[] saved;
        Thread(int pc, int[] saved) { this.pc = pc; this.saved = saved; }
    }

    /// Follow every zero-width step from `pc`, adding the threads that actually
    /// consume. Recursive, as the Rust is: the dedup set bounds the depth by the
    /// instruction count.
    private static void addThread(int[] prog, int codeBase, int classBase, int[] cps,
                                  int i, List<Thread> list, boolean[] seen,
                                  int pc, int[] saved) {
        if (seen[pc]) return;
        seen[pc] = true;
        int b = codeBase + pc * 3;
        int op = prog[b], a = prog[b + 1], c = prog[b + 2];
        switch (op) {
            case OP_JMP -> addThread(prog, codeBase, classBase, cps, i, list, seen, a, saved);
            case OP_SPLIT -> {
                addThread(prog, codeBase, classBase, cps, i, list, seen, a, saved);
                addThread(prog, codeBase, classBase, cps, i, list, seen, c, saved);
            }
            case OP_SAVE -> {
                int[] s2 = saved.clone();
                if (a < s2.length) s2[a] = i;
                addThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, s2);
            }
            case OP_BOL -> {
                if (i == 0) addThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
            }
            case OP_EOL -> {
                if (i == cps.length) addThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
            }
            case OP_WORDB, OP_NWORDB -> {
                boolean before = i > 0 && wordCp(cps[i - 1]);
                boolean after = i < cps.length && wordCp(cps[i]);
                boolean at = before != after;
                if ((op == OP_WORDB) == at) {
                    addThread(prog, codeBase, classBase, cps, i, list, seen, pc + 1, saved);
                }
            }
            default -> list.add(new Thread(pc, saved));
        }
    }

    private static boolean consumes(int[] prog, int codeBase, int classBase, int pc, int v) {
        int b = codeBase + pc * 3;
        return switch (prog[b]) {
            case OP_CHAR -> v == prog[b + 1];
            // NOT a newline. Java's `.` excludes it without DOTALL, and flint's
            // does too -- a host engine defaulting the other way is one of the
            // divergences this port exists to avoid.
            case OP_ANY -> v != 10;
            case OP_CLASS -> {
                boolean hit = classHit(prog, classBase, prog[b + 1], v);
                yield prog[b + 2] == 1 ? !hit : hit;
            }
            default -> false;
        };
    }

    /// The simulator proper. Returns the slot vector of the best match, or null.
    static int[] runOver(int[] prog, int[] cps, int from, int entry, boolean full) {
        int ninstrs = prog[0];
        int nslots = (prog[2] + 1) * 2;
        int codeBase = PROG_HDR, classBase = PROG_HDR + ninstrs * 3;
        if (from > cps.length) return null;

        List<Thread> clist = new ArrayList<>(), nlist = new ArrayList<>();
        boolean[] seen = new boolean[ninstrs];
        int[] start = new int[nslots];
        java.util.Arrays.fill(start, -1);
        addThread(prog, codeBase, classBase, cps, from, clist, seen, entry, start);

        int[] best = null;
        int i = from;
        while (!clist.isEmpty()) {
            boolean have = i < cps.length;
            int v = have ? cps[i] : 0;
            nlist.clear();
            java.util.Arrays.fill(seen, false);
            for (Thread t : clist) {
                int op = prog[codeBase + t.pc * 3];
                if (op == OP_MATCH) {
                    // A full match must reach the end. REJECTING rather than
                    // cutting is what lets a lower-priority alternative win:
                    // `(a|ab)` against "ab" is `ab`, which a backtracker gets by
                    // backtracking and this gets by carrying both threads.
                    if (!full || i == cps.length) { best = t.saved; break; }
                    continue;
                }
                if (have && consumes(prog, codeBase, classBase, t.pc, v)) {
                    addThread(prog, codeBase, classBase, cps, i + 1, nlist, seen, t.pc + 1, t.saved);
                }
            }
            List<Thread> swap = clist; clist = nlist; nlist = swap;
            if (i >= cps.length) break;
            i++;
        }
        return best;
    }

    /// The code points of `s`, in order. A surrogate pair is ONE.
    static int[] codePoints(String s) {
        int n = s.codePointCount(0, s.length());
        int[] out = new int[n];
        int j = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out[j++] = cp;
            i += Character.charCount(cp);
        }
        return out;
    }

    /// `[ninstrs, nclasses, ngroups, code..., classes...]` from `flint.nfa`.
    public static Regex compile(String source, Iterable<Object> words) {
        List<Object> xs = new ArrayList<>();
        for (Object o : words) xs.add(o);
        if (xs.size() < PROG_HDR) throw Vm.err("regex: malformed program");
        int[] prog = new int[xs.size()];
        for (int i = 0; i < prog.length; i++) prog[i] = (int) Vm.num(xs.get(i));
        return new Regex(source, prog, prog[2]);
    }

    /// Match at `from`, answering `[s0 e0 s1 e1 ...]` or null. Positions are
    /// code-point indices and -1 marks a group that did not participate.
    public static List<Object> run(Regex re, String s, int from, int entry, boolean full) {
        int[] slots = runOver(re.prog, codePoints(s), Math.max(0, from), entry, full);
        return slots == null ? null : boxed(slots);
    }

    /// EVERY match, in ONE left-to-right pass, as a flat vector of `nslots`
    /// entries per match.
    ///
    /// This is what `split`, `re-seq` and `replace` actually want. Giving them
    /// `re-run` in a loop is quadratic -- each call decodes the whole subject
    /// again -- and that made the Pike VM four times SLOWER than the
    /// backtracker it replaced until this existed.
    public static List<Object> findAll(Regex re, String s, long limit) {
        int[] cps = codePoints(s);
        List<Object> found = new ArrayList<>();
        int at = 0;
        long count = 0;
        while (at <= cps.length) {
            if (limit > 0 && count >= limit) break;
            int[] slots = runOver(re.prog, cps, at, 0, false);
            if (slots == null) break;
            for (int x : slots) found.add((long) x);
            count++;
            // An empty match must still advance, or this never ends.
            at = slots[1] > slots[0] ? slots[1] : slots[1] + 1;
        }
        return found;
    }

    private static List<Object> boxed(int[] slots) {
        List<Object> out = new ArrayList<>(slots.length);
        for (int x : slots) out.add((long) x);
        return out;
    }
}
