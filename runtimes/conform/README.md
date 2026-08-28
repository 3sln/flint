# Cross-host conformance

One source, compiled once, run on every runtime, and the answers diffed byte
for byte. Run it with `bin/conform-hosts`.

`doc/decisions/0010` is explicit about why this exists:

> The bytecode makes a port *cheap*. It does nothing to make two ports *agree*.

A port that runs is not a port that is correct, and the difference is invisible
without this: every failure it has caught so far produced the **right elements
in the wrong shape**, which no crash would have revealed.

## What it has caught

* **`(rest [1 2 3])` printed as `[2 3]` rather than `(2 3)`.** The JVM port had
  one list type, so a seq and a vector were the same object. They are `=` to
  each other and they print differently, and `pr-str` is how answers are
  compared — so every test that printed a seq disagreed, silently, with the
  right elements in it.

* **Map iteration order.** flint's maps are an array-map up to eight entries
  and a CHAMP past that, so a large map iterates in HASH order. The port used
  insertion order, so a nine-key map printed the right pairs in the wrong
  sequence. Closed by porting the hash bit-for-bit — it is pinned against the
  same vectors `runtime/src/hash.rs` asserts, which were solved against real
  JVM Clojure, so all three agree — and by walking a CHAMP the way `map.rs`
  does: inline entries in bit order, then sub-nodes.

* **`(keyword "key0")` produced `:key0/`.** `flint/keyword2` means the NAME
  with one argument and `(ns, name)` with two; reading argument 0 as the
  namespace regardless gave a keyword with an empty name. It printed almost
  right and matched nothing, so every lookup keyed by one silently missed.

## The floor

`bin/conform-hosts` asserts a minimum number of agreeing cases rather than
demanding all of them, because the port is not finished and a known divergence
is worth keeping visible rather than deleted. What must not happen is the
number going down.
