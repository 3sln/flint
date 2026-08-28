package com.flint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/// The flint interpreter, on the JVM (`doc/decisions/0010`, tier 2).
///
/// **The collector is gone**, and that is the point of the tier rather than a
/// shortcut: a flint value is a Java object, so the JVM's collector owns
/// lifetime and the generational copying collector -- the hardest single piece
/// of the wasm runtime -- simply does not exist here. Nothing in this file
/// roots anything, and there is no write barrier, because there is nothing that
/// moves.
///
/// Calls use the JVM's own stack rather than an explicit frame stack, which is
/// the same trade for the same reason: the JVM scans its stack, so live
/// references in Java locals are found. That is exactly what wasm could not do
/// (`doc/decisions/0001`) and why flint is an interpreter there at all.
public final class Vm {
    // Opcodes, from `runtime/src/vm.rs`.
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

    public final Img img;
    /// Var slots. `def` writes one; every read of a top-level name goes here.
    ///
    /// An `AtomicReferenceArray` rather than an `Object[]`, because several
    /// threads may run this program at once and one of them may be writing.
    /// The ordering it gives is what publishes the value SAFELY: a plain array
    /// write is visible to another thread eventually or never, and "eventually"
    /// is not a semantics.
    ///
    /// This is the whole of what multi-threading costs here, and the contrast
    /// with `doc/decisions/0028` is the point of tier 2. There is no safepoint
    /// to build because there is no collector of ours to stop; the JVM's owns
    /// lifetime, values are immutable, and `Kw`/`Sym` intern through a
    /// `ConcurrentHashMap` which gives for free the "one text, one object"
    /// property the native runtime spends a lock on.
    public final java.util.concurrent.atomic.AtomicReferenceArray<Object> vars;
    /// Native import index to the builtin it names. Resolved BY NAME, because
    /// an image's slots belong to whichever module it was linked against.
    private final Builtins.Fn[] natives;
    /// Have the initialisers run? Volatile and guarded, because two threads
    /// calling into a fresh sandbox must not both run them.
    private volatile boolean started = false;
    private final Object startLock = new Object();

    /// A function value: which function, and what it closed over.
    public record Closure(int fnIndex, Object[] upvals) {}

    /// A flint `throw` on its way out, carrying the thrown VALUE rather than a
    /// message: `catch` binds what was thrown, so flattening it to a string
    /// here would lose the program's own data.
    public static final class Thrown extends RuntimeException {
        public final transient Object value;
        public Thrown(Object value) {
            super(String.valueOf(value), null, false, false);
            this.value = value;
        }
    }

    public Vm(Img img) {
        this.img = img;
        this.vars = new java.util.concurrent.atomic.AtomicReferenceArray<>(img.varNames.length);
        this.natives = new Builtins.Fn[img.nativeNames.length];
        for (int i = 0; i < natives.length; i++) {
            String name = img.nativeNames[i];
            Builtins.Fn f = Builtins.byName(name);
            if (f == null) {
                // Left null rather than refused: an image imports every builtin
                // its namespace mentions, and a program that never calls the
                // missing one runs fine. The failure names it if it is reached.
                continue;
            }
            natives[i] = f;
        }
    }

    /// Run the image's initialisers, once. A sandbox serves many calls and they
    /// run once, not per call (`doc/decisions/0025`).
    public void ensureStarted() {
        if (started) return;
        synchronized (startLock) {
            if (started) return;
            for (int fn : img.init) call(new Closure(fn, new Object[0]), new Object[0]);
            // Set LAST: a thread that saw `started` before the initialisers
            // finished would call into a half-built program.
            started = true;
        }
    }

