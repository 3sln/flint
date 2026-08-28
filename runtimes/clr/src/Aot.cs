using System.Reflection;
using System.Reflection.Emit;

namespace Flint;

/// Tier 3 for the CLR: emit IL for a flint arity instead of dispatching it
/// (`doc/decisions/0010`, `doc/decisions/0013`).
///
/// **Why this is a backend here and a fight on wasm.** flint is an interpreter
/// on wasm because wasm locals are not scannable, so compiled code would put
/// live references where a linear-memory collector cannot see them
/// (`doc/decisions/0001`). The CLR scans its own stack and owns lifetime, so
/// that constraint is simply absent and this can hold values wherever it likes.
///
/// It still keeps the value stack in an `object[]` rather than on the IL
/// evaluation stack. Not caution about the collector -- caution about the
/// EMITTER: a value left on the IL stack across a branch has to have the same
/// depth on every path into a label, and flint's jumps do not respect that.
/// An array and an `sp` local make every opcode independent, which is what
/// lets this be complete rather than clever.
///
/// What it removes is the dispatch: no opcode fetch, no switch, no bounds
/// check per instruction. The bodies are the same work.
public static class Aot {
    /// A compiled arity. `self` is the closure, for upvalues.
    public delegate object Compiled(Vm vm, Vm.Closure self, object[] locals);

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

    /// Opcodes this emitter does not handle.
    ///
    /// A function using ANY of them is left to the interpreter entirely, rather
    /// than compiled with a bail-out per instruction. Exception handling and
    /// tail calls both need control flow that does not fall out of the
    /// straight-line shape below, and a partly-compiled arity would need the
    /// two halves to agree about the stack -- which is the bug this avoids by
    /// not having the seam.
    private static bool Unsupported(int op) => op switch {
        Try or PopHandler or Throw or Rethrow or TailCall or Apply => true,
        _ => false,
    };

    // Helpers the emitted code calls. Looked up once.
    private static readonly MethodInfo MCall =
        typeof(Vm).GetMethod(nameof(Vm.Call), new[] { typeof(object), typeof(object[]) });
    private static readonly MethodInfo MTruthy =
        typeof(Vm).GetMethod(nameof(Vm.Truthy), new[] { typeof(object) });
    private static readonly MethodInfo MNum =
        typeof(Vm).GetMethod(nameof(Vm.Num), new[] { typeof(object) });
    private static readonly MethodInfo MIsType =
        typeof(Builtins).GetMethod(nameof(Builtins.IsType));
    private static readonly MethodInfo MHelpNative =
        typeof(Aot).GetMethod(nameof(HelpNative), BindingFlags.Public | BindingFlags.Static);
    private static readonly MethodInfo MHelpConst =
        typeof(Aot).GetMethod(nameof(HelpConst), BindingFlags.Public | BindingFlags.Static);
    private static readonly MethodInfo MHelpVar =
        typeof(Aot).GetMethod(nameof(HelpVar), BindingFlags.Public | BindingFlags.Static);
    private static readonly MethodInfo MHelpSetVar =
        typeof(Aot).GetMethod(nameof(HelpSetVar), BindingFlags.Public | BindingFlags.Static);
    private static readonly MethodInfo MHelpColl =
        typeof(Aot).GetMethod(nameof(HelpColl), BindingFlags.Public | BindingFlags.Static);
    private static readonly MethodInfo MHelpClosure =
        typeof(Aot).GetMethod(nameof(HelpClosure), BindingFlags.Public | BindingFlags.Static);
    private static readonly MethodInfo MHelpUpval =
        typeof(Aot).GetMethod(nameof(HelpUpval), BindingFlags.Public | BindingFlags.Static);
    private static readonly MethodInfo MBoxLong =
        typeof(Aot).GetMethod(nameof(BoxLong), BindingFlags.Public | BindingFlags.Static);
    private static readonly MethodInfo MBoxBool =
        typeof(Aot).GetMethod(nameof(BoxBool), BindingFlags.Public | BindingFlags.Static);

