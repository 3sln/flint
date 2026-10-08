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
// (`DECISIONS.md#no-runtime-linking`).
//
// The artifacts are imported with `with { type: 'bytes' }`, which esbuild
// turns into an inline `Uint8Array` -- so the published package is one file
// and a consumer's bundler has nothing to resolve.

import COMPILER from '../../../dist/flintc.wasm' with { type: 'bytes' };
import RUNTIME from '../../../dist/flint-runtime.wasm' with { type: 'bytes' };
import RUNTIME_AOT from '../../../dist/flint-runtime-aot.wasm' with { type: 'bytes' };
import READER from '../../../dist/flint-reader.wasm' with { type: 'bytes' };
import SLOTS_JSON from '../../../dist/slots.json' with { type: 'bytes' };
import SLOTS_AOT_JSON from '../../../dist/slots-aot.json' with { type: 'bytes' };
// STDCORE IS HERE, UNCONDITIONALLY: the namespaces every image needs whatever a
// host supplies (`DECISIONS.md#namespaces-over-the-system-port` §4). There is
// no scenario where omitting them is legitimate, so nothing here lets a host
// do it -- and nothing lets a host's resolver answer one of them either.
// stdextra is NOT imported by this module: it lives behind `stdextra()` in
// `./stdextra.js`, so a program that never imports it never carries it.
import STDCORE from '../../../dist/stdcore.forms' with { type: 'bytes' };

// Arguments, the pump, capabilities. Shared with `host/flint.mjs`, which is a
// node wrapper over the same file.
import { instantiate } from './guest.js';
import {
  Reader, layer, compileCall, compileRequest, renderErrors, DEFAULT_FEATURES, PERF_FEATURES,
} from './resolve.js';

// The resolver building blocks (`DECISIONS.md#namespaces-over-the-system-port`
// §4): small resolvers a host composes. `stdextra()` is in `./stdextra.js`.
export {
  chain, fromMap, segregate, virtualNamespaces, Reader, SOURCE_EXTENSIONS, nsPath,
  // What `./stdextra.js` builds its resolver with; a host composing its own
  // pre-read layer may too.
  layer,
} from './resolve.js';

const utf8 = (bytes) => new TextDecoder().decode(bytes);
const parse = (bytes) => JSON.parse(utf8(bytes));

/// The required layer, as a resolver the compile consults FIRST.
const stdcore = layer(STDCORE);

