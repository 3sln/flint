// The version, from the package manifest.
//
// READ rather than restated. `flint version` printing a number that a release
// bumped in one file and not the other is the smallest possible lie and the
// hardest to notice, so there is one place it lives.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
export const VERSION =
  JSON.parse(readFileSync(join(here, '..', 'package.json'), 'utf8')).version;
