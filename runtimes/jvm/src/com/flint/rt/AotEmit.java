package com.flint.rt;

import java.lang.classfile.*;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.util.*;

import static java.lang.constant.ConstantDescs.*;

/// The emitter: one flint arity to one JVM method, ported from
/// `src/flint/aot.cljc`.
///
/// ## Why this is a port and not a new backend
///
/// The wasm emitter produces sequences over a stack machine with locals and a
/// flat memory. So does this one -- JVM bytecode is the same shape -- and the
/// per-opcode sequences transfer almost line for line. What differs:
///
///   * The opcode table, obviously.
///   * The value stack is a `long[]`, so a push is an array store rather than
///     an `i64.store` at an offset. Simpler, and bounds-checked for free.
///   * Wasm's structured `block`/`loop`/`br` becomes a flat `tableswitch` into
///     labels. SIMPLER, because flint's own bytecode already uses flat jumps
///     and the wasm emitter has to reconstruct structure it never wanted.
///
/// ## What the interpreter guarantees before it enters
///
/// The operand stack is already reserved to `Fn.depth`, so every push here is
/// an unchecked store into an array known to be long enough.
public final class AotEmit {

    static final ClassDesc CD_RT = ClassDesc.of("com.flint.rt.Rt");
    static final ClassDesc CD_AOT = ClassDesc.of("com.flint.rt.Aot");
    static final ClassDesc CD_SYNC = ClassDesc.of("com.flint.rt.Aot$Sync");
    static final ClassDesc CD_COMPILED = ClassDesc.of("com.flint.rt.Aot$Compiled");
    static final ClassDesc CD_LONGARR = CD_long.arrayType();

    // JVM local slots. The first four are the ABI.
    static final int L_RT = 1, L_FP = 2, L_RETTO = 3, L_ENTRY = 4;
    static final int L_STACK = 5, L_TOP = 6, L_GAS = 7;
    static final int L_T = 8;        // long, occupies 8-9
    static final int L_CONSTS = 10, L_GLOBALS = 11;
    static final int L_X = 12;       // long, 12-13
    static final int L_Y = 14;       // long, 14-15

    /// Compile one arity, or return null if it cannot be.
    ///
    /// `null` is a normal answer, not a failure: an unknown opcode means the
    /// walk would mis-stride, and a mis-strided walk produces plausible nonsense
    /// rather than an error. The arity simply stays interpreted.
    public static Aot.Fn compile(Rt rt, byte[] code, int start, int len, boolean chunkAll) {
        List<AotPlan.Ins> instrs = AotPlan.decode(code, start, len);
        if (instrs == null || instrs.isEmpty()) return null;
        int[] bounds = AotPlan.boundaries(instrs, chunkAll);
        List<AotPlan.Chunk> chunks = AotPlan.chunks(instrs, bounds);
        if (chunks.isEmpty()) return null;

        Map<Integer,Integer> chunkOf = new HashMap<>();
        for (AotPlan.Chunk c : chunks) chunkOf.put(c.ip, c.idx);

        String name = "com/flint/rt/AotBody$" + Integer.toHexString(System.identityHashCode(instrs));
        ClassDesc self = ClassDesc.ofInternalName(name);
        byte[] bytes;
        try {
            bytes = ClassFile.of().build(self, cb -> {
                cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
                cb.withSuperclass(CD_Object);
                cb.withInterfaceSymbols(CD_COMPILED);
                cb.withMethod(INIT_NAME, MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(x -> x.aload(0)
                        .invokespecial(CD_Object, INIT_NAME, MethodTypeDesc.of(CD_void))
                        .return_()));
                cb.withMethod("run",
                    MethodTypeDesc.of(CD_void, CD_RT, CD_int, CD_int, CD_int),
                    ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(x -> body(x, chunks, chunkOf)));
            });
        } catch (Throwable t) {
            return null;
        }

        Aot.Fn fn = new Aot.Fn();
        try {
            Class<?> k = MethodHandles.lookup().defineClass(bytes);
            fn.body = (Aot.Compiled) k.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
        fn.depth = AotPlan.maxDepth(instrs, bounds);
        fn.pointIp = new int[chunks.size()];
        fn.pointBlock = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            fn.pointIp[i] = chunks.get(i).ip;
            fn.pointBlock[i] = chunks.get(i).idx;
        }
        return fn;
    }

