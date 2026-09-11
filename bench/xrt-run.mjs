// Instantiate a flint module and call `main`. Deliberately minimal: this is the
// JS engines' equivalent of `wasmtime --invoke main`, so it must not do more
// than they do.
// `Deno.args` rather than `process.argv` on deno 1.x, where `process` is not a
// global -- and `Deno.readFileSync`, since `node:fs` needs a compat flag. The
// work either way is: read a file, compile, instantiate, call.
const path = typeof Deno !== 'undefined' ? Deno.args[0] : process.argv[2];
// The FUNCTION, named: `wasmtime --invoke main` had a name in it too.
const FN = (typeof Deno !== 'undefined' ? Deno.args[1] : process.argv[3])
        ?? 'construe.bench.xrt25/main';
const bytes = typeof Deno !== 'undefined'
  ? Deno.readFileSync(path)
  : (await import('node:fs')).readFileSync(path);
const inst = await WebAssembly.instantiate(await WebAssembly.compile(bytes), {});
const code = wireCall(inst.exports, FN);
if (typeof Deno !== 'undefined') Deno.exit(code); else process.exitCode = code;

// `[name []]` in the wire format, written by hand: these drivers deliberately
// use nothing but a `WebAssembly.Instance` (`DECISIONS.md#cross-runtime-benchmarks`), so there is
// no SDK here to encode for them. Nothing is called automatically any more
// (`DECISIONS.md#structured-ports` step 5), so the name has to travel.
function wireCall(e, fn) {
  const enc = (s) => {
    const u = new TextEncoder().encode(s);
    const b = [5];                                   // K_STRING
    for (let i = 0; i < 4; i++) b.push((u.length >> (8 * i)) & 0xff);
    return b.concat(Array.from(u));
  };
  const call = [8, 2, 0, 0, 0]                       // K_VECTOR, 2 items
    .concat(enc(fn))
    .concat([8, 0, 0, 0, 0]);                        // K_VECTOR, no arguments
  const b = Uint8Array.from(call);
  const p = e.arg_alloc(b.length);
  new Uint8Array(e.memory.buffer).set(b, p);
  return e.flint_call(p, b.length);
}
