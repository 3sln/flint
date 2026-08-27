// The flint SDK: compile Clojure and run it, with no toolchain.
//
// Two wasm artifacts do all of it.
//
//   flintc.wasm        the compiler. Source in, a bytecode IMAGE out.
//   flint-loader.wasm  the runtime. An image in, its answer out.
//
// Neither needs babashka, a JVM, a Rust toolchain or a linker, which is the
// point: `bin/flint` needs all of those and cannot run anywhere but a
// developer machine. What it can do that this cannot is emit a standalone
// single-file `.wasm` module -- that requires LINKING, and linking requires
// `wasm-ld`. See `aotAvailable` below for what that costs.
//
// The loader resolves an image's builtins BY NAME when it loads it, so nothing
// here has to patch table slots or know anything about the image format.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

export const DEFAULT_COMPILER = path.join(root, 'dist', 'flintc.wasm');
export const DEFAULT_LOADER = path.join(root, 'dist', 'flint-loader.wasm');
export const DEFAULT_RUNTIME = path.join(root, 'dist', 'flint-runtime.wasm');
const SLOT_MANIFEST = path.join(root, 'dist', 'slots.json');
const BUILTIN_MANIFEST = path.join(root, 'dist', 'builtins.json');

let cachedBuiltins = null;
/// Every builtin the shipped loader carries. The compiler needs this to tell a
/// `flint.rt/add` that exists from one that does not -- and with an EMPTY set
/// it assumes every name is real, which resolves `flint.rt/add` to the
/// unprefixed `add` and fails much later with "builtin `add` is not available
/// at compile time". Generated beside the loader, because it is a property of
/// that artifact and not of this file.
export function loaderBuiltins() {
  if (!cachedBuiltins) {
    try {
      cachedBuiltins = JSON.parse(readFileSync(BUILTIN_MANIFEST, 'utf8'));
    } catch {
      throw new Error(
        `flint: ${BUILTIN_MANIFEST} is missing. It is generated beside the ` +
        'loader and lists the builtins that loader carries; without it the ' +
        'compiler cannot resolve `flint.rt/...` calls.');
    }
  }
  return cachedBuiltins;
}

/// Every `.cljc` under `dir`, keyed by its path relative to `dir` -- which is
/// exactly how a namespace maps to a file, so the compiler can find them.
export function readSourceDir(dir, into = {}) {
  const walk = (d, prefix) => {
    for (const name of readdirSync(d)) {
      const full = path.join(d, name);
      const rel = prefix ? `${prefix}/${name}` : name;
      if (statSync(full).isDirectory()) walk(full, rel);
      else if (name.endsWith('.cljc') || name.endsWith('.clj')) {
        into[rel] = readFileSync(full, 'utf8');
      }
    }
  };
  walk(dir, '');
  return into;
}

let cachedLib = null;
/// flint's own `clojure.core` and friends. Every program needs them, and a
/// caller should not have to know that.
export function standardLibrary() {
  if (!cachedLib) cachedLib = readSourceDir(path.join(root, 'lib'));
  return cachedLib;
}

// --- the module ABI --------------------------------------------------------
//
// A flint module takes a vector of strings and returns a string. Kept here
// rather than imported from `host/flint.mjs` so the SDK is one file with one
// dependency -- the wasm artifacts -- and can be vendored.

function bind(instance) {
  const e = instance.exports;
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const push = (s) => {
    const b = enc.encode(String(s));
    const p = e.arg_alloc(b.length);
    new Uint8Array(e.memory.buffer).set(b, p);
    e.arg_push(p, b.length);
  };
  return {
    exports: e,
    main(...args) {
      for (const a of args) push(a);
      const code = e.main();
      const out = dec.decode(
        new Uint8Array(e.memory.buffer).subarray(e.out_ptr(), e.out_ptr() + e.out_len()));
      // Status 2 is a green thread parked on a port the HOST holds the other
      // end of, and servicing that is a whole event loop (`host/flint.mjs`).
      // This SDK is the compile-and-answer shape; say so rather than returning
      // a half-finished result.
      if (code === 2) {
        throw new Error(
          'this program parked on a host port. The SDK runs programs that ' +
          'return an answer; one that talks to the host needs the full host ' +
          'loop in `host/flint.mjs`.');
      }
      return { code, out };
    },
  };
}

async function moduleFrom(source) {
  if (source instanceof WebAssembly.Module) return source;
  const bytes = source instanceof Uint8Array ? source : readFileSync(source);
  return WebAssembly.compile(bytes);
}

