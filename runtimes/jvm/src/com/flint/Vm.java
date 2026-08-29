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
    /// Throw a runtime error the way the runtime does: a structured value with
    /// a kind and a message, not a bare string. `ex-message` has to be able to
    /// read it, because the compiler catches errors and re-reports them.
    public static Thrown err(String message) {
        return new Thrown(new Ex("Error", message, null));
    }

    public static final class Thrown extends RuntimeException {
        public final transient Object value;
        public Thrown(Object value) {
            super(String.valueOf(value), null, false, false);
            this.value = value;
        }
    }

    public Vm(Img img) {
        this.img = img;
        // What the compiler was told, honoured here. `:optimize [perf]` cannot
        // be carried the same way on two of the three runtimes -- on wasm it
        // changes the artifact, here the arities are emitted at load time from
        // the same bytecode -- so the image carries the DECISION and this reads
        // it. Still settable afterwards, because a host may want it on for an
        // image that never asked.
        this.canCompile = !usesGreenThreads(img);
        this.aotEnabled = (img.flags & Img.FLAG_PERF) != 0 && canCompile;
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

    /// Reached from compiled code, which is a different class.
    public Object callNative(int idx, Object[] args) {
        Builtins.Fn f = natives[idx];
        if (f == null) {
            throw new Thrown("this runtime does not carry the builtin `"
                + img.nativeNames[idx] + "`");
        }
        return f.apply(this, args);
    }

    /// Compiled arities, by (function, arity). `NOT_COMPILED` marks one this
    /// emitter refused, so a function it cannot handle is only tried once.
    private final java.util.Map<Long, Aot.Compiled> compiled = new java.util.HashMap<>();
    private static final Aot.Compiled NOT_COMPILED = (vm, self, locals) -> null;
    /// Off unless asked for, exactly as on wasm: AOT is a preference, not a
    /// default (`doc/decisions/0021`). Set from the image's `FLAG_PERF` in the
    /// constructor, and settable by a host afterwards.
    public boolean aotEnabled;
    /// Whether compiling is possible AT ALL for this image, as opposed to
    /// asked for. A host can set `aotEnabled`; it cannot make a threaded
    /// program compilable, so this is checked separately and wins.
    private final boolean canCompile;

    /// Can this image be compiled at all? False for a program that uses green
    /// threads, which is a limit rather than a preference -- see
    /// `usesGreenThreads`.
    public boolean canCompile() { return canCompile; }
    public int compiledCount = 0;

    private synchronized Aot.Compiled compiledFor(int fnIndex, Img.Arity a) {
        if (!aotEnabled || !canCompile) return null;
        Img.FnDef def = img.fns[fnIndex];
        int ai = 0;
        for (int i = 0; i < def.arities.length; i++) if (def.arities[i] == a) ai = i;
        long key = ((long) fnIndex << 8) | ai;
        Aot.Compiled got = compiled.get(key);
        if (got != null) return got == NOT_COMPILED ? null : got;
        Aot.Compiled c = null;
        try { c = Aot.tryCompile(img, a); } catch (Throwable t) { c = null; }
        compiled.put(key, c == null ? NOT_COMPILED : c);
        if (c != null) compiledCount++;
        return c;
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

    /// A runaway-recursion guard, off unless FLINT_MAX_DEPTH is set.
    ///
    /// A StackOverflowError says only that the stack ran out. It does not say
    /// WHICH functions were calling each other, and the trace is 11 million
    /// identical `Vm.run`/`Vm.call` pairs with no flint name anywhere in it.
    /// This names the cycle instead, which is the thing worth knowing.
    private static final int MAX_DEPTH =
        Integer.getInteger("flint.maxDepth",
                           System.getenv("FLINT_MAX_DEPTH") == null ? 0
                           : Integer.parseInt(System.getenv("FLINT_MAX_DEPTH")));
    private int depth = 0;

    /// One flint call, as DATA rather than as a Java frame
    /// (`doc/decisions/0005`).
    ///
    /// This used to be the Java stack: a `CALL` opcode called `call`, which
    /// called `run`, which called `call`. That is why this port has no green
    /// threads and no ports -- a continuation living on the host stack cannot
    /// be parked, saved, or resumed, and everything `0005` and `0027` want
    /// rests on being able to do all three.
    ///
    /// Each frame owns its own operand stack, which the wasm runtime does not
    /// need to do: there, arguments are already on one shared value stack, and
    /// here a `CALL` gathers them into an array before entering. Keeping them
    /// separate makes the transformation local to `CALL`/`TAIL_CALL`/`RETURN`
    /// rather than a rewrite of every opcode's stack arithmetic.
    static final class Frame {
        Closure self;
        Img.Arity arity;
        Object[] locals;
        Object[] stack = new Object[64];
        int sp = 0;
        int ip;
        int end;
        int[] handlerIp = new int[8];
        int[] handlerSp = new int[8];
        int handlers = 0;
    }

    /// The frame stack IS the continuation, and there is one PER EXECUTING
    /// THREAD.
    ///
    /// A `ThreadLocal` rather than a field, because this port runs several real
    /// Java threads through one `Vm` (`runtimes/jvm/test/ThreadTest.java`) and
    /// the old interpreter was implicitly safe: every frame lived in a Java
    /// local, so each thread had its own by construction. Making the frames
    /// data took that away, and one shared list deadlocked ThreadTest
    /// immediately -- which is the good version of that mistake, since the
    /// alternative is two threads quietly interleaving one continuation.
    ///
    /// This is also the shape green threads want: a green thread IS one of
    /// these lists plus its saved state, and a scheduler swaps which one the
    /// executing thread is running.
    private final ThreadLocal<ArrayList<Frame>> FRAMES = ThreadLocal.withInitial(ArrayList::new);

    /// Does this image use green threads at all?
    ///
    /// If it does, NOTHING is compiled, and the reason is worth stating because
    /// the obvious narrower rule does not work. A compiled arity is one Java
    /// method, so its continuation is the Java stack; a thread that parks
    /// inside one can never resume. Refusing only the arities that CONTAIN a
    /// parking builtin is not enough -- an arity that merely CALLS one is just
    /// as unsafe, because the compiled caller's frame is a host frame and is
    /// not in the continuation either. The transitive closure is every arity
    /// that can reach a park, which in practice is most of them.
    ///
    /// `doc/decisions/0013` solves this properly on wasm by CHUNKING: a
    /// re-entry point before every native, so compiled code can be left and
    /// re-entered mid-body. That is the right answer here too and is not built.
    /// Until it is, a threaded program interprets -- which is correct, slower,
    /// and says so, rather than deadlocking.
    private static boolean usesGreenThreads(Img img) {
        for (String n : img.nativeNames) {
            if (n != null && (n.equals("flint/spawn") || n.equals("flint/channel")
                              || n.equals("flint/open"))) return true;
        }
        return false;
    }

    /// flint's own concurrency. One per `Vm`; real host threads are a separate
    /// matter (`doc/decisions/0028`) and each gets its own frame list.
    public final Green.Sched sched = new Green.Sched();

    /// Point the executing thread's frame list at a green thread's own.
    private void useFrames(ArrayList<Frame> fs) { FRAMES.set(fs); }

    /// Run `entry` as green thread 0, then keep running whatever is runnable
    /// until nothing is.
    ///
    /// This is the scheduler, and it is deliberately the same shape as the
    /// wasm runtime's: pick the next RUNNABLE thread round-robin, run it until
    /// it parks or finishes, repeat. Round-robin from `current + 1` rather
    /// than from 0 is what makes the interleaving fair and, more importantly,
    /// the SAME on every runtime -- `runtimes/conform/green.cljc` compares the
    /// actual order across all three.
    public Object runProgram(Object entry, Object[] args) {
        Green.Thread t0 = new Green.Thread(0, entry, null);
        t0.args = args;
        sched.threads.add(t0);
        sched.current = 0;
        drive();
        if (t0.status == Green.FAILED) throw new Thrown(t0.result);
        return t0.result;
    }

    /// One green thread's turn. Returns when it parks, finishes, or fails.
    private void step(Green.Thread t) {
        ArrayList<Frame> saved = FRAMES.get();
        useFrames(t.frames);
        try {
            Object v;
            if (t.status == Green.NEW) {
                t.status = Green.RUNNABLE;
                v = call(t.entry, t.args == null ? new Object[0] : t.args);
            } else {
                t.status = Green.RUNNABLE;
                // Resume: the frames ARE the continuation, and the top one's
                // `ip` was rewound to the instruction that parked, so it
                // re-executes with its operands still in place.
                v = run(0);
            }
            t.status = Green.DONE;
            t.result = v;
            wake(t);
        } catch (Green.Park p) {
            // The frames stay exactly as they are; that is the continuation.
            // A YIELD is runnable again at once -- it gave up its turn, it did
            // not ask to wait for anything.
            t.status = t.yielded ? Green.RUNNABLE : Green.PARKED;
            t.yielded = false;
        } catch (Thrown th) {
            t.status = Green.FAILED;
            t.result = th.value;
            t.frames.clear();
            wake(t);
        } finally {
            useFrames(saved);
        }
    }

    /// Anything waiting on `t` finishing can run again.
    private void wake(Green.Thread t) {
        for (Green.Thread o : sched.threads) {
            if (o.status == Green.PARKED && o.parkOn == t) {
                o.parkOn = null;
                o.status = Green.RUNNABLE;
            }
        }
    }

    /// Make every thread parked on `o` runnable again. Ports call this when a
    /// message lands or a slot frees.
    public void wakeOn(Object o) {
        for (Green.Thread t : sched.threads) {
            if (t.status == Green.PARKED && t.parkOn == o) {
                t.parkOn = null;
                t.status = Green.RUNNABLE;
            }
        }
    }

    /// A COURTESY yield: the call has already happened, so the instruction must
    /// NOT be re-executed on resume. Parked on nothing and immediately
    /// runnable again, so every other runnable thread gets a turn first.
    ///
    /// The distinction from `park` matters and the wasm runtime makes the same
    /// one: rewinding a `yield` would re-execute it, which would yield again,
    /// for ever.
    public RuntimeException yieldNow() {
        Green.Thread t = sched.cur();
        if (t == null) throw new Thrown("nothing to yield: no green thread is running");
        t.parkOn = null;
        t.yielded = true;
        throw new Green.Park();
    }

    /// Park the CURRENT green thread on `o`, and leave. Never returns.
    public RuntimeException park(Object o) {
        Green.Thread t = sched.cur();
        if (t == null) throw new Thrown("nothing to park: no green thread is running");
        t.parkOn = o;
        throw new Green.Park();
    }

    private void drive() {
        int guard = 0;
        for (;;) {
            Green.Thread next = null;
            int n = sched.threads.size();
            for (int i = 1; i <= n; i++) {
                Green.Thread c = sched.threads.get((sched.current + i) % n);
                if (c.status == Green.NEW || c.status == Green.RUNNABLE) { next = c; break; }
            }
            if (next == null) {
                // Nothing runnable. Either everything finished, or the threads
                // that remain are waiting for each other -- which is a deadlock
                // and is reported rather than hung on, exactly as the wasm
                // runtime reports it.
                for (Green.Thread c : sched.threads) {
                    if (c.status == Green.PARKED) {
                        throw new Thrown("deadlock: " + parkedCount()
                            + " green thread(s) are parked and nothing can wake them");
                    }
                }
                return;
            }
            sched.current = sched.threads.indexOf(next);
            step(next);
            if (++guard > 100_000_000) throw new Thrown("the scheduler made no progress");
        }
    }

    private int parkedCount() {
        int n = 0;
        for (Green.Thread c : sched.threads) if (c.status == Green.PARKED) n++;
        return n;
    }

    /// Build a frame for `c` at `a`, with `args` bound. Does not enter it.
    private Frame frameFor(Closure c, Img.Arity a, Object[] args, int argc) {
        Frame f = new Frame();
        f.self = c;
        f.arity = a;
        f.locals = new Object[Math.max(a.nlocals, a.argc + 1)];
        System.arraycopy(args, 0, f.locals, 0, Math.min(a.argc, argc));
        if (a.variadic) {
            List<Object> rest = new ArrayList<>();
            for (int i = a.argc; i < argc; i++) rest.add(args[i]);
            // A SEQ, not a vector: `clojure.core/list` is `[& xs] xs`, so a
            // vector here makes `(list 1 2)` print as `[1 2]`.
            f.locals[a.argc] = rest.isEmpty() ? null : Seq.of(rest);
        }
        f.ip = a.code;
        f.end = a.code + a.len;
        return f;
    }
    /// The last 128 calls in the order they were MADE, tail calls included.
    ///
    /// A call LOG, not a stack trace: a call that has already returned is still
    /// in it, so an ordinary `reduce` loop reads as a repeating cycle and looks
    /// exactly like runaway recursion. `depth` is the number that distinguishes
    /// them -- it is incremented and decremented, so it really is the stack.
    /// Read the two together or the log will tell you a story.
    private int ringN = 0;
    private final String[] ring = new String[128];
    void ringPut(String name) { ring[ringN++ & 127] = name; }

    /// The call log, oldest first, for a diagnostic. Empty unless
    /// FLINT_MAX_DEPTH is set -- recording costs a store per call.
    public String recentCalls() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ring.length; i++) {
            String n = ring[(ringN + i) & 127];
            if (n != null) b.append("\n    ").append(n);
        }
        return b.toString();
    }

    /// Call a function value with positional arguments.
    public Object call(Object fn, Object[] args) {
        if (fn instanceof Closure c) {
            Img.FnDef def = img.fns[c.fnIndex()];
            if (MAX_DEPTH > 0) {
                ringPut(def.name == null ? ("fn#" + c.fnIndex()) : def.name);
                if (++depth > MAX_DEPTH) {
                    StringBuilder b = new StringBuilder(
                        "stack depth past " + MAX_DEPTH + ". The last " + ring.length
                        + " calls MADE, oldest first -- a log, not a stack, so"
                        + " returned calls are in it too:");
                    for (int i = 0; i < 128; i++) b.append("\n    ").append(ring[(ringN + i) & 127]);
                    depth = 0;
                    // PRINTED, not just thrown: the compiler catches and
                    // rewraps, and its wrapper reads a message off a map, so a
                    // thrown string arrives empty at the top.
                    System.err.println(b);
                    throw new Thrown(b.toString());
                }
            }
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
                // A SEQ, not a vector. `clojure.core/list` is `[& xs] xs`, so
                // a vector here makes `(list 1 2)` print as `[1 2]` -- the
                // right elements in the wrong shape, again.
                locals[n] = rest.isEmpty() ? null : Seq.of(rest);
            }
            try {
                Aot.Compiled compiledArity = compiledFor(c.fnIndex(), a);
                if (compiledArity != null) return compiledArity.run(this, c, locals);
                // Entering from OUTSIDE the interpreter -- a builtin calling
                // back into flint, or the host calling an entry point. That
                // still costs one Java frame, exactly as it does in the wasm
                // runtime's `call_value`; what no longer costs one is a `CALL`
                // opcode, which is the case that recurses without bound.
                Frame f = new Frame();
                f.self = c;
                f.arity = a;
                f.locals = locals;
                f.ip = a.code;
                f.end = a.code + a.len;
                ArrayList<Frame> fs = FRAMES.get();
                int baseDepth = fs.size();
                fs.add(f);
                return run(baseDepth);
            } finally { if (MAX_DEPTH > 0) depth--; }
        }
        if (fn instanceof Builtins.Fn f) return f.apply(this, args);
        if (fn instanceof Img.NativeRef nr) {
            Builtins.Fn f = Builtins.byName(nr.name());
            if (f == null) throw new Thrown("this runtime does not carry `" + nr.name() + "`");
            return f.apply(this, args);
        }
        // A keyword, map, set or vector in call position looks itself up.
        // Clojure's rule, and one the reader itself leans on -- `(#{\\space
        // \\tab} c)` is how whitespace is tested, and without this the
        // compiler cannot read its own source.
        if (args.length >= 1) {
            Object dflt = args.length > 1 ? args[1] : null;
            if (fn instanceof Kw k) return Builtins.get(args[0], k, dflt);
            if (fn instanceof java.util.Set<?> || fn instanceof Map<?, ?>
                || fn instanceof List<?>) {
                return Builtins.get(fn, args[0], dflt);
            }
        }
        throw new Thrown(Builtins.prStr(fn) + " is not a function");
    }

    private int u8(int ip) { return img.code[ip] & 0xFF; }
    private int u16(int ip) { return u8(ip) | (u8(ip + 1) << 8); }
    private int i16(int ip) { return (short) u16(ip); }

    /// Truthiness: only `nil` and `false` are false. Everything else -- zero,
    /// the empty string, the empty vector -- is true.
    static boolean truthy(Object v) { return v != null && v != Boolean.FALSE; }

    /// Run until the frame stack is back down to `baseDepth`.
    ///
    /// The frame's fields are held in LOCALS for the length of one frame and
    /// written back only at a boundary -- the same trade the wasm interpreter
    /// makes with `ip`. That is what keeps this a flattening of `CALL` rather
    /// than a rewrite of every opcode's stack arithmetic.
    private Object run(int baseDepth) {
        final ArrayList<Frame> frames = FRAMES.get();
        Frame f = frames.get(frames.size() - 1);
        Closure self = f.self;
        Img.Arity arity = f.arity;
        Object[] locals = f.locals;
        Object[] stack = f.stack;
        int sp = f.sp;
        int ip = f.ip;
        int end = f.end;
        // Handler stack for TRY/POP_HANDLER: each entry is the ip to jump to
        // and the stack depth to restore, because a throw unwinds the value
        // stack as well as the instruction pointer.
        int[] handlerIp = f.handlerIp;
        int[] handlerSp = f.handlerSp;
        int handlers = f.handlers;

        for (;;) {
            if (ip >= end) {
                Object v = sp > 0 ? stack[sp - 1] : null;
                frames.remove(frames.size() - 1);
                if (frames.size() <= baseDepth) return v;
                        f = frames.get(frames.size() - 1);
                        self = f.self; arity = f.arity; locals = f.locals;
                        stack = f.stack; sp = f.sp; ip = f.ip; end = f.end;
                        handlerIp = f.handlerIp; handlerSp = f.handlerSp;
                        handlers = f.handlers;
                stack[sp++] = v;
                continue;
            }
            if (sp + 4 >= stack.length) {
                Object[] bigger = new Object[stack.length * 2];
                System.arraycopy(stack, 0, bigger, 0, sp);
                stack = bigger;
                f.stack = stack;
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
                        int callAt = ip - 1;
                        int argc = u8(ip); ip += 1;
                        Object[] args = new Object[argc];
                        System.arraycopy(stack, sp - argc, args, 0, argc);
                        sp -= argc + 1;
                        Object callee = stack[sp];
                        // A flint closure gets a FRAME, not a Java call. This
                        // is the whole point of the flattening: Clojure
                        // recursion stops living on the host stack, so a green
                        // thread can park at any depth (`doc/decisions/0005`).
                        if (callee instanceof Closure c) {
                            Img.Arity a = img.fns[c.fnIndex()].select(args.length);
                            if (a != null && compiledFor(c.fnIndex(), a) == null) {
                        f.stack = stack; f.sp = sp; f.ip = ip; f.end = end;
                        f.locals = locals; f.self = self; f.arity = arity;
                        f.handlerIp = handlerIp; f.handlerSp = handlerSp;
                        f.handlers = handlers;
                                frames.add(frameFor(c, a, args, argc));
                        f = frames.get(frames.size() - 1);
                        self = f.self; arity = f.arity; locals = f.locals;
                        stack = f.stack; sp = f.sp; ip = f.ip; end = f.end;
                        handlerIp = f.handlerIp; handlerSp = f.handlerSp;
                        handlers = f.handlers;
                                continue;
                            }
                        }
                        // Everything else -- a builtin, a keyword, a map, a
                        // compiled arity -- completes in place.
                        try {
                            // A local first, for the reason NATIVE gives.
                            Object cv = call(callee, args);
                            stack[sp++] = cv;
                        } catch (Green.Park p) {
                            // Same rule as NATIVE: a park rewinds and a
                            // courtesy yield does not.
                            Green.Thread gt = sched.cur();
                            if (gt != null && gt.yielded) {
                                stack[sp++] = null;
                            } else {
                                sp += argc + 1;
                                ip = callAt;
                            }
                            f.stack = stack; f.sp = sp; f.ip = ip; f.end = end;
                            f.locals = locals; f.self = self; f.arity = arity;
                            f.handlerIp = handlerIp; f.handlerSp = handlerSp;
                            f.handlers = handlers;
                            throw p;
                        }
                        break;
                    }
                    case TAIL_CALL: {
                        // A real tail call: no JVM frame is added, so a loop
                        // written as recursion runs in constant stack -- which
                        // the JVM would not give us for free.
                        //
                        // For ANY closure, not just this one. Restricting it to
                        // self-recursion is what stopped the flint compiler
                        // running here: three of its functions tail-call each
                        // other in a cycle, which is constant stack in flint's
                        // own VM and grew a JVM frame per hop. It ran for
                        // minutes and then overflowed a 2 GB stack, which is
                        // what "unbounded rather than deep" looks like.
                        //
                        // Safe because a tail call is only EMITTED outside a
                        // try (`emitter.cljc`, `:in-try?`), so there are never
                        // handlers to discard here.
                        int argc = u8(ip); ip += 1;
                        Object[] args = new Object[argc];
                        System.arraycopy(stack, sp - argc, args, 0, argc);
                        sp -= argc + 1;
                        Object callee = stack[sp];
                        if (callee instanceof Closure c) {
                            Img.Arity a = img.fns[c.fnIndex()].select(argc);
                            // A COMPILED target has to be entered through its
                            // compiled body, so it takes a frame. It cannot
                            // tail-call back out -- the emitter refuses an
                            // arity containing TAIL_CALL -- so that frame is
                            // bounded.
                            if (a != null && compiledFor(c.fnIndex(), a) == null) {
                                if (MAX_DEPTH > 0) {
                                    Img.FnDef d = img.fns[c.fnIndex()];
                                    ringPut((d.name == null ? "fn#" + c.fnIndex() : d.name) + " (tail)");
                                }
                                self = c;
                                arity = a;
                                locals = new Object[Math.max(a.nlocals, a.argc + 1)];
                                System.arraycopy(args, 0, locals, 0, Math.min(a.argc, argc));
                                if (a.variadic) {
                                    List<Object> rest = new ArrayList<>();
                                    for (int i = a.argc; i < argc; i++) rest.add(args[i]);
                                    locals[a.argc] = rest.isEmpty() ? null : Seq.of(rest);
                                }
                                ip = a.code;
                                end = a.code + a.len;
                                sp = 0;
                                handlers = 0;
                                continue;
                            }
                        }
                        // Not a closure this port can enter: a builtin, or a
                        // compiled arity. It completes in place and this frame
                        // returns its answer.
                        Object v = call(callee, args);
                        frames.remove(frames.size() - 1);
                        if (frames.size() <= baseDepth) return v;
                        f = frames.get(frames.size() - 1);
                        self = f.self; arity = f.arity; locals = f.locals;
                        stack = f.stack; sp = f.sp; ip = f.ip; end = f.end;
                        handlerIp = f.handlerIp; handlerSp = f.handlerSp;
                        handlers = f.handlers;
                        stack[sp++] = v;
                        continue;
                    }
                    case RETURN: {
                        Object v = sp > 0 ? stack[--sp] : null;
                        frames.remove(frames.size() - 1);
                        if (frames.size() <= baseDepth) return v;
                        f = frames.get(frames.size() - 1);
                        self = f.self; arity = f.arity; locals = f.locals;
                        stack = f.stack; sp = f.sp; ip = f.ip; end = f.end;
                        handlerIp = f.handlerIp; handlerSp = f.handlerSp;
                        handlers = f.handlers;
                        stack[sp++] = v;
                        continue;
                    }
                    case CLOSURE: {
                        int fnIdx = u16(ip); ip += 2;
                        int nup = u8(ip); ip += 1;
                        Object[] up = new Object[nup];
                        for (int i = nup - 1; i >= 0; i--) up[i] = stack[--sp];
                        stack[sp++] = new Closure(fnIdx, up);
                        break;
                    }
                    case NATIVE: {
                        int opcodeAt = ip - 1;
                        int idx = u16(ip); ip += 2;
                        int argc = u8(ip); ip += 1;
                        Object[] args = new Object[argc];
                        System.arraycopy(stack, sp - argc, args, 0, argc);
                        sp -= argc;
                        Builtins.Fn nf = natives[idx];
                        if (nf == null) {
                            throw new Thrown("this runtime does not carry the builtin `"
                                + img.nativeNames[idx] + "`");
                        }
                        try {
                            // The result is computed into a LOCAL first.
                            // `stack[sp++] = nf.apply(...)` evaluates the array
                            // index BEFORE the call (JLS 15.26.1), so `sp` has
                            // already moved when a park unwinds through it --
                            // and the rewind below then put the operands back
                            // one slot out.
                            Object nv = nf.apply(this, args);
                            stack[sp++] = nv;
                        } catch (Green.Park p) {
                            // A real PARK rewinds, so resuming re-executes this
                            // instruction with its operands still on the stack.
                            // That is why a parking builtin must decide to park
                            // BEFORE it changes anything: it will run twice.
                            //
                            // A courtesy YIELD must not. Its call already
                            // happened, so re-executing it would yield again,
                            // for ever -- which is exactly what "the scheduler
                            // made no progress" was. The wasm runtime draws the
                            // same distinction and for the same reason.
                            Green.Thread gt = sched.cur();
                            boolean courtesy = gt != null && gt.yielded;
                            if (courtesy) {
                                stack[sp++] = null;
                            } else {
                                sp += argc;
                                ip = opcodeAt;
                            }
                            f.stack = stack; f.sp = sp; f.ip = ip; f.end = end;
                            f.locals = locals; f.self = self; f.arity = arity;
                            f.handlerIp = handlerIp; f.handlerSp = handlerSp;
                            f.handlers = handlers;
                            throw p;
                        }
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
                        Object af = stack[--sp];
                        List<Object> all = new ArrayList<>(List.of());
                        for (Object o : fixed) all.add(o);
                        for (Object o : Builtins.iterate(seq)) all.add(o);
                        stack[sp++] = call(af, all.toArray());
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
                // Unwind FRAMES, not the Java stack.
                //
                // While a flint call was a Java call, `throw` did this for us:
                // the JVM popped host frames until one had a `catch`. Now the
                // frames are ours, so finding the handler is ours too -- pop
                // until a frame has one, and hand the Java exception onward
                // only when the whole region this `run` owns has none.
                //
                // Getting this wrong would not fail loudly: a `try` in a CALLER
                // would simply stop catching what a callee threw.
                while (handlers == 0) {
                    frames.remove(frames.size() - 1);
                    if (frames.size() <= baseDepth) throw t;
                    f = frames.get(frames.size() - 1);
                    self = f.self; arity = f.arity; locals = f.locals;
                    stack = f.stack; sp = f.sp; ip = f.ip; end = f.end;
                    handlerIp = f.handlerIp; handlerSp = f.handlerSp;
                    handlers = f.handlers;
                }
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
