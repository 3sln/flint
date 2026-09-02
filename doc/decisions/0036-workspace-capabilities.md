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
VIRTUAL NAMESPACE underneath -- the resolver flags it, the compiler emits
`flint.virtual/call`, and the protocol below is what travels. A pod is one
implementation of that rather than a shape invented for it, which is also the
test of whether the protocol is right: if a pod needs something it does not
carry, it is wrong.

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
from it -- enough to decide what to emit, and nothing more.

```text
namespace -> { workspace, identity, reader }     // source to compile
namespace -> { workspace, identity, :virtual }   // a flag
```

The resolver deliberately carries no invoke surface. Resolving happens while
COMPILING, invoking happens while RUNNING, and a resolver that did both would
have to exist in the shipped artifact. It does not.

### What the compiler emits

A call to a library. Seeing the flag, a form targeting that namespace -- or an
alias into it -- becomes:

```clojure
(vns/f x y)   =>  (flint.virtual/call 'the.ns/f x y)
vns/x         =>  (flint.virtual/get  'the.ns/x)
vns/f         =>  (flint.virtual/fn   'the.ns/f)   ; used as a value
```

**And that is the whole of the compiler's part.** No stub namespace to generate,
no emission path to add, no port machinery in the emitter: an ordinary call to an
ordinary function, with a quoted symbol naming what is wanted. The symbol is a
constant in the image and carries the name into any diagnostic.

The third form matters as much as the first. `(map vns/f xs)` has to work, and a
virtual var used as a value needs a callable to hand over rather than a call to
make.

### What the library handles

`flint.virtual` does the namespace-to-port resolution, and the waiting,
memoisation and laziness dance that goes with it:

* **Resolution.** One port per virtual namespace, obtained with `flint.port/open`
  under the namespace's name -- which is a request on the system port (`0027`),
  so the host answers or refuses and nothing is manufactured inside.
* **Memoisation.** Opened once and cached, so a thousand calls into one pod cost
  one port.
* **Laziness.** Opened on FIRST USE rather than at load. This is the better
  answer to a question the previous draft left open: a program that never calls
  into a pod never asks for the authority to reach it, which is exactly the
  property a guard is supposed to give.
* **Waiting.** `flint.rpc` over that port: request/response correlated by id, a
  reader thread, `:error` thrown rather than returned as data. It exists.

Being a library is the point. It is ordinary flint in `lib/flint/virtual.cljc`,
testable on its own, and it is a namespace UNIT like any other -- so a program
that touches no virtual namespace links none of it, and the pure-module floor is
untouched.

One consequence to be deliberate about: a namespace that uses a virtual one
acquires an implicit `:require` on `flint.virtual`, the way every namespace
implicitly refers `clojure.core`. It comes from the standard library rather than
from anyone's workspace, and the resolver has to answer for it.

## The protocol

Over one port per virtual namespace, correlated by id.

```text
->  {:op :invoke :var f :args [...]}     <-  {:body v}  |  {:error {...}}
->  {:op :get    :var x}                 <-  {:body v}
->  {:op :list}                          <-  {:body [{:name f :arities [...]}]}
```

`:list` is not needed to call anything; it is there so a build or a tool can ask
what a namespace holds.

**It carries data.** Arguments and results cross a bridge, so they go through the
wire codec: a closure cannot cross one (`0006`, `0025`). Not a new rule, but a
virtual namespace is the first place it becomes visible in the language surface,
and the refusal should say so rather than leave it to be discovered.

### The var list is OPTIONAL, and that is the whole answer

The resolver may answer with the namespace's vars and their signatures, and may
answer without them:

```text
namespace -> { workspace, identity, :virtual }
namespace -> { workspace, identity, :virtual, vars [{:name f :arities [...]}] }
```

**With them**, the compiler puts them in `cc[:vars]` and every existing check
works untouched: an unknown var is a COMPILE error with the message
`resolve-sym` already produces, a wrong arity is caught where every other wrong
arity is caught, and `--explain` can show what a program actually reaches inside
a pod.

**Without them**, any symbol in that namespace resolves, `flint.virtual/call` is
emitted, and both of those become run-time errors -- in a language where they are
otherwise compile-time ones.

Note what does NOT change between the two: the emission. Identical either way,
which is what makes the library shape worth having. The var list buys checking,
not codegen.

### Where the list comes from, and it is the same question asked twice

