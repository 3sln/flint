# 0031 — A Rust `Vec<Value>` is not a root, and it cost a day

> **DONE — found and fixed 2026-08-28.** `Rt::ordered_map` gathered values into
> a Rust `Vec<Value>` across calls that can collect. The collector does not scan
> Rust vectors, so everything gathered before the first collection went stale and
> was then written into the map. It made flint compiling flint trap. The same
> fault was found in the codec by the same reading and fixed with it.

## The symptom, and why it pointed at the wrong thing

Self-hosting started trapping:

```text
RuntimeError: memory access out of bounds
```

No flint frame, no message. It appeared when `src/flint/image.cljc` gained one
`def`, and went away when the same value was written as a literal — 64 bytes of
difference. So the first three hours were spent on the wrong question: *why does
adding a variable break the compiler?*

The table that made it look like a size cliff:

| what `emit` writes | image bytes | gen0 module | self-compile |
| --- | ---: | ---: | --- |
| nothing (baseline) | 162 017 | 571 426 | ok |
| `(u32 0)` | 162 029 | 571 434 | ok |
| `(u32 (if perf? 1 0))` | 162 070 | 571 475 | ok |
| `(u32 (if perf? FLAG-PERF 0))` | 162 134 | 571 539 | **traps** |

Every row is a real run and every row is misleading. The `def` is not the
variable. **The compiler's own source IS the input it compiles**, so changing
`flint.image` changes the program being compiled — and the fault is sensitive to
where a collection lands, which the input size moves.

Two wrong turns followed from that, both worth writing down because both were
reasoned from real evidence:

* **"The diagnostics build passes."** It did — but the spec had been regenerated
  from changed source in between. Once the input was frozen on disk, the
  diagnostics build reproduced it exactly. A discriminator is worthless if the
  thing being varied is not the thing you think.
* **"`--keep-names` fixes it."** Same artefact. The two modules turned out to
  have identical linear-memory layouts — same `__heap_base`, same
  `FLINT_IMAGE_DESC`, same initial pages — and both trapped on the same input.

**What ended it was freezing the pair.** A module and an input, both on disk,
both unchanging. Then the variable was findable in one cross-product: both
modules trapped on one spec and passed on the other. It was never the module.

## What it was

`--keep-names` on the module turns the trap into a stack:

```text
Gc::forward ← Gc::scan_object ← Gc::minor ← Gc::alloc ← Rt::cons
  ← Rt::list_from_roots ← Rt::enter ← run_with ← call_value ← Rt::force ← Rt::seq
```

and the diagnostics build — on the frozen input — names the write:

```text
stale_set:  count 28  object 3282480  slot 141  value 1427704
            object type 14 (TY_ARRAYMAP)  native 93  collection 185
stale_push: count 28  address 1427704  collection 185  pushes checked 24484265
```

`flint/array-map`. 28 stale values out of 24.5 million pushes checked, all at
collection 185, written into slot 141 of an array-map.

The code:

```rust
let mut flat: alloc::vec::Vec<Value> = alloc::vec::Vec::new();
while !self.r(si).is_nil() {
    let x = self.first(self.r(si));
    flat.push(x);                     // NOT a root
    let nx = self.next(self.r(si));   // CAN collect
    self.set_r(si, nx);
}
```

`first` forces a lazy seq and `next` forces the tail, which runs arbitrary flint
code. Both allocate. Every `Value` already in `flat` is an address into a space
that the collection has just evacuated, and they are then rooted (too late) and
written into the map.

The fix is to root them **as they are taken**, on the shadow stack, and read them
back from it after the allocation — the pattern `Rt::cons` and `Rt::rope_node`
already use correctly.

## Why nothing caught it

It needs three things at once:

1. a **lazy** input, so the walk allocates at all;
2. **enough pairs** to span a collection;
3. a build that **notices**, which is `stat_stale_set` / `stat_stale_push`.

`test/gc_stress.clj` already asserted both counters at zero with their coverage
printed beside them. It passed for the life of the runtime because nothing in
`test/gcstress.cljc` built a large map from a lazy seq. The gate was right and
the population was wrong — the same lesson as counting builtins twice.

`test/gcstress.cljc` now has `probe-ordered`. With the old code it does not just
raise the counter, it **traps the module**.

The reason it surfaced in the compiler and nowhere else: the reader builds map
literals with `flint/array-map` so that source order survives (two hosts
iterating a hash map differently is enough to break the fixpoint), and the
compiler's own map literals are the big ones.

## The same fault, one file over

Reading for the shape rather than the symptom found it again in
`Rt::encode_collection`: items walked into a `Vec<Value>` with `next` in the
loop, rooted afterwards. Its comment even said *"walked into a Vec first,
because encoding allocates"* — correct about the encode, silent about the walk.
Fixed the same way.

Every other `Vec<Value>` in the runtime was checked and is safe, for a reason
worth recording in each case:

* `Rt::rope_node` and `Rt::invoke` re-read from the shadow stack after
  allocating. This is the correct pattern.
* `Cursor::leaves` in the Pike VM is built by `code_points(&self, ..)` — an
  immutable borrow, so it **cannot** allocate. The type system carries the
  proof.
* The `map_for_each` gatherers in `codec.rs` and `conc.rs` collect through a
  callback that does not allocate, and root before doing anything that does.

## What must be true, and now is

- No value is held in a Rust container across a call that can collect.
- `test/gcstress.cljc` exercises a large map built from a lazy seq, and
  `test/gc_stress.clj` reads the stale counters with their coverage:
  **0 stale writes and 0 stale roots over 26 083 984 pushes and 201
  collections.**
- The self-hosting fixpoint passes with the `def` restored, which is how this
  started.

## What this says about the tooling

The instrument that named it — `stale_set` recording object, slot, value, type,
native and collection number at the instant of the write — is worth more than
the fix. It turned "somewhere in a 366 KB compile" into one builtin and one slot.

What cost the day was not the absence of an instrument but **a moving
reproducer**. `doc/decisions/0015` argues a snapshot beats a probe because it is
a copy rather than a question; the same argument applies one level up. Freeze
the input before varying anything else, and check that what you think you are
varying is what actually differs.
