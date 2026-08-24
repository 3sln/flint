# 0020 — What a module says about itself, and shards

> **NOT BUILT YET.** Two requests that arrived together and turn out to be one
> mechanism: a module should declare what was built into it, and it should be
> possible to build a namespace as a loadable library rather than a program.

The ask, in the user's terms:

1. **The build-time opt-ins must be encoded in the module's metadata**, so a
   runner picking up a pre-built wasm can inspect it, decide how to spin up its
   glue, and tell whether it is compatible at all. In the program module *and* in
   pre-compiled namespace modules.
2. **The compiler should be able to build a "shard"**: an entry-point namespace
   compiled, bundled and tree-shaken into a **library** wasm module rather than a
   program — self-contained, loadable from the `:wasm-path` search path, carrying
   **no runtime implementation of its own** and leaning on the program module's.

## Where we actually are, because the second one is a change of kind

`doc/unit-format.md` already has most of the vocabulary, and it is worth being
precise about what today's units are, because a shard is not one.

A unit today is a **relocatable wasm object** (`.o` plus rlibs) with a sidecar
`.unit.edn` manifest. `rust-lld` links it into the program at **build time**.
Its `:abi {:runtime :value :image}` is checked before the compile starts, for
every unit on the path, and a mismatch is refused by name and version.

So two things are true:

- **The compatibility check already exists** — but it lives in a sidecar EDN file
  and is consumed by the compiler. Nothing is carried in the wasm itself, and a
  runner handed a finished `.wasm` can learn nothing from it. That is the gap in
  request 1.
- **Units are link inputs, not modules.** They are never loaded; they are linked.
  A shard is a *loadable* module that resolves against a running program. That is
  a genuinely new artifact, not a repackaging of the existing one.

The format's own principle — *"nothing in this format says built-in; a unit is
described by what it is"* — is what makes the extension clean: a shard should be
a **third `:kind`** alongside `:wasm-object` and `:bytecode`, not a parallel
mechanism with its own vocabulary.

## Part 1 — the metadata

### It goes in a custom section, and it must be readable without instantiating

wasm custom sections are ignored by engines and readable straight from the bytes.
A runner must be able to decide *whether to instantiate at all*, which rules out
anything reachable only through an export. `WebAssembly.Module.customSections()`
is the standard accessor on the web; elsewhere it is a few bytes of parsing.

Put it **early in the module**, so a streaming reader has it before the code
section — a runner deciding how to build its glue should not have to download a
megabyte of body first.

The sidecar `.unit.edn` does not go away; it is what the *compiler* reads at link
time. The custom section is what a *runner* reads at load time. Same facts, two
consumers, and the section should be generated from the manifest rather than
maintained beside it.

### Two classes of fact, and conflating them is the trap

This is the part worth getting right, because the obvious implementation — one
blob of build configuration, compared for equality — is wrong in a way that only
shows up once shards exist.

**Compatibility keys** must match or the module cannot be loaded:

- `:abi` as it exists today — runtime calling convention, value layout, image
  format
- the **memory model**: shared (threads, `0019`) or not. A shard built for a
  shared-memory program cannot load into a single-threaded one
- whether gas metering is **compiled into** AOT'd code (`0009`, `0013`), because
  that changes what the emitted code calls, not merely what it reports
- the heap layout the shard's code assumes, since a shard operates on the
  program's heap

**Capability descriptors** are what a runner inspects to wire itself up, and must
*not* gate compatibility:

- which host imports are required (`__host_continue__`, the event-queue drain,
  the document resource) — this is precisely "how to spin up the glue"
- whether diagnostics, snapshots (`0015`) or the profiler (`0017`) are present,
  so a tool knows what it can ask for
- what the module exports and with what signature: does it have `main`, and does
  `main` take the vararg string list

