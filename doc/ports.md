# The JVM and CLR runtimes

There is now ONE implementation of flint on each host. There used to be two, and
this file recorded why; the second is gone and this records what that cost.

## `com.flint.rt` / `Flint.Rt` — the ported runtime

A verbatim mirror of the Rust: the same NaN-boxed 64-bit values, the same
generational collector over the same flat byte-addressed heap, the same object
layouts, the same CHAMP, the same Pike VM, the same green threads and the same
scheduler. The heap is a `MemorySegment` on the JVM and `NativeMemory` on the
CLR, which is the ONLY file on either side that differs from the Rust in more
than syntax.

It runs every conformance program and agrees with the native runtime character
for character, snapshots in both formats, and **self-hosts**: the flint compiler
runs on it and emits byte for byte what the wasm compiler emits.

## Loading an artifact: the JVM was the odd one out

Every other runtime takes an artifact as BYTES. The ESM SDK is
`new Image(wasmBytes)` and then `new Sandbox(module)` — a module OBJECT, never a
path. The CLR is `Img.Load(rt, bytes)`. The JVM was `Class.forName("flint.Artifact")`:
a NAME, resolved through the classpath, which drags the filesystem into the
loading mechanism.

**That is where `:to :jvm :out <directory>` came from.** A JVM loads a class only
from a path matching the class's own name, so a fixed `flint.Artifact` forced
`:out` to be the classpath ROOT that `flint/Artifact.class` hangs off, while every
other target takes a file. The name was never the requirement — an artifact that
only exists as a FILE was.

`com.flint.rt.Artifact.define(byte[])` (2026-09-26) is the mirror of the other
two, and it invents nothing: `AotEmit` already defined classes in memory this way
for compiled arities, and `Img.load` already read an image from bytes. Verified:

    loaded from bytes: Prog          no classpath, no filename
    second, separate: true           two artifacts of the SAME NAME in one
      same name: true                process, as distinct classes -- which a
                                     shared loader cannot do, and is the JVM's
                                     answer to instantiating a module twice
    refuses non-class                0xCAFEBABE checked, so a wrong artifact is
                                     named rather than failing inside the JVM

A loader per artifact is what makes the second row true. `Class.forName` is kept
for a consumer who put the class on a classpath, and the emitter's class name is a
parameter now (`flint.jvm/artifact-class-name`), defaulting to `flint/Artifact` so
existing output is byte-identical.

**`:out` TAKES A FILE ON BOTH DOORS as of 2026-09-26.** `:out Prog.class` emits a
class calling itself `Prog`, and a directory still writes
`<root>/flint/Artifact.class` byte-identically to before. The two doors agree byte
for byte on the same basename, which is the standard AGENTS.md section 1 asks of
them.

A FILENAME IS NOT A CLASS NAME, and both doors refuse one that is not. `:out
my-prog.class` would emit a class called `my-prog`: a JVM loads it — class-file
naming is laxer than the Java language's — and no Java source can reference it,
which is worse than a refusal because the artifact looks fine until somebody
writes code against it. Found by naming a test file `bf-Prog.class` and reading
what `javap` said about it.

### `Flint.Rt.Image`, the CLR's half of the same mirror

`Assembly.LoadFrom(path)` was how a CLR host reached an artifact --
`runtimes/clr/artifact/Check.cs` did it twice and resolved `Program`, `Boot`,
`Link` and the metadata attribute inline each time. `Assembly.Load(byte[])` is the
CLR's `defineClass`, so an artifact never had to be a file here either.

    Image.Of(byte[])     loads the assembly from bytes; no file, no path
    Image.OnPath(path)   for one on disk
    .Metadata() .Bytes() .Type_() .Assembly_() .Bytecode()
    .Boot(IBridge) .Link(bridge, name, fn)

**IT DIFFERS FROM THE JVM'S IN ONE WAY, AND THAT IS NOT AN OVERSIGHT.** The JVM's
`Image` MUST hold the class bytes, because its metadata is a raw class-file
attribute read by hand and recovering the bytes from a byte-defined class returns
null. The CLR's metadata is a real `_3sln.Flint.MetaAttribute` read by REFLECTION,
which works for a byte-loaded assembly exactly as for a file — so `Metadata()`
here needs nothing kept. The bytes are kept anyway, for `Bytes()`, and that is
uniformity rather than necessity. Saying which is which is the point: flattening
the two would hide that the JVM had a defect the CLR never had.

