// The standard library as `{path: text}` JSON, for the two JavaScript doors
// that embed it: `sdks/esm/build` (gen/stdlib.json) and `sdks/cli/build`
// (dist/stdlib.json).
//
// ONE WALK FOR BOTH, because they were two and disagreed: the ESM build took
// `.fln`, `.cljc` and `.clj`, and the npm CLI's copy took only `.cljc` and
// `.clj` -- so a standard-library namespace renamed to `.fln` (the stdlib's
// planned move, `DECISIONS.md#namespaces-over-the-system-port` step 9) would
// have vanished from the npm CLI with no error from either side.
// `flint.project/source-extensions` is the list this mirrors; `cli/build.rs`
// keeps the native door's.
//
//     node stdlib-walk.mjs <lib-dir> <out.json>
import fs from 'node:fs';
import path from 'node:path';

export const SOURCE_EXTENSIONS = ['.fln', '.cljc', '.clj'];

const [lib, outFile] = process.argv.slice(2);
const out = {};
(function walk(dir, prefix) {
  for (const name of fs.readdirSync(dir).sort()) {
    const full = path.join(dir, name);
    const rel = prefix ? prefix + '/' + name : name;
    if (fs.statSync(full).isDirectory()) walk(full, rel);
    else if (SOURCE_EXTENSIONS.some((e) => rel.endsWith(e))) out[rel] = fs.readFileSync(full, 'utf8');
  }
})(lib, '');
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, JSON.stringify(out));
console.log('    ' + Object.keys(out).length + ' files, ' +
            (JSON.stringify(out).length / 1024).toFixed(0) + ' KB');
