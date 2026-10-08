//! Raw memory: one flat, byte-addressed address space that the whole runtime
//! lives inside.
//!
//! Every GC pointer in flint is a **32-bit address in this space**, never a
//! machine pointer. On wasm the space *is* linear memory (base = 0), so the
//! addresses are literally wasm addresses. On a 64-bit host (tests, native
//! benchmarks) the space is one big zeroed mapping and the base is its start.
//! That is what lets the identical GC and data-structure code be unit-tested
//! natively and shipped to wasm.

use alloc::vec::Vec;

/// A GC address: an offset into the flat space above, never a machine pointer.
///
/// **64 bits everywhere, including wasm32.** It does not hurt there -- an
/// address simply never exceeds 32 bits when linear memory cannot -- and it
/// buys two things that a per-target width would not. wasm64 works with no
/// change and no configuration. And there is ONE width to reason about, so a
/// snapshot, an image and a `Value` mean the same thing on every runtime
/// rather than nearly the same thing.
///
/// The `Value` encoding already allowed for it: `TAG_HEAP` leaves 48 payload
/// bits (256 TB, or 2 PB if the 8-byte alignment were folded in). Capping the
/// heap at 4 GB was an artificial limit inherited from the first target, not a
/// property of the representation.
pub type Addr = u64;

pub const PAGE: u32 = 65536;

/// Round up a 32-bit quantity. The wasm ARENA works in linear-memory addresses,
/// which are 32-bit by the platform's definition and have nothing to do with
/// flint's heap `Addr` -- it backs Rust's own allocator there, below the flint
/// heap entirely.
#[inline(always)]
pub fn align_up_u32(n: u32, a: u32) -> u32 {
    (n + a - 1) & !(a - 1)
}

/// Round up, on ADDRESSES. A run length in this space can exceed 4 GB now, so
/// the arithmetic is `Addr`-wide; the few callers that round a `u32` size cast
/// at the call.
#[inline(always)]
pub fn align_up(n: Addr, a: Addr) -> Addr {
    (n + a - 1) & !(a - 1)
}

// ---------------------------------------------------------------------------
// wasm: the arena owns all of linear memory above the program image, and backs
// both the GC and Rust's own `alloc` (there is no libc and no dlmalloc here).
// ---------------------------------------------------------------------------

#[cfg(target_arch = "wasm32")]
pub mod arena {
    use super::{align_up_u32 as align_up, PAGE};

    const NUM_SMALL: usize = 64; // 16,32,...,1024
    const SMALL_MAX: usize = NUM_SMALL * 16;
    const NUM_LARGE: usize = 32; // 2^k

    pub struct Arena {
        pub brk: u32,
        pub limit: u32,
        small: [u32; NUM_SMALL],
        large: [u32; NUM_LARGE],
        pub bytes_from_system: u32,
    }

    pub static mut ARENA: Arena = Arena {
        brk: 0,
        limit: 0,
        small: [0; NUM_SMALL],
        large: [0; NUM_LARGE],
        bytes_from_system: 0,
    };

    /// Called once at startup with the first address the runtime may use.
    pub unsafe fn init(start: u32) {
        let a = &mut *core::ptr::addr_of_mut!(ARENA);
        a.brk = align_up(start, 16);
        a.limit = (core::arch::wasm32::memory_size(0) as u32).wrapping_mul(PAGE);
        if a.limit < a.brk {
            a.limit = a.brk;
        }
    }

    unsafe fn grow_to(need: u32) -> bool {
        let a = &mut *core::ptr::addr_of_mut!(ARENA);
        if need <= a.limit {
            return true;
        }
        let extra = align_up(need - a.limit, PAGE) / PAGE;
        // Grow generously: doubling amortises `memory.grow` and keeps the
        // module from thrashing on a growing heap.
        let want = core::cmp::max(extra, (a.limit / PAGE) / 2 + 1);
        let r = core::arch::wasm32::memory_grow(0, want as usize);
        if r == usize::MAX {
            let r2 = core::arch::wasm32::memory_grow(0, extra as usize);
            if r2 == usize::MAX {
                return false;
            }
        }
        a.limit = (core::arch::wasm32::memory_size(0) as u32).wrapping_mul(PAGE);
        a.bytes_from_system = a.limit;
        true
    }

