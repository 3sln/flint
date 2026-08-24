//! The module's outside edge.
//!
//! ```text
//!   arg_alloc(len) -> ptr      host writes UTF-8 argument bytes there
//!   arg_push(ptr, len)         appends one argument
//!   main() -> i32              0 = ok, 1 = threw
//!   out_ptr() / out_len()      the UTF-8 result
//! ```
//!
//! The Rust function is called `flint_main` and `flint` renames the export to
//! `main` after linking. `wasm-ld` special-cases a symbol literally named
//! `main` and synthesises a `__original_main` wrapper around it, which is 1.7 KB
//! of pointless indirection; renaming afterwards keeps the exported contract the
//! brief asks for without paying for it.
//!
//! `main` takes the pushed arguments, wraps them in a vector of strings, and
//! calls the entry function with that one vector, exactly as the brief
//! specifies. `host/flint.mjs` wraps this into `main("a", "b")` for JavaScript.
//!
//! The result must be a string: the compiler always emits a shim of the shape
//! `(fn [args] (let [r (the-ns/the-fn args)] (if (string? r) r (pr-str r))))`,
//! so rendering is cljc and tree-shakes with everything else. If a module is
//! built without that shim and returns a non-string, `main` says so rather than
//! pulling a printer into the floor.

#![cfg(target_arch = "wasm32")]

use alloc::vec::Vec;

use crate::rt::Rt;
use crate::value::{Value, NIL};

/// Patched by `flint` after linking: [image pointer, image length]. Exported as
/// a wasm global holding its own address, which is how the patcher finds it
/// without a symbol table.
#[no_mangle]
pub static mut FLINT_IMAGE_DESC: [u32; 2] = [0, 0];

static mut RT: Option<Rt> = None;
static mut ARGS: Vec<(u32, u32)> = Vec::new();
/// One owned buffer per argument. A single growing buffer would be simpler and
/// wrong: reallocating it moves bytes the host has already written through a
/// pointer we handed back. Each `Vec`'s own allocation is stable even as the
/// outer `Vec` grows.
static mut BUFS: Vec<Vec<u8>> = Vec::new();
static mut OUT: Vec<u8> = Vec::new();

fn heap_start() -> u32 {
    extern "C" {
        static __heap_base: u8;
    }
    let base = core::ptr::addr_of!(__heap_base) as u32;
    let (p, l) = unsafe {
        let d = core::ptr::addr_of!(FLINT_IMAGE_DESC);
        ((*d)[0], (*d)[1])
    };
    if p == 0 {
        base
    } else {
        core::cmp::max(base, (p + l + 15) & !15)
    }
}

fn image_bytes() -> &'static [u8] {
    let (p, l) = unsafe {
        let d = core::ptr::addr_of!(FLINT_IMAGE_DESC);
        ((*d)[0], (*d)[1])
    };
    if p == 0 {
        &[]
    } else {
        unsafe { core::slice::from_raw_parts(p as *const u8, l as usize) }
    }
}

/// Bring the allocator up before anything allocates. `arg_alloc` runs before
/// `main`, so this cannot wait for `ensure_rt`: an uninitialised arena hands out
/// memory from address 0 and overwrites the data segments, including the
/// program image itself.
unsafe fn ensure_arena() {
    let a = &*core::ptr::addr_of!(crate::mem::arena::ARENA);
    if a.brk == 0 {
        crate::mem::arena::init(heap_start());
    }
}

unsafe fn ensure_rt() -> &'static mut Rt {
    ensure_arena();
    let slot = &mut *core::ptr::addr_of_mut!(RT);
    if slot.is_none() {
        let mut rt = Rt::new();
        let img = image_bytes();
        if !img.is_empty() {
            rt.load_image(img);
        }
        *slot = Some(rt);
    }
    slot.as_mut().unwrap()
}

#[no_mangle]
pub extern "C" fn arg_alloc(len: u32) -> u32 {
    unsafe {
        ensure_arena();
        let bufs = &mut *core::ptr::addr_of_mut!(BUFS);
        let v = alloc::vec![0u8; len as usize];
        let p = v.as_ptr() as u32;
        bufs.push(v);
        p
    }
}

#[no_mangle]
pub extern "C" fn arg_push(ptr: u32, len: u32) {
    unsafe {
        ensure_arena();
        let a = &mut *core::ptr::addr_of_mut!(ARGS);
        a.push((ptr, len));
    }
}

