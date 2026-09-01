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

export { codec } from './codec.js';
import { codec } from './codec.js';

export class Compiler {
  constructor(module) { this.module = module; }

  static async load(source = COMPILER) {
    return new Compiler(await moduleFrom(source));
  }

  /// Compile to an Image.
  ///
  /// Source comes from a RESOLVER -- a namespace to its bytes -- rather than a
  /// directory, because there is no filesystem in a browser, in a Worker or
  /// inside another sandbox, and sources may come from a bundle, a database or
  /// a map already in memory. `files` is the convenience for the common case
  /// of having them all to hand.
  ///
  /// | | |
  /// | --- | --- |
  /// | `resolve` | `(namespace) => source \| null` |
  /// | `files`   | `{ 'path.cljc': source }`, an alternative to `resolve` |
  /// | `fn`      | the function a default `run` would call |
  /// | `exports` | every other function that must stay callable |
  /// | `optimize` | `['perf']` compiles each arity; `['size']` interprets |
  /// | `shake`   | cut the runtime to what the program reaches (on by default) |
  /// | `meta`    | arbitrary metadata to record in the artifact |
  compile({ resolve, files, fn, entry, exports, optimize = [], shake = true,
            runtime, slots, meta, memoryLimit = 3_000_000_000,
            builtins, features, standardLibrary: withLib = true }) {
    const target = fn ?? entry;
    if (!target) throw new Error('compile needs `fn`, e.g. "my.app/main"');
    // An ORDERED preference list. The first token this build understands
    // decides; the rest are what the caller would have wanted otherwise, and
    // anything unrecognised is ignored -- which is what makes a list written
    // against a newer flint still get this one's best effort.
    const known = { perf: true, size: false };
    const aot = optimize.map((o) => String(o).replace(/^:/, ''))
                        .map((o) => known[o]).find((v) => v !== undefined) ?? false;
    const base = runtime ?? (aot ? RUNTIME_AOT : RUNTIME);
    const table = slots ?? (aot ? aotRuntimeSlots() : runtimeSlots());
    const all = collectSources({ resolve, files, target, withLib });
    // Everything that must stay CALLABLE. Only reachable code ships (`0002`),
    // and a function nobody calls from the entry is exactly the one a host
    // wants to call -- so a sandbox's callable set has to be declared. The
    // default is every function in the entry's own namespace, because that is
    // what a caller almost always means.
    const keep = exports ?? [];
    const spec = `{:files ${edn(all)} :entry ${target}` +
                 (keep.length ? ` :exports [${keep.map((k) => String(k)).join(' ')}]` : '') +
                 // STRINGS, not symbols: the analyzer compares a builtin name
                 // as text, and `#{= nil?}` would match nothing while looking
                 // exactly like it should.
                 ` :builtins ${edn(new Set(builtins ?? Object.keys(table)))}` +
                 ` :slots ${edn(table)}` +
                 (aot ? ' :aot true' : '') +
                 (shake ? ' :shake true' : '') +
                 // Recorded IN the artifact, not just kept beside it: an image
                 // written to disk has to still say what it needs. flint never
                 // reads it (`0025`).
                 (meta ? ` :meta ${edn(meta)}` : '') +
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
    const r = inst.run('flint.selfhost/main', ['wasm', spec, base64Encode(base)]);
    if (r.code !== 0) throw new Error(`flint: ${r.out.trim()}`);
    if (r.out.startsWith('!missing')) {
      const missing = r.out.split('\n').slice(1).filter(Boolean);
      throw new Error(
        `flint: no source for ${missing.join(', ')}. ` +
        'Every namespace a program requires has to be resolvable.');
    }
    return new Image(base64Decode(r.out.trim()), meta);
  }
}

