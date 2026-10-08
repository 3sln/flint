// Proof of concept for `DECISIONS.md#the-host-provides-the-runtime`, wasm half.
//
// The PROGRAM module drives; the runtime is only linked to it. The host
// compiles the runtime module (dist/flint-runtime.wasm) ONCE and, per sandbox:
//   1. instantiates the runtime (no imports);
//   2. instantiates a PROGRAM MODULE built here, which imports the runtime's
//      `memory`, an `image_base` i32 global and `flint_load_image`, carries the
//      image as an ACTIVE data segment at `(global.get image_base)` -- placed by
//      instantiation, no copy code anywhere -- and exports `boot`, which calls
//      `flint_load_image(image_base, len)` with its own image's address;
//   3. calls the program's `boot`, then runs through the runtime's exports.
// `flint_load_image` resolves every builtin the image names BY NAME and
// refuses with the missing name (status 2) before anything runs.
//
// ONE STAND-IN: today's runtime exports no reserved base global, so the host
// reserves the range with `flint_in_alloc` and hands the address over as a
// `WebAssembly.Global`. The designed runtime exports that global itself.
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

const REPO = process.env.FLINT_DIST_REPO || new URL('../..', import.meta.url).pathname;
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

const leb = (n) => { const o = []; do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; o.push(b); } while (n); return o; };
const str = (t) => [...leb(t.length), ...Buffer.from(t)];
const sec = (id, body) => [id, ...leb(body.length), ...body];
/// The program-only module: imports memory, image_base and flint_load_image
/// from "flint.rt.abi.1"; one active data segment at image_base; `boot`.
function programModule(image) {
  const M = str('flint.rt.abi.1');
  const types = sec(1, [2, 0x60, 2, 0x7f, 0x7f, 1, 0x7f, 0x60, 0, 1, 0x7f]);
  const imports = sec(2, [3,
    ...M, ...str('memory'), 2, 0, 0,
    ...M, ...str('image_base'), 3, 0x7f, 0,
    ...M, ...str('flint_load_image'), 0, 0]);
  const funcs = sec(3, [1, 1]);
  const exports = sec(7, [1, ...str('boot'), 0, 1]);
  const body = [0, 0x23, 0, 0x41, ...sleb(image.length), 0x10, 0, 0x0b];
  const code = sec(10, [1, ...leb(body.length), ...body]);
  const head = [0, 0x23, 0, 0x0b, ...leb(image.length)];
  const dataHdr = [11, ...leb(1 + head.length + image.length), 1, ...head];
  const pre = Uint8Array.from([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0, ...types, ...imports, ...funcs, ...exports, ...code, ...dataHdr]);
  const out = new Uint8Array(pre.length + image.length); out.set(pre); out.set(image, pre.length);
  return out;
}
function sleb(n) { const o = []; for (;;) { const b = n & 0x7f; n >>= 7; if ((n === 0 && !(b & 0x40)) || (n === -1 && (b & 0x40))) { o.push(b); return o; } o.push(b | 0x80); } }

function loadInto(inst, progModule, len) {
  const e = inst.exports;
  const base = e.flint_in_alloc(len);
  const prog = new WebAssembly.Instance(progModule, { 'flint.rt.abi.1': {
    memory: e.memory, image_base: new WebAssembly.Global({ value: 'i32' }, base),
    flint_load_image: e.flint_load_image } });
  const rc = prog.exports.boot();
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
  const progBytes = programModule(image), progModule = new WebAssembly.Module(progBytes);
  // The shipped door: one module per program, compiled and instantiated per program.
  const a = instantiate(new WebAssembly.Module(shipped)).run(fn, []);
  // Program-only: the cached runtime module, a fresh instance, the image loaded.
  const inst = instantiate(rtModule); loadInto(inst, progModule, image.length);
  const b = inst.run(fn, []);
  if (a.code !== b.code || a.out !== b.out) throw new Error(`${name}: outputs differ\n${JSON.stringify(a)}\n${JSON.stringify(b)}`);
  // Timings, interleaved. A nonce custom section defeats V8's module cache for
  // the shipped arm, which a real host would hit once per PROGRAM.
  const ship = [], host = [], shipRun = [], hostRun = [];
  for (let r = 0; r < N; r++) {
    const nonce = new Uint8Array([0, 7, 5, 110, 111, 110, 99, 101, r & 0xff]);   // custom "nonce"
    const buf = new Uint8Array(shipped.length + nonce.length); buf.set(shipped); buf.set(nonce, shipped.length);
    t = performance.now(); instantiate(new WebAssembly.Module(buf)); ship.push(performance.now() - t);
    t = performance.now(); loadInto(instantiate(rtModule), progModule, image.length); host.push(performance.now() - t);
    // END TO END, the boundary a user waits on: instantiate, load, run `fn`.
    // V8 compiles lazily, so the shipped arm pays for compiling what the run
    // reaches here, and the cached arm reuses code an earlier sandbox compiled.
    const buf2 = new Uint8Array(buf); buf2[buf2.length - 1] ^= 0x80;
    t = performance.now(); instantiate(new WebAssembly.Module(buf2)).run(fn, []); shipRun.push(performance.now() - t);
    t = performance.now(); { const i = instantiate(rtModule); loadInto(i, progModule, image.length); i.run(fn, []); } hostRun.push(performance.now() - t);
  }
  let perf = null;
  try { perf = aotBytes(readFileSync(`${dir}/${name}.perf.wasm`)); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  rows.push({ program: name, out: b.out.slice(0, 24), shipped: shipped.length, image: image.length, 'program module': progBytes.length,
    'aot arities': perf?.arities ?? '-', 'aot code': perf?.bytes ?? '-',
    'shipped compile+inst ms': +median(ship).toFixed(2), 'cached rt inst+load ms': +median(host).toFixed(2),
    'shipped e2e ms': +median(shipRun).toFixed(1), 'cached rt e2e ms': +median(hostRun).toFixed(1) });
}
console.table(rows);
