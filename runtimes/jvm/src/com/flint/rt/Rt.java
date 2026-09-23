package com.flint.rt;

import com._3sln.flint.kgen.rt.Seqwalk;

import com._3sln.flint.kgen.rt.Mapwrite;

import com._3sln.flint.kgen.rt.Mapread;

import com._3sln.flint.kgen.rt.Mapcore;

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
/// capturable -- which is what `DECISIONS.md#snapshots` needs and what
/// `DECISIONS.md#threads-and-ports` needs.
///
/// The frame stack is DATA, so a green thread is a saved copy of it and a
/// snapshot carries it. Neither is bolted on afterwards; both fall out.
public final class Rt {

    // ------------------------------------------------------------- the sink
    //
    // `doc/goals/kin-port.md`'s hole 5. Every operation that flattens a tree
    // needs somewhere to put the bytes, and each runtime reached for its own
    // host type -- `Vec<u8>` there, `ByteArrayOutputStream` here,
    // `MemoryStream` on the CLR. Those have nothing in common a generated
    // source could name, so the operations that used one could not be written
    // once.
    //
    // A buffer HELD BY THE RUNTIME and named by an index has: an index is an
    // integer in all three, the lifetime is the runtime's rather than a
    // borrow's, and it is the same shape the shadow stack already uses for the
    // same reason. `sinkOpen`/`sinkClose` are `mark`/`popTo`.
    private final java.util.ArrayList<java.io.ByteArrayOutputStream> sinks =
        new java.util.ArrayList<>();

    /// CODE-POINT BUFFERS, the same shape as `sinks` and for the same reason:
    /// a generated source names one rather than holding it. See `Rt::cps` in
    /// the Rust, and `kin/codepoints.kin` for why the regex engine stopped
    /// flattening its subject to get these.
    private final java.util.ArrayList<int[]> cpsBuf = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> cpsLen = new java.util.ArrayList<>();

    // -------------------------------------------------------------- the walk
    //
    // The sink's sibling. Comparing or hashing two trees leaf by leaf needs an
    // explicit stack, and `Vec<(Value, u32)>`, an `ArrayDeque` and a
    // `Stack<(long, int)>` have nothing in common a generated source could
    // name. An index does.
    //
    // ONE WALK FOR BOTH TREES: a rope node and a byte-rope node put their
    // children at the same offset and count them the same way, so the only
    // thing that differed was which tag says "this is a node".
    private final java.util.ArrayList<java.util.ArrayList<long[]>> walks =
        new java.util.ArrayList<>();

    public int walkOpen(long v) {
        java.util.ArrayList<long[]> st = new java.util.ArrayList<>();
        st.add(new long[]{v, 0});
        walks.add(st);
        return walks.size() - 1;
    }

    public void walkClose(int w) {
        while (walks.size() > w) walks.remove(walks.size() - 1);
    }

    /// A COPY, so a caller can look ahead without consuming.
    public int walkDup(int w) {
        java.util.ArrayList<long[]> c = new java.util.ArrayList<>();
        for (long[] e : walks.get(w)) c.add(new long[]{e[0], e[1]});
        walks.add(c);
        return walks.size() - 1;
    }

    /// Replace `w`'s position with `src`'s -- what a peek that paid off does.
    public void walkTake(int w, int src) {
        java.util.ArrayList<long[]> c = new java.util.ArrayList<>();
        for (long[] e : walks.get(src)) c.add(new long[]{e[0], e[1]});
        walks.set(w, c);
    }

    public long walkNext(int w) {
        java.util.ArrayList<long[]> st = walks.get(w);
        for (;;) {
            if (st.isEmpty()) return Val.NIL;
            long[] top = st.get(st.size() - 1);
            long node = top[0];
            int i = (int) top[1];
            if (!Bytes.isBrope(this, node) && !Str.isRope(this, node)) {
                st.remove(st.size() - 1);
                return node;
            }
            int kids = Obj.len(gc.sp, Val.asHeap(node)) - Bytes.BB_KIDS;
            if (i >= kids) { st.remove(st.size() - 1); continue; }
            top[1] = i + 1;
            st.add(new long[]{ slot(node, Bytes.BB_KIDS + i), 0 });
        }
    }

    public boolean walkDone(int w) { return walks.get(w).isEmpty(); }

    /// Are `len` bytes at `a` the same as `len` bytes at `b`?
    public boolean runEq(long a, long b, int len) {
        for (int i = 0; i < len; i++) {
            if (gc.sp.readU8(a + i) != gc.sp.readU8(b + i)) return false;
        }
        return true;
    }





    /// Open a code-point buffer and answer its index.
    public int cpsOpen() {
        cpsBuf.add(new int[64]);
        cpsLen.add(0);
        return cpsBuf.size() - 1;
    }

    /// Release `c` and everything opened after it.
    public void cpsClose(int c) {
        while (cpsBuf.size() > c) { cpsBuf.remove(cpsBuf.size() - 1); cpsLen.remove(cpsLen.size() - 1); }
    }

    /// Append one code point, doubling when full.
    public void cpsPut(int c, int v) {
        int[] b = cpsBuf.get(c);
        int n = cpsLen.get(c);
        if (n == b.length) { b = java.util.Arrays.copyOf(b, n * 2); cpsBuf.set(c, b); }
        b[n] = v;
        cpsLen.set(c, n + 1);
    }

    /// How many code points it holds.
    public int cpsLen(int c) { return cpsLen.get(c); }

    /// The buffer's contents, trimmed, and the buffer released.
    ///
    /// The matcher wants an `int[]` and is hand-written on both ports, so this
    /// is where the generated decoder hands over. It costs ONE copy of an
    /// int[]; what it replaces is flattening the whole subject into contiguous
    /// bytes and then decoding that -- a rope materialisation on every regex
    /// call, which native never did.
    public int[] cpsTake(int c) {
        int[] out = java.util.Arrays.copyOf(cpsBuf.get(c), cpsLen.get(c));
        cpsClose(c);
        return out;
    }

    /// The code point at `i`, or 0 past the end -- the TOTAL form; see the Rust.
    public int cpsAt(int c, int i) {
        return i < cpsLen.get(c) ? cpsBuf.get(c)[i] : 0;
    }

    /// Open a buffer and answer its index.
    public int sinkOpen() {
        sinks.add(new java.io.ByteArrayOutputStream());
        return sinks.size() - 1;
    }

    /// Release `s` and everything opened after it.
    public void sinkClose(int s) {
        while (sinks.size() > s) sinks.remove(sinks.size() - 1);
    }

    /// How many bytes are in it.
    public int sinkLen(int s) { return sinks.get(s).size(); }

    /// One byte.
    public void sinkPut(int s, int b) { sinks.get(s).write(b & 0xFF); }

    /// A run of `len` heap bytes from `addr`.
    public void sinkPutRun(int s, long addr, int len) {
        sinks.get(s).write(gc.sp.bytes(addr, len), 0, len);
    }

    /// The raw contents, for host code that wants an array rather than a value.
    public byte[] sinkArray(int s) { return sinks.get(s).toByteArray(); }

    /// How many bytes a LEAF holds, whichever tier it is. See the Rust copy.
    /// The first offset at or after `i` where LEAF `v` holds byte `b`, or
    /// `leafLen(v)` when it holds none.
    ///
    /// `runEq`'s sibling. A search that asked `leafByte` per byte cost about
    /// 9ns a byte because every one is a call that branches on the leaf's
    /// tier, which made `index-of` on a 34 KB rope six times slower than the
    /// flatten it replaced. Here the tier is decided ONCE and the scan is a
    /// tight loop over the backing store.
    public int leafFind(long v, int b, int i) {
        int n = leafLen(v);
        if (i >= n) return n;
        int target = b & 0xFF;
        if (Val.isInlineStr(v)) {
            byte[] bs = Val.inlineBytes(v);
            for (int k = i; k < bs.length; k++) if ((bs[k] & 0xFF) == target) return k;
            return n;
        }
        if (!Val.isHeap(v)) return n;
        long a = Val.asHeap(v);
        long base = Obj.ty(gc.sp, a) == Obj.TY_STR ? a + Obj.STR_DATA : a + Obj.HDR;
        for (int k = i; k < n; k++) if (gc.sp.readU8(base + k) == target) return k;
        return n;
    }

    public int leafLen(long v) {
        if (Val.isInlineStr(v)) return Val.inlineLen(v);
        if (Val.isHeap(v)) return Obj.len(gc.sp, Val.asHeap(v));
        return 0;
    }

    /// Byte `i` of a LEAF, whichever tier it is.
    public int leafByte(long v, int i) {
        if (Val.isInlineStr(v)) {
            byte[] bs = Val.inlineBytes(v);
            return i < bs.length ? (bs[i] & 0xFF) : 0;
        }
        if (!Val.isHeap(v)) return 0;
        long a = Val.asHeap(v);
        long base = Obj.ty(gc.sp, a) == Obj.TY_STR ? a + Obj.STR_DATA : a + Obj.HDR;
        return gc.sp.readU8(base + i);
    }

    /// Append an INLINE string's bytes -- they live in the value, not the
    /// heap, so there is no address to hand to `sinkPutRun`. See the Rust copy.
    public void sinkPutInline(int s, long v) {
        byte[] src = Val.inlineBytes(v);
        sinks.get(s).write(src, 0, src.length);
    }

    /// Copy `len` bytes from the sink at `from` INTO the heap at `addr` --
    /// the other direction from `sinkPutRun`. See the Rust copy.
    public void sinkCopyOut(int s, int from, int len, long addr) {
        byte[] src = sinks.get(s).toByteArray();
        for (int i = 0; i < len; i++) gc.sp.writeU8(addr + i, src[from + i] & 0xFF);
    }

    /// The contents as a byte string. The sink is left alone -- the caller
    /// closes it, because the caller opened it.
    public long sinkBytes(int s) {
        return Bytes.of(this, sinks.get(s).toByteArray());
    }

    /// The contents as a CONTIGUOUS string -- never a tree. See the Rust copy
    /// for why this is not `sinkString`.
    public long sinkContiguous(int s) {
        return Str.contiguous(this, sinks.get(s).toByteArray());
    }

