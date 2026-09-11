(ns flint.protocols
  "The protocols flint defines for itself, rather than inherits from Clojure.

  THE NAMESPACE EXISTS FOR THE NAME. Canonical `clojure.core` has an
  authoritative owner, and a protocol flint invents and puts there is a name
  minted in somebody else's namespace. `doc/manifest.edn` lists what flint
  adds to each namespace it borrows; this one is where an addition goes when
  it is flint's own idea rather than a gap in the port.

  A PROTOCOL'S IDENTITY IS ITS EXTENSION KEY. `Printable` is
  ``flint.protocols/Printable``, and its methods are
  ``flint.protocols/print-data`` and ``flint.protocols/print-human`` -- the
  fully-qualified symbols a value carries in its metadata to extend itself
  without its kind being extended. Moving a protocol therefore changes that
  key, which is why it is worth doing once and early.

  Protocols that are CLOJURE'S do not belong here. `Datafiable` and
  `Navigable` stay in `clojure.core.protocols`, `EqualityPartition` and `Diff`
  in `clojure.data`: those are ports, and a port that renames its subject is
  no longer a port."
  (:require [flint.core :refer [kind]]))

;; NO `:require` of `clojure.core` above: it is referred everywhere already,
;; and naming it here would make a require CYCLE with `clojure.core`'s own
;; `(:require [flint.protocols])`, which is how the printer reaches
;; `printer-for`. The implicit refer is what makes the pair legal -- the same
;; shape `flint.regex` has, which `clojure.core` also requires and which also
;; does not name it back.

(def Printable__impls (atom {}))

