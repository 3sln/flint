// THE ESM SDK AS A DOOR, for `test/door-agreement.clj`.
//
//     node test/esm-door.mjs <src-dir> <ns/fn> <:wasm|:llvm|:clr|:jvm> <out> [perf]
//
// Compiles every source under `src-dir` through `sdks/esm/dist/flint.js` -- the
// bundle that ships, not its source -- with the resolver an embedder would
// write: `chain(stdextra(), fromMap(files))`. The CLIs answer a project the same
// way (stdextra first, then the roots), so for a project with no `deps.edn` the
// three doors are handed the same answers and must write the same bytes.
//
// `out`'s BASENAME IS AN INPUT, as on the CLIs: `:to :clr` writes it into the
// assembly name and `:to :jvm` takes the class name from it.
import { readdirSync, readFileSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const { Compiler, chain, fromMap, SOURCE_EXTENSIONS } = await import(join(root, 'sdks/esm/dist/flint.js'));
const { stdextra } = await import(join(root, 'sdks/esm/dist/stdextra.js'));

const [src, fn, to, out, mode] = process.argv.slice(2);
const files = {};
(function walk(dir, prefix) {
  for (const name of readdirSync(dir).sort()) {
    const p = join(dir, name);
    const rel = prefix ? `${prefix}/${name}` : name;
    if (statSync(p).isDirectory()) walk(p, rel);
    else if (SOURCE_EXTENSIONS.some((e) => rel.endsWith(e))) files[rel] = readFileSync(p, 'utf8');
  }
})(src, '');

const target = String(to).replace(/^:/, '');
const name = basename(out);
const c = await Compiler.load();
try {
  const bytes = await c.emit({
    fn, target, resolve: chain(stdextra(), fromMap(files)),
    optimize: mode === 'perf' ? ['perf'] : [],
    name: target === 'clr' ? name : null,
    className: target === 'jvm' && name.endsWith('.class') ? name.slice(0, -'.class'.length) : null,
  });
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, bytes);
} catch (e) {
  console.error(String(e.message ?? e));
  process.exit(1);
}
