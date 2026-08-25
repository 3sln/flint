# 0022 — Opaque values: identity without structure

> **BUILT.** `(opaque)` and `(opaque "label")` in `clojure.core`; `TY_OPAQUE` in
> the runtime; host-minted values reaching the entry function as its second
> argument; `p/open`'s `:capability`; not sendable; invalidated on snapshot
> import. Metadata and protocol extension are still deliberately absent.
>
> Two things the tests found that reading would not have. A guest-minted
> `(opaque "fs")` opened the filesystem, because "nothing was presented" and
> "something the host never issued" both arrived as 0 and the host read a
> forgery as an absence. And the first snapshot test asserted a clean import on
> a heap that contained no capabilities; the counter now proves the sweep saw
> them.

The generalisation, in the user's terms: rather than a capability type, *"the
idea of an opaque (sentinel) value in general, which can't be minted/created,
only passed around."*

## Why this is worth more than the capability it came from

flint has no `(Object.)`. In Clojure the unique-sentinel idiom is ordinary:

```clojure
(def ^:private not-found (Object.))
(if (identical? not-found (get m k not-found)) ...)
```

It is how you distinguish *absent* from *present and nil*, how a library gets a
key nobody else can collide with, and how a protocol keeps a private marker.
flint has no host classes, so there is no `(Object.)` and no replacement for it —
a real gap in a Clojure dialect, and one that has nothing to do with the CLI.

An opaque value is that, plus the property `0021` needs.

## Two kinds, and the difference is provenance

**Guest-minted.** `(opaque)` — or `(opaque "label")`, the label being for
printing only and playing no part in identity. Each call yields a value distinct
from every other. This is `(Object.)`, and guest code may make as many as it
likes; making one grants nothing.

**Host-minted.** Created only across the host ABI, carrying a host-side
identifier in a field guest code cannot read. This is what a capability is.

Both are the same type to the collector, to `identical?` and to printing. They
differ in whether the host recognises them.

## The trap this generalisation creates

Making the type available to guest code means **authority can no longer be
"is it opaque"**. If it were, a program would mint its own and present it.

So: a capability check is the host recognising *this specific object* in its own
grant table — never a type test. The type is necessary and nowhere near
sufficient. Writing it here because the generalisation is what introduces the
hazard: with a capability-only type, "is it a capability" would have been a
sound check, and a later reader could reasonably assume it still is.

## Semantics

**Equality is identity.** `=` and `identical?` agree; there is no structural
comparison because there is no structure.

**The hash must be stored, not derived from the address.** The nursery is a
copying collector (`0001`, the collector section of the README) — objects move.
An address-derived identity hash would change under collection, and a value in a
map would become unfindable by the key that put it there. So a stable id is
assigned at creation and stored in the object, exactly as the JVM does. This is
the single most likely thing to get wrong here, and it fails intermittently and
under load, which is the worst way to find it.

**Printing reveals nothing but the label.** `#<opaque fs>`, or `#<opaque>`
without one. There is no read syntax, deliberately: a value with a printed form
that can be read back is forgeable by construction.

**Not sendable.** An opaque value may not cross a port, in any codec. Serialise
one and it becomes bytes; accept those bytes back and it is mintable, which is
the whole property gone. This needs **no new mechanism**: `check_sendable`
already walks a message and already rejects ports (`0005` — ports cannot be
transferred), so opaque values join that rejection with the same error shape.

**No metadata, no protocol extension, to begin with.** Both are additive later.
Allowing protocol extension on the opaque type would make every opaque value
share behaviour, which is rarely what a sentinel wants.

## Snapshots, where the two kinds diverge

`0015` exports and imports VM state, and here the provenance matters:

- **Guest-minted values restore normally.** They are identity and nothing else,
  and a restored run is entitled to the identities it had.
- **Host-minted values must be re-bound or invalidated on import**, never
  restored as live authority — otherwise importing a snapshot taken from a run
  that held `:fs` grants `:fs`.

That is the one asymmetry between the two kinds, and it is a property of the
importing host rather than of the value.

## Cost

A heap type with a header and two fields — a stored hash and a host id. No
scanning cost beyond a type tag (`Layout::None`: no interior references, so the
collector never walks one). Creation is an allocation. Comparison is a pointer
compare. Nothing here touches the hot path of anything already built.

## What must be true

- **Guest code cannot construct a host-minted value.** No fixnum, string, or
  arithmetic result passes; asserted directly rather than argued.
- **An opaque value in a map is still findable after a collection**, including a
  major one that moves it. This is the stored-hash requirement, and it needs a
  test that forces collection between `assoc` and `get`.
- **Every codec refuses one**, and the refusal is the same error a port gets.
- **`0021`'s capability check tests the grant table, not the type** — with a test
  that mints a guest opaque value and fails to open with it.
