// The flint SDK: compile Clojure and run it, in any JavaScript runtime.
//
// Two wasm artifacts do all of it, and both are EMBEDDED -- there is no
// filesystem here, no `node:` import and no fetch. It is one ESM module that
// works the same in a browser, in node, in a Worker and in Deno.
//
//   flintc.wasm        the compiler. Source in, a wasm MODULE out.
//   flint-runtime.wasm the runtime a compiled module is spliced into.
//
// Neither needs babashka, a JVM, a Rust toolchain or a linker: the runtime was
// linked once, when flint was built, and compiling splices into it
// (`doc/decisions/0024`).
//
// The artifacts are imported with `with { type: 'bytes' }`, which esbuild
// turns into an inline `Uint8Array` -- so the published package is one file
// and a consumer's bundler has nothing to resolve.

import COMPILER from '../../../dist/flintc.wasm' with { type: 'bytes' };
import RUNTIME from '../../../dist/flint-runtime.wasm' with { type: 'bytes' };
import RUNTIME_AOT from '../../../dist/flint-runtime-aot.wasm' with { type: 'bytes' };
import SLOTS_JSON from '../../../dist/slots.json' with { type: 'bytes' };
import SLOTS_AOT_JSON from '../../../dist/slots-aot.json' with { type: 'bytes' };
import STDLIB_JSON from '../gen/stdlib.json' with { type: 'bytes' };

// Arguments, the pump, capabilities. Shared with `host/flint.mjs`, which is a
// node wrapper over the same file.
import { instantiate } from './guest.js';

const utf8 = (bytes) => new TextDecoder().decode(bytes);
const parse = (bytes) => JSON.parse(utf8(bytes));

/// flint's own `clojure.core` and everything it requires. Every program needs
/// them and a caller should not have to know that.
let cachedLib = null;
export function standardLibrary() {
  if (!cachedLib) cachedLib = parse(STDLIB_JSON);
  return cachedLib;
}

/// Which table slot each builtin sits in, for the shipped runtime. A program
/// spliced into that module has to name ITS table, and a slot is a property of
/// the artifact rather than of the compiler.
let cachedSlots = null;
export function runtimeSlots() {
  if (!cachedSlots) cachedSlots = parse(SLOTS_JSON);
  return cachedSlots;
}

let cachedSlotsAot = null;
export function aotRuntimeSlots() {
  if (!cachedSlotsAot) cachedSlotsAot = parse(SLOTS_AOT_JSON);
  return cachedSlotsAot;
}

/// Every builtin the shipped runtime carries. The compiler needs it to tell a
/// real `flint.rt/add` from a typo -- with an EMPTY set it assumes every name
/// is real, and the failure surfaces much later and much less clearly.
export function loaderBuiltins() {
  return Object.keys(runtimeSlots());
}

/// The raw artifacts, for a caller that wants to splice or instantiate by hand.
export const artifacts = { compiler: COMPILER, runtime: RUNTIME, runtimeAot: RUNTIME_AOT };

