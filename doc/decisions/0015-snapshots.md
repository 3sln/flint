# 0015 — VM snapshots: instant, exportable, inspectable

> **BUILT** — capture, export/import, and an inspector that reads the format,
> all three. Opt-in under `0016`: +41 431 bytes when enabled, absent otherwise.
> `test/snapshot.clj` is the standing check. Specified after a dozen sessions
> of ad-hoc instruments, two of which lied and each cost a run.
>
> **Amended 2026-08-28: there are now TWO capture formats, because there are two
> jobs.** The memcpy below is for post-mortems and is unchanged. A second format
> carries only what survives a collection, for shelving a running sandbox — see
> "Two formats, and why the memcpy is not enough" at the end. A snapshot in either format now
> carries the fingerprint of the image it belongs to, and a mismatch is refused
> by name.

Three pieces:

1. **Snapshot** — the whole VM state, captured instantly.
2. **Export/import** — that state as bytes, and back.
3. **An inspector** — a tool that reads the format, shared with the debugger.

## Why this is the right answer to the instrument problem

Every probe built for the port bug asked ONE question, and two of them answered
it wrongly in ways that looked clean: a counter hooked on `forward()` reported
zero moves and *could not* have reported anything else, and a watch address
registered against a young object went stale the moment it was promoted.

A snapshot has neither failure mode, for one reason: **it is a copy, not a
question.** Ask whatever you like afterwards, re-ask when the question changes,
and compare two snapshots rather than trusting a running probe. The instrument
cannot lie about state it did not interpret.

And flint can do this where most runtimes cannot — the same property that made
green threads and `0014`'s debugger cheap. **The VM's state is data in linear
memory plus a handful of Rust structures.** There is no native stack to unwind
and no JIT state to reconstruct.

## Snapshot must be a MEMCPY, not a traversal

If capturing walks the object graph, it can be wrong exactly as the instruments
were wrong — a traversal that misses an edge produces a snapshot missing an
object, and you would be debugging the capture rather than the bug.

Copy the raw bytes of both semispaces and the old space, plus the Rust-side
state, and interpret **later**. "Instant" and "cannot misrepresent" are the same
requirement.

### What must be in it, because omitting one is silent

Linear memory is the easy half. The half that is easy to forget:

- roots: value stack, shadow stack, globals, consts, singletons, intern tables
- the frame table, and every green thread's saved state
- the remembered set — **both the list and the per-object flags**, since this
  investigation turned on those being able to disagree
- scheduler state: run queue, waiter table with generations, port table
- allocation state: bump pointers, space bases, ages, gas counters

A snapshot missing any of these is a snapshot that answers some questions
correctly and others confidently wrong, which is the failure being designed out.

## Import, and the test that proves it

- **Round trip is byte-identical**: snapshot, export, import, snapshot again,
  compare. Anything that differs is state the format is dropping.
- **Resumption is behaviourally identical**: a program snapshotted mid-run,
  exported, imported and resumed produces the same answer, and the same
  instruction count, as one that never stopped. `0005`'s determinism and
  `0009`'s deterministic gas make that a testable equality rather than a hope —
  and it is the strongest possible check that the format is complete.

**The format is tied to a runtime version.** It is a serialised internal layout,
not an interchange format. Stamp it, and refuse a mismatch loudly rather than
reading a plausible-looking heap that means something else.

## The inspector is the piece with the longest life

A binary snapshot nobody can read is a core dump. The tool should:

- list objects by type, size, age, space
- follow and reverse-follow pointers — *what points at this address* is the
  question this whole investigation needed and never had
- **validate**: walk every object, check every pointer, report every dangling
  or forwarded reference at once, rather than tripping over them one per run
- **diff two snapshots**: what moved, what died, what changed — which is how you
  answer "what did this collection do" without instrumenting the collector

That last one would have answered the current bug in a single run: snapshot
before and after the minor that moves the message, and the diff names it.

