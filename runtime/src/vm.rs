//! The interpreter.
//!
//! # Why an interpreter at all (doc/decisions/0001)
//!
//! Compiling each Clojure fn to a real wasm function would be much faster — the
//! host JIT does the work and there is no dispatch. It collides with the rooting
//! constraint: **wasm locals are not scannable**, so under AOT every live
//! reference sits where the collector cannot see it, and you need a shadow-stack
//! spill around every allocation site, which hands back most of the win. The
//! interpreter keeps every live value in linear memory, which is exactly what
//! makes "the value stack IS the root set" work. WasmGC exists to close this gap;
//! until we can rely on it, the interpreter is the honest choice.
//!
//! # Why a stack machine
//!
//! Registers would dispatch fewer instructions, and the case is stronger on wasm
//! than in the literature because there is no computed goto — `br_table` is what
//! you get and the branch is unpredictable. Two things outweigh it:
//!
//! * register allocation is real work sitting on the **bootstrap critical path**;
//!   the compiler has to compile itself before anything runs at all;
//! * **a stack drops a reference when it pops; a register slot does not.** Dead
//!   register slots keep objects alive until overwritten, which is floating
//!   garbage that is invisible and miserable to diagnose. That is a GC argument,
//!   not just an engineering one.
//!
//! # Room to fuse
//!
//! Opcodes `0x00..=0x7F` are base instructions and `0x80..=0xFF` are reserved for
//! fused superinstructions, so fusing hot pairs later is not a format break.
//! `bench/dispatch` measures dispatch separately from data-structure cost, so
//! the choice above is a number rather than an opinion.

use alloc::vec::Vec;

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, FALSE, NIL, TRUE};

// --- opcodes ---------------------------------------------------------------

pub mod op {
    pub const NOP: u8 = 0x00;
    pub const CONST: u8 = 0x01; // u16
    pub const NIL: u8 = 0x02;
    pub const TRUE: u8 = 0x03;
    pub const FALSE: u8 = 0x04;
    pub const INT: u8 = 0x05; // i16
    pub const LOCAL: u8 = 0x06; // u8
    pub const LOCAL_W: u8 = 0x07; // u16
    pub const SET_LOCAL: u8 = 0x08; // u8, pops
    pub const UPVAL: u8 = 0x09; // u8
    pub const VAR: u8 = 0x0A; // u16
    pub const SET_VAR: u8 = 0x0B; // u16, pops
    pub const POP: u8 = 0x0C;
    pub const DUP: u8 = 0x0D;
    pub const JUMP: u8 = 0x0E; // i16
    pub const JUMP_IF_FALSE: u8 = 0x0F; // i16, pops
    pub const JUMP_IF_TRUE: u8 = 0x10; // i16, pops
    pub const CALL: u8 = 0x11; // u8 argc
    pub const TAIL_CALL: u8 = 0x12; // u8 argc
    pub const RETURN: u8 = 0x13;
    pub const CLOSURE: u8 = 0x14; // u16 fnidx, u8 nupvals
    pub const NATIVE: u8 = 0x15; // u16 native, u8 argc
    pub const THROW: u8 = 0x16;
    pub const TRY: u8 = 0x17; // u16 handler offset
    pub const POP_HANDLER: u8 = 0x18;
    pub const RETHROW: u8 = 0x19;
    pub const VECTOR: u8 = 0x1A; // u16 n
    pub const MAP: u8 = 0x1B; // u16 n (pairs)
    pub const SET: u8 = 0x1C; // u16 n
    pub const LIST: u8 = 0x1D; // u16 n
    pub const APPLY: u8 = 0x1E; // u8 argc, last is a seq
    pub const JUMP_IF_FALSE_KEEP: u8 = 0x1F; // i16, does not pop when jumping
    pub const JUMP_IF_TRUE_KEEP: u8 = 0x20; // i16
    pub const POP_N: u8 = 0x21; // u8
    pub const SET_LOCAL_KEEP: u8 = 0x22; // u8, leaves the value
    /// Push this frame's own closure, so a named `fn` can call itself without
    /// capturing itself (which it could not: it does not exist yet).
    pub const SELF: u8 = 0x23;
    /// Everything from here is reserved for fused superinstructions.
    pub const SUPER_BASE: u8 = 0x80;
}

// --- the program image -----------------------------------------------------

#[derive(Clone, Copy, Debug)]
pub struct Arity {
    pub argc: u8,
    pub variadic: bool,
    pub nlocals: u16,
    pub code: u32,
    pub len: u32,
    /// Index into `Image::aot`, or `AOT_NONE`.
    #[cfg(feature = "aot")]
    pub aot: u32,
}

#[derive(Clone, Debug)]
pub struct FnDef {
    pub name: u32,
    pub arities: Vec<Arity>,
    pub nupvals: u8,
}

impl FnDef {
    pub fn select(&self, argc: usize) -> Option<&Arity> {
        let mut best: Option<&Arity> = None;
        for a in &self.arities {
            if !a.variadic && a.argc as usize == argc {
                return Some(a);
            }
            if a.variadic && argc >= a.argc as usize {
                match best {
                    Some(b) if b.argc >= a.argc => {}
                    _ => best = Some(a),
                }
            }
        }
        best
    }
}

#[derive(Default)]
pub struct Image {
    pub code: Vec<u8>,
    pub fns: Vec<FnDef>,
    /// Table slot (wasm) or host-registry index for each native import.
    pub natives: Vec<u32>,
    /// Constant index of each native import's NAME. The loader used to read this
    /// and discard it, which meant a diagnostic could report "import #63" and
    /// nothing better -- and a slot resolved through the host registry instead
    /// gives a plausible answer from the wrong table.
    #[cfg(feature = "diagnostics")]
    pub native_names: Vec<u32>,
    pub var_names: Vec<u32>,
    pub entry: u32,
    pub init: Vec<u32>,
    /// One entry per compiled arity (`doc/decisions/0013`). Empty in a module
    /// built without AOT, which is what lets the interpreter's own loop be
    /// monomorphised free of the re-entry check.
    #[cfg(feature = "aot")]
    pub aot: Vec<crate::aot::AotFn>,
}

pub struct Handler {
    pub frame: usize,
    pub stack_top: usize,
    pub target: u32,
    pub shadow: usize,
}

pub struct Frame {
    pub fp: usize,
    pub ip: u32,
    pub end: u32,
    /// Stack slot holding the callee. `stack[ret_to]` IS this frame's closure
    /// until the frame returns, and the return value overwrites it.
    ///
    /// The frame deliberately does **not** cache the closure. It used to, and
    /// that copy was a root the collector could not see: after a collection
    /// moved the closure, `UPVAL` and `SELF` read a stale address. Deriving it
    /// from the stack keeps the invariant the whole GC design rests on -- every
    /// live reference is in the value stack -- true with no second mechanism.
    pub ret_to: usize,
    pub handlers: usize,
    /// Index into `image.aot`, or `AOT_NONE`. Set by `enter` from the arity it
    /// selected, so the whole AOT question is one field on the frame rather
    /// than a lookup keyed on something the frame does not carry.
    #[cfg(feature = "aot")]
    pub aot_idx: u32,
    /// Re-enter compiled code when `ip` reaches this, at `aot_block`. Every
    /// re-entry point in the design funnels through this one comparison: frame
    /// entry, the instruction after a call, a resumed park, a caught throw, and
    /// the instruction after an opcode the emitter does not inline.
    #[cfg(feature = "aot")]
    pub aot_ip: u32,
    #[cfg(feature = "aot")]
    pub aot_block: u32,
    /// Instructions executed in THIS invocation, excluding nested frames. The
    /// run length under 0013's guard-only model.
    #[cfg(feature = "diagnostics")]
    pub instrs: u32,
    /// Restored from a thread save, i.e. this frame has parked and come back.
    /// 0013's pathological case is a loop that parks per iteration; without
    /// re-entry points every instruction executed in a resumed frame is an
    /// instruction the compiled body never gets to run.
    #[cfg(feature = "diagnostics")]
    pub resumed: bool,
}

