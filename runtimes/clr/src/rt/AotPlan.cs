namespace Flint.Rt;

using System.Collections.Generic;

/// The analysis half of `doc/decisions/0013`, a MIRROR of the JVM port's
/// `AotPlan.java` which is itself a port of `src/flint/aot.cljc`.
///
/// Decoding, chunk boundaries and the gas charge are decisions about FLINT
/// BYTECODE, so they have nothing to do with which machine the result is
/// emitted for, and this file is the same on every host. The emitter next door
/// is where wasm, JVM bytecode and IL part company.
public static class AotPlan {

    /// opcode -> operand width. The stride a walk takes, and a walk that
    /// mis-strides produces plausible nonsense rather than an error -- so an
    /// unknown opcode refuses the whole arity instead.
    internal static readonly int[] OPERANDS = new int[256];
    /// Emitted inline. `0013`'s opcode histogram says these are 98.7% of
    /// executed instructions; the rest go back to the interpreter one at a time.
    internal static readonly bool[] INLINED = new bool[256];
    /// Can leave compiled code. `Call` often does NOT -- a callee that is not a
    /// closure finishes in place -- but it is still a boundary, because it might.
    internal static readonly bool[] CALLS = new bool[256];
    internal static readonly bool[] JUMPS = new bool[256];
    internal static readonly bool[] KNOWN = new bool[256];

    static AotPlan() {
        void D(int op, int nb) { OPERANDS[op] = nb; KNOWN[op] = true; }
        D(Op.Const,2); D(Op.Nil,0); D(Op.True,0); D(Op.False,0);
        D(Op.Int,2); D(Op.Local,1); D(Op.LocalW,2); D(Op.SetLocal,1); D(Op.Upval,1);
        D(Op.Var,2); D(Op.SetVar,2); D(Op.Pop,0); D(Op.Dup,0); D(Op.Jump,2);
        D(Op.JumpIfFalse,2); D(Op.Call,1); D(Op.TailCall,1);
        D(Op.Return,0); D(Op.Closure,3); D(Op.Native,3); D(Op.Throw,0); D(Op.Try,2);
        D(Op.PopHandler,0); D(Op.Rethrow,0); D(Op.Vector,2); D(Op.Map,2); D(Op.Set,2);
        D(Op.Apply,1); D(Op.Self,0);
        D(Op.AddInt,0); D(Op.SubInt,0); D(Op.MulInt,0); D(Op.LtInt,0);
        D(Op.LeInt,0); D(Op.GtInt,0); D(Op.GeInt,0); D(Op.EqInt,0); D(Op.TypeP,1);

        foreach (int o in new[]{ Op.Jump, Op.JumpIfFalse }) JUMPS[o] = true;
        foreach (int o in new[]{ Op.Call, Op.TailCall, Op.Apply }) CALLS[o] = true;
        foreach (int o in new[]{ Op.Const, Op.Nil, Op.True, Op.False, Op.Int,
                Op.Local, Op.LocalW, Op.SetLocal, Op.Pop,
                Op.Dup, Op.Var, Op.SetVar, Op.Self, Op.Upval, Op.Jump, Op.JumpIfFalse,
                Op.Return,
                Op.Native, Op.AddInt, Op.SubInt, Op.MulInt, Op.LtInt, Op.LeInt,
                Op.GtInt, Op.GeInt, Op.EqInt, Op.TypeP }) INLINED[o] = true;
    }

    /// One decoded instruction.
    public sealed class Ins {
        public int ip, op, len;
        /// Operand bytes, unsigned.
        public int[] b;
    }

