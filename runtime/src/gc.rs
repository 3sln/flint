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

use crate::mem::{Addr, align_up, Region, Space, PAGE};
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
// --- allocation origin ------------------------------------------------------
// Which native was running when an object was allocated. A stale pointer can
// only come from somewhere the collector does not walk -- a Rust local -- so
// "who built this node" is answerable directly rather than by reading files.
#[cfg(feature = "diagnostics")]
pub static mut CUR_NATIVE: u32 = 0;
#[cfg(feature = "diagnostics")]
pub const ORIG_CAP: usize = 1 << 16;
#[cfg(feature = "diagnostics")]
pub static mut ORIG_ADDR: [Addr; ORIG_CAP] = [0; ORIG_CAP];
#[cfg(feature = "diagnostics")]
pub static mut ORIG_WHO: [u32; ORIG_CAP] = [0; ORIG_CAP];
/// A monotonic allocation serial. This bug propagates a stale pointer verbatim
/// into every clone, so several live nodes carry it and each was allocated by
/// somebody -- the stamp names a CARRIER, not necessarily the introducer.
/// Ordering the carriers and taking the earliest is what distinguishes them,
/// and picking between attributions by plausibility instead is the move that
/// made `flint/pow` briefly look like an answer.
#[cfg(feature = "diagnostics")]
pub static mut ORIG_SEQ: [u32; ORIG_CAP] = [0; ORIG_CAP];
/// A stale value caught at the instant it is written, rather than found later
/// in a scan. [count, obj, slot, value, obj-type, native, collection]
#[cfg(feature = "diagnostics")]
pub static mut STALE_SET: [Addr; 10] = [0; 10];

/// Roots that are still stale after a collection has finished.
/// [count, first address, collection, which array, index]
pub static mut STALE_ROOT: [Addr; 7] = [0; 7];

/// The whole shadow stack at the instant of the stale write, so the frame that
/// owns the bad slot is read off rather than inferred. [len, then addresses]
pub static mut STALE_SHADOW: [Addr; 65] = [0; 65];

/// A stale value ROOTED -- pushed onto the shadow stack. This is the exact
/// signature of a Rust local held across an allocation, caught one step earlier
/// than the write that finally makes it visible, and it costs one comparison.
/// [count, first address, collection, pushes checked]
pub static mut STALE_PUSH: [Addr; 4] = [0; 4];
#[cfg(feature = "diagnostics")]
pub static mut ORIG_N: usize = 0;
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

/// One EXECUTOR's roots.
///
/// A sandbox may have several threads inside it (`doc/decisions/0028`), and
/// each has its own value stack and its own shadow stack. These are the roots
/// that come in Ks; everything in `Roots` beside them is one per sandbox.
///
/// The collector has to scan every one of them, not just the one belonging to
/// whichever thread happened to trigger the collection. A thread whose roots
/// were skipped is a thread whose live objects are collected out from under
/// it, and it will not fail where it was skipped.
pub struct ExecRoots {
    /// The interpreter's value stack. Only `[0, stack_top)` is live; slots
    /// above it are stale and must never be traced.
    pub stack: Vec<Value>,
    pub stack_top: usize,
    /// Explicit roots for native code holding values across an allocation.
    pub shadow: Vec<Value>,
    /// Old objects this executor gave a young pointer to.
    ///
    /// PER-EXECUTOR, and that is the whole reason the write barrier is safe
    /// with several threads running. A shared `Vec` pushed to from the barrier
    /// would reallocate under another thread's push, and the barrier is far
    /// hotter than allocation -- locking it would cost more than it protects.
    ///
    /// The collector drains every executor's at a safepoint, which is the only
    /// time anything reads them.
    pub remembered: Vec<Addr>,
}

impl ExecRoots {
    pub fn new() -> ExecRoots {
        ExecRoots {
            stack: alloc::vec![Value(0); 1024],
            stack_top: 0,
            shadow: Vec::with_capacity(64),
            remembered: Vec::new(),
        }
    }
}

impl Default for ExecRoots {
    fn default() -> ExecRoots {
        ExecRoots::new()
    }
}

/// A parked executor's roots, by address.
///
/// A newtype rather than a bare `*mut`, so that the `Send` claim lands on this
/// and carries its argument with it instead of leaking onto everything that
/// happens to contain a `Roots`.
///
/// Sound because of WHEN it is read, not because of what it points at. A
/// collection runs with every other executor stopped at a safepoint
/// (`doc/decisions/0028`), so for the instant the collector walks these,
/// nothing else is touching them. Outside a safepoint the list is empty.
#[cfg(feature = "parallel")]
#[derive(Clone, Copy)]
pub struct ParkedRoots(pub *mut ExecRoots);

/// The pointer is to another executor's roots inside the SAME sandbox, and a
/// sandbox moves between threads as one thing. What must never happen is two
/// executors reading it at once outside a safepoint, and that is the
/// safepoint's job rather than this impl's.
#[cfg(feature = "parallel")]
unsafe impl Send for ParkedRoots {}

/// The roots there is ONE of per sandbox, however many executors it has.
///
/// Globals, constants, the intern tables and the singletons belong to the
/// program, not to whichever thread is running it, so several executors share
/// exactly this (`doc/decisions/0028`).
/// One var slot.
///
/// A newtype so the atomic access is in one place and the ordering argument is
/// written next to it, rather than at fourteen call sites.
/// Atomic only where there can be a second executor.
///
/// With one executor there is no one to race, and the atomic costs 7 277 bytes
/// of module -- MEASURED (249 542 against 242 265 on the pure module). Same
/// rule as the multi-executor root scan: absent rather than disabled
/// (`doc/decisions/0016`), because wasm cannot have a second executor at all.
///
/// Both spellings are `#[repr(transparent)]` over eight bytes, which is not
/// cosmetic: compiled code reads this array through a raw base pointer
/// (`flint.aot`), so the representation is part of an ABI.
#[cfg(feature = "parallel")]
#[repr(transparent)]
pub struct GlobalSlot(core::sync::atomic::AtomicU64);

#[cfg(feature = "parallel")]
impl GlobalSlot {
    pub fn new(v: Value) -> GlobalSlot {
        GlobalSlot(core::sync::atomic::AtomicU64::new(v.0))
    }
    #[inline(always)]
    pub fn get(&self) -> Value {
        Value(self.0.load(core::sync::atomic::Ordering::Relaxed))
    }
    #[inline(always)]
    pub fn set(&self, v: Value) {
        self.0.store(v.0, core::sync::atomic::Ordering::Relaxed);
    }
}

#[cfg(not(feature = "parallel"))]
#[repr(transparent)]
pub struct GlobalSlot(core::cell::Cell<Value>);

#[cfg(not(feature = "parallel"))]
impl GlobalSlot {
    pub fn new(v: Value) -> GlobalSlot {
        GlobalSlot(core::cell::Cell::new(v))
    }
    #[inline(always)]
    pub fn get(&self) -> Value {
        self.0.get()
    }
    #[inline(always)]
    pub fn set(&self, v: Value) {
        self.0.set(v);
    }
}

pub struct SharedRoots {
    /// Every OTHER executor in this sandbox, stopped at a safepoint.
    ///
    /// Raw, and sound only for the instant they are used: a collection happens
    /// with every other executor parked, so nothing else is touching these
    /// while the collector walks them. Outside a safepoint this is empty.
    ///
    /// Absent without the `parallel` feature rather than empty, because a
    /// module that can only ever have one executor should not carry the loop
    /// that walks the others -- 1 056 bytes, measured (`doc/decisions/0016`).
    #[cfg(feature = "parallel")]
    pub others: Vec<ParkedRoots>,
    /// Var slots, read by `GET_VAR` and written by `SET_VAR`.
    ///
    /// Atomic because several executors read them while one may be writing.
    /// `AtomicU64` has the same layout as the `u64` a `Value` is, which matters
    /// beyond tidiness: compiled code reads this array through a raw base
    /// pointer (`flint.aot`), so the representation is part of an ABI.
    ///
    /// Relaxed ordering throughout. A var slot carries no happens-before for
    /// anything else -- the heap object it names is published by the
    /// allocation lock and the safepoint -- so this only has to be a read that
    /// sees a whole value rather than half of one.
    pub globals: Vec<GlobalSlot>,
    pub consts: Vec<Value>,
    /// Weak tables (strings, keywords, symbols).
    pub interns: [InternTable; 4],
    /// Long-lived singletons: empty list/vector/map/set, cached results, ...
    pub singletons: Vec<Value>,
}

/// Anything the sandbox owns and its executors share, by address.
///
/// It DEREFS, which is the point: `self.image.code` reads like a field because
/// it was one, and the twenty-six sites that read it do not know the image
/// stopped belonging to an `Rt` and started belonging to the sandbox.
pub struct SandboxRef<T>(pub core::ptr::NonNull<T>);

