# 0004 — `:exclude` and `:wasm-ld`

> **Superseded in one detail:** `:wasm-ld` is now spelled **`:wasm-path`**
> (`doc/decisions/0005`, section 7). The old name is accepted as a deprecated
> alias for one release. Everything else below stands.

Two options on the CLI. Both are the composition system of 0003 becoming
user-facing, which is what that decision said to leave room for.

```
flint :src <dir> :fn <ns/fn> [:exclude [ns ...]] [:wasm-ld <dir> ...]
```

## `:exclude [ns ...]` — an ASSERTION, not just a pruning

Drop namespaces, **including built-in ones**, from the compilation.

The obvious reading is "leave these out". That reading has a trap in it: if
excluded code is genuinely reachable, leaving it out produces a module that
compiles, links, ships, and dies at runtime on a path nobody tested. A build flag
whose failure mode is a crash in production is a bad flag.

**So an exclusion is a claim, and the compiler checks it.** `:exclude [foo.bar]`
means *"nothing reaches `foo.bar`; tell me if I am wrong"*. If it IS reached,
that is a **compile error**, not a silent omission.

The error has to show the **reference chain** — which namespace required or called
it, and from where — or it is unactionable. "`foo.bar` is excluded but reachable"
sends somebody grepping; "`foo.bar` is excluded but reached by `my.app/handler`
→ `clojure.string/replace` → `flint.regex`" tells them what to do.

That framing makes the flag genuinely useful rather than dangerous:

- **Guarantee an absence.** "This module must not contain an XML parser" becomes
  something the build enforces, not something a reviewer eyeballs.
- **Find out what is dragging something in.** Exclude it and read the chain.
- **Keep a module small on purpose**, with a failure if a refactor quietly
  reintroduces the dependency.

Reachability is already computed for linking, so this is mostly a check over a
set that exists — plus the bookkeeping to remember *why* each namespace is in it.
Keep the predecessor edge when building the reachable set; it is what makes the
chain printable.

## `:wasm-ld <dir> ...` — precompiled namespace units on a search path

Where to find **precompiled wasm namespace units**, resolved by namespace exactly
the way `:src` resolves source: `flint.data.json` → `<dir>/flint/data/json.*`,
by directory hierarchy.

This is 0003's promise coming due. The unit format already describes a unit by
what it is — artifact, exported symbols, dependencies, compatibility metadata —
rather than by who shipped it, so a user-supplied unit and a built-in one are the
same kind of thing. This flag is what lets somebody point at theirs.

Three things to settle and write down:

- **Precedence.** If a namespace is available as both source and a precompiled
  unit, which wins? Either is defensible; silence is not. Decide, state it, and
  say which was used under `--stats`.
- **Compatibility.** A unit built against a different runtime ABI or image layout
  must be **refused by name and version**, not linked and left to crash. The unit
  format has metadata for this; use it.
- **The built-ins should go through the same path.** If `units/` is just the
  default entry on the search path, then there is one mechanism rather than two,
  and the user-supplied case is exercised by every compile you already run.

## What must be true at the end

- Excluding a namespace nothing reaches: succeeds, and the module is smaller —
  **assert the size drop in a test**, not just the exit code.
- Excluding a namespace something reaches: **fails, with the chain**, and a test
  asserts the message names the intermediate namespace rather than only the ends.
- A precompiled unit found via `:wasm-ld` links and runs, proved by a test that
  builds one, puts it on the path, and executes the result.
- An incompatible unit is refused with a reason.
- Both options documented in the README, `:exclude` explained as an assertion
  rather than a suggestion.
