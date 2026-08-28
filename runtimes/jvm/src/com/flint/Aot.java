package com.flint;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Tier 3 for the JVM: emit bytecode for a flint arity instead of dispatching
/// it (`doc/decisions/0010`, `doc/decisions/0013`).
///
/// **Why this is a backend here and a fight on wasm.** flint is an interpreter
/// on wasm because wasm locals are not scannable, so compiled code would put
/// live references where a linear-memory collector cannot see them
/// (`doc/decisions/0001`). The JVM scans its own stack and owns lifetime, so
/// that constraint is simply absent.
///
/// The value stack stays an `Object[]` rather than the JVM's operand stack.
/// Not caution about the collector -- caution about the EMITTER: a value left
/// on the operand stack across a branch must have the same depth on every path
/// into a label, and flint's jumps do not respect that. An array and an `sp`
/// local make every opcode independent, which is what lets this be complete
/// rather than clever.
///
/// Uses `java.lang.classfile`, finalised in JDK 24, so there is no bytecode
/// library to depend on.
public final class Aot {
    private Aot() {}

    /// A compiled arity.
    public interface Compiled {
        Object run(Vm vm, Vm.Closure self, Object[] locals);
    }

    private static final int NOP = 0x00, CONST = 0x01, NIL = 0x02, TRUE = 0x03, FALSE = 0x04,
            INT = 0x05, LOCAL = 0x06, LOCAL_W = 0x07, SET_LOCAL = 0x08, UPVAL = 0x09,
            VAR = 0x0A, SET_VAR = 0x0B, POP = 0x0C, DUP = 0x0D, JUMP = 0x0E,
            JUMP_IF_FALSE = 0x0F, JUMP_IF_TRUE = 0x10, CALL = 0x11, TAIL_CALL = 0x12,
            RETURN = 0x13, CLOSURE = 0x14, NATIVE = 0x15, THROW = 0x16, TRY = 0x17,
            POP_HANDLER = 0x18, RETHROW = 0x19, VECTOR = 0x1A, MAP = 0x1B, SET = 0x1C,
            LIST = 0x1D, APPLY = 0x1E, JUMP_IF_FALSE_KEEP = 0x1F, JUMP_IF_TRUE_KEEP = 0x20,
            POP_N = 0x21, SET_LOCAL_KEEP = 0x22, SELF = 0x23,
            ADD_INT = 0x24, SUB_INT = 0x25, MUL_INT = 0x26, LT_INT = 0x27, LE_INT = 0x28,
            GT_INT = 0x29, GE_INT = 0x2A, EQ_INT = 0x2B, TYPE_P = 0x2C;

    /// Opcodes this emitter does not handle. A function using any of them is
    /// left to the interpreter ENTIRELY rather than compiled with a bail-out
    /// per instruction: a partly-compiled arity would need the two halves to
    /// agree about the stack, and not having that seam is not having that bug.
    private static boolean unsupported(int op) {
        return op == TRY || op == POP_HANDLER || op == THROW || op == RETHROW
            || op == TAIL_CALL || op == APPLY;
    }

    private static int operandLen(int op) {
        return switch (op) {
            case CONST, LOCAL_W, VAR, SET_VAR, JUMP, JUMP_IF_FALSE, JUMP_IF_TRUE,
                 JUMP_IF_FALSE_KEEP, JUMP_IF_TRUE_KEEP, TRY, VECTOR, MAP, SET, LIST, INT -> 2;
            case LOCAL, SET_LOCAL, SET_LOCAL_KEEP, UPVAL, CALL, TAIL_CALL, APPLY,
                 POP_N, TYPE_P -> 1;
            case CLOSURE, NATIVE -> 3;
            default -> 0;
        };
    }

    // --- helpers the emitted code calls -------------------------------------

    /// Boxed longs for the small values a loop counter actually takes. Every
    /// specialised int opcode boxes its result, and in a counting loop that is
    /// an allocation per iteration for a value that was just there.
    private static final int BOX_LO = -128, BOX_HI = 1024;
    private static final Long[] BOX = new Long[BOX_HI - BOX_LO + 1];
    static { for (int i = 0; i < BOX.length; i++) BOX[i] = (long) (i + BOX_LO); }

    public static Object boxLong(long v) {
        return v >= BOX_LO && v <= BOX_HI ? BOX[(int) (v - BOX_LO)] : Long.valueOf(v);
    }
    public static Object boxBool(boolean b) { return b ? Boolean.TRUE : Boolean.FALSE; }

