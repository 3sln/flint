package com.flint.rt;

import java.util.*;

/// The analysis half of `DECISIONS.md#emit-wasm-instead-of-dispatch`, ported from `src/flint/aot.cljc`.
///
/// Decoding, chunk boundaries and gas charge are decisions about FLINT
/// BYTECODE, so they have nothing to do with which machine the result is
/// emitted for and this file is a straight port. The emitter next door is where
/// wasm and JVM bytecode part company.
public final class AotPlan {

    /// opcode -> operand width. The stride a walk takes, and a walk that
    /// mis-strides produces plausible nonsense rather than an error -- so an
    /// unknown opcode refuses the whole arity instead.
    static final int[] OPERANDS = new int[256];
    /// Emitted inline. `emit-wasm-instead-of-dispatch`'s opcode histogram says these are 98.7% of
    /// executed instructions; the rest go back to the interpreter one at a time.
    static final boolean[] INLINED = new boolean[256];
    /// Can leave compiled code. `CALL` often does NOT -- a callee that is not a
    /// closure finishes in place -- but it is still a boundary, because it might.
    static final boolean[] CALLS = new boolean[256];
    static final boolean[] JUMPS = new boolean[256];
    static final boolean[] KNOWN = new boolean[256];

    static {
        int[][] ops = {
            {Op.CONST,2},{Op.NIL,0},{Op.TRUE,0},{Op.FALSE,0},{Op.INT,2},
            {Op.LOCAL,1},{Op.LOCAL_W,2},{Op.SET_LOCAL,1},{Op.SET_LOCAL_W,2},{Op.UPVAL,1},{Op.VAR,2},
            {Op.SET_VAR,2},{Op.POP,0},{Op.DUP,0},{Op.JUMP,2},{Op.JUMP_IF_FALSE,2},
            {Op.CALL,1},{Op.TAIL_CALL,1},{Op.RETURN,0},
            {Op.CLOSURE,3},{Op.NATIVE,3},{Op.THROW,0},{Op.TRY,2},{Op.POP_HANDLER,0},
            {Op.RETHROW,0},{Op.VECTOR,2},{Op.MAP,2},{Op.SET,2},
            {Op.APPLY,1},{Op.SELF,0},
            {Op.ADD_INT,0},{Op.SUB_INT,0},{Op.MUL_INT,0},{Op.LT_INT,0},
            {Op.LE_INT,0},{Op.GT_INT,0},{Op.GE_INT,0},{Op.EQ_INT,0},{Op.TYPE_P,1},
        };
        for (int[] e : ops) { OPERANDS[e[0]] = e[1]; KNOWN[e[0]] = true; }
        for (int o : new int[]{ Op.JUMP, Op.JUMP_IF_FALSE }) JUMPS[o] = true;
        for (int o : new int[]{ Op.CALL, Op.TAIL_CALL, Op.APPLY }) CALLS[o] = true;
        for (int o : new int[]{ Op.CONST, Op.NIL, Op.TRUE, Op.FALSE, Op.INT,
                Op.LOCAL, Op.LOCAL_W, Op.SET_LOCAL, Op.SET_LOCAL_W, Op.POP,
                Op.DUP, Op.VAR, Op.SET_VAR, Op.SELF, Op.UPVAL, Op.JUMP, Op.JUMP_IF_FALSE,
                Op.RETURN,
                Op.NATIVE, Op.ADD_INT, Op.SUB_INT, Op.MUL_INT, Op.LT_INT, Op.LE_INT,
                Op.GT_INT, Op.GE_INT, Op.EQ_INT, Op.TYPE_P }) INLINED[o] = true;
    }

    /// One decoded instruction.
    public static final class Ins {
        public int ip, op, len;
        /// Operand bytes, unsigned.
        public int[] b;
    }