#[no_mangle]
pub extern "C" fn flint_main() -> i32 {
    unsafe {
        let rt = ensure_rt();
        let base = rt.mark();
        let args = &*core::ptr::addr_of!(ARGS);
        for (p, l) in args.iter() {
            let bytes = core::slice::from_raw_parts(*p as *const u8, *l as usize);
            let s: alloc::string::String = core::str::from_utf8(bytes).unwrap_or("").into();
            let v = rt.string(&s);
            rt.push(v);
        }
        let n = args.len();
        let argv = rt.vec_from_roots(base, n);
        rt.pop_to(base);

        // Consume the arguments: `main` may be called more than once on the
        // same instance, and leaving them here made the second call see the
        // first call's arguments followed by its own.
        {
            let a = &mut *core::ptr::addr_of_mut!(ARGS);
            a.clear();
            let b = &mut *core::ptr::addr_of_mut!(BUFS);
            b.clear();
        }

        let result = rt.run_program(argv);
        finish_run(rt, result)
    }
}

/// Render the outcome of a run into `OUT` and give `main`'s status code.
///
/// `2` means **"I need the host"**: some green thread is parked on a port whose
/// other end the host holds. Nothing is suspended -- the interpreter simply has
/// nothing runnable -- so the host services the pending events and calls
/// `flint_resume`. A program with no ports never reaches that branch, and the
/// concurrency unit that produces it is not in the module at all.
pub fn finish_run(rt: &mut Rt, result: Value) -> i32 {
    unsafe {
        let out = &mut *core::ptr::addr_of_mut!(OUT);
        out.clear();
        if rt.status != 0 {
            return rt.status;
        }
        if rt.failed() {
            let e = rt.clear_error();
            let mut b = crate::rt::sbuf();
            let kind = rt.ex_kind(e);
            let k: alloc::string::String = rt.as_str(kind, &mut b).unwrap_or("Error").into();
            let msg = rt.ex_message(e);
            let mut b2 = crate::rt::sbuf();
            let m: alloc::string::String = rt.as_str(msg, &mut b2).unwrap_or("").into();
            out.extend_from_slice(k.as_bytes());
            out.extend_from_slice(b": ");
            out.extend_from_slice(m.as_bytes());
            return 1;
        }
        let mut b = crate::rt::sbuf();
        match rt.as_str(result, &mut b) {
            Some(s) => {
                out.extend_from_slice(s.as_bytes());
                0
            }
            None => {
                out.extend_from_slice(
                    b"flint: the entry function did not return a string (no render shim?)",
                );
                1
            }
        }
    }
}

#[no_mangle]
pub extern "C" fn out_ptr() -> u32 {
    unsafe { (*core::ptr::addr_of!(OUT)).as_ptr() as u32 }
}

#[no_mangle]
pub extern "C" fn out_len() -> u32 {
    unsafe { (*core::ptr::addr_of!(OUT)).len() as u32 }
}

/// The runtime instance, for a unit that adds host-facing exports of its own --
/// the concurrency unit's resume entry is the only one so far. Unreferenced,
/// and therefore absent, unless such a unit is linked.
#[no_mangle]
pub extern "C" fn flint_rt_ptr() -> u32 {
    unsafe { ensure_rt() as *mut Rt as u32 }
}

/// Set an instruction budget. 0 disables it. Used by the test harness to turn a
/// hang into a frame trace.
#[no_mangle]
pub extern "C" fn set_step_limit(hi: u32, lo: u32) {
    unsafe {
        let rt = ensure_rt();
        rt.set_gas_limit(((hi as u64) << 32) | lo as u64);
        rt.steps = 0;
        rt.refresh_checkpoint();
    }
}

/// Instructions dispatched so far. Only counted while a step limit is set, so
/// the counter costs nothing in a normal run -- which is also what makes the
/// dispatch measurement honest: time it with counting off, count it with
/// counting on, divide.
#[no_mangle]
pub extern "C" fn stat_steps() -> u64 {
    unsafe { ensure_rt().steps }
}

/// Cap the heap, in bytes. Exceeding it raises a catchable error **after** a
/// collection, so the cap does not depend on when the collector last ran.
#[no_mangle]
pub extern "C" fn set_memory_limit(bytes: u32) {
    unsafe { ensure_rt().gc.set_heap_limit(bytes) }
}

/// Bytes of heap currently reserved, against the cap.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_heap_used() -> u32 {
    unsafe { ensure_rt().gc.heap_used() }
}

/// Diagnostics for the benchmarks: bytes the collector has handed out.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_bytes_allocated() -> u64 {
    unsafe { ensure_rt().gc.stats.bytes_allocated }
}
/// High-water mark of *live* bytes, sampled at each collection. The number a
/// memory claim is made against: "peak memory is proportional to the content a
/// script actually kept", not to how much it allocated on the way.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_peak_live() -> u64 {
    unsafe { ensure_rt().gc.stats.peak_live }
}

