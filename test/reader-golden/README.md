# The reader's golden set

What `flint.compiler.reader` + `flint.compiler.forms/encode` -- the compiler's
own reader, while there was one -- answered for a FROZEN set of inputs, kept so
the one reader that is left, the kin-generated one, still has an oracle
(`DECISIONS.md#one-reader-and-no-other`). `bin/check-reader` holds the native,
wasm, JVM and CLR copies of the kin reader to these bytes.

**Where it came from.** Captured 2026-10-08 at `8ce4a488` (the last commit with
the guest reader), by that tree's own guard:

    FLINT_READER_REF_DIR=<dir> cargo test --release -p flint-cli kin_reader

which read every file in `lib/`, `src/`, `corpus/` and the test fixtures through
the embedded compiler's `preread` mode under three option sets, and printed
`495 reads compared (165 files x 3 modes), 56 failed alike, 0 differ, 3 known
gap (test/reader/tagged-custom.fln)` -- the kin reader agreed with every one but
the known gap (`DECISIONS.md#reader-tags`). From those 495 reads this keeps:

* every input except `src/flint/compiler/*` (29 files, the largest and the
  least varied as reader input -- the compiler's own Clojure);
* the `deferred` read of each, and the two EAGER reads (`default`, `perf`) only
  for an input that holds a reader conditional, since without one all three
  read alike.

161 reads of 135 inputs, 1.5 MB.

**Layout.** `manifest.tsv` is `tag  mode  name  input`: `name` is the file name
the read was TOLD (it is in the bytes, as `:file`), `input` the frozen copy
under `inputs/`. Each read is `<tag>.forms` (the bytes) or `<tag>.err` (the
message a read that failed failed with).

**The inputs are copies on purpose.** With the guest reader gone nothing can
regenerate a reference, so a reference over the live tree would end up compared
with the reader under test. Do not refresh this set from the kin reader's own
output: that turns the check into a tautology. A deliberate change to what the
reader produces changes these bytes by hand, with the reason recorded in
`DECISIONS.md`.
