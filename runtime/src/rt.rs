//! The runtime context: everything a running flint program owns.
//!
//! `Rt` deliberately holds the collector and the root set as *separate fields*,
//! so `gc.alloc(&mut roots, ..)` borrows both without a cell or a global. That
//! is why there is no `static mut RUNTIME` anywhere in flint, and why the same
//! code is safe to run in several threads on a host during tests.
//!
//! ## The one rule for native code
//!
//! **Any `Value` held in a Rust local across an allocation must be rooted.**
//! Rust locals are invisible to the collector; an allocation can move the object
//! out from under them. `Rt::push`/`Rt::r`/`Rt::pop_to` are the shadow stack.
//! `rooted!` wraps the common shape.

use alloc::vec::Vec;

use crate::gc::Roots;
use crate::obj::*;
use crate::value::{Value, INLINE_MAX, NIL};

pub const SING_EMPTY_LIST: usize = 0;
pub const SING_EMPTY_VEC: usize = 1;
pub const SING_EMPTY_MAP: usize = 2;
pub const SING_EMPTY_SET: usize = 3;
/// The scheduler state (`conc::Sched`), or nil in a program that never spawns a
/// thread or opens a port. It lives in `singletons` -- which the collector
/// already traces -- so that N thread stacks become roots with **no new code in
/// the collector at all**: a thread is a heap object whose slots hold its saved
/// value stack, and tracing it is the ordinary Vals walk.
pub const SING_SCHED: usize = 4;
/// The current dynamic bindings, as a map of var-symbol -> value.
///
/// It lives here rather than on the thread so that `binding` costs a
/// single-threaded program nothing at all: the scheduler *saves and restores
/// this slot* when it switches threads, which is what makes the discipline
/// per green thread without dragging the scheduler into every program that
/// rebinds a var (`doc/decisions/0005`, section 4).
pub const SING_BINDINGS: usize = 5;
pub const SING_COUNT: usize = 6;

/// Where the last non-ASCII string index left off. See `Rt::str_cursor`.
#[derive(Clone, Copy)]
pub struct StrCursor {
    /// The string's bits, or 0 when the cursor holds nothing.
    pub bits: u64,
    /// The collector epoch those bits were valid in.
    pub epoch: u64,
    /// The code-point index, and the byte offset it sits at.
    pub cp: u32,
    pub byte: u32,
}

impl StrCursor {
    pub fn none() -> StrCursor {
        StrCursor { bits: 0, epoch: 0, cp: 0, byte: 0 }
    }
}

