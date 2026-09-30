// The in-process timer for `bin/bench-corpus`'s flint arms: the same policy as
// `bench/corpus_time.clj` -- warm until 300 ms AND three calls, then time
// `reps` calls, best and median -- on a compiled module in node.
//
//   node bench/corpus.mjs <reps> <ns>=<file.wasm> ...
//
// One line of JSON per module. `cold` is instantiate + first call, which is
// where flint's own startup (heap, image, top-level initialisers) lives; the
// host's WebAssembly.Module compile is reported apart as `compile`.
import { readFileSync } from 'fs';
import { instantiate } from '../host/flint.mjs';

const [, , repsArg, ...pairs] = process.argv;
const reps = Number(repsArg);
const now = () => Number(process.hrtime.bigint()) / 1e6;

for (const pair of pairs) {
  const [name, file] = pair.split('=');
  const fn = `${name}/main`;
  try {
    let t = now();
    const module = new WebAssembly.Module(readFileSync(file));
    const compile = now() - t;
    t = now();
    const inst = instantiate(module);
    let r = inst.run(fn, []);
    const cold = now() - t;
    if (r.code !== 0) throw new Error(r.out);
    const t0 = now();
    for (let k = 0; k < 3 || now() - t0 < 300; k++) r = inst.run(fn, []);
    const ts = [];
    for (let k = 0; k < reps; k++) {
      t = now();
      inst.run(fn, []);
      ts.push(now() - t);
    }
    ts.sort((a, b) => a - b);
    console.log(JSON.stringify({ name, file, compile, cold,
      best: ts[0], median: ts[Math.floor(ts.length / 2)], out: r.out }));
  } catch (err) {
    console.log(JSON.stringify({ name, file, error: String(err.message ?? err) }));
  }
}
