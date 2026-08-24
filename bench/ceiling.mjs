// What the emitter leaves on the table, and where it actually is.
//
// `out/ceiling.wasm` (built by `bench/ceiling.clj`) runs tight's loop three
// ways in the same engine, doing the same NaN-boxed arithmetic:
//
//   C1  operands in wasm LOCALS, a real `loop`, arithmetic inlined
//   C2  the same, with the arithmetic through `call_indirect`
//   C3  operands on a linear-memory stack driven by `loop` + `br_table`,
//       which is what `flint.aot` emits
//
// C2 -> C3 is the cost of OUR EMISSION SHAPE, and it is the number that decides
// whether register-allocating operands between safepoints is worth building.
import { readFileSync } from 'fs';
import { load, instantiate } from '../host/flint.mjs';

const N = 1_000_000n, Nn = 1_000_000;
const best = (k, f) => {
  let b = Infinity;
  for (let i = 0; i < k; i++) {
    const t0 = process.hrtime.bigint(); f(); const t1 = process.hrtime.bigint();
    b = Math.min(b, Number(t1 - t0) / 1e6);
  }
  return b;
};
const inst = new WebAssembly.Instance(new WebAssembly.Module(readFileSync('out/ceiling.wasm')));
const t = {};
for (const k of ['c1', 'c2', 'c3']) {
  if (inst.exports[k](10n) !== 45n) throw new Error(k + ' is wrong');
  t[k] = best(7, () => inst.exports[k](N));
}
for (const [k, f] of [['ti', '/tmp/tight-i.wasm'], ['ta', '/tmp/tight-a.wasm'],
                      ['ni', '/tmp/nat-i.wasm'], ['na', '/tmp/nat-a.wasm']]) {
  try {
    const { module } = await load(f);
    t[k] = best(5, () => instantiate(module).main());
  } catch { t[k] = null; }
}
const ns = (x) => x == null ? '     n/a' : (x * 1e6 / Nn).toFixed(1).padStart(8);
const row = (l, k) => console.log(`  ${l.padEnd(48)} ${ns(t[k])} ns/iteration`);

console.log('the ceiling for tight (1,000,000 iterations)');
console.log();
row('C1  locals + real loop, arithmetic inlined', 'c1');
row('C2  locals + real loop, indirect calls', 'c2');
row('C3  memory stack + br_table, indirect calls', 'c3');
console.log();
row('flint, interpreted  (3 closure calls/iteration)', 'ti');
row('flint, compiled     (3 closure calls/iteration)', 'ta');
row('flint, interpreted  (NO closure calls)', 'ni');
row('flint, compiled     (NO closure calls)', 'na');
console.log();
if (t.ta && t.na && t.ti && t.ni) {
  console.log(`  our emission shape (C2 -> C3) costs           ${(t.c3 / t.c2).toFixed(2)}x` +
              `  = ${((t.c3 - t.c2) * 1e6 / Nn).toFixed(1)} ns/iteration`);
  console.log(`  compiled speedup WITH closure calls           ${(t.ti / t.ta).toFixed(2)}x`);
  console.log(`  compiled speedup WITHOUT them                 ${(t.ni / t.na).toFixed(2)}x`);
  const ic = (t.ti - t.ni) * 1e6 / Nn / 3, ac = (t.ta - t.na) * 1e6 / Nn / 3;
  console.log(`  one Clojure call costs  ${ic.toFixed(1)} ns interpreted, ` +
              `${ac.toFixed(1)} ns compiled`);
  console.log(`  everything else is ${((t.ni - t.na) * 1e6 / Nn).toFixed(1)} ns/iteration cheaper compiled`);
}
