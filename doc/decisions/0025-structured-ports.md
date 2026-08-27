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

New tags: **`K_PORT`** and **`K_SENTINEL`**, both indices into the transfer
table below. Byte strings (`0024`) mean binary crosses without base64. `0026`'s
tables will want one too, and a tag is cheaper to add before this ships than
after.

## Sending a port safely, which is the whole difficulty

`0006` forbade this partly to keep the wire format simple and partly so a
capability could not leak through a message. The second is the real one, and it
is not solved by letting ports be encoded — it is solved by deciding **what a
reference means**.

**A raw global port id would be a security hole.** A guest that writes
`K_PORT 7` names port 7 whether or not it holds it. Every capability in the
system would be forgeable by counting.

So an encoded value carries a **transfer table**: the live things it is
carrying, in order, and the body refers to them by index. The sender can only
put something it HOLDS, because it has to pass the value; the receiver gets
handles minted for it. No global id crosses and nothing is trusted. That is the
answer CapTP and Cap'n Proto reach, for the same reason.

**One table, not one per kind.** Ports, sentinels, and whatever becomes live
next all share it — `postMessage`'s transferables, and for the same reason: a
table per kind means a new table, and a format change, every time something new
becomes transferable.

The safety rule is the table's, so it holds for everything in it. A sentinel
arriving from the GUEST carries no host id, because `0022` says authority is
the host recognising a value in its own grant table and an id that came from
the guest must never be believed.

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
5. The transfer table: ports and sentinels crossing, and the yield that
   backpressure needs.
6. Diagnostics on the process, after each step.
