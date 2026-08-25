// Run a compiled module, or a bytecode IMAGE (`doc/decisions/0021`, `0023`).
//
// The image path is the one construe settled on: a resident loader instantiated
// once, an image loaded per call, and nothing shared between runs because the
// image's top-level initialisers re-run per `main()`. It needs no linker
// anywhere, which is what makes it work in a Worker.
import { readFileSync } from 'fs';
import { load, instantiate } from './flint.mjs';

const MAGIC = 'FLINTIMG';

export function isImage(bytes) {
  return bytes.length >= 8 && Buffer.from(bytes.subarray(0, 8)).toString('latin1') === MAGIC;
}

/// Run `file` with `args`. When it is an image, `loaderPath` must name a module
/// built with `--loader`; the same loader may be reused across many images.
export async function run(file, args, { loaderPath, capabilities } = {}) {
  const bytes = readFileSync(file);
  if (!isImage(bytes)) {
    const { module } = await load(file);
    const inst = instantiate(module);
    if (capabilities) inst.capabilities(capabilities);
    return inst.main(...args);
  }
  if (!loaderPath) {
    throw new Error(
      `${file} is a bytecode image, so it needs a loader module to run in. ` +
      'Build one with `flint ... --loader`, then pass it as the loader.');
  }
  const { module } = await load(loaderPath);
  const inst = instantiate(module);
  if (capabilities) inst.capabilities(capabilities);
  const e = inst.exports;
  if (!e.flint_load_image) {
    throw new Error(`${loaderPath} was not built with --loader: it has no flint_load_image`);
  }
  const p = e.arg_alloc(bytes.length);
  new Uint8Array(e.memory.buffer).set(bytes, p);
  const rc = e.flint_load_image(p, bytes.length);
  if (rc !== 0) {
    const why = new TextDecoder().decode(
      new Uint8Array(e.memory.buffer).subarray(e.out_ptr(), e.out_ptr() + e.out_len()));
    throw new Error(`could not load the image (${rc}): ${why}`);
  }
  return inst.main(...args);
}