    /// Carve `bytes` (rounded to 16) off the top of the address space forever.
    pub unsafe fn sbrk(bytes: u32, align: u32) -> u32 {
        let a = &mut *core::ptr::addr_of_mut!(ARENA);
        let start = align_up(a.brk, align);
        let end = start.checked_add(bytes).unwrap_or(u32::MAX);
        if !grow_to(end) {
            return 0;
        }
        a.brk = end;
        start
    }

    #[inline]
    fn class_of(size: usize) -> usize {
        (size + 15) / 16 - 1
    }

    pub unsafe fn alloc(size: usize, align: usize) -> u32 {
        let a = &mut *core::ptr::addr_of_mut!(ARENA);
        if align > 16 {
            return sbrk(size as u32, align as u32);
        }
        if size <= SMALL_MAX {
            let c = class_of(size.max(1));
            let head = a.small[c];
            if head != 0 {
                a.small[c] = *(head as *const u32);
                return head;
            }
            return sbrk(((c + 1) * 16) as u32, 16);
        }
        // Power-of-two classes above 1 KiB.
        let k = (usize::BITS - (size - 1).leading_zeros()) as usize;
        let head = a.large[k];
        if head != 0 {
            a.large[k] = *(head as *const u32);
            return head;
        }
        sbrk(1u32 << k, 16)
    }

    pub unsafe fn free(addr: u32, size: usize, align: usize) {
        if addr == 0 || align > 16 {
            return;
        }
        let a = &mut *core::ptr::addr_of_mut!(ARENA);
        if size <= SMALL_MAX {
            let c = class_of(size.max(1));
            *(addr as *mut u32) = a.small[c];
            a.small[c] = addr;
        } else {
            let k = (usize::BITS - (size - 1).leading_zeros()) as usize;
            *(addr as *mut u32) = a.large[k];
            a.large[k] = addr;
        }
    }
}

#[cfg(target_arch = "wasm32")]
mod global_alloc {
    use core::alloc::{GlobalAlloc, Layout};

    pub struct FlintAlloc;

    unsafe impl GlobalAlloc for FlintAlloc {
        unsafe fn alloc(&self, l: Layout) -> *mut u8 {
            super::arena::alloc(l.size(), l.align()) as *mut u8
        }
        unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
            super::arena::free(p as u32, l.size(), l.align())
        }
    }

    #[global_allocator]
    static A: FlintAlloc = FlintAlloc;
}

// `alloc::alloc::alloc`/`alloc_zeroed` (what `Vec`, `Box` etc. in this
// `no_std` crate ultimately call) now tail-call `__rust_no_alloc_shim_is_unstable_v2`
// before `__rust_alloc`, unconditionally, as of rust-lang/rust#141061 --
// "make sure we don't accidentally allow omitting the allocator shim in
// stable code until it is actually stabilized." See `alloc_shim` in `lib.rs`,
// which now defines it alongside the other shim symbols this crate has
// always had to provide by hand for the same reason
// (DECISIONS.md#pin-the-nightly-toolchain: "whether to add it" was left
// undecided when a 2026-06-01 nightly first expected it -- now decided).

// ---------------------------------------------------------------------------
// Host: reserve address space, commit it on demand (`DECISIONS.md#growable-heap`).
// Three calls, so the platform is three functions and nothing else touches it.
// ---------------------------------------------------------------------------

#[cfg(all(unix, not(target_arch = "wasm32")))]
mod os {
    /// Address space only: `PROT_NONE` pages are neither memory nor commit
    /// charge, and touching one faults rather than reading a zero.
    pub unsafe fn reserve(len: usize) -> *mut u8 {
        let p = libc::mmap(
            core::ptr::null_mut(),
            len,
            libc::PROT_NONE,
            libc::MAP_PRIVATE | libc::MAP_ANON | libc::MAP_NORESERVE,
            -1,
            0,
        );
        if p == libc::MAP_FAILED { core::ptr::null_mut() } else { p as *mut u8 }
    }
    /// Make `[p, p + len)` readable and writable. Never-touched anonymous
    /// pages read as zero, which is what a fresh run promises.
    pub unsafe fn commit(p: *mut u8, len: usize) -> bool {
        len == 0 || libc::mprotect(p as *mut libc::c_void, len,
                                   libc::PROT_READ | libc::PROT_WRITE) == 0
    }
    pub unsafe fn release(p: *mut u8, len: usize) {
        libc::munmap(p as *mut libc::c_void, len);
    }
}

