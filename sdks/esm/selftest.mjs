// The distributable, exercised through the SDK and nothing else.
//
// The point of this file is the negative: it uses `dist/` and `sdk/` only. If
// it passes, someone with node and the npm package can compile and run Clojure
// with no babashka, no JVM, no Rust and no linker -- which is the whole claim.
// The DISTRIBUTABLE, not the source: what is tested is what ships. It is one
// portable ESM with everything inside it, so this import is the only thing a
// consumer does.
import { Compiler, Program, loaderBuiltins, standardLibrary } from './dist/flint.js';

let fails = 0;
const ok = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? '\n        ' + detail : '')); }
};

console.log('the wasm distributable');

const compiler = await Compiler.load();
ok(`the runtime lists its builtins (${loaderBuiltins().length})`, loaderBuiltins().length > 100);
ok(`the standard library is present (${Object.keys(standardLibrary()).length} files)`,
   Object.keys(standardLibrary()).length > 5);

// --- what a compiler is expected to emit ------------------------------------
const t0 = Date.now();
const wasm = compiler.compile({
  files: {
    'app.cljc': `(ns app (:require [app.util :as u]))
                 (defn main [args] (u/shout (or (first args) "world")))`,
    'app/util.cljc': `(ns app.util (:require [clojure.string :as s]))
                      (defn shout [x] (s/upper-case (str "hello " x "! "
                                        (reduce + 0 (range 10))))) `,
  },
  entry: 'app/main',
});
ok(`a two-namespace program compiles to a module (${wasm.length.toLocaleString()} bytes, ` +
   `${((Date.now() - t0) / 1000).toFixed(1)} s)`, wasm.length > 100_000);

const program = await Program.load(wasm);
const r = program.run(['flint']);
ok('and runs', r.code === 0 && r.out === 'HELLO FLINT! 45', JSON.stringify(r));
ok('the same program runs again, sharing nothing',
   program.run(['again']).out === 'HELLO AGAIN! 45');

// A compile error must arrive as a message, not as a trap or a wrong answer.
try {
  compiler.compile({ files: { 'bad.cljc': '(ns bad)\n(defn main [_] (nope 1))' }, entry: 'bad/main' });
  ok('a compile error is reported', false, 'it compiled');
} catch (e) {
  ok('a compile error is reported', /unable to resolve symbol: nope/.test(e.message), e.message);
}
try {
  compiler.compile({ files: { 'x.cljc': '(ns x (:require [no.such.ns :as n]))\n(defn main [_] 1)' }, entry: 'x/main' });
  ok('a missing namespace is named', false, 'it compiled');
} catch (e) {
  ok('a missing namespace is named', /no\.such\.ns/.test(e.message), e.message);
}

// --- a program that talks to the host ---------------------------------------
//
// The module above answers and exits. This one PARKS: it opens a port by name,
// sends, and waits. Nothing about it works unless the caller services the
// request and resumes the guest, which is what a capability is.
//
// It is also the case tree shaking got wrong twice. A `call_indirect` reaches
// any table entry, the scan only follows `call`, and the scheduler indirects --
// so shaking to "the builtins this image imports" stubbed the scheduler's own
// callbacks. Every program here worked and this one trapped.
const echo = compiler.compile({
  files: {
    'echo.cljc': `(ns echo (:require [flint.port :as p] [flint.port.edn :as edn]))
                  (defn main [args]
                    (let [port (p/open "echo" {:codec edn/codec})]
                      (p/send port {:said (or (first args) "hello")})
                      (let [reply (p/receive port)]
                        (p/close port)
                        (str "the host said: " (:heard reply)))))`,
  },
  entry: 'echo/main',
});
const echoed = (await Program.load(echo)).run(['ping'], {
  capabilities: {
    echo: { message: (port, data, api) => api.deliver(port, `{:heard "pong"}`) },
  },
});
ok('a program can park on a host port and be resumed',
   echoed.code === 0 && echoed.out === 'the host said: pong', JSON.stringify(echoed));

// And refusing is a normal outcome, not a crash: a capability nobody granted
// reaches the program as a catchable error.
const refused = (await Program.load(echo)).run(['ping']);
ok('and a capability nobody granted is refused, catchably',
   refused.code === 1 && /refused the capability/.test(refused.out), JSON.stringify(refused));

// --- the module stands on its own -------------------------------------------
//
// It has to run somewhere other than here, and say what it is, or it is not an
// artifact -- just something this file happened to be able to use.
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';

const dir = mkdtempSync(`${tmpdir()}/flint-`);
writeFileSync(`${dir}/m.wasm`, wasm);
const root = new URL('../../', import.meta.url).pathname;
const out = execFileSync('node', [`${root}host/flint.mjs`, `${dir}/m.wasm`, 'there'],
                         { encoding: 'utf8' }).trim();
ok('and it runs under a different host', out === 'HELLO THERE! 45', out);

const meta = execFileSync(`${root}bin/flint`, ['inspect', `${dir}/m.wasm`],
                          { encoding: 'utf8', cwd: root });
ok('and it describes itself', /entry app\/main/.test(meta), meta.split('\n')[0]);

process.exit(fails ? 1 : 0);
