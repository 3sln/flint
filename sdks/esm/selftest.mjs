// The distributable, exercised through its own public surface.
//
// What is imported here is `dist/flint.js` -- the file that ships -- not the
// source it was built from. A test against the source would pass with the
// bundle broken, which is the failure this file exists to catch.
import {
  Compiler, Image, Sandbox, codec, chain, fromMap, virtualNamespaces,
  stdcoreNamespaces, Driver, Inline, ThreadPool,
} from './dist/flint.js';
import { stdextra } from './dist/stdextra.js';
import { deps } from './dist/deps.js';
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdtempSync, readFileSync } from 'node:fs';
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
//
// `compile` is ASYNCHRONOUS now, and source comes only from a RESOLVER
// (`DECISIONS.md#namespaces-over-the-system-port`): `(ns) => answer`. There is
// no `files`, `workspaces` or `standardLibrary` option any more -- the SDK's
// embedded stdcore answers `clojure.core` and seven others unconditionally,
// and `stdextra()` is the optional rest, composed in with `chain`.
//
// Checks are ON BY DEFAULT (`DEFAULT_FEATURES` carries `:flint/check`), and
// `flint.check` -- the namespace that feature needs -- lives in STDEXTRA, not
// stdcore. So `stdextra()` is in this chain even though this program never
// touches `clojure.set` or anything else stdextra carries; see "stdextra
// omitted" below for what happens, and does not happen, without it.
const compiler = await Compiler.load();
ok(`stdcore is answered by the SDK itself, never by a resolver (${stdcoreNamespaces().join(', ')})`,
   stdcoreNamespaces().includes('clojure.core') && stdcoreNamespaces().length > 5);

