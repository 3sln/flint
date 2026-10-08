// Run time and instantiation time, the shipped single module against the same
// image composed from pre-placed units (`DECISIONS.md#composing-runtime-units`).
// Interleaved arms; median and min reported. CHECK `uptime` -- these are only
// as good as the machine was quiet.
//
//   node bench/units-spike/time.mjs <shaken.wasm> <ns/fn> [rounds]
import { readFileSync } from 'fs';
import { imageOf, compose, guest } from './compose.mjs';
import { REPO, OUT } from './common.mjs';
const [, , path, fn, R = '5'] = process.argv;
const bytes = readFileSync(path), image = imageOf(bytes);
const { instantiate } = await import(`${REPO}/sdks/esm/src/guest.js`);
const med = (xs) => [...xs].sort((a, b) => a - b)[xs.length >> 1];
const fmt = (xs) => `median ${med(xs).toFixed(1)} min ${Math.min(...xs).toFixed(1)}`;
// A nonce custom section per compile defeats V8's in-process module cache.
let nonce = 0;
const salt = (b) => { const body = Buffer.concat([Buffer.from([1, 0x78]), Buffer.from(`n${nonce++}`)]); return Buffer.concat([b, Buffer.from([0, body.length]), body]); };
const rtB = readFileSync(`${OUT}/rt.wasm`), concB = readFileSync(`${OUT}/flint.conc.wasm`);
const run = { single: [], composed: [] }, inst = { single: [], composed: [] }, outs = new Set();
for (let r = 0; r < Number(R); r++) for (const arm of ['single', 'composed']) {
  const t0 = performance.now();
  const g = arm === 'single'
    ? instantiate(new WebAssembly.Module(salt(bytes)))
    : await guest(compose(image, ['flint.conc'], [new WebAssembly.Module(salt(rtB)), new WebAssembly.Module(salt(concB))]).exports);
  const t1 = performance.now();
  const o = g.run(fn, []);
  run[arm].push(performance.now() - t1); inst[arm].push(t1 - t0); outs.add(o.code + ':' + o.out);
}
console.log(`${fn}: answers identical ${outs.size === 1}; ms compile+instantiate single ${fmt(inst.single)} | composed ${fmt(inst.composed)}; run single ${fmt(run.single)} | composed ${fmt(run.composed)}`);
