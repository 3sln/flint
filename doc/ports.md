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

It also carries the two things this file used to say only the boxed port had:

* **Parallel executors** (`doc/decisions/0028`) — K REAL host threads driving one
  heap, with the interpreter's checkpoint as the only safepoint. That is the
  host-thread question, asked properly and answered.
* **Host ports** (`doc/decisions/0006`, `0027`) — `open`, `hostContinue`,
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

Regex is SLOWER, and that is not a defect to hide: it is dominated by natives,
where compiled code removes no dispatch and adds a crossing. `doc/decisions/0013`
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
  reads; both ports reload unconditionally after every crossing. `0013` records
  measuring that on a four-instruction callee and finding it pure overhead.
* **Nested compiled calls.** `aot_call_at` runs a compiled callee on the host
  stack to `AOT_MAX_DEPTH`; the ports carry the mechanism but nothing has
  measured what the cap should be here.
* **Specialisation and unboxed locals**, which `0013` lists as the remaining
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
sources themselves as `@kin:link:form:`. Counted across the three runtimes:

    native 12    CLR 12    JVM 12    identical set

    bitpos  char-at  hash-mask  index-of  integer  keyword-hash
    s-concat-copy  s-copy-range  s-empty  set-eq  string-hash  vec-count

That the three lists are the same set is itself worth having: a primitive that
exists on one runtime and not another is a divergence waiting to be found by a
user.

THREE OF THEM ARE NOT PRIMITIVE. `hash-mask`, `bitpos` and `index-of` are the
HAMT's bit arithmetic -- `(h >>> shift) & 0x1f`, `1 << mask`, and
`popcount(bitmap & (bit - 1))`. Nothing about them needs a host; the first two
are already sayable in kin's vocabulary today, and the third needs a popcount
link and nothing else.

They are all three CORRECT right now -- checked, because this is exactly where
a shift is easy to get wrong: Java's `>>` is arithmetic and would silently
corrupt a hash with the top bit set. All three spell it `>>>` (or `>>` on
Rust's unsigned). That they are right is not an argument for leaving them
hand-written; it is the argument for moving them, because this is the class of
function where being wrong is invisible until a hash collides in one runtime
and not another.

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