    static void body(CodeBuilder x, List<AotPlan.Chunk> chunks, Map<Integer,Integer> chunkOf) {
        Label[] labels = new Label[chunks.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = x.newLabel();

        // Everything compiled code caches. The value stack is a `long[]` that
        // `vreserve` reallocates, so it cannot be held across a crossing -- the
        // same reason the wasm emitter has a sync block.
        reload(x);
        x.aload(L_RT).getfield(CD_RT, "aotSync", CD_SYNC)
         .getfield(CD_SYNC, "consts", CD_LONGARR).astore(L_CONSTS);
        x.aload(L_RT).getfield(CD_RT, "aotSync", CD_SYNC)
         .getfield(CD_SYNC, "globals", CD_LONGARR).astore(L_GLOBALS);
        x.iconst_0().istore(L_GAS);

        // The chunk dispatch. Wasm needs nested blocks and a `br_table`; here
        // it is one switch into labels, which is what the flat bytecode wanted
        // all along.
        Label dflt = x.newLabel();
        List<SwitchCase> cases = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) cases.add(SwitchCase.of(i, labels[i]));
        x.iload(L_ENTRY).tableswitch(0, labels.length - 1, dflt, cases);
        x.labelBinding(dflt).return_();

        for (AotPlan.Chunk c : chunks) {
            x.labelBinding(labels[c.idx]);
            // Gas for the whole chunk, charged once. Between exits it lives in a
            // local, so a straight run of chunks touches no memory at all -- it
            // was a load, an add, a store and a compare PER CHUNK before that in
            // the wasm emitter, and most of what made its first version slower
            // than the interpreter.
            if (c.charge > 0) x.iload(L_GAS).loadConstant(c.charge).iadd().istore(L_GAS);
            for (AotPlan.Ins i : c.instrs) emit(x, i, c, labels, chunkOf);
        }
    }

    /// Reload what a crossing can invalidate.
    static void reload(CodeBuilder x) {
        x.aload(L_RT).getfield(CD_RT, "aotSync", CD_SYNC)
         .getfield(CD_SYNC, "stack", CD_LONGARR).astore(L_STACK);
        x.aload(L_RT).getfield(CD_RT, "aotSync", CD_SYNC)
         .getfield(CD_SYNC, "top", CD_int).istore(L_TOP);
    }

    /// `stack[top++] = <the long on the JVM stack>`.
    static void push(CodeBuilder x) {
        // value is on the stack; move it under the array+index
        x.lstore(L_T);
        x.aload(L_STACK).iload(L_TOP).lload(L_T).lastore();
        x.iinc(L_TOP, 1);
    }

    /// `t = stack[--top]`
    static void popToT(CodeBuilder x) {
        x.iinc(L_TOP, -1);
        x.aload(L_STACK).iload(L_TOP).laload().lstore(L_T);
    }

    /// `t = stack[top - 1]`, leaving the top alone.
    static void peekToT(CodeBuilder x) {
        x.aload(L_STACK).iload(L_TOP).loadConstant(1).isub().laload().lstore(L_T);
    }

