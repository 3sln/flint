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

    #[test]
    fn a_path_may_not_leave_the_root() {
        let root = Path::new("/tmp/x");
        assert!(under(root, "a/b").is_ok());
        assert!(under(root, "./a").is_ok());
        for bad in ["../etc/passwd", "a/../../etc", "/etc/passwd", "a/../.."] {
            assert!(under(root, bad).is_err(), "{bad} should be refused");
        }
    }

    #[test]
    fn dotdot_is_refused_and_not_popped() {
        // `a/../b` is INSIDE the root once normalised, and is still refused.
        // Popping would make the answer depend on how deep the path had got,
        // which is arithmetic an attacker gets to do.
        assert!(under(Path::new("/tmp/x"), "a/../b").is_err());
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
    vec![
        (fs.name_static(), fs.vars()),
        (env.name_static(), env.vars()),
        (sl.name_static(), sl.vars()),
        (npm.name_static(), npm.vars()),
        (mvn.name_static(), mvn.vars()),
        (crate::deps::Git.name_static(), crate::deps::Git.vars()),
        (Wasm.name_static(), Wasm.vars()),
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
