# @3sln/flint

Compile pure Clojure to a self-contained WebAssembly module, from JavaScript,
in any runtime.

```js
import { Compiler, chain, fromMap } from '@3sln/flint';
import { stdextra } from '@3sln/flint/stdextra';

const compiler = await Compiler.load();

// Source comes from a RESOLVER, not a `files` map -- see "Compiling" below.
// `stdextra()` is the optional half of the standard library (`clojure.set`,
// `clojure.edn`, `flint.host`, and the rest); `clojure.core` and seven other
// namespaces are answered by the SDK itself and never put to `resolve` at all.
const image = await compiler.compile({
  fn: 'app/main',
  resolve: chain(stdextra(), fromMap({
    'app.cljc': '(ns app)\n(defn main [args] (str "hi " (first args)))',
  })),
});

const sandbox = await image.sandbox();
await sandbox.call('app/main', [['world']]);   // => 'hi world'
```

## One file, no dependencies

Everything is inside the module: the compiler, both runtime modules, their
builtin slot maps and flint's standard library (split into the required
stdcore and the optional `stdextra()`, below). There is **no `node:` import,
no filesystem access and no fetch**, so the same file works in a browser, in
node, in a Worker, in Deno and in Bun, and a bundler has nothing to resolve.

It costs 4.2 MB, plus 0.28 MB for `stdextra.js` if it is imported (`ls -l` of
the two bundles, 2026-10-07: 4 414 260 and 282 252 bytes; base64 inflates the
embedded wasm by a third). That is the compiler, both runtimes, the reader that
reads your source outside the compiler (`flint-reader.wasm`, 452 987 bytes raw)
and stdcore, which have to come from somewhere; the alternative is fetching
them at run time, which is a different trade and not a smaller one.

## Compiling

`compile` is **asynchronous, and there is no synchronous twin** -- a resolver
that fetches over the network or reads IndexedDB answers with a promise, and a
synchronous API cannot be made asynchronous later without breaking every
caller. It returns an `Image`.

| option | |
| --- | --- |
| `resolve` | `(ns) => answer \| Promise<answer>` -- see "The resolver" below |
| `fn` | the function a default `run` would call, e.g. `'my.app/main'` |
| `exports` | every other function that must stay callable |
| `optimize` | `['perf']` compiles each arity and drops checks; `['size']` interprets. An ORDERED preference list: the first token this build understands decides |
| `checks` | `true`/`false` keeps or drops `#?(:flint/check ..)`; default: dropped under `perf`, kept otherwise |
| `features` | the reader features, overriding the two above |
| `shake` | cut the runtime to what the program reaches (on by default) |
| `meta` | arbitrary metadata to record in the artifact |

`emit({ ..., target })` is the same call for any target `compile` does not
wrap: `'wasm'` (what `compile` returns), `'llvm'` (IR text, as bytes), `'clr'`
(an assembly) or `'jvm'` (a class) -- the same request the CLIs send for
`:to <target>`, so the same program is the same bytes from every door.

A failed compile **throws an `Error`** whose `.errors` is the compiler's error
data: every problem at once, each with a `:kind` (`:missing`, `:refused`,
`:read`, `:resolver`, `:compile`, ...) and enough position to report. `.message`
is the same sentences the CLIs print, joined.

### The resolver

There is no `files`, `workspaces` or `standardLibrary` option. Source comes
**only** from `resolve`, a function the compiler asks for one sorted wave of
namespaces at a time, each name once. An answer is:

```
null                              not found
"source text"                     portable source, no workspace, no grants
{ source | forms, file, dialect, workspace, grants, guard, tags, prelude }
{ virtual: true, vars, workspace, grants, guard }
{ error: { message, file, line, column } }
```

`workspace`, `grants`, `guard`, `tags` and `prelude` are fields **of the
answer** -- never of the namespace's name, a path prefix, or anything inside
the source. That is what makes a user file under `flint/` get no grant just
for living there: grants are conferred by whoever answers, not inferred from
where. Promises are fine anywhere a plain answer is: `resolve` may return one,
and `chain` and the SDK wait on it.

**stdcore is always present and is never asked of `resolve`.** `clojure.core`
and seven other namespaces (`stdcoreNamespaces()` lists them) are answered by
the SDK's own embedded copy before `resolve` is consulted at all -- not asked
and ignored, there is no path to a host's answer for those names overriding
them. The rest of the standard library -- `clojure.set`, `clojure.edn`,
`flint.host`, and the rest -- is `stdextra()`, imported separately from
`@3sln/flint/stdextra` and composed in explicitly:

```js
import { stdextra } from '@3sln/flint/stdextra';
resolve: chain(stdextra(), fromMap(files))
```

It is its own module so that a program which never imports
`@3sln/flint/stdextra` never carries its bytes -- a bundler can tree-shake it
away without having to prove anything pure.

**Checks need it.** Checks are on by default, and their runtime, `flint.check`,
is in stdextra -- so a resolver without `stdextra()` gets `no source for
flint.check` unless the compile says `checks: false` (or `optimize: ['perf']`,
which drops them). The error says so.

**Dialect**: when a namespace has source under more than one extension, `.fln`
(flint's own dialect) wins over `.cljc` wins over `.clj`.

**Building blocks**, all exported from `@3sln/flint`:

| | |
| --- | --- |
| `chain(...resolvers)` | the first non-null answer wins; works with resolvers that return promises |
| `fromMap(files, identity?)` | `{ 'my/app.cljc': text, .. }` -> a resolver; `identity` (`workspace`, `grants`, `guard`, `tags`, `prelude`) is attached to every answer this map gives |
| `segregate(prefixes, resolver)` | names under `prefixes` come from `resolver` or nowhere -- a miss there is final, so nothing later in a `chain` can supply e.g. `flint.*` |
| `virtualNamespaces(table)` | `{ 'my.svc': { vars: [{name, arities}], workspace } }` -> a resolver for namespaces the host serves over a port rather than compiles; `vars` is optional and, when present, makes an unknown var a compile error instead of a run-time one |

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