    /// Call a function value with positional arguments.
    public Object call(Object fn, Object[] args) {
        if (fn instanceof Closure c) {
            Img.FnDef def = img.fns[c.fnIndex()];
            Img.Arity a = def.select(args.length);
            if (a == null) {
                throw new Thrown("wrong number of arguments ("
                    + args.length + ") for " + (def.name == null ? "fn" : def.name));
            }
            Object[] locals = new Object[Math.max(a.nlocals, a.argc + 1)];
            int n = a.argc;
            System.arraycopy(args, 0, locals, 0, Math.min(n, args.length));
            if (a.variadic) {
                List<Object> rest = new ArrayList<>();
                for (int i = n; i < args.length; i++) rest.add(args[i]);
                locals[n] = rest.isEmpty() ? null : rest;
            }
            return run(c, a, locals);
        }
        if (fn instanceof Builtins.Fn f) return f.apply(this, args);
        if (fn instanceof Img.NativeRef nr) {
            Builtins.Fn f = Builtins.byName(nr.name());
            if (f == null) throw new Thrown("this runtime does not carry `" + nr.name() + "`");
            return f.apply(this, args);
        }
        // A keyword in call position looks itself up, which is Clojure's rule
        // and one programs lean on constantly.
        if (fn instanceof Kw k && args.length >= 1) return Builtins.get(args[0], k, null);
        throw new Thrown(String.valueOf(fn) + " is not a function");
    }

    private int u8(int ip) { return img.code[ip] & 0xFF; }
    private int u16(int ip) { return u8(ip) | (u8(ip + 1) << 8); }
    private int i16(int ip) { return (short) u16(ip); }

    /// Truthiness: only `nil` and `false` are false. Everything else -- zero,
    /// the empty string, the empty vector -- is true.
    static boolean truthy(Object v) { return v != null && v != Boolean.FALSE; }

