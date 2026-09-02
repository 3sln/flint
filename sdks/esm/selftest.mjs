// The distributable, exercised through its own public surface.
//
// What is imported here is `dist/flint.js` -- the file that ships -- not the
// source it was built from. A test against the source would pass with the
// bundle broken, which is the failure this file exists to catch.
import { Compiler, Image, Sandbox, codec, standardLibrary } from './dist/flint.js';
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';

let fails = 0;
const ok = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? '\n        ' + detail : '')); }
};
const eq = (label, a, b) => ok(label, JSON.stringify(a) === JSON.stringify(b),
                               `${JSON.stringify(a)} != ${JSON.stringify(b)}`);

console.log('the flint SDK');

// --- the codec, which everything else rides on ------------------------------
{
  const round = (v, o) => codec.decode(v.encode(), o);
  eq('an integer survives', round(codec.int(42)), 42);
  eq('a nested structure survives',
     round(codec.from({ a: 1, b: [1, 'two', null], c: { d: true } })),
     { a: 1, b: [1, 'two', null], c: { d: true } });
  eq('string keys stay strings', round(codec.from({ a: 1 })), { a: 1 });
  eq('keywordizeKeys makes them keywords',
     round(codec.from({ a: 1 }, { keywordizeKeys: true })), { ':a': 1 });
  eq('a leading colon does one key', round(codec.from({ ':a': 1 })), { ':a': 1 });
  eq('and a bare string', round(codec.from(':a')), ':a');
  ok('a Set stays a set', round(codec.from(new Set([1, 2]))) instanceof Set);
  ok('bytes stay bytes', round(codec.bytes(new Uint8Array([1, 2, 255]))) instanceof Uint8Array);
  eq('a double is not an integer', round(codec.from(1.5)), 1.5);
}

// --- compiling --------------------------------------------------------------
const compiler = await Compiler.load();
ok(`the standard library is embedded (${Object.keys(standardLibrary()).length} files)`,
   Object.keys(standardLibrary()).length > 5);

const t0 = Date.now();
const image = compiler.compile({
  files: {
    'app.cljc': `(ns app (:require [app.util :as u]))
                 (def seen (atom 0))
                 (defn greet [name] (u/shout (str "hello " name)))
                 (defn tally [] (swap! seen inc))
                 (defn echo [x] x)
                 (defn boom [] (throw (ex-info "deliberate" {:a 1})))
                 (defn main [args] (str "main saw " (pr-str args)))`,
    'app/util.cljc': `(ns app.util (:require [clojure.string :as s]))
                      (defn shout [x] (s/upper-case x))`,
  },
  fn: 'app/main',
  exports: ['app/greet', 'app/tally', 'app/echo', 'app/boom', 'app.util/shout'],
  meta: { capabilities: ['fs'], author: 'the selftest' },
});
ok(`it compiles to an Image (${image.wasm.length.toLocaleString()} bytes, ` +
   `${((Date.now() - t0) / 1000).toFixed(1)} s)`, image.wasm.length > 100_000);
eq('which carries its metadata', image.metadata.author, 'the selftest');
eq('and the CLI convention reads back', image.capabilities, ['fs']);

// --- calling ----------------------------------------------------------------
const sandbox = await image.sandbox();
eq('a function is called by name', await sandbox.call('app/greet', ['flint']), 'HELLO FLINT');
eq('any function, not one entry point', await sandbox.call('app.util/shout', ['x']), 'X');

// State is the SANDBOX's: initialisers run once, not per call, which is what
// makes instantiate-once-call-per-request work at all.
await sandbox.call('app/tally');
eq('a sandbox keeps its state across calls', await sandbox.call('app/tally'), 2);
const fresh = await image.sandbox();
eq('and a fresh sandbox shares none of it', await fresh.call('app/tally'), 1);

// --- what can cross ---------------------------------------------------------
eq('a map round-trips through a call',
   await sandbox.call('app/echo', [{ a: 1, b: [1, 2] }]), { a: 1, b: [1, 2] });
eq('a keyword survives', await sandbox.call('app/echo', [':a']), ':a');
ok('a set survives', await sandbox.call('app/echo', [new Set([1, 2])]) instanceof Set);
eq('nil survives', await sandbox.call('app/echo', [null]), null);

