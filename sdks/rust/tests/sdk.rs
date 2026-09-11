//! The Rust SDK, exercised the way a caller uses it.
//!
//! The shape is the JavaScript SDK's on purpose (`DECISIONS.md#structured-ports`):
//! compile to an Image, instantiate a Sandbox, call functions by name. What
//! differs is only that this runs the program NATIVELY rather than through a
//! wasm engine.

use flint::{Compile, Compiler, Value};

const APP: &str = r#"
(ns app (:require [clojure.string :as s]))
(def seen (atom 0))
(defn greet [name] (s/upper-case (str "hello " name)))
(defn tally [] (swap! seen inc))
(defn echo [x] x)
;; NOTE what this namespace does NOT do: it never requires `flint.table` and
;; never names a table. It reads whatever arrives as an ordinary collection.
(defn tabled [t] [(count t) (map? (get t 0)) (:id (get t 1)) (:name (get t 1))])
(defn spin [] (loop [i 0] (if (< i 10000000) (recur (inc i)) i)))
(defn churn [t] (+ t (reduce + 0 (map (fn [i] i) (range 2000)))))
(defn boom [] (throw (ex-info "deliberate" {:a 1})))
(defn main [args] (str "main saw " (pr-str args)))
"#;

fn image() -> flint::Image {
    let compiler = Compiler::embedded().expect("the compiler is embedded");
    compiler
        .compile(Compile {
            resolve: &|ns: &str| if ns == "app" { Some(APP.to_string()) } else { None },
            fn_name: "app/main",
            exports: &["app/greet", "app/tally", "app/echo", "app/boom", "app/spin", "app/churn", "app/tabled"],
            meta: vec![("capabilities".into(), Value::Vector(vec![Value::str("fs")]))],
            ..Default::default()
        })
        .expect("it compiles")
}

#[test]
fn compile_then_call_by_name() {
    let img = image();
    assert!(img.wasm.len() > 100_000, "the artifact is a module");
    let sandbox = img.sandbox().expect("it instantiates");
    assert_eq!(
        sandbox.call_blocking("app/greet", &[Value::str("flint")]).unwrap(),
        Value::str("HELLO FLINT")
    );
}

/// A sandbox serves MANY calls and they share its state -- initialisers run
/// once, not per call, which is what makes instantiate-once-call-per-request
/// work at all.
#[test]
fn a_sandbox_keeps_its_state_and_a_fresh_one_does_not() {
    let img = image();
    let a = img.sandbox().unwrap();
    a.call_blocking("app/tally", &[]).unwrap();
    assert_eq!(a.call_blocking("app/tally", &[]).unwrap(), Value::Int(2));
    let b = img.sandbox().unwrap();
    assert_eq!(b.call_blocking("app/tally", &[]).unwrap(), Value::Int(1));
}

/// Anything the codec carries can be passed and returned, and comes back as
/// what it was rather than as an approximation of it.
#[test]
fn values_survive_a_call() {
    let img = image();
    let s = img.sandbox().unwrap();
    let cases = vec![
        Value::Nil,
        Value::Int(-7),
        Value::Float(1.5),
        Value::str("text"),
        Value::kw("a"),
        Value::kw("my.ns/a"),
        Value::Vector(vec![Value::Int(1), Value::str("two")]),
        Value::kwmap([("a", Value::Int(1))]),
        Value::Bytes(vec![1, 2, 255]),
    ];
    for v in cases {
        let out = s.call_blocking("app/echo", &[v.clone()]).unwrap();
        assert_eq!(v, out, "a value did not survive a call");
    }
}

/// A failure arrives as a failure, with what went wrong in it.
#[test]
fn a_thrown_error_reaches_the_caller() {
    let img = image();
    let s = img.sandbox().unwrap();
    match s.call_blocking("app/boom", &[]) {
        Err(flint::Error::Call { message, .. }) => {
            assert!(message.contains("deliberate"), "the message should say what: {message}");
        }
        other => panic!("expected a call error, got {other:?}"),
    }
    match s.call_blocking("app/nope", &[]) {
        Err(flint::Error::Call { message, .. }) => {
            assert!(message.contains("app/nope"), "the message should name it: {message}");
        }
        other => panic!("expected a call error, got {other:?}"),
    }
}

