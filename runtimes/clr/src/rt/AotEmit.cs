namespace Flint.Rt;

using System.Collections.Generic;
using System.Reflection;
using System.Reflection.Emit;

/// The emitter: one flint arity to one IL method. A MIRROR of the JVM port's
/// `AotEmit.java`, which is a port of `src/flint/aot.cljc`.
///
/// ## Why this is a port and not a new backend
///
/// The wasm emitter produces sequences over a stack machine with locals and a
/// flat memory. So does this one -- CIL is the same shape -- and the per-opcode
/// sequences transfer almost line for line. What differs:
///
///   * the opcode table;
///   * the value stack is a `long[]`, so a push is an array store rather than
///     an `i64.store` at an offset;
///   * wasm's structured `block`/`loop`/`br` becomes a flat `switch` into
///     labels. SIMPLER, because flint's own bytecode already uses flat jumps
///     and the wasm emitter has to reconstruct structure it never wanted.
public static class AotEmit {

    static readonly FieldInfo F_SYNC = typeof(Rt).GetField("aotSync");
    static readonly FieldInfo F_STACK = typeof(Aot.Sync).GetField("stack");
    static readonly FieldInfo F_TOP = typeof(Aot.Sync).GetField("top");
    static readonly FieldInfo F_CONSTS = typeof(Aot.Sync).GetField("consts");
    static readonly FieldInfo F_GLOBALS = typeof(Aot.Sync).GetField("globals");

    static readonly MethodInfo M_NATIVE = typeof(Aot).GetMethod("AotNative");
    static readonly MethodInfo M_CALL = typeof(Aot).GetMethod("AotCall");
    static readonly MethodInfo M_RETURN = typeof(Aot).GetMethod("AotReturn");
    static readonly MethodInfo M_BAIL = typeof(Aot).GetMethod("AotBail");
    static readonly MethodInfo M_TICK = typeof(Aot).GetMethod("AotTick");
    static readonly MethodInfo M_TYPEP = typeof(Aot).GetMethod("AotTypeP");
    static readonly MethodInfo M_INTBINOP = typeof(Aot).GetMethod("AotIntBinop");
    static readonly MethodInfo M_SLOT = typeof(Rt).GetMethod("Slot", new[]{ typeof(long), typeof(int) });
    static readonly MethodInfo M_FIXNUM = typeof(Val).GetMethod("Fixnum");

    sealed class Ctx {
        public ILGenerator il;
        public LocalBuilder stack, top, gas, t, consts, globals, x, y;
        public Label[] labels;
        public Dictionary<int,int> chunkOf;
    }

    /// Compile one arity, or return null if it cannot be.
    ///
    /// `null` is a normal answer, not a failure: an unknown opcode means the
    /// walk would mis-stride, and a mis-strided walk produces plausible nonsense
    /// rather than an error. The arity simply stays interpreted.
    public static Aot.Fn Compile(Rt rt, byte[] code, int start, int len, bool chunkAll) {
        var instrs = AotPlan.Decode(code, start, len);
        if (instrs == null || instrs.Count == 0) return null;
        var bounds = AotPlan.Boundaries(instrs, chunkAll);
        var chunks = AotPlan.Chunks(instrs, bounds);
        if (chunks.Count == 0) return null;

        var chunkOf = new Dictionary<int,int>();
        foreach (var c in chunks) chunkOf[c.ip] = c.idx;

        var dm = new DynamicMethod("aot", typeof(void),
            new[]{ typeof(Rt), typeof(int), typeof(int), typeof(int) },
            typeof(AotEmit).Module, true);
        var il = dm.GetILGenerator();
        var ctx = new Ctx {
            il = il,
            stack = il.DeclareLocal(typeof(long[])),
            top = il.DeclareLocal(typeof(int)),
            gas = il.DeclareLocal(typeof(int)),
            t = il.DeclareLocal(typeof(long)),
            consts = il.DeclareLocal(typeof(long[])),
            globals = il.DeclareLocal(typeof(long[])),
            x = il.DeclareLocal(typeof(long)),
            y = il.DeclareLocal(typeof(long)),
            chunkOf = chunkOf,
        };
        ctx.labels = new Label[chunks.Count];
        for (int i = 0; i < chunks.Count; i++) ctx.labels[i] = il.DefineLabel();

        try {
            Body(ctx, chunks);
        } catch (System.Exception) {
            return null;
        }

        Aot.Fn fn;
        try {
            fn = new Aot.Fn {
                body = (Aot.Compiled) dm.CreateDelegate(typeof(Aot.Compiled)),
            };
        } catch (System.Exception) {
            return null;
        }
        fn.depth = AotPlan.MaxDepth(instrs, bounds);
        fn.pointIp = new int[chunks.Count];
        fn.pointBlock = new int[chunks.Count];
        for (int i = 0; i < chunks.Count; i++) {
            fn.pointIp[i] = chunks[i].ip;
            fn.pointBlock[i] = chunks[i].idx;
        }
        return fn;
    }

