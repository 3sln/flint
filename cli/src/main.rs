//! flint, as a single binary.
//!
//! Everything is embedded: the compiler, two runtime modules, their builtin
//! slot maps, and the standard library sources. There is nothing to install
//! and nothing to find on disk -- no babashka, no JVM, no Rust toolchain and
//! no linker (`doc/decisions/0024`).
//!
//! The compiler runs NATIVELY. flint's runtime is Rust, so it already compiles
//! through LLVM for every target cargo does -- the collector, the interpreter
//! and every builtin are the same code the wasm module is built from. So this
//! binary carries the compiler as a bytecode IMAGE and runs it directly, with
//! no wasm engine in the binary at all.
//!
//! It is worth 2.7 s against 15.6 s on the same compile (`doc/decisions/0010`).
//! The output is still wasm: what changed is what the compiler runs ON.

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

const VERSION: &str = env!("CARGO_PKG_VERSION");

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
        } else if rel.ends_with(".cljc") || rel.ends_with(".clj") {
            out.insert(rel, fs::read_to_string(&p)?);
        }
    }
    Ok(())
}

// --- the commands ----------------------------------------------------------

/// The EDN the compiler takes: the sources, the entry, and what the runtime
/// carries. Shared by `compile` and `run` so the two cannot drift.
fn build_spec(srcs: &[PathBuf], entry: &str, slots: &BTreeMap<String, u32>,
              aot: bool, shake: bool, meta: &[(String, String)]) -> Result<String> {
    let mut files: BTreeMap<String, String> = BTreeMap::new();
    for (p, body) in STDLIB {
        files.insert((*p).to_string(), (*body).to_string());
    }
    for s in srcs {
        if s.is_dir() {
            read_sources(s, "", &mut files)?;
        } else {
            let name = s.file_name().unwrap().to_string_lossy().to_string();
            files.insert(name, fs::read_to_string(s)?);
        }
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
        // (`doc/decisions/0025`). Declared capabilities live here by
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

fn compile(srcs: &[PathBuf], entry: &str, out_path: &Path, optimize: &[String],
           to: &str, meta: &[(String, String)]) -> Result<()> {
    match to.trim_start_matches(':') {
        "wasm" => {}
        "llvm" | "native" => bail!(
            "`:to :llvm` is not built yet: emitting a native artifact needs a linker,\n\
             and this binary carries none. The native runtime itself IS built -- it is\n\
             what `flint run` uses -- so the way to run natively today is `flint run`."
        ),
        other => bail!("no such target `{other}` (`:to :wasm`)"),
    }
    let aot = wants_aot(optimize);
    let slots = parse_slots(if aot { SLOTS_AOT } else { SLOTS })?;
    let base = if aot { RUNTIME_AOT } else { RUNTIME };
    let spec = build_spec(srcs, entry, &slots, aot, true, meta)?;

    let mut p = Program::load(COMPILER, 3_000_000_000)
        .map_err(|e| anyhow::anyhow!("the embedded compiler did not load: {e}"))?;
    // The runtime module goes as its own ARGUMENT, never inside the spec: it is
    // three-quarters of a megabyte of base64, and inside an EDN string it is
    // three-quarters of a megabyte for flint's reader to scan a character at a
    // time -- 198 seconds against 10.
    let r = p.run(&["wasm", &spec, &base64(base)]);
    if r.code != 0 {
        bail!("{}", r.out.trim());
    }
    if let Some(rest) = r.out.strip_prefix("!missing") {
        bail!("no source for{}\nevery namespace a program requires has to be on the source path",
              rest.replace('\n', " "));
    }
    let module = base64_decode(r.out.trim())?;
    fs::write(out_path, &module)?;
    eprintln!("wrote {} ({} bytes{})", out_path.display(), module.len(),
              if aot { ", compiled arities" } else { "" });
    Ok(())
}

/// Compile and run, in one step.
///
/// Source in, the answer out. There is no artifact in the middle and no file
/// written: `run` compiles to flint's internal bytecode and runs it here,
/// which is what having the runtime compiled in is for. The bytecode format
/// is an implementation detail and never leaves this process.
fn run_source(srcs: &[PathBuf], entry: &str, args: &[String], caps: &[String]) -> Result<i32> {
    let spec = build_spec(srcs, entry, &parse_slots(SLOTS)?, false, false, &[])?;
    let mut c = Program::load(COMPILER, 3_000_000_000)
        .map_err(|e| anyhow::anyhow!("the embedded compiler did not load: {e}"))?;
    let r = c.run(&["project", &spec]);
    if r.code != 0 {
        bail!("{}", r.out.trim());
    }
    if let Some(rest) = r.out.strip_prefix("!missing") {
        bail!("no source for{}\nevery namespace a program requires has to be on the source path",
              rest.replace('\n', " "));
    }
    let bytes = base64_decode(r.out.split('\n').next().unwrap_or(""))?;

    let mut p = Program::load(&bytes, 2_000_000_000)
        .map_err(|e| anyhow::anyhow!("the compiled program did not load: {e}"))?;
    // Capabilities are the host's to grant, and a program holds one because it
    // was GIVEN it (`doc/decisions/0022`). Naming one here is what lends it;
    // a program granted nothing can reach nothing.
    for name in caps {
        p.grant(name);
    }
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    let out = p.run(&refs);
    print!("{}", out.out);
    Ok(out.code)
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

  flint version

`:with` lends capabilities; a program granted none can reach nothing. `:args`
is what the entry function is called with. Both are conventions of THIS CLI --
the SDKs take a function name and an argument list and nothing more.

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
no linker."
    );
    std::process::exit(2)
}

/// `:path [a b] :fn ns/f :to :wasm :out o`, in the style `0021` describes.
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
            let a = parse(&argv[1..])?;
            let Some(entry) = a.entry else { bail!("compile needs :fn ns/fn") };
            if a.srcs.is_empty() {
                bail!("compile needs at least one :src");
            }
            let out = a.out.unwrap_or_else(|| "out.wasm".to_string());
            let to = a.to.clone().unwrap_or_else(|| "wasm".to_string());
            // `:with` on `compile` DECLARES rather than grants: the arguments
            // arrive later, so what a program needs has to survive until then,
            // and metadata is where it survives. flint does not read it -- this
            // is the CLI writing down its own convention where the next tool
            // can find it (`doc/decisions/0021`).
            let mut meta = a.meta.clone();
            if !a.grants.is_empty() {
                meta.push(("capabilities".to_string(), a.grants.join(" ")));
            }
            compile(&a.srcs, &entry, Path::new(&out), &a.optimize, &to, &meta)
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
            std::process::exit(run_source(&a.srcs, &entry, &argv, &a.grants)?);
        }
        other => {
            eprintln!("flint: no such command `{other}`");
            usage()
        }
    }
}
