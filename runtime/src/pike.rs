//! The native Pike VM (`DECISIONS.md#matching-over-ropes`).
//!
//! One left-to-right pass carrying a list of live threads, consuming each
//! character exactly once and never rewinding. That is what lets it read a ROPE:
//! it needs nothing from its input but "the next character", so it walks leaves
//! in order and never asks for contiguous bytes.
//!
//! Linear by construction. Threads are deduplicated by program counter, so a
//! position holds at most one thread per instruction however many ways the
//! pattern could have reached it -- which is why `(a+)+b` is linear here and
//! exponential in the backtracker it replaces.
//!
//! Leftmost-first (Perl) semantics: `add_thread` follows SPLIT's preferred
//! branch first and the dedup set keeps whichever arrived first, so an earlier
//! alternative wins exactly as a backtracker's would. A thread reaching MATCH
//! cuts every LOWER-priority thread; the ones already carried forward have
//! higher priority and may still beat it.

// THIS FILE IS WRITTEN THREE TIMES, and `DECISIONS.md#the-pike-vm-is-the-last-triplicate`
// says why it is not generated: `class_hit`, `consumes`, `add_thread` and
// `run_over` are pure integer functions over a program and a code-point array,
// and kin cannot pass a mutable host array to a callee -- `^:mut` on a
// parameter is a by-value rebinding in Rust and a shared reference on both
// ports. That record also lists the five places the three copies have already
// drifted, two of them reachable from guest code.

// THE CONSUMING LINE. The character-class predicates are generated now,
// `kgen/rt/pike.rs`, and they are FREE FUNCTIONS rather than methods
// -- so unlike an `impl Rt` block they have to be brought into scope. This
// re-export puts them back under `crate::pike`, which is where every call
// site in the runtime already looks for them.
pub(crate) use crate::kgen::rt::pike::*;

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, NIL};

pub const OP_CHAR: u32 = 0;
pub const OP_ANY: u32 = 1;
pub const OP_SPLIT: u32 = 2;
pub const OP_JMP: u32 = 3;
pub const OP_SAVE: u32 = 4;
pub const OP_MATCH: u32 = 5;
pub const OP_BOL: u32 = 6;
pub const OP_EOL: u32 = 7;
pub const OP_WORDB: u32 = 8;
pub const OP_NWORDB: u32 = 9;
pub const OP_CLASS: u32 = 10;

const CL_ONE: u32 = 0;
const CL_RANGE: u32 = 1;
const CL_PRED: u32 = 2;

/// Header words of a compiled program: instruction count, class-table length,
/// group count. Then the code, then the class table.
pub const PROG_HDR: usize = 3;

/// `TY_REGEX` slots.
pub const RX_SOURCE: u32 = 0;
pub const RX_PROG: u32 = 1;
pub const RX_NGROUPS: u32 = 2;


/// GENERATED (`kin/pike.kin`). Whether a code point is in a character class.
///
/// THE SLICE IS THIS RUNTIME'S CONVENIENCE, not the shape. Rust can slice the
/// class table out of the program for nothing and the other three cannot, so
/// the generated function takes the program WHOLE and a base to index from --
/// which is what both ports already did, and why they carried a different
/// signature for the same function. Passing an already-sliced table with a
/// base of zero is the same call.
fn class_hit(classes: &[u32], off: usize, v: u32) -> bool {
    crate::kgen::rt::pike::class_hit(classes, 0, off as u32, v)
}

/// The characters of a string, in order, without materialising it.
///
/// A rope is walked leaf by leaf; the leaves are collected once as VALUES, not
/// as bytes, so nothing is copied and `stat_flattens` stays at zero. That is the
/// property `DECISIONS.md#matching-over-ropes` asks to be asserted rather than assumed.
struct Cursor {
    leaves: alloc::vec::Vec<Value>,
    leaf: usize,
    off: usize,
}

impl Rt {
}

// `Thread` AND `add_thread` ARE GENERATED (`kin/pike.kin`), and the struct is
// gone with them. A thread is a flat row of `1 + nslots` words in an arena the
// caller lays out -- `pc` then its capture slots -- which is what let the
// recursion cross into kin: no growable list, no per-thread allocation, and
// no second object type to keep in step across three runtimes.

