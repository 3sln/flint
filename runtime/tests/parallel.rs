//! Two executors, one heap (`doc/decisions/0028`).
//!
//! The claim under test is not "it does not crash". It is that a collection
//! staged by one thread walks the OTHER thread's roots, so objects that thread
//! is holding survive and the ones it moved are found where they moved to.
//! Allocation-heavy on purpose: the interesting window is the one where the
//! collector is copying while another executor has live values on its stack.

#![cfg(feature = "parallel")]

use flint_rt::rt::Rt;
use flint_rt::value::{Value, NIL};

/// Build a list of `n` strings and read every one back.
///
/// Allocates constantly and holds a growing live set, so the nursery fills
/// repeatedly and each collection has real work to move. Everything stays on
/// the root stack across the allocations, which is the discipline that has to
/// hold across ANOTHER thread's collection now too.
fn churn(rt: &mut Rt, tag: i64, n: usize) -> usize {
    // From here this thread can be stopped for a collection; after `leave`
    // nothing waits for it. Getting this bracket wrong is not a subtle bug: a
    // first version without it hung, because the collector waited for an
    // executor that had already finished.
    rt.enter_running();
    let base = rt.mark();
    // A cons cell per iteration, each carrying a number that says which thread
    // made it and when. Every one stays ROOTED, so the live set grows, the
    // nursery fills repeatedly, and each collection has real work to move.
    //
    // Cons cells rather than strings or byte strings on purpose: `Rt::string`
    // INTERNS, and the intern tables are shared state the allocation lock does
    // not cover -- which is the gap the ignored test below records.
    for i in 0..n {
        let cell = rt.cons(Value::fixnum(tag * 1_000_000 + i as i64), NIL);
        rt.push(cell);
    }
    // A cell whose pointer was not fixed up after a move reads as the wrong
    // number here, rather than crashing. That is the failure worth catching:
    // a crash would at least be obvious.
    for i in 0..n {
        let cell = rt.r(base + i);
        let got = rt.first(cell).as_fixnum();
        assert_eq!(got, tag * 1_000_000 + i as i64, "thread {tag}: slot {i} came back wrong");
    }
    rt.pop_to(base);
    rt.leave_running();
    n
}

#[test]
fn two_executors_share_one_heap_through_collections() {
    let mut primary = Rt::with_heap(64 * 1024, 256 * 1024 * 1024);
    // SAFETY: the secondary is dropped at the end of the scope below, before
    // `primary` -- which owns the heap both of them use.
    let mut secondary = unsafe { primary.executor() }.expect("a second executor");

    let n = 20_000;
    std::thread::scope(|scope| {
        let second = &mut secondary;
        scope.spawn(move || {
            assert_eq!(churn(second, 2, n), n);
        });
        assert_eq!(churn(&mut primary, 1, n), n);
    });

    // Both really did collect, or the test proved nothing about collection.
    // A run in which nothing was collected proves nothing about collection.
    // This says how many, so a change that quietly stops the nursery filling
    // shows up as a weaker test rather than as a passing one.
    let minor = primary.gc.stats.minor;
    assert!(minor >= 4, "only {minor} collections; the nursery is not under pressure");
    println!("    {minor} collections with two executors on one heap");
    drop(secondary);
}

/// A sandbox with one executor takes no lock and stages no safepoint, and this
/// is what says so: `executor()` is what turns any of it on.
#[test]
fn a_lone_executor_is_unregistered_and_pays_nothing() {
    let mut rt = Rt::with_heap(64 * 1024, 64 * 1024 * 1024);
    assert_eq!(churn(&mut rt, 7, 500), 500);
}

/// What is NOT protected yet, recorded as something runnable rather than as a
/// comment someone has to believe.
///
/// The allocation lock covers the collector's bookkeeping and the safepoint
/// covers moving objects. Neither covers the other SHARED MUTABLE structures a
/// running program touches:
///
///   * the intern tables — `Rt::string`, `keyword` and `symbol` all look up
///     and insert into weak tables that every executor shares;
///   * the remembered set, which the write barrier pushes to whenever an old
///     object is given a young pointer;
///   * `globals`, which `def` writes.
///
/// So this fails, and it should: two threads interning concurrently corrupt
/// the table and values come back as the wrong string. Running it is how you
/// see that, and `#[ignore]` is how the suite stays green while it is true.
///
/// Un-ignore it when those three are protected. It is the next piece of
/// `doc/decisions/0028`, and it is what stands between "two executors can
/// allocate and collect" — which the test above proves — and "two executors
/// can run a program".
#[test]
#[ignore = "interning is not thread-safe yet; this is the next piece of 0028"]
fn interning_from_two_executors_is_not_safe_yet() {
    let mut primary = Rt::with_heap(64 * 1024, 256 * 1024 * 1024);
    let mut secondary = unsafe { primary.executor() }.expect("a second executor");

    fn intern_churn(rt: &mut Rt, tag: &str, n: usize) {
        rt.enter_running();
        let base = rt.mark();
        for i in 0..n {
            let s = rt.string(&format!("{tag}-{i}"));
            rt.push(s);
        }
        for i in 0..n {
            let v = rt.r(base + i);
            let bytes = flint_rt::obj::str_bytes(&rt.gc.sp, v.as_heap());
            let text = core::str::from_utf8(bytes).expect("still a string");
            assert_eq!(text, format!("{tag}-{i}"), "{tag}: slot {i} came back wrong");
        }
        rt.pop_to(base);
        rt.leave_running();
    }

    std::thread::scope(|scope| {
        let second = &mut secondary;
        scope.spawn(move || intern_churn(second, "b", 4000));
        intern_churn(&mut primary, "a", 4000);
    });
    drop(secondary);
}
