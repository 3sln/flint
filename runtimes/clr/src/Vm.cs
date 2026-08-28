namespace Flint;

/// The flint interpreter, on the CLR (`doc/decisions/0010`, tier 2).
///
/// **The collector is gone**, which is the tier rather than a shortcut: a flint
/// value is a .NET object, so the CLR owns lifetime and the generational
/// copying collector -- the hardest single piece of the wasm runtime -- does
/// not exist here. Nothing roots anything, there is no write barrier, and no
/// value moves.
///
/// Calls use the CLR's own stack for the same reason: it scans it, so live
/// references in locals are found. That is exactly what wasm cannot do
/// (`doc/decisions/0001`) and why flint is an interpreter there at all.
/// `TAIL_CALL` still loops rather than recursing, because the CLR will not do
/// that for us and flint programs written as self-recursion depend on it.
public sealed class Vm {
    private const int Nop = 0x00, Const = 0x01, Nil = 0x02, True = 0x03, False = 0x04,
        Int = 0x05, Local = 0x06, LocalW = 0x07, SetLocal = 0x08, Upval = 0x09,
        Var = 0x0A, SetVar = 0x0B, Pop = 0x0C, Dup = 0x0D, Jump = 0x0E,
        JumpIfFalse = 0x0F, JumpIfTrue = 0x10, CallOp = 0x11, TailCall = 0x12,
        Return = 0x13, ClosureOp = 0x14, Native = 0x15, Throw = 0x16, Try = 0x17,
        PopHandler = 0x18, Rethrow = 0x19, VectorOp = 0x1A, MapOp = 0x1B, SetOp = 0x1C,
        ListOp = 0x1D, Apply = 0x1E, JumpIfFalseKeep = 0x1F, JumpIfTrueKeep = 0x20,
        PopN = 0x21, SetLocalKeep = 0x22, Self = 0x23,
        AddInt = 0x24, SubInt = 0x25, MulInt = 0x26, LtInt = 0x27, LeInt = 0x28,
        GtInt = 0x29, GeInt = 0x2A, EqInt = 0x2B, TypeP = 0x2C;

    public readonly Img Img;
    /// Var slots. Written by `def`, read by every top-level name.
    ///
    /// Guarded, because several threads may run one program and one may be
    /// writing. A torn or unpublished write is visible to another thread
    /// eventually or never, and "eventually" is not a semantics.
    private readonly object[] _vars;
    private readonly object _varLock = new();
    private readonly Builtins.Fn[] _natives;
    private volatile bool _started;
    private readonly object _startLock = new();

    public sealed record Closure(int FnIndex, object[] Upvals);

    public Vm(Img img) {
        Img = img;
        _vars = new object[img.VarNames.Length];
        _natives = new Builtins.Fn[img.NativeNames.Length];
        for (int i = 0; i < _natives.Length; i++) _natives[i] = Builtins.ByName(img.NativeNames[i]);
    }

    private object GetVar(int i) { lock (_varLock) return _vars[i]; }
    private void SetVarSlot(int i, object v) { lock (_varLock) _vars[i] = v; }

    /// Run the image's initialisers, once. A sandbox serves many calls and they
    /// run once, not per call (`doc/decisions/0025`).
    public void EnsureStarted() {
        if (_started) return;
        lock (_startLock) {
            if (_started) return;
            foreach (int fn in Img.Init) Call(new Closure(fn, Array.Empty<object>()), Array.Empty<object>());
            // Set LAST: a thread that saw this before the initialisers finished
            // would call into a half-built program.
            _started = true;
        }
    }

    public object Call(object fn, object[] args) {
        if (fn is Closure c) {
            var def = Img.Fns[c.FnIndex];
            var a = def.Select(args.Length);
            if (a == null)
                throw new FlintThrow($"wrong number of arguments ({args.Length}) for {def.Name ?? "fn"}");
            var locals = new object[Math.Max(a.Nlocals, a.Argc + 1)];
            Array.Copy(args, locals, Math.Min(a.Argc, args.Length));
            if (a.Variadic) {
                var rest = new List<object>();
                for (int i = a.Argc; i < args.Length; i++) rest.Add(args[i]);
                locals[a.Argc] = rest.Count == 0 ? null : new Seq(rest);
            }
            return Run(c, a, locals);
        }
        if (fn is Builtins.Fn f) return f(this, args);
        if (fn is Img.NativeRef nr) {
            var g = Builtins.ByName(nr.Name);
            if (g == null) throw new FlintThrow($"this runtime does not carry `{nr.Name}`");
            return g(this, args);
        }
        // A keyword in call position looks itself up, which is Clojure's rule
        // and one programs lean on constantly.
        if (fn is Kw k && args.Length >= 1) return Builtins.Get(args[0], k, null);
        throw new FlintThrow(Builtins.PrStr(fn) + " is not a function");
    }

