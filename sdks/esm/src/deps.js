// `deps()`: `flint.deps` and its three siblings, as an OPTIONAL resolver building
// block (`DECISIONS.md#four-units`).
//
// `flint.deps` is its own unit -- workspace `flint/deps`, source root `lib/deps/`
// -- and not part of the standard library: reading and resolving `deps.edn`,
// `package.json` and `pom.xml` is something a tool built on flint may want and an
// ordinary program does not. Pre-read into `flint.forms` bytes at build time
// (`bin/build-stdlib-forms`), each answer carrying `lib/deps/deps.edn`'s
// workspace. A host composes it in like `stdextra()`:
//
//     resolve: chain(stdextra(), deps(), fromMap(files))
//
// `flint.deps` requires `clojure.edn` and `clojure.string`, so it is of no use
// without `stdextra()` beside it. `flint.deps.resolve` additionally requires the
// virtual `flint.deps.npm`/`-git`/`-mvn`, which only the CLIs serve.
//
// IN ITS OWN MODULE, for the reason `stdextra.js` is: a host that never imports
// `@3sln/flint/deps` never loads the blob.
import DEPS from '../../../dist/deps.forms' with { type: 'bytes' };
import { layer } from './flint.js';

let cached = null;

/// The resolver. Answers a `flint.deps*` namespace, and `null` for anything else.
export function deps() {
  if (!cached) cached = layer(DEPS);
  return cached;
}
