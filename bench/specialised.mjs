// What type specialisation is worth, in time.
//
// The instruction COUNT cannot see this: a `NATIVE` and an `ADD_INT` are one
// instruction each, so `stat_steps` is identical either way. What changes is
// what one instruction costs -- a builtin reached through
// `__indirect_function_table`, which re-reads its arguments off the value
// stack, against an arm that already has them.
//
// Same program twice, same answer, same operation count. The only difference
// is whether the compiler could prove the operands were integers.
import { load, instantiate } from '../host/flint.mjs';

const { module } = await load(process.argv[2] ?? 'out/spec.wasm');
const N = Number(process.argv[3] ?? 300000);
const REPS = 7;

const time = (what, n) => {
  let best = Infinity, steps = 0, out = null;
  for (let i = 0; i < REPS; i++) {
    const inst = instantiate(module);
    inst.exports.set_step_limit(0, 1e12);
    const t0 = process.hrtime.bigint();
    const r = inst.main(what, String(n));
    const t1 = process.hrtime.bigint();
    if (r.code !== 0) throw new Error(`${what}: ${r.out}`);
    best = Math.min(best, Number(t1 - t0) / 1e6);
    steps = Number(inst.exports.stat_steps());
    out = r.out;
  }
  return { ms: best, steps, out };
};

// Differenced against a zero-iteration run, because the specialised side pays
// a fixed entry cost -- one `^int` check on the parameter -- that has nothing
// to do with the loop. Without this the two totals differ by 9 instructions
// and the "same instruction count" claim below is false for the wrong reason.
const perLoop = (what) => {
  const hot = time(what, N), cold = time(what, 0);
  return { ...hot, loopSteps: hot.steps - cold.steps, loopMs: hot.ms - cold.ms };
};
const g = perLoop('generic'), s = perLoop('specialised');
if (g.out !== s.out) {
  console.error(`the two loops disagree: ${g.out} vs ${s.out}`);
  process.exit(1);
}
// Same instruction count is the POINT, not a coincidence: it is what makes the
// time difference attributable to the cost of an instruction rather than to
// the program doing less work.
const sameSteps = g.loopSteps === s.loopSteps;

console.log('type specialisation, interpreted');
console.log();
console.log(`  ${N.toLocaleString('en-US')} iterations, 3 arithmetic ops each, ` +
            `best of ${REPS}`);
console.log(`  generic      ${g.loopMs.toFixed(1).padStart(7)} ms   ` +
            `${g.loopSteps.toLocaleString('en-US')} instructions in the loop`);
console.log(`  specialised  ${s.loopMs.toFixed(1).padStart(7)} ms   ` +
            `${s.loopSteps.toLocaleString('en-US')} instructions in the loop`);
console.log(`  ${(g.loopMs / s.loopMs).toFixed(2)}x faster, on ` +
            (sameSteps ? 'THE SAME instruction count'
                       : `DIFFERENT instruction counts -- the comparison is not clean`));
console.log(`  ${((g.loopMs - s.loopMs) * 1e6 / (N * 3)).toFixed(1)} ns saved per ` +
            `arithmetic operation`);
if (!sameSteps) process.exit(1);