`Check.cs` now asserts both routes agree about the metadata and the bytecode
length, that `Of` keeps bytes while `OnPath` has none to keep, and that a non-PE
is refused by name rather than deep inside the loader. The agreement row was proved
able to fail by making `Metadata()` bytes-dependent.

### `Flint.Rt.Compiler`, and the one target that refuses a resolved spec

`Compiler.Of(dist/flintc.bytecode).Run(argv)` runs the compiler on this runtime,
the mirror of `com.flint.Compiler` and the ESM SDK's. Promoted from
`--rt-selfhost` in `runtimes/clr/conform/Program.cs`, which carries the same four
lessons the JVM's did: the var table rather than `img.entry`, initialisers first,
rooting across allocations, and the spec as an argv.

**A RESOLVED SPEC COMPILES HERE TOO**, so source-to-artifact-in-memory works on
both ports. It did not until 2026-09-28, and this section recorded the cause
wrongly twice before measuring it.

The symptom: a resolved spec produced an assembly `Assembly.Load` refused with
`BadImageFormatException: Invalid COR20 header signature`.

**THE CAUSE.** `compile-to-clr` handed `clr/assemble` the VECTOR `img/emit`
answers. `clr/assemble` measures its image with `flint.rt/b-count`, which does not
measure a vector — so the length came out wrong, the COR20 header's metadata RVA
was computed WITHOUT the bytecode's size, and it pointed 26 719 bytes early, into
the embedded image. .NET read `FLIN` where `BSJB` belongs:

    bin/flint's assembly   metadata rva=0x8a48 -> sig BSJB   loads
    the CLI's assembly     metadata rva=0x21d8 -> sig FLIN   refused
    0x8a48 - 0x21d8 = 26 736, against an image of 26 719 bytes

**EVERY SPEC HIT IT, the CLI's own included** — which is why a restriction on
resolved specs fixed nothing, and why the earlier explanations here were wrong.
Three candidates were ruled out by measurement first (a port divergence, the
builtin set, checks), and two wrong causes were published before the right one:
the `:features` map/set collision, and the spec shape.

`compile-to-jvm` had recorded this exact defect — "handing the vector over
produced a class with correct METADATA and no bytecode in it" — and its comment
said *"`vec->b`, WHICH `compile-to-clr` DOES TOO"*. That sentence was false, and a
comment asserting a sibling is correct is how the sibling stays wrong.

**`bin/check-clr` now loads the NATIVE door's assembly**, before the babashka
one. That check's absence is what let this ship: it built with `bin/flint`, whose
door was always fine, while `test/selfhost-targets.clj` checked the native door's
artifact for magic bytes and nothing loaded it. Proved able to fail by
reintroducing the vector and watching it report the same `Invalid COR20 header
signature`.

### `com.flint.Image`, the mirror of the other SDKs' Image

Each SDK takes an artifact as bytes and hands back something bootable — ESM's
`new Image(wasmBytes)` with `.metadata` and `.sandbox()`, the CLR's
`Img.Load(rt, bytes)`. The JVM had no such type: a host reached the artifact by
`Class.forName` and re-derived the three methods reflectively, which
`com.flint.Main` and `com.flint.FourOps` each did PRIVATELY, in two copies.

    Image.of(byte[])          defines the class in memory; no classpath, no file
    Image.onClasspath([name]) for an artifact already on one
    .metadata()               the attribute, as the EDN string it is
    .bytes() .clazz()
    .boot(Bridge) .loop() .link(name, fn)

**It holds the bytes, and that is the point rather than an implementation
detail.** The metadata is a class ATTRIBUTE, read from the file rather than by
calling the artifact. Recovering those bytes through
`getClassLoader().getResourceAsStream(...)` works for a classpath class and
returns NULL for one defined from bytes — so a byte-loaded artifact had metadata
that was present in the file and unreadable through the API. Measured before the
change: `resource for a byte-defined class: NULL`, with the attribute sitting in
the same bytes. Holding them makes `metadata()` work either way and needs no
class loader at all, which is what the metadata decision wanted.