let cachedSlots = null;
/// Which table slot each builtin sits in, for the shipped runtime module. An
/// image spliced into that module has to name ITS table, and a slot is a
/// property of the artifact rather than of the compiler.
export function runtimeSlots() {
  if (!cachedSlots) cachedSlots = JSON.parse(readFileSync(SLOT_MANIFEST, 'utf8'));
  return cachedSlots;
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function base64Encode(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i], b1 = bytes[i + 1] ?? 0, b2 = bytes[i + 2] ?? 0;
    const t = (b0 << 16) | (b1 << 8) | b2;
    out += B64[(t >> 18) & 63] + B64[(t >> 12) & 63] +
           (i + 1 < bytes.length ? B64[(t >> 6) & 63] : '=') +
           (i + 2 < bytes.length ? B64[t & 63] : '=');
  }
  return out;
}

function base64Decode(s) {
  const clean = s.replace(/[^A-Za-z0-9+/]/g, '');
  const out = new Uint8Array((clean.length * 3) >> 2);
  let o = 0;
  for (let i = 0; i < clean.length; i += 4) {
    const n = (B64.indexOf(clean[i]) << 18) | (B64.indexOf(clean[i + 1]) << 12) |
              ((B64.indexOf(clean[i + 2]) & 63) << 6) | (B64.indexOf(clean[i + 3]) & 63);
    out[o++] = (n >> 16) & 255;
    if (i + 2 < clean.length) out[o++] = (n >> 8) & 255;
    if (i + 3 < clean.length) out[o++] = n & 255;
  }
  return out.subarray(0, o);
}

function edn(v) {
  if (typeof v === 'string') return JSON.stringify(v);
  if (Array.isArray(v)) return `[${v.map(edn).join(' ')}]`;
  if (v instanceof Set) return `#{${[...v].map(edn).join(' ')}}`;
  if (v && v.sym) return v.sym;                    // an unquoted symbol
  if (v && typeof v === 'object') {
    return `{${Object.entries(v).map(([k, x]) => `${edn(k)} ${edn(x)}`).join(' ')}}`;
  }
  return String(v);
}
const sym = (s) => ({ sym: s });
const kw = (s) => ({ sym: `:${s}` });

// --- the API ---------------------------------------------------------------

export class Compiler {
  constructor(module) { this.module = module; }

  static async load(source = DEFAULT_COMPILER) {
    return new Compiler(await moduleFrom(source));
  }

  /// Compile a program to a bytecode image.
  ///
  /// `files` maps a source path -- `"my/app.cljc"` -- to its text. flint's own
  /// standard library is added unless `standardLibrary: false`. `entry` is the
  /// function to run, as `"my.app/main"`.
  compile({ files, entry, builtins, features, standardLibrary: withLib = true }) {
    if (!entry) throw new Error('compile needs an entry, e.g. "my.app/main"');
    const all = withLib ? { ...standardLibrary(), ...files } : { ...files };
    const bs = builtins ?? loaderBuiltins();
    const spec = `{:files ${edn(all)} :entry ${entry}` +
                 // STRINGS, not symbols: the analyzer compares a builtin name
                 // as text, and `#{= nil?}` would match nothing while looking
                 // exactly like it should.
                 ` :builtins ${edn(new Set(bs))}` +
                 (features ? ` :features ${edn(new Set(features.map((f) => sym(`:${f}`))))}` : '') +
                 '}';
    const inst = bind(new WebAssembly.Instance(this.module, {}));
    const r = inst.main('project', spec);
    if (r.code !== 0) throw new Error(`flint: ${r.out.trim()}`);
    if (r.out.startsWith('!missing')) {
      const missing = r.out.split('\n').slice(1).filter(Boolean);
      throw new Error(
        `flint: no source for ${missing.join(', ')}. ` +
        'Every namespace a program requires has to be in `files`.');
    }
    const nl = r.out.indexOf('\n');
    return base64Decode(nl < 0 ? r.out : r.out.slice(0, nl));
  }

