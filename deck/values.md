# Values and data

## The value encoding

A `Value` is 64 bits, NaN-boxed. Every IEEE-754 double is stored as its own bit
pattern *except* the negative-NaN range from `0xFFF9…` upward, which is stolen
for tags. Any NaN produced by arithmetic is canonicalised to the positive quiet
NaN `0x7FF8_0000_0000_0000`, which is not in the stolen range, so no observable
double is lost — NaN payloads are not observable in Clojure.

| `bits[63:48]` | meaning |
|---|---|
| `< 0xFFF9` | an IEEE-754 double, verbatim |
| `0xFFF9` | **heap** — `payload[31:0]` is a byte address in the GC heap |
| `0xFFFA` | **fixnum** — `payload[47:0]`, 48-bit two's complement |
| `0xFFFB` | **special** — `nil`, `false`, `true`, and internal sentinels |
| `0xFFFC` | **inline string** — `payload[47:40]` length 0–5, `payload[39:0]` UTF-8 bytes |
| `0xFFFD` | **inline keyword** — no namespace, name ≤ 5 bytes, same shape |
| `0xFFFE`, `0xFFFF` | reserved |

Three consequences worth stating:

**Chars are not a type.** A char is a one-character string, and every character
of Unicode is at most 4 UTF-8 bytes, so every char is an immediate. `\a` reads
as `"a"`, `(nth "abc" 1)` is `"b"`, and `(hash \a)` is `(hash "a")`.

**Equality is usually one compare.** Inline form is canonical, and every string
up to 32 bytes is interned, so two short strings are equal iff their bits are;
keywords are *always* equal iff their bits are. Only strings longer than 32
bytes need a byte comparison. Symbols compare `(ns, name)` instead of identity,
because `with-meta` must produce a distinct object that is still `=` — and those
two slots are themselves inline-or-interned, so it is still two 64-bit compares.

**Integers are 64-bit, in two representations.** Up to 48 bits they are
immediate; beyond that they are heap-boxed `i64`. The representation is
canonical — an `i64` in fixnum range is *always* a fixnum — so integer equality
never has to consider two forms of the same value. There is no `BigInt`:
`+`/`-`/`*` throw on `i64` overflow exactly as Clojure's do, but there is no
`+'` to promote to.

Heap objects have an 8-byte header and one of three layout classes: `Vals`
(every slot is a `Value`, which makes tracing one loop with no per-type
knowledge — the single biggest source of GC bugs removed), `Str` (a cached hash
and an ASCII flag, then UTF-8 bytes), and `Raw` (opaque bytes).

## Data structures

Persistent list, vector, map and set, all with transients.

**Vectors** are Clojure's shape, because Clojure's is right: a 32-way trie with a
tail, so `conj` is a 32-element array copy amortised to O(1) and `nth` is at most
`depth` indexed loads. What differs is bookkeeping — a node is a normal GC object
whose slot 0 is the transient ownership token.

**Maps and sets are CHAMP**, not Clojure's HAMT (Steindorfer & Vinju, OOPSLA
2015; the same line of work ClojureDart's map improvements draw on). A CHAMP node
carries **two** bitmaps — `datamap` for entries stored inline, `nodemap` for
sub-nodes — with entries packed at the front and sub-nodes at the back:

```
  TY_BMNODE   [edit, datamap, nodemap, k0,v0, k1,v1, …, nodeN…node0]
  TY_COLLNODE [edit, hash, k0,v0, …]
```

Three things fall out, and all three matter here:

1. **Nodes are smaller and denser.** Clojure's `BitmapIndexedNode` stores a
   `null` key beside every sub-node pointer, wasting a slot per child, and
   promotes to a 32-wide `ArrayNode` at 16 children. CHAMP needs neither.
2. **The representation is canonical.** Clojure's HAMT can represent the same map
   two ways depending on insertion and deletion history, because deleting does
   not un-inline a node that has shrunk back to one entry. CHAMP always
   collapses. There is a test that builds the same 400-key map forwards,
   backwards, and via 200 insert-then-delete round trips, and asserts all three
   have byte-identical trie shape.
3. **Iteration needs no per-slot type test.** Entries are exactly the first
   `2·popcount(datamap)` slots.

Below `ARRAY_MAP_MAX` (8) entries a map is a flat insertion-ordered array-map, as
in Clojure. For a compiler — the workload on flint's own critical path — most
maps are AST nodes with a handful of bit-comparable keyword keys, and a linear
scan beats descending a trie.

**Transients** own nodes by object identity: the edit token is a freshly
allocated object used only for its identity, so "this transient owns that node"
is a pointer compare that survives a moving collector. `persistent!` nulls the
token, which turns the classic transient bug — a stale handle mutating a value
somebody else now holds — into a detectable state rather than silent corruption.

Sets are maps from element to itself. That costs a slot per element compared with
a set-specific node layout; it buys `get` returning the *stored* element (which
is what Clojure does, and what makes sets usable for canonicalisation) and one
trie implementation instead of two.

**Hashing is bit-compatible with JVM Clojure.** `(hash [1 2 3])` is 736442005
here as there. The formulas were derived by solving against real Clojure values
rather than from memory, which caught two things: strings hash as
`Murmur3.hashInt(String.hashCode())` rather than `hashUnencodedChars`, and a
symbol's *namespace* contributes its raw Java string hash while its *name*
contributes the murmur'd one. Both are pinned by tests, including an
astral-plane case for the UTF-16 view over our UTF-8 strings.