#[cfg(windows)]
mod os {
    use core::ffi::c_void;
    const MEM_COMMIT: u32 = 0x1000;
    const MEM_RESERVE: u32 = 0x2000;
    const MEM_RELEASE: u32 = 0x8000;
    const PAGE_NOACCESS: u32 = 0x01;
    const PAGE_READWRITE: u32 = 0x04;
    #[link(name = "kernel32")]
    extern "system" {
        fn VirtualAlloc(addr: *mut c_void, size: usize, ty: u32, protect: u32) -> *mut c_void;
        fn VirtualFree(addr: *mut c_void, size: usize, ty: u32) -> i32;
    }
    pub unsafe fn reserve(len: usize) -> *mut u8 {
        VirtualAlloc(core::ptr::null_mut(), len, MEM_RESERVE, PAGE_NOACCESS) as *mut u8
    }
    /// Committed pages are zero-filled by the OS.
    pub unsafe fn commit(p: *mut u8, len: usize) -> bool {
        len == 0 || !VirtualAlloc(p as *mut c_void, len, MEM_COMMIT, PAGE_READWRITE).is_null()
    }
    pub unsafe fn release(p: *mut u8, _len: usize) {
        VirtualFree(p as *mut c_void, 0, MEM_RELEASE);
    }
}

// ---------------------------------------------------------------------------
// The address space handed to the GC.
// ---------------------------------------------------------------------------

/// A run of bytes inside the flat space, described by 32-bit address + length.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Region {
    pub addr: Addr,
    pub len: Addr,
}


/// A `Space` may be MOVED to another thread, but not shared with one.
///
/// `base` is a raw pointer, so Rust assumes neither. On a host build the
/// pointer is to memory this `Space` allocated and solely owns -- nothing else
/// aliases it, and it carries no thread affinity -- so moving it is sound.
///
/// `Sync` is deliberately NOT claimed, and that is the whole safety argument.
/// Two threads inside one heap at the same time would race the collector: the
/// nursery moves objects, so a read on one thread can observe a pointer the
/// other is in the middle of forwarding. What makes several threads driving
/// one sandbox safe TODAY is that the driver holds the program behind a lock
/// (`DECISIONS.md#drivers`), so exactly one is ever inside. Lifting that needs
/// per-thread allocation buffers, safepoints and per-executor root sets -- at
/// which point this comment is what has to change first.
///
/// Not claimed on wasm at all: there `base` points into the module's single
/// global arena, which is shared rather than owned, so the argument above does
/// not hold.
#[cfg(not(target_arch = "wasm32"))]
unsafe impl Send for Space {}

pub struct Space {
    pub(crate) base: *mut u8,
    /// wasm: the arena is global, so nothing here is owned.
    #[allow(dead_code)]
    owned_len: usize,
    /// What the allocator returned, which `base` is aligned up from. Null on
    /// wasm, where nothing is owned.
    #[allow(dead_code)]
    raw: *mut u8,
    pub(crate) free_runs: Vec<Region>,
    /// The CEILING: the most this space will ever hand out. `take` refuses a
    /// run past it, which is where the catchable memory limit starts.
    pub reserved: Addr,
    pub in_use: Addr,
    /// How much of `reserved` is BACKED -- readable and writable. Grows in
    /// `take` by `kgen::rt::heapgrow::space_grow_to` (`DECISIONS.md#growable-heap`).
    /// Host only: on wasm the arena's `memory.grow` is the commitment, and a
    /// field here costs every module bytes on the allocation path.
    #[cfg(not(target_arch = "wasm32"))]
    pub committed: Addr,
    #[cfg(debug_assertions)]
    /// True while a collection is running over this space. The collector reads
    /// forwarded pointers as a matter of course -- that is how it updates them
    /// -- so `obj::slot`'s assertion has to exclude it. It lives here, per
    /// space, rather than in a global: the test harness runs Rts in parallel
    /// threads and a global made one Rt's collection silence another's check.
    pub in_gc: core::cell::Cell<bool>,
}