`:list` in the protocol above IS this request. A build that wants the checking
boots the pod, opens its port, sends `{:op :list}`, and compiles against the
answer -- and babashka's pods already support exactly that, which is why the
option is worth having rather than theoretical.

So there is one way to ask what a namespace holds, and the only difference is
WHEN. That is worth more than it sounds: a second, build-only description format
would be a second thing to keep true.

The alternative source is a declaration in `deps.edn` beside the
`:pod/version` -- no process, and true only as long as somebody maintains it.

Both are legitimate and the project picks. A build that boots a pod is a build
with a live process in it, which is a real cost and should be a choice made in
the open rather than a default; the answer should be cached under `.flint` like
a fetched dependency, so it is paid once rather than per build.

### It has to be legible which mode a build was in

The same mistake is a compile error or a run-time error depending on what the
resolver could tell the compiler. That is defensible -- you get more checking if
you can afford to boot the pod -- but only if a program can say which it got.
The build should name the virtual namespaces it CHECKED and the ones it took on
trust, rather than leaving the difference to be discovered when one of them
throws in production.

### Signatures are partial, and should say so

A pod's `describe` gives names and metadata; arities are not always in it. An
implementation that does not know a var's arities should say so explicitly --
"variadic, unchecked" -- rather than by omitting the field, so that "no arity
information" and "takes no arguments" cannot be confused.

### Open, on virtual namespaces

* `:get` on a var: a snapshot taken when the port opens, or a read each time?
  Different semantics, and the interface should not leave an implementation to
  choose silently.
* **Macros.** A pod providing one means invoking at COMPILE time from inside
  the compiler -- which is a bigger step than asking `:list`, even though both
  need the pod booted: `:list` reads a description, a macro runs the pod's code
  and lets its output into the program. Babashka's pods do not. Leaning no,
  stated rather than merely absent.
* A virtual namespace required transitively by something that does not know it
  is virtual. Indistinguishable at the call site is the point, but the guard has
  to be checked at every edge rather than only the first.
* Whether `flint.virtual/call` should be told the ARITY it was compiled for, so
  a pod that changed under a built artifact fails with something better than an
  argument-count mismatch from the far side.

## `open` stops carrying its weight

`flint.port/open` asks the host for a thing, presents what the caller holds, and
gets a PORT back or a refusal. Under this design the presenting half moves into
the capability system and the port-shaped half is one answer among several. What
is left is a request to the host on the system port, which is what `port_open`
already is underneath -- an `EV_OPEN` with an encoded payload and a parked
thread.

So: **one primitive that issues a request to the host over the system port**, and
a port is one possible answer rather than the only one. `open` becomes a thin
thing over it, or goes.

That is a simplification of what exists rather than new machinery: the request,
the token, the park and the answer are all built (`0027`). What changes is that
the answer stops being constrained to a port.

## Capabilities on namespaces and macros, and `&capabilities`

A built-in namespace should be able to say what it needs -- `flint.fs` requires
`:fs` -- and so should a macro, since a macro is where the wrapping happens. The
compiler merges what is declared and what the workspace was granted into the
compilation context, and injects the result as `&capabilities`.

The reason a compile-time check alone is not enough is worth stating plainly: it
is invisible to the HOST. The host sees a request arrive on the system port and
has to decide; it cannot see the build that produced the caller. So something has
to travel from the build to the host, and that something is the token.

### Two questions that look like one

Conflating these is the trap in the whole area.

**Composition: may this code call that code?** That is the GUARD, checked at the
`:require` edge, at compile time, between workspaces. By the time B can call A's
function or expand A's macro at all, B has satisfied A's guard -- so the token is
not defending against B here, and does not need to.

That also settles a question that would otherwise be nasty: a macro from A,
expanded in B, emitting A's capability. It is fine, because it is A choosing to
export a macro that does that -- exactly as A may export a FUNCTION that does it.
Closure capture of authority is delegation, and delegation is A's decision.

**Authorisation: may this program do that thing?** That is the TOKEN, checked by
the host, at run time. Nothing about the build reaches the host, so the program
must present something.

### Two layers, and only one of them is the host's business

The earlier draft of this section said a token must never be compiled into the
artifact, because that makes it a bearer token. That is true of one layer and
wrong about the other, and the distinction is the whole design.

**Intra-sandbox: may THIS namespace call that capability-gated function?** This
is a composition question inside one program. The host has no view of it, cannot
answer it, and should not be asked -- a round trip per invocation would be
absurd. It has to be answered inside the sandbox, in constant time.