    /// The contents as a string. Invalid UTF-8 answers the empty string.
    public long sinkString(int s) {
        return Str.of(this, new String(sinks.get(s).toByteArray(),
                                       java.nio.charset.StandardCharsets.UTF_8));
    }
    public final Gc gc;
    public final Roots roots = new Roots();

    /// The image: bytecode, constants and the function table.
    public byte[] code = new byte[0];
    public long[] consts = new long[0];
    public FnDef[] fns = new FnDef[0];
    /// Index into `consts` of each var's name, by var index.
    ///
    /// Kept for `flint/var-named` (`DECISIONS.md#vars-is-its-own-grant`), which
    /// resolves a var from a string at run time. Copied out of the image beside
    /// `consts` and `fns`, because this runtime keeps the image's parts rather
    /// than the image.
    public int[] varNames = new int[0];

    public final ArrayList<Frame> frames = new ArrayList<>();
    public final ArrayList<Handler> handlers = new ArrayList<>();

    /// The in-flight exception, or NIL. A distinguished VALUE rather than a
    /// host exception, because a throw has to unwind flint frames and a host
    /// exception would unwind the host stack instead.
    public long thrown = Val.NIL;

    /// How the decoder turns a `K_PORT` into a handle -- an indirection kept
    /// because the RUST needs it, and mirrored so the three runtimes stay one
    /// implementation (`DECISIONS.md#ports-are-the-hosts`).
    ///
    /// There it is a size decision: calling `installBridgePort` straight from
    /// the codec made the scheduler, the ring, the event queue and the port
    /// registry reachable from `flint_call` -- an unconditional export -- and so
    /// linked them into every wasm module. A pure one with no threads and no
    /// ports grew 34,519 bytes, 300,801 to 335,320. This runtime ships whole and
    /// pays nothing either way, but a branch that exists on one runtime and not
    /// the others is how the two ports drifted before.
    ///
    /// `null` means this sandbox has no scheduler, so it has no ports, so it
    /// cannot be being handed one: refuse, rather than "not yet".
    public interface BridgeHook { long install(Rt rt, long hostId); }
    public BridgeHook bridgeHook = null;

    /// The singleton slots, from `rt.rs`. Numbered rather than named fields so
    /// a snapshot can write them as one array.
    public static final int SING_EMPTY_LIST = 0, SING_EMPTY_VEC = 1, SING_EMPTY_MAP = 2,
        SING_EMPTY_SET = 3, SING_SCHED = 4, SING_BINDINGS = 5, SING_COUNT = 6;

    public long steps;

    /// True once this sandbox has more than one executor. Read once per
    /// instruction, so it is a plain field rather than a call.
    public boolean safepoints;

    /// True once a scheduler exists. `run` compares `steps` against
    /// `checkpoint` once per instruction; `Long.MAX_VALUE` there means nothing
    /// is counting, so a program that never spawns never reaches it.
    public boolean schedInstalled;

    /// Whether the control plane has been spawned on the system port.
    ///
    /// One-shot, checked on every `drive`
    /// (`DECISIONS.md#bridges-are-the-only-door`). Not a slot on the scheduler,
    /// because a sandbox with no system port has no scheduler either and the
    /// question is asked before that is known.
    public boolean systemBooted;

    /// This image's initialisers, in order: a program's top-level forms.
    public int[] init = new int[0];

    /// Whether this image's initialisers have run.
    ///
    /// They run ONCE, on first use rather than at load
    /// (`DECISIONS.md#structured-ports` step 5), because a sandbox is now a
    /// thing a host CALLS -- there may be no entry function at all, and running
    /// a program's top-level forms at load would run them for a sandbox nobody
    /// ever calls.
    ///
    /// The conform host also runs them explicitly, which this makes harmless
    /// rather than double: the flag is what makes it idempotent.
    public boolean started;

    public void setSliceEnd(long at) {
        sliceEnd = at;
        refreshCheckpoint();
    }

    /// The single value the interpreter's hot loop compares against: whichever
    /// budget runs out first.
    ///
    /// THIS PORT DID NOT ENFORCE GAS AT ALL. `gasLimit` was a field it wrote
    /// into snapshots and never read, and `checkpoint` carried only the
    /// scheduler's slice -- so the native runtime bounded a runaway program and
    /// these two did not. Conformance could not see it: it diffs ANSWERS, and a
    /// program that is allowed to run forever eventually produces the right one
    /// (`DECISIONS.md#resource-limits`).
    ///
    /// `Long.MAX_VALUE` MEANS NOTHING IS COUNTING, and it is spelled that way
    /// because the native runtime spells it that way. This line used to map
    /// that back to `0`, and one field with two sentinels is a trap rather
    /// than a style difference: `checkpoint` goes into the snapshot, the stamp
    /// a snapshot is refused on is MAGIC and VERSION, and those are identical
    /// on all three runtimes -- so a snapshot written here was ACCEPTED by
    /// native, which read the `0` as "trip on the next instruction" from a
    /// field this runtime meant as "never trip". Measured: a program with
    /// `gasLimit == 0` and `sliceEnd == 0` wrote `checkpoint = 0` where native
    /// wrote `0xffff_ffff_ffff_ffff`, and `livedump` now fails on the crossing
    /// rather than passing it (`DECISIONS.md#resource-limits`).
    ///
    /// Nothing about WHAT IS BILLED changed with the spelling. `MAX` is not
    /// reached, so `steps >= checkpoint` is false exactly where `checkpoint
    /// != 0 && steps >= checkpoint` was false -- and one fewer comparison in
    /// the hot loop, because the guard was only needed to stop `steps >= 0`
    /// firing on every instruction.
    public void refreshCheckpoint() {
        com._3sln.flint.kgen.rt.Rtgas.rtRefreshCheckpoint(this);
    }

    /// Is this runtime billing? Asked where work is charged, and NOT the same
    /// question as "is the checkpoint armed".
    ///
    /// **They differ at exactly one moment, and it was worth 140 steps of the
    /// `conform-hosts` gas row.** This used to be spelled `checkpoint !=
    /// <the never-reached value>` at the charge site, which is what the native
    /// runtime's `counting()` says -- but native NEVER disarms its checkpoint
    /// out of band, so there the two questions have one answer. This runtime
    /// disarms at the courtesy yield, to stop re-entering that branch, and
    /// from then until `drive` re-arms the slice the charge site read
    /// "nothing is being billed" and the scheduler's own allocation went free.
    /// Native billed straight through the same window.
    ///
    /// Measured by splitting the gas probe into its seven parts: the gap was
    /// about 6 steps per 4 096-step slice in six of the seven, and asking this
    /// question instead took the whole-program gap from 174 to 34
    /// (`DECISIONS.md#resource-limits`).
    ///
    /// So the predicate names the thing it means. Preemption is untouched --
    /// the checkpoint still disarms, because that is about where to STOP, not
    /// about what to COUNT.
    public boolean billing() {
        return gasLimit != 0 || sliceEnd != 0;
    }

    public void setGasLimit(long limit) {
        com._3sln.flint.kgen.rt.Rtgas.rtSetGasLimit(this, limit);
    }

    /// Room for a handler to unwind after the budget blew. Small, and granted
    /// once.
    public static final long GAS_GRACE = 64 * 1024;

    /// The error a blown budget raises: CATCHABLE, and carrying what was spent
    /// against what was allowed, because a host has to be able to tell "the
    /// program is wrong" from "the budget was too small".
    public long gasError(String where) {
        long e = makeError("ResourceExhausted",
                "gas limit exceeded: spent " + steps + " of " + gasLimit
                        + (where == null || where.isEmpty() ? "" : " in " + where));
        thrown = e;
        return e;
    }

    /// The rest of the interpreter's state, all of it snapshot-visible.
    ///
    /// These are here rather than spread across the classes that use them for
    /// the reason `DECISIONS.md#snapshots` gives: a snapshot is a COPY of the VM,
    /// so anything that survives a park has to be findable in one place. The
    /// Rust keeps exactly this set on its `Rt`, and the port keeps it in the
    /// same order so the two write byte-identical snapshots.
    public long parkOn = Val.NIL;
    public long gasLimit;
    public long sliceEnd;
    /// INITIALISED, because the sentinel is no longer Java's default.
    ///
    /// `Long.MAX_VALUE` means "nothing is counting" now, matching the native
    /// runtime. A field left at Java's `0` would mean "trip on the next
    /// instruction" -- the old spelling's "off" is the new spelling's
    /// "immediately", which is the sharpest possible way for a respelling to
    /// go wrong.
    public long checkpoint = Long.MAX_VALUE;
    /// How many times a thread's state has been RESTORED -- that is, how many
    /// preemptions this sandbox has served.
    ///
    /// Native counts the same thing as `C_RESTORES` and reads it through
    /// `stat_region`; this port has no counter array, so it is a field. It
    /// exists because "the same program costs the same gas" cannot be asserted
    /// between two runtimes without it: a preemption is billed work, and two
    /// runs that preempt a different number of times cost different gas for
    /// reasons that are not the program's (`DECISIONS.md#resource-limits`).
    public long restores;
    /// Billed allocations by object type: how many, and what they were
    /// charged. The mirror of native's `ALLOC_N` / `ALLOC_GAS`
    /// (`runtime/src/aotstat.rs`), and it exists for the same reason -- a
    /// pricing row that differs by a handful of objects cannot be diagnosed
    /// from a total. Indexed by `TY_*`, 64 slots for 55 types.
    public final long[] allocN = new long[64];
    public final long[] allocGas = new long[64];
    /// Instructions dispatched, separate from `steps`. `steps` is instructions
    /// PLUS charged work, so the two together split a gas total into the part
    /// that is the program running and the part that is work priced by size.
    /// Native answers the same question with `C_INSTRS`.
    public long instrs;
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
    public int restoredHostOpaques;

    /// The builtins this image imports, resolved BY NAME. The slots in an image
    /// belong to the module it was linked against and mean nothing here, which
    /// is what makes an image portable between hosts at all.
    public Builtins.Fn[] natives = new Builtins.Fn[0];
    public String[] nativeNames = new String[0];

