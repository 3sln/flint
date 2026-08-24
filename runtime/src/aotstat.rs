//! The region histogram `doc/decisions/0013` is gated on.
//!
//! 0013 says, in the section that decides everything else in it: *"the whole
//! thing turns on a distribution nobody has looked at: how long are the regions
//! in real code, and what does entering one cost?"* This is that distribution,
//! measured on real execution rather than on static occurrence, because a region
//! that appears once and runs a million times is the only kind that matters.
//!
//! Two models are counted side by side, because 0013 argues they differ by an
//! order of magnitude and the argument should be settled by the number:
//!
//! * **Model A** — a region ends at every call, which is what "contiguous
//!   non-parking chunk" meant before the guard.
//! * **Model B** — a call is an inline guard, not a boundary, so the unit is one
//!   frame invocation: every instruction executed in a frame, excluding the
//!   nested frames its calls create. This is the design being built.
//!
//! Nothing here allocates and nothing here is in a production module.

/// log2 buckets. Bucket `k` holds runs of `2^k .. 2^(k+1) - 1`.
pub const NBUCKET: usize = 20;

/// Model B: instructions executed in one frame invocation, excluding callees.
pub static mut FRAME_HIST: [u64; NBUCKET] = [0; NBUCKET];
/// Model A: instructions executed between one call and the next.
pub static mut RUN_HIST: [u64; NBUCKET] = [0; NBUCKET];
/// One CONTIGUOUS stretch of a frame invocation: entry (or resume) to return
/// (or the next park). This is the segment a compiled body would run in one go,
/// and it is the only length that decides re-entry granularity -- a frame that
/// parks sixty-four times contributes sixty-four segments, not one invocation.
pub static mut SEG_HIST: [u64; NBUCKET] = [0; NBUCKET];
/// Model B restricted to frames that have been RESUMED after a park. 0013's
/// pathological case -- a loop that parks per iteration and is de-optimised for
/// ever after -- lives entirely in this histogram.
pub static mut RESUMED_HIST: [u64; NBUCKET] = [0; NBUCKET];

pub const C_INSTRS: usize = 0;
pub const C_FRAMES: usize = 1;
pub const C_CALLS: usize = 2;
pub const C_TAILCALLS: usize = 3;
pub const C_NATIVES: usize = 4;
pub const C_APPLIES: usize = 5;
/// Guards executed: one per call site reached. In the guard-only design this is
/// the number of load-test-branch sequences a compiled body pays for.
pub const C_GUARDS: usize = 6;
/// Guards that FIRED -- the call came back with `thrown` set. The ratio of this
/// to `C_GUARDS` is what says whether the branch predicts.
pub const C_GUARD_HITS: usize = 7;
/// Instructions executed in a frame that has already been resumed at least once.
pub const C_RESUMED_INSTRS: usize = 8;
pub const C_RESUMED_FRAMES: usize = 9;
/// Back-edges taken. Re-entry points are needed at these, so this is the size of
/// the entry dispatch, and it is the cost side of granular re-entry.
pub const C_BACKEDGES: usize = 10;
/// Sum of run lengths, for a mean that does not have to be reconstructed from
/// the buckets.
pub const C_RUN_SUM: usize = 11;
pub const C_RUN_N: usize = 12;
pub const C_FRAME_SUM: usize = 13;
pub const C_FRAME_N: usize = 14;
pub const C_RESUMED_SUM: usize = 15;
/// Restores of a saved thread state, split by WHY. A courtesy yield -- the
/// deterministic scheduler's own preemption -- restores exactly as a port park
/// does, and it is far more common, so lumping them together would attribute
/// the re-entry demand to ports when most of it is the slice.
pub const C_RESTORES: usize = 16;
pub const C_SAVES_PARK: usize = 17;
pub const C_SAVES_YIELD: usize = 18;
pub const C_SEG_SUM: usize = 19;
pub const C_SEG_N: usize = 20;

pub static mut COUNTS: [u64; 24] = [0; 24];

#[inline]
pub fn bucket(n: u32) -> usize {
    if n == 0 {
        return 0;
    }
    let b = 31 - n.leading_zeros();
    (b as usize).min(NBUCKET - 1)
}

#[inline]
pub fn note_run(n: u32) {
    unsafe {
        RUN_HIST[bucket(n)] += 1;
        COUNTS[C_RUN_SUM] += n as u64;
        COUNTS[C_RUN_N] += 1;
    }
}

/// A frame invocation interrupted by a park rather than ended by a return. Its
/// instructions so far are a segment; the frame itself is not over.
#[inline]
pub fn note_segment(n: u32, resumed: bool) {
    unsafe {
        SEG_HIST[bucket(n)] += 1;
        COUNTS[C_SEG_SUM] += n as u64;
        COUNTS[C_SEG_N] += 1;
        // Segments, not invocations. A resumed frame that parks AGAIN never
        // returns, so recording only at return sampled the short ones and
        // disagreed with the per-instruction count by a factor of forty-seven.
        if resumed {
            RESUMED_HIST[bucket(n)] += 1;
            COUNTS[C_RESUMED_SUM] += n as u64;
            COUNTS[C_RESUMED_FRAMES] += 1;
        }
    }
}

#[inline]
pub fn note_frame(n: u32, resumed: bool) {
    unsafe {
        FRAME_HIST[bucket(n)] += 1;
        COUNTS[C_FRAME_SUM] += n as u64;
        COUNTS[C_FRAME_N] += 1;
    }
    note_segment(n, resumed);
}

