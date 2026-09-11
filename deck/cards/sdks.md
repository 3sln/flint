# The SDKs

An SDK is how a host application embeds flint directly, without shelling out
to the [CLI](cli.md). Three exist today (`sdks/README.md`):

| | language | how it runs | status |
|---|---|---|---|
| `sdks/esm/` | JavaScript, any runtime | a wasm engine | built |
| `sdks/rust/` | Rust | native | built |
| `sdks/c/` | C and C++ | native | built |

A Java SDK (over the JVM port) and a C# SDK (over the CLR port) are listed as
not started — the *runtimes* they'd wrap are built and self-hosting, but the
idiomatic host-facing layer over them doesn't exist yet.

All three shipped SDKs are the same design in three languages: **the shape is
deliberately identical**, so knowing one tells you most of what you need for
another. Every one of them is a wrapper around the same two build artifacts —
the compiler and the flint runtime, both compiled to wasm once when flint
itself is built (`bin/build-dist`) — so "the compiler" behaves identically
regardless of which SDK is asking it to compile something.

## The shape every SDK shares

Four nouns, everywhere:

- **A resolver** — namespace name to source text. No SDK touches a
  filesystem itself, so a host can compile out of a database, a zip, an
  embedded string, whatever it wants.
- **A Compiler** — takes an entry function name, a list of exports, an
  `optimize` preference, and arbitrary `meta`.
- **An Image** — the compiled artifact, plus whatever metadata the compiler
  was told to record (this is where a compiled program's *declared*
  capabilities live, as data the host later decides what to do with).
- **A Sandbox** — `call(fn, args)` by name, a step limit, and gas. There used
  to be a fifth noun-level piece here, `Sandbox.grant(name)`, backed by a
  capability table the runtime itself kept — it's gone (removed 2026-08-30,
  decision 0022's cutover). A capability isn't a concept a Sandbox has: what
  a call receives is an ordinary opaque value the host minted, and whether it
  means "capability" is a convention the host and the guest program agree on
  between themselves — see [Capabilities and the
  sandbox](capabilities-and-the-sandbox.md).

And a fifth, a **Driver**, which owns how many threads a sandbox gets and
when a runnable green thread actually runs. Targets differ in what they can
honour: native SDKs can really run several threads inside one sandbox; the
wasm target cannot (no shared-memory, no atomics yet), so asking for
`ThreadPool(4)` there hands back a driver whose measured parallelism reads
back as `1` — a real answer about what you got, not a rejection of what you
asked for. `call` is asynchronous everywhere for the same reason: it queues
a request and wakes the driver, and even the simplest inline driver still
returns a promise/`Task`/`Pending`, because a synchronous API can't be
widened into an asynchronous one later without breaking every caller.

`call` takes just a function name and positional arguments. An argument map,
capabilities-as-CLI-arguments, `:with` — those are conventions of the [CLI
specifically](cli.md), not the SDK surface.

## JavaScript (`sdks/esm`)

One dependency-free file — the compiler, both runtime modules, and flint's
whole standard library are all embedded in it, so the same file runs in a
browser, Node, a Worker, Deno or Bun with nothing to fetch and nothing for a
bundler to resolve. It costs about 2.5 MB for that.

```javascript
import { Compiler, Driver, Inline, ThreadPool } from './dist/flint.js';

const compiler = await Compiler.load();
const image = compiler.compile({
  files: {
    'app.cljc': `(ns app (:require [app.util :as u]))
                 (defn greet [name] (u/shout (str "hello " name)))`,
    'app/util.cljc': `(ns app.util (:require [clojure.string :as s]))
                      (defn shout [x] (s/upper-case x))`,
  },
  fn: 'app/main',
  exports: ['app/greet'],
  meta: { capabilities: ['fs'], author: 'example' },
});

const sandbox = await image.sandbox();
await sandbox.call('app/greet', ['flint']);   // => "HELLO FLINT"
```
— adapted from `sdks/esm/selftest.mjs`, which tests the actual built,
shipped `dist/flint.js` rather than the source it's built from. A `Map`,
`Set`, keyword string (`':a'`), `null` and byte array all round-trip through
`call` unchanged; a thrown flint exception reaches the caller as a rejected
promise carrying `e.flint`; an unknown function name comes back named in the
error rather than as a generic failure. A sandbox keeps state across calls —
an atom bumped by one call is still bumped on the next — while a second,
freshly-instantiated sandbox from the same `image` starts clean, which is
what makes "compile once, instantiate per request" workable. `callSync` also
exists, for a caller with nothing else to do while a wasm sandbox (which
really is synchronous underneath) runs — but `call`'s async `Promise` is the
shape every other SDK mirrors.

