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
(defn boom [] (throw (ex-info "deliberate" {:a 1})))
(defn main [args] (str "main saw " (pr-str args)))
"#;

fn image() -> flint::Image {
    let compiler = Compiler::embedded().expect("the compiler is embedded");
    compiler
        .compile(Compile {
            resolve: &|ns: &str| if ns == "app" { Some(APP.to_string()) } else { None },
            fn_name: "app/main",
            exports: &["app/greet", "app/tally", "app/echo", "app/boom", "app/spin"],
            meta: vec![("capabilities".into(), Value::Vector(vec![Value::str("fs")]))],
            ..Default::default()
        })
        .expect("it compiles")
}

#[test]
fn compile_then_call_by_name() {
    let img = image();
    assert!(img.wasm.len() > 100_000, "the artifact is a module");
    let mut sandbox = img.sandbox().expect("it instantiates");
    assert_eq!(
        sandbox.call("app/greet", &[Value::str("flint")]).unwrap(),
        Value::str("HELLO FLINT")
    );
}

/// A sandbox serves MANY calls and they share its state -- initialisers run
/// once, not per call, which is what makes instantiate-once-call-per-request
/// work at all.
#[test]
fn a_sandbox_keeps_its_state_and_a_fresh_one_does_not() {
    let img = image();
    let mut a = img.sandbox().unwrap();
    a.call("app/tally", &[]).unwrap();
    assert_eq!(a.call("app/tally", &[]).unwrap(), Value::Int(2));
    let mut b = img.sandbox().unwrap();
    assert_eq!(b.call("app/tally", &[]).unwrap(), Value::Int(1));
}

/// Anything the codec carries can be passed and returned, and comes back as
/// what it was rather than as an approximation of it.
#[test]
fn values_survive_a_call() {
    let img = image();
    let mut s = img.sandbox().unwrap();
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
        let out = s.call("app/echo", &[v.clone()]).unwrap();
        assert_eq!(v, out, "a value did not survive a call");
    }
}

/// A failure arrives as a failure, with what went wrong in it.
#[test]
fn a_thrown_error_reaches_the_caller() {
    let img = image();
    let mut s = img.sandbox().unwrap();
    match s.call("app/boom", &[]) {
        Err(flint::Error::Call { message, .. }) => {
            assert!(message.contains("deliberate"), "the message should say what: {message}");
        }
        other => panic!("expected a call error, got {other:?}"),
    }
    match s.call("app/nope", &[]) {
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

    let mut idle = img.sandbox().unwrap();
    idle.call("app/echo", &[Value::Int(1)]).unwrap();
    assert_eq!(idle.gas(), 0, "an unbudgeted sandbox does not count, by design");

    let mut s = img.sandbox().unwrap();
    s.set_step_limit(50_000_000);
    s.call("app/echo", &[Value::Int(1)]).unwrap();
    let first = s.gas();
    assert!(first > 0, "a budgeted sandbox counts");
    s.call("app/echo", &[Value::Int(1)]).unwrap();
    assert!(s.gas() > first, "and keeps counting across calls");
}

/// The limit is a limit: a program that runs past it is stopped rather than
/// finished, and the caller is told which it was.
#[test]
fn a_step_limit_stops_a_program() {
    let img = image();
    let mut s = img.sandbox().unwrap();
    s.set_step_limit(2_000);
    match s.call("app/spin", &[]) {
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