/// A native builtin. `base` indexes the value stack; `argc` values start there.
/// The signature is `extern "C"` and flat so that the wasm type of the table
/// entry is exactly `(i32,i32,i32) -> i64` and a `call_indirect` through it is
/// unambiguous.
pub type NativeFn = extern "C" fn(*mut Rt, u32, u32) -> u64;

pub const MAX_FRAMES: usize = 8192;

/// This arity has no compiled code.
pub const AOT_NONE: u32 = u32::MAX;
/// Re-enter nowhere: `aot_ip` never equals a real `ip`, because `ip` 0 is the
/// image header rather than any function's first instruction.
pub const AOT_NEVER: u32 = u32::MAX;
/// The block is not known yet and must be looked up -- an unwind into a handler
/// is the only path that arrives without one.
pub const AOT_LOOKUP: u32 = u32::MAX;

/// Whether this instantiation of the interpreter counts.
///
/// A zero-sized type with an inlined method rather than a field test, so that
/// the free loop really is free: `NoBudget::tick` returns a constant `false` and
/// nothing to do with budgets survives into the generated code.

/// What a parking native means for the instruction that called it.
///
/// This exists as one function rather than two copies because when it *was*
/// two copies, only one of them existed: the `NATIVE` opcode handled a park
/// correctly, and the dynamic-dispatch path in `call_value` did not -- it
/// dropped the callee and every argument out of the root set while the thread
/// was parked and about to resume, and then handed the park to `unwind` as
/// though it were a thrown error. A third call site must not be able to appear
/// without this.
enum Parked {
    /// The continuation is saved; the call re-executes on resume.
    Saved,
    /// A courtesy yield: the call itself finished, so its result stands.
    Yielded,
    /// `thrown` now holds a real error to unwind.
    Failed,
}

pub trait BudgetPolicy {
    fn tick(rt: &mut Rt) -> bool;
}

pub struct NoBudget;
impl BudgetPolicy for NoBudget {
    #[inline(always)]
    fn tick(_: &mut Rt) -> bool {
        false
    }
}

pub struct Counting;
impl BudgetPolicy for Counting {
    #[inline(always)]
    fn tick(rt: &mut Rt) -> bool {
        rt.steps += 1;
        rt.steps >= rt.checkpoint
    }
}

impl Rt {
    // --- value stack -------------------------------------------------------

    #[inline]
    pub fn vpush(&mut self, v: Value) {
        let t = self.roots.stack_top;
        if t == self.roots.stack.len() {
            self.roots.stack.resize(t * 2, NIL);
        }
        self.roots.stack[t] = v;
        self.roots.stack_top = t + 1;
    }
    #[inline]
    pub fn vpop(&mut self) -> Value {
        self.roots.stack_top -= 1;
        self.roots.stack[self.roots.stack_top]
    }
    #[inline]
    pub fn vpeek(&self, n: usize) -> Value {
        self.roots.stack[self.roots.stack_top - 1 - n]
    }
    #[inline]
    pub fn vat(&self, i: usize) -> Value {
        self.roots.stack[i]
    }
    #[inline]
    fn vreserve(&mut self, n: usize) {
        let need = self.roots.stack_top + n;
        if need > self.roots.stack.len() {
            let mut cap = self.roots.stack.len();
            while cap < need {
                cap *= 2;
            }
            self.roots.stack.resize(cap, NIL);
        }
    }

    // --- code reading ------------------------------------------------------

    #[inline]
    pub(crate) fn u8_at(&self, ip: u32) -> u8 {
        self.image.code[ip as usize]
    }
    #[inline]
    pub(crate) fn u16_at(&self, ip: u32) -> u16 {
        u16::from_le_bytes([self.image.code[ip as usize], self.image.code[ip as usize + 1]])
    }
    #[inline]
    pub(crate) fn i16_at(&self, ip: u32) -> i16 {
        self.u16_at(ip) as i16
    }

    // --- callable objects --------------------------------------------------

    pub fn make_closure(&mut self, fn_idx: u32, upvals: &[Value]) -> Value {
        let base = self.mark();
        for v in upvals {
            self.push(*v);
        }
        let a = self.alloc(TY_CLOSURE, 1 + upvals.len() as u32);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        self.gc.set_slot(a, 0, Value::fixnum(fn_idx as i64));
        for i in 0..upvals.len() {
            let v = self.r(base + i);
            self.gc.set_slot(a, 1 + i as u32, v);
        }
        self.pop_to(base);
        Value::heap(a)
    }

    pub fn make_native(&mut self, native_idx: u32, name: Value) -> Value {
        let base = self.mark();
        let n = self.push(name);
        let a = self.alloc(TY_NATIVEFN, 2);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let name = self.r(n);
        self.pop_to(base);
        self.gc.set_slot(a, 0, Value::fixnum(native_idx as i64));
        self.gc.set_slot(a, 1, name);
        Value::heap(a)
    }

    pub fn is_fn(&self, v: Value) -> bool {
        v.is_heap() && matches!(ty(&self.gc.sp, v.as_heap()), TY_CLOSURE | TY_NATIVEFN | TY_MULTIFN)
    }

    /// Anything callable in a Clojure call position: fns, but also keywords,
    /// maps, sets and vectors.
    pub fn is_callable(&self, v: Value) -> bool {
        self.is_fn(v) || self.is_keyword(v) || self.is_map(v) || self.is_set(v) || self.is_vector(v)
    }

    // --- native dispatch ---------------------------------------------------

    /// Call the native builtin at import index `idx`.
    ///
    /// On wasm the import table holds a **table slot**, and this is a
    /// `call_indirect` through `__indirect_function_table`. That indirection is
    /// the whole modularity story: it is the only reference to a builtin
    /// anywhere in the module, so a builtin the program never reaches is not
    /// exported, and `--gc-sections` deletes it. Nothing may call a builtin
    /// directly, ever.
    #[inline]
    fn call_native(&mut self, idx: u32, base: usize, argc: usize) -> Value {
        #[cfg(feature = "diagnostics")]
        unsafe {
            if let Some(c) = crate::aotstat::NATIVE_CALLS.get_mut(idx as usize) {
                *c += 1;
            }
            if crate::aotstat::NATIVE_TRACE_N < crate::aotstat::TRACE_CAP {
                let k = crate::aotstat::NATIVE_TRACE_N;
                crate::aotstat::NATIVE_TRACE[k] = idx as u16;
                // The FUNCTION, not the ip: compiled code only commits `ip`
                // when it leaves, so an ip recorded here is stale and the two
                // builds cannot be compared on it. `stack[ret_to]` is the
                // frame's own closure and its first slot is the function index.
                crate::aotstat::NATIVE_TRACE_IP[k] = self
                    .frames
                    .last()
                    .map(|f| f.ret_to)
                    .and_then(|rt| self.roots.stack.get(rt).copied())
                    .filter(|c| c.is_heap())
                    .filter(|c| ty(&self.gc.sp, c.as_heap()) == TY_CLOSURE)
                    .map(|c| slot(&self.gc.sp, c.as_heap(), 0).as_fixnum() as u32)
                    .unwrap_or(u32::MAX);
                crate::aotstat::NATIVE_TRACE_N += 1;
            }
        }
        let slot = self.image.natives[idx as usize];
        #[cfg(target_arch = "wasm32")]
        let f: NativeFn = unsafe { core::mem::transmute(slot as usize) };
        // On a host there is no wasm table, so the slot indexes a registry the
        // harness fills. This branch never exists in a shipped module.
        #[cfg(not(target_arch = "wasm32"))]
        let f: NativeFn = self.host_natives[slot as usize];
        let p = self as *mut Rt;
        Value(f(p, base as u32, argc as u32))
    }

    // --- entry points -------------------------------------------------------

    /// Call `f` with `args`, running a nested interpreter loop. Native code uses
    /// this for lazy-seq forcing, comparators and higher-order builtins; it is
    /// safe to re-enter because all VM state lives in `Rt`.
    pub fn invoke(&mut self, f: Value, args: &[Value]) -> Value {
        let save = self.roots.stack_top;
        self.vreserve(args.len() + 1);
        self.vpush(f);
        for a in args {
            self.vpush(*a);
        }
        let r = self.call_value(args.len());
        // Not when the thread parked: its stack is the continuation, and
        // truncating here would throw the continuation away.
        if self.park_on.is_nil() {
            self.roots.stack_top = save;
        }
        r
    }


