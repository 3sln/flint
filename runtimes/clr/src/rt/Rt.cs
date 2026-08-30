using System.Collections.Generic;

namespace Flint.Rt;

using static Flint.Rt.Obj;

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
public sealed class Rt : System.IDisposable {
    public readonly Gc gc;
    public readonly Roots roots = new();

    /// The image: bytecode, constants and the function table.
    public byte[] code = System.Array.Empty<byte>();
    public long[] consts = System.Array.Empty<long>();
    public FnDef[] fns = System.Array.Empty<FnDef>();

    public readonly List<Frame> frames = new();
    public readonly List<Handler> handlers = new();

    /// The in-flight exception, or NIL. A distinguished VALUE rather than a
    /// host exception, because a throw has to unwind flint frames and a host
    /// exception would unwind the host stack instead.
    public long thrown = Val.Nil;

    /// The singleton slots, from `rt.rs`. Numbered rather than named fields so
    /// a snapshot can write them as one array.
    public const int SingEmptyList = 0, SingEmptyVec = 1, SingEmptyMap = 2,
        SingEmptySet = 3, SingSched = 4, SingBindings = 5, SingCount = 6;

    public long steps;

    /// True once a scheduler exists. `Run` compares `steps` against
    /// `checkpoint` once per instruction; 0 there means nothing is counting, so
    /// a program that never spawns runs a loop with no counter in it at all.
    public bool schedInstalled;

    /// True once this sandbox has more than one executor. Read once per
    /// instruction, so it is a plain field rather than a call.
    public bool safepoints;

    /// True while this executor is running GUEST code and can be stopped.
    public bool running;

    public void SetSliceEnd(long at) {
        sliceEnd = at;
        checkpoint = at;
    }

    /// The builtins this image imports, resolved BY NAME. The slots in an image
    /// belong to the module it was linked against and mean nothing here, which
    /// is what makes an image portable between hosts at all.
    public Builtins.Fn[] natives = System.Array.Empty<Builtins.Fn>();
    public string[] nativeNames = System.Array.Empty<string>();

    /// The rest of the interpreter's state, all of it snapshot-visible.
    ///
    /// Here rather than spread across the classes that use them, for the reason
    /// `doc/decisions/0015` gives: a snapshot is a COPY of the VM, so anything
    /// that survives a park has to be findable in one place. The Rust and the
    /// JVM keep exactly this set in exactly this order, so all three write
    /// byte-identical snapshots.
    public long parkOn = Val.Nil;
    public long gasLimit;
    public long sliceEnd;
    public long checkpoint;
    public int gasTrips;
    public int memTrips;
    public int status;
    public bool champAdded;

    /// FNV-1a over the image bytes. A snapshot carries the heap and the VM
    /// state and NO CODE, but every `ip`, constant index and var slot in it is
    /// an index INTO an image -- so restoring against a different program does
    /// not fail, it quietly means something else. This is what makes that
    /// refusable.
    public long fingerprint;

    /// How many host-minted opaque values the last import brought back. A test
    /// reads it to know the sweep saw anything at all: a zero would otherwise
    /// pass every assertion for the wrong reason.
    public int restoredCapabilities;

    public Rt(long nurseryBytes, long maxHeap) {
        this.gc = new Gc(nurseryBytes, maxHeap);
        this.roots.owner = this;
        roots.shared.Consts = consts;
        // The first executor registers too, so `Executors()` counts everybody
        // and `StageStop` has a truthful `live`.
        roots.shared.par.Register();
        roots.shared.all.Add(roots);
    }

    public sealed class FnDef {
        public readonly Arity[] arities;
        public readonly int nupvals;
        /// Index into `Consts` of this function's name, or -1. KEPT rather than
        /// read-and-discarded: a port refuses to send a function BY NAME, and
        /// "cannot send that" with no subject sends somebody hunting through a
        /// nested structure to find which one.
        public readonly int Name;
        public FnDef(Arity[] arities, int nupvals) : this(arities, nupvals, -1) { }
        public FnDef(Arity[] arities, int nupvals, int name) {
            this.arities = arities; this.nupvals = nupvals; this.Name = name;
        }
        /// An EXACT fixed arity wins over a variadic one, whatever order they
        /// were written in -- so `(fn ([] :a) ([& xs] xs))` and the same two
        /// clauses reversed both answer `:a` for zero arguments. Taking
        /// whichever came first made the answer depend on source order.
        ///
        /// Among variadics, the one with the MOST fixed parameters wins:
        /// `([a & xs])` beats `([& xs])` for two arguments.
        public Arity Select(int argc) {
            Arity best = null;
            foreach (Arity a in arities) {
                if (!a.variadic && argc == a.argc) return a;
                if (a.variadic && argc >= a.argc && (best == null || a.argc > best.argc)) best = a;
            }
            return best;
        }
    }

    public sealed class Arity {
        public readonly int argc, nlocals, code, len;
        public readonly bool variadic;
        public Arity(int argc, bool variadic, int nlocals, int code, int len) {
            this.argc = argc; this.variadic = variadic;
            this.nlocals = nlocals; this.code = code; this.len = len;
        }
    }

    public sealed class Handler {
        public int frame, stackTop, target, shadow;
    }

    // --- allocation, with the rooting discipline ---------------------------

