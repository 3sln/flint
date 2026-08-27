//! The Rust SDK, exercised the way a caller uses it.
//!
//! The shape is the JavaScript SDK's on purpose (`doc/decisions/0025`):
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
(defn spin [] (loop [i 0] (if (< i 10000000) (recur (inc i)) i)))
(defn caps [] (vec (sort (map name (keys (flint.rt/capabilities))))))
(defn boom [] (throw (ex-info "deliberate" {:a 1})))
(defn main [args] (str "main saw " (pr-str args)))
"#;

fn image() -> flint::Image {
    let compiler = Compiler::embedded().expect("the compiler is embedded");
    compiler
        .compile(Compile {
            resolve: &|ns: &str| if ns == "app" { Some(APP.to_string()) } else { None },
            fn_name: "app/main",
            exports: &["app/greet", "app/tally", "app/echo", "app/boom", "app/spin", "app/caps"],
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
/// rather than instrumentation (`doc/decisions/0009`).
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
/// not interpret it (`doc/decisions/0025`).
#[test]
fn metadata_is_carried_and_not_interpreted() {
    let img = image();
    let caps = img.metadata().iter().find(|(k, _)| k == "capabilities");
    assert!(caps.is_some(), "the metadata should carry what was given");
}

/// A capability lent to one sandbox is lent to THAT sandbox.
///
/// This was a real hole and not a hypothetical one. The grant table was a
/// `static mut` in the runtime, which on wasm is per-module-instance and so was
/// per-sandbox by accident. Natively one process hosts many sandboxes
/// (`doc/decisions/0010`) and they all read the same table, so granting `fs` to
/// one granted it to every sandbox in the process -- including ones made before
/// the grant, and ones the host never lent anything at all.
///
/// `0022`'s whole claim is that a program holds a capability because the host
/// gave it one. A test for that has to include a sandbox that was given
/// nothing, because the bug is invisible from the sandbox that WAS given
/// something.
#[test]
fn a_grant_reaches_one_sandbox_and_not_its_neighbours() {
    let img = image();

    let lent = img.sandbox().unwrap();
    lent.grant("fs");
    let ungranted = img.sandbox().unwrap();

    let seen = |s: &flint::Sandbox| -> String {
        match s.call_blocking("app/caps", &[]) {
            Ok(v) => v.to_string(),
            Err(e) => panic!("app/caps failed: {e}"),
        }
    };

    assert!(seen(&lent).contains("fs"), "the sandbox that was lent fs should see it");
    assert_eq!(seen(&ungranted), "[]", "a sandbox lent nothing must see nothing");

    // And in the other order, because a global table would also leak backwards.
    let later = img.sandbox().unwrap();
    assert_eq!(seen(&later), "[]", "a sandbox made after the grant must see nothing");
}

// --- drivers (`doc/decisions/0028`) -----------------------------------------

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