Verified: both routes read the metadata and **agree on it**, `of(bytes)` on a
class called `Prog` and `onClasspath()` on `flint.Artifact`.

`Main` and `FourOps` drive this now instead of their own copies, so the harnesses
exercise the API a consumer uses. `metadata()` is NOT parsed into a map: the ESM
SDK returns an object because JSON is its native shape, EDN is not the JVM's, and
an EDN reader in the runtime would be a parser nobody asked for.

### `com.flint.Compiler`, and the half of it that cannot exist yet

`Compiler.of(dist/flintc.bytecode).run(argv)` runs the flint compiler ON this
runtime — the ESM SDK's `new Compiler(module)` over `dist/flintc.wasm`, with the
same compiler, which is a flint program. Verified: 36 864 chars, **byte for byte
what the wasm compiler emits**, which is the assertion
`runtimes/jvm/test/RtSelfHost.java` makes and where this was promoted from.

Four things a second implementation gets wrong now live in one place, each learnt
the hard way by that harness: `flint.selfhost/main` is a VAR found through the
image's var table and not `img.entry`; the initialisers must run first or it is
unbound; everything is rooted across allocations because the nursery copies; and
the spec goes in a LIST, because `main` takes an argv and dispatches on its first
element.

**`compileJvm(spec) -> Image` works as of 2026-09-26, after the cause was fixed at
the source.** It was written, found unreachable, removed, and restored. THERE WERE
TWO SPEC SHAPES:

    self.spec  mode spec -> an image, 36 864 chars
    self.spec  mode jvm  -> !missing clojure.core hello flint.system ...
    jvm.spec   mode spec -> an image
    jvm.spec   mode jvm  -> !missing clojure.core ok flint.system ...

The MODE decides, not the spec. `compile-to-base64` takes a RESOLVED spec,
`{:sources {ns {:src ..}} :order [..]}` — what `bin/flint --emit-spec` writes.
Every artifact target goes through `build-image`, which takes an UNRESOLVED
`{:files .. :entry ..}` and resolves it itself. Each CLI builds that second shape
internally and exposes it to nobody, so a JVM host can obtain the first and no
artifact target accepts it.

**CLOSED IN `build-image`, which now skips resolution for a spec that already
carries `:sources` and `:order`**, plus `spec-builtins` taking the builtin set
from `:builtins` when there is no slot map — the precedent `compile-project`
already set for the same reason. Everything after the resolve step is untouched,
so all four targets still emit byte-identical artifacts from the CLIs' own
unresolved specs; `cmp`-checked on wasm, clr, jvm and llvm.

What this needed was narrower than the roadmap item: not one spec BUILDER, but one
spec SHAPE every target accepts. Sharing construction is still its own change
(`DECISIONS.md#one-dependency-walk`).

So source-to-artifact on the JVM is one call now, in memory, and
`bin/check-jvm-sdk` gates it. THE REFERENCE IS THE WASM COMPILER ON THE SAME
SPEC, which took two attempts to get right: compared against the native CLI's own
artifact it reported 38 777 bytes against 38 777 — same length, different bytes —
which reads exactly like a port divergence and was not one. The CLI builds its own
spec, so the two compilers were handed different inputs. `host/flint-argv.mjs`
exists to drive the wasm compiler with a full argv, because `host/flint-file.mjs`
passes only a spec and so can only reach mode `spec` — which is why wasm-against-a-port
comparisons had never covered an artifact target at all.

The byte-for-byte row was proved able to fail by flipping one byte of the
artifact.

**Each of those is a named gate, added 2026-09-26** -- the claims were true and
unattributed, and a claim with no gate beside it cannot be rechecked:

| claim | what asserts it |
|---|---|
| agrees character for character | `bin/conform-hosts`, phases "every conformance program, 3 runtimes" and "the language suite, every runtime" |
| snapshots in both formats | `bin/conform-hosts`, phases "snapshots, both formats", "a live snapshot across runtimes" and "native reads what the ports wrote" |
| self-hosts, byte for byte | `FLINT_SELFHOST=1 ./bin/conform-hosts`, phase "self-hosting, both ports" -- run by `bin/release-gate`, **skipped by `bin/test`** |
| parallel executors, K host threads | `bin/conform-hosts`, phase "parallel executors, K host threads" |

