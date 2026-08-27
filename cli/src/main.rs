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
              aot: bool, shake: bool) -> Result<String> {
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
    out.push('}');
    Ok(out)
}

fn compile(srcs: &[PathBuf], entry: &str, out_path: &Path, aot: bool) -> Result<()> {
    let slots = parse_slots(if aot { SLOTS_AOT } else { SLOTS })?;
    let base = if aot { RUNTIME_AOT } else { RUNTIME };
    let spec = build_spec(srcs, entry, &slots, aot, true)?;

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
    let spec = build_spec(srcs, entry, &parse_slots(SLOTS)?, false, false)?;
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

  flint compile :src <dir> :fn <ns/fn> :out <file.wasm> [--aot]
      Compile to a standalone wasm module, for any wasm host. `--aot`
      compiles each arity to wasm as well: bigger, and much faster on
      arithmetic.

  flint run :src <dir> :fn <ns/fn> [:grant <name>] [-- args...]
      Compile and run, here. Nothing is written: flint's runtime is compiled
      into this binary, so a program can be run without producing an artifact.

  flint version

`:src` may be given more than once. Everything is embedded -- the compiler,
the runtime and the standard library -- so there is nothing to install: no
babashka, no JVM, no linker."
    );
    std::process::exit(2)
}

/// `:src d :fn ns/f :out o --aot --` in the style `bin/flint` already uses.
/// Anything after `--` is the program's own arguments.
struct Args {
    srcs: Vec<PathBuf>,
    entry: Option<String>,
    out: Option<String>,
    grants: Vec<String>,
    aot: bool,
    rest: Vec<String>,
}

fn parse(args: &[String]) -> Result<Args> {
    let mut a = Args { srcs: Vec::new(), entry: None, out: None,
                       grants: Vec::new(), aot: false, rest: Vec::new() };
    let mut i = 0;
    while i < args.len() {
        let need = |k: &str, i: usize| -> Result<String> {
            args.get(i + 1).cloned().with_context(|| format!("{k} needs a value"))
        };
        match args[i].as_str() {
            "--" => { a.rest = args[i + 1..].to_vec(); break }
            ":src" => { a.srcs.push(PathBuf::from(need(":src", i)?)); i += 2 }
            ":fn" => { a.entry = Some(need(":fn", i)?); i += 2 }
            ":out" | ":o" => { a.out = Some(need(":out", i)?); i += 2 }
            ":grant" => { a.grants.push(need(":grant", i)?); i += 2 }
            "--aot" => { a.aot = true; i += 1 }
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
            compile(&a.srcs, &entry, Path::new(&out), a.aot)
        }
        "run" => {
            let a = parse(&argv[1..])?;
            let Some(entry) = a.entry else { bail!("run needs :fn ns/fn") };
            if a.srcs.is_empty() {
                bail!("run needs at least one :src");
            }
            if a.aot {
                bail!("--aot is a property of a compiled MODULE, and `run` produces none.\n\
                       Use `flint compile --aot` for that.");
            }
            std::process::exit(run_source(&a.srcs, &entry, &a.rest, &a.grants)?);
        }
        other => {
            eprintln!("flint: no such command `{other}`");
            usage()
        }
    }
}
