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

    /// The singleton slots, from `rt.rs`. Numbered rather than named fields so
    /// a snapshot can write them as one array.
    public static final int SING_EMPTY_LIST = 0, SING_EMPTY_VEC = 1, SING_EMPTY_MAP = 2,
        SING_EMPTY_SET = 3, SING_SCHED = 4, SING_BINDINGS = 5, SING_COUNT = 6;

    public long steps;

    /// True once a scheduler exists. `run` compares `steps` against
    /// `checkpoint` once per instruction; 0 there means nothing is counting, so
    /// a program that never spawns runs a loop with no counter in it at all.
    public boolean schedInstalled;

    public void setSliceEnd(long at) {
        sliceEnd = at;
        checkpoint = at;
    }

    /// The rest of the interpreter's state, all of it snapshot-visible.
    ///
    /// These are here rather than spread across the classes that use them for
    /// the reason `doc/decisions/0015` gives: a snapshot is a COPY of the VM,
    /// so anything that survives a park has to be findable in one place. The
    /// Rust keeps exactly this set on its `Rt`, and the port keeps it in the
    /// same order so the two write byte-identical snapshots.
    public long parkOn = Val.NIL;
    public long gasLimit;
    public long sliceEnd;
    public long checkpoint;
    public int gasTrips;
    public int memTrips;
    public int status;
    public boolean champAdded;

    /// FNV-1a over the image bytes. A snapshot carries the heap and the VM
    /// state and NO CODE, but every `ip`, constant index and var slot in it is
    /// an index INTO an image -- so restoring against a different program does
    /// not fail, it quietly means something else. This is what makes that
    /// refusable.
    public long fingerprint;

    /// How many host-minted opaque values the last import brought back. Read by
    /// a test to know the sweep saw anything at all: a zero would otherwise
    /// pass every assertion for the wrong reason.
    public int restoredCapabilities;

    /// The builtins this image imports, resolved BY NAME. The slots in an image
    /// belong to the module it was linked against and mean nothing here, which
    /// is what makes an image portable between hosts at all.
    public Builtins.Fn[] natives = new Builtins.Fn[0];
    public String[] nativeNames = new String[0];

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
            // Fold the surplus into a list in the last fixed slot. A SEQ, not a
            // vector: `clojure.core/list` is `[& xs] xs`, so a vector here makes
            // `(list 1 2)` print as `[1 2]`.
            int extra = argc - a.argc;
            int mk = mark();
            for (int i = 0; i < extra; i++) push(roots.stack[fp + a.argc + i]);
            long restv = extra == 0 ? Val.NIL : Seqs.fromRoots(this, mk, extra);
            popTo(mk);
            vreserve(a.nlocals + 8);
            roots.stack[fp + a.argc] = restv;
            for (int i = a.argc + 1; i < a.nlocals; i++) roots.stack[fp + i] = Val.NIL;
            roots.stackTop = fp + a.nlocals;
            Frame vf = new Frame();
            vf.fp = fp; vf.ip = a.code; vf.end = a.code + a.len;
            vf.retTo = calleeAt; vf.handlers = handlers.size();
            frames.add(vf);
            return true;
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
                while (handlers.size() > f.handlers) handlers.remove(handlers.size() - 1);
                frames.remove(frames.size() - 1);
                roots.stackTop = f.retTo;
                vpush(v);
                if (frames.size() <= baseDepth) return vpop();
                continue;
            }

            // The slice check, BEFORE the opcode is read.
            //
            // The saved `ip` must point AT the next instruction, not past its
            // first byte. Checking after `ip += 1` resumed on the OPERANDS --
            // `(+ 1 <operand-read-as-opcode>)` -- and the first symptom was
            // "not a number: an integer and nil" from a builtin whose second
            // argument had never been pushed.
            //
            // One comparison against a precomputed value, and only when
            // something is counting: `checkpoint` is 0 in a program with no
            // scheduler, so that loop has no counter in it at all.
            if (checkpoint != 0 && steps >= checkpoint) {
                f.ip = ip;
                checkpoint = 0;
                // A COURTESY yield, not a park: the thread stays runnable and
                // must NOT rewind. Preemption is what keeps a thread with no
                // `yield` in it from starving the others.
                parkOn = Conc.PARK_YIELD;
                thrown = Val.PARK;
                return Val.NIL;
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
                case Op.VAR -> { vpush(roots.globals[u16(ip)]); ip += 2; }
                case Op.SET_VAR -> { roots.globals[u16(ip)] = vpop(); ip += 2; }
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
                    if (Val.isHeap(callee) && ty(gc.sp, Val.asHeap(callee)) == TY_CLOSURE) {
                        if (!enter(callee, calleeAt, argc)) return Val.NIL;
                        continue;
                    }
                    // Everything else completes IN PLACE: a builtin held in a
                    // var, a keyword used as a function, a collection looked up.
                    long cv = callValue(calleeAt, argc);
                    if (parked()) {
                        // Same rewind as NATIVE: the callee and its arguments
                        // are still on the stack, so re-executing finds them.
                        thrown = Val.NIL;
                        if (parkOn == Conc.PARK_YIELD) {
                            roots.stackTop = calleeAt;
                            vpush(Val.NIL);
                        } else {
                            f.ip = ip - 2;   // back onto the CALL and its argc
                        }
                        thrown = Val.PARK;
                        return Val.NIL;
                    }
                    roots.stackTop = calleeAt;
                    vpush(cv);
                }
                case Op.TAIL_CALL -> {
                    int opAt = ip - 1;
                    int argc = u8(ip); ip += 1;
                    f.ip = ip;
                    int calleeAt = roots.stackTop - argc - 1;
                    long callee = roots.stack[calleeAt];
                    if (isHeapTy(callee, TY_CLOSURE)) {
                        // Drop this frame FIRST: that is what makes a tail call
                        // constant-space, and it is why mutual recursion between
                        // three functions runs for ever here and overflowed on
                        // the old port.
                        frames.remove(frames.size() - 1);
                        while (handlers.size() > f.handlers) handlers.remove(handlers.size() - 1);
                        int dest = f.retTo;
                        for (int i = 0; i <= argc; i++) roots.stack[dest + i] = roots.stack[calleeAt + i];
                        roots.stackTop = dest + argc + 1;
                        if (!enter(callee, dest, argc)) return Val.NIL;
                        continue;
                    }
                    // A tail call to something that is NOT a closure: a builtin
                    // held in a var, a keyword, a collection. It completes in
                    // place and then this frame returns its value.
                    //
                    // Entering it as a closure instead read `slot(callee, 0)`
                    // off whatever the value happened to be -- for a fixnum,
                    // an address 2 TB into a 2 GB heap. The compiler is the
                    // first program big enough to tail-call a builtin.
                    long cv = callValue(calleeAt, argc);
                    if (parked()) {
                        thrown = Val.NIL;
                        if (parkOn == Conc.PARK_YIELD) {
                            roots.stackTop = calleeAt;
                            vpush(cv);
                        } else {
                            f.ip = opAt;
                        }
                        thrown = Val.PARK;
                        return Val.NIL;
                    }
                    roots.stackTop = calleeAt;
                    vpush(cv);
                    long tv = vpop();
                    frames.remove(frames.size() - 1);
                    while (handlers.size() > f.handlers) handlers.remove(handlers.size() - 1);
                    roots.stackTop = f.retTo;
                    vpush(tv);
                    if (frames.size() <= baseDepth) return vpop();
                    continue;
                }
                case Op.RETURN -> {
                    long v = vpop();
                    frames.remove(frames.size() - 1);
                    // Handlers installed by this frame go with it. A `try` that
                    // returns from inside its body would otherwise leave its
                    // handler on the stack, and the next throw anywhere would
                    // unwind to a frame that had already returned.
                    while (handlers.size() > f.handlers) handlers.remove(handlers.size() - 1);
                    roots.stackTop = f.retTo;
                    vpush(v);
                    if (frames.size() <= baseDepth) return vpop();
                    continue;
                }
                case Op.VECTOR -> {
                    int nv = u16(ip); ip += 2;
                    // The elements are already on the VALUE stack; they are
                    // moved to the shadow stack so they stay rooted across the
                    // allocations `conj` makes.
                    int base = mark();
                    for (int i = 0; i < nv; i++) push(roots.stack[roots.stackTop - nv + i]);
                    roots.stackTop -= nv;
                    long v = Vec.fromRoots(this, base, nv);
                    popTo(base);
                    vpush(v);
                }
                case Op.MAP -> {
                    int nv = u16(ip); ip += 2;
                    // The pairs are on the VALUE stack; the map under
                    // construction goes on the SHADOW stack, because `assoc`
                    // allocates and the accumulator has to survive it.
                    int base = mark();
                    int mi = push(Maps.empty(this));
                    int at = roots.stackTop - 2 * nv;
                    for (int i = 0; i < nv; i++) {
                        long nm = Maps.assoc(this, r(mi), roots.stack[at + 2 * i],
                                             roots.stack[at + 2 * i + 1]);
                        setR(mi, nm);
                    }
                    long m = r(mi);
                    popTo(base);
                    roots.stackTop = at;
                    vpush(m);
                }
                case Op.THROW -> {
                    thrown = vpop();
                    f.ip = ip;
                    if (!unwind()) return Val.NIL;
                    continue;
                }
                case Op.RETHROW -> {
                    thrown = vpop();
                    f.ip = ip;
                    if (!unwind()) return Val.NIL;
                    continue;
                }
                case Op.TRY -> {
                    int off = i16(ip); ip += 2;
                    Handler h = new Handler();
                    h.frame = frames.size() - 1;
                    h.stackTop = roots.stackTop;
                    h.target = ip + off;
                    // The SHADOW depth too, not just the value stack. A throw
                    // out of a builtin that was midway through rooting would
                    // otherwise leave those entries live for ever, and the
                    // collector would keep whatever they name.
                    h.shadow = roots.shadowTop;
                    handlers.add(h);
                }
                case Op.POP_HANDLER -> handlers.remove(handlers.size() - 1);
                case Op.SET -> {
                    int nv = u16(ip); ip += 2;
                    int base = mark();
                    int si = push(Sets.empty(this));
                    int at = roots.stackTop - nv;
                    for (int i = 0; i < nv; i++) {
                        setR(si, Sets.conj(this, r(si), roots.stack[at + i]));
                    }
                    long sv = r(si);
                    popTo(base);
                    roots.stackTop = at;
                    vpush(sv);
                }
                case Op.LIST -> {
                    int nv = u16(ip); ip += 2;
                    // The elements move to the shadow stack first: `cons`
                    // allocates, and the value stack is where they are now.
                    int base = mark();
                    int at = roots.stackTop - nv;
                    for (int i = 0; i < nv; i++) push(roots.stack[at + i]);
                    long lv = Seqs.fromRoots(this, base, nv);
                    popTo(base);
                    roots.stackTop = at;
                    vpush(lv);
                }
                case Op.TYPE_P -> {
                    int c = u8(ip); ip += 1;
                    roots.stack[roots.stackTop - 1] = Val.bool(typeP(c, roots.stack[roots.stackTop - 1]));
                }
                case Op.NATIVE -> {
                    int opAt = ip - 1;
                    int idx = u16(ip); int argc = u8(ip + 2); ip += 3;
                    f.ip = ip;
                    int at = roots.stackTop - argc;
                    Builtins.Fn fn = natives[idx];
                    if (fn == null) {
                        throw new UnsupportedOperationException(
                            "this runtime does not carry the builtin `" + nativeNames[idx] + "`");
                    }
                    long v = fn.apply(this, at, argc);
                    if (parked()) {
                        // A PARKING builtin. Rewind to the instruction and
                        // leave the operands where they are: resuming
                        // RE-EXECUTES the call, which is why such a builtin must
                        // decide to park before it changes anything.
                        //
                        // Except a courtesy yield, which is finished: rewinding
                        // there would re-execute `yield`, which yields again,
                        // for ever, and the scheduler reports no progress.
                        thrown = Val.NIL;
                        if (parkOn == Conc.PARK_YIELD) {
                            roots.stackTop = at;
                            vpush(Val.NIL);
                            thrown = Val.PARK;
                            return Val.NIL;
                        }
                        f.ip = opAt;
                        roots.stackTop = at + argc;
                        thrown = Val.PARK;
                        return Val.NIL;
                    }
                    roots.stackTop = at;
                    vpush(v);
                }
                default -> throw new UnsupportedOperationException(
                    "opcode 0x" + Integer.toHexString(opcode) + " is not ported yet");
            }
            f.ip = ip;
        }
    }

    /// A call whose callee is not a closure.
    ///
    /// Clojure's rule, and one the reader itself leans on: `(#{\space \tab} c)`
    /// is how whitespace is tested, so without collections-in-call-position the
    /// compiler cannot read its own source.
    long callValue(int calleeAt, int argc) {
        long callee = roots.stack[calleeAt];
        if (Val.isInlineKw(callee) || isHeapTy(callee, TY_KW)) {
            // A keyword looks itself up in the collection it is given.
            long coll = argc >= 1 ? roots.stack[calleeAt + 1] : Val.NIL;
            long dflt = argc >= 2 ? roots.stack[calleeAt + 2] : Val.NIL;
            return lookup(coll, callee, dflt);
        }
        if (isHeapTy(callee, TY_NATIVEFN)) {
            int idx = (int) Val.asFixnum(slot(callee, 0));
            Builtins.Fn fn = idx < natives.length ? natives[idx] : null;
            if (fn == null) {
                throw new UnsupportedOperationException(
                    "this runtime does not carry the builtin `"
                    + (idx < nativeNames.length ? nativeNames[idx] : "#" + idx) + "`");
            }
            return fn.apply(this, calleeAt + 1, argc);
        }
        if (Sets.isSet(this, callee)) {
            if (argc < 1) throw new UnsupportedOperationException("a set takes 1 argument");
            return Sets.get(this, callee, roots.stack[calleeAt + 1], Val.NIL);
        }
        if (Maps.isMap(this, callee)) {
            if (argc < 1) throw new UnsupportedOperationException("a map takes 1 or 2 arguments");
            long dflt = argc >= 2 ? roots.stack[calleeAt + 2] : Val.NIL;
            return Maps.get(this, callee, roots.stack[calleeAt + 1], dflt);
        }
        if (isHeapTy(callee, TY_VEC)) {
            if (argc < 1) throw new UnsupportedOperationException("a vector takes 1 argument");
            long got = Vec.nth(this, callee, (int) Val.asFixnum(roots.stack[calleeAt + 1]));
            return got == Val.NOT_FOUND ? Val.NIL : got;
        }
        throw new UnsupportedOperationException(
            "value is not a function (object type "
            + (Val.isHeap(callee) ? String.valueOf(ty(gc.sp, Val.asHeap(callee))) : "inline")
            + ", " + argc + " args)");
    }

    /// `get`, for the collections that are ported.
    long lookup(long coll, long k, long dflt) {
        if (Val.isNil(coll)) return dflt;
        if (Maps.isMap(this, coll)) return Maps.get(this, coll, k, dflt);
        if (Sets.isSet(this, coll)) return Sets.get(this, coll, k, dflt);
        if (isHeapTy(coll, TY_VEC)) {
            if (!Val.isFixnum(k)) return dflt;
            long got = Vec.nth(this, coll, (int) Val.asFixnum(k));
            return got == Val.NOT_FOUND ? dflt : got;
        }
        return dflt;   // `get` on a non-collection is nil, as Clojure's is
    }

    /// `flint.types/code`'s canonical table, from `vm.rs`. The numbers are the
    /// contract between the compiler and every runtime, so they are written out
    /// rather than derived: a port that renumbered one of these would compile
    /// and answer wrongly.
    public boolean typeP(int code, long v) {
        return switch (code) {
            // `isInt`, NOT `isFixnum`. A big integer is an integer, and the
            // library's printer dispatches on this: with `isFixnum` here the
            // bits of 1.5 printed as `#<unprintable>` rather than as
            // 4609434218613702656, because a bigint fell through every arm.
            case 1 -> Num.isInt(this, v);
            case 2 -> Val.isDouble(v);
            case 3 -> Num.isNumber(this, v);
            case 4 -> Str.isString(this, v);
            case 5 -> Val.isInlineKw(v) || isHeapTy(v, TY_KW);
            case 6 -> isHeapTy(v, TY_SYM);
            case 7 -> v == Val.TRUE || v == Val.FALSE;
            case 8 -> isHeapTy(v, TY_VEC);
            case 9 -> isHeapTy(v, TY_ARRAYMAP) || isHeapTy(v, TY_HASHMAP);
            case 10 -> isHeapTy(v, TY_SET);
            case 11 -> isSeq(v);
            case 12 -> isHeapTy(v, TY_CLOSURE) || isHeapTy(v, TY_NATIVEFN);
            case 13 -> Val.isNil(v);
            default -> isHeapTy(v, TY_VEC) || isSeq(v);
        };
    }

    /// What a value IS, for a message. A refusal that says "needs more of the
    /// data structures" without naming the type sends the reader back to a
    /// debugger; naming it is the difference between a report and a shrug.
    public String describe(long v) {
        if (Val.isNil(v)) return "nil";
        if (Val.isFixnum(v)) return "an integer";
        if (Val.isDouble(v)) return "a double";
        if (v == Val.TRUE || v == Val.FALSE) return "a boolean";
        if (Val.isInlineStr(v)) return "an inline string";
        if (Val.isInlineKw(v)) return "an inline keyword";
        if (!Val.isHeap(v)) return "an unknown immediate";
        return switch (ty(gc.sp, Val.asHeap(v))) {
            case TY_STR -> "a string";
            case TY_SYM -> "a symbol";
            case TY_KW -> "a keyword";
            case TY_CONS -> "a list";
            case TY_EMPTY_LIST -> "an empty list";
            case TY_LAZYSEQ -> "a lazy seq";
            case TY_VEC -> "a vector";
            case TY_VECSEQ -> "a vector seq";
            case TY_RANGE -> "a range";
            case TY_ARRAYMAP -> "an array-map";
            case TY_HASHMAP -> "a hash-map";
            case TY_SET -> "a set";
            case TY_MAPENTRY -> "a map entry";
            case TY_CLOSURE -> "a function";
            case TY_NATIVEFN -> "a builtin";
            case TY_TVEC -> "a transient vector";
            case TY_TMAP -> "a transient map";
            case TY_TSET -> "a transient set";
            case TY_ROPE -> "a rope";
            case TY_BYTES -> "a byte string";
            case TY_RECORD -> "a record";
            case TY_ATOM -> "an atom";
            case TY_VAR -> "a var";
            case TY_DELAY -> "a delay";
            case TY_REGEX -> "a regex";
            case TY_MULTIFN -> "a multimethod";
            case TY_REDUCED -> "a reduced";
            case TY_EXINFO -> "an ex-info";
            default -> "object type " + ty(gc.sp, Val.asHeap(v));
        };
    }

    /// Which slot holds this object's metadata, or -1. Metadata is not part
    /// of equality, so `with-meta` copies and the copy is still `=`.
    public int metaSlot(long v) {
        if (!Val.isHeap(v)) return -1;
        switch (ty(gc.sp, Val.asHeap(v))) {
            case TY_SYM: return 2;
            case TY_VEC: return Vec.V_META;
            case TY_ARRAYMAP: return Maps.AM_META;
            case TY_HASHMAP: return Maps.HM_META;
            case TY_SET: return Sets.S_META;
            case TY_CONS: return Seqs.C_META;
            case TY_EMPTY_LIST: return 0;
            case TY_LAZYSEQ: return 2;
            case TY_ATOM: return 1;
            default: return -1;
        }
    }

    boolean isHeapTy(long v, int t) {
        return Val.isHeap(v) && ty(gc.sp, Val.asHeap(v)) == t;
    }

    boolean isSeq(long v) {
        if (!Val.isHeap(v)) return false;
        int t = ty(gc.sp, Val.asHeap(v));
        return t == TY_CONS || t == TY_EMPTY_LIST || t == TY_LAZYSEQ
            || t == TY_VECSEQ || t == TY_STRSEQ || t == TY_RANGE;
    }

    /// Sequential, which is WIDER than `isSeq`: a vector and a map entry are
    /// sequential without being seqs. `=` is over this, not over seq-ness.
    public boolean isSequential(long v) {
        return isSeq(v) || isHeapTy(v, TY_VEC) || isHeapTy(v, TY_MAPENTRY);
    }

    /// True when a park is in flight. A park is NOT an error: it unwinds the
    /// interpreter the same way, but `settle` reads `parkOn` rather than
    /// `thrown`, and every caller between here and the scheduler must pass it
    /// through untouched rather than treating it as a failure.
    public boolean parked() { return thrown == Val.PARK; }

    /// Find the innermost handler that can take `thrown`, or false if nothing
    /// can and the whole call must fail.
    ///
    /// The frames ABOVE the handler are dropped, not returned from: an
    /// exception is not a return, and the operands those frames had pushed are
    /// not values anybody wants. `stackTop` and `shadowTop` are restored to
    /// what they were when the handler was installed, which is what makes a
    /// throw out of arbitrarily deep code leave no residue.
    boolean unwind() {
        while (!handlers.isEmpty()) {
            Handler h = handlers.remove(handlers.size() - 1);
            // The frame that installed it may already be gone -- a handler
            // outlives its frame when the throw came from further out.
            if (h.frame >= frames.size()) continue;
            while (frames.size() > h.frame + 1) frames.remove(frames.size() - 1);
            roots.stackTop = h.stackTop;
            roots.shadowTop = h.shadow;
            long exc = thrown;
            thrown = Val.NIL;
            vpush(exc);
            frames.get(frames.size() - 1).ip = h.target;
            return true;
        }
        return false;
    }

    /// Call ANY callable with `args`: a closure, a builtin, a keyword, or a
    /// collection in function position. `call` handles only closures, and
    /// `apply` has to handle whatever it is given.
    public long invoke(long f, long[] args) {
        if (isHeapTy(f, TY_CLOSURE)) return call(f, args);
        int save = roots.stackTop;
        vreserve(args.length + 1);
        vpush(f);
        for (long a : args) vpush(a);
        long v = callValue(save, args.length);
        if (!parked()) roots.stackTop = save;
        return v;
    }

    /// Carry on a program a snapshot restored.
    ///
    /// A snapshot is faithful: if the program was mid-park when it was taken,
    /// `thrown` comes back holding the PARK sentinel. That is right for a
    /// sandbox parked on a PORT -- it really is still waiting -- and wrong for
    /// one paused by the SLICE, where the pause is over the moment somebody
    /// resumes. Clearing a courtesy yield here is exactly what `settle` does
    /// for a running scheduler; without it every later `call` saw a park
    /// already in flight, rewound, and answered nil.
    ///
    /// A real park is left alone: the caller wants a scheduler, not this.
    public long resume() {
        if (parked() && parkOn == Conc.PARK_YIELD) {
            thrown = Val.NIL;
            parkOn = Val.NIL;
        }
        if (schedInstalled) return Conc.drive(this);
        return run(0);
    }

    /// Run `closure` as the program's ENTRY, under the scheduler if one ever
    /// appears.
    ///
    /// The scheduler is not installed until something spawns, so this starts as
    /// a plain call. When the entry parks or its slice runs out, `thrown` is
    /// the PARK sentinel and the answer is not ready -- that is when the
    /// scheduler takes over, and from then on the loop in `Conc.drive` decides
    /// what runs. A program with no concurrency in it never reaches the second
    /// branch at all.
    public long runProgram(long closure, long[] args) {
        long v = call(closure, args);
        if (!parked() && !schedInstalled) return v;
        thrown = Val.NIL;
        if (!schedInstalled) return v;
        return Conc.scheduler(this, v);
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
        // NOT on a park. `run` returns early with the value stack holding the
        // parked thread's continuation, and truncating it here would throw that
        // away -- the thread would come back with a stack the scheduler had
        // already cut off underneath it. The first symptom was a builtin
        // reading argument -1.
        if (!parked()) roots.stackTop = save;
        return v;
    }
}