    /// The instructions of one arity, or null if any opcode is unknown or the
    /// walk does not land EXACTLY on the end. Both mean the stride is wrong, and
    /// a wrong stride is worse than no compilation.
    public static List<Ins> Decode(byte[] code, int start, int len) {
        int end = start + len;
        var outl = new List<Ins>();
        int ip = start;
        while (ip != end) {
            if (ip > end) return null;
            int op = code[ip] & 0xFF;
            if (!KNOWN[op]) return null;
            int nb = OPERANDS[op];
            var i = new Ins { ip = ip, op = op, len = 1 + nb, b = new int[nb] };
            for (int k = 0; k < nb; k++) i.b[k] = code[ip + 1 + k] & 0xFF;
            outl.Add(i);
            ip += 1 + nb;
        }
        return outl;
    }

    internal static int U16(int[] b) => b[0] | (b[1] << 8);
    internal static int I16(int[] b) { int v = U16(b); return v >= 0x8000 ? v - 0x10000 : v; }

    /// Where a jump goes, or -1.
    public static int JumpTarget(Ins i) => JUMPS[i.op] ? i.ip + i.len + I16(i.b) : -1;

    /// Byte offsets that begin a chunk. Every one is a RE-ENTRY POINT.
    public static int[] Boundaries(List<Ins> instrs, bool chunkAll) {
        var outs = new SortedSet<int>();
        var valid = new HashSet<int>();
        foreach (var i in instrs) valid.Add(i.ip);
        if (chunkAll) {
            // EVERY instruction a boundary. A bisection handle, not a mode: if a
            // failure survives maximal chunking then no boundary was missing and
            // the fault is in how an opcode is EMITTED. Those are the two halves
            // this can be wrong in, and they want opposite fixes.
            foreach (var i in instrs) outs.Add(i.ip);
            return ToArray(outs);
        }
        if (instrs.Count > 0) outs.Add(instrs[0].ip);
        foreach (var i in instrs) {
            int t = JumpTarget(i);
            if (t >= 0 && valid.Contains(t)) outs.Add(t);
            // A NATIVE needs a re-entry point on BOTH sides. Before it, because
            // a park resumes by re-executing it; after it, because a COURTESY
            // YIELD is a park whose call already finished, so it must not run
            // again.
            //
            // And after every JUMP, conditional or not: gas is charged per chunk
            // by the chunk's static instruction count, and that is only exact if
            // a chunk has no INTERNAL branch. The answers all matched and the
            // instruction counts did not.
            bool after = CALLS[i.op] || i.op == Op.Native || JUMPS[i.op] || !INLINED[i.op];
            if (after && valid.Contains(i.ip + i.len)) outs.Add(i.ip + i.len);
            // A NATIVE starts a chunk even though it runs INSIDE compiled code,
            // because it can park -- and a parked thread resumes at the chunk
            // containing it, so anything earlier in that chunk would run twice.
            if (CALLS[i.op] || i.op == Op.Native || !INLINED[i.op]) outs.Add(i.ip);
            // A BACKWARD jump starts a chunk as well as ending one. It is the
            // one preemption point in compiled code, and a thread preempted
            // there resumes at the chunk the emitter named. The EDN reader
            // accumulated a token twice.
            if (t >= 0 && t < i.ip) outs.Add(i.ip);
        }
        return ToArray(outs);
    }

    static int[] ToArray(SortedSet<int> s) {
        var a = new int[s.Count];
        int k = 0;
        foreach (int v in s) a[k++] = v;
        return a;
    }

    /// One chunk: where it starts, what is in it, and what it charges.
    public sealed class Chunk {
        public int idx, ip, charge;
        public List<Ins> instrs = new List<Ins>();
    }

