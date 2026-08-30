package com.flint.rt;

/// The runtime half of `doc/decisions/0013`: what compiled code calls back into.
///
/// PORTED VERBATIM from `runtime/src/aot.rs`. That file is about wasm and this
/// one is about JVM bytecode, and almost none of it had to change -- because the
/// thing being compiled is the same machine either way: a flat byte-addressed
/// heap, NaN-boxed 64-bit values, and an operand stack of `long`s that compiled
/// code manipulates directly.
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
/// `aotCall` does exactly what the interpreter's `CALL` does -- push a frame --
/// and returns. The interpreter enters the callee, which may itself be
/// compiled. When it returns, the caller resumes AT THE BLOCK AFTER THE CALL,
/// because the emitter knew that block statically and handed it over.
///
/// Compiled functions calling each other would put Clojure recursion on the
/// HOST stack, which cannot be suspended -- so a green thread could not park at
/// depth and deep recursion would blow the host stack instead of raising a
/// catchable `StackOverflowError`. Both are load-bearing
/// (`doc/decisions/0005`), and they are why the two boundary crossings per call
/// are worth paying.
public final class Aot {

    /// No compiled arity for this frame.
    public static final int NONE = -1;
    /// "Never re-enter compiled code here." Matches the Rust's `AOT_NEVER`.
    public static final int NEVER = -1;
    /// "I do not know which block; ask the arity." The one path that arrives
    /// without one: an unwind picked the handler's target, and only the compiled
    /// arity knows which of its blocks that is.
    public static final int LOOKUP = -1;

    /// What compiled code re-reads after any call back into the runtime.
    ///
    /// The value stack is a `long[]` that `vreserve` REALLOCATES, so compiled
    /// code cannot hold the array across a call any more than the wasm emitter
    /// could hold a base address across one. Same discipline, and the same
    /// reason it exists; only the thing being reloaded differs -- a reference
    /// here, an integer address there.
    public static final class Sync {
        public long[] stack;
        public int top;
        public long[] consts;
        public long[] globals;
    }

    /// One compiled arity: the method to invoke, and the block to resume at for
    /// each re-entry point. The emitter knows every re-entry block statically
    /// and hands it over at the call, so nothing here is ever searched -- except
    /// the two cases that cannot know it, below.
    public static final class Fn {
        /// The compiled body, or null when this arity is not compiled.
        public Compiled body;
        /// Deepest the operand stack gets. Reserved by the interpreter BEFORE it
        /// enters, so a compiled body makes no call on the way in and every push
        /// it contains is an unchecked store.
        public int depth;
        /// `(bytecode offset, block)` for every re-entry point, sorted by
        /// offset. Used only where a resume ip is not already known -- an unwind
        /// into a handler, and a thread restored from a save.
        public int[] pointIp = new int[0];
        public int[] pointBlock = new int[0];