**It is the same reader `0014` needs**, so build the object model once and let
the debugger and the inspector share it.

## And it is not only for debugging

**Snapshot-restore is the fix for flint's per-invocation cost.** The construe
benchmark showed a warm parse at 0.085 ms against 0.381 ms for a whole
invocation, and most of that gap is top-level initialisers running again on every
`main()`. Snapshot after initialisation, restore per request: the initialisers
run once and each request starts from a memcpy of a 170 KB live set.

That turns a known weakness into a strength, and it is the same machinery.

Two further uses worth naming: **a bug report becomes a snapshot** — for a
platform running model-written code, "here is the exact state when the gate
failed" is worth more than a stack trace; and **snapshot plus the host event log
is a complete replay**, because the scheduler is deterministic.

## Snapshots from inside the program: `(snap "name")`

**Its own form, not an option on `break`.** This was first written as
`(break :snap "name")`, and the owner's correction is right: a form that does not
break should not be called `break`. Two forms, each doing one thing:

- `(break)` — parks for a debugger (`0014`).
- `(snap "the-snapshot")` — **captures a named snapshot and carries on.**

Capture-and-continue is what makes `snap` useful without a debugger attached,
which is the ordinary dev run and where dropping snapshots through a program is
worth most. Somebody who wants both writes `(snap "x")` then `(break)`, and the
reading is obvious rather than depending on a keyword.

### It MUST NOT ALLOCATE, and that is not a performance note

This is the lesson that cost a session. Taking a snapshot hid the port bug
outright: capture grew a buffer, that changed allocation timing, and the bug is
sensitive to precisely that. A snapshot that perturbs the thing it is
investigating is the observer effect the whole tool was meant to escape.

So `(break :snap)` allocates nothing in the flint heap. Reserve the capture
buffer at first use, sized to the maximum heap, outside anything being measured —
and **assert it**: a test that captures inside a loop and shows the allocation
count unchanged. Without that assertion this feature quietly stops being usable
for the one class of bug it exists for.

### Naming, and what happens on the five-hundredth hit

A named break inside a loop fires repeatedly. Keeping every capture exhausts
memory; keeping the first is almost never what somebody wants.

**Last write wins, and the snapshot records how many times that name was hit.**
Then "this is capture 500 of 500" is on the artifact rather than being something
to wonder about. Storage is a bounded ring per name, and the host is notified
through the event queue so it can export and drop rather than accumulate.

### In a production build it is elided, and the count is reported

Under `0016` there is no snapshot machinery in production, so `(snap …)` must
compile to nothing — zero bytes, not a no-op call. The same goes for `(break)`.

But silently eliding is how debug code ships unnoticed, so **the compiler reports
how many of each it elided**, and a flag makes their presence an error for
anybody who wants that guarantee. Same spirit as `:exclude` being an
assertion rather than a pruning (`0004`).

**One consequence to write down: instruction counts are comparable within a build
configuration, not across one.** A program with breaks compiled for diagnostics
executes more instructions than the same source compiled for production. Both are
deterministic; they are not the same number, and a gate comparing them would be
comparing two programs.

## Cost, and where it must not land

It must not grow a pure module (`0005`). Capture and restore are small, but the
inspector and the export format do not belong in the floor — a separate unit, or
debug-gated, with the module-size test proving it.

## What must be true if this is built

- Capture is a memcpy of raw state; nothing in it walks the object graph.
- Round trip is byte-identical, asserted.
- A snapshotted-and-resumed program gives the same answer and the same
  instruction count as one that ran through.
- The inspector validates a whole heap in one pass and reports every bad pointer
  together.
- Diffing two snapshots across a collection names what moved and what did not.
- A pure module's size is unchanged, asserted.
- A snapshot from a different runtime version is refused by name.
- `(snap "x")` in a loop **allocates nothing** — asserted by allocation
  count, not by inspection.
- A repeated name keeps the latest and reports the hit count.
- A production build contains no `snap` or `break` forms and reports how many of
  each it elided.