    static void emit(CodeBuilder x, AotPlan.Ins i, AotPlan.Chunk c,
                     Label[] labels, Map<Integer,Integer> chunkOf) {
        switch (i.op) {
            case Op.NIL -> { x.loadConstant(Val.NIL); push(x); }
            case Op.TRUE -> { x.loadConstant(Val.TRUE); push(x); }
            case Op.FALSE -> { x.loadConstant(Val.FALSE); push(x); }
            case Op.INT -> { x.loadConstant(Val.fixnum(AotPlan.i16(i.b))); push(x); }
            case Op.CONST -> { x.aload(L_CONSTS).loadConstant(AotPlan.u16(i.b)).laload(); push(x); }
            case Op.VAR -> { x.aload(L_GLOBALS).loadConstant(AotPlan.u16(i.b)).laload(); push(x); }
            case Op.LOCAL -> { x.aload(L_STACK).iload(L_FP).loadConstant(i.b[0]).iadd().laload(); push(x); }
            case Op.LOCAL_W -> { x.aload(L_STACK).iload(L_FP).loadConstant(AotPlan.u16(i.b)).iadd().laload(); push(x); }
            case Op.SELF -> { x.aload(L_STACK).iload(L_RETTO).laload(); push(x); }
            case Op.SET_LOCAL -> {
                popToT(x);
                x.aload(L_STACK).iload(L_FP).loadConstant(i.b[0]).iadd().lload(L_T).lastore();
            }
            case Op.SET_LOCAL_W -> {
                popToT(x);
                x.aload(L_STACK).iload(L_FP).loadConstant(AotPlan.u16(i.b)).iadd().lload(L_T).lastore();
            }
            case Op.SET_VAR -> {
                popToT(x);
                x.aload(L_GLOBALS).loadConstant(AotPlan.u16(i.b)).lload(L_T).lastore();
            }
            case Op.POP -> x.iinc(L_TOP, -1);
            case Op.DUP -> { peekToT(x); x.lload(L_T); push(x); }
            // The closure is `stack[retTo]`, and an upvalue is one of its slots.
            // The frame deliberately does not cache the closure -- that copy was
            // once a root the collector could not see -- so this reads it the
            // way the interpreter does.
            case Op.UPVAL -> {
                x.aload(L_RT)
                 .aload(L_STACK).iload(L_RETTO).laload()
                 .loadConstant(i.b[0] + 1)
                 .invokevirtual(CD_RT, "slot", MethodTypeDesc.of(CD_long, CD_long, CD_int));
                push(x);
            }
            case Op.JUMP -> jump(x, AotPlan.jumpTarget(i), labels, chunkOf, i, c);
            case Op.JUMP_IF_FALSE -> { popToT(x); falsy(x); jumpIf(x, i, labels, chunkOf, c); }
            // The `keep` forms do not pop when they jump, so the pop belongs on
            // the fallthrough only.
            case Op.RETURN -> {
                x.aload(L_RT).iload(L_TOP).iload(L_GAS)
                 .invokestatic(CD_AOT, "aotReturn", MethodTypeDesc.of(CD_void, CD_RT, CD_int, CD_int));
                x.return_();
            }
            // A type predicate. No bail protocol and no reload: the helper
            // cannot allocate, cannot fail and cannot re-enter, so nothing
            // cached here can move underneath it. The answer is stored back over
            // the argument, so the top does not change either.
            case Op.TYPE_P -> {
                x.aload(L_STACK).iload(L_TOP).loadConstant(1).isub();
                x.aload(L_RT).loadConstant(i.b[0]).iload(L_TOP)
                 .invokestatic(CD_AOT, "aotTypeP", MethodTypeDesc.of(CD_long, CD_RT, CD_int, CD_int));
                x.lastore();
            }
            case Op.NATIVE -> {
                int nx = i.ip + i.len;
                Integer j = chunkOf.get(nx);
                x.aload(L_RT).loadConstant(AotPlan.u16(i.b)).loadConstant(i.b[2]).iload(L_TOP)
                 .loadConstant(i.ip).loadConstant(c.idx)
                 .loadConstant(j != null ? nx : Aot.NEVER).loadConstant(j != null ? j : 0)
                 .iload(L_GAS)
                 .invokestatic(CD_AOT, "aotNative", MethodTypeDesc.of(
                     CD_int, CD_RT, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int));
                Label ok = x.newLabel();
                x.ifeq(ok).return_().labelBinding(ok);
                x.iconst_0().istore(L_GAS);
                reload(x);
            }
            case Op.CALL -> {
                int nx = i.ip + i.len;
                Integer j = chunkOf.get(nx);
                x.aload(L_RT).loadConstant(i.b[0]).iload(L_TOP)
                 .loadConstant(i.ip).loadConstant(c.idx)
                 .loadConstant(j != null ? nx : Aot.NEVER).loadConstant(j != null ? j : 0)
                 .iload(L_GAS)
                 .invokestatic(CD_AOT, "aotCall", MethodTypeDesc.of(
                     CD_int, CD_RT, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int));
                Label ok = x.newLabel();
                x.ifeq(ok).return_().labelBinding(ok);
                x.iconst_0().istore(L_GAS);
                reload(x);
            }
            case Op.ADD_INT, Op.SUB_INT, Op.MUL_INT, Op.LT_INT, Op.LE_INT,
                 Op.GT_INT, Op.GE_INT, Op.EQ_INT -> intOp(x, i, c, chunkOf);
            // Everything else goes back to the interpreter for exactly ONE
            // instruction. That is what lets this be COMPLETE from the first
            // version instead of refusing a whole arity over one rare opcode,
            // and it is cheap for the same reason re-entry is.
            default -> bail(x, i, c, chunkOf);
        }
    }

