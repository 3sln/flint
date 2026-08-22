//! The collector.
//!
//! # Rooting (the decision everything else depends on)
//!
//! wasm has no scannable machine stack: the operand stack is not in linear
//! memory and locals are not addressable, so a collector *cannot* find roots
//! conservatively. flint therefore never tries. The root set is exact and
//! explicit, and lives in one struct, [`Roots`]:
//!
//! * `stack` - the VM's value stack. Interpreter frames are windows into this
//!   one `Vec<Value>`; every local, argument and intermediate of every active
//!   Clojure frame is in it. This is the primary root set and it costs nothing:
//!   the interpreter needs the stack anyway.
//! * `globals` / `consts` - var slots and the loaded image's constant pool.
//! * `shadow` - an explicit root stack for *native* code. A Rust builtin that
//!   holds a `Value` in a local across an allocation must push it here (see
//!   `rooted!`); Rust locals are invisible to the GC and this is the only way.
//! * `interns` - weak: entries are dropped when their object dies.
//!
//! # Layout
//!
//! * young: two equal semispaces, allocation is a bump pointer, collection is
//!   a copy. Objects that survive `PROMOTE_AGE` copies are promoted.
//! * old: chunks of pages, non-moving, mark-sweep with segregated free lists
//!   rebuilt (with coalescing) on every sweep.
//!
//! Old objects never move. That is the reason the write barrier and the
//! remembered set can stay this simple: a minor collection only ever rewrites
//! pointers that point *into the young semispace*, and there is exactly one
//! contiguous range to test against.

use alloc::vec::Vec;

use crate::mem::{align_up, Region, Space, PAGE};
use crate::obj::*;
use crate::value::Value;

pub const PROMOTE_AGE: u32 = 2;
/// Objects at least this big skip the nursery entirely.
pub const LARGE_OBJECT: u32 = 16 * 1024;
const NCLASS: usize = 65; // 0..63 exact (size = i*8), 64 = "big"
const MIN_CHUNK: u32 = 1024 * 1024;

// ---------------------------------------------------------------------------

/// Weak, hash-keyed interning table. Stores `(hash, value-bits)`; the hash is
/// stored so that rehashing after a collection never has to look at the heap.
pub struct InternTable {
    pub slots: Vec<(u32, u64)>,
    pub count: usize,
}

