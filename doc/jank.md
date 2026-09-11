# flint against jank's dialect suite

[jank](https://github.com/jank-lang/jank) is a Clojure dialect on LLVM. Its
tests are a clean contract, which is why they can be run against a different
implementation at all:

    pass-*.jank   must run to completion
    fail-*.jank   must be REJECTED -- a compile error or a throw

Run them with `bin/jank-suite <jank-checkout> [native|jvm|clr]`. The numbers
below are jank `cd394c63e15d1af5f88bcfbec74fba7873e4b57d` (2026-08-29); a
different revision is a different suite, so quote the one you ran.

## The numbers

    native                     port (jvm)
    pass-*   130 / 195         130 / 195
    fail-*    60 /  99          64 /  99
    ---------------------------------------
    TOTAL    190 / 294 (64%)   194 / 294 (65%)

**The `pass-*` halves are identical, test for test.** Not the same COUNT --
the same SET: `comm` over the two failure lists is empty in both directions, so
the native runtime and the port fail the same 65 and pass the same 130.

Equal counts would not have been that claim. Two runtimes can score 130 apiece
while disagreeing about twenty tests in each direction, and that is exactly
what a pair of nearly-mirrors looks like. The set being identical is what says
they are the same runtime.

The `fail-*` difference is NOT the ported runtime being stricter than the Rust
one. It is an artefact of the harness having to take two different routes:
native runs through `flint run`, which compiles and executes in one process,
while the ports must go through `--emit-image` and then load the image. Those
are different COMPILE paths, and the image writer refuses things the direct
runner does not -- `(fn* 1)` is rejected by one and accepted by the other.

There is no way to make them the same route, because the native CLI has no
mode that runs a pre-built image. So the four-test gap is recorded as what it
is rather than claimed as a win: the ports look stricter because their compile
path is, and the runtimes themselves are not being compared on those four.

Two real port bugs came out of DIFFING the two failure lists, neither
reachable from flint's own conformance set:

* `meta` was a STUB returning nil. Nil is indistinguishable from "no
  metadata", so it passed everything that never set any.
* Arity selection took whichever clause came first, where the Rust prefers an
  EXACT fixed arity over a variadic one. So `(fn ([] :a) ([& xs] xs))` and the
  same two clauses reversed gave different answers for zero arguments -- the
  same function, written two ways. jank's `pass-ambiguous` writes out all six
  orderings precisely because that is the trap.

**The port scored 201 before this was believable.** It ran a program's
initialisers and ignored what they threw: a flint throw is not a host
exception, so a test whose top-level `assert` FAILED ran on to `main`, which
reported success. The harness was counting failed assertions as passes, and the
tell was that the port beat the runtime it mirrors. A port that wins is a
measurement problem.

Before that it scored 0/195 and 99/99 twice, for two different reasons -- a
`:ok` marker the harness printed as hex, and a `JAVA_HOME` with no `bin/java`
under it. That shape is worth recognising: a PERFECT score on the half that
expects rejection is not a runtime refusing everything, it is a harness
recognising nothing.

Sixteen further files follow neither naming convention and assert nothing, so
there is no claim to make about them. One test did not terminate inside twenty
seconds and is counted as a failure -- see below, because it is the most
interesting single result.

## What is excluded, and why it is not a dodge

`test/jank/cpp` is 514 tests of **C++ interop**, and they are not run.

flint has no host classes at all. That is `DECISIONS.md#other-hosts`, and it is the
whole point of the project: a flint module is a self-contained wasm binary with
its own heap, its own collector and its own library, so there is no host to
interoperate with. There is nothing to be "close to" there, and a runtime that
answered those tests would not be flint. Including them to report 190/808 would
be arithmetic, not information.

What remains is 310 files of **language**: the special forms (`case def do fn
if let letfn loop map nil set throw try vector`), the reader, syntax quote,
vars and metadata. Language is exactly what should port, and it is where a
divergence would be a real one.

## What the 104 failures are

Categorised by CAUSE rather than by directory, because the directories are an
accident of jank's layout and the causes are the answer:

| n | cause |
|---:|---|
| 38 | flint **accepts** a form jank rejects |
| 14 | `letfn*` is not implemented |
| 12 | failed with no message |
| 12 | an unresolved symbol in the test's own preamble |
|  7 | ran, but an assertion did not hold |
|  7 | C++ interop leaking into a language test (`cpp/raw` inside `form/`) |
|  3 | Ratio literals -- flint has no Ratio, deliberately |
|  3 | `recur` across a `try` boundary |
|  1 | an integer literal larger than 64 bits |
|  1 | did not terminate |
|  5 | one-off, listed by `SHOW_FAILURES=1` |

**The largest group is not a bug in what flint computes.** Thirty-eight tests
are `fail-*` cases that flint runs happily: duplicate keys in a `case`, two
variadic arities on one `fn`, a parameter after `& rest`, `letfn` with a
malformed binding vector. flint's analyser is PERMISSIVE where jank's is
strict. Every one of these is a program no sane author writes, and each would
need its own check in the analyser -- which is why they are reported rather
than fixed. A compiler that refuses more is better, but "refuses more" is a
long tail of individually small rules, and the brief was to find out where we
are without changing much.

`letfn` is the one clean gap: fourteen tests, one missing special form, and
flint says so by name -- *"letfn* is not implemented; use let with fns that do
not refer to each other"*. Mutual recursion in a `let` needs the bindings to
exist before their initialisers run, which is a real feature and not an
oversight.

Seven failures are C++ interop that leaked out of `cpp/` into the language
directories -- a `form/loop` test whose subject is `cpp/raw`. Those are not
about `loop` at all, and excluding them would be defensible; they are counted
as failures here because drawing that line test-by-test is how a number stops
being trustworthy.

## The most interesting failure

`(recur)` at the top level. jank rejects it; flint accepts it and loops for
ever, which is why the harness needs a per-test clock at all.

This is not a bug so much as a consequence: flint compiles each top-level form
as a thunk, so a bare `recur` **is** in a function's tail position and recurs to
that thunk. `(defn f [] (recur))` loops in Clojure too, and correctly. The
divergence is only that Clojure's compiler refuses `recur` across the `eval`
boundary and flint's does not notice there is one.

Fixing it means teaching the analyser that a top-level thunk is not a recur
target. That is a small change in one place, and it is left undone deliberately:
the brief was to find out how close flint gets *without changing much*, and one
test is a poor reason to add a special case to the analyser. It is written down
here instead, which is the honest form of "we know".
