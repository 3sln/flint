# 0003 — A namespace is a compilation unit, and linking composes them

**Supersedes the mechanism half of 0002.** The requirement there stands — only
reachable code ships, built-ins included — but both routes it proposed are worse
than this one, which came from the owner:

> a pre-compiled wasm module for each built-in namespace
>
> If we have that, then we have a nice wasm composing system already in case we
> later want to support independently compiling namespaces.

That second sentence is the important one. This is not a tree-shaking trick; it
is a **composition system**, and the built-ins are just its first customer.

## The mechanism

Every namespace is compiled ahead of time into its own **relocatable wasm
object**. `flint` computes the reachable set from `:fn` and hands only those
objects to `wasm-ld --gc-sections`, which emits **one module**.

Compiles stay fast because there is no `rustc` on the path — only a link, which
is milliseconds to low hundreds of milliseconds. And the output is a single
module, so the "runnable anywhere" contract survives intact: no host-side module
wiring, no Component Model dependency.

Verified on this machine: `wasm-ld` at `/opt/homebrew/bin/wasm-ld`, and
`rust-lld` in the nightly toolchain. If emitting relocatable objects from Rust
needs nightly, say so plainly rather than quietly requiring it.

### Rejected, and why

- **Rebuild the runtime per compile with cargo features.** Certainly correct, and
  it puts a full Rust build on every compile. Linking gets the same result for a
  fraction of the cost.
- **One prebuilt runtime, patcher nulls dispatch-table entries, then wasm DCE.**
  Clever, and it rests on a discipline nothing enforces — the day something calls
  an optional function directly, it silently stops working.

## The crux: the registry must be assembled BY THE LINKER

This is the detail the whole thing turns on, and it is easy to get wrong.

If the runtime holds a static table naming every builtin, **every builtin is live
and `--gc-sections` removes nothing**. The table itself is the reference that
keeps them all alive — which is exactly the problem 0002 described.

So the registry cannot be written by hand in one place. **Each namespace object
contributes its own registration entries into a dedicated linker section**, and
the runtime walks that section at startup. Link a namespace and its entries
appear; leave it out and they do not exist. The linker becomes the thing that
decides what the registry contains.

Get this right before layer 11, because it is a property of how the runtime finds
its builtins at all — not something to bolt on when the parsers arrive.

## The cljc side gets the same treatment

The same shape applies one tier up. A cljc namespace precompiles to a **bytecode
image fragment**, and `flint` concatenates only the reachable fragments into the
program image.

Otherwise every invocation recompiles `clojure.core` from source, which is both
slow and a strange thing for a compiler that has already done that work once.

## Design the unit boundary for the general case now

The owner's point: this is a composition system that happens to serve built-ins
first. **Do not bake "built-in" into the format.** A namespace unit should be
described by what it IS, not by who shipped it:

- its compiled artifact (a wasm object, or a bytecode fragment)
- the symbols or vars it exports
- the units it depends on
- enough metadata to check compatibility

Get that right and independently compiled user namespaces, incremental builds and
distributable pre-compiled libraries all become the same feature later, rather
than a rewrite. It costs almost nothing to keep the boundary honest today.

Nothing above asks you to BUILD user-namespace compilation now. It asks you not
to make it impossible.

## What must be true at the end

- A program that never mentions XML contains no XML parser, asserted by a
  **test** — module size or a section/symbol check — not by prose.
- Module size for a trivial and a realistic program, in the benchmarks.
- The unavoidable floor named honestly in the README: GC, collections, number
  tower, UTF-8, the interpreter itself.
- The unit format documented, including what would have to change to admit a
  user-compiled namespace.
