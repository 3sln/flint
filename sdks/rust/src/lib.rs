//! flint for Rust: compile pure Clojure, and call it in a sandbox.
//!
//! The same shape as the JavaScript SDK (`doc/decisions/0025`), because the
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
//!     resolve: &|ns| std::fs::read_to_string(format!("src/{ns}.cljc")).ok(),
//!     fn_name: "my.app/handler",
//!     ..Default::default()
//! })?;
//! let mut sandbox = image.sandbox()?;
//! let out = sandbox.call("my.app/handler", &[Value::str("hello")])?;
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
//! once, when flint was built (`doc/decisions/0024`).

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
static STDLIB: &[(&str, &str)] = &include!(concat!(env!("OUT_DIR"), "/stdlib.rs"));

/// Read source from a directory tree, the way the CLI does. One resolver among
/// several, and the only one that needs a filesystem.
pub fn dir_resolver(root: impl Into<std::path::PathBuf>) -> impl Fn(&str) -> Option<String> {
    let root = root.into();
    move |ns: &str| {
        let path = ns.replace('-', "_").replace('.', "/");
        for ext in [".cljc", ".clj"] {
            let p = root.join(format!("{path}{ext}"));
            if let Ok(s) = std::fs::read_to_string(&p) {
                return Some(s);
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
    /// A namespace to its source, or `None` if this resolver does not have it.
    pub resolve: &'a dyn Fn(&str) -> Option<String>,
    /// The function a default run would call, as `"my.ns/main"`.
    pub fn_name: &'a str,
    /// Every OTHER function that must stay callable. Only reachable code ships
    /// (`doc/decisions/0002`), and a function nobody calls from the entry is
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

        let mut files: BTreeMap<String, String> = BTreeMap::new();
        for (p, body) in STDLIB {
            files.insert((*p).to_string(), (*body).to_string());
        }
        // Follow the requires, so a resolver is asked only for what is reached.
        let mut want: Vec<String> = vec![opts.fn_name.split('/').next().unwrap_or("").to_string()];
        for e in opts.exports {
            want.push(e.split('/').next().unwrap_or("").to_string());
        }
        let mut seen = std::collections::BTreeSet::new();
        while let Some(ns) = want.pop() {
            if !seen.insert(ns.clone()) {
                continue;
            }
            if let Some(src) = (opts.resolve)(&ns) {
                for cap in src.split('[').skip(1) {
                    let name: String = cap
                        .chars()
                        .take_while(|c| c.is_alphanumeric() || "._-".contains(*c))
                        .collect();
                    if name.contains('.') {
                        want.push(name);
                    }
                }
                let path = format!("{}.cljc", ns.replace('-', "_").replace('.', "/"));
                files.insert(path, src);
            }
        }

        let spec = build_spec(&files, opts.fn_name, opts.exports, &slots, aot, opts.shake,
                              &opts.meta);
        let mut p = Program::load(&self.bytecode, 3_000_000_000)
            .map_err(|e| Error::Load(format!("the embedded compiler did not load: {e}")))?;
        // The runtime module goes as its own ARGUMENT, never inside the spec:
        // three quarters of a megabyte of base64 in an EDN string is three
        // quarters of a megabyte for flint's reader to scan a character at a
        // time -- 198 seconds against 10.
        let r = p.run(&["wasm", &spec, &base64(base)]);
        if r.code != 0 {
            return Err(Error::Compile(r.out.trim().to_string()));
        }
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
    /// (`doc/decisions/0028`).
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

fn build_spec(
    files: &BTreeMap<String, String>, entry: &str, exports: &[&str],
    slots: &BTreeMap<String, u32>, aot: bool, shake: bool, meta: &[(String, Value)],
) -> String {
    let mut out = String::from("{:files {");
    for (k, v) in files {
        out.push_str(&edn_string(k));
        out.push(' ');
        out.push_str(&edn_string(v));
        out.push(' ');
    }
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
        // (`doc/decisions/0025`).
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
