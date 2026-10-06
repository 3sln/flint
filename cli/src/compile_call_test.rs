//! THE COMPILE AS A CALL, with namespaces asked for over a port the host passed
//! in (`DECISIONS.md#namespaces-over-the-system-port`, migration step 2).
//!
//! In the CLI crate because this is where the compiler, its host loop and the
//! embedded standard library are all linked. Driven through `serve::Host`, the
//! same loop `flint run` uses: the compile is an ordinary `{:op :call}` on a
//! bound port, and the resolver is a port this host minted and handed over as
//! an argument -- holding it is the compiler's only way to ask.
//!
//! What is asserted, and against what:
//!
//! * every top-level `corpus/*.cljc`, `:to :llvm` plain and `:optimize [perf]`,
//!   compiles to BYTE-IDENTICAL IR through the call and through the EDN-spec
//!   path the CLI uses today (`compile_llvm`'s `build_spec_split` +
//!   `compile_split`), in the same binary;
//! * the compiler asks for exactly the namespaces it reached, each once, in
//!   sorted waves, fewer waves than namespaces;
//! * a namespace the resolver does not answer is a `:missing` error positioned
//!   at the require that named it -- with a control that answers it and
//!   compiles, so a failure for any other reason cannot pass for this one;
//! * an answer of the wrong shape is a `:resolver` error, not a hang.
//!
//! The corpus sweep is the slow half (four compiles per program).
//! `FLINT_COMPILE_CALL_ONLY=nbody` narrows it to one program while iterating.

use crate::policy::Policy;
use crate::resolve::Answers;
use crate::serve::Host;
use crate::{build_spec_split, compile_split, load_compiler, parse_slots, SLOTS};
use flint_rt::codec::{self, Val, Wire};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

const ID: i64 = 7;

fn kw(s: &str) -> Val {
    Val::Keyword(None, s.into())
}

fn sym(s: &str) -> Val {
    match s.split_once('/') {
        Some((n, m)) => Val::Symbol(Some(n.into()), m.into()),
        None => Val::Symbol(None, s.into()),
    }
}

fn sym_text(v: &Val) -> String {
    match v {
        Val::Symbol(Some(n), m) => format!("{n}/{m}"),
        Val::Symbol(None, m) => m.clone(),
        other => panic!("a namespace request named {other:?}, not a symbol"),
    }
}

/// The request `compile_llvm` builds as EDN, as DATA: the same entry, slot map,
/// `:aot`, and the reader features `strip_checks` implies.
fn llvm_request(entry: &str, aot: bool) -> Val {
    let slots = parse_slots(SLOTS).unwrap();
    let mut m = vec![
        (kw("id"), Val::Int(ID)),
        (kw("entry"), sym(entry)),
        (kw("target"), kw("llvm")),
        (kw("slots"), Val::Map(slots.iter().map(|(k, v)| (Val::Str(k.clone()), Val::Int(*v as i64))).collect())),
    ];
    if aot {
        m.push((kw("aot"), Val::Bool(true)));
        m.push((kw("features"), Val::Set(vec![kw("flint"), Val::Keyword(Some("flint".into()), "nested".into())])));
    }
    Val::Map(m)
}

/// `:target :image` with the slot map, as the CLI's `project` mode is given
/// it: an image compiled with no builtins at all cannot even run a macro.
fn image_request(entry: &str) -> Val {
    let slots = parse_slots(SLOTS).unwrap();
    Val::Map(vec![(kw("id"), Val::Int(ID)), (kw("entry"), sym(entry)), (kw("target"), kw("image")),
                  (kw("slots"), Val::Map(slots.iter().map(|(k, v)| (Val::Str(k.clone()), Val::Int(*v as i64))).collect()))])
}

struct Outcome {
    result: Val,
    waves: Vec<Vec<String>>,
}

impl Outcome {
    fn artifact(&self) -> &[u8] {
        match self.result.get("artifact") {
            Some(Val::Bytes(b)) => b,
            _ => panic!("no artifact; the compile answered {:?}", self.errors()),
        }
    }
    fn errors(&self) -> Vec<Val> {
        self.result.get("errors").and_then(|e| e.as_slice()).map(|s| s.to_vec()).unwrap_or_default()
    }
    fn reached(&self) -> Vec<String> {
        self.result
            .get("reached")
            .and_then(|r| r.as_slice())
            .unwrap_or(&[])
            .iter()
            .map(|e| sym_text(e.get("ns").expect("a :reached entry with no :ns")))
            .collect()
    }
}

