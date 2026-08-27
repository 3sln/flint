# @3sln/flint

Compile pure Clojure to a self-contained WebAssembly module, from JavaScript,
in any runtime.

```js
import { Compiler, Runtime } from '@3sln/flint';

const compiler = await Compiler.load();

// A standalone .wasm module.
const wasm = compiler.compileToWasm({
  files: { 'app.cljc': '(ns app)\n(defn main [args] (str "hi " (first args)))' },
  entry: 'app/main',
});

// Or a bytecode image, run in a resident loader -- the shape for a
// per-request binding, where the loader is instantiated once.
const image = compiler.compile({ files, entry: 'app/main' });
const runtime = await Runtime.load();
runtime.run(image, ['there']);        // => { code: 0, out: 'hi there' }
```

## One file, no dependencies

Everything is inside the module: the compiler, both runtime modules, their
builtin slot maps and flint's whole standard library. There is **no `node:`
import, no filesystem access and no fetch**, so the same file works in a
browser, in node, in a Worker, in Deno and in Bun, and a bundler has nothing to
resolve.

It costs 2.5 MB. That is the compiler and its standard library, which have to
come from somewhere; the alternative is fetching them at run time, which is a
different trade and not a smaller one.

## `compileToWasm`

| option | |
| --- | --- |
| `files` | `{ 'path.cljc': source }`. flint's standard library is added for you. |
| `entry` | `'my.app/main'` |
| `aot` | compile each arity to wasm as well: bigger, and much faster on arithmetic |
| `shake` | cut the runtime down to what the program reaches (on by default) |

On an arithmetic loop, `aot` runs in 1.6 ms against 11.2 ms. Shaking takes a
602 KB module to 378 KB, or 656 KB to 431 KB with `aot`.

**No linker is involved**, which is what makes this possible at all: the
runtime module was linked once, when flint was built, and compiling splices an
image into it (`doc/decisions/0024`).

## What it does not do

**Green threads that talk to the host.** A program that parks on a host port
needs the full event loop in `host/flint.mjs`; this is the compile-and-answer
shape and says so rather than returning half a result.

**Produce the runtime module itself.** That is a link over relocatable objects
and needs `wasm-ld`. It happens when flint is built.

## Building it

```
./build          # builds the wasm artifacts, bundles, and tests the result
```

The artifacts are generated, never committed, so a distributable can only carry
artifacts built from the source beside it.
