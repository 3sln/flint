# 0024 — No linking at compile time, and byte strings

> **NOT BUILT — this is a plan.** `flint.bundle` exists and works (`0023`'s
> splice, proven end to end); everything below about byte strings, embedded
> runtimes and per-target compilers does not.

## The decision

**`wasm-ld` runs once, when flint is built. Never when a program is compiled.**

Today `flint build` links relocatable wasm objects per program, so compiling
needs a native linker on the machine doing it. That is why the wasm
distributable can compile to a bytecode *image* and not to a *module*, and the
image is internal machinery — a caller wants something it can instantiate.

The owner's direction, and it is right:

> drop the concept of `:wasm-src` and loading/linking external wasm stuff. Our
> compiled binary needs to include the standard library modules as data, for
> each runtime we target: wasm, jvm, clr. For minimal builds, we can have single
> runtime versions of the compiler to keep it smaller. The goal is to get rid of
> runtime linking within flint altogether, offload it to the runtime where
> possible.

So:

* flint's build produces one **prebuilt runtime module per target**, linked
  once, carrying every builtin.
* The compiler **embeds those as data**.
* Compiling a program is: source → image → **splice** the image into the
  embedded runtime. `flint compile wasm` gives a module that interprets;
  `flint compile wasm-aot` gives one with the compiled arities appended.
* Nothing links. Where a target has its own loader — the JVM's classloader, the
  CLR's assembly loader — that is what does the composing, which is the
  "offload it to the runtime" half.

A fat compiler carries every target; a minimal one carries a single runtime and
is correspondingly smaller.

### What this gives up, and it is not a capability

Per-program linking tree-shakes: `--gc-sections` deletes builtins the program
never reached. A prebuilt runtime carries all of them.

| | bytes |
| --- | ---: |
| linked for a trivial program | 219,726 |
| prebuilt runtime, all 166 builtins | 573,959 |

About 360 KB for a small program, converging to nothing for one that uses most
of `clojure.core`. `bin/flint` keeps the linking path for anyone who wants the
small artifact; it is no longer what `compile` means.

### Why AOT never needed the linker either

Worth stating because the previous shape suggested otherwise. `compile-aot`
runs on an **already linked** module and appends function bodies, a type and an
elem segment. wasm cannot add a function to a module that already exists, which
is why this happens at build time — but appending is byte manipulation, and
byte manipulation needs no `wasm-ld`. It now lives in `flint.bundle` rather
than `flint.link`, which is where it always belonged.

## The blocker, and it is one thing

`flint.wasm` — the binary reader and writer everything above stands on — is
built on **Java byte arrays**: `aget`, `alength`, `ByteArrayOutputStream`,
`bytes?`. It does not compile under flint, so `flintc.wasm` cannot splice.

The obvious port is "bytes become a vector of ints". That is wrong and should
not be attempted: a flint vector holds NaN-boxed 64-bit values, so a
574 KB module becomes 4.6 MB of payload plus trie overhead, to represent bytes.

## Byte strings

Ropes already exist for text (`0011` §2): three tiers, inline / flat /
B-tree, with structure sharing so `str` is a tree join and `subs` of a large
range shares subtrees. **Bytes get the same treatment**, as a distinct type
that answers `bytes?`.

* **Flat** — a contiguous `TY_BYTES`, `Layout::Raw`. Small byte strings are
  only this, because a tree costs more in metadata than it saves.
* **Rope** — a shallow B-tree of byte pieces, the same `FANOUT` and
  `SLICE_MIN` reasoning as text.
* Simpler than the text rope in one way: a node carries its subtree's byte
  length and nothing else. There is no code-point count to sum and no ASCII bit
  to AND, because a byte is a byte.

## Transients, for bytes AND for text

This is the part canonical Clojure has no reason to want, and flint does.

Clojure's strings are flat, so building one incrementally is `StringBuilder`
and there is nothing to add. flint's are trees, and the tree has a threshold:
`FLAT_MAX` is 1024 bytes, and a concatenation below it **copies** rather than
building a node. So incremental building is quadratic in bytes copied until the
pieces outgrow the threshold — which is exactly what a loop of `(str acc x)`
does, and exactly what nobody expects a rope to do.

Measured, on 20,000 pieces, building the same 88,890-character answer:

| | time | allocations | bytes | collections |
| --- | ---: | ---: | ---: | ---: |
| repeated `str` | 8.7 ms | 19,995 | 2.1 MB | 1 |
| collect, then one `str-join` | 0.9 ms | 19,999 | 0.7 MB | 0 |

**9.7× slower and three times the bytes for the identical answer, at the same
allocation count.** The count being equal is what names the cause: it is not
more objects, it is bigger ones — the same bytes copied again and again.

A transient byte string, and a transient rope for text, fixes it the way a
transient vector fixes `conj`: a mutable tail buffer that appends fill in
place, promoted into the tree only when it is full. `conj!` amortises to O(1)
and the quadratic disappears. `persistent!` freezes the tail as the last leaf.

That is the answer to *"will it benefit flint's tree-structured strings?"* —
yes, and the reason is specific to trees with a flat threshold, which is why
Clojure never needed it.

## The order to build it in

1. `TY_BYTES` and the byte rope, with `bytes?`, `count`, `nth`, concat and
   slice. Measured against the vector-of-ints it replaces, on bytes held per
   byte.
2. The transient byte string, measured on the table above's shape.
3. Port `flint.wasm` onto it. The suite exercises it on every build, so the
   port has a standing check from the first commit.
4. Embed a runtime module as data; `flint compile wasm` emits a module.
5. `flint compile wasm-aot`, which is step 4 plus `bundle/compile-arities`.
6. The transient rope for text, if step 2's numbers say it carries.
7. JVM and CLR runtimes as further embedded targets (`0010`).

Steps 1–3 are what unblock everything; 4 and 5 are small once they land.