/// Deterministic, and available in every build because it is resource control
/// rather than instrumentation (`DECISIONS.md#resource-limits`).
///
/// Counting is what a STEP LIMIT turns on. Without one the interpreter has no
/// counter in its inner loop at all, which is the point -- so this pins both
/// halves, because "gas reads zero" is exactly the shape a caller would
/// otherwise report as a bug.
#[test]
fn gas_counts_under_a_limit_and_not_otherwise() {
    let img = image();

    let idle = img.sandbox().unwrap();
    idle.call_blocking("app/echo", &[Value::Int(1)]).unwrap();
    assert_eq!(idle.gas(), 0, "an unbudgeted sandbox does not count, by design");

    let s = img.sandbox().unwrap();
    s.set_step_limit(50_000_000);
    s.call_blocking("app/echo", &[Value::Int(1)]).unwrap();
    let first = s.gas();
    assert!(first > 0, "a budgeted sandbox counts");
    s.call_blocking("app/echo", &[Value::Int(1)]).unwrap();
    assert!(s.gas() > first, "and keeps counting across calls");
}

/// The limit is a limit: a program that runs past it is stopped rather than
/// finished, and the caller is told which it was.
#[test]
fn a_step_limit_stops_a_program() {
    let img = image();
    let s = img.sandbox().unwrap();
    s.set_step_limit(2_000);
    match s.call_blocking("app/spin", &[]) {
        Err(e) => {
            let text = e.to_string();
            assert!(text.contains("gas") || text.contains("budget") || text.contains("limit"),
                    "the error should say the budget blew, got: {text}");
        }
        Ok(v) => panic!("2000 instructions should not have finished a spin, got {v:?}"),
    }
}

/// The image carries what the compiler was told to record, and the runtime does
/// not interpret it (`DECISIONS.md#structured-ports`).
#[test]
fn metadata_is_carried_and_not_interpreted() {
    let img = image();
    let caps = img.metadata().iter().find(|(k, _)| k == "capabilities");
    assert!(caps.is_some(), "the metadata should carry what was given");
}

/// A capability lent to one sandbox is lent to THAT sandbox.
///
/// REMOVED 2026-08-30: `a_grant_reaches_one_sandbox_and_not_its_neighbours`.
///
/// It tested that a capability lent to one sandbox did not leak to another,
/// through `Sandbox::grant` and the `flint.rt/capabilities` builtin. Both are
/// gone: the cutover took the concept out of the sandbox entirely, so there is
/// no table to leak from and no ambient path for a guest to ask what it holds.
/// The property is structural now rather than enforced.
///
/// What replaced the coverage is in `test/capability.clj`, where a HOST
/// implements the pattern: it issues ids, checks them on an open, and refuses
/// a guest-minted opaque -- which carries id 0 and cannot carry anything else.
/// `codec.opaque` refusing to issue 0 is the other half.

// --- drivers (`DECISIONS.md#drivers`) -----------------------------------------

/// A sandbox does not run because someone called into it; it runs because a
/// DRIVER gave it a thread. These are the shape the other SDKs mirror.
#[test]
fn parallelism_reads_back_what_the_driver_actually_gives() {
    let img = image();
    assert_eq!(img.sandbox().unwrap().parallelism(), 1, "inline is one thread");

    let pool = std::sync::Arc::new(flint::ThreadPool::new(4));
    assert_eq!(img.sandbox_with(pool).unwrap().parallelism(), 4);
}

/// Four OS threads, one sandbox. They contend on it -- today the sandbox
/// serialises them -- and every answer still has to be right.
#[test]
fn a_pool_drives_one_sandbox_from_several_threads() {
    let img = image();
    let pool = std::sync::Arc::new(flint::ThreadPool::new(4));
    let sandbox = img.sandbox_with(pool).unwrap();

    // Fired from four threads at once, and awaited afterwards, so the requests
    // genuinely overlap rather than taking turns.
    let mut waiting = Vec::new();
    for _ in 0..4 {
        let s = sandbox.clone();
        waiting.push(std::thread::spawn(move || {
            (0..25).map(|_| s.call("app/tally", &[])).collect::<Vec<_>>()
        }));
    }
    let mut seen: Vec<i64> = Vec::new();
    for t in waiting {
        for pending in t.join().unwrap() {
            seen.push(pending.wait().unwrap().as_i64().expect("tally returns an int"));
        }
    }

    // `tally` is `(swap! seen inc)`, so 100 calls must produce exactly 1..=100
    // in some order. A duplicate would mean two threads read the same state.
    seen.sort_unstable();
    assert_eq!(seen, (1..=100).collect::<Vec<i64>>(),
               "100 calls through one sandbox must be 100 distinct increments");
}