    /// Boxed longs for the small values a loop counter actually takes.
    ///
    /// Every specialised int opcode boxes its result, and in a counting loop
    /// that is an allocation per iteration for a value that was just there.
    /// The range is the one Java's `Long.valueOf` caches plus room for typical
    /// indices; outside it, box normally.
    private const int BoxLo = -128, BoxHi = 1024;
    private static readonly object[] BoxCache = BuildBoxCache();
    private static object[] BuildBoxCache() {
        var c = new object[BoxHi - BoxLo + 1];
        for (int i = 0; i < c.Length; i++) c[i] = (long) (i + BoxLo);
        return c;
    }
    public static object BoxLong(long v) =>
        v >= BoxLo && v <= BoxHi ? BoxCache[(int) (v - BoxLo)] : v;

    private static readonly object BoxTrue = true, BoxFalse = false;
    public static object BoxBool(bool b) => b ? BoxTrue : BoxFalse;

    public static object HelpConst(Vm vm, int i) => vm.Img.Consts[i];
    public static object HelpVar(Vm vm, int i) => vm.GetVarPublic(i);
    public static object HelpSetVar(Vm vm, int i, object v) { vm.SetVarPublic(i, v); return null; }
    public static object HelpUpval(Vm.Closure self, int i) => self.Upvals[i];

    public static object HelpNative(Vm vm, int idx, object[] stack, int at, int argc) {
        var args = new object[argc];
        Array.Copy(stack, at, args, 0, argc);
        return vm.CallNative(idx, args);
    }

    public static object HelpClosure(int fnIdx, object[] stack, int at, int nup) {
        var up = new object[nup];
        Array.Copy(stack, at, up, 0, nup);
        return new Vm.Closure(fnIdx, up);
    }

    /// Build a vector, list, set or map from `n` stack slots.
    public static object HelpColl(int kind, object[] stack, int at, int n) {
        switch (kind) {
            case VectorOp: {
                var xs = new List<object>(n);
                for (int i = 0; i < n; i++) xs.Add(stack[at + i]);
                return new Vec(xs);
            }
            case ListOp: {
                var xs = new List<object>(n);
                for (int i = 0; i < n; i++) xs.Add(stack[at + i]);
                return new Seq(xs);
            }
            case SetOp: {
                var xs = new List<object>(n);
                for (int i = 0; i < n; i++) xs.Add(stack[at + i]);
                return new FlintSet(xs);
            }
            default: {
                var m = FlintMap.Empty;
                for (int i = 0; i < n; i++) m = m.Assoc(stack[at + 2 * i], stack[at + 2 * i + 1]);
                return m;
            }
        }
    }

