//! `flint.sys.*`: the system surface, served over ports (`DECISIONS.md#system-namespaces-and-deps`).
//!
//! These namespaces are **VIRTUAL** (`workspace-capabilities` step 4). A program writes
//!
//! ```clojure
//! (:require [flint.sys.fs :as fs])
//! (fs/list-dir "src")
//! ```
//!
//! and the compiler emits a call over a port rather than a var reference. What
//! is on the other end is this file.
//!
//! ## Why they are not `flint.*`
//!
//! `lib/flint/fs.cljc` used to ship in flint's own workspace, beside
//! `clojure.core`, so nothing in the tree said that the thing behind it was a
//! decision the CLI made rather than part of the language. A reader meeting
//! `flint.sys.fs` can tell from the segment. Three things follow, and all three
//! are wanted: it cannot be linked into a pure module, `flint inspect` can list
//! what a program asks the world for, and another host may serve the same name
//! differently.
//!
//! ## The split is by AUTHORITY
//!
//! `slurp` is bytes-at-a-name and `fs` is hierarchy, and they are separate
//! because granting one should not grant the other: a program that reads one
//! configuration file has no business enumerating a disk. That `file://` and
//! `https://` share `:slurp` is the uncomfortable half and is answered the way
//! `workspace-capabilities` answered it for `flint.host/request` -- the compile-time guard is
//! coarse, and the POLICY below is what makes it specific.

use crate::policy::Policy;
use flint_rt::codec::{Val, Wire};
use std::path::{Component, Path, PathBuf};

/// What a request handler answers: a body, or an error to be thrown at the
/// caller.
///
/// `Result` rather than a body and an optional error, because a server that
/// can return both has two ways to say one thing and callers will check the
/// wrong one.
pub type Answer = Result<Wire, String>;

/// A served namespace.
pub trait Service {
    /// The namespace this serves, as a program writes it.
    fn name(&self) -> &str;
    /// `[{:name f :arities [..]} ..]`, for `{:op :list}`.
    ///
    /// Not optional here. `workspace-capabilities` makes the var list optional because a POD may
    /// not be able to answer, but the CLI knows its own surface, so every
    /// namespace in this file gives the compiler what it needs to turn an
    /// unknown var into a compile error. Taking our own API on trust would be
    /// choosing the worse of two answers for no reason.
    fn vars(&self) -> Vec<(&'static str, &'static [u32])>;
    fn invoke(&mut self, var: &str, args: &[Val], policy: &Policy) -> Answer;
}

/// Every path under `:fs` resolves under the granted root, and an escape is
/// REFUSED rather than clamped.
///
/// Silently rewriting `../../etc/passwd` into something inside the root answers
/// a question nobody asked. This is the one part of the old `host/fs.mjs` that
/// was never in the wrong place, and it moves across unchanged.
///
/// The check is on the NORMALISED path and does not touch the filesystem, so it
/// is the same answer whether or not the file exists -- a probe that behaved
/// differently for a missing file would leak whether it was there.
pub fn under(root: &Path, p: &str) -> Result<PathBuf, String> {
    // A BACKSLASH IS A SEPARATOR HERE TOO, whatever this platform thinks.
    //
    // This is where the two implementations of this rule DIVERGED, and the
    // comment beside the other one claimed they agreed "component for component".
    // On Windows `Path` already treats `\` as a separator and refuses the
    // traversal; on unix it is an ordinary filename character, so
    // `(read-file "..\..\etc")` got a FILE from this CLI and a refusal from the
    // node one, which splits on both separators on purpose. Measured, both ways.
    //
    // Neither answer is unsafe where it runs. What is not acceptable is the same
    // program behaving differently per host, so this takes the stricter rule and
    // the answer stops depending on the platform. The cost is filenames
    // containing a backslash, which the other door has never accepted anyway.
    if p.contains('\\') {
        return Err(format!("{p:?} contains a backslash, which is a separator on \
                            some hosts; paths are `/`-separated everywhere"));
    }
    let mut out = PathBuf::from(root);
    for c in Path::new(p).components() {
        match c {
            Component::Normal(s) => out.push(s),
            Component::CurDir => {}
            // `..` is refused rather than popped. Popping would make
            // `a/../../x` depend on how deep `a` was, which is exactly the
            // arithmetic an attacker gets to do.
            Component::ParentDir => {
                return Err(format!("{p:?} leaves the root: `..` is not allowed"))
            }
            Component::RootDir | Component::Prefix(_) => {
                return Err(format!("{p:?} is absolute; paths are relative to the root"))
            }
        }
    }
    Ok(out)
}

fn arg<'a>(args: &'a [Val], i: usize, what: &str) -> Result<&'a Val, String> {
    args.get(i).ok_or_else(|| format!("missing argument {i}: {what}"))
}

fn str_arg<'a>(args: &'a [Val], i: usize, what: &str) -> Result<&'a str, String> {
    arg(args, i, what)?
        .as_str()
        .ok_or_else(|| format!("argument {i} ({what}) has to be a string"))
}

// ------------------------------------------------------------------ flint.sys.fs

pub struct Fs {
    pub root: PathBuf,
    pub write: bool,
}

