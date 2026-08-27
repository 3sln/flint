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
eq('a function is called by name', sandbox.call('app/greet', ['flint']), 'HELLO FLINT');
eq('any function, not one entry point', sandbox.call('app.util/shout', ['x']), 'X');

// State is the SANDBOX's: initialisers run once, not per call, which is what
// makes instantiate-once-call-per-request work at all.
sandbox.call('app/tally');
eq('a sandbox keeps its state across calls', sandbox.call('app/tally'), 2);
const fresh = await image.sandbox();
eq('and a fresh sandbox shares none of it', fresh.call('app/tally'), 1);

// --- what can cross ---------------------------------------------------------
eq('a map round-trips through a call',
   sandbox.call('app/echo', [{ a: 1, b: [1, 2] }]), { a: 1, b: [1, 2] });
eq('a keyword survives', sandbox.call('app/echo', [':a']), ':a');
ok('a set survives', sandbox.call('app/echo', [new Set([1, 2])]) instanceof Set);
eq('nil survives', sandbox.call('app/echo', [null]), null);

// --- failure is data --------------------------------------------------------
try {
  sandbox.call('app/boom');
  ok('a thrown error reaches the caller', false, 'it returned');
} catch (e) {
  ok('a thrown error reaches the caller', /deliberate/.test(JSON.stringify(e.flint)),
     JSON.stringify(e.flint));
}
try {
  sandbox.call('app/nope');
  ok('an unknown function is named', false, 'it returned');
} catch (e) {
  ok('an unknown function is named', /app\/nope/.test(JSON.stringify(e.flint)),
     JSON.stringify(e.flint));
}

// --- diagnostics ------------------------------------------------------------
ok('gas is readable after a call, in every build', sandbox.diagnostics.gas > 0,
   JSON.stringify(sandbox.diagnostics));

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

// --- the artifact stands on its own -----------------------------------------
const dir = mkdtempSync(`${tmpdir()}/flint-`);
writeFileSync(`${dir}/m.wasm`, image.wasm);
const root = new URL('../../', import.meta.url).pathname;
const out = execFileSync('node', [`${root}host/flint.mjs`, `${dir}/m.wasm`, 'x'],
                         { encoding: 'utf8' }).trim();
ok('the module runs under a different host', out.startsWith('main saw'), out);
const meta = execFileSync(`${root}bin/flint`, ['inspect', `${dir}/m.wasm`],
                          { encoding: 'utf8', cwd: root });
ok('and describes itself', /entry app\/main/.test(meta), meta.split('\n')[0]);

process.exit(fails ? 1 : 0);
