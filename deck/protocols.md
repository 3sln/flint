# Protocols

## Protocols, and metadata dispatch as the main road

All polymorphism is built on protocols. They work differently from Clojure's for
a reason that is not a shortcut: **flint has no types.** No `deftype`, no
`defrecord`, no classes. So "which type is this?" has no general answer, and
dispatch has two roads:

```clojure
(defprotocol Shape
  (area [s])
  (describe [s prefix]))

;; 1. built-in kinds -- a small closed set
(extend-protocol Shape
  :vector (area [s] (* (nth s 0) (nth s 1)))
  :number (area [s] (* s s)))

;; 2. metadata -- for everything a user defines
(def circle (with-meta {:r 2} {:shapes/area (fn [s] (* 3 (:r s) (:r s)))}))

(area [3 4])   ; => 12   by kind
(area circle)  ; => 12   by metadata
```

The kinds are `:nil :boolean :number :string :keyword :symbol :vector :map :set
:list :fn :port :thread :atom :var :regex :exception :other`, and `(kind x)`
returns one. It is a closed set because it can be: those are all the things a
flint value *is*.

**Metadata is the primary mechanism here**, not the corner it is in Clojure,
where `extend-via-metadata` is opt-in and slightly out of the way. There is
nothing else a user-defined abstraction can be, so this is the road to reach for
rather than the fallback. A method attached by metadata is keyed by the method's
fully-qualified keyword, exactly as Clojure keys `extend-via-metadata`.

A value with no implementation fails with a message naming the protocol, the
kind, and what to do:

```
no implementation of shapes/area (protocol shapes/Shape) for a value of kind
:string. Extend the protocol to that kind, or attach :shapes/area as metadata
on the value.
```

### What can carry metadata, and what cannot

Since metadata is load-bearing, it matters which values have anywhere to put it.
This falls out of the value encoding rather than being a policy:

| carries metadata | does not |
|---|---|
| vectors, maps, sets, lists and seqs, symbols, atoms | **inline values** — strings of ≤5 bytes, unqualified keywords, and chars, which live *in the value word itself* |
| | numbers, booleans, `nil` — likewise immediate |
| | heap strings and keywords, which are **interned**: metadata would break the invariant that makes `=` on them a single compare |
| | functions and ports |

Those dispatch by kind, which is what kinds are for. `with-meta` on a value that
cannot carry it returns the value unchanged rather than pretending.
