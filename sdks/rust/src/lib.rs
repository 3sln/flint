//! flint for Rust: compile pure Clojure, and call it in a sandbox.
//!
//! The same shape as the JavaScript SDK (`DECISIONS.md#structured-ports`), because the
//! shape is the design rather than a language's convenience:
//!
//! | | |
//! | --- | --- |
//! | [`Image`] | the artifact. Compiled, inert, holds functions. |
//! | [`Sandbox`] | an image instantiated. Holds state, serves calls. |
//! | [`Value`] | anything crossing the boundary, in one encoding. |
//!
//! ```no_run
//! use flint::{Compiler, Value};
//!
//! let compiler = Compiler::embedded()?;
//! let image = compiler.compile(flint::Compile {
//!     resolve: &|ns| std::fs::read_to_string(format!("src/{ns}.cljc")).ok().map(flint::Source::portable),
//!     fn_name: "my.app/handler",
//!     ..Default::default()
//! })?;
//! let sandbox = image.sandbox()?;
//! let out = sandbox.call_blocking("my.app/handler", &[Value::str("hello")])?;
//! # Ok::<(), flint::Error>(())
//! ```
//!
//! ## Where source comes from
//!
//! A **resolver**: a namespace to its bytes. Not a directory, because there is
//! no filesystem in a browser, in a Worker, or inside another sandbox, and
//! sources may come from a bundle, a database or a map already in memory.
//! Reading a directory is one resolver among several -- see [`dir_resolver`].
//!
//! ## Nothing to install
//!
//! The compiler and the runtime are compiled in. There is no babashka, no JVM,
//! no Rust toolchain at run time and no linker: the runtime module was linked
//! once, when flint was built (`DECISIONS.md#no-runtime-linking`).

use flint_rt::native::Program;
use std::collections::BTreeMap;

mod value;
pub use driver::{Driver, Inline, ThreadPool};
pub use sandbox::{Core, Pending, Sandbox};
pub use value::Value;

#[cfg(feature = "capi")]
pub mod capi;
pub mod driver;
pub mod sandbox;

/// What went wrong, in the terms the thing that failed uses.
#[derive(Debug, Clone)]
pub enum Error {
    /// The compiler refused the program. The message is the compiler's own.
    Compile(String),
    /// The call failed: a thrown error, or no such function.
    Call { kind: String, message: String },
    /// The artifact could not be loaded.
    Load(String),
    /// A value could not cross the boundary in either direction.
    Encoding(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Compile(m) => write!(f, "flint: {m}"),
            Error::Call { kind, message } => write!(f, "flint: {kind}: {message}"),
            Error::Load(m) => write!(f, "flint: {m}"),
            Error::Encoding(m) => write!(f, "flint: {m}"),
        }
    }
}
impl std::error::Error for Error {}

type Result<T> = std::result::Result<T, Error>;

// --- the embedded artifacts ------------------------------------------------

/// The compiler, as bytecode: this crate runs it natively, so a wasm module
/// would only be a wasm engine's worth of indirection. The format is an
/// implementation detail and never leaves the process.
static COMPILER: &[u8] = include_bytes!("../../../dist/flintc.bytecode");
static RUNTIME: &[u8] = include_bytes!("../../../dist/flint-runtime.wasm");
static RUNTIME_AOT: &[u8] = include_bytes!("../../../dist/flint-runtime-aot.wasm");
static SLOTS: &str = include_str!("../../../dist/slots.json");
static SLOTS_AOT: &str = include_str!("../../../dist/slots-aot.json");
/// The standard library and `flint.deps`, READ at build time (`build.rs`):
/// `(path, start, end, dialect)` into `STDLIB_FORMS`.
static STDLIB_INDEX: &[(&str, usize, usize, &str)] = &include!(concat!(env!("OUT_DIR"), "/stdlib.rs"));
static STDLIB_FORMS: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/stdlib.forms"));

/// Which dialect a source is written in (`DECISIONS.md#dialects-and-preludes`):
/// a portable `.cljc`/`.clj`, or a flint-only `.fln`. The dialect decides what
/// the source may use -- a workspace prelude, a flint-only reader tag,
/// `defalias` -- and it is the RESOLVER's to say, never the source's.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dialect {
    /// `.cljc` or `.clj`: means the same under Clojure.
    Portable,
    /// `.fln`: flint's own dialect.
    Flint,
}

/// A namespace's source, as a resolver answers it: the TEXT, and the dialect it
/// is written in. The SDK reads the text itself, with the kin reader, before
/// the compiler sees it (`DECISIONS.md#one-reader-and-no-other`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Source {
    pub text: String,
    pub dialect: Dialect,
}

impl Source {
    /// Portable source, the `.cljc` case.
    pub fn portable(text: impl Into<String>) -> Source {
        Source { text: text.into(), dialect: Dialect::Portable }
    }

