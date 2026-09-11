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

| file | fns | lines | verdict, from reading the bodies |
| --- | ---: | ---: | --- |
| `err.rs` | 3 | ~44 | **PORTED** -- `kin/exinfo.kin` has `ex-info`, `is-exception`, `ex-message`, `ex-data`, `ex-kind`. What is left is `ex_matches` (Rust `String` and `ends_with`, Java string methods on the ports -- trap 4 at FUNCTION granularity, in the same file), `make_error` (takes `&str`) and `failed`/`clear_error` (runtime state). |
| `num.rs` | 2 | 16 | host -- value-representation predicates |
| `bytes.rs` | 2 | 28 | **host** -- `new_bytes` takes a Rust slice and `b_to_vec` answers one. Everything else in the file is `@kin:link` vocabulary: the walk, the sink, the code-point buffer, `run_eq`, `leaf_find`. Trap 2, at file scale. |
| `set.rs` | 2 | 30 | host -- `set_for_each` takes a Rust closure |
| `fmath.rs` | 2 | 31 | **host** -- the `f1!`/`f2!` macros are `libm` under wasm and `std` natively; on the ports they are `Math.sqrt` and friends. Only `abs` and `signum` are pure arithmetic, and the ports get both from `Math`, where `Math.abs(-0.0)` and `Math.signum(-0.0)` already agree with the Rust. Porting them would trade a JIT intrinsic for a hand-rolled bit twiddle. |
| `eq.rs` | 3 | 33 | host -- two one-liners, and `utf16_cmp` is `String.compareTo` on the ports |
| `hash.rs` | 4 | 36 | **not a port, a SEMANTICS fix.** Read as the strongest candidate in the table -- three hand-written copies of an arithmetic that must agree, with the JVM port arguing against the host shortcut in its own comments. Reading it found that the three had already drifted: a string hashed over UTF-16 units when flat, bytes when a tree, and whichever the intern path chose when short. Resolved by defining the hash over BYTES everywhere rather than by porting. `hash_double` was already vocabulary; `java_string_hash` and `hash_unencoded_chars` still serve the SYMBOL hash and stay. |
| `rope.rs` | 2 | 42 | **host** -- `value_text` answers a Rust `String` and `s_to_vec` a `Vec<u8>`; `flatten` is diagnostics, a gas charge and a delegation to the generated `s_flatten`. The tier constants and `@kin:link` forms above them are vocabulary. |
| `vector.rs` | 3 | 47 | host -- construction and a predicate |
| `map.rs` | 3 | 91 | **done** -- `init_map` plus two taking Rust closures |
| `obj.rs` | 12 | 113 | host -- object header accessors |
| `value.rs` | 6 | 150 | host -- value representation |
| `strs.rs` | 10 | 275 | host -- interning, with thread-safe publication |
| `pike.rs` | 8 | 339 | **NOT A PORT.** `doc/decisions/0012` says it: "Per host, native -- the simulator." The NFA compiler is the shared half and already is. |
| `coll.rs` | 17 | 619 | mostly host -- see trap 4; nine `Value -> Value` functions that use `StringBuilder` and `String.indexOf` on the ports |
| `builtins.rs` | 6 | 1097 | host -- the builtin table |

THE FOUR UNREAD ROWS ARE READ, and none was a port. Three are host boundaries
that counting could not tell from backlog, and the fourth -- `hash.rs` -- was a
real candidate that turned into a correctness fix instead: the three copies had
drifted, and the answer was to define the hash over bytes rather than to unify
three walks over UTF-16. A table row saying "unread" is the only kind that can
lie in both directions at once, and there are none left.

ONE ROW WENT THE OTHER WAY. `err.rs` read as unremarkable at 92 lines and was
the only verified YES in the table -- three bodies trying to be the same
thing, typed out separately, with `ex_info`'s pop landing at a different point
in each. It is `kin/exinfo.kin` now.

