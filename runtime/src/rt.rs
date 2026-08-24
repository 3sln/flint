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

use crate::gc::{Gc, Roots};
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

pub struct Rt {
    pub gc: Gc,
    pub roots: Roots,
    /// The in-flight thrown value, or `nil`. Native builtins signal failure by
    /// setting this and returning `nil`; the VM checks it after every call.
    pub thrown: Value,
    /// The loaded program: bytecode, function table, native imports.
    pub image: crate::vm::Image,
    /// Interpreter frames. Clojure recursion uses this, not the Rust stack, so
    /// deep recursion fails with a catchable StackOverflowError instead of
    /// smashing the wasm stack.
    pub frames: alloc::vec::Vec<crate::vm::Frame>,
    pub handlers: alloc::vec::Vec<crate::vm::Handler>,
    /// Host-only builtin registry. On wasm the builtins are reached solely
    /// through the wasm table, which is what lets `--gc-sections` drop the ones
    /// a program never calls; a static table here would keep them all alive.
    #[cfg(not(target_arch = "wasm32"))]
    pub host_natives: alloc::vec::Vec<crate::vm::NativeFn>,
    /// Work done: one per dispatched bytecode instruction, plus what natives
    /// charge for work that is not O(1) (`doc/decisions/0009`).
    ///
    /// This is **gas**, and the point of it is that it is deterministic. A
    /// wall-clock timeout bounds *time* and varies with machine load; this
    /// bounds *work* and does not, so "did this candidate hang?" becomes a
    /// reproducible fact rather than a flaky one.
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

impl Rt {
    pub fn new() -> Rt {
        Rt::with_heap(2 * 1024 * 1024, 512 * 1024 * 1024)
    }

    pub fn with_heap(nursery: u32, max: u32) -> Rt {
        let mut rt = Rt {
            gc: Gc::new(nursery, max),
            roots: Roots::new(),
            thrown: NIL,
            image: Default::default(),
            frames: alloc::vec::Vec::new(),
            handlers: alloc::vec::Vec::new(),
            #[cfg(not(target_arch = "wasm32"))]
            host_natives: alloc::vec::Vec::new(),
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
        };
        rt.roots.singletons = alloc::vec![NIL; SING_COUNT];
        rt.init_singletons();
        rt
    }

    fn init_singletons(&mut self) {
        let a = self.alloc(TY_EMPTY_LIST, 1);
        self.roots.singletons[SING_EMPTY_LIST] = Value::heap(a);
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
    fn note_stale_push(&mut self, a: u32) {
        unsafe {
            crate::gc::STALE_PUSH[0] += 1;
            if crate::gc::STALE_PUSH[1] != 0 {
                return;
            }
            crate::gc::STALE_PUSH[1] = a;
            crate::gc::STALE_PUSH[2] = self.gc.stats.minor as u32;
            // And the whole shadow stack with it, so the frame that owns the
            // mistake is READ OFF rather than inferred. Inferring it from the
            // one bad address is what cost this investigation most of its wrong
            // turns.
            crate::gc::STALE_SHADOW[0] = self.roots.shadow.len() as u32;
            for (k, x) in self.roots.shadow.iter().take(32).enumerate() {
                let addr = if x.is_heap() { x.as_heap() } else { 0 };
                crate::gc::STALE_SHADOW[k * 2 + 1] = addr;
                crate::gc::STALE_SHADOW[k * 2 + 2] = if addr != 0 {
                    crate::obj::ty(&self.gc.sp, addr) as u32
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
    #[inline]
    pub fn alloc(&mut self, ty: u8, len: u32) -> u32 {
        let a = self.gc.alloc(&mut self.roots, ty, len);
        if a == 0 && self.thrown.is_nil() {
            // A failed allocation must not read as `nil` to the program. It
            // used to, and a capped run then carried on and reported a WRONG
            // ANSWER rather than an error (doc/decisions/0009).
            self.thrown = crate::value::OOM;
        }
        a
    }

    #[inline]
    pub fn slot(&self, v: Value, i: u32) -> Value {
        slot(&self.gc.sp, v.as_heap(), i)
    }
    #[inline]
    pub fn set(&mut self, v: Value, i: u32, x: Value) {
        self.gc.set_slot(v.as_heap(), i, x);
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
        self.roots.singletons[i]
    }
    pub fn empty_list(&self) -> Value {
        self.roots.singletons[SING_EMPTY_LIST]
    }
    pub fn empty_vec(&self) -> Value {
        self.roots.singletons[SING_EMPTY_VEC]
    }
    pub fn empty_map(&self) -> Value {
        self.roots.singletons[SING_EMPTY_MAP]
    }
    pub fn empty_set(&self) -> Value {
        self.roots.singletons[SING_EMPTY_SET]
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
    #[inline]
    pub fn charge_work(&mut self, n: u64) {
        self.steps = self.steps.saturating_add(n);
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
    pub fn as_str<'a>(&'a self, v: Value, buf: &'a mut [u8; INLINE_MAX]) -> Option<&'a str> {
        if v.is_inline_str() {
            core::str::from_utf8(v.inline_bytes(buf)).ok()
        } else if v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_STR {
            core::str::from_utf8(str_bytes(&self.gc.sp, v.as_heap())).ok()
        } else {
            None
        }
    }

    pub fn is_string(&self, v: Value) -> bool {
        v.is_inline_str() || (v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_STR)
    }

    /// Byte length of a string value.
    pub fn str_len(&self, v: Value) -> u32 {
        if v.is_inline_str() {
            v.inline_len() as u32
        } else {
            len(&self.gc.sp, v.as_heap())
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