impl Space {
    /// `reserve` is the largest number of bytes the GC may ever hand out --
    /// the ceiling. On a host it is reserved as address space and committed
    /// in a growing prefix (`ensure`); on wasm linear memory grows instead.
    pub fn new(reserve: u32) -> Space {
        #[cfg(target_arch = "wasm32")]
        {
            let _ = reserve;
            Space {
                base: core::ptr::null_mut(),
                owned_len: 0,
                raw: core::ptr::null_mut(),
                free_runs: Vec::new(),
                reserved: u32::MAX as Addr,
                in_use: 0,
                #[cfg(debug_assertions)]
                in_gc: core::cell::Cell::new(false),
            }
        }
        #[cfg(not(target_arch = "wasm32"))]
        {
            // RESERVE THE CEILING, COMMIT A PREFIX (`DECISIONS.md#growable-heap`).
            //
            // This was `alloc_zeroed` of the whole ceiling, at align 16 so the
            // system allocator would take its `calloc` path and hand back pages
            // the OS zeroes on first touch (at PAGE alignment it zeroed all 3 GB
            // by hand: 5.0 GB resident for a program answering "hi", measured
            // 2026-10-01). That made the footprint a property of whichever
            // allocator was underneath -- lazy on the two it was measured
            // against, promised by none, and a 3 GB commit charge under strict
            // overcommit or on Windows. Now the ceiling is ADDRESS SPACE
            // (`PROT_NONE` / `MEM_RESERVE`), and only `committed` bytes of it
            // are memory. The base never moves, so nothing that cached it --
            // the AOT sync block, a raw `ptr()` -- can go stale.
            //
            // Over-reserved by one PAGE so the base can be aligned to it.
            let len = align_up(reserve as Addr, PAGE as Addr) as usize;
            let total = len + PAGE as usize;
            let raw = unsafe { os::reserve(total) };
            assert!(!raw.is_null(), "flint: could not reserve {len} bytes of address space");
            let p = align_up(raw as usize as Addr, PAGE as Addr) as usize as *mut u8;
            let committed = crate::kgen::rt::heapgrow::space_initial(len as Addr);
            assert!(unsafe { os::commit(p, committed as usize) },
                    "flint: could not commit {committed} bytes");
            Space {
                base: p,
                raw,
                owned_len: total,
                free_runs: Vec::new(),
                reserved: len as Addr,
                in_use: PAGE as Addr, // address 0 is never a valid object
                committed,
                #[cfg(debug_assertions)]
                in_gc: core::cell::Cell::new(false),
            }
        }
    }

