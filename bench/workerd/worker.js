// The shape construe actually runs: a resident loader instantiated ONCE per
// isolate, an image loaded per request, nothing shared between runs because the
// image's top-level initialisers re-run on every `main()`.
import loaderWasm from './xrt-loader.wasm';
import imageBytes from './xrt.image';
import image0 from './xrt0.image';

let inst = null;
let instantiateUs = 0;

function ensure() {
  if (inst) return inst;
  const t0 = Date.now();
  inst = new WebAssembly.Instance(loaderWasm, {});
  instantiateUs = (Date.now() - t0) * 1000;
  return inst;
}

function runOnce(i, which) {
  const e = i.exports;
  const img = new Uint8Array(which === '0' ? image0 : imageBytes);
  const p = e.arg_alloc(img.length);
  new Uint8Array(e.memory.buffer).set(img, p);
  const rc = e.flint_load_image(p, img.length);
  if (rc !== 0) throw new Error('image load failed: ' + rc);
  const code = e.main();
  const out = new TextDecoder().decode(
    new Uint8Array(e.memory.buffer, e.out_ptr(), e.out_len()));
  return { code, out };
}

export default {
  async fetch(req) {
    const url = new URL(req.url);
    const n = Number(url.searchParams.get('n') || 1);
    const which = url.searchParams.get('img') || '25';
    const i = ensure();
    const t0 = performance.now();
    let last = null;
    for (let k = 0; k < n; k++) last = runOnce(i, which);
    const ms = performance.now() - t0;
    return new Response(JSON.stringify({
      n, img: which, ms, perCallUs: (ms * 1000) / n,
      instantiateUs,
      pages: i.exports.memory.buffer.byteLength / 65536,
      heapBytes: i.exports.memory.buffer.byteLength,
      answer: last.out, code: last.code,
    }), { headers: { 'content-type': 'application/json' } });
  },
};
