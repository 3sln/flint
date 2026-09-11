using System.Collections.Generic;

namespace Flint.Rt;

/// Everything the collector must find, ported from `runtime/src/gc.rs`.
///
/// `DECISIONS.md#dispatch`: values live in explicit arrays the collector walks,
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

    /// What this executor shares with every other in the same sandbox: var
    /// slots, constants, singletons and the intern tables. One object, pointed
    /// at by all of them.
    public Shared shared = new Shared();

    /// The runtime these roots belong to, so that registering a new executor
    /// can flip every peer's `safepoints` flag in one place.
    public Rt owner;

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
        // This executor's own.
        for (int i = 0; i < StackTop; i++) Stack[i] = f(Stack[i]);
        for (int i = 0; i < ShadowTop; i++) Shadow[i] = f(Shadow[i]);
        // The sandbox's.
        long[] g = shared.Globals, c = shared.Consts, sg = shared.Singletons;
        for (int i = 0; i < g.Length; i++) g[i] = f(g[i]);
        for (int i = 0; i < c.Length; i++) c[i] = f(c[i]);
        for (int i = 0; i < sg.Length; i++) sg[i] = f(sg[i]);
        // Every OTHER executor in this sandbox (`DECISIONS.md#drivers`). A
        // collection happens with all of them PARKED at a safepoint, so nothing
        // is mutating these while they are walked.
        //
        // Skipping one would not fail here. It would collect that thread's live
        // objects out from under it and fail somewhere else, later, as a
        // corrupted value in code that did nothing wrong.
        foreach (Roots e in shared.others) {
            if (ReferenceEquals(e, this)) continue;
            // A parked executor's roots do not change. If they have, the
            // collector is walking a thread that is still RUNNING -- say so
            // here rather than as an index error four frames down.
            if (e.StackTop > e.Stack.Length) {
                throw new System.InvalidOperationException(
                    "flint: scanning a RUNNING executor: StackTop " + e.StackTop
                    + " past len " + e.Stack.Length);
            }
            for (int i = 0; i < e.StackTop; i++) e.Stack[i] = f(e.Stack[i]);
            for (int i = 0; i < e.ShadowTop; i++) e.Shadow[i] = f(e.Shadow[i]);
        }
    }

    /// Every executor's remembered set, drained together.
    ///
    /// Only correct during a collection, which is the only time every other
    /// executor is stopped. Draining one and not the rest would LOSE
    /// old-to-young edges another thread recorded, and a lost edge is a young
    /// object collected while an old one still points at it.
    public List<long> DrainRemembered() {
        var outv = new List<long>(Remembered);
        Remembered.Clear();
        foreach (Roots e in shared.others) {
            if (ReferenceEquals(e, this)) continue;
            outv.AddRange(e.Remembered);
            e.Remembered.Clear();
        }
        return outv;
    }
}