    /// A native set `thrown = PARK`. `keep_top` is where the value stack must
    /// be left so the call's operands are still rooted AND still in place for
    /// re-execution; `opcode_at` is the instruction to rewind to.
    fn parked(
        &mut self,
        opcode_at: u32,
        keep_top: usize,
        base_depth: usize,
        reexecutable: bool,
    ) -> Parked {
        if base_depth != 0 {
            // Rust frames are live underneath: a lazy-seq force, a comparator,
            // `map`. There is no continuation to save, so say so plainly rather
            // than corrupting the stack.
            self.thrown = NIL;
            self.park_on = NIL;
            self.throw_str(
                "IllegalStateException",
                "cannot park here: this call is nested inside native code \
                 (map, sort, reduce, a lazy seq). Park from a green thread's \
                 own code instead.",
            );
            return Parked::Failed;
        }
        self.thrown = NIL;
        if self.park_on.bits() == crate::conc::PARK_YIELD.bits() {
            // A courtesy yield: the call itself is finished, so let it finish.
            // Rewinding would re-execute `yield`, which would yield again, for
            // ever.
            return Parked::Yielded;
        }
        if !reexecutable {
            self.park_on = NIL;
            self.throw_str(
                "IllegalStateException",
                "cannot park here: this native was reached through `apply`, and \
                 `apply` has already spread its arguments onto the stack, so the \
                 call cannot be re-executed on resume. Call it directly instead.",
            );
            return Parked::Failed;
        }
        // Rewind to the instruction itself and leave the operands in place:
        // resuming re-executes the call, which is why a parking builtin must
        // decide to park before it changes anything.
        self.frames.last_mut().unwrap().ip = opcode_at;
        self.roots.stack_top = keep_top;
        Parked::Saved
    }

    /// The callee and `argc` args are on top of the value stack. Returns the
    /// result, having consumed them.
    fn call_value(&mut self, argc: usize) -> Value {
        let callee_at = self.roots.stack_top - argc - 1;
        let callee = self.vat(callee_at);
        if !callee.is_heap() {
            if callee.is_inline_kw() {
                return self.apply_keyword(callee_at, argc);
            }
            self.roots.stack_top = callee_at;
            let w = self.where_am_i();
            let msg = alloc::format!(
                "value is not a function ({:?}, {argc} args) in {w}",
                callee
            );
            return self.throw_str("ClassCastException", &msg);
        }
        match ty(&self.gc.sp, callee.as_heap()) {
            TY_NATIVEFN => {
                let idx = self.slot(callee, 0).as_fixnum() as u32;
                #[cfg(feature = "diagnostics")]
                let saved_native = unsafe { crate::gc::CUR_NATIVE };
                #[cfg(feature = "diagnostics")]
                unsafe {
                    crate::gc::CUR_NATIVE = idx + 1;
                }
                let r = self.call_native(idx, callee_at + 1, argc);
                #[cfg(feature = "diagnostics")]
                unsafe {
                    crate::gc::CUR_NATIVE = saved_native;
                }
                if self.thrown.bits() != crate::value::PARK.bits() {
                    self.roots.stack_top = callee_at;
                }
                // On a park the operands stay where they are: they are roots,
                // and the caller re-executes this call on resume. Dropping them
                // here is what made a park through a value corrupt the stack.
                r
            }
            TY_CLOSURE => {
                let depth = self.frames.len();
                if !self.enter(callee, callee_at, argc) {
                    return NIL;
                }
                self.run(depth)
            }
            TY_KW => self.apply_keyword(callee_at, argc),
            TY_ARRAYMAP | TY_HASHMAP => {
                let r = if argc == 1 {
                    let k = self.vat(callee_at + 1);
                    self.map_get(callee, k, NIL)
                } else if argc == 2 {
                    let (k, d) = (self.vat(callee_at + 1), self.vat(callee_at + 2));
                    self.map_get(callee, k, d)
                } else {
                    self.throw_str("ArityException", "a map takes 1 or 2 arguments")
                };
                self.roots.stack_top = callee_at;
                r
            }
            TY_SET => {
                let r = if argc == 1 {
                    let k = self.vat(callee_at + 1);
                    self.set_get(callee, k, NIL)
                } else {
                    self.throw_str("ArityException", "a set takes 1 argument")
                };
                self.roots.stack_top = callee_at;
                r
            }
            TY_VEC => {
                let r = if argc == 1 {
                    let k = self.vat(callee_at + 1);
                    match self.as_i64(k) {
                        Some(i) if i >= 0 => self.vec_nth(callee, i as u32).unwrap_or_else(|| {
                            self.throw_str("IndexOutOfBoundsException", "index out of range")
                        }),
                        _ => self.throw_str("IllegalArgumentException", "vector index must be an integer"),
                    }
                } else {
                    self.throw_str("ArityException", "a vector takes 1 argument")
                };
                self.roots.stack_top = callee_at;
                r
            }
            _ => {
                // Say WHAT was called: "value is not a function" with no subject
                // is the least useful message in the runtime.
                let t = ty(&self.gc.sp, callee.as_heap());
                self.roots.stack_top = callee_at;
                let w = self.where_am_i();
                let msg = alloc::format!(
                    "value is not a function (object type {t}, {argc} args) in {w}"
                );
                self.throw_str("ClassCastException", &msg)
            }
        }
    }

    fn apply_keyword(&mut self, callee_at: usize, argc: usize) -> Value {
        let kw = self.vat(callee_at);
        let r = if argc >= 1 {
            let coll = self.vat(callee_at + 1);
            let dflt = if argc >= 2 { self.vat(callee_at + 2) } else { NIL };
            if self.is_map(coll) {
                self.map_get(coll, kw, dflt)
            } else if self.is_set(coll) {
                self.set_get(coll, kw, dflt)
            } else {
                dflt
            }
        } else {
            self.throw_str("ArityException", "a keyword takes 1 or 2 arguments")
        };
        self.roots.stack_top = callee_at;
        r
    }

    /// Push a frame for `closure`. The callee sits at `callee_at`, args follow.
    pub(crate) fn enter(&mut self, closure: Value, callee_at: usize, argc: usize) -> bool {
        let fn_idx = self.slot(closure, 0).as_fixnum() as usize;
        // Is this call site monomorphic? Keyed on the CALLER's committed ip,
        // which is exact in the interpreter -- the build this is measured on.
        #[cfg(feature = "diagnostics")]
        unsafe {
            use crate::aotstat::*;
            let ip = self.frames.last().map(|f| f.ip).unwrap_or(0);
            // Only a real CALL site. `enter` is also reached through
            // `call_value` and `invoke`, whose committed ip is not a call site
            // at all, and counting those keyed the table on noise.
            if ip >= 2 && self.u8_at(ip - 2) == op::CALL {
                let site = ip as usize % SITE_CAP;
                COUNTS[C_SITES_SEEN] += 1;
                let prev = SITE_FN[site];
                if prev == u32::MAX {
                    SITE_FN[site] = fn_idx as u32;
                } else if prev != fn_idx as u32 {
                    COUNTS[C_SITES_POLY] += 1;
                    SITE_FN[site] = fn_idx as u32;
                }
            }
        }
        #[cfg(feature = "aot")]
        let mut aot_idx = AOT_NONE;
        let (nlocals, code, end, variadic, fixed) = {
            let def = &self.image.fns[fn_idx];
            match def.select(argc) {
                Some(a) => {
                    #[cfg(feature = "aot")]
                    {
                        aot_idx = a.aot;
                    }
                    (
                        a.nlocals as usize,
                        a.code,
                        a.code + a.len,
                        a.variadic,
                        a.argc as usize,
                    )
                }
                None => {
                    let namec = def.name as usize;
                    let name = self.roots.consts.get(namec).copied().unwrap_or(NIL);
                    self.roots.stack_top = callee_at;
                    let mut b = crate::rt::sbuf();
                    let n: alloc::string::String =
                        self.as_str(name, &mut b).unwrap_or("fn").into();
                    let msg = alloc::format!("wrong number of arguments ({argc}) to {n}");
                    self.throw_str("ArityException", &msg);
                    return false;
                }
            }
        };
        if self.frames.len() >= MAX_FRAMES {
            self.roots.stack_top = callee_at;
            self.throw_str("StackOverflowError", "call depth exceeded");
            return false;
        }
        let fp = callee_at + 1;
        if variadic {
            // Fold the surplus arguments into a list in the last fixed slot.
            let rest_start = fp + fixed;
            let n = argc - fixed;
            let rest = if n == 0 {
                NIL
            } else {
                let base = self.mark();
                for i in 0..n {
                    let v = self.vat(rest_start + i);
                    self.push(v);
                }
                let l = self.list_from_roots(base, n);
                self.pop_to(base);
                l
            };
            self.roots.stack_top = rest_start;
            self.vpush(rest);
        }
        let nargs = if variadic { fixed + 1 } else { argc };
        self.vreserve(nlocals.saturating_sub(nargs) + 8);
        for i in nargs..nlocals {
            self.roots.stack[fp + i] = NIL;
        }
        self.roots.stack_top = fp + nlocals;
        debug_assert_eq!(self.roots.stack[callee_at], closure, "stack[ret_to] must be the callee");
        self.frames.push(Frame {
            fp,
            ip: code,
            end,
            ret_to: callee_at,
            handlers: self.handlers.len(),
            #[cfg(feature = "aot")]
            aot_idx,
            // Entry at the top is just the first re-entry point, so nothing
            // about starting a frame is special-cased.
            #[cfg(feature = "aot")]
            aot_ip: if aot_idx == AOT_NONE { AOT_NEVER } else { code },
            #[cfg(feature = "aot")]
            aot_block: 0,
            #[cfg(feature = "diagnostics")]
            instrs: 0,
            #[cfg(feature = "diagnostics")]
            resumed: false,
        });
        true
    }