/// The claim debouncing exists to make, with a number behind it.
///
/// A burst of arrivals into a busy sandbox has to cost ONE crossing rather than
/// one per arrival. Measured, not asserted: the counters are on the sandbox.
#[test]
fn a_burst_of_calls_costs_fewer_dispatches_than_calls() {
    let img = image();
    let pool = std::sync::Arc::new(flint::ThreadPool::with_debounce(
        2,
        std::time::Duration::from_millis(2),
    ));
    let sandbox = img.sandbox_with(pool).unwrap();

    let pending: Vec<_> = (0..200).map(|_| sandbox.call("app/tally", &[])).collect();
    for p in pending {
        p.wait().unwrap();
    }

    let (dispatches, served) = sandbox.dispatch_counts();
    assert_eq!(served, 200, "every request is served exactly once");
    assert!(dispatches < served,
            "coalescing bought nothing: {dispatches} dispatches for {served} requests");
    println!("    coalesced {served} requests into {dispatches} dispatches");
}

/// The inline driver still runs on the calling thread, and a call is still
/// asynchronous in TYPE even when it completes before `call` returns. That is
/// the point: a synchronous API cannot be made asynchronous later.
#[test]
fn inline_completes_before_the_call_returns() {
    let img = image();
    let sandbox = img.sandbox().unwrap();
    let pending = sandbox.call("app/echo", &[Value::Int(7)]);
    assert_eq!(pending.try_take().expect("inline finished it")
                      .unwrap(), Value::Int(7));
}

/// Several driver threads running GUEST CODE on one sandbox at once.
///
/// The earlier pool test proved the dispatch was parallel while the program
/// itself was serialised behind a lock. This one takes that lock off the path:
/// each driver thread gets its own executor on the SAME heap, so two threads
/// are inside the interpreter together.
///
/// Correctness first, and it is the same assertion as before because it is the
/// same property: 100 calls through one `(swap! seen inc)` have to be exactly
/// 1..=100. A missed increment means two threads read one state.
#[test]
fn a_pool_runs_guest_code_on_one_sandbox_in_parallel() {
    let img = image();
    let pool = std::sync::Arc::new(flint::ThreadPool::new(4));
    let sandbox = img.sandbox_with(pool).unwrap();
    assert_eq!(sandbox.parallelism(), 4);

    let mut waiting = Vec::new();
    for _ in 0..4 {
        let s = sandbox.clone();
        waiting.push(std::thread::spawn(move || {
            (0..25).map(|_| s.call("app/tally", &[])).collect::<Vec<_>>()
        }));
    }
    let mut seen: Vec<i64> = Vec::new();
    for t in waiting {
        for pending in t.join().unwrap() {
            seen.push(pending.wait().unwrap().as_i64().expect("tally returns an int"));
        }
    }
    seen.sort_unstable();
    assert_eq!(seen, (1..=100).collect::<Vec<i64>>());

    // And it really did run on the executors. Without this the test passes
    // just as well when every dispatch falls back to the program lock, which
    // is precisely the failure worth catching: correct, and not parallel.
    let on_exec = sandbox.parallel_dispatches();
    assert!(on_exec > 0, "no dispatch ran on a secondary executor");
    println!("    {on_exec} dispatches ran on a secondary executor");
}

