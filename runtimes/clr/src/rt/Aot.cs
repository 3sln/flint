namespace Flint.Rt;

/// The runtime half of `doc/decisions/0013`: what compiled code calls back into.
///
/// A LINE-FOR-LINE MIRROR of `runtimes/jvm/src/com/flint/rt/Aot.java`, which is
/// itself a port of `runtime/src/aot.rs`. Almost none of it had to change across
/// either hop, because the thing being compiled is the same machine every time:
/// a flat byte-addressed heap, NaN-boxed 64-bit values, and an operand stack of
/// `long`s that compiled code manipulates directly.
///
/// ## A compiled arity holds nothing of its own
///
/// It manipulates the same value stack the interpreter does. That is the whole
/// reason this is admissible (`doc/decisions/0001`): a design that kept flint
/// values in host locals across an allocation would need a shadow-stack spill
/// around every one. Here there is nothing to spill, which is also why leaving
/// compiled code costs nothing and why re-entering it mid-body is possible.
///
/// ## Why a call does not become a host call
///
/// `AotCall` does exactly what the interpreter's `CALL` does -- push a frame --
/// and returns. Compiled functions calling each other would put Clojure
/// recursion on the HOST stack, which cannot be suspended, so a green thread
/// could not park at depth and deep recursion would blow the host stack instead
/// of raising a catchable `StackOverflowError`. Both are load-bearing
/// (`doc/decisions/0005`).
public static class Aot {

    /// No compiled arity for this frame.
    public const int NONE = -1;
    /// "Never re-enter compiled code here." Matches the Rust's `AOT_NEVER`.
    public const int NEVER = -1;
    /// "I do not know which block; ask the arity." The one path that arrives
    /// without one: an unwind picked the handler's target, and only the compiled
    /// arity knows which of its blocks that is.
    public const int LOOKUP = -1;

    /// What compiled code re-reads after any call back into the runtime.
    ///
    /// The value stack is a `long[]` that `VReserve` REALLOCATES, so compiled
    /// code cannot hold the array across a call any more than the wasm emitter
    /// could hold a base address across one. Same discipline; only the thing
    /// reloaded differs -- a reference here, an integer address there.
    public sealed class Sync {
        public long[] stack = System.Array.Empty<long>();
        public int top;
        public long[] consts = System.Array.Empty<long>();
        public long[] globals = System.Array.Empty<long>();
    }

    /// What a generated body looks like from here. The parameters are the ABI
    /// `runtime/src/aot.rs` fixes, minus the sync-block pointer -- which is a
    /// field on `Rt` rather than a linear-memory address, because a host method
    /// can simply read it.
    public delegate void Compiled(Rt rt, int fp, int retTo, int entry);

    /// One compiled arity: the body to invoke, and the block to resume at for
    /// each re-entry point. The emitter knows every re-entry block statically
    /// and hands it over at the call, so nothing here is ever searched -- except
    /// the two cases that cannot know it.
    public sealed class Fn {
        public Compiled body;
        /// Deepest the operand stack gets. Reserved by the interpreter BEFORE it
        /// enters, so a compiled body makes no call on the way in and every push
        /// it contains is an unchecked store.
        public int depth;
        public int[] pointIp = System.Array.Empty<int>();
        public int[] pointBlock = System.Array.Empty<int>();

