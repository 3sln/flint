// `:inline` measured, not assumed.
//
// The correctness half is in `test/inline.clj`. This is the half that says the
// feature does anything: two loops computing the same answer, one through vars
// with `:inline` and one through identical vars without, compared on the
// DETERMINISTIC instruction count (0009). Equal counts mean the inline did not
// fire, and that is a failure -- an inline that silently does nothing gives the
// right answer every time.
import { load, instantiate } from '../host/flint.mjs';

let fails = 0;
const ok = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? '\n        ' + detail : '')); }
};

const { module } = await load(process.argv[2] ?? 'out/inline.wasm');
const run = (mode, iters) => {
  const inst = instantiate(module);
  inst.exports.set_step_limit(0, 1e9);
  const r = inst.main(mode, String(iters));
  if (r.code !== 0) throw new Error(`${mode} failed: ${r.out}`);
  return { out: r.out, steps: Number(inst.exports.stat_steps()) };
};

console.log(':inline -- the instruction count, which is deterministic');

// Per-iteration cost: the same mode at N and at 0, differenced. Comparing two
// modes directly also measures this file's own dispatch, which is a handful of
// instructions and would be reported as an effect of inlining.
const ITERS = 2000;
const loopCost = (mode) => {
  const hot = run(mode, ITERS), cold = run(mode, 0);
  return { steps: hot.steps - cold.steps, out: hot.out };
};
const a = loopCost('inlined'), b = loopCost('plain');
ok('the two loops agree on the answer', a.out === b.out, `${a.out} vs ${b.out}`);
ok('the inlined loop is a real run, not an empty one', a.steps > 10000, String(a.steps));
ok('inlining removed instructions', a.steps < b.steps,
   `inlined ${a.steps}, plain ${b.steps} -- equal counts mean the inline never fired`);

const saved = b.steps - a.steps;
console.log(`    inlined ${a.steps.toLocaleString('en-US')}, ` +
            `plain ${b.steps.toLocaleString('en-US')}, ` +
            `${(100 * saved / b.steps).toFixed(1)}% fewer instructions ` +
            `(${(saved / ITERS).toFixed(1)} per iteration over ${ITERS} iterations, ` +
            `for 2 inlined calls per iteration)`);

// The count is deterministic, so a second run must produce the same number. If
// it does not, the measurement above is not a measurement.
ok('the count repeats exactly', loopCost('inlined').steps === a.steps, 'it did not');

process.exit(fails ? 1 : 0);
