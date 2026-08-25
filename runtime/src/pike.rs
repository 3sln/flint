//! The native Pike VM (`doc/decisions/0012`).
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

#[inline]
fn word_cp(v: u32) -> bool {
    (48..=57).contains(&v) || (65..=90).contains(&v) || (97..=122).contains(&v) || v == 95
}
#[inline]
fn space_cp(v: u32) -> bool {
    matches!(v, 32 | 9 | 10 | 13 | 12 | 11)
}

fn pred_hit(code: u32, v: u32) -> bool {
    match code {
        0 => (48..=57).contains(&v),
        1 => !(48..=57).contains(&v),
        2 => word_cp(v),
        3 => !word_cp(v),
        4 => space_cp(v),
        _ => !space_cp(v),
    }
}

fn class_hit(classes: &[u32], off: usize, v: u32) -> bool {
    let n = classes[off] as usize;
    for k in 0..n {
        let b = off + 1 + k * 3;
        let hit = match classes[b] {
            CL_ONE => v == classes[b + 1],
            CL_RANGE => v >= classes[b + 1] && v <= classes[b + 2],
            _ => pred_hit(classes[b + 1], v),
        };
        if hit {
            return true;
        }
    }
    false
}

/// The characters of a string, in order, without materialising it.
///
/// A rope is walked leaf by leaf; the leaves are collected once as VALUES, not
/// as bytes, so nothing is copied and `stat_flattens` stays at zero. That is the
/// property `doc/decisions/0012` asks to be asserted rather than assumed.
struct Cursor {
    leaves: alloc::vec::Vec<Value>,
    leaf: usize,
    off: usize,
}

impl Rt {
    fn collect_leaves(&self, v: Value, out: &mut alloc::vec::Vec<Value>) {
        if v.is_inline_str() {
            out.push(v);
            return;
        }
        if !v.is_heap() {
            return;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_ROPE => {
                let flat = self.slot(v, RP_FLAT);
                if !flat.is_nil() {
                    out.push(flat);
                    return;
                }
                let n = len(&self.gc.sp, v.as_heap()) - RP_KIDS;
                for i in 0..n {
                    let k = self.slot(v, RP_KIDS + i);
                    self.collect_leaves(k, out);
                }
            }
            _ => out.push(v),
        }
    }

    fn leaf_bytes<'a>(&'a self, v: Value, buf: &'a mut [u8; crate::value::INLINE_MAX]) -> &'a [u8] {
        if v.is_inline_str() {
            v.inline_bytes(buf)
        } else {
            str_bytes(&self.gc.sp, v.as_heap())
        }
    }

    /// Every code point of `s`, in order. Decoding UTF-8 as it goes, so a
    /// non-ASCII subject costs the same walk.
    fn code_points(&self, s: Value) -> alloc::vec::Vec<u32> {
        let mut leaves = alloc::vec::Vec::new();
        self.collect_leaves(s, &mut leaves);
        let mut out = alloc::vec::Vec::with_capacity(self.str_len(s) as usize);
        let mut pending: alloc::vec::Vec<u8> = alloc::vec::Vec::new();
        for l in &leaves {
            let mut buf = [0u8; crate::value::INLINE_MAX];
            let b = self.leaf_bytes(*l, &mut buf);
            if pending.is_empty() && b.is_ascii() {
                for c in b {
                    out.push(*c as u32);
                }
                continue;
            }
            // A multi-byte character may straddle a leaf boundary, which is
            // exactly the case a flat-string matcher never has to think about.
            pending.extend_from_slice(b);
            let mut i = 0;
            while i < pending.len() {
                let need = utf8_len(pending[i]);
                if i + need > pending.len() {
                    break;
                }
                out.push(decode_utf8(&pending[i..i + need]));
                i += need;
            }
            pending.drain(..i);
        }
        out
    }
}

fn utf8_len(b: u8) -> usize {
    if b < 0x80 {
        1
    } else if b >> 5 == 0b110 {
        2
    } else if b >> 4 == 0b1110 {
        3
    } else {
        4
    }
}

