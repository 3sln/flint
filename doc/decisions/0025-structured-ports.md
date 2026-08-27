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
2. **A called function takes a map** — `(defn f [{:keys [args capabilities]}] …)`
   rather than `[argv]` or `[argv caps]` — delivered in that codec. Any
   function, not one entry point.
3. **`args` is data, not strings**, the way `clj -X` takes `:key value`.
4. **A port carries values** rather than text through a per-port codec, and
   **a port can be sent through a port**, so a capability can be delegated.
5. **One system port carries everything** between the sandbox and the host,
   which replaces eight ABI exports, a bespoke record format and three
   handler kinds with a loop over one channel.

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

sandbox.port.put(v);            // a call, or any message
const back = await sandbox.port.take();
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
* If guest-callable **encoding** is ever added, the live thing itself has to be
  supplied. `(encode {:port p})` is fine, because holding `p` is the proof;
  anything that would take an id or an index and produce a port is the integer
  conversion this invariant forbids, and there must be no such call.
* If a guest-callable **decoder** is ever added — bytes to a value — it must
  refuse `K_PORT` and `K_SENTINEL` for the same reason, since a decoder is an
  encoder read backwards and bytes are integers a guest can write.

Those two are the whole of it, and they are the lines that can be undone by
accident later, so they belong in a test rather than a comment.

### What that leaves

A sentinel the guest received and sends BACK carries its real host id, which is
what makes delegation work: the host looks it up in its own grant table and
finds the grant it issued. A sentinel the guest minted carries zero and is
recognised as guest-minted. Both are correct, and neither needs a table.

## One system port, and the loop that drives it

The host side does not need an `open` handler, a `message` handler, a `closed`
handler, a continuation-token call and a resume call. It needs **one port**.

Every sandbox has a **system port** — not visible to the guest — carrying all
traffic between it and the host. `open` is a message on it with a transaction
id; the answer comes back the same way. So does a send, a close, and
termination.

### `call`, not `init`, and what that makes the nouns

There is no special message for starting the program, because there is no
special *the program*. There is a **`call`**: a function to run, its arguments,
and a `tx`. Which means an artifact is not a program with an entry point — it
is a set of callable functions, and the host chooses.

That changes what the two things are called, and the names now say what they
are:

| | |
| --- | --- |
| **Image** | the artifact. Compiled, inert, holds functions. |
| **Sandbox** | an image instantiated. Holds state, has a system port, serves calls. |

The Docker analogy is exact and worth taking: an image is a thing you
instantiate, and a container is a running one. `Vm` and `Env` were the other
candidates; **`Sandbox`** wins because it names the property that is the point
of the whole project, and a reader who knows nothing else knows what is
guaranteed.

```js
const image   = await compile({ path: […], … });      // Image
const sandbox = image.sandbox({ capabilities: {…} }); // Sandbox

await sandbox.port.put(codec.from({
  what: 'call', tx: 1, fn: 'my.ns/handler', args: { … },
}));

for (;;) {
  const msg = (await sandbox.port.take()).toJS();
  if (msg.what === 'return' && msg.tx === 1) break;
  if (msg.what === 'open') {
    await sandbox.port.put(codec.from({ what: 'response', tx: msg.tx, payload: … }));
  }
}
```

`main` stops being a mechanism and becomes a convention: the function `flint
run` calls when you do not say which.

**A sandbox serves many calls**, which is the shape a per-request binding
wants — instantiate once, call per request — and it is the shape `0023`
described for construe without being able to express it.

**Capabilities belong to the SANDBOX, not the call.** The sandbox is the trust
boundary; granting per call would mean revoking between them, which is a
different and much harder property. A called function still RECEIVES its
capabilities as values rather than reaching for them — `0022` is unchanged, and
ambient authority is still not a thing here — so every called function takes
one map:

```clojure
(defn handler [{:keys [args capabilities]}] …)
```

Whether a host may grant more authority to a live sandbox — a `grant` message
on the system port — is left open. It is not needed for anything yet, and
"authority only ever narrows after instantiation" is a property worth keeping
until something wants otherwise.

### What it replaces

`units/flint/conc.unit.edn` exports eight functions for this today —
`flint_drain`, `flint_events_ptr`, `flint_continue`, `flint_resume`,
`flint_deliver`, `flint_close`, `flint_port_state`, `flint_in_alloc` — plus a
bespoke record format of five little-endian `u32`s and a payload at an offset.
All of it becomes: a port, and the codec that has to exist anyway.

This is a **unification, not a new mechanism**. The event queue already
multiplexes every port over one channel; what changes is that the channel
becomes a port with the ordinary encoding, and the reply becomes a `put` rather
than a separate ABI call.

