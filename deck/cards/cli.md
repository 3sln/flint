# The CLI

`flint` is a single native binary — the compiler and the runtime embedded
together. It has three things it can do with a program (`run`, `compile`,
`test`), plus a dependency manager (`deps`). This is the exact usage text the
binary prints (`cli/src/main.rs`):

```
flint <version> -- the compiler, as one binary

  flint run :path <dir> :fn <ns/fn> [:with [cap...]] [:args [arg...]]
      Compile and run, here. Nothing is written: flint's runtime is compiled
      into this binary, so a program can be run without producing an artifact.

  flint compile :path <dir> :fn <ns/fn> :to :wasm [:out <file>]
                [:with [cap...]] [:optimize [perf]] [:meta k=v]
      Compile to a standalone module, for any host with a wasm engine. Here
      `:with` DECLARES rather than grants: it is recorded in the artifact's
      metadata, because the arguments arrive later and what a program needs
      has to survive until then.

  flint test :path <dir>
      Run every var marked `^:flint.check/test` under `:path`, and report.
      The suite is what is on the path; nothing has to be registered.

  flint version
```

A few things the usage text is explicit about:

- **`:with` lends capabilities; a program granted none can reach nothing.**
  Both `:with` and `:args` are conventions of *this* CLI, not part of the
  language — an [SDK](sdks.md) takes a function name and an argument list and
  nothing more.
- **`:optimize` is an ordered preference, not a boolean.** `[perf]` compiles
  every arity ahead of time (bigger, much faster on arithmetic); `[size]`
  interprets. Unrecognized tokens are ignored, so a script written for a
  newer flint still runs on an older one.
- **`:meta k=v` writes arbitrary metadata into a compiled artifact.** flint
  never reads it back; it's a convention the host or a later tool can rely
  on. `compile`'s declared capabilities are written this way.
- A value can repeat the key or use a bracketed list — `:path [src lib]` and
  `:path src :path lib` mean the same thing. In zsh, quote the bracket form:
  `:path '[src lib]'`.
- Only `:to :wasm` actually compiles today. Asking for `:to :llvm` or
  `:to :native` fails with an explicit message rather than a silent
  fallback: *"`:to :llvm` is not built yet: emitting a native artifact needs
  a linker, and this binary carries none. The native runtime itself IS
  built — it is what `flint run` uses — so the way to run natively today is
  `flint run`."* (This is the `cli` decision's design, partly shipped — the
  native binary, this command surface, and `deps.edn` work; a native
  cross-compiler and an "entry map" concept from that decision do not exist
  yet.)

## Capabilities: `:with`

`:with` is how a program gets any authority beyond pure computation — see
[Capabilities and the sandbox](capabilities-and-the-sandbox.md) for the
conceptual model. Each entry is a bare name or `name:allowlist`:

```console
$ flint run :path . :fn app.a/go :with [fs]
$ flint run :path . :fn app/main :with [slurp:https://example.com/**,file://exact.txt]
```

Known names, each backed by a virtual namespace the binary itself serves over
RPC (the `system-namespaces-and-deps` decision): `fs` (add `fs:write` to allow mutation — filesystem
access is rooted at the current directory; a path that escapes it, like
`../../etc/passwd`, is refused rather than clamped), `slurp` (read bytes from
a `file://` or `https://` URL), `net`, `env`, and `deps` (defaults to the
public npm/Clojars/Maven registries if given with no allowlist, since a
dependency resolver that can reach nowhere is useless to anyone). Three of
these — `slurp`, `net`, `env`, `deps` — take an allowlist of URL/name
prefixes (a trailing `**` matches any continuation); `fs` does not use the
allowlist mechanism and is either held or not, with `fs:write` as a separate
flag. **A grant may be narrowed at every hop and never widened** — the
invoker's `:with`, the project's `deps.edn`, and each dependency's own
declared grant each narrow the one above. Holding a capability with an empty
allowlist reaches nothing — that's deliberate, so a bare `:with [slurp]`
never quietly means "the whole internet."

## `flint deps`

```
flint deps add kind:name[@range]   npm:, mvn:, git:, pod:
flint deps tree                    what is reached, and how deep
flint deps why <dep>               why it is in the plan
flint deps pin                     pin every transitive
flint deps bump [:patch|:minor|:major]
flint deps agree [--apply]        git tags that disagree
```

`add`, `tree`, `why`, `pin`, `bump` and `agree` all resolve through
`flint.deps.resolve` — the same resolver a build uses — so what `deps add`
writes into `deps.edn` and what a build actually picks can't disagree.
`bump` refuses to cross a major version unless `:major` is given explicitly,
printing what it would have done instead of doing it silently. `agree` finds
git dependencies whose tag and pinned sha have drifted apart and, with
`--apply`, pins the agreed value into `:flint/overrides`.

## `flint test`

Runs every var under `:path` marked `^:flint.check/test` and reports
failures — this is what backs the `expect` assertions used throughout
flint's own test suite (see the real examples all through [The
language](language.md)) and is checked and stripped entirely by
`:optimize [perf]`, so a production build carries none of it.

## The development script, `bin/flint`

Separately from the native CLI above, `bin/flint` is a babashka script used
to build and self-host flint during development — it has its own, lower-level
flag syntax (`:src`, `:fn`, `:exclude`, `:wasm-path`, `--self`, `--disasm`)
and project-management subcommands (`tasks`, `build`, `fetch`) implemented in
`lib/flint/cli.cljc`. It's how the compiler bootstraps itself before the
native binary exists, and is development tooling rather than the end-user
interface described above.
