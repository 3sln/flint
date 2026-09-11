# Capabilities and the sandbox

A flint sandbox has no ambient authority. It holds logic and its own heap; it
imports nothing and cannot reach a filesystem, the network, an environment
variable, or the host it's running inside of, except through something the
host explicitly handed it. That something is a **port**, and the values a
host lends across one are **capabilities**.

## Ports are how a sandbox reaches the outside

A sandbox can only ask on the one *system port* it was given at construction.
`flint.port/open` is the shape for that ask — "lend me a port called this" —
and the host may grant it or refuse:

```clojure
(p/open "fs")                   ; ask, present nothing
(p/open "fs" {:capability c})   ; present what you hold
```

A refusal is a normal, catchable outcome — a `SecurityException`, not a
crash — and a sandbox given no system port at all is told so rather than
hanging forever waiting for an answer that can never come. `flint.host/request`
is the more general form of the same ask, for when the answer should be an
ordinary value rather than a port:

```clojure
(request "config")
(request "config" {:for :startup})
```

Both `flint.port/open` and `flint.host/request` are requests, not
constructions (decision 0027) — the sandbox cannot make a bridge to the
outside world itself; it can only ask the host for one it already owns.
Everything else described in [Concurrency](concurrency.md) — channels,
send/receive, back-pressure — is what happens once a port exists.

## Opaque values: capabilities without structure

**"Capability" is not a concept the runtime has — it's a pattern, built out
of one primitive.** That primitive is the **opaque value**: a value a
program can hold, compare, and pass around, but never inspect or forge
(decision 0022, shipped, amended 2026-08-30). `(flint.core/opaque)` mints
one; a program can mint its own, but only ever with a host id of `0`, so a
guest-minted opaque value is structurally distinguishable from one the host
issued. Anything in a request's arguments that is an opaque value crosses
carrying the host id it was *issued with* — so a host recognizes the
specific capability it lent, and nothing else, even if the program tries to
pass back something it made up itself. Opaque identity survives a
[snapshot](limits-api.md) export and import.

The runtime used to hold a grant table of its own — a slot on every port, an
ABI export to read it back, a policy check baked into one SDK — and decision
0022 records that all of it is gone, on the grounds that between them they
decided what a capability *was*, which is a decision only the embedding host
should get to make. What's left is just this: a host projects an opaque
value into a sandbox by whatever means it likes; a program that wants
something it enables *presents* that value alongside its request; the host
looks the id up in its own table and decides. Nothing requires this pattern,
and nothing in the runtime enforces it — the native CLI implements it (see
below), and a different host embedding flint through an [SDK](sdks.md) could
build a different one on the same primitive.

## Granting authority: the CLI's `:with`

The native CLI is one concrete way a program gets capabilities — see [The
CLI](cli.md) for the full syntax:

```console
$ flint run :path . :fn app.a/go :with [fs]
```

Without `:with [fs]`, a program that requires `flint.sys.fs` fails at the
`open` with "no system port"; granted, the same virtual namespace — served
by the binary itself over RPC rather than implemented in the language's own
library — answers real filesystem calls, rooted at the invocation directory,
with a path that escapes the root (`../../etc/passwd`) refused rather than
silently clamped. `:with` is a convention of the CLI specifically; an SDK
embedding flint directly grants capabilities its own way, by what it chooses
to serve on the sandbox's system port.

## Guarding *who* may even ask: workspace capabilities

`:with` controls what a *running* program can reach. A separate, earlier
check controls what a piece of *source code* may even reference at all.
`flint.host/request` is guarded:

```clojure
(defn ^{:flint/capabilities-guard [:host]} request
  ...)
```

Only a workspace whose `deps.edn` declares `:flint/capabilities-grant [:host]`
may compile a reference to `request` — checked where the reference is
written, at compile time, and costing nothing at a run. flint's own standard
library declares this for itself:

```clojure
;; lib/deps.edn
{:flint/workspace flint/flint
 :flint/capabilities-grant [:host]}
```

This is a *grant*, not a self-exemption: a var's own guard cannot authorize
its own body (an earlier version tried exactly that, and it let any
workspace mint authority by writing a guard on its own wrapper function —
measured, and it compiled, which is why the design changed). Authority
instead comes from outside — the embedder's workspace table, or a
`deps.edn` a dependency ships. And it's coarse on purpose: the guard answers
"may this workspace talk to the host *at all*", while the host's own
`:with`-style check answers the specific question per call. Holding `:host`
doesn't make what a workspace does with it any less visible or auditable —
it can wrap `request` in a function of its own and hand that out, so the
guard shrinks the set of places that ask *directly*, and does not confine
what they pass on afterward. (This whole mechanism is decision 0036, partly
shipped: the resolver, grants, workspace and var guards, and the request
primitive are built; virtual namespaces, pods and load-time binding are
not.)

## Why an instruction budget instead of a timeout

Alongside authority, a host also bounds how much a sandboxed program can
*do* — and it does this by counting work, not clock time:

```js
inst.exports.set_step_limit(hi, lo);      // gas, in bytecode instructions
inst.exports.set_memory_limit(bytes);     // heap ceiling
```

A wall-clock timeout bounds *time*, and time varies with machine load, with
whatever else is running, with whether a JIT happened to warm up — the same
program can pass on a quiet machine and fail on a busy one. An instruction
count bounds *work*, which is identical on every machine: `test/limits.clj`
runs one program five times and gets the same instruction count every time.
Every native builtin whose cost isn't O(1) charges the counter in proportion
to what it actually touched, so `(= big-vector-a big-vector-b)` can't hide a
million-element comparison behind a charge of one. Exceeding a limit is a
catchable error carrying `{:spent :limit :thread}` as data — and catching it
doesn't defeat it: an escape that itself starts another runaway loop is
stopped by a check that escapes every handler, because a budget a program
can catch its way out of isn't a budget. See [Resource limits](limits-api.md)
for the full argument and measurements.

## What's not there yet

A capability, once granted, **cannot currently be delegated at run time**
from one part of a sandboxed program to another over an ordinary channel — a
port is not itself sendable across a *channel* (though a *bridge* — a
host-backed port — can be sent across another bridge; see [the port
distinction in Concurrency](concurrency.md#channels-vs-bridges)). Per-project
policy narrowing beyond the single program-wide `:with` grant, pods (a
protocol for plugging in a language-agnostic capability server), and
load-time capability binding for an image loaded outside any compiler's
check are all part of decision 0036's design and not built yet.
