// AOT against the interpreter, on the same payloads, in the same process.
//
// `doc/decisions/0013` is a bet that removing dispatch is worth the module
// bytes and the cold start. Both halves are measured here, and a case that gets
// SLOWER is printed as such -- a benchmark section containing only wins is a
// marketing page, which is this project's own rule from `bench/construe.mjs`.
import { readFileSync, statSync } from 'fs';
import { load, instantiate } from '../host/flint.mjs';

const best = (n, f) => {
  let b = Infinity;
  for (let i = 0; i < n; i++) {
    const t0 = process.hrtime.bigint();
    f();
    const t1 = process.hrtime.bigint();
    b = Math.min(b, Number(t1 - t0) / 1e6);
  }
  return b;
};
const ms = (x) => (x < 1 ? x.toFixed(3) : x.toFixed(2));
const kb = (x) => (x / 1024).toFixed(0) + ' KB';
const pad = (s, n) => String(s).padEnd(n);

const WORK = [
  ['parse', ['parse', '20'], 5],
  ['suggest', ['suggest', '1'], 5],
];

const mods = {};
for (const [k, f] of [['interpreter', 'out/cb-int.wasm'], ['aot', 'out/cb-aot.wasm']]) {
  const { module } = await load(f);
  mods[k] = { module, bytes: statSync(f).size, file: f };
}

console.log('aot vs interpreter (doc/decisions/0013)');
console.log();
console.log(`  module: interpreter ${kb(mods.interpreter.bytes)}, ` +
            `aot ${kb(mods.aot.bytes)} ` +
            `(+${kb(mods.aot.bytes - mods.interpreter.bytes)}, ` +
            `${(100 * mods.aot.bytes / mods.interpreter.bytes - 100).toFixed(0)}% larger)`);

// Cold start is flint's largest measured win, so a regression in it is the one
// that would matter most. Measured as compile + instantiate + first answer.
for (const k of ['interpreter', 'aot']) {
  const bytes = readFileSync(mods[k].file);
  const cold = best(5, () => {
    const mod = new WebAssembly.Module(bytes);
    const inst = instantiate(mod);
    inst.main('parse', '1');
  });
  console.log(`  cold start (compile + instantiate + first answer), ${pad(k, 12)} ${ms(cold)} ms`);
}
console.log();

let regressions = 0;
for (const [name, args, reps] of WORK) {
  const row = {};
  for (const k of ['interpreter', 'aot']) {
    const inst = instantiate(mods[k].module);
    const r = inst.main(...args);
    if (r.code !== 0) throw new Error(`${k} ${name} failed: ${r.out}`);
    row[k] = { out: r.out.trim(), t: best(reps, () => instantiate(mods[k].module).main(...args)) };
  }
  if (row.interpreter.out !== row.aot.out) {
    console.log(`  ${name}: ANSWERS DIFFER`);
    console.log(`    interpreter ${row.interpreter.out}`);
    console.log(`    aot         ${row.aot.out}`);
    regressions++;
    continue;
  }
  const ratio = row.aot.t / row.interpreter.t;
  const verdict = ratio < 0.98 ? `${(1 / ratio).toFixed(2)}x FASTER`
    : ratio > 1.02 ? `${ratio.toFixed(2)}x SLOWER` : 'no change';
  if (ratio > 1.02) regressions++;
  console.log(`  ${pad(name, 10)} interpreter ${pad(ms(row.interpreter.t) + ' ms', 12)} ` +
              `aot ${pad(ms(row.aot.t) + ' ms', 12)} ${verdict}`);
}
console.log();
if (regressions) console.log(`  ${regressions} case(s) worse or wrong — see above`);