    public static Object constAt(Vm vm, int i) { return vm.img.consts[i]; }
    public static Object varAt(Vm vm, int i) { return vm.vars.get(i); }
    public static Object setVar(Vm vm, int i, Object v) { vm.vars.set(i, v); return null; }
    public static Object upval(Vm.Closure self, int i) { return self.upvals()[i]; }
    public static long num(Object v) { return Vm.num(v); }
    public static boolean truthy(Object v) { return Vm.truthy(v); }
    public static Object isType(Object v, int code) { return Builtins.isType(v, code); }

    public static Object[] slice(Object[] stack, int at, int n) {
        Object[] out = new Object[n];
        System.arraycopy(stack, at, out, 0, n);
        return out;
    }

    public static Object callNative(Vm vm, int idx, Object[] stack, int at, int argc) {
        return vm.callNative(idx, slice(stack, at, argc));
    }

    public static Object callValue(Vm vm, Object f, Object[] stack, int at, int argc) {
        return vm.call(f, slice(stack, at, argc));
    }

    public static Object closure(int fnIdx, Object[] stack, int at, int nup) {
        return new Vm.Closure(fnIdx, slice(stack, at, nup));
    }

    /// Build a vector, list, set or map from `n` stack slots.
    public static Object coll(int kind, Object[] stack, int at, int n) {
        if (kind == MAP) {
            FlintMap m = FlintMap.empty();
            for (int i = 0; i < n; i++) m = m.assoc(stack[at + 2 * i], stack[at + 2 * i + 1]);
            return m;
        }
        List<Object> xs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) xs.add(stack[at + i]);
        if (kind == SET) return new java.util.LinkedHashSet<>(xs);
        return kind == LIST ? Seq.of(xs) : xs;
    }

    // --- the emitter ---------------------------------------------------------

    private static final ClassDesc CD_OBJ = ClassDesc.of("java.lang.Object");
    private static final ClassDesc CD_OBJARR = CD_OBJ.arrayType();
    private static final ClassDesc CD_VM = ClassDesc.of("com.flint.Vm");
    private static final ClassDesc CD_CLOSURE = ClassDesc.of("com.flint.Vm$Closure");
    private static final ClassDesc CD_AOT = ClassDesc.of("com.flint.Aot");
    private static final ClassDesc CD_COMPILED = ClassDesc.of("com.flint.Aot$Compiled");

    private static int counter = 0;

    /// Compile one arity, or return null if it uses something this does not do.
    public static Compiled tryCompile(Img img, Img.Arity a) {
        byte[] code = img.code;
        int start = a.code, end = a.code + a.len;

        Set<Integer> targets = new HashSet<>();
        for (int ip = start; ip < end; ) {
            int op = code[ip] & 0xFF;
            if (unsupported(op)) return null;
            int len = operandLen(op);
            if (op == JUMP || op == JUMP_IF_FALSE || op == JUMP_IF_TRUE
                || op == JUMP_IF_FALSE_KEEP || op == JUMP_IF_TRUE_KEEP) {
                int off = (short) ((code[ip + 1] & 0xFF) | ((code[ip + 2] & 0xFF) << 8));
                targets.add(ip + 3 + off);
            }
            ip += 1 + len;
        }

        String name = "com.flint.AotFn$" + (counter++);
        ClassDesc self = ClassDesc.of(name);
        byte[] bytes;
        try {
            bytes = ClassFile.of().build(self, cb -> {
                cb.withInterfaceSymbols(CD_COMPILED);
                cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
                cb.withMethod("<init>", MethodTypeDesc.of(ClassDesc.ofDescriptor("V")),
                    ClassFile.ACC_PUBLIC, mb -> mb.withCode(xb -> {
                        xb.aload(0);
                        xb.invokespecial(CD_OBJ, "<init>", MethodTypeDesc.of(ClassDesc.ofDescriptor("V")));
                        xb.return_();
                    }));
                cb.withMethod("run",
                    MethodTypeDesc.of(CD_OBJ, CD_VM, CD_CLOSURE, CD_OBJARR),
                    ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(xb -> emit(xb, img, a, targets)));
            });
        } catch (Throwable t) {
            return null;
        }

        try {
            MethodHandles.Lookup lk = MethodHandles.lookup()
                .defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE);
            return (Compiled) lk.lookupClass().getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            // An emitter bug must not be a wrong ANSWER: refuse the compiled
            // form and let the interpreter run it.
            return null;
        }
    }

    // Slots: 0=this 1=vm 2=self 3=locals 4=stack 5=sp 6=tmp 7,8=long scratch
    private static final int S_VM = 1, S_SELF = 2, S_LOCALS = 3, S_STACK = 4, S_SP = 5, S_TMP = 6;

    private static void emit(CodeBuilder xb, Img img, Img.Arity a, Set<Integer> targets) {
        byte[] code = img.code;
        int start = a.code, end = a.code + a.len;

        xb.loadConstant(Math.max(16, a.len + a.nlocals + 8));
        xb.anewarray(CD_OBJ);
        xb.astore(S_STACK);
        xb.loadConstant(0);
        xb.istore(S_SP);

        Map<Integer, Label> labels = new HashMap<>();
        for (int t : targets) labels.put(t, xb.newLabel());

        for (int ip = start; ip < end; ) {
            Label here = labels.get(ip);
            if (here != null) xb.labelBinding(here);
            int op = code[ip] & 0xFF;
            int len = operandLen(op);
            int b0 = code[ip + 1] & 0xFF;
            int u16 = (code[ip + 1] & 0xFF) | ((code[ip + 2] & 0xFF) << 8);
            int i16 = (short) u16;
            int next = ip + 1 + len;

            switch (op) {
                case NOP -> {}
                case NIL -> { xb.aconst_null(); push(xb); }
                case TRUE -> { xb.loadConstant(1); boxBool(xb); push(xb); }
                case FALSE -> { xb.loadConstant(0); boxBool(xb); push(xb); }
                case INT -> { xb.loadConstant((long) i16); boxLong(xb); push(xb); }
                case CONST -> { xb.aload(S_VM); xb.loadConstant(u16); call(xb, "constAt", CD_OBJ, CD_VM, intD()); push(xb); }
                case VAR -> { xb.aload(S_VM); xb.loadConstant(u16); call(xb, "varAt", CD_OBJ, CD_VM, intD()); push(xb); }
                case SET_VAR -> {
                    xb.aload(S_VM); xb.loadConstant(u16); popToStack(xb);
                    call(xb, "setVar", CD_OBJ, CD_VM, intD(), CD_OBJ); xb.pop();
                }
                case LOCAL, LOCAL_W -> {
                    xb.aload(S_LOCALS); xb.loadConstant(op == LOCAL ? b0 : u16);
                    xb.aaload(); push(xb);
                }
                case SET_LOCAL -> { xb.aload(S_LOCALS); xb.loadConstant(b0); popToStack(xb); xb.aastore(); }
                case SET_LOCAL_KEEP -> { xb.aload(S_LOCALS); xb.loadConstant(b0); peek(xb, 1); xb.aastore(); }
                case UPVAL -> { xb.aload(S_SELF); xb.loadConstant(b0); call(xb, "upval", CD_OBJ, CD_CLOSURE, intD()); push(xb); }
                case SELF -> { xb.aload(S_SELF); push(xb); }
                case POP -> drop(xb, 1);
                case POP_N -> drop(xb, b0);
                case DUP -> { peek(xb, 1); push(xb); }
                case JUMP -> xb.goto_(labels.get(next + i16));
                case JUMP_IF_FALSE -> { popToStack(xb); truthy(xb); xb.ifeq(labels.get(next + i16)); }
                case JUMP_IF_TRUE -> { popToStack(xb); truthy(xb); xb.ifne(labels.get(next + i16)); }
                case JUMP_IF_FALSE_KEEP -> { peek(xb, 1); truthy(xb); xb.ifeq(labels.get(next + i16)); drop(xb, 1); }
                case JUMP_IF_TRUE_KEEP -> { peek(xb, 1); truthy(xb); xb.ifne(labels.get(next + i16)); drop(xb, 1); }
                case RETURN -> { popToStack(xb); xb.areturn(); }
                case ADD_INT -> { twoLongs(xb); xb.ladd(); boxLong(xb); push(xb); }
                case SUB_INT -> { twoLongs(xb); xb.lsub(); boxLong(xb); push(xb); }
                case MUL_INT -> { twoLongs(xb); xb.lmul(); boxLong(xb); push(xb); }
                case LT_INT -> cmp(xb, -1, false);
                case GT_INT -> cmp(xb, 1, false);
                case LE_INT -> cmp(xb, 1, true);
                case GE_INT -> cmp(xb, -1, true);
                case EQ_INT -> cmp(xb, 0, false);
                case TYPE_P -> {
                    peek(xb, 1); xb.loadConstant(b0);
                    call(xb, "isType", CD_OBJ, CD_OBJ, intD());
                    xb.astore(S_TMP);
                    xb.aload(S_STACK); xb.iload(S_SP); xb.loadConstant(1); xb.isub();
                    xb.aload(S_TMP); xb.aastore();
                }
                case NATIVE -> {
                    int argc = code[ip + 3] & 0xFF;
                    xb.aload(S_VM); xb.loadConstant(u16); xb.aload(S_STACK);
                    xb.iload(S_SP); xb.loadConstant(argc); xb.isub();
                    xb.loadConstant(argc);
                    call(xb, "callNative", CD_OBJ, CD_VM, intD(), CD_OBJARR, intD(), intD());
                    xb.astore(S_TMP); drop(xb, argc); xb.aload(S_TMP); push(xb);
                }
                case CALL -> {
                    int argc = b0;
                    xb.aload(S_VM); peek(xb, argc + 1); xb.aload(S_STACK);
                    xb.iload(S_SP); xb.loadConstant(argc); xb.isub();
                    xb.loadConstant(argc);
                    call(xb, "callValue", CD_OBJ, CD_VM, CD_OBJ, CD_OBJARR, intD(), intD());
                    xb.astore(S_TMP); drop(xb, argc + 1); xb.aload(S_TMP); push(xb);
                }
                case CLOSURE -> {
                    int nup = code[ip + 3] & 0xFF;
                    xb.loadConstant(u16); xb.aload(S_STACK);
                    xb.iload(S_SP); xb.loadConstant(nup); xb.isub();
                    xb.loadConstant(nup);
                    call(xb, "closure", CD_OBJ, intD(), CD_OBJARR, intD(), intD());
                    xb.astore(S_TMP); drop(xb, nup); xb.aload(S_TMP); push(xb);
                }
                case VECTOR, LIST, SET, MAP -> {
                    int n = u16;
                    int slots = op == MAP ? 2 * n : n;
                    xb.loadConstant(op); xb.aload(S_STACK);
                    xb.iload(S_SP); xb.loadConstant(slots); xb.isub();
                    xb.loadConstant(n);
                    call(xb, "coll", CD_OBJ, intD(), CD_OBJARR, intD(), intD());
                    xb.astore(S_TMP); drop(xb, slots); xb.aload(S_TMP); push(xb);
                }
                default -> throw new IllegalStateException("unhandled opcode " + op);
            }
            ip = next;
        }

        // Falling off the end returns the top of stack, as the interpreter does.
        Label empty = xb.newLabel();
        xb.iload(S_SP); xb.ifle(empty);
        peek(xb, 1); xb.areturn();
        xb.labelBinding(empty);
        xb.aconst_null(); xb.areturn();
    }

    private static ClassDesc intD() { return ClassDesc.ofDescriptor("I"); }

    private static void call(CodeBuilder xb, String name, ClassDesc ret, ClassDesc... args) {
        xb.invokestatic(CD_AOT, name, MethodTypeDesc.of(ret, args));
    }
    private static void boxLong(CodeBuilder xb) {
        xb.invokestatic(CD_AOT, "boxLong", MethodTypeDesc.of(CD_OBJ, ClassDesc.ofDescriptor("J")));
    }
    private static void boxBool(CodeBuilder xb) {
        xb.invokestatic(CD_AOT, "boxBool", MethodTypeDesc.of(CD_OBJ, ClassDesc.ofDescriptor("Z")));
    }
    private static void truthy(CodeBuilder xb) {
        xb.invokestatic(CD_AOT, "truthy", MethodTypeDesc.of(ClassDesc.ofDescriptor("Z"), CD_OBJ));
    }
    private static void toLong(CodeBuilder xb) {
        xb.invokestatic(CD_AOT, "num", MethodTypeDesc.of(ClassDesc.ofDescriptor("J"), CD_OBJ));
    }

    private static void push(CodeBuilder xb) {
        xb.astore(S_TMP);
        xb.aload(S_STACK); xb.iload(S_SP); xb.aload(S_TMP); xb.aastore();
        xb.iload(S_SP); xb.loadConstant(1); xb.iadd(); xb.istore(S_SP);
    }
    private static void peek(CodeBuilder xb, int depth) {
        xb.aload(S_STACK); xb.iload(S_SP); xb.loadConstant(depth); xb.isub(); xb.aaload();
    }
    private static void drop(CodeBuilder xb, int n) {
        xb.iload(S_SP); xb.loadConstant(n); xb.isub(); xb.istore(S_SP);
    }
    private static void popToStack(CodeBuilder xb) { peek(xb, 1); drop(xb, 1); }

    /// Both operands of a specialised int op, as longs, in the right order.
    private static void twoLongs(CodeBuilder xb) {
        peek(xb, 2); toLong(xb);
        peek(xb, 1); toLong(xb);
        drop(xb, 2);
    }

    /// `lcmp` gives -1/0/1; `want` says which of those is true, and `negate`
    /// flips it for `<=` and `>=`.
    private static void cmp(CodeBuilder xb, int want, boolean negate) {
        twoLongs(xb);
        xb.lcmp();
        Label yes = xb.newLabel(), done = xb.newLabel();
        xb.loadConstant(want);
        if (negate) xb.if_icmpne(yes); else xb.if_icmpeq(yes);
        xb.loadConstant(0);
        xb.goto_(done);
        xb.labelBinding(yes);
        xb.loadConstant(1);
        xb.labelBinding(done);
        boxBool(xb);
        push(xb);
    }
}
