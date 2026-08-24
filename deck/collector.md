# The collector

## The collector

### Rooting: the decision everything else rests on

wasm has no scannable machine stack. The operand stack is not in linear memory
and locals are not addressable, so a collector **cannot** find roots
conservatively. flint therefore never tries. The root set is exact, explicit,
and lives in one struct:

- **the VM's value stack** — interpreter frames are windows into one
  `Vec<Value>`, so every local, argument and intermediate of every active
  Clojure frame is in it. This is the primary root set and it costs nothing: the
  interpreter needs the stack anyway.
- **vars and the image's constant pool**;
- **a shadow root stack for native code** — a Rust builtin holding a `Value`
  across an allocation must push it here, because Rust locals are invisible to
  the collector and an allocation can move the object out from under them;
- **the intern tables**, which are *weak*: entries are dropped when their object
  dies.

This is also why flint is an interpreter rather than an ahead-of-time compiler
to wasm functions. Under AOT, live references sit in wasm locals where the
collector cannot see them, and you need a shadow-stack spill around every
allocation site — which hands back most of the speed. WasmGC exists to close
this gap; until it can be relied on, the interpreter is the honest choice.
([`doc/decisions/0001`](doc/decisions/0001-dispatch.md).)

The design has already paid for itself once. A VM frame used to *cache* its
closure, and that copy was a root the collector could not see; after a collection
moved the closure, `UPVAL` read a stale address. The fix was to delete the copy —
`stack[ret_to]` **is** the frame's closure — which keeps the invariant true with
no second mechanism. There is a regression test that forces a collection while an
upvalue-using frame is live.

**And once more, in a place that was much easier to get wrong.** `=` and `hash`
on a *compound* value allocate: comparing two vectors walks both through
`seq`/`first`/`next`, and each of those is an allocation. So a collection can run
in the middle of a map lookup or insert — and the CHAMP code was holding raw
addresses across exactly those calls. The symptoms were a key silently missing
from a map whose `count` said it was there, a hash cached into a moved object,
and eventually an out-of-bounds trap. Scalar keys never showed any of it, because
hashing a fixnum allocates nothing; it took the self-hosting fixpoint test
failing (the compiler interns its constants in a map keyed by vectors) to expose
it. Every path that calls `=` or `hash` now roots what it is holding, and
`test/gcstress.cljc` builds 30 000-entry maps and sets with vector, list, map and
set keys and asserts every one is findable afterwards. Lookups with a scalar key
take a version with no rooting at all, so `get` keeps its old cost.

### Generations

- **young**: two equal semispaces, allocation is a bump pointer, collection is a
  copy (Cheney with an explicit worklist so tracing is iterative, not recursive).
  Objects surviving two copies are promoted.
- **old**: chunks of pages, **non-moving**, mark-sweep with segregated free
  lists rebuilt with coalescing on every sweep. Objects ≥ 16 KiB skip the
  nursery entirely.

Old objects never move. That is the reason the write barrier and the remembered
set stay simple: a minor collection only ever rewrites pointers that point *into
the young semispace*, and there is exactly one contiguous range to test against.

Freshly allocated slots are initialised to `nil`, not zero. Zero bits are the
double `0.0`, and "unset trie slot" reading as a number instead of `nil` is a bug
that surfaces a long way from its cause.

Measured, building a 400 000-element vector (Apple M1 Pro):

| | count | median | p95 | max |
|---|---:|---:|---:|---:|
| minor collections | 84 | 12.9 µs | 14.2 µs | 24.1 µs |
| major collections | 6 | 165.2 µs | 257.2 µs | 305.0 µs |

6.7% of wall clock, with 3.4 MiB promoted out of 89 MiB allocated. A major
collection scales with the live set: 10.4 µs at 10 000 live objects, 82.2 µs at
100 000, 316.8 µs at 400 000.