/// Call `flint.selfhost/compile` with `request` and a resolver port, answering
/// each wave with `answer` (one element per wanted name) -- or with `whole`'s
/// value for the whole wave when it is given, which is how a malformed answer
/// is made.
fn compile_call(request: Val, answer: &dyn Fn(&str) -> Val, whole: Option<Val>) -> Outcome {
    let mut p = load_compiler().unwrap();
    let mut host = Host::new(Policy::default());
    let caller = host.caller(&mut p).unwrap();
    let rport = host.mint_port();
    let mut waves: Vec<Vec<String>> = Vec::new();
    let mut serve = |port: u32, payload: &[u8]| -> Option<Vec<u8>> {
        if port != rport {
            return None;
        }
        let m = codec::parse(payload).expect("a namespace request that does not decode");
        assert_eq!(m.get("id"), Some(&Val::Int(ID)), "the request did not echo the compile's :id");
        let want: Vec<String> =
            m.get("want").and_then(|w| w.as_slice()).expect("a request with no :want").iter().map(sym_text).collect();
        let ans = match &whole {
            Some(v) => v.clone(),
            None => Val::Vector(want.iter().map(|n| answer(n)).collect()),
        };
        waves.push(want);
        let mut w = Wire::new();
        ans.write(&mut w);
        Some(w.done())
    };
    let bytes = host
        .call_serving(&mut p, &caller, "flint.selfhost/compile", &[request, Val::Port(rport)], &mut serve)
        .unwrap_or_else(|e| panic!("the compile call failed: {e}"));
    Outcome { result: codec::parse(&bytes).unwrap(), waves }
}

/// The IR the CLI's own path writes today, `compile_llvm` minus the file -- or
/// the error it stops with, since two corpus programs are JVM-only by
/// declaration and flint refuses them.
fn old_llvm(srcs: &[PathBuf], entry: &str, aot: bool) -> Result<Vec<u8>, String> {
    let slots = parse_slots(SLOTS).unwrap();
    let (spec, files) =
        build_spec_split(srcs, entry, &slots, aot, false, &[], None, &[], aot, None).unwrap();
    let mut p = load_compiler().unwrap();
    let r = compile_split(&mut p, &["llvm", &spec], &files);
    if r.code != 0 {
        return Err(r.out);
    }
    assert!(r.out.starts_with("; flint program, as LLVM IR."), "the EDN path answered no IR for {entry}: {}",
            &r.out[..r.out.len().min(400)]);
    Ok(r.out.into_bytes())
}

fn corpus() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../corpus")
}

fn first_difference(a: &[u8], b: &[u8]) -> String {
    let at = a.iter().zip(b).position(|(x, y)| x != y).unwrap_or(a.len().min(b.len()));
    let show = |s: &[u8]| String::from_utf8_lossy(&s[at.saturating_sub(80)..(at + 80).min(s.len())]).to_string();
    format!("{} vs {} bytes, first difference at {at}:\n--- EDN path\n{}\n--- call\n{}", a.len(), b.len(),
            show(a), show(b))
}

fn assert_waves(name: &str, o: &Outcome) {
    let mut asked = BTreeSet::new();
    for w in &o.waves {
        let mut sorted = w.clone();
        sorted.sort();
        assert_eq!(w, &sorted, "{name}: a wave was not sorted by printed name");
        for n in w {
            assert!(asked.insert(n.clone()), "{name}: {n} was asked for twice");
        }
    }
    let reached: Vec<String> = o.reached();
    let flat: Vec<String> = o.waves.iter().flatten().cloned().collect();
    // EXACTLY the reached namespaces: nothing asked that was not then part of
    // the program, in request order.
    assert_eq!(flat, reached, "{name}: what was asked is not what was reached");
    assert!(o.waves.len() < reached.len(),
            "{name}: {} waves for {} namespaces -- the requests are not batched", o.waves.len(), reached.len());
}