    /// Split into chunks at the boundaries.
    public static List<Chunk> Chunks(List<Ins> instrs, int[] bounds) {
        var idx = new Dictionary<int,int>();
        var boundSet = new HashSet<int>();
        for (int k = 0; k < bounds.Length; k++) { idx[bounds[k]] = k; boundSet.Add(bounds[k]); }
        var outc = new List<Chunk>();
        Chunk cur = null;
        foreach (var i in instrs) {
            if (cur == null || boundSet.Contains(i.ip)) {
                cur = new Chunk { ip = i.ip };
                cur.idx = idx.TryGetValue(i.ip, out int gi) ? gi : outc.Count;
                outc.Add(cur);
            }
            cur.instrs.Add(i);
        }
        foreach (var c in outc) {
            int lastOp = c.instrs[c.instrs.Count - 1].op;
            // A chunk that hands its final instruction back does not charge for
            // it: the interpreter is about to.
            bool handedBack = CALLS[lastOp] || !INLINED[lastOp];
            c.charge = c.instrs.Count - (handedBack ? 1 : 0);
        }
        return outc;
    }

    /// Deepest the operand stack gets, so the interpreter can reserve before it
    /// enters and every push in a compiled body is an unchecked store.
    ///
    /// A DATAFLOW rather than a sum, because the `keep` jumps do not pop on the
    /// taken edge and do on the fallthrough -- so the depth along the two edges
    /// differs and a running total would be wrong on one of them.
    public static int MaxDepth(List<Ins> instrs, int[] bounds) {
        var at = new Dictionary<int,int>();
        var byIp = new Dictionary<int,Ins>();
        foreach (var i in instrs) { at[i.ip] = 0; byIp[i.ip] = i; }
        var work = new Stack<int[]>();
        if (instrs.Count > 0) work.Push(new int[]{ instrs[0].ip, 0 });
        int max = 0, guard = 0;
        while (work.Count > 0 && guard++ < 1_000_000) {
            var w = work.Pop();
            if (!byIp.TryGetValue(w[0], out var i)) continue;
            int d = w[1];
            if (at.TryGetValue(w[0], out int prev) && prev >= d && d != 0) continue;
            at[w[0]] = d;
            if (d > max) max = d;
            var eff = Effect(i);
            int t = JumpTarget(i);
            if (t >= 0) work.Push(new int[]{ t, System.Math.Max(0, d + eff[1]) });
            if (i.op != Op.Jump && i.op != Op.Return && i.op != Op.TailCall) {
                work.Push(new int[]{ i.ip + i.len, System.Math.Max(0, d + eff[0]) });
            }
        }
        return max + 8;
    }

    /// Net operand-stack effect, and the effect along a `keep` jump's TAKEN
    /// edge. Those two differ, which is why `MaxDepth` is a dataflow.
    static int[] Effect(Ins i) {
        switch (i.op) {
            case Op.Nil: case Op.True: case Op.False: case Op.Int: case Op.Const:
            case Op.Var: case Op.Local: case Op.LocalW: case Op.Self: case Op.Upval:
            case Op.Dup: return new int[]{ 1, 1 };
            case Op.TypeP: return new int[]{ 0, 0 };
            case Op.Pop: case Op.SetLocal: case Op.SetVar: case Op.Throw:
            case Op.Rethrow: case Op.Return: return new int[]{ -1, -1 };
            case Op.Jump: return new int[]{ 0, 0 };
            case Op.JumpIfFalse: return new int[]{ -1, -1 };
            // The `keep` forms do not pop when they JUMP.
            case Op.AddInt: case Op.SubInt: case Op.MulInt: case Op.LtInt:
            case Op.LeInt: case Op.GtInt: case Op.GeInt: case Op.EqInt:
                return new int[]{ -1, -1 };
            case Op.Native: { int n = i.b[2]; return new int[]{ 1 - n, 1 - n }; }
            case Op.Call: case Op.TailCall: case Op.Apply: {
                int n = i.b[0]; return new int[]{ -n, -n };
            }
            case Op.Closure: { int n = i.b[2]; return new int[]{ 1 - n, 1 - n }; }
            case Op.Vector: case Op.Set: {
                int n = U16(i.b); return new int[]{ 1 - n, 1 - n };
            }
            case Op.Map: { int n = U16(i.b) * 2; return new int[]{ 1 - n, 1 - n }; }
            default: return new int[]{ 0, 0 };
        }
    }
}
