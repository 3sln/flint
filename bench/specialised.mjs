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
import { fnOf } from './entry.mjs';

const wasmPath = process.argv[2] ?? 'out/spec.wasm';
const { module } = await load(wasmPath);
const FN = fnOf(wasmPath);
const N = Number(process.argv[3] ?? 300000);
const REPS = 7;

const time = (what, n) => {
  let best = Infinity, steps = 0, out = null;
  for (let i = 0; i < REPS; i++) {
    const inst = instantiate(module);
    inst.exports.set_step_limit(BigInt(1e12));
    const t0 = process.hrtime.bigint();
    const r = inst.run(FN, [what, String(n)]);
    const t1 = process.hrtime.bigint();
    if (r.code !== 0) throw new Error(`${what}: ${r.out}`);
    best = Math.min(best, Number(t1 - t0) / 1e6);
    steps = Number(inst.exports.stat_steps());
    out = r.out;
  }
  return { ms: best, steps, out };
};

// THE COST OF `N` MORE ITERATIONS: two hot runs differenced, not a hot run
// against a cold one.
//
// Differencing against a zero-iteration run was the first fix here, and the
// comment it replaced is worth keeping: the specialised side pays a fixed entry
// cost -- one `^int` check on the parameter -- so without any differencing the
// totals differ by 9 instructions and the claim below is false for the wrong
// reason. But a cold run does not pay everything a hot run pays ONCE either.
// Something in a long run happens that does not happen in a short one -- a
// collection, most likely -- and it survives the subtraction, leaving these two
// loops differing by exactly 2 out of 5,159,815 at every size measured.
//
// Two hot runs cancel it, because whatever happens once happens in both, and
// then the counts are equal TO THE INSTRUCTION: 5,159,762 against 5,159,762 at
// N = 300 000 and 2,579,904 against 2,579,904 at N = 150 000. This is the
// stricter measurement, not the looser one -- it still demands exact equality,
// of a quantity that is actually per-iteration.
const perLoop = (what) => {
  const twice = time(what, 2 * N), once = time(what, N);
  return { ...twice, loopSteps: twice.steps - once.steps, loopMs: twice.ms - once.ms };
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