/// Which namespaces are stdcore -- answered by the embedded copy, and never put
/// to a host's resolver.
export function stdcoreNamespaces() {
  return stdcore.names().map((p) => p.replace(/\//g, '.').replace(/_/g, '-'));
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
export const artifacts = { compiler: COMPILER, runtime: RUNTIME, runtimeAot: RUNTIME_AOT, reader: READER };

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

// --- the API ---------------------------------------------------------------

export { codec } from './codec.js';
import { codec } from './codec.js';

export class Compiler {
  constructor(module, reader) { this.module = module; this.reader = reader; }

  /// The compiler and the reader, both compiled. Asynchronous because a browser
  /// main thread refuses to compile modules this size synchronously.
  static async load(source = COMPILER) {
    const [module, reader] = await Promise.all([moduleFrom(source), Reader.load(READER)]);
    return new Compiler(module, reader);
  }

  /// Compile to an Image. **Asynchronous, and there is no synchronous twin**
  /// (`DECISIONS.md#namespaces-over-the-system-port` §3): a resolver that
  /// fetches over the network or reads IndexedDB answers with a promise, and a
  /// synchronous API cannot be made asynchronous later without breaking every
  /// caller.
  ///
  /// Source comes from a RESOLVER -- a namespace to its source -- asked only
  /// for what the compiler actually reaches, one wave of names at a time. The
  /// namespaces every image needs (`stdcoreNamespaces()`) are answered by the
  /// SDK's own copy before `resolve` is ever consulted, and `resolve` is never
  /// asked for one. The rest of the standard library is the `stdextra()`
  /// building block, which a host composes in or leaves out:
  ///
  ///     import { Compiler, chain, fromMap } from '@3sln/flint';
  ///     import { stdextra } from '@3sln/flint/stdextra';
  ///     const c = await Compiler.load();
  ///     const image = await c.compile({
  ///       fn: 'app/main',
  ///       resolve: chain(stdextra(), fromMap({ 'app.cljc': '(ns app) ..' })),
  ///     });
  ///
  /// | | |
  /// | --- | --- |
  /// | `resolve` | `(ns) => answer \| Promise<answer>`; see `resolve.js` for the answer's shape |
  /// | `fn`      | the function a default `run` would call |
  /// | `exports` | every other function that must stay callable |
  /// | `optimize` | `['perf']` compiles each arity and drops checks; `['size']` interprets |
  /// | `checks`  | `true`/`false` keeps or drops `#?(:flint/check ..)`; default: dropped under `perf` |
  /// | `features` | the reader features, overriding the two above |
  /// | `shake`   | cut the runtime to what the program reaches (on by default) |
  /// | `meta`    | arbitrary metadata to record in the artifact |
  async compile(opts = {}) {
    const bytes = await this.emit({ ...opts, target: 'wasm' });
    return new Image(bytes, opts.meta);
  }

  /// The artifact for `target` -- `'wasm'` (what `compile` wraps), `'llvm'`
  /// (IR text, as bytes), `'clr'` (an assembly; `name` is its file's basename)
  /// or `'jvm'` (a class; `className` names it) -- as bytes. The same request
  /// the CLIs send for `:to <target>`, so the same program is the same bytes
  /// from every door.
  async emit({ resolve, fn, entry, target = 'wasm', exports, optimize = [], checks = null,
               features, shake = true, runtime, slots, meta, builtins, name = null,
               className = null, memoryLimit = 3_000_000_000 } = {}) {
    const main = fn ?? entry;
    if (!main) throw new Error('compile needs `fn`, e.g. "my.app/main"');
    const t = String(target).replace(/^:/, '');
    // An ORDERED preference list. The first token this build understands
    // decides; the rest are what the caller would have wanted otherwise, and
    // anything unrecognised is ignored -- which is what makes a list written
    // against a newer flint still get this one's best effort.
    const known = { perf: true, size: false };
    const aot = optimize.map((o) => String(o).replace(/^:/, ''))
                        .map((o) => known[o]).find((v) => v !== undefined) ?? false;
    // Checks follow `:optimize` unless said otherwise -- the CLIs' rule
    // (`strip_checks`), so `:optimize [perf]` is the same compile from here.
    const strip = checks === null || checks === undefined ? aot : !checks;
    const said = features ?? (strip ? PERF_FEATURES : null);
    const wasm = t === 'wasm';
    const table = slots ?? (wasm && aot ? aotRuntimeSlots() : runtimeSlots());
    const request = compileRequest({
      entry: main, target: t, exports: exports ?? [], features: said, aot,
      // Only a wasm module is SHAKEN: the other targets resolve natives by name
      // against whatever the host carries, and there is no module to cut.
      shake: wasm && shake, meta, slots: table, builtins,
      base: wasm ? (runtime ?? (aot ? RUNTIME_AOT : RUNTIME)) : null,
      name: t === 'clr' ? (name ?? '') : null, className: t === 'jvm' ? className : null,
    });
    const inst = instantiate(this.module);
    // Compiling a whole program, appending its compiled arities and then tree
    // shaking the result is the most memory this ever does, and the default
    // cap is 512 MB. Past it an allocation answers NIL, the NIL reaches the
    // tree, and the failure surfaces as `memory access out of bounds` with
    // nothing pointing at the cap.
    if (inst.exports.set_memory_limit) inst.exports.set_memory_limit(memoryLimit);
    const r = await compileCall(inst, request, {
      resolve, stdcore, reader: this.reader, features: said ?? DEFAULT_FEATURES,
    });
    if (r[':errors']) {
      const err = new Error(`flint: ${renderErrors(r[':errors'])}`);
      // DATA, as the compiler answered it: every error at once, with kind,
      // namespace and position, for a host that wants more than a sentence.
      err.errors = r[':errors'];
      throw err;
    }
    return r[':artifact'];
  }
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
  /// grant: the host still decides (`DECISIONS.md#opaque-values`).
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
/// Who advances a sandbox, and when (`DECISIONS.md#drivers`).
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
  /// asynchronous later without breaking every caller (`DECISIONS.md#drivers`).
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
  callSync(fn, args = []) {
    const e = this.inst.exports;
    if (!e.flint_system_port) {
      throw new Error(
        'this module predates the system-port call: rebuild it with a current flint');
    }
    // DELEGATED, rather than a second copy of the call path.
    //
    // There used to be TWO ways to make a call, and choosing between them was
    // this method's job: `flint_call` when the sandbox had no system port, a
    // message when it had one. It got the choice wrong for every sandbox with a
    // capability -- the guest opened a port, the run came back "the host is
    // needed", and `flint_call` encoded the nil that a not-yet-finished run
    // returns, so every such call answered null.
    //
    // There is one way now (`DECISIONS.md#calls-are-ports`), so there is
    // nothing to choose and nothing to get wrong. This stays because a wasm
    // sandbox is synchronous underneath and a caller with nothing else to do
    // should not await an already-resolved promise.
    return this.inst.call(fn, args);
  }

  /// What the build measures, read after a call rather than printed
  /// (`DECISIONS.md#structured-ports`). Gas is in every build because it is resource
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
  /// There is no entry point to default to (`DECISIONS.md#structured-ports` step 5).
  ///
  /// There is deliberately no `call` here. There WAS -- a passthrough to
  /// `this.inst.call` added beside `run` when the entry point went away -- and
  /// a second method of that name in one class body silently replaced the
  /// asynchronous `call` above with no error anywhere. Every call then bypassed
  /// the driver: the returned value was not a promise, nothing was ever
  /// queued, and coalescing counted zero dispatches for fifty requests while
  /// the answers still came back correct. `callSync` is the undecorated call,
  /// and it is the one this was duplicating.
  run(fn, args = []) { return this.inst.run(fn, args); }
}

/// Compile and call, for the case that just wants an answer.
export async function evaluate({ fn, args = [], compiler, ...opts }) {
  const c = compiler instanceof Compiler ? compiler : await Compiler.load(compiler);
  const image = await c.compile({ fn, ...opts });
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