impl Service for Fs {
    fn name(&self) -> &str {
        "flint.sys.fs"
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        vec![
            ("read-file", &[1]),
            ("write-file", &[2]),
            ("exists?", &[1]),
            ("dir?", &[1]),
            ("list-dir", &[1]),
            ("mkdir", &[1]),
            ("delete", &[1]),
            ("root", &[0]),
        ]
    }
    fn invoke(&mut self, var: &str, args: &[Val], _p: &Policy) -> Answer {
        let mut w = Wire::new();
        match var {
            "root" => {
                w.string(&self.root.display().to_string());
            }
            "read-file" => {
                let p = under(&self.root, str_arg(args, 0, "path")?)?;
                let b = std::fs::read(&p).map_err(|e| format!("read {p:?}: {e}"))?;
                match String::from_utf8(b) {
                    Ok(s) => {
                        w.string(&s);
                    }
                    // NOT lossy. A file that is not text comes back as BYTES
                    // (`DECISIONS.md#no-runtime-linking`) rather than as a string with
                    // replacement characters in it, because a caller can act on
                    // bytes and cannot undo a lossy conversion.
                    Err(e) => {
                        w.bytes(e.as_bytes());
                    }
                }
            }
            "write-file" => {
                if !self.write {
                    return Err("this :fs grant is read-only".into());
                }
                let p = under(&self.root, str_arg(args, 0, "path")?)?;
                let body = arg(args, 1, "body")?;
                let bytes = match body {
                    Val::Str(s) => s.as_bytes().to_vec(),
                    Val::Bytes(b) => b.clone(),
                    _ => return Err("write-file wants a string or a byte string".into()),
                };
                if let Some(d) = p.parent() {
                    std::fs::create_dir_all(d).map_err(|e| format!("mkdir {d:?}: {e}"))?;
                }
                std::fs::write(&p, bytes).map_err(|e| format!("write {p:?}: {e}"))?;
                w.nil();
            }
            "exists?" => {
                let p = under(&self.root, str_arg(args, 0, "path")?)?;
                w.bool(p.exists());
            }
            "dir?" => {
                let p = under(&self.root, str_arg(args, 0, "path")?)?;
                w.bool(p.is_dir());
            }
            "list-dir" => {
                let p = under(&self.root, str_arg(args, 0, "path")?)?;
                let mut es: Vec<(String, bool)> = std::fs::read_dir(&p)
                    .map_err(|e| format!("list {p:?}: {e}"))?
                    .filter_map(|e| e.ok())
                    .map(|e| {
                        (
                            e.file_name().to_string_lossy().into_owned(),
                            e.path().is_dir(),
                        )
                    })
                    .collect();
                // SORTED. A directory listing in filesystem order makes a build
                // that reads one non-reproducible, and the cost is nothing.
                es.sort();
                w.vector(es.len() as u32);
                for (name, dir) in es {
                    w.map(2);
                    w.keyword(None, "name");
                    w.string(&name);
                    w.keyword(None, "dir");
                    w.bool(dir);
                }
            }
            "mkdir" => {
                if !self.write {
                    return Err("this :fs grant is read-only".into());
                }
                let p = under(&self.root, str_arg(args, 0, "path")?)?;
                std::fs::create_dir_all(&p).map_err(|e| format!("mkdir {p:?}: {e}"))?;
                w.nil();
            }
            "delete" => {
                if !self.write {
                    return Err("this :fs grant is read-only".into());
                }
                let p = under(&self.root, str_arg(args, 0, "path")?)?;
                let r = if p.is_dir() {
                    std::fs::remove_dir_all(&p)
                } else {
                    std::fs::remove_file(&p)
                };
                r.map_err(|e| format!("delete {p:?}: {e}"))?;
                w.nil();
            }
            other => return Err(format!("flint.sys.fs has no {other}")),
        }
        Ok(w)
    }
}

// --------------------------------------------------------------- flint.sys.env

pub struct Env {
    pub args: Vec<String>,
}

impl Service for Env {
    fn name(&self) -> &str {
        "flint.sys.env"
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        vec![("get", &[1]), ("args", &[0]), ("cwd", &[0])]
    }
    fn invoke(&mut self, var: &str, args: &[Val], p: &Policy) -> Answer {
        let mut w = Wire::new();
        match var {
            "get" => {
                let k = str_arg(args, 0, "name")?;
                // The POLICY decides which variables exist, and a refused one
                // reads as ABSENT rather than as an error: a program asking for
                // `HOME` and a program probing for `AWS_SECRET_ACCESS_KEY` get
                // the same nil, so the allowlist does not leak its own contents.
                match p.env_allows(k).then(|| std::env::var(k).ok()).flatten() {
                    Some(v) => {
                        w.string(&v);
                    }
                    None => {
                        w.nil();
                    }
                }
            }
            "args" => {
                w.vector(self.args.len() as u32);
                for a in &self.args {
                    w.string(a);
                }
            }
            "cwd" => {
                let d = std::env::current_dir().map_err(|e| format!("cwd: {e}"))?;
                w.string(&d.display().to_string());
            }
            other => return Err(format!("flint.sys.env has no {other}")),
        }
        Ok(w)
    }
}

/// The `{:op :list}` answer for a service.
pub fn list_reply(s: &dyn Service) -> Wire {
    let vars = s.vars();
    let mut w = Wire::new();
    w.vector(vars.len() as u32);
    for (name, arities) in vars {
        w.map(2);
        w.keyword(None, "name");
        w.symbol(None, name);
        w.keyword(None, "arities");
        w.vector(arities.len() as u32);
        for a in arities {
            w.int(*a as i64);
        }
    }
    w
}

/// Serve one decoded request, producing the reply bytes.
///
/// The `:id` is copied from the request and never invented: `flint.rpc`
/// correlates on it, and a reply carrying the wrong one wakes the wrong caller.
pub fn serve(s: &mut dyn Service, req: &Val, policy: &Policy) -> Vec<u8> {
    let id = req.get("id").cloned().unwrap_or(Val::Nil);
    let op = req.get("op").and_then(|v| v.as_str()).unwrap_or("");
    let result: Answer = match op {
        "list" => Ok(list_reply(s)),
        "invoke" => {
            let var = req.get("var").and_then(|v| v.as_str()).unwrap_or("");
            let empty: Vec<Val> = Vec::new();
            let args = req
                .get("args")
                .and_then(|v| v.as_slice())
                .map(|s| s.to_vec())
                .unwrap_or(empty);
            s.invoke(var, &args, policy)
        }
        // `:get` is a var's VALUE. Nothing in `flint.sys.*` holds one -- every
        // var here is a function -- so this is refused by name rather than
        // silently answering nil, which a caller could not tell from a var that
        // really is nil.
        "get" => Err(format!(
            "{} holds no values, only functions -- use a call",
            s.name()
        )),
        other => Err(format!("unknown op {other:?}")),
    };
    let mut w = Wire::new();
    w.map(2);
    w.keyword(None, "id");
    put(&mut w, &id);
    match result {
        Ok(body) => {
            w.keyword(None, "body");
            w.raw(body.as_bytes());
        }
        Err(msg) => {
            w.keyword(None, "error");
            w.map(1);
            w.keyword(None, "message");
            w.string(&msg);
        }
    }
    w.done()
}