TWO ROWS THAT WERE BACKLOG THIS MORNING AND ARE NOT. `pike.rs` at 339 lines
is a documented architectural decision, not unported work. `coll.rs`'s nine
portable-looking functions would make every runtime slower if unified. Both
were found by reading rather than counting, which is what the fourth trap
below is about.

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
4. **A `Value -> Value` SIGNATURE DOES NOT IMPLY PORTABLE LOGIC**, and this is
   the trap that survives all the others. `coll.rs` has nine functions taking
   `Value` and answering `Value` -- `join_strings`, `str_index_of`,
   `str_concat2` and the rest -- which reads as nine ports waiting to happen.
   Look at what the ports do with the same builtins:

       flint/str-join       native builds a ROPE     the JVM uses StringBuilder
       flint/str-index-of   native walks the bytes   the JVM materialises a
                                                     String and calls indexOf
       compare on strings   native's `utf16_cmp`     Java's String.compareTo

   These are not three copies of one algorithm that drifted. They are three
   implementations that each use what their host actually has, and the ports
   have real host strings while native does not. Unifying them into one kin
   source would force every runtime onto the algorithm of the one with the
   fewest facilities -- which is a PERFORMANCE decision wearing the clothes of
   a deduplication.

   The `conj!`/`assoc!`/`dissoc!` family was the opposite case and that is why
   it was worth doing: same algorithm, three copies, drifted. The test is not
   the signature. It is whether the three bodies are trying to be the same
   thing.

The table above already excludes the first three. The fourth cannot be
excluded mechanically -- it needs the bodies read.

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

#### A string hashed three ways depending on its tier -- FIXED

`(hash s)` for a non-ASCII string disagreed with itself either side of
`FLAT_MAX`. Measured at the boundary, `\u00e9` repeated: 1 024 bytes hashed
1656509768, 1 026 bytes hashed 1603365571 where Clojure says 1433180138.

THREE BASES, not two. A flat string walked UTF-16 UNITS, to match Java's
`String.hashCode`; a tree walked BYTES; and an INTERNED string carried a
UTF-16 hash written into its header at intern time, which decided the answer
for anything short enough to intern. Changing the flat walk alone moved the
seam one line down rather than closing it -- the intern path kept handing back
the old basis.

WHY NOTHING CAUGHT IT. All four runtimes agreed with each other, so
`conform-hosts` was green: they were wrong together. And flint's own tier rule
-- two values that are `=` must hash alike -- held as well, because no short
string is ever EQUAL to a long one. The bug lived exactly in the gap between
"the runtimes agree" and "the answer is right".

FIXED AS ONE WALK OVER UTF-8 BYTES at every tier, verified against an
independent computation for inline, interned, flat, rope, astral-plane and
mixed content.

THE TARGET WAS THE INTERESTING PART. The first fix made trees walk UTF-16 so
everything matched Clojure -- and that was solving the right problem against
the wrong target. Clojure changed its own hash in 1.6 and documents no
stability across versions, nothing in this tree depends on the numbers, and
the UTF-16 basis cannot compose with `pow31`, so a non-ASCII rope lost its
per-node cache. The tree had already said so: the first fix kept a byte fast
path for ASCII BECAUSE the unit walk could not compose, which was evidence
read as an optimisation.

ASCII still agrees with Clojure exactly, because there a byte IS a unit. Of
the five pinned values in `runtimes/jvm/test/RtHash.java`, only the non-ASCII
one moved. Recorded as a deliberate divergence rather than a bug.

#### A collision node was scanned for free -- FIXED

Hash flooding. A map lookup on keys sharing a hash scanned the whole collision
node and was billed a FLAT 16 STEPS -- flat at 1 024, 4 096 and 16 384
colliding keys alike, where 16 384 was 179us of work billed the same as
1.70us. 105x the work, the same bill, with the multiplier chosen by whoever
supplies the keys.

Reachable rather than theoretical: flint's string hash is a base-31
polynomial, so `Aa`/`BB` collide and the property composes -- 2^k strings with
one hash. Any map holding attacker-influenced strings could be made to do
unbounded uncharged work.

Fixed by charging per entry, matching what `kin/mapeq.kin` already did. Now
`16 + entries scanned`, with legitimate code unchanged to the step. Pinned by
`test/gas.clj`, verified to fail against the unfixed runtime. Full write-up in
`doc/goals/hash-flooding.md`.

FOUND WHILE CLOSING A COMMENT. `bench/progs/equiv.cljc` said collision
coverage was out of reach because `Aa`/`BB` "collide under Java's
`String.hashCode` and mean nothing here". That was a guess in the direction of
not testing, and it was wrong -- asking flint for the hashes took one probe.
The lesson is not about hashing: an unverified aside in a comment is where the
next one of these will be.

#### A plain vector was walked as a seq by `=` and by `hash` -- FIXED