    /// Run until the frame stack drops back to `base_depth`.
    ///
    /// Two instantiations, chosen **once at entry** rather than branched on per
    /// instruction (`doc/decisions/0009`). With no gas limit and no scheduler
    /// slice, `NoBudget::tick` is a `false` the optimiser deletes along with the
    /// counter, and the loop has no budget machinery in it at all.
    pub fn run(&mut self, base_depth: usize) -> Value {
        // Two instantiations, not four. Monomorphising the AOT check as well
        // would double the biggest function in the module a second time, and
        // 0009 traded a known budget for the free loop -- spending it again on
        // a feature most modules do not use is exactly what that budget exists
        // to stop. One predictable comparison instead.
        if self.counting() {
            self.run_with::<Counting>(base_depth)
        } else {
            self.run_with::<NoBudget>(base_depth)
        }
    }

    fn run_with<B: BudgetPolicy>(&mut self, base_depth: usize) -> Value {
        // Saved and restored: `run` re-enters for a lazy-seq force, a
        // comparator, `map`.
        #[cfg(feature = "aot")]
        let outer_base = core::mem::replace(&mut self.run_base, base_depth);
        let out = self.run_inner::<B>(base_depth);
        #[cfg(feature = "aot")]
        {
            self.run_base = outer_base;
        }
        out
    }