  /// Compile a program to a standalone `.wasm` MODULE.
  ///
  /// The image `compile` returns is internal machinery; this is the artifact.
  /// It is produced by splicing that image into a prebuilt runtime module --
  /// no linker anywhere, because the runtime was linked once when flint was
  /// built (`doc/decisions/0024`).
  ///
  /// `aot: true` appends compiled arities as well, which is the same
  /// operation: wasm cannot add a function to a module that already exists, so
  /// they go in at build time, and appending needs no `wasm-ld`.
  /// `shake` cuts the runtime down to what this program reaches: about 40%
  /// off, for a module that runs the same.
  compileToWasm({ files, entry, aot = false, shake = true, runtime, slots,
                  memoryLimit = 3_000_000_000,
                  builtins, features, standardLibrary: withLib = true }) {
    if (!entry) throw new Error('compileToWasm needs an entry, e.g. "my.app/main"');
    const base = runtime instanceof Uint8Array ? runtime : readFileSync(runtime ?? DEFAULT_RUNTIME);
    const table = slots ?? runtimeSlots();
    const all = withLib ? { ...standardLibrary(), ...files } : { ...files };
    const spec = `{:files ${edn(all)} :entry ${entry}` +
                 ` :builtins ${edn(new Set(builtins ?? Object.keys(table)))}` +
                 ` :slots ${edn(table)}` +
                 (aot ? ' :aot true' : '') +
                 (shake ? ' :shake true' : '') +
                 (features ? ` :features ${edn(new Set(features.map((f) => sym(`:${f}`))))}` : '') +
                 '}';
    const inst = bind(new WebAssembly.Instance(this.module, {}));
    // Compiling a whole program, appending its compiled arities and then tree
    // shaking the result is the most memory this ever does, and the default
    // cap is 512 MB. Past it an allocation answers NIL, the NIL reaches the
    // tree, and the failure surfaces as `memory access out of bounds` with
    // nothing pointing at the cap.
    if (inst.exports.set_memory_limit) inst.exports.set_memory_limit(memoryLimit);
    // The module goes as its own ARGUMENT, not inside the spec: three-quarters
    // of a megabyte of base64 in an EDN string is three-quarters of a megabyte
    // for flint's reader to scan a character at a time, and that alone was 198
    // seconds of a 199-second compile.
    const r = inst.main('wasm', spec, base64Encode(base));
    if (r.code !== 0) throw new Error(`flint: ${r.out.trim()}`);
    if (r.out.startsWith('!missing')) {
      const missing = r.out.split('\n').slice(1).filter(Boolean);
      throw new Error(`flint: no source for ${missing.join(', ')}.`);
    }
    return base64Decode(r.out.trim());
  }
}

export class Runtime {
  constructor(module) { this.module = module; }

  static async load(source = DEFAULT_LOADER) {
    return new Runtime(await moduleFrom(source));
  }

  /// Run an image. A fresh instance every time: an image's initialisers run on
  /// load, so two runs share nothing, which is the property a per-request
  /// binding wants.
  run(image, args = [], { capabilities } = {}) {
    const inst = bind(new WebAssembly.Instance(this.module, {}));
    const e = inst.exports;
    if (!e.flint_load_image) {
      throw new Error('this module was not built with --loader: it cannot load an image');
    }
    if (capabilities && e.flint_grant) {
      for (const name of capabilities) {
        const b = new TextEncoder().encode(name);
        const p = e.arg_alloc(b.length);
        new Uint8Array(e.memory.buffer).set(b, p);
        e.flint_grant(p, b.length);
      }
    }
    const p = e.arg_alloc(image.length);
    new Uint8Array(e.memory.buffer).set(image, p);
    const rc = e.flint_load_image(p, image.length);
    if (rc !== 0) {
      const why = new TextDecoder().decode(
        new Uint8Array(e.memory.buffer).subarray(e.out_ptr(), e.out_ptr() + e.out_len()));
      throw new Error(`could not load the image (${rc}): ${why}`);
    }
    return inst.main(...args);
  }
}

/// The whole thing, for the case that just wants an answer.
export async function evaluate({ files, entry, args = [], compiler, loader, ...opts }) {
  const c = compiler instanceof Compiler ? compiler : await Compiler.load(compiler);
  const r = loader instanceof Runtime ? loader : await Runtime.load(loader);
  return r.run(c.compile({ files, entry, ...opts }), args);
}

/// Whether this SDK can produce a standalone `.wasm` module, and AOT-compile
/// it. It cannot, and saying so beats a caller discovering it: linking a module
/// means running `wasm-ld` over relocatable objects, which is a native tool.
/// `bin/flint` does that; this ships the two artifacts it produced.
export const aotAvailable = true;
export const capabilities = {
  compile: true,
  run: true,
  /// A standalone module, without a linker: the image is spliced into a
  /// prebuilt runtime that was linked once, when flint was built.
  emitModule: true,
  aot: true,
  /// What is still not here: producing the RUNTIME module itself. That is a
  /// link over relocatable objects and needs `wasm-ld`; it happens when flint
  /// is built, and the result ships in `dist/`.
  linkRuntime: false,
};
