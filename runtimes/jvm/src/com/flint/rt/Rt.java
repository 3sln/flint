package com.flint.rt;

import java.util.ArrayList;

import static com.flint.rt.Obj.*;

/// The interpreter, ported from `runtime/src/vm.rs`.
///
/// ## What makes this a mirror rather than a rewrite
///
/// ONE value stack, shared by every frame, with `fp` and `retTo` as offsets
/// into it. The previous JVM port gave each frame its own `Object[]`, which
/// worked and cost three things this does not: every value was boxed, the
/// collector could not see the stack, and a continuation could not be
/// serialised. Here the stack is `Roots.stack` -- scanned, movable, and
/// capturable -- which is what `doc/decisions/0015` needs and what
/// `doc/decisions/0005` needs.
///
/// The frame stack is DATA, so a green thread is a saved copy of it and a
/// snapshot carries it. Neither is bolted on afterwards; both fall out.
public final class Rt {
    public final Gc gc;
    public final Roots roots = new Roots();

    /// The image: bytecode, constants and the function table.
    public byte[] code = new byte[0];
    public long[] consts = new long[0];
    public FnDef[] fns = new FnDef[0];

    public final ArrayList<Frame> frames = new ArrayList<>();
    public final ArrayList<Handler> handlers = new ArrayList<>();

    /// The in-flight exception, or NIL. A distinguished VALUE rather than a
    /// host exception, because a throw has to unwind flint frames and a host
    /// exception would unwind the host stack instead.
    public long thrown = Val.NIL;

    public long steps;

    public Rt(long nurseryBytes, long maxHeap) {
        this.gc = new Gc(nurseryBytes, maxHeap);
        roots.consts = consts;
    }

    public static final class FnDef {
        public final Arity[] arities;
        public final int nupvals;
        public FnDef(Arity[] arities, int nupvals) { this.arities = arities; this.nupvals = nupvals; }
        public Arity select(int argc) {
            for (Arity a : arities) {
                if (a.variadic ? argc >= a.argc : argc == a.argc) return a;
            }
            return null;
        }
    }

    public static final class Arity {
        public final int argc, nlocals, code, len;
        public final boolean variadic;
        public Arity(int argc, boolean variadic, int nlocals, int code, int len) {
            this.argc = argc; this.variadic = variadic;
            this.nlocals = nlocals; this.code = code; this.len = len;
        }
    }

    public static final class Handler {
        public int frame, stackTop, target, shadow;
    }

    // --- allocation, with the rooting discipline ---------------------------

    public long alloc(int ty, int len) { return gc.alloc(roots, ty, len); }

    public void setSlot(long obj, int i, long v) { gc.setSlot(obj, i, v, roots); }
    public long slot(long v, int i) { return Obj.slot(gc.sp, Val.asHeap(v), i); }

    public int mark() { return roots.mark(); }
    public int push(long v) { return roots.push(v); }
    public long r(int i) { return roots.r(i); }
    public void setR(int i, long v) { roots.setR(i, v); }
    public void popTo(int n) { roots.popTo(n); }

    public void vpush(long v) { roots.vpush(v); }
    public long vpop() { return roots.vpop(); }
    public long vat(int i) { return roots.stack[i]; }

    /// Reserve stack so a frame's pushes are unchecked. Called on the way in,
    /// which is why a body makes no bounds check per push.
    void vreserve(int n) {
        while (roots.stackTop + n >= roots.stack.length) {
            long[] bigger = new long[roots.stack.length * 2];
            System.arraycopy(roots.stack, 0, bigger, 0, roots.stackTop);
            roots.stack = bigger;
        }
    }

    public long makeClosure(int fnIdx, long[] upvals) {
        int base = mark();
        for (long u : upvals) push(u);
        long a = alloc(TY_CLOSURE, 1 + upvals.length);
        if (a == 0) { popTo(base); return Val.NIL; }
        setSlot(a, 0, Val.fixnum(fnIdx));
        for (int i = 0; i < upvals.length; i++) setSlot(a, 1 + i, r(base + i));
        popTo(base);
        return Val.heap(a);
    }