The self-hosting row is the one worth knowing about: it is opt-in, so no ordinary
gate run checks it. Its own comment used to give the reason as "179 s on the JVM
and 704 s on the CLR" while contradicting itself three lines later with "SIX
SECONDS ... wrong by a factor of 150". **Re-measured 2026-09-26 at `7b73c0cb`
(Apple M1 Pro, each step timed alone): 1.02 s to emit the spec, 2.45 s for the
wasm reference, 3.35 s on the JVM and 2.03 s on the CLR -- 8.85 s in total, all
three assertions passing on both ports.** So the cost that justifies its being
opt-in is not there. Whether to move it into `bin/test` is a change to
`AGENTS.md` section 4's tier split rather than to any script, and so the
maintainer's.

It also carries the two things this file used to say only the boxed port had:

* **Parallel executors** (`DECISIONS.md#drivers`) — K REAL host threads driving one
  heap, with the interpreter's checkpoint as the only safepoint. That is the
  host-thread question, asked properly and answered.
* **Host ports** (`DECISIONS.md#host-abi`, `ports-are-the-hosts`) — `open`, `hostContinue`,
  `hostDeliver`, `hostClosePort`, `drainEvents`, `reapPorts`, the weak port
  registry and the generation-tagged waiter tokens. Three drivers run one image
  through one script and `bin/conform-hosts` compares the transcripts byte for
  byte.

## AOT, which the cutover did not cost after all

An earlier version of this file said AOT was gone on these hosts and not coming
back, on the grounds that `Aot.java` emitted bytecode over a value model where
every value was already a host object, and the ported runtime uses NaN-boxed
longs in a flat heap.

That reasoning was about the wrong artifact. The thing to port was never
`Aot.java`; it is `runtime/src/aot.rs` and `src/flint/aot.cljc`, which run over
the SAME value model the ported runtime already mirrors -- and they port the way
everything else here did.

Wasm, JVM bytecode and CIL are all stack machines with locals over a flat
memory. `emit-instr` is a case over flint opcodes producing about twelve
primitives -- locals, constants, loads and stores at an offset, arithmetic,
helper calls, branches -- and every one exists on all three. What differs is the
opcode table, the value stack being a `long[]` so a push is an array store, and
the container. Wasm's structured `block`/`loop`/`br` becomes a flat switch into
labels, which is SIMPLER: flint's own bytecode already uses flat jumps, and the
wasm emitter has to reconstruct structure it never wanted.

So `:optimize [perf]` compiles arities on all four runtimes. `AotPlan` is the
analysis half and is identical on both hosts, because decoding, chunk
boundaries, the gas charge and the depth dataflow are decisions about FLINT
BYTECODE and have nothing to do with the target. `AotEmit` is the backend.

### What the gate asserts

Per conformance program, on both hosts: the answer interpreted against
compiled; the answer again under MAXIMAL CHUNKING, which bisects the two halves
an emitter can be wrong in -- a missing boundary and a mis-emitted opcode --
because a failure that survives it is the second kind; and the GAS COUNT, since
compiled code charges per chunk from a static instruction count while the
interpreter charges per instruction, so the two agreeing says the chunking is
right.

Then the two hosts' transcripts are compared CHARACTER FOR CHARACTER. Two
emitters written separately against different instruction sets agreeing on the
arity count, the entry count and the gas is a stronger statement than either
passing alone.

And that compiled code was ENTERED. The first version of the JVM port left the
frame's `aotIp` at `NEVER`, so every arity compiled, every answer matched, every
gas count matched, and not one instruction of compiled code ever ran -- an
interpreter agrees with itself. `entries=0` is a failure.

### Measured

    control   interpreted 50.94 ms   compiled 32.95 ms   1.55x
    numbers   interpreted  1.25 ms   compiled  0.92 ms   1.36x
    maps      interpreted  3.44 ms   compiled  2.68 ms   1.28x
    regex     interpreted  4.16 ms   compiled  6.07 ms   0.68x

