// Like flint.mjs, but takes the single argument from a file: the self-hosting
// spec is far larger than an argv entry.
import { readFileSync } from 'fs';
import { instantiate } from './flint.mjs';
import { codec } from '../sdks/esm/src/codec.js';
import { readSpec } from './spec.mjs';

const [, , wasmPath, argPath, fnArg] = process.argv;
// THE FUNCTION, named. A module has no entry point (`DECISIONS.md#structured-ports`
// step 5). Every caller of this file compiles `flint.compiler.selfhost/main` -- it is
// what `bin/build-dist`, `bin/flint` and `test/selfhost.clj` all build -- so it
// is the default rather than something three call sites repeat.
const fn = fnArg ?? 'flint.compiler.selfhost/main';
const bytes = readFileSync(wasmPath);
const module = await WebAssembly.compile(bytes);
const inst = instantiate(module);
// Compiling a whole program can outgrow the 512 MB default, and past it an
// allocation answers NIL, the NIL reaches the tree, and the failure surfaces as
// `memory access out of bounds` with nothing pointing at the cap. The compiler
// grew past it the day it started carrying the wasm writer.
if (inst.exports.set_memory_limit) inst.exports.set_memory_limit(3_000_000_000);
// THE SPEC READ HERE, not by the compiler (`host/spec.mjs`).
const spec = readSpec(readFileSync(argPath, 'utf8'), argPath);
let r;
try {
  const v = inst.call(fn, [codec.vec([codec.bytes(spec)])]);
  r = { code: 0, out: v === null || v === undefined ? '' : String(v) };
} catch (err) {
  r = { code: 1, out: err.kind ? `${err.kind}: ${err.message}` : String(err.message ?? err) };
}
// `process.exit` does not flush an async write to a pipe: anything past the
// pipe buffer (64 KiB here) is silently lost. Set the code and let node drain.
process.exitCode = r.code;
process.stdout.write(r.out);