**Host-facing: may this program touch the real filesystem?** Only the host can
answer, and it answers ONCE, when the capability is acquired -- not per call. The
cost objection does not apply here.

So the artifact carries what settles the first, and the host issues what settles
the second, and **the two must never be the same value**. A token that is both is
a bearer token: copy the `.wasm`, copy the authority. Keeping them separate is
what makes baking safe.

### Why a compile-time-only check cannot work

Because anything a macro emits can be written by hand. If `(fs/read p)` expands
to a form that a hostile namespace can read in `--explain` and type out itself,
the check has moved from the compiler into the source, where it is not a check.

So the emitted code must carry something the hand-writer cannot produce. That is
the argument for a run-time token, and it is correct.

### Sentinel, not secret -- and the reason is specific

Given "a sentinel that cannot be hydrated by the guest, or a secure random
token", the sentinel wins, and not on general principle.

**A secret is defeated by reading the artifact.** The adversary's code is
compiled INTO the same artifact that holds the token. Bake a random integer, and
a hostile namespace reads it out of the `.wasm` and writes the same integer
literal. Baking a secret next to the code that must not have it is not a hiding
place.

**A sentinel cannot be spelled.** There is no reader syntax for an opaque value
and no constructor that takes an id: `flint/opaque` hardcodes 0 (`builtins.rs`),
which is exactly why 0 must never be issued. So a hostile namespace that has READ
the artifact still cannot produce one, because the thing it would have to write
down has no written form.

Three supporting properties, all already true:

* **Identity is the object, not its bytes.** The check is a pointer compare:
  constant time by construction, with no value comparison to get wrong.
* **Provenance is unreadable from guest code.** No builtin returns an opaque's
  host id -- deliberately, and `builtins.rs` says so at the definition. Holding
  one teaches you nothing about how to make one.
* **No reflection.** There is no builtin that enumerates vars or resolves one by
  name, and `native-name` shows why that matters: any source may write
  `flint.rt/<name>` for anything in the catalogue, so a reflective lookup added
  to the catalogue would be reachable from hostile source immediately. Not adding
  one is now load-bearing rather than incidental.

### Better than either: the artifact carries SLOTS, not values

Pushed one step further, the artifact need not contain a secret at all.

A constant-pool entry says *"sentinel #3"*, and the LOADER mints the actual
opaque -- with the runtime's authority, not the guest's -- when the image loads.
Every reference to #3 in that artifact resolves to the same object; identity is
established per run.

What this buys:

* **Reading the `.wasm` yields nothing usable.** It tells you there are four
  sentinels. It does not give you one, and there is no way to write one down.
* **Nothing is a bearer token**, because nothing in the file IS the authority --
  the authority is an object that exists only inside a run.
* **The check stays a pointer compare.** No hashing, no comparison of secrets, no
  timing surface.
* **The image format gains no way to express a host id**, so the mintable
  serialisation `0022` forbids is still not expressible.

The constant pool would gain one tag for this, next to `[:tagged ...]`. That is a
real format change and should be costed, but it is a small one and it is the
piece that makes capability checking a purely internal, constant-time concern.



### Can a macro hand a live opaque to the runtime? No, and the compiler already says so

This is a REPRESENTATION question before it is a security one, and the code
answers it.

A macro runs at compile time and returns a FORM. That form is analysed and
emitted, and any literal in it becomes a constant-pool entry. `image/const`
accepts nil, booleans, ints, doubles, strings, keywords, symbols, vectors, sets,
maps, lists and tagged literals, and everything else is:

```clojure
:else (throw (ex-info "not a constant" {:v v :type (type v)}))
```

There is no opaque constant and no port constant. A macro that tried to put a
sentinel in its output would fail the compile, today, with that message.

**And a HOST-ISSUED opaque must stay unwritable.** An image is bytes on disk, so
adding `[:opaque host-id label]` to the pool would write the authority down --
and anything that can write those bytes can mint one, which is exactly the
integer-to-capability conversion the sandbox exists to forbid. Same hazard
`decode_guest` refuses `K_SENTINEL` for, by a different road; `0022` states it as
*"accept those bytes back and it is mintable, which is the entire property
gone."*

The slot tag proposed above is not that, and the difference is the whole reason
it is safe: a slot entry says *"sentinel #3"*, carries no id, and is minted by the
LOADER. Nothing about the authority is in the file, so there is nothing in the
file to forge. Adding `[:sentinel n]` is safe for precisely the reason adding
`[:opaque id label]` is not.

