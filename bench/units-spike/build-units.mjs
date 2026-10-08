// Build flint's runtime units as SEPARATE, PRE-PLACED wasm modules
// (`DECISIONS.md#composing-runtime-units`).
//
// Each module imports one shared memory and one shared funcref table, and owns
// a disjoint memory range (its data and its OWN shadow stack) and a disjoint
// table range, both fixed HERE, at build time. Cross-unit FUNCTION references
// become imports by symbol name. Cross-unit DATA references cannot be
// imported from a non-PIC object -- `flint-conc` reads `flint_rt::snap::REFUSED`
// (units-src/flint-conc/src/lib.rs, `flint_live_import`), and any `#[inline]`
// or generic function of flint_rt that touches a static makes the same kind of
// reference without the unit's source naming one -- so the dependent units are
// compiled PIC (`run`) and receive each such address as a `GOT.mem` global.
//
// Writes out/units-spike/{rt,flint.conc,flint.data.json}.wasm and layout.json.
import { readFileSync, writeFileSync } from 'fs';
import { parse } from './wasmlib.mjs';
import { U, OUT, lld, sysroot, provides, unitExports, ls, ABI } from './common.mjs';
const align = (n, a) => Math.ceil(n / a) * a;
const strip = process.env.KEEP_NAMES ? '--strip-debug' : '--strip-all';
// Builtins keep the slots dist/slots.json assigns (43..267 today), so an image
// the SHIPPED door compiled runs unchanged. Unit-internal function pointers
// (closures, vtables) get ranges above that.
const TABLE0 = 300, RT_TABLE = 2048, UNIT_TABLE = 512;
// FIXED ORDER, and every unit is placed whether or not a program uses it: a
// unit's addresses then do not depend on which others are present.
const deps = [
  { name: 'flint.conc', obj: `${OUT}/conc-pic.o`, edn: `${U}/flint/conc.unit.edn`, libs: [] },
  { name: 'flint.data.json', obj: `${OUT}/json-pic.o`, edn: `${U}/flint/data/json.unit.edn`, libs: ls(`${U}/flint/data/json.libs/*.rlib`) },
];
function linkUnit(u, base, tbase, out) {
  const exps = [...Object.values(provides(u.edn)), ...unitExports(u.edn), '__heap_base'].map((s) => `--export=${s}`);
  lld(['--no-entry', '--import-memory', '--import-table', '--unresolved-symbols=import-dynamic', '--gc-sections',
    `--global-base=${base}`, `--table-base=${tbase}`, '-z', 'stack-size=65536', '--no-stack-first', strip,
    ...exps, '-o', out, u.obj, ...u.libs, ...sysroot()]);
  return parse(readFileSync(out));
}
// Pass 1: what the dependants import from rt. In a real build this is a
// build-time artefact, like dist/slots.json.
const want = new Set(), wantData = new Set();
for (const u of deps) {
  for (const im of linkUnit(u, 1 << 24, 4096, `${OUT}/probe.wasm`).imports) {
    if (im.mod === 'env' && im.kind === 0) want.add(im.fld);
    if (im.mod === 'GOT.mem') wantData.add(im.fld);
  }
}
// rt: the stack first, so its overflow traps at address 0, then its data.
const rtExp = [...new Set([...Object.values(provides(`${U}/flint/rt.unit.edn`)), ...ABI, ...want, ...wantData])];
lld(['--no-entry', '--import-memory', '--import-table', '--gc-sections', `--table-base=${TABLE0}`,
  '-z', 'stack-size=1048576', '--stack-first', strip, ...rtExp.map((s) => `--export=${s}`),
  '-o', `${OUT}/rt.wasm`, `${U}/flint/rt.o`, ...sysroot()]);
const rt = parse(readFileSync(`${OUT}/rt.wasm`));
const layout = { rt: { mem: [0, rt.globalValue('__heap_base')], table: [TABLE0, TABLE0 + RT_TABLE] } };
let memTop = align(rt.globalValue('__heap_base'), 65536), tabTop = TABLE0 + RT_TABLE;
for (const u of deps) {
  const m = linkUnit(u, memTop, tabTop, `${OUT}/${u.name}.wasm`);
  const hb = m.globalValue('__heap_base');
  layout[u.name] = { mem: [memTop, hb], table: [tabTop, tabTop + UNIT_TABLE] };
  memTop = align(hb, 65536); tabTop += UNIT_TABLE;
}
layout.imageBase = memTop; layout.tableSize = tabTop;
layout.rtExports = rtExp.length;
layout.gotMem = Object.fromEntries([...wantData].map((d) => [d, rt.globalValue(d)]));
writeFileSync(`${OUT}/layout.json`, JSON.stringify(layout, null, 1));
console.log(JSON.stringify(layout));