// --- failure is data --------------------------------------------------------
try {
  await sandbox.call('app/boom');
  ok('a thrown error reaches the caller', false, 'it returned');
} catch (e) {
  ok('a thrown error reaches the caller', /deliberate/.test(JSON.stringify(e.flint)),
     JSON.stringify(e.flint));
}
try {
  await sandbox.call('app/nope');
  ok('an unknown function is named', false, 'it returned');
} catch (e) {
  ok('an unknown function is named', /app\/nope/.test(JSON.stringify(e.flint)),
     JSON.stringify(e.flint));
}

// --- diagnostics ------------------------------------------------------------
ok('gas is readable after a call, in every build', sandbox.diagnostics.gas > 0,
   JSON.stringify(sandbox.diagnostics));

// --- drivers (`doc/decisions/0028`) -----------------------------------------
//
// The same four nouns and the same fifth as the Rust SDK, by the same names.
// What differs is the ANSWER: wasm cannot put two executors in one sandbox
// until it has the threads proposal, atomics and a shared-memory build, so a
// pool of four reports a parallelism of ONE. Not a refusal -- portable code
// could not then be written -- and not silence either.
import { Driver, Inline, ThreadPool } from './dist/flint.js';
eq('an inline driver is one thread', new Inline().parallelism, 1);
eq('and a pool on wasm says so rather than pretending',
   new ThreadPool(4).parallelism, 1);
eq('while recording what was asked for', new ThreadPool(4).requested, 4);
ok('a driver is a Driver', new ThreadPool(4) instanceof Driver);

const pooled = await image.sandbox({ driver: new ThreadPool(4) });
eq('a sandbox reads its parallelism back', pooled.parallelism, 1);

// Coalescing: a burst of calls has to cost ONE dispatch, not one each. That is
// the claim debouncing exists to make, and it is measured rather than assumed.
const burst = await Promise.all(
  Array.from({ length: 50 }, () => pooled.call('app/tally')));
eq('every request in a burst is served', burst.length, 50);
const { dispatches, requests } = pooled.dispatchCounts;
eq('and none is served twice', requests, 50);
ok('a burst costs fewer dispatches than calls', dispatches < requests,
   `${dispatches} dispatches for ${requests} requests`);
console.log(`      coalesced ${requests} requests into ${dispatches} dispatches`);

// `call` is asynchronous even under the inline driver. `callSync` stays for a
// caller with nothing else to do -- a wasm sandbox really is synchronous
// underneath -- but the async type is what the other SDKs mirror.
ok('call returns a promise', sandbox.call('app/echo', [1]) instanceof Promise);
eq('callSync answers here and now', sandbox.callSync('app/echo', [7]), 7);

// --- optimize is an ordered preference, not a switch -------------------------
//
// Measured rather than asserted from the option: `[perf]` compiles every arity
// into the module, so it is BIGGER, and that size difference is the only proof
// from out here that the token did anything at all.
const sources = { files: { 'a.cljc': '(ns a) (defn f [x] (+ x 1)) (defn main [_] (f 1))' },
                  fn: 'a/main' };
const sizeOf = (optimize) => compiler.compile({ ...sources, optimize }).wasm.length;
const small = sizeOf(['size']);
const fast = sizeOf(['perf']);
ok('optimize [perf] compiles arities and [size] does not', fast > small,
   `perf ${fast} bytes against size ${small}`);
ok('an unrecognised token is ignored rather than refused',
   sizeOf(['no-such-thing', 'size']) === small, 'it changed the output');
ok('and the FIRST token this build understands decides',
   sizeOf(['no-such-thing', 'perf', 'size']) === fast, 'a later token won');
ok('no optimize at all is the interpreter', sizeOf([]) === small, 'it compiled arities');

// --- workspaces (`doc/decisions/0035` step 3, on `0036`'s resolver) ---------
//
// A reader tag is bound per WORKSPACE, and until now that was true from the CLI
// and silently false through here: the SDK had no notion of which project a
// file belonged to, so nothing was ever bound and every tag was unknown.
//
// Two workspaces binding the SAME name to different readers is the test that
// distinguishes "bound per workspace" from "bound globally" -- either answer
// alone would look like success.
{
  const files = {
    'alpha/a.cljc':
      '(ns alpha.a) (defn read-x [v] (str "alpha:" v)) (defn go [] #x "one")',
    'beta/b.cljc':
      '(ns beta.b (:require [alpha.a :as a])) (defn read-x [v] (str "beta:" v))' +
      ' (defn go [] [(a/go) #x "two"])',
  };
  const workspaces = [
    { prefix: 'alpha/', name: 'alpha/alpha', tags: { x: 'alpha.a/read-x' } },
    { prefix: 'beta/', name: 'beta/beta', tags: { x: 'beta.b/read-x' } },
  ];
  const sb = await (compiler.compile({ files, workspaces, fn: 'beta.b/go' })).sandbox();
  eq('a tag is bound per workspace, not globally',
     await sb.call('beta.b/go'), ['alpha:one', 'beta:two']);
  // And undeclared is UNBOUND. Without this the test above would pass just as
  // well if tags were bound from thin air.
  let refused = false;
  try { compiler.compile({ files, fn: 'beta.b/go' }); }
  catch (e) { refused = /no reader for the tag/.test(e.message); }
  ok('and a tag no workspace binds is refused', refused);
}