pub struct Rt {
    /// The heap, when this `Rt` is the one that made it.
    ///
    /// A sandbox has ONE heap and may have several executors on it
    /// (`doc/decisions/0028`). The first `Rt` owns it; the rest point at it and
    /// hold `None`, so the heap outlives every executor by construction rather
    /// than by anyone remembering an order.
    owned_heap: Option<alloc::boxed::Box<crate::gc::Heap>>,
    /// The heap, by address, however it is owned.
    heap: core::ptr::NonNull<crate::gc::Heap>,
    /// This executor's slot in the sandbox, once it has one.
    ///
    /// `None` means "the only executor", which is the whole of the wasm build
    /// and the default everywhere: it takes no lock and stages no safepoint,
    /// so a single-threaded sandbox pays nothing for any of this.
    #[cfg(feature = "parallel")]
    exec_id: Option<usize>,
    /// Is this executor inside guest code right now?
    ///
    /// The collector waits for running executors and not for idle ones, so
    /// staging a stop has to know whether the stager is one of the threads it
    /// is counting.
    #[cfg(feature = "parallel")]
    running: bool,
    pub gc: crate::gc::GcPtr,
    pub roots: Roots,
    /// GROWABLE BYTE BUFFERS, owned by the runtime and addressed by index.
    ///
    /// `doc/goals/kin-port.md`'s hole 5: every operation that flattens a tree
    /// needs somewhere to put the bytes, and each runtime reached for its own
    /// host type -- `Vec<u8>`, `ByteArrayOutputStream`, `MemoryStream`. Those
    /// have nothing in common a generated source could name, so the operations
    /// that used one could not be written once.
    ///
    /// A buffer HELD BY THE RUNTIME and named by an index has: an index is an
    /// integer in all three, the lifetime is the runtime's rather than a
    /// borrow's, and it is the same shape the shadow stack already uses for
    /// exactly the same reason. `sink_open`/`sink_close` are `mark`/`pop_to`.
    pub(crate) sinks: alloc::vec::Vec<alloc::vec::Vec<u8>>,
    /// TREE WALKS IN PROGRESS, owned by the runtime and addressed by index.
    ///
    /// The sink's sibling, and for the same reason: comparing or hashing two
    /// trees leaf by leaf needs an explicit stack, and `Vec<(Value, u32)>`,
    /// `ArrayDeque<long[]>` and `Stack<(long, int)>` have nothing in common a
    /// generated source could name. An index does.
    ///
    /// Each entry is a node and how many of its children have been taken.
    pub(crate) walks: alloc::vec::Vec<alloc::vec::Vec<(crate::value::Value, u32)>>,
    /// The in-flight thrown value, or `nil`. Native builtins signal failure by
    /// setting this and returning `nil`; the VM checks it after every call.
    pub thrown: Value,
    /// The loaded program: bytecode, function table, native imports.
    pub image: crate::gc::SandboxRef<crate::vm::Image>,
    /// How the decoder turns a `K_PORT` into a handle -- **an indirection on
    /// purpose, and it is a SIZE decision** (`doc/decisions/0027`).
    ///
    /// A port arriving in a message must be interned-or-minted, which is
    /// `install_bridge_port`, which reaches the scheduler, the ring, the event
    /// queue and the port registry. Calling it straight from `codec.rs` made
    /// all of that reachable from `flint_call` -- an unconditional export --
    /// and so linked it into EVERY module: a pure one with no threads and no
    /// ports grew 34,519 bytes, from 300,801 to 335,320, which is precisely the
    /// rule `test/threads.clj` exists to hold ("none of this may grow a pure
    /// module").
    ///
    /// Through a pointer that only `ensure_sched` sets, the chain is reachable
    /// only from a program that already has a scheduler -- and a program with
    /// no ports can never be handed one, because a port arrives over a bridge
    /// and it has none. `None` therefore means "refuse", not "not yet".
    pub bridge_hook: Option<fn(&mut Rt, i64) -> Value>,
    /// Interpreter frames. Clojure recursion uses this, not the Rust stack, so
    /// deep recursion fails with a catchable StackOverflowError instead of
    /// smashing the wasm stack.
    pub frames: alloc::vec::Vec<crate::vm::Frame>,
    pub handlers: alloc::vec::Vec<crate::vm::Handler>,
    /// Host-only builtin registry. On wasm the builtins are reached solely
    /// through the wasm table, which is what lets `--gc-sections` drop the ones
    /// a program never calls; a static table here would keep them all alive.
    #[cfg(not(target_arch = "wasm32"))]
    pub host_natives: crate::gc::SandboxRef<alloc::vec::Vec<crate::vm::NativeFn>>,
    /// Work done: one per dispatched bytecode instruction, plus what natives
    /// charge for work that is not O(1) (`doc/decisions/0009`).
    ///
    /// This is **gas**, and the point of it is that it is deterministic. A
    /// wall-clock timeout bounds *time* and varies with machine load; this
    /// bounds *work* and does not, so "did this candidate hang?" becomes a
    /// reproducible fact rather than a flaky one.
    /// An uncaught throw unwound out of compiled code. The interpreter's own
    /// arms answer this by returning from `run`; compiled code cannot, so it
    /// says so here (`doc/decisions/0013`).
    #[cfg(feature = "aot")]
    pub aot_unwound_out: bool,
    /// How many compiled frames are live on the WASM stack right now.
    #[cfg(feature = "aot")]
    pub aot_depth: u32,
    /// Bumped by every unwind. A nested compiled call cannot use the frame
    /// COUNT to tell "the callee returned" from "a throw was caught": an unwind
    /// to a handler in the caller's own frame truncates back to exactly the
    /// depth the call started at, and compiled code then carried on past the
    /// handler with an unwound stack.
    #[cfg(feature = "aot")]
    pub unwinds: u64,
    /// The next identity for an opaque value (`doc/decisions/0022`). A counter
    /// rather than the address, because the nursery moves objects and an
    /// identity hash that changes under collection makes a map key unfindable.
    pub next_opaque: u64,
    /// A one-entry cursor into the last NON-ASCII string that was indexed.
    ///
    /// Indexing UTF-8 by CODE POINT means walking it, so `nth`/`code-point-at`
    /// cost O(i) -- and every reader in the language walks a string with
    /// exactly that, so every reader was O(n^2). One `ä` in a 115 KB EDN
    /// document made `clojure.edn/read-string` 190x slower than the same
    /// document with the `ä` replaced by an `x`: 5 375 ms against 119 ms, and
    /// quadrupling each time the input doubled.
    ///
    /// A cursor makes SEQUENTIAL indexing O(1) amortised, which is the access
    /// pattern that was quadratic. Random access is unchanged.
    ///
    /// Keyed on the `epoch` as well as the value, because a copying collector
    /// moves objects and can put a DIFFERENT object where this one was --
    /// comparing addresses would be a memo that is silently wrong rather than
    /// merely stale.
    pub str_cursor: StrCursor,
    /// How many host-minted opaque values the last `restore` invalidated. A
    /// counter so a test can assert the sweep SAW them -- a snapshot that
    /// carried no capabilities proves nothing about one that did.
    pub restored_host_opaques: u32,
    /// The `base_depth` the innermost `run` was called with. `parked` needs it
    /// -- a park is illegal when Rust frames are live underneath -- and compiled
    /// code cannot be passed it, so the loop leaves it here.
    #[cfg(feature = "aot")]
    pub run_base: usize,
    pub steps: u64,
    /// Hard budget. 0 means unlimited. Exceeding it is a **catchable error**
    /// carrying what was spent against what was allowed, not a trap.
    pub gas_limit: u64,
    /// Soft budget: the scheduler's time slice. Reaching it means "your turn is
    /// over", not "you have hung". 0 when nothing is preempting.
    pub slice_end: u64,
    /// How many times the gas limit has already fired. The first is a catchable
    /// error with a small grace budget so a `finally` can clean up; a second
    /// means the program caught it and carried on regardless, and that one
    /// escapes every handler -- otherwise a candidate could catch its own
    /// runaway and defeat the gate that exists to catch it.
    pub gas_trips: u32,
    /// Whether the one-off memory grace has been spent.
    pub mem_trips: u32,
    /// `min` of whichever of the two are set, so the hot loop makes **one**
    /// comparison rather than two. Recomputed whenever either changes.
    pub checkpoint: u64,
    /// Out-parameter for CHAMP insert/remove: did the entry count change?
    /// A scratch field rather than a tuple return, because every one of those
    /// returns would otherwise have to be threaded through the rooting dance.
    pub champ_added: bool,
    /// What the current green thread is parked on, or nil. Set by a parking
    /// builtin together with `thrown = PARK`; the VM's existing post-native
    /// check picks it up, so the fast path costs nothing.
    pub park_on: Value,
    /// Set by the concurrency unit the first time a program spawns a thread or
    /// opens a port. `run_program` calls it instead of returning directly.
    /// `None` in every module that never mentions those, which is what keeps
    /// the scheduler out of a pure build.
    pub sched_hook: Option<fn(&mut Rt, Value) -> Value>,
    /// Non-zero when `main` should report something other than "here is your
    /// answer" -- 2 means "I need the host" (`doc/decisions/0005`, section 1).
    pub status: i32,
}