Serialising also loses the thing the question was trying to keep. Reconstructing
an opaque from bytes produces a NEW object; the identity that made it worth
having does not survive the round trip. So serialising costs the typing AND opens
the minting -- there is no version of it that pays.

### So the handoff is by REFERENCE, which is what vars already do

The macro emits a reference -- a capability name the compiler resolves to a
sentinel slot, or the slot directly -- and the loader supplies the value:

```clojure
;; the macro emits data -- a keyword names what is wanted
(flint.cap/of :fs)
;; and at run time that is a lookup in the table the HOST populated
```

The type is not lost, because the value never becomes data: only the name does.
Nothing is serialised, so nothing is mintable. And the emitted form is ordinary
data, so it goes in the constant pool like any other keyword.

This is exactly the var mechanism, and the resemblance is not a coincidence.
`(defn f ...)` does not put the closure in the constant pool -- it puts a SLOT
INDEX, and an initialiser binds that slot at load. A capability is the same
shape: the compiler emits a name, and something at load binds it to a value the
compiler never held.

Which suggests the cheap implementation, if it is wanted later: give capabilities
slots the way vars have them, and `&capabilities` lookup becomes a slot read
rather than a map lookup.

### The table is PER NAMESPACE, not one merged set

An earlier draft of this file said `&capabilities` was "the merged capability set
for the compilation context". That is too flat and it is wrong.

Grants are per workspace, and one program contains many workspaces. So two
namespaces in the same program legitimately see different capability sets, and a
single global table cannot express that -- it would either be the union, which
hands every workspace everything, or the intersection, which is useless.

The table belongs to the NAMESPACE. Which is again the var shape: vars are
per-namespace and bound at load, and capability slots can be exactly that.

### Why it must not be `*ns*`, or any other dynamic var

The tempting spelling is Clojure's: hang `:workspace {:capabilities {...}}` off
`*ns*`, which during macroexpansion is the namespace being compiled, so a macro
picks up its caller's table for free.

Two things are wrong with it here.

**flint has no `*ns*`.** There is no runtime namespace object -- flint compiles
ahead of time and namespaces do not exist as values at run time. Adding one
solely to carry this table would be inventing a runtime concept to hold
something the compiler already knows.

**And dynamic scoping is backwards for authority.** flint's dynamic vars are real
(`^:dynamic`, `binding`, a per-green-thread binding map, `dyn-get` reading
through it), so this is not hypothetical:

* A function from workspace A, CALLED from B, would read B's binding. A would
  lose its own authority the moment it was called from somewhere else. Authority
  has to travel with the code that was granted it -- lexically -- not with
  whoever calls it. This one is fatal on its own.
* A caller could SUBSTITUTE. Rebind so that a library asking for `:net` is handed
  the caller's `:fs` token; the library then presents it to the host believing it
  is something else. No privilege escalation -- you can only substitute tokens
  you already hold -- but a confused deputy, and an avoidable one.

### What keeps the conciseness without the dynamic scope

The macro still emits a reference and never holds a token; the difference is that
the COMPILER resolves it, against the namespace it is expanding in:

```clojure
(fs/read path)   =>   (fs/read* <cap :fs of this namespace> path)
```

Lexical, static, resolved at compile time to a per-namespace slot that the host
binds at load. Nothing is rebindable, nothing is dynamic, nothing is looked up
by a name a caller controls -- and the source is as short as it would have been.

The macro's compile-time knowledge is the same knowledge either way: which
capability names its namespace has. It just does not need a runtime var to reach
them.

### Two things were being called `&capabilities`

Separating them is what makes the above work:

* **At compile time**, the set of capability NAMES available in the namespace
  being compiled. Ordinary data, safe in a form, safe in the pool. This is what
  a macro branches on when it wants compile-time knowledge of what is
  available.
* **At run time**, the mapping from those names to host-issued sentinels, PER
  NAMESPACE. A live table, populated over the system port, never serialised and
  never in the image.

A macro reads the first and emits a reference into the second. It never holds a
sentinel, so the question of handing one over does not arise.

## Guard the REFERENCE, not the invocation

This is the answer to "must every call carry the calling workspace?" -- no, and
the reason removes most of the machinery above.

### Two things a guard could mean

* **Authority to act**: what the callee may do. LEXICAL. It travels with the code
  that was granted it and the caller has nothing to do with it. A library called
  from anywhere keeps its own authority, which is the property the dynamic-var
  shape lost.
