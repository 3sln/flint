// A SPEC, READ BY THE HOST (`DECISIONS.md#one-reader-and-no-other`).
//
// The compiler reads no text: not source, and not the EDN spec a door hands its
// `main` either. So a host that has a spec as text reads it with the one
// kin-generated reader -- here `dist/flint-reader.wasm`, the module the
// JavaScript SDK reads source with -- and passes the `flint.forms` bytes. The
// spec's sources travel the same way, already read: `bin/flint --emit-spec`
// writes each one's bytes as base64 under `:preread`.
import { readFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { Reader } from '../sdks/esm/src/resolve.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
let reader = null;

/// The spec text `text` as `flint.forms` bytes, or a thrown Error naming where
/// it does not read.
export function readSpec(text, file = 'spec.edn') {
  if (!reader) reader = Reader.loadSync(readFileSync(join(root, 'dist/flint-reader.wasm')));
  const r = reader.read(text, { file, features: [], dialect: 'portable' });
  if (r.error) {
    throw new Error(`${file}:${r.error.line}:${r.error.column}: ${r.error.message}`);
  }
  return r.forms;
}
