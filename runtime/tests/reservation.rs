//! A heap RESERVATION is address space, not memory.
//!
//! The CLI creates every sandbox with a 3 GB ceiling, and `Space::new` used to
//! hand that to `alloc_zeroed` at PAGE (64 KB) alignment. An alignment above
//! the allocator's own makes Rust's system allocator take an aligned block and
//! zero it by hand, so every sandbox wrote three gigabytes of zeros before it
//! ran an instruction: measured 2026-10-01, a `flint run` of a program
//! answering "hi" peaked at 5.0 GB resident and spent ~0.5 s of CPU per
//! sandbox in `__bzero`. After the fix the same run peaks at 28 MB.
//!
//! In a file of its own because the peak is PROCESS-wide: any other test in
//! the same binary could raise it and make this one fail for a reason that is
//! not its own, or -- worse -- mask nothing because the peak was already high.

use flint_rt::rt::Rt;

/// The peak resident set so far, in bytes. `ru_maxrss` is bytes on macOS and
/// kilobytes on Linux.
fn peak_rss() -> u64 {
    let mut u: libc::rusage = unsafe { core::mem::zeroed() };
    unsafe { libc::getrusage(libc::RUSAGE_SELF, &mut u) };
    let raw = u.ru_maxrss as u64;
    if cfg!(target_os = "macos") { raw } else { raw * 1024 }
}

#[test]
fn a_reservation_is_not_resident() {
    const RESERVE: u32 = 3_000_000_000;
    // Two, as `flint run` makes: the compiler and the program.
    let a = Rt::with_heap(2 * 1024 * 1024, RESERVE);
    let b = Rt::with_heap(2 * 1024 * 1024, RESERVE);
    let peak = peak_rss();
    drop((a, b));
    // Far below one reservation, let alone two: zeroing either would put the
    // peak past 3 GB on its own.
    assert!(peak < 512 * 1024 * 1024,
            "peak resident {} MB after reserving 2 x {} MB: the reservation was touched",
            peak >> 20, RESERVE >> 20);
}