async function moduleFrom(source) {
  if (source instanceof WebAssembly.Module) return source;
  if (source instanceof Uint8Array || source instanceof ArrayBuffer) {
    return WebAssembly.compile(source);
  }
  throw new TypeError(
    'pass wasm bytes or a WebAssembly.Module. There is no filesystem here: ' +
    'the artifacts are embedded, so `Compiler.load()` with no argument is ' +
    'the usual call.');
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

  static async load(source = COMPILER) {
    return new Compiler(await moduleFrom(source));
  }

  /// Compile a program to a standalone `.wasm` module.
  ///
  /// | | |
  /// | --- | --- |
  /// | `files` | `{ 'path.cljc': source }`; flint's standard library is added |
  /// | `entry` | `'my.app/main'` |
  /// | `aot`   | compile each arity to wasm too: bigger, much faster on arithmetic |
  /// | `shake` | cut the runtime to what the program reaches (on by default) |
  ///
  /// No linker is involved, which is what makes this possible at all: the
  /// runtime module was linked once, when flint was built, and compiling
  /// splices into it (`doc/decisions/0024`).
  compile({ files, entry, aot = false, shake = true, runtime, slots,
            memoryLimit = 3_000_000_000,
            builtins, features, standardLibrary: withLib = true }) {
    if (!entry) throw new Error('compile needs an entry, e.g. "my.app/main"');
    const base = runtime ?? (aot ? RUNTIME_AOT : RUNTIME);
    const table = slots ?? (aot ? aotRuntimeSlots() : runtimeSlots());
    const all = withLib ? { ...standardLibrary(), ...files } : { ...files };
    const spec = `{:files ${edn(all)} :entry ${entry}` +
                 // STRINGS, not symbols: the analyzer compares a builtin name
                 // as text, and `#{= nil?}` would match nothing while looking
                 // exactly like it should.
                 ` :builtins ${edn(new Set(builtins ?? Object.keys(table)))}` +
                 ` :slots ${edn(table)}` +
                 (aot ? ' :aot true' : '') +
                 (shake ? ' :shake true' : '') +
                 (features ? ` :features ${edn(new Set(features.map((f) => sym(`:${f}`))))}` : '') +
                 '}';
    const inst = instantiate(this.module);
    // Compiling a whole program, appending its compiled arities and then tree
    // shaking the result is the most memory this ever does, and the default
    // cap is 512 MB. Past it an allocation answers NIL, the NIL reaches the
    // tree, and the failure surfaces as `memory access out of bounds` with
    // nothing pointing at the cap.
    if (inst.exports.set_memory_limit) inst.exports.set_memory_limit(memoryLimit);
    // The runtime module goes as its own ARGUMENT, not inside the spec: three
    // quarters of a megabyte of base64 in an EDN string is three quarters of a
    // megabyte for flint's reader to scan a character at a time, and that alone
    // was 198 seconds of a 199-second compile.
    const r = inst.main('wasm', spec, base64Encode(base));
    if (r.code !== 0) throw new Error(`flint: ${r.out.trim()}`);
    if (r.out.startsWith('!missing')) {
      const missing = r.out.split('\n').slice(1).filter(Boolean);
      throw new Error(
        `flint: no source for ${missing.join(', ')}. ` +
        'Every namespace a program requires has to be in `files`.');
    }
    return base64Decode(r.out.trim());
  }
}

/// A compiled program: an opaque handle over the module, with the host side of
/// the conversation attached.
///
/// A handle rather than raw bytes because running one is not just calling a
/// function. A program with green threads can PARK on a port the host holds --
/// that is what a capability is -- and somebody has to service the request and
/// resume it. `program.wasm` is the artifact, for writing to a file or handing
/// to another host; everything else here is that conversation.
export class Program {
  constructor(module, wasm) {
    this.module = module;
    /// The `.wasm` bytes. This is the artifact.
    this.wasm = wasm;
  }

  static async load(wasm) {
    return new Program(await moduleFrom(wasm), wasm);
  }

  /// Run it. Instantiating is the expensive part, and this does it per call on
  /// purpose: a program's initialisers re-run on load, so two runs share
  /// nothing, which is the property a per-request binding wants.
  ///
  /// | | |
  /// | --- | --- |
  /// | `capabilities` | `{ name: handler }` -- what this run may reach |
  /// | `stepLimit` | a bound on WORK: deterministic, so the same everywhere |
  ///
  /// A capability is granted, never claimed: the guest asks by name, the host
  /// decides, and authority is the host's grant table rather than any property
  /// of the value (`doc/decisions/0022`).
  run(args = [], { capabilities, stepLimit = 0 } = {}) {
    const inst = instantiate(this.module, { stepLimit });
    if (capabilities) inst.capabilities(capabilities);
    return inst.main(...args);
  }

  /// The instance itself, for a caller that wants to drive several calls
  /// against one instantiation, or to reach the module's exports.
  open({ capabilities, stepLimit = 0 } = {}) {
    const inst = instantiate(this.module, { stepLimit });
    if (capabilities) inst.capabilities(capabilities);
    return inst;
  }
}

/// Compile and run, for the case that just wants an answer.
export async function evaluate({ files, entry, args = [], compiler, ...opts }) {
  const c = compiler instanceof Compiler ? compiler : await Compiler.load(compiler);
  const p = await Program.load(c.compile({ files, entry, ...opts }));
  return p.run(args, opts);
}

export const capabilities = {
  compile: true,
  run: true,
  aot: true,
  /// What is NOT here: producing the runtime module itself. That is a link
  /// over relocatable objects and needs `wasm-ld`; it happens when flint is
  /// built, and the result is embedded above.
  linkRuntime: false,
};