    /// Allocate. With one executor this is `gc.Alloc` and nothing else; with
    /// several it is the whole safepoint protocol (`doc/decisions/0028`).
    ///
    /// The allocation lock keeps two threads out of the collector's
    /// bookkeeping. The safepoint is staged ONLY when this allocation would
    /// actually collect, because staging one every time would be a
    /// stop-the-world per allocation rather than per collection.
    public long Alloc(int ty, int len) {
        Parallel par = roots.shared.par;
        if (par.Executors() <= 1) return gc.Alloc(roots, ty, len);

        par.LockAlloc();
        bool staged = gc.WouldCollect(ty, len);
        if (staged) {
            // `running` is not a detail. The target is "every running executor
            // EXCEPT ME", and an allocation can happen outside guest code --
            // loading an image, running initialisers, a host call -- where this
            // thread is not one of them.
            par.StageStop(running);
            // Every other executor is stopped NOW. Built here and dropped
            // after, because a list that outlived the stop would be pointers
            // into threads that have started running again.
            roots.shared.others.Clear();
            lock (roots.shared) {
                foreach (Roots r in roots.shared.all)
                    if (!ReferenceEquals(r, roots)) roots.shared.others.Add(r);
            }
        }
        long a;
        try {
            a = gc.Alloc(roots, ty, len);
        } finally {
            if (staged) {
                roots.shared.others.Clear();
                par.ReleaseStop();
            }
            par.UnlockAlloc();
        }
        return a;
    }

    /// Another executor on THIS sandbox's heap (`doc/decisions/0028`).
    ///
    /// One heap, one set of shared roots, one safepoint protocol -- and its own
    /// value stack, shadow stack and remembered set, because those are per
    /// THREAD.
    public Rt Executor() {
        var e = new Rt(gc, roots.shared);
        lock (roots.shared) {
            roots.shared.all.Add(e.roots);
            bool many = roots.shared.par.Executors() > 1;
            foreach (Roots r in roots.shared.all) r.owner.safepoints = many;
        }
        return e;
    }

    /// Bracket the stretch where this executor runs GUEST code.
    ///
    /// Between these two calls the thread polls and can be stopped. Outside
    /// them it is registered -- its roots are still scanned -- but the collector
    /// does not wait for it.
    public void EnterGuest() {
        if (running) return;
        roots.shared.par.Enter();
        running = true;
    }

    public void LeaveGuest() {
        if (!running) return;
        running = false;
        roots.shared.par.Leave();
    }

    /// Give this executor's slot back. Its roots stop being scanned.
    public void Close() {
        LeaveGuest();
        lock (roots.shared) {
            roots.shared.all.Remove(roots);
            roots.shared.par.Deregister();
            bool many = roots.shared.par.Executors() > 1;
            foreach (Roots r in roots.shared.all) r.owner.safepoints = many;
        }
    }

    Rt(Gc gc, Shared shared) {
        this.gc = gc;
        this.roots.shared = shared;
        this.roots.owner = this;
        this.consts = shared.Consts;
        shared.par.Register();
    }

    public void SetSlot(long obj, int i, long v) { gc.SetSlot(obj, i, v, roots); }
    public long Slot(long v, int i) { return Obj.Slot(gc.sp, Val.AsHeap(v), i); }

    public int Mark() { return roots.Mark(); }
    public int Push(long v) { return roots.Push(v); }
    public long R(int i) { return roots.R(i); }
    public void SetR(int i, long v) { roots.SetR(i, v); }
    public void PopTo(int n) { roots.PopTo(n); }

    public void VPush(long v) { roots.VPush(v); }
    public long VPop() { return roots.VPop(); }
    public long VAt(int i) { return roots.Stack[i]; }

    /// Reserve stack so a frame's pushes are unchecked. Called on the way in,
    /// which is why a body makes no bounds check per push.
    void VReserve(int n) {
        while (roots.StackTop + n >= roots.Stack.Length) {
            long[] bigger = new long[roots.Stack.Length * 2];
            System.Array.Copy(roots.Stack, bigger, roots.StackTop);
            roots.Stack = bigger;
        }
    }

    public long MakeClosure(int fnIdx, long[] upvals) {
        int mk = Mark();
        foreach (long u in upvals) Push(u);
        long a = Alloc(TyClosure, 1 + upvals.Length);
        if (a == 0) { PopTo(mk); return Val.Nil; }
        SetSlot(a, 0, Val.Fixnum(fnIdx));
        for (int i = 0; i < upvals.Length; i++) SetSlot(a, 1 + i, R(mk + i));
        PopTo(mk);
        return Val.Heap(a);
    }

    int U8(int ip) { return code[ip] & 0xFF; }
    int U16(int ip) { return U8(ip) | (U8(ip + 1) << 8); }
    int I16(int ip) { return (short) U16(ip); }

    // --- the dispatch loop -------------------------------------------------

    /// Push a frame for `closure`. The callee sits at `calleeAt`, args follow.
    public bool Enter(long closure, int calleeAt, int argc) {
        int fnIdx = (int) Val.AsFixnum(Slot(closure, 0));
        FnDef def = fns[fnIdx];
        Arity a = def.Select(argc);
        if (a == null) {
            roots.StackTop = calleeAt;
            thrown = Val.Fixnum(-1);   // an arity error; the real message needs strings
            return false;
        }
        int fp = calleeAt + 1;
        if (a.variadic) {
            // Fold the surplus into a list in the last fixed slot. A SEQ, not a
            // vector: `clojure.core/list` is `[& xs] xs`, so a vector here makes
            // `(list 1 2)` print as `[1 2]`.
            int extra = argc - a.argc;
            int mk = Mark();
            for (int i = 0; i < extra; i++) Push(roots.Stack[fp + a.argc + i]);
            long restv = extra == 0 ? Val.Nil : Seqs.FromRoots(this, mk, extra);
            PopTo(mk);
            VReserve(a.nlocals + 8);
            roots.Stack[fp + a.argc] = restv;
            for (int i = a.argc + 1; i < a.nlocals; i++) roots.Stack[fp + i] = Val.Nil;
            roots.StackTop = fp + a.nlocals;
            Frame vf = new Frame();
            vf.Fp = fp; vf.Ip = a.code; vf.End = a.code + a.len;
            vf.RetTo = calleeAt; vf.Handlers = handlers.Count;
            frames.Add(vf);
            return true;
        }
        VReserve(a.nlocals + 8);
        for (int i = argc; i < a.nlocals; i++) roots.Stack[fp + i] = Val.Nil;
        roots.StackTop = fp + a.nlocals;
        Frame f = new Frame();
        f.Fp = fp;
        f.Ip = a.code;
        f.End = a.code + a.len;
        f.RetTo = calleeAt;
        f.Handlers = handlers.Count;
        frames.Add(f);
        return true;
    }