* **Permission to call**: who may invoke this. A property of the CALLER, and the
  thing `:flint/capabilities-guard` on a function means.

Only the second needs caller identity, and only at the point the caller is
identified -- which is not the invocation.

### The closure case dissolves

The worry is real: `(map fs/read paths)` hands a guarded function to `map`, which
lives in another workspace, and `map` invokes it. Checking at the invocation
checks `clojure.core`, which is wrong.

But **the reference `fs/read` appears in the caller's source, in exactly one
workspace, at compile time.** Guard it there. Once the reference is allowed, the
resulting closure is an ordinary value, and `map` calling it is fine -- the
caller chose to hand it over, which is delegation, and delegation is allowed
everywhere else in this design for the same reason.

So:

* Every reference to a guarded var has a static site in one workspace.
* Obtaining the closure is the guarded act; calling it is not.
* Nothing is threaded, nothing is passed, and a builtin call costs exactly what
  it costs today.

The only ways to obtain a reference without a static site are reflection -- which
does not exist, and now must not be added -- or being handed one, which is
delegation.

### Which means the run-time check is nearly free, and nearly unnecessary

The argument for a run-time token was that anything a macro emits can be written
by hand: read the expansion, type it out, bypass the macro. That holds only if
the thing the expansion reaches is itself unguarded. If `fs/read*` and the raw
builtin under it are guarded vars too, then the hand-written bypass is refused at
compile time exactly as the macro path would have been. **Guard every rung and
the ladder has no unguarded rung.**

And the sentinel-slot scheme does NOT defend against the case it looked like it
did. A hostile artifact is not compiled by an honest compiler, so it never had a
reference checked -- but it also writes its own constant pool, so it declares
whatever slots it likes and the loader mints them. Slots minted from the file
defend against nothing the compiler was not already defending against.

### What the load-time binding is actually for

It earns its place once the loader binds slots from what the HOST granted, rather
than from what the file asked for:

* A slot is not "mint object #3", it is "bind the `:fs` capability here, if this
  artifact's workspace was granted it".
* A hostile artifact declaring the slot gets nil, because the host never granted
  it, and the guarded primitive's one pointer compare fails.
* This is once per load, not per call.

That covers the case compile-time checking cannot: an image loaded at run time
(`0023`, `flint_load_image`) whose call sites were never checked by a compiler
anyone trusts.

### So the shape is

| when | what | cost |
|---|---|---|
| compile | the reference to a guarded var is checked against the referencing WORKSPACE's grants | none at run time |
| load | the host binds each workspace's capability slots from what it granted | once |
| run | a boundary primitive compares its slot | one pointer compare, at the boundary only |

No workspace is passed anywhere. No call site changes. A closure is an ordinary
value again, because the guard was spent when it was obtained.

### What the reference compiles to

A cross-workspace reference to a var that DECLARES requirements becomes,
in shape:

```clojure
(resolve 'the.ns/the-var :capabilities (select-keys &capabilities [:fs]))
```

This unifies the two halves rather than stacking them: you cannot resolve the
var without holding what it requires, and resolving is what hands the tokens
over. The guard and the authority are the same act.

`select-keys` is exactly right for the check, too -- it OMITS missing keys, so a
workspace without `:fs` produces `{}` and the requirement is visibly unmet rather
than met with a nil.

### `&capabilities` is not a secret, and that is fine

An earlier section here said guest code must never reach the table. That was too
strong. `&capabilities` is lexical -- it means "this workspace's capabilities",
the way `&env` means "this expansion's environment" -- so a guest writing it gets
ITS OWN set and learns nothing it did not already have. There is no hole to
close, and no need for the table to be unnameable.

What a guest can then do is pass its own tokens to a workspace that lacks them.
That is delegation, which is allowed everywhere else here for the same reason it
is allowed here.

### Three things that keep it free

**Elide the empty case.** A var that declares no requirements emits no resolve.
That is the overwhelming majority -- `str`, `map`, `+` -- and it matters more
than it sounds, because the standard library is a different workspace from user
code, so without this every call into `clojure.core` would pay.

**Hoist to load.** A workspace's capabilities do not change during a run, so the
`select-keys` and the check happen ONCE, at load, into a slot. The reference site
is then a slot read -- which is what a var reference already is. Nothing is
recomputed per call and nothing is recomputed per reference.

