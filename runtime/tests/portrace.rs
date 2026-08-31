//! One port, two executors (`doc/decisions/0028`).
//!
//! A port's inbox used to be a persistent vector and a read cursor, and
//! `conj`-then-store is a read-modify-write with an ALLOCATION in the middle --
//! which takes the allocation lock and can stage a safepoint, so the window was
//! as wide as a collection. The first version of the test below sent 4 000
//! messages from two executors and received 2 000: exactly half, which is the
//! signature of two threads writing the same slot.
//!
//! It is a ring now, reserved by compare-and-swap and published by a sequence
//! word, with no lock anywhere on the path.
//!
//! KNOWN GAP, 2026-08-30: a program compiled through the SELF-HOSTED compiler
//! with tree-shaking on traps when it opens a BRIDGE. `shake: false` on the
//! same source, through the same compiler, is correct; so is every path that
//! composes the runtime from objects rather than shaking a prebuilt module
//! (`bin/flint`, with and without `--loader`, and the native runtime). It is
//! not the ring size -- 16 traps exactly as 1024 does -- and channels are
//! unaffected, which is what makes `open` the thing to look at rather than the
//! ring itself. The shaker is `src/flint/wasmshake.cljc`; the new call edges
//! `new_port` gained are the place to start.

#![cfg(feature = "parallel")]

use flint_rt::rt::Rt;
use flint_rt::value::Value;

/// Both executors send into ONE end, then everything is drained and counted.
///
/// The VALUES are checked, not just how many there are: a ring that dropped a
/// reservation and a ring that published one slot twice both lose messages, and
/// only looking at what arrived tells them apart. Each executor sends a
/// disjoint range, so the drained set has to be exactly the union.
#[test]
fn two_executors_sending_into_one_end_lose_nothing() {
    let n = 2000i64;
    let mut primary = Rt::with_heap(64 * 1024, 256 * 1024 * 1024);
    let label = primary.string("race");
    let pair = primary.make_channel(4 * n + 16, label);
    let a = primary.vec_nth(pair, 0).unwrap();
    let b = primary.vec_nth(pair, 1).unwrap();
    let (ai, bi) = (primary.push(a), primary.push(b));
    let mut secondary = unsafe { primary.executor() }.expect("a second executor");

    std::thread::scope(|scope| {
        let second = &mut secondary;
        let av = primary.r(ai);
        scope.spawn(move || {
            for i in 0..n {
                second.port_send(av, Value::fixnum(1_000_000 + i));
            }
        });
        for i in 0..n {
            let av = primary.r(ai);
            primary.port_send(av, Value::fixnum(i));
        }
    });

    let mut seen: Vec<i64> = Vec::new();
    loop {
        let bv = primary.r(bi);
        let v = primary.port_receive(bv);
        if v.is_nil() || primary.failed() {
            break;
        }
        seen.push(v.as_fixnum());
        if seen.len() as i64 > 4 * n {
            break;
        }
    }
    seen.sort_unstable();
    let mut want: Vec<i64> = (0..n).collect();
    want.extend(1_000_000..1_000_000 + n);
    assert_eq!(seen.len() as i64, 2 * n, "{} of {} messages arrived", seen.len(), 2 * n);
    assert_eq!(seen, want, "the messages that arrived are not the ones that were sent");
    println!("    {} messages from two executors, all {} received", 2 * n, seen.len());
    drop(secondary);
}

/// The bound still bounds, and it is claimed atomically.
///
/// Back-pressure is the reason a port has a capacity at all, and a lock-free
/// reservation is exactly where it could be lost: two senders reading the same
/// cursor would both believe there is one slot left. `port_enqueue` reports the
/// ring full rather than growing it, and the count never exceeds what was
/// asked for -- checked from two executors at once, on a ring small enough that
/// they are fighting over the last slot constantly.
#[test]
fn a_full_ring_refuses_rather_than_growing() {
    let cap = 8i64;
    let mut primary = Rt::with_heap(64 * 1024, 64 * 1024 * 1024);
    let label = primary.string("tiny");
    let pair = primary.make_channel(cap, label);
    let a = primary.vec_nth(pair, 0).unwrap();
    let b = primary.vec_nth(pair, 1).unwrap();
    let ai = primary.push(a);
    // Enqueued into THIS port's own ring, which is what `port_try_enqueue`
    // does -- `send` reaches across to the peer's, and mixing the two is what
    // the first version of this test did.
    let _ = b;
    let mut secondary = unsafe { primary.executor() }.expect("a second executor");

    let refused = std::sync::atomic::AtomicU64::new(0);
    let accepted = std::sync::atomic::AtomicU64::new(0);
    std::thread::scope(|scope| {
        let second = &mut secondary;
        let av = primary.r(ai);
        let (rf, ac) = (&refused, &accepted);
        scope.spawn(move || {
            for i in 0..5000 {
                if second.port_try_enqueue(av, Value::fixnum(i)) {
                    ac.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                } else {
                    rf.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                }
            }
        });
        for i in 0..5000 {
            let av = primary.r(ai);
            if primary.port_try_enqueue(av, Value::fixnum(i)) {
                accepted.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            } else {
                refused.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            }
        }
    });

    let acc = accepted.load(std::sync::atomic::Ordering::Relaxed);
    let av = primary.r(ai);
    let held = primary.inbox_depth(av) as u64;
    assert!(
        refused.load(std::sync::atomic::Ordering::Relaxed) > 0,
        "nothing was refused, so the bound was never reached and this proves nothing"
    );
    assert_eq!(acc, held, "{acc} sends were accepted but {held} are in the ring");
    assert!(held <= cap as u64, "{held} messages in a ring of {cap}");
    println!("    ring of {cap}: {acc} accepted, {} refused, {held} held",
             refused.load(std::sync::atomic::Ordering::Relaxed));
    drop(secondary);
}