    /// Compile one arity, or return null if it uses something this does not do.
    public static Compiled TryCompile(Img img, Img.Arity a) {
        byte[] code = img.Code;
        int start = a.Code, end = a.Code + a.Len;

        // One pass to reject, and to find every jump target so a label can be
        // defined for it before anything branches there.
        var targets = new HashSet<int>();
        for (int ip = start; ip < end; ) {
            int op = code[ip];
            if (Unsupported(op)) return null;
            int len = OperandLen(op);
            if (op is Jump or JumpIfFalse or JumpIfTrue or JumpIfFalseKeep or JumpIfTrueKeep) {
                int off = (short) (code[ip + 1] | (code[ip + 2] << 8));
                targets.Add(ip + 3 + off);
            }
            ip += 1 + len;
        }

        var m = new DynamicMethod(
            "flint_arity", typeof(object),
            new[] { typeof(Vm), typeof(Vm.Closure), typeof(object[]) },
            typeof(Aot).Module, skipVisibility: true);
        var il = m.GetILGenerator();

        var stack = il.DeclareLocal(typeof(object[]));
        var sp = il.DeclareLocal(typeof(int));
        var tmp = il.DeclareLocal(typeof(object));
        var lhs = il.DeclareLocal(typeof(long));
        var rhs = il.DeclareLocal(typeof(long));

        // The value stack, sized once. `max-depth` is a dataflow in the wasm
        // emitter; here the arity's own local count plus its code length is a
        // safe bound and costs one allocation.
        il.Emit(OpCodes.Ldc_I4, Math.Max(16, a.Len + a.Nlocals + 8));
        il.Emit(OpCodes.Newarr, typeof(object));
        il.Emit(OpCodes.Stloc, stack);
        il.Emit(OpCodes.Ldc_I4_0);
        il.Emit(OpCodes.Stloc, sp);

        var labels = new Dictionary<int, Label>();
        foreach (int t in targets) labels[t] = il.DefineLabel();

        // sp -= n
        void Drop(int n) {
            il.Emit(OpCodes.Ldloc, sp); il.Emit(OpCodes.Ldc_I4, n);
            il.Emit(OpCodes.Sub); il.Emit(OpCodes.Stloc, sp);
        }
        // stack[sp++] = <value on the IL stack>
        void Push() {
            il.Emit(OpCodes.Stloc, tmp);
            il.Emit(OpCodes.Ldloc, stack); il.Emit(OpCodes.Ldloc, sp); il.Emit(OpCodes.Ldloc, tmp);
            il.Emit(OpCodes.Stelem_Ref);
            il.Emit(OpCodes.Ldloc, sp); il.Emit(OpCodes.Ldc_I4_1); il.Emit(OpCodes.Add);
            il.Emit(OpCodes.Stloc, sp);
        }
        // push stack[sp - depth] onto the IL stack
        void Peek(int depth) {
            il.Emit(OpCodes.Ldloc, stack); il.Emit(OpCodes.Ldloc, sp);
            il.Emit(OpCodes.Ldc_I4, depth); il.Emit(OpCodes.Sub);
            il.Emit(OpCodes.Ldelem_Ref);
        }
        void PopToIl() { Peek(1); Drop(1); }
        // Both operands of a specialised int op, as longs.
        void TwoLongs() {
            PopToIl(); il.Emit(OpCodes.Call, MNum); il.Emit(OpCodes.Stloc, rhs);
            PopToIl(); il.Emit(OpCodes.Call, MNum); il.Emit(OpCodes.Stloc, lhs);
            il.Emit(OpCodes.Ldloc, lhs); il.Emit(OpCodes.Ldloc, rhs);
        }

        for (int ip = start; ip < end; ) {
            if (labels.TryGetValue(ip, out var here)) il.MarkLabel(here);
            int op = code[ip];
            int len = OperandLen(op);
            int b0 = ip + 1 < code.Length ? code[ip + 1] : 0;
            int u16 = ip + 2 < code.Length ? (code[ip + 1] | (code[ip + 2] << 8)) : 0;
            int i16 = (short) u16;
            int next = ip + 1 + len;

            switch (op) {
                case Nop: break;
                case Nil: il.Emit(OpCodes.Ldnull); Push(); break;
                case True: il.Emit(OpCodes.Ldc_I4_1); il.Emit(OpCodes.Call, MBoxBool); Push(); break;
                case False: il.Emit(OpCodes.Ldc_I4_0); il.Emit(OpCodes.Call, MBoxBool); Push(); break;
                case Int:
                    il.Emit(OpCodes.Ldc_I8, (long) i16); il.Emit(OpCodes.Call, MBoxLong); Push(); break;
                case Const:
                    il.Emit(OpCodes.Ldarg_0); il.Emit(OpCodes.Ldc_I4, u16);
                    il.Emit(OpCodes.Call, MHelpConst); Push(); break;
                case Var:
                    il.Emit(OpCodes.Ldarg_0); il.Emit(OpCodes.Ldc_I4, u16);
                    il.Emit(OpCodes.Call, MHelpVar); Push(); break;
                case SetVar:
                    il.Emit(OpCodes.Ldarg_0); il.Emit(OpCodes.Ldc_I4, u16);
                    PopToIl(); il.Emit(OpCodes.Call, MHelpSetVar); il.Emit(OpCodes.Pop); break;
                case Local: case LocalW: {
                    int idx = op == Local ? b0 : u16;
                    il.Emit(OpCodes.Ldarg_2); il.Emit(OpCodes.Ldc_I4, idx);
                    il.Emit(OpCodes.Ldelem_Ref); Push(); break;
                }
                case SetLocal:
                    il.Emit(OpCodes.Ldarg_2); il.Emit(OpCodes.Ldc_I4, b0);
                    PopToIl(); il.Emit(OpCodes.Stelem_Ref); break;
                case SetLocalKeep:
                    il.Emit(OpCodes.Ldarg_2); il.Emit(OpCodes.Ldc_I4, b0);
                    Peek(1); il.Emit(OpCodes.Stelem_Ref); break;
                case Upval:
                    il.Emit(OpCodes.Ldarg_1); il.Emit(OpCodes.Ldc_I4, b0);
                    il.Emit(OpCodes.Call, MHelpUpval); Push(); break;
                case Self: il.Emit(OpCodes.Ldarg_1); Push(); break;
                case Pop: Drop(1); break;
                case PopN: Drop(b0); break;
                case Dup: Peek(1); Push(); break;
                case Jump: il.Emit(OpCodes.Br, labels[next + i16]); break;
                case JumpIfFalse:
                    PopToIl(); il.Emit(OpCodes.Call, MTruthy);
                    il.Emit(OpCodes.Brfalse, labels[next + i16]); break;
                case JumpIfTrue:
                    PopToIl(); il.Emit(OpCodes.Call, MTruthy);
                    il.Emit(OpCodes.Brtrue, labels[next + i16]); break;
                case JumpIfFalseKeep: {
                    var keep = il.DefineLabel();
                    Peek(1); il.Emit(OpCodes.Call, MTruthy);
                    il.Emit(OpCodes.Brfalse, labels[next + i16]);
                    Drop(1); il.MarkLabel(keep); break;
                }
                case JumpIfTrueKeep: {
                    Peek(1); il.Emit(OpCodes.Call, MTruthy);
                    il.Emit(OpCodes.Brtrue, labels[next + i16]);
                    Drop(1); break;
                }
                case Return: PopToIl(); il.Emit(OpCodes.Ret); break;
                case AddInt: TwoLongs(); il.Emit(OpCodes.Add_Ovf); il.Emit(OpCodes.Call, MBoxLong); Push(); break;
                case SubInt: TwoLongs(); il.Emit(OpCodes.Sub_Ovf); il.Emit(OpCodes.Call, MBoxLong); Push(); break;
                case MulInt: TwoLongs(); il.Emit(OpCodes.Mul_Ovf); il.Emit(OpCodes.Call, MBoxLong); Push(); break;
                case LtInt: TwoLongs(); il.Emit(OpCodes.Clt); il.Emit(OpCodes.Call, MBoxBool); Push(); break;
                case GtInt: TwoLongs(); il.Emit(OpCodes.Cgt); il.Emit(OpCodes.Call, MBoxBool); Push(); break;
                case LeInt:
                    TwoLongs(); il.Emit(OpCodes.Cgt); il.Emit(OpCodes.Ldc_I4_0); il.Emit(OpCodes.Ceq);
                    il.Emit(OpCodes.Call, MBoxBool); Push(); break;
                case GeInt:
                    TwoLongs(); il.Emit(OpCodes.Clt); il.Emit(OpCodes.Ldc_I4_0); il.Emit(OpCodes.Ceq);
                    il.Emit(OpCodes.Call, MBoxBool); Push(); break;
                case EqInt: TwoLongs(); il.Emit(OpCodes.Ceq); il.Emit(OpCodes.Call, MBoxBool); Push(); break;
                case TypeP:
                    Peek(1); il.Emit(OpCodes.Ldc_I4, b0); il.Emit(OpCodes.Call, MIsType);
                    il.Emit(OpCodes.Stloc, tmp);
                    il.Emit(OpCodes.Ldloc, stack); il.Emit(OpCodes.Ldloc, sp);
                    il.Emit(OpCodes.Ldc_I4_1); il.Emit(OpCodes.Sub);
                    il.Emit(OpCodes.Ldloc, tmp); il.Emit(OpCodes.Stelem_Ref); break;
                case Native: {
                    int argc = code[ip + 3];
                    il.Emit(OpCodes.Ldarg_0); il.Emit(OpCodes.Ldc_I4, u16);
                    il.Emit(OpCodes.Ldloc, stack);
                    il.Emit(OpCodes.Ldloc, sp); il.Emit(OpCodes.Ldc_I4, argc); il.Emit(OpCodes.Sub);
                    il.Emit(OpCodes.Ldc_I4, argc);
                    il.Emit(OpCodes.Call, MHelpNative);
                    il.Emit(OpCodes.Stloc, tmp);
                    Drop(argc);
                    il.Emit(OpCodes.Ldloc, tmp); Push(); break;
                }
                case CallOp: {
                    int argc = b0;
                    // The callee sits under its arguments, so the args are
                    // gathered first and the function read after dropping them.
                    il.Emit(OpCodes.Ldarg_0);
                    Peek(argc + 1);                                  // the function
                    il.Emit(OpCodes.Ldloc, stack);
                    il.Emit(OpCodes.Ldloc, sp); il.Emit(OpCodes.Ldc_I4, argc); il.Emit(OpCodes.Sub);
                    il.Emit(OpCodes.Ldc_I4, argc);
                    il.Emit(OpCodes.Call, typeof(Aot).GetMethod(nameof(Slice)));
                    il.Emit(OpCodes.Callvirt, MCall);
                    il.Emit(OpCodes.Stloc, tmp);
                    Drop(argc + 1);
                    il.Emit(OpCodes.Ldloc, tmp); Push(); break;
                }
                case ClosureOp: {
                    int nup = code[ip + 3];
                    il.Emit(OpCodes.Ldc_I4, u16);
                    il.Emit(OpCodes.Ldloc, stack);
                    il.Emit(OpCodes.Ldloc, sp); il.Emit(OpCodes.Ldc_I4, nup); il.Emit(OpCodes.Sub);
                    il.Emit(OpCodes.Ldc_I4, nup);
                    il.Emit(OpCodes.Call, MHelpClosure);
                    il.Emit(OpCodes.Stloc, tmp);
                    Drop(nup);
                    il.Emit(OpCodes.Ldloc, tmp); Push(); break;
                }
                case VectorOp: case ListOp: case SetOp: case MapOp: {
                    int n = u16;
                    int slots = op == MapOp ? 2 * n : n;
                    il.Emit(OpCodes.Ldc_I4, op);
                    il.Emit(OpCodes.Ldloc, stack);
                    il.Emit(OpCodes.Ldloc, sp); il.Emit(OpCodes.Ldc_I4, slots); il.Emit(OpCodes.Sub);
                    il.Emit(OpCodes.Ldc_I4, n);
                    il.Emit(OpCodes.Call, MHelpColl);
                    il.Emit(OpCodes.Stloc, tmp);
                    Drop(slots);
                    il.Emit(OpCodes.Ldloc, tmp); Push(); break;
                }
                default: return null;   // anything unrecognised: interpret it
            }
            ip = next;
        }

        // Falling off the end returns the top of stack, as the interpreter does.
        var empty = il.DefineLabel();
        il.Emit(OpCodes.Ldloc, sp); il.Emit(OpCodes.Ldc_I4_0);
        il.Emit(OpCodes.Ble, empty);
        Peek(1); il.Emit(OpCodes.Ret);
        il.MarkLabel(empty);
        il.Emit(OpCodes.Ldnull); il.Emit(OpCodes.Ret);

        try {
            return (Compiled) m.CreateDelegate(typeof(Compiled));
        } catch (Exception) {
            // An emitter bug must not be a wrong ANSWER: refuse the compiled
            // form and let the interpreter run it.
            return null;
        }
    }

    public static object[] Slice(object[] stack, int at, int n) {
        var args = new object[n];
        Array.Copy(stack, at, args, 0, n);
        return args;
    }

    private static int OperandLen(int op) => op switch {
        Const or LocalW or Var or SetVar or Jump or JumpIfFalse or JumpIfTrue
            or JumpIfFalseKeep or JumpIfTrueKeep or Try or VectorOp or MapOp
            or SetOp or ListOp => 2,
        Int => 2,
        Local or SetLocal or SetLocalKeep or Upval or CallOp or TailCall or Apply
            or PopN or TypeP => 1,
        ClosureOp or Native => 3,
        _ => 0,
    };
}