    /// Run until the frame stack is back down to `baseDepth`.
    public long Run(int baseDepth) {
        for (;;) {
            if (frames.Count <= baseDepth) return VPop();
            Frame f = frames[frames.Count - 1];
            int ip = f.Ip;
            int fp = f.Fp;

            if (ip >= f.End) {   // fell off the end: an implicit return
                long v = roots.StackTop > fp ? VPop() : Val.Nil;
                while (handlers.Count > f.Handlers) handlers.RemoveAt(handlers.Count - 1);
                frames.RemoveAt(frames.Count - 1);
                roots.StackTop = f.RetTo;
                VPush(v);
                if (frames.Count <= baseDepth) return VPop();
                continue;
            }

            // The slice check, BEFORE the opcode is read.
            //
            // The saved `Ip` must point AT the next instruction, not past its
            // first byte. Checking after `ip += 1` resumed on the OPERANDS, and
            // the first symptom was a builtin reading an argument that had
            // never been pushed.
            //
            // One comparison against a precomputed value, and only when
            // something is counting.
            // THE SAFEPOINT (`doc/decisions/0028`), and the slice check, at the
            // same place -- because they want the same place. `Ip` has been
            // written back and every live value is on the value stack by
            // construction, which is what makes it safe to stop here and
            // nowhere else.
            //
            // `safepoints` is false in a sandbox with one executor and
            // `checkpoint` is 0 when nothing is counting, so a single-threaded
            // program with no scheduler runs a loop with neither test in it.
            if (safepoints && roots.shared.par.StopRequested()) {
                f.Ip = ip;
                roots.shared.par.Park();
            }
            if (checkpoint != 0 && steps >= checkpoint) {
                f.Ip = ip;
                checkpoint = 0;
                // A COURTESY yield, not a park: the thread stays runnable and
                // must NOT rewind. Preemption is what keeps a thread with no
                // `yield` in it from starving the others.
                parkOn = Conc.PARK_YIELD;
                thrown = Val.Park;
                return Val.Nil;
            }

            int opcode = U8(ip);
            ip += 1;
            steps++;

            switch (opcode) {
                case Op.Nop: {} break;
                case Op.Const: { VPush(consts[U16(ip)]); ip += 2; } break;
                case Op.Nil: VPush(Val.Nil); break;
                case Op.True: VPush(Val.True); break;
                case Op.False: VPush(Val.False); break;
                case Op.Int: { VPush(Val.Fixnum(I16(ip))); ip += 2; } break;
                case Op.Local: { VPush(roots.Stack[fp + U8(ip)]); ip += 1; } break;
                case Op.LocalW: { VPush(roots.Stack[fp + U16(ip)]); ip += 2; } break;
                case Op.SetLocal: { roots.Stack[fp + U8(ip)] = VPop(); ip += 1; } break;
                case Op.SetLocalKeep: { roots.Stack[fp + U8(ip)] = roots.Stack[roots.StackTop - 1]; ip += 1; } break;
                case Op.Self: VPush(roots.Stack[f.RetTo]); break;
                case Op.Var: { VPush(roots.shared.Globals[U16(ip)]); ip += 2; } break;
                case Op.SetVar: { roots.shared.Globals[U16(ip)] = VPop(); ip += 2; } break;
                case Op.Upval: { VPush(Slot(roots.Stack[f.RetTo], 1 + U8(ip))); ip += 1; } break;
                case Op.Pop: roots.StackTop -= 1; break;
                case Op.PopN: { roots.StackTop -= U8(ip); ip += 1; } break;
                case Op.Dup: VPush(roots.Stack[roots.StackTop - 1]); break;
                case Op.Jump: ip += 2 + I16(ip); break;
                case Op.JumpIfFalse: { int off = I16(ip); ip += 2; if (!Val.Truthy(VPop())) ip += off; } break;
                case Op.JumpIfTrue: { int off = I16(ip); ip += 2; if (Val.Truthy(VPop())) ip += off; } break;
                case Op.JumpIfFalseKeep: {
                    int off = I16(ip); ip += 2;
                    if (!Val.Truthy(roots.Stack[roots.StackTop - 1])) ip += off; else roots.StackTop -= 1;
                    break;
                }
                case Op.JumpIfTrueKeep: {
                    int off = I16(ip); ip += 2;
                    if (Val.Truthy(roots.Stack[roots.StackTop - 1])) ip += off; else roots.StackTop -= 1;
                    break;
                }
                case Op.AddInt: { long b = Val.AsFixnum(VPop()), x = Val.AsFixnum(VPop()); VPush(Val.Fixnum(AddExact(x, b))); } break;
                case Op.SubInt: { long b = Val.AsFixnum(VPop()), x = Val.AsFixnum(VPop()); VPush(Val.Fixnum(SubExact(x, b))); } break;
                case Op.MulInt: { long b = Val.AsFixnum(VPop()), x = Val.AsFixnum(VPop()); VPush(Val.Fixnum(MulExact(x, b))); } break;
                case Op.LtInt: { long b = Val.AsFixnum(VPop()), x = Val.AsFixnum(VPop()); VPush(Val.Bool(x < b)); } break;
                case Op.LeInt: { long b = Val.AsFixnum(VPop()), x = Val.AsFixnum(VPop()); VPush(Val.Bool(x <= b)); } break;
                case Op.GtInt: { long b = Val.AsFixnum(VPop()), x = Val.AsFixnum(VPop()); VPush(Val.Bool(x > b)); } break;
                case Op.GeInt: { long b = Val.AsFixnum(VPop()), x = Val.AsFixnum(VPop()); VPush(Val.Bool(x >= b)); } break;
                case Op.EqInt: { long b = Val.AsFixnum(VPop()), x = Val.AsFixnum(VPop()); VPush(Val.Bool(x == b)); } break;
                case Op.Closure: {
                    int fnIdx = U16(ip); int nup = U8(ip + 2); ip += 3;
                    long[] up = new long[nup];
                    for (int i = nup - 1; i >= 0; i--) up[i] = VPop();
                    VPush(MakeClosure(fnIdx, up));
                    break;
                }
                case Op.Call: {
                    int argc = U8(ip); ip += 1;
                    f.Ip = ip;                       // committed BEFORE entering
                    int calleeAt = roots.StackTop - argc - 1;
                    long callee = roots.Stack[calleeAt];
                    if (Val.IsHeap(callee) && Ty(gc.sp, Val.AsHeap(callee)) == TyClosure) {
                        if (!Enter(callee, calleeAt, argc)) return Val.Nil;
                        continue;
                    }
                    // Everything else completes IN PLACE: a builtin held in a
                    // var, a keyword used as a function, a collection looked up.
                    long cv = CallValue(calleeAt, argc);
                    if (Parked()) {
                        // Same rewind as Native: the callee and its arguments
                        // are still on the stack, so re-executing finds them.
                        thrown = Val.Nil;
                        if (parkOn == Conc.PARK_YIELD) {
                            roots.StackTop = calleeAt;
                            VPush(Val.Nil);
                        } else {
                            f.Ip = ip - 2;   // back onto the CALL and its argc
                        }
                        thrown = Val.Park;
                        return Val.Nil;
                    }
                    roots.StackTop = calleeAt;
                    VPush(cv);
                } break;
                case Op.TailCall: {
                    int opAt = ip - 1;
                    int argc = U8(ip); ip += 1;
                    f.Ip = ip;
                    int calleeAt = roots.StackTop - argc - 1;
                    long callee = roots.Stack[calleeAt];
                    if (IsHeapTy(callee, TyClosure)) {
                        // Drop this frame FIRST: that is what makes a tail call
                        // constant-space, and it is why mutual recursion between
                        // three functions runs for ever here and overflowed on
                        // the old port.
                        frames.RemoveAt(frames.Count - 1);
                        while (handlers.Count > f.Handlers) handlers.RemoveAt(handlers.Count - 1);
                        int dest = f.RetTo;
                        for (int i = 0; i <= argc; i++) roots.Stack[dest + i] = roots.Stack[calleeAt + i];
                        roots.StackTop = dest + argc + 1;
                        if (!Enter(callee, dest, argc)) return Val.Nil;
                        continue;
                    }
                    // A tail call to something that is NOT a closure: a builtin
                    // held in a var, a keyword, a collection. It completes in
                    // place and then this frame returns its value.
                    //
                    // Entering it as a closure instead read `Slot(callee, 0)`
                    // off whatever the value happened to be -- for a fixnum, an
                    // address 2 TB into a 2 GB heap. The compiler is the first
                    // program big enough to tail-call a builtin.
                    long cv = CallValue(calleeAt, argc);
                    if (Parked()) {
                        thrown = Val.Nil;
                        if (parkOn == Conc.PARK_YIELD) {
                            roots.StackTop = calleeAt;
                            VPush(cv);
                        } else {
                            f.Ip = opAt;
                        }
                        thrown = Val.Park;
                        return Val.Nil;
                    }
                    roots.StackTop = calleeAt;
                    VPush(cv);
                    long tv = VPop();
                    frames.RemoveAt(frames.Count - 1);
                    while (handlers.Count > f.Handlers) handlers.RemoveAt(handlers.Count - 1);
                    roots.StackTop = f.RetTo;
                    VPush(tv);
                    if (frames.Count <= baseDepth) return VPop();
                    continue;
                }
                case Op.Return: {
                    long v = VPop();
                    frames.RemoveAt(frames.Count - 1);
                    // Handlers installed by this frame go with it. A `try` that
                    // returns from inside its body would otherwise leave its
                    // handler on the stack, and the next throw anywhere would
                    // unwind to a frame that had already returned.
                    while (handlers.Count > f.Handlers) handlers.RemoveAt(handlers.Count - 1);
                    roots.StackTop = f.RetTo;
                    VPush(v);
                    if (frames.Count <= baseDepth) return VPop();
                    continue;
                }
                case Op.Vector: {
                    int nv = U16(ip); ip += 2;
                    // The elements are already on the VALUE stack; they are
                    // moved to the shadow stack so they stay rooted across the
                    // allocations `Conj` makes.
                    int bas = Mark();
                    for (int i = 0; i < nv; i++) Push(roots.Stack[roots.StackTop - nv + i]);
                    roots.StackTop -= nv;
                    long v = Vec.FromRoots(this, bas, nv);
                    PopTo(bas);
                    VPush(v);
                } break;
                case Op.Map: {
                    int nv = U16(ip); ip += 2;
                    // The pairs are on the VALUE stack; the map under
                    // construction goes on the SHADOW stack, because `Assoc`
                    // allocates and the accumulator has to survive it.
                    int bas = Mark();
                    int mi = Push(Maps.Empty(this));
                    int at2 = roots.StackTop - 2 * nv;
                    for (int i = 0; i < nv; i++) {
                        long nm = Maps.Assoc(this, R(mi), roots.Stack[at2 + 2 * i],
                                             roots.Stack[at2 + 2 * i + 1]);
                        SetR(mi, nm);
                    }
                    long m = R(mi);
                    PopTo(bas);
                    roots.StackTop = at2;
                    VPush(m);
                } break;
                case Op.Set: {
                    int nv = U16(ip); ip += 2;
                    int bas = Mark();
                    int si = Push(Sets.Empty(this));
                    int at3 = roots.StackTop - nv;
                    for (int i = 0; i < nv; i++) SetR(si, Sets.Conj(this, R(si), roots.Stack[at3 + i]));
                    long sv = R(si);
                    PopTo(bas);
                    roots.StackTop = at3;
                    VPush(sv);
                } break;
                case Op.List: {
                    int nv = U16(ip); ip += 2;
                    // The elements move to the shadow stack first: `Cons`
                    // allocates, and the value stack is where they are now.
                    int bas = Mark();
                    int at4 = roots.StackTop - nv;
                    for (int i = 0; i < nv; i++) Push(roots.Stack[at4 + i]);
                    long lv = Seqs.FromRoots(this, bas, nv);
                    PopTo(bas);
                    roots.StackTop = at4;
                    VPush(lv);
                } break;
                case Op.Throw:
                case Op.Rethrow: {
                    thrown = VPop();
                    f.Ip = ip;
                    if (!Unwind()) return Val.Nil;
                    continue;
                }
                case Op.Try: {
                    int off = I16(ip); ip += 2;
                    Handler h = new Handler();
                    h.frame = frames.Count - 1;
                    h.stackTop = roots.StackTop;
                    h.target = ip + off;
                    // The SHADOW depth too, not just the value stack. A throw
                    // out of a builtin that was midway through rooting would
                    // otherwise leave those entries live for ever, and the
                    // collector would keep whatever they name.
                    h.shadow = roots.ShadowTop;
                    handlers.Add(h);
                } break;
                case Op.PopHandler: handlers.RemoveAt(handlers.Count - 1); break;
                case Op.TypeP: {
                    int c = U8(ip); ip += 1;
                    roots.Stack[roots.StackTop - 1] = Val.Bool(TypeP(c, roots.Stack[roots.StackTop - 1]));
                } break;
                case Op.Native: {
                    int opAt = ip - 1;
                    int idx = U16(ip); int argc = U8(ip + 2); ip += 3;
                    f.Ip = ip;
                    int at = roots.StackTop - argc;
                    Builtins.Fn fn = natives[idx];
                    if (fn == null) {
                        throw new System.NotSupportedException(
                            "this runtime does not carry the builtin `" + nativeNames[idx] + "`");
                    }
                    long v = fn(this, at, argc);
                    roots.StackTop = at;
                    if (Failed()) {
                        if (Parked()) {
                            // A PARKING builtin. Rewind to the instruction and
                            // leave the operands where they are: resuming
                            // RE-EXECUTES the call, which is why such a builtin
                            // must decide to park before it changes anything.
                            //
                            // Except a courtesy yield, which is finished:
                            // rewinding there would re-execute `yield`, which
                            // yields again, for ever.
                            thrown = Val.Nil;
                            if (parkOn == Conc.PARK_YIELD) {
                                VPush(Val.Nil);
                                thrown = Val.Park;
                                return Val.Nil;
                            }
                            f.Ip = opAt;
                            roots.StackTop = at + argc;
                            thrown = Val.Park;
                            return Val.Nil;
                        }
                        // A BUILTIN THAT FAILED. It set `thrown` and returned
                        // nil, exactly as the Rust does -- so the throw unwinds
                        // to a `catch` in the program rather than out of the
                        // interpreter as a host exception. A host exception here
                        // is uncatchable by flint code, which is how a port with
                        // a working `try` could still not catch a division by
                        // zero: the failure never entered the flint machinery.
                        f.Ip = ip;
                        if (!Unwind()) return Val.Nil;
                        continue;
                    }
                    VPush(v);
                } break;
                default: throw new System.NotSupportedException(
                    $"opcode 0x{opcode:x} is not ported yet");
            }
            f.Ip = ip;
        }
    }