Both found by `bench/equiv.mjs`, which exists because `doc/goals/equiv-hash.md`
said its own first implementation step was a baseline and not an
implementation. The baseline found two defects before the design it was taken
for got its first line of code.

`mapeq` had been given a structural comparison; the SEQUENTIAL paths were left
generic. So the INDEXABLE case was the slow one -- `=` on 20 000 vector
elements cost 1.27ms against 0.42ms for a 20 000-entry MAP -- and both
allocated a seq cell per element, two per element for `=` and one for `hash`.

| operation | before | after | allocations |
| --- | --- | --- | --- |
| `=` on 32 | 2 061 ns | 330 ns | 64 -> 0 |
| `=` on 20 000 | 1 273 190 ns | 203 818 ns | 40 000 -> 0 |
| `hash` of 2 000 | 74 927 ns | 19 181 ns | 2 000 -> 0 |

WHY NOTHING CAUGHT IT, which is the part that generalises. Both operations
were CORRECT, identical across all four runtimes, and inside every size and
gas budget. `check-builtin-coverage` reported `=` as reached the whole time.
They were only slow and allocating, and no gate was asking that question.
This is "reached is not exercised" in a third form: not a wrong answer, not an
unmetered path, but a right answer computed wastefully.

There is now a gate. `bin/test` asserts `=` and `hash` allocate NOTHING on
four shapes, and it was verified to FAIL against the pre-fix code rather than
merely to pass against the fixed code.

COST: 1 934 bytes on the module floor, 0.61%, which moved two size budgets.
Priced and reversible -- `test/threads.clj` carries the decomposition and the
one-line reversal.

#### Gas diverged on ropes -- DISSOLVED, not decided

RECORDED EARLIER TODAY as needing a choice between three options: the ports
adopt native's flatten-and-cache, native adopts the ports' walk, or `0009`
gains a documented exception for rope materialisation. None of them was taken,
because the question stopped existing.

The measurement was `FAIL gas differs by 1350: 425020 native against 423670
[jvm]`, and the diagnosis was right as far as it went: native's `string_arg`
flattened a rope, cached it in `RP_FLAT` and charged for the copy, while the
JVM built a `java.lang.String` and charged nothing. What that diagnosis MISSED
is that this was not two prices for one operation -- it was three
implementations of `str-index-of`, one per runtime, over three different
string representations. There was no charge to move because they were not
doing the same work.

`kin/ropefind.kin` made it one algorithm over the rope, which every runtime
shares. Gas now agrees by construction rather than by agreement: 113 steps on
both, and the same for a flat string as for a three-leaf rope holding the same
bytes.

THE THING WORTH KEEPING is the shape of the error. A divergence between two
runtimes looks like a pricing question and can be a DUPLICATION question. The
fix was not to reconcile the numbers but to delete two of the three
implementations -- and the same shape then appeared twice more the same day,
in `rope-append`/`rope-prepend` and in whatever the duplication audit turns
up.

#### Five divergences from Clojure -- FIXED

All five now answer what Clojure answers. The direction was the obvious one and
it did not need a decision: a standard function that is compatible is worth
more than one that is defensible.

| expression | was | now, and Clojure |
| --- | --- | --- |
| `(pop nil)` | `IllegalStateException` | `nil` |
| `(subvec [1 2 3] 2 1)` | `[]` | throws |
| `(peek #{1})` | `1` | `ClassCastException` |
| `(nth {:a 1} 0)` | `[:a 1]` | `UnsupportedOperationException` |
| `(contains? '(1 2) 0)` | `false` | `IllegalArgumentException` |

`(pop nil)` was the only one where flint was STRICTER, and so the only one a
working program could meet by accident. The other four were flint answering
something plausible to a question that has no answer -- `(contains? '(1 2) 0)`
read as "this list has no key 0" when the truth is "a list has no keys", and
the caller who wrote it meant `(some #{0} '(1 2))`.

TWO THINGS THE TABLE DID NOT CAPTURE, both found by asking Clojure rather than
reading the row:

* **An empty range is not an inverted one.** `(subvec [1 2 3] 3 3)` is `[]` and
  must stay `[]`; `(subvec [1 2 3] 4 4)` throws. So the bound is checked
  against the length BEFORE the two ends are compared.
* **`(contains? "ab" 0)` is `true`** -- strings are indexed. The refusal names
  the seq tags rather than everything that is not a map, set or vector.

