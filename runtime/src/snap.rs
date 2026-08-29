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
use crate::value::{Value, NIL};
use crate::vm::{Frame, Handler};
use alloc::vec::Vec;

/// "FLSN". Bumped whenever the layout below changes.
pub const MAGIC: u32 = 0x464C_534E;
pub const VERSION: u32 = 3;

/// Why a restore was refused. `restore` answers a bool because that is what the
/// host ABI can carry; this says which of the two checks failed, so the message
/// names a cause instead of "no".
pub const REFUSE_NONE: u32 = 0;
pub const REFUSE_LAYOUT: u32 = 1;
pub const REFUSE_IMAGE: u32 = 2;
pub static mut REFUSED: u32 = REFUSE_NONE;

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
    /// An ADDRESS, at whatever width `Addr` is. Its own method so the format
    /// has one place that decides, rather than a `u32` per field that has to be
    /// found and changed together.
    fn addr(&mut self, v: crate::mem::Addr) {
        self.u64(v as u64);
    }
    fn vals(&mut self, xs: &[Value]) {
        self.usz(xs.len());
        for v in xs {
            self.u64(v.bits());
        }
    }
    fn addrs(&mut self, xs: &[crate::mem::Addr]) {
        self.usz(xs.len());
        for v in xs {
            self.u64(*v as u64);
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
    fn u8(&mut self) -> u8 {
        let v = self.b[self.i];
        self.i += 1;
        v
    }
    fn addr(&mut self) -> crate::mem::Addr {
        self.u64() as crate::mem::Addr
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
    fn addrs(&mut self) -> Vec<crate::mem::Addr> {
        let n = self.usz();
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(self.u64() as crate::mem::Addr);
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
    // The image this state belongs to. NOT the image itself: a snapshot carries
    // the heap and the VM state and no code, which is what keeps it small and is
    // the whole reason it can be moved. But every frame's `ip`, every constant
    // index and every var slot in it is an index INTO an image, so restoring one
    // against a different program does not fail -- it quietly means something
    // else. Carrying the fingerprint is what makes that refusable.
    w.u64(rt.image.fingerprint);

    // --- allocation geometry, so import can restore to the SAME addresses.
    // Every pointer in the heap is an absolute offset; restoring elsewhere
    // would mean rewriting them, which is a traversal, which is the thing
    // this design exists to avoid.
    let g = &rt.gc;
    w.addr(g.sp.in_use);
    w.addr(g.sp.reserved);
    w.addr(g.young_base);
    w.addr(g.half);
    w.addr(g.from);
    w.addr(g.to);
    w.addr(g.to_bump);
    w.addr(g.bump);
    w.addr(g.from_end);
    w.addr(g.old_capacity);
    w.addr(g.old_live);
    w.addr(g.max_heap);
    w.u32(g.collecting as u32);
    w.u32(g.oom as u32);
    w.u32(g.stress as u32);
    w.addr(g.bad_forward);
    w.usz(g.old_chunks.len());
    for c in &g.old_chunks {
        w.addr(c.addr);
        w.addr(c.len);
    }
    w.addrs(&g.free_lists);
    // The remembered set as a LIST. The per-object FLAGS travel in the heap
    // bytes below, in each object's header. This investigation turned on those
    // two being able to disagree, so both are captured and neither is derived.
    //
    // It is PER-EXECUTOR now (`doc/decisions/0028`), so what is captured is
    // this executor's plus whatever the last collection handed back. A
    // snapshot is taken from one executor and describes what it can see.
    w.addrs(&rt.roots.own.remembered);
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
    {
        // Var slots are atomic now; snapshot their values.
        let globals: Vec<Value> = r.shared.globals.iter().map(|g| g.get()).collect();
        w.vals(&globals);
    }
    w.vals(&r.shared.consts);
    w.vals(&r.shared.singletons);
    w.usz(r.shared.interns.len());
    for t in r.shared.interns.iter() {
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
    let mut regions: Vec<(crate::mem::Addr, crate::mem::Addr)> = Vec::new();
    regions.push((g.young_base, g.half * 2));
    for c in &g.old_chunks {
        regions.push((c.addr, c.len));
    }
    w.usz(regions.len());
    for (addr, len) in &regions {
        w.addr(*addr);
        w.addr(*len);
        w.b.extend_from_slice(g.sp.bytes(*addr, *len as u32));
    }
    *out = w.b;
}

/// The whole VM state as bytes.
pub fn capture(rt: &Rt) -> Vec<u8> {
    let mut out = Vec::new();
    capture_into(rt, &mut out);
    out
}

/// Count the host-minted opaque values an import brought back, and leave every
/// one of them ALONE.
///
/// This used to zero the host id, on the argument that "an imported snapshot
/// grants nothing". That is the wrong place to enforce it, and it makes
/// shelving useless: a sandbox holding a file handle comes back holding a
/// handle to nothing, and no host can put it right because the identity it
/// would rehydrate against has been erased.
///
/// The check belongs where `doc/decisions/0022` always said it belongs -- the
/// GRANT TABLE, not possession. A host that no longer honours id 7 refuses it
/// exactly as it refuses a forgery, and a host that wants the shelved sandbox
/// to carry on rebinds 7 to a live resource. Erasing the id took that choice
/// away from the only party entitled to make it.
///
/// The sandbox cannot exploit a preserved id, and that is a property of the
/// SURFACE rather than of this function: guest code can mint an opaque value
/// only with id 0, and there is deliberately no builtin that reads an id back.
/// So an id is a thing the host wrote and only the host can read.
///
/// The count is still taken, because `restored_capabilities` is what a test
/// reads to know the sweep saw anything at all -- a zero here would otherwise
/// pass every assertion for the wrong reason.
fn count_host_opaques(rt: &mut Rt) -> u32 {
    use crate::obj::{size_of, ty, TY_FREE, TY_OPAQUE};
    let mut n = 0u32;
    let mut clear = |sp: &crate::mem::Space, a: crate::mem::Addr| {
        if ty(sp, a) == TY_OPAQUE {
            n += 1;
        }
    };
    let (from, bump) = (rt.gc.from, rt.gc.bump);
    let mut a = from;
    while a < bump {
        clear(&rt.gc.sp, a);
        a += size_of(&rt.gc.sp, a);
    }
    let chunks = core::mem::take(&mut rt.gc.old_chunks);
    for ch in &chunks {
        let end = ch.addr + ch.len;
        let mut a = ch.addr;
        while a < end {
            let size = size_of(&rt.gc.sp, a);
            if size < 8 || a + size > end {
                break;
            }
            if ty(&rt.gc.sp, a) != TY_FREE {
                clear(&rt.gc.sp, a);
            }
            a += size;
        }
    }
    rt.gc.old_chunks = chunks;
    n
}

pub fn restore(rt: &mut Rt, bytes: &[u8]) -> bool {
    if bytes.len() < 8 {
        return false;
    }
    let mut r = R { b: bytes, i: 0 };
    unsafe { REFUSED = REFUSE_NONE };
    if r.u32() != MAGIC || r.u32() != VERSION {
        unsafe { REFUSED = REFUSE_LAYOUT };
        return false;
    }
    if bytes.len() < 16 {
        unsafe { REFUSED = REFUSE_LAYOUT };
        return false;
    }
    if r.u64() != rt.image.fingerprint {
        unsafe { REFUSED = REFUSE_IMAGE };
        return false;
    }
    let in_use = r.addr();
    let _reserved = r.addr();
    let g = &mut rt.gc;
    g.young_base = r.addr();
    g.half = r.addr();
    g.from = r.addr();
    g.to = r.addr();
    g.to_bump = r.addr();
    g.bump = r.addr();
    g.from_end = r.addr();
    g.old_capacity = r.addr();
    g.old_live = r.addr();
    g.max_heap = r.addr();
    g.collecting = r.u32() != 0;
    g.oom = r.u32() != 0;
    g.stress = r.u32() != 0;
    g.bad_forward = r.addr();
    let nch = r.usz();
    g.old_chunks.clear();
    for _ in 0..nch {
        let addr = r.addr();
        let len = r.addr();
        g.old_chunks.push(Region { addr, len });
    }
    let fl = r.addrs();
    for (i, v) in fl.iter().enumerate() {
        if i < g.free_lists.len() {
            g.free_lists[i] = *v;
        }
    }
    // Restored into THIS executor's list; see the note where it is written.
    let remembered = r.addrs();
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
        frames.push(Frame {
            fp,
            ip,
            end,
            ret_to,
            handlers,
            #[cfg(feature = "aot")]
            aot_idx: crate::vm::AOT_NONE,
            #[cfg(feature = "aot")]
            aot_ip: crate::vm::AOT_NEVER,
            #[cfg(feature = "aot")]
            aot_block: 0,
            instrs: 0,
            resumed: false,
        });
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
    let mut plan: Vec<(crate::mem::Addr, crate::mem::Addr, usize)> = Vec::new();
    for _ in 0..nreg {
        let addr = r.addr();
        let len = r.addr();
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
            .bytes_mut(addr, len as u32)
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
    // Back into THIS executor's list. A snapshot is taken and restored by one
    // executor, so that is where the set it captured belongs.
    rr.own.remembered = remembered;
    rr.shared.globals = globals.into_iter().map(crate::gc::GlobalSlot::new).collect();
    rr.shared.consts = consts;
    rr.shared.singletons = singletons;
    for (i, t) in interns.into_iter().enumerate() {
        if i < rr.shared.interns.len() {
            rr.shared.interns[i] = t;
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
    // LAST, after the heap is in place. Identities are PRESERVED; whether any
    // of them still means anything is the host's grant table to answer.
    rt.restored_capabilities = count_host_opaques(rt);
    true
}

// ---------------------------------------------------------------------------
// The LIVE-SET export: relocatable, and no dead objects in it.
//
// `capture`/`restore` above copy the heap verbatim. That is the right
// instrument for a POST-MORTEM -- it can capture a heap that is already
// corrupt, which is the one case a walk cannot -- and the wrong one for
// SHELVING, for two reasons the memcpy design cannot fix:
//
//   * it copies whatever is in the semispaces, dead objects and unused reserve
//     included. A trivial program's snapshot is 5.2 MB of which 1.6 MB is live;
//   * it pins the restore to identical addresses, because rewriting pointers
//     "would mean a traversal, which is the thing this design exists to avoid".
//     Shelving needs the opposite: rehydrate in another process, another heap.
//
// So this one traverses. `doc/decisions/0015`'s objection to a traversal is
// that one which misses an edge yields a snapshot missing an object, and then
// the capture is what needs debugging. That objection is fatal to a BESPOKE
// traversal and not to this one, because **the collector decides what is live
// and this only enumerates what survived**. A missed edge here would be a
// collector bug that loses objects in ordinary running, which is a thing the
// suite already checks for from several directions.
//
// It costs a major collection, which is also what it buys: the live set is
// dense, so the export is the size of the data rather than the size of the
// heap.

/// "FLSX". A different format from `MAGIC`, deliberately: the two are not
/// interchangeable and a reader should not have to guess.
pub const MAGIC_LIVE: u32 = 0x464C_5358;
pub const VERSION_LIVE: u32 = 1;

/// How a `Value` is written when it may point at the heap. One byte, so the
/// encoding is unambiguous rather than clever: a NaN-boxed value uses the bits
/// a tag would want.
const V_LITERAL: u8 = 0;
const V_REF: u8 = 1;

/// Every live object, in address order after a collection.
///
/// Address order rather than discovery order because it is derived from the
/// heap rather than from a walk this file wrote -- one less thing that can be
/// subtly wrong and still look plausible.
fn live_objects(rt: &Rt) -> Vec<crate::mem::Addr> {
    let mut out: Vec<crate::mem::Addr> = Vec::new();
    let sp = &rt.gc.sp;
    // The nursery is contiguous: a copying minor leaves exactly the survivors
    // between `from` and `bump`.
    let mut a = rt.gc.from;
    while a < rt.gc.bump {
        let size = crate::obj::size_of(sp, a);
        if size == 0 {
            break;
        }
        out.push(a);
        a += size;
    }
    // Old space is swept, so the holes are `TY_FREE` and everything else is
    // live.
    for ch in &rt.gc.old_chunks {
        let mut a = ch.addr;
        let end = ch.addr + ch.len;
        while a < end {
            let size = crate::obj::size_of(sp, a);
            if size == 0 {
                break;
            }
            let t = crate::obj::ty(sp, a);
            if t != crate::obj::TY_FREE {
                out.push(a);
            }
            a += size;
        }
    }
    out.sort_unstable();
    out
}

struct Index {
    addrs: Vec<crate::mem::Addr>,
}

impl Index {
    fn of(&self, addr: crate::mem::Addr) -> Option<u32> {
        self.addrs.binary_search(&addr).ok().map(|i| i as u32)
    }
}

fn write_value(w: &mut W, v: Value, ix: &Index) -> bool {
    if v.is_heap() {
        match ix.of(v.as_heap()) {
            Some(i) => {
                w.b.push(V_REF);
                w.u32(i);
                true
            }
            // A heap pointer to something the collector did not keep. That is
            // not a snapshot problem to paper over -- it means the walk and the
            // collector disagree, which is exactly the failure this design is
            // supposed to make impossible. Refuse, loudly, rather than write a
            // snapshot with a hole in it.
            None => false,
        }
    } else {
        w.b.push(V_LITERAL);
        w.u64(v.bits());
        true
    }
}

/// Export the live set. Returns false if the walk and the collector disagreed.
pub fn export_live(rt: &mut Rt, out: &mut Vec<u8>) -> bool {
    // The collector decides what is live. Everything below only enumerates.
    rt.gc.major(&mut rt.roots);

    let addrs = live_objects(rt);
    let ix = Index { addrs };
    out.clear();
    let mut w = W { b: core::mem::take(out) };
    w.u32(MAGIC_LIVE);
    w.u32(VERSION_LIVE);
    w.u64(rt.image.fingerprint);

    // --- objects: type, len, then the body.
    w.usz(ix.addrs.len());
    let mut ok = true;
    for &a in &ix.addrs {
        let t = crate::obj::ty(&rt.gc.sp, a);
        let n = crate::obj::len(&rt.gc.sp, a);
        w.b.push(t);
        w.u32(n);
        match crate::obj::layout_of(t) {
            crate::obj::Layout::Vals => {
                for i in 0..n {
                    let v = crate::obj::slot(&rt.gc.sp, a, i);
                    ok &= write_value(&mut w, v, &ix);
                }
            }
            // Everything past the standard header, verbatim. A string keeps
            // eight bytes of its own header before its bytes and the collector
            // does not scan them, so copying them raw is what preserves them.
            _ => {
                let size = crate::obj::size_of(&rt.gc.sp, a);
                let body = size - crate::obj::HDR;
                w.u32(body as u32);
                let bytes: Vec<u8> = rt.gc.sp.bytes(a + crate::obj::HDR, body as u32).to_vec();
                w.b.extend_from_slice(&bytes);
            }
        }
    }

    // --- roots, the same set `capture_into` writes and for the same reasons.
    let vals = |w: &mut W, xs: &[Value], ix: &Index, ok: &mut bool| {
        w.usz(xs.len());
        for v in xs {
            *ok &= write_value(w, *v, ix);
        }
    };
    w.usz(rt.roots.stack_top);
    let stack: Vec<Value> = rt.roots.stack[..rt.roots.stack_top].to_vec();
    vals(&mut w, &stack, &ix, &mut ok);
    let shadow: Vec<Value> = rt.roots.shadow.clone();
    vals(&mut w, &shadow, &ix, &mut ok);
    let globals: Vec<Value> = rt.roots.shared.globals.iter().map(|g| g.get()).collect();
    vals(&mut w, &globals, &ix, &mut ok);
    let consts: Vec<Value> = rt.roots.shared.consts.clone();
    vals(&mut w, &consts, &ix, &mut ok);
    let singletons: Vec<Value> = rt.roots.shared.singletons.clone();
    vals(&mut w, &singletons, &ix, &mut ok);

    // Intern tables are WEAK: an entry whose value did not survive is CLEARED
    // rather than written as a dangling index.
    //
    // Cleared IN PLACE, not compacted. These are open-addressed, so a slot's
    // position is part of the data structure -- dropping an entry from the
    // middle moves every later one out from under its probe sequence, and the
    // symptom would be a symbol that exists and cannot be found.
    w.usz(rt.roots.shared.interns.len());
    let tables: Vec<Vec<(u32, u64)>> =
        rt.roots.shared.interns.iter().map(|t| t.slots.clone()).collect();
    for slots in &tables {
        w.usz(slots.len());
        let mut count = 0usize;
        let mut kept: Vec<(u32, Value)> = Vec::with_capacity(slots.len());
        for (h, v) in slots {
            let v = Value(*v);
            let live = !v.is_heap() || ix.of(v.as_heap()).is_some();
            if live && !(*h == 0 && v.bits() == 0) {
                count += 1;
                kept.push((*h, v));
            } else {
                kept.push((0, Value(0)));
            }
        }
        w.usz(count);
        for (h, v) in &kept {
            w.u32(*h);
            ok &= write_value(&mut w, *v, &ix);
        }
    }

    // --- interpreter state, byte for byte as the verbatim format writes it.
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
    ok &= write_value(&mut w, rt.thrown, &ix);
    ok &= write_value(&mut w, rt.park_on, &ix);
    w.u64(rt.steps);
    w.u64(rt.gas_limit);
    w.u64(rt.slice_end);
    w.u64(rt.checkpoint);
    w.u32(rt.gas_trips);
    w.u32(rt.mem_trips);
    w.u32(rt.status as u32);
    w.u32(rt.champ_added as u32);
    *out = w.b;
    ok
}

/// Read a value written by `write_value`. `map` turns a snapshot index into the
/// address it was rebuilt at.
fn read_value(r: &mut R, map: &[crate::mem::Addr]) -> Value {
    match r.u8() {
        V_REF => {
            let i = r.u32() as usize;
            Value::heap(map[i])
        }
        _ => Value(r.u64()),
    }
}

/// Import a live-set export. Refuses a different layout or a different program,
/// exactly as `restore` does, and for the same reason: every index in it means
/// something only against the image it came from.
pub fn import_live(rt: &mut Rt, bytes: &[u8]) -> bool {
    if bytes.len() < 16 {
        unsafe { REFUSED = REFUSE_LAYOUT };
        return false;
    }
    let mut r = R { b: bytes, i: 0 };
    unsafe { REFUSED = REFUSE_NONE };
    if r.u32() != MAGIC_LIVE || r.u32() != VERSION_LIVE {
        unsafe { REFUSED = REFUSE_LAYOUT };
        return false;
    }
    if r.u64() != rt.image.fingerprint {
        unsafe { REFUSED = REFUSE_IMAGE };
        return false;
    }

    // Nothing of the old state may be reachable while the new objects are
    // being built, or a collection in the middle would try to keep both.
    rt.frames.clear();
    rt.handlers.clear();
    rt.roots.stack_top = 0;
    rt.roots.shadow.clear();
    for g in rt.roots.shared.globals.iter() {
        g.set(NIL);
    }

    // Pass one: allocate every object, empty.
    //
    // The addresses are held on the SHADOW STACK rather than in a Rust vector,
    // because allocating can collect and a collection moves what it has already
    // built. That is not a hypothetical: it is `doc/decisions/0031`, one file
    // over, and the shadow stack is what makes it a non-question here.
    let n = r.usz();
    let base = rt.mark();
    let mut bodies: Vec<(u8, u32, usize)> = Vec::with_capacity(n);
    let mut at = r.i;
    for _ in 0..n {
        let t = r.u8();
        let len = r.u32();
        let a = rt.alloc(t, len);
        if a == 0 {
            rt.pop_to(base);
            unsafe { REFUSED = REFUSE_LAYOUT };
            return false;
        }
        rt.push(Value::heap(a));
        // Skip the body; pass two comes back for it once every address exists.
        match crate::obj::layout_of(t) {
            crate::obj::Layout::Vals => {
                for _ in 0..len {
                    if r.u8() == V_REF {
                        r.u32();
                    } else {
                        r.u64();
                    }
                }
            }
            _ => {
                let body = r.u32() as usize;
                r.i += body;
            }
        }
        bodies.push((t, len, at));
        at = r.i;
    }

    // Every address, now that they all exist and nothing more will move them:
    // the objects are all rooted, so the map is taken AFTER the last allocation.
    let map: Vec<crate::mem::Addr> = (0..n).map(|i| rt.r(base + i).as_heap()).collect();

    // Pass two: fill the bodies.
    for (i, (t, len, off)) in bodies.iter().enumerate() {
        let a = map[i];
        let mut rr = R { b: bytes, i: *off };
        // The type and length again -- `off` points at the record, not past it.
        rr.u8();
        rr.u32();
        match crate::obj::layout_of(*t) {
            crate::obj::Layout::Vals => {
                for k in 0..*len {
                    let v = read_value(&mut rr, &map);
                    crate::obj::set_slot_raw(&rt.gc.sp, a, k, v);
                }
            }
            _ => {
                let body = rr.u32();
                let src: Vec<u8> = bytes[rr.i..rr.i + body as usize].to_vec();
                rt.gc.sp.bytes_mut(a + crate::obj::HDR, body).copy_from_slice(&src);
            }
        }
    }
    // An old object may now point at a young one, and the write barrier was
    // bypassed on purpose above -- `set_slot_raw` is what makes pass two a fill
    // rather than N barrier calls. Enrol every old object once, here.
    for &a in &map {
        if !rt.gc.is_young(a) {
            rt.gc.remember(a, &mut rt.roots.own.remembered);
        }
    }

    // --- roots
    let mut r = R { b: bytes, i: at };
    let stack_top = r.usz();
    let ns = r.usz();
    let mut stack = Vec::with_capacity(ns);
    for _ in 0..ns {
        stack.push(read_value(&mut r, &map));
    }
    let nsh = r.usz();
    let mut shadow = Vec::with_capacity(nsh);
    for _ in 0..nsh {
        shadow.push(read_value(&mut r, &map));
    }
    let ng = r.usz();
    let mut globals = Vec::with_capacity(ng);
    for _ in 0..ng {
        globals.push(read_value(&mut r, &map));
    }
    let nc = r.usz();
    let mut consts = Vec::with_capacity(nc);
    for _ in 0..nc {
        consts.push(read_value(&mut r, &map));
    }
    let nsg = r.usz();
    let mut singletons = Vec::with_capacity(nsg);
    for _ in 0..nsg {
        singletons.push(read_value(&mut r, &map));
    }
    let nt = r.usz();
    let mut interns = Vec::with_capacity(nt);
    for _ in 0..nt {
        let cap = r.usz();
        let count = r.usz();
        let mut slots = Vec::with_capacity(cap);
        for _ in 0..cap {
            let h = r.u32();
            let v = read_value(&mut r, &map);
            slots.push((h, v.bits()));
        }
        interns.push(crate::gc::InternTable { slots, count });
    }

    // --- interpreter state
    let nf = r.usz();
    let mut frames = Vec::with_capacity(nf);
    for _ in 0..nf {
        let fp = r.usz();
        let ip = r.u32();
        let end = r.u32();
        let ret_to = r.usz();
        let handlers = r.usz();
        frames.push(Frame {
            fp,
            ip,
            end,
            ret_to,
            handlers,
            #[cfg(feature = "aot")]
            aot_idx: crate::vm::AOT_NONE,
            #[cfg(feature = "aot")]
            aot_ip: crate::vm::AOT_NEVER,
            #[cfg(feature = "aot")]
            aot_block: 0,
            #[cfg(feature = "diagnostics")]
            instrs: 0,
            #[cfg(feature = "diagnostics")]
            resumed: true,
        });
    }
    let nh = r.usz();
    let mut handlers = Vec::with_capacity(nh);
    for _ in 0..nh {
        handlers.push(Handler {
            frame: r.usz(),
            stack_top: r.usz(),
            target: r.u32(),
            shadow: r.usz(),
        });
    }
    let thrown = read_value(&mut r, &map);
    let park_on = read_value(&mut r, &map);
    let steps = r.u64();
    let gas_limit = r.u64();
    let slice_end = r.u64();
    let checkpoint = r.u64();
    let gas_trips = r.u32();
    let mem_trips = r.u32();
    let status = r.u32();
    let champ_added = r.u32() != 0;

    rt.pop_to(base);
    rt.roots.stack.clear();
    rt.roots.stack.extend_from_slice(&stack);
    rt.roots.stack.resize(stack.len().max(stack_top) + 64, NIL);
    rt.roots.stack_top = stack_top;
    rt.roots.shadow = shadow;
    for (i, v) in globals.iter().enumerate() {
        if i < rt.roots.shared.globals.len() {
            rt.roots.shared.globals[i].set(*v);
        }
    }
    rt.roots.shared.consts = consts;
    rt.roots.shared.singletons = singletons;
    for (i, t) in interns.into_iter().enumerate() {
        if i < rt.roots.shared.interns.len() {
            rt.roots.shared.interns[i] = t;
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
    rt.status = status as i32;
    rt.champ_added = champ_added;
    // LAST, and for the same reason as `restore`: the identities come back
    // intact so a host can rehydrate against them.
    rt.restored_capabilities = count_host_opaques(rt);
    true
}

/// `main` should report "this sandbox was shelved", not "here is your answer".
/// 0 is a normal return and 2 is "I need the host" (`doc/decisions/0005`), so
/// this takes the next free code rather than overloading either.
pub const STATUS_SHELVED: i32 = 3;

/// Leave the sandbox with nothing runnable.
///
/// Not a flag the interpreter has to consult: there is simply nothing left to
/// run. The frame stack IS the continuation here -- that is what makes green
/// threads and snapshots cheap in the first place -- so dropping it is what
/// "stopped" means, and no loop needs a new condition in it.
///
/// The heap is deliberately left alone. It has just been exported, and a caller
/// that wants the memory back drops the whole instance; a caller that wants to
/// look at what it shelved still can.
pub fn halt(rt: &mut Rt) {
    rt.frames.clear();
    rt.handlers.clear();
    rt.roots.stack_top = 0;
    rt.roots.shadow.clear();
    rt.park_on = NIL;
    rt.thrown = NIL;
    rt.status = STATUS_SHELVED;
}