fn decode_utf8(b: &[u8]) -> u32 {
    match b.len() {
        1 => b[0] as u32,
        2 => ((b[0] as u32 & 0x1F) << 6) | (b[1] as u32 & 0x3F),
        3 => ((b[0] as u32 & 0x0F) << 12) | ((b[1] as u32 & 0x3F) << 6) | (b[2] as u32 & 0x3F),
        _ => {
            ((b[0] as u32 & 0x07) << 18)
                | ((b[1] as u32 & 0x3F) << 12)
                | ((b[2] as u32 & 0x3F) << 6)
                | (b[3] as u32 & 0x3F)
        }
    }
}

struct Thread {
    pc: u32,
    saved: alloc::vec::Vec<i32>,
}

fn add_thread(
    code: &[u32],
    classes: &[u32],
    cps: &[u32],
    i: usize,
    list: &mut alloc::vec::Vec<Thread>,
    seen: &mut alloc::vec::Vec<bool>,
    pc: u32,
    saved: &[i32],
) {
    if seen[pc as usize] {
        return;
    }
    seen[pc as usize] = true;
    let b = (pc * 3) as usize;
    let (op, a, c) = (code[b], code[b + 1], code[b + 2]);
    match op {
        OP_JMP => add_thread(code, classes, cps, i, list, seen, a, saved),
        OP_SPLIT => {
            add_thread(code, classes, cps, i, list, seen, a, saved);
            add_thread(code, classes, cps, i, list, seen, c, saved);
        }
        OP_SAVE => {
            let mut s2 = saved.to_vec();
            if (a as usize) < s2.len() {
                s2[a as usize] = i as i32;
            }
            add_thread(code, classes, cps, i, list, seen, pc + 1, &s2);
        }
        OP_BOL => {
            if i == 0 {
                add_thread(code, classes, cps, i, list, seen, pc + 1, saved);
            }
        }
        OP_EOL => {
            if i == cps.len() {
                add_thread(code, classes, cps, i, list, seen, pc + 1, saved);
            }
        }
        OP_WORDB | OP_NWORDB => {
            let before = i > 0 && word_cp(cps[i - 1]);
            let after = i < cps.len() && word_cp(cps[i]);
            let at = before != after;
            if (op == OP_WORDB) == at {
                add_thread(code, classes, cps, i, list, seen, pc + 1, saved);
            }
        }
        _ => list.push(Thread {
            pc,
            saved: saved.to_vec(),
        }),
    }
}

#[inline]
fn consumes(code: &[u32], classes: &[u32], pc: u32, v: u32) -> bool {
    let b = (pc * 3) as usize;
    match code[b] {
        OP_CHAR => v == code[b + 1],
        // Not a newline. Java's `.` excludes it without DOTALL; the backtracker
        // this replaces matched it, so the divergence is being closed.
        OP_ANY => v != 10,
        OP_CLASS => {
            let hit = class_hit(classes, code[b + 1] as usize, v);
            if code[b + 2] == 1 {
                !hit
            } else {
                hit
            }
        }
        _ => false,
    }
}

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
        let cps = self.code_points(s);
        run_over(prog, ninstrs, nslots, &cps, from, entry, full)
    }
}

