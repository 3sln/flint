# The corpus

Real programs, each run unmodified on **JVM Clojure**, **babashka** and **flint**.
Two jobs, one set of files:

* **`bin/corpus`** checks that all three give the same answer. JVM Clojure is the
  reference; a program flint answers differently is either a flint bug or a
  divergence the program declares.
* **`bin/bench-corpus`** times the same programs on the same three, so a speed
  claim is always about code that has been shown to compute the same thing.

## The contract

1. **One entry namespace per program**, `corpus/<name>.cljc` holding
   `(ns <name> ...)` (a `-` in the name is `_` in the file). Only top-level
   `.cljc` files are programs. A program of several namespaces keeps the rest in
   a subdirectory, on the same classpath: `corpus/construe/bench/*.cljc` is
   construe's code, which `construe-parse` and `construe-suggest` require, and
   `corpus/construe/typed/` is the annotated copy under its own namespaces so
   both can be loaded at once.
2. **`(defn main [_] ...)` returns a string** -- the answer. No arguments are
   passed, so each program runs at its own default size, and that size is the one
   the benchmark measures.
3. **Deterministic.** No `rand`, no clock, no hash-order-dependent output: sort
   before printing a map or set.
4. **Portable `.cljc`, unmodified.** `clojure.math` rather than `Math/` interop, no
   host classes, no ratios, no reader conditionals that change what the program
   computes.
5. **Metadata on the `ns`**, read by both scripts:

   | key                   | meaning                                           |
   |-----------------------|---------------------------------------------------|
   | `:corpus/kind`        | what it stresses: `:numeric` `:collections` `:strings` `:parser` `:search` `:graph` `:alloc` |
   | `:corpus/annotated`   | `true` if it carries type hints; the untyped twin, if any, is `:corpus/twin` |
   | `:corpus/twin`        | the other half of an annotated/unannotated pair, as a STRING -- JVM Clojure evaluates `ns` metadata, so a bare symbol there is resolved as a var and fails to compile |
   | `:corpus/source`      | where the algorithm comes from                    |
   | `:corpus/diverges`    | `{:flint "why"}` -- a KNOWN difference, reported as such and never counted as agreement |

## Why declared divergences exist

flint deliberately differs from Clojure in a few places (`README.md` "Chars are not
a type"; no ratios; no host classes). Real code runs into those, and a corpus that
quietly avoided them would say less about real code than one that names them.
A program that hits one keeps its natural form and declares the divergence;
`bin/corpus` reports it as `--` with the reason, fails it if Clojure or babashka
cannot run it either, and says so when a declared divergence has gone away.
Where there is a portable way to write the same thing, it is a separate program
(`caesar` and `caesar-portable`).

Declared now: `caesar` (chars are strings) and `dijkstra` (no sorted
collections). Both are gaps README.md's "Limits" already lists; the corpus is
where they meet code written the ordinary way.

Not in the corpus: `bench/progs/wordsstr.cljc`, which splits on a string --
flint's `str/split` takes one and JVM Clojure's takes only a regex. It stays an
instrument for flint's regex cost. The `bench/progs` programs that call
`flint.rt/*` are instruments too, not programs anybody would write.

## Annotations: flint accepts more than Clojure does

flint reads `^long`/`^int` on any binding as a checked claim. JVM Clojure refuses a
hint on a local whose initializer is already primitive -- "Can't type hint a local
with a primitive initializer" -- so `(loop [^long n 0] ..)` and
`(let [^long c (count xs)] ..)` compile in flint and not in Clojure.

The `:corpus/annotated` programs therefore use only hints Clojure accepts:
parameters, and locals bound from an object. That makes them portable; it also
means they carry fewer hints than flint could use, so they measure what PORTABLE
annotations buy, which is the number that matters for code that has to run in
both places. Found by the first typed twins failing on the reference runtime.

## Timing: `bin/bench-corpus`

Times every program flint runs (declared divergences are skipped) and prints
the machine, the commit, whether the tree was dirty, the tool versions and the
load average above the numbers, so a pasted table carries its own provenance.

* **Steady state.** JVM Clojure and babashka run ONE file,
  `bench/corpus_time.clj`, so the two cannot differ in method; flint runs
  `bench/corpus.mjs` over a compiled module in node, once as built and once
  `:optimize [perf]`. Each: call `main` until 300 ms and three calls have
  passed, then the best of `REPS` (default 10) timed calls.
* **End to end.** One process per run, best of 3: `clojure -M -e`, `bb -e`,
  `flint run`. `flint run` compiles the program from source every time, so this
  column is compile plus run -- which is what a person at a terminal waits for.
* **A wrong answer prints `wrong`, never a time.** Every arm's answer is
  compared with JVM Clojure's before its number is shown.