    /// The instructions of one arity, or null if any opcode is unknown or the
    /// walk does not land EXACTLY on the end. Both mean the stride is wrong, and
    /// a wrong stride is worse than no compilation.
    public static List<Ins> decode(byte[] code, int start, int len) {
        int end = start + len;
        List<Ins> out = new ArrayList<>();
        int ip = start;
        while (ip != end) {
            if (ip > end) return null;
            int op = code[ip] & 0xFF;
            if (!KNOWN[op]) return null;
            int nb = OPERANDS[op];
            Ins i = new Ins();
            i.ip = ip; i.op = op; i.len = 1 + nb;
            i.b = new int[nb];
            for (int k = 0; k < nb; k++) i.b[k] = code[ip + 1 + k] & 0xFF;
            out.add(i);
            ip += 1 + nb;
        }
        return out;
    }

    static int u16(int[] b) { return b[0] | (b[1] << 8); }
    static int i16(int[] b) { int v = u16(b); return v >= 0x8000 ? v - 0x10000 : v; }

    /// Where a jump goes, or -1.
    public static int jumpTarget(Ins i) {
        return JUMPS[i.op] ? i.ip + i.len + i16(i.b) : -1;
    }

    /// Byte offsets that begin a chunk. Every one is a RE-ENTRY POINT.
    public static int[] boundaries(List<Ins> instrs, boolean chunkAll) {
        TreeSet<Integer> out = new TreeSet<>();
        Set<Integer> valid = new HashSet<>();
        for (Ins i : instrs) valid.add(i.ip);
        if (chunkAll) {
            // EVERY instruction a boundary. A bisection handle, not a mode: if a
            // failure survives maximal chunking then no boundary was missing and
            // the fault is in how an opcode is EMITTED. Those are the two halves
            // this can be wrong in, and they want opposite fixes.
            for (Ins i : instrs) out.add(i.ip);
            return toArray(out);
        }
        if (!instrs.isEmpty()) out.add(instrs.get(0).ip);
        for (Ins i : instrs) {
            int t = jumpTarget(i);
            if (t >= 0 && valid.contains(t)) out.add(t);
            // A NATIVE needs a re-entry point on BOTH sides. Before it, because a
            // park resumes by re-executing it; after it, because a COURTESY YIELD
            // is a park whose call already finished, so it must not run again.
            //
            // And after every JUMP, conditional or not: gas is charged per chunk
            // by the chunk's static instruction count, and that is only exact if
            // a chunk has no INTERNAL branch. A conditional jump in the middle
            // leaves without running the rest, and the count charged for them
            // anyway. The answers all matched and the instruction counts did not.
            boolean after = CALLS[i.op] || i.op == Op.NATIVE || JUMPS[i.op] || !INLINED[i.op];
            if (after && valid.contains(i.ip + i.len)) out.add(i.ip + i.len);
            // A NATIVE starts a chunk even though it runs INSIDE compiled code,
            // because it can park -- and a parked thread resumes at the chunk
            // containing it, so anything earlier in that chunk would run twice.
            // Which natives park is not knowable here, and a boundary is free at
            // run time, so every one of them gets one.
            if (CALLS[i.op] || i.op == Op.NATIVE || !INLINED[i.op]) out.add(i.ip);
            // A BACKWARD jump starts a chunk as well as ending one. It is the
            // one preemption point in compiled code, and a thread preempted
            // there resumes at the chunk the emitter named -- so if the jump is
            // not that chunk's first instruction, everything before it runs a
            // SECOND time. The EDN reader accumulated a token twice.
            if (t >= 0 && t < i.ip) out.add(i.ip);
        }
        return toArray(out);
    }

    static int[] toArray(TreeSet<Integer> s) {
        int[] a = new int[s.size()];
        int k = 0;
        for (int v : s) a[k++] = v;
        return a;
    }

    /// One chunk: where it starts, what is in it, and what it charges.
    public static final class Chunk {
        public int idx, ip, charge;
        public List<Ins> instrs = new ArrayList<>();
    }

