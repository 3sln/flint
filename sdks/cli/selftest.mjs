// Does this package do the whole job?
//
// The temptation with a wasm CLI is to check that the compiler instantiates and
// answers, which it always did -- `host/flint.mjs` has run `dist/flintc.wasm`
// for a long time. That proves nothing about a CLI, because the CLI is the
// compiler PLUS the source reading, the namespace resolution and the server of
// namespaces the program calls back out to. So every check here goes end to
// end: a directory in, an answer out.
//
// THE STRONG CHECK IS THE LAST ONE. If `target/release/flint` has been built,
// the same project is compiled with both CLIs and the bytes are compared. It is
// skipped when the binary is absent -- a developer with no Rust toolchain still
// gets the rest -- and the skip is ANNOUNCED, because a check that silently
// passes when it ran nothing is worse than no check.

import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { under } from './src/sys.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');
const FIXTURE = join(here, 'fixture', 'src');
const CLI = join(here, 'bin', 'flint.mjs');
const NATIVE = join(repo, 'target', 'release', 'flint');

let failures = 0;
function check(what, ok, detail) {
  if (ok) { console.log(`ok    ${what}`); return; }
  failures += 1;
  console.error(`FAIL  ${what}`);
  if (detail !== undefined) console.error(String(detail).split('\n').map((l) => `        ${l}`).join('\n'));
}

function flint(args, opts = {}) {
  try {
    return {
      code: 0,
      out: execFileSync(process.execPath, [CLI, ...args], {
        encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], cwd: opts.cwd ?? here,
        // `env` was NOT threaded through, so a row that set one silently got
        // the parent's environment and asserted against a run that never saw
        // its variable. The gas-limit row passed the limit, the CLI never
        // received it, and the check read as a failure of the product.
        env: opts.env ?? process.env,
      }),
    };
  } catch (e) {
    return { code: e.status ?? 1, out: `${e.stdout ?? ''}${e.stderr ?? ''}` };
  }
}

// --- the containment rule, on its own ---------------------------------------
//
// An access check FAILS OPEN: a broken one produces no error, no warning and a
// passing suite -- the same output as a working one. So the refusals are
// asserted by name, each beside a control that differs in exactly one thing.

console.log('== flint.sys.fs path containment ==');
const ok = (p) => { try { under('/root', p); return true; } catch { return false; } };
for (const p of ['a/b', './a', 'a/./b', '', 'a//b']) {
  check(`under() admits ${JSON.stringify(p)}`, ok(p));
}
for (const p of ['../etc/passwd', 'a/../../etc', '/etc/passwd', 'a/../..',
                 'a/../b', '..\\..\\etc', 'C:\\windows', '/']) {
  check(`under() refuses ${JSON.stringify(p)}`, !ok(p));
}
// `a/../b` normalises to somewhere INSIDE the root and is still refused.
// Popping `..` would make `a/../../x` depend on how deep `a` was, which is
// exactly the arithmetic an attacker gets to do.
check('under() puts a plain path under the root', under('/root', 'a/b') === '/root/a/b');

// --- end to end -------------------------------------------------------------

console.log('== run ==');
{
  const r = flint(['run', ':path', FIXTURE, ':fn', 'demo.main/main', ':args', '[a b]']);
  const want = 'args=["a" "b"]\ngreet=hello, World\nupper=ABC\nsum=15\n';
  check('a multi-namespace project runs', r.code === 0 && r.out === want, r.out);
}
{
  // A program granted nothing must be TOLD so rather than parked: the honest
  // failure, and the thing a host that installs a system port unconditionally
  // would quietly break.
  //
  // REFUSED BY NAME, not by the transport being missing. `flint.ception` is served
  // to every program (`DECISIONS.md#flint-ception`), so a system port now always
  // exists and the refusal names the namespace instead. Still a refusal, still
  // non-zero, and strictly more informative -- but the sentence changed, and
  // this row is what noticed.
  const r = flint(['run', ':path', FIXTURE, ':fn', 'demo.sysdemo/main', ':args', '[.]']);
  check('a program granted nothing cannot ask',
        r.code !== 0
          && r.out.includes('refused to open')
          && r.out.includes('flint.sys.env'), r.out);
}
{
  const r = flint(['run', ':path', FIXTURE, ':fn', 'demo.sysdemo/main',
                   ':with', '[fs env]', ':args', '[fixture/src]']);
  const lines = Object.fromEntries(
    r.out.split('\n').filter((l) => l.includes('=')).map((l) => {
      const i = l.indexOf('=');
      return [l.slice(0, i), l.slice(i + 1)];
    }));
  check('flint.sys.fs answers a read', lines['control-read'] === 'true', r.out);
  check('flint.sys.env answers cwd', lines['cwd-is-string'] === 'true', r.out);
  check('a listing is sorted',
        lines.listed === '["checks.cljc" "main.cljc" "sysdemo.cljc" "util.cljc"]', r.out);
  check('an existing file exists and a missing one does not',
        lines.exists === 'true' && lines.missing === 'false', r.out);
  for (const k of ['escape-dotdot', 'escape-inside', 'escape-absolute', 'escape-absent']) {
    check(`${k} is refused`, lines[k] === 'refused', r.out);
  }
  check('a read-only :fs grant refuses write-file', lines.write === 'refused', r.out);
}