> **WHAT THIS TABLE DOES NOT SAY, noted 2026-09-26 rather than quietly trusted.**
> No machine, no run count, no date, no flint commit, and no command that
> reproduces it -- and a search of `bin/` for a script printing these four
> program names beside interpreted/compiled columns found none, so it appears to
> be a one-off. Every other measurement in this repository names at least its
> machine and method; `doc/benchmarks.txt`, `doc/benchmarks-vs-clojure.txt` and
> `doc/jank.md` all do. AGENTS.md section 2 is about exactly this shape: "a
> benchmark ratio with no host, machine, or method -- unreproducible, so it can
> only be believed or ignored, and it gets believed."
>
> It is kept because the RATIOS still carry the argument the section makes, and
> the `regex` row being BELOW 1.00 is the part worth having: a compiled arity
> dominated by natives removes no dispatch and adds a crossing, which is a claim
> the direction of the number supports whatever the machine was. What should not
> be quoted elsewhere is the absolute milliseconds. Re-measuring is a real
> benchmarking job, not a doc fix, and is left as such.

Regex is SLOWER, and that is not a defect to hide: it is dominated by natives,
where compiled code removes no dispatch and adds a crossing. `DECISIONS.md#emit-wasm-instead-of-dispatch`
predicts exactly that.

### Tuning, left for later

The port is the port; none of this is a gap in it, and all of it is measurable
against the gate as it stands.

* **Decline native-bound arities.** The `regex` number above is the whole
  argument: an arity whose instructions are mostly `NATIVE` has no dispatch to
  remove, so compiling it buys a crossing per call and nothing else. The
  histogram needed to decide is already computed -- `AotPlan` knows every
  instruction's opcode before anything is emitted.
* **The `need` analysis.** The wasm emitter reloads only what a body actually
  reads; both ports reload unconditionally after every crossing. `emit-wasm-instead-of-dispatch` records
  measuring that on a four-instruction callee and finding it pure overhead.
* **Nested compiled calls.** `aot_call_at` runs a compiled callee on the host
  stack to `AOT_MAX_DEPTH`; the ports carry the mechanism but nothing has
  measured what the cap should be here.
* **Specialisation and unboxed locals**, which `emit-wasm-instead-of-dispatch` lists as the remaining
  wins for wasm and which apply unchanged to a host that has real registers.

## What the cutover removed

7 353 lines of a second runtime, and with it:

* `ThreadTest` / `--threads`, superseded by `RtParallel` / `--rt-parallel`,
  which asks a harder version of the same question: K host threads on ONE heap,
  with a collection staged by one walking the other's roots.
* `Conform` and the CLR's bare-argument form, superseded by `RtImage` /
  `--rt-image`, which also compares against the NATIVE answer rather than only
  printing its own.
* `SelfHost` / `--selfhost`, superseded by `RtSelfHost` / `--rt-selfhost`.
* `AotTest` / `--aot`, which had nothing left to test.
* `LoadTest`, `RunTest`, `HashTest`, which nothing had run for some time.

## What is still written three times, and why

"29 hand-written fns in bytes/strs/vector/pike" is the number the work reminder
carries, and it overstates the problem: most of those are `#[test]`. The
boundary that MATTERS is the one kin calls into, and it is declared in the
sources themselves as a link form. It was twelve forms in five namespaces; it
is SEVEN in two now, and the three that moved took their whole namespace with
them rather than shrinking it — `flint.rt.maps`, `flint.rt.sets`,
`flint.rt.vector` are gone.

    char-at  s-copy-range  s-concat-copy  s-empty      genuinely primitive
    integer  string-hash  keyword-hash                 portable, blocked

That the three runtimes declare the SAME set is itself worth having: a
primitive on one runtime and not another is a divergence waiting to be found by
a user.

WHAT MOVED, AND WHAT IT WAS WORTH. `hash-mask`, `bitpos`, `index-of` (the
HAMT's bit arithmetic), `set-eq` (a count compare and a delegation to generated
`map-eq`), and `vec-count` (one slot read). Every one of them was CORRECT in
all three runtimes before it moved, checked rather than assumed, and that is
the argument for moving them rather than against. `hash-mask` is the example:
Java's `>>` is arithmetic, so a mask written with it would pick a different
SLOT for any hash with the top bit set, losing map entries rather than slowing
anything down. All three spelled it `>>>`. Being right three times by hand is
not the same as being right once.

AND THE FOUR THAT STAY ARE NOT A JUDGEMENT CALL. `char-at` reads memory at a
computed offset; `s-copy-range` and `s-concat-copy` are memcpy; `s-empty`
constructs a string. Those want the host.

