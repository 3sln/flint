//! The C ABI, emitted.
//!
//! Every symbol is in `flint::capi`; this crate exists only to build it as a
//! `cdylib` and a `staticlib`, which cargo cannot do conditionally on a
//! feature. The header is `include/flint.h`, and `include/flint.hpp` is the C++
//! wrapper over the same symbols.
//!
//! `pub use` rather than a re-export list: `#[no_mangle]` symbols are emitted
//! by the dependency, and this line is what keeps the linker from dropping the
//! crate as unused.
pub use flint::capi::*;