        /// The block to enter at `ip`, or -1 if `ip` is not a re-entry point.
        public int BlockAt(int ip) {
            int lo = 0, hi = pointIp.Length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >> 1;
                if (pointIp[mid] == ip) return pointBlock[mid];
                if (pointIp[mid] < ip) lo = mid + 1; else hi = mid - 1;
            }
            return -1;
        }
    }

    /// Everything a crossing can invalidate. The other two -- `consts` and
    /// `globals` -- stop growing when the image finishes loading, so writing
    /// them on every crossing was pure cost.
    public static void Refresh(Rt rt) {
        var s = rt.aotSync;
        s.stack = rt.roots.Stack;
        s.top = rt.roots.StackTop;
    }

    /// Exported so the interpreter can call it after IT has changed the stack
    /// under compiled code's feet.
    public static void Resync(Rt rt) => Refresh(rt);

    /// Fill the fields that are written once, when an image finishes loading.
    public static void Install(Rt rt) {
        var s = rt.aotSync;
        s.consts = rt.roots.shared.Consts;
        s.globals = rt.roots.shared.Globals;
        Refresh(rt);
    }

    // --- what compiled code calls -------------------------------------------

    /// `NATIVE`. Runs INSIDE compiled code -- a native is a host call either
    /// way, so there is nothing to gain by leaving. Returns 1 if the caller
    /// must bail.
    public static int AotNative(Rt rt, int idx, int argc, int top,
                                int ip, int block, int nextIp, int nextBlock, int gas) {
        rt.steps += gas;
        Rt.aotNatives++;
        rt.roots.StackTop = top;
        int outv = rt.AotNativeAt(idx, argc, ip, block, nextIp, nextBlock);
        Refresh(rt);
        return outv;
    }

    /// The specialised integer operations, when the fast path did not apply.
    /// The common case never reaches here: compiled code tests both tags and
    /// does the arithmetic itself.
    public static int AotIntBinop(Rt rt, int opcode, int top,
                                  int ip, int block, int nextIp, int nextBlock, int gas) {
        rt.steps += gas;
        rt.roots.StackTop = top;
        int outv = rt.AotIntBinopAt(opcode, ip, block, nextIp, nextBlock);
        Refresh(rt);
        return outv;
    }

    /// A type predicate. No bail protocol, no `Refresh` and no gas flush: this
    /// allocates nothing and cannot fail, so nothing the caller has cached can
    /// move underneath it. What a predicate costs is being REACHED, and this is
    /// the cheapest way to reach one.
    public static long AotTypeP(Rt rt, int code, int top) {
        long v = rt.roots.Stack[top - 1];
        return Val.Bool(rt.TypeP(code, v));
    }

    /// `RETURN`. Pops the frame and pushes the result where the caller expects
    /// it, exactly as the interpreter's own arm does.
    public static void AotReturn(Rt rt, int top, int gas) {
        rt.steps += gas;
        rt.roots.StackTop = top;
        rt.AotReturnHere();
        Refresh(rt);
    }

    /// `CALL`. Completes in place when the callee is not a closure; otherwise
    /// pushes the frame and asks compiled code to leave.
    public static int AotCall(Rt rt, int argc, int top,
                              int ip, int block, int nextIp, int nextBlock, int gas) {
        // `gas + 1`: the chunk did not charge for this CALL, because a chunk
        // that hands its last instruction back leaves the charging to the
        // interpreter -- and this one is NOT handed back.
        rt.steps += gas + 1;
        Rt.aotCalls++;
        rt.roots.StackTop = top;
        int outv = rt.AotCallAt(argc, ip, block, nextIp, nextBlock);
        Refresh(rt);
        return outv;
    }

    /// Leave compiled code. ONE helper covers every exit -- a call, an opcode
    /// the emitter does not inline, a gas trip -- because every way back in is
    /// the same comparison: the interpreter re-enters when `ip` reaches
    /// `resumeIp`.
    public static void AotBail(Rt rt, int top, int ip, int resumeIp, int resumeBlock, int gas) {
        rt.steps += gas;
        Rt.aotBails++;
        rt.roots.StackTop = top;
        if (rt.frames.Count > 0) {
            var f = rt.frames[rt.frames.Count - 1];
            f.Ip = ip;
            f.AotIp = resumeIp;
            f.AotBlock = resumeBlock;
        }
        Refresh(rt);
    }

    /// A back-edge: flush the gas accumulated since the last exit and say
    /// whether the interpreter's own tick would now fire. This is the ONE place
    /// a long compiled loop can be preempted, which is what the deterministic
    /// scheduler needs.
    public static int AotTick(Rt rt, int gas, int top, int ip, int block) {
        // Compiled code pushes straight into the value stack and only tells the
        // runtime where the top is when it calls back. Omitting this handed the
        // interpreter a STALE TOP at a slice trip.
        rt.roots.StackTop = top;
        rt.steps += gas;
        Rt.aotTicks++;
        if (rt.checkpoint != 0 && rt.steps >= rt.checkpoint) {
                // ONE BACK, because the back-edge instruction is about to be
                // charged a second time. `gas` is the chunk's static count and
                // the back-edge is IN it -- compiled code jumps for itself.
                // Handing the instruction back makes the interpreter's own tick
                // charge it again on the way to the hand-over, and the resumed
                // slice a third time when it dispatches it; the interpreter
                // alone charges it twice. Subtracted AFTER the comparison, so
                // the slice still ends on the same instruction. See the Rust,
                // and `test/aot.clj` for how it was found.
            rt.steps -= 1;
            if (rt.frames.Count > 0) {
                var f = rt.frames[rt.frames.Count - 1];
                f.Ip = ip;
                // NOT a re-entry right here: the interpreter has to reach its
                // own tick for the gas error or the slice hand-over to happen.
                f.AotIp = NEVER;
                f.AotBlock = block;
            }
            return 1;
        }
        return 0;
    }
}
