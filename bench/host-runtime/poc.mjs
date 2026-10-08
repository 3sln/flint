// Proof of concept for `DECISIONS.md#the-host-provides-the-runtime`, wasm half.
//
// A program artefact is the IMAGE only. The host compiles the runtime module
// (dist/flint-runtime.wasm) ONCE, instantiates it once per sandbox, writes the
// image into that instance's memory and calls `flint_load_image` -- which
// resolves every builtin the image names against the runtime's registry, BY
// NAME, and refuses with the missing name (status 2) before anything runs.
//
// The image is read back out of the module the SHIPPED door wrote
// (`flint compile ... :to :wasm`), so both arms run exactly the same bytecode,
// and the program-only artefact's output is compared with the shipped one's.
//
//   node bench/host-runtime/poc.mjs <dir-of-shipped-modules> name=ns/fn...
//
// For each NAME it expects <dir>/NAME.wasm (interpreted) and, if present,
// <dir>/NAME.perf.wasm (compiled with `:optimize [perf]`), whose appended
// arities it SIZES -- that is the code a program-only wasm module would carry.
import { readFileSync } from 'fs';
import { performance } from 'perf_hooks';
import { parse } from '../units-spike/wasmlib.mjs';
import { instantiate } from '../../sdks/esm/src/guest.js';

const REPO = new URL('../..', import.meta.url).pathname;
const [dir, ...specs] = process.argv.slice(2);

function imageOf(bytes) {
  const m = parse(bytes);
  const desc = m.globalValue('FLINT_IMAGE_DESC');
  const last = (off) => [...m.data].reverse().find((d) => d.offset === off);
  const dv = new DataView(bytes.buffer, bytes.byteOffset + last(desc).start, 8);
  const [addr, len] = [dv.getUint32(0, true), dv.getUint32(4, true)];
  const img = last(addr);
  return bytes.slice(img.start, img.start + len);
}

/// Bytes of the compiled arities the shipped door APPENDED: the bodies of the
/// last N functions, N being the last element segment's length. Read from the
/// code section directly, entry by entry.
function aotBytes(bytes) {
  const m = parse(bytes);
  const code = m.sections.find((s) => s.id === 10), elem = m.sections.find((s) => s.id === 9);
  let p;
  const u = () => { let r = 0, s = 0, b; do { b = bytes[p++]; r |= (b & 0x7f) << s; s += 7; } while (b & 0x80); return r >>> 0; };
  // element segments: count, then (flags, offset expr, n, idx...) -- flint's are flags 0.
  p = elem.start; let nseg = u(), lastN = 0;
  for (let i = 0; i < nseg; i++) {
    const flags = u(); if (flags !== 0) throw new Error(`elem flags ${flags}`);
    while (bytes[p++] !== 0x0b);           // offset expr
    const n = u(); for (let k = 0; k < n; k++) u(); lastN = n;
  }
  p = code.start; const nf = u(), sizes = [];
  for (let i = 0; i < nf; i++) { const sz = u(); sizes.push(sz); p += sz; }
  return { arities: lastN, bytes: sizes.slice(nf - lastN).reduce((a, b) => a + b, 0) };
}

function loadInto(inst, image) {
  const e = inst.exports;
  const ptr = e.flint_in_alloc(image.length);
  new Uint8Array(e.memory.buffer).set(image, ptr);
  const rc = e.flint_load_image(ptr, image.length);
  if (rc !== 0) {
    const why = new TextDecoder().decode(new Uint8Array(e.memory.buffer).subarray(e.out_ptr(), e.out_ptr() + e.out_len()));
    throw new Error(`load refused (${rc}): ${why}`);
  }
}

const median = (xs) => [...xs].sort((a, b) => a - b)[xs.length >> 1];
const N = Number(process.env.ROUNDS || 15);

// The runtime: compiled once per HOST, for every program and every sandbox.
const rtBytes = readFileSync(`${REPO}/dist/flint-runtime.wasm`);
let t = performance.now();
const rtModule = new WebAssembly.Module(rtBytes);
const rtCompileMs = performance.now() - t;
console.log(JSON.stringify({ runtime: 'dist/flint-runtime.wasm', bytes: rtBytes.length, compileMs: +rtCompileMs.toFixed(1) }));

const rows = [];
for (const spec of specs) {
  const [name, fn] = spec.split('=');
  const shipped = readFileSync(`${dir}/${name}.wasm`);
  const image = imageOf(shipped);
  // The shipped door: one module per program, compiled and instantiated per program.
  const a = instantiate(new WebAssembly.Module(shipped)).run(fn, []);
  // Program-only: the cached runtime module, a fresh instance, the image loaded.
  const inst = instantiate(rtModule); loadInto(inst, image);
  const b = inst.run(fn, []);
  if (a.code !== b.code || a.out !== b.out) throw new Error(`${name}: outputs differ\n${JSON.stringify(a)}\n${JSON.stringify(b)}`);
  // Timings, interleaved. A nonce custom section defeats V8's module cache for
  // the shipped arm, which a real host would hit once per PROGRAM.
  const ship = [], host = [], shipRun = [], hostRun = [];
  for (let r = 0; r < N; r++) {
    const nonce = new Uint8Array([0, 7, 5, 110, 111, 110, 99, 101, r & 0xff]);   // custom "nonce"
    const buf = new Uint8Array(shipped.length + nonce.length); buf.set(shipped); buf.set(nonce, shipped.length);
    t = performance.now(); instantiate(new WebAssembly.Module(buf)); ship.push(performance.now() - t);
    t = performance.now(); loadInto(instantiate(rtModule), image); host.push(performance.now() - t);
    // END TO END, the boundary a user waits on: instantiate, load, run `fn`.
    // V8 compiles lazily, so the shipped arm pays for compiling what the run
    // reaches here, and the cached arm reuses code an earlier sandbox compiled.
    const buf2 = new Uint8Array(buf); buf2[buf2.length - 1] ^= 0x80;
    t = performance.now(); instantiate(new WebAssembly.Module(buf2)).run(fn, []); shipRun.push(performance.now() - t);
    t = performance.now(); { const i = instantiate(rtModule); loadInto(i, image); i.run(fn, []); } hostRun.push(performance.now() - t);
  }
  let perf = null;
  try { perf = aotBytes(readFileSync(`${dir}/${name}.perf.wasm`)); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  rows.push({ program: name, out: b.out.slice(0, 24), shipped: shipped.length, image: image.length,
    'aot arities': perf?.arities ?? '-', 'aot code': perf?.bytes ?? '-',
    'shipped compile+inst ms': +median(ship).toFixed(2), 'cached rt inst+load ms': +median(host).toFixed(2),
    'shipped e2e ms': +median(shipRun).toFixed(1), 'cached rt e2e ms': +median(hostRun).toFixed(1) });
}
console.table(rows);