    int u8(int ip) { return code[ip] & 0xFF; }
    int u16(int ip) { return u8(ip) | (u8(ip + 1) << 8); }
    int i16(int ip) { return (short) u16(ip); }

    // --- the dispatch loop -------------------------------------------------

    /// Push a frame for `closure`. The callee sits at `calleeAt`, args follow.
    public boolean enter(long closure, int calleeAt, int argc) {
        int fnIdx = (int) Val.asFixnum(slot(closure, 0));
        FnDef def = fns[fnIdx];
        Arity a = def.select(argc);
        if (a == null) {
            roots.stackTop = calleeAt;
            thrown = Val.fixnum(-1);   // an arity error; the real message needs strings
            return false;
        }
        int fp = calleeAt + 1;
        if (a.variadic) {
            // Fold the surplus into a list in the last fixed slot. Not built
            // until `seqs.rs` is ported; refused loudly rather than silently
            // producing the wrong shape.
            throw new UnsupportedOperationException("variadic arities need seqs.rs ported");
        }
        vreserve(a.nlocals + 8);
        for (int i = argc; i < a.nlocals; i++) roots.stack[fp + i] = Val.NIL;
        roots.stackTop = fp + a.nlocals;
        Frame f = new Frame();
        f.fp = fp;
        f.ip = a.code;
        f.end = a.code + a.len;
        f.retTo = calleeAt;
        f.handlers = handlers.size();
        frames.add(f);
        return true;
    }

