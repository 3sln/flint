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
        // Into the existing buffer: a capture that allocated would grow linear
        // memory and shift every allocation after it, which perturbs exactly
        // the timing a collector bug depends on.
        flint_rt::snap::capture_into(rt, buf());
        buf().len() as u32
    }

    #[no_mangle]
    pub extern "C" fn flint_snapshot_ptr() -> u32 {
        buf().as_ptr() as u32
    }

    /// Room for the host to write a snapshot it holds, before restoring it.
    ///
    /// `rt()` first, and not for the runtime: it is what creates the ARENA the
    /// Rust allocator draws from. Importing into a FRESH instance is the whole
    /// point of the live-set format -- the host instantiates a module, writes a
    /// snapshot into it and imports, without ever running the program -- and
    /// without this that first `resize` is an allocation before there is
    /// anything to allocate from. It failed as `unreachable` out of
    /// `__rust_alloc_error_handler`, several frames from anything recognisable.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_alloc(len: u32) -> u32 {
        let _ = rt();
        let b = buf();
        b.clear();
        b.resize(len as usize, 0);
        b.as_ptr() as u32
    }

    /// Restore whatever is in the buffer. 0 on refusal -- a snapshot from another
    /// layout version, or one belonging to a different PROGRAM, is rejected
    /// rather than read as a plausible heap that means something else.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_restore(len: u32) -> u32 {
        let rt = rt();
        let bytes = buf()[..len as usize].to_vec();
        flint_rt::snap::restore(rt, &bytes) as u32
    }

    /// Why the last restore was refused: 0 nothing, 1 layout version, 2 a
    /// different image. A bool cannot say which, and "no" is not a diagnosis.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_refused() -> u32 {
        unsafe { flint_rt::snap::REFUSED }
    }

    /// This module's image fingerprint, so a host can tell whether a snapshot it
    /// holds belongs here BEFORE trying to restore it.
    #[no_mangle]
    pub extern "C" fn flint_image_fingerprint() -> u64 {
        rt().image.fingerprint
    }

    /// Export the LIVE SET: a relocatable snapshot with no dead objects in it.
    /// Returns the byte length; the bytes are at `flint_snapshot_ptr`. 0 means
    /// the walk and the collector disagreed, which is a bug rather than a
    /// condition -- see `flint_rt::snap::export_live`.
    ///
    /// This is the one to SHELVE with. `flint_snapshot_capture` copies the heap
    /// verbatim, which is the right instrument for a post-mortem and the wrong
    /// one for moving a sandbox: it carries dead objects and unused reserve, and
    /// it can only be restored to identical addresses.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_export() -> u32 {
        let rt = rt();
        if flint_rt::snap::export_live(rt, buf()) {
            buf().len() as u32
        } else {
            0
        }
    }

    /// Import a live-set export. 0 on refusal; `flint_snapshot_refused` says
    /// which check failed.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_import(len: u32) -> u32 {
        let rt = rt();
        let bytes = buf()[..len as usize].to_vec();
        flint_rt::snap::import_live(rt, &bytes) as u32
    }

    /// Is this sandbox shelved? 1 when `export_and_stop` has run and nothing
    /// has restarted it: no frames, and the status says so.
    ///
    /// Exported because otherwise the test for `export_and_stop` has nothing to
    /// look at, and a check that cannot fail is not a check.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_stopped() -> u32 {
        let rt = rt();
        (rt.frames.is_empty() && rt.status == flint_rt::snap::STATUS_SHELVED) as u32
    }

    /// Export, and then STOP: the sandbox is left with nothing runnable, so the
    /// state in the caller's hands is the only copy that can advance.
    ///
    /// Two calls would be a race in any runtime where something else can run in
    /// between. Nothing else can here -- a builtin runs between two bytecode
    /// instructions and the scheduler is cooperative -- but the pair exists as
    /// one call anyway, because "capture, then stop" being atomic is the whole
    /// property a caller is relying on and it should not depend on knowing that.
    #[no_mangle]
    pub extern "C" fn flint_snapshot_export_and_stop() -> u32 {
        let rt = rt();
        if !flint_rt::snap::export_live(rt, buf()) {
            return 0;
        }
        let n = buf().len() as u32;
        flint_rt::snap::halt(rt);
        n
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
    flint_rt::snap::capture_into(rt, buf());
    Value::fixnum(buf().len() as i64)
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