    static void Body(Ctx c, List<AotPlan.Chunk> chunks) {
        var il = c.il;
        // Everything compiled code caches. The value stack is a `long[]` that
        // `VReserve` reallocates, so it cannot be held across a crossing -- the
        // same reason the wasm emitter has a sync block.
        Reload(c);
        il.Emit(OpCodes.Ldarg_0); il.Emit(OpCodes.Ldfld, F_SYNC);
        il.Emit(OpCodes.Ldfld, F_CONSTS); il.Emit(OpCodes.Stloc, c.consts);
        il.Emit(OpCodes.Ldarg_0); il.Emit(OpCodes.Ldfld, F_SYNC);
        il.Emit(OpCodes.Ldfld, F_GLOBALS); il.Emit(OpCodes.Stloc, c.globals);
        il.Emit(OpCodes.Ldc_I4_0); il.Emit(OpCodes.Stloc, c.gas);

        // The chunk dispatch. Wasm needs nested blocks and a `br_table`; here it
        // is one switch into labels, which is what the flat bytecode wanted all
        // along.
        var done = il.DefineLabel();
        il.Emit(OpCodes.Ldarg_3);
        il.Emit(OpCodes.Switch, c.labels);
        il.Emit(OpCodes.Br, done);

        foreach (var ch in chunks) {
            il.MarkLabel(c.labels[ch.idx]);
            // Gas for the whole chunk, charged once. Between exits it lives in a
            // local, so a straight run of chunks touches no memory at all.
            if (ch.charge > 0) {
                il.Emit(OpCodes.Ldloc, c.gas);
                il.Emit(OpCodes.Ldc_I4, ch.charge);
                il.Emit(OpCodes.Add);
                il.Emit(OpCodes.Stloc, c.gas);
            }
            foreach (var i in ch.instrs) Emit(c, i, ch);
        }
        il.MarkLabel(done);
        il.Emit(OpCodes.Ret);
    }

    /// Reload what a crossing can invalidate.
    static void Reload(Ctx c) {
        var il = c.il;
        il.Emit(OpCodes.Ldarg_0); il.Emit(OpCodes.Ldfld, F_SYNC);
        il.Emit(OpCodes.Ldfld, F_STACK); il.Emit(OpCodes.Stloc, c.stack);
        il.Emit(OpCodes.Ldarg_0); il.Emit(OpCodes.Ldfld, F_SYNC);
        il.Emit(OpCodes.Ldfld, F_TOP); il.Emit(OpCodes.Stloc, c.top);
    }

    /// `stack[top++] = <the long on the IL stack>`.
    static void Push(Ctx c) {
        var il = c.il;
        il.Emit(OpCodes.Stloc, c.t);
        il.Emit(OpCodes.Ldloc, c.stack);
        il.Emit(OpCodes.Ldloc, c.top);
        il.Emit(OpCodes.Ldloc, c.t);
        il.Emit(OpCodes.Stelem_I8);
        Inc(c, c.top, 1);
    }

    static void Inc(Ctx c, LocalBuilder v, int by) {
        c.il.Emit(OpCodes.Ldloc, v);
        c.il.Emit(OpCodes.Ldc_I4, by);
        c.il.Emit(OpCodes.Add);
        c.il.Emit(OpCodes.Stloc, v);
    }

    /// `t = stack[--top]`
    static void PopToT(Ctx c) {
        Inc(c, c.top, -1);
        c.il.Emit(OpCodes.Ldloc, c.stack);
        c.il.Emit(OpCodes.Ldloc, c.top);
        c.il.Emit(OpCodes.Ldelem_I8);
        c.il.Emit(OpCodes.Stloc, c.t);
    }

    /// `t = stack[top - 1]`, leaving the top alone.
    static void PeekToT(Ctx c) {
        var il = c.il;
        il.Emit(OpCodes.Ldloc, c.stack);
        il.Emit(OpCodes.Ldloc, c.top);
        il.Emit(OpCodes.Ldc_I4_1);
        il.Emit(OpCodes.Sub);
        il.Emit(OpCodes.Ldelem_I8);
        il.Emit(OpCodes.Stloc, c.t);
    }

