// Like flint.mjs, but takes the single argument from a file: the self-hosting
// spec is far larger than an argv entry.
import { readFileSync } from 'fs';
import { instantiate } from './flint.mjs';

const [, , wasmPath, argPath] = process.argv;
const bytes = readFileSync(wasmPath);
const module = await WebAssembly.compile(bytes);
const { main } = instantiate(module);
const arg = readFileSync(argPath, 'utf8');
const r = main(arg);
// `process.exit` does not flush an async write to a pipe: anything past the
// pipe buffer (64 KiB here) is silently lost. Set the code and let node drain.
process.exitCode = r.code;
process.stdout.write(r.out);