    /// flint's integers OVERFLOW rather than wrap. The JVM has `Math.*Exact`;
    /// .NET has `checked`, and `doc/decisions/0010` names silent wrapping as one
    /// of the ways two hosts quietly disagree.
    static long AddExact(long a, long b) { checked { return a + b; } }
    static long SubExact(long a, long b) { checked { return a - b; } }
    static long MulExact(long a, long b) { checked { return a * b; } }

    /// A call whose callee is not a closure.
    ///
    /// Clojure's rule, and one the reader itself leans on: `(#{\space \tab} c)`
    /// is how whitespace is tested, so without collections-in-call-position the
    /// compiler cannot read its own source.
    long CallValue(int calleeAt, int argc) {
        long callee = roots.Stack[calleeAt];
        if (Val.IsInlineKw(callee) || IsHeapTy(callee, TyKw)) {
            // A keyword looks itself up in the collection it is given.
            long coll = argc >= 1 ? roots.Stack[calleeAt + 1] : Val.Nil;
            long dflt = argc >= 2 ? roots.Stack[calleeAt + 2] : Val.Nil;
            return Lookup(coll, callee, dflt);
        }
        if (IsHeapTy(callee, TyNativefn)) {
            int idx = (int) Val.AsFixnum(Slot(callee, 0));
            Builtins.Fn fn = idx < natives.Length ? natives[idx] : null;
            if (fn == null) {
                return ThrowStr("UnsupportedOperationException",
                    "this runtime does not carry the builtin `"
                    + (idx < nativeNames.Length ? nativeNames[idx] : "#" + idx) + "`");
            }
            return fn(this, calleeAt + 1, argc);
        }
        if (Sets.IsSet(this, callee)) {
            if (argc < 1) return ThrowStr("ArityException", "a set takes 1 argument");
            return Sets.Get(this, callee, roots.Stack[calleeAt + 1], Val.Nil);
        }
        if (Maps.IsMap(this, callee)) {
            if (argc < 1) return ThrowStr("ArityException", "a map takes 1 or 2 arguments");
            long dflt2 = argc >= 2 ? roots.Stack[calleeAt + 2] : Val.Nil;
            return Maps.Get(this, callee, roots.Stack[calleeAt + 1], dflt2);
        }
        if (IsHeapTy(callee, TyVec)) {
            if (argc < 1) return ThrowStr("ArityException", "a vector takes 1 argument");
            long got = Vec.Nth(this, callee, (int) Val.AsFixnum(roots.Stack[calleeAt + 1]));
            return got == Val.NotFound ? Val.Nil : got;
        }
        // Say WHAT was called: "value is not a function" with no subject is
        // the least useful message in the runtime.
        return ThrowStr("ClassCastException",
            "value is not a function (object type "
            + (Val.IsHeap(callee) ? Ty(gc.sp, Val.AsHeap(callee)).ToString() : "inline")
            + ", " + argc + " args)");
    }