/// Write a decoded value back out. Only the shapes an `:id` can be, because
/// that is all this needs and a general one would be an encoder nobody checked.
fn put(w: &mut Wire, v: &Val) {
    match v {
        Val::Int(n) => {
            w.int(*n);
        }
        Val::Str(s) => {
            w.string(s);
        }
        Val::Keyword(ns, n) => {
            w.keyword(ns.as_deref(), n);
        }
        _ => {
            w.nil();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// THE TABLE IS READ, NOT RESTATED. `../containment-cases.txt` is the same
    /// file `bin/check-containment` runs against the node CLI's `under`, which is
    /// the other implementation of this rule -- and restating the cases in two
    /// suites is how the two drifted in the first place. `sdks/cli/src/catalogue.mjs`
    /// makes the same argument about the var lists: "AGENTS.md section 1 says to
    /// make one list read the other rather than restate it."
    ///
    /// These used to be two tests over six cases against the node side's
    /// thirteen, which is why nothing noticed that this one admitted
    /// `..\..\etc` and `C:\windows` while the other refused them.
    const CASES: &str = include_str!("../containment-cases.txt");

    #[test]
    fn every_case_in_the_shared_table() {
        let root = Path::new("/tmp/x");
        let mut n = 0;
        for line in CASES.lines() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let (verb, rest) = line
                .split_once(' ')
                .unwrap_or_else(|| panic!("every case is `verb path`, got {line:?}"));
            // `""` is how the table spells the EMPTY path, which is the root
            // itself and cannot be written as a bare trailing space.
            let p = if rest == "\"\"" { "" } else { rest };
            n += 1;
            let admitted = under(root, p).is_ok();
            match verb {
                "admit" => assert!(admitted, "{p:?} should be admitted"),
                "refuse" => assert!(!admitted, "{p:?} should be refused"),
                other => panic!("bad verb {} in {}", other, line),
            }
        }
        // A TABLE THAT PARSED TO NOTHING would pass every assertion above. This
        // is the count the file actually holds; bump it when a case is added.
        assert_eq!(n, 13, "the shared table should hold 13 cases");
    }

    #[test]
    fn a_plain_path_lands_under_the_root() {
        assert_eq!(under(Path::new("/tmp/x"), "a/b").unwrap(), Path::new("/tmp/x/a/b"));
        assert_eq!(under(Path::new("/tmp/x"), "").unwrap(), Path::new("/tmp/x"));
    }
}

/// Every namespace this binary serves, and what each holds.
///
/// One list, used twice and deliberately so: it is what the COMPILER is told
/// (so a `:require` resolves and an unknown var is a compile error) and what a
/// `{:op :list}` answers at run time. Two lists would drift, and the drift
/// would read as "the compiler and the server disagree about my program".
pub fn catalogue() -> Vec<(&'static str, Vec<(&'static str, &'static [u32])>)> {
    let fs = Fs { root: PathBuf::from("."), write: false };
    let env = Env { args: Vec::new() };
    let sl = Slurp;
    let npm = crate::deps::Npm::default();
    let mvn = crate::deps::Mvn::default();
    // The catalogue is the VAR LIST, so the capabilities it is built with are
    // irrelevant here -- what a caller holds decides what `run` may lend, not
    // which vars exist.
    let ception = Ception { caps: Vec::new(), sandboxes: Vec::new(), callers: Vec::new(), gas: 0 };
    vec![
        (fs.name_static(), fs.vars()),
        (env.name_static(), env.vars()),
        (sl.name_static(), sl.vars()),
        (npm.name_static(), npm.vars()),
        (mvn.name_static(), mvn.vars()),
        (crate::deps::Git.name_static(), crate::deps::Git.vars()),
        (Wasm.name_static(), Wasm.vars()),
        (ception.name_static(), ception.vars()),
    ]
}

impl Fs {
    fn name_static(&self) -> &'static str {
        "flint.sys.fs"
    }
}
impl Env {
    fn name_static(&self) -> &'static str {
        "flint.sys.env"
    }
}

// ------------------------------------------------------------- flint.sys.slurp

/// Bytes at a NAME. `file://`, `http://`, `https://`, `data:`.
///
/// One capability for all of them, and that is the uncomfortable decision this
/// namespace exists to make (`DECISIONS.md#system-namespaces-and-deps`). `(slurp "file:///etc/x")`
/// and `(slurp "https://example.com/x")` are the same question -- give me the
/// bytes at this name -- and a program reading one configuration file should
/// not be holding the thing that can enumerate a disk, which is what `:fs` is.
///
/// That a URL fetch also SENDS the URL to somebody is the half that does not
/// fit, and it is answered by the POLICY rather than by splitting the
/// capability: the guard is coarse because the guard is checked at the
/// reference and a URL is a run-time value, so a scheme-varying guard could not
/// work. The allowlist is where the specific question gets answered.
pub struct Slurp;

/// The largest thing `slurp` will pull into memory.
///
/// A cap rather than a stream, because `slurp` answers with the WHOLE thing by
/// definition -- a namespace that sometimes returns bytes and sometimes returns
/// a handle is two namespaces wearing one name. Something bigger than this
/// wants `flint.sys.stream`, which does not exist yet and is honestly absent
/// rather than half-present.
const SLURP_LIMIT: u64 = 64 * 1024 * 1024;

