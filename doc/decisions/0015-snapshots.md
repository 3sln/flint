# 0015 — VM snapshots: instant, exportable, inspectable

> **NOT BUILT — specified after a dozen sessions of ad-hoc instruments, two of
> which lied and each cost a run.**

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