    /// Back every byte below `end`, growing `committed` by the shared policy.
    /// False when `end` is past the ceiling or the OS refuses -- the caller
    /// turns that into the same catchable limit a full space always was.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn ensure(&mut self, end: Addr) -> bool {
        if end > self.reserved {
            return false;
        }
        if end <= self.committed {
            return true;
        }
        let want = crate::kgen::rt::heapgrow::space_grow_to(self.committed, end, self.reserved);
        let ok = unsafe {
            os::commit(self.ptr(self.committed), (want - self.committed) as usize)
        };
        if ok {
            self.committed = want;
        }
        ok
    }

    /// On wasm linear memory is the commitment, and `arena::sbrk` grows it.
    /// What is below `memory.size` is backed; the caller bounds by that.
    #[cfg(target_arch = "wasm32")]
    #[inline(always)]
    pub fn ensure(&mut self, _end: Addr) -> bool {
        true
    }

    #[inline(always)]
    pub fn base(&self) -> *mut u8 {
        self.base
    }

    /// Address of a fresh run of `len` bytes, page aligned. 0 on exhaustion.
    /// `len` is `Addr`-wide: a semispace or an old-space chunk can exceed 4 GB
    /// now, so a run length is measured in the same units as an address.
    pub fn take(&mut self, len: Addr) -> Addr {
        let len = align_up(len, PAGE as Addr);
        // Reuse an exact-fit or larger freed run first.
        let mut best: Option<usize> = None;
        for (i, r) in self.free_runs.iter().enumerate() {
            if r.len >= len && best.map_or(true, |b| self.free_runs[b].len > r.len) {
                best = Some(i);
            }
        }
        if let Some(i) = best {
            let r = self.free_runs[i];
            if r.len == len {
                self.free_runs.swap_remove(i);
            } else {
                self.free_runs[i] = Region { addr: r.addr + len, len: r.len - len };
            }
            return r.addr;
        }
        #[cfg(target_arch = "wasm32")]
        {
            // The wasm arena is 32-bit: linear memory cannot exceed 4 GB, so
            // the request narrows here and the answer widens back. This is the
            // one place the two address spaces meet.
            let a = unsafe { arena::sbrk(len as u32, PAGE) } as Addr;
            if a != 0 {
                self.in_use += len;
            }
            a
        }
        #[cfg(not(target_arch = "wasm32"))]
        {
            let end = match self.in_use.checked_add(len) {
                Some(e) if e <= self.reserved => e,
                _ => return 0,
            };
            if !self.ensure(end) {
                return 0;
            }
            let a = self.in_use;
            self.in_use += len;
            a
        }
    }

    pub fn give_back(&mut self, addr: Addr, len: Addr) {
        self.free_runs.push(Region { addr, len: align_up(len, PAGE as Addr) });
    }

    #[inline(always)]
    pub unsafe fn ptr(&self, addr: Addr) -> *mut u8 {
        self.base.wrapping_add(addr as usize)
    }
    #[inline(always)]
    pub fn read_u32(&self, addr: Addr) -> u32 {
        unsafe { core::ptr::read_unaligned(self.ptr(addr) as *const u32) }
    }
    #[inline(always)]
    pub fn write_u32(&self, addr: Addr, v: u32) {
        unsafe { core::ptr::write_unaligned(self.ptr(addr) as *mut u32, v) }
    }
    /// Where object addresses are measured from. Compiled code needs it to read
    /// a slot without a call back into Rust (`DECISIONS.md#emit-wasm-instead-of-dispatch`).
    ///
    /// `usize`, not `u32`. This is a HOST pointer -- `ptr()` two functions up
    /// adds an `Addr` to it -- and the `as u32` that used to be here was the
    /// same truncation `Value::as_heap` already has a comment about: right
    /// while the only reader was a wasm module, and a wild base everywhere
    /// else. The LLVM target is the everywhere else, and the symptom was a
    /// `SIGSEGV` on the first `:upval` (`DECISIONS.md#llvm-ir-target`).
    #[inline(always)]
    pub fn base_addr(&self) -> usize {
        self.base as usize
    }
    #[inline(always)]
    pub fn read_u64(&self, addr: Addr) -> u64 {
        unsafe { core::ptr::read_unaligned(self.ptr(addr) as *const u64) }
    }
    #[inline(always)]
    pub fn write_u64(&self, addr: Addr, v: u64) {
        unsafe { core::ptr::write_unaligned(self.ptr(addr) as *mut u64, v) }
    }
    // --- atomic word access -------------------------------------------------
    //
    // Every object is 8-aligned -- `size_for` rounds each layout to a multiple
    // of 8 and the arena base is aligned to 16 -- so `slot_addr`, which is
    // `base + 8 + i * 8`, is always 8-aligned and a `u64` atomic on it is
    // sound. The plain accessors above use `read_unaligned` because they also
    // serve string and byte payloads, which are not.
    //
    // These exist for ONE thing: a port's inbox, where two executors reserve
    // and publish without a lock (`DECISIONS.md#drivers`). Nothing else in the
    // heap is written by two threads at once -- a collection is stop-the-world
    // at a safepoint, so ordinary slots need no synchronisation at all.
    #[inline(always)]
    fn atomic_at(&self, addr: Addr) -> &core::sync::atomic::AtomicU64 {
        debug_assert!(addr % 8 == 0, "an atomic word must be 8-aligned, got {addr}");
        unsafe { &*(self.ptr(addr) as *const core::sync::atomic::AtomicU64) }
    }

    #[inline(always)]
    pub fn atomic_load(&self, addr: Addr) -> u64 {
        self.atomic_at(addr).load(core::sync::atomic::Ordering::Acquire)
    }

    /// Compare-and-swap. `Ok(())` when this thread won the slot.
    #[inline(always)]
    pub fn cas(&self, addr: Addr, want: u64, next: u64) -> bool {
        use core::sync::atomic::Ordering;
        self.atomic_at(addr)
            .compare_exchange(want, next, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }

    #[inline(always)]
    pub fn read_u8(&self, addr: Addr) -> u8 {
        unsafe { *self.ptr(addr) }
    }
    #[inline(always)]
    pub fn write_u8(&self, addr: Addr, v: u8) {
        unsafe { *self.ptr(addr) = v }
    }
    #[inline(always)]
    pub fn bytes(&self, addr: Addr, len: u32) -> &[u8] {
        unsafe { core::slice::from_raw_parts(self.ptr(addr), len as usize) }
    }
    #[inline(always)]
    pub fn bytes_mut(&self, addr: Addr, len: u32) -> &mut [u8] {
        unsafe { core::slice::from_raw_parts_mut(self.ptr(addr), len as usize) }
    }
    pub fn copy_within(&self, from: Addr, to: Addr, len: u32) {
        unsafe { core::ptr::copy(self.ptr(from), self.ptr(to), len as usize) }
    }
    pub fn zero(&self, addr: Addr, len: u32) {
        unsafe { core::ptr::write_bytes(self.ptr(addr), 0, len as usize) }
    }
}