For producing a standalone module rather than an in-process sandbox, there is
also `compileToWasm({ files, entry, aot, shake })`, which writes a `.wasm`
you can hand to any host with a wasm engine (see the worked example in
[Introduction](introduction.md)). What the ESM SDK explicitly does **not**
do: run a program that parks on a *host* port, which needs the fuller event
loop `host/flint.mjs` implements — the SDK's `call` is a compile-and-answer
shape, not a host pump.

## Rust (`sdks/rust`)

Runs flint **natively** — no wasm engine at all. flint's runtime is Rust, so
it already compiles through LLVM for the host's own target; the collector,
the interpreter and every builtin are the same code that the wasm build
compiles from.

```rust
use flint::{Compile, Compiler, Value};

const APP: &str = r#"
(ns app (:require [clojure.string :as s]))
(def seen (atom 0))
(defn greet [name] (s/upper-case (str "hello " name)))
(defn tally [] (swap! seen inc))
"#;

let compiler = Compiler::embedded().expect("the compiler is embedded");
let img = compiler
    .compile(Compile {
        resolve: &|ns: &str| if ns == "app" { Some(APP.to_string()) } else { None },
        fn_name: "app/greet",
        exports: &["app/greet", "app/tally"],
        meta: vec![("capabilities".into(), Value::Vector(vec![Value::str("fs")]))],
        ..Default::default()
    })
    .expect("it compiles");

let sandbox = img.sandbox().expect("it instantiates");
sandbox.call_blocking("app/greet", &[Value::str("flint")]).unwrap();
// => Value::str("HELLO FLINT")
```
— `sdks/rust/tests/sdk.rs`. `call_blocking` is the synchronous entry point;
an async `call` exists too, for the same driver-coalescing reasons described
above. A `Value` enum (`Nil`, `Int`, `Float`, `str`, `kw`, `Vector`, a
keyword-map constructor, `Bytes`, …) is the same codec the JS SDK's `codec`
module implements — whatever it can carry survives a call unchanged.

## C and C++ (`sdks/c`)

One library, two headers: `flint.h` is the plain-C ABI; `flint.hpp` layers
RAII and `flint::Error` exceptions over the same symbols, so there's no
second implementation to drift out of sync.

```c
#include "flint.h"

static const char *APP = "(ns app) (defn greet [n] (str \"hello \" n))";

static int resolve(void *ctx, const char *ns, const char **out) {
  if (strcmp(ns, "app") != 0) return 0;
  *out = APP;
  return 1;
}

char *err = NULL;
FlintCompiler *c = flint_compiler_new(&err);

FlintCompileOpts opts;
memset(&opts, 0, sizeof opts);   /* a zeroed struct is a valid request */
opts.fn_name = "app/greet";
opts.resolve = resolve;
opts.shake = 1;

FlintImage *img = flint_compile(c, &opts, &err);
FlintSandbox *s = flint_sandbox_new(img, &err);

FlintValue *name = flint_str("world");
const FlintValue *args[] = {name};
FlintValue *out = flint_call(s, "app/greet", args, 1, &err);
char *text = flint_as_str(out);   /* "hello world" */
```

The C++ version of the same program is shorter and throws instead of setting
`err`:

```cpp
flint::Compiler compiler;
flint::Compile spec;
spec.fn = "app/greet";
spec.resolve = [](const std::string &ns, std::string &out) {
  if (ns != "app") return false;
  out = APP;
  return true;
};
auto sandbox = compiler.compile(spec).sandbox();
std::string text = sandbox.call("app/greet", {flint::Value::string("world")}).asString();
```

Two rules cover the whole C header: anything the library *returns* is
yours — free it with the matching `flint_*_free`; anything you *pass in*
stays yours, and collection constructors copy. A `FlintValue*` is
deliberately opaque — flint's value representation is NaN-boxed and a
runtime detail, and an opaque handle is also what keeps C code from
fabricating a port or a capability out of a bare integer. Like the Rust SDK,
this carries no wasm engine; it links the same Rust runtime natively.

The header still declares `flint_sandbox_grant(s, name)` (and `flint.hpp`'s
`Sandbox::grant`) as a way to lend a named capability — but nothing in
`sdks/c/src` implements that symbol any more, which looks like the header
simply not having caught up with the Rust SDK's 2026-08-30 removal of its own
`Sandbox::grant` (see [Capabilities and the sandbox](capabilities-and-the-sandbox.md)
for why: a capability isn't a concept the runtime holds a table for any
more). Don't rely on it; present an opaque value with the request instead,
the way `flint.port/open`'s `opts` argument does at the language level.

## What's shared, worth remembering

Compile once, produce an `Image`, then instantiate as many independent
`Sandbox`es from it as you need — each with its own heap and its own state,
sharing only the compiled bytecode. This is the shape for a long-lived host
serving many requests: pay the compile cost once. And whatever authority a
sandbox has beyond pure computation comes from what the host grants it — see
[Capabilities and the sandbox](capabilities-and-the-sandbox.md) for the
conceptual model, which applies to an SDK-embedded sandbox exactly as it
does to a program run through the CLI.