    /// `get`, for the collections that are ported.
    long Lookup(long coll, long k, long dflt) {
        if (Val.IsNil(coll)) return dflt;
        if (Maps.IsMap(this, coll)) return Maps.Get(this, coll, k, dflt);
        if (Sets.IsSet(this, coll)) return Sets.Get(this, coll, k, dflt);
        if (IsHeapTy(coll, TyVec)) {
            if (!Val.IsFixnum(k)) return dflt;
            long got = Vec.Nth(this, coll, (int) Val.AsFixnum(k));
            return got == Val.NotFound ? dflt : got;
        }
        return dflt;   // `get` on a non-collection is nil, as Clojure's is
    }

    /// `flint.types/code`'s canonical table, from `vm.rs`. The numbers are the
    /// contract between the compiler and every runtime, so they are written out
    /// rather than derived: a port that renumbered one of these would compile
    /// and answer wrongly.
    public bool TypeP(int code, long v) {
        switch (code) {
            // `IsInt`, NOT `IsFixnum`. A big integer is an integer, and the
            // library's printer dispatches on this: with `IsFixnum` here the
            // bits of 1.5 printed as `#<unprintable>` rather than as
            // 4609434218613702656, because a bigint fell through every arm.
            case 1: return Num.IsInt(this, v);
            case 2: return Val.IsDouble(v);
            case 3: return Num.IsNumber(this, v);
            case 4: return Str.IsString(this, v);
            case 5: return Val.IsInlineKw(v) || IsHeapTy(v, TyKw);
            case 6: return IsHeapTy(v, TySym);
            case 7: return v == Val.True || v == Val.False;
            case 8: return IsHeapTy(v, TyVec);
            case 9: return IsHeapTy(v, TyArraymap) || IsHeapTy(v, TyHashmap);
            case 10: return IsHeapTy(v, TySet);
            case 11: return IsSeq(v);
            case 12: return IsHeapTy(v, TyClosure) || IsHeapTy(v, TyNativefn);
            case 13: return Val.IsNil(v);
            default: return IsHeapTy(v, TyVec) || IsSeq(v);
        }
    }