#[test]
fn the_compile_call_builds_the_same_ir_as_the_edn_path_and_asks_each_namespace_once() {
    let only = std::env::var("FLINT_COMPILE_CALL_ONLY").ok();
    let srcs = vec![corpus()];
    let answers = Answers::new(&srcs, &[], None).unwrap();
    let mut names: Vec<String> = std::fs::read_dir(corpus())
        .unwrap()
        .filter_map(|e| {
            let p = e.unwrap().path();
            (p.extension().is_some_and(|x| x == "cljc"))
                .then(|| p.file_stem().unwrap().to_string_lossy().replace('_', "-"))
        })
        .filter(|n| only.as_deref().is_none_or(|o| o == n))
        .collect();
    names.sort();
    assert!(!names.is_empty(), "no corpus programs matched");
    let (mut identical, mut refused) = (0, 0);
    for name in &names {
        let entry = format!("{name}/main");
        for aot in [false, true] {
            let label = format!("{name}{}", if aot { " :optimize [perf]" } else { "" });
            let new = compile_call(llvm_request(&entry, aot), &|n| answers.answer(n), None);
            match old_llvm(&srcs, &entry, aot) {
                Ok(old) => {
                    assert!(new.errors().is_empty(), "{label}: {:?}", new.errors());
                    assert!(old == new.artifact(), "{label}: {}", first_difference(&old, new.artifact()));
                    assert_waves(&label, &new);
                    identical += 1;
                }
                // A REFUSED PROGRAM IS REFUSED THE SAME WAY: one `:compile`
                // error whose message is the one the EDN path stopped with.
                Err(old) => {
                    let errors = new.errors();
                    assert_eq!(errors.len(), 1, "{label}: the EDN path refused it ({old}), the call answered {errors:?}");
                    assert_eq!(errors[0].get("kind"), Some(&kw("compile")), "{label}: {errors:?}");
                    let msg = errors[0].get("message").and_then(|m| m.as_str()).unwrap_or("");
                    assert!(!msg.is_empty() && old.contains(msg),
                            "{label}: the EDN path said {old:?}, the call said {msg:?}");
                    refused += 1;
                }
            }
        }
    }
    eprintln!("compile call: {identical} corpus compiles byte-identical to the EDN path, \
               {refused} refused by both with the same message");
    assert!(identical > 0);
}

/// A one-file project in a fresh directory, keyed as the CLI keys a source root.
fn project(tag: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("flint-compile-call-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    for (path, body) in files {
        let p = dir.join(path);
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(&p, body).unwrap();
    }
    dir
}

const APP: &str = "(ns app\n  (:require [clojure.string :as s]\n            [nope.gone :as g]))\n(defn main [_] (g/f))\n";

