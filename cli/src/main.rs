//! flint, as a single binary.
//!
//! Everything is embedded: the compiler, two runtime modules, their builtin
//! slot maps, and the standard library sources. There is nothing to install
//! and nothing to find on disk -- no babashka, no JVM, no Rust toolchain and
//! no linker (`DECISIONS.md#no-runtime-linking`).
//!
//! The compiler runs NATIVELY. flint's runtime is Rust, so it already compiles
//! through LLVM for every target cargo does -- the collector, the interpreter
//! and every builtin are the same code the wasm module is built from. So this
//! binary carries the compiler as a bytecode IMAGE and runs it directly, with
//! no wasm engine in the binary at all.
//!
//! It is worth 2.7 s against 15.6 s on the same compile (`DECISIONS.md#other-hosts`).
//! The output is still wasm: what changed is what the compiler runs ON.

mod deps;
mod depscmd;
mod pod;
mod policy;
mod script;
mod serve;
mod sys;

use anyhow::{bail, Context, Result};
use flint_rt::native::Program;
use std::{collections::BTreeMap, fs, path::{Path, PathBuf}};

include!(concat!(env!("OUT_DIR"), "/stdlib.rs"));

/// The compiler, as bytecode rather than as a wasm module: this binary runs it
/// natively, so a module would only be a wasm engine worth of indirection.
///
/// The format is an implementation detail. It is embedded here and never
/// written out --  emits wasm, and  keeps this
/// entirely inside the process.
static COMPILER: &[u8] = include_bytes!("../../dist/flintc.bytecode");
static RUNTIME: &[u8] = include_bytes!("../../dist/flint-runtime.wasm");
static RUNTIME_AOT: &[u8] = include_bytes!("../../dist/flint-runtime-aot.wasm");
static SLOTS: &str = include_str!("../../dist/slots.json");
static SLOTS_AOT: &str = include_str!("../../dist/slots-aot.json");

pub(crate) const VERSION: &str = env!("CARGO_PKG_VERSION");

// --- EDN, written rather than depended on ----------------------------------
//
// The spec handed to the compiler is a map of strings to strings and a set of
// strings. Escaping that correctly is fifteen lines; a serialiser crate for it
// would be a dependency in a binary whose whole point is having none.

fn edn_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

/// `{"name": 12, ...}` -> the pairs. A hand-rolled reader for the one shape
/// `bin/build-dist` writes, for the same reason as above.
fn parse_slots(text: &str) -> Result<BTreeMap<String, u32>> {
    let mut out = BTreeMap::new();
    for line in text.lines() {
        let line = line.trim().trim_end_matches(',');
        let Some((k, v)) = line.split_once(':') else { continue };
        let k = k.trim();
        if !k.starts_with('"') || !k.ends_with('"') {
            continue;
        }
        let name = k[1..k.len() - 1].replace("\\\"", "\"");
        let slot: u32 = v.trim().parse().with_context(|| format!("bad slot for {name}"))?;
        out.insert(name, slot);
    }
    if out.is_empty() {
        bail!("the embedded slot map is empty");
    }
    Ok(out)
}

fn base64(bytes: &[u8]) -> String {
    const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for c in bytes.chunks(3) {
        let b = [c[0], *c.get(1).unwrap_or(&0), *c.get(2).unwrap_or(&0)];
        let t = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(A[(t >> 18) as usize & 63] as char);
        out.push(A[(t >> 12) as usize & 63] as char);
        out.push(if c.len() > 1 { A[(t >> 6) as usize & 63] as char } else { '=' });
        out.push(if c.len() > 2 { A[t as usize & 63] as char } else { '=' });
    }
    out
}

fn base64_decode(text: &str) -> Result<Vec<u8>> {
    const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut idx = [255u8; 256];
    for (i, c) in A.iter().enumerate() {
        idx[*c as usize] = i as u8;
    }
    let clean: Vec<u8> = text.bytes().filter(|b| idx[*b as usize] != 255).collect();
    let mut out = Vec::with_capacity(clean.len() / 4 * 3);
    for c in clean.chunks(4) {
        if c.len() < 2 {
            break;
        }
        let v: Vec<u32> = c.iter().map(|b| idx[*b as usize] as u32).collect();
        let t = (v[0] << 18) | (v[1] << 12) | (v.get(2).copied().unwrap_or(0) << 6)
            | v.get(3).copied().unwrap_or(0);
        out.push((t >> 16) as u8);
        if c.len() > 2 {
            out.push((t >> 8) as u8);
        }
        if c.len() > 3 {
            out.push(t as u8);
        }
    }
    Ok(out)
}

// --- sources ---------------------------------------------------------------

/// Every `.cljc` under `dir`, keyed by its path relative to `dir` -- which is
/// exactly how a namespace maps to a file, so the compiler can find them.
fn read_sources(dir: &Path, prefix: &str, out: &mut BTreeMap<String, String>) -> Result<()> {
    let mut names: Vec<_> = fs::read_dir(dir)
        .with_context(|| format!("cannot read {}", dir.display()))?
        .filter_map(|e| e.ok())
        .collect();
    names.sort_by_key(|e| e.path());
    for e in names {
        let p = e.path();
        let name = p.file_name().unwrap().to_string_lossy().to_string();
        let rel = if prefix.is_empty() { name.clone() } else { format!("{prefix}/{name}") };
        if p.is_dir() {
            read_sources(&p, &rel, out)?;
        // `flint.project/source-extensions` is the same list, and the one that
        // decides which file WINS for a namespace. This side only decides what
        // is worth reading off the disk, so order does not matter here and a
        // superset would merely cost a read.
        } else if rel.ends_with(".fln") || rel.ends_with(".cljc") || rel.ends_with(".clj") {
            out.insert(rel, fs::read_to_string(&p)?);
        }
    }
    Ok(())
}

/// The source extension `name` ends in, or `.fln` when it ends in none.
///
/// A shebang script is `~/bin/greet` with no extension at all, and it is by
/// definition not portable -- it carries a `#!` line and an entry point, and
/// nothing outside flint reads either. `.fln` is therefore the honest default
/// rather than a convenience (`DECISIONS.md#dialects-and-preludes`).
fn source_ext(name: &str) -> &'static str {
    for e in [".fln", ".cljc", ".clj"] {
        if name.ends_with(e) {
            // The list is `flint.project/source-extensions`; this returns a
            // `'static` copy of the match so the key can outlive the name.
            return match e {
                ".cljc" => ".cljc",
                ".clj" => ".clj",
                _ => ".fln",
            };
        }
    }
    ".fln"
}

// --- the commands ----------------------------------------------------------

/// The EDN the compiler takes: the sources, the entry, and what the runtime
/// carries. Shared by `compile` and `run` so the two cannot drift.
fn build_spec(srcs: &[PathBuf], entry: &str, slots: &BTreeMap<String, u32>,
              aot: bool, shake: bool, meta: &[(String, String)],
              roots: Option<&[String]>, strip_checks: bool,
              features: Option<&[String]>) -> Result<String> {
    build_spec_with(srcs, entry, slots, aot, shake, meta, roots, &[], strip_checks, features)
}

/// The same, plus the pod namespaces this build booted.
#[allow(clippy::too_many_arguments)]
/// The `deps.edn` governing a source root: beside it, or one directory up.
///
/// ONE RULE, because there were two. `bin/flint`'s `workspace-of` states it --
/// "read from `deps.edn` beside the root or one directory up" -- and the
/// workspace reader here followed it while `declared_pods` looked only beside
/// the root. On the standard layout (`{:paths ["src"]}` with `deps.edn` at the
/// project root) that meant capabilities were read from the project and pods
/// were not: the same file, governing the same build, with two lookups and one
/// of them wrong.
///
/// Returns the text AND THE DIRECTORY IT CAME FROM, because a relative
/// coordinate in it -- `:pod/path "./demopod"`, `:local/root "../lib"` -- is
/// relative to the file that declares it and not to the source root. Resolving
/// against the root gave `src/./demopod` for a pod sitting beside `deps.edn`
/// one level up, which is a path that exists nowhere.
///
/// Empty text when neither exists.
fn project_deps_edn(dir: &Path) -> (String, PathBuf) {
    let here = dir.join("deps.edn");
    if let Ok(t) = fs::read_to_string(&here) { return (t, dir.to_path_buf()) }
    if let Some(up) = dir.parent() {
        if let Ok(t) = fs::read_to_string(up.join("deps.edn")) {
            return (t, up.to_path_buf());
        }
    }
    (String::new(), dir.to_path_buf())
}

/// One workspace's facts, read from a `deps.edn`.
///
/// The same four keys `bin/flint`'s `workspace-of` reads, and for the same
/// reason: a workspace is one concept, not a set of unrelated lookups that
/// happen to share a file.
///
/// Read with a SCAN rather than an EDN parser, as everywhere else on this side
/// -- the guest owns the format and a second reader of it is a second thing to
/// keep true.
#[derive(Default)]
struct Workspace {
    name: String,
    tags: String,
    prelude: String,
    grants: String,
    guard: String,
}

/// The text between the delimiters of the collection following `key`.
pub(crate) fn edn_block(text: &str, key: &str, open: char, close: char) -> String {
    let Some(at) = text.find(key) else { return String::new() };
    let rest = &text[at + key.len()..];
    let Some(o) = rest.find(open) else { return String::new() };
    let mut depth = 0i32;
    for (i, c) in rest[o..].char_indices() {
        if c == open { depth += 1 } else if c == close {
            depth -= 1;
            if depth == 0 { return rest[o + 1..o + i].trim().to_string() }
        }
    }
    String::new()
}

/// The bare token following `key`.
fn edn_token(text: &str, key: &str) -> String {
    let Some(at) = text.find(key) else { return String::new() };
    text[at + key.len()..]
        .split_whitespace()
        .next()
        .unwrap_or("")
        .trim_end_matches('}')
        .to_string()
}

fn read_workspace(text: &str) -> Workspace {
    Workspace {
        name: edn_token(text, ":flint/workspace"),
        tags: edn_block(text, ":flint/tag-readers", '{', '}'),
        prelude: edn_block(text, ":flint/prelude", '[', ']'),
        grants: edn_block(text, ":flint/capabilities-grant", '[', ']'),
        guard: edn_block(text, ":flint/capabilities-guard", '[', ']'),
    }
}