    /// What a value IS, for a message. A refusal that says "needs more of the
    /// data structures" without naming the type sends the reader back to a
    /// debugger; naming it is the difference between a report and a shrug.
    public string Describe(long v) {
        if (Val.IsNil(v)) return "nil";
        if (Val.IsFixnum(v)) return "an integer";
        if (Val.IsDouble(v)) return "a double";
        if (v == Val.True || v == Val.False) return "a boolean";
        if (Val.IsInlineStr(v)) return "an inline string";
        if (Val.IsInlineKw(v)) return "an inline keyword";
        if (!Val.IsHeap(v)) return "an unknown immediate";
        int t = Ty(gc.sp, Val.AsHeap(v));
        switch (t) {
            case TyStr: return "a string";
            case TySym: return "a symbol";
            case TyKw: return "a keyword";
            case TyCons: return "a list";
            case TyEmptyList: return "an empty list";
            case TyLazyseq: return "a lazy seq";
            case TyVec: return "a vector";
            case TyVecseq: return "a vector seq";
            case TyRange: return "a range";
            case TyArraymap: return "an array-map";
            case TyHashmap: return "a hash-map";
            case TySet: return "a set";
            case TyMapentry: return "a map entry";
            case TyClosure: return "a function";
            case TyNativefn: return "a builtin";
            case TyTvec: return "a transient vector";
            case TyTmap: return "a transient map";
            case TyTset: return "a transient set";
            case TyRope: return "a rope";
            case TyBytes: return "a byte string";
            case TyRecord: return "a record";
            case TyAtom: return "an atom";
            case TyVar: return "a var";
            case TyDelay: return "a delay";
            case TyRegex: return "a regex";
            case TyMultifn: return "a multimethod";
            case TyReduced: return "a reduced";
            case TyExinfo: return "an ex-info";
            default: return "object type " + t;
        }
    }