/// The same, but allocation-heavy, so collections happen WHILE other threads
/// are inside the interpreter. That is the case the safepoint exists for.
#[test]
fn guest_code_survives_collections_staged_by_another_thread() {
    let img = image();
    let pool = std::sync::Arc::new(flint::ThreadPool::new(4));
    let sandbox = img.sandbox_with(pool).unwrap();

    let mut waiting = Vec::new();
    for t in 0..4i64 {
        let s = sandbox.clone();
        waiting.push(std::thread::spawn(move || {
            (0..12)
                .map(|_| s.call("app/churn", &[Value::Int(t)]))
                .collect::<Vec<_>>()
        }));
    }
    for (t, h) in waiting.into_iter().enumerate() {
        for pending in h.join().unwrap() {
            // `churn` builds a list and sums it, so a value lost to a
            // collection staged by another thread comes back as a wrong TOTAL
            // rather than as a crash.
            assert_eq!(
                pending.wait().unwrap(),
                Value::Int(2000 * 1999 / 2 + t as i64),
                "thread {t} got a wrong sum"
            );
        }
    }
}

/// `swap!` under real contention, on the native runtime.
///
/// The JVM port surfaced this: flint's `swap!` is `(reset! a (f (deref a)))`
/// in `lib/clojure/core.cljc` -- a plain read-modify-write with no atomicity.
/// It loses updates whenever two threads are inside it at once, and no part of
/// that crashes: the counter is simply smaller than it should be.
///
/// The existing pool test did not catch it because only three of its dispatches
/// ever ran on a secondary executor. This one contends on purpose.
#[test]
fn swap_under_contention_loses_nothing() {
    let img = image();
    let pool = std::sync::Arc::new(flint::ThreadPool::new(4));
    let sandbox = img.sandbox_with(pool).unwrap();

    const THREADS: usize = 4;
    const PER: usize = 250;
    let mut waiting = Vec::new();
    for _ in 0..THREADS {
        let s = sandbox.clone();
        waiting.push(std::thread::spawn(move || {
            let pending: Vec<_> = (0..PER).map(|_| s.call("app/tally", &[])).collect();
            for p in pending {
                p.wait().unwrap();
            }
        }));
    }
    for t in waiting {
        t.join().unwrap();
    }

    let total = sandbox.call_blocking("app/tally", &[]).unwrap();
    let want = (THREADS * PER) as i64 + 1;
    assert_eq!(
        total,
        Value::Int(want),
        "swap! lost {} increments; it is a read-modify-write, not a CAS",
        want - total.as_i64().unwrap_or(0)
    );
}


/// A TABLE ARRIVES AT A MODULE THAT NEVER MENTIONS TABLES.
///
/// This is the tree-shaking soundness question Ray raised: if a program can
/// TAKE from a port, it might receive a table, so the shaker must not remove
/// the machinery that builds one just because the program never names it.
///
/// It holds for a reason worth writing down rather than hoping about:
/// `flint_call` is an unconditional export (`link.rs`'s root list), the wire
/// decoder hangs off it, and the decoder references the table constructor
/// directly -- so the constructor is reachable in every module, always.
///
/// The test is behavioural rather than a symbol check because release modules
/// are stripped: grepping the wasm for `table_from_columns` finds nothing in a
/// module that certainly contains it.
#[test]
fn a_table_reaches_a_module_that_never_heard_of_tables() {
    let img = image();
    let sandbox = img.sandbox().expect("it instantiates");
    let table = Value::Table {
        schema: vec![("id".into(), "int".into()), ("name".into(), "string".into())],
        columns: vec![
            vec![Value::Int(10), Value::Int(20)],
            vec![Value::str("a"), Value::str("b")],
        ],
    };
    let got = sandbox.call_blocking("app/tabled", &[table]).unwrap();
    assert_eq!(
        got,
        Value::Vector(vec![
            Value::Int(2),
            Value::Bool(true),
            Value::Int(20),
            Value::str("b"),
        ]),
        "a shaken module that never names a table must still be able to receive one"
    );
}

/// And it comes back OUT as a table, columnar, rather than as a vector of maps.
#[test]
fn a_table_returns_as_a_table() {
    let img = image();
    let sandbox = img.sandbox().expect("it instantiates");
    let table = Value::Table {
        schema: vec![("id".into(), "int".into())],
        columns: vec![vec![Value::Int(1), Value::Int(2), Value::Int(3)]],
    };
    let got = sandbox.call_blocking("app/echo", &[table.clone()]).unwrap();
    assert_eq!(got, table, "a table crosses both ways as a table");
}