/// Every namespace the target reaches, from a resolver or a map.
///
/// The compiler resolves `:require`s itself, so what it needs is every file it
/// might ask for. With `files` that is the map; with `resolve` it is what the
/// resolver answers, and the compiler names anything missing.
function collectSources({ resolve, files, target, withLib }) {
  const all = withLib ? { ...standardLibrary() } : {};
  if (files) Object.assign(all, files);
  if (resolve) {
    // A resolver is asked by NAMESPACE and answers with source; the compiler
    // wants them keyed by the path a namespace maps to.
    const seen = new Set();
    const want = [target.split('/')[0]];
    while (want.length) {
      const ns = want.pop();
      if (seen.has(ns)) continue;
      seen.add(ns);
      const src = resolve(ns);
      if (src == null) continue;
      const path = ns.replace(/-/g, '_').replace(/\./g, '/') + '.cljc';
      all[path] = src;
      // Follow its requires, so a resolver is asked only for what is reached.
      for (const m of String(src).matchAll(/\[([a-zA-Z0-9._-]+)\s/g)) want.push(m[1]);
    }
  }
  return all;
}

/// A compiled artifact: inert, holds functions, says what it is.
///
/// `image.wasm` is the bytes -- write them to a file, hand them to another
/// host. Everything else here is what the artifact says about itself, read
/// without instantiating anything.
export class Image {
  constructor(wasm, meta) {
    this.wasm = wasm;
    this._meta = meta ?? null;
    this._module = null;
  }

  /// Arbitrary metadata the compiler recorded. The runtime does not interpret
  /// it -- what a key means is between whoever wrote it and whoever reads it.
  /// `flint compile :with [:fs]` writes `{capabilities: ['fs']}` here, which
  /// is the CLI's convention and nobody else's.
  get metadata() { return this._meta ?? {}; }

  /// What the CLI's convention says this image will ask for. A REQUEST, not a
  /// grant: the host still decides (`doc/decisions/0022`).
  get capabilities() { return this.metadata.capabilities ?? []; }

  /// Instantiate it. A sandbox holds state and serves many calls.
  async sandbox(opts = {}) {
    if (!this._module) this._module = await moduleFrom(this.wasm);
    return new Sandbox(this._module, opts);
  }
}

/// An image instantiated: state, capabilities, and calls.
///
/// A sandbox serves MANY calls, and they share the state the image set up when
/// it loaded -- initialisers run once, not per call, which is what makes
/// instantiate-once-call-per-request work.
/// Who advances a sandbox, and when (`doc/decisions/0028`).
///
/// A sandbox does not run because someone called into it. It runs because it
/// has work and a driver decided to give it a thread. The indirection is the
/// point: a host that calls straight into a sandbox has decided forever that
/// the sandbox runs on the caller's thread, which forecloses a pool.
///
/// The vocabulary is identical in every SDK and only the ANSWER differs. On
/// wasm that answer is 1: several executors in one sandbox needs the threads
/// proposal, atomics, a shared-memory build and cross-origin isolation, none
/// of which exists yet. So `new ThreadPool(4)` here reports a parallelism of
/// 1 -- not a refusal, because portable code could not then be written, and
/// not silence either, because a driver that quietly gives one thread when
/// asked for four is a performance mystery with no evidence in it.
export class Driver {
  /// How many threads may be inside one sandbox at once. READ IT rather than
  /// assume it.
  get parallelism() { return 1; }

  /// There is work for this sandbox. Cheap to call redundantly: N wakes
  /// between two runs must cost ONE dispatch, not N.
  wake(core) { core.schedule() && core.advance(); }
}

/// Runs the sandbox on whichever thread woke it. The default.
export class Inline extends Driver {}

/// K threads, where the target can give them. On wasm it cannot, so this is
/// `Inline` with `requested` recorded and `parallelism` still 1.
export class ThreadPool extends Driver {
  constructor(threads = 4, { debounceMs = 0 } = {}) {
    super();
    this.requested = threads;
    this.debounceMs = debounceMs;
  }
  /// What you actually got. See the note on `Driver`.
  get parallelism() { return 1; }

  wake(core) {
    if (!core.schedule()) return;
    if (this.debounceMs > 0) {
      // Bounded, always. Coalescing without a bound is a deadlock with a
      // plausible explanation: a runnable sandbox has to eventually run.
      setTimeout(() => core.advance(), this.debounceMs);
    } else {
      // A microtask, so a burst of synchronous calls lands in ONE dispatch.
      queueMicrotask(() => core.advance());
    }
  }
}

export class Sandbox {
  constructor(module, { capabilities, stepLimit = 0, driver } = {}) {
    this.inst = instantiate(module, { stepLimit });
    if (capabilities) this.inst.capabilities(capabilities);
    this._caps = capabilities;
    this._driver = driver ?? new Inline();
    // The inbox is what makes coalescing safe: a wake that is folded into
    // another loses no work, because the work was never in the wake.
    this._inbox = [];
    this._scheduled = false;
    this._dispatches = 0;
    this._served = 0;
  }

  /// How many threads may be inside this sandbox at once. On wasm, 1.
  get parallelism() { return this._driver.parallelism; }

  /// Claim the right to queue this sandbox. True exactly once per dispatch --
  /// THE coalescing flag.
  schedule() {
    if (this._scheduled) return false;
    this._scheduled = true;
    return true;
  }

  /// Run whatever is in the inbox, then whatever arrived while doing so.
  /// Called by a driver, never by a caller.
  advance() {
    for (;;) {
      const batch = this._inbox;
      if (batch.length === 0) {
        // Clear and re-check, in that order and both: a request can land
        // between the drain and the clear, and clearing without looking again
        // leaves it queued behind a flag that says a dispatch is coming.
        this._scheduled = false;
        if (this._inbox.length === 0) return;
        if (!this.schedule()) return;
        continue;
      }
      this._inbox = [];
      this._dispatches += 1;
      this._served += batch.length;
      for (const { fn, args, opts, resolve, reject } of batch) {
        try {
          resolve(this.callSync(fn, args, opts));
        } catch (e) {
          reject(e);
        }
      }
    }
  }

  /// How many times a dispatch crossed into this sandbox, against how many
  /// requests were served. The second over the first is what debouncing
  /// bought, and a host seeing them equal under load is getting none.
  get dispatchCounts() {
    return { dispatches: this._dispatches, requests: this._served };
  }

  /// Call a function by name with positional arguments.
  ///
  /// **Asynchronous**, and it queues rather than runs: it hands the request to
  /// the driver, which decides when a runnable sandbox runs. The inline driver
  /// may finish before the returned promise is awaited, but the TYPE is
  /// asynchronous either way, because a synchronous API cannot be made
  /// asynchronous later without breaking every caller (`doc/decisions/0028`).
  ///
  /// What an argument MEANS is the caller's business -- no entry map, no
  /// capability argument; those are the CLI's convention.
  call(fn, args = [], opts = {}) {
    return new Promise((resolve, reject) => {
      // Queued BEFORE the wake, always: a wake that arrives before the work it
      // announces can be answered by a dispatch that finds an empty inbox.
      this._inbox.push({ fn, args, opts, resolve, reject });
      this._driver.wake(this);
    });
  }

  /// The same call, run here and now.
  ///
  /// Kept because a wasm sandbox genuinely is synchronous underneath, and a
  /// caller with nothing else to do should not have to await a promise that is
  /// already resolved. Never reachable from inside a sandbox.
  callSync(fn, args = [], opts = {}) {
    const e = this.inst.exports;
    if (!e.flint_call) {
      throw new Error(
        'this module predates `flint_call`: rebuild it with a current flint');
    }
    const encoded = codec.vec([codec.str(fn), ...args.map((a) => codec.from(a))]).encode();
    const p = e.arg_alloc(encoded.length);
    new Uint8Array(e.memory.buffer).set(encoded, p);
    const code = e.flint_call(p, encoded.length);
    const out = new Uint8Array(e.memory.buffer,
                               e.out_ptr(), e.out_len()).slice();
    const value = codec.decode(out, opts);
    if (code !== 0) {
      const err = new Error(`flint: ${value?.[':message'] ?? value?.message ?? 'the call failed'}`);
      err.flint = value;
      throw err;
    }
    return value;
  }

  /// What the build measures, read after a call rather than printed
  /// (`doc/decisions/0025`). Gas is in every build because it is resource
  /// control; the rest appears only when the module carries it, and an absent
  /// counter reads as ABSENT rather than as zero.
  get diagnostics() {
    const e = this.inst.exports;
    const out = { gas: e.stat_steps ? Number(e.stat_steps()) : undefined };
    const opt = {
      collections: 'stat_collections', allocations: 'stat_allocs',
      bytesAllocated: 'stat_bytes_allocated', peakLive: 'stat_peak_live',
      heapUsed: 'stat_heap_used',
    };
    for (const [k, f] of Object.entries(opt)) {
      if (e[f]) out[k] = Number(e[f]());
    }
    return out;
  }

  /// The raw instance, for a caller that wants the module's own exports.
  get exports() { return this.inst.exports; }
  /// Run a named function, rendering its answer the way a command line would.
  /// There is no entry point to default to (`doc/decisions/0025` step 5).
  run(fn, args = []) { return this.inst.run(fn, args); }
  /// Ask for a function by name and get its VALUE back, undecorated.
  call(fn, args = []) { return this.inst.call(fn, args); }
}

/// Compile and call, for the case that just wants an answer.
export async function evaluate({ fn, args = [], compiler, ...opts }) {
  const c = compiler instanceof Compiler ? compiler : await Compiler.load(compiler);
  const image = c.compile({ fn, ...opts });
  const sandbox = await image.sandbox(opts);
  return sandbox.call(fn, args);
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