// --- workspace capability guards (`doc/decisions/0036`) ---------------------
//
// Level one of two: a workspace declaring `guard` may be required only by a
// workspace holding it. The three cases that are easy to get wrong are here
// alongside the one that is easy to get right.
{
  const files = {
    'priv/p.cljc': '(ns priv.p) (defn secret [] "the goods")',
    'app/a.cljc': '(ns app.a (:require [priv.p :as p])) (defn go [] (p/secret))',
    'mid/m.cljc': '(ns mid.m (:require [priv.p :as p])) (defn wrap [] (p/secret))',
    'app2/a.cljc': '(ns app2.a (:require [mid.m :as m])) (defn go [] (m/wrap))',
  };
  const priv = { prefix: 'priv/', name: 'priv/priv', guard: ['fs'] };
  const run = async (workspaces, fn) =>
    (await (compiler.compile({ files, workspaces, fn })).sandbox()).call(fn);

  let refused = null;
  try { compiler.compile({ files, workspaces: [priv, { prefix: 'app/', name: 'app/app' }], fn: 'app.a/go' }); }
  catch (e) { refused = e.refused?.[0] ?? e.message; }
  ok('a guarded workspace refuses a require from one without the capability',
     /priv\.p/.test(refused ?? '') && /:fs/.test(refused ?? ''), refused);

  eq('and allows it from one that holds it',
     await run([priv, { prefix: 'app/', name: 'app/app', grants: ['fs'] }], 'app.a/go'),
     'the goods');

  // NOT transitive, deliberately. `app2` holds nothing and reaches the guarded
  // workspace through `mid`, which does -- that is `mid` re-exporting, and it
  // is the delegation a guard cannot and should not stop. A guard makes the set
  // of DIRECT holders small and declared; it does not confine what they hand on.
  eq('a guard is checked per edge, not transitively',
     await run([priv, { prefix: 'mid/', name: 'mid/mid', grants: ['fs'] },
                { prefix: 'app2/', name: 'app2/app2' }], 'app2.a/go'),
     'the goods');

  // A project is not a security boundary against itself.
  eq('and nothing is checked within one workspace',
     await run([{ prefix: '', name: 'one/one', guard: ['fs'] }], 'app.a/go'),
     'the goods');
}

// --- var capability guards (`doc/decisions/0036`) ---------------------------
//
// Level two: the workspace is NOT guarded and may be depended on freely, and
// one var in it is. This is what makes a partly privileged library expressible
// -- everyone depends on the standard library, and three vars in it matter.
{
  const files = {
    'lib/l.cljc': '(ns lib.l)\n(defn safe [] "anyone")\n' +
                  '(defn ^{:flint/capabilities-guard [:fs]} danger [] "privileged")\n',
    'app/a.cljc': '(ns app.a (:require [lib.l :as l])) (defn go [] (l/safe))',
    'app/b.cljc': '(ns app.b (:require [lib.l :as l])) (defn go [] (l/danger))',
    'app/c.cljc': '(ns app.c (:require [lib.l :as l])) (defn go [] (first (map l/danger [1])))',
  };
  const lib = { prefix: 'lib/', name: 'lib/lib' };
  const bare = { prefix: 'app/', name: 'app/app' };
  const held = { prefix: 'app/', name: 'app/app', grants: ['fs'] };
  const run = async (ws, fn) =>
    (await (compiler.compile({ files, workspaces: ws, fn })).sandbox()).call(fn);
  const refusal = (ws, fn) => {
    try { compiler.compile({ files, workspaces: ws, fn }); return null; }
    catch (e) { return e.message; }
  };

  eq('an unguarded var in the same workspace is free',
     await run([lib, bare], 'app.a/go'), 'anyone');
  ok('a guarded var is refused without the capability',
     /lib\.l\/danger is guarded/.test(refusal([lib, bare], 'app.b/go') ?? ''),
     refusal([lib, bare], 'app.b/go'));
  eq('and allowed with it', await run([lib, held], 'app.b/go'), 'privileged');

  // The REFERENCE is guarded, not the call. Otherwise `(map l/danger ..)` would
  // hand the function to something unguarded and the guard would be one line of
  // indirection deep.
  ok('and refused when merely passed as a value, not called',
     /lib\.l\/danger is guarded/.test(refusal([lib, bare], 'app.c/go') ?? ''),
     refusal([lib, bare], 'app.c/go'));

  eq('a program declaring no workspaces is checked nowhere',
     await run(undefined, 'app.b/go'), 'privileged');
}