fn fetch(url: &str) -> Result<Vec<u8>, String> {
    if let Some(path) = url.strip_prefix("file://") {
        let md = std::fs::metadata(path).map_err(|e| format!("{url}: {e}"))?;
        if md.len() > SLURP_LIMIT {
            return Err(format!("{url}: {} bytes is over the slurp limit", md.len()));
        }
        return std::fs::read(path).map_err(|e| format!("{url}: {e}"));
    }
    if url.starts_with("http://") || url.starts_with("https://") {
        let resp = ureq::get(url)
            .call()
            .map_err(|e| format!("{url}: {e}"))?;
        // The STATUS is checked by `ureq` for us -- a 4xx or 5xx is already an
        // error above -- so what is left is the body and its size.
        let mut body = Vec::new();
        // `Read::take`, so a server that keeps sending cannot make this
        // allocate without bound. The limit is checked with one byte of slack
        // so that "exactly at the limit" and "over it" are distinguishable.
        use std::io::Read as _;
        let mut r = resp.into_body().into_reader().take(SLURP_LIMIT + 1);
        r.read_to_end(&mut body).map_err(|e| format!("{url}: {e}"))?;
        if body.len() as u64 > SLURP_LIMIT {
            return Err(format!("{url}: over the slurp limit of {SLURP_LIMIT} bytes"));
        }
        return Ok(body);
    }
    // REFUSED BY NAME. A scheme nobody implemented must not fall through to
    // "treat it as a path", which is how `https:/typo` becomes a local file
    // read.
    Err(format!("{url}: no such scheme -- slurp reads file://, http:// and https://"))
}

impl Service for Slurp {
    fn name(&self) -> &str {
        "flint.sys.slurp"
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        vec![("slurp", &[1]), ("slurp-bytes", &[1])]
    }
    fn invoke(&mut self, var: &str, args: &[Val], p: &Policy) -> Answer {
        let url = str_arg(args, 0, "url")?;
        // THE POLICY, before anything is opened. A refusal names the URL and
        // says what would change it, because "refused" alone sends a reader to
        // the wrong file.
        if !p.slurp_allows(url) {
            return Err(format!(
                "{url} is not in this program's :slurp allowlist -- \
                 grant it with :with [slurp:{url}] or a prefix ending in **"
            ));
        }
        let bytes = fetch(url)?;
        let mut w = Wire::new();
        match var {
            "slurp" => {
                let s = String::from_utf8(bytes)
                    .map_err(|_| format!("{url} is not utf-8; use slurp-bytes"))?;
                w.string(&s);
            }
            "slurp-bytes" => {
                w.bytes(&bytes);
            }
            other => return Err(format!("flint.sys.slurp has no {other}")),
        }
        Ok(w)
    }
}

impl Slurp {
    fn name_static(&self) -> &'static str {
        "flint.sys.slurp"
    }
}

// ------------------------------------------------------------ flint.sys.wasm

/// Running a compiled module, from flint code.
///
/// THE ONE THING THE THREE FRONT ENDS DIFFER ON IN KIND. The native binary has
/// flint's runtime compiled in, so it runs an IMAGE without help; node has a
/// wasm engine; babashka has neither and shells out. Every other host
/// operation is one idea with three implementations, and this was the
/// exception that kept `flint.cli` telling its host to run things for it.
///
/// Served as a namespace, so flint code asks for it like anything else and
/// each front end answers with whatever it has.
///
/// ## Finding an engine, once
///
/// A native binary carries no wasm engine and should not: embedding one costs
/// megabytes for something most runs never do. So it LOOKS, in the order that
/// asks least of the machine:
///
///   1. `jsc` -- JavaScriptCore, present on every mac, no install
///   2. `node`, `bun`, `deno` -- whatever is already on PATH
///   3. `wasmtime` -- fetched for the platform, and only as a last resort
///
/// The answer is remembered in `~/.flint/wasm-runner`, because the search is
/// worth doing once and not once per invocation. `flint wasm reset` forgets it.
pub struct Wasm;

/// Where the chosen engine is remembered.
fn runner_file() -> Option<std::path::PathBuf> {
    std::env::var_os("HOME").map(|h| std::path::Path::new(&h).join(".flint").join("wasm-runner"))
}

/// Candidates, in the order they are tried.
///
/// `jsc` FIRST and by absolute path: it ships with macOS and needs no install,
/// which is the whole reason to prefer it, but it is not on anybody's PATH.
fn candidates() -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = Vec::new();
    let jsc = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc";
    if std::path::Path::new(jsc).exists() {
        out.push(("jsc".into(), jsc.into()));
    }
    // JAVASCRIPT ENGINES ONLY, and `wasmtime` is deliberately not here even
    // when it is installed. The driver hands the engine a `.mjs`; wasmtime
    // would be pinned, handed a file it cannot read, and fail on every run
    // afterwards. Listing something we cannot drive is worse than listing
    // nothing -- `DECISIONS.md#wasm-engine` records why the wasmtime path is a
    // different piece of work.
    for n in ["node", "bun", "deno"] {
        if let Ok(p) = which(n) {
            out.push((n.to_string(), p));
        }
    }
    out
}

/// `command -v`, without a crate for it.
fn which(name: &str) -> Result<String, ()> {
    let path = std::env::var("PATH").map_err(|_| ())?;
    for dir in path.split(':') {
        let p = std::path::Path::new(dir).join(name);
        if p.is_file() {
            return Ok(p.to_string_lossy().to_string());
        }
    }
    Err(())
}

/// The pinned engine, WITHOUT choosing one.
///
/// Separate from `engine()` on purpose: enquiring must not have the side effect
/// of searching the machine and writing a file.
pub fn pinned_engine() -> Option<(String, String)> {
    let t = std::fs::read_to_string(runner_file()?).ok()?;
    let (k, p) = t.trim().split_once(' ')?;
    std::path::Path::new(p).exists().then(|| (k.to_string(), p.to_string()))
}

