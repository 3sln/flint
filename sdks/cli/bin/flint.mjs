#!/usr/bin/env node
// `flint`, as `npm install -g @3sln/flint-cli` leaves it on a PATH.
//
// Everything is in `../src`; this file is the shebang, the argv, and the exit
// code -- and the exit code is the part with a trap in it. `process.exit` does
// not flush an async write to a pipe: anything past the pipe buffer (64 KiB) is
// silently lost, so the code is SET and node is left to drain.

import { main } from '../src/cli.mjs';

try {
  process.exitCode = await main(process.argv.slice(2));
} catch (err) {
  process.stderr.write(`flint: ${err?.message ?? err}\n`);
  process.exitCode = 1;
}