    fn run_inner<B: BudgetPolicy>(&mut self, base_depth: usize) -> Value {
        #[cfg(feature = "aot")]
        let aot_on = !self.image.aot.is_empty();
        // doc/decisions/0013's region histogram. `run` is Model A -- the
        // distance from one call to the next. `last_ip`/`last_depth` detect a
        // TAKEN backward jump without knowing anything about which opcodes jump,
        // which matters because "was it taken" is not readable from the opcode.
        #[cfg(feature = "diagnostics")]
        let (mut run, mut last_ip, mut last_depth) = (0u32, u32::MAX, usize::MAX);
        loop {
            if self.frames.len() <= base_depth {
                return self.vpop();
            }
            let (mut ip, fp) = {
                let f = self.frames.last().unwrap();
                (f.ip, f.fp)
            };
            // The one comparison every re-entry point in the design funnels
            // through: frame entry, the instruction after a call, a resumed
            // park, a caught throw, and the instruction after an opcode the
            // emitter does not inline. It is a constant `false` in a module
            // with nothing compiled.
            #[cfg(feature = "aot")]
            if aot_on && self.frames.last().unwrap().aot_ip == ip {
                if self.aot_enter(ip) {
                    return NIL;
                }
                continue;
            }
            if B::tick(self) {
                // `ip` is a local for speed and is written back to the frame
                // only by `commit!`, which is declared further down and so is
                // not even in scope here. Everything below can return or
                // unwind, and a frame carrying a stale `ip` resumes PART WAY
                // BACK through instructions it already ran. Write it back
                // first.
                if let Some(f) = self.frames.last_mut() {
                    f.ip = ip;
                }
                // One comparison covers both budgets; which one fired is a cold
                // path. In a threaded program the slice is doing double duty as
                // preemption -- running out means "your turn is over" rather
                // than "you have hung" -- so gas costs nothing extra exactly
                // where concurrency already made the counter necessary.
                if self.gas_limit != 0 && self.steps >= self.gas_limit {
                    let t = self.frame_trace();
                    let e = self.gas_error(&alloc::format!("\n{t}"));
                    self.thrown = e;
                    self.gas_trips += 1;
                    if self.gas_trips > 1 {
                        // It was caught once and the program carried on. A gate
                        // that a candidate can catch its way out of is not a
                        // gate, so this one escapes every handler.
                        return NIL;
                    }
                    // Grace, once, so a `finally` can put things back.
                    self.gas_limit = self.steps + Rt::GAS_GRACE;
                    self.refresh_checkpoint();
                    if !self.unwind() {
                        return NIL;
                    }
                    continue;
                }
                if self.sched_hook.is_some() {
                    if base_depth == 0 {
                        self.park_on = crate::conc::PARK_YIELD;
                        return NIL;
                    }
                    // Rust frames underneath: there is nothing to save, so let
                    // this call finish and preempt at the next chance.
                    let at = self.steps + crate::conc::SLICE;
                    self.set_slice_end(at);
                } else {
                    let at = self.steps + crate::conc::SLICE;
                    self.set_slice_end(at);
                }
            }
            let opcode = self.u8_at(ip);
            #[cfg(feature = "diagnostics")]
            unsafe {
                use crate::aotstat::*;
                let depth = self.frames.len();
                if depth == last_depth && ip < last_ip {
                    COUNTS[C_BACKEDGES] += 1;
                }
                (last_ip, last_depth) = (ip, depth);
                COUNTS[C_INSTRS] += 1;
                OPS[opcode as usize] += 1;
                run += 1;
                if let Some(f) = self.frames.last_mut() {
                    f.instrs += 1;
                    if f.resumed {
                        COUNTS[C_RESUMED_INSTRS] += 1;
                    }
                }
                match opcode {
                    op::CALL | op::TAIL_CALL | op::APPLY | op::NATIVE => {
                        // One guard per call site reached. In the guard-only
                        // design this is what a compiled body pays: a load, a
                        // test, a branch.
                        COUNTS[C_GUARDS] += 1;
                        COUNTS[match opcode {
                            op::CALL => C_CALLS,
                            op::TAIL_CALL => C_TAILCALLS,
                            op::APPLY => C_APPLIES,
                            _ => C_NATIVES,
                        }] += 1;
                        note_run(run);
                        run = 0;
                    }
                    op::RETURN | op::THROW | op::RETHROW => {
                        note_run(run);
                        run = 0;
                    }
                    _ => {}
                }
            }
            ip += 1;

            macro_rules! commit {
                () => {
                    self.frames.last_mut().unwrap().ip = ip;
                };
            }

            match opcode {
                op::NOP => {}
                op::CONST => {
                    let k = self.u16_at(ip) as usize;
                    ip += 2;
                    let v = self.roots.consts[k];
                    self.vpush(v);
                }
                op::NIL => self.vpush(NIL),
                op::TRUE => self.vpush(TRUE),
                op::FALSE => self.vpush(FALSE),
                op::INT => {
                    let n = self.i16_at(ip) as i64;
                    ip += 2;
                    self.vpush(Value::fixnum(n));
                }
                op::LOCAL => {
                    let i = self.u8_at(ip) as usize;
                    ip += 1;
                    let v = self.roots.stack[fp + i];
                    self.vpush(v);
                }
                op::LOCAL_W => {
                    let i = self.u16_at(ip) as usize;
                    ip += 2;
                    let v = self.roots.stack[fp + i];
                    self.vpush(v);
                }
                op::SET_LOCAL => {
                    let i = self.u8_at(ip) as usize;
                    ip += 1;
                    let v = self.vpop();
                    self.roots.stack[fp + i] = v;
                }
                op::SET_LOCAL_KEEP => {
                    let i = self.u8_at(ip) as usize;
                    ip += 1;
                    let v = self.vpeek(0);
                    self.roots.stack[fp + i] = v;
                }
                op::SELF => {
                    let c = self.cur_closure();
                    self.vpush(c);
                }
                op::UPVAL => {
                    let i = self.u8_at(ip) as u32;
                    ip += 1;
                    let c = self.cur_closure();
                    let v = self.slot(c, 1 + i);
                    self.vpush(v);
                }
                op::VAR => {
                    let k = self.u16_at(ip) as usize;
                    ip += 2;
                    let v = self.roots.globals[k];
                    self.vpush(v);
                }
                op::SET_VAR => {
                    let k = self.u16_at(ip) as usize;
                    ip += 2;
                    let v = self.vpop();
                    self.roots.globals[k] = v;
                }
                op::POP => {
                    self.roots.stack_top -= 1;
                }
                op::POP_N => {
                    let n = self.u8_at(ip) as usize;
                    ip += 1;
                    self.roots.stack_top -= n;
                }
                op::DUP => {
                    let v = self.vpeek(0);
                    self.vpush(v);
                }
                op::JUMP => {
                    let off = self.i16_at(ip) as i32;
                    ip = (ip as i32 + 2 + off) as u32;
                }
                op::JUMP_IF_FALSE => {
                    let off = self.i16_at(ip) as i32;
                    ip += 2;
                    let v = self.vpop();
                    if !v.truthy() {
                        ip = (ip as i32 + off) as u32;
                    }
                }
                op::JUMP_IF_TRUE => {
                    let off = self.i16_at(ip) as i32;
                    ip += 2;
                    let v = self.vpop();
                    if v.truthy() {
                        ip = (ip as i32 + off) as u32;
                    }
                }
                op::JUMP_IF_FALSE_KEEP => {
                    let off = self.i16_at(ip) as i32;
                    ip += 2;
                    if !self.vpeek(0).truthy() {
                        ip = (ip as i32 + off) as u32;
                    } else {
                        self.roots.stack_top -= 1;
                    }
                }
                op::JUMP_IF_TRUE_KEEP => {
                    let off = self.i16_at(ip) as i32;
                    ip += 2;
                    if self.vpeek(0).truthy() {
                        ip = (ip as i32 + off) as u32;
                    } else {
                        self.roots.stack_top -= 1;
                    }
                }
                op::CALL => {
                    let opcode_at = ip - 1;
                    let argc = self.u8_at(ip) as usize;
                    ip += 1;
                    commit!();
                    let callee_at = self.roots.stack_top - argc - 1;
                    let callee = self.vat(callee_at);
                    if callee.is_heap() && ty(&self.gc.sp, callee.as_heap()) == TY_CLOSURE {
                        // Inline the frame push so Clojure recursion uses our
                        // frame stack, not the Rust one.
                        if !self.enter(callee, callee_at, argc) {
                            if !self.unwind() {
                                return NIL;
                            }
                        }
                    } else {
                        let r = self.call_value(argc);
                        // The same park handling as the NATIVE opcode: a native
                        // reached through a VALUE parks exactly as one reached
                        // through the opcode does.
                        if self.thrown.bits() == crate::value::PARK.bits() {
                            match self.parked(opcode_at, callee_at + 1 + argc, base_depth, true) {
                                Parked::Saved => return NIL,
                                Parked::Yielded => {
                                    // The call finished after all. `call_value`
                                    // left the callee and arguments in place
                                    // for a re-execution that is not going to
                                    // happen, so drop them before pushing the
                                    // result -- otherwise the stack grows by
                                    // the whole call frame every yield.
                                    self.roots.stack_top = callee_at;
                                    self.vpush(r);
                                    return NIL;
                                }
                                Parked::Failed => {
                                    if !self.unwind() {
                                        return NIL;
                                    }
                                    continue;
                                }
                            }
                        }
                        if self.failed() {
                            if !self.unwind() {
                                return NIL;
                            }
                        } else {
                            self.vpush(r);
                        }
                    }
                    continue;
                }
                op::TAIL_CALL => {
                    let opcode_at = ip - 1;
                    let argc = self.u8_at(ip) as usize;
                    ip += 1;
                    commit!();
                    let callee_at = self.roots.stack_top - argc - 1;
                    let callee = self.vat(callee_at);
                    if callee.is_heap() && ty(&self.gc.sp, callee.as_heap()) == TY_CLOSURE {
                        // Drop this frame first: that is what makes a tail call
                        // constant-space.
                        let f = self.frames.pop().unwrap();
                        #[cfg(feature = "diagnostics")]
                        crate::aotstat::note_frame(f.instrs, f.resumed);
                        self.handlers.truncate(f.handlers);
                        let dest = f.ret_to;
                        for i in 0..=argc {
                            self.roots.stack[dest + i] = self.roots.stack[callee_at + i];
                        }
                        self.roots.stack_top = dest + argc + 1;
                        if !self.enter(callee, dest, argc) {
                            if !self.unwind() {
                                return NIL;
                            }
                        }
                    } else {
                        let r = self.call_value(argc);
                        if self.thrown.bits() == crate::value::PARK.bits() {
                            match self.parked(opcode_at, callee_at + 1 + argc, base_depth, true) {
                                Parked::Saved => return NIL,
                                Parked::Yielded => {
                                    // The call finished after all. `call_value`
                                    // left the callee and arguments in place
                                    // for a re-execution that is not going to
                                    // happen, so drop them before pushing the
                                    // result -- otherwise the stack grows by
                                    // the whole call frame every yield.
                                    self.roots.stack_top = callee_at;
                                    self.vpush(r);
                                    return NIL;
                                }
                                Parked::Failed => {
                                    if !self.unwind() {
                                        return NIL;
                                    }
                                    continue;
                                }
                            }
                        }
                        if self.failed() {
                            if !self.unwind() {
                                return NIL;
                            }
                        } else {
                            self.vpush(r);
                            let f = self.frames.pop().unwrap();
                            #[cfg(feature = "diagnostics")]
                            crate::aotstat::note_frame(f.instrs, f.resumed);
                            self.handlers.truncate(f.handlers);
                            let v = self.vpop();
                            self.roots.stack_top = f.ret_to;
                            self.vpush(v);
                            if self.frames.len() <= base_depth {
                                return self.vpop();
                            }
                        }
                    }
                    continue;
                }
                op::RETURN => {
                    let v = self.vpop();
                    let f = self.frames.pop().unwrap();
                    #[cfg(feature = "diagnostics")]
                    crate::aotstat::note_frame(f.instrs, f.resumed);
                    self.handlers.truncate(f.handlers);
                    self.roots.stack_top = f.ret_to;
                    self.vpush(v);
                    if self.frames.len() <= base_depth {
                        return self.vpop();
                    }
                    continue;
                }
                op::CLOSURE => {
                    let fn_idx = self.u16_at(ip) as u32;
                    let n = self.u8_at(ip + 2) as usize;
                    ip += 3;
                    let base = self.roots.stack_top - n;
                    let a = self.alloc(TY_CLOSURE, 1 + n as u32);
                    if a == 0 {
                        self.oom_unwind();
                        if !self.unwind() {
                            return NIL;
                        }
                        continue;
                    }
                    self.gc.set_slot(a, 0, Value::fixnum(fn_idx as i64));
                    for i in 0..n {
                        let v = self.roots.stack[base + i];
                        self.gc.set_slot(a, 1 + i as u32, v);
                    }
                    self.roots.stack_top = base;
                    self.vpush(Value::heap(a));
                }
                op::NATIVE => {
                    let opcode_at = ip - 1;
                    let idx = self.u16_at(ip) as u32;
                    let argc = self.u8_at(ip + 2) as usize;
                    ip += 3;
                    commit!();
                    let base = self.roots.stack_top - argc;
                    #[cfg(feature = "diagnostics")]
                    let saved_native = unsafe { crate::gc::CUR_NATIVE };
                    #[cfg(feature = "diagnostics")]
                    unsafe {
                        crate::gc::CUR_NATIVE = idx + 1;
                    }
                    let r = self.call_native(idx, base, argc);
                    #[cfg(feature = "diagnostics")]
                    unsafe {
                        crate::gc::CUR_NATIVE = saved_native;
                    }
                    self.roots.stack_top = base;
                    #[cfg(feature = "diagnostics")]
                    if self.failed() {
                        unsafe { crate::aotstat::COUNTS[crate::aotstat::C_GUARD_HITS] += 1 };
                    }
                    if self.failed() {
                        // A park travels as a distinguished `thrown` value, so
                        // that this branch -- which already exists -- is the
                        // whole cost of green threads to the interpreter's hot
                        // path. See doc/decisions/0005.
                        if self.thrown.bits() == crate::value::PARK.bits() {
                            match self.parked(opcode_at, base + argc, base_depth, true) {
                                Parked::Saved => return NIL,
                                Parked::Yielded => {
                                    self.vpush(r);
                                    return NIL;
                                }
                                Parked::Failed => {
                                    if !self.unwind() {
                                        return NIL;
                                    }
                                    continue;
                                }
                            }
                        }
                        if !self.unwind() {
                            return NIL;
                        }
                    } else {
                        self.vpush(r);
                    }
                    continue;
                }
                op::THROW => {
                    let v = self.vpop();
                    self.thrown = v;
                    commit!();
                    if !self.unwind() {
                        return NIL;
                    }
                    continue;
                }
                op::RETHROW => {
                    let v = self.vpop();
                    self.thrown = v;
                    commit!();
                    if !self.unwind() {
                        return NIL;
                    }
                    continue;
                }
                op::TRY => {
                    let off = self.i16_at(ip) as i32;
                    ip += 2;
                    let target = (ip as i32 + off) as u32;
                    self.handlers.push(Handler {
                        frame: self.frames.len() - 1,
                        stack_top: self.roots.stack_top,
                        target,
                        shadow: self.roots.shadow.len(),
                    });
                }
                op::POP_HANDLER => {
                    self.handlers.pop();
                }
                op::VECTOR => {
                    let n = self.u16_at(ip) as usize;
                    ip += 2;
                    commit!();
                    let base = self.roots.stack_top - n;
                    let mut v = self.empty_vec();
                    let vi = self.push(v);
                    for i in 0..n {
                        let x = self.roots.stack[base + i];
                        let nv = self.vec_conj(self.r(vi), x);
                        self.set_r(vi, nv);
                    }
                    v = self.r(vi);
                    self.pop_to(vi);
                    self.roots.stack_top = base;
                    self.vpush(v);
                    continue;
                }
                op::LIST => {
                    let n = self.u16_at(ip) as usize;
                    ip += 2;
                    commit!();
                    let base = self.roots.stack_top - n;
                    let mut acc = self.empty_list();
                    let ai = self.push(acc);
                    for i in (0..n).rev() {
                        let x = self.roots.stack[base + i];
                        let c = self.cons(x, self.r(ai));
                        self.set_r(ai, c);
                    }
                    acc = self.r(ai);
                    self.pop_to(ai);
                    self.roots.stack_top = base;
                    self.vpush(acc);
                    continue;
                }
                op::MAP => {
                    let n = self.u16_at(ip) as usize;
                    ip += 2;
                    commit!();
                    let base = self.roots.stack_top - 2 * n;
                    let mut m = self.empty_map();
                    let mi = self.push(m);
                    for i in 0..n {
                        let k = self.roots.stack[base + 2 * i];
                        let v = self.roots.stack[base + 2 * i + 1];
                        let nm = self.map_assoc(self.r(mi), k, v);
                        self.set_r(mi, nm);
                    }
                    m = self.r(mi);
                    self.pop_to(mi);
                    self.roots.stack_top = base;
                    self.vpush(m);
                    continue;
                }
                op::SET => {
                    let n = self.u16_at(ip) as usize;
                    ip += 2;
                    commit!();
                    let base = self.roots.stack_top - n;
                    let mut s = self.empty_set();
                    let si = self.push(s);
                    for i in 0..n {
                        let x = self.roots.stack[base + i];
                        let ns = self.set_conj(self.r(si), x);
                        self.set_r(si, ns);
                    }
                    s = self.r(si);
                    self.pop_to(si);
                    self.roots.stack_top = base;
                    self.vpush(s);
                    continue;
                }
                op::APPLY => {
                    let argc = self.u8_at(ip) as usize;
                    ip += 1;
                    commit!();
                    // stack: callee, a1..a(argc-1), seq
                    let seq = self.vpop();
                    let mut spread = 0usize;
                    let si = self.push(seq);
                    let mut cur = self.seq(self.r(si));
                    self.set_r(si, cur);
                    while !self.r(si).is_nil() {
                        let f = self.first(self.r(si));
                        self.vpush(f);
                        spread += 1;
                        cur = self.next(self.r(si));
                        self.set_r(si, cur);
                    }
                    self.pop_to(si);
                    let total = argc - 1 + spread;
                    let apply_callee_at = self.roots.stack_top - total - 1;
                    let r = self.call_value(total);
                    if self.thrown.bits() == crate::value::PARK.bits() {
                        // `apply` has already spread the seq onto the stack, so
                        // there is no instruction that would re-execute this
                        // call. Refusing is the honest outcome; silently losing
                        // the operands is not.
                        match self.parked(ip, 0, base_depth, false) {
                            Parked::Yielded => {
                                self.roots.stack_top = apply_callee_at;
                                self.vpush(r);
                                return NIL;
                            }
                            _ => {
                                if !self.unwind() {
                                    return NIL;
                                }
                                continue;
                            }
                        }
                    }
                    if !self.park_on.is_nil() {
                        // A closure called through `apply` parked further in.
                        // Its continuation is already saved, frames and all, so
                        // this must not push a result on top of it.
                        return NIL;
                    }
                    if self.failed() {
                        if !self.unwind() {
                            return NIL;
                        }
                    } else {
                        self.vpush(r);
                    }
                    continue;
                }
                _ => {
                    self.throw_str("VerifyError", "unknown opcode");
                    commit!();
                    if !self.unwind() {
                        return NIL;
                    }
                    continue;
                }
            }
            commit!();
        }
    }