/// A workspace entry for the spec, or empty when there is nothing to say.
fn workspace_entry(prefix: &str, w: &Workspace, fallback_name: &str) -> String {
    let name = if w.name.is_empty() { fallback_name } else { &w.name };
    if name.is_empty() && w.tags.is_empty() && w.prelude.is_empty()
        && w.grants.is_empty() && w.guard.is_empty() {
        return String::new();
    }
    format!(
        "{{:prefix {} :name {} :tags {{{}}} :prelude [{}] :grants [{}] :guard [{}]}} ",
        edn_string(prefix), name, w.tags, w.prelude, w.grants, w.guard)
}

#[allow(clippy::too_many_arguments)]
fn build_spec_with(srcs: &[PathBuf], entry: &str, slots: &BTreeMap<String, u32>,
                   aot: bool, shake: bool, meta: &[(String, String)],
                   roots: Option<&[String]>,
                   pods: &[(String, Vec<String>)],
                   strip_checks: bool,
                   features: Option<&[String]>) -> Result<String> {
    // `:flint/nested` decides whether `flint.ception` is offered at all. Absent
    // from an explicit set, the namespace is not emitted and a program naming
    // it does not compile (`DECISIONS.md#flint-ception`). Default is ON, so a build
    // that says nothing about features keeps it.
    let nested = features.map_or(true, |f| f.iter().any(|x| x == ":flint/nested"));
    let mut files: BTreeMap<String, String> = BTreeMap::new();
    for (p, body) in STDLIB {
        files.insert((*p).to_string(), (*body).to_string());
    }
    // PER ROOT, so each file can be attributed to the workspace that owns it.
    // Reading them all into one map loses which root a file came from, and the
    // paths here are namespace-derived (`acme/thing.cljc`) with no marker to
    // recover it -- which is why the first version of this could express only
    // one project workspace and a guard BETWEEN two of them never fired.
    let mut owned: Vec<(PathBuf, Vec<String>)> = Vec::new();
    for s in srcs {
        let mut mine: BTreeMap<String, String> = BTreeMap::new();
        if s.is_dir() {
            read_sources(s, "", &mut mine)?;
        } else {
            // A FILE IS KEYED BY THE NAMESPACE IT DECLARES, not by what it is
            // called on disk. The compiler finds a namespace at `ns->path`, so
            // `flint ~/bin/greet` -- and every other single-file source --
            // would otherwise hand over a file the resolver never looks for
            // and report the namespace missing. A file with no readable `ns`
            // keeps its own name, which is what this always did.
            let body = fs::read_to_string(s)?;
            let name = s.file_name().unwrap().to_string_lossy().to_string();
            let key = match script::read_ns(&body) {
                Some(n) => script::ns_key(&n.ns, source_ext(&name)),
                None => name,
            };
            mine.insert(key, body);
        }
        owned.push((s.clone(), mine.keys().cloned().collect()));
        files.extend(mine);
    }
    let mut out = String::from("{:files {");
    for (k, v) in &files {
        out.push_str(&edn_string(k));
        out.push(' ');
        out.push_str(&edn_string(v));
        out.push(' ');
    }
    out.push_str("} :entry ");
    out.push_str(entry);
    // `:roots` overrides "start from the entry namespace", which `test` needs
    // because its entry is `flint.check.registry` -- a namespace the compiler
    // GENERATES from what it found, so no source path contains it and
    // resolving from it reports the entry itself missing.
    if let Some(rs) = roots {
        out.push_str(" :roots [");
        for r in rs {
            out.push_str(r);
            out.push(' ');
        }
        out.push(']');
    }
    // The VIRTUAL namespaces this binary serves (`DECISIONS.md#workspace-capabilities` step 4,
    // `system-namespaces-and-deps`). They have no source, so the resolver has to be told they exist
    // or a `:require` of one is reported missing -- and it has to be told what
    // they HOLD, so an unknown var is a compile error rather than a run-time
    // one. The CLI knows its own surface, so taking it on trust would be
    // choosing the worse of two available answers.
    out.push_str(" :workspaces [");
    // POD namespaces, if any were declared. A pod's surface is discovered by
    // BOOTING it, so this is the one virtual namespace whose var list costs a
    // process -- which is why `workspace-capabilities` made the list optional and why a build
    // that boots is a choice made in the open rather than a default.
    for (ns, vars) in pods {
        out.push_str("{:prefix ");
        out.push_str(&edn_string(&format!("{}/", ns.replace('.', "/"))));
        out.push_str(&format!(" :name {} :virtual true :vars [", "pod/pod"));
        for v in vars {
            // NO ARITIES. A pod's `describe` gives names and metadata, and
            // arities are not always in it -- so this says "unchecked" by
            // omitting the field rather than claiming `[]`, which would read as
            // "takes no arguments" and refuse every real call.
            out.push_str(&format!("{{:name {v}}} "));
        }
        out.push_str("]} ");
    }
    for (ns, vars) in crate::sys::catalogue() {
        // NOT NAMEABLE without the feature. Omitting the workspace is what
        // makes `(:require [flint.ception])` a compile error rather than a run-time
        // refusal -- an artifact built without it cannot reach the SDK however
        // it is later run.
        if ns == "flint.ception" && !nested {
            continue;
        }
        out.push_str("{:prefix ");
        out.push_str(&edn_string(&format!("{}/", ns.replace('.', "/"))));
        out.push_str(" :name flint/sys :virtual true :vars [");
        for (name, arities) in vars {
            out.push_str(&format!("{{:name {name} :arities ["));
            for a in arities {
                out.push_str(&format!("{a} "));
            }
            out.push_str("]} ");
        }
        out.push_str("]} ");
    }
    // THE SOURCE WORKSPACES, after the virtual ones because the first matching
    // prefix wins and `flint/` would otherwise swallow `flint/sys/fs/`.
    //
    // Until this existed the native CLI emitted virtual workspaces ONLY, so
    // every compiled file belonged to the anonymous workspace -- and since the
    // capability guard skips references within one workspace, it never fired.
    // `bin/flint` read `deps.edn` and refused the same program. The binary
    // users run was the one nothing tested, because every test for the guard
    // and for `:flint/tag-readers` drives `bin/flint`.
    let stdlib = read_workspace(STDLIB_DEPS);
    for pre in ["clojure/", "flint/"] {
        out.push_str(&workspace_entry(pre, &stdlib, ""));
    }
    // The PROJECT's own, from `deps.edn` beside a source root or one directory
    // up -- the rule `bin/flint` states and follows. A catch-all prefix,
    // because this side keys files by their namespace-derived path with no
    // marker for which root they came from; a project whose roots carry
    // DIFFERENT `deps.edn` files therefore gets the first one found, which is
    // narrower than `bin/flint` and is recorded rather than hidden.
    for (sdir, paths) in &owned {
        // A SOURCE THAT IS A FILE INHERITS NOTHING FROM THE DIRECTORY IT SITS
        // IN. This used to take the `deps.edn` beside it, which for a
        // standalone script is the whole hazard the feature exists to avoid:
        // dropping `greet` into a working tree would have silently handed it
        // that project's reader tags, its prelude and -- worse -- its
        // capability grants, none of which the script's author wrote or saw
        // (`DECISIONS.md#standalone-scripts`). A file names its own workspace
        // or has none.
        if !sdir.is_dir() {
            continue;
        }
        let dir = sdir.clone();
        let (text, _) = project_deps_edn(&dir);
        if text.trim().is_empty() { continue }
        let w = read_workspace(&text);
        // ONE ENTRY PER FILE, with the file's own path as the prefix. Prefix
        // matching is `starts-with?`, so a full path matches exactly that file
        // -- which is how a root whose files interleave with another root's in
        // one flat namespace-derived space still gets its own workspace.
        for path in paths {
            let entry = workspace_entry(path, &w, &edn_string(&dir.display().to_string()));
            if !entry.is_empty() { out.push_str(&entry) }
        }
    }
    out.push_str("]");
    // THE READER'S FEATURE SET, said rather than defaulted.
    //
    // Emitting nothing left the guest on `default-features`, which carries
    // `:flint/check` -- so a release module built here contained its own test
    // code and a failing check threw from it, whatever `:optimize` said.
    //
    // Said only when it differs from the default, so an ordinary build's spec
    // is byte-identical to what it was.
    // Said only when it differs from the default, so an ordinary build's spec
    // is byte-identical to what it was.
    //
    // THE STRIP-CHECKS SET KEEPS `:flint/nested`. It used to be `#{:flint}`,
    // which was the whole default minus checks -- but the default gained
    // `:flint/nested`, and emitting the old literal would have turned the SDK
    // off in every `:optimize [perf]` build as a side effect of dropping
    // checks. Two features, two decisions.
    match features {
        Some(f) => {
            out.push_str(" :features #{");
            for x in f {
                out.push_str(x);
                out.push(' ');
            }
            out.push('}');
        }
        None if strip_checks => out.push_str(" :features #{:flint :flint/nested}"),
        None => {}
    }
    out.push_str(" :builtins #{");
    for k in slots.keys() {
        out.push_str(&edn_string(k));
        out.push(' ');
    }
    out.push_str("} :slots {");
    for (k, v) in slots {
        out.push_str(&edn_string(k));
        out.push_str(&format!(" {v} "));
    }
    out.push('}');
    if aot {
        out.push_str(" :aot true");
    }
    if shake {
        out.push_str(" :shake true");
    }
    if !meta.is_empty() {
        // Arbitrary, and never read: flint carries what the host put there
        // (`DECISIONS.md#structured-ports`). Declared capabilities live here by
        // convention, and the convention belongs to whoever reads them.
        out.push_str(" :meta {");
        for (k, v) in meta {
            out.push_str(&edn_string(k));
            out.push(' ');
            out.push_str(&edn_string(v));
            out.push(' ');
        }
        out.push('}');
    }
    out.push('}');
    if std::env::var("FLINT_TRACE_SPEC").is_ok() {
        if let Some(i) = out.find(":workspaces") {
            eprintln!("[spec] {}", &out[i..(i + 400).min(out.len())]);
        }
    }
    Ok(out)
}

