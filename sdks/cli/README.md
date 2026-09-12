# @3sln/flint-cli

The flint compiler as a command line, on node.

```sh
npm install -g @3sln/flint-cli
flint run :path src :fn my.app/main
```

There is nothing else to install: no babashka, no JVM, no Rust toolchain and no
linker. The compiler is `flintc.wasm` — flint compiled by itself — and node
hosts it.

## Commands

```
flint run :path <dir> :fn <ns/fn> [:with [cap...]] [:args [arg...]]
flint compile :path <dir> :fn <ns/fn> [:to :wasm] [:out <file>]
              [:with [cap...]] [:optimize [perf]] [:meta k=v]
flint test :path <dir>
flint version
```

`:path` may be given more than once, or as a bracketed list. In zsh an unquoted
bracket is a glob, so quote it there: `:path '[src lib]'`.

`flint compile` writes a standalone `.wasm` module. It imports nothing and needs
no runtime beside it — any wasm engine will take it.

`flint test` runs every var marked `^:flint.check/test` under `:path`. The suite
is whatever is on the path; a test that nothing requires is still a test, so
nothing has to be registered.

### Not implemented here

`flint deps` (`add`, `tree`, `why`, `pin`, `bump`, `agree`) and pods. Both exist
on the native CLI. The dependency NAMESPACES are served here — a program can
call `flint.deps.npm` and friends — but the `deps` subcommand that drives
`flint.deps.resolve` and rewrites `deps.edn` is not packaged yet, and says so
rather than failing as an unknown command.

## Capabilities

A program granted nothing can reach nothing, and is told so rather than left
waiting. `:with` lends authority by name:

| grant | what it lends |
| --- | --- |
| `fs` | `flint.sys.fs`, read-only, rooted at the working directory |
| `fs:write` | the same, and `write-file`, `mkdir`, `delete` |
| `env:NAME,OTHER` | `flint.sys.env`; only the variables named |
| `slurp:https://example.com/**` | `flint.sys.slurp`; only those URLs |
| `deps` | `flint.deps.npm`, `.mvn`, `.git`, from the public registries |
| `deps:https://mirror/**` | the same, from your mirror and nothing else |

**A bare name grants an EMPTY allowlist**, except `deps`. `:with [slurp]`
reaches nothing, because the alternative is that it quietly means the whole
internet. `deps` defaults to the public registries because a resolver that
reaches nowhere is one nobody can use.

**Every path under `:fs` resolves under the root, and an escape is refused
rather than clamped.** `..` is refused rather than popped, so `a/../b` — which
normalises to somewhere inside the root — is refused too: popping would make
`a/../../x` depend on how deep `a` was, which is exactly the arithmetic an
attacker gets to do. The check never touches the filesystem, so it gives the
same answer whether or not the file is there.

On `compile`, `:with` **declares** rather than grants: the arguments arrive
later, so what a program needs is recorded in the artifact's metadata. flint
does not read it — that is this CLI's convention, and the host that loads the
module decides what to make of it.

## The same answer as the native CLI

`target/release/flint` is the other front door. The two run the same compiler
over the same spec, so the same project compiles to the **same bytes** —
verified for a plain compile, for `:optimize [perf]`, for `:with`/`:meta`
recorded in the artifact, and for a multi-root project with a capability guard
between the roots. `selftest.mjs` runs that comparison when the native binary is
built, and says so when it is skipped.

One difference, and it is deliberate: `run` here compiles to a module and
instantiates it, where the native CLI runs a bytecode image on its linked-in
runtime. See `DECISIONS.md#npm-cli`.

## Building it

```sh
sdks/cli/build     # runs bin/build-dist, copies the artifacts in, runs selftest.mjs
```

`dist/` is generated and not tracked. A distributable can only ever carry
artifacts built from the source beside it.