    /// `t` is falsy: nil or false. Leaves 1/0 on the JVM stack.
    static void falsy(CodeBuilder x) {
        Label t = x.newLabel(), end = x.newLabel();
        x.lload(L_T).loadConstant(Val.NIL).lcmp().ifeq(t);
        x.lload(L_T).loadConstant(Val.FALSE).lcmp().ifeq(t);
        x.iconst_0().goto_(end).labelBinding(t).iconst_1().labelBinding(end);
    }

    static void truthy(CodeBuilder x) {
        Label f = x.newLabel(), end = x.newLabel();
        x.lload(L_T).loadConstant(Val.NIL).lcmp().ifeq(f);
        x.lload(L_T).loadConstant(Val.FALSE).lcmp().ifeq(f);
        x.iconst_1().goto_(end).labelBinding(f).iconst_0().labelBinding(end);
    }

    /// An unconditional jump to a target that begins a chunk, or a bail when it
    /// does not.
    static void jump(CodeBuilder x, int target, Label[] labels,
                     Map<Integer,Integer> chunkOf, AotPlan.Ins i, AotPlan.Chunk c) {
        Integer j = chunkOf.get(target);
        if (j == null) { bail(x, i, c, chunkOf); return; }
        maybeTick(x, target, i, c);
        x.goto_(labels[j]);
    }

    static void jumpIf(CodeBuilder x, AotPlan.Ins i, Label[] labels,
                       Map<Integer,Integer> chunkOf, AotPlan.Chunk c) {
        int target = AotPlan.jumpTarget(i);
        Integer j = chunkOf.get(target);
        Label no = x.newLabel();
        x.ifeq(no);
        if (j == null) bail(x, i, c, chunkOf);
        else { maybeTick(x, target, i, c); x.goto_(labels[j]); }
        x.labelBinding(no);
    }

    /// A BACK-EDGE: flush the gas and ask whether the interpreter's own tick
    /// would fire. This is the one place a long compiled loop can be preempted,
    /// which is what the deterministic scheduler needs -- and back-edges are a
    /// small share of executed instructions, so it is also the cheapest place.
    static void maybeTick(CodeBuilder x, int target, AotPlan.Ins i, AotPlan.Chunk c) {
        if (target >= i.ip) return;
        x.aload(L_RT).iload(L_GAS).iload(L_TOP).loadConstant(i.ip).loadConstant(c.idx)
         .invokestatic(CD_AOT, "aotTick",
             MethodTypeDesc.of(CD_int, CD_RT, CD_int, CD_int, CD_int, CD_int));
        Label go = x.newLabel();
        x.ifeq(go).return_().labelBinding(go);
        x.iconst_0().istore(L_GAS);
    }

    /// Hand control back at `ip`, and say where compiled code takes over again.
    /// ONE helper covers every exit, because every way back in is the same
    /// comparison on `ip`.
    static void bail(CodeBuilder x, AotPlan.Ins i, AotPlan.Chunk c, Map<Integer,Integer> chunkOf) {
        // A TAIL CALL replaces this frame, so there is no next instruction of
        // THIS arity left to run and naming one registers a re-entry point
        // against a frame that no longer exists.
        int nx = i.ip + i.len;
        Integer j = i.op == Op.TAIL_CALL ? null : chunkOf.get(nx);
        x.aload(L_RT).iload(L_TOP).loadConstant(i.ip)
         .loadConstant(j != null ? nx : Aot.NEVER).loadConstant(j != null ? j : 0)
         .iload(L_GAS)
         .invokestatic(CD_AOT, "aotBail",
             MethodTypeDesc.of(CD_void, CD_RT, CD_int, CD_int, CD_int, CD_int, CD_int));
        x.return_();
    }

