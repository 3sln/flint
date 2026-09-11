# Goal — a collision node must cost what it scans

## Status

**FIXED**, and pinned by `test/gas.clj`. Kept because the finding is worth more
than the patch: the hole was invisible to every gate in the tree, and the
reasoning that nearly skipped it is recorded in full below.

## The hole

A map lookup on keys that share a hash scans the whole collision node and is
billed a flat 16 steps, whatever the node holds:

| map | time per lookup | gas per lookup |
| --- | --- | --- |
| 16 384 keys, ONE hash | 179.05 us | 16.0 |
| 16 384 keys, distinct | 1.70 us | 16.0 |

**105x the work, the same bill.** And 16.0 is flat at 1 024, 4 096 and 16 384
colliding keys -- the attacker picks the multiplier, and gas does not move.

`DECISIONS.md#resource-limits` is the promise this breaks. `kin/collhash.kin` names the
class exactly, about a defect that was fixed rather than tolerated:

> it is not a slow path, it is a metering hole: the first version of this
> walked without charging at all, which made hashing a two-hundred-thousand-
> entry map free to untrusted code.

## Why it is reachable rather than theoretical

**Flint's string hash is a base-31 polynomial**, the same family as Java's
`String.hashCode`, so the classic construction works: `Aa` and `BB` hash alike
because `'A'*31 + 'a' == 'B'*31 + 'B'`, and the property composes. Concatenating
a choice of `Aa` or `BB` at each of k positions gives **2^k strings with one
hash**. Verified here -- every pair below collides:

    Aa/BB  AaAa/BBBB  AaBB/BBAa  xAay/xBBy  prefix-Aa/prefix-BB  Ca/DB  Ba/CB

So any program that puts attacker-influenced strings in a map or set -- a
header table, a query parameter, a JSON object, a symbol table -- can be made
to do unbounded uncharged work.

> This was nearly missed. `bench/progs/equiv.cljc` first said a collision mode
> was NOT COVERED because engineering a collision "needs flint's hash rather
> than a guess -- `Aa`/`BB` collide under Java's `String.hashCode` and mean
> nothing here". That was a guess, in the direction of not testing, and it was
> wrong. Asking flint for the hashes took one probe.

A separate and reassuring measurement, from the same probe: the hash is
INJECTIVE over 400 000 sequentially-structured strings (`key-0` .. `key-399999`),
confirmed by a sort-based check that uses no hashing. A polynomial hash is
near-injective on structured input, which is why a birthday-paradox estimate
does not describe it. Collisions here are adversarial, not accidental.

## Where the charge is missing

Three sites scan a collision node without charging. A fourth already charges,
which is what the fix should match.

| site | charges? |
| --- | --- |
| `kin/find.kin`, scalar path | NO |
| `kin/find.kin`, compound path | NO |
| `kin/assoc.kin` | NO |
| `kin/dissoc.kin` | NO |
| `kin/mapeq.kin` | YES -- `charge-work rt 1` per entry |

`mapeq` is the newer file and was written with the metering lesson already
learned. The lookup paths predate it.

## The fix, and what it cost

`charge-work rt 1` per entry examined, matching `mapeq`. One generated source,
so all four runtimes moved together and no cross-runtime divergence was
created -- `conform-hosts` confirms they still agree to the step.

MEASURED AFTER, and the shape is exactly right:

| keys | gas before | gas after |
| --- | --- | --- |
| 1 024 sharing one hash | 16.0 | 1 040.0 |
| 4 096 sharing one hash | 16.0 | 4 112.0 |
| 16 384 sharing one hash | 16.0 | 16 400.0 |
| 16 384 DISTINCT | 16.0 | **16.0** |

`16 + entries scanned`, and legitimate code is unchanged to the step -- a map
with no collisions never goes round the loop twice. That was the property to
verify rather than assume, and it holds at every size.

## The gate

`test/gas.clj`, which already existed to say "no operation does unbounded work
for bounded gas" and whose method is to run at n and at 8n. Two rows: a
flooded lookup that must scale, and the same shape with distinct keys that
must not. Without the control, a runtime charging by map SIZE rather than by
work scanned would pass the case and still be wrong.

VERIFIED TO FAIL against the unfixed runtime rather than merely to pass against
the fixed one. Unfixed it reads:

    a flooded lookup is billed               11         11      1.0

Eleven gas at 200 colliding keys and eleven at 1 600 -- byte for byte the
O(1) control. Fixed it reads 211 and 1 611.

> The first version of the gate measured 2.1x and failed. The op built the
> 24-character probe key inside the measured arm, adding a fixed ~1 021 gas
> that the baseline did not pay. The tell was that 1 221 and 2 621 differ by
> exactly 1 400 for exactly 1 400 more entries: the scan was already billed
> per entry and the MEASUREMENT was wrong. Building the key in the setup, where
> it cancels, gives 7.6x.

## Reproduction

`/tmp` probe, kept here because the shape is the point:

```clojure
(defn- keys-of [k]                       ; 2^k strings sharing one hash
  (loop [i 0 acc [""]]
    (if (< i k)
      (recur (inc i) (vec (concat (mapv (fn [s] (str s "Aa")) acc)
                                  (mapv (fn [s] (str s "BB")) acc))))
      acc)))
```

Build `(into {} (mapv (fn [s] [s 1]) (keys-of 14)))`, look one key up R times,
and difference `stat_steps()` between R=0 and R=20000. Compare against the same
map built from `(str "distinct-" i)`.