impl<T> core::ops::Deref for SandboxRef<T> {
    type Target = T;
    fn deref(&self) -> &T {
        unsafe { self.0.as_ref() }
    }
}
impl<T> core::ops::DerefMut for SandboxRef<T> {
    fn deref_mut(&mut self) -> &mut T {
        unsafe { self.0.as_mut() }
    }
}
/// Sound for the same reason `GcPtr` is: what these point at outlives every
/// executor, and simultaneous access is the safepoint's problem.
unsafe impl<T> Send for SandboxRef<T> {}

/// The shared roots, by address.
///
/// A pointer rather than ownership because every executor in a sandbox reaches
/// the same one. It DEREFS, which is the point: `roots.shared.globals` reads
/// like a field because it was one.
pub struct SharedPtr(pub core::ptr::NonNull<SharedRoots>);

impl core::ops::Deref for SharedPtr {
    type Target = SharedRoots;
    fn deref(&self) -> &SharedRoots {
        unsafe { self.0.as_ref() }
    }
}
impl core::ops::DerefMut for SharedPtr {
    fn deref_mut(&mut self) -> &mut SharedRoots {
        unsafe { self.0.as_mut() }
    }
}
/// Sound for the same reason `ParkedRoots` is: a sandbox moves between threads
/// as one thing, and simultaneous access is the safepoint's problem rather
/// than this impl's.
unsafe impl Send for SharedPtr {}

pub struct Roots {
    /// The executor running on THIS thread, inline and under its own field
    /// names so that the interpreter's two thousand `roots.stack` sites are
    /// unaffected by any of this.
    pub own: ExecRoots,
    /// What every executor in this sandbox shares.
    pub shared: SharedPtr,
}

impl core::ops::Deref for Roots {
    type Target = ExecRoots;
    fn deref(&self) -> &ExecRoots {
        &self.own
    }
}

impl core::ops::DerefMut for Roots {
    fn deref_mut(&mut self) -> &mut ExecRoots {
        &mut self.own
    }
}

pub const INTERN_STR: usize = 0;
pub const INTERN_KW: usize = 1;
pub const INTERN_SYM: usize = 2;
/// Ports, keyed by id. **Weak on purpose** (`doc/decisions/0006`): the flint end
/// of a port is ordinary reachable memory, and when the collector finds it
/// unreachable that *means* the script is finished with it -- the scheduler
/// notices the entry has gone and raises `:closed` on the script's behalf. A
/// host end is additionally a strong root, so it survives regardless.
pub const INTERN_PORT: usize = 3;

impl SharedRoots {
    pub fn new() -> SharedRoots {
        SharedRoots {
            #[cfg(feature = "parallel")]
            others: Vec::new(),
            globals: Vec::new(),
            consts: Vec::new(),
            interns: [
                InternTable::new(1024),
                InternTable::new(1024),
                InternTable::new(512),
                // Four slots: a program with no ports never grows it, and one
                // with ports pays for what it uses.
                InternTable::new(4),
            ],
            singletons: Vec::new(),
        }
    }
}

impl Default for SharedRoots {
    fn default() -> SharedRoots {
        SharedRoots::new()
    }
}

/// Everything one sandbox's executors share: the heap and the roots that
/// belong to the program rather than to a thread.
///
/// Owned by whoever made the sandbox and pointed at by every `Rt` in it. It is
/// its own struct so that `gc` and `shared` stay SEPARATE FIELDS -- the
/// collector allocates as `gc.alloc(&mut shared, ...)`, and two fields can be
/// borrowed disjointly where two calls through one accessor cannot.
pub struct Heap {
    pub gc: Gc,
    pub shared: SharedRoots,
    /// The program. One per sandbox: every executor runs the same code.
    pub image: crate::vm::Image,
    /// The host's native functions, by index. Host builds only: on wasm a
    /// native is a table slot, not a function pointer.
    #[cfg(not(target_arch = "wasm32"))]
    pub host_natives: Vec<crate::vm::NativeFn>,
    /// What the host lent THIS SANDBOX. Per-sandbox, not per-executor: a
    /// capability is lent to a sandbox, and its threads are not separate
    /// tenants (`doc/decisions/0022`).
    pub grants: Vec<(alloc::string::String, u64)>,
    /// Have the image's initialisers run? Once per sandbox, not per executor
    /// and not per call.
    pub started: bool,
    /// The safepoint, the allocation lock and the shared gas counter. One per
    /// sandbox because they coordinate its executors with each other.
    #[cfg(feature = "parallel")]
    pub par: crate::par::Parallel,
}

impl Heap {
    pub fn new(nursery: u32, max: u32) -> Heap {
        Heap {
            gc: Gc::new(nursery, max),
            shared: SharedRoots::new(),
            image: Default::default(),
            #[cfg(not(target_arch = "wasm32"))]
            host_natives: Vec::new(),
            grants: Vec::new(),
            started: false,
            #[cfg(feature = "parallel")]
            par: crate::par::Parallel::new(),
        }
    }
}

/// The heap, by address.
///
/// Derefs to `Gc`, which is the whole point: the four hundred `self.gc.…`
/// sites in the interpreter do not know or care that the heap stopped being
/// something an `Rt` owns and became something several of them share.
pub struct GcPtr(pub core::ptr::NonNull<Gc>);

impl core::ops::Deref for GcPtr {
    type Target = Gc;
    fn deref(&self) -> &Gc {
        unsafe { self.0.as_ref() }
    }
}
impl core::ops::DerefMut for GcPtr {
    fn deref_mut(&mut self) -> &mut Gc {
        unsafe { self.0.as_mut() }
    }
}
/// Sound for the same reason `SharedPtr` is: exclusivity while a collection
/// runs is the safepoint's job (`doc/decisions/0028`), not this impl's.
unsafe impl Send for GcPtr {}

impl Roots {
    pub fn new(shared: SharedPtr) -> Roots {
        Roots { own: ExecRoots::new(), shared }
    }

    /// Does any TRACED root hold this address right now?
    ///
    /// A presence question, answered directly rather than through the thing
    /// presence causes. "It is reachable because it is on the value stack" is a
    /// rooting ARGUMENT, and a more careful reading of a rooting argument is
    /// still a rooting argument.
    #[cfg(feature = "diagnostics")]
    pub fn holds(&self, addr: Addr) -> bool {
        let hit = |v: &Value| v.is_heap() && v.as_heap() == addr;
        let top = self.own.stack_top;
        self.own.stack[..top].iter().any(hit)
            || self.own.shadow.iter().any(hit)
            // Every other executor too: "is this address rooted anywhere in
            // the sandbox" has to mean anywhere, or the answer is a rooting
            // argument rather than a fact.
            || self.parked_hold(addr)
            || self.shared.globals.iter().any(|g| hit(&g.get()))
            || self.shared.consts.iter().any(hit)
            || self.shared.singletons.iter().any(hit)
    }

    #[cfg(all(feature = "diagnostics", feature = "parallel"))]
    fn parked_hold(&self, addr: Addr) -> bool {
        let hit = |v: &Value| v.is_heap() && v.as_heap() == addr;
        self.shared.others.iter().any(|e| {
            let e = unsafe { &*e.0 };
            e.stack[..e.stack_top].iter().any(hit) || e.shadow.iter().any(hit)
        })
    }

    #[cfg(all(feature = "diagnostics", not(feature = "parallel")))]
    fn parked_hold(&self, _addr: Addr) -> bool {
        false
    }

    /// Every executor's remembered set, drained into one list.
    ///
    /// Only correct during a collection, which is the only time every other
    /// executor is stopped. Draining one executor's list and not the rest would
    /// lose old-to-young edges that another thread recorded, and a lost edge is
    /// a young object collected while an old one still points at it.
    fn drain_remembered(&mut self) -> Vec<Addr> {
        let mut out = core::mem::take(&mut self.own.remembered);
        #[cfg(feature = "parallel")]
        for e in self.shared.others.iter().copied() {
            let e = unsafe { &mut *e.0 };
            out.append(&mut e.remembered);
        }
        out
    }

