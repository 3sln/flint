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
        rt.step_limit = ((hi as u64) << 32) | lo as u64;
        rt.steps = 0;
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

/// Diagnostics for the benchmarks: bytes the collector has handed out.
#[no_mangle]
pub extern "C" fn stat_bytes_allocated() -> u64 {
    unsafe { ensure_rt().gc.stats.bytes_allocated }
}
/// High-water mark of *live* bytes, sampled at each collection. The number a
/// memory claim is made against: "peak memory is proportional to the content a
/// script actually kept", not to how much it allocated on the way.
#[no_mangle]
pub extern "C" fn stat_peak_live() -> u64 {
    unsafe { ensure_rt().gc.stats.peak_live }
}

/// Force a collection, so a measurement can be taken at a defined point rather
/// than wherever the allocator happened to trip.
#[no_mangle]
pub extern "C" fn collect_now() {
    unsafe { ensure_rt().collect() }
}

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
