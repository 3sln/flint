//! The runtime half of `DECISIONS.md#emit-wasm-instead-of-dispatch`: what compiled wasm calls back into.
//!
//! A compiled arity is a wasm function
//!
//! ```text
//!   (func (param $rt i32) (param $fp i32) (param $ret_to i32) (param $entry i32))
//! ```
//!
//! It manipulates the same linear-memory value stack the interpreter does, and
//! holds nothing of its own. That is the whole reason this is admissible at all
//! (`DECISIONS.md#dispatch`): wasm locals are not scannable, so a design that kept
//! values in them would need a shadow-stack spill around every allocation. Here
//! there is nothing to spill, which is also why leaving compiled code costs
//! nothing and why re-entering it mid-body is possible at all.
//!
//! ## Why a call does not become a wasm call
//!
//! 0013 assumed compiled functions call each other, and that a bail unwinds
//! several wasm frames. That cannot be right here: Clojure recursion would then
//! live on the wasm stack, which cannot be suspended -- so a green thread could
//! not park at depth, and deep recursion would trap instead of raising a
//! catchable `StackOverflowError`. Both are load-bearing (`DECISIONS.md#threads-and-ports`).
//!
//! So `aot_call` does exactly what the interpreter's `CALL` does -- push a frame
//! -- and returns. The interpreter loop enters the callee, which may itself be
//! compiled. When it returns, the caller resumes AT THE BLOCK AFTER THE CALL,
//! because the emitter knew that block statically and handed it over. The cost
//! is two boundary crossings per call, and the boundary was measured at 2.02 ns
//! against 6.2 ns of dispatch: about 4% of the win, for keeping the frame
//! discipline exactly as it was.

use crate::rt::Rt;
use crate::value::Value;

/// A compiled arity, as the wasm table sees it. Flat and `extern "C"` so the
/// table entry's wasm type is exactly `(i32,i32,i32,i32) -> ()` and a
/// `call_indirect` through it is unambiguous -- the same reasoning as
/// `NativeFn`.
///
/// The last parameter is `usize`, not `u32`: it is an ADDRESS (the sync block),
/// and an address is pointer-width. On wasm32 `usize` IS `u32`, so the wasm
/// type of this table entry is unchanged; on a 64-bit host it is what makes the
/// same signature mean the same thing (`DECISIONS.md#llvm-ir-target`).
pub type AotEntry = extern "C" fn(*mut Rt, u32, u32, u32, usize);

/// What compiled code reads after any call back into Rust. Two loads rather
/// than two calls: the value stack is a `Vec` and can be reallocated by a push,
/// so the base cannot be cached across a call.
///
/// Every field is `usize`. They were `u32` when the only consumer was a wasm
/// module, where that is the same type; the LLVM target
/// (`DECISIONS.md#llvm-ir-target`) is the same emitter aimed at a 64-bit host,
/// where a truncated `stack` pointer is a wild store on the first push. `usize`
/// is byte-identical under `wasm32`, so the wasm layout is untouched --
/// `size_of::<AotSync>()` is asserted at the bottom of this file for both.
#[repr(C)]
#[derive(Default)]
pub struct AotSync {
    /// Byte address of `roots.stack[0]`.
    pub stack: usize,
    /// `roots.stack_top`, as an index.
    pub top: usize,
    /// Byte address of `roots.shared.consts[0]`.
    pub consts: usize,
    /// Byte address of `roots.shared.globals[0]`.
    pub globals: usize,
    /// Base of the object heap, which object addresses are relative to.
    pub heap: usize,
    /// Address of `Rt::steps` and of `Rt::checkpoint`. Gas is charged inline,
    /// per chunk, so compiled code needs to reach both without a call
    /// (`DECISIONS.md#two-builds` makes gas a production feature, and construe's
    /// gates depend on the count being the same as the interpreter's).
    pub steps: usize,
    pub checkpoint: usize,
}

pub static mut SYNC: AotSync = AotSync {
    stack: 0,
    top: 0,
    consts: 0,
    globals: 0,
    heap: 0,
    steps: 0,
    checkpoint: 0,
};

/// One compiled arity: where its wasm function sits in the table, and the block
/// to resume at for each re-entry point. The emitter knows every re-entry block
/// statically and hands it over at the call, so nothing here is ever searched.
#[derive(Clone, Debug, Default)]
pub struct AotFn {
    /// Table slot of the compiled function. 0 means this arity is not compiled.
    pub slot: u32,
    /// Deepest the operand stack gets. Reserved by the interpreter before it
    /// enters, so a compiled body makes NO call on the way in and every push it
    /// contains is an unchecked store.
    pub depth: u32,
    /// `(bytecode offset, block)` for every re-entry point, sorted by offset.
    /// Used only where a resume ip is not already known -- an unwind into a
    /// handler, and a thread restored from a save.
    pub points: alloc::vec::Vec<(u32, u32)>,
}

