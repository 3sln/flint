# The runtime-agnostic suite

Written in flint, so it runs wherever flint runs. One source, one image, and
every runtime — wasm, native, JVM, CLR — executes the same checks.

## Why this is not `runtimes/conform`

`runtimes/conform/*.cljc` prints a value and the harness DIFFS the transcripts
across runtimes. That is a differential test, and it answers exactly one
question: do the runtimes agree?

It cannot answer whether they are RIGHT. Both ports once read a double's
mantissa as an integer, so `numbers` said `:float [0 0 0]` on both and the
cross-port compare saw nothing wrong — the divergence check had nothing to
diverge from. Only comparing against the native answer caught it, and that
only works while one runtime is trusted.

These files close that gap by writing the expected answer down. `(expect = 3
(+ 1 2))` is wrong on every runtime at once or on none, which is the property
a differential test structurally cannot have.

## Why it belongs here rather than per-runtime

Nothing in this directory names a runtime, opens a port, reaches for a
capability, or touches an SDK. Every check is a statement about the LANGUAGE:
what `assoc` does to a vector, when a map becomes a CHAMP, whether a lazy seq
stays lazy. A statement like that has no business being written four times.

A check that needs the innards of one runtime — heap layout, image format,
host ABI — belongs beside that runtime, not here. The dividing line is whether
the check would still make sense if a fifth port appeared tomorrow.

## Running them

    flint test :src test/common          # on the wasm runtime, via node
    bin/conform-hosts                  # the same image on native, JVM and CLR
