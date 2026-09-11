//! Two ways to walk a map, measured in ONE runtime.
//!
//! Rust iterates with a CALLBACK (`map_for_each`); both ports iterate with a
//! BUFFER (`entries`), writing every key and value onto the shadow stack and
//! returning the pair count. The same job, and the buffer needs no callback --
//! which decides whether kin has to grow closures at all.
//!
//! Measuring one against the other ACROSS runtimes would compare the approach
//! and everything else about the runtimes at once, so both live here, in Rust,
//! over the same map.
//!
//!     cargo run -p flint-rt --release --example iterbench
//!
//! Reports wall time and PEAK ROOT DEPTH, which is the cost the buffer shape
//! is suspected of: it pushes 2N roots before the caller reads any of them.
use flint_rt::rt::Rt;
use flint_rt::value::{Value, NIL};

fn build(rt: &mut Rt, n: u32) -> Value {
    let base = rt.mark();
    let e = rt.empty_map();
    let mi = rt.push(e);
    for i in 0..n {
        let k = Value::fixnum(i as i64);
        let v = Value::fixnum((i * 2) as i64);
        let m = rt.r(mi);
        let nm = rt.map_assoc(m, k, v);
        rt.set_r(mi, nm);
    }
    let out = rt.r(mi);
    rt.pop_to(base);
    out
}

fn main() {
    let n: u32 = std::env::args().nth(1).and_then(|s| s.parse().ok()).unwrap_or(50_000);
    let reps: u32 = std::env::args().nth(2).and_then(|s| s.parse().ok()).unwrap_or(20);
    let mut rt = Rt::new();
    let built = build(&mut rt, n);
    // Rooted for the whole run and never popped. The entry-vector rounds below
    // allocate, and a map held only in a Rust local does not survive that
    // (`DECISIONS.md#a-vec-of-values-is-not-a-root`) -- the first draft of this harness left it bare and
    // got an empty vector back, which is the rule enforcing itself.
    let mi = rt.push(built);
    let m = rt.r(mi);
    println!("map of {n} entries, {reps} walks each\n");

    // CALLBACK: visits in place, pushes nothing per entry.
    let mut peak_cb = 0usize;
    let t0 = std::time::Instant::now();
    let mut sum: u64 = 0;
    for _ in 0..reps {
        let mut st = (0u64, 0usize);
        rt.map_for_each(m, &mut st, &mut |rt, _k, v, st| {
            st.0 = st.0.wrapping_add(v.as_fixnum() as u64);
            let d = rt.mark();
            if d > st.1 { st.1 = d; }
        });
        sum = sum.wrapping_add(st.0);
        if st.1 > peak_cb { peak_cb = st.1; }
    }
    let cb = t0.elapsed();

    // BUFFER: every key and value onto the shadow stack, then a loop.
    let mut peak_buf = 0usize;
    let t1 = std::time::Instant::now();
    let mut sum2: u64 = 0;
    for _ in 0..reps {
        let at = rt.mark();
        let cnt = rt.map_entries(m, at);
        let depth = rt.mark();
        if depth > peak_buf { peak_buf = depth; }
        for i in 0..cnt {
            sum2 = sum2.wrapping_add(rt.r(at + 2 * i as usize + 1).as_fixnum() as u64);
        }
        rt.pop_to(at);
    }
    let buf = t1.elapsed();

    // BUFFER, no slide: the shape's honest best case.
    let mut peak_flat = 0usize;
    let t2 = std::time::Instant::now();
    let mut sum3: u64 = 0;
    for _ in 0..reps {
        let at = rt.mark();
        let cnt = rt.map_entries_flat(m, at);
        let depth = rt.mark();
        if depth > peak_flat { peak_flat = depth; }
        for i in 0..cnt {
            sum3 = sum3.wrapping_add(rt.r(at + 2 * i as usize + 1).as_fixnum() as u64);
        }
        rt.pop_to(at);
    }
    let flat = t2.elapsed();

    // --- the REAL workload -------------------------------------------------
    //
    // A bare walk allocates nothing, so no collection can happen during it and
    // 2N live roots cost nothing. The cost only lands when the caller ALLOCATES
    // while holding them -- which is what `map_entry_vector` does: an entry and
    // a conj per key, every one of them a chance to collect, with the whole
    // buffer live and scanned each time. This is the comparison that decides.
    let vreps = if reps > 5 { 5 } else { reps };
    let t3 = std::time::Instant::now();
    for _ in 0..vreps {
        let v = rt.map_entry_vector(rt.r(mi));
        assert_eq!(rt.vec_count(v), n);
    }
    let via_cb = t3.elapsed();

    let t4 = std::time::Instant::now();
    for _ in 0..vreps {
        let base = rt.mark();
        let cnt = rt.map_entries_flat(rt.r(mi), base);
        let acc = rt.empty_vec();
        let ai = rt.push(acc);
        for i in 0..cnt {
            let k = rt.r(base + 2 * i as usize);
            let vv = rt.r(base + 2 * i as usize + 1);
            let ent = rt.map_entry(k, vv);
            let nv = rt.vec_conj(rt.r(ai), ent);
            rt.set_r(ai, nv);
        }
        let out = rt.r(ai);
        assert_eq!(rt.vec_count(out), n);
        rt.pop_to(base);
    }
    let via_buf = t4.elapsed();

    assert_eq!(sum, sum2, "the two walks must see the same entries");
    assert_eq!(sum, sum3, "the slide-free walk must see the same entries too");
    println!("  callback  {:>9.3} ms   peak roots {}", cb.as_secs_f64() * 1000.0, peak_cb);
    println!("  buffer    {:>9.3} ms   peak roots {}", buf.as_secs_f64() * 1000.0, peak_buf);
    println!("  buf/noslide {:>7.3} ms   peak roots {}", flat.as_secs_f64() * 1000.0, peak_flat);
    println!("\n  entry-vector ({vreps}x), where allocation meets the live roots:");
    println!("    via callback {:>9.3} ms", via_cb.as_secs_f64() * 1000.0);
    println!("    via buffer   {:>9.3} ms  ({:.3}x)",
             via_buf.as_secs_f64() * 1000.0,
             via_buf.as_secs_f64() / via_cb.as_secs_f64());
    let r = buf.as_secs_f64() / cb.as_secs_f64();
    println!("  buf/noslide vs callback {:.3}x", flat.as_secs_f64() / cb.as_secs_f64());
    println!("\n  buffer/callback time {r:.3}, peak roots {}x", peak_buf / peak_cb.max(1));
    let _ = NIL;
}
