# The wasm distributable

Built by `bin/build-dist`. Three files, and the two `.wasm` are **generated** —
they are not tracked, because a committed binary is a binary nobody checks
against the source beside it. The release workflow builds them from the tag it
is publishing and attaches them there.

| file | what it is |
| --- | --- |
| `flintc.wasm` | the compiler. Clojure source in, a bytecode **image** out. |
| `flint-loader.wasm` | the runtime. Any image loads into it and runs. |
| `builtins.json` | every builtin the loader carries. |
| `src/loader.cljc` | generated: names every builtin so the linker keeps it. |

## What it can do

Compile and run Clojure with **no babashka, no JVM, no Rust and no linker**.
That is the whole point: `bin/flint` needs all four and only runs on a
developer machine.

```js
import { Compiler, Runtime } from '@3sln/flint';

const compiler = await Compiler.load();
const runtime  = await Runtime.load();

const image = compiler.compile({
  files: { 'app.cljc': '(ns app)\n(defn main [args] (str "hi " (first args)))' },
  entry: 'app/main',
});

runtime.run(image, ['there']);   // => { code: 0, out: 'hi there' }
```

The loader resolves an image's builtins **by name** when it loads it, so
nothing on the host patches table slots or knows the image format.

## What it cannot do, and why

**It cannot emit a standalone single-file `.wasm` module.** That is *linking*,
and linking relocatable wasm objects means running `wasm-ld`, which is a native
tool. `bin/flint` does that; this ships the two artifacts it produced.

**So `--aot` is not available here either**, because AOT emits wasm functions
that are appended to a module at link time, and there is no per-program module
on this path. Use `flint build --aot` for that.

Building the compiler itself with `--aot` was measured and rejected: **8138 ms
against 8036 ms**, a 1.3% saving for a module three times the size (479 KB →
1.47 MB). The reason it does nothing is that compiling is not
dispatch-dominated — nearly all of the time below is reading and analysing the
183 KB standard library, which happens on every compile.

## What it costs

| | |
| --- | --- |
| compile a two-namespace program | ~8.4 s |
| of which, the standard library | nearly all of it |
| run an image | microseconds; the loader is instantiated once |

**Eight seconds is a build-time cost, not a per-request one.** Every compile
re-reads and re-analyses `clojure.core` and everything it requires. A
precompiled prelude would remove it and does not exist yet — that is the single
biggest thing between this and a per-request compiler.

`bin/flint` does the same work in about 2 s on babashka. The 4× is the
interpreter tax, and it is the honest shape of a compiler that compiled itself.