    static void Emit(Ctx c, AotPlan.Ins i, AotPlan.Chunk ch) {
        var il = c.il;
        switch (i.op) {
            case Op.Nil: il.Emit(OpCodes.Ldc_I8, Val.Nil); Push(c); break;
            case Op.True: il.Emit(OpCodes.Ldc_I8, Val.True); Push(c); break;
            case Op.False: il.Emit(OpCodes.Ldc_I8, Val.False); Push(c); break;
            case Op.Int: il.Emit(OpCodes.Ldc_I8, Val.Fixnum(AotPlan.I16(i.b))); Push(c); break;
            case Op.Const:
                il.Emit(OpCodes.Ldloc, c.consts);
                il.Emit(OpCodes.Ldc_I4, AotPlan.U16(i.b));
                il.Emit(OpCodes.Ldelem_I8); Push(c); break;
            case Op.Var:
                il.Emit(OpCodes.Ldloc, c.globals);
                il.Emit(OpCodes.Ldc_I4, AotPlan.U16(i.b));
                il.Emit(OpCodes.Ldelem_I8); Push(c); break;
            case Op.Local: LoadFrameSlot(c, i.b[0]); Push(c); break;
            case Op.LocalW: LoadFrameSlot(c, AotPlan.U16(i.b)); Push(c); break;
            case Op.Self:
                il.Emit(OpCodes.Ldloc, c.stack);
                il.Emit(OpCodes.Ldarg_2);
                il.Emit(OpCodes.Ldelem_I8); Push(c); break;
            case Op.SetLocal: PopToT(c); StoreFrameSlot(c, i.b[0]); break;
            case Op.SetLocalW: PopToT(c); StoreFrameSlot(c, AotPlan.U16(i.b)); break;
            case Op.SetVar:
                PopToT(c);
                il.Emit(OpCodes.Ldloc, c.globals);
                il.Emit(OpCodes.Ldc_I4, AotPlan.U16(i.b));
                il.Emit(OpCodes.Ldloc, c.t);
                il.Emit(OpCodes.Stelem_I8); break;
            case Op.Pop: Inc(c, c.top, -1); break;
            case Op.Dup: PeekToT(c); il.Emit(OpCodes.Ldloc, c.t); Push(c); break;
            // The closure is `stack[retTo]`, and an upvalue is one of its slots.
            // The frame deliberately does not cache the closure -- that copy was
            // once a root the collector could not see -- so this reads it the
            // way the interpreter does.
            case Op.Upval:
                il.Emit(OpCodes.Ldarg_0);
                il.Emit(OpCodes.Ldloc, c.stack);
                il.Emit(OpCodes.Ldarg_2);
                il.Emit(OpCodes.Ldelem_I8);
                il.Emit(OpCodes.Ldc_I4, i.b[0] + 1);
                il.Emit(OpCodes.Callvirt, M_SLOT); Push(c); break;
            case Op.Jump: Jump(c, AotPlan.JumpTarget(i), i, ch); break;
            case Op.JumpIfFalse: PopToT(c); Falsy(c); JumpIf(c, i, ch); break;
            // The `keep` forms do not pop when they jump, so the pop belongs on
            // the fallthrough only.
            case Op.Return:
                il.Emit(OpCodes.Ldarg_0);
                il.Emit(OpCodes.Ldloc, c.top);
                il.Emit(OpCodes.Ldloc, c.gas);
                il.Emit(OpCodes.Call, M_RETURN);
                il.Emit(OpCodes.Ret); break;
            // A type predicate. No bail protocol and no reload: the helper
            // cannot allocate, cannot fail and cannot re-enter, so nothing
            // cached here can move underneath it.
            case Op.TypeP:
                il.Emit(OpCodes.Ldloc, c.stack);
                il.Emit(OpCodes.Ldloc, c.top);
                il.Emit(OpCodes.Ldc_I4_1);
                il.Emit(OpCodes.Sub);
                il.Emit(OpCodes.Ldarg_0);
                il.Emit(OpCodes.Ldc_I4, i.b[0]);
                il.Emit(OpCodes.Ldloc, c.top);
                il.Emit(OpCodes.Call, M_TYPEP);
                il.Emit(OpCodes.Stelem_I8); break;
            case Op.Native: Crossing(c, i, ch, M_NATIVE, true); break;
            case Op.Call: Crossing(c, i, ch, M_CALL, false); break;
            case Op.AddInt: case Op.SubInt: case Op.MulInt: case Op.LtInt:
            case Op.LeInt: case Op.GtInt: case Op.GeInt: case Op.EqInt:
                IntOp(c, i, ch); break;
            // Everything else goes back to the interpreter for exactly ONE
            // instruction. That is what lets this be COMPLETE from the first
            // version rather than refusing a whole arity over one rare opcode.
            default: Bail(c, i, ch); break;
        }
    }

