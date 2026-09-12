// The engine-independent driver: run one function in one module, print one
// line of JSON, exit.
//
// It prints JSON and nothing else ON PURPOSE. `inst.run` captures the
// program's own output rather than writing it, so there is no interleaving to
// get wrong and no dependence on how an engine spells `process.stdout.write`
// or whether its `print` appends a newline. The caller parses one line.
//
// The job arrives in a generated prelude (see `cli/src/sys.rs`), because argv
// is the other thing four engines disagree about: `globalThis.__FLINT_JOB`.
import { installShim, readBytes } from './portable.js';
installShim(globalThis);
const { instantiate } = await import('../sdks/esm/src/guest.js');

const job = globalThis.__FLINT_JOB;
let result;
try {
  const bytes = await readBytes(job.path);
  const module = await WebAssembly.compile(bytes);
  const inst = instantiate(module, { stepLimit: job.stepLimit || 0 });
  if (job.caps) inst.capabilities(job.caps);
  const r = inst.run(job.fn, job.args || []);
  result = { code: r.code, out: r.out };
} catch (e) {
  result = { code: 1, out: '', error: String((e && e.stack) || e) };
}
// `print` on jsc, `console.log` everywhere else -- both write a line to stdout.
(typeof print === 'function' ? print : console.log)(JSON.stringify(result));