THE OTHER THREE ARE BLOCKED ON THE VOCABULARY, NOT ON BEING PRIMITIVE, and the
blockage is small and nameable:

* `string-hash` and `keyword-hash` walk bytes, which kin says fine — but the
  flat string's hash cache is a RAW u32 at `a + HDR`, and kin has `read-u8` and
  `write-u8` and nothing wider. (The rope's cache is an ordinary `Value` slot,
  which is why `rope-hash` is already generated and these are not: two
  different caching mechanisms, not two different walks.)
* `integer` is a range test, an `alloc`, and a 64-bit write. Both ports spell
  the test `n >= -(1L << 47) && n < (1L << 47)` and Rust spells it
  `n >= FIXNUM_MIN && n <= FIXNUM_MAX`; since `FIXNUM_MAX` is `(1 << 47) - 1`
  those are the identical set, checked. `write-u64` is the easy half.

  SAYING 2^47 IS THE HARD HALF, and `hex` is not the answer — which is worth
  writing down, because it looks like it is. `hex` emits its literal VERBATIM:
  `0xcc9e2d51` is a `u32` in Rust and an `int` in Java and needs no help. A
  48-bit constant is `integer number too large` in Java without an `L`, and
  nothing in kin adds one — no source emits a large `I64` literal today, so the
  facility does not exist rather than merely being unused. `integer` needs a
  literal form that knows its own width, not another template entry.

The `u32`/`u64` accessors do all exist on all three runtimes
(`read_u32`/`write_u32`, `read_u64`/`write_u64`, and the camel/Pascal
spellings), so THOSE are template entries beside the `u8` ones. Additive, but
to the vocabulary all 88 sources share, which is a different kind of change
from moving a function — and for `integer`, not sufficient on its own.

## One algorithm, two GENERATED sources

The sharpest instance of "ported into isolated namespaces and so written
twice", and it is not in the hand-written tree at all — both halves are kin
output, which is why counting hand-written functions never showed it.

`rope-hash` (`kin/ropeflat.kin`) and `b-hash` (`kin/bytehash.kin`) are the same
cached tree-fold: walk a leaf with `h * 31 + byte`; return a cached hash if the
node has one; otherwise fold the children with `h * pow31(child bytes) + child
hash`, cache, return. Diffed as EMITTED Rust, the fold is line-for-line
identical under six substitutions:

    is_rope          is_brope
    leaf_len/_byte   olen / read_u8 at HDR+i, behind a TY_BYTES test
    RP_HASH          BB_HASH
    rope_kids        olen - BB_KIDS
    RP_KIDS          BB_KIDS
    s_bytes          b_count

`pow31` is ALREADY shared — `ropeflat.kin` requires it from `bytehash.kin` — so
the arithmetic converged and the walk around it did not.

THE TWO ARE ALLOWED TO DIFFER IN RESULT, and do: the string side finishes with
`hash-int` and the byte side does not. That is legal because a byte string is
only ever `=` to another byte string (`valeq.kin` tests `TY_BYTES`/`TY_BROPE`
on BOTH sides), so nothing requires the two to agree. The duplication is the
WALK, not the answer.

WHAT UNIFYING WOULD COST, stated because it is not obviously worth paying. kin
has no macro, template or generic form — `defconst`, `defdata`, `defn`,
`defstruct` is the whole list — so one source cannot emit two specialisations.
A single function would have to branch on the type per node, in a hot path, and
would couple two layouts in one place. The recursion is what forces it: the
fold calls back into itself, so the branch cannot be hoisted out of the loop.

So this is recorded and not done. It is a real instance of the pattern and the
price is real too; whether ~20 lines of shared structure is worth a per-node
branch is a judgement about this runtime, not a cleanup.

## What is left on the table, audited

A body-by-body pass over the hand-written trees, 2026-09. Ranked, with the
strongest first. Everything here was confirmed by reading all three
implementations, not by matching names.

**1. NaN-box pack and unpack.** `from_f64`/`as_f64` (`value.rs`),
`ofDouble`/`asDouble` (`Val.java`), `OfDouble`/`AsDouble` (`Val.cs`). Nine to
thirteen lines each. Pure bit work on a word already in a register, plus the
canonicalisation of a NaN whose payload would collide with the tag range.