    static void LoadFrameSlot(Ctx c, int n) {
        var il = c.il;
        il.Emit(OpCodes.Ldloc, c.stack);
        il.Emit(OpCodes.Ldarg_1);
        il.Emit(OpCodes.Ldc_I4, n);
        il.Emit(OpCodes.Add);
        il.Emit(OpCodes.Ldelem_I8);
    }

    static void StoreFrameSlot(Ctx c, int n) {
        var il = c.il;
        il.Emit(OpCodes.Ldloc, c.stack);
        il.Emit(OpCodes.Ldarg_1);
        il.Emit(OpCodes.Ldc_I4, n);
        il.Emit(OpCodes.Add);
        il.Emit(OpCodes.Ldloc, c.t);
        il.Emit(OpCodes.Stelem_I8);
    }

    /// A `Native` or a `Call`: cross, and leave if it says to.
    static void Crossing(Ctx c, AotPlan.Ins i, AotPlan.Chunk ch, MethodInfo m, bool isNative) {
        var il = c.il;
        int nx = i.ip + i.len;
        bool has = c.chunkOf.TryGetValue(nx, out int j);
        il.Emit(OpCodes.Ldarg_0);
        if (isNative) {
            il.Emit(OpCodes.Ldc_I4, AotPlan.U16(i.b));
            il.Emit(OpCodes.Ldc_I4, i.b[2]);
        } else {
            il.Emit(OpCodes.Ldc_I4, i.b[0]);
        }
        il.Emit(OpCodes.Ldloc, c.top);
        il.Emit(OpCodes.Ldc_I4, i.ip);
        il.Emit(OpCodes.Ldc_I4, ch.idx);
        il.Emit(OpCodes.Ldc_I4, has ? nx : Aot.NEVER);
        il.Emit(OpCodes.Ldc_I4, has ? j : 0);
        il.Emit(OpCodes.Ldloc, c.gas);
        il.Emit(OpCodes.Call, m);
        var ok = il.DefineLabel();
        il.Emit(OpCodes.Brfalse, ok);
        il.Emit(OpCodes.Ret);
        il.MarkLabel(ok);
        il.Emit(OpCodes.Ldc_I4_0);
        il.Emit(OpCodes.Stloc, c.gas);
        Reload(c);
    }

    /// `t` is falsy: nil or false. Leaves 1/0 on the IL stack.
    static void Falsy(Ctx c) {
        var il = c.il;
        var yes = il.DefineLabel();
        var end = il.DefineLabel();
        il.Emit(OpCodes.Ldloc, c.t); il.Emit(OpCodes.Ldc_I8, Val.Nil); il.Emit(OpCodes.Beq, yes);
        il.Emit(OpCodes.Ldloc, c.t); il.Emit(OpCodes.Ldc_I8, Val.False); il.Emit(OpCodes.Beq, yes);
        il.Emit(OpCodes.Ldc_I4_0); il.Emit(OpCodes.Br, end);
        il.MarkLabel(yes); il.Emit(OpCodes.Ldc_I4_1);
        il.MarkLabel(end);
    }

    static void Truthy(Ctx c) {
        var il = c.il;
        var no = il.DefineLabel();
        var end = il.DefineLabel();
        il.Emit(OpCodes.Ldloc, c.t); il.Emit(OpCodes.Ldc_I8, Val.Nil); il.Emit(OpCodes.Beq, no);
        il.Emit(OpCodes.Ldloc, c.t); il.Emit(OpCodes.Ldc_I8, Val.False); il.Emit(OpCodes.Beq, no);
        il.Emit(OpCodes.Ldc_I4_1); il.Emit(OpCodes.Br, end);
        il.MarkLabel(no); il.Emit(OpCodes.Ldc_I4_0);
        il.MarkLabel(end);
    }