/// Everything on this machine that could run a module, best first.
pub fn available_engines() -> Vec<(String, String)> {
    candidates()
}

/// Forget the pinned engine. Absent is already forgotten, so this cannot fail.
pub fn reset_engine() {
    if let Some(f) = runner_file() {
        let _ = std::fs::remove_file(f);
    }
}

/// Pin one by hand, overruling the search.
pub fn pin_engine(path: &str) -> Result<(), String> {
    if !std::path::Path::new(path).exists() {
        return Err(format!("no such engine: {path}"));
    }
    let kind = std::path::Path::new(path)
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "custom".into());
    let f = runner_file().ok_or("no HOME to remember the engine in")?;
    if let Some(d) = f.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    std::fs::write(&f, format!("{kind} {path}\n")).map_err(|e| e.to_string())
}

/// The engine to use, remembered across runs.
pub fn engine() -> Result<(String, String), String> {
    if let Some(f) = runner_file() {
        if let Ok(t) = std::fs::read_to_string(&f) {
            let t = t.trim();
            if let Some((k, p)) = t.split_once(' ') {
                if std::path::Path::new(p).exists() {
                    return Ok((k.to_string(), p.to_string()));
                }
            }
        }
    }
    let found = candidates();
    let Some((kind, path)) = found.into_iter().next() else {
        return Err(String::from(
            "no wasm engine found.\n\
             flint carries no wasm engine and looks for a JavaScript one: jsc (ships with macOS), \
             node, bun or deno.\n\
             Install any of those, or `flint wasm use <path>` to name one.\n\
             wasmtime is NOT usable here even if installed: driving it needs its C API rather \
             than a subprocess (DECISIONS.md#wasm-engine).",
        ));
    };
    if let Some(f) = runner_file() {
        if let Some(d) = f.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        let _ = std::fs::write(&f, format!("{kind} {path}\n"));
    }
    Ok((kind, path))
}

impl Wasm {
    fn name_static(&self) -> &'static str {
        "flint.sys.wasm"
    }
}

impl Service for Wasm {
    fn name(&self) -> &str {
        "flint.sys.wasm"
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        vec![("run", &[2, 3]), ("engine", &[0]), ("use", &[1]), ("reset", &[0])]
    }
    fn invoke(&mut self, var: &str, args: &[Val], _p: &Policy) -> Answer {
        let mut w = Wire::new();
        match var {
            // `(run module fn)` / `(run module fn args)` -- the FUNCTION IS
            // NAMED, because a flint module has no entry point that is special
            // (`DECISIONS.md#structured-ports` step 5).
            "run" => {
                let path = str_arg(args, 0, "module")?;
                let f = str_arg(args, 1, "fn")?;
                let argv: Vec<String> = match args.get(2) {
                    None | Some(Val::Nil) => Vec::new(),
                    Some(Val::Vector(xs)) => xs
                        .iter()
                        .map(|x| match x {
                            Val::Str(s) => Ok(s.clone()),
                            _ => Err(String::from("run: args must be strings")),
                        })
                        .collect::<Result<_, _>>()?,
                    Some(_) => return Err("run: args must be a vector".into()),
                };
                let r = run_module(path, f, &argv)?;
                w.map(2);
                w.keyword(None, "code");
                w.int(r.0 as i64);
                w.keyword(None, "out");
                w.string(&r.1);
            }
            // Which engine is pinned, WITHOUT choosing one. A program asking
            // what is configured should not cause a filesystem search and a
            // write as a side effect, so this reports the file and nothing more.
            "engine" => {
                let pinned = runner_file()
                    .and_then(|f| std::fs::read_to_string(f).ok())
                    .and_then(|t| t.trim().split_once(' ').map(|(k, p)| (k.to_string(), p.to_string())));
                match pinned {
                    Some((k, p)) => {
                        w.map(2);
                        w.keyword(None, "kind");
                        w.string(&k);
                        w.keyword(None, "path");
                        w.string(&p);
                    }
                    None => {
                        w.nil();
                    }
                }
            }
            "use" => {
                let p = str_arg(args, 0, "path")?;
                if !std::path::Path::new(p).exists() {
                    return Err(format!("no such engine: {p}"));
                }
                let kind = std::path::Path::new(p)
                    .file_name()
                    .map(|s| s.to_string_lossy().to_string())
                    .unwrap_or_else(|| "custom".into());
                let f = runner_file().ok_or("no HOME to remember the engine in")?;
                if let Some(d) = f.parent() {
                    let _ = std::fs::create_dir_all(d);
                }
                std::fs::write(&f, format!("{kind} {p}\n")).map_err(|e| e.to_string())?;
                w.string(p);
            }
            "reset" => {
                if let Some(f) = runner_file() {
                    let _ = std::fs::remove_file(f);
                }
                w.nil();
            }
            _ => return Err(format!("flint.sys.wasm has no {var}")),
        }
        Ok(w)
    }
}