/// Force a collection, so a measurement can be taken at a defined point rather
/// than wherever the allocator happened to trip.
/// Collect at every single allocation. This is how the parked-thread cases get
/// tested to the standard the rest of the collector is held to: a thread that
/// parks holds live references in a saved stack, and only stress mode makes
/// every one of those saves and restores race a collection.
/// Collect only for allocations in `[from, until)`. Bisect it to find the single
/// allocation whose collection causes a timing-dependent fault. The predicate is
/// NOT monotone, so any window a bisection lands on must be re-run to confirm.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn set_gc_stress_window(from_lo: u32, from_hi: u32, until_lo: u32, until_hi: u32) {
    unsafe {
        let rt = ensure_rt();
        rt.gc.stress_from = ((from_hi as u64) << 32) | from_lo as u64;
        rt.gc.stress_until = ((until_hi as u64) << 32) | until_lo as u64;
    }
}

/// Run collections `[from, until)` as majors rather than minors, changing no
/// allocation timing. Bisect it to find the collection at which something is
/// lost.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn set_gc_upgrade_window(from_lo: u32, from_hi: u32, until_lo: u32, until_hi: u32) {
    unsafe {
        let rt = ensure_rt();
        rt.gc.upgrade_from = ((from_hi as u64) << 32) | from_lo as u64;
        rt.gc.upgrade_until = ((until_hi as u64) << 32) | until_lo as u64;
    }
}

/// Check the generational invariant at the start of every collection: every old
/// object pointing at a young one must be in the remembered set.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn set_gc_verify_remset(on: u32) {
    unsafe { ensure_rt().gc.verify_remset = on != 0 }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_remset_violations() -> u32 {
    unsafe { ensure_rt().gc.remset_violations }
}

/// Coverage of the invariant walk: objects visited, spans it could not finish,
/// and whether it reached the address it was told to watch.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_remset_cover(i: u32) -> u32 {
    unsafe {
        let g = &ensure_rt().gc;
        match i { 0 => g.remset_walked, 1 => g.remset_walk_errors, _ => g.remset_watch_seen }
    }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_chain2(i: u32) -> u32 {
    unsafe { if (i as usize) < 16 { crate::gc::CHAIN[i as usize] } else { 0 } }
}

/// Pointers into the DEAD half found by the invariant walk: `(object, its type,
/// slot, target, the collection it was seen at)`.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_dead_half(i: u32, f: u32) -> u32 {
    unsafe {
        let g = &ensure_rt().gc;
        if i == 99 { return g.dead_half_refs; }
        if (i as usize) < 8 && (f as usize) < 7 { g.dead_half_bad[i as usize][f as usize] } else { 0 }
    }
}

/// Pointers into limbo seen by `forward`: `(target, collection, to, to_bump)`.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_limbo(i: u32, f: u32) -> u32 {
    unsafe {
        let g = &ensure_rt().gc;
        if i == 99 { return g.limbo_refs; }
        if (i as usize) < 8 && (f as usize) < 4 { g.limbo_bad[i as usize][f as usize] } else { 0 }
    }
}

/// Capture `bump` at the end of collection `c`, before anything allocated after
/// it. `stat_end_bump(0)` is the bump, `(1)` the from base.
/// Stale values seen in a restored stack: `(slot, address, type, collection)`.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_restore_stale(i: u32, f: u32) -> u32 {
    unsafe {
        let g = &ensure_rt().gc;
        if i == 99 { return g.restore_stale; }
        if i == 98 { return g.restores_checked; }
        if i == 97 { return g.restore_values; }
        if (i as usize) < 8 && (f as usize) < 4 { g.restore_bad[i as usize][f as usize] } else { 0 }
    }
}

/// Record allocation origins only for collections in `[from, until)`.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn set_gc_origin_window(from: u32, until: u32) {
    unsafe {
        let rt = ensure_rt();
        rt.gc.orig_from = from as u64;
        rt.gc.orig_until = until as u64;
    }
}

/// Who allocated the object at `addr`: the native import index + 1, or 0 for the
/// interpreter itself. Searches the origin ring newest-first.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_origin(addr: u32) -> u32 {
    unsafe {
        let n = crate::gc::ORIG_N;
        let cap = crate::gc::ORIG_CAP;
        let seen = if n < cap { n } else { cap };
        for k in 1..=seen {
            let i = (n - k) & (cap - 1);
            if crate::gc::ORIG_ADDR[i] == addr {
                return crate::gc::ORIG_WHO[i] + 1; // +1 so 0 means "not found"
            }
        }
        0
    }
}

