//! VM snapshots: capture, export, import (`doc/decisions/0015`).
//!
//! **Capture is a memcpy, not a traversal.** A capture that walked the object
//! graph could be wrong exactly as an ad-hoc probe can be wrong -- a missed
//! edge yields a snapshot missing an object, and then the capture is what needs
//! debugging. So this copies raw bytes of the heap plus the Rust-side state and
//! interprets **later**. That is what makes a snapshot a copy rather than a
//! question, and it is why it cannot lie about state it did not interpret.

use crate::mem::Region;
use crate::rt::Rt;
use crate::value::Value;
use crate::vm::{Frame, Handler};
use alloc::vec::Vec;

/// "FLSN". Bumped whenever the layout below changes.
pub const MAGIC: u32 = 0x464C_534E;
pub const VERSION: u32 = 1;

struct W {
    b: Vec<u8>,
}
impl W {
    fn u32(&mut self, v: u32) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    fn u64(&mut self, v: u64) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    fn usz(&mut self, v: usize) {
        self.u32(v as u32);
    }
    fn vals(&mut self, xs: &[Value]) {
        self.usz(xs.len());
        for v in xs {
            self.u64(v.bits());
        }
    }
    fn u32s(&mut self, xs: &[u32]) {
        self.usz(xs.len());
        for v in xs {
            self.u32(*v);
        }
    }
}

struct R<'a> {
    b: &'a [u8],
    i: usize,
}
impl<'a> R<'a> {
    fn u32(&mut self) -> u32 {
        let v = u32::from_le_bytes([self.b[self.i], self.b[self.i + 1], self.b[self.i + 2], self.b[self.i + 3]]);
        self.i += 4;
        v
    }
    fn u64(&mut self) -> u64 {
        let mut a = [0u8; 8];
        a.copy_from_slice(&self.b[self.i..self.i + 8]);
        self.i += 8;
        u64::from_le_bytes(a)
    }
    fn usz(&mut self) -> usize {
        self.u32() as usize
    }
    fn vals(&mut self) -> Vec<Value> {
        let n = self.usz();
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(Value(self.u64()));
        }
        out
    }
    fn u32s(&mut self) -> Vec<u32> {
        let n = self.usz();
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(self.u32());
        }
        out
    }
}

/// How much linear memory exists, which is the only bound a restore needs.
#[cfg(target_arch = "wasm32")]
fn memory_bytes() -> u64 {
    (core::arch::wasm32::memory_size(0) as u64) * 65536
}
#[cfg(not(target_arch = "wasm32"))]
fn memory_bytes() -> u64 {
    u64::MAX
}