/// Root `$v` for the duration of `$body`, rebinding the name to the (possibly
/// moved) value on each use via `rt.r(idx)`.
#[macro_export]
macro_rules! rooted {
    ($rt:expr, $($name:ident),+ => $body:block) => {{
        let __base = $rt.roots.shadow.len();
        $(let $name = { $rt.roots.shadow.push($name); $rt.roots.shadow.len() - 1 };)+
        let __r = (|| $body)();
        $rt.roots.shadow.truncate(__base);
        __r
    }};
}


/// An `Rt` is ONE executor and may be moved to the thread that will run it.
///
/// Not `Sync`, deliberately: two threads sharing one executor would share its
/// value stack and its frames, which is nonsense. What they share is the HEAP,
/// and that sharing is mediated by the allocation lock and the safepoint
/// (`doc/decisions/0028`) rather than by this impl.
///
/// The raw pointers inside are to the heap and to the shared roots, which
/// outlive every executor by construction: the primary owns the heap in a box
/// and secondaries hold `None`.
#[cfg(feature = "parallel")]
unsafe impl Send for Rt {}

/// Leave the registry on the way out.
///
/// A registered executor is an address the collector will read. Dropping one
/// without saying so leaves the collector walking freed memory, which is not a
/// crash where it happens -- it is a garbage `stack_top` somewhere else.
#[cfg(feature = "parallel")]
impl Drop for Rt {
    fn drop(&mut self) {
        if let Some(id) = self.exec_id.take() {
            if self.running {
                unsafe { (*self.heap.as_ptr()).par.leave() };
            }
            unsafe { (*self.heap.as_ptr()).par.deregister(id) };
        }
    }
}

impl Rt {
    pub fn new() -> Rt {
        Rt::with_heap(2 * 1024 * 1024, 512 * 1024 * 1024)
    }

    pub fn with_heap(nursery: u32, max: u32) -> Rt {
        // Boxed so the two pointers below stay valid when this `Rt` is
        // returned by value. A `Box`'s CONTENTS do not move when the box does,
        // which is the whole reason it is a box.
        let mut heap = alloc::boxed::Box::new(crate::gc::Heap::new(nursery, max));
        let gc = crate::gc::GcPtr(core::ptr::NonNull::from(&mut heap.gc));
        let shared = crate::gc::SharedPtr(core::ptr::NonNull::from(&mut heap.shared));
        let heap_ptr = core::ptr::NonNull::from(&mut *heap);
        let mut rt = Rt::on_heap(Some(heap), heap_ptr, gc, shared);
        rt.roots.shared.singletons = alloc::vec![NIL; SING_COUNT];
        rt.init_singletons();
        rt
    }

