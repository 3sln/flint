//! Native benchmarks for the runtime layers the wasm benchmarks cannot isolate:
//! the collector, and the persistent data structures with and without
//! transients.
//!
//! Native, not wasm, on purpose: this measures the data structures themselves,
//! with no interpreter dispatch in the way. `bench/wasm.mjs` measures the other
//! half. Every number is the best of several runs, and the harness prints the
//! machine and the method so the table can be reproduced.

use std::time::Instant;

use flint_rt::rt::Rt;
use flint_rt::value::Value;

/// Setup is untimed and re-run for every repetition, so each measurement starts
/// from the same state without paying for `Rt::new` inside the clock. That is
/// not a detail: a fresh runtime reserves its heap, and reserving dominated
/// every small measurement until it was moved out.
fn bench<S, W>(name: &str, iters: u64, reps: usize, mut setup: S, mut work: W)
where
    S: FnMut() -> Rt,
    W: FnMut(&mut Rt) -> u64,
{
    let mut best = f64::MAX;
    let mut checksum = 0u64;
    for _ in 0..reps {
        let mut rt = setup();
        let t = Instant::now();
        let c = work(&mut rt);
        let ns = t.elapsed().as_nanos() as f64;
        checksum = checksum.wrapping_add(c);
        best = best.min(ns);
    }
    println!(
        "{:<42} {:>11.1} us  {:>9.1} ns/op   (n={}, best of {}, checksum {})",
        name,
        best / 1000.0,
        best / iters as f64,
        iters,
        reps,
        checksum & 0xffff
    );
}