/// What to optimise for. An ORDERED PREFERENCE, not a switch.
///
/// `:optimize [:perf]` compiles every arity ahead of time; `:optimize [:size]`
/// is a pure interpreter. It is a list because there will be more axes than
/// two and a boolean cannot grow into them, and it is ordered because the
/// tokens are preferences: the first one this build understands decides, and
/// the rest are what the caller would have wanted otherwise.
///
/// **Unrecognised tokens are ignored.** That is what makes the list safe to
/// write against a newer flint than the one reading it -- asking for something
/// this build has never heard of gets you its best effort, not a refusal.
/// Whether `#?(:flint/check ...)` is read out of the source.
///
/// `:checks` WINS OVER `:optimize`, in both directions. `:optimize [perf]`
/// removes checks (`DECISIONS.md#checks`), and that is the right default for a
/// release build -- but tying the two together makes them inseparable, and they
/// are separate questions. `:checks false` drops them from an interpreted
/// build; `:checks true` keeps them in a compiled one, which is what lets two
/// optimisation levels be compared while reading the SAME source.
fn strip_checks(optimize: &[String], checks: Option<bool>) -> bool {
    match checks {
        Some(v) => !v,
        None => wants_aot(optimize),
    }
}

fn wants_aot(optimize: &[String]) -> bool {
    optimize
        .iter()
        .find_map(|t| match t.trim_start_matches(':') {
            "perf" => Some(true),
            "size" => Some(false),
            _ => None,
        })
        .unwrap_or(false)
}

