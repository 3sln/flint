// The construe fixtures, interpreted against compiled. Like for like.
//
// This is the benchmark that decides anything, because the microbenchmark is
// arithmetic in a loop and construe is a parser: mostly sequence traversal,
// keyword comparison and map lookup. The opcode census says half of all native
// calls in this code are `first`, `next`, `seq`, `rest` and `nil?` -- none of
// which type specialisation touches -- so this is where the honest number is.
//
// Both modules are built from the SAME source with the same compiler; the only
// difference is `--aot`. The instruction counts must agree, and that is
// asserted rather than assumed: if they differ, the two are not running the
// same program and the times are not comparable.
import { load, instantiate } from '../host/flint.mjs';
import { statSync } from 'fs';

const WORK = [
  ['parse', ['parse', '20'], 'interpret 4 real contexts, 20x'],
  ['suggest', ['suggest', '1'], 'rank 4000 candidates'],
  ['patterns', ['glob', '200'], 'glob patterns, 200x'],
];
const REPS = 5;

const open = async (f) => ({ file: f, ...(await load(f)) });
const interp = await open('out/cb.wasm');
const aot = await open('out/cb-aot.wasm');

const run = ({ module }, args) => {
  let best = Infinity, steps = 0, out = null;
  for (let i = 0; i < REPS; i++) {
    const inst = instantiate(module);
    inst.exports.set_step_limit(0, 1e12);
    const t0 = process.hrtime.bigint();
    const r = inst.main(...args);
    const t1 = process.hrtime.bigint();
    if (r.code !== 0) throw new Error(`${args.join(' ')}: ${r.out}`);
    best = Math.min(best, Number(t1 - t0) / 1e6);
    steps = Number(inst.exports.stat_steps());
    out = r.out;
  }
  return { ms: best, steps, out };
};

console.log('construe fixtures: interpreted against compiled');
console.log();
console.log(`  module  ${statSync('out/cb.wasm').size.toLocaleString('en-US')} bytes plain, ` +
            `${statSync('out/cb-aot.wasm').size.toLocaleString('en-US')} with compiled arities ` +
            `(+${Math.round(100 * (statSync('out/cb-aot.wasm').size / statSync('out/cb.wasm').size - 1))}%)`);
console.log();
console.log(`  ${'fixture'.padEnd(10)} ${'interpreted'.padStart(12)} ${'compiled'.padStart(10)} ` +
            `${'speedup'.padStart(8)}   instructions`);

let fails = 0;
for (const [name, args, desc] of WORK) {
  const a = run(interp, args), b = run(aot, args);
  if (a.out !== b.out) { fails++; console.log(`  ${name}: DIFFERENT ANSWERS`); continue; }
  if (a.steps !== b.steps) {
    fails++;
    console.log(`  ${name}: different instruction counts (${a.steps} vs ${b.steps}) ` +
                `-- not the same program, times not comparable`);
    continue;
  }
  console.log(`  ${name.padEnd(10)} ${(a.ms.toFixed(1) + ' ms').padStart(12)} ` +
              `${(b.ms.toFixed(1) + ' ms').padStart(10)} ${(a.ms / b.ms).toFixed(2) + 'x'} `.padStart(9) +
              `  ${a.steps.toLocaleString('en-US')}   (${desc})`);
}
// The coverage, without which the speedups above invite exactly the wrong
// conclusion. A reader seeing "1.34x" beside a commit about type
// specialisation will attribute it to type specialisation. It is not: almost
// none of this code reaches the specialised path, because construe carries no
// annotations and its arithmetic operands come from untyped parameters. What
// the 1.2-1.34x measures is dispatch removal, which is what 0013 already had.
try {
  const { module } = await load('out/cb-diag.wasm');
  const NB = 20, NCOUNT = 28, OPS_AT = NB * 4 + NCOUNT;
  const SPEC = [0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B];
  console.log();
  console.log('  how much of this reaches the specialised path at all:');
  for (const [name, args] of [['parse', ['parse', '3']], ['suggest', ['suggest', '1']]]) {
    const inst = instantiate(module);
    inst.exports.set_step_limit(0, 1e12);
    inst.main(...args);
    const total = Number(inst.exports.stat_region(NB * 4));
    let spec = 0;
    for (const k of SPEC) spec += Number(inst.exports.stat_region(OPS_AT + k));
    const nat = Number(inst.exports.stat_region(OPS_AT + 0x15));
    console.log(`    ${name.padEnd(9)} ${spec.toLocaleString('en-US').padStart(7)} of ` +
                `${total.toLocaleString('en-US')} instructions ` +
                `(${(100 * spec / total).toFixed(2)}%), with ` +
                `${nat.toLocaleString('en-US')} still going through a NATIVE call`);
  }
  console.log('    so the speedups above are dispatch removal, not specialisation.');
} catch (e) {
  console.log(`  (no diagnostics module: ${e.message})`);
}

process.exit(fails ? 1 : 0);
