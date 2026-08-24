//! The snapshot unit (`doc/decisions/0015`).
//!
//! A unit like any other, so a program that never asks for a snapshot never
//! links one and the pure-module floor is untouched (`doc/decisions/0005`).
//! Capture and restore live in the runtime; what is here is the host-facing
//! surface and the buffer the bytes cross in.
#![no_std]

extern crate alloc;

use alloc::vec::Vec;
use flint_rt::rt::Rt;
use flint_rt::value::{Value, NIL};

static mut BUF: Vec<u8> = Vec::new();

fn buf() -> &'static mut Vec<u8> {
    unsafe { &mut *core::ptr::addr_of_mut!(BUF) }
}

/// The host-facing surface. wasm only, for the same reason the concurrency
/// unit gates its exports: on a host build there is no single ambient runtime.
#[cfg(target_arch = "wasm32")]
mod host {
    use super::*;

    fn rt() -> &'static mut Rt {
        unsafe { &mut *(flint_rt::abi::flint_rt_ptr() as *mut Rt) }
    }

    /// Capture the whole VM state. Returns the byte length; the bytes are at
    /// `flint_snapshot_ptr`.
    ///
    /// **This is a memcpy, not a traversal.** A capture that walked the object graph
    /// could omit an object by missing an edge, and then the capture is what needs
    /// debugging rather than the bug.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_capture() -> u32 {
        let rt = rt();
        let b = flint_rt::snap::capture(rt);
        let out = b.len() as u32;
        *buf() = b;
        out
    }

    #[no_mangle]
    pub extern "C" fn flint_snapshot_ptr() -> u32 {
        buf().as_ptr() as u32
    }

    /// Room for the host to write a snapshot it holds, before restoring it.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_alloc(len: u32) -> u32 {
        let b = buf();
        b.clear();
        b.resize(len as usize, 0);
        b.as_ptr() as u32
    }

    /// Restore whatever is in the buffer. 0 on refusal -- a snapshot from another
    /// layout version is rejected rather than read as a plausible heap that means
    /// something else.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_restore(len: u32) -> u32 {
        let rt = rt();
        let bytes = buf()[..len as usize].to_vec();
        flint_rt::snap::restore(rt, &bytes) as u32
    }
}

macro_rules! builtin {
    ($name:ident, $f:expr) => {
        #[no_mangle]
        pub extern "C" fn $name(rt: *mut Rt, b: u32, n: u32) -> u64 {
            let rt: &mut Rt = unsafe { &mut *rt };
            let _ = (b, n);
            let f: fn(&mut Rt) -> Value = $f;
            f(rt).bits()
        }
    };
}

builtin!(flint_b_snapshot, |rt| {
    let b = flint_rt::snap::capture(rt);
    let n = b.len();
    *buf() = b;
    Value::fixnum(n as i64)
});

builtin!(flint_b_snapshot_size, |_rt| Value::fixnum(buf().len() as i64));

builtin!(flint_b_snapshot_restore, |rt| {
    let bytes = buf().clone();
    if flint_rt::snap::restore(rt, &bytes) {
        Value::boolean(true)
    } else {
        NIL
    }
});
