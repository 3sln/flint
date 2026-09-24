// Run a flint module. The module is self-contained: no imports, no host
// functions, nothing to wire up. This wrapper exists only to turn
// a call into a message on the module's system port (`calls-are-ports`).
import { readFileSync } from 'fs';
// The guest driver -- arguments, the pump, capabilities -- is portable and
// lives with the SDK, which needs the same thing. This file is the node half:
// reading a module off disk and being runnable as a script.
export { instantiate } from '../sdks/esm/src/guest.js';
import { instantiate } from '../sdks/esm/src/guest.js';

export async function load(path) {
  const bytes = readFileSync(path);
  const module = await WebAssembly.compile(bytes);
  return { module, size: bytes.length };
}

/// Run `fn` in the module at `path`, and hand back what it returned.
///
/// **The caller names the function.** Nothing is called automatically
/// (`DECISIONS.md#structured-ports` step 5): a module has no entry point, the runtime
/// invokes nothing, and a call is a message on the system port. There used to
/// be a `main` here that the runtime ran on its own, and the name it ran was
/// recorded in the module's metadata so a runner could find it -- both are gone.
export async function run(path, args, caps, fn) {
  const { module, size } = await load(path);
  const inst = instantiate(module, {
    stepLimit: process.env.FLINT_STEP_LIMIT ? Number(process.env.FLINT_STEP_LIMIT) : 0,
  });
  if (caps) inst.capabilities(caps);
  return { ...inst.run(fn, args), size };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  // `node host/flint.mjs <module> <ns/fn> [args...]`. The FUNCTION IS NAMED,
  // because a module has none that is special: nothing is called automatically
  // and there is no entry recorded for a runner to find
  // (`DECISIONS.md#structured-ports` step 5).
  const [, , path, fn, ...args] = process.argv;
  if (!fn) {
    process.stderr.write('usage: flint.mjs <module.wasm> <ns/fn> [args...]\n');
    process.exitCode = 2;
  }
  const r = await run(path, args, null, fn);
  // Not process.exit: it does not flush an async pipe write, and output past
  // the pipe buffer would be lost.
  process.exitCode = r.code;
  process.stdout.write(r.out);
  if (r.out.length && !r.out.endsWith('\n')) process.stdout.write('\n');
}
