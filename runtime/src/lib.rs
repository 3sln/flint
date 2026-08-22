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

pub mod gc;
pub mod hash;
pub mod mem;
pub mod obj;
pub mod rt;
pub mod strs;
pub mod vector;
pub mod value;

#[cfg(all(target_arch = "wasm32", not(test)))]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