    /// flint-only source, the `.fln` case.
    pub fn flint(text: impl Into<String>) -> Source {
        Source { text: text.into(), dialect: Dialect::Flint }
    }
}

/// Read source from a directory tree, the way the CLI does. One resolver among
/// several, and the only one that needs a filesystem. The dialect is the
/// EXTENSION the file was found under.
pub fn dir_resolver(root: impl Into<std::path::PathBuf>) -> impl Fn(&str) -> Option<Source> {
    let root = root.into();
    move |ns: &str| {
        let path = ns.replace('-', "_").replace('.', "/");
        // Most specific first (`DECISIONS.md#dialects-and-preludes`): a
        // namespace may have both a `.fln` and a `.cljc`.
        for ext in [".fln", ".cljc", ".clj"] {
            let p = root.join(format!("{path}{ext}"));
            if let Ok(s) = std::fs::read_to_string(&p) {
                return Some(if ext == ".fln" { Source::flint(s) } else { Source::portable(s) });
            }
        }
        None
    }
}

/// What to optimise for. A preference, not a switch -- see `Compile::optimize`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Optimize {
    /// Compile every arity ahead of time: bigger, and much faster on
    /// arithmetic.
    Perf,
    /// A pure interpreter: smaller, and the right default for code that is
    /// mostly sequence work rather than arithmetic.
    Size,
}

/// What to compile.
pub struct Compile<'a> {
    /// A namespace to its source and dialect, or `None` if this resolver does
    /// not have it.
    pub resolve: &'a dyn Fn(&str) -> Option<Source>,
    /// The function a default run would call, as `"my.ns/main"`.
    pub fn_name: &'a str,
    /// Every OTHER function that must stay callable. Only reachable code ships
    /// (`DECISIONS.md#modularity`), and a function nobody calls from the entry is
    /// exactly the one a host wants to call.
    pub exports: &'a [&'a str],
    /// An ORDERED preference list, not a switch. `[Perf]` compiles every
    /// arity ahead of time; `[Size]` is a pure interpreter. The first token
    /// this build understands decides, and unrecognised ones are ignored --
    /// which is what makes a list written against a newer flint still get an
    /// older one's best effort rather than a refusal.
    pub optimize: &'a [Optimize],
    /// Cut the runtime down to what the program reaches.
    pub shake: bool,
    /// Arbitrary metadata to record in the artifact. The runtime does not
    /// interpret it.
    pub meta: Vec<(String, Value)>,
}

impl<'a> Default for Compile<'a> {
    fn default() -> Self {
        Compile {
            resolve: &|_| None,
            fn_name: "",
            exports: &[],
            optimize: &[],
            shake: true,
            meta: Vec::new(),
        }
    }
}

/// The compiler, which is itself a flint program.
pub struct Compiler {
    bytecode: Vec<u8>,
}

impl Compiler {
    /// The compiler compiled into this binary.
    pub fn embedded() -> Result<Compiler> {
        Ok(Compiler { bytecode: COMPILER.to_vec() })
    }