    static void Jump(Ctx c, int target, AotPlan.Ins i, AotPlan.Chunk ch) {
        if (!c.chunkOf.TryGetValue(target, out int j)) { Bail(c, i, ch); return; }
        MaybeTick(c, target, i, ch);
        c.il.Emit(OpCodes.Br, c.labels[j]);
    }

    static void JumpIf(Ctx c, AotPlan.Ins i, AotPlan.Chunk ch) {
        var il = c.il;
        int target = AotPlan.JumpTarget(i);
        var no = il.DefineLabel();
        il.Emit(OpCodes.Brfalse, no);
        if (!c.chunkOf.TryGetValue(target, out int j)) Bail(c, i, ch);
        else { MaybeTick(c, target, i, ch); il.Emit(OpCodes.Br, c.labels[j]); }
        il.MarkLabel(no);
    }

    /// A BACK-EDGE: flush the gas and ask whether the interpreter's own tick
    /// would fire. The ONE place a long compiled loop can be preempted, which is
    /// what the deterministic scheduler needs.
    static void MaybeTick(Ctx c, int target, AotPlan.Ins i, AotPlan.Chunk ch) {
        if (target >= i.ip) return;
        var il = c.il;
        il.Emit(OpCodes.Ldarg_0);
        il.Emit(OpCodes.Ldloc, c.gas);
        il.Emit(OpCodes.Ldloc, c.top);
        il.Emit(OpCodes.Ldc_I4, i.ip);
        il.Emit(OpCodes.Ldc_I4, ch.idx);
        il.Emit(OpCodes.Call, M_TICK);
        var go = il.DefineLabel();
        il.Emit(OpCodes.Brfalse, go);
        il.Emit(OpCodes.Ret);
        il.MarkLabel(go);
        il.Emit(OpCodes.Ldc_I4_0);
        il.Emit(OpCodes.Stloc, c.gas);
    }

    /// Hand control back at `ip`, and say where compiled code takes over again.
    /// ONE helper covers every exit, because every way back in is the same
    /// comparison on `ip`.
    static void Bail(Ctx c, AotPlan.Ins i, AotPlan.Chunk ch) {
        var il = c.il;
        // A TAIL CALL replaces this frame, so there is no next instruction of
        // THIS arity left to run and naming one registers a re-entry point
        // against a frame that no longer exists.
        int nx = i.ip + i.len;
        bool has = i.op != Op.TailCall && c.chunkOf.TryGetValue(nx, out int j0);
        int j = 0;
        if (has) c.chunkOf.TryGetValue(nx, out j);
        il.Emit(OpCodes.Ldarg_0);
        il.Emit(OpCodes.Ldloc, c.top);
        il.Emit(OpCodes.Ldc_I4, i.ip);
        il.Emit(OpCodes.Ldc_I4, has ? nx : Aot.NEVER);
        il.Emit(OpCodes.Ldc_I4, j);
        il.Emit(OpCodes.Ldloc, c.gas);
        il.Emit(OpCodes.Call, M_BAIL);
        il.Emit(OpCodes.Ret);
    }

