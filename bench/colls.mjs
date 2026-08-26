// The bulk collection operations, on time AND memory.
//
// Time alone is not the whole cost. A `reduce` that walks a vector through
// `seq`/`first`/`next` allocates a seq step per element; in a benchmark that
// collects once, that is invisible in wall-clock and entirely real in a
// 128 MiB isolate. So every row reports allocations and collections beside the
// milliseconds, and the diagnostics build is what makes that possible.
import { load, instantiate } from '../host/flint.mjs';

const MODES = process.argv.slice(3);
const N = Number(process.argv[2] ?? 20000);
const file = 'out/colls-diag.wasm';
const { module } = await load(file);

const run = (what) => {
  let best = Infinity, allocs = 0, bytes = 0, colls = 0, out = null;
  for (let k = 0; k < 7; k++) {
    const inst = instantiate(module);
    inst.exports.set_step_limit(0, 1e12);
    const t0 = process.hrtime.bigint();
    const r = inst.main(what, String(N));
    const t1 = process.hrtime.bigint();
    if (r.code !== 0) throw new Error(`${what}: ${r.out}`);
    best = Math.min(best, Number(t1 - t0) / 1e6);
    allocs = Number(inst.exports.stat_allocs());
    bytes = Number(inst.exports.stat_bytes_allocated());
    colls = Number(inst.exports.stat_collections());
    out = r.out;
  }
  return { ms: best, allocs, bytes, colls, out };
};

const num = (n) => n.toLocaleString('en-US');
console.log(`bulk collection operations, n=${num(N)}`);
console.log();
console.log(`  ${'operation'.padEnd(13)} ${'time'.padStart(9)} ${'allocations'.padStart(13)} ` +
            `${'bytes'.padStart(10)} ${'collections'.padStart(11)} ${'per elem'.padStart(10)}   result`);
// Every mode is differenced against a BASE mode that does the same setup and
// skips the operation, so what is reported is the operation and not the
// fixtures it needed.
for (const what of MODES) {
  const r = run(what), b = run('base-' + what);
  console.log(`  ${what.padEnd(13)} ${((r.ms - b.ms).toFixed(1) + ' ms').padStart(9)} ` +
              `${num(r.allocs - b.allocs).padStart(13)} ` +
              `${(((r.bytes - b.bytes) / 1048576).toFixed(1) + ' MB').padStart(10)} ` +
              `${String(r.colls - b.colls).padStart(11)}   ` +
              `${(((r.allocs - b.allocs) / N).toFixed(1) + '/elem').padStart(10)}   ${r.out}`);
}
