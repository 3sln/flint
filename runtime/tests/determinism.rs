use flint_rt::rt::Rt;

fn walk_cost(collect_at: Option<u32>) -> u64 {
    let mut rt = Rt::new();
    rt.install_host_natives();
    let mut text = String::from("ä");
    for _ in 0..4000 {
        text.push_str("abcdefgh");
    }
    let s = rt.string(&text);
    let si = rt.push(s);
    let before = rt.steps;
    for i in 0..8000u32 {
        if Some(i) == collect_at {
            rt.gc.minor(&mut rt.roots);
        }
        let v = rt.r(si);
        let _ = rt.char_at(v, i, flint_rt::value::NIL);
    }
    rt.steps - before
}

/// Gas is meant to be REPRODUCIBLE (`doc/decisions/0009`): the counter measures
/// work, and the same work must cost the same whatever the collector did.
///
/// This exists because a one-entry cursor over the last-indexed string broke it.
/// The cursor had to be invalidated when the collector moved things, so the same
/// walk cost 8 000 gas undisturbed and 8 500 with one collection halfway -- and
/// under parallel executors (`doc/decisions/0028`) that collection belongs to
/// ANOTHER THREAD, so a program's gas depended on what its neighbours were
/// doing. Worse than a wrong number: an unreproducible one.
#[test]
fn gas_for_a_walk_does_not_depend_on_when_the_collector_ran() {
    let clean = walk_cost(None);
    let disturbed = walk_cost(Some(4000));
    println!("undisturbed {clean}, one collection halfway {disturbed}");
    // NOT ZERO. An instrument reading zero agrees with everything, which is the
    // other way this check can be worthless.
    assert!(clean > 0, "the walk must be charged for, or this proves nothing");
    assert_eq!(clean, disturbed, "gas must be reproducible (doc/decisions/0009)");
}