fn main() {
    println!("flint runtime benchmarks (native, {} )", std::env::consts::ARCH);
    println!("method: best wall-clock of N repetitions, one process, no warmup discarding");
    println!();

    const N: u64 = 100_000;
    const REPS: usize = 7;

    // --- vectors ---------------------------------------------------------
    bench("vector: conj x100k (persistent)", N, REPS, Rt::new, |rt| {
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..N {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i as i64));
            rt.set_r(vi, nv);
        }
        v = rt.r(vi);
        rt.vec_count(v) as u64
    });

    bench("vector: conj! x100k (transient)", N, REPS, Rt::new, |rt| {
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        for i in 0..N {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i as i64));
            rt.set_r(ti, nt);
        }
        let v = rt.tvec_persistent(rt.r(ti));
        rt.vec_count(v) as u64
    });

    bench(
        "vector: nth x100k",
        N,
        REPS,
        || {
            let mut rt = Rt::new();
            let mut v = rt.empty_vec();
            let vi = rt.push(v);
            for i in 0..N {
                let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i as i64));
                rt.set_r(vi, nv);
            }
            v = rt.r(vi);
            rt.roots.singletons.push(v);
            rt
        },
        |rt| {
            let v = *rt.roots.singletons.last().unwrap();
            let mut acc = 0u64;
            for i in 0..N {
                acc = acc.wrapping_add(rt.vec_nth(v, i as u32).unwrap().as_fixnum() as u64);
            }
            acc
        },
    );

    // --- maps at several sizes -------------------------------------------
    for size in [8u64, 64, 1_000, 100_000] {
        let iters = size;
        bench(&format!("map: assoc x{} (persistent)", size), iters, REPS, Rt::new, |rt| {
            let mut m = rt.empty_map();
            let mi = rt.push(m);
            for i in 0..size {
                let nm = rt.map_assoc(rt.r(mi), Value::fixnum(i as i64), Value::fixnum(i as i64));
                rt.set_r(mi, nm);
            }
            m = rt.r(mi);
            rt.map_count(m) as u64
        });
        bench(&format!("map: assoc! x{} (transient)", size), iters, REPS, Rt::new, |rt| {
            let e = rt.empty_map();
            let t = rt.map_transient(e);
            let ti = rt.push(t);
            for i in 0..size {
                let nt = rt.tmap_assoc(rt.r(ti), Value::fixnum(i as i64), Value::fixnum(i as i64));
                rt.set_r(ti, nt);
            }
            let m = rt.tmap_persistent(rt.r(ti));
            rt.map_count(m) as u64
        });
        bench(
            &format!("map: get x{}", size),
            iters,
            REPS,
            || {
                let mut rt = Rt::new();
                let mut m = rt.empty_map();
                let mi = rt.push(m);
                for i in 0..size {
                    let nm = rt.map_assoc(rt.r(mi), Value::fixnum(i as i64), Value::fixnum(i as i64));
                    rt.set_r(mi, nm);
                }
                m = rt.r(mi);
                rt.roots.singletons.push(m);
                rt
            },
            |rt| {
                let m = *rt.roots.singletons.last().unwrap();
                let mut acc = 0u64;
                for i in 0..size {
                    let v = rt.map_get(m, Value::fixnum(i as i64), Value::fixnum(-1));
                    acc = acc.wrapping_add(v.as_fixnum() as u64);
                }
                acc
            },
        );
    }

    // --- sets ------------------------------------------------------------
    bench("set: conj x100k", N, REPS, Rt::new, |rt| {
        let mut s = rt.empty_set();
        let si = rt.push(s);
        for i in 0..N {
            let ns = rt.set_conj(rt.r(si), Value::fixnum(i as i64));
            rt.set_r(si, ns);
        }
        s = rt.r(si);
        rt.set_count(s) as u64
    });

    // --- strings and keywords --------------------------------------------
    bench("keyword: intern lookup x100k", N, REPS, Rt::new, |rt| {
        let mut acc = 0u64;
        for i in 0..N {
            let k = rt.keyword(None, if i % 2 == 0 { "alpha" } else { "beta-longer-name" });
            acc = acc.wrapping_add(k.bits());
        }
        acc
    });

    // --- collector -------------------------------------------------------
    println!();
    println!("collector: pause distribution while building a 400k-element vector");
    {
        let mut rt = Rt::with_heap(1024 * 1024, 512 * 1024 * 1024);
        let mut minor_pauses: Vec<f64> = Vec::new();
        let mut major_pauses: Vec<f64> = Vec::new();
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        let start = Instant::now();
        for i in 0..400_000u64 {
            let before_minor = rt.gc.stats.minor;
            let before_major = rt.gc.stats.major;
            let t = Instant::now();
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i as i64));
            let el = t.elapsed().as_nanos() as f64 / 1000.0;
            rt.set_r(vi, nv);
            if rt.gc.stats.major > before_major {
                major_pauses.push(el);
            } else if rt.gc.stats.minor > before_minor {
                minor_pauses.push(el);
            }
        }
        let total = start.elapsed().as_micros() as f64;
        v = rt.r(vi);
        let pct = |v: &mut Vec<f64>, p: f64| -> f64 {
            if v.is_empty() {
                return 0.0;
            }
            v.sort_by(|a, b| a.partial_cmp(b).unwrap());
            v[((v.len() - 1) as f64 * p) as usize]
        };
        let sum: f64 = minor_pauses.iter().sum::<f64>() + major_pauses.iter().sum::<f64>();
        println!("  built {} elements in {:.0} us", rt.vec_count(v), total);
        println!(
            "  minor collections: {:<6} median {:>7.1} us  p95 {:>7.1} us  max {:>7.1} us",
            minor_pauses.len(),
            pct(&mut minor_pauses.clone(), 0.5),
            pct(&mut minor_pauses.clone(), 0.95),
            pct(&mut minor_pauses.clone(), 1.0)
        );
        println!(
            "  major collections: {:<6} median {:>7.1} us  p95 {:>7.1} us  max {:>7.1} us",
            major_pauses.len(),
            pct(&mut major_pauses.clone(), 0.5),
            pct(&mut major_pauses.clone(), 0.95),
            pct(&mut major_pauses.clone(), 1.0)
        );
        println!(
            "  collector share of wall clock: {:.1}%   promoted {} KiB of {} KiB allocated",
            100.0 * sum / total,
            rt.gc.stats.bytes_promoted / 1024,
            rt.gc.stats.bytes_allocated / 1024
        );
    }

    println!();
    println!("collector: cost of a major collection against live-set size");
    for live in [10_000u64, 100_000, 400_000] {
        let mut rt = Rt::with_heap(1024 * 1024, 512 * 1024 * 1024);
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..live {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i as i64));
            rt.set_r(vi, nv);
        }
        v = rt.r(vi);
        let _ = v;
        rt.collect(); // settle everything into the old generation
        let t = Instant::now();
        rt.collect();
        let us = t.elapsed().as_nanos() as f64 / 1000.0;
        println!(
            "  live {:>7} elements ({:>6} KiB old)   major collection {:>8.1} us",
            live,
            rt.gc.old_live() / 1024,
            us
        );
    }
}