    fn for_each<F: FnMut(&mut Value)>(&mut self, mut f: F) {
        // Read through the deref ONCE. `self.stack[..self.stack_top]` cannot
        // split its borrows the way two real fields could, because both go
        // through `DerefMut`.
        let top = self.own.stack_top;
        for v in &mut self.own.stack[..top] {
            f(v);
        }
        for v in &mut self.own.shadow {
            f(v);
        }
        // Every OTHER executor in this sandbox. A collection happens with all
        // of them parked at a safepoint (`doc/decisions/0028`), so nothing is
        // mutating these while they are walked.
        //
        // Skipping one would not fail here. It would collect that thread's
        // live objects out from under it, and it would fail somewhere else,
        // later, as a corrupted value in code that did nothing wrong -- which
        // is why this is one loop in one place rather than a rule to remember.
        #[cfg(feature = "parallel")]
        for e in self.shared.others.iter().copied() {
            let e = unsafe { &mut *e.0 };
            let top = e.stack_top;
            // A parked executor's roots do not change. If they have, the
            // collector is walking a thread that is still running -- say so
            // here rather than as an index panic four frames down.
            assert!(
                top <= e.stack.len(),
                "flint: scanning a RUNNING executor: stack_top {} past len {}",
                top,
                e.stack.len()
            );
            for v in &mut e.stack[..top] {
                f(v);
            }
            for v in &mut e.shadow {
                f(v);
            }
        }
        for g in &self.shared.globals {
            // Read, let the collector rewrite it, write back. Safe unlocked
            // because this only runs with every executor stopped.
            let mut v = g.get();
            f(&mut v);
            g.set(v);
        }
        for v in &mut self.shared.consts {
            f(v);
        }
        for v in &mut self.shared.singletons {
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
    pub old_live: Addr,
    pub old_capacity: Addr,
    /// High-water mark of live bytes -- old survivors plus whatever is in the
    /// nursery -- sampled at every collection. This is the number a memory
    /// claim has to be made against: "peak memory is proportional to content
    /// actually fetched" is a statement about *this*, not about how much has
    /// been allocated over a run (`doc/decisions/0008`).
    pub peak_live: u64,
}

pub struct Gc {
    pub sp: Space,
    pub(crate) young_base: Addr,
    pub(crate) half: Addr,
    pub(crate) from: Addr,
    pub(crate) to: Addr,
    /// Bump pointer into the destination semispace; live only during `minor`.
    pub(crate) to_bump: Addr,
    pub(crate) bump: Addr,
    pub(crate) from_end: Addr,
    pub(crate) old_chunks: Vec<Region>,
    pub(crate) free_lists: [Addr; NCLASS],
    pub(crate) old_capacity: Addr,
    pub(crate) old_live: Addr,
    /// What the COLLECTOR re-enrols while rebuilding the set.
    ///
    /// The mutators' lists are per-executor (`ExecRoots::remembered`); this one
    /// belongs to the collection itself, which runs with everything stopped.
    pub(crate) remembered_during_collect: Vec<Addr>,
    work: Vec<Addr>,
    pub(crate) max_heap: Addr,
    pub stats: GcStats,
    /// Bumped at the START of every collection, in every build.
    ///
    /// `stats` is a diagnostics-only counter; this one always exists, because
    /// something outside the collector needs to know when its cached view of an
    /// object's ADDRESS has expired. A copying collector moves objects, so a
    /// memo keyed on an address is valid only within one epoch -- and comparing
    /// the epoch is exact where comparing the address is not: an object can be
    /// freed and a different one allocated where it was.
    pub epoch: u64,
    pub oom: bool,
    /// Guards the retry below: a collection must not try to collect again when
    /// it is the thing that ran out of room.
    pub(crate) collecting: bool,
    /// Inside a collection. A collector writes leftover addresses on purpose;
    /// only mutator writes are suspect.
    #[cfg(feature = "diagnostics")]
    pub(crate) in_collect: bool,
    /// Set by tests/benchmarks to force a collection at every allocation.
    #[cfg(feature = "diagnostics")]
    pub stress: bool,
    /// Collect only for allocations in `[stress_from, stress_until)`. Bisecting
    /// this narrows a timing-dependent fault to the single allocation whose
    /// collection causes it -- at which point the question stops being "where in
    /// the collector" and becomes "what is live across THIS allocation and not
    /// rooted", which is small and answerable.
    #[cfg(feature = "diagnostics")]
    pub alloc_seq: u64,
    #[cfg(feature = "diagnostics")]
    pub stress_from: u64,
    #[cfg(feature = "diagnostics")]
    pub stress_until: u64,
    /// Run collections `[upgrade_from, upgrade_until)` as MAJORS instead of
    /// minors.
    ///
    /// This is the one perturbation that leaves allocation timing alone: the
    /// same number of collections happen at the same allocation indices, and a
    /// major performs no `Gc::alloc`, so nothing downstream shifts. Every other
    /// knob tried on the wave loss -- forcing a collection, a slice size, a
    /// guest code change -- moves *when* a collection happens, which is exactly
    /// what that bug is sensitive to. Upgrading discriminates instead.
    #[cfg(feature = "diagnostics")]
    pub upgrade_from: u64,
    #[cfg(feature = "diagnostics")]
    pub upgrade_until: u64,
    /// Log the traversal of exactly one collection. Scoped to one, this costs
    /// nothing and perturbs nothing -- the reason a hop-by-hop log was
    /// unaffordable before is that it would have run for all 748.
    #[cfg(feature = "diagnostics")]
    /// Check the generational invariant at the start of every collection.
    ///
    /// **Every old object pointing at a young one must be in the remembered
    /// set.** That is not a question about any particular bug -- it is THE
    /// invariant a generational collector rests on, and violating it means a
    /// young object is never traced, dies, and leaves a stale pointer behind in
    /// something that is still live. Silent, and it surfaces somewhere else
    /// entirely.
    ///
    /// It is read-only and allocates nothing, so unlike a snapshot it cannot
    /// perturb the run it is inspecting. Under `0016` a production build carries
    /// none of it; within a diagnostics build it is a flag because the walk is
    /// O(old heap) per collection.
    #[cfg(feature = "diagnostics")]
    pub verify_remset: bool,
    #[cfg(feature = "diagnostics")]
    pub remset_violations: u32,
    #[cfg(feature = "diagnostics")]
    pub remset_end_violations: u32,
    /// The first few, as (object, its type, slot, young target, target type).
    #[cfg(feature = "diagnostics")]
    pub remset_bad: [[Addr; 5]; 8],
    /// Coverage, not just result. "Zero violations" and "walked nothing
    /// relevant" produce identical output, and this codebase has already shipped
    /// one walker that reported success while covering part of the heap.
    #[cfg(feature = "diagnostics")]
    pub remset_walked: u32,
    #[cfg(feature = "diagnostics")]
    pub remset_walk_errors: u32,
    /// An address the walk must reach, or the zero above means nothing.
    #[cfg(feature = "diagnostics")]
    pub remset_watch: Addr,
    #[cfg(feature = "diagnostics")]
    pub remset_watch_seen: u32,
    /// A young pointer must be in the LIVE half, not merely inside the young
    /// address range.
    ///
    /// `is_young` spans BOTH semispaces, so a pointer left over from before a
    /// flip still tests young -- which makes a stale pointer indistinguishable
    /// from a live one to the write barrier and to the generational invariant
    /// alike. Asking the second question is what separates them, and it is
    /// cheap: two comparisons per slot.
    #[cfg(feature = "diagnostics")]
    pub dead_half_refs: u32,
    #[cfg(feature = "diagnostics")]
    pub dead_half_bad: [[Addr; 7]; 8],
    /// A young-range pointer handed to `forward` that is in NEITHER a live
    /// from-space object nor an already-copied to-space one.
    ///
    /// `forward` validates a from-space address, but says nothing about a
    /// pointer that is in the young range and in neither place -- and that case
    /// is never legitimate, because after a flip the abandoned half is neither
    /// from-space nor to-space, so `forward` finds such a pointer is not
    /// `in_from` and returns it UNCHANGED. One stale pointer is then preserved
    /// verbatim through every later collection and copied into every clone of
    /// its holder. Catching it here fires at the first collection AFTER it is
    /// created, rather than whenever somebody next walks the heap.
    #[cfg(feature = "diagnostics")]
    pub limbo_refs: u32,
    #[cfg(feature = "diagnostics")]
    pub limbo_bad: [[Addr; 4]; 8],
    /// `bump` at the END of a chosen collection, before any allocation that
    /// follows it. Comparing an object's address against THIS says whether it
    /// existed then or was created afterwards -- and the value at the next
    /// collection's walk cannot answer that, because it has already grown.
    #[cfg(feature = "diagnostics")]
    pub watch_end_cycle: u64,
    #[cfg(feature = "diagnostics")]
    pub watch_end_bump: Addr,
    #[cfg(feature = "diagnostics")]
    pub watch_end_from: Addr,
    /// Record allocation origins only for collections in `[from, until)`. The
    /// ring is a fixed size, so recording every allocation in an 18-million
    /// allocation run overwrites the interesting one long before anything asks.
    #[cfg(feature = "diagnostics")]
    pub orig_from: u64,
    #[cfg(feature = "diagnostics")]
    pub orig_until: u64,
    /// Values restored into the value stack at a resume that point into a half
    /// which is neither from- nor to-space.
    ///
    /// A parking native re-executes on resume and re-reads its arguments from a
    /// stack that was saved and restored in between. If the save area is not a
    /// scanned root, an argument comes back pointing at where its object used to
    /// be -- and `is_young` cannot tell, because it spans both halves. This is
    /// the same predicate the live-half check uses, applied at that boundary.
    #[cfg(feature = "diagnostics")]
    pub restore_stale: u32,
    #[cfg(feature = "diagnostics")]
    pub restore_bad: [[Addr; 4]; 8],
    /// Coverage: how many resumes were checked, and how many values across
    /// them. Zero violations from a check that never ran is not a result.
    #[cfg(feature = "diagnostics")]
    pub restores_checked: u32,
    #[cfg(feature = "diagnostics")]
    pub restore_values: u32,
    /// First from-space address `forward` was asked to treat as an object and
    /// could not believe. `0` means none seen. See `plausible_from_object`.
    #[cfg(feature = "diagnostics")]
    pub bad_forward: Addr,
}

impl Gc {
    pub fn new(nursery_bytes: u32, max_heap: u32) -> Gc {
        let half = align_up((nursery_bytes as Addr).max(64 * 1024), PAGE as Addr);
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
            remembered_during_collect: Vec::new(),
            work: Vec::new(),
            max_heap: max_heap as Addr,
            stats: GcStats::default(),
            epoch: 0,
            oom: false,
            collecting: false,
            #[cfg(feature = "diagnostics")]
            in_collect: false,
            #[cfg(feature = "diagnostics")]
            stress: false,
            #[cfg(feature = "diagnostics")]
            alloc_seq: 0,
            #[cfg(feature = "diagnostics")]
            stress_from: u64::MAX,
            #[cfg(feature = "diagnostics")]
            stress_until: 0,
            #[cfg(feature = "diagnostics")]
            upgrade_from: u64::MAX,
            #[cfg(feature = "diagnostics")]
            upgrade_until: 0,
            #[cfg(feature = "diagnostics")]
            #[cfg(feature = "diagnostics")]
            verify_remset: false,
            #[cfg(feature = "diagnostics")]
            remset_violations: 0,
            #[cfg(feature = "diagnostics")]
            remset_end_violations: 0,
            #[cfg(feature = "diagnostics")]
            remset_bad: [[0; 5]; 8],
            #[cfg(feature = "diagnostics")]
            remset_walked: 0,
            #[cfg(feature = "diagnostics")]
            remset_walk_errors: 0,
            #[cfg(feature = "diagnostics")]
            remset_watch: 0,
            #[cfg(feature = "diagnostics")]
            remset_watch_seen: 0,
            #[cfg(feature = "diagnostics")]
            dead_half_refs: 0,
            #[cfg(feature = "diagnostics")]
            dead_half_bad: [[0; 7]; 8],
            #[cfg(feature = "diagnostics")]
            limbo_refs: 0,
            #[cfg(feature = "diagnostics")]
            limbo_bad: [[0; 4]; 8],
            #[cfg(feature = "diagnostics")]
            watch_end_cycle: u64::MAX,
            #[cfg(feature = "diagnostics")]
            watch_end_bump: 0,
            #[cfg(feature = "diagnostics")]
            watch_end_from: 0,
            #[cfg(feature = "diagnostics")]
            orig_from: u64::MAX,
            #[cfg(feature = "diagnostics")]
            orig_until: 0,
            #[cfg(feature = "diagnostics")]
            restore_stale: 0,
            #[cfg(feature = "diagnostics")]
            restore_bad: [[0; 4]; 8],
            #[cfg(feature = "diagnostics")]
            restores_checked: 0,
            #[cfg(feature = "diagnostics")]
            restore_values: 0,
            #[cfg(feature = "diagnostics")]
            bad_forward: 0,
        };
        gc.add_chunk(MIN_CHUNK as Addr);
        gc
    }

    #[cfg(feature = "diagnostics")]
    pub fn from_now(&self) -> Addr { self.from }
    #[cfg(feature = "diagnostics")]
    pub fn bump_now(&self) -> Addr { self.bump }
    #[cfg(feature = "diagnostics")]
    pub fn to_now(&self) -> Addr { self.to }
    #[cfg(feature = "diagnostics")]
    pub fn half_now(&self) -> Addr { self.half }

    /// Is `addr` in the LIVE half -- an actually allocated young object -- as
    /// opposed to merely inside the young address range?
    ///
    /// `is_young` spans BOTH semispaces, so a pointer left over from before a
    /// flip still tests young. That makes a stale pointer indistinguishable from
    /// a live one to anything that only asks `is_young`, including the write
    /// barrier and the generational invariant check.
    #[cfg(feature = "diagnostics")]
    #[inline(always)]
    pub fn in_live_half(&self, addr: Addr) -> bool {
        addr >= self.from && addr < self.bump
    }
    #[inline(always)]
    pub fn is_young(&self, addr: Addr) -> bool {
        addr.wrapping_sub(self.young_base) < self.half * 2
    }
    #[inline(always)]
    fn in_from(&self, addr: Addr) -> bool {
        addr.wrapping_sub(self.from) < self.half
    }

    pub fn young_used(&self) -> Addr {
        self.bump - self.from
    }

    /// Would allocating `size` bytes collect?
    ///
    /// Asked BEFORE allocating, because under several executors a collection
    /// has to be staged -- everyone stopped -- and staging one around every
    /// allocation would be a stop-the-world per allocation. Conservative on
    /// purpose: a false yes costs one needless safepoint, a false no would let
    /// the collector move objects while another thread was running.
    #[cfg(feature = "parallel")]
    pub fn would_collect(&self, ty: u8, len_: u32) -> bool {
        let size = size_for(ty, len_);
        size >= LARGE_OBJECT as Addr || self.bump.saturating_add(size) > self.from.saturating_add(self.half)
    }

    /// Sample the high-water mark. Called after each collection, when the
    /// numbers mean something: mid-cycle the nursery is full of garbage.
    fn note_peak(&mut self) {
        let live = self.old_live as u64 + self.young_used() as u64;
        if live > self.stats.peak_live {
            self.stats.peak_live = live;
        }
    }
    pub fn old_capacity(&self) -> Addr {
        self.old_capacity
    }
    pub fn old_live(&self) -> Addr {
        self.old_live
    }
    /// Bytes of heap this program is permitted, and how much it holds now.
    pub fn heap_limit(&self) -> Addr {
        self.max_heap
    }
    pub fn set_heap_limit(&mut self, bytes: u32) {
        self.max_heap = bytes as Addr;
    }
    pub fn heap_used(&self) -> Addr {
        self.old_capacity.saturating_add(self.half * 2)
    }

    // --- old space -------------------------------------------------------

    fn add_chunk(&mut self, want: Addr) -> bool {
        let size = align_up((want as Addr).max(MIN_CHUNK as Addr), PAGE as Addr);
        if self.old_capacity.saturating_add(self.half * 2).saturating_add(size) > self.max_heap {
            return false;
        }
        let addr = self.sp.take(size);
        if addr == 0 {
            return false;
        }
        self.old_chunks.push(Region { addr, len: size });
        self.old_capacity += size;
        // A free block's `len` is a BYTE SIZE in a u32 field, so one block
        // caps at 4 GB even though the space no longer does. Chunks are far
        // smaller than that and there can be many, so this is a cast rather
        // than a limit anyone reaches.
        write_header(&self.sp, addr, TY_FREE, size as u32);
        self.push_free(addr, size);
        true
    }

    #[inline]
    fn class_of(size: Addr) -> usize {
        let c = (size / 8) as usize;
        if c >= NCLASS {
            NCLASS - 1
        } else {
            c
        }
    }

    fn push_free(&mut self, addr: Addr, size: Addr) {
        // A free block's `len` is a BYTE SIZE in a u32 field, so one block
        // caps at 4 GB even though the space no longer does. Chunks are far
        // smaller than that and there can be many, so this is a cast rather
        // than a limit anyone reaches.
        write_header(&self.sp, addr, TY_FREE, size as u32);
        if size < 16 {
            return; // an 8-byte hole: unlinkable, coalesced by the next sweep
        }
        let c = Self::class_of(size);
        // A `u64`, because the next-pointer IS an address. The `size < 16`
        // guard above is what reserves the room: a linked block always has the
        // eight bytes at +8 free, which is exactly a wide pointer.
        self.sp.write_u64(addr + 8, self.free_lists[c]);
        self.free_lists[c] = addr;
    }

    fn take_free(&mut self, size: Addr) -> Addr {
        let want = Self::class_of(size);
        // Exact and larger fixed classes first.
        for c in want..NCLASS - 1 {
            let head = self.free_lists[c];
            if head != 0 {
                self.free_lists[c] = self.sp.read_u64(head + 8);
                let bs = len(&self.sp, head) as Addr;
                self.split(head, bs, size);
                return head;
            }
        }
        // The "big" list: first fit.
        let mut prev = 0 as Addr;
        let mut cur = self.free_lists[NCLASS - 1];
        while cur != 0 {
            let bs = len(&self.sp, cur) as Addr;
            let next = self.sp.read_u64(cur + 8);
            if bs >= size {
                if prev == 0 {
                    self.free_lists[NCLASS - 1] = next;
                } else {
                    self.sp.write_u64(prev + 8, next);
                }
                self.split(cur, bs, size);
                return cur;
            }
            prev = cur;
            cur = next;
        }
        0
    }

    fn split(&mut self, addr: Addr, block: Addr, want: Addr) {
        let rest = block - want;
        if rest > 0 {
            self.push_free(addr + want, rest);
        }
    }

    /// Allocate in the old generation, **collecting before giving up**.
    ///
    /// Failing while there is still garbage to reclaim would make the memory cap
    /// depend on when the collector last ran, which is exactly the kind of
    /// timing dependence a deterministic limit exists to avoid
    /// (`doc/decisions/0009`).
    fn alloc_old_collecting(&mut self, roots: &mut Roots, ty: u8, len_: u32) -> Addr {
        let a = self.alloc_old(ty, len_);
        if a != 0 || self.collecting {
            return a;
        }
        self.oom = false;
        self.collecting = true;
        self.major(roots);
        self.collecting = false;
        self.alloc_old(ty, len_)
    }

    fn alloc_old(&mut self, ty: u8, len_: u32) -> Addr {
        let size = size_for(ty, len_);
        let mut a = self.take_free(size);
        if a == 0 {
            if !self.add_chunk(size + PAGE as Addr) {
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

    /// Allocate an object. Value slots are initialised to `nil`, so a
    /// half-built object is both safe to trace and semantically sane -- an
    /// unset trie slot reads as `nil`, not as the double `0.0`, which is what
    /// zero bits would have meant.
    pub fn alloc(&mut self, roots: &mut Roots, ty: u8, len_: u32) -> Addr {
        let size = size_for(ty, len_);
        self.stats.bytes_allocated += size as u64;
        if size >= LARGE_OBJECT as Addr {
            let a = self.alloc_old_collecting(roots, ty, len_);
            if a != 0 {
                self.zero_body(a, ty, len_);
                // A fresh old object may be given young pointers, and we do not
                // know yet, so enrol it in the remembered set up front.
                self.remember(a, &mut roots.own.remembered);
            }
            return a;
        }
        #[cfg(feature = "diagnostics")]
        {
            self.alloc_seq += 1;
            if self.stress
                || (self.alloc_seq >= self.stress_from && self.alloc_seq < self.stress_until)
            {
                self.collect_cycle(roots);
            }
        }
        if self.bump + size > self.from_end {
            self.collect_cycle(roots);
            if self.bump + size > self.from_end {
                // Nursery cannot hold it even when empty (should not happen
                // below LARGE_OBJECT, but be safe).
                let a = self.alloc_old_collecting(roots, ty, len_);
                if a != 0 {
                    self.zero_body(a, ty, len_);
                    self.remember(a, &mut roots.own.remembered);
                }
                return a;
            }
        }
        let a = self.bump;
        self.bump += size;
        #[cfg(feature = "diagnostics")]
        unsafe {
            // A ring, so it costs a constant and cannot grow the heap it is
            // observing -- and scoped to a window, so the entry that matters is
            // still there when something asks.
            if self.stats.minor >= self.orig_from && self.stats.minor < self.orig_until {
                let i = ORIG_N & (ORIG_CAP - 1);
                ORIG_ADDR[i] = a;
                ORIG_WHO[i] = CUR_NATIVE;
                ORIG_SEQ[i] = ORIG_N as u32;
                ORIG_N += 1;
            }
        }
        write_header(&self.sp, a, ty, len_);
        self.zero_body(a, ty, len_);
        a
    }

    #[inline]
    fn zero_body(&self, a: Addr, ty: u8, len_: u32) {
        match layout_of(ty) {
            Layout::Vals => {
                let dst = self.sp.bytes_mut(a + HDR, len_ * 8);
                for c in dst.chunks_exact_mut(8) {
                    c.copy_from_slice(&Value::NIL_.0.to_le_bytes());
                }
            }
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
    /// Enrol an old object in the remembered set.
    ///
    /// `&self`, not `&mut self`: the only thing this mutates in the heap is a
    /// flag bit in the object's own header, and the list it appends to belongs
    /// to the CALLING executor. That is what lets several threads run the write
    /// barrier at once without two of them holding `&mut Gc`, which would be
    /// aliasing UB whether or not the writes conflicted.
    ///
    /// Two executors can both set the flag on one object. They write the same
    /// bit, and the worst case is the object appearing in two executors'
    /// lists -- a second scan, not a wrong answer.
    pub(crate) fn remember(&self, obj: Addr, rem: &mut Vec<Addr>) {
        if !in_remset(&self.sp, obj) {
            set_in_remset(&self.sp, obj, true);
            rem.push(obj);
        }
    }

    /// Store a value into a slot, running the generational write barrier.
    #[inline]
    pub fn set_slot(&self, obj: Addr, i: u32, v: Value, rem: &mut Vec<Addr>) {
        // A young pointer that is not in the LIVE half is a leftover from
        // before a flip. `is_young` spans both semispaces, so no barrier and no
        // generational check can tell one from the other -- only this can, and
        // only at the moment of the write, which is the only moment that names
        // the culprit.
        #[cfg(feature = "diagnostics")]
        unsafe {
            if !self.in_collect
                && v.is_heap()
                && self.is_young(v.as_heap())
                && !self.in_live_half(v.as_heap())
            {
                STALE_SET[0] += 1;
                if STALE_SET[1] == 0 {
                    STALE_SET[1] = obj;
                    STALE_SET[2] = i as Addr;
                    STALE_SET[3] = v.as_heap();
                    STALE_SET[4] = crate::obj::ty(&self.sp, obj) as Addr;
                    STALE_SET[5] = CUR_NATIVE as Addr;
                    STALE_SET[6] = self.stats.minor as Addr;
                }
            }
        }
        set_slot_raw(&self.sp, obj, i, v);
        if v.is_heap() && self.is_young(v.as_heap()) && !self.is_young(obj) {
            self.remember(obj, rem);
        }
    }

    /// Alias for `set_slot`, used where the object was just allocated. Kept
    /// separate for readability only: making it skip the barrier was tried and
    /// is a footgun, because a large object is born in the *old* generation.
    #[inline]
    pub fn init_slot(&self, obj: Addr, i: u32, v: Value, rem: &mut Vec<Addr>) {
        self.set_slot(obj, i, v, rem)
    }

    // --- minor collection ------------------------------------------------

    /// Could `a` be the start of a live from-space object?
    ///
    /// `forward` used to take *any* from-space value as an object start. One
    /// stale pointer -- to an address that had been an object in an earlier
    /// nursery cycle and was now the middle of an unrelated live one -- was
    /// therefore enough to stamp a `TY_FWD` header into the middle of that
    /// object, and the damage surfaced much later and somewhere else entirely
    /// (`doc/HANDOFF.md`). This turns that into something catchable where it
    /// happens.
    #[cfg(feature = "diagnostics")]
    fn plausible_from_object(&self, a: Addr) -> bool {
        // Live from-space runs from `from` to the allocation top, which `bump`
        // still holds until the flip at the end of the collection.
        if a < self.from || a >= self.bump {
            return false;
        }
        let t = ty(&self.sp, a);
        // Nothing in the nursery is a free block, and a type tag out of range
        // is not a header at all.
        if t == TY_FREE || t >= TY_MAX {
            return false;
        }
        let size = size_of(&self.sp, a);
        size >= 8 && a.saturating_add(size) <= self.bump
    }

    fn forward(&mut self, v: Value) -> Value {
        if !v.is_heap() {
            return v;
        }
        let a = v.as_heap();
        if !self.in_from(a) {
            // In the young range but not in from-space: it can only legitimately
            // be a to-space address already copied this cycle. Anything else
            // points into the half this collection abandoned, at nothing.
            #[cfg(feature = "diagnostics")]
            if self.is_young(a) && !(a >= self.to && a < self.to_bump) && self.limbo_refs < u32::MAX
            {
                let k = self.limbo_refs as usize;
                if k < 8 {
                    self.limbo_bad[k] = [a, self.stats.minor as Addr, self.to, self.to_bump];
                }
                self.limbo_refs += 1;
            }
            return v;
        }
        // A FEATURE, not a runtime flag (doc/decisions/0016): a flag would leave
        // this linked and branched on in production, and it is 357 bytes. The
        // capability survives for dev and staging; production does not pay.
        #[cfg(feature = "diagnostics")]
        if ty(&self.sp, a) != TY_FWD && !self.plausible_from_object(a) && self.bad_forward == 0 {
            self.bad_forward = a;
            debug_assert!(false, "forward: {a} is not the start of a from-space object");
        }
        if ty(&self.sp, a) == TY_FWD {
            return Value::heap(forward_target(&self.sp, a));
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
            self.sp.copy_within(a, dest, size as u32);
            set_age(&self.sp, dest, new_age);
            set_in_remset(&self.sp, dest, false);
            set_marked(&self.sp, dest, false);
            dest
        } else {
            let d = self.to_bump;
            debug_assert!(d + size <= self.to + self.half, "to-space overflow");
            self.to_bump += size;
            self.sp.copy_within(a, d, size as u32);
            set_age(&self.sp, d, new_age.min(PROMOTE_AGE - 1));
            self.stats.bytes_copied += size as u64;
            d
        };
        set_forward(&self.sp, a, dest);
        self.work.push(dest);
        Value::heap(dest)
    }

    fn scan_object(&mut self, a: Addr) {
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
            // Inside a collection, so nothing else is running and this goes
            // straight back into the set being rebuilt.
            if !in_remset(&self.sp, a) {
                set_in_remset(&self.sp, a, true);
                self.remembered_during_collect.push(a);
            }
        }
    }

    /// One collection cycle. Normally a minor plus whatever `maybe_major`
    /// decides; under an upgrade window, a major instead.
    fn collect_cycle(&mut self, roots: &mut Roots) {
        #[cfg(feature = "diagnostics")]
        {
            // The index this cycle is about to take.
            let n = self.stats.minor + 1;
            if n >= self.upgrade_from && n < self.upgrade_until {
                self.major(roots);
                return;
            }
        }
        self.minor(roots);
        self.maybe_major(roots);
    }

    /// Walk every old object and check the generational invariant. Read-only.
    #[cfg(feature = "diagnostics")]
    /// `cycle` is the collection this walk belongs to. It must be passed in:
    /// the start-of-minor call runs BEFORE `stats.minor` is incremented and the
    /// end-of-minor call runs after, so reading the counter directly labels the
    /// two halves of the same collection with different numbers.
    pub fn check_remset(&mut self, cycle: u64) {
        // Old chunks AND the live half. The dead-half check below only means
        // something for the space it actually walks, and the first version of
        // this covered old space alone -- so a stale pointer sitting in a young
        // object was invisible to it and it reported a clean zero.
        let mut spans: Vec<(Addr, Addr)> =
            self.old_chunks.iter().map(|c| (c.addr, c.addr + c.len)).collect();
        spans.push((self.from, self.bump));
        for c in &spans {
            let mut a = c.0;
            let end = c.1;
            while a < end {
                let size = size_of(&self.sp, a);
                if size < 8 || a + size > end {
                    // A parse error. The walk cannot continue, and every object
                    // after this point is unvisited -- so it must be REPORTED,
                    // not silently truncated into a clean zero.
                    self.remset_walk_errors += 1;
                    break;
                }
                self.remset_walked += 1;
                if self.remset_watch != 0 && a == self.remset_watch {
                    self.remset_watch_seen += 1;
                }
                let t = ty(&self.sp, a);
                if t != TY_FREE && t != TY_FWD && layout_of(t) == Layout::Vals {
                    let n = len(&self.sp, a);
                    for i in 0..n {
                        let v = slot(&self.sp, a, i);
                        if v.is_heap() && self.is_young(v.as_heap()) && !self.in_live_half(v.as_heap()) {
                            // Points into the dead half: stale, and nothing that
                            // only asks `is_young` can tell.
                            let k = self.dead_half_refs as usize;
                            if k < 8 {
                                // The space bounds AS THEY STAND NOW, not as
                                // they stand when someone reads this later: a
                                // half boundary moves at every flip, so bounds
                                // from another moment classify nothing.
                                self.dead_half_bad[k] = [
                                    a,
                                    t as Addr,
                                    i as Addr,
                                    v.as_heap(),
                                    cycle as Addr,
                                    self.from,
                                    self.bump,
                                ];
                            }
                            self.dead_half_refs += 1;
                        }
                        if v.is_heap() && self.is_young(v.as_heap()) && !in_remset(&self.sp, a)
                            && !self.is_young(a)
                        {
                            let k = self.remset_violations as usize;
                            if k < 8 {
                                self.remset_bad[k] = [
                                    a,
                                    t as Addr,
                                    i as Addr,
                                    v.as_heap(),
                                    ty(&self.sp, v.as_heap()) as Addr,
                                ];
                            }
                            self.remset_violations += 1;
                            break; // one report per object is enough
                        }
                    }
                }
                a += size;
            }
            if a != end {
                self.remset_walk_errors += 1;
            }
        }
    }

    pub fn minor(&mut self, roots: &mut Roots) {
        #[cfg(feature = "diagnostics")]
        let was_collecting = core::mem::replace(&mut self.in_collect, true);
        #[cfg(feature = "diagnostics")]
        if self.verify_remset {
            let c = self.stats.minor + 1;
            self.check_remset(c);
        }
        #[cfg(debug_assertions)]
        self.sp.in_gc.set(true);
        self.epoch += 1;
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

        // 2. remembered set (old -> young edges), from EVERY executor
        let old_rem = roots.drain_remembered();
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
        for t in roots.shared.interns.iter_mut() {
            t.refresh(|v| {
                if v.is_heap() && v.as_heap().wrapping_sub(from) < half {
                    let a = v.as_heap();
                    if ty(spc, a) == TY_FWD {
                        Some(Value::heap(forward_target(spc, a)))
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
        self.note_peak();
        // A root that still points into the abandoned half once the flip is
        // done. Nothing downstream can distinguish this from a live pointer --
        // `is_young` spans both semispaces -- so it has to be caught here or
        // not at all.
        #[cfg(feature = "diagnostics")]
        unsafe {
            let (from, bump) = (self.from, self.bump);
            let (yb, span) = (self.young_base, self.half * 2);
            STALE_ROOT[5] += 1;
            let check = |v: &Value, which: u32, idx: u32| {
                STALE_ROOT[6] += 1;
                if v.is_heap() {
                    let a = v.as_heap();
                    if a.wrapping_sub(yb) < span && !(a >= from && a < bump) {
                        STALE_ROOT[0] += 1;
                        if STALE_ROOT[1] == 0 {
                            STALE_ROOT[1] = a;
                            STALE_ROOT[2] = self.stats.minor as Addr + 1;
                            STALE_ROOT[3] = which as Addr;
                            STALE_ROOT[4] = idx as Addr;
                        }
                    }
                }
            };
            for (k, v) in roots.stack[..roots.stack_top].iter().enumerate() {
                check(v, 0, k as u32);
            }
            for (k, v) in roots.shadow.iter().enumerate() {
                check(v, 1, k as u32);
            }
            for (k, g) in roots.shared.globals.iter().enumerate() {
                check(&g.get(), 2, k as u32);
            }
            for (k, v) in roots.shared.consts.iter().enumerate() {
                check(v, 3, k as u32);
            }
            for (k, v) in roots.shared.singletons.iter().enumerate() {
                check(v, 4, k as u32);
            }
        }
        #[cfg(feature = "diagnostics")]
        if self.stats.minor == self.watch_end_cycle {
            self.watch_end_bump = self.bump;
            self.watch_end_from = self.from;
        }
        // And at the END: a promotion during this collection can create a fresh
        // old-to-young edge, and if `scan_object` did not re-remember it the
        // next collection will never trace it.
        #[cfg(feature = "diagnostics")]
        if self.verify_remset {
            let before = self.remset_violations;
            let c = self.stats.minor;
            self.check_remset(c);
            if self.remset_violations > before {
                self.remset_end_violations += self.remset_violations - before;
            }
        }
        // Hand the rebuilt set back to a mutator's list.
        //
        // `scan_object` re-enrols every old object that STILL points young.
        // Leaving those in the collector's own list drops them from the
        // remembered set entirely, and the next minor collection then frees
        // young objects an old one is still holding -- which reads back as a
        // slot that lost its value, one collection later and nowhere near the
        // cause.
        roots.own.remembered.append(&mut self.remembered_during_collect);
        #[cfg(feature = "diagnostics")]
        {
            self.in_collect = was_collecting;
        }
        #[cfg(debug_assertions)]
        self.sp.in_gc.set(false);
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
        #[cfg(feature = "diagnostics")]
        let was_collecting = core::mem::replace(&mut self.in_collect, true);
        #[cfg(debug_assertions)]
        self.sp.in_gc.set(true);
        self.epoch += 1;
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
            for t in roots.shared.interns.iter_mut() {
                t.refresh(|v| if !v.is_heap() || marked(spc, v.as_heap()) { Some(v) } else { None });
            }
        }

        // The remembered set may name objects we are about to free.
        let rem = roots.drain_remembered();
        for a in rem {
            if marked(&self.sp, a) {
                self.remembered_during_collect.push(a);
            } else {
                set_in_remset(&self.sp, a, false);
            }
        }

        self.sweep_old();
        #[cfg(debug_assertions)]
        self.sp.in_gc.set(false);
        self.note_peak();

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
        // Same as `minor`: the survivors go back to a mutator's list.
        roots.own.remembered.append(&mut self.remembered_during_collect);
        #[cfg(feature = "diagnostics")]
        {
            self.in_collect = was_collecting;
        }
    }

    fn sweep_old(&mut self) {
        self.free_lists = [0; NCLASS];
        let mut live = 0 as Addr;
        let chunks = core::mem::take(&mut self.old_chunks);
        for ch in &chunks {
            let end = ch.addr + ch.len;
            let mut a = ch.addr;
            let mut run_start = 0 as Addr; // start of the current dead run, 0 = none
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


// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// A tiny harness: a heap plus roots, with cons-cell helpers. Deliberately
    /// does *not* use the higher layers, so a failure here is a GC failure.
    struct H {
        /// Boxed and kept alive for the whole of `H`, because `r` points into
        /// it. The real runtime does the same thing for the same reason.
        heap: alloc::boxed::Box<SharedRoots>,
        gc: Gc,
        r: Roots,
    }

    impl H {
        fn new() -> H {
            H::with(64 * 1024, 64 * 1024 * 1024)
        }
        fn with(nursery: u32, max: u32) -> H {
            let mut heap = alloc::boxed::Box::new(SharedRoots::new());
            let shared = SharedPtr(core::ptr::NonNull::from(&mut *heap));
            H { heap, gc: Gc::new(nursery, max), r: Roots::new(shared) }
        }
        /// Push a value on the shadow root stack and return its index.
        /// Write a slot through the barrier, the way an `Rt` does: the list
        /// the barrier appends to is this harness's own executor roots.
        fn set_slot(&mut self, obj: Addr, i: u32, v: Value) {
            self.gc.set_slot(obj, i, v, &mut self.r.own.remembered);
        }
        fn init_slot(&mut self, obj: Addr, i: u32, v: Value) {
            self.gc.init_slot(obj, i, v, &mut self.r.own.remembered);
        }
        fn root(&mut self, v: Value) -> usize {
            self.r.shadow.push(v);
            self.r.shadow.len() - 1
        }
        fn get(&self, i: usize) -> Value {
            self.r.shadow[i]
        }
        fn cons(&mut self, car: Value, cdr: Value) -> Value {
            // car/cdr must survive the allocation, so root them first.
            let a = self.root(car);
            let b = self.root(cdr);
            let addr = self.gc.alloc(&mut self.r, TY_CONS, 4);
            assert_ne!(addr, 0, "out of memory");
            let (car, cdr) = (self.get(a), self.get(b));
            self.init_slot(addr, 0, car);
            self.init_slot(addr, 1, cdr);
            self.r.shadow.truncate(a);
            Value::heap(addr)
        }
        fn car(&self, v: Value) -> Value {
            slot(&self.gc.sp, v.as_heap(), 0)
        }
        fn cdr(&self, v: Value) -> Value {
            slot(&self.gc.sp, v.as_heap(), 1)
        }
        fn string(&mut self, s: &str) -> Value {
            let addr = self.gc.alloc(&mut self.r, TY_STR, s.len() as u32);
            assert_ne!(addr, 0);
            self.gc.sp.bytes_mut(addr + STR_DATA, s.len() as u32).copy_from_slice(s.as_bytes());
            Value::heap(addr)
        }
        fn str_of(&self, v: Value) -> &str {
            core::str::from_utf8(str_bytes(&self.gc.sp, v.as_heap())).unwrap()
        }
        /// Build the list (n-1 n-2 ... 0), rooted at shadow index `slot`.
        fn list(&mut self, n: i64) -> Value {
            let head = self.root(Value(0));
            for i in 0..n {
                let prev = self.get(head);
                let c = self.cons(Value::fixnum(i), prev);
                self.r.shadow[head] = c;
            }
            let v = self.get(head);
            self.r.shadow.truncate(head);
            v
        }
        fn list_len(&self, mut v: Value) -> i64 {
            let mut n = 0;
            while v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_CONS {
                n += 1;
                v = self.cdr(v);
            }
            n
        }
    }

    #[test]
    fn allocation_is_bump_and_headers_are_right() {
        let mut h = H::new();
        let a = h.gc.alloc(&mut h.r, TY_CONS, 4);
        let b = h.gc.alloc(&mut h.r, TY_CONS, 4);
        assert_eq!(b - a, HDR + 4 * 8);
        assert_eq!(ty(&h.gc.sp, a), TY_CONS);
        assert_eq!(len(&h.gc.sp, a), 4);
        // Slots start as nil, so a half-built object is both safe to trace and
        // reads sanely: an unset slot is `nil`, not the double 0.0.
        for i in 0..4 {
            assert_eq!(slot(&h.gc.sp, a, i), Value::NIL_);
            assert!(!slot(&h.gc.sp, a, i).is_heap());
        }
    }

    #[test]
    fn minor_keeps_reachable_and_drops_garbage() {
        let mut h = H::new();
        let keep = h.list(100);
        let k = h.root(keep);
        // A pile of unreachable conses.
        for _ in 0..2000 {
            let _ = h.cons(Value::fixnum(1), Value::fixnum(2));
        }
        let before = h.gc.young_used();
        h.gc.minor(&mut h.r);
        let after = h.gc.young_used();
        assert!(after < before / 2, "garbage should be gone: {before} -> {after}");
        let keep = h.get(k);
        assert_eq!(h.list_len(keep), 100);
        let mut v = keep;
        for i in (0..100).rev() {
            assert_eq!(h.car(v), Value::fixnum(i));
            v = h.cdr(v);
        }
    }

    #[test]
    fn roots_are_updated_to_the_new_addresses() {
        let mut h = H::new();
        let s = h.string("hello, flint");
        let i = h.root(s);
        let before = h.get(i);
        h.gc.minor(&mut h.r);
        let after = h.get(i);
        assert_ne!(before, after, "the object must have moved");
        assert_eq!(h.str_of(after), "hello, flint");
    }

    #[test]
    fn sharing_is_preserved_not_duplicated() {
        let mut h = H::new();
        let shared = h.string("shared");
        let s = h.root(shared);
        let a = h.cons(h.get(s), Value::fixnum(1));
        let ai = h.root(a);
        let b = h.cons(h.get(s), Value::fixnum(2));
        let bi = h.root(b);
        h.gc.minor(&mut h.r);
        let (a, b) = (h.get(ai), h.get(bi));
        assert_eq!(h.car(a), h.car(b), "one object, not two copies");
        assert_eq!(h.str_of(h.car(a)), "shared");
    }

    #[test]
    fn cycles_do_not_hang_and_survive() {
        let mut h = H::new();
        let c = h.cons(Value::fixnum(7), Value::NIL_);
        let ci = h.root(c);
        let addr = c.as_heap();
        h.set_slot(addr, 1, c); // c.cdr = c
        h.gc.minor(&mut h.r);
        let c = h.get(ci);
        assert_eq!(h.car(c), Value::fixnum(7));
        assert_eq!(h.cdr(c), c, "self-reference survives as a self-reference");
        h.gc.minor(&mut h.r);
        let c = h.get(ci);
        assert_eq!(h.cdr(c), c);
    }

    #[test]
    fn survivors_are_promoted_after_promote_age() {
        let mut h = H::new();
        let v = h.string("long lived");
        let i = h.root(v);
        for _ in 0..PROMOTE_AGE - 1 {
            h.gc.minor(&mut h.r);
            assert!(h.gc.is_young(h.get(i).as_heap()), "too eager");
        }
        h.gc.minor(&mut h.r);
        let a = h.get(i).as_heap();
        assert!(!h.gc.is_young(a), "should have been promoted to the old generation");
        assert_eq!(h.str_of(h.get(i)), "long lived");
        // And it stays put from now on: old objects never move.
        h.gc.minor(&mut h.r);
        assert_eq!(h.get(i).as_heap(), a);
    }

    #[test]
    fn write_barrier_keeps_an_old_to_young_pointer_alive() {
        let mut h = H::new();
        // Age a cons into the old generation.
        let holder = h.cons(Value::fixnum(0), Value::fixnum(0));
        let hi = h.root(holder);
        for _ in 0..=PROMOTE_AGE {
            h.gc.minor(&mut h.r);
        }
        let old_addr = h.get(hi).as_heap();
        assert!(!h.gc.is_young(old_addr));

        // Now point it at a brand new young object.
        let young = h.string("young");
        assert!(h.gc.is_young(young.as_heap()));
        h.set_slot(old_addr, 0, young);

        // The young object is reachable ONLY through the old object.
        for _ in 0..3 {
            h.gc.minor(&mut h.r);
        }
        let holder = h.get(hi);
        assert_eq!(holder.as_heap(), old_addr, "old object stayed put");
        let s = h.car(holder);
        assert_ne!(s, young, "the pointer should have been rewritten as it was evacuated");
        assert_eq!(h.str_of(s), "young", "write barrier lost the reference");
    }

    #[test]
    fn without_the_barrier_the_reference_would_be_lost() {
        // Guards the test above from silently passing for the wrong reason:
        // a raw store that skips the barrier really does lose the object.
        let mut h = H::new();
        let holder = h.cons(Value::fixnum(0), Value::fixnum(0));
        let hi = h.root(holder);
        for _ in 0..=PROMOTE_AGE {
            h.gc.minor(&mut h.r);
        }
        let old_addr = h.get(hi).as_heap();
        let young = h.string("young");
        set_slot_raw(&h.gc.sp, old_addr, 0, young); // deliberately unbarriered
        h.gc.minor(&mut h.r);
        let s = h.car(h.get(hi));
        // The collector never saw the edge, so the object was not evacuated and
        // the slot still holds the *stale* address into the dead semispace --
        // exactly the dangling pointer the barrier exists to prevent. Contrast
        // with the barriered case above, where the pointer was rewritten.
        assert_eq!(s, young, "unbarriered slot should still hold the stale address");
        assert_ne!(ty(&h.gc.sp, young.as_heap()), TY_FWD, "it was never evacuated");
        assert!(!h.gc.is_young(old_addr));
    }

    #[test]
    fn major_sweeps_the_old_generation() {
        let mut h = H::new();
        // Promote a lot of garbage.
        for _ in 0..4000 {
            let s = h.string("dead");
            let i = h.root(s);
            let _ = i;
        }
        // Everything above is rooted; drop it all and age the survivors out.
        h.r.shadow.clear();
        let keeper = h.string("keeper");
        let ki = h.root(keeper);
        for _ in 0..4 {
            h.gc.minor(&mut h.r);
        }
        // Force garbage into the old generation, then collect it.
        for _ in 0..4000 {
            let s = h.string("garbage that gets promoted");
            let i = h.root(s);
            for _ in 0..=PROMOTE_AGE {
                h.gc.minor(&mut h.r);
            }
            h.r.shadow.truncate(i);
        }
        let live_before = h.gc.old_live();
        h.gc.major(&mut h.r);
        let live_after = h.gc.old_live();
        assert!(live_after < live_before / 2, "old space not reclaimed: {live_before} -> {live_after}");
        assert_eq!(h.str_of(h.get(ki)), "keeper");
    }

    #[test]
    fn old_space_stays_parseable_and_reusable_across_many_cycles() {
        let mut h = H::new();
        let mut peak = 0;
        for round in 0..40 {
            let s = h.string("recycle me, repeatedly, at a size that splits blocks");
            let i = h.root(s);
            for _ in 0..=PROMOTE_AGE {
                h.gc.minor(&mut h.r);
            }
            assert!(!h.gc.is_young(h.get(i).as_heap()));
            h.r.shadow.truncate(i);
            h.gc.major(&mut h.r);
            peak = peak.max(h.gc.old_capacity());
            assert!(h.gc.old_live() < 64 * 1024, "round {round}: old space is growing without bound");
        }
        assert!(peak <= 8 * 1024 * 1024, "old space ballooned to {peak}");
    }

    #[test]
    fn large_objects_are_born_old_and_never_move() {
        let mut h = H::new();
        let big = alloc::vec![b'x'; (LARGE_OBJECT + 64) as usize];
        let s = h.string(core::str::from_utf8(&big).unwrap());
        let i = h.root(s);
        let a = s.as_heap();
        assert!(!h.gc.is_young(a), "a large object should skip the nursery");
        h.gc.minor(&mut h.r);
        h.gc.major(&mut h.r);
        assert_eq!(h.get(i).as_heap(), a);
        assert_eq!(h.str_of(h.get(i)).len(), big.len());
    }

    #[cfg(feature = "diagnostics")]
    #[test]
    fn stress_mode_collects_at_every_allocation() {
        let mut h = H::new();
        h.gc.stress = true;
        let l = h.list(500);
        let i = h.root(l);
        assert_eq!(h.list_len(h.get(i)), 500);
        // Every cons above was allocated under a full collection; the list is
        // still intact and its elements are still in order.
        let mut v = h.get(i);
        for n in (0..500).rev() {
            assert_eq!(h.car(v), Value::fixnum(n));
            v = h.cdr(v);
        }
        assert!(h.gc.stats.minor >= 500);
    }

    #[test]
    fn deep_structure_survives_collection_under_pressure() {
        let mut h = H::new();
        // A chain far deeper than any plausible recursion limit, to make sure
        // tracing is iterative and not recursive.
        let l = h.list(200_000);
        let i = h.root(l);
        h.gc.minor(&mut h.r);
        h.gc.major(&mut h.r);
        assert_eq!(h.list_len(h.get(i)), 200_000);
    }

    #[test]
    fn stale_stack_slots_are_not_traced() {
        let mut h = H::new();
        let s = h.string("popped");
        h.r.stack[0] = s;
        h.r.stack_top = 1;
        h.gc.minor(&mut h.r);
        assert_eq!(h.str_of(h.r.stack[0]), "popped");
        // Pop it: the slot still holds the old bits, but stack_top says dead.
        h.r.stack_top = 0;
        let before = h.r.stack[0];
        h.gc.minor(&mut h.r);
        assert_eq!(h.r.stack[0], before, "a popped slot must not be rewritten");
    }

    #[test]
    fn weak_interns_drop_dead_entries_and_forward_live_ones() {
        let mut h = H::new();
        let live = h.string("live");
        let li = h.root(live);
        let dead = h.string("dead");
        // The slot is looked up BEFORE the insert borrows the table, because
        // both now reach it through one `DerefMut` rather than two fields.
        let slot = h.r.shared.interns[INTERN_STR].lookup(1, |_| false).unwrap_err();
        h.r.shared.interns[INTERN_STR].insert_at(slot, 1, live);
        let slot = h.r.shared.interns[INTERN_STR].lookup(2, |_| false).unwrap_err();
        h.r.shared.interns[INTERN_STR].insert_at(slot, 2, dead);
        assert_eq!(h.r.shared.interns[INTERN_STR].count, 2);
        h.gc.minor(&mut h.r);
        assert_eq!(h.r.shared.interns[INTERN_STR].count, 1, "the unreachable entry should be gone");
        let found = h.r.shared.interns[INTERN_STR].lookup(1, |_| true).unwrap();
        assert_eq!(found, h.get(li), "the surviving entry was forwarded");
        assert_eq!(h.str_of(found), "live");
    }

    #[test]
    fn intern_table_grows_without_losing_entries() {
        let mut t = InternTable::new(8);
        for i in 1..100u32 {
            if t.needs_grow() {
                t.grow();
            }
            let idx = t.lookup(i, |_| false).unwrap_err();
            t.insert_at(idx, i, Value::fixnum(i as i64));
        }
        assert_eq!(t.count, 99);
        for i in 1..100u32 {
            assert_eq!(t.lookup(i, |v| v == Value::fixnum(i as i64)).unwrap(), Value::fixnum(i as i64));
        }
    }

    #[test]
    fn exhaustion_is_reported_not_crashed() {
        let mut h = H::with(64 * 1024, 4 * 1024 * 1024);
        // Retain everything so nothing can be collected.
        let mut n = 0u64;
        loop {
            let a = h.gc.alloc(&mut h.r, TY_STR, 4096);
            if a == 0 {
                break;
            }
            h.r.shadow.push(Value::heap(a));
            n += 1;
            assert!(n < 100_000, "should have run out long before this");
        }
        assert!(h.gc.oom, "OOM must be visible to the caller, not a panic");
        assert!(n > 100, "should have fitted a reasonable number first: {n}");
    }

    #[test]
    fn stats_are_accounted() {
        let mut h = H::new();
        let l = h.list(5000);
        let _ = h.root(l);
        let minors = h.gc.stats.minor;
        h.gc.minor(&mut h.r);
        h.gc.minor(&mut h.r);
        h.gc.minor(&mut h.r);
        // 5000 conses of 40 bytes each, plus whatever the harness used.
        assert!(h.gc.stats.bytes_allocated >= 5000 * (HDR as u64 + 32));
        assert!(h.gc.stats.bytes_copied > 0, "survivors were copied");
        assert!(h.gc.stats.bytes_promoted > 0, "survivors were promoted");
        assert_eq!(h.gc.stats.minor, minors + 3);
    }
}