    /// Names of the active frames, innermost first. Costs nothing until asked
    /// for, and turns "it hangs" into "it hangs in read-form".
    /// This frame's closure, read from the stack rather than cached.
    #[inline]
    fn cur_closure(&self) -> Value {
        self.roots.stack[self.frames.last().unwrap().ret_to]
    }

    fn frame_closure(&self, i: usize) -> Value {
        self.roots.stack[self.frames[i].ret_to]
    }

    /// Just the frame names, for attaching to a runtime error.
    pub fn where_am_i(&mut self) -> alloc::string::String {
        let mut out = alloc::string::String::new();
        let n = self.frames.len();
        for i in (0..n).rev().take(12) {
            let fnidx = {
                let c = self.frame_closure(i);
                if c.is_heap() { self.slot(c, 0).as_fixnum() as usize } else { usize::MAX }
            };
            let name = if fnidx < self.image.fns.len() {
                let namec = self.image.fns[fnidx].name as usize;
                let v = self.roots.consts.get(namec).copied().unwrap_or(NIL);
                let mut b = crate::rt::sbuf();
                let s: alloc::string::String = self.as_str(v, &mut b).unwrap_or("?").into();
                s
            } else { "?".into() };
            if !out.is_empty() { out.push_str(" <- "); }
            out.push_str(&name);
        }
        out
    }