// A MACRO CANNOT LAUNDER A GUARDED REFERENCE, and a function can.
//
// This is the hazard that made a compile-time-only check look unworkable:
// macros emit code, so anything a macro emits could be inlined into a caller
// that was never allowed to write it. It does not happen, because an expansion
// is analysed in the CALLER's namespace -- so the caller's workspace is what
// the guard is checked against, which is the right answer and the safe one.
//
// A function in the granted workspace still wraps it freely. That is the
// delegation guards do not stop, and the two rows belong together: without the
// second, the first would read as "authority cannot spread", which is false.
{
  const files = {
    'lib/l.cljc': '(ns lib.l)\n' +
      '(defn ^{:flint/capabilities-guard [:fs]} danger [] "privileged")\n' +
      '(defmacro sneak [] `(danger))\n' +
      '(defn wrapped [] (danger))\n',
    'app/m.cljc': '(ns app.m (:require [lib.l :as l])) (defn go [] (l/sneak))',
    'app/f.cljc': '(ns app.f (:require [lib.l :as l])) (defn go [] (l/wrapped))',
  };
  const ws = [{ prefix: 'lib/', name: 'lib/lib', grants: ['fs'] },
              { prefix: 'app/', name: 'app/app' }];
  let refused = null;
  try { compiler.compile({ files, workspaces: ws, fn: 'app.m/go' }); }
  catch (e) { refused = e.message; }
  ok('a macro cannot expand a guarded reference into an ungranted caller',
     /lib\.l\/danger is guarded/.test(refused ?? ''), refused);
  eq('but a function in the granted workspace wraps it freely',
     await (await (compiler.compile({ files, workspaces: ws, fn: 'app.f/go' })).sandbox())
       .call('app.f/go'),
     'privileged');
}

// --- asking the host for something (`doc/decisions/0036` step 7) ------------
//
// `open` asks for a PORT; `request` asks for anything and gets a value. It is
// guarded with `:host`, and the standard library is its own workspace, which is
// what makes that guard mean something to a program.
{
  const files = {
    'app/a.cljc':
      '(ns app.a (:require [flint.host :as h]))\n' +
      '(defn go [] (h/request "config"))\n' +
      '(defn opt [] (h/ask "nothing-here"))\n' +
      '(defn nily [] (pr-str (h/request "nil-please")))\n',
  };
  let refused = null;
  try { compiler.compile({ files, workspaces: [{ prefix: 'app/', name: 'app/app' }], fn: 'app.a/go' }); }
  catch (e) { refused = e.message; }
  ok('asking the host is refused without the :host capability',
     /flint\.host\/request is guarded/.test(refused ?? ''), refused);

  const sb = await (compiler.compile({
    files, workspaces: [{ prefix: 'app/', name: 'app/app', grants: ['host'] }],
    exports: ['app.a/go', 'app.a/opt', 'app.a/nily'], fn: 'app.a/go',
  })).sandbox();
  sb.inst.requests({ config: () => ({ mode: 'live', retries: 3 }), 'nil-please': () => null });
  eq('and answered with a value when it is held',
     await sb.call('app.a/go'), { mode: 'live', retries: 3 });

  // NIL IS AN ANSWER. The runtime wraps a host's answer in a one-element vector
  // for exactly this: without it, a host answering nil and a host refusing
  // would be the same bits on the parked thread.
  eq('nil is an answer, not a refusal', await sb.call('app.a/nily'), 'nil');
  // And an unhandled name REFUSES rather than answering nil, which is the same
  // distinction from the other side.
  eq('an unhandled request is refused', await sb.call('app.a/opt'), null);
}