    /// Sequential, which is WIDER than `IsSeq`: a vector and a map entry are
    /// sequential without being seqs. `=` is over this, not over seq-ness.
    public bool IsSequential(long v) =>
        IsSeq(v) || IsHeapTy(v, TyVec) || IsHeapTy(v, TyMapentry);

    /// Which slot holds this object's metadata, or -1. Metadata is not part
    /// of equality, so `with-meta` copies and the copy is still `=`.
    public int MetaSlot(long v) {
        if (!Val.IsHeap(v)) return -1;
        switch (Ty(gc.sp, Val.AsHeap(v))) {
            case TySym: return 2;
            case TyVec: return Vec.VMeta;
            case TyArraymap: return Maps.AM_META;
            case TyHashmap: return Maps.HM_META;
            case TySet: return Sets.S_META;
            case TyCons: return Seqs.CMeta;
            case TyEmptyList: return 0;
            case TyLazyseq: return 2;
            case TyAtom: return 1;
            default: return -1;
        }
    }

    internal bool IsHeapTy(long v, int t) => Val.IsHeap(v) && Ty(gc.sp, Val.AsHeap(v)) == t;

    internal bool IsSeq(long v) {
        if (!Val.IsHeap(v)) return false;
        int t = Ty(gc.sp, Val.AsHeap(v));
        return t == TyCons || t == TyEmptyList || t == TyLazyseq
            || t == TyVecseq || t == TyStrseq || t == TyRange;
    }

    public void Dispose() => gc.Dispose();

    /// Find the innermost handler that can take `thrown`, or false if nothing
    /// can and the whole call must fail.
    ///
    /// The frames ABOVE the handler are dropped, not returned from: an
    /// exception is not a return, and the operands those frames had pushed are
    /// not values anybody wants. `StackTop` and `ShadowTop` are restored to
    /// what they were when the handler was installed, which is what makes a
    /// throw out of arbitrarily deep code leave no residue.
    public bool Unwind() {
        while (handlers.Count != 0) {
            Handler h = handlers[handlers.Count - 1];
            handlers.RemoveAt(handlers.Count - 1);
            // The frame that installed it may already be gone -- a handler
            // outlives its frame when the throw came from further out.
            if (h.frame >= frames.Count) continue;
            while (frames.Count > h.frame + 1) frames.RemoveAt(frames.Count - 1);
            roots.StackTop = h.stackTop;
            roots.ShadowTop = h.shadow;
            long exc = thrown;
            thrown = Val.Nil;
            VPush(exc);
            frames[frames.Count - 1].Ip = h.target;
            return true;
        }
        return false;
    }

    /// Call ANY callable with `args`: a closure, a builtin, a keyword, or a
    /// collection in function position. `Call` handles only closures, and
    /// `apply` has to handle whatever it is given.
    public long Invoke(long f, long[] args) {
        if (IsHeapTy(f, TyClosure)) return Call(f, args);
        int save = roots.StackTop;
        VReserve(args.Length + 1);
        VPush(f);
        foreach (long a in args) VPush(a);
        long v = CallValue(save, args.Length);
        if (!Parked()) roots.StackTop = save;
        return v;
    }

    /// True when a park is in flight. A park is NOT an error: it unwinds the
    /// interpreter the same way, but `Settle` reads `parkOn` rather than
    /// `thrown`, and every caller between here and the scheduler must pass it
    /// through untouched rather than treating it as a failure.

    // --- exceptions ---------------------------------------------------------
    //
    // An exception is `[kind, msg, data, cause]` and the KIND IS A STRING:
    // flint has no class hierarchy, so `(catch ClassCastException e ...)` has
    // to match on a flat name. The port carried only `[msg, data, cause]` and
    // answered `:ex-info` to every `flint/ex-kind`, so every catch clause the
    // emitter wrote asked a question the runtime could not answer.

    public const int ExKindSlot = 0, ExMsgSlot = 1, ExDataSlot = 2, ExCauseSlot = 3;

    public static long ExInfo(Rt rt, long kind, long msg, long data, long cause) {
        int bas = rt.Mark();
        int k = rt.Push(kind), m = rt.Push(msg), d = rt.Push(data), c = rt.Push(cause);
        long a = rt.Alloc(Obj.TyExinfo, 4);
        if (a == 0) { rt.PopTo(bas); return Val.Nil; }
        rt.SetSlot(a, ExKindSlot, rt.R(k));
        rt.SetSlot(a, ExMsgSlot, rt.R(m));
        rt.SetSlot(a, ExDataSlot, rt.R(d));
        rt.SetSlot(a, ExCauseSlot, rt.R(c));
        rt.PopTo(bas);
        return Val.Heap(a);
    }

    /// Build an exception without throwing it. The scheduler needs this: it
    /// hands an error to a PARKED thread, to be raised when that thread next
    /// resumes rather than in whatever thread noticed the problem.
    public long MakeError(string kind, string msg) {
        int bas = Mark();
        int ki = Push(Str.Of(this, kind));
        long m = Str.Of(this, msg);
        long e = ExInfo(this, R(ki), m, Val.Nil, Val.Nil);
        PopTo(bas);
        return e;
    }