/// GENERATED (`kin/pike.kin`). Whether the instruction at `pc` consumes a code
/// point.
///
/// THE PROGRAM IS INDEXED, NOT SLICED. Both ports already took the program
/// whole plus two bases, because neither can slice for free; native took two
/// slices and so carried a different signature for the same function. This
/// wrapper is gone -- the call site below passes the bases directly, which is
/// what `run_over` has in hand anyway.
impl Rt {
    /// Run a compiled program against `s`, anchored at code-point index `from`.
    /// Returns the slot vector of the best match, or `None`.
    pub fn pike_run(
        &mut self,
        prog: &[u32],
        s: Value,
        from: usize,
        entry: u32,
        full: bool,
    ) -> Option<alloc::vec::Vec<i32>> {
        let ninstrs = prog[0] as usize;
        let nslots = (prog[2] as usize + 1) * 2;
        // GENERATED, from `kin/codepoints.kin`, which walks the leaves. Both
        // ports used to flatten the subject to get here. The buffer is MOVED
        // out rather than copied, so this costs what it always did.
        let ci = self.code_points(s);
        let cps = core::mem::take(&mut self.cps[ci as usize]);
        self.cps_close(ci);
        // ONE ARENA, laid out here because the simulator is generated and
        // cannot allocate (`kin/pike.kin`). Two thread lists, the scratch the
        // capture copies live in, the initial slots and the answer:
        //
        //     [ list A ][ list B ][ scratch ][ start ][ best ]
        //
        // A list is `ninstrs` rows of `1 + nslots`, because `seen` admits each
        // pc at most once per character and so bounds the row count.
        let width = nslots + 1;
        let a_at = 0usize;
        let b_at = ninstrs * width;
        let scratch_at = 2 * ninstrs * width;
        let start_at = scratch_at + ninstrs * nslots;
        let best_at = start_at + nslots;
        let mut mem = alloc::vec![-1i32; best_at + nslots];
        let mut seen = alloc::vec![false; ninstrs];
        let hit = crate::kgen::rt::pike::run_over(
            prog, ninstrs as u32, nslots as u32, &cps, cps.len() as u32,
            from as u32, entry, full, &mut mem,
            a_at as u32, b_at as u32, scratch_at as u32,
            start_at as u32, best_at as u32, &mut seen);
        if hit {
            Some(mem[best_at..best_at + nslots].to_vec())
        } else {
            None
        }
    }
}

// `run_over` IS GENERATED (`kin/pike.kin`). The simulator proper -- lockstep
// over the code points, leftmost-first decided by cutting the walk at a match
// -- with no `Rt` in it, so one decoding of the subject can be run many times.
// `pike_run` above lays out the arena it works in, because generated code
// cannot allocate.
impl Rt {
    /// Build a `TY_REGEX` from a program the shared cljc compiler emitted.
    ///
    /// `words` is a vector of fixnums: `[ninstrs, nclasses, ngroups, code...,
    /// classes...]`. It is copied into a `TY_RAW` blob once, so the hot loop
    /// reads plain words rather than walking a persistent vector per
    /// instruction.
    ///
    /// Gas is charged HERE, on every call, hit or miss -- see
    /// `DECISIONS.md#matching-over-ropes`: if compiling charged only on a miss, whether a
    /// compile happened would depend on whether a collection had run, and the
    /// same program would report two different instruction counts. That is
    /// exactly the flakiness 0009's counter exists to replace.
    pub fn re_compile(&mut self, source: Value, words: Value) -> Value {
        let n = self.count_of(words) as usize;
        if n < PROG_HDR {
            return self.throw_str("IllegalArgumentException", "regex: malformed program");
        }
        self.charge_compile(n as u32);
        let mut raw: alloc::vec::Vec<u32> = alloc::vec::Vec::with_capacity(n);
        for k in 0..n {
            let v = self.vec_nth(words, k as u32, NIL);
            raw.push(v.as_fixnum() as u32);
        }
        let base = self.mark();
        let si = self.push(source);
        let blob = self.alloc(TY_RAW, (raw.len() * 4) as u32);
        if blob == 0 {
            self.pop_to(base);
            return NIL;
        }
        {
            let bytes = self.gc.sp.bytes_mut(blob + HDR, (raw.len() * 4) as u32);
            for (k, w) in raw.iter().enumerate() {
                bytes[k * 4..k * 4 + 4].copy_from_slice(&w.to_le_bytes());
            }
        }
        let bi = self.push(Value::heap(blob));
        let a = self.alloc(TY_REGEX, 3);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (src, blob) = (self.r(si), self.r(bi));
        self.pop_to(base);
        self.set_slot(a, RX_SOURCE, src);
        self.set_slot(a, RX_PROG, blob);
        self.set_slot(a, RX_NGROUPS, Value::fixnum(raw[2] as i64));
        Value::heap(a)
    }