/// Compile from SOURCE THE CALLER SUPPLIED, to a loadable image.
///
/// The IO-free half of `compile` (`DECISIONS.md#flint-ception`). There is no path
/// here and no output file: the caller hands over namespace-to-source text and
/// gets bytes back, so `sdk` confers no reach into the filesystem at all. A
/// caller that wants to compile a project on disk reads it with its own `fs`
/// grant first, and the two capabilities compose instead of one implying the
/// other.
///
/// The sources are written to a PRIVATE temporary directory and the ordinary
/// spec builder runs over that. That is the host touching its own disk, not the
/// guest reaching anything: no path here is caller-controlled, the directory is
/// removed on the way out, and reusing `build_spec_with` keeps one spec emitter
/// rather than a second that agrees with it until it does not.
pub(crate) fn sdk_compile(sources: &[(String, String)], entry: &str, exports: &[String],
                          optimize: &[String], shake: bool, checks: Option<bool>,
                          meta: &[(String, String)]) -> Result<Vec<u8>> {
    let dir = std::env::temp_dir().join(format!("flint-ception-{}-{}", std::process::id(), sources.len()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir)?;
    for (ns, body) in sources {
        let rel = ns_to_path(ns);
        let path = dir.join(&rel);
        if let Some(p) = path.parent() {
            fs::create_dir_all(p)?;
        }
        fs::write(&path, body)?;
    }
    let slots = parse_slots(SLOTS)?;
    let strip = strip_checks(optimize, checks);
    let spec = build_spec_with(&[dir.clone()], entry, &slots, false, shake, meta, None, &[], strip,
                               None);
    let spec = match spec {
        Ok(s) => s,
        Err(e) => { let _ = fs::remove_dir_all(&dir); return Err(e); }
    };
    // `:exports` KEEPS A FUNCTION CALLABLE THROUGH THE SHAKE, and is not
    // `:roots` -- roots are namespaces to resolve from, so a qualified function
    // name there reports itself missing. Only reachable code ships, and a
    // function nobody calls from the entry is exactly the one a host wants to
    // call: without this `(call box "guest/greet" ..)` answers "this image has
    // no `guest/greet`" for a function whose source is right there.
    //
    // Appended rather than threaded through `build_spec_with`, which already
    // takes nine arguments. The spec is an EDN MAP, so a key's position in it
    // carries no meaning -- unlike `:features`, which had to go outside the
    // `:workspaces` vector it was being written into.
    let spec = if exports.is_empty() {
        spec
    } else {
        debug_assert!(spec.ends_with('}'));
        let mut t = spec[..spec.len() - 1].to_string();
        t.push_str(" :exports [");
        t.push_str(&exports.join(" "));
        t.push_str("]}");
        t
    };
    let out = (|| -> Result<Vec<u8>> {
        let mut c = load_compiler()?;
        let r = c.run(&["project", &spec]);
        if r.code != 0 {
            bail!("{}", r.out.trim());
        }
        if let Some(rest) = r.out.strip_prefix("!missing") {
            bail!("no source for{}\nthe sources map has to carry every namespace the entry requires",
                  rest.replace('\n', " "));
        }
        if let Some(rest) = r.out.strip_prefix("!refused") {
            bail!("{}", rest.trim());
        }
        Ok(base64_decode(r.out.split('\n').next().unwrap_or(""))?)
    })();
    let _ = fs::remove_dir_all(&dir);
    out
}

/// The "no source for ..." sentence, plus WHY when the reason is known.
///
/// A namespace the CLI deliberately withheld reads exactly like one the author
/// misspelled, and the compiler cannot tell them apart -- it was never offered
/// either. Only this side knows the feature was off, so only this side can say
/// so (`DECISIONS.md#flint-ception`).
fn missing_message(missing: &str, features: Option<&[String]>) -> String {
    let mut m = format!(
        "no source for{}\nevery namespace a program requires has to be on the source path",
        missing.replace('\n', " ")
    );
    let nested_off = features.is_some_and(|f| !f.iter().any(|x| x == ":flint/nested"));
    if nested_off && missing.contains("flint.ception") {
        m.push_str(
            "\n\n`flint.ception` is not missing -- this build turned it off. `:features` was given \
             without `:flint/nested`, which is what makes the SDK nameable. Add it, or drop \
             `:features` to get the default set.",
        );
    }
    m
}

/// `my.ns` -> `my/ns.cljc`, the path the spec keys a file by.
///
/// The same mapping `flint.project/ns->path` makes, and it has to stay the
/// same: the compiler finds a namespace by the path its name implies.
fn ns_to_path(ns: &str) -> String {
    let mut out = String::with_capacity(ns.len() + 5);
    for c in ns.chars() {
        match c {
            '.' => out.push('/'),
            '-' => out.push('_'),
            c => out.push(c),
        }
    }
    out.push_str(".cljc");
    out
}

/// The embedded compiler, loaded with every unit this binary carries.
///
/// `Program::load` is not enough any more. The compiler image now contains
/// `flint.system` -- the control plane is a root in every program
/// (`DECISIONS.md#bridges-are-the-only-door`), and the compiler is a program --
/// so the image needs `flint/spawn` and the rest of the concurrency unit. A
/// plain load answered "this runtime does not carry the builtin `flint/spawn`,
/// which the image needs", which is the loader being right.
fn load_compiler() -> Result<Program> {
    load_sandbox(COMPILER).map_err(|e| anyhow::anyhow!("the embedded compiler did not load: {e:#}"))
}

/// Load an image so it can be called, with every unit this binary carries.
///
/// `run_source_q` does the same thing for `flint run`; a sandbox needs it for
/// the same reason -- green threads, ports, JSON and XML are namespace UNITS,
/// so a natively linked binary hands them over by name or a program that
/// reaches one cannot run.
pub(crate) fn load_sandbox(image: &[u8]) -> Result<Program> {
    let mut natives: Vec<(&str, flint_rt::vm::NativeFn)> = Vec::new();
    natives.extend_from_slice(flint_conc::HOST_CATALOGUE);
    natives.extend_from_slice(flint_data_json::HOST_CATALOGUE);
    natives.extend_from_slice(flint_data_xml::HOST_CATALOGUE);
    Program::load_with(image, 3_000_000_000, &natives)
        .map_err(|e| anyhow::anyhow!("the image did not load: {e}"))
}

/// `:to :llvm`: the program as one LLVM IR module, and no linker anywhere.
///
/// What comes back is TEXT, which is the whole difference from the wasm path:
/// there is no prebuilt module to splice into and nothing to base64. The
/// module carries the program image, its compiled arities as LLVM functions,
/// and a `main` that hands both to the runtime archive -- so turning it into
/// an executable is one `clang` invocation, run by whoever has one:
///
/// ```sh
/// cargo build --release -p flint-native-abi
/// clang prog.ll target/release/libflintnative.a -o prog
/// ```
///
/// `SLOTS`, not `SLOTS_AOT`, even when compiling arities. The slot map is read
/// here only for the set of builtin NAMES the compiler validates against, and
/// the runtime a `.ll` links into is the native one -- the same one `flint
/// run` uses. `SLOTS_AOT` describes the wasm AOT module's table, which this
/// artifact does not have: its natives are resolved by name.
fn compile_llvm(srcs: &[PathBuf], entry: &str, out_path: &Path,
                optimize: &[String], checks: Option<bool>, quiet: bool,
                features: Option<&[String]>) -> Result<()> {
    let aot = wants_aot(optimize);
    let strip_checks = strip_checks(optimize, checks);
    let slots = parse_slots(SLOTS)?;
    // No shaking: shaking cuts a finished module down to what a program
    // reaches, and there is no module here to cut. The equivalent for a
    // natively linked artifact is the linker's own `--gc-sections`.
    let spec = build_spec(srcs, entry, &slots, aot, false, &[], None, strip_checks, features)?;
    let mut p = load_compiler()?;
    let r = p.run(&["llvm", &spec]);
    if r.code != 0 {
        bail!("{}", r.out.trim());
    }
    if let Some(rest) = r.out.strip_prefix("!missing") {
        bail!("{}", missing_message(rest, features));
    }
    if let Some(rest) = r.out.strip_prefix("!refused") {
        bail!("{}", rest.trim());
    }
    // A `.ll` that is not IR is the failure this has to refuse rather than
    // write: the guest answers with a string either way, and a compiler that
    // wrote a diagnostic into an artifact would be found out by the linker,
    // three commands later, with no idea which step lied.
    if !r.out.starts_with("; flint program, as LLVM IR.") {
        bail!("the compiler did not answer with LLVM IR:\n{}", r.out.trim());
    }
    fs::write(out_path, r.out.as_bytes())?;
    if !quiet { eprintln!("wrote {} ({} bytes{})", out_path.display(), r.out.len(),
              if aot { ", compiled arities" } else { "" }); }
    Ok(())
}

pub(crate) fn compile(srcs: &[PathBuf], entry: &str, out_path: &Path, optimize: &[String],
           to: &str, meta: &[(String, String)], checks: Option<bool>) -> Result<()> {
    compile_q(srcs, entry, out_path, optimize, to, meta, checks, false, None)
}

/// The same, without the "wrote ..." line.
///
/// `flint.ception` serves `compile` to a PROGRAM (`DECISIONS.md#flint-ception`), and a
/// library call that prints to the user's terminal is chatter the caller did
/// not ask for -- `flint task` would announce a temporary file on every run.
/// The same split `run_source_q` makes, for the same reason.
#[allow(clippy::too_many_arguments)]
pub(crate) fn compile_q(srcs: &[PathBuf], entry: &str, out_path: &Path, optimize: &[String],
           to: &str, meta: &[(String, String)], checks: Option<bool>, quiet: bool,
           features: Option<&[String]>) -> Result<()> {
    let strip_checks = strip_checks(optimize, checks);
    // TWO TARGETS, NOT ONE ARM. `:to :llvm` emits LLVM IR -- text, no linker,
    // nothing to link -- and `:to :native` emits an executable, which is a
    // link. They shared an arm and a sentence ("emitting a native artifact
    // needs a linker, and this binary carries none") that was true of the
    // second and never of the first: what actually blocked `:to :llvm` was
    // that no IR emitter existed (`DECISIONS.md#llvm-ir-target`).
    match to.trim_start_matches(':') {
        "wasm" => {}
        "llvm" => return compile_llvm(srcs, entry, out_path, optimize, checks, quiet, features),
        "native" => bail!(
            "`:to :native` is not built: an executable is a LINK, and this binary carries\n\
             no linker. `:to :llvm` emits the LLVM IR for the same program and needs none;\n\
             linking it is then your own `clang` (see `nativeabi/`). To just run the\n\
             program, `flint run` executes it natively here."
        ),
        other => bail!("no such target `{other}` (`:to :wasm`, `:to :llvm`)"),
    }
    let aot = wants_aot(optimize);
    let slots = parse_slots(if aot { SLOTS_AOT } else { SLOTS })?;
    let base = if aot { RUNTIME_AOT } else { RUNTIME };
    let spec = build_spec(srcs, entry, &slots, aot, true, meta, None, strip_checks, features)?;

    let mut p = load_compiler()?;
    // The runtime module goes as its own ARGUMENT, never inside the spec: it is
    // three-quarters of a megabyte of base64, and inside an EDN string it is
    // three-quarters of a megabyte for flint's reader to scan a character at a
    // time -- 198 seconds against 10.
    let r = p.run(&["wasm", &spec, &base64(base)]);
    if r.code != 0 {
        bail!("{}", r.out.trim());
    }
    if let Some(rest) = r.out.strip_prefix("!missing") {
        bail!("{}", missing_message(rest, features));
    }
    // A REFUSED require is not a missing one: the source is there and
    // readable, and the answer is that this workspace may not have it. The
    // guest has already formed the sentence -- it names both ends and the
    // capability -- so this passes it through rather than rewording it.
    //
    // Unreachable until source workspaces existed: with everything anonymous
    // nothing was ever refused, so the marker fell through to the image
    // loader and surfaced as "this is not a flint image".
    if let Some(rest) = r.out.strip_prefix("!refused") {
        bail!("{}", rest.trim());
    }
    let module = base64_decode(r.out.trim())?;
    fs::write(out_path, &module)?;
    if !quiet { eprintln!("wrote {} ({} bytes{})", out_path.display(), module.len(),
              if aot { ", compiled arities" } else { "" }); }
    Ok(())
}

/// Compile and run, in one step.
///
/// Source in, the answer out. There is no artifact in the middle and no file
/// written: `run` compiles to flint's internal bytecode and runs it here,
/// which is what having the runtime compiled in is for. The bytecode format
/// is an implementation detail and never leaves this process.
fn run_source(srcs: &[PathBuf], entry: &str, args: &[String], caps: &[String],
              roots: Option<&[String]>) -> Result<(i32, String)> {
    run_source_q(srcs, entry, args, caps, roots, false)
}

/// The same, with the program's own output SUPPRESSED.
///
/// `deps add` runs `flint.deps.resolve` as a program to get an answer, and the
/// answer is for this process rather than for the terminal -- printing it would
/// put a raw EDN map above the human line that follows it.
pub(crate) fn run_source_q(srcs: &[PathBuf], entry: &str, args: &[String], caps: &[String],
                roots: Option<&[String]>, quiet: bool) -> Result<(i32, String)> {
    // PODS ARE BOOTED FIRST, because their surface is what the compiler needs
    // and only a running pod can say what it is (`DECISIONS.md#system-namespaces-and-deps`). A build
    // with no `:flint/pods` boots nothing and this costs a map lookup.
    let mut pods: Vec<crate::pod::Pod> = Vec::new();
    for (ns, program, pargs) in declared_pods(srcs)? {
        pods.push(
            crate::pod::Pod::boot(&ns, &program, &pargs).map_err(|e| anyhow::anyhow!("{e}"))?,
        );
    }
    let pod_vars: Vec<(String, Vec<String>)> = pods
        .iter()
        .map(|p| {
            use crate::sys::Service as _;
            (p.name().to_string(), p.var_names().to_vec())
        })
        .collect();
    let spec = build_spec_with(srcs, entry, &parse_slots(SLOTS)?, false, false, &[], roots,
                               &pod_vars, false, None)?;
    let mut c = load_compiler()?;
    let r = c.run(&["project", &spec]);
    if r.code != 0 {
        bail!("{}", r.out.trim());
    }
    if let Some(rest) = r.out.strip_prefix("!missing") {
        bail!("{}", missing_message(rest, None));
    }
    // A REFUSED require is not a missing one: the source is there and
    // readable, and the answer is that this workspace may not have it. The
    // guest has already formed the sentence -- it names both ends and the
    // capability -- so this passes it through rather than rewording it.
    //
    // Unreachable until source workspaces existed: with everything anonymous
    // nothing was ever refused, so the marker fell through to the image
    // loader and surfaced as "this is not a flint image".
    if let Some(rest) = r.out.strip_prefix("!refused") {
        bail!("{}", rest.trim());
    }
    let bytes = base64_decode(r.out.split('\n').next().unwrap_or(""))?;

    // `load_with`, carrying the concurrency unit's builtins: green threads and
    run_image_q(&bytes, args, caps, pods, quiet)
}

/// Serve and run an image that is already compiled.
///
/// The half of `run_source_q` after the compile, split out because
/// `flint.ception` needs exactly this and none of the rest: it has an image
/// already, from source the caller supplied rather than from a path
/// (`DECISIONS.md#flint-ception`). One serving loop rather than two that agree
/// until one of them is changed.
pub(crate) fn run_image_q(bytes: &[u8], args: &[String], caps: &[String],
                          pods: Vec<crate::pod::Pod>, quiet: bool) -> Result<(i32, String)> {
    run_image_gas(bytes, args, caps, pods, quiet, gas_limit())
}

/// The gas limit this run is under, in instructions. Zero is no limit.
///
/// `FLINT_STEP_LIMIT` is the spelling `host/flint.mjs` and the wasm driver
/// already use, so a limit set for one front end means the same thing here.
pub(crate) fn gas_limit() -> u64 {
    std::env::var("FLINT_STEP_LIMIT").ok().and_then(|s| s.parse::<u64>().ok()).unwrap_or(0)
}

/// A `Host` serving exactly what `caps` grants.
///
/// Factored out because `flint.ception` needs the same table: a sandbox it
/// constructs is served whatever the caller lent it, by the same rule and the
/// same code (`DECISIONS.md#flint-ception`). Two copies of this list would be
/// two ideas of what a grant means.
pub(crate) fn host_for(caps: &[String], args: &[String], gas: u64) -> crate::serve::Host {
    let mut policy = crate::policy::Policy::default();
    for c in caps {
        policy.add(c);
    }
    let mut host = crate::serve::Host::new(policy);
    let root = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    if caps.iter().any(|c| c == "fs" || c.starts_with("fs:")) {
        host.serve(Box::new(crate::sys::Fs {
            root,
            write: caps.iter().any(|c| c == "fs:write"),
        }));
    }
    if caps.iter().any(|c| c == "slurp" || c.starts_with("slurp:")) {
        host.serve(Box::new(crate::sys::Slurp));
    }
    if caps.iter().any(|c| c == "deps" || c.starts_with("deps:")) {
        host.serve(Box::new(crate::deps::Npm::default()));
        host.serve(Box::new(crate::deps::Mvn::default()));
        host.serve(Box::new(crate::deps::Git));
    }
    if caps.iter().any(|c| c == "env" || c.starts_with("env:")) {
        host.serve(Box::new(crate::sys::Env { args: args.to_vec() }));
    }
    // Running a module is EXECUTING CODE, so it is a grant like any other and
    // not something the CLI does because it can. The engine behind it is found
    // once and remembered (`DECISIONS.md#wasm-engine`).
    if caps.iter().any(|c| c == "wasm" || c.starts_with("wasm:")) {
        host.serve(Box::new(crate::sys::Wasm));
    }
    // The compiler, served to the program (`DECISIONS.md#flint-ception`).
    //
    // NOT A GRANT, and it used to be one. Since `compile` takes source text
    // rather than paths and hands back bytes rather than writing a file, the
    // SDK reaches nothing a program could not already reach -- it is pure
    // computation, and `run`'s `:with` can still only pass on what the caller
    // holds. Gating it bought no safety and made every nested compile ask for
    // a capability that conferred nothing.
    //
    // EXCEPT UNDER A GAS LIMIT. A limit is a promise about how much work a
    // program can do before it is stopped, and a nested sandbox runs on its
    // own budget -- so a program that could build one would step outside the
    // promise by construction, no matter how small its own allowance. The
    // guarantee has to hold for the whole process or it is not one.
    host.serve(Box::new(crate::sys::Ception {
        callers: Vec::new(),
        caps: caps.to_vec(), sandboxes: Vec::new(), gas,
    }));
    // A booted pod is served whatever the grants say, because DECLARING one in
    // `deps.edn` is the grant: a pod that was started is a process this build
    // already chose to run, and refusing to talk to it afterwards would be a
    // check that costs a subprocess and prevents nothing.
    host
}

pub(crate) fn run_image_gas(bytes: &[u8], args: &[String], caps: &[String],
                            pods: Vec<crate::pod::Pod>, quiet: bool, gas: u64)
                            -> Result<(i32, String)> {
    // ports are a namespace UNIT rather than part of the runtime, so a
    // natively-linked binary has to hand them over by name. Without this
    // `flint run` cannot execute a program that spawns a thread.
    // EVERY unit this binary carries, concatenated. `flint-conc` was the only
    // one, so a program that parsed JSON ran under `flint compile` -- which
    // links units -- and not under `flint run`, which hands them over by name.
    let mut natives: Vec<(&str, flint_rt::vm::NativeFn)> =
        Vec::with_capacity(flint_conc::HOST_CATALOGUE.len() + 2);
    natives.extend_from_slice(flint_conc::HOST_CATALOGUE);
    natives.extend_from_slice(flint_data_json::HOST_CATALOGUE);
    natives.extend_from_slice(flint_data_xml::HOST_CATALOGUE);
    let mut p = Program::load_with(bytes, 2_000_000_000, &natives)
        .map_err(|e| anyhow::anyhow!("the compiled program did not load: {e}"))?;
    if gas > 0 {
        p.set_step_limit(gas);
    }
    // `:with` mints one opaque value per name and PROJECTS them in as the
    // entry's second argument, so a program receives `[args {name -> cap}]`.
    //
    // The CLI decides these are capabilities. The runtime does not know the
    // word: what it carries is an opaque value with an id, and the check that
    // matters -- "did I issue this?" -- happens here, where the ids are, rather
    // than in a table the sandbox keeps. Ids start at 1 because 0 is what
    // `flint/opaque` gives guest-minted values, and a capability whose id was 0
    // would be indistinguishable from one the guest made up.
    let named: Vec<(&str, u64)> =
        caps.iter().enumerate().map(|(i, n)| (n.as_str(), i as u64 + 1)).collect();
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    // SERVED, not just run. `run_with` alone leaves a program that opens a port
    // parked for ever, because nothing drains the event queue -- so `flint run`
    // could execute logic and nothing that talked to the world
    // (`DECISIONS.md#system-namespaces-and-deps`).
    //
    // The opaque values `:with` mints stay: they are `opaque-values`'s capabilities, a
    // different mechanism from the served namespaces, and a host may hand over
    // both. What each `:with` entry now ALSO does is carry the policy for the
    // namespace of that name.
    let mut host = host_for(caps, args, gas);
    for p in pods {
        host.serve(Box::new(p));
    }
    let out = host.run_with(&mut p, &refs, &named);
    if !quiet {
        print!("{}", out.out);
    }
    Ok((out.code, out.out))
}

/// The pods a project declares, as `(namespace, program, args)`.
///
/// A pod is an ORDINARY DEPENDENCY, which is what
/// `DECISIONS.md#workspace-capabilities` said it would be: it sits in `:deps`
/// beside every other kind, and the symbol that keys it is the namespace a
/// program `:require`s.
///
/// ```clojure
/// {:deps {pod.demo {:pod/path "./demopod"}}}
/// ```
///
/// `:pod/path` names a DIRECTORY holding a pod manifest, the same way
/// `:local/root` names a directory holding a `deps.edn`. The coordinate says
/// which manifest to read -- that is the whole rule for a locally referenced
/// dependency -- so a pod under development needs no registry entry and no
/// second declaration mechanism.
///
/// There used to be a `:flint/pods` key holding `{ns {:pod/program "..."}}`.
/// It was a SECOND dependency mechanism beside the real one: a pod declared
/// there could not be resolved, pinned, or reasoned about by anything that
/// understands `:deps`, and it named the executable directly, so there was
/// nowhere to say "this binary on Linux, that one on macOS" -- which is the
/// entire reason pods are shipped prebuilt per platform. The manifest is where
/// that answer lives, so the declaration has to point AT a manifest.
///
/// Read with a SCAN rather than an EDN parser, for the reason `depscmd` gives
/// and for a harder one here: pods boot BEFORE the compiler runs, because
/// their surface is what the compiler needs, so the embedded reader is not
/// available to read what tells us which pods to boot. The scan is
/// deliberately narrow and anything it cannot read is simply not a pod, which
/// fails as "no such namespace" rather than as a corrupt build.
fn declared_pods(srcs: &[PathBuf]) -> Result<Vec<(String, String, Vec<String>)>> {
    let mut out = Vec::new();
    for s in srcs {
        let dir = if s.is_dir() { s } else { continue };
        let (text, base) = project_deps_edn(dir);
        if text.trim().is_empty() { continue }
        for (ns, rel, fetched) in scan_pod_dirs(&text) {
            let pod_dir = base.join(&rel);
            let manifest = pod_dir.join("manifest.edn");
            let m = fs::read_to_string(&manifest).with_context(|| {
                if fetched {
                    // A `:pod/version` is RESOLVED AND FETCHED rather than
                    // named on disk, and what is missing when the directory is
                    // not there is the fetch -- not the manifest. Saying "no
                    // manifest.edn" would send a reader looking for a file they
                    // were never supposed to write.
                    format!("the pod {ns} has not been fetched: no {}\n\
                             `flint fetch` resolves it against the registries in deps.edn",
                            pod_dir.display())
                } else {
                    format!("the pod {ns} names {}, which has no manifest.edn",
                            pod_dir.display())
                }
            })?;
            let program = manifest_program(&m).ok_or_else(|| {
                anyhow::anyhow!(
                    "{}: no executable for this platform ({} {})",
                    manifest.display(), std::env::consts::OS, std::env::consts::ARCH)
            })?;
            // The executable is named RELATIVE TO THE MANIFEST, and is made
            // absolute here. A pod is spawned with the invoking process's
            // working directory, which is the user's and not the pod's, so a
            // relative name would resolve against wherever `flint` was run.
            let exe = pod_dir.join(&program);
            let exe = exe.canonicalize().unwrap_or(exe);
            out.push((ns, exe.to_string_lossy().to_string(), manifest_args(&m)));
        }
    }
    Ok(out)
}

/// Every pod a `deps.edn` declares, as `(namespace, directory, fetched?)`.
///
/// TWO COORDINATES, ONE ANSWER. `:pod/path` names the directory itself;
/// `:pod/version` names one to be resolved against a registry and fetched, and
/// what lands is an ordinary pod directory with an ordinary manifest -- the
/// artifact was chosen once, where the plan was made. So the only difference
/// here is how the directory is spelled, which is what makes a fetched pod and
/// a local one the same thing to everything downstream.
///
/// The cache path is `flint.deps/pod-dir`'s, restated here because pods boot
/// BEFORE the compiler runs and there is no guest to ask yet. It is the one
/// place that rule lives twice, and it is written next to the reason.
fn scan_pod_dirs(text: &str) -> Vec<(String, String, bool)> {
    let mut out: Vec<(String, String, bool)> = scan_pod_key(text, ":pod/path")
        .into_iter()
        .map(|(ns, rel)| (ns, rel, false))
        .collect();
    for (ns, version) in scan_pod_key(text, ":pod/version") {
        let dir = format!(".flint/pod/{}-{}", ns.replace('@', "").replace('/', "-"), version);
        out.push((ns, dir, true));
    }
    out
}

/// Every `symbol {... <key> "..." ...}` entry in a `deps.edn`.
///
/// The symbol is found by walking BACK from the `{` that opens the entry,
/// rather than by splitting forward: `:deps` holds entries of every kind and
/// only some are pods, so there is no fixed distance from the key to the
/// coordinate.
fn scan_pod_key(text: &str, key: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut from = 0usize;
    while let Some(rel) = text[from..].find(key) {
        let at = from + rel;
        from = at + key.len();
        // The value: the next quoted string.
        let Some(q1) = text[from..].find('"') else { continue };
        let after = &text[from + q1 + 1..];
        let Some(q2) = after.find('"') else { continue };
        let path = after[..q2].to_string();
        // The key: the symbol before the `{` that opened THIS entry. Scan back
        // to that brace, then take the last token before it.
        let Some(open) = text[..at].rfind('{') else { continue };
        let before = text[..open].trim_end();
        let ns = before
            .rsplit(|c: char| c.is_whitespace() || c == '{' || c == ',')
            .next()
            .unwrap_or("")
            .to_string();
        if !ns.is_empty() && !ns.starts_with(':') {
            out.push((ns, path));
        }
    }
    out
}

/// The executable a pod manifest names for THIS platform.
///
/// ```clojure
/// {:pod/name pod.demo
///  :pod/artifacts [{:os/name "macos" :os/arch "aarch64"
///                   :artifact/executable "demopod"}]}
/// ```
///
/// `:pod/program "..."` is accepted as the flat form for a pod that is one
/// script on every platform, which is what a pod under development usually is.
///
/// An artifact with no `:os/name` and no `:os/arch` matches anything, so the
/// vector form degrades to the flat one. The names are flint's own
/// (`std::env::consts`), NOT babashka's registry values -- that registry keys
/// on java `os.name` strings matched as regexes, and mapping onto them is a
/// step for when the registry lands rather than a regex engine invented here.
fn manifest_program(m: &str) -> Option<String> {
    if let Some(at) = m.find(":pod/artifacts") {
        for seg in m[at..].split('{').skip(1) {
            let seg = &seg[..seg.find('}').unwrap_or(seg.len())];
            let field = |k: &str| -> Option<String> {
                let i = seg.find(k)?;
                let rest = &seg[i + k.len()..];
                let q1 = rest.find('"')?;
                let tail = &rest[q1 + 1..];
                let q2 = tail.find('"')?;
                Some(tail[..q2].to_string())
            };
            let Some(exe) = field(":artifact/executable") else { continue };
            let ok = |k: &str, actual: &str| {
                field(k).map(|v| v == actual || v == "*").unwrap_or(true)
            };
            if ok(":os/name", std::env::consts::OS) && ok(":os/arch", std::env::consts::ARCH) {
                return Some(exe);
            }
        }
        return None;
    }
    let at = m.find(":pod/program")?;
    let rest = &m[at + ":pod/program".len()..];
    let q1 = rest.find('"')?;
    let tail = &rest[q1 + 1..];
    let q2 = tail.find('"')?;
    Some(tail[..q2].to_string())
}

/// The fixed arguments a manifest gives the pod, if any.
fn manifest_args(m: &str) -> Vec<String> {
    let Some(at) = m.find(":pod/args") else { return Vec::new() };
    let rest = &m[at + ":pod/args".len()..];
    let Some(open) = rest.find('[') else { return Vec::new() };
    let rest = &rest[open + 1..];
    let end = rest.find(']').unwrap_or(0);
    let mut out = Vec::new();
    let mut seg = &rest[..end];
    while let Some(q1) = seg.find('"') {
        let tail = &seg[q1 + 1..];
        let Some(q2) = tail.find('"') else { break };
        out.push(tail[..q2].to_string());
        seg = &tail[q2 + 1..];
    }
    out
}

/// Every namespace declared under `srcs`, for `test`.
///
/// A test that nothing requires is still a test, so a test run cannot collect
/// from one entry outwards -- it would silently run a subset and report the
/// subset as the total, which is the one failure mode a test runner must not
/// have. Every file on the path is a root instead.
///
/// The `ns` form is found by scanning rather than by reading, and that is a
/// deliberate limit: this binary has no Clojure reader outside the embedded
/// compiler, and calling into the compiler to find out what to hand the
/// compiler is a circle. A file whose `ns` form is not the first `(ns ` in it
/// is not found, and no such file exists.
fn test_roots(srcs: &[PathBuf]) -> Result<Vec<String>> {
    let mut files: BTreeMap<String, String> = BTreeMap::new();
    for s in srcs {
        if s.is_dir() {
            read_sources(s, "", &mut files)?;
        } else {
            let name = s.file_name().unwrap().to_string_lossy().to_string();
            files.insert(name, fs::read_to_string(s)?);
        }
    }
    let mut out = vec!["clojure.core".to_string()];
    for body in files.values() {
        let Some(i) = body.find("(ns ") else { continue };
        let rest = &body[i + 4..];
        let name: String = rest
            .chars()
            .skip_while(|c| c.is_whitespace())
            .take_while(|c| !c.is_whitespace() && *c != ')')
            .collect();
        if !name.is_empty() && !out.contains(&name) {
            out.push(name);
        }
    }
    Ok(out)
}

/// `flint <file> [args...]` -- a standalone script
/// (`DECISIONS.md#standalone-scripts`).
///
/// `#!/usr/bin/env flint` hands this the script path and then the user's
/// arguments, so anything after the file is the SCRIPT's and is passed through
/// untouched. `#!/usr/bin/env -S flint :with [fs]` puts options in front of it,
/// which is the only way a script is granted anything: a grant is conferred
/// from outside, and the script is the inside.
fn script_argv(argv: &[String]) -> Result<Option<(PathBuf, Vec<String>, Vec<String>)>> {
    let mut caps: Vec<String> = Vec::new();
    let mut i = 0;
    while i < argv.len() {
        match argv[i].as_str() {
            ":with" | ":grant" => {
                let (v, n) = values(":with", argv, i)?;
                caps.extend(v);
                i = n;
            }
            other => {
                let p = PathBuf::from(other);
                if p.is_file() {
                    return Ok(Some((p, caps, argv[i + 1..].to_vec())));
                }
                return Ok(None);
            }
        }
    }
    Ok(None)
}

/// WHAT A SCRIPT COMPILES TO, worked out once for every command that takes one.
///
/// `run` and `compile` need exactly the same three things -- the source paths,
/// the entry, and what the file declared -- and they needed them badly enough
/// that `run` grew its own copy and `compile` simply did not take a script at
/// all (`DECISIONS.md#standalone-scripts`). One computation now, so a change to
/// what a script MEANS cannot reach one command and miss the other.
///
/// It does NOT do consent. Granting is about RUNNING: `compile` writes what a
/// script declared into the module's metadata and the person who runs the
/// result answers for it then, which is the same order `flint compile :with`
/// already uses.
fn script_spec(path: &Path) -> Result<(Vec<PathBuf>, String, script::ScriptNs, String)> {
    let body = fs::read_to_string(path)
        .with_context(|| format!("cannot read {}", path.display()))?;
    let Some(nsf) = script::read_ns(&body) else {
        bail!("{} has no `ns` form, so it is not a flint script.\n\
               A script begins `#!/usr/bin/env flint` and then \
               `(ns ^:script the-name ...)`.", path.display())
    };
    let Some(entry) = nsf.entry.clone() else {
        // `^:script` IS THE MARK, and its absence is not a detail to work
        // around. A module deliberately has no entry point
        // (`DECISIONS.md#structured-ports`); running or compiling a file that
        // never claimed to be runnable would have to invent one, and inventing
        // an entry is what this codebase refuses everywhere else.
        bail!("(ns {}) in {} is not marked `^:script`, so nothing names its entry point.\n\
               A module has no entry by design. Write `(ns ^:script {} ...)` to use \
               {}/main, or `(ns ^{{:script go}} {} ...)` to use {}/go -- or build it \
               as part of a project with `:path <dir> :fn {}/main`.",
              nsf.ns, path.display(), nsf.ns, nsf.ns, nsf.ns, nsf.ns, nsf.ns)
    };
    if nsf.has_deps {
        bail!("{} declares `:deps`, and this binary does not fetch dependencies for a \
               script yet.\n\
               `flint run`/`flint compile` build from the source path alone; fetching is \
               `flint fetch`, which reads a deps.edn.\n\
               Until a script's `:deps` is wired to it, name what you need with \
               `(:paths [\"...\"])` and put it on disk.", path.display())
    }
    // THE SCRIPT ITSELF FIRST, then whatever it named. `:paths` are relative to
    // the script rather than to the working directory, because a script is run
    // from wherever the person happens to be standing and the file's own
    // neighbours are the only thing it can mean.
    let mut srcs = vec![path.to_path_buf()];
    let dir = path.parent().unwrap_or_else(|| Path::new("."));
    for pth in &nsf.paths {
        let d = dir.join(pth);
        if !d.is_dir() {
            bail!("(ns {}) asks for the source directory {:?}, which is not a directory.\n\
                   A script's `:paths` are relative to the script itself, so this was \
                   looked for at {}.", nsf.ns, pth, d.display())
        }
        srcs.push(d);
    }
    Ok((srcs, entry, nsf, body))
}

fn run_script(path: &Path, caps: &[String], args: &[String]) -> Result<i32> {
    let (srcs, entry, nsf, body) = script_spec(path)?;
    // WHAT THE SCRIPT ASKED FOR, once the person running it has said so.
    //
    // A script is self-contained, which is the point of it -- having to
    // remember the right `:with` flags every time defeats that. So it DECLARES
    // what it needs and the launcher asks, once, keyed to the file's content:
    // editing the script asks again, because a grant that survived an edit
    // would be a grant to code nobody agreed to.
    //
    // `:with` on the command line is added to whatever consent yields, so the
    // explicit route still works and still wins where there is no terminal.
    //
    // CONSENT IS ONLY HERE, and `script_spec` deliberately does not do it:
    // `compile` takes the same script and asks nobody, because granting is
    // about running and the person who runs the module answers for it then.
    let mut caps: Vec<String> = caps.to_vec();
    let unmet: Vec<String> = nsf.capabilities.iter()
        .filter(|c| !caps.contains(c))
        .cloned()
        .collect();
    if !unmet.is_empty() {
        let hash = crate::deps::sha256_hex(body.as_bytes());
        for c in script::consent(path, &unmet, &hash)? {
            if !caps.contains(&c) {
                caps.push(c);
            }
        }
    }
    let caps = &caps[..];
    Ok(run_source(&srcs, &entry, args, caps, None)?.0)
}

fn usage() -> ! {
    eprintln!(
        "flint {VERSION} -- the compiler, as one binary

  flint run :path <dir> :fn <ns/fn> [:with [cap...]] [:args [arg...]]
      Compile and run, here. Nothing is written: flint's runtime is compiled
      into this binary, so a program can be run without producing an artifact.

  flint compile :path <dir> :fn <ns/fn> :to :wasm [:out <file>]
                [:with [cap...]] [:optimize [perf]] [:meta k=v]
      Compile to a standalone module, for any host with a wasm engine. Here
      `:with` DECLARES rather than grants: it is recorded in the artifact's
      metadata, because the arguments arrive later and what a program needs
      has to survive until then.

  flint compile :path <dir> :fn <ns/fn> :to :llvm [:out <file>]
                [:optimize [perf]]
      Compile to LLVM IR: one `.ll` carrying the program, its compiled
      arities and a `main`. No linker runs here and none is needed -- turning
      it into an executable is your own:

          cargo build --release -p flint-native-abi
          clang prog.ll target/release/libflintnative.a -o prog

  flint test :path <dir>
      Run every var marked `^:flint.check/test` under `:path`, and report.
      The suite is what is on the path; nothing has to be registered.

  flint wasm [show | reset | use <path>]
      The engine that runs a compiled module. This binary carries flint's
      runtime but NO wasm engine, so it finds one -- JavaScriptCore on macOS,
      else node, bun or deno -- the first time a program runs a module, and
      remembers it. `reset` forgets it; `use` overrules the search.

  flint [:with [cap...]] <file> [args...]
      Run a STANDALONE SCRIPT: one file carrying its own configuration.

          #!/usr/bin/env flint
          (ns ^:script greet
            (:require [clojure.string :as str]))
          (defn main [args] (str \"hello \" (first args)))

      `^:script` names the entry -- `main` by default, `^{{:script go}}` for
      another -- and everything after the file is the script's own arguments.
      The source path is THE FILE and the directories its `(:paths [..])`
      names; the directory it sits in is NOT scanned, so dropping a script
      into a working tree does not pull that tree into the build.

  flint version

`:with` lends capabilities; a program granted none can reach nothing. `:args`
is what the entry function is called with. Both are conventions of THIS CLI --
the SDKs take a function name and an argument list and nothing more.

`:with [fs]` IS ROOTED AT YOUR WORKING DIRECTORY, not at `:path`. Running
`flint run :path /somewhere/proj :with [fs]` from your home directory lends the
program your home directory -- not the project's tree. That is deliberate: you
are conferring YOUR authority, and where you stand is what you are authorised
over. It is also easy to misread, so it is said here. `cd` to the tree you mean
to lend, or lend nothing.

`:checks false` removes `#?(:flint/check ...)` from the source before it is
read; `:checks true` keeps it. `:optimize [perf]` implies `false` and is the
usual way to get it, but the two are SEPARATE questions: an interpreted release
build wants its checks gone, and a comparison between optimisation levels wants
them held constant so the two arms read the same source. `:checks` wins over
whatever `:optimize` implies, in both directions.

`:optimize` is an ordered preference: `[perf]` compiles every arity as well
(bigger, much faster on arithmetic), `[size]` interprets. Unrecognised tokens
are ignored, so a script written for a newer flint still runs here.

`:meta k=v` records arbitrary metadata in the artifact. flint does not read it:
the DECLARED CAPABILITIES of a program are metadata by this convention, and it
is the host that decides what to make of them.

A value may be a bracketed list -- `:path [src lib]` -- or the key may simply
be repeated; the two mean the same thing. In zsh an unquoted bracket is a glob,
so quote it there: `:path '[src lib]'`.

Everything is embedded, so there is nothing to install: no babashka, no JVM,
no linker. `:to :llvm` is where that last one shows: flint emits the IR, and
linking it is a step you run with a linker you already have."
    );
    std::process::exit(2)
}

/// `:path [a b] :fn ns/f :to :wasm :out o`, in the style `cli` describes.
///
/// A value may be a bracketed list or a repeated key; both mean the same
/// thing. Brackets are written the way they are read aloud, and a shell splits
/// them into separate words, so `[a` .. `b]` is gathered back up here.
#[derive(Default)]
struct Args {
    srcs: Vec<PathBuf>,
    entry: Option<String>,
    out: Option<String>,
    to: Option<String>,
    grants: Vec<String>,
    optimize: Vec<String>,
    // `:checks false` -- OFF INDEPENDENTLY OF OPTIMISATION.
    //
    // `:optimize [perf]` also removes them (`DECISIONS.md#checks`), but tying
    // the two together is wrong in both directions: a release build that wants
    // the interpreter cannot drop its checks, and a comparison that wants two
    // optimisation levels to differ ONLY in compilation cannot have one of them
    // silently reading different source. `None` means "whatever optimisation
    // implies"; `Some(false)` means off whatever it implies.
    checks: Option<bool>,
    // `:features [flint flint/check]` -- the reader's feature set, said rather
    // than defaulted. `None` means `flint.reader/default-features`.
    //
    // It also decides whether `flint.ception` is NAMEABLE: `:flint/nested` is in
    // the default set, and a build compiled without it cannot `:require` the
    // SDK at all (`DECISIONS.md#flint-ception`). A feature and not a grant, because
    // the SDK confers no access -- it is a statement about what the artifact is
    // allowed to BE.
    features: Option<Vec<String>>,
    meta: Vec<(String, String)>,
    args: Vec<String>,
    rest: Vec<String>,
}

/// One value, or a bracketed run of them. Returns the values and the index
/// just past them.
fn values(key: &str, args: &[String], at: usize) -> Result<(Vec<String>, usize)> {
    let first = args.get(at + 1).with_context(|| format!("{key} needs a value"))?;
    if !first.starts_with('[') {
        return Ok((vec![first.clone()], at + 2));
    }
    // A whole list in ONE argument, which is what a quoted `'[a b]'` is. It has
    // to work, because in zsh an unquoted `[a b]` is a GLOB: `:path [src]`
    // fails with "no matches found" before flint sees it at all. sh and bash
    // pass it through, so both spellings are real and both are handled here.
    if let Some(inner) = first.strip_suffix(']') {
        let inner = inner.strip_prefix('[').unwrap_or(inner);
        return Ok((inner.split_whitespace().map(str::to_string).collect(), at + 2));
    }
    let mut out = Vec::new();
    let mut i = at + 1;
    let mut open = false;
    while i < args.len() {
        let mut t = args[i].as_str();
        if !open {
            t = t.strip_prefix('[').unwrap_or(t);
            open = true;
        }
        let last = t.ends_with(']');
        if last {
            t = t.strip_suffix(']').unwrap();
        }
        if !t.is_empty() {
            out.push(t.to_string());
        }
        i += 1;
        if last {
            return Ok((out, i));
        }
    }
    bail!("{key} opens a `[` that is never closed")
}

fn parse(args: &[String]) -> Result<Args> {
    let mut a = Args::default();
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--" => { a.rest = args[i + 1..].to_vec(); break }
            ":path" | ":src" => {
                let (v, n) = values(":path", args, i)?;
                a.srcs.extend(v.into_iter().map(PathBuf::from));
                i = n;
            }
            ":fn" => {
                let (v, n) = values(":fn", args, i)?;
                a.entry = v.into_iter().next();
                i = n;
            }
            ":out" | ":o" => {
                let (v, n) = values(":out", args, i)?;
                a.out = v.into_iter().next();
                i = n;
            }
            ":to" => {
                let (v, n) = values(":to", args, i)?;
                a.to = v.into_iter().next();
                i = n;
            }
            ":with" | ":grant" => {
                let (v, n) = values(":with", args, i)?;
                a.grants.extend(v);
                i = n;
            }
            ":optimize" => {
                let (v, n) = values(":optimize", args, i)?;
                a.optimize.extend(v);
                i = n;
            }
            ":checks" => {
                let (v, n) = values(":checks", args, i)?;
                let w = v.into_iter().next().unwrap_or_default();
                a.checks = match w.as_str() {
                    "false" | "off" | "no" => Some(false),
                    "true" | "on" | "yes" => Some(true),
                    other => bail!("`:checks {other}` is not true or false"),
                };
                i = n;
            }
            ":features" => {
                let (v, n) = values(":features", args, i)?;
                // Written `flint` or `:flint`; kept with the colon, which is
                // how the spec spells a keyword.
                a.features = Some(v.into_iter()
                    .map(|f| if f.starts_with(':') { f } else { format!(":{f}") })
                    .collect());
                i = n;
            }
            ":args" => {
                let (v, n) = values(":args", args, i)?;
                a.args.extend(v);
                i = n;
            }
            ":meta" => {
                let (v, n) = values(":meta", args, i)?;
                for kv in v {
                    let (k, val) = kv.split_once('=').with_context(|| {
                        format!("`:meta {kv}` is not a pair; write it as `:meta key=value`")
                    })?;
                    a.meta.push((k.to_string(), val.to_string()));
                }
                i = n;
            }
            // `--aot` was the old spelling of `:optimize [perf]`. It still
            // means that: a flag that grew into a list should not break the
            // scripts that were written before it grew.
            "--aot" => { a.optimize.push("perf".to_string()); i += 1 }
            other if other.starts_with(':') || other.starts_with('-') => {
                bail!("no such option `{other}`")
            }
            // A bare path is a source, so `flint run src :fn app/main` reads
            // the way anyone would write it.
            other => { a.srcs.push(PathBuf::from(other)); i += 1 }
        }
    }
    Ok(a)
}

fn main() -> Result<()> {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    if argv.is_empty() {
        usage();
    }
    match argv[0].as_str() {
        "version" | "--version" | "-v" => {
            println!("flint {VERSION}");
            Ok(())
        }
        "help" | "--help" | "-h" => usage(),
        "compile" => {
            // THE SCRIPT IS TAKEN OFF THE FRONT BEFORE THE OPTIONS ARE READ.
            // `parse` treats a bare word as a source path, so leaving it in
            // would make the script look like a `:src` and the guard below
            // fire on the command that is actually correct.
            let script = argv.get(1).map(PathBuf::from).filter(|p| p.is_file());
            let rest: Vec<String> = if script.is_some() {
                argv[2..].to_vec()
            } else {
                argv[1..].to_vec()
            };
            let a = parse(&rest)?;
            // A SCRIPT COMPILES TOO, and by naming the file rather than a
            // directory and an entry. `flint <file>` already runs one; not
            // being able to BUILD the same file was the gap
            // (`DECISIONS.md#standalone-scripts`), and it was a gap in the
            // front end only -- the entry and the source paths are the same
            // computation either way, which `script_spec` now does once.
            //
            // WHAT IT DECLARED BECOMES METADATA rather than a grant. `:with`
            // on `compile` already means "write down what this will need",
            // because the arguments arrive later and the person who runs the
            // module answers for them then. A script's `:capabilities` is the
            // same statement made in the file instead of on the line, so it
            // lands in the same place and is merged with `:with` rather than
            // replacing it.
            let (srcs, entry, mut grants) = match &script {
                Some(p) => {
                    if a.entry.is_some() || !a.srcs.is_empty() {
                        bail!("compile was given both the script {} and :fn/:src.\n\
                               A script names its own entry and its own sources; pass one \
                               or the other.", p.display())
                    }
                    let (srcs, entry, nsf, _) = script_spec(p)?;
                    let mut g = a.grants.clone();
                    for c in &nsf.capabilities {
                        if !g.contains(c) {
                            g.push(c.clone());
                        }
                    }
                    (srcs, entry, g)
                }
                None => {
                    let Some(entry) = a.entry.clone() else {
                        bail!("compile needs :fn ns/fn, or the path of a script")
                    };
                    if a.srcs.is_empty() {
                        bail!("compile needs at least one :src, or the path of a script");
                    }
                    (a.srcs.clone(), entry, a.grants.clone())
                }
            };
            let _ = &mut grants;
            let out = a.out.unwrap_or_else(|| "out.wasm".to_string());
            let to = a.to.clone().unwrap_or_else(|| "wasm".to_string());
            // `:with` on `compile` DECLARES rather than grants: the arguments
            // arrive later, so what a program needs has to survive until then,
            // and metadata is where it survives. flint does not read it -- this
            // is the CLI writing down its own convention where the next tool
            // can find it (`DECISIONS.md#cli`).
            let mut meta = a.meta.clone();
            if !grants.is_empty() {
                meta.push(("capabilities".to_string(), grants.join(" ")));
            }
            compile_q(&srcs, &entry, Path::new(&out), &a.optimize, &to, &meta, a.checks,
                      false, a.features.as_deref())
        }
        // `test` is `run` with a generated entry: the compiler collects every
        // var marked `^:flint.check/test` into `flint.check.registry` and this
        // calls its `run`. The suite is therefore whatever is ON THE PATH, not
        // whatever a list somewhere remembered to name.
        //
        // It exists on this binary and not only on the development CLI because
        // a check system that runs on the compiler's own host and not on the
        // one that ships is a check system half the users cannot use.
        // Managing the engine the binary FOUND, since it found it rather than
        // shipping it (`DECISIONS.md#wasm-engine`). Three words, because the
        // whole point of pinning is that this is rarely needed: show it, forget
        // it, or overrule it.
        "wasm" => {
            match argv.get(1).map(|s| s.as_str()) {
                None | Some("show") => match crate::sys::pinned_engine() {
                    Some((kind, path)) => println!("{kind}  {path}"),
                    None => {
                        // NOT an error, and it does not go looking. Nothing is
                        // wrong with a fresh install; the search happens when a
                        // program first asks, not when somebody enquires.
                        println!("no engine pinned yet -- one is found when a program first runs a module");
                        let found = crate::sys::available_engines();
                        if found.is_empty() {
                            println!("and none is available: install node, bun or deno\n\
                                      (wasmtime does not work here -- DECISIONS.md#wasm-engine)");
                        } else {
                            println!("available, in the order they would be tried:");
                            for (k, p) in found {
                                println!("  {k}  {p}");
                            }
                        }
                    }
                },
                Some("reset") => {
                    crate::sys::reset_engine();
                    println!("forgotten -- the next run looks again");
                }
                Some("use") => {
                    let p = argv.get(2).context("wasm use needs a path")?;
                    crate::sys::pin_engine(p).map_err(|e| anyhow::anyhow!(e))?;
                    println!("pinned {p}");
                }
                Some(other) => bail!("no such wasm command: {other}\nknown: show, reset, use <path>"),
            }
            Ok(())
        }
        "test" => {
            let a = parse(&argv[1..])?;
            if a.srcs.is_empty() {
                bail!("test needs at least one :path");
            }
            let roots = test_roots(&a.srcs)?;
            let (code, out) = run_source(&a.srcs, "flint.check.registry/run", &[],
                                         &a.grants, Some(&roots))?;
            // A failing check is a failing RUN. `run-tests` reports by
            // returning text -- it catches what a check throws, so the program
            // itself succeeds -- and a test command that exits 0 on a red
            // suite is a test command CI cannot use.
            std::process::exit(if code != 0 || out.contains("FAILED") { 1 } else { 0 });
        }
        // `deps` -- the dependency surface (`DECISIONS.md#system-namespaces-and-deps`). The
        // RESOLUTION is `flint.deps.resolve`, run as a program, so what `add`
        // writes and what a build picks cannot disagree.
        "deps" => {
            let sub = argv.get(1).map(|s| s.as_str()).unwrap_or("");
            let dir = std::env::current_dir()?;
            match sub {
                "add" => {
                    let Some(spec) = argv.get(2) else {
                        bail!("flint deps add kind:name[@range], e.g. npm:left-pad")
                    };
                    depscmd::add(&dir, spec, |src, entry, caps| {
                        let tmp = std::env::temp_dir().join(format!(
                            "flint-deps-{}",
                            std::process::id()
                        ));
                        std::fs::create_dir_all(&tmp)?;
                        let f = tmp.join("depsadd.cljc");
                        std::fs::write(&f, src)?;
                        let (code, out) = run_source_q(
                            &[tmp.clone()],
                            entry,
                            &[],
                            // What resolution needs and all it needs -- the
                            // registries, plus whatever URL the user named.
                            caps,
                            None,
                            true,
                        )?;
                        let _ = std::fs::remove_dir_all(&tmp);
                        if code != 0 {
                            bail!("{out}");
                        }
                        Ok(out)
                    })
                }
                "tree" | "why" | "pin" => {
                    let target = argv.get(2).cloned().unwrap_or_default();
                    if sub == "why" && target.is_empty() {
                        bail!("flint deps why <dep>");
                    }
                    let tmp = std::env::temp_dir()
                        .join(format!("flint-depsview-{}", std::process::id()));
                    let out = depscmd::view(sub, &target, |src, entry, caps| {
                        std::fs::create_dir_all(&tmp)?;
                        std::fs::write(tmp.join("depsview.cljc"), src)?;
                        let (code, out) =
                            run_source_q(&[tmp.clone()], entry, &[], caps, None, true)?;
                        if code != 0 {
                            bail!("{out}");
                        }
                        Ok(out)
                    });
                    let _ = std::fs::remove_dir_all(&tmp);
                    let out = out?;
                    if sub == "pin" {
                        // WRITTEN, not printed. `pin` exists to change the file.
                        let path = depscmd::deps_path(&dir);
                        let before = std::fs::read_to_string(&path)?;
                        std::fs::write(&path, depscmd::set_overrides(&before, out.trim()))?;
                        println!("pinned every transitive into :flint/overrides");
                    } else {
                        println!("{}", out.trim_end());
                    }
                    Ok(())
                }
                "bump" => {
                    let level = argv.get(2).cloned().unwrap_or_default();
                    let tmp = std::env::temp_dir()
                        .join(format!("flint-depsbump-{}", std::process::id()));
                    let out = depscmd::bump(&level, |src, entry, caps| {
                        std::fs::create_dir_all(&tmp)?;
                        std::fs::write(tmp.join("depsbump.cljc"), src)?;
                        let (code, out) =
                            run_source_q(&[tmp.clone()], entry, &[], caps, None, true)?;
                        if code != 0 {
                            bail!("{out}");
                        }
                        Ok(out)
                    });
                    let _ = std::fs::remove_dir_all(&tmp);
                    let out = out?;
                    let changes = depscmd::parse_bumps(&out);
                    if changes.is_empty() {
                        println!("nothing to bump");
                        return Ok(());
                    }
                    // A MAJOR IS A DECISION. Crossing one silently is how a
                    // tool loses the trust it needs to be used at all, so it
                    // is reported and refused unless it was asked for by name.
                    let crossing: Vec<_> = changes.iter().filter(|c| c.major).collect();
                    if !crossing.is_empty() && level != ":major" {
                        for c in &crossing {
                            println!("{} {} -> {}  CROSSES A MAJOR", c.dep, c.from, c.to);
                        }
                        println!(
                            "\nnot bumped. `flint deps bump :major` if that is what you mean."
                        );
                    }
                    let path = depscmd::deps_path(&dir);
                    let mut text = std::fs::read_to_string(&path)?;
                    for c in &changes {
                        if c.major && level != ":major" {
                            continue;
                        }
                        text = depscmd::set_version(&text, &c.dep, &c.to);
                        println!("{} {} -> {}", c.dep, c.from, c.to);
                    }
                    std::fs::write(&path, text)?;
                    Ok(())
                }
                "agree" => {
                    let apply = argv.iter().any(|a| a == "--apply");
                    let tmp = std::env::temp_dir()
                        .join(format!("flint-depsagree-{}", std::process::id()));
                    let out = depscmd::agree(apply, |src, entry, caps| {
                        std::fs::create_dir_all(&tmp)?;
                        std::fs::write(tmp.join("depsagree.cljc"), src)?;
                        let (code, out) =
                            run_source_q(&[tmp.clone()], entry, &[], caps, None, true)?;
                        if code != 0 {
                            bail!("{out}");
                        }
                        Ok(out)
                    });
                    let _ = std::fs::remove_dir_all(&tmp);
                    let out = out?;
                    if out.trim().is_empty() {
                        println!("every git dependency agrees on its tag");
                    } else if apply {
                        let path = depscmd::deps_path(&dir);
                        let before = std::fs::read_to_string(&path)?;
                        std::fs::write(&path, depscmd::set_overrides(&before, out.trim()))?;
                        println!("wrote the agreed tags into :flint/overrides");
                    } else {
                        println!("{}", out.trim_end());
                        println!("\n`flint deps agree --apply` writes these into :flint/overrides.");
                    }
                    Ok(())
                }
                "" | "help" => {
                    eprintln!(
                        "flint deps add kind:name[@range]   npm:, mvn:, git:, pod:\n\
                         flint deps tree                    what is reached, and how deep\n\
                         flint deps why <dep>               why it is in the plan\n\
                         flint deps pin                     pin every transitive\n\
                         flint deps bump [:patch|:minor|:major]\n\
                         flint deps agree [--apply]        git tags that disagree\n\
                         \n\
                         Resolves, pins and writes into deps.edn. The version written is the\n\
                         version a build picks, because both ask flint.deps.resolve."
                    );
                    std::process::exit(2)
                }
                other => bail!("no such deps command {other:?}"),
            }
        }
        "run" => {
            let a = parse(&argv[1..])?;
            let Some(entry) = a.entry else { bail!("run needs :fn ns/fn") };
            if a.srcs.is_empty() {
                bail!("run needs at least one :src");
            }
            // `:optimize` is a PREFERENCE, so asking `run` for one it cannot
            // give is not an error: compiled arities are a property of a
            // module and `run` produces none, so it interprets and says so
            // once, rather than refusing to do the thing that was asked.
            if wants_aot(&a.optimize) {
                eprintln!("flint: `run` produces no module, so there are no arities to \
                           compile; interpreting.");
            }
            // `:args` is the CLI's convention for what the entry is called
            // with; anything after `--` is the same thing, spelled the way a
            // shell spells it.
            let mut argv = a.args.clone();
            argv.extend(a.rest.iter().cloned());
            std::process::exit(run_source(&a.srcs, &entry, &argv, &a.grants, None)?.0);
        }
        // A STANDALONE SCRIPT, which is what `#!/usr/bin/env flint` produces:
        // the kernel invokes `flint <the file> <the user's args>`, so the first
        // argument is a path and not a command (`DECISIONS.md#standalone-scripts`).
        //
        // Checked LAST, after every command name, so a file called `run` in the
        // working directory cannot shadow `flint run`.
        other => {
            if let Some((p, caps, args)) = script_argv(&argv)? {
                std::process::exit(run_script(&p, &caps, &args)?);
            }
            if other.starts_with(':') {
                eprintln!("flint: `{other}` is an option, and no command or script \
                           was named before it");
            } else {
                eprintln!("flint: no such command `{other}`, and no file of that name");
            }
            usage()
        }
    }
}

#[cfg(test)]
mod pod_scan_tests {
    use super::*;

    /// The two coordinate forms end up as the SAME thing: a directory holding
    /// a manifest. That is what makes a fetched pod and a local one
    /// indistinguishable to everything that boots one.
    #[test]
    fn both_pod_coordinates_name_a_directory() {
        let text = r#"{:paths ["src"]
 :deps {pod.local {:pod/path "./demopod"}
        my/lib    {:git/url "u" :git/sha "s"}
        pod.remote {:pod/version "1.2.0"}}}"#;
        let mut got = scan_pod_dirs(text);
        got.sort();
        assert_eq!(
            got,
            vec![
                ("pod.local".to_string(), "./demopod".to_string(), false),
                ("pod.remote".to_string(), ".flint/pod/pod.remote-1.2.0".to_string(), true),
            ]
        );
    }

    /// A `:deps` map holds entries of every kind and only some are pods, so
    /// the symbol is found by walking BACK from the brace that opened the
    /// entry -- there is no fixed distance from the key to the coordinate.
    #[test]
    fn a_coordinate_with_other_keys_first_still_finds_its_symbol() {
        let text = r#"{:deps {pod.x {:flint/capabilities-grant [:fs] :pod/version "0.1.0"}}}"#;
        assert_eq!(
            scan_pod_dirs(text),
            vec![("pod.x".to_string(), ".flint/pod/pod.x-0.1.0".to_string(), true)]
        );
    }
}
