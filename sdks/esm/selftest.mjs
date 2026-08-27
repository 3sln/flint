// The distributable, exercised through the SDK and nothing else.
//
// The point of this file is the negative: it uses `dist/` and `sdk/` only. If
// it passes, someone with node and the npm package can compile and run Clojure
// with no babashka, no JVM, no Rust and no linker -- which is the whole claim.
// The DISTRIBUTABLE, not the source: what is tested is what ships. It is one
// portable ESM with everything inside it, so this import is the only thing a
// consumer does.
import { Compiler, Runtime, loaderBuiltins, standardLibrary } from './dist/flint.js';

let fails = 0;
const ok = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? '\n        ' + detail : '')); }
};

console.log('the wasm distributable');

const compiler = await Compiler.load();
const runtime = await Runtime.load();
ok(`the loader lists its builtins (${loaderBuiltins().length})`, loaderBuiltins().length > 100);
ok(`the standard library is present (${Object.keys(standardLibrary()).length} files)`,
   Object.keys(standardLibrary()).length > 5);

const t0 = Date.now();
const image = compiler.compile({
  files: {
    'app.cljc': `(ns app (:require [app.util :as u]))
                 (defn main [args] (u/shout (or (first args) "world")))`,
    'app/util.cljc': `(ns app.util (:require [clojure.string :as s]))
                      (defn shout [x] (s/upper-case (str "hello " x "! "
                                        (reduce + 0 (range 10)))))`,
  },
  entry: 'app/main',
});
const compileMs = Date.now() - t0;
ok(`a two-namespace program compiles (${image.length} bytes, ${compileMs} ms)`, image.length > 100);

const r = runtime.run(image, ['flint']);
ok('and runs', r.code === 0 && r.out === 'HELLO FLINT! 45', JSON.stringify(r));
ok('the same image runs again, sharing nothing',
   runtime.run(image, ['again']).out === 'HELLO AGAIN! 45');

// A compile error must arrive as a message, not as a trap or a wrong answer.
try {
  compiler.compile({ files: { 'bad.cljc': '(ns bad)\n(defn main [_] (nope 1))' }, entry: 'bad/main' });
  ok('a compile error is reported', false, 'it compiled');
} catch (e) {
  ok('a compile error is reported', /unable to resolve symbol: nope/.test(e.message), e.message);
}
// And so must a missing namespace, naming what is missing.
try {
  compiler.compile({ files: { 'x.cljc': '(ns x (:require [no.such.ns :as n]))\n(defn main [_] 1)' }, entry: 'x/main' });
  ok('a missing namespace is named', false, 'it compiled');
} catch (e) {
  ok('a missing namespace is named', /no\.such\.ns/.test(e.message), e.message);
}

// --- a MODULE, which is what a compiler is expected to emit ----------------
//
// The image above is internal machinery. This is the artifact: a standalone
// `.wasm` the caller can instantiate, produced by splicing that image into a
// prebuilt runtime -- with no linker anywhere, because the runtime was linked
// once when flint was built.
// node only for the harness -- writing the module out and running it through
// the standalone host. The SDK itself imports nothing.
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';

const modT0 = Date.now();
const wasm = compiler.compileToWasm({
  files: { 'app.cljc': '(ns app)\n(defn main [args] (str "module " (reduce + 0 (range 10))))' },
  entry: 'app/main',
});
ok(`a standalone module is emitted (${wasm.length.toLocaleString()} bytes, ` +
   `${((Date.now() - modT0) / 1000).toFixed(1)} s)`, wasm.length > 100_000);

// It has to RUN, and nothing else here would notice if it did not.
const dir = mkdtempSync(`${tmpdir()}/flint-`);
writeFileSync(`${dir}/m.wasm`, wasm);
const root = new URL('../../', import.meta.url).pathname;
const out = execFileSync('node', [`${root}host/flint.mjs`, `${dir}/m.wasm`],
                         { encoding: 'utf8' }).trim();
ok('and it runs on its own, with no loader and no image', out === 'module 45', out);

// And it says what it is (doc/decisions/0020), read from its bytes.
const meta = execFileSync(`${root}bin/flint`, ['inspect', `${dir}/m.wasm`],
                          { encoding: 'utf8', cwd: root });
ok('and it describes itself', /entry app\/main/.test(meta), meta.split('\n')[0]);

process.exit(fails ? 1 : 0);