    pub fn compile(&self, opts: Compile<'_>) -> Result<Image> {
        if opts.fn_name.is_empty() {
            return Err(Error::Compile("compile needs a function name".into()));
        }
        // The first token this build understands decides; the rest are what
        // the caller would have wanted otherwise, and anything unrecognised is
        // ignored by construction because this only looks for what it knows.
        let aot = opts.optimize.iter().find_map(|o| match o {
            Optimize::Perf => Some(true),
            Optimize::Size => Some(false),
        }).unwrap_or(false);
        let slots = parse_slots(if aot { SLOTS_AOT } else { SLOTS })?;
        let base = if aot { RUNTIME_AOT } else { RUNTIME };

        // EVERY BODY GOES READ (`DECISIONS.md#one-reader-and-no-other`): the
        // standard library as embedded, and each resolved source read here by
        // the kin reader, DEFERRED -- the compiler resolves its conditionals
        // for the compile's features -- with its dialect beside it.
        let mut files: BTreeMap<String, (Vec<u8>, &'static str)> = BTreeMap::new();
        for (p, a, b, d) in STDLIB_INDEX {
            files.insert((*p).to_string(), (STDLIB_FORMS[*a..*b].to_vec(), *d));
        }
        // Follow the requires, so a resolver is asked only for what is reached.
        let mut want: Vec<String> = vec![opts.fn_name.split('/').next().unwrap_or("").to_string()];
        for e in opts.exports {
            want.push(e.split('/').next().unwrap_or("").to_string());
        }
        let mut seen = std::collections::BTreeSet::new();
        let mut rt = flint_rt::rt::Rt::new();
        while let Some(ns) = want.pop() {
            if !seen.insert(ns.clone()) {
                continue;
            }
            if let Some(src) = (opts.resolve)(&ns) {
                for cap in src.text.split('[').skip(1) {
                    let name: String = cap
                        .chars()
                        .take_while(|c| c.is_alphanumeric() || "._-".contains(*c))
                        .collect();
                    if name.contains('.') {
                        want.push(name);
                    }
                }
                // THE EXTENSION FOLLOWS THE DIALECT THE RESOLVER SAID, so the
                // compiler reads the file as what it is. Until 2026-10-08 the
                // hook returned bare text and every embedder's namespace was
                // labelled `.cljc` -- portable -- whatever it was.
                let portable = src.dialect == Dialect::Portable;
                let path = format!("{}{}", ns.replace('-', "_").replace('.', "/"),
                                   if portable { ".cljc" } else { ".fln" });
                let how = flint_rt::hostread::ReadAs { features: None, tags: &[], portable };
                let bytes = flint_rt::hostread::read_text(&mut rt, &path, &src.text, &how)
                    .map_err(|e| Error::Compile(e.message))?;
                files.insert(path, (bytes, if portable { "portable" } else { "flint" }));
            }
        }

        let spec = build_spec(opts.fn_name, opts.exports, &slots, aot, opts.shake, &opts.meta);
        let spec_bytes = {
            let how = flint_rt::hostread::ReadAs { features: Some(&[]), tags: &[], portable: true };
            flint_rt::hostread::read_text(&mut rt, "spec.edn", &spec, &how)
                .map_err(|e| Error::Compile(format!("the spec does not read: {}", e.message)))?
        };
        // `load_with`, not `load`: see the note on the dependency in Cargo.toml.
        // Plain `load` sees only the runtime's own registry, and every image
        // needs `flint/spawn` from the concurrency unit.
        let mut p = Program::load_with(&self.bytecode, 3_000_000_000,
                                       flint_conc::HOST_CATALOGUE)
            .map_err(|e| Error::Load(format!("the embedded compiler did not load: {e}")))?;
        // The runtime module goes as its own ARGUMENT, never inside the spec:
        // three quarters of a megabyte of base64 in an EDN string is three
        // quarters of a megabyte for flint's reader to scan a character at a
        // time -- 198 seconds against 10.
        // `split`, as the native CLI calls it: the bodies as an ENCODED map
        // the runtime decodes natively, then the mode, the spec as the
        // `flint.forms` bytes read above, and the runtime module.
        use flint_rt::codec::{parse, Val, Wire};
        let mut w = Wire::new();
        w.vector(2).string("flint.compiler.selfhost/main").vector(5).string("split");
        w.map(files.len() as u32);
        for (k, (b, d)) in &files {
            w.string(k);
            w.map(2).keyword(None, "preread").bytes(b).keyword(None, "dialect").keyword(None, d);
        }
        w.string("wasm").bytes(&spec_bytes).string(&base64(base));
        let r = match p.call(&w.done()).map_err(Error::Compile).and_then(|b| parse(&b).map_err(Error::Compile))? {
            Val::Str(s) => s,
            v => match (v.get("error"), v.get("message").and_then(|m| m.as_str())) {
                (Some(Val::Keyword(_, k)), Some(m)) | (Some(Val::Symbol(_, k)), Some(m)) => {
                    return Err(Error::Compile(format!("{k}: {m}")))
                }
                _ => return Err(Error::Compile(format!("the compiler answered something that is not a string: {v:?}"))),
            },
        };
        let r = flint_rt::native::Outcome { code: 0, out: r };
        if let Some(rest) = r.out.strip_prefix("!missing") {
            return Err(Error::Compile(format!(
                "no source for{}; every namespace a program requires has to be resolvable",
                rest.replace('\n', " ")
            )));
        }
        Ok(Image { wasm: base64_decode(r.out.trim())?, meta: opts.meta })
    }
}

/// A compiled artifact: inert, holds functions, says what it is.
pub struct Image {
    /// The bytes. Write them to a file, or hand them to another host.
    pub wasm: Vec<u8>,
    meta: Vec<(String, Value)>,
}

impl Image {
    /// Arbitrary metadata the compiler recorded. The runtime does not
    /// interpret it: what a key means is between whoever wrote it and whoever
    /// reads it.
    pub fn metadata(&self) -> &[(String, Value)] {
        &self.meta
    }

    /// Instantiate it. A sandbox holds state and serves many calls.
    ///
    /// This runs the image NATIVELY, through flint's own runtime rather than a
    /// wasm engine -- which is why `wasm` above is the artifact and this is not
    /// the only way to use it.
    pub fn sandbox(&self) -> Result<Sandbox> {
        self.sandbox_with(std::sync::Arc::new(crate::driver::Inline))
    }

