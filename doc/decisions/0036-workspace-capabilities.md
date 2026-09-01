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

A pod becomes an ordinary dependency with `:pod/version x`, and an ordinary
VIRTUAL NAMESPACE underneath -- the resolver flags it, the compiler wires it to
the system port, and the protocol below is what travels. A pod is one
implementation of that, not a shape invented for it, which is also the test of
whether the protocol is right: if a pod needs something it does not carry, it is
wrong.

What makes it tractable is the guard, not the grant: we cannot regulate what a
pod does internally (it is a process with its own authority), but we can regulate
WHO IS ALLOWED TO DEPEND ON IT. A guard turns "this code can do anything" from a
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

`reader` is one of two answers. The other is a **flag**: this namespace is
VIRTUAL. That is all the resolver says about it, and it is all the compiler needs
from the resolver -- enough to decide what to emit, and nothing more.

```text
namespace -> { workspace, identity, reader }     // source to compile
namespace -> { workspace, identity, :virtual }   // a flag
```

The resolver deliberately does NOT carry an invoke surface. Resolving happens
while compiling; invoking happens while running; a resolver that could do both
would have to exist in the shipped artifact, and it does not.

**The wire-up is on the system port.** Seeing the flag, the compiler emits code
that asks the system port for a port for that namespace, and compiles every form
targeting it -- or an alias into it -- into blocking port operations that request
the equivalent thing over that port.

That is `open` (`0027`): a sandbox cannot manufacture a port, it asks on the
system port and the host answers or refuses. So a virtual namespace costs no new
run-time mechanism. It also means requiring one IS requiring the authority to
reach it, which is what the guard above is for.

**Blocking is fine and is the point.** A port operation parks the green thread
and the scheduler runs something else; a call that parks is answered while it is
outstanding (`0025` step 5). A virtual call looks synchronous in the source and
is not synchronous in the runtime, which is the same trade every other port
operation makes.

## The protocol

Request/response over one port per virtual namespace, correlated by id --
which is exactly what `flint.rpc` already is (`client`, `call`, a reader thread,
`:id` correlation, `:error` thrown rather than returned as data). The protocol
below is what goes over it.

```text
->  {:op :invoke :var f :args [...]}     <-  {:body v}  |  {:error {...}}
->  {:op :get    :var x}                 <-  {:body v}
->  {:op :list}                          <-  {:body [{:name f :arities [...]}]}
```

`:list` is not needed to CALL anything; it is there so a build or a tool can ask
what a namespace holds. Whether the build asks is the open question below.

**It carries data.** Arguments and results cross a bridge, so they go through the
wire codec: a closure cannot cross one (`0006`, `0025`). Not a new rule, but a
virtual namespace is the first place it becomes visible in the language surface,
and the refusal should say so rather than leave it to be discovered.

### The one thing that has to be decided first: are the var names known?

The flag is enough to choose what to emit. It is not enough to know whether
`(vns/f x)` should compile at all, and that fork is worth taking deliberately.

**If the names are known at build time** -- declared in `deps.edn` beside the
`:pod/version`, or fetched once and cached -- then the compiler generates a stub
namespace: one `defn` per var, each an `rpc/call`. Three things follow, and they
are all good. `resolve-sym` keeps working untouched, so an unknown var stays a
COMPILE error with the message it has today. Arities are checked like any other
call. And there is no new emitter path at all -- the stub is ordinary flint
source, which is the same trick the check registry and the entry shim already
use, and which that code records as cheaper than a second path through the
emitter.

**If the names are not known** then every call site emits an inline request,
unknown-var becomes a RUN-TIME error in a language where it is otherwise a
compile-time one, and `vns/f` used as a value -- passed to `map`, say -- has to
synthesise a function with no arity to give it.

Leaning strongly to **known at build time, declared rather than fetched**:
fetching means a live process in the middle of a build, and the whole reason
this file exists is to make what a dependency can do a thing you can read.

### Open, on virtual namespaces

* Whether the port is opened at LOAD time by the stub namespace's initialiser,
  or lazily on first use. Load time is simpler and matches "initialisers run
  once"; lazy means a program that never calls into the pod never asks for the
  authority, which is a real difference for a guard.
* `:get` on a var: a snapshot taken when the port opens, or a read each time?
  Different semantics, and the interface should not leave an implementation to
  choose silently.
* **Macros.** A pod providing one means invoking at COMPILE time, from inside
  the compiler, which is the live-process-in-the-build problem again. Babashka's
  pods do not. Leaning no, stated rather than merely absent.
* A virtual namespace required transitively by something that does not know it
  is virtual. Indistinguishable at the call site is the point, but the guard has
  to be checked at every edge rather than only the first.

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
4. **Virtual namespaces**: the resolver's flag, the protocol above over one
   port per namespace, and the generated stub namespace that turns each var into
   an `rpc/call`. Independent of grants and guards -- a virtual namespace is
   useful without them -- but it is what makes a pod expressible. Decide the
   names question first; everything else follows from it.
5. Pods as a dependency kind: one implementation of the virtual interface,
   behind a guard.

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