AND `peek` IS STRICTER THAN THE ROW SAID. Clojure casts to `IPersistentStack`,
which a set is not -- and neither is a map, a string, or any SEQ:
`(peek (map inc [1]))` and `(peek "ab")` both throw there. `seqshapes` only
asked about the set, so only the set was on the list. The fix draws the line
where Clojure draws it, except that a CONS is treated as a list here, because
flint has one list type where Clojure separates `PersistentList` from `Cons`
and refuses the second -- refusing it here would break `(peek (conj '(1) 2))`,
which Clojure accepts.

`runtimes/conform/seqshapes.cljc` already had a case for every one of the five
(`:pop-nil`, `:sub-rev`, `:peek-set`, `:nth-map`, `:has-list`), so all four
runtimes are held to the new answers.

### 2. `Equiv` / `Hash` / `EquivHash`

Designed in [equiv-hash](equiv-hash.md), not started. The first step is not an
implementation: it is a benchmark that can show the unextended path is
unchanged, because every cost claim in that document is read rather than run.

The first thing to DECIDE is whether `TY_TAGGED` gets a metadata slot. It
cannot carry metadata today, and it is the natural carrier for a domain value
in a language with no `deftype` — so without it the mechanism has little to
attach to.

### 3. Extern refs

Drafted in [extern-refs](extern-refs.md). **All three open questions are now
decided**; what remains is design work rather than judgement calls.

* **The `map` cliff -- decided, and not by any of the three options the draft
  offered.** The park moves to the operation's ENTRY: a collection that
  receives an extern is pinned at its top level, and every builtin that would
  otherwise park inside a native frame checks the pin on entry and parks there,
  which is legal. That removes the cliff rather than giving programmers a way
  around it, and the failure conditional on data stops existing.
  *Open piece:* pins must PROPAGATE on nesting -- a pinned collection inside
  another obliges the parent -- which is `equiv-hash`'s node bit in different
  clothes, including the same conservative answer on removal.
  *And in the common case none of it exists:* a sandbox with no thread pool
  marks its externs global and maintains nothing. That is the HOST'S
  declaration, not an inference from the executor count, because thread
  affinity belongs to the host object.
* **Determinism -- decided: a property of a CONFIGURATION, and opt-in.** The
  deterministic sandbox is unchanged and stays the default; externs with a pool
  are additive. `0005` is scoped rather than weakened. It becomes an
  ENFORCEMENT question: the SDK must refuse pinned externs in a sandbox
  configured deterministic instead of silently degrading it.
* **The capability hole -- decided, and closed.** Externs enter only through a
  port; `flint.interop/type` rather than `clojure.core/type`, so it is not
  reachable from any code that happens to call `type`; and it yields an opaque
  extern-type from which opaque CLOSURES are obtained as accessors. No `Class`
  is ever handed back, so the path to the classloader is gone. The closure IS
  the capability, which turns ambient authority into ordinary capability
  discipline.
  *Still design work:* the accessor-construction protocol, the SDK override,
  and revoking a grant already handed out.
* **Extending protocols to extern types -- decided: PERMANENT, not a weak
  map.** A protocol extension is not a cache, so a miss is not recomputed, it
  is a different answer -- and a weak map would make `(satisfies? P x)` depend
  on whether a collection ran between two calls. The scenario that decides it:
  a type arrives over a port, is extended, leaves, and an equal one returns.
  Weakly held, the extension is there or gone by GC timing, so two identical
  runs dispatch differently.
  *And it needs no new machinery:* `kind` answers `:extern/<host-name>`. A
  keyword already interns by name, so a type crossing a port twice yields the
  identical keyword and finds its extension -- the stable-identity constraint
  dissolves rather than being satisfied. `flint.interop/extend` is then
  `clojure.core/extend` with no wrapper and no second dispatch path.
  *A kind per host type is `0005`'s rule, not an exception:* `kind-of` already
  records that `:other` was a hole because one extension written for one type
  caught them all. A single `:extern` kind would be that hole again.

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

## SOLVED: `extend-method` must live in a namespace that is INITIALISED FIRST

`clojure.core` is pinned to position 0 by `core-first`, so top-level code in
ANY namespace can call into it whether or not that namespace requires it.
`flint.protocols` is not pinned. A using namespace with no requires sorts
before it, so `(extend-protocol ...)` at top level calls
`flint.protocols/extend-method` while that namespace's vars are still nil --
which is what "value is not a function (nil, 4 args)" says.

