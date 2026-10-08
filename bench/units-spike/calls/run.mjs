// Cost of a call across a module boundary, against the same call inside one
// module (`DECISIONS.md#composing-runtime-units`). `a.wasm` imports `f` from
// `b.wasm`; `a1.wasm` defines it. `indirect` calls through a function pointer,
// which is how flint reaches EVERY builtin (CALL_NATIVE is a call_indirect).
import { readFileSync } from 'fs';
const dir = process.argv[2], N = 50_000_000, R = 9;
const memory = new WebAssembly.Memory({ initial: 4 }), table = new WebAssembly.Table({ initial: 200, element: 'anyfunc' });
const load = (f, imp) => new WebAssembly.Instance(new WebAssembly.Module(readFileSync(`${dir}/${f}`)), imp);
const B = load('b.wasm', { env: { memory, __indirect_function_table: table } });
const A = load('a.wasm', { env: { memory, __indirect_function_table: table, f: B.exports.f } });
const A1 = load('a1.wasm', {});
const arms = { 'two modules, direct (import)': A.exports.direct, 'two modules, call_indirect': A.exports.indirect,
  'one module, direct': A1.exports.direct, 'one module, call_indirect': A1.exports.indirect };
for (const f of Object.values(arms)) f(1e6);
const t = Object.fromEntries(Object.keys(arms).map((k) => [k, []]));
for (let r = 0; r < R; r++) for (const [k, f] of Object.entries(arms)) {
  const t0 = process.hrtime.bigint(); f(N); t[k].push(Number(process.hrtime.bigint() - t0) / N);
}
for (const [k, xs] of Object.entries(t)) { xs.sort((a, b) => a - b); console.log(`${k.padEnd(30)} ns/call median ${xs[R >> 1].toFixed(2)} min ${xs[0].toFixed(2)}`); }
