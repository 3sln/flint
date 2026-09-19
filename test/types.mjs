// What the annotations cost, on the deterministic instruction count (0009).
//
// The design claim is that the check goes at the WRITE so reads are free. That
// is falsifiable: a loop whose every value is already known must compile to the
// same instructions annotated as unannotated. If it does not, the elision is
// not working and every annotation is a tax.
import { load, instantiate } from '../host/flint.mjs';

let fails = 0;
const ok = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? '\n        ' + detail : '')); }
};

const { module } = await load(process.argv[2] ?? 'out/types.wasm');
const run = (mode, iters) => {
  const inst = instantiate(module);
  inst.exports.set_step_limit(BigInt(1e9));
  const r = inst.run('types/main', [mode, String(iters)]);
  if (r.code !== 0) throw new Error(`${mode} failed: ${r.out}`);
  return { out: r.out, steps: Number(inst.exports.stat_steps()) };
};

console.log('type annotations -- what they cost');

// Per-ITERATION cost, obtained by differencing against a zero-iteration run of
// the same mode. The first version of this compared modes directly and reported
// a 4-instruction penalty for annotating -- which was the cost of the extra
// equality test in this file's own `cond`, since `proven` is the second clause
// and `bare` the first. Differencing cancels dispatch, entry, and printing, and
// leaves only the loop.
const loopCost = (mode, iters) => {
  const hot = run(mode, iters), cold = run(mode, 0);
  return { steps: hot.steps - cold.steps, out: hot.out };
};

// THE COST OF `n` MORE ITERATIONS, which is what "per iteration" actually
// means. Differencing against a ZERO-iteration run cancels entry, dispatch and
// printing, but it does not cancel a one-time event that only happens once the
// loop is long enough -- a collection, most likely: up to 80 iterations these
// loops cost exactly 20 instructions each and agree to the instruction, and
// somewhere before 100 they both pay a one-off ~47 and stop agreeing by 2.
//
// Differencing two HOT runs cancels that too, because whatever happens once
// happens in both. It is the stricter measurement and not the looser one: the
// rows below still demand EXACT equality, they just demand it of a quantity
// that is actually per-iteration.
const slopeCost = (mode, n) => {
  const twice = run(mode, 2 * n), once = run(mode, n);
  return { steps: twice.steps - once.steps, out: twice.out };
};
const ITERS = 2000;
const bare = loopCost('bare', ITERS), proven = loopCost('proven', ITERS),
      opaque = loopCost('opaque', ITERS);
const n = (x) => x.steps.toLocaleString('en-US');

ok('the annotated loop computes the same thing', bare.out === proven.out,
   `${bare.out} vs ${proven.out}`);
ok('it is a real run', bare.steps > 10000, String(bare.steps));
ok('a PROVEN annotation costs exactly nothing', bare.steps === proven.steps,
   `bare ${n(bare)}, annotated ${n(proven)} -- a difference means elision failed`);
ok('an UNPROVEN annotation does cost something', opaque.steps > bare.steps,
   `opaque ${n(opaque)} vs bare ${n(bare)} -- if equal, no check was emitted ` +
   `and the annotation is not sound`);

console.log(`    over ${ITERS} iterations, loop cost only: ` +
            `bare ${n(bare)}   annotated-and-proven ${n(proven)}   ` +
            `unproven ${n(opaque)}`);
console.log(`    the unproven loop pays ` +
            `${((opaque.steps - bare.steps) / ITERS).toFixed(1)} instructions ` +
            `per iteration for its one check; the proven one pays ` +
            `${((proven.steps - bare.steps) / ITERS).toFixed(1)}`);

// --- occurrence narrowing ---------------------------------------------------
//
// The same program twice: once guarded by a real `int?`, once by a test the
// analyzer cannot read anything from. The annotation inside the guard is free
// in the first and a real check in the second, and the difference is what
// narrowing is worth on code that was never annotated at all.
//
// WAS failing, and the reason is worth keeping because the comment here
// outlived the bug it described. It read "annotated 54,000 against unannotated
// 48,000 -- so a `^int` inside `(if (int? x) ...)` is still emitting a check",
// and that WAS true when it was written. It is not true now: a live check costs
// 7.1 instructions an iteration, 14,189 over this loop, and what these rows
// actually measured by 2026-09-18 was a difference of TWO -- 40,481 against
// 40,483 -- which is not a check and never was.
//
// What was left was the measurement. `loopCost` cancels one-time costs by
// differencing against a zero-iteration run, but a collection that only happens
// in the long run is not in the short one, so it survives the subtraction. The
// slope does cancel it, and on the slope the two loops agree EXACTLY: 20,240
// against 20,240 at n=1000, 40,478 against 40,478 at n=2000, 60,674 against
// 60,674 at n=3000. Narrowing is free, to the instruction.
//
const narrowed = slopeCost('narrowed', ITERS);
const unnarrowed = slopeCost('unnarrowed', ITERS);

ok('an annotation inside an int? guard costs exactly nothing',
   narrowed.steps === unnarrowed.steps,
   `annotated ${n(narrowed)}, unannotated ${n(unnarrowed)} -- a difference ` +
   `means the guard taught the analyzer nothing and the check is still there`);
ok('the two narrowing loops agree', narrowed.out === unnarrowed.out,
   `${narrowed.out} vs ${unnarrowed.out}`);

console.log(`    inside an int? guard: annotated ${n(narrowed)}, ` +
            `unannotated ${n(unnarrowed)} ` +
            `(${((narrowed.steps - unnarrowed.steps) / ITERS).toFixed(1)} ` +
            `per iteration); the same annotation with nothing known costs ` +
            `${((opaque.steps - bare.steps) / ITERS).toFixed(1)}`);

// The same claim for `and`, which reaches the branch through the let it expands
// to. Without that propagation every answer in the suite is still right and
// this is the only thing that notices.
const andA = slopeCost('and-narrowed', ITERS), andB = slopeCost('and-plain', ITERS);
ok('an annotation inside an `and` guard costs exactly nothing',
   andA.steps === andB.steps,
   `annotated ${n(andA)}, unannotated ${n(andB)} -- a difference means the ` +
   `projection did not survive the let that \`and\` expands to`);
ok('the two `and` loops agree', andA.out === andB.out, `${andA.out} vs ${andB.out}`);
console.log(`    inside an \`and\` guard: annotated ${n(andA)}, unannotated ${n(andB)}`);

process.exit(fails ? 1 : 0);
