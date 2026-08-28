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

* **Map iteration order**, which is still open. flint's maps are a CHAMP and
  iterate in hash order; this port uses insertion order. `0010` singles this
  one out as not cosmetic: `pr-str` of a map is how an answer is compared, and
  content-addressed artifacts would hash differently per host. Closing it means
  porting flint's hash and the CHAMP's ordering, not choosing a different Java
  map.

## The floor

`bin/conform-hosts` asserts a minimum number of agreeing cases rather than
demanding all of them, because the port is not finished and a known divergence
is worth keeping visible rather than deleted. What must not happen is the
number going down.
