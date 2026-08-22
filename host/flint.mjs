// Run a flint module. The module is self-contained: no imports, no host
// functions, nothing to wire up. This wrapper exists only to turn
// `main("a","b")` into the module's arg_alloc / arg_push / main / out_ptr ABI.
import { readFileSync } from 'fs';

export async function load(path) {
  const bytes = readFileSync(path);
  const module = await WebAssembly.compile(bytes);
  return { module, size: bytes.length };
}

export function instantiate(module) {
  const instance = new WebAssembly.Instance(module, {});
  const e = instance.exports;
  const enc = new TextEncoder();
  const dec = new TextDecoder();

  function main(...args) {
    if (process.env.FLINT_STEP_LIMIT && e.set_step_limit) {
      const n = Number(process.env.FLINT_STEP_LIMIT);
      e.set_step_limit(Math.floor(n / 2 ** 32), n >>> 0);
    }
    for (const a of args) {
      const b = enc.encode(String(a));
      const p = e.arg_alloc(b.length);
      new Uint8Array(e.memory.buffer).set(b, p);
      e.arg_push(p, b.length);
    }
    const code = e.main();
    const out = new Uint8Array(e.memory.buffer, e.out_ptr(), e.out_len());
    return { code, out: dec.decode(out) };
  }
  return { main, exports: e };
}

export async function run(path, args) {
  const { module, size } = await load(path);
  const { main } = instantiate(module);
  return { ...main(...args), size };
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
