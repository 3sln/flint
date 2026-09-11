// Per-call latency DISTRIBUTION in the image-per-call shape (`DECISIONS.md#cross-runtime-benchmarks`).
//
// A Worker has a CPU budget per request, so what decides whether a deployment
// works is the tail, not the mean. And flint brings its own collector inside the
// wasm heap, so a pause is flint's rather than the engine's -- which means it
// should cost the same INSTRUCTIONS on every engine and differ only in
// wall-clock. That is checkable, and it is checked here.
const argv = typeof Deno !== 'undefined' ? Deno.args : process.argv.slice(2);
const read = typeof Deno !== 'undefined'
  ? (p) => Deno.readFileSync(p)
  : (await import('node:fs')).readFileSync;

const [loaderPath, imagePath, callsArg, fnArg] = argv;
// The FUNCTION, named: a loaded image cannot be asked what it calls itself
// (`DECISIONS.md#structured-ports` step 5).
const FN = fnArg ?? 'construe.bench.xrt25/main';
const CALLS = Number(callsArg || 2000);
const loader = read(loaderPath);
const image = read(imagePath);
const inst = new WebAssembly.Instance(await WebAssembly.compile(loader), {});
const e = inst.exports;

function once() {
  const p = e.arg_alloc(image.length);
  new Uint8Array(e.memory.buffer).set(image, p);
  if (e.flint_load_image(p, image.length) !== 0) throw new Error('image load failed');
  wireCall(e, FN);
}

for (let i = 0; i < 300; i++) once();          // warm
const us = new Float64Array(CALLS);
for (let i = 0; i < CALLS; i++) {
  const t = performance.now();
  once();
  us[i] = (performance.now() - t) * 1000;
}
const s = Array.from(us).sort((a, b) => a - b);
const q = (p) => s[Math.min(s.length - 1, Math.floor(s.length * p))];
// A timer that cannot resolve the thing being timed does not produce a small
// number, it produces a meaningless one: deno's `performance.now()` is clamped
// to whole milliseconds, so it reports p50 = 0 and p99 = 2000 for calls that
// take 80 microseconds. Refusing is the only honest answer.
if (q(0.5) === 0) {
  console.log(JSON.stringify({ unmeasurable: 'the timer cannot resolve a call: p50 rounds to 0' }));
} else {
console.log(JSON.stringify({
  calls: CALLS,
  p50: +q(0.5).toFixed(1), p90: +q(0.9).toFixed(1), p99: +q(0.99).toFixed(1),
  max: +s[s.length - 1].toFixed(1),
  tailRatio: +(q(0.99) / q(0.5)).toFixed(2),
  heapMB: +(e.memory.buffer.byteLength / 1048576).toFixed(2),
}));
}

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
