# 0025 — A wire codec, and structured ports

> **NOT BUILT — a proposal.** Nothing in this file exists. It replaces the
> codec-per-port design of `0006` §5 and reverses one thing `0006` settled,
> which that decision explicitly left open:
>
> > Ports are NOT transferable and cannot be sent through a port. […] **That is
> > the right default — transfer can be added later; it cannot be removed.**
>
> This is the "later".

## What changes

Four things, and they are one change wearing four hats.

1. **One wire codec** for every flint value crossing the host boundary.
2. **The entry takes a map** — `(defn main [{:keys [args capabilities]}] …)`
   rather than `[argv]` or `[argv caps]` — delivered in that codec.
3. **`args` is data, not strings**, the way `clj -X` takes `:key value`.
4. **A port carries values** rather than text through a per-port codec, and
   **a port can be sent through a port**, so a capability can be delegated.

## It is a codec, not a message format

The thing that crosses is not always a message. The entry map is not a message;
it is the argument to a function, and it arrives in exactly the same encoding.
Calling it `msg` would name one of its uses after the other.

```js
import { codec } from '@3sln/flint';

const v = codec.map([[codec.kw('name'), codec.str('ada')],
                     [codec.kw('n'),    codec.int(42)]]);

// or, when guessing is fine
const v2 = codec.from({ name: 'ada', n: 42 }, { keywordizeKeys: true });

program.exec({ args: v2 });     // the entry map
port.put(v);                    // or a message
const back = await port.take();
back.tag();        // 'map'
back.get('name');  // an encoded value
back.toJS();       // { name: 'ada', n: 42 }
```

### Why an explicit builder exists at all

A host that can only convert JS cannot say what it means. `{a: 1}` is a map
with a string key or a keyword key; `1` is an integer or a double; `[1,2]` is a
vector or a list; a `Set` is a set or a vector. Guessing is right often enough
to be a bug.

So an encoded value is explicit and introspectable, and `from` is a convenience
on top rather than the only road.

### `from` guesses by shape, and a leading colon is the escape

```js
codec.from({ a: 1 })                           // {"a" 1}   string keys
codec.from({ a: 1 }, { keywordizeKeys: true }) // {:a 1}
codec.from({ ':a': 1 })                        // {:a 1}    per key
codec.from(':a')                               // :a        a keyword
```

A JS object has string keys, so string keys are what it means. The two ways to
get a keyword are the flag, for a whole structure, and a leading colon, for
one — and the colon rule holds for a bare string too. The cost is that a
program wanting the literal string `":a"` writes `codec.str(':a')`. That is the
right way round: the explicit builder is always there, and the guess is the
convenience.

## The format

A self-contained recursive stream, tagged. flint already has a tagged value
encoding — `K_NIL`, `K_INT`, `K_STRING`, `K_KEYWORD`, `K_VECTOR`, `K_MAP` and
the rest, decoded natively in `runtime/src/image.rs` — and this reuses those
tag numbers. What it does not reuse is the shape: the image's is a constant
POOL where entries reference each other by index, which is right for a program
and heavy for one value.

A stream duplicates a subtree that appears twice, where a pool would share it.
That is an optimisation and a pool variant can be added; a value crossing a
boundary is a tree in practice.

New tags: **`K_PORT`** and **`K_SENTINEL`**, both carrying their identity
inline — see below for why that is safe. Byte strings (`0024`) mean binary crosses without base64. `0026`'s
tables will want one too, and a tag is cheaper to add before this ships than
after.

## Sending a port: identities go inline, and the invariant is the sandbox's

The first draft of this document put a **transfer table** in every encoded
value — the live things it carried, referenced from the body by index — on the
grounds that a raw port id would let a guest write `K_PORT 7` and name a port
it does not hold.

**That was the wrong answer, and the reason it is wrong is worth keeping.** The
forgery it defends against needs the guest to construct an ENCODING. It cannot.
When a program does `(p/send port v)` it hands over a *value*, and the runtime
does the encoding; for `K_PORT` to appear in the bytes, the guest has to have
had a port value, which means it legitimately held one. `7` encodes as
`K_INT 7` and arrives as an integer.

