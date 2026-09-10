# Goals — what is designed, what is open, what is next

`doc/decisions` records what was DECIDED and why. This directory holds what is
still being worked out: a design that has not shipped, a port that is partly
done, a question with no answer yet. A goal graduates to a decision when it
ships and the reasoning is worth keeping; it is deleted when it turns out to
be wrong.

**This index exists because a design that lives only in conversation is a
design that will be rediscovered.** That has already cost this repo twice:
four documents said the ports had no collector for eleven days after they grew
one, and the `Equiv`/`Hash` mechanism was settled in dialogue while a written
draft argued the opposite conclusion, unaware. Both were found by accident.

| goal | state |
| --- | --- |
| [data-structures](data-structures.md) | reference for the collection layouts |
| [kin-port](kin-port.md) | in progress — the shared logic moving to one source |
| [equiv-hash](equiv-hash.md) | **designed, not built.** A value bringing its own equality |
| [extern-refs](extern-refs.md) | **drafted, not decided.** Host references, pinning, orphaning |

## What is next, in the order I would take it

### 1. The remaining kin ports

`kin-port.md` has the measurement; this is the short version, easiest first,
counting only functions no `@kin:link:` tag or vocabulary template names.
`Num`'s arithmetic went this way and is the model: nine hand-written copies of
one shape became one source, and the overflow edge got decided once instead of
three times.

| file | fns | lines | notes |
| --- | ---: | ---: | --- |
| `eq.rs` | 3 | 33 | `eq_value`, `utf16_cmp`, `nil_or` |
| `set.rs` | 3 | 33 | `set_for_each` takes a closure — not portable as-is |
| `vector.rs` | 3 | 47 | `init_vector`, `is_vector`, `vec_from_roots` |
| `num.rs` | 4 | ~40 | `is_float`, `as_i64`, `num_eq`, `num_hash` |
| `map.rs` | 13 | 232 | |
| `strs.rs` | 10 | 275 | interning — likely genuinely host |
| `pike.rs` | 8 | 339 | 0% holes: real unported logic |

`Seqs` and `Table` no longer appear at all — those are finished. `Conc`,
`Rt`/`Vm` and `Gc` are host and stay last, by standing instruction.

TWO MEASUREMENT TRAPS, both of which I fell into: Rust's inline `#[test]`
functions are not logic (they made `Vec` look 81% divergent when it is 4%),
and a function named by a `@kin:link:` tag is a deliberate BOUNDARY rather
than unported work (counting them made `Bytes` look like 172 lines of work
when it was 39).

### 2. `Equiv` / `Hash` / `EquivHash`

Designed in [equiv-hash](equiv-hash.md), not started. The first step is not an
implementation: it is a benchmark that can show the unextended path is
unchanged, because every cost claim in that document is read rather than run.

The first thing to DECIDE is whether `TY_TAGGED` gets a metadata slot. It
cannot carry metadata today, and it is the natural carrier for a domain value
in a language with no `deftype` — so without it the mechanism has little to
attach to.

### 3. Extern refs

Drafted in [extern-refs](extern-refs.md), three questions left open that are
not the draft's to answer:

* **The `map` cliff.** An interop call cannot park inside a native frame, so
  `(map the-getter xs)` over pinned externs throws — a failure conditional on
  data, which is the worst shape for a surprise. A batching form is proposed
  and undesigned, and wasm needs the same thing independently.
* **Determinism.** `0005` promises a deterministic scheduler. A pinned park
  makes a green thread runnable on one executor, so a multi-executor sandbox
  cannot stay deterministic. Single-executor and `owner = 0` survive.
* **`flint.interop` is a capability hole the size of the host** — `type`
  reaches the `Class`, and from a `Class` the classloader.

### 4. Smaller, and written down so they are not lost

* **The protocol machinery should follow `Printable` out of `clojure.core`.**
  `kind`, `extend-method`, `methods-of` and `protocol-miss` are flint's answer
  to having no host interfaces, and they are minted in a namespace with an
  authoritative owner. Moving `protocol-miss` means `defprotocol` emits a
  `flint.protocols/`-qualified call, which works — a qualified reference needs
  no require here (`test/requires.clj`).
* **Eight arity-specialised internals should be `defn-`.** `apply2`, `map2`,
  `mapcat2`, `keep2`, `interleave2`, `repeat2`, `subvec2` and `spread` are
  compiler entry points, not API. Making them private removes eight names from
  the `:extra` list without needing a namespace, and `spread` is private in
  Clojure for the same reason.
* **`^:pub` widens `pub(crate)` to `pub`.** Worth revisiting as one change
  across the whole tree rather than as a rider — see `kin-port.md`, which also
  makes the inlining argument.
* **Two names are misplaced rather than invented.** `re-quote-replacement` is
  `clojure.string`'s in real Clojure; `spread` exists there but is private.

## Adding a library namespace: three registration points, one of them obvious

A new `lib/` file is not reachable by writing it. It has to be registered in
three places, and only the first announces itself:

1. **`bin/manifest`'s `shipped` map** -- or `doc/manifest.edn` and `README.md`
   never mention it.
2. **`sdks/esm/gen/stdlib.json`**, by running `./sdks/esm/build`. The ESM SDK
   embeds every library source and resolves namespaces from that bundle, so an
   unbundled namespace fails at RUN time with "no source for x. Every
   namespace a program requires has to be resolvable" -- nowhere near the file
   you added.
3. **The native runtime**, which needs rebuilding before `flint run` sees a
   new library file.

And when MOVING a name between namespaces, the consumers are not all in
`*.cljc`. `test/threads.clj` builds four flint programs as STRING LITERALS --
`delegate`, `closed`, `orphan`, `crossing` -- and a grep over source files
cannot see them. Moving `opaque` to `flint.core` broke all four, and the suite
was what found it.

## What is NOT pending, recorded so it is not reopened

* **Qualified references as dependency edges.** Measured and declined:
  `read-namespace!` registers every definition across every namespace before
  analysing any, so a require never establishes existence. The change would be
  a no-op, and under it a single `(clojure.core/str ..)` added to
  `flint.regex` would turn the build into a refused cycle. Pinned by
  `test/requires.clj`.
* **The vocabulary's cross-module templates.** All 47 are gone. Two genuine
  cross-namespace mutual recursions remain and are `declarefn` declarations in
  the sources that need them, which is what a declaration is for.
