# Green threads and ports

## Green threads, and why nothing suspends

> A blocking `open` looks like it needs JSPI or Asyncify. It needs neither.

A synchronous wasm export **cannot be suspended** mid-execution to wait for a
host answer. The two usual escapes both cost something this project is not
willing to spend: **JSPI** is JavaScript-hosts-only, and portability of logic is
the entire point; **Asyncify** rewrites every function so the stack can be
unwound and rewound, and charges size and speed forever, on every program,
including the ones with no ports at all.

flint needs neither, because **it is an interpreter**. A green thread is a VM
state — its own value stack and frame stack. The scheduler is a loop *inside*
the interpreter that picks a runnable thread and runs it for a fixed slice.
"Blocked" means "not runnable yet", which an interpreter can simply say.
**Nothing suspends a wasm frame and nothing blocks the host**, because the
interpreter never left its own loop. This is the leverage the dispatch decision
in [`doc/decisions/0001`](doc/decisions/0001-dispatch.md) was already paying for.

```clojure
(require '[flint.thread :as t] '[flint.port :as p])

(let [[a b] (p/channel 1)                     ; a one-slot buffer
      w (t/spawn (fn [] (dotimes [i 5] (p/send a i)) :sent))]
  [(repeatedly 5 #(p/receive b)) (t/join w)])
;; => [(0 1 2 3 4) :sent]
```

Both directions park there: the sender on a full buffer, the receiver on an
empty one, five times each, and the whole thing is one `main()` call.

**Parking costs the interpreter's hot path nothing.** A park travels as a
distinguished value in `Rt::thrown`, so the check the VM already makes after
every native call is the whole mechanism. On resume the interpreter rewinds to
the call instruction and re-executes it — which is why a parking builtin must
decide to park *before* it changes anything.

**A thread cannot park inside native code.** `map`, `sort`, a comparator and a
lazy-seq force all re-enter the interpreter with Rust frames underneath, and
those frames are not a continuation anybody can save. Trying says so:
`cannot park here: this call is nested inside native code`.

### The scheduler is deterministic

Round-robin from the thread that just ran, with a fixed instruction slice, no
randomness and no clock. The same program with the same host answers in the same
order gives the same result, every time — `test/threads.clj` runs one five times
and asserts a single answer. A pure logic executor whose answer depends on
scheduling order would not be worth the name.

Preemption reuses the interpreter's existing step budget, so it costs no new
check: when a thread's slice runs out the VM yields instead of throwing. (In a
threaded program that budget belongs to the scheduler, so `set_step_limit` is not
available as a debugging aid there.)

### `binding` is per green thread

Dynamic vars work, and they are per green thread rather than per host thread:

```clojure
(def ^:dynamic *level* :info)

(binding [*level* :debug] (log "..."))       ; :debug in this thread only
(binding [*level* :trace] (t/spawn f))       ; f sees :trace
```

**A spawned thread inherits a snapshot of its spawner's bindings**, which is what
Clojure conveys to `future` and to agents. A *snapshot*: rebinding in the spawner
afterwards does not reach the child, and rebinding in one thread is never visible
in another. The scheduler saves and restores the whole binding map when it
switches, which is what makes it per-thread — and why `binding` costs a
single-threaded program nothing but the map.

Rebinding a var that was not defined `^:dynamic` is a compile error naming the
fix, not a silent set.

## Ports

A port is the unit of impurity. flint is a pure logic executor; a port is how a
host *lends* it a capability, and how two green threads talk.

```clojure
(let [[a b] (p/channel "label")]  (p/send a :hello) (p/receive b))   ; => :hello

(p/with-open [r (p/open "the-thing" {:codec edn/codec})]
  (p/send r :now)
  (p/receive r))
```

`open` signals the host, which **allows or refuses**. A refusal is a normal,
expected outcome and arrives as a catchable `SecurityException` — not a crash,
and not something a program has to guess at.

### What may cross, and why by reference is sound

**Data only.** A function is refused *by name* at the send —
`helper is a function, and a closure's meaning is its environment — which does
not travel` — and the check is deep, so a function nested inside a map is caught
too.

