//! flint runtime.
//!
//! `#![no_std]` everywhere. On wasm the only things under us are `core`,
//! `alloc` (on our own allocator, see `mem`) and `libm`. On a 64-bit host the
//! same code links `std` so the collector and the data structures can be unit
//! tested and benchmarked natively.

#![no_std]
#![allow(clippy::missing_safety_doc)]

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
pub mod mem;
pub mod image;
pub mod map;
pub mod num;
pub mod obj;
pub mod rt;
pub mod seqs;
pub mod set;
// Snapshots are diagnostic machinery (doc/decisions/0016): absent from a
// production build, not merely disabled.
#[cfg(feature = "diagnostics")]
pub mod snap;
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
