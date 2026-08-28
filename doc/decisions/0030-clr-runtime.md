# 0030 — The CLR runtime

> **PARTLY BUILT.** The image loader, the interpreter over all 46 opcodes and
> all 155 builtins run real flint programs on .NET, agreeing with the
> native runtime on all nine conformance cases, several threads run one program
> on it, and AOT emits real IL (1.8x on a counting loop, every case agreeing with
> the interpreter). **The flint compiler runs on it and emits the same image the
> native compiler does, byte for byte.**

Tier 2, the same as `0029`: port the VM and lean on the host's collector. A
flint value is a .NET object, the CLR owns lifetime, and the generational
copying collector does not exist here.

## It passed conformance on the first run, and that is the finding

Every case agreed with the native runtime immediately — hashes, forty-key CHAMP
ordering, seqs printing as seqs, all of it. Not luck. It is the JVM port's
mistakes, already paid for and written down:

* a seq is not a vector, and they print differently;
* the `TYPE_P` codes are COPIED from `flint.types`, not inferred;
* `flint/keyword2` means the NAME with one argument and `(ns, name)` with two;
* maps are an array-map to eight entries and a CHAMP past that, in hash order.

Each of those cost a debugging session on the JVM. On the CLR they cost
nothing, because `runtimes/conform/README.md` and `0029` said what they were.

**That is the argument for the conformance harness stated as a number rather
than a principle.** The first port needed the harness to find four divergences
that all produced the right elements in the wrong shape. The second port needed
it to confirm there were none.

## Multi-threading

Works, and needed almost nothing: values are immutable, `Kw`/`Sym` intern
through a `ConcurrentDictionary` — which gives free the "one text, one object"
property the wasm runtime spends a lock on — and the var slots are guarded.
There is no safepoint to build because there is no collector of ours to stop.

`swap!` loses nothing under contention here either: 8 threads x 200 increments
gives 1601 of 1601. It is a retry loop over `compare-and-set!` in
`lib/clojure/core.cljc`, so the property holds on every runtime that has real
threads -- 4 x 250 through the Rust SDK, 8 x 200 on both ports -- and the
compiled wasm path answers what the interpreter answers.

## The emitter lives on the platform, and compiles on first call

`System.Reflection.Emit` builds a `DynamicMethod` per arity, the first time
that arity is called. Two things follow, both recorded in `0029` and true here:

* **No cross-compilation.** Emitting IL needs .NET present, so there is no
  `flint compile :to :clr` producing a DLL from any host. That is the cost of
  not writing the emitter in flint, and it was taken deliberately: a loadable
  assembly means PE plus metadata tables, which is a much larger format than
  the class file the JVM would need.
* **It is not ahead of deployment.** The wasm backend splices compiled arities
  into the artifact at build time; this one compiles into memory on first call.
  Both are called AOT in the harness and only one produces something you can
  ship.

## What is missing

**Nothing, of the 155 builtins the native runtime carries.** Both ports carry
all of them, and `bin/check-builtins` counts it in the suite rather than
letting it be assumed.

The last three were the regex builtins, and they were the only ones that were
not mechanical. What is ported is the **Pike VM**, not a pattern parser:
`flint.nfa` parses the pattern in cljc, which already runs on every host, and
hands the matcher a finished program as a vector of integers. So one engine's
semantics reach every host instead of each host bolting on its own.

Handing the pattern to `System.Text.RegularExpressions` would have been a
tenth of the code and a different language. `runtimes/conform/regex.cljc` is
chosen at the places they differ: `.` against a newline, leftmost-FIRST rather
than leftmost-longest, an empty match still advancing, a non-participating
group answering nil rather than "", and spans in code points rather than UTF-16
units. All of it agrees with Clojure's own answers.

Should a builtin ever go missing again, it is **absent, not stubbed**: reaching
one names it, because a stub returning nil would let a program answer wrongly
here and rightly elsewhere.

Porting the other 71 was mechanical; getting them RIGHT was not, and
`runtimes/conform/hosted.cljc` exercises them rather than counting them. Each
is a place the CLR's own library is close to what flint means and not the same:

* `Math.Round` defaults to half-to-even, which is what `rint` means -- but only
  said explicitly does it stay that way. 2.5 → 2.0 beside 3.5 → 4.0 is the case
  that pins it.
* `IndexOf` finds "a" inside "A" under some cultures. Ordinal, or the answer
  depends on where the program runs.
* The CLR counts UTF-16 units and flint counts code points, so `subs` on
  "héllo" is what separates them.
* `hypot` is not `sqrt(x*x + y*y)`: that overflows for large operands and
  underflows for small ones.

Dynamic bindings are per OS **thread** here, where flint's are per green thread.
A spawned thread starts empty rather than inheriting a snapshot: threads on this
port are made by the host, so there is no spawn site to take one at.

## What running the compiler found

The same two the JVM did, because they were the same code twice: a tail call
that was only a tail call to ITSELF, so mutual tail recursion grew a frame per
hop; and `seq?` answering `seqable?`, true for a vector, a map, a set and a
string. `runtimes/conform/control.cljc` pins both -- mutual recursion 300 000
deep, which no host survives one frame per hop, and `seq?` against the answers
Clojure gives.

**Both ports self-host.** The flint compiler runs on .NET and emits an image
byte for byte identical to the native compiler's -- the same 5 361 bytes the
JVM produces. `0029` records the seven bugs that took, and the three lessons
about how to measure a runaway recursion, all of which applied here too.

Two were the CLR's alone. `count` refused a cons cell or a lazy seq, because
neither has a `Count` to read: they are WALKED, which is also what makes
counting an infinite sequence hang rather than answer, exactly as in Clojure.
And an error was a bare STRING rather than a structured value, so `ex-message`
on one answered nil -- and the compiler, which catches an error and re-throws
it with the form it happened in, produced `"\n  in clojure.core/identity"`: a
location with no message in front of it. There is an `Ex` type now, as on the
JVM and as in `runtime/src/err.rs`. The failure was real and the report said
nothing, which is the worst combination, and it is the second time this
codebase has paid for it.