;; `Printable`: how a value writes ITSELF.
;;
;; `pr-str` and `str` fall through to this for anything `clojure.core` does not
;; define itself, so a library type specialises its own printing without the
;; printer learning what it is.
;;
;; TWO METHODS. `print-data` is the form that READS BACK -- what `pr-str` wants.
;; `print-human` is the form for a person, with no quoting and nothing there for
;; a reader -- what `print-str` wants. They are separate rather than one method
;; taking a `readable?` flag, because they are separate jobs and a flag argument
;; is two functions sharing a name. An implementation may give only
;; `print-data`; `print-human` falls back to it, never the other way round.
;;
;; A method recurses into its children through `pr-str` or `print-str`, which
;; are the same two things it is choosing between, so no flag has to travel.
;;
;; STILL WRITTEN LONGHAND, but no longer for the reason it used to be. In
;; `clojure.core` it had to be, because top-level `def` names are collected
;; before macros expand and the printer sat four hundred lines above
;; `defprotocol`. Here that constraint is gone and a different one remains:
;; `print-human` FALLS BACK to `print-data`, and `defprotocol` emits a plain
;; dispatch with no fallback. The shape is otherwise exactly what it emits, so
;; `extend`, `extend-method`, `extend-protocol` and `satisfies?` work on it
;; unchanged.
(def Printable
  (hash-map :flint/protocol 'flint.protocols/Printable
            :impls Printable__impls
            :method-keys '[flint.protocols/print-data flint.protocols/print-human]))

(defn print-data
  "`x` as DATA: a form meant to be read back. This is what `pr-str` reaches."
  [x]
  (let [f (find-protocol-method Printable__impls 'flint.protocols/print-data x)]
    (if f (f x) (protocol-miss 'flint.protocols/Printable 'flint.protocols/print-data x))))

(defn print-human
  "`x` FOR A PERSON: no quoting, no escaping, nothing there to satisfy a reader.
  This is what `print-str` reaches.

  A kind that gives only `print-data` gets it used here too, because a readable
  form is a serviceable human one. The reverse is NOT true and is not done: a
  human form promoted into `pr-str` would produce something that does not read
  back, which is the one thing `pr-str` promises."
  [x]
  (let [f (or (find-protocol-method Printable__impls 'flint.protocols/print-human x)
              (find-protocol-method Printable__impls 'flint.protocols/print-data x))]
    (if f (f x) (protocol-miss 'flint.protocols/Printable 'flint.protocols/print-human x))))

(defn printer-for
  "The implementation that prints `x`, or `nil` when nothing does.

  THE PRINTER'S DOOR, and it answers `nil` rather than throwing because a miss
  in `pr-str` is a FALLBACK and not an error: an unprintable value should print
  as one rather than throw out of `str`. `print-data` and `print-human` throw,
  because a caller naming a method by hand has asked for that method.

  Kept here rather than reaching into `Printable__impls` from `clojure.core`,
  so the impls atom stays this namespace's business."
  [x readable?]
  (if readable?
    (find-protocol-method Printable__impls 'flint.protocols/print-data x)
    (or (find-protocol-method Printable__impls 'flint.protocols/print-human x)
        (find-protocol-method Printable__impls 'flint.protocols/print-data x))))

;; --------------------------------------------------------- the machinery
;;
;; `defprotocol` EMITS calls to these into whatever namespace expands it, so
;; they cannot be private: the caller is the one who names them.
;;
;; THEY SHOULD NOT LIVE IN `clojure.core` EITHER -- Clojure has no
;; `protocol-miss` or `extend-method`, and a port that invents them there is
;; publishing under somebody else's name. Both are here now, and
;; `doc/manifest.edn` records `clojure.core`'s `:extra` as empty because of it.
;;
;; `extend-method` ARRIVED LATE. It could not move while this namespace was
;; unpinned: a top-level `(extend-protocol ...)` calls it during the using
;; namespace's initialisation, and the load order is built from `:require`
;; edges, which that call has none of -- so it found a nil. `core-first` pins
;; this namespace now, which it had to for `protocol-miss` anyway.
;;
;; Moving it was an API change, not a tidy-up: a caller naming it through
;; `clojure.core`'s implicit refer has to require this namespace instead.
;; `test/common/lang/extending` was the one in tree.
;;
;; `find-protocol-method` and `extend` STAY in `clojure.core`: real Clojure
;; has both, and a port that moves what its subject does have is no longer a
;; port.


(defn methods-of [tbl] @tbl)

;; PUBLIC, and it has to be: `defprotocol` EMITS a call to this into its
;; expansion, so the reference lands in whatever namespace used the macro. A
;; var that appears in an expansion cannot be private, because the caller is
;; the one who names it.
;;
;; It was `defn-` until privacy was actually enforced, at which point every
;; namespace using `defprotocol` stopped compiling. That is the mark being
;; wrong rather than the check: it had been documentation, and documentation
;; can be wrong without anything noticing.
(defn protocol-miss [pname mname x]
  (throw (ex-info (flint.rt/str-join
                   ["no implementation of " (str mname) " (protocol " (str pname)
                    ") for a value of kind " (str (kind x))
                    ". Extend the protocol to that kind, or attach "
                    (str mname)
                    " as metadata on the value."])
                  {:protocol pname :method mname :kind (kind x) :value x})))

(defn- method-key
  "The protocol's OWN key for the method named `n`.

  A method key is qualified by the namespace that DEFINED the protocol, which
  is not the namespace doing the extending. Computing it lexically at the
  extend site -- which is what `extend-protocol` did -- writes an
  implementation under a key nobody ever reads, so extending a protocol from
  another namespace was a silent no-op that surfaced later as `protocol-miss`.
  Silent is the part that made it worth a named function and this comment."
  [protocol n]
  (loop [ks (seq (:method-keys protocol))]
    (cond
      (nil? ks)
      (throw (ex-info (str "the protocol " (:flint/protocol protocol)
                           " has no method named " n "; its methods are "
                           (pr-str (mapv name (:method-keys protocol))))
                      {:protocol (:flint/protocol protocol) :method n}))
      (= n (name (first ks))) (first ks)
      :else (recur (next ks)))))

(defn extend-method
  "One method of `protocol` for one `kind`. `mname` is the method's bare name as
  a string, resolved against the protocol rather than against the caller."
  [protocol kind mname f]
  (extend protocol kind (hash-map (method-key protocol mname) f)))
