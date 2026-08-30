# flint against jank's dialect suite

[jank](https://github.com/jank-lang/jank) is a Clojure dialect on LLVM. Its
tests are a clean contract, which is why they can be run against a different
implementation at all:

    pass-*.jank   must run to completion
    fail-*.jank   must be REJECTED -- a compile error or a throw

Run them with `bin/jank-suite <jank-checkout> [native|jvm|clr]`. The numbers
below are jank `cd394c63e15d1af5f88bcfbec74fba7873e4b57d` (2026-08-29); a
different revision is a different suite, so quote the one you ran.

## The number

    pass-* accepted and ran    130 / 195
    fail-* correctly refused    60 /  99
    ------------------------------------
    TOTAL                      190 / 294   (64%)

Sixteen further files follow neither naming convention and assert nothing, so
there is no claim to make about them. One test did not terminate inside twenty
seconds and is counted as a failure -- see below, because it is the most
interesting single result.

## What is excluded, and why it is not a dodge

`test/jank/cpp` is 514 tests of **C++ interop**, and they are not run.

flint has no host classes at all. That is `doc/decisions/0010`, and it is the
whole point of the project: a flint module is a self-contained wasm binary with
its own heap, its own collector and its own library, so there is no host to
interoperate with. There is nothing to be "close to" there, and a runtime that
answered those tests would not be flint. Including them to report 190/808 would
be arithmetic, not information.

What remains is 310 files of **language**: the special forms (`case def do fn
if let letfn loop map nil set throw try vector`), the reader, syntax quote,
vars and metadata. Language is exactly what should port, and it is where a
divergence would be a real one.

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