    /// Run until the frame stack is back down to `baseDepth`.
    public long run(int baseDepth) {
        for (;;) {
            if (frames.size() <= baseDepth) return vpop();
            Frame f = frames.get(frames.size() - 1);
            int ip = f.ip;
            int fp = f.fp;

            if (ip >= f.end) {   // fell off the end: an implicit return
                long v = roots.stackTop > fp ? vpop() : Val.NIL;
                frames.remove(frames.size() - 1);
                roots.stackTop = f.retTo;
                vpush(v);
                if (frames.size() <= baseDepth) return vpop();
                continue;
            }

            int opcode = u8(ip);
            ip += 1;
            steps++;

            switch (opcode) {
                case Op.NOP -> {}
                case Op.CONST -> { vpush(consts[u16(ip)]); ip += 2; }
                case Op.NIL -> vpush(Val.NIL);
                case Op.TRUE -> vpush(Val.TRUE);
                case Op.FALSE -> vpush(Val.FALSE);
                case Op.INT -> { vpush(Val.fixnum(i16(ip))); ip += 2; }
                case Op.LOCAL -> { vpush(roots.stack[fp + u8(ip)]); ip += 1; }
                case Op.LOCAL_W -> { vpush(roots.stack[fp + u16(ip)]); ip += 2; }
                case Op.SET_LOCAL -> { roots.stack[fp + u8(ip)] = vpop(); ip += 1; }
                case Op.SET_LOCAL_KEEP -> { roots.stack[fp + u8(ip)] = roots.stack[roots.stackTop - 1]; ip += 1; }
                case Op.SELF -> vpush(roots.stack[f.retTo]);
                case Op.UPVAL -> { vpush(slot(roots.stack[f.retTo], 1 + u8(ip))); ip += 1; }
                case Op.POP -> roots.stackTop -= 1;
                case Op.POP_N -> { roots.stackTop -= u8(ip); ip += 1; }
                case Op.DUP -> vpush(roots.stack[roots.stackTop - 1]);
                case Op.JUMP -> ip += 2 + i16(ip);
                case Op.JUMP_IF_FALSE -> { int off = i16(ip); ip += 2; if (!Val.truthy(vpop())) ip += off; }
                case Op.JUMP_IF_TRUE -> { int off = i16(ip); ip += 2; if (Val.truthy(vpop())) ip += off; }
                case Op.JUMP_IF_FALSE_KEEP -> {
                    int off = i16(ip); ip += 2;
                    if (!Val.truthy(roots.stack[roots.stackTop - 1])) ip += off; else roots.stackTop -= 1;
                }
                case Op.JUMP_IF_TRUE_KEEP -> {
                    int off = i16(ip); ip += 2;
                    if (Val.truthy(roots.stack[roots.stackTop - 1])) ip += off; else roots.stackTop -= 1;
                }
                case Op.ADD_INT -> { long b = Val.asFixnum(vpop()), x = Val.asFixnum(vpop()); vpush(Val.fixnum(Math.addExact(x, b))); }
                case Op.SUB_INT -> { long b = Val.asFixnum(vpop()), x = Val.asFixnum(vpop()); vpush(Val.fixnum(Math.subtractExact(x, b))); }
                case Op.MUL_INT -> { long b = Val.asFixnum(vpop()), x = Val.asFixnum(vpop()); vpush(Val.fixnum(Math.multiplyExact(x, b))); }
                case Op.LT_INT -> { long b = Val.asFixnum(vpop()), x = Val.asFixnum(vpop()); vpush(Val.bool(x < b)); }
                case Op.LE_INT -> { long b = Val.asFixnum(vpop()), x = Val.asFixnum(vpop()); vpush(Val.bool(x <= b)); }
                case Op.GT_INT -> { long b = Val.asFixnum(vpop()), x = Val.asFixnum(vpop()); vpush(Val.bool(x > b)); }
                case Op.GE_INT -> { long b = Val.asFixnum(vpop()), x = Val.asFixnum(vpop()); vpush(Val.bool(x >= b)); }
                case Op.EQ_INT -> { long b = Val.asFixnum(vpop()), x = Val.asFixnum(vpop()); vpush(Val.bool(x == b)); }
                case Op.CLOSURE -> {
                    int fnIdx = u16(ip); int nup = u8(ip + 2); ip += 3;
                    long[] up = new long[nup];
                    for (int i = nup - 1; i >= 0; i--) up[i] = vpop();
                    vpush(makeClosure(fnIdx, up));
                }
                case Op.CALL -> {
                    int argc = u8(ip); ip += 1;
                    f.ip = ip;                       // committed BEFORE entering
                    int calleeAt = roots.stackTop - argc - 1;
                    long callee = roots.stack[calleeAt];
                    if (!Val.isHeap(callee) || ty(gc.sp, Val.asHeap(callee)) != TY_CLOSURE) {
                        throw new UnsupportedOperationException("only closures are callable until builtins are ported");
                    }
                    if (!enter(callee, calleeAt, argc)) return Val.NIL;
                    continue;
                }
                case Op.TAIL_CALL -> {
                    int argc = u8(ip); ip += 1;
                    f.ip = ip;
                    int calleeAt = roots.stackTop - argc - 1;
                    long callee = roots.stack[calleeAt];
                    // Drop this frame FIRST: that is what makes a tail call
                    // constant-space, and it is why mutual recursion between
                    // three functions runs for ever here and overflowed on the
                    // old port.
                    frames.remove(frames.size() - 1);
                    int dest = f.retTo;
                    for (int i = 0; i <= argc; i++) roots.stack[dest + i] = roots.stack[calleeAt + i];
                    roots.stackTop = dest + argc + 1;
                    if (!enter(callee, dest, argc)) return Val.NIL;
                    continue;
                }
                case Op.RETURN -> {
                    long v = vpop();
                    frames.remove(frames.size() - 1);
                    roots.stackTop = f.retTo;
                    vpush(v);
                    if (frames.size() <= baseDepth) return vpop();
                    continue;
                }
                default -> throw new UnsupportedOperationException(
                    "opcode 0x" + Integer.toHexString(opcode) + " is not ported yet");
            }
            f.ip = ip;
        }
    }

    /// Call `closure` with `args` from outside the interpreter.
    public long call(long closure, long[] args) {
        int save = roots.stackTop;
        vreserve(args.length + 1);
        vpush(closure);
        for (long a : args) vpush(a);
        int calleeAt = roots.stackTop - args.length - 1;
        int depth = frames.size();
        if (!enter(closure, calleeAt, args.length)) { roots.stackTop = save; return Val.NIL; }
        long v = run(depth);
        roots.stackTop = save;
        return v;
    }
}