impl AotFn {
    /// The block to enter at `ip`, or `None` if `ip` is not a re-entry point.
    pub fn block_at(&self, ip: u32) -> Option<u32> {
        match self.points.binary_search_by_key(&ip, |&(o, _)| o) {
            Ok(k) => Some(self.points[k].1),
            Err(_) => None,
        }
    }
}

/// Have the write-once fields been filled?
static mut SYNC_FIXED: bool = false;

/// Fill the five fields that can only ever be written once, and prove it.
///
/// `consts` and `globals` are `Vec`s that stop growing when the image finishes
/// loading; `heap` is the arena base, which `sbrk` extends but never moves; and
/// the last two are the addresses of two `Rt` fields, and the `Rt` lives in a
/// static it is moved into exactly once. None of that is obvious from the call
/// site, which is why the diagnostics build re-derives all five on every
/// crossing and asserts they have not changed rather than taking the argument on
/// trust. `test/aot.clj` reads the counter, so the zero comes with its coverage.
#[cfg(feature = "diagnostics")]
pub static mut SYNC_DRIFT: [u64; 2] = [0; 2];

#[inline]
fn refresh(rt: &mut Rt) {
    unsafe {
        // The only two that a crossing can change: a push can reallocate the
        // value stack, and the top moves constantly. Writing the other five as
        // well cost seven stores on every one of five call sites, three of them
        // per Clojure call.
        SYNC.stack = rt.roots.stack.as_ptr() as usize;
        SYNC.top = rt.roots.stack_top;
        if !SYNC_FIXED {
            SYNC_FIXED = true;
            SYNC.consts = rt.roots.shared.consts.as_ptr() as usize;
            SYNC.globals = rt.roots.shared.globals.as_ptr() as usize;
            SYNC.heap = rt.gc.sp.base_addr() as usize;
            SYNC.steps = core::ptr::addr_of!(rt.steps) as usize;
            SYNC.checkpoint = core::ptr::addr_of!(rt.checkpoint) as usize;
        }
        #[cfg(feature = "diagnostics")]
        {
            SYNC_DRIFT[0] += 1;
            if SYNC.consts != rt.roots.shared.consts.as_ptr() as usize
                || SYNC.globals != rt.roots.shared.globals.as_ptr() as usize
                || SYNC.heap != rt.gc.sp.base_addr() as usize
                || SYNC.steps != core::ptr::addr_of!(rt.steps) as usize
                || SYNC.checkpoint != core::ptr::addr_of!(rt.checkpoint) as usize
            {
                SYNC_DRIFT[1] += 1;
            }
        }
    }
}

/// Reset for a fresh instantiation. The statics outlive an `Rt` on the host, and
/// a second `Rt` would otherwise inherit the first one's addresses.
pub fn forget_fixed() {
    unsafe {
        SYNC_FIXED = false;
    }
}

/// The sync block's address. Called once per module rather than once per entry:
/// the interpreter hands it to compiled code as a parameter, so a body makes no
/// call at all on the way in.
#[no_mangle]
pub extern "C" fn aot_prologue() -> usize {
    unsafe { core::ptr::addr_of!(SYNC) as usize }
}

/// `NATIVE`. Runs inside compiled code -- a native is a Rust call either way, so
/// there is nothing to gain by leaving. Returns 1 if the caller must bail.
#[no_mangle]
pub extern "C" fn aot_native(
    rt: *mut Rt,
    idx: u32,
    argc: u32,
    top: u32,
    ip: u32,
    block: u32,
    next_ip: u32,
    next_block: u32,
    gas: u32,
) -> u32 {
    let rt = unsafe { &mut *rt };
    rt.steps += gas as u64;
    #[cfg(feature = "diagnostics")]
    unsafe {
        crate::aotstat::COUNTS[crate::aotstat::C_AOT_NATIVES] += 1;
    }
    rt.roots.stack_top = top as usize;
    let out = rt.aot_native_at(idx, argc as usize, ip, block, next_ip, next_block);
    refresh(rt);
    out
}

/// The specialised integer operations, when the fast path did not apply. The
/// common case never reaches here: compiled code tests both tags and does the
/// arithmetic itself. This is a bigint, an overflow, or a result past the
/// fixnum range. Returns 1 if the caller must bail.
#[no_mangle]
pub extern "C" fn aot_int_binop(
    rt: *mut Rt,
    opcode: u32,
    top: u32,
    ip: u32,
    block: u32,
    next_ip: u32,
    next_block: u32,
    gas: u32,
) -> u32 {
    let rt = unsafe { &mut *rt };
    rt.steps += gas as u64;
    rt.roots.stack_top = top as usize;
    let out = rt.aot_int_binop_at(opcode, ip, block, next_ip, next_block);
    refresh(rt);
    out
}

