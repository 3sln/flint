// THE WASM ROW OF `bin/check-reader`: `dist/flint-reader.wasm`, the kin reader
// the JavaScript doors read source with, against the guest's bytes.
//
//     node test/wasm-reader.mjs <refs-dir>
//
// `refs-dir` is what `cargo test kin_reader` writes under `FLINT_READER_REF_DIR`:
// `manifest.tsv` (tag, mode, file name) and, per read, the source and either
// the guest's `.forms` bytes or its `.err` message. Every read goes through ONE
// `Reader` -- one module instance, one heap -- as it does in a JS host, so a read
// that leaves the heap or the root stack dirty shows up in a later read here.
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Reader, DEFAULT_FEATURES, PERF_FEATURES } from '../sdks/esm/src/resolve.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dir = process.argv[2];
const MODES = { deferred: null, default: DEFAULT_FEATURES, perf: PERF_FEATURES };
const reader = Reader.loadSync(readFileSync(join(root, 'dist/flint-reader.wasm')));
let compared = 0, alike = 0;
const differ = [];
for (const line of readFileSync(join(dir, 'manifest.tsv'), 'utf8').split('\n')) {
  if (!line) continue;
  const [tag, mode, name] = line.split('\t');
  const text = readFileSync(join(dir, `${tag}.src`), 'utf8');
  const got = reader.read(text, {
    file: name, features: MODES[mode], tags: [], dialect: name.endsWith('.fln') ? 'flint' : 'portable',
  });
  compared++;
  const forms = join(dir, `${tag}.forms`);
  if (existsSync(forms)) {
    const want = readFileSync(forms);
    if (got.error) differ.push(`${mode} ${name}: the guest read it, wasm said ${got.error.message}`);
    else if (Buffer.compare(Buffer.from(got.forms), want) !== 0) {
      differ.push(`${mode} ${name}: bytes differ (${want.length} against ${got.forms.length})`);
    }
  } else {
    const want = readFileSync(join(dir, `${tag}.err`), 'utf8');
    if (!got.error) differ.push(`${mode} ${name}: the guest failed (${want}), wasm read it`);
    else if (got.error.message !== want) differ.push(`${mode} ${name}: guest "${want}" wasm "${got.error.message}"`);
    else alike++;
  }
}
console.log(`wasm kin reader: ${compared} reads compared, ${alike} failed alike, ${differ.length} differ`);
for (const d of differ.slice(0, 40)) console.log(`  DIFF ${d}`);
process.exit(differ.length || compared === 0 ? 1 : 0);