    /// An `Rt` on a heap that already exists.
    ///
    /// Shared by `with_heap`, which makes the heap, and `executor`, which
    /// joins one. Everything here beside the four arguments is PER-EXECUTOR
    /// and therefore fresh: the frames, the gas, the thrown slot and the
    /// scheduler's park slot belong to a thread, not to a sandbox.
    fn on_heap(
        owned_heap: Option<alloc::boxed::Box<crate::gc::Heap>>,
        heap: core::ptr::NonNull<crate::gc::Heap>,
        gc: crate::gc::GcPtr,
        shared: crate::gc::SharedPtr,
    ) -> Rt {
        let image = crate::gc::SandboxRef(core::ptr::NonNull::from(unsafe {
            &mut (*heap.as_ptr()).image
        }));
        #[cfg(not(target_arch = "wasm32"))]
        let host_natives = crate::gc::SandboxRef(core::ptr::NonNull::from(unsafe {
            &mut (*heap.as_ptr()).host_natives
        }));
        Rt {
            bridge_hook: None,
            owned_heap,
            image,
            #[cfg(not(target_arch = "wasm32"))]
            host_natives,
            heap,
            #[cfg(feature = "parallel")]
            exec_id: None,
            #[cfg(feature = "parallel")]
            running: false,
            gc,
            roots: Roots::new(shared),
            sinks: alloc::vec::Vec::new(),
            walks: alloc::vec::Vec::new(),
            thrown: NIL,
            frames: alloc::vec::Vec::new(),
            handlers: alloc::vec::Vec::new(),
            #[cfg(feature = "aot")]
            aot_unwound_out: false,
            #[cfg(feature = "aot")]
            aot_depth: 0,
            #[cfg(feature = "aot")]
            unwinds: 0,
            // From 1: a `host-id` of 0 means "guest-minted", so identities and
            // host ids never share the sentinel.
            next_opaque: 1,
            str_cursor: StrCursor::none(),
            restored_host_opaques: 0,
            #[cfg(feature = "aot")]
            run_base: 0,
            steps: 0,
            gas_limit: 0,
            slice_end: 0,
            gas_trips: 0,
            mem_trips: 0,
            checkpoint: u64::MAX,
            champ_added: false,
            park_on: NIL,
            sched_hook: None,
            status: 0,
        }
    }

    /// Have this sandbox's initialisers run? One answer per sandbox, not per
    /// executor and not per call.
    #[inline]
    pub fn started(&self) -> bool {
        unsafe { (*self.heap.as_ptr()).started }
    }
    #[inline]
    pub fn set_started(&mut self, v: bool) {
        unsafe { (*self.heap.as_ptr()).started = v };
    }

    fn init_singletons(&mut self) {
        let a = self.alloc(TY_EMPTY_LIST, 1);
        self.roots.shared.singletons[SING_EMPTY_LIST] = Value::heap(a);
        self.init_vector();
        self.init_map();
        self.init_set();
    }

    // --- shadow roots ------------------------------------------------------

    #[inline]
    pub fn push(&mut self, v: Value) -> usize {
        // Rooting a value that is already stale. A caller that read a `Value`
        // into a Rust local, allocated, and then pushed it lands here -- one
        // step before the write that eventually makes it visible, and while the
        // frame that owns the mistake is still on the stack.
        //
        // The test is two comparisons; everything else is out of line. `push` is
        // inlined at some hundreds of call sites, and carrying the reporting
        // inline cost 51 KB of diagnostics module the first time it was written
        // that way.
        #[cfg(feature = "diagnostics")]
        self.check_push(v);
        self.roots.shadow.push(v);
        self.roots.shadow.len() - 1
    }
    #[cfg(feature = "diagnostics")]
    #[inline(never)]
    fn check_push(&mut self, v: Value) {
        unsafe { crate::gc::STALE_PUSH[3] += 1 };
        if v.is_heap() && !self.gc.in_live_half(v.as_heap()) && self.gc.is_young(v.as_heap()) {
            self.note_stale_push(v.as_heap());
        }
    }

    #[cfg(feature = "diagnostics")]
    #[inline(never)]
    #[cold]
    fn note_stale_push(&mut self, a: crate::mem::Addr) {
        unsafe {
            crate::gc::STALE_PUSH[0] += 1;
            if crate::gc::STALE_PUSH[1] != 0 {
                return;
            }
            crate::gc::STALE_PUSH[1] = a;
            crate::gc::STALE_PUSH[2] = self.gc.stats.minor as crate::mem::Addr;
            // And the whole shadow stack with it, so the frame that owns the
            // mistake is READ OFF rather than inferred. Inferring it from the
            // one bad address is what cost this investigation most of its wrong
            // turns.
            crate::gc::STALE_SHADOW[0] = self.roots.shadow.len() as crate::mem::Addr;
            for (k, x) in self.roots.shadow.iter().take(32).enumerate() {
                let addr = if x.is_heap() { x.as_heap() } else { 0 };
                crate::gc::STALE_SHADOW[k * 2 + 1] = addr;
                crate::gc::STALE_SHADOW[k * 2 + 2] = if addr != 0 {
                    crate::obj::ty(&self.gc.sp, addr) as crate::mem::Addr
                } else {
                    255
                };
            }
        }
    }

    #[inline]
    pub fn r(&self, i: usize) -> Value {
        self.roots.shadow[i]
    }
    #[inline]
    pub fn set_r(&mut self, i: usize, v: Value) {
        self.roots.shadow[i] = v;
    }
    #[inline]
    pub fn mark(&self) -> usize {
        self.roots.shadow.len()
    }
    #[inline]
    pub fn pop_to(&mut self, n: usize) {
        self.roots.shadow.truncate(n);
    }

    // --- allocation --------------------------------------------------------

