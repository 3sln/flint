// The size table (`DECISIONS.md#composing-runtime-units`), per program:
//
//   shaken    the shipped door: dist/flint-runtime.wasm spliced + flint.wasmshake
//   linked    bin/flint: rust-lld --gc-sections over the reached units, per program
//   composed  rt + flint.conc (+ flint.data.json when called) as pre-placed
//             modules at TODAY's unit granularity, plus the image
//   ideal     the same, but rt linked against EXACTLY the program's builtins --
//             what pre-placement approaches as flint.rt is split finer, and
//             cannot pass, since the floor and the duplication stay
//
// Plus the floor (rt with no builtin at all) and the cost of shipping a
// runtime that keeps its relocations (--emit-relocs), for the relinking option.
import { readFileSync, statSync } from 'fs';
import { parse } from './wasmlib.mjs';
import { imageOf, nativesOf } from './compose.mjs';
import { U, OUT, REPO, lld, sysroot, provides, unitExports, ls, ABI } from './common.mjs';
const sz = (f) => statSync(f).size;
const rtSyms = provides(`${U}/flint/rt.unit.edn`);
const unitWants = ['flint.conc', 'flint.data.json'].flatMap((u) => parse(readFileSync(`${OUT}/${u}.wasm`)).imports
  .filter((i) => (i.mod === 'env' && i.kind === 0) || i.mod === 'GOT.mem').map((i) => i.fld));
function rtWith(builtinSyms) {
  const out = `${OUT}/rt-with.wasm`;
  lld(['--no-entry', '--import-memory', '--import-table', '--gc-sections', '--table-base=300', '-z', 'stack-size=1048576',
    '--stack-first', '--strip-all', ...[...new Set([...ABI, ...unitWants, ...builtinSyms])].map((s) => `--export=${s}`),
    '-o', out, `${U}/flint/rt.o`, ...sysroot()]);
  return sz(out);
}
const floor = rtWith([]);
const programs = JSON.parse(process.env.PROGRAMS || '[]');
const rows = [];
for (const { name, units = [] } of programs) {
  const shaken = readFileSync(`${OUT}/progs/${name}.shaken.wasm`), image = imageOf(shaken), m = parse(shaken);
  const seen = new Set(); let shadowed = 0;
  for (const d of [...m.data].reverse()) { if (seen.has(d.offset)) shadowed += d.len; seen.add(d.offset); }
  const natives = nativesOf(image);
  const unitBytes = ['flint.conc', ...units].reduce((a, u) => a + sz(`${OUT}/${u}.wasm`), 0);
  rows.push({ program: name, builtins: natives.length, image: image.length, shaken: shaken.length,
    'shadowed segs': shadowed, linked: sz(`${OUT}/progs/${name}.linked.wasm`),
    composed: sz(`${OUT}/rt.wasm`) + unitBytes + image.length,
    ideal: rtWith(natives.filter((n) => rtSyms[n]).map((n) => rtSyms[n])) + unitBytes + image.length });
}
console.table(rows);
// The prebuilt runtime, linked as bin/build-dist links it (every builtin
// exported), with and without its relocations kept.
const allSyms = [`${U}/flint/rt.unit.edn`, `${U}/flint/conc.unit.edn`, ...ls(`${U}/flint/data/*.unit.edn`)].flatMap((e) => Object.values(provides(e)));
const common = ['--no-entry', '--gc-sections', '--export-table', '-z', 'stack-size=1048576', '--stack-first',
  ...[...ABI, ...allSyms, ...unitExports(`${U}/flint/conc.unit.edn`)].map((s) => `--export=${s}`)];
const objs = [`${U}/flint/rt.o`, `${U}/flint/conc.o`, ...ls(`${U}/flint/data/*.o`), ...new Set(ls(`${U}/flint/data/*.libs/*.rlib`)), ...sysroot()];
lld([...common, '--strip-all', '-o', `${OUT}/full.wasm`, ...objs]);
lld([...common, '--emit-relocs', '--strip-debug', '-o', `${OUT}/full-relocs.wasm`, ...objs]);
const sec = (f) => Object.fromEntries(parse(readFileSync(f)).sections.map((s) => [s.name || s.id, s.end - s.start]));
const r = sec(`${OUT}/full-relocs.wasm`);
console.log(JSON.stringify({ 'dist/flint-runtime.wasm': sz(`${REPO}/dist/flint-runtime.wasm`), 'rt floor (no builtins)': floor,
  'rt.wasm (all rt builtins)': sz(`${OUT}/rt.wasm`), 'flint.conc.wasm': sz(`${OUT}/flint.conc.wasm`), 'flint.data.json.wasm': sz(`${OUT}/flint.data.json.wasm`),
  'full, stripped': sz(`${OUT}/full.wasm`), 'full, --emit-relocs': sz(`${OUT}/full-relocs.wasm`),
  linking: r.linking, 'reloc.CODE': r['reloc.CODE'], 'reloc.DATA': r['reloc.DATA'], name: r.name }, null, 1));
