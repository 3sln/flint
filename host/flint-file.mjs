// Like flint.mjs, but takes the single argument from a file: the self-hosting
// spec is far larger than an argv entry.
import { readFileSync } from 'fs';
import { instantiate } from './flint.mjs';

const [, , wasmPath, argPath, fnArg] = process.argv;
// THE FUNCTION, named. A module has no entry point (`DECISIONS.md#structured-ports`
// step 5). Every caller of this file compiles `flint.selfhost/main` -- it is
// what `bin/build-dist`, `bin/flint` and `test/selfhost.clj` all build -- so it
// is the default rather than something three call sites repeat.
const fn = fnArg ?? 'flint.selfhost/main';
const bytes = readFileSync(wasmPath);
const module = await WebAssembly.compile(bytes);
const inst = instantiate(module);
// Compiling a whole program can outgrow the 512 MB default, and past it an
// allocation answers NIL, the NIL reaches the tree, and the failure surfaces as
// `memory access out of bounds` with nothing pointing at the cap. The compiler
// grew past it the day it started carrying the wasm writer.
if (inst.exports.set_memory_limit) inst.exports.set_memory_limit(3_000_000_000);
const arg = readFileSync(argPath, 'utf8');
const r = inst.run(fn, [arg]);
// `process.exit` does not flush an async write to a pipe: anything past the
// pipe buffer (64 KiB here) is silently lost. Set the code and let node drain.
process.exitCode = r.code;
process.stdout.write(r.out);
