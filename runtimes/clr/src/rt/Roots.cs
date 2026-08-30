using System.Collections.Generic;

namespace Flint.Rt;

/// Everything the collector must find, ported from `runtime/src/gc.rs`.
///
/// `doc/decisions/0001`: values live in explicit arrays the collector walks,
/// never in host locals. On wasm that was forced -- wasm locals are not
/// scannable. Here it is a CHOICE, and the same one, because it is what makes
/// the collector identical across the three runtimes rather than merely
/// equivalent.
public sealed class Roots {
    /// The interpreter's operand stack. `StackTop` is the live prefix; nothing
    /// above it is scanned.
    public long[] Stack = new long[1024];
    public int StackTop;

    /// Values held across an allocation by host code. A value in a CLR local
    /// does not survive a collection -- the collector cannot see it and cannot
    /// update it -- so anything live across an `Alloc` goes here.
    public long[] Shadow = new long[256];
    public int ShadowTop;

    /// The intern tables. WEAK, and scanned by the collector rather than
    /// traced: an entry whose value died is dropped, which is what lets every
    /// short string and keyword be interned without the table being a leak.
    public readonly Interns[] interns = Interns.Tables();

    public long[] Globals = System.Array.Empty<long>();
    public long[] Consts = System.Array.Empty<long>();
    public long[] Singletons = System.Array.Empty<long>();

    /// Old objects holding a young pointer. An old object pointing at a young
    /// one MUST be in here, or the young one is never traced, dies, and leaves
    /// a stale pointer in something still live.
    public readonly List<long> Remembered = new();

    public void VPush(long v) {
        if (StackTop == Stack.Length) System.Array.Resize(ref Stack, Stack.Length * 2);
        Stack[StackTop++] = v;
    }

    public long VPop() => Stack[--StackTop];

    /// Root `v` and return its index. Read it back with `R(i)` AFTER any
    /// allocation -- that is the whole discipline, and reading the host local
    /// instead is the bug this exists to prevent.
    public int Push(long v) {
        if (ShadowTop == Shadow.Length) System.Array.Resize(ref Shadow, Shadow.Length * 2);
        Shadow[ShadowTop] = v;
        return ShadowTop++;
    }

    public long R(int i) => Shadow[i];
    public void SetR(int i, long v) => Shadow[i] = v;
    public int Mark() => ShadowTop;
    public void PopTo(int n) => ShadowTop = n;

    /// Every root, for the collector to read and REWRITE. One loop in one
    /// place: skipping an array would collect those objects out from under the
    /// program and fail somewhere else, later.
    public delegate long Visitor(long v);

    public void ForEach(Visitor f) {
        for (int i = 0; i < StackTop; i++) Stack[i] = f(Stack[i]);
        for (int i = 0; i < ShadowTop; i++) Shadow[i] = f(Shadow[i]);
        for (int i = 0; i < Globals.Length; i++) Globals[i] = f(Globals[i]);
        for (int i = 0; i < Consts.Length; i++) Consts[i] = f(Consts[i]);
        for (int i = 0; i < Singletons.Length; i++) Singletons[i] = f(Singletons[i]);
    }
}