#[cfg(not(target_arch = "wasm32"))]
impl Drop for Space {
    fn drop(&mut self) {
        if !self.raw.is_null() {
            unsafe { os::release(self.raw, self.owned_len) }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn space_hands_out_disjoint_runs() {
        let mut s = Space::new(4 * 1024 * 1024);
        let a = s.take((PAGE as Addr) as Addr);
        let b = s.take(2 * (PAGE as Addr) as Addr);
        assert_ne!(a, 0);
        assert_ne!(b, 0);
        assert!(b >= a + (PAGE as Addr) || a >= b + 2 * (PAGE as Addr));
        s.write_u64(a, 0xdead_beef_cafe_babe);
        s.write_u64(b, 1);
        assert_eq!(s.read_u64(a), 0xdead_beef_cafe_babe);
        assert_eq!(s.read_u64(b), 1);
    }

    #[test]
    fn space_reuses_returned_runs() {
        let mut s = Space::new(4 * 1024 * 1024);
        let a = s.take(2 * (PAGE as Addr));
        s.give_back(a, 2 * (PAGE as Addr));
        let b = s.take(2 * (PAGE as Addr) as Addr);
        assert_eq!(a, b, "a freed run should be reused, not leaked");
    }

    #[test]
    fn space_reports_exhaustion_rather_than_crashing() {
        let mut s = Space::new(2 * PAGE);
        assert_ne!(s.take(PAGE as Addr), 0);
        assert_eq!(s.take(64 * (PAGE as Addr)), 0);
    }

    /// A big ceiling starts small, grows as runs are taken, and the bytes
    /// already written survive the growth. Writing the LAST byte of each run
    /// is the probe: an uncommitted page faults, so this crashes rather than
    /// passes if `take` hands out a run it did not back.
    #[test]
    fn a_large_ceiling_starts_small_and_grows() {
        const M: Addr = 1 << 20;
        let mut s = Space::new(1 << 30);
        assert_eq!(s.reserved, 1 << 30);
        assert_eq!(s.committed, 8 * M, "a 1 GB ceiling should back 8 MiB at birth");
        let a = s.take(4 * M);
        s.write_u64(a, 0x1234_5678);
        let mut runs = Vec::new();
        for _ in 0..20 {
            let r = s.take(4 * M);
            assert_ne!(r, 0);
            s.write_u8(r + 4 * M - 1, 0xab);
            runs.push(r);
        }
        assert!(s.committed >= s.in_use && s.committed < 1 << 30,
                "committed {} for in_use {}", s.committed, s.in_use);
        assert_eq!(s.committed, 128 * M, "8 doubles to 128 MiB to cover ~84 MiB");
        assert_eq!(s.read_u64(a), 0x1234_5678, "growth lost a write");
        for r in runs {
            assert_eq!(s.read_u8(r + 4 * M - 1), 0xab);
        }
        // Fresh committed bytes read as zero, as a fresh run always did.
        let z = s.take(PAGE as Addr);
        assert_eq!(s.read_u64(z), 0);
    }

    /// The ceiling is still where `take` says no, and growth does not move it.
    #[test]
    fn growth_stops_at_the_ceiling() {
        const M: Addr = 1 << 20;
        let mut s = Space::new(24 * M as u32);
        assert_ne!(s.take(10 * M), 0);
        assert_eq!(s.committed, 16 * M, "8 MiB doubles to 16");
        assert_ne!(s.take(12 * M), 0);
        assert_eq!(s.committed, 24 * M, "doubling 16 would pass the ceiling of 24");
        assert_eq!(s.take(2 * M), 0, "in_use + 2 MiB is one PAGE past the ceiling");
        assert_ne!(s.take(2 * M - PAGE as Addr), 0);
        assert_eq!(s.committed, s.reserved);
        assert!(!s.ensure(s.reserved + 1));
    }

    #[test]
    fn byte_windows_are_addressable() {
        let s = Space::new(1024 * 1024);
        let mut s = s;
        let a = s.take((PAGE as Addr) as Addr);
        s.bytes_mut(a, 5).copy_from_slice(b"hello");
        assert_eq!(s.bytes(a, 5), b"hello");
        s.zero(a, 5);
        assert_eq!(s.bytes(a, 5), b"\0\0\0\0\0");
    }
}