/// A type predicate from compiled code. Reads the top of the value stack and
/// returns the boxed answer; the caller stores it back over the argument.
///
/// No bail protocol, no `refresh`, and no gas flush: this allocates nothing and
/// cannot fail, so nothing the caller has cached can move underneath it. That
/// is the whole reason it is worth having -- what a predicate costs is being
/// REACHED, and this is the cheapest possible way to reach one.
#[no_mangle]
pub extern "C" fn aot_type_p(rt: *mut Rt, code: u32, top: u32) -> u64 {
    let rt = unsafe { &mut *rt };
    let v = rt.roots.stack[top as usize - 1];
    Value::boolean(rt.type_p(code, v)).0
}

/// `RETURN`. Pops the frame and pushes the result where the caller expects it,
/// exactly as the interpreter's own arm does. The caller then resumes AT THE
/// BLOCK AFTER ITS CALL, because that frame's `aot_ip` was set when it bailed.
#[no_mangle]
pub extern "C" fn aot_return(rt: *mut Rt, top: u32, gas: u32) {
    let rt = unsafe { &mut *rt };
    rt.steps += gas as u64;
    rt.roots.stack_top = top as usize;
    rt.aot_return_here();
    refresh(rt);
}

/// `CALL`. Completes in place when the callee is not a closure; otherwise
/// pushes the frame and asks compiled code to leave.
#[no_mangle]
pub extern "C" fn aot_call(
    rt: *mut Rt,
    argc: u32,
    top: u32,
    ip: u32,
    block: u32,
    next_ip: u32,
    next_block: u32,
    gas: u32,
) -> u32 {
    let rt = unsafe { &mut *rt };
    // `gas + 1`: the chunk did not charge for this CALL, because a chunk that
    // hands its last instruction back leaves the charging to the interpreter --
    // and this one is NOT handed back. Either it completes here or this function
    // pushes the frame itself; either way the interpreter never dispatches it.
    rt.steps += gas as u64 + 1;
    rt.roots.stack_top = top as usize;
    let out = rt.aot_call_at(argc as usize, ip, block, next_ip, next_block);
    refresh(rt);
    out
}

/// Leave compiled code. ONE helper covers every exit -- a call, an opcode the
/// emitter does not inline, and anything else -- because every way back in is
/// the same comparison: the interpreter re-enters when `ip` reaches `resume_ip`.
///
/// `ip` is where the interpreter should carry on (the call's own instruction, so
/// it dispatches it with all its existing logic and none of it duplicated here);
/// `resume_ip` is where compiled code takes over again.
#[no_mangle]
pub extern "C" fn aot_bail(
    rt: *mut Rt,
    top: u32,
    ip: u32,
    resume_ip: u32,
    resume_block: u32,
    gas: u32,
) {
    let rt = unsafe { &mut *rt };
    rt.steps += gas as u64;
    #[cfg(feature = "diagnostics")]
    unsafe {
        crate::aotstat::COUNTS[crate::aotstat::C_AOT_BAILS] += 1;
    }
    rt.roots.stack_top = top as usize;
    // Is the operand stack the shape the instruction being handed back expects?
    // A bail publishes a top computed in compiled code, and the interpreter
    // then reads its operands relative to it -- so a top that is wrong by one
    // shows up as a callee that is not a function, several frames later.
    #[cfg(feature = "diagnostics")]
    unsafe {
        if rt.u8_at(ip) == crate::vm::op::TAIL_CALL || rt.u8_at(ip) == crate::vm::op::CALL {
            let argc = rt.u8_at(ip + 1) as usize;
            crate::aotstat::COUNTS[crate::aotstat::C_BAIL_CALLS] += 1;
            let at = (top as usize).wrapping_sub(argc + 1);
            let ok = at < rt.roots.stack_top
                && rt.roots.stack[at].is_heap()
                && matches!(crate::obj::ty(&rt.gc.sp, rt.roots.stack[at].as_heap()),
                            crate::obj::TY_CLOSURE | crate::obj::TY_NATIVEFN);
            if !ok {
                crate::aotstat::COUNTS[crate::aotstat::C_BAIL_BAD_CALLEE] += 1;
            }
        }
    }
    if let Some(f) = rt.frames.last_mut() {
        f.ip = ip;
        f.aot_ip = resume_ip;
        f.aot_block = resume_block;
    }
    refresh(rt);
}

