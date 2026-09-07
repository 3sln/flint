# The collections, against canonical Clojure

An audit of what flint's data structures do against what Clojure's do, with a
measurement behind every claim. Reproduce the timings with
`./target/release/flint run :path <dir> :fn <ns>/main`; the JVM ones are
straight `java -cp runtimes/jvm/classes`.

## What already matches

* **PersistentVector**: a 32-way trie WITH A TAIL, which is what makes `conj`
  amortised O(1). Same shape as Clojure's.
* **Sets are their backing map**, element to itself -- Clojure does the same,
  and it is why `Sets.eq` is `Maps.eq` and `disj` is `dissoc`.
* **Transients** are used where Clojure uses them: `into`, `mapv`, `filterv`,
  `frequencies`, `group-by`, `merge`, `zipmap`. `distinct` and
  `clojure.set/union` are persistent, and so are Clojure's.
* **`TY_CONS` carries its count**, so `count` on a list is O(1).
* **Sorted collections are absent**, and that is deliberate and recorded in the
  README rather than an oversight.
* **The map is a CHAMP**, which is a better structure than Clojure's HAMT --
  see below, because for a long time nothing used the difference.

## Fixed

### Map equality ignored everything a CHAMP is for

All three runtimes walked every entry of `a` and did a full `map-get` descent
into `b`, and never looked at `b`'s structure. Per comparison, 20,000 entries,
on the JVM:

| | before | after |
|---|---|---|
| independent equal maps | 877us | 329us |
| sharing structure | 840us | **1.15us** |

877 and 840 being the same number is the finding: structural sharing bought
nothing. Now in `kin/mapeq.kin`, generated for all three. Clojure cannot do
this -- a HAMT is not canonical -- and ours is.

### `nth` on an ASCII rope flattened on the ports

Native descends every rope; both ports flattened ASCII ones, so `(nth s i)`
materialised a large rope on the JVM and the CLR and not on native. Measured:
`RP_FLAT` went nil to non-nil across one `nth` on a 2,700-character rope.

The cause is worth keeping: native's comment still described the OLD policy
("ASCII: FLATTEN, once", with a 3.05x measurement), the code under it had
stopped doing that, and both ports were written from the comment. A conform
diff cannot see it, because both answers are right.

## Open, in the order I would take them

### 1. Chunked seqs are declared and never built

`TY_CHUNKSEQ` is in the type table and the seq predicates recognise it.
Nothing constructs one. `map`, `filter`, `keep` and `mapcat` are lazy one
element at a time -- a thunk and a cons per element -- where Clojure allocates
one chunk per 32.

Measured over 2M elements: `(reduce + 0 v)` is 0.28s and
`(reduce + 0 (map inc v))` is 0.84s. **One lazy stage triples it.**

The largest measured cost here, and half-built already.

### 2. `reduce` has a fast path only for vectors

Everything else goes through `reduce-seq`, one seq cell per element. Clojure
has internal reduce (`IReduceInit`) for maps, sets and ranges. Over 2M
elements, each against its own build baseline:

| collection | reduce | vs vector |
|---|---|---|
| vector (index walk) | 0.28s | -- |
| set (seq path) | 0.36s | 1.3x |
| map (seq path, plus a `MapEntry` per pair) | 0.65s | 2.3x |

`reduce-kv` is `(reduce (fn [acc e] (f acc (key e) (val e))))`, so it allocates
a map entry per pair only to take it apart again.

RANGES DO NOT NEED THIS. Measured within ~12% of the vector path, which is not
worth a special case -- checked so that nobody adds one on principle.

Note what blocks it: iterating a map or set from inside the runtime is the
closure hole. This is the same decision, wearing different clothes.

### 3. `subvec` copies where Clojure shares -- and `sort` no longer cares

`(subvec v start end)` is `(loop [acc []] (conj acc (nth v i)))` -- O(n).
Clojure returns an O(1) view. Measured: 200 slices of a 50,000-element vector
cost 1.20s of pure copying.

A transient makes NO difference (2.58s against 2.59s), so the cost is the
per-element loop running interpreted, not persistent allocation. That leaves
two real options: a native `vec-slice` in kin, which keeps it O(n) but moves
the constant into the runtime, or a genuine view.

A view is the Clojure answer and it needs a new heap type handled everywhere a
vector is -- `nth`, `count`, `seq`, `assoc`, `conj`, `pop`, `hash`, printing,
equality. THE RETENTION QUESTION IS ALREADY ANSWERED IN THIS REPOSITORY: ropes
share their interior but `SLICE_MIN` makes a small slice copy, so a short
`subs` cannot pin a megabyte. Whatever is done for vectors should say the same
thing.

**Its only caller in the standard library was `merge-sort`, and that is
fixed.** `sort` split with `subvec` at every level and merged through
`first`/`next` with a persistent `conj`. Sorting 20,000 numbers:

| | time | allocations | bytes | collections | per element |
|---|---|---|---|---|---|
| top-down, `subvec` | 252.6 ms | 1,493,957 | 109.2 MB | 57 | 74.7 |
| bottom-up, transient | 129.3 ms | 29,749 | 3.1 MB | 1 | 1.5 |

`bin/test` now holds sort under ten allocations per element.

So `subvec` is still O(n) where Clojure is O(1), but nothing in the standard
library is paying for it any more. That lowers the priority rather than
closing it: user code calls `subvec` too.

## Method

Two habits that this audit needed and would have failed without:

**Subtract a build baseline.** The first reduce comparison said a set was
faster than a vector; it was measuring 200,000 `assoc` calls, not the reduce.

**And when the thing being measured is allocation, do not measure the clock.**
The first reading of the sort rewrite said it was slightly SLOWER -- 0.54s
against 0.50s -- because process startup and building the input swamped it. On
`bench/colls.mjs`, which subtracts its own setup and counts allocations, the
same change is 50x fewer allocations and roughly twice as fast. `colls.cljc`
says this in its own header, and I measured the wrong thing anyway.

**Check the assumption the algorithm rests on.** Structural map equality is
only valid because the CHAMP is canonical, which was verified across
ascending, descending, `dissoc`-from-above and transient construction at five
sizes -- and then collision nodes turned out NOT to be canonical, which is a
special case in the code rather than a bug in the field.
