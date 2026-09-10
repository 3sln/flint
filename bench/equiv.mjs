// EQUALITY AND HASH, on the unextended path.
//
// The baseline `doc/goals/equiv-hash.md` asks for before its first line of
// implementation. Read that file's §2 for what the numbers are FOR; read
// `bench/progs/equiv.cljc` for what each row does and what is not covered.
//
// Taken BEFORE the header bit exists. A baseline taken afterwards cannot
// answer the only question the design needs answered, which is whether the
// unextended path changed.
//
// WHY THE REPETITION COUNT IS CALIBRATED AND NOT PASSED IN. The first version
// took a fixed `r` for every mode, and the modes span four orders of
// magnitude -- `reject-hi` at 62ns against `noshare` at 415us. At a fixed
// r=2000 the cheap rows were a 2ms signal sitting on top of a 50ms map build,
// and subtracting one noisy 50ms number from another moved `share` between
// 477ns and 1119ns across two runs of an unchanged tree. A baseline that
// swings 2x cannot detect the thing it exists to detect.
//
// So each mode is calibrated: measure once, then choose `r` so the operation
// arm runs for TARGET_MS, and report the setup cost beside the result. When
// the operation is small against its own setup, the row says so rather than
// printing a confident number the next run will contradict.
import { load, instantiate } from '../host/flint.mjs';

const MODES = process.argv.slice(3);
const N = Number(process.argv[2] ?? 20000);
// Lowered by `bin/test`, which runs this as an ALLOCATION gate rather than
// for the timings: allocation counts are exact and machine-independent, so
// they need no signal to separate, and a gate should not spend a second per
// row establishing one.
const TARGET_MS = Number(process.env.EQUIV_TARGET_MS ?? 250);
const file = 'out/equiv-diag.wasm';
const { module } = await load(file);
const FN = 'equiv/main';

const run = (what, r, reps = 5) => {
  let best = Infinity, allocs = 0, out = null;
  for (let k = 0; k < reps; k++) {
    const inst = instantiate(module);
    inst.exports.set_step_limit(BigInt(1e12));
    const t0 = process.hrtime.bigint();
    const res = inst.run(FN, [what, String(N), String(r)]);
    const t1 = process.hrtime.bigint();
    if (res.code !== 0) throw new Error(`${what}: ${res.out}`);
    best = Math.min(best, Number(t1 - t0) / 1e6);
    allocs = Number(inst.exports.stat_allocs());
    out = res.out;
  }
  return { ms: best, allocs, out };
};

const num = (n) => n.toLocaleString('en-US');
console.log(`equality and hash on the unextended path, n=${num(N)}`);
console.log();
console.log(`  ${'mode'.padEnd(14)} ${'per op'.padStart(11)} ${'ops'.padStart(9)} ` +
            `${'signal'.padStart(8)} ${'base'.padStart(9)} ${'alloc/op'.padStart(9)}   result`);
for (const what of MODES) {
  // Calibrate by DOUBLING until the marginal signal is large enough to
  // extrapolate from. A single small probe is not enough: a 64-iteration
  // probe of a 1us operation is a 60us signal sitting on a 30ms map build,
  // and it estimated `share` at 8.3us against an actual 0.95us -- an 8x
  // error, which then chose an `r` that produced the very same weak-signal
  // row the calibration exists to avoid.
  let r = 256, per = null;
  for (let i = 0; i < 14; i++) {
    const d = run(what, r, 2).ms - run('base-' + what, r, 2).ms;
    if (d > 8) { per = d / r; break; }              // 8ms is well clear of jitter
    if (r >= 4_000_000) { per = Math.max(d, 0.001) / r; break; }
    r *= 4;
  }
  r = Math.max(256, Math.min(4_000_000, Math.ceil(TARGET_MS / per)));

  const a = run(what, r), b = run('base-' + what, r);
  const ms = a.ms - b.ms;
  const perOp = (ms * 1e6) / r;
  const al = (a.allocs - b.allocs) / r;
  // The guard the first version of this file needed: a signal that is small
  // against the arm it is subtracted from is not a fast operation, it is a
  // measurement that did not separate. Say which, in the row.
  //
  // `base` is setup AND the empty loop, not setup alone -- at four million
  // iterations the loop is most of it. That is the right thing to subtract
  // and the wrong thing to call `setup`, which is what it said at first.
  const ratio = ms / Math.max(b.ms, 1e-9);
  const flag = ratio < 0.25 ? `  <- signal ${(ratio * 100).toFixed(0)}% of base, weak` : '';
  console.log(`  ${what.padEnd(14)} ${(perOp.toFixed(1) + ' ns').padStart(11)} ${num(r).padStart(9)} ` +
              `${(ms.toFixed(0) + ' ms').padStart(8)} ${(b.ms.toFixed(0) + ' ms').padStart(9)} ` +
              `${al.toFixed(2).padStart(9)}   ${a.out}${flag}`);
}
