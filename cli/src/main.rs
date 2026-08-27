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

/// The compiler, as a bytecode image rather than a wasm module: this runs it
/// natively, so the module would only be a wasm engine's worth of indirection.
static COMPILER: &[u8] = include_bytes!("../../dist/flintc.image");
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

fn compile(srcs: &[PathBuf], entry: &str, out_path: &Path, aot: bool, image: bool) -> Result<()> {
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

    let slots = parse_slots(if aot { SLOTS_AOT } else { SLOTS })?;
    let base = if aot { RUNTIME_AOT } else { RUNTIME };

    let spec = {
        let mut s = String::from("{:files {");
        for (k, v) in &files {
            s.push_str(&edn_string(k));
            s.push(' ');
            s.push_str(&edn_string(v));
            s.push(' ');
        }
        s.push_str("} :entry ");
        s.push_str(entry);
        s.push_str(" :builtins #{");
        for k in slots.keys() {
            s.push_str(&edn_string(k));
            s.push(' ');
        }
        s.push_str("} :slots {");
        for (k, v) in &slots {
            s.push_str(&edn_string(k));
            s.push_str(&format!(" {v} "));
        }
        s.push('}');
        if aot {
            s.push_str(" :aot true");
        }
        s.push_str(" :shake true");
        s.push('}');
        s
    };

    let mut p = Program::load(COMPILER, 3_000_000_000)
        .map_err(|e| anyhow::anyhow!("the embedded compiler did not load: {e}"))?;

    // An IMAGE is what this binary can run: it has flint's runtime compiled in
    // and no wasm engine, so a module would need one. It is also far smaller --
    // the runtime is not repeated in every artifact.
    if image {
        let r = p.run(&["project", &spec]);
        if r.code != 0 {
            bail!("{}", r.out.trim());
        }
        if let Some(rest) = r.out.strip_prefix("!missing") {
            bail!("no source for{}", rest.replace('\n', " "));
        }
        let b64 = r.out.split('\n').next().unwrap_or("");
        let bytes = base64_decode(b64)?;
        fs::write(out_path, &bytes)?;
        eprintln!("wrote {} ({} bytes, image)", out_path.display(), bytes.len());
        return Ok(());
    }

    // The runtime module goes as its own ARGUMENT, never inside the spec: it is
    // three-quarters of a megabyte of base64, and inside an EDN string it is
    // three-quarters of a megabyte for flint's reader to scan a character at a
    // time -- 198 seconds against 10.
    let r = p.run(&["wasm", &spec, &base64(base)]);
    let text = r.out;
    if r.code != 0 {
        bail!("{}", text.trim());
    }
    if let Some(rest) = text.strip_prefix("!missing") {
        bail!("no source for{}\nevery namespace a program requires has to be on the source path",
              rest.replace('\n', " "));
    }
    let module = base64_decode(text.trim())?;
    fs::write(out_path, &module)?;
    eprintln!("wrote {} ({} bytes{})", out_path.display(), module.len(),
              if aot { ", compiled arities" } else { "" });
    Ok(())
}

/// Run a compiled artifact.
///
/// A `.wasm` MODULE is not runnable here: it is the artifact for a wasm host,
/// and this binary has no wasm engine in it. An IMAGE is, and that is what the
/// compiler emits for this path. Saying which is which beats a trap.
fn run_program(path: &Path, args: &[String]) -> Result<i32> {
    let bytes = fs::read(path).with_context(|| format!("cannot read {}", path.display()))?;
    if bytes.starts_with(b"\0asm") {
        bail!(
            "{} is a wasm MODULE, and this binary has no wasm engine in it.\n\
             Run it with a wasm host -- `node host/flint.mjs {}` -- or compile \n\
             to an image with `--image` and run that here.",
            path.display(), path.display()
        );
    }
    let mut p = Program::load(&bytes, 2_000_000_000)
        .map_err(|e| anyhow::anyhow!("{}: {e}", path.display()))?;
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    let r = p.run(&refs);
    print!("{}", r.out);
    Ok(r.code)
}

fn usage() -> ! {
    eprintln!(
        "flint {VERSION} -- the compiler, as one binary

  flint compile <src>... --entry ns/fn -o <out> [--aot] [--image]
      Compile. By default a standalone `.wasm` module, for any wasm host.
      `--aot` compiles each arity to wasm as well: bigger, much faster on
      arithmetic. `--image` emits a bytecode image instead -- far smaller,
      and what `flint run` takes, because this binary has flint's runtime
      compiled in and no wasm engine.

  flint run <program.image> [args...]
      Run an image, natively.

  flint version

Everything is embedded -- the compiler, the runtime and the standard library.
There is nothing to install: no babashka, no JVM, no linker."
    );
    std::process::exit(2)
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() {
        usage();
    }
    match args[0].as_str() {
        "version" | "--version" | "-v" => {
            println!("flint {VERSION}");
            Ok(())
        }
        "help" | "--help" | "-h" => usage(),
        "compile" => {
            let mut srcs = Vec::new();
            let (mut entry, mut out, mut aot, mut image) = (None, None, false, false);
            let mut i = 1;
            while i < args.len() {
                match args[i].as_str() {
                    "--entry" | "-e" => { entry = args.get(i + 1).cloned(); i += 2 }
                    "-o" | "--out" => { out = args.get(i + 1).cloned(); i += 2 }
                    "--aot" => { aot = true; i += 1 }
                    "--image" => { image = true; i += 1 }
                    other => { srcs.push(PathBuf::from(other)); i += 1 }
                }
            }
            let Some(entry) = entry else { bail!("compile needs --entry ns/fn") };
            let out = out.unwrap_or_else(|| {
                if image { "out.image".to_string() } else { "out.wasm".to_string() }
            });
            if srcs.is_empty() {
                bail!("compile needs at least one source file or directory");
            }
            if image && aot {
                bail!("--image and --aot are different artifacts: compiled arities are \n\
                       wasm functions appended to a module, and an image has no module \n\
                       to append them to.");
            }
            compile(&srcs, &entry, Path::new(&out), aot, image)
        }
        "run" => {
            let Some(m) = args.get(1) else { bail!("run needs a module") };
            let code = run_program(Path::new(m), &args[2..])?;
            std::process::exit(code);
        }
        other => {
            eprintln!("flint: no such command `{other}`");
            usage()
        }
    }
}
