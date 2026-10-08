# flint against jank's dialect suite

[jank](https://github.com/jank-lang/jank) is a Clojure dialect on LLVM. Its
tests are a clean contract, which is why they can be run against a different
implementation at all:

    pass-*.jank   must run to completion
    fail-*.jank   must be REJECTED -- a compile error or a throw

Run them with `bin/jank-suite <jank-checkout> [native|jvm|clr]`. The numbers
below are jank `cd394c63e15d1af5f88bcfbec74fba7873e4b57d` (2026-08-29); a
different revision is a different suite, so quote the one you ran.

**AND THE SAME IS TRUE OF THE OTHER SIDE, which this file did not say.** A score
here is a claim about two trees and it named only jank's. The flint commit these
numbers came from is not recorded and cannot be recovered from the file; the
last change to it is `8cee498d`, 2026-09-11, so they are no newer than that and
predate the kin port's later work. As of 2026-09-26 `bin/jank-suite` prints both
-- `flint <sha> on <date>` and `jank <sha>` -- so a future capture carries its
own subject as well as its input.

**RE-RUN 2026-09-29 AFTER `letfn*` LANDED**, and again against a sparse checkout of
jank at `0169e88`
(`git clone --depth 1 --filter=blob:none --sparse` and
`git sparse-checkout set compiler+runtime/test/jank` -- 4 MB, tests only, which is
all `bin/jank-suite` reads). The figures this replaces stand recorded below,
because both moved and so did the DENOMINATOR: jank has added a `fail-*` test
since, so 294 and 295 are scores over two different suites and the old total was
never comparable to the new one by subtraction.

## The numbers

    flint dcf8b860, jank 0169e88, 2026-09-29

    native                     port (jvm)
    pass-*   146 / 195         146 / 195
    fail-*    62 / 100          64 / 100
    ---------------------------------------
    TOTAL    208 / 295 (70%)   210 / 295 (71%)

    +2 is `case`: a vector or set test constant was expanded into a chain of
    ALTERNATIVES, where Clojure gives that meaning to a list alone. See
    `test/conform/basics.cljc`, which pins all six shapes against babashka.

    A later commit (`c756151f`) does NOT move this and was checked rather than
    assumed: it makes a thrown non-exception name its kind, so 13 failures that
    reported nothing now report "a keyword was thrown, with no message". The
    `catch` clauses in those tests name a jank host type, so they still fail --
    legibly instead of silently.

    flint 10cc7241 (before the `case` fix)
    pass-*   144 / 195         144 / 195
    fail-*    62 / 100          64 / 100
    TOTAL    206 / 295 (69%)   208 / 295 (70%)

    the two runs before it, for comparison:

    flint 753b5baf, 2026-09-28 (before `letfn*`)
    pass-*   131 / 195         131 / 195
    fail-*    62 / 100          64 / 100
    TOTAL    193 / 295 (65%)   195 / 295 (66%)

    undated, before this file recorded its own subject
    TOTAL    190 / 294 (64%)   194 / 294 (65%)

**+13 IS `letfn*`, AND IT COST 4 BEFORE IT PAID 13.** Implementing the form turned
thirteen `pass-*` failures into passes -- the fourteenth, `pass-destructure`, fails
on "unsupported binding form", which is fn-parameter destructuring and a different
gap. It also turned FOUR `fail-*` tests from refused into accepted: `(letfn* 1)`, an
odd-length binding vector, a qualified name, and a vector holding a bare `fn*` had
all been "correctly refused" by the blanket "letfn* is not implemented", for a
reason that had nothing to do with them. `clojure.core/letfn*` validates its
bindings now, and the `fail-*` column is back where it was -- by checking rather
than by accident.

**The `pass-*` halves are identical, test for test.** Not the same COUNT --
the same SET: `comm` over the two failure lists is empty in both directions, so
the native runtime and the port fail the same 51 and pass the same 144. Checked on
both re-runs, and it is the claim worth re-checking rather than the score: two
runtimes can post 144 apiece while disagreeing about twenty tests each way.
`SHOW_FAILURES=1` lists the `pass-*` failures only -- 51 lines now, 64 before
`letfn*`, and 0 `FAIL-EXPECTED` either time -- so that is the half the set
comparison covers.

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

## What the 102 failures are

Categorised by CAUSE rather than by directory, because the directories are an
accident of jank's layout and the causes are the answer.

**RE-DERIVED 2026-09-28** from `SHOW_FAILURES=1` on the native arm, which lists
the `pass-*` failures by name with the message each gave. The two halves are
counted separately now, because they are different kinds of finding and the old
single table mixed them: 38 `fail-*` misses (flint accepting what jank rejects)
plus 64 `pass-*` failures.

| n | cause | half |
|---:|---|---|
| 38 | flint **accepts** a form jank rejects | `fail-*` |
| 14 | `letfn*` is not implemented | `pass-*` |
| 13 | **a value was thrown with no message** — `form/try` 11, `form/if` 1, `syntax-quote` 1. These read as "failed with no message" until `c756151f`, which was flint's own words: a thrown keyword rendered as exactly `Error: `. They throw a keyword past a `catch` naming a jank host type, so the throw escapes | `pass-*` |
| 10 | an unresolved symbol -- **8 of them `rand`**, plus `ns-unalias` and `create-ns` | `pass-*` |
|  5 | ran, but an assertion did not hold — and all five have a NAMED cause, measured 2026-09-29 rather than left as a bucket: **2 are var metadata** (`form/def/pass-docstring`, `pass-meta` want `(:doc (meta #'one))`; flint's `(var one)` answers the SYMBOL and `(meta (var one))` is nil, which is the "var objects" absence `ROADMAP.md` already lists), **2 were reader metadata on literals, FIXED 2026-09-29** (`metadata/pass-hint`, `pass-meta`: `(meta ^:foo [1 2])` is nil here and `{:foo true}` in Clojure — `with-meta` works at run time AND the reader attaches it -- reading `^:foo [1 2]` through `flint.compiler.reader` gives a form whose meta carries `:foo true` -- so the loss is between them, where the analyser turns a literal into a constant. I first wrote "the READER is what drops it", which was an inference from `with-meta` working and not a measurement; reading one form settled it), and **1 is syntax-quote resolution** (`` `meow `` gives `t/meow` under the test's own ns where Clojure gives `user/meow`; the test asserts the literal `user/` prefix, so this one is about the harness's namespace, not about flint) | `pass-*` |
|  7 | C++ interop leaking into a language test (`cpp/raw` inside `form/`) | `pass-*` |
|  3 | Ratio literals -- flint has no Ratio, deliberately | `pass-*` |
|  3 | `recur` across a `try` boundary | `pass-*` |
|  3 | threw at run time (`ClassCastException`, `ArityException`, one `ExceptionInfo`) | `pass-*` |
|  2 | a reader tag flint does not bind (`#uuid`) | `pass-*` |
|  3 | other, and one that did not terminate inside 20 s | `pass-*` |

**`rand` IS THE SECOND-LARGEST FIXABLE CLUSTER and it is not a language gap.**
Eight tests fail on `unable to resolve symbol: rand`. `doc/manifest.edn` lists
`rand`, `rand-int`, `rand-nth`, `random-sample` and `random-uuid` under `:absent`,
so the absence is tracked -- but nothing in `DECISIONS.md` records WHY, and the
reason is not obvious: flint bills deterministic gas and has no entropy
capability, so a PRNG is a design question (seeded from where, and does a sandbox
get to observe it?) rather than a missing function. Worth an answer either way,
because eight tests is the price of not having one written down.

The old table's numbers are kept here for comparison: 38 / 14 / 12 / 12 / 7 / 7 /
3 / 3 / 1 / 1 / 5. The `letfn*`, no-message, assertion, C++, Ratio and `recur`
rows are unchanged; the unresolved-symbol row was 12 and is 10; and the reader-tag
pair was inside "one-off" before.

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

> **THE FOURTEEN ARE EXACT, and the re-run names them.** `SHOW_FAILURES=1` lists
> fourteen `PASS-EXPECTED` entries under `form/letfn`, all "compile failed", on both
> runtimes: `pass-destructure`, `pass-empty`, `pass-empty-body-with-bindings`,
> `pass-empty-body-within-let`, `pass-multi-arity`, `pass-munged-name`,
> `pass-practical`, `pass-varargs`, and six more under `binding/`.
>
> **BECAUSE JANK'S TESTS WRITE THE SPECIAL FORM.** `pass-multi-arity.jank` is
> `(letfn* [a (fn* a ([v] (a v :something)) ([v & args] v))] (a :success))` -- not
> `letfn`, not `fn`. Twenty of the twenty-one tests under `form/letfn` write
> `letfn*`, so flint's `letfn` MACRO (`lib/stdextra/clojure/core.fln`, since `b7e3d247` on
> 2026-08-22) cannot help them: it is a different name.
>
> The one test that writes plain `letfn` is `binding/pass-shadow-outer-scope.jank`,
> and it is absent from the failure list -- it passes. That single test IS the
> `pass-*` count moving 130 to 131. The macro earns exactly one test and the special
> form still owes fourteen.
>
> **I GOT THIS WRONG FIRST, and the wrong version is worth keeping.** This note
> previously reasoned that since the paragraph above lists "`letfn` with a malformed
> binding vector" among the permissive failures, the suite must write `letfn` rather
> than `letfn*` -- so the fourteen either predated the macro or failed for some
> unexamined reason. The directory is named `letfn`; the test bodies say `letfn*`. A
> directory name is not a form, and the inference was drawn from this file's prose
> instead of from the tests. What saved it was flagging the number as needing a
> re-run rather than "correcting" it to zero.

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
