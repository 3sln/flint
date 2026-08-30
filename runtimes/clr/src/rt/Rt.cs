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

    public long steps;

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
        roots.Consts = consts;
    }

    public sealed class FnDef {
        public readonly Arity[] arities;
        public readonly int nupvals;
        public FnDef(Arity[] arities, int nupvals) { this.arities = arities; this.nupvals = nupvals; }
        public Arity Select(int argc) {
            foreach (Arity a in arities) {
                if (a.variadic ? argc >= a.argc : argc == a.argc) return a;
            }
            return null;
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

    public long Alloc(int ty, int len) { return gc.Alloc(roots, ty, len); }

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
                frames.RemoveAt(frames.Count - 1);
                roots.StackTop = f.RetTo;
                VPush(v);
                if (frames.Count <= baseDepth) return VPop();
                continue;
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
                case Op.Var: { VPush(roots.Globals[U16(ip)]); ip += 2; } break;
                case Op.SetVar: { roots.Globals[U16(ip)] = VPop(); ip += 2; } break;
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
                    roots.StackTop = calleeAt;
                    VPush(cv);
                } break;
                case Op.TailCall: {
                    int argc = U8(ip); ip += 1;
                    f.Ip = ip;
                    int calleeAt = roots.StackTop - argc - 1;
                    long callee = roots.Stack[calleeAt];
                    // Drop this frame FIRST: that is what makes a tail call
                    // constant-space, and it is why mutual recursion between
                    // three functions runs for ever here and overflowed on the
                    // old port.
                    frames.RemoveAt(frames.Count - 1);
                    int dest = f.RetTo;
                    for (int i = 0; i <= argc; i++) roots.Stack[dest + i] = roots.Stack[calleeAt + i];
                    roots.StackTop = dest + argc + 1;
                    if (!Enter(callee, dest, argc)) return Val.Nil;
                    continue;
                }
                case Op.Return: {
                    long v = VPop();
                    frames.RemoveAt(frames.Count - 1);
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
                case Op.TypeP: {
                    int c = U8(ip); ip += 1;
                    roots.Stack[roots.StackTop - 1] = Val.Bool(TypeP(c, roots.Stack[roots.StackTop - 1]));
                } break;
                case Op.Native: {
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
                throw new System.NotSupportedException(
                    "this runtime does not carry the builtin `"
                    + (idx < nativeNames.Length ? nativeNames[idx] : "#" + idx) + "`");
            }
            return fn(this, calleeAt + 1, argc);
        }
        if (IsHeapTy(callee, TyVec)) {
            if (argc < 1) throw new System.NotSupportedException("a vector takes 1 argument");
            long got = Vec.Nth(this, callee, (int) Val.AsFixnum(roots.Stack[calleeAt + 1]));
            return got == Val.NotFound ? Val.Nil : got;
        }
        throw new System.NotSupportedException(
            "value is not a function (object type "
            + (Val.IsHeap(callee) ? Ty(gc.sp, Val.AsHeap(callee)).ToString() : "inline")
            + ", " + argc + " args)");
    }

    /// `get`, for the collections that are ported.
    long Lookup(long coll, long k, long dflt) {
        if (Val.IsNil(coll)) return dflt;
        if (Maps.IsMap(this, coll)) return Maps.Get(this, coll, k, dflt);
        if (IsHeapTy(coll, TyVec)) {
            if (!Val.IsFixnum(k)) return dflt;
            long got = Vec.Nth(this, coll, (int) Val.AsFixnum(k));
            return got == Val.NotFound ? dflt : got;
        }
        throw new System.NotSupportedException("lookup needs sets ported");
    }

    /// `flint.types/code`'s canonical table, from `vm.rs`. The numbers are the
    /// contract between the compiler and every runtime, so they are written out
    /// rather than derived: a port that renumbered one of these would compile
    /// and answer wrongly.
    bool TypeP(int code, long v) {
        switch (code) {
            case 1: return Val.IsFixnum(v);
            case 2: return Val.IsDouble(v);
            case 3: return Val.IsFixnum(v) || Val.IsDouble(v);
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

    internal bool IsHeapTy(long v, int t) => Val.IsHeap(v) && Ty(gc.sp, Val.AsHeap(v)) == t;

    internal bool IsSeq(long v) {
        if (!Val.IsHeap(v)) return false;
        int t = Ty(gc.sp, Val.AsHeap(v));
        return t == TyCons || t == TyEmptyList || t == TyLazyseq
            || t == TyVecseq || t == TyStrseq || t == TyRange;
    }

    public void Dispose() => gc.Dispose();

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
        roots.StackTop = save;
        return v;
    }
}