MEASURED, all three rows with a freshly built CLI:

| `extend-method` in | using ns requires `flint.protocols` | result |
| --- | --- | --- |
| `clojure.core` (pinned) | no | works |
| `flint.protocols` | no | ClassCastException (nil, 4 args) |
| `flint.protocols` | yes | works |

So this was never about emission, reachability, or the SHAPE of the expansion.
It is initialisation order, and the reason the six probes below all "worked" is
that none of them put the callee in a namespace that could sort late.

AND THE STALE-BINARY TRAP NEARLY WROTE THE OPPOSITE. `flint run` compiles with
the compiler built INTO the binary, and `bin/build-dist` does not rebuild that
binary. The first run of this experiment reported that moving `extend-method`
worked in both configurations -- a clean resolution, and entirely an artefact
of a `target/release/flint` from before the move. `cargo build --release -p
flint-cli` is the missing step, and the failure appeared the moment it ran.

AND IT IS NO LONGER BLOCKED, which this paragraph used to say it was. Pinning
`flint.protocols` in `core-first` is exactly why `flint.check` is pinned --
"nothing `:require`s `flint.check`, so the graph has no edge to order by and
this supplies one" -- and it LANDED, with `flint.core` beside it, because
`protocol-miss` calls `kind`. It landed for its own reason rather than for this
one: a protocol miss during load reported "value is not a function (nil, 3
args)" instead of naming the missing implementation, which is a live defect on
ordinary code.

So the thing that kept `extend-method` in `clojure.core` is gone. Moving it is
now an ordinary change rather than a blocked one, and it is worth doing only if
the name bothers somebody -- `extend-method` is the one name that namespace
publishes which Clojure does not. It stays for now because nothing forces it,
not because anything stops it.

## THE GENERAL CASE: a compiler-emitted reference is not an edge

`extend-method` was one instance of something larger, and the larger thing is
still open. The load order is built from `:require` EDGES. The COMPILER emits
references the user never wrote, into the user's namespace, and those are real
runtime dependencies with no edge behind them.

The set it emits is small and was probed in full:

| emitted | when | state |
| --- | --- | --- |
| `clojure.core/assoc` | `binding` | safe — pinned first |
| `flint.rt/dyn-bindings`, `dyn-set-bindings` | dynamic vars | safe — `flint.rt` is a builtin, it has no vars to be nil |
| `flint.protocols/extend-method`, `protocol-miss` | `defprotocol` | FIXED by pinning (`cb8fadd`) |
| `flint.virtual/fn-for`, `call` | a reference to a virtual namespace | FIXED by `implied-requires` |
| `flint.regex/pattern` | ANY regex literal | FIXED by `implied-requires` |