    /// Allocate, and **raise if it fails**.
    ///
    /// Every constructor in the runtime answers a failed allocation with `nil`.
    /// If nothing turned that into an error the program would carry on with a
    /// `nil` where a value should be and produce a quietly wrong answer, which
    /// is worse than any crash. So the error is set here, once, and the check
    /// the VM already makes after every call finds it.
    /// Allocate, and **flag a failure** -- never let one pass silently.
    ///
    /// Every constructor in the runtime answers a failed allocation with `nil`.
    /// If nothing turned that into an error the program would carry on with a
    /// `nil` where a value should be and produce a quietly wrong answer, which
    /// is worse than any crash. So a sentinel goes into `thrown` here -- three
    /// instructions, and nothing else pulled in -- and the interpreter's cold
    /// path turns it into a real error with numbers in it.
    /// Allocate WITHOUT charging gas, for the runtime's own bookkeeping.
    ///
    /// There is exactly one legitimate use: state the runtime saves on the
    /// program's behalf whose SIZE is a property of the runtime rather than of
    /// the program. A parked thread's saved shadow stack and frame record are
    /// that -- compiled code keeps fewer values live across a call than the
    /// interpreter does, so the same program parks 112 bytes lighter compiled.
    ///
    /// Billing it made `stat_steps` differ by exactly 14 (112/8) between the
    /// two, which broke `test/aot.clj`'s "the same instruction count" -- and
    /// that test is right: gas measures the PROGRAM's work, and a program does
    /// not do more work by being interpreted.
    ///
    /// Nothing a program can ask for goes through here. If a guest can make it
    /// bigger, it must be billed.
    #[inline]
    pub fn alloc_unbilled(&mut self, ty: u8, len: u32) -> crate::mem::Addr {
        #[cfg(feature = "parallel")]
        if self.exec_id.is_some() {
            return self.alloc_shared(ty, len);
        }
        let a = self.gc.alloc(&mut self.roots, ty, len);
        if a == 0 && self.thrown.is_nil() {
            self.thrown = crate::value::OOM;
        }
        a
    }

    #[inline]
    pub fn alloc(&mut self, ty: u8, len: u32) -> crate::mem::Addr {
        // ALLOCATION CHARGES GAS, one unit per 8 bytes -- the same rate
        // `charge_bytes` uses for string work, and for the same reason: eight
        // bytes touched is eight bytes touched whether a string copied them or
        // an allocation initialised them.
        //
        // It is HERE rather than in each builtin because "the builtin that
        // allocates a lot must remember to charge" is a rule that gets
        // forgotten, and had been: the table builtins allocated 49 MB to append
        // 20 000 rows and charged 461 232 gas, LESS than the 741 252 of the bulk
        // path that allocated 3.25 MB. Gas said the expensive one was cheap.
        //
        // A budget that a single call can escape is worse than no budget,
        // because somebody will trust it (`doc/decisions/0009`). One place
        // covers every builtin that exists and every one added later.
        //
        // The COLLECTOR does not come through here -- promotion uses
        // `alloc_old` directly -- which is what keeps gas independent of when a
        // collection ran, as `determinism.rs` requires.
        // Only when the sandbox is COUNTING. `0009` says an unbudgeted sandbox
        // does not count -- the interpreter is instantiated twice so a run with
        // no budget carries no counter at all -- and charging here regardless
        // put 1 231 steps on a sandbox that the SDK asserts reports zero.
        // A charge that appears when nobody asked for a budget is not free: it
        // is the counter the second instantiation exists to avoid.
        if self.counting() {
            self.charge_work((crate::obj::size_for(ty, len) as u64) >> 3);
        }
        #[cfg(feature = "parallel")]
        if self.exec_id.is_some() {
            return self.alloc_shared(ty, len);
        }
        let a = self.gc.alloc(&mut self.roots, ty, len);
        if a == 0 && self.thrown.is_nil() {
            // A failed allocation must not read as `nil` to the program. It
            // used to, and a capped run then carried on and reported a WRONG
            // ANSWER rather than an error (doc/decisions/0009).
            self.thrown = crate::value::OOM;
        }
        a
    }

    /// Take an intern table's lock. A no-op when this is the only executor.
    ///
    /// # Safety of the call site, not of the call
    /// Everything live must be rooted: this can park at a safepoint, and a
    /// `Value` in a Rust local does not survive one.
    #[cfg(feature = "parallel")]
    #[inline]
    pub(crate) fn lock_intern(&mut self, table: usize) {
        if self.exec_id.is_some() {
            unsafe { (*self.heap.as_ptr()).par.lock_intern(table) };
        }
    }

    #[cfg(feature = "parallel")]
    #[inline]
    pub(crate) fn unlock_intern(&mut self, table: usize) {
        if self.exec_id.is_some() {
            unsafe { (*self.heap.as_ptr()).par.unlock_intern(table) };
        }
    }

    #[cfg(not(feature = "parallel"))]
    #[inline(always)]
    pub(crate) fn lock_intern(&mut self, _table: usize) {}

    #[cfg(not(feature = "parallel"))]
    #[inline(always)]
    pub(crate) fn unlock_intern(&mut self, _table: usize) {}

    /// How many executors share this heap.
    #[cfg(feature = "parallel")]
    #[inline]
    pub fn executor_count(&self) -> u32 {
        unsafe { (*self.heap.as_ptr()).par.executors() }
    }