#[test]
fn a_namespace_the_resolver_does_not_answer_is_a_missing_error_at_its_require() {
    let dir = project("missing", &[("app.cljc", APP)]);
    let answers = Answers::new(&[dir.clone()], &[], None).unwrap();
    let o = compile_call(image_request("app/main"), &|n| answers.answer(n), None);
    let errors = o.errors();
    assert_eq!(errors.len(), 1, "{errors:?}");
    let e = &errors[0];
    assert_eq!(e.get("kind"), Some(&kw("missing")), "{e:?}");
    assert_eq!(e.get("ns"), Some(&sym("nope.gone")), "{e:?}");
    assert_eq!(e.get("required-by"), Some(&Val::Vector(vec![sym("app")])), "{e:?}");
    assert_eq!(e.get("file").and_then(|f| f.as_str()), Some("app.cljc"), "{e:?}");
    // `[nope.gone :as g]` opens at line 3, column 13 (1-based, as the reader counts).
    assert_eq!(e.get("line"), Some(&Val::Int(3)), "{e:?}");
    assert_eq!(e.get("column"), Some(&Val::Int(13)), "{e:?}");
    assert!(o.result.get("artifact").is_none());
    // Asked once, even though it was not found.
    assert_eq!(o.waves.iter().flatten().filter(|n| *n == "nope.gone").count(), 1);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn the_control_answers_that_namespace_and_compiles() {
    let dir = project("present", &[("app.cljc", APP), ("nope/gone.cljc", "(ns nope.gone)\n(defn f [] \"ok\")\n")]);
    let answers = Answers::new(&[dir.clone()], &[], None).unwrap();
    let o = compile_call(image_request("app/main"), &|n| answers.answer(n), None);
    assert!(o.errors().is_empty(), "{:?}", o.errors());
    assert!(o.artifact().starts_with(b"FLIN"), "the image does not start with its magic");
    assert!(o.reached().iter().any(|n| n == "nope.gone"));
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn an_answer_of_the_wrong_shape_is_a_resolver_error_not_a_hang() {
    let dir = project("shape", &[("app.cljc", "(ns app)\n(defn main [_] \"x\")\n")]);
    let o = compile_call(image_request("app/main"), &|_| Val::Nil, Some(Val::Vector(vec![])));
    let errors = o.errors();
    assert_eq!(errors.len(), 1, "{errors:?}");
    assert_eq!(errors[0].get("kind"), Some(&kw("resolver")), "{errors:?}");
    assert_eq!(o.waves.len(), 1, "it asked again after a malformed answer");
    let _ = std::fs::remove_dir_all(Path::new(&dir));
}

// ------------------------------------------------- shaped virtual namespaces
//
// A `:virtual` answer may carry a SHAPE, `:vars [{:name f :arities [..]} ..]`
// (`DECISIONS.md#namespaces-over-the-system-port`, "shaped virtual
// namespaces"). Present, the compiler refuses a var the shape does not declare
// and a call with an arity it does not declare; absent, it treats the namespace
// exactly as before -- any name compiles, to a call over the port.

fn shaped(vars: Option<Val>) -> Val {
    let mut m = vec![(kw("virtual"), Val::Bool(true)), (kw("workspace"), sym("test/shapes"))];
    if let Some(v) = vars {
        m.push((kw("vars"), v));
    }
    Val::Map(m)
}

fn hello_shape() -> Val {
    Val::Vector(vec![Val::Map(vec![(kw("name"), sym("hello")), (kw("arities"), Val::Vector(vec![Val::Int(1)]))])])
}

/// Compile `app` whose `main` body is `body`, against `shaped.ns` answered as `v`.
fn with_shape(tag: &str, body: &str, v: Val) -> Outcome {
    let app = format!("(ns app (:require [shaped.ns :as s]))\n(defn main [_] {body})\n");
    let dir = project(tag, &[("app.cljc", &app)]);
    let answers = Answers::new(&[dir.clone()], &[], None).unwrap();
    let o = compile_call(image_request("app/main"),
                         &|n| if n == "shaped.ns" { v.clone() } else { answers.answer(n) }, None);
    let _ = std::fs::remove_dir_all(&dir);
    o
}

fn one_compile_error(o: &Outcome, says: &str) -> Val {
    let errors = o.errors();
    assert_eq!(errors.len(), 1, "{errors:?}");
    let e = errors[0].clone();
    assert_eq!(e.get("kind"), Some(&kw("compile")), "{e:?}");
    let msg = e.get("message").and_then(|m| m.as_str()).unwrap_or("");
    assert!(msg.contains(says), "expected {says:?} in {msg:?}");
    // Positioned at the call on line 2, `(defn main [_] (s/..` -- the call
    // opens at column 16 and its head at 17; either is the right line to read.
    assert_eq!(e.get("line"), Some(&Val::Int(2)), "{e:?}");
    assert!(matches!(e.get("column"), Some(Val::Int(16 | 17))), "{e:?}");
    e
}

#[test]
fn a_shaped_virtual_namespace_compiles_a_declared_var() {
    let o = with_shape("shape-ok", "(s/hello 1)", shaped(Some(hello_shape())));
    assert!(o.errors().is_empty(), "{:?}", o.errors());
    assert!(o.artifact().starts_with(b"FLIN"));
}

#[test]
fn a_shaped_virtual_namespace_refuses_an_undeclared_var_where_it_is_named() {
    let o = with_shape("shape-undeclared", "(s/nope 1)", shaped(Some(hello_shape())));
    one_compile_error(&o, "does not hold nope");
}

#[test]
fn a_shaped_virtual_namespace_refuses_an_undeclared_arity_where_it_is_called() {
    let o = with_shape("shape-arity", "(s/hello 1 2)", shaped(Some(hello_shape())));
    one_compile_error(&o, "called with 2 arguments");
}

#[test]
fn the_control_an_unshaped_virtual_namespace_compiles_any_name() {
    let o = with_shape("shape-none", "(s/nope 1 2)", shaped(None));
    assert!(o.errors().is_empty(), "{:?}", o.errors());
    assert!(o.artifact().starts_with(b"FLIN"));
}
