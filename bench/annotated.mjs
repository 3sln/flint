// Does annotating real code help? Measured, because the answer is no.
//
// The construe fixtures, twice: as written, and with `^int` on every place a
// person would plausibly put one -- the driver loops, the reduce accumulators,
// the lexicon index, and the hot `(< (:start node) (:covered state))` in
// `step`, bound and checked once rather than annotated at the use site.
//
// The mechanism this exposes is the point. An annotation on a value the
// compiler ALREADY knows is free -- that is what elision is for. An annotation
// on a value it does not know costs a `check-tag`, and a `check-tag` is itself
// a native call. So annotating pays only where one check feeds MANY
// specialisable operations, and in a parser it feeds one, or none.
import { load, instantiate } from '../host/flint.mjs';

const NB = 20, NCOUNT = 28, OPS_AT = NB * 4 + NCOUNT, NAT_AT = OPS_AT + 256;
const SPEC = [0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B];
const WORK = [['parse', ['parse', '20']], ['suggest', ['suggest', '1']]];

const nativeName = (e, i) => {
  let s = '';
  for (let k = 0; k < 64; k++) {
    const c = Number(e.stat_native_name(i, k));
    if (!c) break;
    s += String.fromCharCode(c);
  }
  return s;
};

const timeIt = async (file, args) => {
  const { module } = await load(file);
  let best = Infinity, steps = 0, out = null;
  for (let k = 0; k < 5; k++) {
    const inst = instantiate(module);
    inst.exports.set_step_limit(0, 1e12);
    const t0 = process.hrtime.bigint();
    const r = inst.main(...args);
    const t1 = process.hrtime.bigint();
    if (r.code !== 0) throw new Error(`${file} ${args.join(' ')}: ${r.out}`);
    best = Math.min(best, Number(t1 - t0) / 1e6);
    steps = Number(inst.exports.stat_steps());
    out = r.out;
  }
  return { ms: best, steps, out };
};

const countIt = async (file, args) => {
  const { module } = await load(file);
  const inst = instantiate(module);
  inst.exports.set_step_limit(0, 1e12);
  inst.main(...args);
  const e = inst.exports;
  let spec = 0, checks = 0;
  for (const k of SPEC) spec += Number(e.stat_region(OPS_AT + k));
  for (let k = 0; k < 512; k++) {
    const n = Number(e.stat_region(NAT_AT + k));
    if (n && nativeName(e, k) === 'flint/check-tag') checks += n;
  }
  return { spec, checks, total: Number(e.stat_region(NB * 4)) };
};

let fails = 0;
console.log('does annotating real code help?');
console.log();
console.log(`  ${'fixture'.padEnd(9)} ${'as written'.padStart(11)} ${'annotated'.padStart(11)} ` +
            `${'ratio'.padStart(7)}   instructions`);
for (const [name, args] of WORK) {
  const a = await timeIt('out/cb.wasm', args);
  const b = await timeIt('out/cba.wasm', args);
  if (a.out !== b.out) { fails++; console.log(`  ${name}: DIFFERENT ANSWERS`); continue; }
  console.log(`  ${name.padEnd(9)} ${(a.ms.toFixed(1) + ' ms').padStart(11)} ` +
              `${(b.ms.toFixed(1) + ' ms').padStart(11)} ${(a.ms / b.ms).toFixed(3) + 'x'}` +
              `   ${a.steps.toLocaleString('en-US')} -> ${b.steps.toLocaleString('en-US')}`);
}

console.log();
console.log('  and why -- what each annotation bought against what it cost:');
console.log(`    ${'fixture'.padEnd(9)} ${'specialised'.padStart(12)} ${'check-tag'.padStart(11)}`);
for (const [name, args] of [['parse', ['parse', '3']], ['suggest', ['suggest', '1']]]) {
  const a = await countIt('out/cb-diag.wasm', args);
  const b = await countIt('out/cba-diag.wasm', args);
  console.log(`    ${name.padEnd(9)} ${String(a.spec).padStart(5)} -> ${String(b.spec).padEnd(6)} ` +
              `${String(a.checks).padStart(5)} -> ${String(b.checks).padEnd(6)} ` +
              `(+${b.spec - a.spec} specialised, +${b.checks - a.checks} checks)`);
  if (b.checks - a.checks <= b.spec - a.spec) {
    fails++;
    console.log('      NOTE: this file claims annotating costs more than it buys, ' +
                'and here it did not. Rewrite the claim.');
  }
}
console.log();
console.log('  A check is a native call. Annotating pays where one check feeds MANY');
console.log('  specialisable operations; in a parser it feeds one, or none. The lever');
console.log('  for code like this is INFERENCE, which adds no check at all.');
process.exit(fails ? 1 : 0);
