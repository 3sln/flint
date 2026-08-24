#!/usr/bin/env node
// npm entry point for `flint`.
//
// The compiler itself is `bin/flint`, a babashka script — flint is written in
// portable cljc and bootstraps on babashka (doc/decisions/0003). npm can install
// the package but cannot install babashka, so this shim exists to fail with a
// sentence a reader can act on rather than with `env: bb: No such file`.
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const flint = path.join(here, 'flint');

const probe = spawnSync('bb', ['--version'], {stdio: 'ignore'});
if (probe.error) {
  console.error('flint needs babashka (`bb`) on PATH: https://babashka.org');
  console.error('  brew install borkdude/brew/babashka');
  console.error('  # or: curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && bash install');
  process.exit(127);
}

const run = spawnSync('bb', [flint, ...process.argv.slice(2)], {stdio: 'inherit'});
process.exit(run.status ?? 1);
