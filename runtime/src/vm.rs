//! The interpreter.
//!
//! # Why an interpreter at all (DECISIONS.md#dispatch)
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
    pub const CONST: u8 = 0x01; // u16
    // 0x00, 0x10, 0x1D and 0x1F..0x22 ARE NOT REUSED.
    //
    // `nop`, `jump-if-true`, `list`, the two `*-keep` jumps, `pop-n` and
    // `set-local-keep` were defined here and implemented in all three
    // interpreters, and the compiler emitted NONE of them -- found by running
    // the conformance suite under the opcode census (`DECISIONS.md#kin`).
    // They are removed rather than ported, because maintaining three copies of
    // unreachable code is worse than not having it.
    //
    // The numbers stay retired so that an image built before this cannot be
    // silently misread as something else. A future opcode takes a new number.
    pub const NIL: u8 = 0x02;
    pub const TRUE: u8 = 0x03;
    pub const FALSE: u8 = 0x04;
    pub const INT: u8 = 0x05; // i16
    pub const LOCAL: u8 = 0x06; // u8
    pub const LOCAL_W: u8 = 0x07; // u16
    pub const SET_LOCAL: u8 = 0x08; // u8, pops
    pub const SET_LOCAL_W: u8 = 0x2D; // u16, pops -- the counterpart of LOCAL_W
    pub const UPVAL: u8 = 0x09; // u8
    pub const VAR: u8 = 0x0A; // u16
    pub const SET_VAR: u8 = 0x0B; // u16, pops
    pub const POP: u8 = 0x0C;
    pub const DUP: u8 = 0x0D;
    pub const JUMP: u8 = 0x0E; // i16
    pub const JUMP_IF_FALSE: u8 = 0x0F; // i16, pops
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
    pub const APPLY: u8 = 0x1E; // u8 argc, last is a seq
    /// Push this frame's own closure, so a named `fn` can call itself without
    /// capturing itself (which it could not: it does not exist yet).
    pub const SELF: u8 = 0x23;
    // --- specialised on type ------------------------------------------------
    //
    // Emitted where the COMPILER proved both operands are integers. Each one
    // replaces a NATIVE call: no argc byte, no builtin table lookup, no call
    // through `__indirect_function_table` into a Rust function that re-reads
    // its arguments off the value stack.
    //
    // `^int` means integer, not fixnum -- a value past the fixnum range is a
    // boxed bigint and still answers `int?` -- so each still tests for fixnum
    // and falls back to the same number-tower routine the builtin would have
    // called. What is removed is the CALL, not the check.
    pub const ADD_INT: u8 = 0x24;
    pub const SUB_INT: u8 = 0x25;
    pub const MUL_INT: u8 = 0x26;
    pub const LT_INT: u8 = 0x27;
    pub const LE_INT: u8 = 0x28;
    pub const GT_INT: u8 = 0x29;
    pub const GE_INT: u8 = 0x2A;
    pub const EQ_INT: u8 = 0x2B;
    /// Every type predicate, with the `flint.types/code` as its operand. These
    /// need no type information to EMIT -- `(nil? x)` is a tag test whatever
    /// `x` is -- so they fire on code nobody annotated, which is where the
    /// census says the time actually is: 28% of the native calls in a real
    /// workload are a tag test reached through a call that costs 22.7 ns.
    pub const TYPE_P: u8 = 0x2C; // u8 type code
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
    ///
    /// It is also what makes an image PORTABLE between modules: the slots in an
    /// image belong to the module it was linked against, and a resident loader
    /// re-resolves them by name.
    pub native_names: Vec<u32>,
    pub var_names: Vec<u32>,
    pub entry: u32,
    pub init: Vec<u32>,
    /// A fingerprint of the image bytes this was loaded from.
    ///
    /// A snapshot carries the heap and the VM state and NOT the code
    /// (`DECISIONS.md#snapshots`), which is what keeps it small and is the whole
    /// reason it can be moved. But every frame's `ip`, every constant index and
    /// every var slot in one is an index INTO an image -- so restoring a
    /// snapshot against a different program does not fail, it means something
    /// else. This is what makes that refusable.
    pub fingerprint: u64,
    /// What the compiler decided, as bits. `image::FLAG_PERF` is
    /// `:optimize [perf]`: a runtime that can compile arities at load time
    /// reads this and does, which is how one config means the same thing on
    /// three runtimes that cannot carry it the same way.
    pub flags: u32,
    /// One entry per compiled arity (`DECISIONS.md#emit-wasm-instead-of-dispatch`). Empty in a module
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

/// One activation record.
///
/// **Packed deliberately, and every field is the width it means.** `fp`,
/// `ret_to` and `handlers` were `usize` and were truncated to `u32` on the way
/// into a thread save -- so the wider form was never anything but padding that
/// looked like precision, and a reader had to check the serialiser to find out
/// what the real range was. A value-stack index and a handler depth are `u32`
/// here and `u32` there.
///
/// The size is measured by `frame_layout` rather than asserted, so a field
/// added later shows up as a number instead of as a failure somewhere else.
pub struct Frame {
    /// Frame pointer: where this frame's locals begin in the value stack.
    pub fp: u32,
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
    pub ret_to: u32,
    /// Handler-stack depth on entry. A DEPTH, not an index into anything
    /// long-lived, so it is bounded by how deep `try` nests.
    pub handlers: u32,
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
    /// Flags. `FRAME_RESUMED` says this frame has parked and come back --
    /// 0013's pathological case is a loop that parks per iteration, and without
    /// re-entry points every instruction executed in a resumed frame is one the
    /// compiled body never gets to run.
    ///
    /// A WORD RATHER THAN A `bool`, so the three bytes the alignment would
    /// spend anyway have a name and somewhere to go. The rest is reserved: if
    /// the re-entry design ever wants "this frame has a record on the re-entry
    /// stack" as a flag, it belongs here and costs nothing.
    #[cfg(feature = "diagnostics")]
    pub flags: u32,
}