    /// Another executor on THIS sandbox's heap (`DECISIONS.md#drivers`).
    ///
    /// One heap, one set of shared roots, one safepoint protocol -- and its own
    /// value stack, shadow stack and remembered set, because those are per
    /// THREAD. The returned runtime is meant to be driven from another host
    /// thread; `enter`/`leave` bracket the stretch where it runs guest code and
    /// can therefore be stopped.
    public Rt executor() {
        Rt e = new Rt(gc, roots.shared);
        // Every executor already registered learns about this one, and it
        // learns about them: the collector walks `shared.others` and each list
        // has to include everybody.
        synchronized (roots.shared) {
            roots.shared.all.add(e.roots);
            boolean many = roots.shared.par.executors() > 1;
            for (Roots r : roots.shared.all) r.owner.safepoints = many;
        }
        return e;
    }

    /// Bracket the stretch where this executor runs GUEST code.
    ///
    /// Between these two calls the thread polls and can be stopped. Outside
    /// them it is registered -- its roots are still scanned -- but the
    /// collector does not wait for it, which is the difference between a
    /// thread "in Java" and one "in native".
    public void enterGuest() {
        if (running) return;
        roots.shared.par.enter();
        running = true;
    }

    public void leaveGuest() {
        if (!running) return;
        running = false;
        roots.shared.par.leave();
    }

    /// Give this executor's slot back. Its roots stop being scanned.
    public void close() {
        leaveGuest();
        synchronized (roots.shared) {
            roots.shared.all.remove(roots);
            roots.shared.par.deregister();
            boolean many = roots.shared.par.executors() > 1;
            for (Roots r : roots.shared.all) r.owner.safepoints = many;
        }
    }

    private Rt(Gc gc, Shared shared) {
        this.gc = gc;
        this.roots.shared = shared;
        this.roots.owner = this;
        this.consts = shared.consts;
        shared.par.register();
    }

    public Rt(long nurseryBytes, long maxHeap) {
        this.gc = new Gc(nurseryBytes, maxHeap);
        this.roots.owner = this;
        roots.shared.consts = consts;
        // The first executor registers too, so `executors()` counts everybody
        // and `stageStop` has a truthful `live`.
        roots.shared.par.register();
        roots.shared.all.add(roots);
        initSingletons();
    }

    /// The empty collections, allocated ONCE per sandbox.
    ///
    /// This port allocated a fresh empty every time `Maps.empty`, `Vec.empty`
    /// or `Sets.empty` was called, where the Rust runtime returns a singleton.
    /// It is a parity defect and not only a performance one: with allocation
    /// charged as gas, the same program billed 143 247 steps here against
    /// 137 207 native, because it allocated 346 928 bytes against 299 024. A
    /// map literal cost 24 bytes more EVERY TIME, which is exactly one empty
    /// array-map, and an empty vector cost three objects.
    ///
    /// A budget that fits on one runtime has to fit on the others, or "verbatim
    /// mirror" stops at the answers (`DECISIONS.md#resource-limits`).
    void initSingletons() {
        long el = alloc(Obj.TY_EMPTY_LIST, 1);
        roots.shared.singletons[SING_EMPTY_LIST] = Val.heap(el);
        roots.shared.singletons[SING_EMPTY_MAP] = Maps.newEmpty(this);
        roots.shared.singletons[SING_EMPTY_VEC] = Vec.newEmpty(this);
        roots.shared.singletons[SING_EMPTY_SET] = Sets.newEmpty(this);
    }


    public static final class FnDef {
        public final Arity[] arities;
        public final int nupvals;
        /// Index into `consts` of this function's name, or -1. KEPT rather than
        /// read-and-discarded: a port refuses to send a function BY NAME, and
        /// "cannot send that" with no subject sends somebody hunting through a
        /// nested structure to find which one.
        public final int name;
        public FnDef(Arity[] arities, int nupvals) { this(arities, nupvals, -1); }
        public FnDef(Arity[] arities, int nupvals, int name) {
            this.arities = arities; this.nupvals = nupvals; this.name = name;
        }
        /// An EXACT fixed arity wins over a variadic one, whatever order they
        /// were written in -- so `(fn ([] :a) ([& xs] xs))` and the same two
        /// clauses reversed both answer `:a` for zero arguments. Taking
        /// whichever came first made the answer depend on source order, which
        /// is the kind of divergence that looks like a program bug.
        ///
        /// Among variadics, the one with the MOST fixed parameters wins:
        /// `([a & xs])` beats `([& xs])` for two arguments, because it is the
        /// more specific match.
        public Arity select(int argc) {
            Arity best = null;
            for (Arity a : arities) {
                if (!a.variadic && argc == a.argc) return a;
                if (a.variadic && argc >= a.argc && (best == null || a.argc > best.argc)) {
                    best = a;
                }
            }
            return best;
        }
    }

    public static final class Arity {
        public final int argc, nlocals, code, len;
        public final boolean variadic;
        /// Index into `Rt.aot`, or `Aot.NONE`. The whole AOT question is one
        /// field on the arity, so `enter` answers it without a lookup keyed on
        /// something the frame does not carry.
        public int aotIdx = Aot.NONE;
        public Arity(int argc, boolean variadic, int nlocals, int code, int len) {
            this.argc = argc; this.variadic = variadic;
            this.nlocals = nlocals; this.code = code; this.len = len;
        }
    }

    public static final class Handler {
        public int frame, stackTop, target, shadow;
    }

    // --- allocation, with the rooting discipline ---------------------------

    /// Allocate. With one executor this is `gc.alloc` and nothing else; with
    /// several it is the whole safepoint protocol (`DECISIONS.md#drivers`).
    ///
    /// The allocation lock keeps two threads out of the collector's
    /// bookkeeping. The safepoint is staged ONLY when this allocation would
    /// actually collect, because staging one every time would be a
    /// stop-the-world per allocation rather than per collection.
    public long alloc(int ty, int len) {
        // ALLOCATION CHARGES GAS, one unit per 8 bytes -- the same rate
        // `chargeBytes` uses, and mirroring `Rt::alloc` in the Rust runtime.
        //
        // Without it the three runtimes bill DIFFERENTLY for the same program:
        // 137 207 steps against 100 410 on an allocation-heavy one, a 37% gap,
        // while a loop-heavy program agreed to 0.07%. "Verbatim mirror" has to
        // mean the counter too, or a budget that fits on one runtime does not
        // fit on another (`DECISIONS.md#resource-limits`).
        //
        // Only when COUNTING: an unbudgeted sandbox does not count, which is
        // what the second interpreter instantiation exists to express.
        if (billing()) {
            long g = Obj.sizeFor(ty, len) >> 3;
            allocN[ty & 63]++;
            allocGas[ty & 63] += g;
            chargeWork(g);
        }
        return allocUnbilled(ty, len);
    }

    /// Allocate WITHOUT charging, for the runtime's own bookkeeping.
    ///
    /// One legitimate use: state the runtime saves on the program's behalf
    /// whose SIZE is a property of the runtime rather than of the program. A
    /// parked thread's saved shadow stack is that -- compiled code keeps fewer
    /// values live across a call than the interpreter does, so billing it made
    /// the same program cost more interpreted than compiled.
    public long allocUnbilled(int ty, int len) {
        // NOTHING MAY ALLOCATE WHILE `thrown` OR `parkOn` HOLDS A HEAP VALUE --
        // see the Rust copy for why. Under the stress flag only, which is
        // where this class of bug shows up and costs nothing otherwise.
        if (gc.stress && (Val.isHeap(thrown) || Val.isHeap(parkOn))) {
            throw new IllegalStateException(
                "flint: allocating while " + (Val.isHeap(thrown) ? "`thrown`" : "`parkOn`")
                + " holds a heap value -- it is not a root");
        }
        Parallel par = roots.shared.par;
        if (par.executors() <= 1) return gc.alloc(roots, ty, len);

        par.lockAlloc();
        boolean staged = gc.wouldCollect(ty, len);
        if (staged) {
            // `running` is not a detail. The target is "every running executor
            // EXCEPT ME", and an allocation can happen outside guest code --
            // loading an image, running initialisers, a host call -- where this
            // thread is not one of them. Getting it wrong either waits for a
            // thread that will never park, or starts collecting with a peer
            // still executing.
            par.stageStop(running);
            // Every other executor is stopped NOW, so their roots can be given
            // to the collector. Built here and dropped after, because a list
            // that outlived the stop would be pointers into threads that have
            // started running again.
            roots.shared.others.clear();
            synchronized (roots.shared) {
                for (Roots r : roots.shared.all) if (r != roots) roots.shared.others.add(r);
            }
        }
        long a;
        try {
            a = gc.alloc(roots, ty, len);
        } finally {
            if (staged) {
                roots.shared.others.clear();
                par.releaseStop();
            }
            par.unlockAlloc();
        }
        return a;
    }

    /// True while this executor is running GUEST code and can be stopped.
    public boolean running;

    public void setSlot(long obj, int i, long v) { gc.setSlot(obj, i, v, roots); }
    public long slot(long v, int i) { return Obj.slot(gc.sp, Val.asHeap(v), i); }

    /// The CLOSED SET of `threads-and-ports`, generated from `kin/tablekind.kin`.
    public long kindOf(long v) { return com._3sln.flint.kgen.rt.Tablekind.kindOf(this, v); }

    /// Slot `i` of a MAP ENTRY, or element `i` of anything else.
    ///
    /// A `seq` over a map yields map entries, but a row may also be written as
    /// `[[:a 1] [:b 2]]`, where the pairs are VECTORS. Rust handled both and
    /// both ports read slot 0 unconditionally, which is a garbage key for the
    /// vector form -- unreachable through `seq` over a map, and a divergence
    /// all the same.
    public long slotOrNth(long v, int i) {
        return Obj.ty(gc.sp, Val.asHeap(v)) == Obj.TY_MAPENTRY ? slot(v, i)
                                                               : Vec.nth(this, v, i, Val.NIL);
    }