    /// Instantiate under a driver of your choosing -- a pool, for instance.
    /// The driver decides the thread, and eventually the threads
    /// (`DECISIONS.md#drivers`).
    pub fn sandbox_with(&self, driver: std::sync::Arc<dyn Driver>) -> Result<Sandbox> {
        Sandbox::from_wasm_with(&self.wasm, driver)
    }
}

/// An image instantiated: state, and calls.
///
/// A sandbox serves MANY calls, and they share the state the image set up when
/// it loaded -- initialisers run once, not per call, which is what makes
/// instantiate-once-call-per-request work.
// --- the spec, and the two encodings the boundary needs ---------------------

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

/// The spec's ENVELOPE: everything but the file bodies, which go beside it
/// already read.
fn build_spec(
    entry: &str, exports: &[&str],
    slots: &BTreeMap<String, u32>, aot: bool, shake: bool, meta: &[(String, Value)],
) -> String {
    let mut out = String::from("{:files {");
    out.push_str("} :entry ");
    out.push_str(entry);
    if !exports.is_empty() {
        out.push_str(" :exports [");
        out.push_str(&exports.join(" "));
        out.push(']');
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
        // Recorded IN the artifact, not just kept beside it: an image written
        // to disk has to still say what it needs. flint never reads it
        // (`DECISIONS.md#structured-ports`).
        out.push_str(" :meta {");
        for (k, v) in meta {
            out.push_str(&edn_string(k));
            out.push(' ');
            out.push_str(&edn_value(v));
            out.push(' ');
        }
        out.push('}');
    }
    out.push('}');
    out
}

/// A `Value` as EDN, for the metadata map.
///
/// Metadata is DATA the compiler carries and never reads, so this only has to
/// round-trip through flint's reader. A live thing has no written form at all
/// -- a port is an identity in one process and means nothing in a file -- so
/// one in metadata is an error rather than something to invent a syntax for.
fn edn_value(v: &Value) -> String {
    fn join(items: &[Value]) -> String {
        items.iter().map(edn_value).collect::<Vec<_>>().join(" ")
    }
    fn qualified(ns: &Option<String>, name: &str) -> String {
        match ns {
            Some(n) => format!("{n}/{name}"),
            None => name.to_string(),
        }
    }
    match v {
        Value::Nil => "nil".to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Int(i) => i.to_string(),
        Value::Float(f) => {
            // `1` would read back as an integer, and metadata that changes type
            // on the way through is worse than metadata that is verbose.
            if f.fract() == 0.0 && f.is_finite() { format!("{f:.1}") } else { f.to_string() }
        }
        Value::Str(t) => edn_string(t),
        Value::Keyword(ns, n) => format!(":{}", qualified(ns, n)),
        Value::Symbol(ns, n) => qualified(ns, n),
        Value::Vector(xs) => format!("[{}]", join(xs)),
        Value::List(xs) => format!("({})", join(xs)),
        Value::Set(xs) => format!("#{{{}}}", join(xs)),
        Value::Map(kvs) => {
            let body: Vec<String> = kvs.iter()
                .map(|(k, v)| format!("{} {}", edn_value(k), edn_value(v)))
                .collect();
            format!("{{{}}}", body.join(" "))
        }
        // Deliberately not representable. Bytes have no EDN literal, and a
        // port or a sentinel is an identity in a running process: writing one
        // into a file would produce a number that reads back as authority
        // nobody granted.
        Value::Bytes(_) | Value::Port(_) | Value::Sentinel { .. } => {
            edn_string("<not representable in metadata>")
        }
        // EDN HAS TAGS, so a tagged literal is written as one -- which is the
        // format-level half of why `tagged-literals` made it a type rather than a map: a
        // map would have come out here as a map and read back as one.
        Value::Tagged { tag, form } => {
            format!("#{} {}", qualified(&tag.0, &tag.1), edn_value(form))
        }
        // COLUMNAR, so a table survives as a table rather than flattening into
        // a vector of maps on the way out -- which would lose exactly what the
        // type is for (`DECISIONS.md#tables`).
        Value::Table { schema, columns } => {
            let cols: Vec<String> = schema
                .iter()
                .zip(columns.iter())
                .map(|((n, t), col)| {
                    let vs: Vec<String> = col.iter().map(edn_value).collect();
                    format!("[:{n} :{t} [{}]]", vs.join(" "))
                })
                .collect();
            format!("#flint/table {{:columns [{}]}}", cols.join(" "))
        }
    }
}

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
        if let Ok(slot) = v.trim().parse() {
            out.insert(name, slot);
        }
    }
    if out.is_empty() {
        return Err(Error::Load("the embedded slot map is empty".into()));
    }
    Ok(out)
}

pub(crate) fn base64(bytes: &[u8]) -> String {
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

pub(crate) fn base64_decode(text: &str) -> Result<Vec<u8>> {
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