    pub fn frame_trace(&mut self) -> alloc::string::String {
        let mut out = alloc::string::String::new();
        let n = self.frames.len();
        for i in (0..n).rev().take(24) {
            let fnidx = {
                let c = self.frame_closure(i);
                if c.is_heap() { self.slot(c, 0).as_fixnum() as usize } else { usize::MAX }
            };
            let name = if fnidx < self.image.fns.len() {
                let namec = self.image.fns[fnidx].name as usize;
                let v = self.roots.consts.get(namec).copied().unwrap_or(NIL);
                let mut b = crate::rt::sbuf();
                let s: alloc::string::String = self.as_str(v, &mut b).unwrap_or("?").into();
                s
            } else {
                "?".into()
            };
            if !out.is_empty() {
                out.push_str(" <- ");
            }
            out.push_str(&name);
        }
        alloc::format!("step limit exceeded; frames ({}): {}", n, out)
    }

    /// The heap cap was reached, and a collection has already been tried. A
    /// **catchable error** carrying what was held against what was allowed, not
    /// a trap: a host has to be able to tell "the program is wrong" from "the
    /// limit was too small".
    fn oom_unwind(&mut self) {
        // One extra megabyte, granted once, purely so the error describing the
        // exhaustion can be built. A cap enforced to the last byte cannot
        // report itself; better to say so than to fail silently.
        let limit = self.gc.heap_limit();
        if self.mem_trips == 0 {
            self.mem_trips = 1;
            self.gc.set_heap_limit(limit.saturating_add(1024 * 1024));
        }
        let used = self.gc.heap_used();
        let msg = alloc::format!(
            "memory limit exceeded: {used} bytes of {limit} in use after a collection"
        );
        let base = self.mark();
        let k = self.string("ResourceExhausted");
        let ki = self.push(k);
        let m = self.string(&msg);
        let mi = self.push(m);
        let d = self.empty_map();
        let di = self.push(d);
        for (key, val) in [("used", used as i64), ("limit", limit as i64)] {
            let kw = self.keyword(None, key);
            let kwi = self.push(kw);
            let dv = self.r(di);
            let kv = self.r(kwi);
            let nd = self.map_assoc(dv, kv, Value::fixnum(val));
            self.set_r(di, nd);
            self.pop_to(kwi);
        }
        let (kk, mm, dd) = (self.r(ki), self.r(mi), self.r(di));
        let e = self.ex_info(kk, mm, dd, NIL);
        self.pop_to(base);
        if !e.is_nil() {
            self.thrown = e;
        }
    }

    /// Find a handler for `self.thrown`. Returns false when the exception
    /// escapes past `base_depth`, in which case the caller returns.
    /// Unwind into a handler for a thread that is being resumed with an error
    /// the scheduler handed it. Same machinery as a throw; the only difference
    /// is who set `thrown`.
    pub fn unwind_from_resume(&mut self) -> bool {
        self.unwind()
    }

    fn unwind(&mut self) -> bool {
        #[cfg(feature = "aot")]
        {
            self.unwinds += 1;
        }
        // Every failure comes through here, so this is the one place that has
        // to turn the allocator's cheap sentinel into an error somebody can
        // read. Cold path: an ordinary throw pays one comparison.
        if self.thrown.bits() == crate::value::OOM.bits() {
            self.thrown = NIL;
            self.oom_unwind();
        }
        while let Some(h) = self.handlers.pop() {
            if h.frame >= self.frames.len() {
                continue; // the frame that installed it is already gone
            }
            #[cfg(feature = "diagnostics")]
            for f in &self.frames[h.frame + 1..] {
                crate::aotstat::note_frame(f.instrs, f.resumed);
            }
            self.frames.truncate(h.frame + 1);
            self.roots.stack_top = h.stack_top;
            self.roots.shadow.truncate(h.shadow);
            let exc = self.clear_error();
            self.vpush(exc);
            let f = self.frames.last_mut().unwrap();
            f.ip = h.target;
            // A handler target is a jump target, so it is a chunk start -- but
            // only the compiled arity knows which chunk, and an unwind is the
            // one path that arrives without having been told.
            #[cfg(feature = "aot")]
            if f.aot_idx != AOT_NONE {
                f.aot_ip = h.target;
                f.aot_block = AOT_LOOKUP;
            }
            return true;
        }
        false
    }

    // --- program entry ------------------------------------------------------

    /// Host-only: point the native table at the real builtins so `cargo test`
    /// exercises them. On wasm the slots are wasm table indices and this does
    /// not exist -- a static registry here would pin every builtin.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn install_host_natives(&mut self) {
        self.host_natives = crate::builtins::host_registry().iter().map(|(_, f)| *f).collect();
    }

    /// Host-only: register one more builtin -- a unit's, which is not in this
    /// crate's registry -- and return the slot to put in an image.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn add_host_native(&mut self, f: NativeFn) -> u32 {
        self.host_natives.push(f);
        (self.host_natives.len() - 1) as u32
    }

    /// Host-only: the registry index for a builtin, as an image `natives` slot.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn host_native_slot(name: &str) -> Option<u32> {
        crate::builtins::host_registry().iter().position(|(n, _)| *n == name).map(|i| i as u32)
    }

    /// Run the image's top-level initialisers, then call `entry` with `args`.
    pub fn run_program(&mut self, args: Value) -> Value {
        // `args` is a freshly allocated vector that its caller has already
        // unrooted, and every initialiser below allocates. Held only in this
        // Rust local it is a stale pointer by the time the entry function is
        // invoked -- and a stale pointer whose address is later reused by an
        // unrelated object turns into silent corruption of THAT object, not a
        // wrong argument. See `doc/HANDOFF.md`.
        let base = self.mark();
        let ai = self.push(args);
        for i in 0..self.image.init.len() {
            let f = self.image.init[i];
            let c = self.make_closure(f, &[]);
            let _ = self.invoke(c, &[]);
            if self.failed() {
                self.pop_to(base);
                return NIL;
            }
        }
        let entry = self.image.entry;
        let c = self.make_closure(entry, &[]);
        let args = self.r(ai);
        self.pop_to(base);
        // `invoke` enters with the frame stack empty, so `run`'s `base_depth`
        // is 0 and the entry *can* park. Anything deeper -- a comparator, a
        // lazy-seq force -- re-enters with Rust frames underneath and cannot.
        let r = self.invoke(c, &[args]);
        match self.sched_hook {
            // Only ever `Some` in a module that reached the concurrency unit.
            Some(f) => f(self, r),
            None => r,
        }
    }
}

// ---------------------------------------------------------------------------
// The runtime half of the emitted code (`doc/decisions/0013`).
//
// A separate `impl` behind its own feature, so that a module which never asks
// for compiled arities carries none of this. That is 0016's rule applied to an
// optimisation rather than a diagnostic: measured at 7 002 bytes of production
// module, against a budget 0009 had already spent once on instantiating the
// loop twice.
#[cfg(feature = "aot")]
impl Rt {
    /// Call a compiled arity through the wasm table. The signature is fixed by
    /// the emitter: `(rt, fp, ret_to, block)`, no result -- everything it needs
    /// to say it says through the frame and the sync block.
    fn call_aot(&mut self, slot: u32, fp: u32, ret_to: u32, block: u32, sync: u32) {
        #[cfg(target_arch = "wasm32")]
        {
            let f: crate::aot::AotEntry = unsafe { core::mem::transmute(slot as usize) };
            let p = self as *mut Rt;
            f(p, fp, ret_to, block, sync);
        }
        // On a host there is no wasm table and nothing is compiled, so this is
        // unreachable rather than emulated -- pretending otherwise would give
        // `cargo test` a path the shipped module does not have.
        #[cfg(not(target_arch = "wasm32"))]
        {
            let _ = (slot, fp, ret_to, block, sync);
            unreachable!("compiled arities exist only in a wasm module");
        }
    }


    /// How deep compiled code may call compiled code on the wasm stack. Past
    /// this it hands back, so the wasm stack is bounded no matter how deep the
    /// Clojure recursion goes.
    const AOT_MAX_DEPTH: u32 = 48;

    /// Reserve the whole body's operand stack in one go, so that every push the
    /// emitter produces is an unchecked store rather than a bounds test.
    pub(crate) fn aot_reserve(&mut self, n: usize) {
        self.vreserve(n);
    }