    /// Bracket a run of guest code.
    ///
    /// Between these, this executor polls and a collector may wait for it.
    /// Outside them it polls nothing and nothing waits for it -- its roots are
    /// still scanned, because they are still roots.
    #[cfg(feature = "parallel")]
    pub fn enter_running(&mut self) {
        if self.exec_id.is_some() && !self.running {
            unsafe { (*self.heap.as_ptr()).par.enter() };
            self.running = true;
        }
    }

    #[cfg(feature = "parallel")]
    pub fn leave_running(&mut self) {
        if self.exec_id.is_some() && self.running {
            unsafe { (*self.heap.as_ptr()).par.leave() };
            self.running = false;
        }
    }

    /// Stop here if another executor is staging a collection.
    ///
    /// Called ONLY from the interpreter's checkpoint, where `ip` is written
    /// back and every live value is on the value stack. A thread that stopped
    /// anywhere else would resume holding a stale pointer, because the
    /// collector it stopped for moves objects (`doc/decisions/0028`).
    #[cfg(feature = "parallel")]
    #[inline]
    pub fn safepoint(&mut self) {
        if self.exec_id.is_none() {
            return;
        }
        let par: &crate::par::Parallel = unsafe { &(*self.heap.as_ptr()).par };
        if par.stop_requested() {
            par.park();
        }
    }

    /// Register this `Rt` as an executor on its heap.
    ///
    /// Must be called once the `Rt` is at its final address -- a box, or a
    /// local that will not move -- because what gets registered is the address
    /// of its root stack, and the collector reads it while this thread is
    /// parked. Registering an `Rt` that then moves would hand the collector a
    /// dangling root set, which is the one mistake here that does not announce
    /// itself.
    ///
    /// # Safety
    /// `self` must not move for as long as it stays registered.
    #[cfg(feature = "parallel")]
    pub unsafe fn join_sandbox(&mut self) -> bool {
        if self.exec_id.is_some() {
            return true;
        }
        let par: &crate::par::Parallel = unsafe { &(*self.heap.as_ptr()).par };
        let roots: *mut crate::gc::ExecRoots = &mut self.roots.own;
        match par.register(roots) {
            Some(id) => {
                self.exec_id = Some(id);
                true
            }
            None => false,
        }
    }

    /// A second executor on the SAME heap.
    ///
    /// It shares the heap, the globals, the constants, the intern tables and
    /// the singletons, and gets its own value stack, frames and gas -- which
    /// is the split `doc/decisions/0028` is about.
    ///
    /// Boxed because it registers its own root stack with the collector, and
    /// that address has to stay put.
    ///
    /// # Safety
    /// The returned executor borrows this one's heap and must be dropped
    /// before it.
    #[cfg(feature = "parallel")]
    pub unsafe fn executor(&mut self) -> Option<alloc::boxed::Box<Rt>> {
        // The primary has to be registered too, or a collection it stages
        // would wait for a count that includes it and never reach it.
        unsafe { self.join_sandbox() };

        let mut rt = alloc::boxed::Box::new(Rt::on_heap(
            None,
            self.heap,
            crate::gc::GcPtr(self.gc.0),
            crate::gc::SharedPtr(self.roots.shared.0),
        ));
        rt.sched_hook = self.sched_hook;
        if !unsafe { rt.join_sandbox() } {
            return None;
        }
        Some(rt)
    }

    /// Allocate with other executors on this heap.
    ///
    /// The allocation lock keeps two threads out of the collector's
    /// bookkeeping. The safepoint is staged only when this allocation would
    /// actually collect, because staging one every time would be a
    /// stop-the-world per allocation rather than per collection.
    #[cfg(feature = "parallel")]
    fn alloc_shared(&mut self, ty: u8, len: u32) -> crate::mem::Addr {
        // Taken as a shared reference straight from the pointer, so it does
        // not borrow `self` and the disjoint `self.gc` / `self.roots` borrow
        // below still works. `Parallel` is all atomics, so `&` is enough.
        let par: &crate::par::Parallel = unsafe { &(*self.heap.as_ptr()).par };
        let mine = self.exec_id.unwrap_or(usize::MAX);

        par.lock_alloc();
        let staged = self.gc.would_collect(ty, len);
        if staged {
            par.stage_stop(self.running);
            // Every other executor is stopped now, so their roots can be given
            // to the collector. Built here and dropped after, because a list
            // that outlived the stop would be pointers into threads that have
            // started running again.
            self.roots.shared.others.clear();
            for p in par.parked_roots(mine) {
                self.roots.shared.others.push(crate::gc::ParkedRoots(p));
            }
        }

        let a = self.gc.alloc(&mut self.roots, ty, len);

        if staged {
            self.roots.shared.others.clear();
            par.release_stop();
        }
        par.unlock_alloc();

        if a == 0 && self.thrown.is_nil() {
            self.thrown = crate::value::OOM;
        }
        a
    }

