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
  const code = wireCall(e, FN);
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

// `[name []]` in the wire format, written by hand: these drivers use nothing but
// a `WebAssembly.Instance` on purpose (`DECISIONS.md#cross-runtime-benchmarks`), so there is no SDK
// to encode for them, and nothing is called automatically any more
// (`DECISIONS.md#structured-ports` step 5).
function wireCall(e, fn) {
  var enc = function (s) {
    var u = new TextEncoder().encode(s);
    var b = [5];                                     // K_STRING
    for (var i = 0; i < 4; i++) b.push((u.length >> (8 * i)) & 0xff);
    return b.concat(Array.prototype.slice.call(u));
  };
  var call = [8, 2, 0, 0, 0]                         // K_VECTOR, 2 items
    .concat(enc(fn))
    .concat([8, 0, 0, 0, 0]);                        // K_VECTOR, no arguments
  var b = Uint8Array.from(call);
  var p = e.arg_alloc(b.length);
  new Uint8Array(e.memory.buffer).set(b, p);
  return e.flint_call(p, b.length);
}

const FN = 'construe.bench.xrt25/main';
