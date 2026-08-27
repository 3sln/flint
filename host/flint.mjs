// Run a flint module. The module is self-contained: no imports, no host
// functions, nothing to wire up. This wrapper exists only to turn
// `main("a","b")` into the module's arg_alloc / arg_push / main / out_ptr ABI.
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

export async function run(path, args, caps) {
  const { module, size } = await load(path);
  const inst = instantiate(module, {
    stepLimit: process.env.FLINT_STEP_LIMIT ? Number(process.env.FLINT_STEP_LIMIT) : 0,
  });
  if (caps) inst.capabilities(caps);
  return { ...inst.main(...args), size };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [, , path, ...args] = process.argv;
  const r = await run(path, args);
  // Not process.exit: it does not flush an async pipe write, and output past
  // the pipe buffer would be lost.
  process.exitCode = r.code;
  process.stdout.write(r.out);
  if (r.out.length && !r.out.endsWith('\n')) process.stdout.write('\n');
}