/// Run `f` in the module at `path`, on whatever engine this machine has.
///
/// The job travels in a GENERATED PRELUDE rather than argv, because argv is
/// the thing four JavaScript shells disagree about most: jsc's module mode has
/// no `process`, deno wants `--`, and bun follows node. A prelude that sets one
/// global and imports the driver is the same on all four.
fn run_module(path: &str, f: &str, argv: &[String]) -> Result<(i32, String), String> {
    let (_kind, exe) = engine()?;
    let root = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().and_then(|p| p.parent()).and_then(|p| p.parent()).map(|p| p.to_path_buf()))
        .unwrap_or_else(|| std::path::PathBuf::from("."));
    let driver = root.join("host").join("run.js");
    if !driver.exists() {
        return Err(format!("the wasm driver is missing: {}", driver.display()));
    }
    let abs = std::fs::canonicalize(path).map_err(|e| format!("{path}: {e}"))?;
    let job = format!(
        "globalThis.__FLINT_JOB = {{path:{},fn:{},args:[{}],stepLimit:{}}};\nawait import({});\n",
        js_string(&abs.to_string_lossy()),
        js_string(f),
        argv.iter().map(|a| js_string(a)).collect::<Vec<_>>().join(","),
        std::env::var("FLINT_STEP_LIMIT").ok().and_then(|s| s.parse::<u64>().ok()).unwrap_or(0),
        js_string(&driver.to_string_lossy()),
    );
    let dir = std::env::temp_dir().join(format!("flint-wasm-{}", std::process::id()));
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let jobfile = dir.join("job.mjs");
    std::fs::write(&jobfile, job).map_err(|e| e.to_string())?;

    let mut cmd = std::process::Command::new(&exe);
    // Each shell needs its own words for "this file is a module, run it".
    match std::path::Path::new(&exe).file_name().and_then(|s| s.to_str()).unwrap_or("") {
        "jsc" => {
            cmd.arg("-m");
        }
        "deno" => {
            cmd.args(["run", "-A"]);
        }
        _ => {}
    }
    cmd.arg(&jobfile);
    let out = cmd.output().map_err(|e| format!("{exe}: {e}"))?;
    let _ = std::fs::remove_file(&jobfile);
    let text = String::from_utf8_lossy(&out.stdout);
    let last = text.lines().last().unwrap_or("").trim();
    if last.is_empty() {
        return Err(format!(
            "the wasm engine produced no result\nengine: {exe}\n{}",
            String::from_utf8_lossy(&out.stderr)
        ));
    }
    decode_result(last)
}

/// A JavaScript string literal. Everything the job carries -- paths, a function
/// name, arguments -- is attacker-adjacent text being pasted into source, so it
/// is escaped rather than quoted and hoped over.
fn js_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{2028}' => out.push_str("\\u2028"),
            '\u{2029}' => out.push_str("\\u2029"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// The driver's one line of JSON, back into a result.
fn decode_result(line: &str) -> Result<(i32, String), String> {
    let v: serde_json::Value =
        serde_json::from_str(line).map_err(|e| format!("the wasm engine answered unreadably: {e}\n{line}"))?;
    if let Some(e) = v.get("error").and_then(|e| e.as_str()) {
        return Err(format!("the module failed:\n{e}"));
    }
    Ok((
        v.get("code").and_then(|c| c.as_i64()).unwrap_or(0) as i32,
        v.get("out").and_then(|o| o.as_str()).unwrap_or("").to_string(),
    ))
}

// ------------------------------------------------------------------ flint.ception

/// flint's own SDK, served to flint.
///
/// `sdks/c`, `sdks/rust` and `sdks/esm` let C, Rust and JavaScript embed the
/// compiler. This is the same offer made to the language itself, and the
/// reason it can be made at all is that flint is self-hosted: the compiler is
/// already linked into this binary, so `flint.cli` compiling a program is a
/// function call, not a subprocess and not a task handed back to the host.
///
/// THAT HAND-BACK IS WHAT THIS REPLACES. `flint.cli/run` returns
/// `{:exec {...}}` for `flint task` -- source, an entry and a path list, for
/// the host to compile and run on its behalf -- and each of the three front
/// ends then does it differently, which is two implementations too many.
///
/// `run` INTERPRETS rather than going through a module: the native runtime is
/// compiled into this binary, so source can be executed without an artifact and
/// without a wasm engine. `compile` is the one that produces a module, and
/// `flint.sys.wasm` is what runs one afterwards.
/// One constructed sandbox: the program, and the host that serves it.
///
/// The host is kept ALONGSIDE rather than rebuilt per call, because it owns the
/// system port the call protocol travels over and installing a second one would
/// leave the first orphaned.
pub struct Sandboxed {
    pub program: flint_rt::native::Program,
    pub host: crate::serve::Host,
}

pub struct Ception {
    /// What the CALLER was granted. A program may not confer what it does not
    /// hold: without this, `sdk` was the only capability anyone needed, because
    /// `(sdk/run {... :with ["fs"]})` minted the rest onto a child it wrote.
    pub caps: Vec<String>,
    /// Callers this program took, by handle: which sandbox, and the bound port.
    ///
    /// A CALLER IS THE THING YOU CALL ON, not the sandbox
    /// (`DECISIONS.md#bridges-are-the-only-door`). `:bind` gives the control
    /// plane a port and it spawns ONE thread serving calls on it, so a caller
    /// is that thread's queue -- serial, in arrival order -- and concurrency is
    /// had by taking a second one. Naming it makes that cost visible instead of
    /// hiding a thread per call behind `(call sandbox ..)`.
    ///
    /// `None` is a closed one, kept for the same reason the sandbox slots are.
    pub callers: Vec<Option<(usize, crate::serve::Caller)>>,
    /// Sandboxes this program constructed, by handle. `None` is a closed one --
    /// the slot is kept so a stale handle reads as closed rather than as some
    /// later sandbox that reused the number.
    pub sandboxes: Vec<Option<Sandboxed>>,
    /// The gas limit the OUTER program is under, in instructions. Non-zero
    /// turns this namespace off (`DECISIONS.md#flint-ception`).
    pub gas: u64,
}

impl Ception {
    fn name_static(&self) -> &'static str {
        "flint.ception"
    }
}