/// A back-edge: flush the gas accumulated since the last exit and say whether
/// the interpreter's own tick would now fire. This is the ONE place a long
/// compiled loop can be preempted, which is what the deterministic scheduler
/// needs -- and back-edges are 2.4% of executed instructions, so it is also the
/// cheapest place to put it.
#[no_mangle]
pub extern "C" fn aot_tick(rt: *mut Rt, gas: u32, top: u32, ip: u32, block: u32) -> u32 {
    let rt = unsafe { &mut *rt };
    // Compiled code pushes straight into the value stack and only tells Rust
    // where the top is when it calls back. This one did not, so a slice that
    // tripped at a back-edge handed the interpreter a stale top -- and the
    // values above it, one of which was a message on its way to the host, were
    // simply not there.
    rt.roots.stack_top = top as usize;
    rt.steps += gas as u64;
    #[cfg(feature = "diagnostics")]
    unsafe {
        crate::aotstat::COUNTS[crate::aotstat::C_AOT_TICKS] += 1;
    }
    if rt.steps >= rt.checkpoint {
        #[cfg(feature = "diagnostics")]
        unsafe {
            crate::aotstat::COUNTS[crate::aotstat::C_AOT_TICK_TRIPS] += 1;
        }
        // ONE BACK, because the back-edge instruction is about to be charged a
        // second time. `gas` above is the chunk's static count and the
        // back-edge is IN it -- compiled code jumps for itself, so the chunk
        // charges for the jump. Handing the instruction back then makes the
        // interpreter's own `tick` charge it again on the way to the
        // hand-over, and the resumed slice charges it a third time when it
        // finally dispatches it. The interpreter alone charges it twice: once
        // at the tick that trips, once after the resume.
        //
        // So the flush is right for a chunk that RUNS ON and wrong for one
        // that trips, and this is the difference. Subtracted after the
        // comparison, not before: the jump did execute, so it counts towards
        // deciding that the slice is over -- and the interpreter's tick adds
        // it straight back, so the trip lands on the same instruction either
        // way.
        //
        // Worth one gas unit of care because `emit-wasm-instead-of-dispatch` treats interpreted and
        // compiled charging the same as the evidence that the chunking is
        // right, and `resource-limits` makes gas a bound on WORK -- a program that costs
        // more compiled hits a limit the interpreter would not. It showed up
        // as exactly +1 on any program that spawns a thread, because a slice
        // is what makes this path run at all.
        rt.steps -= 1;
        if let Some(f) = rt.frames.last_mut() {
            f.ip = ip;
            // Not a re-entry right here: the interpreter has to reach its own
            // tick for the gas error or the slice hand-over to happen.
            f.aot_ip = AOT_NEVER_U32;
            f.aot_block = block;
        }
        return 1;
    }
    0
}

/// Matches `vm::AOT_NEVER`; repeated here so this file does not have to reach
/// into the interpreter for a constant.
const AOT_NEVER_U32: u32 = u32::MAX;

/// Refresh, exported so the interpreter can call it after it has changed the
/// stack under compiled code's feet.
pub fn resync(rt: &mut Rt) {
    refresh(rt);
}

const _: () = {
    // A `Value` is eight bytes and the emitter hard-codes that in every load and
    // store it produces. If it ever stops being true, fail here rather than in
    // emitted code.
    assert!(core::mem::size_of::<Value>() == 8);
    // Seven pointer-width fields, no padding. BOTH emitters hard-code these
    // offsets -- `flint.aot` as `S-STACK`..`S-CHK` for a 4-byte word,
    // `flint.llvm` as a struct type for an 8-byte one -- so the layout is
    // asserted here rather than trusted at either end.
    assert!(core::mem::size_of::<AotSync>() == 7 * core::mem::size_of::<usize>());
};

/// The compiled arities of a natively linked program
/// (`DECISIONS.md#llvm-ir-target`).
///
/// On wasm a `slot` indexes `__indirect_function_table`, which the module
/// already has. Natively there is no table, so the emitted module carries one
/// and hands it over before the program runs -- which is exactly what
/// `native::resolve_natives` does for builtins, and for the same reason: an
/// index only means something inside the artifact that produced it.
#[cfg(not(target_arch = "wasm32"))]
static mut AOT_TABLE: (*const AotEntry, usize) = (core::ptr::null(), 0);

/// Register a natively linked program's compiled arities. `slot` 1 is
/// `table[0]`; slot 0 stays "not compiled", as `AotFn` documents.
///
/// # Safety
/// `table` must point at `n` function pointers that live as long as the
/// program. The emitted module puts them in a constant global, so they do.
#[cfg(not(target_arch = "wasm32"))]
#[no_mangle]
pub unsafe extern "C" fn flint_aot_register(table: *const AotEntry, n: usize) {
    unsafe { AOT_TABLE = (table, n) };
}

/// The compiled arity at `slot`, or `None` if nothing was registered there.
#[cfg(not(target_arch = "wasm32"))]
pub(crate) fn registered(slot: u32) -> Option<AotEntry> {
    unsafe {
        let (p, n) = AOT_TABLE;
        let i = slot as usize;
        if p.is_null() || i == 0 || i > n {
            None
        } else {
            Some(*p.add(i - 1))
        }
    }
}
