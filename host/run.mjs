// Run a compiled module, or a bytecode IMAGE (`doc/decisions/0021`, `0023`).
//
// The image path is the one construe settled on: a resident loader instantiated
// once, an image loaded per call, and nothing shared between runs because the
// image's top-level initialisers re-run per `main()`. It needs no linker
// anywhere, which is what makes it work in a Worker.
import { readFileSync } from 'fs';
import { load, instantiate } from './flint.mjs';
import { readSection } from './modmeta.mjs';

const MAGIC = 'FLINTIMG';

/// The `:features` map out of the metadata section, without a full EDN reader.
/// Deliberately narrow: the host needs three booleans, and pulling an EDN parser
/// into every runner to get them would be the wrong trade.
function parseFeatures(text) {
  const m = text.match(/:features \{([^}]*)\}/);
  if (!m) return null;
  const out = {};
  for (const [, k, v] of m[1].matchAll(/:([a-z-]+) (true|false)/g)) out[k] = v === 'true';
  return out;
}

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
  // Check the loader from its BYTES before instantiating it (0020). The old
  // check was `!e.flint_load_image` after instantiation, which is a fine test
  // and a late one: compiling and instantiating half a megabyte of wasm to
  // discover it is the wrong artifact is work nobody needed to do.
  const loaderBytes = readFileSync(loaderPath);
  const sec = readSection(loaderBytes);
  if (sec) {
    let meta = null;
    try { meta = parseFeatures(sec.text); } catch { /* older module, fall through */ }
    if (meta && meta.loader === false) {
      throw new Error(
        `${loaderPath} is a flint module but was not built with --loader: ` +
        'it cannot load an image. Build one with `flint build --image`, which builds both.');
    }
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
