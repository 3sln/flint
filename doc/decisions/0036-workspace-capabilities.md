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

## The shape: a namespace resolver

The SDK takes a **namespace resolver**, and it answers with a record rather than
a string:

```text
namespace -> { workspace, identity, reader }
```

* **`workspace`** — the namespace's workspace, and everything scoped to one:
  reader tags today, capability grants and guards next, whatever comes after.
  This is the thing that has no representation in the SDK at all right now.
* **`identity`** — the namespace's module identity: source path, and whatever
  else a diagnostic needs. It is what reader metadata is annotated WITH, so an
  error can say where a form came from.
* **`reader`** — a closure producing something the reader can pull from, rather
  than the source as one string.

Where there are no closures — the C ABI, and a host driving the runtime through
it — the same three members become an explicit `NamespaceResolver` implementation.
That shape is already established here: `Rt::bridge_hook` is a function pointer
the runtime installs, and the Rust SDK's `Driver` is a trait.

### A namespace does not have to have source

`reader` is one of two answers. The other is a **virtual namespace**: an
interface that lists its vars, invokes them, and gets their values. Same surface
a pod needs underneath, so a pod is one implementation of it rather than a
special case beside it.

```text
namespace -> { workspace, identity, reader }              // source to compile
namespace -> { workspace, identity, virtual }             // an interface
```

**The compiler needs the var list, and nothing else, to compile against one.**
`resolve-sym` refuses any symbol not in `cc[:vars]`, and `:vars` is populated by
reading each namespace's source. A virtual namespace populates the same map from
`list`, and every existing check -- unknown var, missing `:require`, the alias
message -- keeps working unchanged. That is the whole reason this fits: the
compiler already has one place where "what names exist here" lives.

**What differs is who binds the slot.** A var compiles to `VAR <slot>` either
way (`emitter.cljc`), and for an ordinary namespace an initialiser binds that
slot to the `defn`'s closure. A virtual namespace has no `defn`, so something
must bind it at load time to a stub that invokes across a boundary. The compiler
already generates synthetic namespaces for exactly this kind of job -- the check
registry, and the entry shim -- and records why: a generated namespace is
cheaper than a second path through the emitter, and it is ordinary flint a
person can read in `--explain`.

Tree shaking then works unchanged. A virtual var nothing reaches gets no stub,
for the same reason an unreached `defn` ships no code.

**Crossing it is crossing a bridge, so it carries data.** The stub's arguments
and its result go through the wire codec, which means a virtual namespace's
functions take and return DATA -- a closure cannot cross one (`0006`, `0025`).
That is not a new rule, but it becomes visible in the language surface for the
first time, and it should be said in the error rather than discovered.

**Reaching it at run time is a capability**, and this is where the two halves of
this file meet. Per `0027` a sandbox cannot manufacture a port; it is given one
or it asks on the system port. So a virtual namespace's stub can only reach its
implementation through a bridge the host granted -- which means requiring a
virtual namespace IS requiring the authority to reach it, and the guard is the
mechanism that makes that a decision rather than an inheritance.

### The split that is easy to get wrong

The resolver is a **compile-time** object. The invoke surface it describes is a
**run-time** one. The shipped artifact cannot hold the resolver -- it holds
stubs, and a way to reach what they stand for.

So `virtual` has to answer two different questions: what the namespace CONTAINS,
now, for the compiler; and how a running program REACHES it, later. Those are
not the same field and should not pretend to be.

### Open, on virtual namespaces

* **Arities.** If `list` gives them, the compiler checks a call like any other.
  If it gives only names, wrong-arity becomes a run-time error in a language
  where it is otherwise a compile-time one. Leaning: require arities, and let an
  implementation say "variadic, unchecked" explicitly rather than by omission.
* **`get` on a non-function var**: a snapshot taken at load, or a live read each
  time? They are different semantics and the interface should not leave it to
  the implementation to decide silently.
* **Macros.** A pod providing a macro would mean invoking the interface AT
  COMPILE TIME, from inside the compiler. Babashka's pods do not, and allowing
  it would put a live process in the middle of a build. Leaning no, stated.
* Whether a virtual namespace can be `:require`d transitively by something that
  does not know it is virtual. It should be indistinguishable at the call site
  -- that is the point -- but the guard has to be checked at every edge, not
  only the first.

### Why this is the fix and not a refactor

**The COMPILER enforces the guard, at require-resolution time.** It has both
halves there: the requiring namespace's workspace and the required one's, each
from the same resolver. So the check is local, and neither front door can be
correct while the other is not — which is exactly how `0035` went wrong.

Everything workspace-scoped stops being something the CLI knows and the SDK does
not. Reader tags become one field of a record both front doors produce.

### What it costs, from the code

**The reader is a whole-string cursor.** `make-state` is
`{:s s :i 0 :n (count s)}` and every `peek`/`next` indexes into it. A `reader`
that does not hold the file resident means changing the reader's substrate to a
pull source. That is a real change, not an interface tidy-up, and a rope does
not get us out of it: a rope is still resident.

Worth doing anyway, and worth doing for its own reason rather than this one --
but it should be costed separately, and it does not block grants and guards. A
first cut can hand back a whole string behind the same interface.

**The compiler runs inside a wasm module, so a resolver is a CALL BACK OUT.**
Today `Compiler.compile` marshals every source into one EDN blob and hands it
over in a single `flint_call`. A resolver function means the guest asks the host
for a namespace mid-compile.

That was not possible when `0035` was written. It is now: a sandbox has a system
port, a call is a message, and a call that parks is answered while it is
outstanding (`0025` step 5, `0027`). The compiler asking its host to resolve a
namespace is exactly that shape. The port work is what makes this design
buildable, which is worth saying because it also means it should be built on
that machinery rather than beside it.

**The resolver will be asked for the same namespace more than once.** `0035`
records that `:tags` had to reach THREE readers -- `collect`, `topo-order` and
the compiler -- and that a value only one of them knows about is a value the
other two get wrong. A resolver has the same exposure: either it must be
idempotent and cheap, or the compiler must ask once and carry the record through
all three. **Carry it once** is the answer that does not depend on the host
being careful.

### Open, in this shape

* Which workspace `clojure.core` and the standard library are in. Everything
  refers them implicitly, so it must be one that grants nothing and guards
  nothing, and that should be stated rather than emergent.
* Whether `identity` is the same thing `0020`'s module metadata records, or a
  compile-time-only notion that happens to overlap.

## Order

1. **The namespace resolver**, as above: one concept both front doors produce,
   answering `{workspace, identity, reader}`. The CLI's source roots and the
   SDK's file map become two ways of building the same resolver. This is the
   blocker and should land alone, with `0035` step 3 -- reader tags through the
   SDK -- completed on top of it as the proof the shape works. A first cut may
   hand back a whole string from `reader`; the incremental source is a separate
   piece of work with its own reason.
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