    /// Write a slot, running the generational write barrier.
    ///
    /// This exists so the 131 call sites do not each have to name the
    /// remembered set. The barrier appends to THIS executor's list, which is
    /// what makes it safe to run from several threads at once: the heap write
    /// needs only `&Gc`, and two `&mut Gc` at the same time would be aliasing
    /// UB even where the writes never touched.
    #[inline]
    pub fn set_slot(&mut self, obj: crate::mem::Addr, i: u32, v: Value) {
        self.gc.set_slot(obj, i, v, &mut self.roots.own.remembered);
    }

    #[inline]
    pub fn slot(&self, v: Value, i: u32) -> Value {
        slot(&self.gc.sp, v.as_heap(), i)
    }
    #[inline]
    pub fn set(&mut self, v: Value, i: u32, x: Value) {
        self.set_slot(v.as_heap(), i, x);
    }
    #[inline]
    pub fn ty(&self, v: Value) -> u8 {
        if v.is_heap() {
            ty(&self.gc.sp, v.as_heap())
        } else {
            255
        }
    }
    #[inline]
    pub fn olen(&self, v: Value) -> u32 {
        len(&self.gc.sp, v.as_heap())
    }

    pub fn singleton(&self, i: usize) -> Value {
        self.roots.shared.singletons[i]
    }
    pub fn empty_list(&self) -> Value {
        self.roots.shared.singletons[SING_EMPTY_LIST]
    }
    pub fn empty_vec(&self) -> Value {
        self.roots.shared.singletons[SING_EMPTY_VEC]
    }
    pub fn empty_map(&self) -> Value {
        self.roots.shared.singletons[SING_EMPTY_MAP]
    }
    pub fn empty_set(&self) -> Value {
        self.roots.shared.singletons[SING_EMPTY_SET]
    }

    pub fn collect(&mut self) {
        self.gc.major(&mut self.roots);
    }

    // --- gas ---------------------------------------------------------------

    /// Recompute the single value the interpreter's hot loop compares against.
    #[inline]
    pub fn refresh_checkpoint(&mut self) {
        let a = if self.gas_limit == 0 { u64::MAX } else { self.gas_limit };
        let b = if self.slice_end == 0 { u64::MAX } else { self.slice_end };
        self.checkpoint = if a < b { a } else { b };
    }

    pub fn set_gas_limit(&mut self, limit: u64) {
        self.gas_limit = limit;
        self.gas_trips = 0;
        self.refresh_checkpoint();
    }

    /// Room for a handler to unwind after the budget blew. Small, and granted
    /// once.
    pub const GAS_GRACE: u64 = 64 * 1024;

    pub fn set_slice_end(&mut self, at: u64) {
        self.slice_end = at;
        self.refresh_checkpoint();
    }

    /// Is anything counting? When nothing is, the interpreter runs a loop with
    /// no counter in it at all.
    #[inline]
    pub fn counting(&self) -> bool {
        self.checkpoint != u64::MAX
    }

    /// Charge `n` units of work for something a single bytecode instruction did.
    ///
    /// **This is the half that would quietly not work without it.** Instruction
    /// counting bounds *bytecode*, and a call into a native builtin is one
    /// instruction whatever it does -- one `sort` of a huge vector, one deep
    /// `=`, one big `merge`. A budget that does not bound those does not bound
    /// the thing most worth bounding, and a limit that a single call escapes is
    /// worse than no limit, because somebody will trust it.
    ///
    /// Returns true when the budget is now exhausted, so a native that can stop
    /// part-way may.
    #[inline]
    pub fn charge(&mut self, n: u64) -> bool {
        self.steps = self.steps.saturating_add(n);
        self.counting() && self.steps >= self.checkpoint
    }

    /// Charge for work that cannot stop part-way. Same counter, no return value
    /// to check: the limit trips at the next instruction instead.
    ///
    /// Use this only where the loop is already bounded by something the guest
    /// cannot grow. Where it is not, use `charge_tick`, and charge INSIDE the
    /// loop -- see there for why charging the whole cost up front does not
    /// bound anything.
    #[inline]
    pub fn charge_work(&mut self, n: u64) {
        self.steps = self.steps.saturating_add(n);
    }

    /// How often `charge_tick` looks at the budget. A power of two so the test
    /// is a mask, and small enough that the overshoot -- at most this many
    /// iterations past the limit -- is not worth measuring.
    pub const TICK_MASK: u64 = 63;

    /// Charge one iteration of a loop and say whether to keep going.
    ///
    /// `false` means the budget is gone AND the gas error has already been
    /// thrown: the caller must unwind, not continue.
    ///
    /// **Charge inside the loop, not before it.** Charging `n` up front bills
    /// correctly and bounds NOTHING: `charge_work` only adds to a counter, and
    /// the counter is not examined until the next interpreter instruction -- so
    /// a native that charges a billion and then loops a billion times still
    /// burns a billion iterations of real CPU before anyone looks. A budget a
    /// single call can outrun is worse than no budget, because somebody will
    /// trust it (`doc/decisions/0009`).
    ///
    /// The budget is examined every 64 iterations rather than every one,
    /// because the check is a branch on a hot path and 64 iterations of
    /// overshoot bounds nothing differently.
    /// Charge `n` up front for work whose size is known, and REFUSE it if the
    /// budget cannot cover it.
    ///
    /// This is the better shape whenever `n` is known before the loop runs: it
    /// never begins work it cannot pay for, and costs nothing per iteration. A
    /// tick is for the other case -- walking a seq whose length is not known
    /// until it ends.
    ///
    /// On refusal it charges NOTHING. Billing for work that was declined is how
    /// the first version of this read: every refused operation reported an
    /// overshoot exactly equal to its own estimate, which looks like a runaway
    /// and is the opposite of one.
    #[inline]
    pub fn charge_checked(&mut self, n: u64, where_: &str) -> bool {
        if self.gas_limit != 0 && self.steps.saturating_add(n) >= self.gas_limit {
            self.gas_error(where_);
            return false;
        }
        self.steps = self.steps.saturating_add(n);
        true
    }