// --- virtual namespaces (`doc/decisions/0036` step 4, `0037`) ---------------
//
// A namespace with NO SOURCE, spoken to over a port. The call site reads like
// any other call, which is the whole design goal -- so the tests below are as
// much about where the resemblance ENDS as about where it holds.
{
  const files = {
    'app/a.cljc':
      '(ns app.a (:require [demo.svc :as s]))\n' +
      '(defn go [_] (s/greet "world"))\n' +
      '(defn indirect [_] (let [f s/greet] (f "via a value")))\n' +
      '(defn mapv- [_] (mapv s/greet ["a" "b"]))\n' +
      '(defn lazily [_] (vec (for [x ["a"]] (s/greet x))))\n' +
      '(defn unknown [_] (s/nope 1))\n',
  };
  const app = { prefix: 'app/', name: 'app/app' };
  const virt = { prefix: 'demo/', name: 'demo/demo', virtual: true };
  const exports = ['app.a/go', 'app.a/indirect', 'app.a/mapv-', 'app.a/lazily', 'app.a/unknown'];
  const server = { allow: () => true, open() {},
    message(port, v, api) {
      const req = v[':body'] ?? v;
      if (req[':op'] === ':invoke' && req[':var'] !== 'greet') {
        api.deliver(port, { ':id': v[':id'], ':error': { ':message': `no var ${req[':var']}` } });
        return;
      }
      api.deliver(port, { ':id': v[':id'], ':body': `hello ${req[':args'][0]}` });
    } };
  const sb = await (compiler.compile({ files, workspaces: [virt, app], fn: 'app.a/go', exports }))
    .sandbox({ capabilities: { 'demo.svc': server } });

  eq('a call into a virtual namespace reads like any other call',
     await sb.call('app.a/go', [[]]), 'hello world');
  // Held in a local, so the compiler emitted a closure rather than a call --
  // and the closure writes its arities out, because `apply` is native and a
  // park inside native code is refused.
  eq('and a virtual var can be held as a value',
     await sb.call('app.a/indirect', [[]]), 'hello via a value');
  eq('and passed to an EAGER higher-order function',
     await sb.call('app.a/mapv-', [[]]), ['hello a', 'hello b']);

  // WHERE THE RESEMBLANCE ENDS, asserted rather than left in a docstring: a
  // virtual call parks, parking is refused inside native code, and a lazy seq
  // is native. True of every port operation and not this feature's doing, but
  // this is where it stops looking like a port.
  let lazyErr = null;
  try { await sb.call('app.a/lazily', [[]]); } catch (e) { lazyErr = e.message; }
  ok('but NOT to a lazy one, and the refusal says why',
     /cannot park here/.test(lazyErr ?? ''), lazyErr);

  // The far side's error is the caller's error.
  let farErr = null;
  try { await sb.call('app.a/unknown', [[]]); } catch (e) { farErr = e.message; }
  ok('an error from the far side reaches the caller', /no var nope/.test(farErr ?? ''), farErr);

  // THE VAR LIST IS OPTIONAL, and that is what decides compile error vs
  // run-time error. Both modes asserted, because the difference is the feature.
  let checked = null;
  try {
    compiler.compile({ files, fn: 'app.a/unknown',
                       workspaces: [{ ...virt, vars: [{ name: 'greet', arities: [1] }] }, app] });
  } catch (e) { checked = e.message; }
  ok('with a var list, an unknown var is a COMPILE error',
     /does not hold nope/.test(checked ?? ''), checked);
  ok('without one it compiles, and fails when called',
     /no var nope/.test(farErr ?? ''), farErr);
}

// --- the artifact stands on its own -----------------------------------------
const dir = mkdtempSync(`${tmpdir()}/flint-`);
writeFileSync(`${dir}/m.wasm`, image.wasm);
const root = new URL('../../', import.meta.url).pathname;
// The FUNCTION is named, and then its arguments. `x` alone used to be the
// argument, back when there was an entry point to default to; with `main` gone
// there is nothing to default to and nothing named `x` (`doc/decisions/0025`).
const out = execFileSync('node', [`${root}host/flint.mjs`, `${dir}/m.wasm`, 'app/main', 'x'],
                         { encoding: 'utf8' }).trim();
ok('the module runs under a different host', out.startsWith('main saw'), out);
const meta = execFileSync(`${root}bin/flint`, ['inspect', `${dir}/m.wasm`],
                          { encoding: 'utf8', cwd: root });
// It no longer claims an entry, and it still carries what the host recorded.
// NOT the callable flint functions: `exports` in the metadata is the module's
// ABI surface, and which flint names a host may call is not recorded yet.
ok('and describes itself', !/entry/.test(meta) && /author/.test(meta),
   meta.split('\n')[0]);

process.exit(fails ? 1 : 0);