/// This frame has parked and been restored from a thread save.
#[cfg(feature = "diagnostics")]
pub const FRAME_RESUMED: u32 = 1;

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
        // TEST, THEN CHARGE -- and the order is the whole of a defect that
        // made gas depend on `SLICE`.
        //
        // This ran `steps += 1` first and tested after, and `tick` is called
        // BEFORE the opcode is read. So on the iteration that trips, the
        // instruction had been CHARGED and NOT EXECUTED; after the resume it
        // was dispatched and charged again. Exactly one double charge per
        // slice.
        //
        // Measured on a counting loop that allocates nothing, so allocation
        // billing could not confound it: against the ports the gap was not
        // proportional to the work, it EQUALLED the slice count --
        // 2/11/20/42/86 against 3/11/22/44/88 slices. The ports test first and
        // charge second and were right (`DECISIONS.md#resource-limits`).
        //
        // It also made the same program cost different gas at different
        // preemption quanta, which a program's instruction count must not do.
        if rt.steps >= rt.checkpoint {
            return true;
        }
        rt.steps += 1;
        false
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

    /// `[fnidx, ...upvals, meta]`.
    ///
    /// METADATA IS THE LAST SLOT, not the second, and that is worth a sentence
    /// because the obvious layout is the expensive one. Putting it after the
    /// function index would shift every upvalue by one, and `UPVAL` is indexed
    /// arithmetically in five places -- two interpreter loops, and three AOT
    /// emitters that bake the offset into generated code. At the end, every
    /// `1 + i` stays exactly as it was and the only files that change are this
    /// one and `meta_slot`.
    ///
    /// A closure could not carry metadata at all before, and `with_meta`
    /// answered by SILENTLY RETURNING THE VALUE UNCHANGED -- so
    /// `(meta (with-meta f {:a 1}))` was nil and nothing said why. That is what
    /// made per-function protocol implementations impossible, since dispatch
    /// looks at metadata first.
    pub fn make_closure(&mut self, fn_idx: u32, upvals: &[Value]) -> Value {
        let base = self.mark();
        for v in upvals {
            self.push(*v);
        }
        let a = self.alloc(TY_CLOSURE, 2 + upvals.len() as u32);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        self.set_slot(a, 0, Value::fixnum(fn_idx as i64));
        for i in 0..upvals.len() {
            let v = self.r(base + i);
            self.set_slot(a, 1 + i as u32, v);
        }
        self.set_slot(a, 1 + upvals.len() as u32, NIL);
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
        self.set_slot(a, 0, Value::fixnum(native_idx as i64));
        self.set_slot(a, 1, name);
        Value::heap(a)
    }

    /// Anything callable in a Clojure call position: fns, but also keywords,
    /// maps, sets and vectors.
    pub fn is_callable(&self, v: Value) -> bool {
        self.is_fn(v) || self.is_keyword(v) || self.is_map(v) || self.is_set(v) || self.is_vector(v)
    }

    // --- native dispatch ---------------------------------------------------

    /// The half of a specialised integer operation that is not the common
    /// case: a bigint operand, a result past the fixnum range, an overflow, or
    /// -- if a type tag were ever wrong -- something that is not a number at
    /// all. Out of line because the interpreter loop is emitted twice and this
    /// is never the hot path. It answers exactly as the builtin it replaced
    /// would, including the refusal.
    #[inline(never)]
    pub(crate) fn int_binop_slow(&mut self, opcode: u8, x: Value, y: Value) -> Value {
        match opcode {
            op::ADD_INT => self.num_add(x, y),
            op::SUB_INT => self.num_sub(x, y),
            op::MUL_INT => self.num_mul(x, y),
            _ => {
                if !self.is_number(x) || !self.is_number(y) {
                    return self.throw_not_a_number(x, y);
                }
                let c = self.num_cmp(x, y);
                Value::boolean(match opcode {
                    op::LT_INT => c < 0,
                    op::LE_INT => c <= 0,
                    op::GT_INT => c > 0,
                    op::GE_INT => c >= 0,
                    _ => c == 0,
                })
            }
        }
    }

    #[inline]
    fn call_native(&mut self, idx: u32, base: usize, argc: usize) -> Value {
        #[cfg(feature = "diagnostics")]
        unsafe {
            if let Some(c) = crate::aotstat::NATIVE_CALLS.get_mut(idx as usize) {
                *c += 1;
            }
            // Would a fixnum specialisation have applied here? Read off the
            // real operands rather than inferred from the source, because the
            // question a specialising compiler faces is what actually arrives.
            // A zero-arg call is vacuously all-fixnum and would specialise to
            // nothing, so it does not count.
            if argc > 0 && (0..argc).all(|k| self.vat(base + k).is_fixnum()) {
                if let Some(c) = crate::aotstat::NATIVE_FIX.get_mut(idx as usize) {
                    *c += 1;
                }
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
                    .map(|f| f.ret_to as usize)
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
    /// `invoke`, with the arguments taken from a CONTIGUOUS RUN of shadow-stack
    /// roots rather than a host slice.
    ///
    /// The `(base, n)` convergence `list_from_roots` already uses, and here it
    /// buys more than portability: a generated source cannot hold a host array
    /// at all, and the callers that would have built one are building it PER
    /// ITERATION. `table_reduce_column` calls this once per row, and its
    /// arguments -- the accumulator and the cell -- are already rooted and
    /// already adjacent, so there is nothing left to allocate.
    ///
    /// The body is `invoke`'s with the loop reading roots instead of a slice.
    /// Sharing more than that would mean an iterator generic over both, which
    /// is a bigger thing than the six lines it would save.
    pub fn invoke_roots(&mut self, f: Value, base: usize, n: u32) -> Value {
        let save = self.roots.stack_top;
        self.vreserve(n as usize + 1);
        self.vpush(f);
        for i in 0..n {
            let a = self.r(base + i as usize);
            self.vpush(a);
        }
        let r = self.call_value(n as usize);
        if self.park_on.is_nil() {
            self.roots.stack_top = save;
        }
        r
    }

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
            // `describe` AND NOT `{:?}`, so the two ports can say the same
            // thing. It is generated from one source and identical on all
            // three; a Rust `Debug` is not portable and the heap arm below was
            // printing a raw type NUMBER for want of anything better.
            let what = self.describe(callee);
            let w = self.where_am_i();
            let msg = alloc::format!("value is not a function ({what}, {argc} args) in {w}");
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
            // A MAP ENTRY IS A VECTOR here as everywhere else. `vector?`
            // answers true for one, `nth`, `get`, `conj` and `assoc` all treat
            // it as a vector, and `([:x :y] 1)` works -- but CALLING one threw,
            // on all three runtimes. Clojure's is callable and flint's own
            // predicate already says it should be.
            //
            // The bound is checked HERE and not delegated to `slot_or_nth`,
            // which reads the slot without one: it is safe only because its
            // callers pass 0 and 1, and a call site takes whatever a program
            // wrote.
            TY_VEC | TY_MAPENTRY => {
                let is_entry = ty(&self.gc.sp, callee.as_heap()) == TY_MAPENTRY;
                let r = if argc == 1 {
                    let k = self.vat(callee_at + 1);
                    match self.as_i64(k) {
                        Some(i) if i >= 0 && is_entry => {
                            if i < 2 {
                                self.slot(callee, i as u32)
                            } else {
                                self.throw_str("IndexOutOfBoundsException", "index out of range")
                            }
                        }
                        Some(i) if i >= 0 => {
                            // NOT_FOUND as the default, because out of range
                            // has to RAISE here rather than answer nil, and
                            // NOT_FOUND is the one value that cannot be an
                            // element of a vector.
                            let x = self.vec_nth(callee, i as u32, crate::value::NOT_FOUND);
                            if x == crate::value::NOT_FOUND {
                                self.throw_str("IndexOutOfBoundsException", "index out of range")
                            } else {
                                x
                            }
                        }
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
                self.roots.stack_top = callee_at;
                let what = self.describe(callee);
                let w = self.where_am_i();
                let msg = alloc::format!("value is not a function ({what}, {argc} args) in {w}");
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
            } else if self.is_table_ref(coll) {
                // `(:name row)` on a row ref, which is how a table is read.
                self.coll_get(coll, kw, dflt)
            } else if self.is_tagged(coll) {
                // `(:tag x)` and `(:form x)`, which is how anyone actually
                // reads one (`DECISIONS.md#tagged-literals`). This arm used to fall to
                // `dflt` for everything that was not a map or a set, so
                // `(get x :tag)` answered and `(:tag x)` did not -- the same
                // lookup by two spellings disagreeing.
                self.coll_get(coll, kw, dflt)
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
                    let name = self.roots.shared.consts.get(namec).copied().unwrap_or(NIL);
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
                let l = self.list_from_roots(base, n as u32);
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
            fp: fp as u32,
            ip: code,
            end,
            ret_to: callee_at as u32,
            handlers: self.handlers.len() as u32,
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
            flags: 0,
        });
        true
    }

    /// Run until the frame stack drops back to `base_depth`.
    ///
    /// Two instantiations, chosen **once at entry** rather than branched on per
    /// instruction (`DECISIONS.md#resource-limits`). With no gas limit and no scheduler
    /// slice, `NoBudget::tick` is a `false` the optimiser deletes along with the
    /// counter, and the loop has no budget machinery in it at all.
    pub fn run(&mut self, base_depth: usize) -> Value {
        // Two instantiations, not four. Monomorphising the AOT check as well
        // would double the biggest function in the module a second time, and
        // 0009 traded a known budget for the free loop -- spending it again on
        // a feature most modules do not use is exactly what that budget exists
        // to stop. One predictable comparison instead.
        // A sandbox with more than one executor MUST poll, or a thread that
        // never allocates never reaches a safepoint and the collector waits
        // for it forever. `Counting` is the policy that polls, so having
        // another executor turns counting on the same way a gas limit does.
        //
        // This is what keeps `DECISIONS.md#resource-limits`'s free loop free where it
        // matters: one executor with no limit still runs `NoBudget`, whose
        // `tick` is a constant the optimiser deletes. The poll is compiled in
        // only when there is something to poll FOR.
        if self.counting() || self.has_peers() {
            self.run_with::<Counting>(base_depth)
        } else {
            self.run_with::<NoBudget>(base_depth)
        }
    }

    /// Is another executor sharing this heap?
    #[cfg(feature = "parallel")]
    #[inline]
    fn has_peers(&self) -> bool {
        self.executor_count() > 1
    }

    #[cfg(not(feature = "parallel"))]
    #[inline(always)]
    fn has_peers(&self) -> bool {
        false
    }

    fn run_with<B: BudgetPolicy>(&mut self, base_depth: usize) -> Value {
        // Saved and restored: `run` re-enters for a lazy-seq force, a
        // comparator, `map`.
        let outer_base = core::mem::replace(&mut self.run_base, base_depth);
        let out = self.run_inner::<B>(base_depth);
        self.run_base = outer_base;
        out
    }

    fn run_inner<B: BudgetPolicy>(&mut self, base_depth: usize) -> Value {
        #[cfg(feature = "aot")]
        let aot_on = !self.image.aot.is_empty();
        // DECISIONS.md#emit-wasm-instead-of-dispatch's region histogram. `run` is Model A -- the
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
                (f.ip, f.fp as usize)
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
                // The safepoint, and it is HERE for the reason above rather
                // than by coincidence: `ip` is written back and every live
                // value is on the value stack, so a thread stopped at this
                // point can have its roots walked and its objects moved. Two
                // instructions earlier that would not be true.
                #[cfg(feature = "parallel")]
                self.safepoint();
                // One comparison covers both budgets; which one fired is a cold
                // path. In a threaded program the slice is doing double duty as
                // preemption -- running out means "your turn is over" rather
                // than "you have hung" -- so gas costs nothing extra exactly
                // where concurrency already made the counter necessary.
                if self.gas_limit != 0 && self.steps >= self.gas_limit {
                    // A BATCH BOUNDARY, USUALLY -- not a blown budget.
                    //
                    // Under a shared budget `gas_limit` is the end of this
                    // executor's current batch rather than the sandbox's cap,
                    // so the first question is whether the SANDBOX is out of
                    // gas, not whether this executor reached a number. When it
                    // is not, this publishes what was spent, moves the local
                    // limit forward and carries on (`DECISIONS.md#resource-limits`).
                    //
                    // The answer is TRUE when there is no shared budget, which
                    // is every inline sandbox and the whole wasm build -- so
                    // the old meaning survives without a second test here.
                    #[cfg(feature = "parallel")]
                    if !self.flush_shared_gas() {
                        continue;
                    }
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
                    // AND GO ROUND AGAIN, so the instruction is CHARGED.
                    //
                    // `tick` tests before it charges, so the iteration that
                    // trips does not increment `steps`. Falling through from
                    // here executed the instruction anyway -- one instruction
                    // run for free, every time a slice boundary landed where
                    // the runtime could not preempt. That is a deferred
                    // preemption, and forcing a lazy seq is the common way to
                    // be inside one (`Seqwalk` re-enters the interpreter with
                    // Rust frames underneath).
                    //
                    // The jvm and the clr already `continue` here and so
                    // charged it. This was the whole of `bin/conform-hosts`'s
                    // last gas difference: a complete decomposition of `steps`
                    // balanced on the ports and came out NEGATIVE on native,
                    // by exactly the number of these
                    // (`DECISIONS.md#resource-limits`).
                    //
                    // It cannot spin: the slice end has just been moved past
                    // `steps`, so the next tick charges and proceeds.
                    continue;
                } else {
                    let at = self.steps + crate::conc::SLICE;
                    self.set_slice_end(at);
                    continue;
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
                    if f.flags & FRAME_RESUMED != 0 {
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
                op::CONST => {
                    let k = self.u16_at(ip) as usize;
                    ip += 2;
                    let v = self.roots.shared.consts[k];
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
                op::SET_LOCAL_W => {
                    let i = self.u16_at(ip) as usize;
                    ip += 2;
                    let v = self.vpop();
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
                    let v = self.roots.shared.globals[k].get();
                    self.vpush(v);
                }
                op::SET_VAR => {
                    let k = self.u16_at(ip) as usize;
                    ip += 2;
                    let v = self.vpop();
                    self.roots.shared.globals[k].set(v);
                }
                op::POP => {
                    self.roots.stack_top -= 1;
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
                        crate::aotstat::note_frame(f.instrs, f.flags & FRAME_RESUMED != 0);
                        self.handlers.truncate(f.handlers as usize);
                        let dest = f.ret_to as usize;
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
                            crate::aotstat::note_frame(f.instrs, f.flags & FRAME_RESUMED != 0);
                            self.handlers.truncate(f.handlers as usize);
                            let v = self.vpop();
                            self.roots.stack_top = f.ret_to as usize;
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
                    crate::aotstat::note_frame(f.instrs, f.flags & FRAME_RESUMED != 0);
                    self.handlers.truncate(f.handlers as usize);
                    self.roots.stack_top = f.ret_to as usize;
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
                    // `2 + n`, and the trailing slot is the metadata one --
                    // see `make_closure`, which this arm deliberately does not
                    // call because the upvalues are already on the value stack
                    // and rooted. Two allocation sites for one layout is how a
                    // layout change goes half-applied, so they carry the same
                    // note.
                    let a = self.alloc(TY_CLOSURE, 2 + n as u32);
                    if a == 0 {
                        self.oom_unwind();
                        if !self.unwind() {
                            return NIL;
                        }
                        continue;
                    }
                    self.set_slot(a, 0, Value::fixnum(fn_idx as i64));
                    for i in 0..n {
                        let v = self.roots.stack[base + i];
                        self.set_slot(a, 1 + i as u32, v);
                    }
                    self.set_slot(a, 1 + n as u32, NIL);
                    self.roots.stack_top = base;
                    self.vpush(Value::heap(a));
                }
                // The specialised integer operations. Both operands are
                // already on the value stack, so the fast path touches no
                // heap, commits nothing, and roots nothing.
                //
                // The fast path is INLINE and the rest is a call. The whole
                // arm inline costs 6,483 module bytes -- the interpreter loop
                // is instantiated twice (0009: a run with no budget has no
                // counter in it), so everything here is paid for twice. The
                // slow path is a bigint, an overflow, or a wrong tag, and none
                // of those is worth inlining twice.
                op::ADD_INT | op::SUB_INT | op::MUL_INT | op::LT_INT | op::LE_INT
                | op::GT_INT | op::GE_INT | op::EQ_INT => {
                    let y = self.vpop();
                    let x = self.vpop();
                    let fast = if x.is_fixnum() && y.is_fixnum() {
                        let (p, q) = (x.as_fixnum(), y.as_fixnum());
                        match opcode {
                            op::ADD_INT => p.checked_add(q).filter(|v| Value::fits_fixnum(*v)).map(Value::fixnum),
                            op::SUB_INT => p.checked_sub(q).filter(|v| Value::fits_fixnum(*v)).map(Value::fixnum),
                            op::MUL_INT => p.checked_mul(q).filter(|v| Value::fits_fixnum(*v)).map(Value::fixnum),
                            op::LT_INT => Some(Value::boolean(p < q)),
                            op::LE_INT => Some(Value::boolean(p <= q)),
                            op::GT_INT => Some(Value::boolean(p > q)),
                            op::GE_INT => Some(Value::boolean(p >= q)),
                            _ => Some(Value::boolean(p == q)),
                        }
                    } else {
                        None
                    };
                    match fast {
                        Some(v) => self.vpush(v),
                        None => {
                            commit!();
                            let r = self.int_binop_slow(opcode, x, y);
                            if self.failed() {
                                if !self.unwind() {
                                    return NIL;
                                }
                                continue;
                            }
                            self.vpush(r);
                        }
                    }
                }
                op::TYPE_P => {
                    let code = self.u8_at(ip);
                    ip += 1;
                    let v = self.vpop();
                    let r = self.type_p(code as u32, v);
                    self.vpush(Value::boolean(r));
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
                        // path. See DECISIONS.md#threads-and-ports.
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
                    let opcode_at = ip - 1;
                    let argc = self.u8_at(ip) as usize;
                    ip += 1;
                    commit!();
                    // stack: callee, a1..a(argc-1), seq
                    let operands_at = self.roots.stack_top - argc - 1;
                    let apply_callee = self.vat(operands_at);
                    let is_closure = apply_callee.is_heap()
                        && ty(&self.gc.sp, apply_callee.as_heap()) == TY_CLOSURE;

                    // TWO PATHS, because a park has to be survivable on both
                    // and they survive it differently (`DECISIONS.md#system-namespaces-and-deps`).
                    //
                    // A CLOSURE IS ENTERED, not called -- exactly as `op::CALL`
                    // does it, and for the reason `op::CALL` states: "inline
                    // the frame push so Clojure recursion uses our frame stack,
                    // not the Rust one". `call_value` on a closure re-enters
                    // the interpreter RECURSIVELY, so a park inside the callee
                    // had a Rust frame between it and the green thread, and a
                    // Rust frame's state cannot be saved.
                    //
                    // A NATIVE cannot be entered -- it has no frame -- so it is
                    // called, and survives a park by being RE-EXECUTED. That
                    // needs the operands still in place, which is why this path
                    // copies rather than consuming: the seq stays where it is
                    // and the call is built above it.
                    if is_closure {
                        let seq = self.vpop();
                        let mut spread = 0usize;
                        let si = self.push(seq);
                        let mut cur = self.seq(self.r(si));
                        self.set_r(si, cur);
                        while !self.r(si).is_nil() {
                            if !self.charge_tick(spread as u64, 1, "apply") {
                                self.pop_to(si);
                                break;
                            }
                            let f = self.first(self.r(si));
                            self.vpush(f);
                            spread += 1;
                            cur = self.next(self.r(si));
                            self.set_r(si, cur);
                        }
                        if self.failed() {
                            self.pop_to(si);
                            self.roots.stack_top = operands_at;
                            if !self.unwind() {
                                return NIL;
                            }
                            continue;
                        }
                        self.pop_to(si);
                        let total = argc - 1 + spread;
                        let at = self.roots.stack_top - total - 1;
                        if !self.enter(apply_callee, at, total) {
                            if !self.unwind() {
                                return NIL;
                            }
                        }
                        continue;
                    }

                    // The native path. `callee, a1..a(argc-1)` are COPIED above
                    // the operands and the seq is spread above the copy, so
                    // rewinding to `operands_at + argc + 1` leaves exactly what
                    // this instruction expects to find when it runs again.
                    let seq = self.vpeek(0);
                    for i in 0..argc {
                        let v = self.vat(operands_at + i);
                        self.vpush(v);
                    }
                    let mut spread = 0usize;
                    let si = self.push(seq);
                    let mut cur = self.seq(self.r(si));
                    self.set_r(si, cur);
                    while !self.r(si).is_nil() {
                        // The same hazard as the `flint/apply` BUILTIN: a
                        // spread walks a whole sequence under one bytecode
                        // instruction, so it gets the same tick -- a bound that
                        // holds on one of two paths is not a bound.
                        if !self.charge_tick(spread as u64, 1, "apply") {
                            self.pop_to(si);
                            break;
                        }
                        let f = self.first(self.r(si));
                        self.vpush(f);
                        spread += 1;
                        cur = self.next(self.r(si));
                        self.set_r(si, cur);
                    }
                    if self.failed() {
                        self.pop_to(si);
                        self.roots.stack_top = operands_at;
                        if !self.unwind() {
                            return NIL;
                        }
                        continue;
                    }
                    self.pop_to(si);
                    let total = argc - 1 + spread;
                    let r = self.call_value(total);
                    if self.thrown.bits() == crate::value::PARK.bits() {
                        // RE-EXECUTABLE now: the operands are still there, so
                        // rewinding to this instruction re-copies, re-spreads
                        // and calls again. Which is the same contract every
                        // other parking call already has.
                        match self.parked(opcode_at, operands_at + argc + 1, base_depth, true) {
                            Parked::Saved => return NIL,
                            Parked::Yielded => {
                                self.roots.stack_top = operands_at;
                                self.vpush(r);
                                continue;
                            }
                            Parked::Failed => {
                                self.roots.stack_top = operands_at;
                                if !self.unwind() {
                                    return NIL;
                                }
                                continue;
                            }
                        }
                    }
                    if !self.park_on.is_nil() {
                        // A native cannot park "further in" -- it has no frame
                        // to save -- so this is the courtesy-yield shape, and
                        // the operands go because nothing will re-execute.
                        self.roots.stack_top = operands_at;
                        return NIL;
                    }
                    if self.failed() {
                        self.roots.stack_top = operands_at;
                        if !self.unwind() {
                            return NIL;
                        }
                    } else {
                        self.roots.stack_top = operands_at;
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
        self.roots.stack[self.frames.last().unwrap().ret_to as usize]
    }

    fn frame_closure(&self, i: usize) -> Value {
        self.roots.stack[self.frames[i].ret_to as usize]
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
                let v = self.roots.shared.consts.get(namec).copied().unwrap_or(NIL);
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
                let v = self.roots.shared.consts.get(namec).copied().unwrap_or(NIL);
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
            self.gc.set_heap_limit(limit.saturating_add(1024 * 1024) as u32);
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
        // A HANDLER BELOW THIS `run` IS NOT THIS `run`'s TO JUMP TO.
        //
        // `run` re-enters for a delay's thunk and a table fold's callback, and
        // when it does there is a NATIVE frame waiting underneath holding
        // shadow-stack indices. Unwinding to a handler installed on the other
        // side of that frame truncates the shadow stack under it, resumes the
        // guest, and eventually returns to native code that reads a root which
        // is no longer there:
        //
        //     (try @(delay (throw (ex-info "no" {}))) (catch Exception e :caught))
        //
        // panicked "index out of bounds: the len is 2 but the index is 2" on
        // native and trapped `unreachable` on wasm, where Clojure catches it.
        // Without the surrounding `try` there was no handler to find and the
        // throw propagated correctly, which is why it took a `catch` to see.
        //
        // Handing the throw BACK instead is what the re-entrant callers already
        // expect: both ask `is-thrown` immediately and unwind their own roots.
        // The outer `run` then finds the handler with its own frames intact.
        while let Some(frame) = self.handlers.last().map(|h| h.frame) {
            if frame < self.run_base {
                return false;
            }
            let h = self.handlers.pop().unwrap();
            if h.frame >= self.frames.len() {
                continue; // the frame that installed it is already gone
            }
            #[cfg(feature = "diagnostics")]
            for f in &self.frames[h.frame + 1..] {
                crate::aotstat::note_frame(f.instrs, f.flags & FRAME_RESUMED != 0);
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
        *self.host_natives = crate::builtins::host_registry().iter().map(|(_, f)| *f).collect();
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
        // INITIALISERS RUN WITHOUT PREEMPTION.
        //
        // A slice is armed the moment a scheduler exists, and a scheduler can
        // exist before this function is ever entered -- a host that installs a
        // port at construction (`DECISIONS.md#ports-are-the-hosts`) creates one. The loop
        // below then ran namespace initialisers under a live slice, and a yield
        // inside one is DISCARDED here (`let _ =`): the thread came back with
        // `park_on` still set, `settle` read it as a yield, saved a half-built
        // state and never recorded the entry's value. The run reported "the
        // entry function did not return a string", and it reported it for a
        // program whose entry was `(defn main [_] "constant")`.
        //
        // There is nothing to preempt here anyway: no other thread can be
        // runnable until the program has been initialised.
        // NOT PREEMPTIBLE, BUT STILL BILLED -- and those are two questions that
        // `checkpoint` answers with one number.
        //
        // This set `slice_end` to 0, which stops preemption (what the paragraph
        // above needs) and ALSO stops counting, because `refresh_checkpoint`
        // then leaves `checkpoint` at `u64::MAX` and `run` dispatches to
        // `NoBudget`, whose `tick` never increments. Initialisers were
        // therefore FREE here and BILLED on the jvm and the clr, whose
        // interpreters increment unconditionally -- measured at 226 steps
        // against 8 135 reaching the entry on the same image.
        //
        // Initialisers are top-level forms, which is guest code, and the
        // standing decision is that all guest code is charged
        // (`DECISIONS.md#resource-limits`). So the ports were right. A slice
        // end that cannot be reached suspends preemption without suspending
        // the counter: `counting()` stays true because the checkpoint is not
        // the sentinel, and `steps` never climbs anywhere near it.
        let slice = self.slice_end;
        self.set_slice_end(u64::MAX - 1);
        for i in 0..self.image.init.len() {
            let f = self.image.init[i];
            let c = self.make_closure(f, &[]);
            let _ = self.invoke(c, &[]);
            // AND A GENUINE PARK IS REFUSED, where a yield is merely impossible.
            //
            // The paragraph above disarms preemption so nothing YIELDS in here.
            // A park is the other thing: a top-level form that asks the HOST
            // comes back with `park_on` set and no way to be resumed, because
            // this loop is not re-entrant -- there is no saved position to come
            // back to. Discarding it is what `let _` does, and the result was a
            // half-built program that reported "the entry function returned
            // nil" for an entry that was never reached.
            //
            // `ports-are-the-hosts` says a sandbox that cannot ask is TOLD so rather than
            // parked. This is the same sentence one phase earlier: it cannot
            // ask HERE, so say that, at the form that asked.
            if !self.park_on.is_nil() && !self.failed() {
                self.park_on = NIL;
                self.throw_str(
                    "IllegalStateException",
                    "a top-level form asked the host while the program was still \
                     initialising, and cannot wait for the answer there. Move the \
                     call into a function the entry reaches.",
                );
            }
            if self.failed() {
                self.set_slice_end(slice);
                self.pop_to(base);
                return NIL;
            }
        }
        // Re-armed for the ENTRY, which may legitimately park: it is invoked
        // with the frame stack empty, so a park there is one `settle` handles.
        self.set_slice_end(slice);
        let entry = self.image.entry;
        let c = self.make_closure(entry, &[]);
        // ROOTED ACROSS THE CALL, both of them.
        //
        // This used to `pop_to(base)` first and then pass two Rust locals into
        // `invoke`, which allocates a frame before it roots anything -- so a
        // collection there left the entry closure and the argument vector
        // pointing into the abandoned semispace. It is the same mistake the
        // comment at the top of this function describes, one line further down,
        // and it survived because it needs a collection to land inside that
        // window: a program whose entry ran with no scheduler never allocated
        // enough to see it. Installing a port before `main` changed the timing
        // and it became reproducible -- the entry returned nil, `settle`
        // recorded nil, and the run reported "the entry function did not return
        // a string".
        let ci = self.push(c);
        let (cv, av) = (self.r(ci), self.r(ai));
        // `invoke` enters with the frame stack empty, so `run`'s `base_depth`
        // is 0 and the entry *can* park. Anything deeper -- a comparator, a
        // lazy-seq force -- re-enters with Rust frames underneath and cannot.
        let r = self.invoke(cv, &[av]);
        self.pop_to(base);
        match self.sched_hook {
            // Only ever `Some` in a module that reached the concurrency unit.
            Some(f) => f(self, r),
            None => r,
        }
    }

    /// Re-enter the scheduler after the host has answered a parked thread.
    ///
    /// What a host calls when `run_program` came back with `status == 2`. The
    /// answer was already recorded by `host_continue` or `host_deliver`; this
    /// only starts the loop again.
    pub fn resume(&mut self) -> Value {
        crate::conc::resume(self)
    }

    /// Run the image's initialisers, once. A sandbox serves many calls
    /// (`DECISIONS.md#structured-ports`) and they must not re-run per call -- the state a
    /// program sets up at load is the state every call after it sees.
    pub fn ensure_started(&mut self) -> bool {
        if self.started() {
            return true;
        }
        self.set_started(true);
        // THE STACK IS RESTORED ON EVERY EXIT, including the refusal below.
        //
        // A park leaves the value stack holding its continuation -- that is the
        // point of not truncating it -- and this loop cannot resume one, so it
        // has to be cut back. `run_program` marks and pops for exactly this;
        // without it the loop returned with a stack the park had cut down and
        // the next `vpop` read index `usize::MAX`. A panic, from a program
        // whose only sin was a top-level `(fs/exists? ..)`.
        let base = self.mark();
        // WITHOUT PREEMPTION, for the reason `run_program` gives at length: a
        // slice is armed the moment a scheduler exists, a yield inside an
        // initialiser is discarded here (`let _ =`), and the thread comes back
        // with `park_on` still set. There is nothing to preempt anyway -- no
        // other thread can be runnable until the program is initialised.
        //
        // It matters more here than there. A CALL runs the initialisers on
        // first use (`DECISIONS.md#structured-ports` step 5), so this now happens with a
        // scheduler already built and a slice already counting down.
        let slice = self.slice_end;
        self.set_slice_end(0);
        for i in 0..self.image.init.len() {
            let f = self.image.init[i];
            let c = self.make_closure(f, &[]);
            let _ = self.invoke(c, &[]);
            // A GENUINE PARK IS REFUSED, where a yield is merely impossible.
            //
            // The line above disarms preemption so nothing YIELDS in here. A
            // park is the other thing: a top-level form that asks the HOST
            // comes back with `park_on` set and no way to be resumed, because
            // this loop is not re-entrant -- there is no saved position to come
            // back to. Discarding it is what `let _` does, and the thread then
            // carries on with a value stack the park had already cut down: the
            // symptom is `index out of bounds: the index is <usize::MAX>` out
            // of `vpop`, which is a panic and not a diagnosis.
            //
            // `ports-are-the-hosts` says a sandbox that cannot ask is TOLD so
            // rather than parked. This is that sentence one phase earlier: it
            // cannot ask HERE, so say so at the form that asked.
            //
            // `run_program` has carried this since a top-level `slurp` found
            // it. This copy did not, and nothing reached it until the entry
            // itself became a CALL (`DECISIONS.md#bridges-are-the-only-door`) --
            // which runs the initialisers through here instead.
            //
            // NOT GUARDED ON `!failed()`. A park travels as `thrown == PARK`,
            // so `failed()` is TRUE for one; the copy in `run_program` carries
            // that condition and this one deliberately does not. Both halves of
            // the park state are cleared explicitly, or the scheduler goes on
            // believing this thread is waiting for an answer.
            if !self.park_on.is_nil() {
                self.park_on = NIL;
                if self.thrown == crate::value::PARK {
                    self.thrown = NIL;
                }
                // The stack this thread was parked on is about to be cut back,
                // so nothing may try to resume it. See `abandon_current_thread`.
                self.abandon_current_thread();
                self.throw_str(
                    "IllegalStateException",
                    "a top-level form asked the host while the program was still \
                     initialising, and cannot wait for the answer there. Move the \
                     call into a function the entry reaches.",
                );
            }
            if self.failed() {
                self.set_slice_end(slice);
                self.pop_to(base);
                return false;
            }
        }
        self.set_slice_end(slice);
        self.pop_to(base);
        true
    }

    /// The var a qualified name refers to, or `None`.
    ///
    /// Names are compared as text because that is the only durable identifier
    /// a var has across a compile: a slot index belongs to whichever image
    /// produced it (the same argument `construe-integration-bar` makes for builtins).
    pub fn var_named(&mut self, want: &str) -> Option<u32> {
        for i in 0..self.image.var_names.len() {
            let namec = self.image.var_names[i] as usize;
            let nv = self.roots.shared.consts.get(namec).copied().unwrap_or(NIL);
            let mut b = crate::rt::sbuf();
            if self.as_str(nv, &mut b) == Some(want) {
                return Some(i as u32);
            }
        }
        None
    }

    /// Call a named function with the arguments in `args`.
    ///
    /// This is what a sandbox does, and `run_program` is now one special case
    /// of it: the function the CLI calls when it is not told which.
    pub fn call_named(&mut self, name: &str, args: &[Value]) -> Result<Value, alloc::string::String> {
        if !self.ensure_started() {
            return Ok(NIL); // the error is in `self.thrown`, rendered by the caller
        }
        let idx = self
            .var_named(name)
            .ok_or_else(|| alloc::format!("this image has no `{name}`"))?;
        let f = self.roots.shared.globals.get(idx as usize).map_or(NIL, |g| g.get());
        if f.is_nil() {
            return Err(alloc::format!("`{name}` is not a function"));
        }
        let base = self.mark();
        self.push(f);
        for a in args {
            self.push(*a);
        }
        let held: alloc::vec::Vec<Value> = (1..=args.len()).map(|i| self.r(base + i)).collect();
        let fv = self.r(base);
        let r = self.invoke(fv, &held);
        self.pop_to(base);
        Ok(match self.sched_hook {
            Some(hook) => hook(self, r),
            None => r,
        })
    }

}

// ---------------------------------------------------------------------------
// The runtime half of the emitted code (`DECISIONS.md#emit-wasm-instead-of-dispatch`).
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
    fn call_aot(&mut self, slot: u32, fp: u32, ret_to: u32, block: u32, sync: usize) {
        #[cfg(target_arch = "wasm32")]
        {
            let f: crate::aot::AotEntry = unsafe { core::mem::transmute(slot as usize) };
            let p = self as *mut Rt;
            f(p, fp, ret_to, block, sync);
        }
        // Natively a slot is an INDEX into a table the artifact registered, not
        // an address: a natively linked program's compiled arities are ordinary
        // symbols, and nothing turns one into a wasm table index
        // (`DECISIONS.md#llvm-ir-target`).
        //
        // This used to be `unreachable!`, and that was right while nothing but
        // a wasm module could hold compiled code. It is now reachable for an
        // artifact from `:to :llvm` and STILL unreachable for everything else:
        // an image whose `aot` table is empty never asks, and one that asks
        // without having registered gets the panic rather than a jump through
        // whatever integer the slot happened to be.
        #[cfg(not(target_arch = "wasm32"))]
        {
            match crate::aot::registered(slot) {
                Some(f) => {
                    let p = self as *mut Rt;
                    f(p, fp, ret_to, block, sync);
                }
                None => unreachable!(
                    "compiled arity {slot} was never registered: this image was built with \
                     compiled arities and linked without them"
                ),
            }
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
    /// The out-of-line half of a specialised integer operation, reached from
    /// COMPILED code. Same protocol as `aot_native_at`: the fast path is
    /// emitted inline as wasm, and this is where a bigint operand, a result
    /// past the fixnum range, or an overflow ends up. It is a helper rather
    /// than a bail so that no chunk boundary is needed after arithmetic --
    /// a boundary per arithmetic instruction is the shape 0013 measured and
    /// rejected.
    pub(crate) fn aot_int_binop_at(
        &mut self,
        opcode: u32,
        ip: u32,
        block: u32,
        next_ip: u32,
        next_block: u32,
    ) -> u32 {
        let keep_top = self.roots.stack_top;
        let base = keep_top - 2;
        let y = self.roots.stack[base + 1];
        let x = self.roots.stack[base];
        let r = self.int_binop_slow(opcode as u8, x, y);
        self.roots.stack_top = base;
        if self.failed() {
            return self.aot_failed(ip, block, next_ip, next_block, keep_top, base, r);
        }
        self.vpush(r);
        0
    }

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
                self.call_aot(slot, cfp, cret, 0, sync);
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
        crate::aotstat::note_frame(f.instrs, (f.flags & FRAME_RESUMED != 0));
        self.handlers.truncate(f.handlers as usize);
        self.roots.stack_top = f.ret_to as usize;
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
        self.call_aot(slot, fp, ret_to, block, sync);
        core::mem::take(&mut self.aot_unwound_out)
    }
}

#[cfg(test)]
mod frame_layout {
    use super::*;
    #[test]
    fn how_big_is_an_activation_record() {
        // Printed rather than asserted: this is a measurement, and pinning it
        // would make an unrelated field addition fail here rather than where it
        // was made.
        std::eprintln!(
            "Frame = {} bytes, align {}; every field u32",
            core::mem::size_of::<Frame>(),
            core::mem::align_of::<Frame>()
        );
        std::eprintln!("MAX_FRAMES = {}, so a frame index needs {} bits",
                       MAX_FRAMES, usize::BITS - (MAX_FRAMES as usize).leading_zeros());
    }
}