const appFiles = {
  'app.cljc': `(ns app (:require [app.util :as u]))
               (def seen (atom 0))
               (defn greet [name] (u/shout (str "hello " name)))
               (defn tally [] (swap! seen inc))
               (defn echo [x] x)
               (defn boom [] (throw (ex-info "deliberate" {:a 1})))
               (defn main [args] (str "main saw " (pr-str args)))`,
  // `clojure.string` is itself stdcore (see the join above), so this require
  // needs no `stdextra()` at all -- only `flint.check`, from the chain, does.
  'app/util.cljc': `(ns app.util (:require [clojure.string :as s]))
                    (defn shout [x] (s/upper-case x))`,
};
const t0 = Date.now();
const image = await compiler.compile({
  resolve: chain(stdextra(), fromMap(appFiles)),
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

// --- drivers (`DECISIONS.md#drivers`) -----------------------------------------
//
// The same four nouns and the same fifth as the Rust SDK, by the same names.
// What differs is the ANSWER: wasm cannot put two executors in one sandbox
// until it has the threads proposal, atomics and a shared-memory build, so a
// pool of four reports a parallelism of ONE. Not a refusal -- portable code
// could not then be written -- and not silence either.
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
const optimizeResolve = chain(stdextra(), fromMap(
  { 'optapp.cljc': '(ns optapp) (defn f [x] (+ x 1)) (defn main [_] (f 1))' }));
const sizeOf = async (optimize) =>
  (await compiler.compile({ resolve: optimizeResolve, fn: 'optapp/main', optimize })).wasm.length;
const small = await sizeOf(['size']);
const fast = await sizeOf(['perf']);
ok('optimize [perf] compiles arities and [size] does not', fast > small,
   `perf ${fast} bytes against size ${small}`);
ok('an unrecognised token is ignored rather than refused',
   (await sizeOf(['no-such-thing', 'size'])) === small, 'it changed the output');
ok('and the FIRST token this build understands decides',
   (await sizeOf(['no-such-thing', 'perf', 'size'])) === fast, 'a later token won');
ok('no optimize at all is the interpreter', (await sizeOf([])) === small, 'it compiled arities');

// --- workspaces (`DECISIONS.md#reader-tags` step 3, on `workspace-capabilities`'s resolver) ---------
//
// A reader tag is bound per WORKSPACE, and WORKSPACE IS A FIELD OF AN ANSWER
// now, never a path prefix (`DECISIONS.md#namespaces-over-the-system-port` §4
// rule 2). Two namespaces served by two `fromMap`s, chained, each carrying its
// own `workspace` and `tags` -- that is "composed with chain/fromMap" doing
// the job the old `workspaces: [{prefix, name, tags}, ..]` array used to do.
//
// Two workspaces binding the SAME name to different readers is the test that
// distinguishes "bound per workspace" from "bound globally" -- either answer
// alone would look like success.
{
  const alphaSrc = '(ns alpha.a) (defn read-x [v] (str "alpha:" v)) (defn go [] #x "one")';
  const betaSrc = '(ns beta.b (:require [alpha.a :as a])) (defn read-x [v] (str "beta:" v))' +
      ' (defn go [] [(a/go) #x "two"])';
  const alpha = fromMap({ 'alpha/a.fln': alphaSrc }, { workspace: 'alpha/alpha', tags: { x: 'alpha.a/read-x' } });
  const beta = fromMap({ 'beta/b.fln': betaSrc }, { workspace: 'beta/beta', tags: { x: 'beta.b/read-x' } });
  const sb = await (await compiler.compile({ resolve: chain(stdextra(), alpha, beta), fn: 'beta.b/go' })).sandbox();
  eq('a tag is bound per workspace, not globally',
     await sb.call('beta.b/go'), ['alpha:one', 'beta:two']);
  // And undeclared is UNBOUND. Without this the test above would pass just as
  // well if tags were bound from thin air.
  const alphaNoTags = fromMap({ 'alpha/a.fln': alphaSrc }, { workspace: 'alpha/alpha' });
  const betaNoTags = fromMap({ 'beta/b.fln': betaSrc }, { workspace: 'beta/beta' });
  let refused = false;
  try { await compiler.compile({ resolve: chain(stdextra(), alphaNoTags, betaNoTags), fn: 'beta.b/go' }); }
  catch (e) { refused = /no reader for the tag/.test(e.message); }
  ok('and a tag no workspace binds is refused', refused);
}

// --- workspace capability guards (`DECISIONS.md#workspace-capabilities`) ---------------------
//
// Level one of two: a workspace declaring `guard` may be required only by a
// workspace holding it. The three cases that are easy to get wrong are here
// alongside the one that is easy to get right.
{
  const priv = fromMap({ 'priv/p.cljc': '(ns priv.p) (defn secret [] "the goods")' },
                        { workspace: 'priv/priv', guard: ['fs'] });
  const appBare = fromMap({ 'app/a.cljc': '(ns app.a (:require [priv.p :as p])) (defn go [] (p/secret))' },
                           { workspace: 'app/app' });
  const appHeld = fromMap({ 'app/a.cljc': '(ns app.a (:require [priv.p :as p])) (defn go [] (p/secret))' },
                           { workspace: 'app/app', grants: ['fs'] });
  const mid = fromMap({ 'mid/m.cljc': '(ns mid.m (:require [priv.p :as p])) (defn wrap [] (p/secret))' },
                       { workspace: 'mid/mid', grants: ['fs'] });
  const app2 = fromMap({ 'app2/a.cljc': '(ns app2.a (:require [mid.m :as m])) (defn go [] (m/wrap))' },
                        { workspace: 'app2/app2' });
  const oneWs = fromMap({
      'priv/p.cljc': '(ns priv.p) (defn secret [] "the goods")',
      'app/a.cljc': '(ns app.a (:require [priv.p :as p])) (defn go [] (p/secret))',
    }, { workspace: 'one/one', guard: ['fs'] });
  const run = async (resolve, fn) => (await (await compiler.compile({ resolve, fn })).sandbox()).call(fn);

  let refused = null;
  try { await compiler.compile({ resolve: chain(stdextra(), priv, appBare), fn: 'app.a/go' }); }
  catch (e) { refused = e.message; }
  ok('a guarded workspace refuses a require from one without the capability',
     /priv\.p/.test(refused ?? '') && /:fs/.test(refused ?? ''), refused);

  eq('and allows it from one that holds it',
     await run(chain(stdextra(), priv, appHeld), 'app.a/go'), 'the goods');

  // NOT transitive, deliberately. `app2` holds nothing and reaches the guarded
  // workspace through `mid`, which does -- that is `mid` re-exporting, and it
  // is the delegation a guard cannot and should not stop. A guard makes the set
  // of DIRECT holders small and declared; it does not confine what they hand on.
  eq('a guard is checked per edge, not transitively',
     await run(chain(stdextra(), priv, mid, app2), 'app2.a/go'), 'the goods');

  // A project is not a security boundary against itself.
  eq('and nothing is checked within one workspace',
     await run(chain(stdextra(), oneWs), 'app.a/go'), 'the goods');
}

// --- var capability guards (`DECISIONS.md#workspace-capabilities`) ---------------------------
//
// Level two: the workspace is NOT guarded and may be depended on freely, and
// one var in it is. This is what makes a partly privileged library expressible
// -- everyone depends on the standard library, and three vars in it matter.
{
  const libFiles = {
    'lib/l.cljc': '(ns lib.l)\n(defn safe [] "anyone")\n' +
                  '(defn ^{:flint/capabilities-guard [:fs]} danger [] "privileged")\n',
  };
  const appFiles2 = {
    'app/a.cljc': '(ns app.a (:require [lib.l :as l])) (defn go [] (l/safe))',
    'app/b.cljc': '(ns app.b (:require [lib.l :as l])) (defn go [] (l/danger))',
    'app/c.cljc': '(ns app.c (:require [lib.l :as l])) (defn go [] (first (map l/danger [1])))',
  };
  const lib = fromMap(libFiles, { workspace: 'lib/lib' });
  const bare = fromMap(appFiles2, { workspace: 'app/app' });
  const held = fromMap(appFiles2, { workspace: 'app/app', grants: ['fs'] });
  const run = async (resolve, fn) => (await (await compiler.compile({ resolve, fn })).sandbox()).call(fn);
  const refusal = async (resolve, fn) => {
    try { await compiler.compile({ resolve, fn }); return null; }
    catch (e) { return e.message; }
  };

  eq('an unguarded var in the same workspace is free',
     await run(chain(stdextra(), lib, bare), 'app.a/go'), 'anyone');
  ok('a guarded var is refused without the capability',
     /lib\.l\/danger is guarded/.test(await refusal(chain(stdextra(), lib, bare), 'app.b/go') ?? ''),
     await refusal(chain(stdextra(), lib, bare), 'app.b/go'));
  eq('and allowed with it', await run(chain(stdextra(), lib, held), 'app.b/go'), 'privileged');

  // The REFERENCE is guarded, not the call. Otherwise `(map l/danger ..)` would
  // hand the function to something unguarded and the guard would be one line of
  // indirection deep.
  ok('and refused when merely passed as a value, not called',
     /lib\.l\/danger is guarded/.test(await refusal(chain(stdextra(), lib, bare), 'app.c/go') ?? ''),
     await refusal(chain(stdextra(), lib, bare), 'app.c/go'));

  // "A program declaring no workspaces" becomes "an answer with no `workspace`
  // field at all" -- workspace comes only from the answer now, so leaving it
  // off every identity is how this case is spelled.
  const noWsFiles = { ...libFiles, 'app/b.cljc': appFiles2['app/b.cljc'] };
  eq('a program declaring no workspaces is checked nowhere',
     await run(chain(stdextra(), fromMap(noWsFiles)), 'app.b/go'), 'privileged');
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
  const libSrc = '(ns lib.l)\n' +
    '(defn ^{:flint/capabilities-guard [:fs]} danger [] "privileged")\n' +
    '(defmacro sneak [] `(danger))\n' +
    '(defn wrapped [] (danger))\n';
  const lib = fromMap({ 'lib/l.cljc': libSrc }, { workspace: 'lib/lib', grants: ['fs'] });
  const app = fromMap({
    'app/m.cljc': '(ns app.m (:require [lib.l :as l])) (defn go [] (l/sneak))',
    'app/f.cljc': '(ns app.f (:require [lib.l :as l])) (defn go [] (l/wrapped))',
  }, { workspace: 'app/app' });
  const resolve = chain(stdextra(), lib, app);
  let refused = null;
  try { await compiler.compile({ resolve, fn: 'app.m/go' }); }
  catch (e) { refused = e.message; }
  ok('a macro cannot expand a guarded reference into an ungranted caller',
     /lib\.l\/danger is guarded/.test(refused ?? ''), refused);
  eq('but a function in the granted workspace wraps it freely',
     await (await (await compiler.compile({ resolve, fn: 'app.f/go' })).sandbox())
       .call('app.f/go'),
     'privileged');
}

// --- asking the host for something (`DECISIONS.md#workspace-capabilities` step 7) ------------
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
  const bare = chain(stdextra(), fromMap(files, { workspace: 'app/app' }));
  const held = chain(stdextra(), fromMap(files, { workspace: 'app/app', grants: ['host'] }));

  let refused = null;
  try { await compiler.compile({ resolve: bare, fn: 'app.a/go' }); }
  catch (e) { refused = e.message; }
  ok('asking the host is refused without the :host capability',
     /flint\.host\/request is guarded/.test(refused ?? ''), refused);

  const sb = await (await compiler.compile({
    resolve: held, exports: ['app.a/go', 'app.a/opt', 'app.a/nily'], fn: 'app.a/go',
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

// --- virtual namespaces (`DECISIONS.md#workspace-capabilities` step 4, `system-namespaces-and-deps`) ---------------
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
  const app = fromMap(files, { workspace: 'app/app' });
  const virt = virtualNamespaces({ 'demo.svc': { workspace: 'demo/demo' } });
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
  const sb = await (await compiler.compile({ resolve: chain(stdextra(), virt, app), fn: 'app.a/go', exports }))
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
    await compiler.compile({ fn: 'app.a/unknown',
      resolve: chain(stdextra(),
        virtualNamespaces({ 'demo.svc': { workspace: 'demo/demo', vars: [{ name: 'greet', arities: [1] }] } }),
        app) });
  } catch (e) { checked = e.message; }
  ok('with a var list, an unknown var is a COMPILE error',
     /does not hold nope/.test(checked ?? ''), checked);
  ok('without one it compiles, and fails when called',
     /no var nope/.test(farErr ?? ''), farErr);
}

// --- HOSTILE STDCORE (AGENTS.md #5: probe, with a control) -------------------
//
// stdcore is answered by the SDK's embedded copy BEFORE a host's resolver ever
// runs (`compileCall` in `resolve.js`: `if (stdcore.has(ns)) return stdcore(ns)`
// -- the user resolver is not even called). This resolver tries to redefine
// `clojure.core`'s `str` and `flint.port` (a control-plane root) with hostile
// source, and ALSO answers `clojure.set` -- not stdcore -- with a distinctive
// body. The calls it was actually asked for are recorded, so "never consulted
// for a stdcore name" is checked directly and not inferred from the program's
// result alone.
{
  const calls = [];
  const hostile = (ns) => {
    calls.push(String(ns));
    if (String(ns) === 'clojure.core' || String(ns) === 'flint.port') {
      return `(ns ${ns}) (defn str [& _] "PWNED")`;
    }
    if (String(ns) === 'clojure.set') {
      return '(ns clojure.set) (defn union [& _] "MINE")';
    }
    return null;
  };
  const files = {
    'hostileapp.cljc': '(ns hostileapp (:require [clojure.set :as set])) ' +
                '(defn go [] [(str 1 2) (set/union #{1} #{2})])',
  };
  // `stdextra()` AFTER `hostile`: a non-stdcore name the hostile resolver wants
  // to answer (`clojure.set`) must win the race, which is the control -- if it
  // did not, this test would not be able to tell "never asked" from "asked and
  // ignored".
  const resolve = chain(hostile, stdextra(), fromMap(files));
  const sb = await (await compiler.compile({ resolve, fn: 'hostileapp/go' })).sandbox();
  const result = await sb.call('hostileapp/go');
  eq('clojure.core is the real one despite a hostile resolver answering it',
     result[0], '12');
  // The CONTROL: the same resolver's answer for a non-stdcore name DOES take
  // effect, so "stdcore never reached the resolver" is not just "the resolver
  // never did anything".
  eq('and a non-stdcore name (clojure.set) DOES take the resolver\'s answer',
     result[1], 'MINE');
  ok('the resolver was never asked for a stdcore name',
     !calls.includes('clojure.core') && !calls.includes('flint.port'),
     JSON.stringify(calls));
}

// --- a resolver answering every wave with a Promise -------------------------
{
  const filesP = { 'promiseapp.cljc': '(ns promiseapp) (defn main [] "ok")' };
  const inner = chain(stdextra(), fromMap(filesP));
  const resolve = (ns) => Promise.resolve(inner(ns));
  const sb = await (await compiler.compile({ resolve, fn: 'promiseapp/main' })).sandbox();
  eq('a resolver that answers every wave with a Promise compiles and runs',
     await sb.call('promiseapp/main'), 'ok');
}

// --- `.fln` wins over `.cljc` for the same namespace -------------------------
{
  const fln = '(ns flnapp) (defn val [] "fln")';
  const cljc = '(ns flnapp) (defn val [] "cljc")';
  const sbBoth = await (await compiler.compile({
    resolve: chain(stdextra(), fromMap({ 'flnapp.fln': fln, 'flnapp.cljc': cljc })),
    fn: 'flnapp/val',
  })).sandbox();
  eq('.fln wins over .cljc for the same namespace', await sbBoth.call('flnapp/val'), 'fln');
  // Control: with only the `.cljc` present, its own answer is what shows up --
  // the win above is a dialect preference, not `.cljc` being ignored outright.
  const sbCljc = await (await compiler.compile({
    resolve: chain(stdextra(), fromMap({ 'flnapp.cljc': cljc })),
    fn: 'flnapp/val',
  })).sandbox();
  eq('and with only .cljc present, its answer is what compiles', await sbCljc.call('flnapp/val'), 'cljc');
}

// --- stdextra omitted --------------------------------------------------------
//
// `clojure.core` is stdcore, so a resolver with no `stdextra()` still compiles a
// program written in it. `flint.check` is stdcore too now (the maintainer's
// revision, `DECISIONS.md#checks`): it moved out of stdextra because it is
// meant to ALWAYS exist, a root unconditionally like `flint.port`/`flint.wire`,
// picking one of its two internal variants per compile rather than vanishing
// under `:optimize [perf]`. So, unlike before this revision, checks being ON
// is no longer a reason a no-`stdextra()` compile needs `checks: false` --
// that case is asserted directly below, next to the one `clojure.set` (a true
// stdextra namespace) still needs it for.
{
  const filesD1 = { 'stdxapp.cljc': '(ns stdxapp) (defn go [] (vec (map str [1 2 3])))' };
  const sb = await (await compiler.compile({
    resolve: fromMap(filesD1), fn: 'stdxapp/go', exports: ['stdxapp/go'], checks: false,
  })).sandbox();
  eq('with no stdextra() and checks off, clojure.core is all there (str, map, vec)',
     await sb.call('stdxapp/go'), ['1', '2', '3']);

  // CHECKS ON, STILL NO `stdextra()` AT ALL: this used to throw "no source for
  // flint.check" (588f2980), because flint.check lived in stdextra. It is
  // stdcore now, so the identical program with no `resolve` fallback beyond
  // `fromMap` simply compiles.
  const sb2 = await (await compiler.compile({
    resolve: fromMap(filesD1), fn: 'stdxapp/go', exports: ['stdxapp/go'],
  })).sandbox();
  eq('and with checks ON and no stdextra() at all, it compiles too -- flint.check is stdcore now',
     await sb2.call('stdxapp/go'), ['1', '2', '3']);

  const filesD2 = { 'stdxapp2.cljc': '(ns stdxapp2 (:require [clojure.set :as set])) (defn go [] (set/union #{1} #{2}))' };
  let errsD2 = null;
  try { await compiler.compile({ resolve: fromMap(filesD2), fn: 'stdxapp2/go', checks: false }); }
  catch (e) { errsD2 = e.errors; }
  ok('and a program requiring clojure.set, still without stdextra, reports it :missing',
     Array.isArray(errsD2) && errsD2.some((e) => e[':kind'] === ':missing' && e[':ns'] === 'clojure.set'),
     JSON.stringify(errsD2));
}

// --- flint.check with no #? wrapper, in both builds ---------------------------
//
// The whole point of the revision: a BARE `(flint.check/expect pred x)`, with
// no `#?(:flint/check ...)` around it and no `:require [flint.check]`,
// compiles in a checked build (and really checks) AND in an unchecked one
// (and costs nothing). The argument is a call with a visible side effect --
// bumping an atom -- so "checked" and "inlined away to nil, argument
// discarded" are told apart by whether the bump happened, not just by `expect`'s
// own return value.
{
  const src = '(ns barecheck.app)\n' +
    '(def counter (atom 0))\n' +
    '(defn bump [] (swap! counter inc) true)\n' +
    '(defn go [] [(flint.check/expect (fn [x] x) (bump)) @counter])\n';
  const files = { 'barecheck/app.cljc': src };

  const sbOn = await (await compiler.compile({
    resolve: chain(stdextra(), fromMap(files)), fn: 'barecheck.app/go',
  })).sandbox();
  eq('checks ON: a bare flint.check/expect still checks, and evaluates its argument once',
     await sbOn.call('barecheck.app/go'), [true, 1]);

  const sbOff = await (await compiler.compile({
    resolve: chain(stdextra(), fromMap(files)), fn: 'barecheck.app/go', checks: false,
  })).sandbox();
  eq('checks OFF: the same bare call expands to nil -- the argument (the bump) never runs',
     await sbOff.call('barecheck.app/go'), [null, 0]);
}

// --- the OFF variant's throwing half -------------------------------------------
//
// `run-tests` only makes sense while actually running checks, so its OFF
// variant throws a clear, named message rather than no-opping.
{
  const files = { 'runoff/app.cljc': '(ns runoff.app) (defn go [] (flint.check/run-tests []))' };
  let threw = null;
  try {
    const sb = await (await compiler.compile({
      resolve: chain(stdextra(), fromMap(files)), fn: 'runoff.app/go', checks: false,
    })).sandbox();
    await sb.call('runoff.app/go');
  } catch (e) { threw = e; }
  ok('checks OFF: flint.check/run-tests throws, naming why',
     threw != null && /checks are not enabled in this build/.test(String(threw.message ?? threw)),
     threw ? String(threw.message ?? threw) : 'it did not throw');
}

// --- a hostile resolver cannot answer flint.check, on or off -------------------
//
// stdcore's "answered first, never asked of the resolver" guarantee applies to
// `flint.check` exactly as it does to `clojure.core` (the test above this
// file's original hostile-stdcore block), checked here in BOTH builds since
// which variant resolves is a `features` question and this is a resolver-
// priority question -- orthogonal, and both need checking.
for (const checks of [true, false]) {
  const calls = [];
  const hostile = (ns) => { calls.push(String(ns)); return null; };
  const files = { 'notcheckedapp.cljc':
    '(ns notcheckedapp) (defn go [] (flint.check/test-var? {:flint.check/test true}))' };
  const resolve = chain(hostile, stdextra(), fromMap(files));
  const sb = await (await compiler.compile({ resolve, fn: 'notcheckedapp/go', checks })).sandbox();
  eq(`checks ${checks}: flint.check/test-var? is still the real one (stdcore wins over a resolver)`,
     await sb.call('notcheckedapp/go'), true);
  ok(`checks ${checks}: the resolver was never asked for flint.check`,
     !calls.includes('flint.check'), JSON.stringify(calls));
}

// --- the bundle split: stdextra's bytes are not in the core bundle -----------
//
// `dist/stdextra.js` imports `dist/stdextra.forms` `with { type: 'bytes' }`;
// `dist/flint.js` never imports it. esbuild turns such an import into the
// base64 of the WHOLE file, contiguous, so a 60-byte slice at an offset that
// is a multiple of 3 (preserving base64's byte/char alignment) has to appear
// verbatim in the bundle that carries it and nowhere else. *Confirmed by
// direct inspection in this worktree* before writing this assertion: at
// offset 3000, the stdextra slice is in `dist/stdextra.js` and not in
// `dist/flint.js`, and the stdcore slice (from `dist/stdcore.forms`, which
// `flint.js` DOES import) is in `dist/flint.js` and not in `dist/stdextra.js`.
// If a future esbuild stopped inlining this way, these four assertions would
// all flip together, which is itself the signal to come back and adapt them.
{
  const root = new URL('../../', import.meta.url).pathname;
  const flintJsText = readFileSync(new URL('./dist/flint.js', import.meta.url), 'utf8');
  const stdextraJsText = readFileSync(new URL('./dist/stdextra.js', import.meta.url), 'utf8');
  const stdcoreForms = readFileSync(`${root}dist/stdcore.forms`);
  const stdextraForms = readFileSync(`${root}dist/stdextra.forms`);
  const offset = 3 * 1000;
  const len = 60;
  const stdcoreSlice = Buffer.from(stdcoreForms.subarray(offset, offset + len)).toString('base64');
  const stdextraSlice = Buffer.from(stdextraForms.subarray(offset, offset + len)).toString('base64');
  ok('stdextra.forms bytes are inlined in dist/stdextra.js', stdextraJsText.includes(stdextraSlice));
  ok('and NOT in dist/flint.js, which never imports stdextra.forms',
     !flintJsText.includes(stdextraSlice));
  ok('stdcore.forms bytes are inlined in dist/flint.js, which imports it unconditionally',
     flintJsText.includes(stdcoreSlice));
  ok('and NOT in dist/stdextra.js', !stdextraJsText.includes(stdcoreSlice));
}

// --- flint.cli ships with the CLIs only; flint.deps is optional ---------------
//
// `DECISIONS.md#four-units`: `flint.cli` is the two CLIs' command surface and is
// in neither ESM bundle; `flint.deps` is its own unit, offered as `deps()` from
// `@3sln/flint/deps`. The control for the absence: the SAME program compiles
// once `deps()` is in the chain, so the refusal is about what was composed and
// not about the program.
{
  const bundles = ['flint.js', 'stdextra.js', 'deps.js']
    .map((f) => readFileSync(new URL(`./dist/${f}`, import.meta.url), 'utf8'));
  ok('no ESM bundle carries flint.cli', bundles.every((t) => !t.includes('flint.cli/run')),
     'found the text flint.cli/run in a bundle');
  ok('stdextra() does not answer flint.cli or flint.deps',
     !stdextra().has('flint.cli') && !stdextra().has('flint.deps'));
  const files = { 'depsapp.cljc': '(ns depsapp (:require [flint.deps :as d])) (defn main [_] (fn? d/coord-type))' };
  let missing = null;
  try { await compiler.compile({ resolve: chain(stdextra(), fromMap(files)), fn: 'depsapp/main' }); }
  catch (e) { missing = e; }
  ok('without deps() a program requiring flint.deps reports it missing',
     missing && /flint\.deps/.test(missing.message), String(missing));
  const sb = await (await compiler.compile({ resolve: chain(stdextra(), deps(), fromMap(files)),
                                             fn: 'depsapp/main' })).sandbox();
  eq('and with deps() in the chain the same program compiles and runs', await sb.call('depsapp/main', [null]), true);
}

// --- a resolver that throws -------------------------------------------------
{
  const resolve = (ns) => { throw new Error(`hostile: refuse to answer ${ns}`); };
  let threw = null;
  try { await compiler.compile({ resolve, fn: 'throwapp/main' }); }
  catch (e) { threw = e; }
  ok('a resolver that throws makes the compile REJECT, not hang',
     threw instanceof Error && /hostile: refuse/.test(threw.message), String(threw));
}

// --- an answer that declares a different namespace than it was asked for -----
//
// `DECISIONS.md#a-source-defines-only-its-own-namespace`, checked at the
// answer: an answer for `wrongnsapp` whose source says `(ns totally.different)`
// is refused, not silently accepted under either name.
{
  const files = { 'wrongnsapp.cljc': '(ns totally.different) (defn main [_] 1)' };
  let threw = null;
  try { await compiler.compile({ resolve: chain(stdextra(), fromMap(files)), fn: 'wrongnsapp/main' }); }
  catch (e) { threw = e; }
  ok('an answer declaring a different namespace than it was asked for is refused',
     threw instanceof Error, String(threw));
}

// --- grants come from the answer, never from the path (AGENTS.md #5 control) --
//
// The redesign's whole point, per `DECISIONS.md#namespaces-over-the-system-port`
// ("Why", the measured bypass): a user file whose PATH starts with `flint/`
// used to inherit the standard library's grants. Here a user resolver CAN
// answer `flint.evil` -- segregation is absent, and that is fine, the note
// says so -- but it gets NO grant for doing so. Served with no identity at
// all, it is refused exactly like any other ungranted caller of a `:host`-
// guarded function; served again with `{workspace, grants}` on the SAME
// answer, it compiles. The path never changes; only the answer does.
{
  const evilSrc = '(ns flint.evil (:require [flint.host :as h])) (defn go [] (h/ask "x"))';
  const noGrant = chain(stdextra(), fromMap({ 'flint/evil.cljc': evilSrc }));
  let refused = null;
  try { await compiler.compile({ resolve: noGrant, fn: 'flint.evil/go' }); }
  catch (e) { refused = e.message; }
  ok('a user file under flint/ gets no grant from its path alone',
     /flint\.host\/ask is guarded with #\{:host\}/.test(refused ?? ''), refused);

  const held = chain(stdextra(), fromMap({ 'flint/evil.cljc': evilSrc },
                                          { workspace: 'app/app', grants: ['host'] }));
  let compiledOk = true, err = null;
  try { await compiler.compile({ resolve: held, fn: 'flint.evil/go' }); }
  catch (e) { compiledOk = false; err = e.message; }
  ok('and the SAME file compiles once its answer carries the grant', compiledOk, err);
}

// --- the artifact stands on its own -----------------------------------------
const dir = mkdtempSync(`${tmpdir()}/flint-`);
writeFileSync(`${dir}/m.wasm`, image.wasm);
const root = new URL('../../', import.meta.url).pathname;
// The FUNCTION is named, and then its arguments. `x` alone used to be the
// argument, back when there was an entry point to default to; with `main` gone
// there is nothing to default to and nothing named `x` (`DECISIONS.md#structured-ports`).
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
