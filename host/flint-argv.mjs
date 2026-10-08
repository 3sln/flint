// Run the compiler with a FULL ARGV, not just a spec.
//
// `host/flint-file.mjs` passes `[spec]`, so it can only reach mode `spec`
// (`flint.compiler.selfhost/main` dispatches on the first element). Every ARTIFACT target
// -- `jvm`, `clr`, `llvm` -- needs `["jvm", spec, "", ""]`, and nothing could
// hand that to the wasm compiler, which is why wasm-against-a-port comparisons
// only ever covered mode `spec`.
//
//     node host/flint-argv.mjs <wasm> <spec-file> <mode> [extra...]
//
// The spec comes from a FILE for `flint-file.mjs`'s reason: it is far larger
// than an argv entry.
import { readFileSync } from 'fs';
import { instantiate } from './flint.mjs';
import { codec } from '../sdks/esm/src/codec.js';
import { readSpec } from './spec.mjs';

const [, , wasmPath, specPath, ...rest] = process.argv;
const bytes = readFileSync(wasmPath);
const module = await WebAssembly.compile(bytes);
const inst = instantiate(module);
if (inst.exports.set_memory_limit) inst.exports.set_memory_limit(3_000_000_000);
// THE SPEC READ HERE, not by the compiler (`host/spec.mjs`).
const spec = readSpec(readFileSync(specPath, 'utf8'), specPath);
// MODE FIRST, then the spec, then whatever the mode takes -- the order
// `flint.compiler.selfhost/main` reads its argv in.
const argv = [codec.str(rest[0]), codec.bytes(spec), ...rest.slice(1).map((a) => codec.str(String(a)))];
let r;
try {
  const v = inst.call('flint.compiler.selfhost/main', [codec.vec(argv)]);
  r = { code: 0, out: v === null || v === undefined ? '' : String(v) };
} catch (err) {
  r = { code: 1, out: err.kind ? `${err.kind}: ${err.message}` : String(err.message ?? err) };
}
process.exitCode = r.code;
process.stdout.write(r.out);