    /// A specialised integer operation.
    ///
    /// The compiler emits these only where it PROVED both operands are
    /// integers, so the fast path below is the path taken. `^int` means integer
    /// and not fixnum -- a value past the fixnum range is a boxed bigint and
    /// still answers `int?` -- so the tags are still tested, and everything else
    /// goes to a helper rather than a bail, because a boundary per arithmetic
    /// instruction is the shape `emit-wasm-instead-of-dispatch` measured and rejected.
    static void intOp(CodeBuilder x, AotPlan.Ins i, AotPlan.Chunk c, Map<Integer,Integer> chunkOf) {
        x.iinc(L_TOP, -2);
        x.aload(L_STACK).iload(L_TOP).laload().lstore(L_X);
        x.aload(L_STACK).iload(L_TOP).loadConstant(1).iadd().laload().lstore(L_Y);
        Label slow = x.newLabel(), done = x.newLabel();
        isFixnum(x, L_X); x.ifeq(slow);
        isFixnum(x, L_Y); x.ifeq(slow);
        // Both fixnums: sign-extend the 48-bit payloads and operate.
        payload(x, L_X);
        payload(x, L_Y);
        switch (i.op) {
            case Op.ADD_INT, Op.SUB_INT, Op.MUL_INT -> {
                // Two 48-bit values cannot overflow a long, so the only question
                // is whether the RESULT still fits a fixnum.
                if (i.op == Op.ADD_INT) x.ladd();
                else if (i.op == Op.SUB_INT) x.lsub();
                else x.lmul();
                x.lstore(L_T);
                x.lload(L_T).loadConstant(Val.FIXNUM_MAX).lcmp();
                x.ifgt(slow);
                x.lload(L_T).loadConstant(Val.FIXNUM_MIN).lcmp();
                x.iflt(slow);
                x.lload(L_T)
                 .invokestatic(ClassDesc.of("com.flint.rt.Val"), "fixnum",
                     MethodTypeDesc.of(CD_long, CD_long));
            }
            default -> {
                x.lcmp();
                Label yes = x.newLabel(), no = x.newLabel();
                switch (i.op) {
                    case Op.LT_INT -> x.iflt(yes);
                    case Op.LE_INT -> x.ifle(yes);
                    case Op.GT_INT -> x.ifgt(yes);
                    case Op.GE_INT -> x.ifge(yes);
                    default -> x.ifeq(yes);
                }
                x.loadConstant(Val.FALSE).goto_(no).labelBinding(yes)
                 .loadConstant(Val.TRUE).labelBinding(no);
            }
        }
        push(x);
        x.goto_(done);

        x.labelBinding(slow);
        // The operands are still where they were; the helper reads them off the
        // value stack, so put the top back first.
        x.aload(L_RT).getfield(CD_RT, "aotSync", CD_SYNC).iload(L_TOP).loadConstant(2).iadd()
         .putfield(CD_SYNC, "top", CD_int);
        int nx = i.ip + i.len;
        Integer j = chunkOf.get(nx);
        x.aload(L_RT).loadConstant(i.op).iload(L_TOP).loadConstant(2).iadd()
         .loadConstant(i.ip).loadConstant(c.idx)
         .loadConstant(j != null ? nx : Aot.NEVER).loadConstant(j != null ? j : 0)
         .iload(L_GAS)
         .invokestatic(CD_AOT, "aotIntBinop", MethodTypeDesc.of(
             CD_int, CD_RT, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int));
        Label ok = x.newLabel();
        x.ifeq(ok).return_().labelBinding(ok);
        x.iconst_0().istore(L_GAS);
        reload(x);
        x.labelBinding(done);
    }

    /// Is the value in local `slot` a fixnum? 1/0 on the JVM stack.
    static void isFixnum(CodeBuilder x, int slot) {
        Label yes = x.newLabel(), end = x.newLabel();
        x.lload(slot).loadConstant(48).lushr().loadConstant(0xFFFFL).land()
         .loadConstant((long) Val.TAG_FIXNUM).lcmp().ifeq(yes);
        x.iconst_0().goto_(end).labelBinding(yes).iconst_1().labelBinding(end);
    }

    /// The signed 48-bit payload of the fixnum in local `slot`.
    static void payload(CodeBuilder x, int slot) {
        x.lload(slot).loadConstant(16).lshl().loadConstant(16).lshr();
    }

    private AotEmit() {}
}