**Ports are not transferable and cannot be sent through a port.** That is a
deliberate simplification: no ownership transfer to reason about, no capability
leaking through a message, and a wire format that never has to represent a port.
The cost is real and named in [Limits](#limits): a capability cannot be delegated
at run time. Transfer can be added later; it could not be removed.

Transfer is **by value**. Inside one runtime the value is passed **by reference**
as an optimisation — and that is sound *precisely because flint values are
immutable*. There is no way for the sender to observe a later change, because
there are no later changes; sender and receiver cannot disagree about what was
sent. A mutable-object language could not take this shortcut, and would have to
copy or freeze. It is worth saying plainly because it is the property that makes
message passing cheap here rather than merely possible.

### Back-pressure

Every port has a bounded buffer and a send to a full one **parks the sender** —
the same parking mechanism as `open`, not a second one. A channel is bounded in
*messages*; a host port is bounded in **bytes**, because the point of
back-pressure is to bound memory and one 4 MB message is not one message's worth
of memory.

### Two ends, two lifetimes

This is the part that took two goes to get right, and the first answer was wrong
in an instructive way. Making the host end a root is necessary — without it every
handle the host is holding is a use-after-free waiting for a collection — but on
its own it leaves **explicit `close` as the only way a script can say it is
finished**, which is the `free()` problem. The common case is not a script that
forgets; it is a script that throws, or returns having simply dropped its last
reference.

So the two ends have **separate lifetimes**:

- **The host end is a strong root.** The port cannot be collected while the host
  holds a handle.
- **The flint end is ordinary reachable memory.** When the collector finds it
  unreachable, that is semantically identical to `close`, so the runtime raises
  `{:kind :closed}` on the script's behalf and the port lives on, half-closed,
  until the host lets go.

Which is why the two ends hold each other's *id* rather than each other: a strong
peer link would keep a dropped end alive forever, and would also defeat the
liveness check below. Ids resolve through a weak table — the same machinery the
string interner already uses.

A channel is finished only when **both** ends are, which is what makes
`:half-closed` a state you can see rather than a race you cannot:

| state | meaning |
|---|---|
| `:open` | both ends live |
| `:half-closed` | the peer closed cleanly — drain what is buffered, then end of stream |
| `:closed` | this end is closed |
| `:orphaned` | the peer went away *without* closing; receiving **errors** |
| `:refused` | the host would not lend this capability |

`:orphaned` and `:half-closed` are deliberately different. One is a tidy goodbye
and reads as end of stream; the other is a hang-up and says so.

**`with-open` is the good path**, and the collector is the net. Collection is
deterministic but it is not *prompt*, and a host holding a socket open until a
collection happens is a real cost.

Two things fall out of the same reachability, free:

- **A thread parked on a port whose peer has become unreachable is woken with an
  error** rather than hanging. That receive can never succeed, and the collector
  has already worked out that it cannot. (A parked thread is a root, so the port
  it is parked *on* is never collected; only its peer can vanish, which is
  exactly the case worth catching.)
- **Program exit closes every flint end and leaves the events for one last
  drain**, so a host never has to guess whether more is coming.

### The host interface

A module with no ports is exactly what it was: `main()` returns 0 or 1 and there
is no pump. When there *are* ports, `main` may return **2 — "I need the host"**.
Nothing is suspended; the interpreter simply has nothing runnable.

```js
let code = main();
while (code === 2) {
  for (const ev of drain()) handle(ev);   // one call, everything pending
  code = flint_resume();
}
```

One outbound queue, drained in one call, in a deterministic order:

| event | carries |
|---|---|
| `open-request` | a **token** to answer with, the port id you will hold, the capability name |
| `message` | the port id and the bytes |
| `closed` | the port id — the flint end has gone |

Three kinds through one export rather than three exports: one call per pump, one
ordering rule, and no chance of forgetting one.

**The token is a continuation, not an id.** It is `(generation << 16) | slot`,
and the generation is bumped when the slot is freed, so a late or duplicated
reply is *rejected* rather than resuming whatever thread now occupies that slot —
a wrong thread woken with a stranger's value is the kind of bug that is never
found in production. `flint_continue` returns 0 when it refuses a token, and
`test/host_abi.mjs` asserts every way of getting it wrong. Token 0 is never
valid.

**`flint_continue` enqueues; it never re-enters.** It records the answer and
marks the thread runnable, and the scheduler runs at the next pump. A host may
well call it from inside a host function that wasm invoked, and a naive
implementation would run the scheduler on top of itself.

**The runtime creates the port pair**, keeps the flint end, and tells the host
the id of the end it holds. The host never holds two ends and never hands one
back.

**The event is a notification; the state is the truth.** `flint_port_state(id)`
answers *what is the runtime end of this port doing?* at any time. That is not a
convenience: if an event were the only way to learn a durable fact, then an event
dropped, missed, or simply not drained yet would be an **unrecoverable leak** — a
host handle to a port nobody will ever mention again. The pushed `:closed` is an
optimisation over polling, not the sole carrier of the fact, and
`test/host_abi.mjs` proves it by throwing every `:closed` away and asking
instead. The principle generalises: never let a transient notification be the
only record of a durable state.

It is symmetric. A script can ask its own end (`closed?`), and a send or receive
against a port whose peer is gone **errors rather than parking** — a script
blocked forever on a host that hung up is the same failure as a leaked handle,
seen from the other side.

### Where the cost is, and therefore what is batched

A wasm↔host call is tens of nanoseconds. The expensive part is **marshalling**.
So a message is serialised into linear memory **at send time**, the host reads
byte ranges, and one drain hands over everything pending. Measured, on the
machine named under [Benchmarks](#benchmarks):

| batch size | per message |
|---:|---:|
| 1 | 11 833 ns |
| 1000 | 1 483 ns |

Eager serialisation does cost work when the host never reads. That is the trade:
it is what makes the drain cheap and the byte budget mean anything.

### Formats, and the conversion that is allowed to fail

A host port carries bytes, so a value has to be encoded. The codec is a **value
you pass**:

```clojure
(:require [flint.port :as p] [flint.port.edn :as edn])
(p/open "thing" {:codec edn/codec})
```

| codec | carries | notes |
|---|---|---|
| `flint.port.edn` | everything | flint's own notation; nothing is lost |
| `flint.port.json` | JSON's data model | **strict**: see below |
| `flint.port.transit` | everything | Transit over msgpack, binary |
| *(none)* | raw bytes | `send` takes a string; driving a resource raw has to work |

Passing the codec rather than naming a format is deliberate twice over. A `cond`
over every format inside `flint.port` would make all of them reachable from any
program that opens any port, so a JSON program would carry an EDN reader it never
uses. And a registry filled by requiring a namespace for its side effect is a
load-order trap.

**JSON cannot represent EDN**, and that is not a detail to paper over. Keywords,
symbols, sets and non-string map keys have no JSON form. "The runtime will try to
convert" hides exactly the failures that bite later: a keyword that comes back a
string, a set that comes back an array, `{:a 1}` that becomes `{"a": 1}` and
never comes home. So a value JSON cannot carry is **an error at the send, naming
the value**:

```
JSON cannot represent a keyword: :nope. JSON has no keywords, symbols, sets or
non-string map keys, and converting silently is how a :a comes back a "a".
```

Where the coercion genuinely is wanted, ask for it, the way `clojure.data.json`
makes `:key-fn` the caller's decision: `(p/open "x" {:codec json/codec :key-fn name})`.

**Transit rather than a fourth format**, because it exists for this, it is
self-describing, and it already has the extension mechanism tagged values need.
This implementation leaves out Transit's *caching* — an optimisation, not part of
the data model — so messages are larger than a caching writer's would be, and
says so in the namespace docstring rather than leaving it to be discovered.

### None of it is in a pure module

Threads and ports are namespace units like any other
([`doc/decisions/0003`](doc/decisions/0003-namespace-units.md)), so a program
that never mentions `spawn`, `channel` or `open` carries **no scheduler, no port
machinery and no host-callback surface**. `test/threads.clj` asserts that by
symbol name and reports the number:

| | bytes |
|---|---:|
| pure module before threads | 179 250 |
| pure module with threads linked out | **179 196** |

Fifty-four bytes *smaller*, which is noise in the right direction: the hooks the
scheduler needs cost a few hundred bytes and removing a redundant entry path paid
for them. The requirement was that a pure program not be made worse, and it was
not.

That floor has since moved, and deliberately — see
[Resource limits](#resource-limits), which reports what bought the difference.
Threads and ports are still not in it.

### Two builds

A production module carries **no diagnostic machinery** — absent, not disabled
(`doc/decisions/0016`). Not a runtime flag: a flag leaves the code linked, still
costing bytes and still branching somewhere hot. It is a cargo feature, so the
code is not there at all.

| | bytes |
|---|---:|
| production module | **203 360** |
| the same with `--diagnostics` | 203 884 |
| what turning diagnostics on costs | **+524** |

Absent from production: snapshots and their export format, the inspector, GC
stress mode, the `forward()` plausibility check, the `slot()` forwarded-pointer
assertion, and the heap statistics exports. `test/twobuilds.clj` asserts each by
name.

Present in production, and also asserted, because these are the ones most likely
to be cut by mistake: **gas, the memory cap and the deterministic scheduler**.
They are resource control, not instrumentation, and construe's gates depend on a
reproducible instruction count — the test does not merely check the symbol is
exported, it runs a program under a limit and checks the count is non-zero and
the limit still fires.

It is a security argument as much as a size one. flint's strongest measured case
is sandboxing code somebody else wrote, and a module that ships snapshot export
is a module that can be asked to dump its heap. *Absent* is a different
guarantee from *disabled*.