    /// EVERY match, in ONE left-to-right pass.
    ///
    /// This is the operation `split`, `re-seq` and `replace` actually want, and
    /// giving them `re-run` in a loop was quadratic: each call decoded the whole
    /// subject again, so splitting a 32 799-character corpus into 6 601 pieces
    /// decoded it 6 601 times. The Pike VM was four times SLOWER than the
    /// backtracker it replaced until this existed -- the third time in this
    /// codebase that a per-call O(n) step turned a linear algorithm quadratic.
    ///
    /// Returns a flat vector: `nslots` entries per match, back to back.
    pub fn re_find_all(&mut self, re: Value, s: Value, limit: i64) -> Value {
        if !re.is_heap() || ty(&self.gc.sp, re.as_heap()) != TY_REGEX {
            return self.throw_str("ClassCastException", "not a compiled pattern");
        }
        if !self.is_string(s) {
            return self.throw_str("ClassCastException", "re-find-all wants a string");
        }
        self.charge_subject(s);
        let blob = self.slot(re, RX_PROG);
        let prog: alloc::vec::Vec<u32> = {
            let b = crate::obj::raw_bytes(&self.gc.sp, blob.as_heap());
            b.chunks_exact(4)
                .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                .collect()
        };
        // GENERATED, from `kin/codepoints.kin`, which walks the leaves. Both
        // ports used to flatten the subject to get here. The buffer is MOVED
        // out rather than copied, so this costs what it always did.
        let ci = self.code_points(s);
        let cps = core::mem::take(&mut self.cps[ci as usize]);
        self.cps_close(ci);
        let ninstrs = prog[0] as usize;
        let nslots = (prog[2] as usize + 1) * 2;
        let mut found: alloc::vec::Vec<i32> = alloc::vec::Vec::new();
        let mut at = 0usize;
        let mut count = 0i64;
        // THE ARENA IS ALLOCATED ONCE for every match in the subject, which is
        // most of why this function exists: `re-seq`, `split` and `replace`
        // used to call `find-from` in a loop and decode the subject again
        // every time. `run_over` clears `seen` and writes `start` itself, so a
        // second call needs nothing reset between matches.
        let width = nslots + 1;
        let (a_at, b_at) = (0usize, ninstrs * width);
        let scratch_at = 2 * ninstrs * width;
        let start_at = scratch_at + ninstrs * nslots;
        let best_at = start_at + nslots;
        let mut mem = alloc::vec![-1i32; best_at + nslots];
        let mut seen = alloc::vec![false; ninstrs];
        while at <= cps.len() {
            if limit > 0 && count >= limit {
                break;
            }
            let hit = crate::kgen::rt::pike::run_over(
                &prog, ninstrs as u32, nslots as u32, &cps, cps.len() as u32,
                at as u32, 0, false, &mut mem,
                a_at as u32, b_at as u32, scratch_at as u32,
                start_at as u32, best_at as u32, &mut seen);
            if !hit {
                break;
            }
            let (st, en) = (mem[best_at], mem[best_at + 1]);
            found.extend_from_slice(&mem[best_at..best_at + nslots]);
            count += 1;
            at = if en > st { en as usize } else { en as usize + 1 };
        }
        let base = self.mark();
        let mut v = self.empty_vec();
        let vi = self.push(v);
        for x in found {
            let e = Value::fixnum(x as i64);
            v = self.vec_conj(self.r(vi), e);
            self.set_r(vi, v);
        }
        let out = self.r(vi);
        self.pop_to(base);
        out
    }

    /// Match at `from`, returning `[s0 e0 s1 e1 ...]` or nil. Positions are
    /// code-point indices and `-1` marks a group that did not participate.
    pub fn re_run(&mut self, re: Value, s: Value, from: i64, entry: u32, full: bool) -> Value {
        if !re.is_heap() || ty(&self.gc.sp, re.as_heap()) != TY_REGEX {
            return self.throw_str("ClassCastException", "not a compiled pattern");
        }
        if !self.is_string(s) {
            return self.throw_str("ClassCastException", "re-run wants a string");
        }
        // A match walks the subject once, so it is O(n) in the input and O(m) in
        // the program -- charged for both, which is what makes a pathological
        // pattern hit the budget rather than the wall clock.
        self.charge_subject(s);
        let blob = self.slot(re, RX_PROG);
        let prog: alloc::vec::Vec<u32> = {
            let b = crate::obj::raw_bytes(&self.gc.sp, blob.as_heap());
            b.chunks_exact(4)
                .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                .collect()
        };
        match self.pike_run(&prog, s, from.max(0) as usize, entry, full) {
            None => NIL,
            Some(slots) => {
                let base = self.mark();
                let mut v = self.empty_vec();
                let vi = self.push(v);
                for x in slots {
                    let e = Value::fixnum(x as i64);
                    v = self.vec_conj(self.r(vi), e);
                    self.set_r(vi, v);
                }
                let out = self.r(vi);
                self.pop_to(base);
                out
            }
        }
    }
}
