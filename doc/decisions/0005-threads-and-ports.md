# 0005 — Green threads, ports, and protocols

A large phase. Read it all before starting: the first section decides the shape
of everything after it.

## 1. `open` must NOT block wasm. It parks a green thread.

The obvious reading of "a blocking `open` that calls out to the host" leads
straight into the one hard problem in wasm: **a synchronous wasm export cannot be
suspended mid-execution** to await a host answer. The usual escapes are both bad
for us:

- **JSPI** — JavaScript Promise Integration. JS-hosts only, and this project
  claims to run on workers, native embedders, the JVM, C#. It would trade the
  portability that is the entire point.
- **Asyncify** — a binaryen transform that rewrites the module so its stack can
  be unwound and rewound. Works everywhere, and costs code size and speed on
  every function, forever.

**We need neither, because flint is an interpreter.** A green thread is just a VM
state — its own value stack and frame stack. The scheduler is a loop that picks a
runnable thread and runs it for a step budget. "Blocking" means *this thread is
not runnable until something makes it runnable*. **Nothing blocks the host, and
nothing suspends a wasm frame**, because the interpreter never left its own loop.

This is the leverage the interpreter design was already paying for. Take it.

### What that does to the module's interface

The contract grows from "call `main` once, get an answer". Roughly:

- the host calls `main` as now;
- the scheduler runs until every thread is finished or parked;
- if threads are parked on host ports, `main` returns a status meaning **"I need
  the host"**, with the pending requests readable;
- the host services them and calls back in to resume; the scheduler continues.

**Keep the pure case exactly as simple as it is today.** A program with no ports
runs to completion in one call and returns its answer — no pump loop, no status
protocol, nothing new in `host/flint.mjs` for the caller to care about.

## 2. None of this may grow a pure module

The project is a *pure logic executor*. Threads and ports are explicitly impure —
useful only when a host wants to lend a capability.

So they are **namespace units like any other** (0003), and a program that never
mentions `open`, `channel` or thread spawning must produce a module with **no
scheduler, no port machinery and no host callback surface** — the same size as
today, within noise.

Assert it with a test and report the number. This is the requirement that keeps
the feature honest.

## 3. Threads and the collector — the part that will actually bite

The GC's whole design rests on *the VM's value stack IS the root set*, precisely
scanned. **With N threads there are N stacks**, and a parked thread's stack is
full of live references that nothing is currently executing.

So the root walk must iterate the thread table, and the thread table is itself a
root. Get this wrong and the failure is a use-after-collect that only appears
when a collection lands while a thread is parked — which is exactly the case the
existing tests do not cover.

**Extend the stress mode to cover it**: a test that spawns threads, parks some,
collects at every allocation, and checks parked threads resume with their values
intact. The existing negative-control discipline (`without_the_barrier_…`) is the
standard to hold this to.

## 4. Dynamic vars are per GREEN thread

`binding` becomes a stack discipline per green thread, not per host thread. The
README currently lists dynamic vars under Limits; this removes that limit and the
Limits section must stop claiming it.

**Decide and state one thing: does a spawned thread inherit its spawner's
bindings?** Clojure conveys them to `future` and agents. Either answer is
defensible; silence is not, because it is the kind of thing somebody discovers
through a bug at three in the morning.

## 5. Ports

A port is an endpoint. Messages go in one and come out of the other. Either end
may be inside the runtime or held by the host.

```clojure
(def resource (open "the-thing"))        ; blocking; host allows or refuses
(let [[a b] (channel "optional label")]  ; a coupled pair
  ...)
(close p)                                 ; any port, not only opened ones
```

- **`open`** signals the host, which either **refuses** (the capability is not
  allowed — a normal, expected outcome that must be a clean error, not a crash)
  or allocates a channel, keeping one end and handing the other back. The port
  *is* the resource; a friendly driver is usually wrapped around it, but driving
  it raw must work.
- **Back-pressure and bounded buffers.** A send to a full port parks the sending
  thread — the same parking mechanism as `open`, not a second one.
- **What may cross:** data, and other ports. Nothing else. Functions and
  closures must be refused, by name, at the send.

### Value semantics, and why the optimisation is sound

Transfer is **by value**; sending may serialise. Within one runtime you may pass
by reference as an optimisation — **and that is safe precisely because flint
values are immutable**. Say so in the README: it is the property that lets this
runtime take a shortcut a mutable-object language could not.

**Ports are the exception, and they are not values.** A port has identity and
state. Sending a port over a port is therefore a real design question: does the
sender keep its end, or hand it over? Decide, state it, test it. Ownership
transfer is usually what people mean by "a port carries a port", and a silent
copy would be a security hole in a capability system.

### Determinism

A pure logic executor whose answer depends on scheduling order is no longer
worth its name. **The scheduler must be deterministic** given the same inputs and
the same order of host events: round-robin with a fixed step budget, no
randomness, no wall-clock dependence. Reproducibility is a large part of what
makes this project valuable; do not spend it here.

### `open` as a function

The request says "built-in form". Prefer a **function** unless something forces
otherwise — it composes (`(map open names)`), needs no compiler support, and the
capability name is an ordinary string. If it must be a special form, say why.

## 6. Protocols, and metadata dispatch as the MAIN road

All polymorphism will be built on protocols, and they do not exist yet.

Note what flint does not have: **no deftype, no defrecord, no types at all.** So
the usual question "which type is this?" has no general answer. That makes the
dispatch design unusual and, I think, rather clean:

- **Built-in kinds** are a small closed set — nil, number, string, keyword,
  symbol, vector, map, set, list, fn, port. Protocols extend to those by kind.
- **Everything else dispatches on METADATA.** Clojure has this as
  `extend-via-metadata`, opt-in and a bit of a corner. **Here it is the primary
  mechanism**, because there is nothing else for a user-defined abstraction to
  be.

That is a real deviation from Clojure and it belongs in *Where flint differs*,
stated plainly rather than left for somebody to infer.

**Metadata must therefore work properly**, including on the values that can carry
it. One limit falls out of the value encoding and should be documented rather
than discovered: **inline values cannot carry metadata** — small strings,
keywords and chars are interned into the value word itself, so there is nowhere
to hang a map. Say which kinds can and cannot.

## 7. `:wasm-ld` becomes `:wasm-path`

The old name reads like "flags to pass to wasm-ld" rather than "where to find
units". Rename it. If `:wasm-ld` already shipped, accept it as a deprecated alias
for one release and say so; otherwise just change it.

## What must be true at the end

- A pure program's module size is unchanged, asserted by a test.
- A program using `channel` sends, receives, blocks on a full buffer and resumes.
- A host capability is opened, driven, and **refused** — all three tested, the
  refusal as a clean error.
- Sending a function is refused by name.
- Parked threads survive collection under stress, with values intact.
- `binding` works per thread, and the spawn-inheritance rule is tested.
- A protocol dispatches on a built-in kind and via metadata, and a value with no
  implementation fails with a message naming the protocol and the value's kind.
- The scheduler is deterministic: the same program and the same host event order
  produce the same answer, asserted by a test that runs it repeatedly.
- README: threads, ports, the host interface, the value-transfer rule and why the
  by-reference optimisation is sound, protocol dispatch including the metadata
  road, and every new limit.
