// `stdextra()`: the OPTIONAL half of flint's standard library, as a resolver
// (`DECISIONS.md#namespaces-over-the-system-port` §4).
//
// Everything in `lib/` that an image does not need whatever the host supplies
// -- `clojure.set`, `clojure.edn`, `clojure.walk`, `flint.data.json` and the
// rest -- pre-read into `flint.compiler.forms` bytes at build time
// (`bin/build-stdlib-forms`), each answer carrying the workspace and grants of
// the `deps.edn` that owns it. A host composes it into the resolver it passes,
// usually first:
//
//     resolve: chain(stdextra(), fromMap(files))
//
// or leaves it out, and those namespaces resolve as missing like any other.
//
// IN ITS OWN MODULE ON PURPOSE. The SDK's core (`flint.js`) does not import
// this file, so a program that never imports `@3sln/flint/stdextra` never loads
// the blob, and a bundler drops it without having to prove anything pure.
// `clojure.core` is NOT here and cannot be left out: it is stdcore, answered by
// the core before any resolver runs.
import STDEXTRA from '../../../dist/stdextra.forms' with { type: 'bytes' };
// From the CORE, not from `./resolve.js`: the bundle keeps this import, so the
// shipped `stdextra.js` is the blob and one import, and shares the core's code.
import { layer } from './flint.js';

let cached = null;

/// The resolver. Answers a stdextra namespace, and `null` for anything else.
export function stdextra() {
  if (!cached) cached = layer(STDEXTRA);
  return cached;
}