console.log('== test ==');
{
  const r = flint(['test', ':path', FIXTURE]);
  check('every check on the path runs, including one nothing requires',
        r.code === 0 && r.out.includes('2/2 checks passed'), r.out);
}

console.log('== flint.ception ==');
{
  // NOTHING GATED THIS SIDE UNTIL NOW. `test/sysns.clj` covers the SDK
  // thoroughly, but it drives `target/release/flint` -- so every assertion
  // about `flint.ception` was about the native front end, and node's
  // implementation was checked only by hand. That is the shape the `:checks`
  // divergence had (`DECISIONS.md#aot-diverges-between-hosts`): one front end
  // exercised, the other assumed.
  const d = mkdtempSync(join(tmpdir(), 'flint-cli-sdk-'));
  try {
    writeFileSync(join(d, 'deps.edn'), '{}');
    writeFileSync(join(d, 'drv.cljc'),
      '(ns drv (:require [flint.ception :as sdk]))\n'
      + '(def src (str "(ns kid)\\n"\n'
      + '              "(defn greet [a b] (str \\"hi \\" a \\" and \\" b))\\n"\n'
      + '              "(defn main [args] (str \\"kid ran with \\" (count args) \\" args\\"))\\n"))\n'
      + '(defn go [_]\n'
      + '  (let [r (sdk/run {:sources {"kid" src} :fn "kid/main" :args ["x" "y"]})\n'
      + '        img (sdk/compile {:sources {"kid" src} :fn "kid/main" :exports ["kid/greet"]})\n'
      + '        b (sdk/sandbox img)\n'
      + '        k (sdk/caller b)\n'
      + '        c (sdk/call k "kid/greet" ["ada" "alan"])]\n'
      + '    (sdk/close-caller k)\n'
      + '    (sdk/close b)\n'
      + '    (str "run=" (:out r) " call=" c)))\n'
      + '(defn mint [_] (:out (sdk/run {:sources {"kid" src} :fn "kid/main" :with ["fs"]})))\n');

    // NO CAPABILITY IS ASKED FOR: the SDK takes source text and hands back
    // bytes, so it reaches nothing a program could not already reach.
    const r = flint(['run', ':path', '.', ':fn', 'drv/go'], { cwd: d });
    check('the SDK compiles and runs a nested program, ungated',
          r.out.includes('run=kid ran with 2 args'), r.out);
    check('  ... and calls a named export with individual arguments',
          r.out.includes('call=hi ada and alan'), r.out);

    // A SANDBOX CAN BE LENT A CAPABILITY, and a throw inside it is catchable
    // outside. Both were divergences when first written: the native side
    // raised where this one answered the error as data, so a `catch` around a
    // nested call fired on one front end and not the other.
    writeFileSync(join(d, 'lent.cljc'),
      '(ns lent (:require [flint.ception :as ception]))\n'
      + '(def src (str "(ns g (:require [flint.sys.env :as env]))\\n"\n'
      + '              "(defn peek [] (str \\"inner:\\" (if (env/cwd) \\"yes\\" \\"no\\")))\\n"\n'
      + '              "(defn main [a] \\"e\\")\\n"))\n'
      + '(defn go [_]\n'
      + '  (let [img (ception/compile {:sources {"g" src} :fn "g/main" :exports ["g/peek"]})\n'
      + '        b (ception/sandbox img {:with ["env"]})\n'
      + '        c (ception/caller b)]\n'
      + '    (str (ception/call c "g/peek" []))))\n'
      + '(defn boom [_]\n'
      + '  (let [img (ception/compile {:sources {"g" "(ns g)\\n(defn bang [] (throw (ex-info \\"inner blew up\\" {})))\\n(defn main [a] \\"e\\")\\n"}\n'
      + '                              :fn "g/main" :exports ["g/bang"]})\n'
      + '        b (ception/sandbox img)\n'
      + '        c (ception/caller b)]\n'
      + '    (try (ception/call c "g/bang" []) "NO THROW" (catch Throwable e (ex-message e)))))\n');
    const l = flint(['run', ':path', '.', ':fn', 'lent/go', ':with', '[env]'], { cwd: d });
    check('  ... and a sandbox lent a capability can use it',
          l.out.includes('inner:yes'), l.out);
    const bm = flint(['run', ':path', '.', ':fn', 'lent/boom'], { cwd: d });
    check('  ... and a throw inside it is catchable outside',
          bm.out.includes('inner blew up') && !bm.out.includes('NO THROW'), bm.out);

    // AUTHORITY IS NOT CREATED: a caller holding nothing may lend nothing.
    const m = flint(['run', ':path', '.', ':fn', 'drv/mint'], { cwd: d });
    check('  ... and cannot lend a capability it does not hold',
          m.code !== 0 && m.out.includes('cannot lend it'), m.out);

    // OFF UNDER A GAS LIMIT, because a nested sandbox runs on its own budget.
    const g = flint(['run', ':path', '.', ':fn', 'drv/go'],
                    { cwd: d, env: { ...process.env, FLINT_STEP_LIMIT: '50000000' } });
    check('  ... and is off under a gas limit, saying why',
          g.code !== 0 && g.out.includes('off under a gas limit'), g.out);
  } finally { rmSync(d, { recursive: true, force: true }); }
}

