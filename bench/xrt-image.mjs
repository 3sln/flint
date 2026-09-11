// The IMAGE-PER-CALL shape (`DECISIONS.md#cli`, `construe-integration-bar`), across engines.
//
// This is what flint's first real consumer runs: a resident loader instantiated
// once per isolate, a bytecode image loaded per call, and nothing shared
// between runs because the image's top-level initialisers re-run on every
// `main()`. It needs no linker anywhere, which is what makes it work inside a
// Worker.
//
// Under that deployment the numbers that matter are instantiation cost and PEAK
// MEMORY -- a Worker isolate has a 128 MiB ceiling and a CPU budget per request
// -- not steady-state throughput. So this reports per-call latency for an image
// that does no work (the load-and-initialise floor), per-call latency for one
// that does, and the heap after thousands of loads.
const argv = typeof Deno !== 'undefined' ? Deno.args : process.argv.slice(2);
const read = typeof Deno !== 'undefined'
  ? (p) => Deno.readFileSync(p)
  : (await import('node:fs')).readFileSync;

const [loaderPath, imagePath, itersArg, fnArg] = argv;
// The FUNCTION, named: a loaded image cannot be asked what it calls itself
// (`DECISIONS.md#structured-ports` step 5).
const FN = fnArg ?? 'construe.bench.xrt25/main';
const N = Number(itersArg || 2000);

const loader = read(loaderPath);
const image = read(imagePath);

const t0 = performance.now();
const mod = await WebAssembly.compile(loader);
const tCompile = performance.now() - t0;

const t1 = performance.now();
const inst = new WebAssembly.Instance(mod, {});
const tInstantiate = performance.now() - t1;
const e = inst.exports;

function once() {
  const p = e.arg_alloc(image.length);
  new Uint8Array(e.memory.buffer).set(image, p);
  if (e.flint_load_image(p, image.length) !== 0) throw new Error('image load failed');
  const code = wireCall(e, FN);
  return code;
}

// Warm, then measure. The first calls include tier-up, and a Worker request is
// short enough that it may never get there -- which is why the cold figure is
// reported separately rather than averaged in.
const tCold0 = performance.now();
once();
const tCold = performance.now() - tCold0;

for (let i = 0; i < Math.min(200, N); i++) once();
const pagesBefore = e.memory.buffer.byteLength / 65536;
const t2 = performance.now();
for (let i = 0; i < N; i++) once();
const perCallUs = ((performance.now() - t2) * 1000) / N;
const pagesAfter = e.memory.buffer.byteLength / 65536;

console.log(JSON.stringify({
  compileMs: +tCompile.toFixed(3),
  instantiateMs: +tInstantiate.toFixed(3),
  firstCallMs: +tCold.toFixed(3),
  perCallUs: +perCallUs.toFixed(1),
  pagesBefore, pagesAfter,
  heapMB: +(e.memory.buffer.byteLength / 1048576).toFixed(2),
  loaderKB: +(loader.length / 1024).toFixed(1),
  imageKB: +(image.length / 1024).toFixed(1),
  calls: N,
}));

// `[name []]` in the wire format, written by hand: these drivers deliberately
// use nothing but a `WebAssembly.Instance` (`DECISIONS.md#cross-runtime-benchmarks`), so there is
// no SDK here to encode for them. Nothing is called automatically any more
// (`DECISIONS.md#structured-ports` step 5), so the name has to travel.
function wireCall(e, fn) {
  const enc = (s) => {
    const u = new TextEncoder().encode(s);
    const b = [5];                                   // K_STRING
    for (let i = 0; i < 4; i++) b.push((u.length >> (8 * i)) & 0xff);
    return b.concat(Array.from(u));
  };
  const call = [8, 2, 0, 0, 0]                       // K_VECTOR, 2 items
    .concat(enc(fn))
    .concat([8, 0, 0, 0, 0]);                        // K_VECTOR, no arguments
  const b = Uint8Array.from(call);
  const p = e.arg_alloc(b.length);
  new Uint8Array(e.memory.buffer).set(b, p);
  return e.flint_call(p, b.length);
}