    private int U8(int ip) => Img.Code[ip];
    private int U16(int ip) => U8(ip) | (U8(ip + 1) << 8);
    private int I16(int ip) => (short) U16(ip);

    /// Only `nil` and `false` are false. Zero, the empty string and the empty
    /// vector are all true.
    public static bool Truthy(object v) => v != null && !(v is bool b && !b);

    private object Run(Closure self, Img.Arity arity, object[] locals) {
        var stack = new object[64];
        int sp = 0;
        int ip = arity.Code;
        int end = arity.Code + arity.Len;
        var handlerIp = new int[8];
        var handlerSp = new int[8];
        int handlers = 0;

        for (;;) {
            if (ip >= end) return sp > 0 ? stack[sp - 1] : null;
            if (sp + 4 >= stack.Length) Array.Resize(ref stack, stack.Length * 2);
            int opcode = U8(ip);
            ip += 1;
            try {
                switch (opcode) {
                    case Nop: break;
                    case Const: stack[sp++] = Img.Consts[U16(ip)]; ip += 2; break;
                    case Nil: stack[sp++] = null; break;
                    case True: stack[sp++] = true; break;
                    case False: stack[sp++] = false; break;
                    case Int: stack[sp++] = (long) I16(ip); ip += 2; break;
                    case Local: stack[sp++] = locals[U8(ip)]; ip += 1; break;
                    case LocalW: stack[sp++] = locals[U16(ip)]; ip += 2; break;
                    case SetLocal: locals[U8(ip)] = stack[--sp]; ip += 1; break;
                    case SetLocalKeep: locals[U8(ip)] = stack[sp - 1]; ip += 1; break;
                    case Upval: stack[sp++] = self.Upvals[U8(ip)]; ip += 1; break;
                    case Var: stack[sp++] = GetVar(U16(ip)); ip += 2; break;
                    case SetVar: SetVarSlot(U16(ip), stack[--sp]); ip += 2; break;
                    case Pop: sp -= 1; break;
                    case PopN: sp -= U8(ip); ip += 1; break;
                    case Dup: stack[sp] = stack[sp - 1]; sp += 1; break;
                    case Self: stack[sp++] = self; break;
                    case Jump: ip += 2 + I16(ip); break;
                    case JumpIfFalse: { int off = I16(ip); ip += 2; if (!Truthy(stack[--sp])) ip += off; break; }
                    case JumpIfTrue: { int off = I16(ip); ip += 2; if (Truthy(stack[--sp])) ip += off; break; }
                    case JumpIfFalseKeep: { int off = I16(ip); ip += 2; if (!Truthy(stack[sp - 1])) ip += off; else sp -= 1; break; }
                    case JumpIfTrueKeep: { int off = I16(ip); ip += 2; if (Truthy(stack[sp - 1])) ip += off; else sp -= 1; break; }
                    case CallOp: {
                        int argc = U8(ip); ip += 1;
                        var args = new object[argc];
                        Array.Copy(stack, sp - argc, args, 0, argc);
                        sp -= argc + 1;
                        object f = stack[sp];
                        stack[sp++] = Call(f, args);
                        break;
                    }
                    case TailCall: {
                        // A real tail call: no CLR frame is added, so a loop
                        // written as self-recursion runs in constant stack.
                        int argc = U8(ip); ip += 1;
                        var args = new object[argc];
                        Array.Copy(stack, sp - argc, args, 0, argc);
                        sp -= argc + 1;
                        object f = stack[sp];
                        if (f is Closure c2 && c2.FnIndex == self.FnIndex) {
                            var a2 = Img.Fns[c2.FnIndex].Select(argc);
                            if (a2 != null) {
                                self = c2; arity = a2;
                                locals = new object[Math.Max(a2.Nlocals, a2.Argc + 1)];
                                Array.Copy(args, locals, Math.Min(a2.Argc, argc));
                                if (a2.Variadic) {
                                    var rest = new List<object>();
                                    for (int i = a2.Argc; i < argc; i++) rest.Add(args[i]);
                                    locals[a2.Argc] = rest.Count == 0 ? null : new Seq(rest);
                                }
                                ip = a2.Code; end = a2.Code + a2.Len; sp = 0; handlers = 0;
                                continue;
                            }
                        }
                        return Call(f, args);
                    }
                    case Return: return sp > 0 ? stack[--sp] : null;
                    case ClosureOp: {
                        int fnIdx = U16(ip); ip += 2;
                        int nup = U8(ip); ip += 1;
                        var up = new object[nup];
                        for (int i = nup - 1; i >= 0; i--) up[i] = stack[--sp];
                        stack[sp++] = new Closure(fnIdx, up);
                        break;
                    }
                    case Native: {
                        int idx = U16(ip); ip += 2;
                        int argc = U8(ip); ip += 1;
                        var args = new object[argc];
                        Array.Copy(stack, sp - argc, args, 0, argc);
                        sp -= argc;
                        var f = _natives[idx];
                        if (f == null)
                            throw new FlintThrow($"this runtime does not carry the builtin `{Img.NativeNames[idx]}`");
                        stack[sp++] = f(this, args);
                        break;
                    }
                    case VectorOp: {
                        int n = U16(ip); ip += 2;
                        var xs = new List<object>(n);
                        for (int i = 0; i < n; i++) xs.Add(stack[sp - n + i]);
                        sp -= n;
                        stack[sp++] = new Vec(xs);
                        break;
                    }
                    case ListOp: {
                        int n = U16(ip); ip += 2;
                        var xs = new List<object>(n);
                        for (int i = 0; i < n; i++) xs.Add(stack[sp - n + i]);
                        sp -= n;
                        stack[sp++] = new Seq(xs);
                        break;
                    }
                    case SetOp: {
                        int n = U16(ip); ip += 2;
                        var xs = new List<object>(n);
                        for (int i = 0; i < n; i++) xs.Add(stack[sp - n + i]);
                        sp -= n;
                        stack[sp++] = new FlintSet(xs);
                        break;
                    }
                    case MapOp: {
                        int n = U16(ip); ip += 2;
                        var m = FlintMap.Empty;
                        for (int i = 0; i < n; i++)
                            m = m.Assoc(stack[sp - 2 * n + 2 * i], stack[sp - 2 * n + 2 * i + 1]);
                        sp -= 2 * n;
                        stack[sp++] = m;
                        break;
                    }
                    case Apply: {
                        int argc = U8(ip); ip += 1;
                        object seq = stack[--sp];
                        var fixedArgs = new object[argc - 1];
                        Array.Copy(stack, sp - (argc - 1), fixedArgs, 0, argc - 1);
                        sp -= argc - 1;
                        object f = stack[--sp];
                        var all = new List<object>(fixedArgs);
                        foreach (var o in Builtins.Iterate(seq)) all.Add(o);
                        stack[sp++] = Call(f, all.ToArray());
                        break;
                    }
                    case Throw: case Rethrow: throw new FlintThrow(stack[--sp]);
                    case Try: {
                        int off = U16(ip); ip += 2;
                        if (handlers == handlerIp.Length) {
                            Array.Resize(ref handlerIp, handlers * 2);
                            Array.Resize(ref handlerSp, handlers * 2);
                        }
                        handlerIp[handlers] = ip + off;
                        handlerSp[handlers] = sp;
                        handlers += 1;
                        break;
                    }
                    case PopHandler: handlers -= 1; break;
                    case AddInt: { long b = Num(stack[--sp]), a3 = Num(stack[--sp]); stack[sp++] = checked(a3 + b); break; }
                    case SubInt: { long b = Num(stack[--sp]), a3 = Num(stack[--sp]); stack[sp++] = checked(a3 - b); break; }
                    case MulInt: { long b = Num(stack[--sp]), a3 = Num(stack[--sp]); stack[sp++] = checked(a3 * b); break; }
                    case LtInt: { long b = Num(stack[--sp]), a3 = Num(stack[--sp]); stack[sp++] = a3 < b; break; }
                    case LeInt: { long b = Num(stack[--sp]), a3 = Num(stack[--sp]); stack[sp++] = a3 <= b; break; }
                    case GtInt: { long b = Num(stack[--sp]), a3 = Num(stack[--sp]); stack[sp++] = a3 > b; break; }
                    case GeInt: { long b = Num(stack[--sp]), a3 = Num(stack[--sp]); stack[sp++] = a3 >= b; break; }
                    case EqInt: { long b = Num(stack[--sp]), a3 = Num(stack[--sp]); stack[sp++] = a3 == b; break; }
                    case TypeP: { int code = U8(ip); ip += 1; stack[sp - 1] = Builtins.IsType(stack[sp - 1], code); break; }
                    default: throw new FlintThrow($"unknown opcode 0x{opcode:x}");
                }
            } catch (FlintThrow t) {
                if (handlers == 0) throw;
                handlers -= 1;
                sp = handlerSp[handlers];
                ip = handlerIp[handlers];
                stack[sp++] = t.Value;
            }
        }
    }

    /// flint's integers are i64 and **overflow throws**. .NET's `long` wraps
    /// unless checked, which `doc/decisions/0010` names as one of the ways two
    /// hosts quietly disagree -- so the arithmetic above is `checked`.
    public static long Num(object v) {
        if (v is long l) return l;
        if (v is int i) return i;
        throw new FlintThrow(Builtins.PrStr(v) + " is not an integer");
    }
}