    /// `NATIVE`, run from compiled code. A native is a Rust call either way, so
    /// there is nothing to gain by leaving -- and at 18% of executed
    /// instructions this is the single biggest thing worth keeping inside.
    pub(crate) fn aot_native_at(
        &mut self,
        idx: u32,
        argc: usize,
        ip: u32,
        block: u32,
        next_ip: u32,
        next_block: u32,
    ) -> u32 {
        let keep_top = self.roots.stack_top;
        let base = keep_top - argc;
        let r = self.call_native(idx, base, argc);
        self.roots.stack_top = base;
        if self.failed() {
            return self.aot_failed(ip, block, next_ip, next_block, keep_top, keep_top - argc, r);
        }
        self.vpush(r);
        0
    }

    /// A call from compiled code failed. Three outcomes, and conflating any two
    /// of them is a bug this already had:
    ///
    /// * **A park** is handled HERE rather than handed back. Handing it back
    ///   looks tidier -- all the park logic in one place -- but it makes the
    ///   interpreter dispatch the same call a second time, and a parking builtin
    ///   is only re-executable across a RESUME, not twice in a row before the
    ///   host has answered. `open` registered its request twice.
    /// * **A courtesy yield** has already made the call, so the frame must come
    ///   back AFTER it, and the callee and arguments have to come off first --
    ///   a different top from the one a re-execution needs.
    /// * **A throw must not re-execute.** It already happened. Unwind here,
    ///   exactly as the interpreter's arm would.
    #[allow(clippy::too_many_arguments)]
    fn aot_failed(
        &mut self,
        ip: u32,
        block: u32,
        next_ip: u32,
        next_block: u32,
        keep_top: usize,
        yield_top: usize,
        r: Value,
    ) -> u32 {
        if self.thrown.bits() == crate::value::PARK.bits() {
            if let Some(f) = self.frames.last_mut() {
                f.aot_block = block;
            }
            let base = self.run_base;
            match self.parked(ip, keep_top, base, true) {
                Parked::Saved => {
                    self.aot_unwound_out = true;
                }
                Parked::Yielded => {
                    self.roots.stack_top = yield_top;
                    self.vpush(r);
                    if let Some(f) = self.frames.last_mut() {
                        f.ip = next_ip;
                        f.aot_ip = next_ip;
                        f.aot_block = next_block;
                    }
                    self.aot_unwound_out = true;
                }
                Parked::Failed => {
                    if !self.unwind() {
                        self.aot_unwound_out = true;
                    }
                }
            }
            return 1;
        }
        if !self.unwind() {
            self.aot_unwound_out = true;
        }
        1
    }

    /// `CALL`, run from compiled code.
    ///
    /// A callee that is not a closure -- a builtin held in a var, a keyword used
    /// as a function, a map looked up -- completes right here and compiled code
    /// carries on.
    ///
    /// A callee that IS a closure gets its frame pushed here, and then runs on
    /// the wasm stack to a BOUNDED depth. 0013 assumed that and it is right for
    /// the common case: a Clojure call otherwise costs four boundary crossings
    /// and an interpreter dispatch, which in a numeric loop is three of those
    /// per iteration and more than the dispatch it saves. Bounded, because the
    /// wasm stack cannot be suspended and cannot be grown -- past the cap this
    /// hands back, so deep recursion still fails with a catchable
    /// `StackOverflowError` at `MAX_FRAMES` rather than trapping.
    ///
    /// Parking still works at any depth: a park leaves through every level in
    /// turn, each one seeing its callee's frame still present and returning too.
    /// The frames the scheduler saves are OURS, not wasm's, so nothing about the
    /// continuation lives on the stack being unwound.
    pub(crate) fn aot_call_at(
        &mut self,
        argc: usize,
        ip: u32,
        block: u32,
        next_ip: u32,
        next_block: u32,
    ) -> u32 {
        let keep_top = self.roots.stack_top;
        let callee_at = keep_top - argc - 1;
        let callee = self.vat(callee_at);
        if callee.is_heap() && ty(&self.gc.sp, callee.as_heap()) == TY_CLOSURE {
            // `next_ip`, NOT `ip`. This function performs the `enter` itself, so
            // an `ip` still pointing at the CALL would have the interpreter
            // dispatch it a second time when the callee returned.
            if let Some(f) = self.frames.last_mut() {
                f.ip = next_ip;
                f.aot_ip = next_ip;
                f.aot_block = next_block;
            }
            let (before, unwinds) = (self.frames.len(), self.unwinds);
            if !self.enter(callee, callee_at, argc) {
                if !self.unwind() {
                    self.aot_unwound_out = true;
                }
                return 1;
            }
            let f = self.frames.last().unwrap();
            let (idx, cfp, cret) = (f.aot_idx, f.fp, f.ret_to);
            if idx != AOT_NONE && self.aot_depth < Self::AOT_MAX_DEPTH {
                let (slot, depth) = {
                    let a = &self.image.aot[idx as usize];
                    (a.slot, a.depth)
                };
                self.aot_reserve(depth as usize);
                self.frames.last_mut().unwrap().aot_ip = AOT_NEVER;
                crate::aot::resync(self);
                let sync = crate::aot::aot_prologue();
                self.aot_depth += 1;
                self.call_aot(slot, cfp as u32, cret as u32, 0, sync);
                self.aot_depth -= 1;
                // NOT the frame count on its own: an unwind to a handler in this
                // very frame truncates back to exactly the depth the call
                // started at, and compiled code then carried on past the handler
                // with an unwound stack.
                if self.frames.len() == before
                    && self.unwinds == unwinds
                    && !self.aot_unwound_out
                {
                    return 0;
                }
            }
            return 1;
        }
        let r = self.call_value(argc);
        if self.failed() {
            return self.aot_failed(
                ip,
                block,
                next_ip,
                next_block,
                keep_top,
                keep_top - argc - 1,
                r,
            );
        }
        self.vpush(r);
        0
    }

    /// `RETURN`, run from compiled code.
    pub(crate) fn aot_return_here(&mut self) {
        let v = self.vpop();
        let f = self.frames.pop().unwrap();
        #[cfg(feature = "diagnostics")]
        crate::aotstat::note_frame(f.instrs, f.resumed);
        self.handlers.truncate(f.handlers);
        self.roots.stack_top = f.ret_to;
        self.vpush(v);
    }

    /// Run compiled code for the top frame, if it is asking to be entered.
    /// Returns true if the interpreter should return from `run` -- an uncaught
    /// throw inside compiled code unwound past every handler.
    #[inline]
    pub(crate) fn aot_enter(&mut self, ip: u32) -> bool {
        let (idx, fp, ret_to, mut block) = {
            let f = self.frames.last().unwrap();
            (f.aot_idx, f.fp, f.ret_to, f.aot_block)
        };
        if idx == AOT_NONE {
            self.frames.last_mut().unwrap().aot_ip = AOT_NEVER;
            return false;
        }
        if block == AOT_LOOKUP {
            // The one path that arrives without a block: an unwind picked the
            // handler's target, and only the compiled arity knows which of its
            // blocks that is.
            match self.image.aot[idx as usize].block_at(ip) {
                Some(b) => block = b,
                None => {
                    self.frames.last_mut().unwrap().aot_ip = AOT_NEVER;
                    return false;
                }
            }
        }
        #[cfg(feature = "diagnostics")]
        unsafe {
            crate::aotstat::COUNTS[crate::aotstat::C_AOT_ENTRIES] += 1;
        }
        let (slot, depth) = {
            let a = &self.image.aot[idx as usize];
            (a.slot, a.depth)
        };
        // Reserved here rather than by a prologue call, so a compiled body makes
        // no call at all on the way in -- and it is entered once per frame AND
        // once per return-from-call, so a call on that path is not cheap.
        self.aot_reserve(depth as usize);
        self.frames.last_mut().unwrap().aot_ip = AOT_NEVER;
        crate::aot::resync(self);
        let sync = crate::aot::aot_prologue();
        self.call_aot(slot, fp as u32, ret_to as u32, block, sync);
        core::mem::take(&mut self.aot_unwound_out)
    }
}
