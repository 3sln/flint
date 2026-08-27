// Like flint.mjs, but takes the single argument from a file: the self-hosting
// spec is far larger than an argv entry.
import { readFileSync } from 'fs';
import { instantiate } from './flint.mjs';

const [, , wasmPath, argPath] = process.argv;
const bytes = readFileSync(wasmPath);
const module = await WebAssembly.compile(bytes);
const inst = instantiate(module);
// Compiling a whole program can outgrow the 512 MB default, and past it an
// allocation answers NIL, the NIL reaches the tree, and the failure surfaces as
// `memory access out of bounds` with nothing pointing at the cap. The compiler
// grew past it the day it started carrying the wasm writer.
if (inst.exports.set_memory_limit) inst.exports.set_memory_limit(3_000_000_000);
const { main } = inst;
const arg = readFileSync(argPath, 'utf8');
const r = main(arg);
// `process.exit` does not flush an async write to a pipe: anything past the
// pipe buffer (64 KiB here) is silently lost. Set the code and let node drain.
process.exitCode = r.code;
process.stdout.write(r.out);