/// The simulator proper: no `Rt`, so it can be run many times over one decoding
/// of the subject.
fn run_over(
    prog: &[u32],
    ninstrs: usize,
    nslots: usize,
    cps: &[u32],
    from: usize,
    entry: u32,
    full: bool,
) -> Option<alloc::vec::Vec<i32>> {
    {
        let code = &prog[PROG_HDR..PROG_HDR + ninstrs * 3];
        let classes = &prog[PROG_HDR + ninstrs * 3..];
        if from > cps.len() {
            return None;
        }
        let mut clist: alloc::vec::Vec<Thread> = alloc::vec::Vec::new();
        let mut nlist: alloc::vec::Vec<Thread> = alloc::vec::Vec::new();
        let mut seen = alloc::vec![false; ninstrs];
        let start = alloc::vec![-1i32; nslots];
        add_thread(code, classes, cps, from, &mut clist, &mut seen, entry, &start);
        let mut best: Option<alloc::vec::Vec<i32>> = None;
        let mut i = from;
        loop {
            if clist.is_empty() {
                break;
            }
            let v = if i < cps.len() { Some(cps[i]) } else { None };
            nlist.clear();
            for x in seen.iter_mut() {
                *x = false;
            }
            for t in clist.iter() {
                let op = code[(t.pc * 3) as usize];
                if op == OP_MATCH {
                    // A full match must reach the end. Rejecting rather than
                    // cutting is what lets a LOWER-priority alternative win --
                    // `(a|ab)` against "ab" is `ab`, which a backtracker gets by
                    // backtracking against the anchor and this gets by carrying
                    // both threads.
                    if !full || i == cps.len() {
                        best = Some(t.saved.clone());
                        break;
                    }
                    continue;
                }
                if let Some(v) = v {
                    if consumes(code, classes, t.pc, v) {
                        add_thread(
                            code,
                            classes,
                            cps,
                            i + 1,
                            &mut nlist,
                            &mut seen,
                            t.pc + 1,
                            &t.saved,
                        );
                    }
                }
            }
            core::mem::swap(&mut clist, &mut nlist);
            if i >= cps.len() {
                break;
            }
            i += 1;
        }
        best
    }
}

impl Rt {
    /// Build a `TY_REGEX` from a program the shared cljc compiler emitted.
    ///
    /// `words` is a vector of fixnums: `[ninstrs, nclasses, ngroups, code...,
    /// classes...]`. It is copied into a `TY_RAW` blob once, so the hot loop
    /// reads plain words rather than walking a persistent vector per
    /// instruction.
    ///
    /// Gas is charged HERE, on every call, hit or miss -- see
    /// `doc/decisions/0012`: if compiling charged only on a miss, whether a
    /// compile happened would depend on whether a collection had run, and the
    /// same program would report two different instruction counts. That is
    /// exactly the flakiness 0009's counter exists to replace.
    pub fn re_compile(&mut self, source: Value, words: Value) -> Value {
        let n = self.count_of(words) as usize;
        if n < PROG_HDR {
            return self.throw_str("IllegalArgumentException", "regex: malformed program");
        }
        self.charge_work(n as u64);
        let mut raw: alloc::vec::Vec<u32> = alloc::vec::Vec::with_capacity(n);
        for k in 0..n {
            let v = self.vec_nth(words, k as u32).unwrap_or(NIL);
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
        self.gc.set_slot(a, RX_SOURCE, src);
        self.gc.set_slot(a, RX_PROG, blob);
        self.gc.set_slot(a, RX_NGROUPS, Value::fixnum(raw[2] as i64));
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
        let n = self.str_len(s);
        self.charge_bytes(n);
        let blob = self.slot(re, RX_PROG);
        let prog: alloc::vec::Vec<u32> = {
            let b = crate::obj::raw_bytes(&self.gc.sp, blob.as_heap());
            b.chunks_exact(4)
                .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                .collect()
        };
        let cps = self.code_points(s);
        let ninstrs = prog[0] as usize;
        let nslots = (prog[2] as usize + 1) * 2;
        let mut found: alloc::vec::Vec<i32> = alloc::vec::Vec::new();
        let mut at = 0usize;
        let mut count = 0i64;
        while at <= cps.len() {
            if limit > 0 && count >= limit {
                break;
            }
            match run_over(&prog, ninstrs, nslots, &cps, at, 0, false) {
                None => break,
                Some(slots) => {
                    let (st, en) = (slots[0], slots[1]);
                    found.extend_from_slice(&slots);
                    count += 1;
                    // An empty match must still advance, or this never ends.
                    at = if en > st { en as usize } else { en as usize + 1 };
                }
            }
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
        let n = self.str_len(s);
        self.charge_bytes(n);
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
