//! flint runtime.
//!
//! `#![no_std]` everywhere. On wasm the only things under us are `core`,
//! `alloc` (on our own allocator, see `mem`) and `libm`. On a 64-bit host the
//! same code links `std` so the collector and the data structures can be unit
//! tested and benchmarked natively.

#![no_std]
#![allow(clippy::missing_safety_doc)]

// `unused_comparisons` IS AN ERROR IN THIS CRATE, and it is here rather than
// only on `kgen` because the trap is not confined to generated code. kin's
// `I32` is `u32` in Rust and `int` in both ports, so a guard written `x < 0`
// reads as a clamp in two runtimes and is DEAD CODE in the third -- either
// hiding an underflow that already happened, or, when a sentinel converges
// from `-1` to a positive value, silently turning a live test into `false`.
//
// It was denied on `kgen` first, for two generated bugs. Converging
// `schema_id` then produced SEVEN more, all in hand-written `table.rs`, none
// of them errors -- rustc had been reporting each one as one warning among
// forty since the moment it appeared. Warnings nobody reads are not a gate.
#![deny(unused_comparisons)]

// LITTLE-ENDIAN, PINNED. The heap is read and written in the machine's own
// byte order (`read_unaligned` on `u32`/`u64`), and a live-set export copies a
// raw object's body -- a string's byte and code-point counts, a bigint's limbs
// -- verbatim. Both are portable exactly when every runtime is little-endian,
// which wasm is by specification and every host flint runs on is in practice.
// Refusing a big-endian target here makes that a property of the build rather
// than an assumption nobody wrote down; the JVM and CLR heaps check it when
// they are made.
#[cfg(target_endian = "big")]
compile_error!("flint's heap and its live-set format are little-endian; this target is not");

extern crate alloc;

#[cfg(not(target_arch = "wasm32"))]
extern crate std;

pub mod abi;
pub mod builtins;
pub mod coll;
pub mod conc;
pub mod eq;
pub mod err;
pub mod fmath;
pub mod gc;
pub mod hash;
// The generated subtree. See `kgen.rs` -- these `mod` lines are the one
// thing kin reports and does not write.
pub mod kgen;
pub mod mem;
pub mod image;
pub mod map;
pub mod num;
pub mod obj;
/// Emitted-wasm support (`DECISIONS.md#emit-wasm-instead-of-dispatch`). Optional by the same rule as
/// diagnostics (`DECISIONS.md#two-builds`): a module with nothing compiled should
/// not carry the machinery that would run it.
#[cfg(feature = "aot")]
pub mod aot;
#[cfg(feature = "parallel")]
pub mod par;
pub mod pike;
pub mod rope;
pub mod bytes;
pub mod codec;
#[cfg(not(target_arch = "wasm32"))]
pub mod native;
pub mod rt;
pub mod seqs;
pub mod set;
// Snapshots are diagnostic machinery (DECISIONS.md#two-builds): absent from a
// production build, not merely disabled.
// The GRAPH-WALKING live set (`export_live`/`import_live`) is what shelving a
// sandbox needs, so it ships in every build. Only the verbatim heap capture
// inside this module is a troubleshooting tool, and that half is gated there.
pub mod snap;
/// The region histogram `DECISIONS.md#emit-wasm-instead-of-dispatch` is gated on. Diagnostic by the same
/// rule: it exists to decide whether to build the AOT compiler, not to run.
#[cfg(feature = "diagnostics")]
pub mod aotstat;
pub mod table;
pub mod strs;
pub mod vector;
pub mod vm;
pub mod value;

#[cfg(all(target_arch = "wasm32", not(test)))]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

// rustc synthesises these in the allocator shim when IT drives the final link.
// flint drives the link itself (doc/unit-format.md), so we provide them.
#[cfg(target_arch = "wasm32")]
mod alloc_shim {
    #[no_mangle]
    pub static __rust_no_alloc_shim_is_unstable: u8 = 0;
    #[no_mangle]
    pub static __rust_alloc_error_handler_should_panic: u8 = 0;
    #[no_mangle]
    pub extern "C" fn __rust_alloc_error_handler(_size: usize, _align: usize) -> ! {
        core::arch::wasm32::unreachable()
    }
}
