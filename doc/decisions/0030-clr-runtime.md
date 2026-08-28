# 0030 — The CLR runtime

> **PARTLY BUILT.** The image loader, the interpreter over all 46 opcodes and
> every builtin but three run real flint programs on .NET, agreeing with the
> native runtime on all eight conformance cases, several threads run one program
> on it, and AOT emits real IL (1.8x on a counting loop, every case agreeing with
> the interpreter). Not built: the three regex builtins, and self-hosting.

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

`swap!` still loses updates under contention, on every runtime, because it is a
read-modify-write in `lib/clojure/core.cljc`. The test asserts the loss rather
than wishing otherwise; `doc/decisions/0013` records why the fix is not on.

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

**Three builtins**, down from 74: `re-compile`, `re-run` and `re-find-all`. The
JVM is missing the same three. They need flint's Pike VM ported rather than a
host regex engine bolted on, because a host's engine has different semantics
and a conformance run compares answers.

Missing builtins are **absent, not stubbed**: reaching one names it, because a
stub returning nil would let a program answer wrongly here and rightly
elsewhere.

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

Neither port self-hosts yet. `0029` records what is known and, more usefully,
what has been ruled out by measurement rather than by reading.