**Fail at load, naming the capability.** A requirement that cannot be met should
stop the program before it starts, which is a better failure than the first call
in production. It does foreclose graceful degradation; if a program should be
able to run without an optional capability, that has to be a different construct
rather than this one failing softly.

### The one spelling that would undo all of it

**`resolve` must not be a callable builtin.**

`native-name` in `analyzer.cljc` lets any source write `flint.rt/<name>` for
anything in the builtin catalogue. So a builtin `resolve` taking a quoted symbol
is a general reflective var lookup, and a hostile namespace writes:

```clojure
(flint.rt/resolve 'flint.fs/read {})
```

-- obtaining the var with no compile-time check, which is precisely the
reflection hole that reference-guarding depends on not existing. It would defeat
the entire scheme, by the same route the `*ns*` shape would have.

So the form above is a NOTATION for what the analyzer does, not a call it emits.
It has to be an analyzer construct with no builtin and no var behind it, lowered
at compile time to the load-bound slot described above. Which is where the
hoisting lands anyway -- the two constraints agree.

This is now the third time in this file that a natural spelling has been the
dangerous part of an otherwise correct idea. Worth stating as a rule: **anything
that resolves authority must be a compile-time construct, never a callable.**

### Per WORKSPACE, and per-namespace was a mechanism mistaken for a concept

The grain is the workspace: that is the unit of third-party identity, and inside
one there is nothing to defend. An earlier section here said "per namespace",
which is right as an IMPLEMENTATION -- a namespace belongs to exactly one
workspace, statically, so a per-namespace slot is just where a workspace's grant
is reached from -- and wrong as a concept, because it suggests two namespaces in
one project could differ. They cannot and should not.

### The reader-conditional hazard, if the declaration hides in one

Putting the declaration behind `#?(:flint ...)` for `.cljc` portability is
reasonable on its face and has a real edge. `bin/flint` records it: *"a
conditional that matched nothing DELETED the form it stood in -- a function body,
or a `:require` this loop is here to find. Silently compiling a mutilated library
is the failure this note exists to prevent."*

A guard that can be elided by a feature set is not a guard. If the declaration
lives inside a conditional, a build with different `:features` drops it silently
and the module compiles without the requirement it was written with. Either it
goes somewhere the reader cannot elide -- the `ns` form proper, or `deps.edn` --
or an elided capability declaration has to be an ERROR rather than the note that
elision is today.

### Open, on capabilities

* Whether the compile-time NAME SET is a macro's definition site or its use
  site. Definition site matches "A's macro knows what A was granted"; use site
  matches "the compilation context", which is what the name says. They differ
  exactly when A's macro is expanded in B, which is a case the guard has already
  authorised -- so definition site looks right, and the name may be what is
  wrong. The run-time table does not have this question: there is one per
  program.
* Whether a declaration is a requirement (refuse to run without it) or a request
  (run, and fail at the call). A requirement is checkable at load and is the
  better failure; a request allows a program that degrades.
* What the host answers when a capability is declared and not granted: absent
  from `&capabilities`, or present and refusing. Absent is simpler; present-and-
  refusing lets an error say WHICH capability was wanted.

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
4. **Virtual namespaces**: the resolver's flag, `flint.virtual` over
   `flint.port` and `flint.rpc`, and the three forms the compiler emits into it.
   Independent of grants and guards -- a virtual namespace is useful without
   them -- but it is what makes a pod expressible.
5. **The optional var list**, through the protocol's own `:list`, and the build
   output that says which virtual namespaces were checked and which were taken
   on trust. Separable from 4: it buys checking, not codegen.
6. Pods as a dependency kind: one implementation of the virtual interface,
   behind a guard.
7. **The request primitive**, generalising `port_open` so its answer is not
   constrained to a port, and `open` retired onto it.
8. **Reference guards**: `:flint/capabilities-guard` on a var, checked where the
   var is REFERENCED, against the referencing workspace's grants -- an analyzer
   construct lowered to a load-bound slot, never a callable. Compile time, no
   run-time cost, no workspace threaded anywhere. This is most of the feature.
9. **Load-time slot binding**, so an image the compiler never checked
   (`flint_load_image`, `0023`) still cannot help itself: the host binds each
   workspace's slots from what it granted, and a boundary primitive compares
   one. Once per load, one pointer compare per boundary crossing.
10. **The host-facing half**: what the host issues and the program presents when
    it touches the world -- acquired once, never the same value as a slot
    sentinel, or the artifact becomes a bearer token.

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
