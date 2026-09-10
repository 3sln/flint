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

| file | fns | lines | what they are |
| --- | ---: | ---: | --- |
| `num.rs` | 2 | 16 | value-representation predicates -- host |
| `bytes.rs` | 2 | 28 | |
| `set.rs` | 2 | 30 | `set_for_each` takes a Rust closure -- not portable as-is |
| `fmath.rs` | 2 | 31 | |
| `eq.rs` | 3 | 33 | two one-liners; `utf16_cmp` is host (Java/C# get it from `String.compareTo`) |
| `hash.rs` | 4 | 36 | |
| `rope.rs` | 2 | 42 | |
| `vector.rs` | 3 | 47 | construction and a predicate -- mostly host |
| `map.rs` | 3 | 91 | `init_map` plus two taking Rust closures -- **effectively done** |
| `err.rs` | 8 | 92 | |
| `obj.rs` | 12 | 113 | |
| `value.rs` | 6 | 150 | |
| `strs.rs` | 10 | 275 | interning -- likely genuinely host |
| `pike.rs` | 8 | 339 | the Pike VM simulator; 0% holes, real logic |
| `coll.rs` | 17 | 619 | **the largest genuinely portable one** |
| `builtins.rs` | 6 | 1097 | the builtin table -- host by nature |

`Seqs` and `Table` no longer appear at all — those are finished. `Conc`,
`Rt`/`Vm` and `Gc` are host and stay last, by standing instruction.

THREE MEASUREMENT TRAPS, each of which I fell into, and each of which makes
this table lie if it is regenerated naively:

1. **Rust's inline `#[test]` functions are not logic.** Counting them made
   `Vec` look 81% divergent from the ports when it is 4%.
2. **A function named by a `@kin:link:` tag is a BOUNDARY**, not unported
   work. Counting them made `Bytes` look like 172 lines of work when it was
   39.
3. **`#[cfg(feature = "bench")]` functions are benchmark scaffolding.** This
   one nearly cost a session: `map.rs` read as 13 functions and 232 lines of
   collection logic, and all four `*_entries` walks -- the ones that looked
   like the obvious next port -- exist only under the bench feature. Excluding
   them, `map.rs` has three functions left and none is portable.

The table above already excludes all three.

#### `coll.rs`, looked at properly

Seventeen functions, and most are host-bound by their SIGNATURES rather than
their subject: `find_bytes(&[u8], &[u8]) -> Option<usize>`, `cp_bytes_at(..,
&mut [u8; 4])`, `fmt_i64(.., &mut [u8; 24]) -> &str`, `hash_of_str(&str)`,
and the two `substring` arms taking `Option<i64>`. kin has no slices, no
`Option` and no borrowed buffers, so those are the boundary.

WHAT IS ACTUALLY PORTABLE takes `Value` and answers `Value`: `conj`,
`str_concat2`, `code_point_at`, `keyword_from_values`, `symbol_from_values`,
`join_strings`, `str_index_of`, `string_bytes_vector`, `gc_stats_map`.

`conj` IS THE ONE WORTH DOING and it is not mechanical. It is ~70 lines of
dispatch over collection types, it is triplicated, and the three copies are
STRUCTURALLY DIFFERENT: a named function in `coll.rs`, and inlined into the
builtin table in both ports (`Builtins.java:368`, `Builtins.cs:348`). Porting
it means extracting the ports' inline arms into a call, which is the value --
the Rust copy carries a comment about `(conj 1 2)` reading a fixnum's payload
as an address and answering `(2)`, a bug the ports did not have and could not
have shared a fix for.

It also builds its error message with `alloc::format!`, which kin cannot
express; that arm needs the vocabulary's string primitives instead.

#### The transient builtins: duplicates gone, and what they cost on the way

`conj!`, `assoc!` and `dissoc!` each had a hand-written copy of a generated
function inside both ports' builtin tables. All three are now one-line calls
into `kin/transients.kin`, about 190 lines deleted -- but the deletions came
LAST, and that order was not tidiness. Each duplicate turned out to be right
about something the generated function was wrong about:

| door | the duplicate had | the generated one had |
| --- | --- | --- |
| `conj!` | the aliveness check | entry validation |
| `assoc!` | the aliveness check | (neither had the upper bound) |
| `dissoc!` | -- | the right operation name, sort of |

DELETING FIRST WOULD HAVE SHIPPED CORRUPTION each time, under a diff that was
pure subtraction and a message about removing duplication. What made it safe
was probing the door, fixing `transients.kin`, and only then cutting.

`(dissoc! 7 :a)` and `(disj! 7 :a)` deserve a note. `clojure.core` defines
`disj!` as a call to `flint.rt/dissoc!` -- ONE builtin, two library names --
so the runtime cannot know which the caller wrote. Every runtime named one
and was wrong half the time, and they had picked differently. The refusal
names both now, which is the truth about what arrived.

MAKING IT EXACT means giving them separate builtins, which costs an entry in
the native slot table and the ABI. That is a lot to spend on one word, and it
is written in the source rather than left as a puzzle.

#### Five divergences from Clojure, found by `seqshapes`, needing a decision

All four runtimes AGREE on these -- they are not port bugs. They are places
flint answers something Clojure does not, consistently, and nobody has
decided whether that is intended:

| expression | Clojure | flint |
| --- | --- | --- |
| `(pop nil)` | `nil` | `IllegalStateException: cannot pop nil` |
| `(subvec [1 2 3] 2 1)` | `IndexOutOfBoundsException` | `[]` |
| `(peek #{1})` | `ClassCastException` | `1` |
| `(nth {:a 1} 0)` | `UnsupportedOperationException` | `[:a 1]` |
| `(contains? '(1 2) 0)` | `IllegalArgumentException` | `false` |

`(pop nil)` is the one a program meets by accident, and it is flint being
STRICTER. The other four are flint being permissive where Clojure refuses --
the safer direction, but still a difference.

`test/conform_vs_clojure.clj` is the harness that would catch these
automatically; it runs `test/conform/basics.cljc` only. Either these move
there as marked `:divergence` cases, or they get fixed. Both are decisions.

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

## Reached is not exercised

`bin/check-builtin-coverage` reports 152 of 192 builtins reached by a
conformance image, 38 accounted for with a reason, 2 unwatched. That gate is
healthy and it measures the wrong axis for the bugs found on 2026-09-09.

`dissoc` was REACHED. Twelve calls in `test/common`, all passing. It was also
wrong on every runtime, in three different ways, for a shape nobody had
tried -- `(dissoc [1 2] 0)`. `conj` was reached by `collections.cljc`, which
conjes onto a vector and a set, and was silently corrupting maps on two
runtimes.

REACHABILITY SAYS A BUILTIN RAN. It does not say which of its branches ran,
and the branch that matters is usually the one that should REFUSE. All four
bugs that day were a fall-through producing a plausible value -- `[1 2]`,
`nil`, `#{}`, `{nil nil}` -- rather than an error. None crashed, so none was
found by anything.

The probes that found them (`conjshapes`, `mapshapes`, `seqshapes`) are built
the other way round: every case that should be refused, and the refusal
recorded as the answer. That is the shape worth copying for the next family.

And the third probe found NOTHING, which is also a result: `contains?`,
`peek`, `pop`, `subvec` and `nth` agree everywhere. The fall-through cluster
was in the map/set WRITE family, not general.

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

* **The vocabulary's cross-module templates.** All 47 are gone.
  Two genuine cross-namespace mutual recursions remain and are `declarefn`
  declarations in the sources that need them, which is what a declaration is
  for.

## Reopened: moving `extend-method` fails, and I do not know why

I measured "should a qualified reference be a dependency edge", declined the
change, and was wrong -- recorded here rather than quietly fixed, because the
wrong answer is written into `test/requires.clj` and commit `c36afd0`.

WHAT IS OBSERVED, and only this:

* Moving `extend-method` to `flint.protocols` -- reached only through
  `extend-protocol`'s expansion, which never names it -- makes a top-level
  `(extend-protocol ...)` in a using namespace fail with
  `ClassCastException: value is not a function (nil, 4 args)`.
* An explicit `(:require [flint.protocols])` in the using namespace does NOT
  fix it.
* At one point the same move reported `no slot for var
  flint.protocols/extend-method -- it was reached but not emitted`, which
  points at emission or reachability rather than at load order.
* `methods-of` and `protocol-miss` moved to the same namespace with no
  trouble. They are called at runtime; `extend-method` is called while a
  using namespace is loading.

WHERE IT IS NOT. The error comes from `var-slot!` in `src/flint/emitter.cljc`,
which reads the EMITTER's slot table -- so this is about what gets emitted,
not about initialisation order, and my first explanation was looking in the
wrong pass entirely.

TWO HYPOTHESES, BOTH DISPROVED BY PROBE rather than argued away:

| probe | result |
| --- | --- |
| a top-level side effect into a never-required namespace | works |
| a macro expanding to a qualified call, invoked at top level | works |

The first is the explanation I originally wrote here and had to retract. The
second was the obvious next guess. Neither reproduces it, so whatever
separates the `extend-method` case, it is not "top-level effect" and it is not
"reached through a macro expansion".

WHAT IS LEFT UNTESTED is the bootstrap relationship itself: `clojure.core`
REQUIRES `flint.protocols`, and the macro that expands to the call
(`extend-protocol`) is defined in `clojure.core`. Neither probe had an
equivalent, and neither can be built without editing `lib/`.

SO THE MECHANISM IS UNKNOWN, and this section says so rather than offering a
third guess. Two disproofs narrow it; they do not close it. `extend-method` stays in `clojure.core`. It is the one name that
namespace publishes which Clojure does not.

MISSING TEST: `test/requires.clj` pins three cases that pass -- a function
call, a top-level `def`, a macro -- and a fourth added since, a top-level
effect. All four work. None reproduces the failure, so the file should not be
read as proof that qualified references are always sufficient. It checks four
shapes that are.