    /// Split into chunks at the boundaries.
    public static List<Chunk> chunks(List<Ins> instrs, int[] bounds) {
        Map<Integer,Integer> idx = new HashMap<>();
        Set<Integer> boundSet = new HashSet<>();
        for (int k = 0; k < bounds.length; k++) { idx.put(bounds[k], k); boundSet.add(bounds[k]); }
        List<Chunk> out = new ArrayList<>();
        Chunk cur = null;
        for (Ins i : instrs) {
            if (cur == null || boundSet.contains(i.ip)) {
                cur = new Chunk();
                cur.ip = i.ip;
                cur.idx = idx.getOrDefault(i.ip, out.size());
                out.add(cur);
            }
            cur.instrs.add(i);
        }
        for (Chunk c : out) {
            int lastOp = c.instrs.get(c.instrs.size() - 1).op;
            // A chunk that hands its final instruction back does not charge for
            // it: the interpreter is about to.
            boolean handedBack = CALLS[lastOp] || !INLINED[lastOp];
            c.charge = c.instrs.size() - (handedBack ? 1 : 0);
        }
        return out;
    }

    /// Deepest the operand stack gets, so the interpreter can reserve before it
    /// enters and every push in a compiled body is an unchecked store.
    ///
    /// A DATAFLOW rather than a sum, because the `keep` jumps do not pop on the
    /// taken edge and do on the fallthrough -- so the depth along the two edges
    /// differs and a running total would be wrong on one of them.
    public static int maxDepth(List<Ins> instrs, int[] bounds) {
        Map<Integer,Integer> at = new HashMap<>();
        for (Ins i : instrs) at.put(i.ip, 0);
        Deque<int[]> work = new ArrayDeque<>();
        if (!instrs.isEmpty()) work.push(new int[]{ instrs.get(0).ip, 0 });
        Map<Integer,Ins> byIp = new HashMap<>();
        for (Ins i : instrs) byIp.put(i.ip, i);
        int max = 0, guard = 0;
        while (!work.isEmpty() && guard++ < 1_000_000) {
            int[] w = work.pop();
            Ins i = byIp.get(w[0]);
            if (i == null) continue;
            int d = w[1];
            if (at.getOrDefault(w[0], -1) >= d && d != 0) continue;
            at.put(w[0], d);
            if (d > max) max = d;
            int[] eff = effect(i);
            int t = jumpTarget(i);
            if (t >= 0) work.push(new int[]{ t, Math.max(0, d + eff[1]) });
            if (i.op != Op.JUMP && i.op != Op.RETURN && i.op != Op.TAIL_CALL) {
                work.push(new int[]{ i.ip + i.len, Math.max(0, d + eff[0]) });
            }
        }
        return max + 8;
    }

    /// Net operand-stack effect, and the effect along a `keep` jump's TAKEN
    /// edge. Those two differ, which is the whole reason `maxDepth` is a
    /// dataflow rather than a sum.
    static int[] effect(Ins i) {
        return switch (i.op) {
            case Op.NIL, Op.TRUE, Op.FALSE, Op.INT, Op.CONST, Op.VAR, Op.LOCAL,
                 Op.LOCAL_W, Op.SELF, Op.UPVAL, Op.DUP -> new int[]{ 1, 1 };
            case Op.TYPE_P -> new int[]{ 0, 0 };
            case Op.POP, Op.SET_LOCAL, Op.SET_LOCAL_W, Op.SET_VAR, Op.THROW, Op.RETHROW,
                 Op.RETURN -> new int[]{ -1, -1 };
            case Op.JUMP -> new int[]{ 0, 0 };
            case Op.JUMP_IF_FALSE -> new int[]{ -1, -1 };
            // The `keep` forms do not pop when they JUMP.
            case Op.ADD_INT, Op.SUB_INT, Op.MUL_INT, Op.LT_INT, Op.LE_INT,
                 Op.GT_INT, Op.GE_INT, Op.EQ_INT -> new int[]{ -1, -1 };
            case Op.NATIVE -> { int n = i.b[2]; yield new int[]{ 1 - n, 1 - n }; }
            case Op.CALL, Op.TAIL_CALL, Op.APPLY -> { int n = i.b[0]; yield new int[]{ -n, -n }; }
            case Op.CLOSURE -> { int n = i.b[2]; yield new int[]{ 1 - n, 1 - n }; }
            case Op.VECTOR, Op.SET -> { int n = u16(i.b); yield new int[]{ 1 - n, 1 - n }; }
            case Op.MAP -> { int n = u16(i.b) * 2; yield new int[]{ 1 - n, 1 - n }; }
            default -> new int[]{ 0, 0 };
        };
    }

    private AotPlan() {}
}