/// `[allocations checked inside port-send, message unrooted at one, the type
/// being allocated there, its allocation serial]`
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_watch_hit(i: u32) -> u32 {
    unsafe { if (i as usize) < 4 { crate::gc::WATCH_HIT[i as usize] } else { 0 } }
}

/// `[parks checked, message NOT in any traced root, first such address, its type]`
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_park_rooted(i: u32) -> u32 {
    unsafe { if (i as usize) < 4 { crate::gc::PARK_ROOTED[i as usize] } else { 0 } }
}

/// The allocation serial of the object at `addr`, so several carriers of one
/// stale pointer can be ordered and the EARLIEST -- the introducer -- picked out.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_origin_seq(addr: u32) -> u32 {
    unsafe {
        let n = crate::gc::ORIG_N;
        let cap = crate::gc::ORIG_CAP;
        let seen = if n < cap { n } else { cap };
        for k in 1..=seen {
            let i = (n - k) & (cap - 1);
            if crate::gc::ORIG_ADDR[i] == addr {
                return crate::gc::ORIG_SEQ[i] + 1; // +1 so 0 means "not found"
            }
        }
        0
    }
}

/// The `i`-th byte of native import `idx`'s NAME, read from the image's own
/// table. This is the right table: a slot resolved through the host registry
/// answers from a different index space and returns something plausible.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_native_name(idx: u32, i: u32) -> u32 {
    unsafe {
        let rt = ensure_rt();
        let Some(&namec) = rt.image.native_names.get(idx as usize) else { return 0 };
        let Some(&v) = rt.roots.consts.get(namec as usize) else { return 0 };
        let mut b = crate::rt::sbuf();
        match rt.as_str(v, &mut b) {
            Some(s) => s.as_bytes().get(i as usize).copied().unwrap_or(0) as u32,
            None => 0,
        }
    }
}

/// The builtin table slot behind native import `idx`. The loaded image keeps
/// slots rather than names, so this is the identity a host can compare against
/// `Rt::host_native_slot` for a known builtin.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_native_slot(idx: u32) -> u32 {
    unsafe { ensure_rt().image.natives.get(idx as usize).copied().unwrap_or(u32::MAX) }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn set_gc_watch_end(c: u32) {
    unsafe { ensure_rt().gc.watch_end_cycle = c as u64 }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_end_bump(i: u32) -> u32 {
    unsafe {
        let g = &ensure_rt().gc;
        if i == 0 { g.watch_end_bump } else { g.watch_end_from }
    }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn set_gc_remset_watch(a: u32) {
    unsafe { ensure_rt().gc.remset_watch = a }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_remset_end_violations() -> u32 {
    unsafe { ensure_rt().gc.remset_end_violations }
}

/// `(object, its type, slot, young target, target type)` for the first eight.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_remset_bad(i: u32, f: u32) -> u32 {
    unsafe {
        let g = &ensure_rt().gc;
        if (i as usize) < 8 && (f as usize) < 5 { g.remset_bad[i as usize][f as usize] } else { 0 }
    }
}

/// Log the traversal of exactly this collection, and nothing else.
#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn set_gc_trace_cycle(lo: u32, hi: u32) {
    unsafe { ensure_rt().gc.trace_cycle = ((hi as u64) << 32) | lo as u64 }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_trace_n() -> u32 {
    unsafe { crate::gc::TRACE_N as u32 }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_trace_addr(i: u32) -> u32 {
    unsafe { if (i as usize) < crate::gc::TRACE_N { crate::gc::TRACE_ADDR[i as usize] } else { 0 } }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_trace_kind(i: u32) -> u32 {
    unsafe { if (i as usize) < crate::gc::TRACE_N { crate::gc::TRACE_KIND[i as usize] } else { 0 } }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_allocs() -> u64 {
    unsafe { ensure_rt().gc.alloc_seq }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn set_gc_stress(on: u32) {
    unsafe { ensure_rt().gc.stress = on != 0 }
}


#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn collect_now() {
    unsafe { ensure_rt().collect() }
}

#[cfg(feature = "diagnostics")]
#[no_mangle]
pub extern "C" fn stat_collections() -> u64 {
    unsafe {
        let rt = ensure_rt();
        rt.gc.stats.minor + rt.gc.stats.major
    }
}

/// Keep the linker from dropping the descriptor: the patcher must find it.
#[no_mangle]
pub extern "C" fn image_desc_addr() -> u32 {
    core::ptr::addr_of!(FLINT_IMAGE_DESC) as u32
}

const _: () = {
    let _ = NIL.bits();
    let _: Option<Value> = None;
};
