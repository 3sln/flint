//! A heap's CEILING is a limit, not an allocation (`DECISIONS.md#growable-heap`).
//!
//! In a file of its own, as `reservation.rs` is, because what it reads --
//! the space's commitment -- is per `Rt`, but the peak it guards is
//! process-wide.

use flint_rt::obj::TY_NODE;
use flint_rt::rt::Rt;
use flint_rt::value::{Value, OOM};

const M: u64 = 1 << 20;

/// Fill a heap with rooted 1 MiB nodes until allocation fails, and answer how
/// many fitted, what the space committed along the way, and what was thrown.
fn fill(ceiling: u32) -> (usize, u64, u64, Value) {
    let mut rt = Rt::with_heap(2 * 1024 * 1024, ceiling);
    let born = rt.gc.sp.committed;
    let base = rt.mark();
    let mut n = 0;
    loop {
        // 131 071 slots plus the header is just under 1 MiB, which is above
        // the large-object threshold: each one is an old-space allocation,
        // the path that calls `take`.
        let a = rt.alloc(TY_NODE, 131_071);
        if a == 0 {
            break;
        }
        rt.push(Value::heap(a));
        n += 1;
    }
    let grown = rt.gc.sp.committed;
    let thrown = rt.thrown;
    rt.pop_to(base);
    (n, born, grown, thrown)
}

/// THE CLI'S CEILING, 3 GB, starts at 8 MiB.
#[test]
fn the_cli_ceiling_backs_eight_mib_at_birth() {
    let rt = Rt::with_heap(2 * 1024 * 1024, 3_000_000_000);
    assert_eq!(rt.gc.sp.reserved, 3_000_041_472, "the ceiling, page-rounded");
    assert_eq!(rt.gc.sp.committed, 8 * M);
}

/// Allocating well past the initial commitment works, and the ceiling still
/// ends it with the same catchable error at the same point.
///
/// `56` is not derived from this code: it is what 4192d734 -- before the heap
/// could grow, when the whole ceiling was backed at birth -- answered for the
/// same fill, run by copying this function into that tree (2026-10-05). Same
/// count means the limit fires where it did; `OOM` means it is still the
/// catchable one. A space that handed out a run it had not committed would
/// not fail this assertion but fault on the first write to the node.
#[test]
fn a_fill_past_the_initial_commitment_stops_where_it_always_did() {
    let (n, born, grown, thrown) = fill(64 * 1024 * 1024);
    assert_eq!(born, 8 * M);
    assert_eq!(n, 56, "how many 1 MiB nodes fit under a 64 MiB ceiling");
    assert!(grown > 8 * M && grown <= 64 * M, "committed {grown}");
    assert!(thrown == OOM, "a full heap throws the memory-limit error, got {:?}", thrown.0);
}
