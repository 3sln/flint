// Instantiate a flint module and call `main`. Deliberately minimal: this is the
// JS engines' equivalent of `wasmtime --invoke main`, so it must not do more
// than they do.
// `Deno.args` rather than `process.argv` on deno 1.x, where `process` is not a
// global -- and `Deno.readFileSync`, since `node:fs` needs a compat flag. The
// work either way is: read a file, compile, instantiate, call.
const path = typeof Deno !== 'undefined' ? Deno.args[0] : process.argv[2];
const bytes = typeof Deno !== 'undefined'
  ? Deno.readFileSync(path)
  : (await import('node:fs')).readFileSync(path);
const inst = await WebAssembly.instantiate(await WebAssembly.compile(bytes), {});
const code = inst.exports.main();
if (typeof Deno !== 'undefined') Deno.exit(code); else process.exitCode = code;
