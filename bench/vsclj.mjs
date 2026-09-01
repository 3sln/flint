// One flint module, called `main` with NO arguments -- each program's own
// default size -- best of 9 WARM calls on one instance.
//
// The same method the JVM and CLR harnesses use, so the numbers are comparable
// across runtimes and against canonical Clojure. Cold start is excluded on
// purpose: it is where flint wins by three orders of magnitude, and leaving it
// out is what makes this the honest comparison rather than the flattering one.
import { readFileSync } from 'node:fs';
import { instantiate } from './../host/flint.mjs';

const path = process.argv[2];
const module = new WebAssembly.Module(readFileSync(path));
const inst = instantiate(module);
// Built `:fn <prog>/main` into `out/vsclj/<prog>{,-aot}.wasm`; a call names it
// (`doc/decisions/0025` step 5).
const FN = `${/vsclj\/([a-z0-9-]+?)(?:-aot)?\.wasm$/.exec(path)[1]}/main`;
const main = () => inst.run(FN, []);

const now = () => Number(process.hrtime.bigint()) / 1e6;
for (let i = 0; i < 3; i++) main();          // warm V8's tiers before timing
let best = Infinity, out = null;
for (let i = 0; i < 9; i++) {
  const t = now();
  const r = main();
  best = Math.min(best, now() - t);
  out = r.out;
}
console.log(`${best.toFixed(2)} ${out}`);