/// `:sources {"my.ns" "(ns my.ns) .."}` from a request.
///
/// A MAP OF SOURCE TEXT, not a list of directories, and that is the whole of
/// why `sdk` reaches no filesystem: there is no path in this request for a
/// caller to point anywhere (`DECISIONS.md#flint-ception`).
fn sources_of(opts: &Val) -> Result<Vec<(String, String)>, String> {
    match opts.get("sources") {
        Some(Val::Map(es)) => {
            let mut out = Vec::with_capacity(es.len());
            for (k, v) in es {
                let ns = k.as_str().ok_or("sources: every key must be a namespace")?;
                let body = match v {
                    Val::Str(s) => s.clone(),
                    _ => return Err(format!("sources: {ns} must map to source text")),
                };
                out.push((ns.to_string(), body));
            }
            if out.is_empty() {
                return Err("sources: at least one namespace".into());
            }
            Ok(out)
        }
        _ => Err("compile/run needs :sources {\"my.ns\" \"(ns my.ns) ..\"}".into()),
    }
}

/// A vector of strings at `key`, or empty. Absent and empty mean the same here.
fn strings_at(opts: &Val, key: &str) -> Result<Vec<String>, String> {
    match opts.get(key) {
        None | Some(Val::Nil) => Ok(Vec::new()),
        Some(Val::Vector(xs)) | Some(Val::List(xs)) => xs
            .iter()
            .map(|x| x.as_str().map(|s| s.to_string()).ok_or(format!("{key}: every entry must be a string")))
            .collect(),
        _ => Err(format!("{key} must be a vector")),
    }
}

impl Ception {
    /// Whether the caller holds `want`, by the same spelling `:with` uses.
    ///
    /// A bare `fs` covers `fs:write`, because the bare name is the whole
    /// capability; holding `fs:write` does NOT confer a bare `fs`, which would
    /// be a widening.
    fn holds(&self, want: &str) -> bool {
        let base = want.split(':').next().unwrap_or(want);
        self.caps.iter().any(|c| c == want || c == base)
    }
}