    public int mark() { return roots.mark(); }
    /// ROOT `v`, and in a diagnostic build check that it is not already stale.
    ///
    /// The native runtime has had this check since rooting bugs were first
    /// hunted (`DECISIONS.md#a-vec-of-values-is-not-a-root`); the ports had NOTHING equivalent, which
    /// is why a rooting bug here has to be found by bisecting a conformance
    /// suite instead of being named at the moment it happens.
    ///
    /// A caller that read a heap value into a Java local, allocated, and then
    /// pushed it lands here -- one step before the write that makes the
    /// mistake visible, and while the frame that owns it is still on the
    /// stack. That is the whole value of checking at PUSH rather than at use.
    ///
    /// Off by default and behind a system property, because it is two
    /// comparisons on a method called at some hundreds of sites:
    ///
    ///     java -Dflint.stale=1 ...
    public int push(long v) {
        if (STALE_CHECK) checkPush(v);
        return roots.push(v);
    }

    /// ONE SWITCH FOR BOTH PORTS. This was `-Dflint.stale` only, which the CLR
    /// has no equivalent of, so a gate could not turn the check on in both at
    /// once -- and nothing ever turned it on in either.
    public static final boolean STALE_CHECK =
        System.getProperty("flint.stale") != null || System.getenv("FLINT_STALE") != null;
    /// How many stale pushes have been seen, and the first one's address.
    public static int staleCount = 0;
    public static long staleFirst = 0;

    private void checkPush(long v) {
        if (!Val.isHeap(v)) return;
        long a = Val.asHeap(v);
        if (gc.isYoung(a) && !gc.inLiveHalf(a)) {
            if (staleCount == 0) staleFirst = a;
            staleCount++;
            // The stack trace is the point: it names the frame that read the
            // value before allocating.
            new Throwable("STALE PUSH of " + a + " (" + describe(v) + ")").printStackTrace();
        }
    }
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

    /// GENERATED (`kin/closure.kin`). The upvalues are pushed BEFORE the
    /// single allocation and read back out of the roots: a `long[]` is not
    /// walked by the collector, so an upvalue read from it after a collection
    /// points at where an object used to be.
    public long makeClosure(int fnIdx, long[] upvals) {
        return com._3sln.flint.kgen.rt.Closure.makeClosureAt(this, fnIdx, upvals);
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
            // AN EXCEPTION, not a fixnum. This threw `Val.fixnum(-1)` with a
            // note that the real message needed strings, and the note stayed:
            // an arity error was not an exception object at all, so
            // `(catch Exception e ...)` did not catch it and the program died
            // with "the program threw an integer" where native answers
            // `ArityException: wrong number of arguments (0) to fn`.
            //
            // `FnDef.name` is the const index of the function's name and was
            // already kept for a different message, so the subject is here.
            roots.stackTop = calleeAt;
            long nameV = def.name >= 0 && def.name < roots.shared.consts.length
                    ? roots.shared.consts[def.name] : Val.NIL;
            String n = Val.isNil(nameV) ? "fn" : Str.text(this, nameV);
            throwStr("ArityException", "wrong number of arguments (" + argc + ") to " + n);
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
        f.aotIdx = a.aotIdx;
        // ENTRY AT THE TOP is just the first re-entry point, so nothing about
        // starting a frame is special-cased. Leaving this at `NEVER` -- which is
        // what the port did at first -- meant compiled code was never entered at
        // all: every arity compiled, every answer matched, every gas count
        // matched, and not one instruction of it ever ran.
        f.aotIp = a.aotIdx == Aot.NONE ? Aot.NEVER : a.code;
        f.aotBlock = 0;
        frames.add(f);
        return true;
    }

    /// Run until the frame stack is back down to `baseDepth`.
    public long run(int baseDepth) {
        // SAVED AND RESTORED, because `run` re-enters: a delay's thunk and a
        // table fold's callback are called from NATIVE frames that are still
        // holding shadow-stack roots. `unwind` needs to know where this
        // invocation began so it does not jump to a handler on the other side
        // of one. The field existed and nothing ever assigned it.
        int outerBase = runBase;
        runBase = baseDepth;
        try {
            return runLoop(baseDepth);
        } finally {
            runBase = outerBase;
        }
    }