/// The whole VM state as bytes, reusing `out`'s capacity.
///
/// Reuse is not an optimisation. A capture that allocates grows linear memory,
/// and growing memory shifts every subsequent allocation -- so an allocating
/// capture perturbs the very timing a collector bug depends on, and the run you
/// snapshot stops being the run you wanted to look at. Warm the buffer once and
/// later captures cost nothing.
pub fn capture_into(rt: &Rt, out: &mut Vec<u8>) {
    out.clear();
    let mut w = W { b: core::mem::take(out) };
    w.u32(MAGIC);
    w.u32(VERSION);

    // --- allocation geometry, so import can restore to the SAME addresses.
    // Every pointer in the heap is an absolute offset; restoring elsewhere
    // would mean rewriting them, which is a traversal, which is the thing
    // this design exists to avoid.
    let g = &rt.gc;
    w.u32(g.sp.in_use);
    w.u32(g.sp.reserved);
    w.u32(g.young_base);
    w.u32(g.half);
    w.u32(g.from);
    w.u32(g.to);
    w.u32(g.to_bump);
    w.u32(g.bump);
    w.u32(g.from_end);
    w.u32(g.old_capacity);
    w.u32(g.old_live);
    w.u32(g.max_heap);
    w.u32(g.collecting as u32);
    w.u32(g.oom as u32);
    w.u32(g.stress as u32);
    w.u32(g.bad_forward);
    w.usz(g.old_chunks.len());
    for c in &g.old_chunks {
        w.u32(c.addr);
        w.u32(c.len);
    }
    w.u32s(&g.free_lists);
    // The remembered set as a LIST. The per-object FLAGS travel in the heap
    // bytes below, in each object's header. This investigation turned on those
    // two being able to disagree, so both are captured and neither is derived.
    w.u32s(&g.remembered);
    w.u64(g.stats.minor);
    w.u64(g.stats.major);
    w.u64(g.stats.bytes_allocated);
    w.u64(g.stats.bytes_copied);
    w.u64(g.stats.bytes_promoted);
    w.u64(g.stats.peak_live);

    // --- roots
    let r = &rt.roots;
    w.usz(r.stack_top);
    w.vals(&r.stack[..r.stack_top]);
    w.vals(&r.shadow);
    w.vals(&r.globals);
    w.vals(&r.consts);
    w.vals(&r.singletons);
    w.usz(r.interns.len());
    for t in r.interns.iter() {
        w.usz(t.slots.len());
        w.usz(t.count);
        for (h, v) in t.slots.iter() {
            w.u32(*h);
            w.u64(*v);
        }
    }

    // --- interpreter state
    w.usz(rt.frames.len());
    for f in &rt.frames {
        w.usz(f.fp);
        w.u32(f.ip);
        w.u32(f.end);
        w.usz(f.ret_to);
        w.usz(f.handlers);
    }
    w.usz(rt.handlers.len());
    for h in &rt.handlers {
        w.usz(h.frame);
        w.usz(h.stack_top);
        w.u32(h.target);
        w.usz(h.shadow);
    }
    w.u64(rt.thrown.bits());
    w.u64(rt.park_on.bits());
    w.u64(rt.steps);
    w.u64(rt.gas_limit);
    w.u64(rt.slice_end);
    w.u64(rt.checkpoint);
    w.u32(rt.gas_trips);
    w.u32(rt.mem_trips);
    w.u32(rt.status as u32);
    w.u32(rt.champ_added as u32);

    // --- the heap itself, verbatim, REGION BY REGION. No interpretation.
    //
    // Not one contiguous range: on wasm `Space::take` grows linear memory via
    // `sbrk`, so an old chunk can sit far above `in_use` and a capture that
    // assumed contiguity silently missed it. That is exactly the "answers some
    // questions confidently wrong" failure this design exists to prevent, and
    // the inspector's walk-completeness check is what caught it.
    let mut regions: Vec<(u32, u32)> = Vec::new();
    regions.push((g.young_base, g.half * 2));
    for c in &g.old_chunks {
        regions.push((c.addr, c.len));
    }
    w.usz(regions.len());
    for (addr, len) in &regions {
        w.u32(*addr);
        w.u32(*len);
        w.b.extend_from_slice(g.sp.bytes(*addr, *len));
    }
    *out = w.b;
}

/// The whole VM state as bytes.
pub fn capture(rt: &Rt) -> Vec<u8> {
    let mut out = Vec::new();
    capture_into(rt, &mut out);
    out
}

