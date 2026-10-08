// Read source with THE reader, for a caller that has none of its own
// (`DECISIONS.md#one-reader-and-no-other`).
//
// The compiler reads no text, and babashka -- which runs most of `test/` -- can
// load the compiler's Clojure but not the kin-generated reader. This is the
// bridge: a JSON array of `{file, text, dialect, tags, features}` on stdin
// (`features` null reads DEFERRED; `tags` is `[[tag, var], ..]`), and on stdout
// a JSON array parallel to it of `{forms: <base64 flint.forms bytes>}` or
// `{error: {message, line, column}}`. One process reads a whole batch, so a
// caller pays node's start-up once per batch rather than once per file.
//
// Through `dist/flint-reader.wasm`, the module the JavaScript SDK reads with --
// the same reader `bin/check-reader` holds every runtime's copy to.
import { readFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { Reader } from '../sdks/esm/src/resolve.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const reader = Reader.loadSync(readFileSync(join(root, 'dist/flint-reader.wasm')));
const readOne = ({ file, text, dialect = 'portable', tags = [], features = null }) => {
  const r = reader.read(text, { file, features, tags, dialect });
  if (r.error) return { error: { message: r.error.message, line: r.error.line, column: r.error.column } };
  return { forms: Buffer.from(r.forms).toString('base64') };
};

if (process.argv[2] === '--serve') {
  // ONE BATCH PER LINE, answered with one line, for as long as stdin is open:
  // a caller that reads many small things -- `test/reader_test.clj` -- pays
  // node's start-up once.
  const { createInterface } = await import('readline');
  for await (const line of createInterface({ input: process.stdin })) {
    if (line.trim() === '') continue;
    process.stdout.write(JSON.stringify(JSON.parse(line).map(readOne)) + '\n');
  }
} else {
  process.stdout.write(JSON.stringify(JSON.parse(readFileSync(0, 'utf8')).map(readOne)));
}