THIS IS THE `hash-mask` ARGUMENT AGAIN, and more sharply. Each runtime spells
the same shift differently BECAUSE ITS LANGUAGE WOULD OTHERWISE GET IT WRONG:

    Rust   b >> 48            u64, already logical
    Java   (b >>> 48)         `>>` sign-extends; the file says so
    C#     ((ulong)b >> 48)   cast first, same reason

Three correct answers, independently arrived at, to one trap. The Java comment
records what the wrong one does: "every tag comes back as -1".

**2. The Pike VM's simulator core.** `class-hit`, `add-thread`, `consumes`,
`run-over` -- about 150 lines, over flat integer arrays already in memory.
`class-hit` was verified line-for-line. CAVEAT, unresolved: it recurses and
grows a thread list, and whether kin's vocabulary reaches that has not been
checked. Algorithmically portable is not the same as portable.

**3. Numeric three-way compare.** `num-cmp` / `cmp` / `Cmp`. Integers first,
then `f64` with NaN sorting equal. Its siblings `num-eq` and `num-hash` are
already generated; this is the leftover.

**4. Object-size arithmetic.** `layout-of`, `align8`, `size-for`. Arithmetic on
`(ty, len)` with no memory parameter, sitting in a file that is otherwise
genuine host-bound accessors -- which is why it was easy to miss.

**5. `conj`'s dispatch, but NOT a drop-in.** Most arms match. Two do not: the
map-onto-map arm uses a closure natively (`map-for-each`) and a seq walk on the
ports, and the variadic shape differs -- native re-tests the type per argument,
the ports hoist the test out of the loop. Porting means choosing one shape and
changing two runtimes to match. The closure half is blocked on kin having no
closures, which is a known hole.

Plus a handful of genuine one-to-three-liners (`take-opaque-id`, `hash-double`,
`ex-matches`, `vec-from-roots`) where the round trip probably costs more than
the duplication, worth batching only if kin work touches that area anyway.

WHAT THE AUDIT RULED OUT, so it is not re-done. Memory, the collector, threads,
AOT, the codec, snapshots and the image reader are host-bound as expected.
`abi.rs` is wasm-only and has no counterpart. Most of `map`, `set`, `table`,
`seqs` and `Bytes` turned out to be ALREADY generated, with only layout
constants and wrappers left by hand.

And two traps worth keeping. `map-for-each` is not an oversight -- the
alternative was measured at 1.73x time and 12,500x peak roots, so the closure
is deliberate. And `utf16-cmp` looks identical down to its doc comment while
being a different mechanism underneath: .NET and Java strings ARE UTF-16 and
index directly, while Rust has to synthesise code units. Same specification,
asymmetric work, correctly not a candidate.

WHAT THIS COUNT IS NOT. It measures the surface kin CALLS INTO, not everything
written three times. The runtimes also hold parallel hand-written code kin
never sees -- memory, the collector, threads, AOT, the codec -- and most of
that genuinely wants a host.

A NAME-LEVEL CENSUS OF THE REST DOES NOT WORK, tried and recorded so nobody
repeats it. Normalising for snake/camel/Pascal and intersecting the three trees
gives 192 shared names, which looks like a finding and is not: the matches
collide across concepts. `conj` is in all three, and it is a type DISPATCHER in
`runtime/src/coll.rs`, a byte-transient append in `Bytes.java`, and a vector
shim in `Vec.java`. Three unrelated functions, one name. Finding real
duplication out there needs bodies compared, not names.

AND THE 31-WALK EXISTS FOUR TIMES. `hashBytes` / `HashBytes` / `hash_bytes` are
one rule -- `h * 31 + byte`, then the final mix -- written once per runtime,
and `kin/bytehash.kin` writes the SAME walk for byte trees, generated. The two
are not interchangeable as they stand (the byte-tree hash caches a raw walk per
node and applies no final mix; the string hash mixes), but the walk under them
is one thing, and the string side is the hand-written one.

Written down because it was introduced RECENTLY, by the change that made a
string hash over its bytes, and by the habit this file exists to name: three
files were open, so the function was written three times. The port boundary is
small and deliberate; this was neither.
