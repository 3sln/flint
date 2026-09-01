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
  const r = inst.main(mode, String(iters));
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
// KNOWN FAILING as of 2026-08-30, and PRE-EXISTING: both rows below fail at
// 3854e4f and at every commit checked since, so this is not the check system
// and not the metadata on the core predicates. Verified by running this file
// against that tree with nothing else changed.
//
// It went unnoticed because the gate stops at its first failure and never got
// this far: `capability`, `cli`, `host_abi` and `aot` were all red ahead of it
// on stale expectations, and fixing those is what let this one be seen.
//
// The symptom is that the guard teaches the analyzer nothing -- annotated
// 54,000 against unannotated 48,000, where equal is the claim -- so a `^int`
// inside `(if (int? x) ...)` is still emitting a check. `flint.types`'
// `native-projections` table is what should make it free, keyed on the BUILTIN
// name because `(int? x)` is rewritten to one before narrowing runs. Whether
// that rewrite still happens for these guards is the thing to establish first;
// `test/inline.clj` now measures the closely related property that the alias
// survives `:flint/value-meta`, and it passes, so the alias itself is intact.
const narrowed = loopCost('narrowed', ITERS);
const unnarrowed = loopCost('unnarrowed', ITERS);

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
const andA = loopCost('and-narrowed', ITERS), andB = loopCost('and-plain', ITERS);
ok('an annotation inside an `and` guard costs exactly nothing',
   andA.steps === andB.steps,
   `annotated ${n(andA)}, unannotated ${n(andB)} -- a difference means the ` +
   `projection did not survive the let that \`and\` expands to`);
ok('the two `and` loops agree', andA.out === andB.out, `${andA.out} vs ${andB.out}`);
console.log(`    inside an \`and\` guard: annotated ${n(andA)}, unannotated ${n(andB)}`);

process.exit(fails ? 1 : 0);