    /// Set the pending exception and return nil, which is what a failing
    /// builtin returns.
    public long ThrowStr(string kind, string msg) {
        thrown = MakeError(kind, msg);
        return Val.Nil;
    }

    public bool IsException(long v) => IsHeapTy(v, Obj.TyExinfo);
    public long ExMessage(long e) => IsException(e) ? Slot(e, ExMsgSlot) : Val.Nil;
    public long ExData(long e) => IsException(e) ? Slot(e, ExDataSlot) : Val.Nil;
    public long ExKind(long e) => IsException(e) ? Slot(e, ExKindSlot) : Val.Nil;

    /// Does an exception match a `catch` clause's name?
    ///
    /// The rules are Java's, over flat names rather than classes: `Throwable`
    /// matches everything; `Exception` and `RuntimeException` match everything
    /// that is not an `...Error` -- the distinction a program catching broadly
    /// still wants, so a stack overflow is not swallowed by a `catch Exception`
    /// around a parser; `Error` matches the `...Error`s; anything else is exact.
    public long ExMatches(long e, long name) {
        string k = Str.IsString(this, ExKind(e)) ? Str.Text(this, ExKind(e)) : "";
        string n = Str.IsString(this, name) ? Str.Text(this, name) : "";
        bool isError = k.EndsWith("Error");
        bool hit = n switch {
            "Throwable" => true,
            "Exception" or "RuntimeException" => !isError,
            "Error" => isError,
            _ => k == n,
        };
        return hit ? Val.True : Val.False;
    }

    // --- opaque values (`doc/decisions/0022`) --------------------------------

    /// The next identity to hand out. STORED in the object rather than derived
    /// from its address: the nursery is a copying collector, so an
    /// address-derived hash would change under collection and a value in a map
    /// would stop being findable by the key that put it there.
    /// A one-entry cursor into the last NON-ASCII string that was indexed.
    /// See `Str.ByteOfCp` -- this is the state, that is the reason.
    public long cursorBits = 0, cursorEpoch = 0;
    public int cursorCp = 0, cursorByte = 0;

    public long nextOpaque = 1;

    /// `[label, id, host-id]`. Guest code can mint one only with host id 0, and
    /// there is deliberately no builtin that reads an id back -- so a host id is
    /// a thing the HOST wrote and only the host can read.
    public long NewOpaque(long label, long hostId) {
        int bas = Mark();
        int li = Push(label);
        long a = Alloc(Obj.TyOpaque, 3);
        if (a == 0) { PopTo(bas); return Val.Nil; }
        long l = R(li);
        PopTo(bas);
        long id = nextOpaque++;
        SetSlot(a, 0, l);
        SetSlot(a, 1, Val.Fixnum(id));
        SetSlot(a, 2, Val.Fixnum(hostId));
        return Val.Heap(a);
    }

    public bool IsOpaque(long v) => IsHeapTy(v, Obj.TyOpaque);

    /// The host id a capability was issued with, or 0 for a guest-minted one.
    public long OpaqueHostId(long v) {
        if (!IsOpaque(v)) return 0;
        long s = Slot(v, 2);
        return Val.IsFixnum(s) ? Val.AsFixnum(s) : 0;
    }

    public long OpaqueLabel(long v) => IsOpaque(v) ? Slot(v, 0) : Val.Nil;

    public bool Parked() => thrown == Val.Park;
    public bool Failed() => !Val.IsNil(thrown);

    /// Carry on a program a snapshot restored.
    ///
    /// A snapshot is faithful: if the program was mid-park when it was taken,
    /// `thrown` comes back holding the PARK sentinel. That is right for a
    /// sandbox parked on a PORT -- it really is still waiting -- and wrong for
    /// one paused by the SLICE, where the pause is over the moment somebody
    /// resumes. Clearing a courtesy yield here is exactly what `Settle` does
    /// for a running scheduler; without it every later call saw a park already
    /// in flight, rewound, and answered nil.
    public long Resume() {
        if (Parked() && parkOn == Conc.PARK_YIELD) {
            thrown = Val.Nil;
            parkOn = Val.Nil;
        }
        if (schedInstalled) return Conc.Drive(this);
        return Run(0);
    }

    /// Run `closure` as the program's ENTRY, under the scheduler if one ever
    /// appears.
    ///
    /// The scheduler is not installed until something spawns, so this starts as
    /// a plain call. When the entry parks or its slice runs out, `thrown` is
    /// the PARK sentinel and the answer is not ready -- that is when the
    /// scheduler takes over. A program with no concurrency in it never reaches
    /// the second branch at all.
    public long RunProgram(long closure, long[] args) {
        long v = Call(closure, args);
        if (!Parked() && !schedInstalled) return v;
        thrown = Val.Nil;
        if (!schedInstalled) return v;
        return Conc.Scheduler(this, v);
    }

    /// Call `closure` with `args` from outside the interpreter.
    public long Call(long closure, long[] args) {
        int save = roots.StackTop;
        VReserve(args.Length + 1);
        VPush(closure);
        foreach (long a in args) VPush(a);
        int calleeAt = roots.StackTop - args.Length - 1;
        int depth = frames.Count;
        if (!Enter(closure, calleeAt, args.Length)) { roots.StackTop = save; return Val.Nil; }
        long v = Run(depth);
        // NOT on a park. `Run` returns early with the value stack holding the
        // parked thread's continuation, and truncating it here would throw that
        // away -- the thread would come back with a stack the scheduler had
        // already cut off underneath it.
        if (!Parked()) roots.StackTop = save;
        return v;
    }
}

