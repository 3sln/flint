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

pub const PAGE: u32 = 65536;

#[inline(always)]
pub fn align_up(n: u32, a: u32) -> u32 {
    (n + a - 1) & !(a - 1)
}

// ---------------------------------------------------------------------------
// wasm: the arena owns all of linear memory above the program image, and backs
// both the GC and Rust's own `alloc` (there is no libc and no dlmalloc here).
// ---------------------------------------------------------------------------

#[cfg(target_arch = "wasm32")]
pub mod arena {
    use super::{align_up, PAGE};

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

// ---------------------------------------------------------------------------
// The address space handed to the GC.
// ---------------------------------------------------------------------------

/// A run of bytes inside the flat space, described by 32-bit address + length.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Region {
    pub addr: u32,
    pub len: u32,
}

pub struct Space {
    base: *mut u8,
    /// wasm: the arena is global, so nothing here is owned.
    #[allow(dead_code)]
    owned_len: usize,
    free_runs: Vec<Region>,
    pub reserved: u32,
    pub in_use: u32,
    #[cfg(debug_assertions)]
    /// True while a collection is running over this space. The collector reads
    /// forwarded pointers as a matter of course -- that is how it updates them
    /// -- so `obj::slot`'s assertion has to exclude it. It lives here, per
    /// space, rather than in a global: the test harness runs Rts in parallel
    /// threads and a global made one Rt's collection silence another's check.
    pub in_gc: core::cell::Cell<bool>,
}

impl Space {
    /// `reserve` is the largest number of bytes the GC may ever hand out.
    /// On a host it is committed lazily by the OS (zero pages).
    pub fn new(reserve: u32) -> Space {
        #[cfg(target_arch = "wasm32")]
        {
            let _ = reserve;
            Space {
                base: core::ptr::null_mut(),
                owned_len: 0,
                free_runs: Vec::new(),
                reserved: u32::MAX,
                in_use: 0,
                #[cfg(debug_assertions)]
                in_gc: core::cell::Cell::new(false),
            }
        }
        #[cfg(not(target_arch = "wasm32"))]
        {
            let len = align_up(reserve, PAGE) as usize;
            let layout = core::alloc::Layout::from_size_align(len, PAGE as usize).unwrap();
            let p = unsafe { alloc::alloc::alloc_zeroed(layout) };
            assert!(!p.is_null(), "flint: could not reserve {len} bytes");
            Space {
                base: p,
                owned_len: len,
                free_runs: Vec::new(),
                reserved: len as u32,
                in_use: PAGE, // address 0 is never a valid object
                #[cfg(debug_assertions)]
                in_gc: core::cell::Cell::new(false),
            }
        }
    }

    #[inline(always)]
    pub fn base(&self) -> *mut u8 {
        self.base
    }

    /// Address of a fresh run of `len` bytes, page aligned. 0 on exhaustion.
    pub fn take(&mut self, len: u32) -> u32 {
        let len = align_up(len, PAGE);
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
            let a = unsafe { arena::sbrk(len, PAGE) };
            if a != 0 {
                self.in_use += len;
            }
            a
        }
        #[cfg(not(target_arch = "wasm32"))]
        {
            if self.in_use.checked_add(len).map_or(true, |e| e > self.reserved) {
                return 0;
            }
            let a = self.in_use;
            self.in_use += len;
            a
        }
    }

    pub fn give_back(&mut self, addr: u32, len: u32) {
        self.free_runs.push(Region { addr, len: align_up(len, PAGE) });
    }

    #[inline(always)]
    pub unsafe fn ptr(&self, addr: u32) -> *mut u8 {
        self.base.wrapping_add(addr as usize)
    }
    #[inline(always)]
    pub fn read_u32(&self, addr: u32) -> u32 {
        unsafe { core::ptr::read_unaligned(self.ptr(addr) as *const u32) }
    }
    #[inline(always)]
    pub fn write_u32(&self, addr: u32, v: u32) {
        unsafe { core::ptr::write_unaligned(self.ptr(addr) as *mut u32, v) }
    }
    #[inline(always)]
    pub fn read_u64(&self, addr: u32) -> u64 {
        unsafe { core::ptr::read_unaligned(self.ptr(addr) as *const u64) }
    }
    #[inline(always)]
    pub fn write_u64(&self, addr: u32, v: u64) {
        unsafe { core::ptr::write_unaligned(self.ptr(addr) as *mut u64, v) }
    }
    #[inline(always)]
    pub fn read_u8(&self, addr: u32) -> u8 {
        unsafe { *self.ptr(addr) }
    }
    #[inline(always)]
    pub fn write_u8(&self, addr: u32, v: u8) {
        unsafe { *self.ptr(addr) = v }
    }
    #[inline(always)]
    pub fn bytes(&self, addr: u32, len: u32) -> &[u8] {
        unsafe { core::slice::from_raw_parts(self.ptr(addr), len as usize) }
    }
    #[inline(always)]
    pub fn bytes_mut(&self, addr: u32, len: u32) -> &mut [u8] {
        unsafe { core::slice::from_raw_parts_mut(self.ptr(addr), len as usize) }
    }
    pub fn copy_within(&self, from: u32, to: u32, len: u32) {
        unsafe { core::ptr::copy(self.ptr(from), self.ptr(to), len as usize) }
    }
    pub fn zero(&self, addr: u32, len: u32) {
        unsafe { core::ptr::write_bytes(self.ptr(addr), 0, len as usize) }
    }
}

#[cfg(not(target_arch = "wasm32"))]
impl Drop for Space {
    fn drop(&mut self) {
        if !self.base.is_null() {
            let layout =
                core::alloc::Layout::from_size_align(self.owned_len, PAGE as usize).unwrap();
            unsafe { alloc::alloc::dealloc(self.base, layout) }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn space_hands_out_disjoint_runs() {
        let mut s = Space::new(4 * 1024 * 1024);
        let a = s.take(PAGE);
        let b = s.take(2 * PAGE);
        assert_ne!(a, 0);
        assert_ne!(b, 0);
        assert!(b >= a + PAGE || a >= b + 2 * PAGE);
        s.write_u64(a, 0xdead_beef_cafe_babe);
        s.write_u64(b, 1);
        assert_eq!(s.read_u64(a), 0xdead_beef_cafe_babe);
        assert_eq!(s.read_u64(b), 1);
    }

    #[test]
    fn space_reuses_returned_runs() {
        let mut s = Space::new(4 * 1024 * 1024);
        let a = s.take(2 * PAGE);
        s.give_back(a, 2 * PAGE);
        let b = s.take(2 * PAGE);
        assert_eq!(a, b, "a freed run should be reused, not leaked");
    }

    #[test]
    fn space_reports_exhaustion_rather_than_crashing() {
        let mut s = Space::new(2 * PAGE);
        assert_ne!(s.take(PAGE), 0);
        assert_eq!(s.take(64 * PAGE), 0);
    }

    #[test]
    fn byte_windows_are_addressable() {
        let s = Space::new(1024 * 1024);
        let mut s = s;
        let a = s.take(PAGE);
        s.bytes_mut(a, 5).copy_from_slice(b"hello");
        assert_eq!(s.bytes(a, 5), b"hello");
        s.zero(a, 5);
        assert_eq!(s.bytes(a, 5), b"\0\0\0\0\0");
    }
}