    /// A specialised integer operation.
    ///
    /// The compiler emits these only where it PROVED both operands are
    /// integers, so the fast path is the path taken. `^int` means integer and
    /// not fixnum -- a value past the fixnum range is a boxed bigint and still
    /// answers `int?` -- so the tags are still tested, and everything else goes
    /// to a HELPER rather than a bail, because a boundary per arithmetic
    /// instruction is the shape `emit-wasm-instead-of-dispatch` measured and rejected.
    static void IntOp(Ctx c, AotPlan.Ins i, AotPlan.Chunk ch) {
        var il = c.il;
        Inc(c, c.top, -2);
        il.Emit(OpCodes.Ldloc, c.stack); il.Emit(OpCodes.Ldloc, c.top);
        il.Emit(OpCodes.Ldelem_I8); il.Emit(OpCodes.Stloc, c.x);
        il.Emit(OpCodes.Ldloc, c.stack); il.Emit(OpCodes.Ldloc, c.top);
        il.Emit(OpCodes.Ldc_I4_1); il.Emit(OpCodes.Add);
        il.Emit(OpCodes.Ldelem_I8); il.Emit(OpCodes.Stloc, c.y);

        var slow = il.DefineLabel();
        var done = il.DefineLabel();
        IsFixnum(c, c.x); il.Emit(OpCodes.Brfalse, slow);
        IsFixnum(c, c.y); il.Emit(OpCodes.Brfalse, slow);
        Payload(c, c.x);
        Payload(c, c.y);
        switch (i.op) {
            case Op.AddInt: case Op.SubInt: case Op.MulInt: {
                // Two 48-bit values cannot overflow a long, so the only question
                // is whether the RESULT still fits a fixnum.
                if (i.op == Op.AddInt) il.Emit(OpCodes.Add);
                else if (i.op == Op.SubInt) il.Emit(OpCodes.Sub);
                else il.Emit(OpCodes.Mul);
                il.Emit(OpCodes.Stloc, c.t);
                il.Emit(OpCodes.Ldloc, c.t); il.Emit(OpCodes.Ldc_I8, Val.FixnumMax);
                il.Emit(OpCodes.Bgt, slow);
                il.Emit(OpCodes.Ldloc, c.t); il.Emit(OpCodes.Ldc_I8, Val.FixnumMin);
                il.Emit(OpCodes.Blt, slow);
                il.Emit(OpCodes.Ldloc, c.t);
                il.Emit(OpCodes.Call, M_FIXNUM);
            } break;
            default: {
                var yes = il.DefineLabel();
                var no = il.DefineLabel();
                switch (i.op) {
                    case Op.LtInt: il.Emit(OpCodes.Blt, yes); break;
                    case Op.LeInt: il.Emit(OpCodes.Ble, yes); break;
                    case Op.GtInt: il.Emit(OpCodes.Bgt, yes); break;
                    case Op.GeInt: il.Emit(OpCodes.Bge, yes); break;
                    default: il.Emit(OpCodes.Beq, yes); break;
                }
                il.Emit(OpCodes.Ldc_I8, Val.False); il.Emit(OpCodes.Br, no);
                il.MarkLabel(yes); il.Emit(OpCodes.Ldc_I8, Val.True);
                il.MarkLabel(no);
            } break;
        }
        Push(c);
        il.Emit(OpCodes.Br, done);

        il.MarkLabel(slow);
        // The operands are still where they were; the helper reads them off the
        // value stack, so put the top back first.
        int nx = i.ip + i.len;
        bool has = c.chunkOf.TryGetValue(nx, out int j);
        il.Emit(OpCodes.Ldarg_0);
        il.Emit(OpCodes.Ldc_I4, i.op);
        il.Emit(OpCodes.Ldloc, c.top); il.Emit(OpCodes.Ldc_I4_2); il.Emit(OpCodes.Add);
        il.Emit(OpCodes.Ldc_I4, i.ip);
        il.Emit(OpCodes.Ldc_I4, ch.idx);
        il.Emit(OpCodes.Ldc_I4, has ? nx : Aot.NEVER);
        il.Emit(OpCodes.Ldc_I4, has ? j : 0);
        il.Emit(OpCodes.Ldloc, c.gas);
        il.Emit(OpCodes.Call, M_INTBINOP);
        var ok = il.DefineLabel();
        il.Emit(OpCodes.Brfalse, ok);
        il.Emit(OpCodes.Ret);
        il.MarkLabel(ok);
        il.Emit(OpCodes.Ldc_I4_0); il.Emit(OpCodes.Stloc, c.gas);
        Reload(c);
        il.MarkLabel(done);
    }

    /// Is the value in `v` a fixnum? 1/0 on the IL stack.
    static void IsFixnum(Ctx c, LocalBuilder v) {
        var il = c.il;
        var yes = il.DefineLabel();
        var end = il.DefineLabel();
        il.Emit(OpCodes.Ldloc, v);
        il.Emit(OpCodes.Ldc_I4, 48);
        il.Emit(OpCodes.Shr_Un);
        il.Emit(OpCodes.Ldc_I8, 0xFFFFL);
        il.Emit(OpCodes.And);
        il.Emit(OpCodes.Ldc_I8, Val.TagFixnum);
        il.Emit(OpCodes.Beq, yes);
        il.Emit(OpCodes.Ldc_I4_0); il.Emit(OpCodes.Br, end);
        il.MarkLabel(yes); il.Emit(OpCodes.Ldc_I4_1);
        il.MarkLabel(end);
    }

    /// The signed 48-bit payload of the fixnum in `v`.
    static void Payload(Ctx c, LocalBuilder v) {
        var il = c.il;
        il.Emit(OpCodes.Ldloc, v);
        il.Emit(OpCodes.Ldc_I4, 16);
        il.Emit(OpCodes.Shl);
        il.Emit(OpCodes.Ldc_I4, 16);
        il.Emit(OpCodes.Shr);
    }
}
