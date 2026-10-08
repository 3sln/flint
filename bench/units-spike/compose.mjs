// Compose pre-placed unit modules in a JavaScript host -- no linker, and no
// code spliced (`DECISIONS.md#composing-runtime-units`).
//
// The memory and the table are created HERE and imported by every unit. Units
// are instantiated in dependency order, each handed rt's exports as `env`
// imports by name. Builtins are bound to the table slots dist/slots.json
// already assigns, the image goes at the build-time image base, and the
// SDK's own guest driver (sdks/esm/src/guest.js) then runs it, unchanged.
//
//   node bench/units-spike/compose.mjs <module-from-the-shipped-door.wasm> <ns/fn> [unit,...]
import { readFileSync } from 'fs';
import { parse } from './wasmlib.mjs';
import { REPO, U, OUT, provides } from './common.mjs';
const layout = () => JSON.parse(readFileSync(`${OUT}/layout.json`, 'utf8'));
const slots = JSON.parse(readFileSync(`${REPO}/dist/slots.json`, 'utf8'));
const provider = {};   // builtin name -> [unit, symbol]
for (const [unit, edn] of [['rt', `${U}/flint/rt.unit.edn`], ['flint.conc', `${U}/flint/conc.unit.edn`],
  ['flint.data.json', `${U}/flint/data/json.unit.edn`]])
  for (const [b, s] of Object.entries(provides(edn))) provider[b] = [unit, s];

/// The image the shipped door spliced, read back out of a finished module.
/// The LAST segment at an address wins -- the prebuilt runtime's own loader
/// image is still in every spliced module, shadowed rather than removed.
export function imageOf(bytes) {
  const m = parse(bytes);
  const desc = m.globalValue('FLINT_IMAGE_DESC');
  const last = (off) => [...m.data].reverse().find((d) => d.offset === off);
  const seg = last(desc);
  const dv = new DataView(bytes.buffer, bytes.byteOffset + seg.start, 8);
  const [addr, len] = [dv.getUint32(0, true), dv.getUint32(4, true)];
  const img = last(addr);
  return bytes.slice(img.start, img.start + len);
}

/// The builtin names an image calls, from its native table.
export function nativesOf(image) {
  const bySlot = Object.fromEntries(Object.entries(slots).map(([n, s]) => [s, n]));
  const dv = new DataView(image.buffer, image.byteOffset);
  const n = dv.getUint32(12, true), out = [];
  for (let k = 0; k < n; k++) out.push(bySlot[dv.getUint32(16 + k * 8 + 4, true)]);
  return out;
}

const modCache = {};
export function compileUnits(names) {
  for (const n of names) if (!modCache[n]) modCache[n] = new WebAssembly.Module(readFileSync(`${OUT}/${n}.wasm`));
  return names.map((n) => modCache[n]);
}

/// Instantiate rt + `units` against one memory and one table, with `image`.
/// REFUSES an image calling a builtin no instantiated unit provides: left to
/// run, that is a `null function` trap at the call, far from the cause.
export function compose(image, units = ['flint.conc'], mods) {
  const L = layout();
  const missing = nativesOf(image).filter((b) => !provider[b] || (provider[b][0] !== 'rt' && !units.includes(provider[b][0])));
  if (missing.length) throw new Error(`no unit provides ${missing.join(', ')}`);
  const memory = new WebAssembly.Memory({ initial: Math.ceil((L.imageBase + image.length) / 65536) + 1 });
  const table = new WebAssembly.Table({ initial: L.tableSize, element: 'anyfunc' });
  const [rtMod, ...unitMods] = mods || compileUnits(['rt', ...units]);
  const inst = { rt: new WebAssembly.Instance(rtMod, { env: { memory, __indirect_function_table: table } }) };
  const rtFns = Object.fromEntries(Object.entries(inst.rt.exports).filter(([, v]) => typeof v === 'function'));
  units.forEach((u, k) => {
    const got = Object.fromEntries(Object.entries(L.gotMem).map(([s, a]) => [s, new WebAssembly.Global({ value: 'i32', mutable: true }, a)]));
    inst[u] = new WebAssembly.Instance(unitMods[k], { env: { memory, __indirect_function_table: table, ...rtFns }, 'GOT.mem': got });
  });
  let bound = 0;
  for (const [name, slot] of Object.entries(slots)) {
    const p = provider[name];
    if (p && inst[p[0]]) { table.set(slot, inst[p[0]].exports[p[1]]); bound++; }
  }
  new Uint8Array(memory.buffer).set(image, L.imageBase);
  const desc = inst.rt.exports.FLINT_IMAGE_DESC.value, dv = new DataView(memory.buffer);
  dv.setUint32(desc, L.imageBase, true); dv.setUint32(desc + 4, image.length, true);
  const exports = { memory };
  for (const i of Object.values(inst)) for (const [k, v] of Object.entries(i.exports)) if (!(k in exports)) exports[k] = v;
  return { exports, bound, memory, table };
}

/// Hand a composed export set to the SDK's guest driver UNCHANGED: guest.js
/// does `new WebAssembly.Instance(module, {})`, so answer that with the set.
const Orig = WebAssembly.Instance;
WebAssembly.Instance = function (mod, imp) { return mod && mod.__composed ? { exports: mod.__composed } : new Orig(mod, imp); };
export async function guest(exports, opts) {
  const { instantiate } = await import(`${REPO}/sdks/esm/src/guest.js`);
  return instantiate({ __composed: exports }, opts);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [, , wasmPath, fn, units = 'flint.conc'] = process.argv;
  const image = imageOf(readFileSync(wasmPath));
  const { exports, bound } = compose(image, units.split(',').filter(Boolean));
  const r = (await guest(exports)).run(fn, []);
  console.log(JSON.stringify({ image: image.length, bound, ...r }));
  process.exitCode = r.code;
}