    #[inline]
    pub fn charge_tick(&mut self, i: u64, n: u64, where_: &str) -> bool {
        self.steps = self.steps.saturating_add(n);
        if (i & Self::TICK_MASK) != 0 {
            return true;
        }
        if self.gas_limit != 0 && self.steps >= self.gas_limit {
            self.gas_error(where_);
            return false;
        }
        true
    }

    /// Charge for `n` bytes of string work. Scaled so that a byte is not an
    /// instruction -- copying is much cheaper than dispatching -- while keeping
    /// a megabyte of concatenation from costing 1.
    #[inline]
    pub fn charge_bytes(&mut self, n: u32) {
        self.charge_work((n as u64 / 8) + 1);
    }

    /// The error a blown gas budget raises: catchable, and carrying what was
    /// spent against what was allowed, because a host has to be able to tell
    /// "the program is wrong" from "the budget was too small".
    pub fn gas_error(&mut self, where_: &str) -> Value {
        let (spent, limit) = (self.steps, self.gas_limit);
        let thread = {
            let cur = self.current_thread();
            if cur.is_nil() {
                0
            } else {
                self.slot(cur, crate::conc::TH_ID).as_fixnum()
            }
        };
        let msg = alloc::format!(
            "gas limit exceeded: spent {spent} of {limit} (thread {thread}){where_}"
        );
        let base = self.mark();
        let k = self.string("ResourceExhausted");
        let ki = self.push(k);
        let m = self.string(&msg);
        let mi = self.push(m);
        let data = self.empty_map();
        let di = self.push(data);
        for (key, val) in [
            ("spent", spent as i64),
            ("limit", limit as i64),
            ("thread", thread),
        ] {
            let kw = self.keyword(None, key);
            let kwi = self.push(kw);
            let d = self.r(di);
            let kv = self.r(kwi);
            let nd = self.map_assoc(d, kv, Value::fixnum(val));
            self.set_r(di, nd);
            self.pop_to(kwi);
        }
        let (kk, mm, dd) = (self.r(ki), self.r(mi), self.r(di));
        let e = self.ex_info(kk, mm, dd, NIL);
        self.pop_to(base);
        e
    }

    /// A borrowed view of any string value. The `buf` argument exists because an
    /// inline string's bytes live in the `Value` itself and have nowhere else to
    /// be borrowed from.
    ///
    /// The returned `&str` borrows `self`, so the borrow checker will reject any
    /// attempt to hold it across an allocation -- which is exactly the bug it
    /// would otherwise be.
    /// A string argument, guaranteed contiguous. Every builtin that reaches for
    /// bytes goes through this, so a rope reaching an operation that has not
    /// been taught about ropes gets flattened rather than misread.
    pub fn string_arg(&mut self, v: Value) -> Value {
        if self.is_rope(v) {
            self.flatten(v)
        } else {
            v
        }
    }

    pub fn as_str<'a>(&'a self, v: Value, buf: &'a mut [u8; INLINE_MAX]) -> Option<&'a str> {
        if v.is_inline_str() {
            core::str::from_utf8(v.inline_bytes(buf)).ok()
        } else if v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_STR {
            core::str::from_utf8(str_bytes(&self.gc.sp, v.as_heap())).ok()
        } else if v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_ROPE {
            // Only if something has already materialised it. This borrows, so it
            // cannot flatten; a caller that needs the bytes calls `flatten`
            // first, and `string_arg` is the one-liner that does.
            let f = self.slot(v, crate::obj::RP_FLAT);
            if f.is_nil() {
                #[cfg(feature = "diagnostics")]
                unsafe {
                    crate::rope::FLATTENS[crate::rope::F_UNFLAT_ASSTR] += 1;
                }
                None
            } else {
                self.as_str(f, buf)
            }
        } else {
            None
        }
    }


}

impl Default for Rt {
    fn default() -> Self {
        Rt::new()
    }
}

/// Scratch buffer type for `as_str`.
pub type SBuf = [u8; INLINE_MAX];
#[inline]
pub fn sbuf() -> SBuf {
    [0u8; INLINE_MAX]
}

/// Convenience: collect a `Vec<Value>` of shadow-root indices.
pub struct Frame(pub usize);
impl Frame {
    pub fn open(rt: &Rt) -> Frame {
        Frame(rt.mark())
    }
    pub fn close(self, rt: &mut Rt) {
        rt.pop_to(self.0)
    }
}

pub type ValVec = Vec<Value>;