        /// The block to enter at `ip`, or -1 if `ip` is not a re-entry point.
        public int blockAt(int ip) {
            int lo = 0, hi = pointIp.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (pointIp[mid] == ip) return pointBlock[mid];
                if (pointIp[mid] < ip) lo = mid + 1; else hi = mid - 1;
            }
            return -1;
        }
    }

    /// What a generated body looks like from here.
    ///
    /// The four parameters are the ABI `runtime/src/aot.rs` fixes for the wasm
    /// table, minus the sync-block pointer -- which is a field on `Rt` here
    /// rather than a linear-memory address, because a JVM method can simply
    /// read it.
    public interface Compiled {
        void run(Rt rt, int fp, int retTo, int entry);
    }

    /// Everything a crossing can invalidate. The other two -- `consts` and
    /// `globals` -- stop growing when the image finishes loading, so writing
    /// them on every crossing was pure cost.
    static void refresh(Rt rt) {
        Sync s = rt.aotSync;
        s.stack = rt.roots.stack;
        s.top = rt.roots.stackTop;
    }

    /// Exported so the interpreter can call it after IT has changed the stack
    /// under compiled code's feet.
    public static void resync(Rt rt) { refresh(rt); }

    /// Fill the fields that are written once, when an image finishes loading.
    public static void install(Rt rt) {
        Sync s = rt.aotSync;
        s.consts = rt.roots.shared.consts;
        s.globals = rt.roots.shared.globals;
        refresh(rt);
    }

    // --- what compiled code calls -------------------------------------------

    /// `NATIVE`. Runs INSIDE compiled code -- a native is a host call either
    /// way, so there is nothing to gain by leaving. Returns 1 if the caller
    /// must bail.
    public static int aotNative(Rt rt, int idx, int argc, int top,
                                int ip, int block, int nextIp, int nextBlock, int gas) {
        rt.steps += gas;
        Rt.aotNatives++;
        rt.roots.stackTop = top;
        int out = rt.aotNativeAt(idx, argc, ip, block, nextIp, nextBlock);
        refresh(rt);
        return out;
    }

    /// The specialised integer operations, when the fast path did not apply.
    ///
    /// The common case never reaches here: compiled code tests both tags and
    /// does the arithmetic itself. This is a bigint, an overflow, or a result
    /// past the fixnum range.
    public static int aotIntBinop(Rt rt, int opcode, int top,
                                  int ip, int block, int nextIp, int nextBlock, int gas) {
        rt.steps += gas;
        rt.roots.stackTop = top;
        int out = rt.aotIntBinopAt(opcode, ip, block, nextIp, nextBlock);
        refresh(rt);
        return out;
    }

    /// A type predicate. Reads the top of the value stack and returns the boxed
    /// answer; the caller stores it back over the argument.
    ///
    /// No bail protocol, no `refresh` and no gas flush: this allocates nothing
    /// and cannot fail, so nothing the caller has cached can move underneath it.
    /// That is the whole reason it is worth having -- what a predicate costs is
    /// being REACHED, and this is the cheapest way to reach one.
    public static long aotTypeP(Rt rt, int code, int top) {
        long v = rt.roots.stack[top - 1];
        return Val.bool(rt.typeP(code, v));
    }

    /// `RETURN`. Pops the frame and pushes the result where the caller expects
    /// it, exactly as the interpreter's own arm does.
    public static void aotReturn(Rt rt, int top, int gas) {
        rt.steps += gas;
        rt.roots.stackTop = top;
        rt.aotReturnHere();
        refresh(rt);
    }

    /// `CALL`. Completes in place when the callee is not a closure; otherwise
    /// pushes the frame and asks compiled code to leave.
    public static int aotCall(Rt rt, int argc, int top,
                              int ip, int block, int nextIp, int nextBlock, int gas) {
        // `gas + 1`: the chunk did not charge for this CALL, because a chunk
        // that hands its last instruction back leaves the charging to the
        // interpreter -- and this one is NOT handed back. Either it completes
        // here or this function pushes the frame; either way the interpreter
        // never dispatches it.
        rt.steps += gas + 1;
        Rt.aotCalls++;
        rt.roots.stackTop = top;
        int out = rt.aotCallAt(argc, ip, block, nextIp, nextBlock);
        refresh(rt);
        return out;
    }

    /// Leave compiled code. ONE helper covers every exit -- a call, an opcode
    /// the emitter does not inline, a gas trip -- because every way back in is
    /// the same comparison: the interpreter re-enters when `ip` reaches
    /// `resumeIp`.
    ///
    /// `ip` is where the interpreter carries on (the instruction's own address,
    /// so it dispatches it with all its existing logic and none of it
    /// duplicated here); `resumeIp` is where compiled code takes over again.
    public static void aotBail(Rt rt, int top, int ip, int resumeIp, int resumeBlock, int gas) {
        rt.steps += gas;
        Rt.aotBails++;
        rt.roots.stackTop = top;
        if (!rt.frames.isEmpty()) {
            Frame f = rt.frames.get(rt.frames.size() - 1);
            f.ip = ip;
            f.aotIp = resumeIp;
            f.aotBlock = resumeBlock;
        }
        refresh(rt);
    }

    /// A back-edge: flush the gas accumulated since the last exit and say
    /// whether the interpreter's own tick would now fire.
    ///
    /// This is the ONE place a long compiled loop can be preempted, which is
    /// what the deterministic scheduler needs -- and back-edges are a small
    /// fraction of executed instructions, so it is also the cheapest place.
    public static int aotTick(Rt rt, int gas, int top, int ip, int block) {
        // Compiled code pushes straight into the value stack and only tells the
        // runtime where the top is when it calls back. Omitting this handed the
        // interpreter a STALE TOP at a slice trip, and the values above it --
        // one of which was a message on its way to the host -- were not there.
        rt.roots.stackTop = top;
        rt.steps += gas;
        Rt.aotTicks++;
        if (rt.checkpoint != 0 && rt.steps >= rt.checkpoint) {
            if (!rt.frames.isEmpty()) {
                Frame f = rt.frames.get(rt.frames.size() - 1);
                f.ip = ip;
                // NOT a re-entry right here: the interpreter has to reach its
                // own tick for the gas error or the slice hand-over to happen.
                f.aotIp = NEVER;
                f.aotBlock = block;
            }
            return 1;
        }
        return 0;
    }
}