impl InternTable {
    pub fn new(cap_pow2: usize) -> InternTable {
        InternTable { slots: alloc::vec![(0u32, 0u64); cap_pow2], count: 0 }
    }
    #[inline]
    fn mask(&self) -> usize {
        self.slots.len() - 1
    }
    /// Probe for `hash`; calls `eq` on each candidate. Returns the found value
    /// or the index at which to insert.
    pub fn lookup<F: FnMut(Value) -> bool>(&self, hash: u32, mut eq: F) -> Result<Value, usize> {
        let mask = self.mask();
        let mut i = hash as usize & mask;
        loop {
            let (h, v) = self.slots[i];
            if v == 0 {
                return Err(i);
            }
            if h == hash && eq(Value(v)) {
                return Ok(Value(v));
            }
            i = (i + 1) & mask;
        }
    }
    pub fn insert_at(&mut self, idx: usize, hash: u32, v: Value) {
        self.slots[idx] = (hash, v.0);
        self.count += 1;
    }
    pub fn needs_grow(&self) -> bool {
        self.count * 4 >= self.slots.len() * 3
    }
    pub fn grow(&mut self) {
        let n2 = self.slots.len() * 2;
        let old = core::mem::replace(&mut self.slots, alloc::vec![(0u32, 0u64); n2]);
        self.count = 0;
        for (h, v) in old {
            if v != 0 {
                self.raw_insert(h, v);
            }
        }
    }
    fn raw_insert(&mut self, h: u32, v: u64) {
        let mask = self.mask();
        let mut i = h as usize & mask;
        while self.slots[i].1 != 0 {
            i = (i + 1) & mask;
        }
        self.slots[i] = (h, v);
        self.count += 1;
    }
    /// Rebuild, keeping only entries `f` maps to `Some`.
    fn refresh<F: FnMut(Value) -> Option<Value>>(&mut self, mut f: F) {
        let n = self.slots.len();
        let old = core::mem::replace(&mut self.slots, alloc::vec![(0u32, 0u64); n]);
        self.count = 0;
        for (h, v) in old {
            if v != 0 {
                if let Some(nv) = f(Value(v)) {
                    self.raw_insert(h, nv.0);
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

pub struct Roots {
    /// The interpreter's value stack. Only `[0, stack_top)` is live; slots
    /// above it are stale and must never be traced.
    pub stack: Vec<Value>,
    pub stack_top: usize,
    /// Explicit roots for native code holding values across an allocation.
    pub shadow: Vec<Value>,
    pub globals: Vec<Value>,
    pub consts: Vec<Value>,
    /// Weak tables (strings, keywords, symbols).
    pub interns: [InternTable; 3],
    /// Long-lived singletons: empty list/vector/map/set, cached results, ...
    pub singletons: Vec<Value>,
}

pub const INTERN_STR: usize = 0;
pub const INTERN_KW: usize = 1;
pub const INTERN_SYM: usize = 2;

impl Roots {
    pub fn new() -> Roots {
        Roots {
            stack: alloc::vec![Value(0); 1024],
            stack_top: 0,
            shadow: Vec::with_capacity(64),
            globals: Vec::new(),
            consts: Vec::new(),
            interns: [InternTable::new(1024), InternTable::new(1024), InternTable::new(512)],
            singletons: Vec::new(),
        }
    }

    fn for_each<F: FnMut(&mut Value)>(&mut self, mut f: F) {
        for v in &mut self.stack[..self.stack_top] {
            f(v);
        }
        for v in &mut self.shadow {
            f(v);
        }
        for v in &mut self.globals {
            f(v);
        }
        for v in &mut self.consts {
            f(v);
        }
        for v in &mut self.singletons {
            f(v);
        }
    }
}

// ---------------------------------------------------------------------------

#[derive(Default, Clone, Copy, Debug)]
pub struct GcStats {
    pub minor: u64,
    pub major: u64,
    pub bytes_allocated: u64,
    pub bytes_copied: u64,
    pub bytes_promoted: u64,
    pub old_live: u32,
    pub old_capacity: u32,
}

pub struct Gc {
    pub sp: Space,
    young_base: u32,
    half: u32,
    from: u32,
    to: u32,
    /// Bump pointer into the destination semispace; live only during `minor`.
    to_bump: u32,
    bump: u32,
    from_end: u32,
    old_chunks: Vec<Region>,
    free_lists: [u32; NCLASS],
    old_capacity: u32,
    old_live: u32,
    remembered: Vec<u32>,
    work: Vec<u32>,
    max_heap: u32,
    pub stats: GcStats,
    pub oom: bool,
    /// Set by tests/benchmarks to force a collection at every allocation.
    pub stress: bool,
}

impl Gc {
    pub fn new(nursery_bytes: u32, max_heap: u32) -> Gc {
        let half = align_up(nursery_bytes.max(64 * 1024), PAGE);
        let mut sp = Space::new(max_heap);
        let young_base = sp.take(half * 2);
        assert!(young_base != 0, "flint: cannot reserve nursery");
        let mut gc = Gc {
            sp,
            young_base,
            half,
            from: young_base,
            to: young_base + half,
            to_bump: 0,
            bump: young_base,
            from_end: young_base + half,
            old_chunks: Vec::new(),
            free_lists: [0; NCLASS],
            old_capacity: 0,
            old_live: 0,
            remembered: Vec::new(),
            work: Vec::new(),
            max_heap,
            stats: GcStats::default(),
            oom: false,
            stress: false,
        };
        gc.add_chunk(MIN_CHUNK);
        gc
    }

    #[inline(always)]
    pub fn is_young(&self, addr: u32) -> bool {
        addr.wrapping_sub(self.young_base) < self.half * 2
    }
    #[inline(always)]
    fn in_from(&self, addr: u32) -> bool {
        addr.wrapping_sub(self.from) < self.half
    }

    pub fn young_used(&self) -> u32 {
        self.bump - self.from
    }
    pub fn old_capacity(&self) -> u32 {
        self.old_capacity
    }
    pub fn old_live(&self) -> u32 {
        self.old_live
    }

    // --- old space -------------------------------------------------------

    fn add_chunk(&mut self, want: u32) -> bool {
        let size = align_up(want.max(MIN_CHUNK), PAGE);
        if self.old_capacity.saturating_add(self.half * 2).saturating_add(size) > self.max_heap {
            return false;
        }
        let addr = self.sp.take(size);
        if addr == 0 {
            return false;
        }
        self.old_chunks.push(Region { addr, len: size });
        self.old_capacity += size;
        write_header(&self.sp, addr, TY_FREE, size);
        self.push_free(addr, size);
        true
    }

    #[inline]
    fn class_of(size: u32) -> usize {
        let c = (size / 8) as usize;
        if c >= NCLASS {
            NCLASS - 1
        } else {
            c
        }
    }

    fn push_free(&mut self, addr: u32, size: u32) {
        write_header(&self.sp, addr, TY_FREE, size);
        if size < 16 {
            return; // an 8-byte hole: unlinkable, coalesced by the next sweep
        }
        let c = Self::class_of(size);
        self.sp.write_u32(addr + 8, self.free_lists[c]);
        self.free_lists[c] = addr;
    }

    fn take_free(&mut self, size: u32) -> u32 {
        let want = Self::class_of(size);
        // Exact and larger fixed classes first.
        for c in want..NCLASS - 1 {
            let head = self.free_lists[c];
            if head != 0 {
                self.free_lists[c] = self.sp.read_u32(head + 8);
                let bs = len(&self.sp, head);
                self.split(head, bs, size);
                return head;
            }
        }
        // The "big" list: first fit.
        let mut prev = 0u32;
        let mut cur = self.free_lists[NCLASS - 1];
        while cur != 0 {
            let bs = len(&self.sp, cur);
            let next = self.sp.read_u32(cur + 8);
            if bs >= size {
                if prev == 0 {
                    self.free_lists[NCLASS - 1] = next;
                } else {
                    self.sp.write_u32(prev + 8, next);
                }
                self.split(cur, bs, size);
                return cur;
            }
            prev = cur;
            cur = next;
        }
        0
    }

    fn split(&mut self, addr: u32, block: u32, want: u32) {
        let rest = block - want;
        if rest > 0 {
            self.push_free(addr + want, rest);
        }
    }

    fn alloc_old(&mut self, ty: u8, len_: u32) -> u32 {
        let size = size_for(ty, len_);
        let mut a = self.take_free(size);
        if a == 0 {
            if !self.add_chunk(size + PAGE) {
                self.oom = true;
                return 0;
            }
            a = self.take_free(size);
            if a == 0 {
                self.oom = true;
                return 0;
            }
        }
        self.old_live += size;
        write_header(&self.sp, a, ty, len_);
        a
    }

    // --- allocation ------------------------------------------------------

    /// Allocate an object. Slots (or the string's hash word) are zeroed, so a
    /// half-initialised object is always safe to trace.
    pub fn alloc(&mut self, roots: &mut Roots, ty: u8, len_: u32) -> u32 {
        let size = size_for(ty, len_);
        self.stats.bytes_allocated += size as u64;
        if size >= LARGE_OBJECT {
            let a = self.alloc_old(ty, len_);
            if a != 0 {
                self.zero_body(a, ty, len_);
                // A fresh old object may be given young pointers, and we do not
                // know yet, so enrol it in the remembered set up front.
                self.remember(a);
            }
            return a;
        }
        if self.stress {
            self.minor(roots);
            self.maybe_major(roots);
        }
        if self.bump + size > self.from_end {
            self.minor(roots);
            self.maybe_major(roots);
            if self.bump + size > self.from_end {
                // Nursery cannot hold it even when empty (should not happen
                // below LARGE_OBJECT, but be safe).
                let a = self.alloc_old(ty, len_);
                if a != 0 {
                    self.zero_body(a, ty, len_);
                    self.remember(a);
                }
                return a;
            }
        }
        let a = self.bump;
        self.bump += size;
        write_header(&self.sp, a, ty, len_);
        self.zero_body(a, ty, len_);
        a
    }

    #[inline]
    fn zero_body(&self, a: u32, ty: u8, len_: u32) {
        match layout_of(ty) {
            Layout::Vals => self.sp.zero(a + HDR, len_ * 8),
            Layout::Str => self.sp.write_u32(a + HDR, 0),
            Layout::Raw => {}
        }
    }

    /// A minor collection promotes, so the old generation can fill up without
    /// anyone asking for old-space memory directly. Check after every minor.
    fn maybe_major(&mut self, roots: &mut Roots) {
        if self.old_live * 2 > self.old_capacity {
            self.major(roots);
        }
    }

    // --- write barrier ---------------------------------------------------

    #[inline]
    fn remember(&mut self, obj: u32) {
        if !in_remset(&self.sp, obj) {
            set_in_remset(&self.sp, obj, true);
            self.remembered.push(obj);
        }
    }

    /// Store a value into a slot, running the generational write barrier.
    #[inline]
    pub fn set_slot(&mut self, obj: u32, i: u32, v: Value) {
        set_slot_raw(&self.sp, obj, i, v);
        if v.is_heap() && self.is_young(v.as_heap()) && !self.is_young(obj) {
            self.remember(obj);
        }
    }

    /// Alias for `set_slot`, used where the object was just allocated. Kept
    /// separate for readability only: making it skip the barrier was tried and
    /// is a footgun, because a large object is born in the *old* generation.
    #[inline]
    pub fn init_slot(&mut self, obj: u32, i: u32, v: Value) {
        self.set_slot(obj, i, v)
    }

    // --- minor collection ------------------------------------------------

    fn forward(&mut self, v: Value) -> Value {
        if !v.is_heap() {
            return v;
        }
        let a = v.as_heap();
        if !self.in_from(a) {
            return v;
        }
        if ty(&self.sp, a) == TY_FWD {
            return Value::heap(len(&self.sp, a));
        }
        let size = size_of(&self.sp, a);
        let new_age = age(&self.sp, a) + 1;
        let dest = if new_age >= PROMOTE_AGE {
            let t = ty(&self.sp, a);
            let l = len(&self.sp, a);
            let d = self.alloc_old(t, l);
            if d == 0 {
                // Out of old space: keep it young for now; the next major GC
                // will try again. Falls through to the young path below.
                0
            } else {
                self.stats.bytes_promoted += size as u64;
                d
            }
        } else {
            0
        };
        let dest = if dest != 0 {
            self.sp.copy_within(a, dest, size);
            set_age(&self.sp, dest, new_age);
            set_in_remset(&self.sp, dest, false);
            set_marked(&self.sp, dest, false);
            dest
        } else {
            let d = self.to_bump;
            debug_assert!(d + size <= self.to + self.half, "to-space overflow");
            self.to_bump += size;
            self.sp.copy_within(a, d, size);
            set_age(&self.sp, d, new_age.min(PROMOTE_AGE - 1));
            self.stats.bytes_copied += size as u64;
            d
        };
        write_header(&self.sp, a, TY_FWD, dest);
        self.work.push(dest);
        Value::heap(dest)
    }

    fn scan_object(&mut self, a: u32) {
        let t = ty(&self.sp, a);
        if layout_of(t) != Layout::Vals {
            return;
        }
        let n = len(&self.sp, a);
        let old = !self.is_young(a);
        let mut points_young = false;
        for i in 0..n {
            let v = slot(&self.sp, a, i);
            if v.is_heap() {
                let nv = self.forward(v);
                if nv != v {
                    set_slot_raw(&self.sp, a, i, nv);
                }
                if self.is_young(nv.as_heap()) {
                    points_young = true;
                }
            }
        }
        if old && points_young {
            self.remember(a);
        }
    }

    pub fn minor(&mut self, roots: &mut Roots) {
        self.stats.minor += 1;
        self.to_bump = self.to;
        self.work.clear();

        // 1. roots
        let mut pending: Vec<Value> = Vec::new();
        roots.for_each(|v| {
            if v.is_heap() {
                pending.push(*v);
            }
        });
        let mut fwd: Vec<Value> = Vec::with_capacity(pending.len());
        for v in &pending {
            fwd.push(self.forward(*v));
        }
        let mut k = 0usize;
        roots.for_each(|v| {
            if v.is_heap() {
                *v = fwd[k];
                k += 1;
            }
        });

        // 2. remembered set (old -> young edges)
        let old_rem = core::mem::take(&mut self.remembered);
        for a in &old_rem {
            set_in_remset(&self.sp, *a, false);
        }
        for a in old_rem {
            self.scan_object(a);
        }

        // 3. transitive closure
        while let Some(a) = self.work.pop() {
            self.scan_object(a);
        }

        // 4. weak tables
        let from = self.from;
        let half = self.half;
        let spc = &self.sp;
        for t in roots.interns.iter_mut() {
            t.refresh(|v| {
                if v.is_heap() && v.as_heap().wrapping_sub(from) < half {
                    let a = v.as_heap();
                    if ty(spc, a) == TY_FWD {
                        Some(Value::heap(len(spc, a)))
                    } else {
                        None // died in the nursery
                    }
                } else {
                    Some(v)
                }
            });
        }

        // 5. flip
        core::mem::swap(&mut self.from, &mut self.to);
        self.bump = self.to_bump;
        self.from_end = self.from + self.half;
    }

    // --- major collection ------------------------------------------------

    fn mark_from(&mut self, v: Value) {
        if !v.is_heap() {
            return;
        }
        let a = v.as_heap();
        if marked(&self.sp, a) {
            return;
        }
        set_marked(&self.sp, a, true);
        self.work.push(a);
    }

    pub fn major(&mut self, roots: &mut Roots) {
        self.minor(roots);
        self.stats.major += 1;
        self.work.clear();

        let mut seeds: Vec<Value> = Vec::new();
        roots.for_each(|v| {
            if v.is_heap() {
                seeds.push(*v)
            }
        });
        for v in seeds {
            self.mark_from(v);
        }
        while let Some(a) = self.work.pop() {
            let t = ty(&self.sp, a);
            if layout_of(t) != Layout::Vals {
                continue;
            }
            let n = len(&self.sp, a);
            for i in 0..n {
                let v = slot(&self.sp, a, i);
                self.mark_from(v);
            }
        }

        // Weak tables: anything unmarked is unreachable.
        {
            let spc = &self.sp;
            for t in roots.interns.iter_mut() {
                t.refresh(|v| if !v.is_heap() || marked(spc, v.as_heap()) { Some(v) } else { None });
            }
        }

        // The remembered set may name objects we are about to free.
        let rem = core::mem::take(&mut self.remembered);
        for a in rem {
            if marked(&self.sp, a) {
                self.remembered.push(a);
            } else {
                set_in_remset(&self.sp, a, false);
            }
        }

        self.sweep_old();

        // Clear marks on the (live) nursery.
        let mut a = self.from;
        while a < self.bump {
            set_marked(&self.sp, a, false);
            a += size_of(&self.sp, a);
        }

        // Grow if we are running hot: keep at least half the old space free.
        if self.old_live * 2 > self.old_capacity {
            let want = self.old_live.saturating_mul(2).saturating_sub(self.old_capacity);
            self.add_chunk(want);
        }
        self.stats.old_live = self.old_live;
        self.stats.old_capacity = self.old_capacity;
    }

    fn sweep_old(&mut self) {
        self.free_lists = [0; NCLASS];
        let mut live = 0u32;
        let chunks = core::mem::take(&mut self.old_chunks);
        for ch in &chunks {
            let end = ch.addr + ch.len;
            let mut a = ch.addr;
            let mut run_start = 0u32; // start of the current dead run, 0 = none
            while a < end {
                let size = size_of(&self.sp, a);
                debug_assert!(size >= 8 && a + size <= end, "old space parse error");
                let t = ty(&self.sp, a);
                let alive = t != TY_FREE && marked(&self.sp, a);
                if alive {
                    set_marked(&self.sp, a, false);
                    live += size;
                    if run_start != 0 {
                        self.push_free(run_start, a - run_start);
                        run_start = 0;
                    }
                } else if run_start == 0 {
                    run_start = a;
                }
                a += size;
            }
            if run_start != 0 {
                self.push_free(run_start, end - run_start);
            }
        }
        self.old_chunks = chunks;
        self.old_live = live;
    }

    /// Force a full collection.
    pub fn collect_all(&mut self, roots: &mut Roots) {
        self.major(roots);
    }
}