impl Service for Ception {
    fn name(&self) -> &str {
        "flint.ception"
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        vec![("compile", &[1]), ("run", &[1]), ("sandbox", &[1, 2]), ("caller", &[1]),
             ("call", &[3]), ("close-caller", &[1]),
             ("close", &[1]), ("version", &[0])]
    }
    fn invoke(&mut self, var: &str, args: &[Val], _p: &Policy) -> Answer {
        // A GAS LIMIT IS A PROMISE ABOUT THE WHOLE PROCESS. A nested sandbox
        // runs on its own budget, so a program that could build one would step
        // outside the promise by construction, however small its own allowance.
        //
        // Served and refusing rather than absent, so the reason is said. Left
        // unserved, the program meets "this sandbox was given no system port",
        // which names neither this namespace nor the limit that turned it off.
        if self.gas > 0 {
            return Err(format!(
                "flint.ception is off under a gas limit.\n\
                 this program is limited to {} instructions, and a sandbox it built would run \
                 on its own budget -- so the limit would stop meaning what it says.\n\
                 run without FLINT_STEP_LIMIT to use it.",
                self.gas
            ));
        }
        let mut w = Wire::new();
        match var {
            // `(compile {:sources {"my.ns" "(ns my.ns) .."} :fn "my.ns/main"})`
            //
            // NO `:paths` AND NO `:out`. The caller hands over source text and
            // gets the image bytes back, so `sdk` confers no filesystem reach
            // at all -- a caller compiling a project on disk reads it with its
            // own `fs` grant first, and the two capabilities compose rather
            // than one implying the other.
            "compile" => {
                let o = args.first().ok_or("compile needs an options map")?;
                let sources = sources_of(o)?;
                let entry = o.get("fn").and_then(|v| v.as_str()).ok_or("compile needs :fn \"ns/fn\"")?;
                let optimize = strings_at(o, "optimize")?;
                let checks = match o.get("checks") {
                    Some(Val::Bool(b)) => Some(*b),
                    _ => None,
                };
                let shake = !matches!(o.get("shake"), Some(Val::Bool(false)));
                let mut meta: Vec<(String, String)> = Vec::new();
                if let Some(Val::Map(es)) = o.get("meta") {
                    for (k, v) in es {
                        if let (Some(k), Some(v)) = (k.as_str(), v.as_str()) {
                            meta.push((k.to_string(), v.to_string()));
                        }
                    }
                }
                let exports = strings_at(o, "exports")?;
                let image = crate::sdk_compile(&sources, entry, &exports, &optimize, shake,
                                               checks, &meta)
                    .map_err(|e| format!("{e:#}"))?;
                w.bytes(&image);
            }
            // `(sandbox image)` -- a loaded, callable program.
            //
            // It holds NOTHING. No ports, no capabilities, no IO: a sandbox
            // constructed here can run its logic and reach the world only
            // through what it is later handed, which is the inversion
            // `DECISIONS.md#ports-are-the-hosts` made for ports applied to
            // everything a program might want.
            "sandbox" => {
                let image = match args.first() {
                    Some(Val::Bytes(b)) => b.clone(),
                    _ => return Err("sandbox needs the image bytes `compile` returned".into()),
                };
                // `:with` LENDS, and only what the caller holds. A sandbox
                // given nothing reaches nothing, which is the default and the
                // point; a sandbox given `[fs]` by a caller holding `fs` is the
                // caller passing on what it has.
                let caps = match args.get(1) {
                    None | Some(Val::Nil) => Vec::new(),
                    Some(o) => strings_at(o, "with")?,
                };
                if let Some(extra) = caps.iter().find(|c| !self.holds(c)) {
                    return Err(format!(
                        "sandbox: this program was not granted `{extra}`, so it cannot lend it.\n\
                         it holds: {}\n\
                         a program may pass on what it has, not mint what it has not.",
                        if self.caps.is_empty() { "nothing".to_string() } else { self.caps.join(" ") }
                    ));
                }
                let program = crate::load_sandbox(&image).map_err(|e| format!("{e:#}"))?;
                let host = crate::host_for(&caps, &[], 0);
                self.sandboxes.push(Some(Sandboxed { program, host }));
                w.int((self.sandboxes.len() - 1) as i64);
            }
            // `(caller sandbox)` -- bind a port and hand back what calls go on.
            "caller" => {
                let h = args.first().and_then(|v| v.as_i64())
                    .ok_or("caller needs the handle `sandbox` returned")? as usize;
                let slot = self.sandboxes.get_mut(h)
                    .ok_or_else(|| format!("no such sandbox: {h}"))?;
                let sb = slot.as_mut().ok_or_else(|| format!("sandbox {h} is closed"))?;
                let c = sb.host.caller(&mut sb.program)?;
                self.callers.push(Some((h, c)));
                w.int((self.callers.len() - 1) as i64);
            }
            // `(close-caller caller)` -- `:unbind`, which closes the bound port
            // and ends the thread serving it. The SANDBOX is untouched: other
            // callers on it go on working, which is the whole reason they are
            // separate things.
            "close-caller" => {
                let h = args.first().and_then(|v| v.as_i64())
                    .ok_or("close-caller needs the handle `caller` returned")? as usize;
                match self.callers.get_mut(h) {
                    Some(slot) => { *slot = None; }
                    None => return Err(format!("no such caller: {h}")),
                }
                w.nil();
            }
            // `(call caller "ns/f" [args])`
            "call" => {
                let ch = args.first().and_then(|v| v.as_i64())
                    .ok_or("call needs the handle `caller` returned")? as usize;
                let h = match self.callers.get(ch) {
                    Some(Some((h, _))) => *h,
                    Some(None) => return Err(format!("caller {ch} is closed")),
                    None => return Err(format!("no such caller: {ch}")),
                };
                let f = str_arg(args, 1, "fn")?;
                // ANY VALUE, not just strings. `Sandbox::call` in `sdks/rust`
                // takes values, and the restriction here was the thing that
                // stopped a PORT being passed inward -- which is the one
                // argument worth passing, since a port is how a sandbox reaches
                // anything at all (`DECISIONS.md#ports-are-the-hosts`).
                let argv: &[Val] = match args.get(2) {
                    None | Some(Val::Nil) => &[],
                    Some(Val::Vector(xs)) | Some(Val::List(xs)) => xs.as_slice(),
                    Some(_) => return Err("call: args must be a vector".into()),
                };
                let slot = self.sandboxes.get_mut(h)
                    .ok_or_else(|| format!("no such sandbox: {h}"))?;
                let sb = slot.as_mut().ok_or_else(|| format!("sandbox {h} is closed"))?;
                // OVER THE SYSTEM PORT, not `Program::call`. That one is
                // `flint_call` -- synchronous, no scheduler, no way to express
                // parking -- so a function that opens a port could not be
                // called through it at all. The runtime already implements the
                // port protocol, and a call there RUNS AS A GREEN THREAD, so
                // the called function may park and the pump answers it while
                // this call is outstanding (`DECISIONS.md#flint-ception`).
                // A CALLER, not a global call. One per sandbox, made on first
                // use and kept: a caller is a bound port with a thread serving
                // it, so minting one per call would spawn a thread per call --
                // which is the cost `one bound port is a queue` exists to keep
                // visible (`DECISIONS.md#bridges-are-the-only-door`).
                let caller = match self.callers.get(ch) {
                    Some(Some((_, c))) => c,
                    _ => return Err(format!("caller {ch} is closed")),
                };
                let reply = sb.host.call(&mut sb.program, caller, f, argv)?;
                // Forwarded VERBATIM. The answer is already an encoded value,
                // so decoding it here to re-encode it would be two chances to
                // disagree with the sandbox about what it said.
                w.raw(&reply);
            }
            "close" => {
                let h = args.first().and_then(|v| v.as_i64())
                    .ok_or("close needs the handle `sandbox` returned")? as usize;
                match self.sandboxes.get_mut(h) {
                    Some(slot) => { *slot = None; }
                    None => return Err(format!("no such sandbox: {h}")),
                }
                w.nil();
            }
            // `(run {:paths [..] :fn "ns/f" :args [..] :with [..]})`
            //
            // NO MODULE AND NO ENGINE. The runtime is compiled in, so this
            // loads the program into a second `Program` in this process and
            // interprets it. The guest's own output is captured rather than
            // printed, because the caller asked for an answer.
            "run" => {
                let o = args.first().ok_or("run needs an options map")?;
                let sources = sources_of(o)?;
                let entry = o.get("fn").and_then(|v| v.as_str()).ok_or("run needs :fn \"ns/fn\"")?;
                let argv = strings_at(o, "args")?;
                let caps = strings_at(o, "with")?;
                // AUTHORITY IS NOT CREATED HERE. A child may be given any
                // subset of what the caller holds and nothing beyond it.
                // Refused rather than quietly narrowed: a child that silently
                // loses a capability fails somewhere else, for a reason that
                // does not name this.
                if let Some(extra) = caps.iter().find(|c| !self.holds(c)) {
                    return Err(format!(
                        "run: this program was not granted `{extra}`, so it cannot lend it.\n\
                         it holds: {}\n\
                         a program may pass on what it has, not mint what it has not.",
                        if self.caps.is_empty() { "nothing".to_string() } else { self.caps.join(" ") }
                    ));
                }
                // Compile then run, from the same source map `compile` takes.
                // No pods: booting one is starting a process, which is not
                // something a program holding `sdk` alone may do.
                let image = crate::sdk_compile(&sources, entry, &[], &[], true, None, &[])
                    .map_err(|e| format!("{e:#}"))?;
                let (code, out) = crate::run_image_q(&image, &argv, &caps, Vec::new(), true)
                    .map_err(|e| format!("{e:#}"))?;
                w.map(2);
                w.keyword(None, "code");
                w.int(code as i64);
                w.keyword(None, "out");
                w.string(&out);
            }
            "version" => {
                w.string(crate::VERSION);
            }
            _ => return Err(format!("flint.ception has no {var}")),
        }
        Ok(w)
    }
}