console.log('== compile ==');
const tmp = mkdtempSync(join(tmpdir(), 'flint-cli-selftest-'));
try {
  const out = join(tmp, 'demo.wasm');
  const r = flint(['compile', ':path', FIXTURE, ':fn', 'demo.main/main', ':out', out]);
  check('a project compiles to a module', r.code === 0 && existsSync(out), r.out);
  if (existsSync(out)) {
    const bytes = readFileSync(out);
    check('the module is wasm', bytes.subarray(0, 4).toString('latin1') === '\0asm');
    // The module is RUN, not just written. A `compile` that emits something no
    // engine will take is the failure a size check cannot see.
    const mod = await WebAssembly.compile(bytes);
    const { instantiate } = await import('./dist/guest.js');
    const got = instantiate(mod).run('demo.main/main', ['a', 'b']);
    check('and the module it wrote runs', got.code === 0 && got.out.includes('sum=15'), got.out);
  }

  console.log('== the same input through both CLIs ==');
  if (!existsSync(NATIVE)) {
    console.log(`(skipped: no ${NATIVE}; \`cargo build --release -p flint-cli\` builds it)`);
    console.log('  the byte comparison against the native CLI did NOT run');
  } else {
    const a = join(tmp, 'native.wasm');
    const b = join(tmp, 'node.wasm');
    // BOTH AXES, IN BOTH DIRECTIONS. `plain` and `:optimize [perf]` alone
    // would not have caught the bug this exists to catch: the node CLI had no
    // `:checks` at all, so it behaved as though `:checks true` were always set
    // (`DECISIONS.md#aot-diverges-between-hosts`). The last two rows are the
    // ones that pin the axes as INDEPENDENT -- drop checks without compiling,
    // and compile while keeping them.
    for (const [label, extra] of [
      ['plain', []],
      [':optimize [perf]', [':optimize', '[perf]']],
      [':checks false', [':checks', 'false']],
      [':optimize [perf] :checks true', [':optimize', '[perf]', ':checks', 'true']],
    ]) {
      execFileSync(NATIVE,
        ['compile', ':path', FIXTURE, ':fn', 'demo.main/main', ':out', a, ...extra],
        { stdio: ['ignore', 'ignore', 'ignore'] });
      flint(['compile', ':path', FIXTURE, ':fn', 'demo.main/main', ':out', b, ...extra]);
      check(`${label}: byte-identical to the native CLI`,
            Buffer.compare(readFileSync(a), readFileSync(b)) === 0,
            `native ${readFileSync(a).length} bytes, node ${readFileSync(b).length} bytes`);
    }
    // THE CONTROL. Two arms that cannot be told apart would report every line
    // above as agreement, so one pair is compiled differently on purpose.
    // A SCRIPT THROUGH BOTH DOORS. `compile <file>` is a capability the native
    // CLI and `bin/flint` gained before this one did, and nothing here would
    // have said so: the four arms above all pass `:path`, and
    // `check-api-review` compares which COMMANDS exist and not what they
    // accept. A front door that takes a shape the others do not is the drift
    // `DECISIONS.md#standalone-scripts` is about, so it gets a row.
    {
      const sf = join(tmp, 'greet.fln');
      writeFileSync(sf, '#!/usr/bin/env flint\n(ns ^:script greet)\n' +
                        '(defn main [args] (str "hello " (first args) "!"))\n');
      execFileSync(NATIVE, ['compile', sf, ':out', a],
                   { stdio: ['ignore', 'ignore', 'ignore'] });
      flint(['compile', sf, ':out', b]);
      check('a script: byte-identical to the native CLI',
            Buffer.compare(readFileSync(a), readFileSync(b)) === 0,
            `native ${readFileSync(a).length} bytes, node ${readFileSync(b).length} bytes`);
    }
    execFileSync(NATIVE, ['compile', ':path', FIXTURE, ':fn', 'demo.main/main', ':out', a],
                 { stdio: ['ignore', 'ignore', 'ignore'] });
    flint(['compile', ':path', FIXTURE, ':fn', 'demo.main/main', ':optimize', '[perf]', ':out', b]);
    check('the comparison can tell its arms apart',
          Buffer.compare(readFileSync(a), readFileSync(b)) !== 0);
  }
} finally {
  rmSync(tmp, { recursive: true, force: true });
}

console.log();
if (failures) {
  console.error(`${failures} failed`);
  process.exitCode = 1;
} else {
  console.log('all good');
}
