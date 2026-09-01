# 0036 — Capabilities are granted per workspace, and guarded per dependency

> **NOT BUILT — a proposal.** Nothing in this file exists yet. It is recorded
> now because the design leans on a notion of PROJECT that the SDK does not
> have, and that gap is the first thing to fix — see "What the SDK is missing".

## What is wrong with what we have

A capability today is an opaque value (`0022`) a host projects in, that a
program PRESENTS when it asks for a port (`0027`). That is the right shape for
the boundary between a host and a sandbox, and it is the wrong grain for
everything inside one.

It gives no way to say **which code** may use a capability. A program that opens
`fs` can hand the handle to any function it calls, including one from a
dependency it did not read. There is no unit of identity between "the whole
sandbox" and "this one value", so there is nothing to attach a policy to.

## The unit is the WORKSPACE

A workspace — a project, its `deps.edn`, its source roots — is the unit of
development and third-party identity. It is who wrote the code. Inside one
workspace there is nothing to defend: the author can already call their own
functions, so a boundary there costs indirection and buys nothing.

So a capability is granted to a workspace, and every module in it has it.

**`:flint/capabilities-grant`** in a dependency's coordinate grants that
dependency's code the named capabilities.

**`:flint/capabilities-guard`** on a project says the reverse: this project may
only be REQUIRED by a project that holds the named capabilities. A namespace in
a guarded project cannot be required from a project without the grant.

The two are not the same relation and both are needed. A grant is what a
dependency may DO. A guard is who may USE it. A pod that shells out needs both:
the grant to be allowed to, and the guard so that pulling it in is a decision
the requiring project made on purpose rather than one it inherited.

## Why this opens the door to babashka pods

A pod becomes an ordinary dependency with `:pod/version x`. What makes that
tractable is the guard, not the grant: we cannot regulate what a pod does
internally — it is a process with its own authority — but we can regulate WHO IS
ALLOWED TO DEPEND ON IT. A guard turns "this code can do anything" from a
property of the build into a decision a project takes and records.

That is a real and honest limit, stated rather than papered over: the guard is a
statement about the dependency graph, not a sandbox around the pod.

## What the SDK is missing, and it is the blocker

**The SDK has no notion of a project at all.** `Compiler.compile` takes
`{files}` — a FLAT MAP from path to source:

    {"clojure/core.cljc": "...", "my/app.cljc": "...", "dep/thing.cljc": "..."}

Every file is a peer. There is no root, no boundary, and nowhere to hang a
per-project anything. `collectSources` in `sdks/esm/src/flint.js` merges the
standard library and the caller's files into one map, and `flint.selfhost`
resolves requires out of it by namespace-to-path.

The CLI, by contrast, does have the notion and uses it correctly. `bin/flint`
keeps an ordered list of source ROOTS, `root-of` says which root a file came
from, and `tag-readers-for` reads that root's OWN `deps.edn` — beside it or one
directory up — never the compiling project's.

### The reader-tag precedent, and what it got right and wrong

`0035` needed exactly this and hit exactly this wall.

**Right:** the model. Tags are bound per project, apply only to that project's
own roots, and a dependency is read under ITS OWN bindings — so two libraries
can both want `#x` and using one is opt-in. The threading is per-source-root and
keyed on the root a file came from, which is the same key a capability grant
would use.

**Right:** it found that the value has to reach EVERY reader. `:tags` had to be
threaded to three of them — `collect`, `topo-order` and the compiler — and
`0035` records that a value only one reader knows about is a value the other two
get wrong. `default-features` had recorded the same defect before it. A
workspace-scoped anything will have the same shape and should expect the same
three call sites.

**Wrong, or rather unfinished:** it never reached the SDK. `0035`'s own order
lists "the SDK's equivalent option" as step 3, undone, and the code shows why it
stayed undone: `flint.selfhost` builds the compiler's `:sources` as
`{:src :file}` per namespace and drops `:tags` on the floor. So **every source
compiled through the SDK is read with no tag bindings at all** — not the
project's, not a dependency's. The CLI is correct and the SDK silently is not,
which is worse than either being wrong consistently.

So the honest assessment: the design was right, the CLI implementation was right,
and the SDK was left with a flat file map that cannot express the concept. That
gap is now load-bearing for a second feature, which is the moment to fix it
rather than route around it again.

## Order

1. **A project boundary in the SDK's input**, and it must be one concept both
   front doors share. Something like `{:projects [{:root "x" :files {...}
   :config {...}}]}` where the CLI's roots and the SDK's file groups are the
   same thing seen from two sides. `:tags` and any capability key hang off
   `:config`. This is the blocker and should land alone, with `0035` step 3
   completed on top of it as the proof that the shape works.
2. `:flint/capabilities-grant`, read from a dependency's own project file, the
   way tags already are.
3. `:flint/capabilities-guard`, checked when resolving a `:require` across a
   project boundary — the one new rule in the resolver.
4. Pods as a dependency kind, behind the guard.

## What is undecided

* Whether a grant is transitive. If A grants `fs` to B, and B requires C, does C
  have it? "No" makes the graph auditable and makes vendoring a chore; "yes"
  makes a grant a hole you cannot see the bottom of. Leaning **no**, with the
  guard as the mechanism that makes the refusal legible.
* What a capability NAME is. `0022` says the authority is an opaque value's host
  id and the runtime has no concept of a capability. A grant keyed on a name is
  a build-time concept that has to resolve to a run-time value, and where that
  binding happens is not settled.
* Whether the guard is checked at build time only. It is a statement about the
  dependency graph, so build time is where it can be enforced — but a loaded
  image (`0023`) arrives without one.
