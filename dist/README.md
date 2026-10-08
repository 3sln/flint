# The wasm distributable

Built by `bin/build-dist`. Three files, and the two `.wasm` are **generated** —
they are not tracked, because a committed binary is a binary nobody checks
against the source beside it. The release workflow builds them from the tag it
is publishing and attaches them there.

| file | what it is |
| --- | --- |
| `flintc.wasm` | the compiler. Clojure in, an **image** or a **module** out. |
| `flint-loader.wasm` | the runtime. Any image loads into it and runs. |
| `flint-runtime.wasm` | what a compiled module is spliced into. |
| `flint-runtime-aot.wasm` | the same, carrying the compiled-arity helpers. |
| `flint-reader.wasm` | the kin reader alone: source text to `flint.compiler.forms` bytes, for a host that reads outside the compiler (`DECISIONS.md#namespaces-over-the-system-port`). |
| `stdcore.forms`, `stdextra.forms` | the standard library, read, in its required and optional layers (`bin/build-stdlib-forms`). |
| `builtins.json`, `slots.json`, `slots-aot.json` | what those runtimes carry, and where. |
| `src/loader.cljc` | generated: names every builtin so the linker keeps it. |

## What it can do

Compile and run Clojure with **no babashka, no JVM, no Rust and no linker**.
That is the whole point: `bin/flint` needs all four and only runs on a
developer machine.

```js
import { Compiler, chain, fromMap } from '@3sln/flint';
import { stdextra } from '@3sln/flint/stdextra';

const compiler = await Compiler.load();
const image = await compiler.compile({
  fn: 'app/main',
  resolve: chain(stdextra(), fromMap({
    'app.cljc': '(ns app)\n(defn main [args] (str "hi " (first args)))',
  })),
});
await (await image.sandbox()).call('app/main', [['there']]);   // => 'hi there'
```

The compiler asks the host for each namespace it reaches, over a port, and the
host answers with source it READ itself, with `flint-reader.wasm`
(`DECISIONS.md#namespaces-over-the-system-port`). What comes back is a
standalone module; `optimize: ['perf']` appends compiled arities to it.

*This section showed `Runtime.load()` and `compiler.compileToWasm(..)` until
2026-10-07, neither of which the SDK exports, and the sizes beside them (600 KB,
638 KB) were never re-measured against the current runtime; they are dropped
rather than carried.*

No linker in either path. The runtime module was linked once, when flint was
built; splicing an image into it and appending compiled arities is byte
manipulation on a finished module (`DECISIONS.md#no-runtime-linking`). `--aot` is **7×
faster** on arithmetic — 11.2 ms against 1.6 ms — for 38 KB more.

## What it cannot do, and why

**It cannot produce the RUNTIME module itself.** That is a link over
relocatable objects and needs `wasm-ld`. It happens when flint is built, and
the result ships here.

Tree shaking is **on** by default and cuts the runtime down to what the
program reaches: 602 KB plain against 378 KB shaken, 656 KB against 431 KB with
`--aot`.

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
