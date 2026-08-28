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

/// A name in the band that is ACTUALLY interned.
///
/// Below `INLINE_MAX` (5) a string is NaN-boxed into the value and never
/// reaches a table; above `INTERN_MAX` (32) it is allocated raw and not
/// interned either. Only 6..=32 bytes exercises the tables -- and getting this
/// wrong is how an earlier version of this test "failed": it read inline
/// strings as if they were heap objects and reported the runtime broken.
fn interned_name(tag: &str, i: usize) -> String {
    format!("{tag}-{i:016}")
}

/// Interning from two executors at once.
///
/// This is the test that used to be ignored, and it failed for a reason worth
/// keeping: two threads interning concurrently produced TWO interned copies of
/// one string, and `eq` reads "both interned and not bit-equal" as NOT EQUAL.
/// So equal strings compared unequal while reading identically -- and symbol
/// equality is slot equality on those same strings, so it spread.
///
/// The fix is a lock per table held only across a hash PROBE, never across an
/// allocation, plus a re-probe after building the value so that a loser in the
/// race takes the winner's copy instead of publishing a second one.
///
/// Not sharded, and that is measured rather than assumed: on the most
/// string-heavy workload we have -- flint compiling construe -- the tables are
/// probed 18 247 times across 4.25 seconds. 26% of `Rt::string` calls reach a
/// table, but the duty cycle is about 0.04%, and sharding would optimise
/// something that is not happening.
#[test]
fn interning_from_two_executors_agrees_on_one_copy() {
    let mut primary = Rt::with_heap(64 * 1024, 256 * 1024 * 1024);
    let mut secondary = unsafe { primary.executor() }.expect("a second executor");

    fn intern_churn(rt: &mut Rt, tag: &str, n: usize) {
        rt.enter_running();
        let base = rt.mark();
        for i in 0..n {
            let s = rt.string(&interned_name(tag, i));
            rt.push(s);
        }
        let mut wrong = 0usize;
        let mut first = None;
        for i in 0..n {
            let v = rt.r(base + i);
            let want = interned_name(tag, i);
            let ok = v.is_heap()
                && flint_rt::obj::ty(&rt.gc.sp, v.as_heap()) == flint_rt::obj::TY_STR
                && flint_rt::obj::str_bytes(&rt.gc.sp, v.as_heap()) == want.as_bytes();
            if !ok {
                wrong += 1;
                if first.is_none() {
                    let t = if v.is_heap() {
                        flint_rt::obj::ty(&rt.gc.sp, v.as_heap())
                    } else {
                        255
                    };
                    first = Some((i, v.0, t));
                }
            }
        }
        if wrong > 0 {
            let (i, bits, t) = first.unwrap();
            panic!("{tag}: {wrong}/{n} wrong; first slot {i} bits {bits:#x} ty {t}");
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

/// The same interning work on ONE executor. If this fails, the problem is the
/// intern refactor and not concurrency at all -- worth separating before
/// chasing a race that may not exist.
#[test]
fn interning_on_one_executor_still_works() {
    let mut rt = Rt::with_heap(64 * 1024, 256 * 1024 * 1024);
    rt.enter_running();
    let base = rt.mark();
    for i in 0..4000 {
        let s = rt.string(&interned_name("solo", i));
        rt.push(s);
    }
    for i in 0..4000 {
        let v = rt.r(base + i);
        let bytes = flint_rt::obj::str_bytes(&rt.gc.sp, v.as_heap());
        let text = core::str::from_utf8(bytes).expect("still a string");
        assert_eq!(text, interned_name("solo", i), "slot {i} came back wrong");
    }
    rt.pop_to(base);
    rt.leave_running();
}

/// The interning invariant itself: one text, one interned object.
///
/// **Measured both ways.** Without the intern locks this fails 40 times out of
/// 40, with 10 to 17 of 3 000 texts interning to two objects. With them, 0 out
/// of 40. The disjoint-names test above fails 0 out of 60 either way, which is
/// why it could not be the evidence for anything.
///
/// Both executors intern the SAME names, so they race for the same probe
/// sequences -- which the disjoint-names test above never does, and is why it
/// could not demonstrate the race it was written to demonstrate.
///
/// What breaks without a lock is not a crash. Two threads both miss, both
/// allocate, and both publish, leaving TWO interned objects with the same
/// text. `eq` reads "both interned and not bit-equal" as NOT EQUAL, so those
/// two strings compare unequal while printing identically -- and symbol
/// equality is slot equality on exactly these strings, so it spreads to
/// symbols and to anything keyed by one.
///
/// So the assertion is bit-equality, not text-equality: text-equality is what
/// still passes when this is broken.
#[test]
fn one_text_interns_to_one_object_across_executors() {
    const N: usize = 3000;
    let mut primary = Rt::with_heap(1024 * 1024, 256 * 1024 * 1024);
    let mut secondary = unsafe { primary.executor() }.expect("a second executor");

    fn intern_all(rt: &mut Rt, tag: i64) -> Vec<u64> {
        rt.enter_running();
        let base = rt.mark();
        let mut out = Vec::with_capacity(N);
        for i in 0..N {
            // The SAME text from both threads, and no tag in it.
            let s = rt.string(&interned_name("shared", i));
            rt.push(s);
        }
        for i in 0..N {
            out.push(rt.r(base + i).0);
        }
        rt.pop_to(base);
        rt.leave_running();
        let _ = tag;
        out
    }

    let mut theirs = Vec::new();
    let mine = std::thread::scope(|scope| {
        let second = &mut secondary;
        let h = scope.spawn(move || intern_all(second, 2));
        let mine = intern_all(&mut primary, 1);
        theirs = h.join().expect("the second executor finished");
        mine
    });

    let mut differ = 0usize;
    for i in 0..N {
        if mine[i] != theirs[i] {
            differ += 1;
        }
    }
    assert_eq!(
        differ, 0,
        "{differ}/{N} texts interned to two different objects; \
         those compare UNEQUAL despite reading identically"
    );
    drop(secondary);
}

/// A keyword and a string with the same text already SHARE that text.
///
/// This is here because merging the three intern tables into one looks like it
/// would buy this, and it would not: it is already true, by a different
/// mechanism. `keyword` builds its name slot with `Rt::string`, so a heap
/// keyword's name IS the interned string; and a short unqualified keyword is
/// NaN-boxed into the value, so `name` on it is a tag flip with no table
/// involved at all.
///
/// Merging would also cost something. `hash_string`, `hash_keyword` and
/// `hash_symbol` differ on purpose -- a keyword and a string with equal text
/// are not `=` and must not collide as map keys -- so one table would have to
/// key on (kind, text): the same distinctions, more entries, more collisions.
#[test]
fn a_keywords_name_is_the_same_object_as_the_equal_string() {
    let mut rt = Rt::with_heap(1024 * 1024, 64 * 1024 * 1024);

    // Heap keyword: long enough not to be inline.
    let text = "a-keyword-long-enough-to-be-heap";
    let kw = rt.keyword(None, text);
    let s = rt.string(text);
    assert_eq!(rt.name_of(kw).0, s.0, "a heap keyword's name should BE the interned string");

    // Inline keyword: no table, no allocation, just a tag flip.
    let kw = rt.keyword(None, "abc");
    let s = rt.string("abc");
    assert_eq!(rt.name_of(kw).0, s.0, "an inline keyword's name should be the inline string");

    // And the reverse direction reuses it rather than allocating a second one.
    let sym = rt.symbol(None, text);
    let interned = rt.string(text);
    assert_eq!(rt.name_of(sym).0, interned.0, "a symbol's name too");
}

/// The write barrier, across executors.
///
/// An old object pointing at a young one is only found by a minor collection
/// because the WRITE BARRIER recorded it. Those records are per-executor, so a
/// collection staged by one thread has to drain every thread's list -- and if
/// it drained only its own, the young objects another thread had just stored
/// into old ones would be collected while still referenced.
///
/// That failure does not crash. It reads back as the wrong number, later,
/// which is why this checks values rather than liveness.
#[test]
fn old_to_young_edges_survive_a_collection_staged_elsewhere() {
    // An object of at least LARGE_OBJECT (16 KB) is born in the OLD
    // generation, which is the only way to get an old-to-young edge without
    // waiting for a promotion.
    const SLOTS: u32 = 4096; // 4096 * 8 bytes, comfortably over the threshold
    let mut primary = Rt::with_heap(64 * 1024, 256 * 1024 * 1024);
    let mut secondary = unsafe { primary.executor() }.expect("a second executor");

    fn barrier_churn(rt: &mut Rt, tag: i64) {
        rt.enter_running();
        let base = rt.mark();
        let a = rt.alloc(flint_rt::obj::TY_VEC, SLOTS);
        assert_ne!(a, 0, "the old-generation object should allocate");
        let holder = rt.push(Value::heap(a));

        // Each slot gets a freshly allocated YOUNG object. Every one of these
        // is an old-to-young edge and fires the barrier.
        for i in 0..SLOTS {
            let cell = rt.cons(Value::fixnum(tag * 1_000_000 + i as i64), NIL);
            // Re-read AFTER the allocation: `cons` can collect, and while a
            // large object does not move in a minor collection, reading its
            // address before an allocation is the habit that breaks the day it
            // is promoted or compacted.
            let at = rt.r(holder).as_heap();
            rt.set_slot(at, i, cell);
        }

        // Now allocate hard, so collections happen -- staged by whichever
        // executor fills the nursery first, which is the point.
        for _ in 0..20_000 {
            let junk = rt.cons(Value::fixnum(0), NIL);
            rt.push(junk);
            rt.pop_to(rt.mark() - 1);
        }

        for i in 0..SLOTS {
            // This loop READS the heap and never allocates, so nothing else
            // would make it reach a safepoint -- and a thread that is
            // `enter_running` is one the collector waits for. Without this the
            // other executor waits forever, which is exactly what it did:
            // "waiting for executors to park: stop=1 active=2 parked=0".
            //
            // The rule it makes concrete: a native that walks heap data for a
            // long time without allocating has to poll.
            rt.safepoint();
            let at = rt.r(holder).as_heap();
            let cell = flint_rt::obj::slot(&rt.gc.sp, at, i);
            let got = rt.first(cell).as_fixnum();
            assert_eq!(
                got,
                tag * 1_000_000 + i as i64,
                "thread {tag}: slot {i} lost its young object"
            );
        }
        rt.pop_to(base);
        rt.leave_running();
    }

    // ONE executor first. If this fails, the problem is the test or the
    // barrier itself, not anything to do with two threads -- worth separating
    // before chasing a race.
    barrier_churn(&mut primary, 9);

    std::thread::scope(|scope| {
        let second = &mut secondary;
        scope.spawn(move || barrier_churn(second, 2));
        barrier_churn(&mut primary, 1);
    });
    assert!(primary.gc.stats.minor > 0, "no collection ran, so nothing was proved");
    drop(secondary);
}