    private long runLoop(int baseDepth) {
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
            // One comparison against a precomputed value. `checkpoint` is
            // `Long.MAX_VALUE` in a program with no scheduler and no gas
            // limit, so the comparison is there and never true.
            // THE SAFEPOINT (`DECISIONS.md#drivers`), and the slice check, at the
            // same place -- because they want the same place. `ip` has been
            // written back and every live value is on the value stack by
            // construction, which is what makes it safe to stop here and
            // nowhere else.
            //
            // One comparison guards both. `checkpoint` is `Long.MAX_VALUE`
            // when nothing is counting, and `safepoints` is false in a sandbox
            // with one executor, so neither test ever fires in a
            // single-threaded program with no scheduler.
            // COMPILED CODE takes over when `ip` reaches the point it named.
            // Every re-entry in the design funnels through this one comparison:
            // an entry, a return from a call, a resumed thread, an unwind into a
            // handler. `aotIp` is `Aot.NEVER` in a program with no compiled
            // arities, so this costs one compare.
            if (f.aotIp == ip && f.aotIdx != Aot.NONE) {
                if (aotEnter(ip)) return Val.NIL;
                if (frames.isEmpty() || frames.size() <= baseDepth) {
                    return roots.stackTop > 0 ? vpop() : Val.NIL;
                }
                continue;
            }
            if (safepoints && roots.shared.par.stopRequested()) {
                f.ip = ip;
                roots.shared.par.park();
            }
            if (steps >= checkpoint) {
                f.ip = ip;
                // WHICH budget fired. One comparison covers both; telling them
                // apart is a cold path.
                if (gasLimit != 0 && steps >= gasLimit) {
                    gasError("");
                    gasTrips++;
                    if (gasTrips > 1) {
                        // It was caught once and the program carried on. A gate
                        // a candidate can catch its way out of is not a gate,
                        // so this one escapes every handler.
                        return Val.NIL;
                    }
                    // Grace, once, so a `finally` can put things back.
                    gasLimit = steps + GAS_GRACE;
                    refreshCheckpoint();
                    if (!unwind()) return Val.NIL;
                    continue;
                }
                // NOT AT A NESTED DEPTH. `baseDepth` is 0 only when this run
                // IS a thread's own run; anything else means host frames sit
                // underneath -- `Seqwalk.force` calling a lazy seq's thunk is
                // the one that happens constantly -- and a Java stack cannot be
                // saved and resumed. Yielding there unwinds through those
                // frames and never comes back to them: `force` never records
                // the forced value, and the value stack is left holding
                // operands the caller has already moved past.
                //
                // The symptom was a call answering `value is not a function
                // (a double, 2 args)` from inside `reduce-seq`, for
                // `(into [] (map inc (range 100)))` and for nothing else in the
                // suite -- because a lazy seq is the only thing that re-enters
                // the interpreter from generated code. It appeared the moment
                // calls began running as green threads
                // (`DECISIONS.md#bridges-are-the-only-door`); nothing before
                // that ran a preemptible thread through a forced thunk.
                //
                // So let this call finish and preempt at the next chance, which
                // is what the native runtime has always done here.
                if (baseDepth != 0) {
                    setSliceEnd(steps + Conc.SLICE);
                    continue;
                }
                // DISARMED, not "not counting". The value says "do not trip
                // again before `drive` re-arms the slice"; it does not say the
                // program has stopped being measured. Under the old spelling
                // those were the same number, which is how the scheduler's own
                // allocation stopped being billed in this window and native's
                // did not (`DECISIONS.md#resource-limits`). It is still the
                // same number today -- `alloc` asks the same question -- but
                // now it is the same number for the same reason on all three.
                checkpoint = Long.MAX_VALUE;
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
            instrs++;

            switch (opcode) {
                case Op.CONST -> { vpush(consts[u16(ip)]); ip += 2; }
                case Op.NIL -> vpush(Val.NIL);
                case Op.TRUE -> vpush(Val.TRUE);
                case Op.FALSE -> vpush(Val.FALSE);
                case Op.INT -> { vpush(Val.fixnum(i16(ip))); ip += 2; }
                case Op.LOCAL -> { vpush(roots.stack[fp + u8(ip)]); ip += 1; }
                case Op.LOCAL_W -> { vpush(roots.stack[fp + u16(ip)]); ip += 2; }
                case Op.SET_LOCAL -> { roots.stack[fp + u8(ip)] = vpop(); ip += 1; }
                case Op.SET_LOCAL_W -> { roots.stack[fp + u16(ip)] = vpop(); ip += 2; }
                case Op.SELF -> vpush(roots.stack[f.retTo]);
                case Op.VAR -> { vpush(roots.shared.globals[u16(ip)]); ip += 2; }
                case Op.SET_VAR -> { roots.shared.globals[u16(ip)] = vpop(); ip += 2; }
                case Op.UPVAL -> { vpush(slot(roots.stack[f.retTo], 1 + u8(ip))); ip += 1; }
                case Op.POP -> roots.stackTop -= 1;
                case Op.DUP -> vpush(roots.stack[roots.stackTop - 1]);
                case Op.JUMP -> ip += 2 + i16(ip);
                case Op.JUMP_IF_FALSE -> { int off = i16(ip); ip += 2; if (!Val.truthy(vpop())) ip += off; }
                // The SPECIALISED integer operations. The compiler emits these
                // only where it proved both operands are integers -- but
                // `^int` means integer and not FIXNUM, so a bigint still
                // answers `int?` and still arrives here. Reading one with
                // `asFixnum` was silently wrong, and `Math.addExact` threw a
                // host exception on overflow instead of promoting.
                case Op.ADD_INT, Op.SUB_INT, Op.MUL_INT, Op.LT_INT,
                     Op.LE_INT, Op.GT_INT, Op.GE_INT, Op.EQ_INT -> {
                    long y = vpop(), x = vpop();
                    boolean fast = Val.isFixnum(x) && Val.isFixnum(y);
                    long v = 0;
                    if (fast) {
                        long p = Val.asFixnum(x), q = Val.asFixnum(y);
                        switch (opcode) {
                            case Op.ADD_INT -> {
                                long r = p + q;
                                if (((p ^ r) & (q ^ r)) < 0 || !Val.fitsFixnum(r)) fast = false;
                                else v = Val.fixnum(r);
                            }
                            case Op.SUB_INT -> {
                                long r = p - q;
                                if (((p ^ q) & (p ^ r)) < 0 || !Val.fitsFixnum(r)) fast = false;
                                else v = Val.fixnum(r);
                            }
                            case Op.MUL_INT -> {
                                long r = p * q;
                                if (p != 0 && (r / p != q || (p == -1 && q == Long.MIN_VALUE))
                                    || !Val.fitsFixnum(r)) fast = false;
                                else v = Val.fixnum(r);
                            }
                            case Op.LT_INT -> v = Val.bool(p < q);
                            case Op.LE_INT -> v = Val.bool(p <= q);
                            case Op.GT_INT -> v = Val.bool(p > q);
                            case Op.GE_INT -> v = Val.bool(p >= q);
                            default -> v = Val.bool(p == q);
                        }
                    }
                    if (fast) { vpush(v); }
                    else {
                        f.ip = ip;
                        long r = intBinopSlow(opcode, x, y);
                        if (failed()) { if (!unwind()) return Val.NIL; continue; }
                        vpush(r);
                    }
                }
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
                        // UNWIND, do not return. A failed `enter` is a THROW --
                        // an arity error, or the frame limit -- and returning
                        // straight out of the loop skips every handler the guest
                        // installed, so `(try ((fn [a] a)) (catch ...))` never
                        // caught one. Native unwinds here and only gives up when
                        // there is nothing to unwind to.
                        if (!enter(callee, calleeAt, argc)) {
                            if (!unwind()) return Val.NIL;
                        }
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
                    // A CALLED VALUE THAT FAILED. `callValue` sets `thrown`
                    // and answers nil, exactly as a builtin does -- and this
                    // checked only `parked()`, so the failure did not unwind.
                    // Execution CONTINUED with the nil, the `try` around the
                    // bad call saw no exception, and the pending throw aborted
                    // an unrelated expression two operations later. Measured:
                    // `(try (me 0) :no-throw (catch ...))` answered `:no-throw`
                    // and a later `(count [1 2 3])` raised instead.
                    if (failed()) {
                        roots.stackTop = calleeAt;
                        f.ip = ip;
                        if (!unwind()) return Val.NIL;
                        continue;
                    }
                    roots.stackTop = calleeAt;
                    vpush(cv);
                }
                case Op.APPLY -> {
                    // `(apply f xs)` in head position (`DECISIONS.md#system-namespaces-and-deps`).
                    //
                    // TWO PATHS, because a park has to be survivable on both and
                    // they survive it differently. A CLOSURE is ENTERED, exactly
                    // as `CALL` does it, so the callee runs on this frame stack
                    // and a park inside it saves like any other. A NATIVE cannot
                    // be entered, so it is called and survives a park by being
                    // RE-EXECUTED -- which needs its operands still in place,
                    // which is why that path copies rather than consuming.
                    int opAt = ip - 1;
                    int argc = u8(ip); ip += 1;
                    f.ip = ip;
                    // stack: callee, a1..a(argc-1), seq
                    int operandsAt = roots.stackTop - argc - 1;
                    long applyCallee = roots.stack[operandsAt];
                    if (isHeapTy(applyCallee, TY_CLOSURE)) {
                        long seq = vpop();
                        int si = push(seq);
                        int spread = 0;
                        setR(si, com._3sln.flint.kgen.rt.Seqwalk.seq(this, r(si)));
                        while (!Val.isNil(r(si))) {
                            // The same tick the Rust runtime charges: a spread
                            // walks a whole sequence under one instruction.
                            if (!chargeTick(spread, 1, "apply")) { popTo(si); break; }
                            vpush(Seqwalk.first(this, r(si)));
                            spread++;
                            setR(si, com._3sln.flint.kgen.rt.Seqwalk.next(this, r(si)));
                        }
                        if (failed()) {
                            popTo(si);
                            roots.stackTop = operandsAt;
                            return Val.NIL;
                        }
                        popTo(si);
                        int total = argc - 1 + spread;
                        int at = roots.stackTop - total - 1;
                        if (!enter(applyCallee, at, total)) {
                            if (!unwind()) return Val.NIL;
                        }
                        continue;
                    }
                    // The native path: copy `callee, a1..` above the operands
                    // and spread above the copy, so rewinding to
                    // `operandsAt + argc + 1` leaves what this instruction
                    // expects to find when it runs again.
                    long seq = roots.stack[roots.stackTop - 1];
                    for (int i = 0; i < argc; i++) vpush(roots.stack[operandsAt + i]);
                    int si = push(seq);
                    int spread = 0;
                    setR(si, com._3sln.flint.kgen.rt.Seqwalk.seq(this, r(si)));
                    while (!Val.isNil(r(si))) {
                        if (!chargeTick(spread, 1, "apply")) { popTo(si); break; }
                        vpush(Seqwalk.first(this, r(si)));
                        spread++;
                        setR(si, com._3sln.flint.kgen.rt.Seqwalk.next(this, r(si)));
                    }
                    if (failed()) {
                        popTo(si);
                        roots.stackTop = operandsAt;
                        return Val.NIL;
                    }
                    popTo(si);
                    int total = argc - 1 + spread;
                    long cv = callValue(roots.stackTop - total - 1, total);
                    if (parked()) {
                        thrown = Val.NIL;
                        if (parkOn == Conc.PARK_YIELD) {
                            roots.stackTop = operandsAt;
                            vpush(Val.NIL);
                        } else {
                            roots.stackTop = operandsAt + argc + 1;
                            f.ip = opAt;
                        }
                        thrown = Val.PARK;
                        return Val.NIL;
                    }
                    // The same check the CALL path needs, on the APPLY path.
                    if (failed()) {
                        roots.stackTop = operandsAt;
                        f.ip = ip;
                        if (!unwind()) return Val.NIL;
                        continue;
                    }
                    roots.stackTop = operandsAt;
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
                        if (!enter(callee, dest, argc)) {
                            if (!unwind()) return Val.NIL;
                        }
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
                    // The same check the CALL path needs, on the TAIL path.
                    if (failed()) {
                        roots.stackTop = calleeAt;
                        f.ip = opAt;
                        if (!unwind()) return Val.NIL;
                        continue;
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
                        long nm = Mapwrite.mapAssoc(this, r(mi), roots.stack[at + 2 * i],
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
                    roots.stackTop = at;
                    if (failed()) {
                        if (parked()) {
                            // A PARKING builtin. Rewind to the instruction and
                            // leave the operands where they are: resuming
                            // RE-EXECUTES the call, which is why such a builtin
                            // must decide to park before it changes anything.
                            //
                            // Except a courtesy yield, which is finished:
                            // rewinding there would re-execute `yield`, which
                            // yields again, for ever, and the scheduler reports
                            // no progress.
                            thrown = Val.NIL;
                            if (parkOn == Conc.PARK_YIELD) {
                                vpush(Val.NIL);
                                thrown = Val.PARK;
                                return Val.NIL;
                            }
                            f.ip = opAt;
                            roots.stackTop = at + argc;
                            thrown = Val.PARK;
                            return Val.NIL;
                        }
                        // A BUILTIN THAT FAILED. It set `thrown` and returned
                        // nil, exactly as the Rust does -- so the throw unwinds
                        // to a `catch` in the program rather than out of the
                        // interpreter as a host exception. A host exception here
                        // is uncatchable by flint code, which is how a port with
                        // a working `try` could still not catch a division by
                        // zero: the failure never entered the flint machinery.
                        f.ip = ip;
                        if (!unwind()) return Val.NIL;
                        continue;
                    }
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
                return throwStr("UnsupportedOperationException",
                    "this runtime does not carry the builtin `"
                    + (idx < nativeNames.length ? nativeNames[idx] : "#" + idx) + "`");
            }
            return fn.apply(this, calleeAt + 1, argc);
        }
        if (Sets.isSet(this, callee)) {
            if (argc < 1) return throwStr("ArityException", "a set takes 1 argument");
            return Sets.get(this, callee, roots.stack[calleeAt + 1], Val.NIL);
        }
        if (Mapcore.isMap(this, callee)) {
            if (argc < 1) return throwStr("ArityException", "a map takes 1 or 2 arguments");
            long dflt = argc >= 2 ? roots.stack[calleeAt + 2] : Val.NIL;
            return Mapread.mapGet(this, callee, roots.stack[calleeAt + 1], dflt);
        }
        // A MAP ENTRY IS A VECTOR here as everywhere else: `vector?` answers
        // true, `nth`, `get`, `conj` and `assoc` all treat it as one, and
        // `([:x :y] 1)` works -- but CALLING one threw, on all three runtimes.
        // The bound is checked here rather than left to the slot read, which
        // has none.
        if (isHeapTy(callee, TY_MAPENTRY)) {
            if (argc < 1) return throwStr("ArityException", "a vector takes 1 argument");
            long k = roots.stack[calleeAt + 1];
            if (!Num.isInt(this, k))
                return throwStr("IllegalArgumentException", "vector index must be an integer");
            long i = Num.i64Of(this, k);
            if (i < 0 || i > 1)
                return throwStr("IndexOutOfBoundsException", "index out of range");
            return slot(callee, (int) i);
        }
        if (isHeapTy(callee, TY_VEC)) {
            if (argc < 1) return throwStr("ArityException", "a vector takes 1 argument");
            long got = Vec.nth(this, callee, (int) Val.asFixnum(roots.stack[calleeAt + 1]), Val.NOT_FOUND);
            return got == Val.NOT_FOUND ? Val.NIL : got;
        }
        // Say WHAT was called, AND WHERE. "value is not a function" with no
        // subject is the least useful message in the runtime, and "object type
        // inline" was barely better -- it named an implementation detail for a
        // value and nothing at all for the call path. Native says
        //
        //     value is not a function (an integer, 1 args) in inner <- main
        //
        // and this now says the same, from the same generated `describe` and
        // the same frame walk.
        return throwStr("ClassCastException",
            "value is not a function (" + describe(callee) + ", " + argc + " args) in "
            + whereAmI());
    }

    /// The frame names, innermost first, for attaching to a runtime error.
    ///
    /// A frame does not cache its closure -- `stack[retTo]` IS it until the
    /// frame returns -- which is the same reason `UPVAL` and `SELF` read it
    /// from there rather than from the frame.
    public String whereAmI() {
        StringBuilder out = new StringBuilder();
        int n = frames.size();
        for (int i = n - 1; i >= 0 && n - i <= 12; i--) {
            String name = "?";
            long c = roots.stack[frames.get(i).retTo];
            if (Val.isHeap(c)) {
                int fnIdx = (int) Val.asFixnum(slot(c, 0));
                if (fnIdx >= 0 && fnIdx < fns.length) {
                    int namec = fns[fnIdx].name;
                    if (namec >= 0 && namec < roots.shared.consts.length) {
                        long v = roots.shared.consts[namec];
                        if (!Val.isNil(v)) name = Str.text(this, v);
                    }
                }
            }
            if (out.length() > 0) out.append(" <- ");
            out.append(name);
        }
        return out.toString();
    }

    /// `get`, for the collections that are ported.
    long lookup(long coll, long k, long dflt) {
        if (Val.isNil(coll)) return dflt;
        if (Mapcore.isMap(this, coll)) return Mapread.mapGet(this, coll, k, dflt);
        if (Sets.isSet(this, coll)) return Sets.get(this, coll, k, dflt);
        // `(:tag x)` and `(:form x)` (`DECISIONS.md#tagged-literals`). Without this the
        // same lookup by two spellings disagrees: `(get x :tag)` answers and
        // `(:tag x)` falls through to the default.
        if (isHeapTy(coll, Obj.TY_TAGGED)) return Builtins.taggedGet(this, coll, k, dflt);
        // A TABLE indexes by ROW and hands back a ref, which materialises
        // nothing (`DECISIONS.md#tables`).
        if (isHeapTy(coll, Obj.TY_TABLE)) {
            if (!Val.isFixnum(k)) return dflt;
            long r = Table.tableRef(this, coll, (int) Val.asFixnum(k));
            return Val.isNil(r) ? dflt : r;
        }
        if (isHeapTy(coll, TY_VEC)) {
            if (!Val.isFixnum(k)) return dflt;
            long got = Vec.nth(this, coll, (int) Val.asFixnum(k), Val.NOT_FOUND);
            return got == Val.NOT_FOUND ? dflt : got;
        }
        return dflt;   // `get` on a non-collection is nil, as Clojure's is
    }

    /// `flint.types/code`'s canonical table, GENERATED from `kin/typep.kin`
    /// so that the three runtimes cannot drift from each other -- which the
    /// comments this replaced record happening twice.
    public boolean typeP(int code, long v) {
        return com._3sln.flint.kgen.rt.Typep.typeP(this, code, v);
    }

    /// What a value IS, for a message. A refusal that says "needs more of the
    /// data structures" without naming the type sends the reader back to a
    /// debugger; naming it is the difference between a report and a shrug.
    public String describe(long v) { return com._3sln.flint.kgen.rt.Nouns.describe(this, v); }

    /// Which slot holds this object's metadata, or -1. Metadata is not part
    /// of equality, so `with-meta` copies and the copy is still `=`.
    /// WHICH slot holds `v`'s metadata, or -1 when it can carry none.
    ///
    /// The `-1` is this method's contract, not the generated one's: `Meta`
    /// answers a PREDICATE and a total accessor, because no slot number is
    /// free to mean "nowhere" -- slot 0 is a real slot for an empty list.
    public int metaSlot(long v) {
        return com._3sln.flint.kgen.rt.Meta.hasMeta(this, v) ? com._3sln.flint.kgen.rt.Meta.metaSlot(this, v) : -1;
    }

    public boolean isHeapTy(long v, int t) {
        return Val.isHeap(v) && ty(gc.sp, Val.asHeap(v)) == t;
    }

    /// GENERATED, as `Seqcore.isSeq`. Kept as a method here because ~20 call
    /// sites say `rt.isSeq(v)` and a receiver is not something a static
    /// import can supply.
    boolean isSeq(long v) { return com._3sln.flint.kgen.rt.Seqcore.isSeq(this, v); }

    /// Sequential, which is WIDER than `isSeq`: a vector and a map entry are
    /// sequential without being seqs. `=` is over this, not over seq-ness.
    /// GENERATED, from `kin/typep.kin`, and asked of `category` there rather
    /// than restated as a tag list.
    public boolean isSequential(long v) {
        return com._3sln.flint.kgen.rt.Typep.isSequential(this, v);
    }


    /// True when a park is in flight. A park is NOT an error: it unwinds the
    /// interpreter the same way, but `settle` reads `parkOn` rather than
    /// `thrown`, and every caller between here and the scheduler must pass it
    /// through untouched rather than treating it as a failure.

    // --- exceptions ---------------------------------------------------------
    //
    // An exception is `[kind, msg, data, cause]` and the KIND IS A STRING:
    // flint has no class hierarchy, so `(catch ClassCastException e ...)` has
    // to match on a flat name. The port carried only `[msg, data, cause]` and
    // answered `:ex-info` to every `flint/ex-kind`, so every catch clause the
    // emitter wrote asked a question the runtime could not answer.

    public static final int EX_KIND = 0, EX_MSG = 1, EX_DATA = 2, EX_CAUSE = 3;

    /// GENERATED, from `kin/exinfo.kin`. The body here was the same algorithm
    /// as native's, written twice by hand: mark, push four, allocate, check
    /// for a failed allocation, fill the slots, pop.
    public static long exInfo(Rt rt, long kind, long msg, long data, long cause) {
        return com._3sln.flint.kgen.rt.Exinfo.exInfo(rt, kind, msg, data, cause);
    }

    /// Build an exception without throwing it. The scheduler needs this: it
    /// hands an error to a PARKED thread, to be raised when that thread next
    /// resumes rather than in whatever thread noticed the problem.
    public long makeError(String kind, String msg) {
        int base = mark();
        int ki = push(Str.of(this, kind));
        long m = Str.of(this, msg);
        long e = exInfo(this, r(ki), m, Val.NIL, Val.NIL);
        popTo(base);
        return e;
    }

    /// Set the pending exception and return nil, which is what a failing
    /// builtin returns.
    public long throwStr(String kind, String msg) {
        thrown = makeError(kind, msg);
        return Val.NIL;
    }

    public boolean isException(long v) { return com._3sln.flint.kgen.rt.Exinfo.isException(this, v); }
    public long exMessage(long e) { return com._3sln.flint.kgen.rt.Exinfo.exMessage(this, e); }
    public long exData(long e) { return com._3sln.flint.kgen.rt.Exinfo.exData(this, e); }
    public long exKind(long e) { return com._3sln.flint.kgen.rt.Exinfo.exKind(this, e); }

    /// Does an exception match a `catch` clause's name?
    ///
    /// The rules are Java's, over flat names rather than classes: `Throwable`
    /// matches everything; `Exception` and `RuntimeException` match everything
    /// that is not an `...Error` -- the distinction a program catching broadly
    /// still wants, so a stack overflow is not swallowed by a `catch Exception`
    /// around a parser; `Error` matches the `...Error`s; anything else is exact.
    public long exMatches(long e, long name) {
        String k = Str.isString(this, exKind(e)) ? Str.text(this, exKind(e)) : "";
        String n = Str.isString(this, name) ? Str.text(this, name) : "";
        boolean isError = k.endsWith("Error");
        boolean hit = switch (n) {
            case "Throwable" -> true;
            case "Exception", "RuntimeException" -> !isError;
            case "Error" -> isError;
            default -> k.equals(n);
        };
        return hit ? Val.TRUE : Val.FALSE;
    }

    // --- opaque values (`DECISIONS.md#opaque-values`) --------------------------------

    /// The next identity to hand out. STORED in the object rather than derived
    /// from its address: the nursery is a copying collector, so an
    /// address-derived hash would change under collection and a value in a map
    /// would stop being findable by the key that put it there.
    /// A one-entry cursor into the last NON-ASCII string that was indexed.
    /// See `Str.byteOfCp` -- this is the state, that is the reason.
    public long cursorBits = 0, cursorEpoch = 0;
    public int cursorCp = 0, cursorByte = 0;

    public long nextOpaque = 1;

    /// The next opaque identity, and step the counter -- see the Rust copy.
    public long takeOpaqueId() { return nextOpaque++; }

    /// `[label, id, host-id]`. Guest code can mint one only with host id 0, and
    /// there is deliberately no builtin that reads an id back -- so a host id is
    /// a thing the HOST wrote and only the host can read.
    /// `#my.ns/thing v` (`DECISIONS.md#tagged-literals`): `[tag, form]`, tag a symbol.
    public long newTagged(long tag, long form) { return com._3sln.flint.kgen.rt.Opaque.newTagged(this, tag, form); }

    public long newOpaque(long label, long hostId) { return com._3sln.flint.kgen.rt.Opaque.newOpaque(this, label, hostId); }

    public boolean isOpaque(long v) { return com._3sln.flint.kgen.rt.Opaque.isOpaque(this, v); }

    /// The host id a capability was issued with, or 0 for a guest-minted one.
    public long opaqueHostId(long v) { return com._3sln.flint.kgen.rt.Opaque.opaqueHostId(this, v); }

    public long opaqueLabel(long v) { return com._3sln.flint.kgen.rt.Opaque.opaqueLabel(this, v); }


    /// Gas for work that is not O(1) (`DECISIONS.md#resource-limits`).
    ///
    /// The counter is meant to be proportional to WORK and reproducible, and
    /// that is a property of the language rather than of one runtime: a port
    /// that dispatches the same opcodes but does not charge for the same scans
    /// answers a different number for the same program, and the number is the
    /// whole point.
    /// Gas added by each of the paths that can raise `steps`, mirroring
    /// native's `G_*` counters. Counting ALL of them is what makes
    /// `steps - instrs` fully attributed; a partial split leaves a remainder,
    /// and a remainder is where a cross-runtime difference hides.
    public long gWork, gTick, gChecked;
    public void chargeWork(long n) { gWork += n; steps += n; }
    /// Gas charged by `chargeBytes` -- the string and byte work share of the
    /// non-allocation total. Native counts the same thing; see
    /// `runtime/src/aotstat.rs`.
    public long chargeBytesGas;
    public void chargeBytes(long n) { long g = (n / 8) + 1; chargeBytesGas += g; chargeWork(g); }

    /// How often `chargeTick` looks at the budget: a power of two so the test
    /// is a mask, small enough that the overshoot is not worth measuring.
    public static final long TICK_MASK = 63;

    /// Charge one iteration of a loop and say whether to keep going. `false`
    /// means the budget is gone AND the gas error is already thrown.
    ///
    /// CHARGE INSIDE THE LOOP, not before it: `chargeWork` only adds to a
    /// counter, and nothing reads that counter until the next interpreter
    /// instruction, so a native that charges a million and then loops a million
    /// times still burns a million iterations (`DECISIONS.md#resource-limits`).
    public boolean chargeTick(long i, long n, String where) {
        gTick += n;
        steps += n;
        if ((i & TICK_MASK) != 0) return true;
        if (gasLimit != 0 && steps >= gasLimit) { gasError(where); return false; }
        return true;
    }

    /// Charge `n` up front for work whose size is known, and REFUSE it if the
    /// budget cannot cover it. Better than a tick whenever `n` is known: it
    /// never begins work it cannot pay for. Charges NOTHING when it refuses.
    public boolean chargeChecked(long n, String where) {
        if (gasLimit != 0 && steps + n >= gasLimit) { gasError(where); return false; }
        gChecked += n;
        steps += n;
        return true;
    }

    /// The out-of-line half of a specialised integer operation: a bigint
    /// operand, an overflow, or a result past the fixnum range.
    ///
    /// Shared with compiled code, which emits the fast path inline and calls
    /// this when the tags say it does not apply.
    public long intBinopSlow(int opcode, long x, long y) {
        switch (opcode) {
            case Op.ADD_INT: return Num.add(this, x, y);
            case Op.SUB_INT: return Num.sub(this, x, y);
            case Op.MUL_INT: return Num.mul(this, x, y);
            default:
                if (!Num.isNumber(this, x) || !Num.isNumber(this, y)) {
                    return throwStr("ClassCastException",
                        "not a number: " + describe(x) + " and " + describe(y));
                }
                int c = Num.cmp(this, x, y);
                return Val.bool(switch (opcode) {
                    case Op.LT_INT -> c < 0;
                    case Op.LE_INT -> c <= 0;
                    case Op.GT_INT -> c > 0;
                    case Op.GE_INT -> c >= 0;
                    default -> c == 0;
                });
        }
    }

    /// Compile every arity the image asked for.
    ///
    /// Called once, after an image loads, when `:optimize [perf]` set the flag.
    /// An arity that cannot be compiled -- an opcode the walk does not know --
    /// simply stays interpreted, which is why this can be COMPLETE from the
    /// first version rather than refusing a whole program over one rare opcode.
    ///
    /// Returns how many arities were compiled.
    public int compileArities(boolean chunkAll) {
        java.util.ArrayList<Aot.Fn> out = new java.util.ArrayList<>();
        for (FnDef d : fns) {
            for (Arity a : d.arities) {
                Aot.Fn fn = AotEmit.compile(this, code, a.code, a.len, chunkAll);
                if (fn == null) continue;
                a.aotIdx = out.size();
                out.add(fn);
            }
        }
        aot = out.toArray(new Aot.Fn[0]);
        Aot.install(this);
        return aot.length;
    }

    // --- AOT (`DECISIONS.md#emit-wasm-instead-of-dispatch`) ------------------------------------------
    //
    // PORTED from `runtime/src/vm.rs`, and it is a port rather than a rewrite
    // because the machine is the same: a flat byte-addressed heap, NaN-boxed
    // 64-bit values, and an operand stack of `long`s compiled code writes
    // directly. Only the instruction encoding differs.

    /// What compiled code re-reads after any crossing.
    /// The image's compiled arities, indexed by `Frame.aotIdx`. Empty when the
    /// image was not built with `:optimize [perf]`, which is the ordinary case
    /// and costs one array read on entry.
    /// Counters, so a claim about where the time goes is measured rather than
    /// argued. Cheap enough to leave in: a static increment on paths that
    /// already cross a boundary.
    public static long aotEntries, aotBails, aotCalls, aotNatives, aotTicks;
    public Aot.Fn[] aot = new Aot.Fn[0];
        public final Aot.Sync aotSync = new Aot.Sync();
    /// An uncaught throw unwound out of compiled code. The interpreter's own
    /// arms answer this by returning from `run`; compiled code cannot, so it
    /// says so here.
    public boolean aotUnwoundOut;
    /// How many compiled frames are live on the HOST stack right now.
    public int aotDepth;
    /// Bumped by every unwind. A nested compiled call cannot use the frame
    /// COUNT to tell "the callee returned" from "a throw was caught": an unwind
    /// to a handler in the caller's own frame truncates back to exactly the
    /// depth the call started at, and compiled code then carried on past the
    /// handler with an unwound stack.
    public long unwinds;
    /// The `baseDepth` the innermost `run` was called with. A park is illegal
    /// when host frames are live underneath, and compiled code cannot be passed
    /// it, so the loop leaves it here.
    public int runBase;

    /// Bounded, because the HOST stack cannot be suspended and cannot be grown.
    /// Past the cap `aotCallAt` hands back, so deep recursion still fails with a
    /// catchable `StackOverflowError` rather than blowing the host's stack.
    static final int AOT_MAX_DEPTH = 48;

    /// Three outcomes, and conflating any two of them is a bug the Rust already
    /// had. See `aotFailed`.
    static final int PARKED_SAVED = 0, PARKED_YIELDED = 1, PARKED_FAILED = 2;

    int aotParked(int opcodeAt, int keepTop, int baseDepth, boolean reexecutable) {
        if (baseDepth != 0) {
            // Host frames are live underneath: a lazy-seq force, a comparator,
            // `map`. There is no continuation to save, so say so plainly rather
            // than corrupting the stack.
            thrown = Val.NIL;
            parkOn = Val.NIL;
            throwStr("IllegalStateException",
                "cannot park here: this call is nested inside native code "
                + "(map, sort, reduce, a lazy seq). Park from a green thread's own code instead.");
            return PARKED_FAILED;
        }
        thrown = Val.NIL;
        if (parkOn == Conc.PARK_YIELD) {
            // A courtesy yield: the call itself is finished, so let it finish.
            // Rewinding would re-execute `yield`, which yields again, for ever.
            return PARKED_YIELDED;
        }
        if (!reexecutable) {
            parkOn = Val.NIL;
            throwStr("IllegalStateException",
                "cannot park here: this native was reached through `apply`, which has already "
                + "spread its arguments onto the stack, so the call cannot be re-executed on "
                + "resume. Call it directly instead.");
            return PARKED_FAILED;
        }
        // Rewind to the instruction itself and leave the operands in place:
        // resuming re-executes the call, which is why a parking builtin must
        // decide to park before it changes anything.
        if (!frames.isEmpty()) frames.get(frames.size() - 1).ip = opcodeAt;
        roots.stackTop = keepTop;
        thrown = Val.PARK;
        return PARKED_SAVED;
    }

    /// A call from compiled code failed. THREE outcomes:
    ///
    /// * A PARK is handled here rather than handed back. Handing it back looks
    ///   tidier -- all the park logic in one place -- but it makes the
    ///   interpreter dispatch the same call a second time, and a parking
    ///   builtin is only re-executable across a RESUME, not twice in a row
    ///   before the host has answered. `open` registered its request twice.
    /// * A COURTESY YIELD has already made the call, so the frame comes back
    ///   AFTER it, and the callee and arguments come off first -- a different
    ///   top from the one a re-execution needs.
    /// * A THROW must not re-execute. It already happened.
    int aotFailed(int ip, int block, int nextIp, int nextBlock,
                  int keepTop, int yieldTop, long r) {
        if (thrown == Val.PARK) {
            if (!frames.isEmpty()) frames.get(frames.size() - 1).aotBlock = block;
            switch (aotParked(ip, keepTop, runBase, true)) {
                case PARKED_SAVED -> aotUnwoundOut = true;
                case PARKED_YIELDED -> {
                    roots.stackTop = yieldTop;
                    vpush(r);
                    if (!frames.isEmpty()) {
                        Frame f = frames.get(frames.size() - 1);
                        f.ip = nextIp;
                        f.aotIp = nextIp;
                        f.aotBlock = nextBlock;
                    }
                    aotUnwoundOut = true;
                }
                default -> { if (!unwind()) aotUnwoundOut = true; }
            }
            return 1;
        }
        if (!unwind()) aotUnwoundOut = true;
        return 1;
    }

    /// `NATIVE`, run from compiled code. A native is a host call either way, so
    /// there is nothing to gain by leaving -- and at a large share of executed
    /// instructions this is the single biggest thing worth keeping inside.
    public int aotNativeAt(int idx, int argc, int ip, int block, int nextIp, int nextBlock) {
        int keepTop = roots.stackTop;
        int base = keepTop - argc;
        Builtins.Fn fn = natives[idx];
        if (fn == null) {
            throwStr("UnsupportedOperationException",
                "this runtime does not carry the builtin `" + nativeNames[idx] + "`");
            return aotFailed(ip, block, nextIp, nextBlock, keepTop, base, Val.NIL);
        }
        long r = fn.apply(this, base, argc);
        roots.stackTop = base;
        if (failed()) return aotFailed(ip, block, nextIp, nextBlock, keepTop, base, r);
        vpush(r);
        return 0;
    }

    /// The out-of-line half of a specialised integer operation. The fast path is
    /// emitted INLINE, and this is where a bigint operand, an overflow, or a
    /// result past the fixnum range ends up. A helper rather than a bail so that
    /// no chunk boundary is needed after arithmetic -- a boundary per arithmetic
    /// instruction is the shape `emit-wasm-instead-of-dispatch` measured and rejected.
    public int aotIntBinopAt(int opcode, int ip, int block, int nextIp, int nextBlock) {
        int keepTop = roots.stackTop;
        int base = keepTop - 2;
        long y = roots.stack[base + 1], x = roots.stack[base];
        long r = intBinopSlow(opcode, x, y);
        roots.stackTop = base;
        if (failed()) return aotFailed(ip, block, nextIp, nextBlock, keepTop, base, r);
        vpush(r);
        return 0;
    }

    /// `CALL`, run from compiled code.
    ///
    /// A callee that is not a closure -- a builtin held in a var, a keyword used
    /// as a function, a map looked up -- completes right here and compiled code
    /// carries on. A callee that IS a closure gets its frame pushed here and
    /// then runs on the HOST stack to a bounded depth.
    public int aotCallAt(int argc, int ip, int block, int nextIp, int nextBlock) {
        int keepTop = roots.stackTop;
        int calleeAt = keepTop - argc - 1;
        long callee = roots.stack[calleeAt];
        if (isHeapTy(callee, Obj.TY_CLOSURE)) {
            // `nextIp`, NOT `ip`. This performs the `enter` itself, so an `ip`
            // still pointing at the CALL would have the interpreter dispatch it
            // a second time when the callee returned.
            if (!frames.isEmpty()) {
                Frame f = frames.get(frames.size() - 1);
                f.ip = nextIp;
                f.aotIp = nextIp;
                f.aotBlock = nextBlock;
            }
            int before = frames.size();
            long unwindsBefore = unwinds;
            if (!enter(callee, calleeAt, argc)) {
                if (!unwind()) aotUnwoundOut = true;
                return 1;
            }
            Frame callf = frames.get(frames.size() - 1);
            if (callf.aotIdx != Aot.NONE && aotDepth < AOT_MAX_DEPTH) {
                Aot.Fn a = aot[callf.aotIdx];
                int cfp = callf.fp, cret = callf.retTo;
                aotReserve(a.depth);
                frames.get(frames.size() - 1).aotIp = Aot.NEVER;
                Aot.resync(this);
                aotDepth++;
                a.body.run(this, cfp, cret, 0);
                aotDepth--;
                // NOT the frame count on its own: an unwind to a handler in this
                // very frame truncates back to exactly the depth the call
                // started at, and compiled code then carried on past the handler
                // with an unwound stack.
                if (frames.size() == before && unwinds == unwindsBefore && !aotUnwoundOut) {
                    return 0;
                }
            }
            return 1;
        }
        long r = callValue(calleeAt, argc);
        if (failed()) {
            return aotFailed(ip, block, nextIp, nextBlock, keepTop, calleeAt, r);
        }
        roots.stackTop = calleeAt;
        vpush(r);
        return 0;
    }

    /// `RETURN`, run from compiled code.
    public void aotReturnHere() {
        long v = vpop();
        Frame f = frames.remove(frames.size() - 1);
        while (handlers.size() > f.handlers) handlers.remove(handlers.size() - 1);
        roots.stackTop = f.retTo;
        vpush(v);
    }

    void aotReserve(int n) { vreserve(n); }

    /// Run compiled code for the top frame, if it is asking to be entered.
    /// Returns true if the interpreter should return from `run` -- an uncaught
    /// throw inside compiled code unwound past every handler.
    boolean aotEnter(int ip) {
        Frame f = frames.get(frames.size() - 1);
        int idx = f.aotIdx, fp = f.fp, retTo = f.retTo, block = f.aotBlock;
        if (idx == Aot.NONE) { f.aotIp = Aot.NEVER; return false; }
        if (block == Aot.LOOKUP) {
            // The one path that arrives without a block: an unwind picked the
            // handler's target, and only the compiled arity knows which of its
            // blocks that is.
            int b = aot[idx].blockAt(ip);
            if (b < 0) { f.aotIp = Aot.NEVER; return false; }
            block = b;
        }
        Aot.Fn a = aot[idx];
        aotEntries++;
        // Reserved HERE rather than by a prologue call, so a compiled body makes
        // no call at all on the way in -- and it is entered once per frame AND
        // once per return-from-call, so a call on that path is not cheap.
        aotReserve(a.depth);
        frames.get(frames.size() - 1).aotIp = Aot.NEVER;
        Aot.resync(this);
        a.body.run(this, fp, retTo, block);
        boolean out = aotUnwoundOut;
        aotUnwoundOut = false;
        return out;
    }

    public boolean parked() { return thrown == Val.PARK; }
    public boolean failed() { return !Val.isNil(thrown); }

    /// Find the innermost handler that can take `thrown`, or false if nothing
    /// can and the whole call must fail.
    ///
    /// The frames ABOVE the handler are dropped, not returned from: an
    /// exception is not a return, and the operands those frames had pushed are
    /// not values anybody wants. `stackTop` and `shadowTop` are restored to
    /// what they were when the handler was installed, which is what makes a
    /// throw out of arbitrarily deep code leave no residue.
    public boolean unwind() {
        // COUNTED, because `aotCallAt` asks whether one happened.
        //
        // The field and its reader were both ported and this increment was not,
        // so the guard's second condition was the constant `true` and the guard
        // degenerated to exactly the frame-count test its own comment warns
        // against. An unwind to a handler in the SAME frame as the call leaves
        // the frame count where it started, so compiled code was told to carry
        // on -- past the handler, with an unwound stack. Two `try` regions in
        // one compiled arity is the smallest program that shows it
        // (`runtimes/conform/aot_try.cljc`); one `try` never reaches the call
        // path that consults this.
        //
        // Unconditional here where the Rust has it under `feature = "aot"`:
        // the ports have no such switch and the counter is a long add.
        unwinds++;
        while (!handlers.isEmpty()) {
            // A HANDLER BELOW THIS `run` IS NOT THIS `run`'S TO JUMP TO.
            // Unwinding past a native frame truncates the shadow stack under
            // it and then returns to code holding indices into what was
            // truncated. `(try @(delay (throw ...)) (catch ...))` read a stale
            // root here and answered `#<unprintable>` on the second deref;
            // native panicked outright. Hand the throw back instead -- the
            // re-entrant callers ask `is-thrown` and clean up their own roots.
            if (handlers.get(handlers.size() - 1).frame < runBase) return false;
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
            Frame hf = frames.get(frames.size() - 1);
            hf.ip = h.target;
            // A handler target is a jump target, so it is a CHUNK START -- but
            // only the compiled arity knows which chunk, and an unwind is the
            // one path that arrives without having been told. The Rust runtime
            // does this and the ports did not, so a `try` inside a compiled
            // arity resumed compiled code at whatever `aotIp` the last bail had
            // registered: the handler ran with the wrong entry point and the
            // value stack came apart, reported as "value is not a function".
            //
            // Nothing caught it because no conformance program had a `try`
            // inside an arity the AOT layer compiles.
            if (hf.aotIdx != Aot.NONE) {
                hf.aotIp = h.target;
                hf.aotBlock = Aot.LOOKUP;
            }
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

    /// Run this image's initialisers, once.
    ///
    /// WITHOUT PREEMPTION. A slice is armed the moment a scheduler exists, and
    /// a yield inside an initialiser would come back with `parkOn` still set;
    /// there is nothing to preempt anyway, because no other thread can be
    /// runnable until the program is initialised. It matters here more than at
    /// load: a CALL runs these on first use, so this now happens with a
    /// scheduler already built and a slice already counting down.
    public boolean ensureStarted() {
        if (started) return true;
        started = true;
        long slice = sliceEnd;
        setSliceEnd(0);
        for (int fn : init) {
            call(makeClosure(fn, new long[0]), new long[0]);
            // A TOP-LEVEL FORM THAT ASKED THE HOST, which is the one thing it
            // may not do. It comes back with `parkOn` set and no way to be
            // resumed: this loop is not re-entrant, so there is no saved
            // position to come back to.
            //
            // `DECISIONS.md#ports-are-the-hosts` says a sandbox that cannot
            // ask is TOLD so rather than parked. Without this the park fell
            // through the `failed()` test below -- a park travels as
            // `thrown == PARK`, so `failed()` is ALREADY true for one -- and
            // the program answered the EMPTY STRING with no reason given.
            // Measured on both ports against the same image native refuses
            // by name (`runtimes/conform-host/initpark.cljc`).
            //
            // NOT GUARDED ON `!failed()`, and that is the difference from the
            // copy in the Rust's `run_program`: this one has to fire for a
            // state where `failed()` is true.
            if (!Val.isNil(parkOn)) {
                parkOn = Val.NIL;
                if (thrown == Val.PARK) thrown = Val.NIL;
                // The stack this thread is parked on is about to be cut back,
                // so nothing may try to resume it -- and the WAITER is the
                // half that bites: the next host answer would otherwise find
                // it and put the thread back to RUNNABLE. Generated from
                // `kin/sched.kin`, which pins exactly that with a wake.
                com._3sln.flint.kgen.rt.Sched.abandonCurrentThread(this);
                throwStr("IllegalStateException",
                         "a top-level form asked the host while the program was still "
                         + "initialising, and cannot wait for the answer there. Move the "
                         + "call into a function the entry reaches.");
            }
            if (failed()) { setSliceEnd(slice); return false; }
        }
        setSliceEnd(slice);
        return true;
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
    /// `call`, with the arguments taken from a CONTIGUOUS RUN of shadow-stack
    /// roots rather than a host array -- see the Rust copy. The array this
    /// avoids was being built once per iteration.
    public long invokeRoots(long f, int base, int n) {
        long[] args = new long[n];
        for (int i = 0; i < n; i++) args[i] = r(base + i);
        return call(f, args);
    }

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
    /// The bare name of a keyword, symbol or string -- what `name` returns.
    /// Generated from `kin/names.kin`.
    public long nameOf(long v) { return com._3sln.flint.kgen.rt.Names.nameOf(this, v); }

}
