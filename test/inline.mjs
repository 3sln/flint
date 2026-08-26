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
const run = (mode) => {
  const inst = instantiate(module);
  inst.exports.set_step_limit(0, 1e9);
  const r = inst.main(mode);
  if (r.code !== 0) throw new Error(`${mode} failed: ${r.out}`);
  return { out: r.out, steps: Number(inst.exports.stat_steps()) };
};

console.log(':inline -- the instruction count, which is deterministic');

const a = run('inlined'), b = run('plain');
ok('the two loops agree on the answer', a.out === b.out, `${a.out} vs ${b.out}`);
ok('the inlined loop is a real run, not an empty one', a.steps > 10000, String(a.steps));
ok('inlining removed instructions', a.steps < b.steps,
   `inlined ${a.steps}, plain ${b.steps} -- equal counts mean the inline never fired`);

const saved = b.steps - a.steps;
console.log(`    inlined ${a.steps.toLocaleString('en-US')}, ` +
            `plain ${b.steps.toLocaleString('en-US')}, ` +
            `${(100 * saved / b.steps).toFixed(1)}% fewer instructions ` +
            `(${(saved / 2000).toFixed(1)} per iteration over 2000 iterations, ` +
            `for 3 calls per iteration)`);

// The count is deterministic, so a second run must produce the same number. If
// it does not, the measurement above is not a measurement.
ok('the count repeats exactly', run('inlined').steps === a.steps, 'it did not');

process.exit(fails ? 1 : 0);