---

## Two formats, and why the memcpy is not enough

Everything above argues for a memcpy over a traversal, and the argument is
sound **for the job it was written about**. It is not sound for shelving, and
the difference is worth stating rather than quietly picking one.

### What the memcpy cannot do

* **It copies what is not there.** The semispaces are copied whole, dead objects
  and unused reserve included. A small program's capture is **5 275 808 bytes**,
  of which the inspector reports 1.6 MB live and 141 objects after a collection.
* **It pins the restore to identical addresses.** The design says so in its own
  words — rewriting pointers "would mean a traversal, which is the thing this
  design exists to avoid" — so a capture cannot be restored into a different
  instance, let alone a different process. Shelving is exactly that.
* **It is wasm-only in principle, not just in practice.** The JVM and CLR ports
  hold flint values as host objects on a host collector's heap. There is no byte
  range to copy. A memcpy format can never reach them.

### Why a traversal is admissible after all

The objection above is that a traversal which misses an edge yields a snapshot
missing an object, and then the capture is what needs debugging rather than the
bug. That is fatal to a **bespoke** traversal.

It is not fatal to this one, because **the collector decides what is live and
the exporter only enumerates what survived**. `export_live` runs a major
collection and then walks the heap linearly: the nursery is contiguous after a
copying minor, and old space is swept, so everything that is not `TY_FREE` is
live. There is no second opinion about reachability to get wrong. A missed edge
here would be a collector bug that loses objects in ordinary running — which
`test/gc_stress.clj` already checks for from several directions.

That also makes the cost honest: it **is** a major collection, which is what the
owner said it should be.

### What it buys, measured

| | verbatim | live set |
| --- | ---: | ---: |
| the same program's state | 5 275 808 B | **38 524 B** (0.7%) |
| restores into another instance | no | **yes** |
| carries dead objects | yes | no |
| can capture a corrupt heap | **yes** | no |

The last row is why both are kept. A capture that walks cannot capture a heap
whose pointers are already wrong — it would follow the bad one or refuse — and
that is precisely the situation the memcpy was built for. Two operations, two
jobs: **traverse to move a sandbox, memcpy to debug one.**

### The fingerprint, and the failure it prevents

A snapshot carries the heap and the VM state and **no code**. That is what keeps
it small and is the whole reason it can be moved — but every frame's `ip`, every
constant index and every var slot in one is an index *into an image*. Restoring
against a different program does not fail. The header parses, the geometry is
plausible, and all of those indices quietly mean something else.

So both formats carry a fingerprint of the image bytes, taken at load before
anything is interpreted, and both refuse a mismatch. `flint_snapshot_refused`
says which check failed — layout or image — because a bool cannot, and "no" is
not a diagnosis. `flint_image_fingerprint` lets a host ask before it tries.

Tested with a second program rather than by construction, and with the control
that makes the rest mean anything: two programs really do fingerprint
differently, each refuses the other's snapshot, the reason given is the image
rather than the layout, **and each still accepts its own**.

### Export and stop

`flint_snapshot_export_and_stop` captures and then leaves the sandbox with
nothing runnable. Not a flag the interpreter consults: the frame stack **is** the
continuation, so dropping it is what "stopped" means and no loop needs a new
condition in it.

The two halves are one call because "capture, then stop" being atomic is the
property a caller relies on, and it should not have to know that a builtin runs
between two bytecode instructions and that the scheduler is cooperative.

The heap is deliberately left alone: it has just been exported, and a caller who
wants the memory back drops the instance, while one who wants to inspect what
they shelved still can.

### What is not done

* **The ports have neither format.** The traversal is what makes one possible
  there — a host-object graph can be walked and cannot be memcpy'd — but it is
  not written.
* **The live-set format is not versioned across a runtime change** beyond its
  own `VERSION_LIVE`. A snapshot outliving a flint upgrade is refused by
  fingerprint anyway, since the image changes, but that is a side effect rather
  than a designed guarantee.