/// Restore a snapshot over this runtime. Returns false if it is not one, or is
/// from a different layout version -- refused by name rather than read as a
/// plausible-looking heap that means something else.
pub fn restore(rt: &mut Rt, bytes: &[u8]) -> bool {
    if bytes.len() < 8 {
        return false;
    }
    let mut r = R { b: bytes, i: 0 };
    if r.u32() != MAGIC || r.u32() != VERSION {
        return false;
    }
    let in_use = r.u32();
    let _reserved = r.u32();
    let g = &mut rt.gc;
    g.young_base = r.u32();
    g.half = r.u32();
    g.from = r.u32();
    g.to = r.u32();
    g.to_bump = r.u32();
    g.bump = r.u32();
    g.from_end = r.u32();
    g.old_capacity = r.u32();
    g.old_live = r.u32();
    g.max_heap = r.u32();
    g.collecting = r.u32() != 0;
    g.oom = r.u32() != 0;
    g.stress = r.u32() != 0;
    g.bad_forward = r.u32();
    let nch = r.usz();
    g.old_chunks.clear();
    for _ in 0..nch {
        let addr = r.u32();
        let len = r.u32();
        g.old_chunks.push(Region { addr, len });
    }
    let fl = r.u32s();
    for (i, v) in fl.iter().enumerate() {
        if i < g.free_lists.len() {
            g.free_lists[i] = *v;
        }
    }
    g.remembered = r.u32s();
    g.stats.minor = r.u64();
    g.stats.major = r.u64();
    g.stats.bytes_allocated = r.u64();
    g.stats.bytes_copied = r.u64();
    g.stats.bytes_promoted = r.u64();
    g.stats.peak_live = r.u64();

    let stack_top = r.usz();
    let stack = r.vals();
    let shadow = r.vals();
    let globals = r.vals();
    let consts = r.vals();
    let singletons = r.vals();
    let nt = r.usz();
    let mut interns = Vec::with_capacity(nt);
    for _ in 0..nt {
        let n = r.usz();
        let count = r.usz();
        let mut slots = Vec::with_capacity(n);
        for _ in 0..n {
            let h = r.u32();
            let v = r.u64();
            slots.push((h, v));
        }
        interns.push(crate::gc::InternTable { slots, count });
    }

    let nf = r.usz();
    let mut frames = Vec::with_capacity(nf);
    for _ in 0..nf {
        let fp = r.usz();
        let ip = r.u32();
        let end = r.u32();
        let ret_to = r.usz();
        let handlers = r.usz();
        frames.push(Frame { fp, ip, end, ret_to, handlers });
    }
    let nh = r.usz();
    let mut handlers = Vec::with_capacity(nh);
    for _ in 0..nh {
        let frame = r.usz();
        let st = r.usz();
        let target = r.u32();
        let shadow = r.usz();
        handlers.push(Handler { frame, stack_top: st, target, shadow });
    }
    let thrown = Value(r.u64());
    let park_on = Value(r.u64());
    let steps = r.u64();
    let gas_limit = r.u64();
    let slice_end = r.u64();
    let checkpoint = r.u64();
    let gas_trips = r.u32();
    let mem_trips = r.u32();
    let status = r.u32() as i32;
    let champ_added = r.u32() != 0;

    // Regions, blitted back to the SAME addresses. Relocating would mean
    // rewriting every pointer, which is a traversal, which is what this avoids.
    let nreg = r.usz();
    let mut plan: Vec<(u32, u32, usize)> = Vec::new();
    for _ in 0..nreg {
        let addr = r.u32();
        let len = r.u32();
        if r.i + len as usize > bytes.len() {
            return false;
        }
        plan.push((addr, len, r.i));
        r.i += len as usize;
    }
    // Refuse rather than write garbage: if this runtime's memory does not
    // already cover a region, its addresses mean something else here. Read the
    // bound from linear memory rather than tracking a high-water mark, because
    // a field on `Space` costs 1 412 bytes on every module -- `take` is on the
    // allocation path and the extra branch is not free.
    for (addr, len, _) in &plan {
        if (*addr as u64) + (*len as u64) > memory_bytes() {
            return false;
        }
    }
    rt.gc.sp.in_use = in_use;
    for (addr, len, off) in plan {
        rt.gc.sp
            .bytes_mut(addr, len)
            .copy_from_slice(&bytes[off..off + len as usize]);
    }

    let rr = &mut rt.roots;
    if rr.stack.len() < stack.len() + 8 {
        rr.stack.resize(stack.len() + 8, crate::value::NIL);
    }
    for (i, v) in stack.iter().enumerate() {
        rr.stack[i] = *v;
    }
    rr.stack_top = stack_top;
    rr.shadow = shadow;
    rr.globals = globals;
    rr.consts = consts;
    rr.singletons = singletons;
    for (i, t) in interns.into_iter().enumerate() {
        if i < rr.interns.len() {
            rr.interns[i] = t;
        }
    }
    rt.frames = frames;
    rt.handlers = handlers;
    rt.thrown = thrown;
    rt.park_on = park_on;
    rt.steps = steps;
    rt.gas_limit = gas_limit;
    rt.slice_end = slice_end;
    rt.checkpoint = checkpoint;
    rt.gas_trips = gas_trips;
    rt.mem_trips = mem_trips;
    rt.status = status;
    rt.champ_added = champ_added;
    true
}
