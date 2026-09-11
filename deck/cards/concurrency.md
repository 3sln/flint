# Concurrency

Flint's concurrency is cooperative green threads talking over ports. There
are no host threads, no futures, no refs, no agents — `spawn`, `channel`,
`send` and `receive` are the whole surface (`flint.thread` and `flint.port`,
decision 0005, shipped).

## Green threads, and why nothing suspends

Flint is a bytecode *interpreter*, and that's what makes blocking cheap. A
green thread is just VM state — its own value stack and frame stack, held as
an ordinary heap object. The scheduler is a loop inside the interpreter that
picks a runnable thread and runs it for a fixed instruction slice.
"Blocked" simply means "not runnable yet," which an interpreter can express
directly — nothing ever suspends a wasm frame or blocks the host, because
the interpreter never left its own loop. (This is also why a green thread
*can't* park while inside native code: `map`, `sort`, a comparator, or a
lazy-seq force all re-enter the interpreter underneath a native Rust frame,
and that frame isn't a continuation anything can save — trying gives a clean
error rather than corruption.)

Scheduling is deterministic: round-robin from the thread that just ran, a
fixed instruction slice, no randomness and no clock. The same program with
the same host answers, in the same order, gives the same result every time.

```clojure
(require '[flint.thread :as t])

(defn spawn [f])   ;; run f (no args) as a new green thread; returns the thread
(defn yield [])     ;; give the scheduler a chance to run someone else
(defn self [])
(defn thread? [x])
(defn state [t])    ;; :new :runnable :parked :done :failed
(defn result [t])   ;; what it returned, or what it threw; nil until it finishes
(defn join [t])      ;; park until t finishes; rethrows on failure
```
— `lib/flint/thread.cljc`. `join` *parks* rather than spinning, deliberately:
a spinning thread is always runnable, and the scheduler would never get a
chance to hand control back to anything else.

`binding` works, and is **per green thread** rather than per host thread — a
divergence from Clojure worth knowing about if you're porting code:

```clojure
(def ^:dynamic *level* :info)
(binding [*level* :debug] (log "..."))   ; :debug in this thread only
(binding [*level* :trace] (t/spawn f))   ; f sees :trace
```

A spawned thread **inherits a snapshot** of its spawner's dynamic bindings at
spawn time, the way Clojure conveys them to `future` and to agents —
rebinding in the spawner afterward doesn't reach an already-spawned child,
and rebinding in one thread is never visible in another.

## Ports: channels and bridges

A port is an endpoint you send to and receive from. Either end can live
inside the runtime, or be held by the host — and which one determines how it
behaves:

```clojure
(require '[flint.port :as p])

(let [[a b] (p/channel)]        ; a coupled pair, both ends in this heap
  (p/send a :hello)
  (p/receive b))                 ; => :hello

(p/with-open [r (p/open "clock")]  ; a host-backed port
  (p/send r :now)
  (p/receive r))
```
— `lib/flint/port.cljc`. `channel` takes an optional buffer size (default 16
messages) and a label for diagnostics (shown in a deadlock report):
`(channel)`, `(channel "label")`, `(channel cap label)`.

**Only data and capabilities cross a port — never a function.** A function
is refused *by name* at the send, on any port, because a closure's meaning
is its environment, and an environment doesn't travel. Transfer is *by
value* everywhere; within one runtime, that value is passed *by reference*
as a sound optimization, and it's sound specifically because flint values
are immutable — there's no way for a sender to observe a later change,
because there is no later change. A mutable-object language couldn't take
this shortcut.

**Back-pressure**: every port has a bounded buffer, and a send to a full one
*parks the sender* — the same parking mechanism `open` uses, not a second
one. A channel is bounded in messages; a host-backed port is bounded in
bytes, since the point of back-pressure is bounding memory, and one 4 MB
message isn't one message's worth of it.

### Channels vs. bridges

`flint.port/bridge?` distinguishes the two: a channel's ends are both inside
this heap; a bridge crosses to the host, and messages on it are
encoded/decoded by the runtime itself — there's no codec to choose or
misconfigure, `send` takes the same value either kind of port would take,
and a value the wire format can't represent is an error at the send, naming
the value. This asymmetry matters for what's allowed to cross a bridge:

- **A channel end may not cross a bridge.** Both its ends live in this heap
  and the host was never told it exists, so its id would name one of this
  program's own objects from the outside — refused, with a reason, rather
  than silently promoted into something it isn't.
- **A bridge *may* be sent through another bridge**, and that's how a
  capability gets delegated: its id is the host's own and means the same
  thing on the far side, so the receiver ends up holding the *same* port
  the sender had.

### Lifetime

`with-open` closes a port on the way out, including on a throw, and is the
path to reach for. If a program simply drops its last reference, the
collector eventually notices the port is unreachable and the runtime closes
it on the program's behalf — a genuine safety net, but not a *prompt* one,
and a host holding a resource open until a collection happens is a real
cost. A port's state is visible at any time via `flint.port/state`:

| state | meaning |
|---|---|
| `:pending` | an `open` the host hasn't answered yet |
| `:open` | both ends live |
| `:half-closed` | the peer closed cleanly; drain what's buffered, then end of stream |
| `:closed` | this end is closed |
| `:orphaned` | the peer went away *without* closing; receiving errors |
| `:refused` | the host wouldn't lend this capability |

`:orphaned` and `:half-closed` are deliberately different states: one is a
tidy goodbye that reads as end-of-stream; the other is a hang-up and says
so, rather than leaving a thread parked forever on an answer that will never
come.

## Where authority comes from

None of this — `open`, a bridge, a capability crossing one — works without
a host granting it in the first place. See [Capabilities and the
sandbox](capabilities-and-the-sandbox.md) for how a program acquires the
system port it asks `open` on, and how the CLI's `:with` flag and workspace
capability guards fit together.
