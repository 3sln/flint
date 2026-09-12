// The artifacts this CLI carries, read off disk.
//
// Unlike `sdks/esm`, which inlines everything because it has to work in a
// browser with no filesystem, this package is node-only by definition -- it IS
// the node CLI -- so the artifacts stay as files and are read on demand. A
// three-quarter-megabyte runtime that a `version` invocation never touches
// should not be parsed to print a version string.
//
// They are GENERATED (`bin/build-dist`) and copied here by `sdks/cli/build`, so
// a distributable can only ever carry artifacts built from the source beside
// it. A missing one fails with a sentence rather than with ENOENT.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

export const DIST = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');

function read(name) {
  try {
    return readFileSync(join(DIST, name));
  } catch {
    throw new Error(
      `${name} is missing from this package's dist/.\n` +
      'It is generated: run `sdks/cli/build` (which runs `bin/build-dist` first).');
  }
}

const memo = new Map();
function once(name, f) {
  if (!memo.has(name)) memo.set(name, f());
  return memo.get(name);
}

export const compilerWasm = () => once('flintc', () => read('flintc.wasm'));
export const runtimeWasm = () => once('rt', () => read('flint-runtime.wasm'));
export const runtimeAotWasm = () => once('rt-aot', () => read('flint-runtime-aot.wasm'));
export const slots = () => once('slots', () => JSON.parse(read('slots.json').toString('utf8')));
export const slotsAot = () =>
  once('slots-aot', () => JSON.parse(read('slots-aot.json').toString('utf8')));

/// flint's own `clojure.core` and everything it requires. Every program needs
/// them and a user should not have to carry them.
export const stdlib = () =>
  once('stdlib', () => JSON.parse(read('stdlib.json').toString('utf8')));

/// The standard library's OWN `deps.edn`, which names its workspace and says
/// what it holds. Without it the package has the library's code and no idea
/// whose it is -- and a guard that cannot name a workspace never fires.
export const stdlibDeps = () => once('lib-deps', () => read('lib-deps.edn').toString('utf8'));