/// Read-out, so the whole thing is one export rather than one per array.
/// `i < NBUCKET` is `FRAME_HIST`, then `RUN_HIST`, `RESUMED_HIST`, `SEG_HIST`,
/// then `COUNTS`.
pub fn read(i: u32) -> u64 {
    let i = i as usize;
    unsafe {
        if i < NBUCKET {
            FRAME_HIST[i]
        } else if i < NBUCKET * 2 {
            RUN_HIST[i - NBUCKET]
        } else if i < NBUCKET * 3 {
            RESUMED_HIST[i - NBUCKET * 2]
        } else if i < NBUCKET * 4 {
            SEG_HIST[i - NBUCKET * 3]
        } else if i < NBUCKET * 4 + COUNTS.len() {
            COUNTS[i - NBUCKET * 4]
        } else {
            0
        }
    }
}

// --- the static side: what the metadata would cost --------------------------
//
// Re-entry points are nearly free at run time -- a compiled body is entered by
// a `br_table` and every value is already in the linear-memory stack, so there
// is nothing to reconstruct (`doc/decisions/0001`, and 0013's section on why
// deopt is cheap here). What they are NOT free in is module bytes, and 0003's
// whole modularity story is measured in bytes. So the count that sizes the
// chunks is a static one: how many re-entry points does real code actually
// want, against how many instructions?

pub const S_FNS: usize = 0;
pub const S_ARITIES: usize = 1;
pub const S_BYTES: usize = 2;
pub const S_INSTRS: usize = 3;
pub const S_CALLSITES: usize = 4;
/// Distinct backward-jump TARGETS. One re-entry point each; a loop with three
/// `recur`s to the same head still needs only one.
pub const S_BACKTARGETS: usize = 5;
pub const S_MAXINSTRS: usize = 6;
/// Arities of one instruction. These cannot pay for a wasm call at all and are
/// the population an inliner should take instead.
pub const S_TINY: usize = 7;
pub const S_UNKNOWN: usize = 8;

pub static mut STATIC: [u64; 12] = [0; 12];

/// Bytes of immediate operand after each opcode. `u32::MAX` marks an opcode this
/// table does not know, which is counted rather than guessed -- a walk that
/// silently mis-strides produces a plausible histogram of nonsense.
fn operand_len(op: u8) -> u32 {
    use crate::vm::op;
    match op {
        op::NOP | op::NIL | op::TRUE | op::FALSE | op::POP | op::DUP | op::RETURN
        | op::THROW | op::POP_HANDLER | op::RETHROW | op::SELF => 0,
        op::LOCAL | op::SET_LOCAL | op::SET_LOCAL_KEEP | op::UPVAL | op::CALL
        | op::TAIL_CALL | op::APPLY | op::POP_N => 1,
        op::CONST | op::INT | op::LOCAL_W | op::VAR | op::SET_VAR | op::JUMP
        | op::JUMP_IF_FALSE | op::JUMP_IF_TRUE | op::JUMP_IF_FALSE_KEEP
        | op::JUMP_IF_TRUE_KEEP | op::TRY | op::VECTOR | op::MAP | op::SET
        | op::LIST => 2,
        op::CLOSURE | op::NATIVE => 3,
        _ => u32::MAX,
    }
}

/// Walk every compiled function once and count what a compiler would have to
/// emit metadata for.
pub fn scan(rt: &mut crate::rt::Rt) {
    use crate::vm::op;
    unsafe {
        STATIC = [0; 12];
        for f in &rt.image.fns {
            STATIC[S_FNS] += 1;
            for a in &f.arities {
                STATIC[S_ARITIES] += 1;
                STATIC[S_BYTES] += a.len as u64;
                let (start, end) = (a.code, a.code + a.len);
                let mut ip = start;
                let mut instrs = 0u64;
                // Bit per byte offset: a backward target counted twice is a
                // re-entry point counted twice.
                let mut targets: alloc::vec::Vec<u32> = alloc::vec::Vec::new();
                while ip < end {
                    let opcode = rt.u8_at(ip);
                    let n = operand_len(opcode);
                    if n == u32::MAX {
                        STATIC[S_UNKNOWN] += 1;
                        break;
                    }
                    instrs += 1;
                    match opcode {
                        op::CALL | op::TAIL_CALL | op::APPLY | op::NATIVE => {
                            STATIC[S_CALLSITES] += 1;
                        }
                        op::JUMP | op::JUMP_IF_FALSE | op::JUMP_IF_TRUE
                        | op::JUMP_IF_FALSE_KEEP | op::JUMP_IF_TRUE_KEEP => {
                            let off = rt.i16_at(ip + 1) as i32;
                            if off < 0 {
                                let t = (ip as i32 + 3 + off) as u32;
                                if !targets.contains(&t) {
                                    targets.push(t);
                                }
                            }
                        }
                        _ => {}
                    }
                    ip += 1 + n;
                }
                STATIC[S_INSTRS] += instrs;
                STATIC[S_BACKTARGETS] += targets.len() as u64;
                if instrs > STATIC[S_MAXINSTRS] {
                    STATIC[S_MAXINSTRS] = instrs;
                }
                if instrs <= 1 {
                    STATIC[S_TINY] += 1;
                }
            }
        }
    }
}

pub fn read_static(i: u32) -> u64 {
    unsafe { *STATIC.get(i as usize).unwrap_or(&0) }
}