THE REGEX ONE IS ORDINARY CODE, which is what makes this worth a section:

    (ns app.main)
    (def re #"a+b")
    ;; ClassCastException: value is not a function (nil, 1 args)

`(:require [flint.regex])` fixes it, and that is the whole problem: the user
must require an implementation-detail namespace they never named to make a
literal work. The virtual case is worse — requiring `flint.sys.fs`, the
namespace actually mentioned, does NOT help, because the emitted call is on
`flint.virtual`.

PINNING DOES NOT REACH THEM, and that is the finding rather than the
inconvenience. `core-first` went from two entries to four for the protocol fix.
`flint.regex` requires `clojure.string` and `flint.nfa`; `flint.virtual`
requires `flint.port`, `flint.rpc` and `flint.thread`. Covering these by
pinning means hand-writing a large prefix of the stdlib's load order, and the
next emitted reference reopens it.

WHAT THE FIX IS. `implied-requires` walks the read forms -- which the pre-pass
already does, before any analysis -- and adds the edge the user did not write:
`flint.regex` for a source containing a regex literal, `flint.protocols` for
one containing `defprotocol`/`extend-protocol`/`extend-type`. Self is excluded,
because `flint.protocols` defines a protocol.

DERIVED RATHER THAN PINNED, and the difference is why this is the shape it is.
`core-first` names four namespaces by hand; `flint.regex` alone would add three
more, and the next emitted reference would reopen it. A derived edge covers the
reference that exists rather than the list somebody remembered to update.

Cycle risk was measured, not assumed: `flint.regex`, `clojure.string` and
`flint.nfa` contain no regex literals, and `flint.core` -- the one namespace
`flint.protocols` requires -- contains no protocol forms. So neither new edge
can point back into its own chain.

THE VIRTUAL CASE IS FIXED TOO, in the compiler only. Its trigger is "this
source requires a namespace that is VIRTUAL", which `topo-order` reads from
`sources`. `bin/flint` has NO counterpart and that is not a divergence: it
cannot compile such a program at all -- measured, it answers "cannot find
source for namespace flint.sys.fs", because its workspace model has no
`:virtual`. Its own `virtual-namespaces` is `#{flint.rt}`, a different thing:
builtins compiled to native calls, with no `flint.virtual` behind them. Using
that set for this trigger would add an edge for every `(:require [flint.rt])`
and be wrong.

AND A SEPARATE DEFECT CAME OUT OF TESTING IT, since fixed. A GRANTED virtual
call at load time did not work: it produced no value the entry could render,
while the same call from a function body succeeded.

THE CAUSE WAS DOCUMENTED THREE LINES ABOVE THE LINE THAT CAUSED IT.
`run_program`'s initialiser loop carries a paragraph explaining that a YIELD
inside an initialiser used to be discarded by its `let _ = self.invoke(..)`,
leaving a half-built program that reported "the entry function did not return a
string" for an entry returning a constant. It disarms preemption so no yield can
happen. A PARK is the other thing, and was still discarded -- the loop is not
re-entrant, so there is no position to resume to.

It is refused now, at the form that asked:

    IllegalStateException: a top-level form asked the host while the program
    was still initialising, and cannot wait for the answer there. Move the call
    into a function the entry reaches.

Which is `0027` one phase earlier: a sandbox that cannot ask is TOLD so rather
than parked.

THE LINE IS "CAN IT BE RESUMED", not "did it park". A channel round-trip in a
top-level `def` parks and resumes inside the same call, so `park_on` is nil
when the initialiser returns, and it still answers `[:got 7]`. Only a park
nothing can resume is refused.

AND IT IS NOT PARKING IN GENERAL was the first guess, and wrong; the second
guess, that the returned value could not be rendered, was also wrong -- an
entry returning a LITERAL failed identically, which is what showed the entry
was never reached. Two wrong readings before the right one, both of them
plausible, both cheap to disprove.

A TRAP WORTH KEEPING: the first version of that test had `go` ignore the
top-level `def`, and it passed. The linker had dropped the def as unreachable,
so the row never ran the thing it was about. A load-time probe has to be READ
by the entry or it proves nothing.

THE OLD RECORD FOLLOWS, corrected where it was wrong.

WHAT WAS OBSERVED:

* Moving `extend-method` to `flint.protocols` -- reached only through
  `extend-protocol`'s expansion, which never names it -- makes a top-level
  `(extend-protocol ...)` in a using namespace fail with
  `ClassCastException: value is not a function (nil, 4 args)`.
* ~~An explicit `(:require [flint.protocols])` in the using namespace does NOT
  fix it.~~ WRONG, and measured wrong above: it fixes it, because it is the
  dependency edge the order is built from. This claim is why the search went
  looking at emission for so long.
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

SIX HYPOTHESES, ALL DISPROVED BY PROBE rather than argued away:

| probe | result |
| --- | --- |
| a top-level side effect into a never-required namespace | works |
| a macro expanding to a qualified call, invoked at top level | works |
| A requires B; a macro in A expands to `B/f`; U calls it at top level | works |
| the full `extend-protocol` shape -- `do`, a user-namespace var, a keyword, a name, a `fn` form | works |
| `B/f`'s body back-references A without requiring it | works |
| ...and the back-referenced name is private | works |

The first is the explanation I originally wrote here and had to retract. The
rest are the obvious next guesses, taken in order. None reproduces it, so the
cause is not "top-level effect", not "reached through a macro expansion", not
the SHAPE of the expansion, not an unrequirable back-reference, and not
privacy.

AND ONE CONFOUND HAS BEEN REMOVED. Moving `extend-method` really did contain a
second, unrelated fault: its body calls `clojure.core/method-key`, which is
`defn-`, so the compiler says `clojure.core/method-key is private to
clojure.core; flint.protocols may not name it`.

THE REASON I GAVE FOR IT WAS WRONG, and this is the third correction this
section has taken, so it is worth saying plainly. I wrote that the reference
"passed silently" because privacy was order-dependent and `flint.protocols` is
analysed BEFORE `clojure.core`. It is not. `core-first`
(`src/flint/project.cljc:267`) pins `clojure.core` to the FRONT of the order
unconditionally -- `(concat pinned (remove pin? order))` -- whatever the
require graph says. So `clojure.core` is analysed first, its `:var-meta` is
already filled when `flint.protocols` is analysed, and the old check would have
refused this too. I asserted the mechanism from a plausible story about
ordering instead of reading the ordering, which is the same mistake as the
first retraction in this section, made about a different pass.

IT IS NOT THE ANSWER, THOUGH, and that is worth being exact about. Making
`method-key` public and moving `extend-method` anyway builds the compiler
clean -- and `(extend-protocol ...)` at top level still fails with the same
`ClassCastException: value is not a function (nil, 4 args)`. So privacy was a
blocker, not THE blocker. What the experiment bought is a cleaner starting
point: the next person is no longer chasing a failure with a silent privacy
violation mixed into it.

WHAT IS LEFT UNTESTED is what `clojure.core` IS, rather than how it is
arranged. The third and fourth probes above build the bootstrap SHAPE in
ordinary namespaces -- A requires B, the macro lives in A, the user namespace
names neither -- and it works. So the difference is not the shape of the
dependency; it is something about `clojure.core` specifically: auto-referred
into every namespace, bootstrapped, and the one namespace whose own analysis
order is not a free variable.

AND `core-first` IS WORTH READING BEFORE GUESSING AGAIN, because it is the one
place where the load order is not the require graph. It pins two names:
`clojure.core`, and `flint.check` -- the latter with the comment "`expect` is a
MACRO, and a macro has to be compiled before the namespace that expands it is
analysed. Nothing `:require`s `flint.check`, so the graph has no edge to order
by and this supplies one."

That is a hand-made fix for a general hole, and the hole has since been found
and closed: a macro used from a namespace analysed before its definer was
compiled as an ORDINARY CALL, silently, evaluating arguments the macro was
written to discard. It refuses now. Four more of that family -- a table
written at analysis and read at a reference -- were found and fixed with it:
capability guards, `^:private`/`^:internal` on macro-defined vars, and
`:dynamic`. `extend-method` is the same shape one pass later: not a table read
before it was filled, but a var READ before its namespace was initialised.

AND A FIFTH MEMBER THAT MUST NOT BE FIXED THE SAME WAY. `:inline`,
`register-native-aliases!`, `register-projections!` and `register-inversion!`
are all registered while the DEFINING form is analysed, exactly like `:macros`.
So they too are missing for a reference analysed earlier. Measured, with an
`:inline` whose expansion names a symbol that cannot resolve:

    ordinary edge -> "unable to resolve symbol: no-such-inline-marker"   (inlined)
    reverse edge  -> no such error                                        (not inlined)

The difference is real and it is silent. It is NOT the same defect, though:
an `:inline` is required to mean what the function means, so the program still
computes the right answer. What changes is the code emitted, and therefore the
GAS -- the same source, arranged differently, is charged differently.

Refusing, which is right for a macro, would be wrong here: "this call was not
inlined" is a thing a compiler is allowed to decide. So this is recorded and
not fixed, and the open question is whether the compiler should SAY it -- a
note that an `:inline` was skipped for ordering reasons -- rather than leave
the gas difference to be discovered from a bill.

Whether `core-first` hoisting `clojure.core` over the namespaces it requires
is what leaves `flint.protocols` uninitialised at the moment
`(extend-protocol ...)` runs is the obvious next probe, and it is a PROBE and
not a conclusion -- this section has already offered two explanations that did
not survive contact, and the pattern in both was reasoning about order instead
of reading it.

THE MECHANISM IS ESTABLISHED NOW, and it is the one thing the earlier passes
kept walking past: the order is built from `:require` EDGES, and a qualified
reference is not an edge. `defprotocol` expands to a call the using namespace
never required, so the only thing making it safe is that the callee is pinned
first. Two disproofs narrow it; they do not close it. `extend-method` stays in `clojure.core`, and now for a stated reason rather
than a failed experiment: that namespace is pinned first, so a top-level
expansion can reach it from anywhere. It is the one name that namespace
publishes which Clojure does not, and that is the price of the pin.

MISSING TEST: `test/requires.clj` pins three cases that pass -- a function
call, a top-level `def`, a macro -- and a fourth added since, a top-level
effect. All four work. None reproduces the failure, so the file should not be
read as proof that qualified references are always sufficient. It checks four
shapes that are.