So identities go **inline**: `K_PORT <id>`, `K_SENTINEL <host-id> <label>`. No
table, no index indirection, no per-value bookkeeping — and the concern that
motivated a *unified* table, that each new live kind would otherwise want its
own buffer, dissolves rather than needing an answer.

### The invariant, stated where it actually lives

> **flint is given no way to turn an integer into a port or a sentinel.**

The protection is not from the host. The host has free rein by definition: it
holds the memory, it can call any export, and if it is compromised there is
nothing left to protect. The protection is on the SANDBOX end, and it is a
property of what the guest is handed rather than of the format.

It already holds today, in the one place it has to:
`(opaque "label")` reaches `new_opaque(label, 0)` — the host id is hard-coded
to zero on the guest path, so a guest-minted sentinel encodes as
`K_SENTINEL 0 "label"` and the host correctly reads it as *not one of mine*
(`0022`). There is no builtin that sets a host id, and there must not be.

The rule this puts on the codec is therefore small and precise:

* The runtime ENCODES ports and sentinels from real values only, which it does
  by construction.
* If a guest-callable **decoder** is ever added — bytes to a value — it must
  refuse `K_PORT` and `K_SENTINEL`, or it hands the guest exactly the integer
  conversion this invariant forbids. That is the one line of this design that
  can be undone by accident later, so it belongs in a test rather than a
  comment.

### What that leaves

A sentinel the guest received and sends BACK carries its real host id, which is
what makes delegation work: the host looks it up in its own grant table and
finds the grant it issued. A sentinel the guest minted carries zero and is
recognised as guest-minted. Both are correct, and neither needs a table.

## `take()` is async, and the runtime has to yield

On the host `take()` returns a promise, because that is the interface a host
wants whatever the runtime does underneath. The SDK drives those promises on
each **step** rather than on every send: a boundary crossing per message would
cost more than the messages do.

Which means the runtime must yield — give the host a chance to flush and
service ports — and one case makes that necessary rather than merely nice.

**A thread blocked on a full buffer has no continuation token.** Every other
park hands the host a token and is resumed by callback (`0006`). A `send` into
a full port is different: nobody will call back, because what unblocks it is
the host TAKING from that port and freeing a slot. So the runtime re-checks on
entry rather than waiting to be told.

That is the one park that polls, and it is worth naming because it is the only
thing that does not fit `0006`'s *everything that parks parks the same way*.

## The process exposes what the build measures

When a program is compiled with diagnostics, what the runtime counts is
readable **on the process, after each step** — gas used, collections, peak
live, the rest. Not printed and not a side channel: a field on the thing being
stepped.

Gas is in every build (`0009` keeps `stat_steps` outside the diagnostics gate)
because it is resource control rather than instrumentation. Everything else
appears only when the module carries it, and the process says WHICH — an absent
counter has to read as absent rather than as zero, which is the distinction
`../HANDOFF.md` was written about.

## What this costs

**Every program's entry changes.** `(defn main [args])` becomes
`(defn main [{:keys [args]}])` — every test, example and benchmark in the tree.
Affordable exactly once, before anything is published, and this is that moment.

**`0006` §5's per-port codecs go.** `flint.port.edn`, `flint.port.json` and
`flint.port.transit` stop being how a port talks. They may stay as
value-to-bytes utilities, which is a different job, and a port that wants to
speak JSON to something outside can still do it explicitly.

**A capability can be delegated.** That is the point, and a real widening of
what a program can do with what it was given. `0006` called the absence the
right default; the default is now the other way, and the README line about it
changes rather than being deleted.

## Order

1. The codec and its native encoder/decoder, with tests both ways.
2. The host API — explicit builders, introspection, `from`/`toJS`.
3. The entry map, which is the breaking change; do it in one commit.
4. Ports carrying encoded values instead of codec bytes.
5. Ports and sentinels crossing, and the yield that backpressure needs. With
   the test that a guest cannot turn an integer into either.
6. Diagnostics on the process, after each step.