**The trap: a diagnostics build must not invalidate every shard.** Diagnostics
that only add side tables change nothing a shard's code depends on. If the
compatibility check is equality over one flat config blob, then turning
diagnostics on rebuilds the world for no reason. So the compatibility key should
be a **hash over the ABI-affecting subset only**, with everything else carried
descriptively beside it.

A hash rather than a version number because a version number is bumped by hand
and a layout change forgets. Carry both: the version for a legible error message,
the hash to catch the drift the version missed. `0016`'s two builds are the case
that proves it — they differ in diagnostics and must remain shard-compatible.

## Part 2 — shards

### The hard constraints

**A shard cannot own its linear memory.** Values live in the program's heap, so
the shard's code must operate on it: the shard **imports** memory rather than
defining it. This follows from `0001` — the heap is the VM's, and the shard is
running the VM's values.

**A shard should carry no static data in linear memory.** With an imported
memory, data segments would need relocating into space the program owns.
Avoid it entirely: a shard's constants are **built at init through the runtime's
allocator**, which is what the program does for its own image anyway.

**That init path is known-dangerous.** Building constants into the program's heap
at load is exactly the shape that produced the `run_program` bug — a value held
across a module initialiser that allocates. Whatever else shards get, they get a
test for that, and the standing GC checks stay on for it.

### The sharp problem: self-contained conflicts with identity

The user asked for a **self-contained** shard: bundle and tree-shake the entry
namespace's transitive dependencies into one module. For pure code that is fine.
For anything carrying identity or state it is not, and this needs stating plainly
rather than discovering later.

**Protocols are the polymorphism basis** (`0005`). A protocol is identity: a type
extended to *this* protocol object satisfies *this* one. If a shard privately
bundles a namespace that defines a protocol, and the program has its own copy,
there are two protocols with the same name and dispatch silently diverges — a
value extended on one side fails `satisfies?` on the other. The same argument
applies to namespace-level vars and atoms, and to anything else where two copies
are two identities rather than one behaviour.

So the rule:

> **A shard may privately bundle pure code. It must import anything that carries
> identity or mutable state.**

Which means the manifest and the custom section both need three lists, not one:

- **`:provides`** — namespaces this shard defines **canonically**. Two shards
  claiming the same namespace here is an error at load, not last-wins.
- **`:bundles`** — namespaces duplicated privately because duplication is
  harmless for them. Declared so a loader can *report* the duplication, and so
  the claim "this was safe to duplicate" is auditable rather than implicit.
- **`:requires`** — namespaces that must come from the program, because a second
  copy would be a second identity.

The compiler decides which list a dependency falls into; it already knows whether
a namespace defines a protocol or top-level mutable state. **That classification
is the real work in this feature** — the module format around it is
straightforward.

### What a shard is, concretely

- `:kind :wasm-module` — a third artifact kind in the existing unit format
- imports: the program's memory, the runtime ABI surface it uses, and the
  namespaces in `:requires`
- exports: an initialiser, and the vars of its `:provides` namespaces
- contains: its own compiled code plus its `:bundles`, tree-shaken from the
  entry namespace, and **no runtime implementation**
- found on `:wasm-path`, by namespace, exactly as units are today

## Naming

The request said `:wasm-src`; the shipped option is **`:wasm-path`** (`0004`,
superseding `:wasm-ld`). Keeping `:wasm-path` — it is in the README, the tests
and the unit format, and a shard is found the same way a unit is.

## What must be true before this is called done

- A runner can read the section from bytes **without instantiating**, and there is
  a test that it can.
- A shard built against an incompatible program is **refused by name and version**
  with a legible message, the way a bad unit already is — never loaded and left
  to trap.
- The two builds of `0016` remain shard-compatible with each other. If turning
  diagnostics on invalidates shards, the compatibility key is drawn wrong.
- A shard privately bundling a protocol-defining namespace is a **compile-time
  error**, not a runtime dispatch mystery.
- Two shards canonically providing the same namespace are refused at load.