`0006`'s continuation token does not disappear — it becomes the `tx` field. Its
own description still holds: *it is not an id, it is a continuation.* What
changes is that it travels in a message rather than as an argument to
`flint_continue`.

**The guest does not change.** `p/open`, `p/send`, `p/receive` are what a
program writes, before and after. Only what is underneath them, and what the
host sees, is different.

### One open must not block the others

The loop takes whatever is next and never waits for a PARTICULAR message. When
a thread opens, the runtime writes the request and parks THAT THREAD; every
other thread keeps running, and the host answers when it likes, in any order,
because `tx` says which request an answer belongs to.

A host that instead waited for the answer to one open would serialise the
program to its own latency. The loop above cannot: it dispatches on what
arrives.

### `take` drives the sandbox; `put` usually does not

This is the question the shape raises, and the answer falls out of what each
one means.

**`take` runs the sandbox** until it produces a message or terminates. That is
where the boundary crossing happens, and it happens once per batch rather than
once per message.

**`put` enqueues**, and does not cross. It returns a promise so backpressure
has somewhere to live, and in the common case that promise is already resolved.

**Except when the buffer is full**, where `put` must drive the sandbox, because
what makes room is the guest consuming. That is the same polling park as below,
seen from the other side: nobody is going to call back, so somebody has to run
the thing that drains it.

So there is no `flush()` and no debounce to tune. The two operations already
say when work has to happen: you cannot take without running, and you cannot
fill a full buffer without draining it.

## The one park that polls

Every park in `0006` hands the host a token and is resumed by callback. One is
different, and it is worth naming because it does not fit *everything that
parks parks the same way*.

**A thread blocked on a full buffer has no continuation token.** Nobody will
call back, because what unblocks it is the host TAKING from that port and
freeing a slot. So the runtime re-checks on entry rather than waiting to be
told, and `put` on a full buffer drives the sandbox for the same reason.

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

## What the CLI comes out as

```
flint run     :with [...capabilities] :path [...paths] :fn ns/my-fn :args [...]
flint compile :with [...capabilities] :path [...paths] :fn ns/my-fn :to :wasm
```

`run` builds `{:args … :capabilities …}` and calls the entry with it. `compile`
takes the same `:with`, but `:args` arrive when the artifact is executed rather
than now — which has a consequence worth stating on its own.

`:to` names a TARGET rather than a file: `:wasm` today, and `0010`'s `:jvm`,
`:clr` and native later. A file name is an output detail; the target is the
decision.

`:path` rather than `:src`, because it is a search path — several roots, first
hit wins — and `:src` reads like "the source".

### A compiled artifact declares the authority it needs

If `:with` is given at compile time and the arguments arrive at run time, then
what the program requires has to survive in the artifact. So the declared
capabilities go into the module's metadata section (`0020`), and three things
follow:

* **A host can read what a program needs before instantiating it.** That is
  exactly what `0020`'s section is for -- it is read from the bytes without
  running anything -- and "what authority does this want" is the question most
  worth answering before you run something.
* **The runtime provisions them.** A module that declares `:fs` and is given
  nothing fails at the grant rather than deep inside a call, and says which.
* **The SDK exposes them on the IMAGE**, beside everything else the artifact
  says about itself: `image.capabilities` is the list, `image.metadata` the
  rest. A caller can read what an image wants before making a sandbox for it,
  which is the point of putting it in the bytes.

This does NOT weaken `0022`. A declaration is a REQUEST, not a grant: it says
what the program will ask for, and the host still decides. Authority remains
the host recognising a value in its own grant table, and a program that
declares `:fs` and is refused gets a catchable error exactly as one that
declared nothing does.

## What this costs

**Every program's entry changes.** `(defn main [args])` becomes
`(defn main [{:keys [args]}])` — every test, example and benchmark in the tree.
And `main` stops being special: it is the function `flint run` calls by
default, and nothing else.
Affordable exactly once, before anything is published, and this is that moment.

**`0006` §6's host ABI goes**, though its concepts survive: the continuation
token becomes a `tx` field, and the event queue becomes the system port. Eight
exports and a five-`u32` record format are replaced by a port and the codec.

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
3. The call map, which is the breaking change; do it in one commit.
4. Ports carrying encoded values instead of codec bytes.
5. The system port: `call`, `return`, `open`, `send` and `close` as messages
   with a `tx`, replacing eight ABI exports and the five-`u32` record format.
   Image and Sandbox as the two nouns. A helper over the loop for callers who
   want handlers back.
6. Ports and sentinels crossing, and the yield that backpressure needs. With
   the test that a guest cannot turn an integer into either.
7. Diagnostics on the process, after each step.
8. `:with` in the CLI, the declaration in `0020`'s metadata section, and
   `program.capabilities` in the SDK.