    private Object run(Closure self, Img.Arity arity, Object[] locals) {
        Object[] stack = new Object[64];
        int sp = 0;
        int ip = arity.code;
        int end = arity.code + arity.len;
        // Handler stack for TRY/POP_HANDLER: each entry is the ip to jump to
        // and the stack depth to restore, because a throw unwinds the value
        // stack as well as the instruction pointer.
        int[] handlerIp = new int[8];
        int[] handlerSp = new int[8];
        int handlers = 0;

        for (;;) {
            if (ip >= end) return sp > 0 ? stack[sp - 1] : null;
            if (sp + 4 >= stack.length) {
                Object[] bigger = new Object[stack.length * 2];
                System.arraycopy(stack, 0, bigger, 0, sp);
                stack = bigger;
            }
            int opcode = u8(ip);
            ip += 1;
            try {
                switch (opcode) {
                    case NOP: break;
                    case CONST: stack[sp++] = img.consts[u16(ip)]; ip += 2; break;
                    case NIL: stack[sp++] = null; break;
                    case TRUE: stack[sp++] = Boolean.TRUE; break;
                    case FALSE: stack[sp++] = Boolean.FALSE; break;
                    case INT: stack[sp++] = (long) i16(ip); ip += 2; break;
                    case LOCAL: stack[sp++] = locals[u8(ip)]; ip += 1; break;
                    case LOCAL_W: stack[sp++] = locals[u16(ip)]; ip += 2; break;
                    case SET_LOCAL: locals[u8(ip)] = stack[--sp]; ip += 1; break;
                    case SET_LOCAL_KEEP: locals[u8(ip)] = stack[sp - 1]; ip += 1; break;
                    case UPVAL: stack[sp++] = self.upvals()[u8(ip)]; ip += 1; break;
                    case VAR: stack[sp++] = vars.get(u16(ip)); ip += 2; break;
                    case SET_VAR: vars.set(u16(ip), stack[--sp]); ip += 2; break;
                    case POP: sp -= 1; break;
                    case POP_N: sp -= u8(ip); ip += 1; break;
                    case DUP: stack[sp] = stack[sp - 1]; sp += 1; break;
                    case SELF: stack[sp++] = self; break;
                    case JUMP: ip += 2 + i16(ip); break;
                    case JUMP_IF_FALSE: {
                        int off = i16(ip); ip += 2;
                        if (!truthy(stack[--sp])) ip += off;
                        break;
                    }
                    case JUMP_IF_TRUE: {
                        int off = i16(ip); ip += 2;
                        if (truthy(stack[--sp])) ip += off;
                        break;
                    }
                    case JUMP_IF_FALSE_KEEP: {
                        int off = i16(ip); ip += 2;
                        if (!truthy(stack[sp - 1])) ip += off; else sp -= 1;
                        break;
                    }
                    case JUMP_IF_TRUE_KEEP: {
                        int off = i16(ip); ip += 2;
                        if (truthy(stack[sp - 1])) ip += off; else sp -= 1;
                        break;
                    }
                    case CALL: {
                        int argc = u8(ip); ip += 1;
                        Object[] args = new Object[argc];
                        System.arraycopy(stack, sp - argc, args, 0, argc);
                        sp -= argc + 1;
                        Object f = stack[sp];
                        stack[sp++] = call(f, args);
                        break;
                    }
                    case TAIL_CALL: {
                        // A real tail call: no JVM frame is added, so a loop
                        // written as self-recursion runs in constant stack --
                        // which the JVM would not give us for free.
                        int argc = u8(ip); ip += 1;
                        Object[] args = new Object[argc];
                        System.arraycopy(stack, sp - argc, args, 0, argc);
                        sp -= argc + 1;
                        Object f = stack[sp];
                        if (f instanceof Closure c && c.fnIndex() == self.fnIndex()) {
                            Img.Arity a = img.fns[c.fnIndex()].select(argc);
                            if (a != null) {
                                self = c;
                                arity = a;
                                locals = new Object[Math.max(a.nlocals, a.argc + 1)];
                                System.arraycopy(args, 0, locals, 0, Math.min(a.argc, argc));
                                if (a.variadic) {
                                    List<Object> rest = new ArrayList<>();
                                    for (int i = a.argc; i < argc; i++) rest.add(args[i]);
                                    locals[a.argc] = rest.isEmpty() ? null : rest;
                                }
                                ip = a.code;
                                end = a.code + a.len;
                                sp = 0;
                                handlers = 0;
                                continue;
                            }
                        }
                        return call(f, args);
                    }
                    case RETURN: return sp > 0 ? stack[--sp] : null;
                    case CLOSURE: {
                        int fnIdx = u16(ip); ip += 2;
                        int nup = u8(ip); ip += 1;
                        Object[] up = new Object[nup];
                        for (int i = nup - 1; i >= 0; i--) up[i] = stack[--sp];
                        stack[sp++] = new Closure(fnIdx, up);
                        break;
                    }
                    case NATIVE: {
                        int idx = u16(ip); ip += 2;
                        int argc = u8(ip); ip += 1;
                        Object[] args = new Object[argc];
                        System.arraycopy(stack, sp - argc, args, 0, argc);
                        sp -= argc;
                        Builtins.Fn f = natives[idx];
                        if (f == null) {
                            throw new Thrown("this runtime does not carry the builtin `"
                                + img.nativeNames[idx] + "`");
                        }
                        stack[sp++] = f.apply(this, args);
                        break;
                    }
                    case VECTOR: {
                        int n = u16(ip); ip += 2;
                        List<Object> xs = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) xs.add(stack[sp - n + i]);
                        sp -= n;
                        stack[sp++] = xs;
                        break;
                    }
                    case LIST: {
                        int n = u16(ip); ip += 2;
                        List<Object> xs = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) xs.add(stack[sp - n + i]);
                        sp -= n;
                        stack[sp++] = Seq.of(xs);
                        break;
                    }
                    case SET: {
                        int n = u16(ip); ip += 2;
                        LinkedHashSet<Object> xs = new LinkedHashSet<>();
                        for (int i = 0; i < n; i++) xs.add(stack[sp - n + i]);
                        sp -= n;
                        stack[sp++] = xs;
                        break;
                    }
                    case MAP: {
                        int n = u16(ip); ip += 2;
                        FlintMap m = FlintMap.empty();
                        for (int i = 0; i < n; i++) {
                            m = m.assoc(stack[sp - 2 * n + 2 * i], stack[sp - 2 * n + 2 * i + 1]);
                        }
                        sp -= 2 * n;
                        stack[sp++] = m;
                        break;
                    }
                    case APPLY: {
                        int argc = u8(ip); ip += 1;
                        Object seq = stack[--sp];
                        Object[] fixed = new Object[argc - 1];
                        System.arraycopy(stack, sp - (argc - 1), fixed, 0, argc - 1);
                        sp -= argc - 1;
                        Object f = stack[--sp];
                        List<Object> all = new ArrayList<>(List.of());
                        for (Object o : fixed) all.add(o);
                        for (Object o : Builtins.iterate(seq)) all.add(o);
                        stack[sp++] = call(f, all.toArray());
                        break;
                    }
                    case THROW: throw new Thrown(stack[--sp]);
                    case RETHROW: throw new Thrown(stack[--sp]);
                    case TRY: {
                        int off = u16(ip); ip += 2;
                        if (handlers == handlerIp.length) {
                            int[] a = new int[handlers * 2], b = new int[handlers * 2];
                            System.arraycopy(handlerIp, 0, a, 0, handlers);
                            System.arraycopy(handlerSp, 0, b, 0, handlers);
                            handlerIp = a; handlerSp = b;
                        }
                        handlerIp[handlers] = ip + off;
                        handlerSp[handlers] = sp;
                        handlers += 1;
                        break;
                    }
                    case POP_HANDLER: handlers -= 1; break;
                    case ADD_INT: { long b = num(stack[--sp]), a = num(stack[--sp]);
                                    stack[sp++] = Math.addExact(a, b); break; }
                    case SUB_INT: { long b = num(stack[--sp]), a = num(stack[--sp]);
                                    stack[sp++] = Math.subtractExact(a, b); break; }
                    case MUL_INT: { long b = num(stack[--sp]), a = num(stack[--sp]);
                                    stack[sp++] = Math.multiplyExact(a, b); break; }
                    case LT_INT: { long b = num(stack[--sp]), a = num(stack[--sp]);
                                   stack[sp++] = a < b; break; }
                    case LE_INT: { long b = num(stack[--sp]), a = num(stack[--sp]);
                                   stack[sp++] = a <= b; break; }
                    case GT_INT: { long b = num(stack[--sp]), a = num(stack[--sp]);
                                   stack[sp++] = a > b; break; }
                    case GE_INT: { long b = num(stack[--sp]), a = num(stack[--sp]);
                                   stack[sp++] = a >= b; break; }
                    case EQ_INT: { long b = num(stack[--sp]), a = num(stack[--sp]);
                                   stack[sp++] = a == b; break; }
                    case TYPE_P: {
                        int code = u8(ip); ip += 1;
                        stack[sp - 1] = Builtins.isType(stack[sp - 1], code);
                        break;
                    }
                    default:
                        throw new Thrown("unknown opcode 0x" + Integer.toHexString(opcode));
                }
            } catch (Thrown t) {
                if (handlers == 0) throw t;
                handlers -= 1;
                sp = handlerSp[handlers];
                ip = handlerIp[handlers];
                stack[sp++] = t.value;
            }
        }
    }

    /// flint's integers are i64 and **overflow throws**. The JVM's `long`
    /// wraps silently, which `doc/decisions/0010` names as one of the ways two
    /// hosts quietly disagree -- so every specialised arithmetic opcode above
    /// uses `Math.*Exact`, and this is where a non-integer is caught.
    static long num(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        throw new Thrown(String.valueOf(v) + " is not an integer");
    }
}
