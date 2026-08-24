# Handoff — the document wave loss, closed

Fixed in `5d2071e`. `test/document.clj` reports 64 waves and 4 194 304 bytes,
`test/gc_stress.clj` is clean on every standing check, and both builds pass.

## The bug, in one paragraph

`Rt::port_send` handed its Rust parameter `v` to `check_sendable` **before**
pushing it as a root. Checking a sequential value walks it, walking allocates,
allocating can collect — and the local came back holding the address the object
had *before* the flip. That stale address was then pushed as a root, carried
through `port_enqueue` into `vec_conj`, and written into the inbox tail with
`node_set`.

Nothing downstream could see it, and that is the part worth keeping:

* `is_young(addr)` is `addr.wrapping_sub(young_base) < half * 2` — it spans
  **both** semispaces. A pre-flip address still tests young, so the write
  barrier passes and the generational invariant passes.
* `forward()` returns anything outside from-space unchanged, so once the pointer
  is in a live object it survives every later collection and is copied verbatim
  into every clone.

One wave in sixty-four went missing and the run reported success.

## What is now standing, and where

Four checks, all `#[cfg(feature = "diagnostics")]`, all asserted by
`test/gc_stress.clj` and the wave run in `test/document.mjs`:

| check | where | what it asks |
| --- | --- | --- |
| `stat_stale_push` | `Rt::push` | is a value **stale as it is rooted**? This is the exact signature of a Rust local carried across an allocation, and it fires one step before the damage. |
| `stat_stale_set` | `Gc::set_slot` | is a value **stale as it is written**? Names the native that wrote it. |
| `stat_stale_root` | end of `Gc::minor` | does any root point into the half just abandoned? Over every root array. |
| `stat_stale_shadow` | first stale push | the whole shadow stack, so the frame that owns the bad slot is **read off rather than inferred**. |

Plus the two from before: `stat_remset_violations` (old→young edges are all
remembered) and `stat_dead_half` (no live object points into the dead half).

`Gc::in_live_half(a) = a >= from && a < bump` is the predicate that does the
work. It is the only thing in the runtime that can tell a live young pointer
from a pre-flip one.

## The standard this investigation ended at

Five rules, each of which was bought with a wrong answer:

1. **Measurements over readings.** Six opcodes were eliminated by a counter
   after an afternoon of eliminating none by reading. `flint/pow` briefly looked
   like the culprit because a builtin slot was resolved through the wrong index
   space; the image's own native-name table settled it in one run.
2. **Coverage is counted before a zero is trusted.** `forward()`'s counter
   reported zero out of sixty-four and looked like a result — a message whose
   edge is never traced is never forwarded, so the check could not have fired.
   Every standing check above reports the number of things it walked, and the
   test fails if that number is zero.
3. **State the question the failure mode poses before believing a clean
   result.** A walker aimed at old space "visited 0 times" on a young object. A
   watch address registered against a young port went stale on promotion and
   produced a confident wrong answer.
4. **Walking too much is the same failure as walking too little.** Young space
   is never swept, so a linear walk of it sees discarded intermediates; a dead
   node holding a pre-collection address is garbage, not a bug. Gating the frame
   scan on reachability was what stopped that reading.
5. **An instrument that asks an invariant is a guard and lives forever; one that
   asks about a suspect is scaffolding and goes when the suspect is settled.**
   `b731387` retired nearly three hundred lines on that rule.

And one about honesty, from earlier in the same investigation: a module that
grew 3 665 bytes was attributed to two commits that touch only `doc/`. The
growth was un-reverted scaffolding of my own. *"I would rather you had told me
'my scaffolding is still in and I have not measured what it costs' than
attributed it."*
