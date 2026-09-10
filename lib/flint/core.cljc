(ns flint.core
  "flint's own primitives: what a flint program can reach that Clojure has no
  counterpart for.

  THE NAMESPACE EXISTS FOR THE NAME. `clojure.core` is a PORT, and a port that
  publishes names its subject does not have stops describing the thing it
  ports -- a reader cannot tell which half is Clojure's and which is ours.
  `doc/manifest.edn` had thirty-eight of ours mixed in when this started.

  WHAT IS HERE IS GENUINELY FLINT'S, and each one is here because a Clojure
  program would have reached for the host instead: a string builder because
  there is no `StringBuilder` to interop with, `opaque` because there is no
  object identity to borrow, `kind` because flint has no types and dispatches
  on a closed set.

  WHAT IS NOT HERE, and the distinction took a second pass to get right: a
  helper with no caller outside `clojure.core` is an INTERNAL, not a primitive
  -- `interleave-all` exists for `interleave` and nothing else, and belongs
  behind `defn-` rather than in a namespace of its own. What earns a place
  here is being reachable by a flint program that has a reason to reach it.

  The protocol machinery is `flint.protocols`, not this."

  ;; No `:require` of `clojure.core`: it is referred everywhere already, and
  ;; naming it would make a require CYCLE with `clojure.core`'s own require of
  ;; this namespace -- the shape `flint.regex` and `flint.protocols` have.
  )
;; ------------------------------------------------------------------ strings
;;
;; The runtime reached directly. Clojure gets the same reach through Java
;; interop, which flint does not have and will not.

(defn str-join [xs] (flint.rt/str-join xs))

(defn str-bytes [s] (flint.rt/str-bytes s))

(defn bytes->str [bs] (flint.rt/bytes->str bs))

(defn int-of-char [c] (flint.rt/code-point-at c 0))


;; ---------------------------------------------------------- a string builder
;;
;; Building a string by repeated `str` is quadratic, and Clojure's answer is a
;; `StringBuilder` through interop. This is that instead: three functions over
;; a volatile vector, joined once at the end.

(defn ->str-builder [] (volatile! []))

(defn sb-append! [sb s] (vswap! sb conj s) sb)

(defn sb-str [sb] (flint.rt/str-join @sb))


;; ------------------------------------------------------------------- opaque

(defn opaque
  "A value equal only to itself. `(opaque)` twice gives two different values.

  The optional label is for PRINTING and plays no part in identity: two opaque
  values with the same label are still distinct, which is the point.

  Minting one grants nothing -- see 0022. Anyone can, so possession of *an*
  opaque value is never authority; only the host recognising a specific one is."
  ([] (flint.rt/opaque nil))
  ([label] (flint.rt/opaque label)))

(defn opaque? [x] (flint.rt/opaque? x))

(defn opaque-label
  "The label an opaque value was given, or nil. Printing only."
  [x]
  (flint.rt/opaque-label x))


;; --------------------------------------------------------------------- kind
;;
;; The dispatch key, and it is HERE rather than in `flint.protocols` because
;; it answers what a value IS -- which protocols USE and do not own.
;; `clojure.datafy` reaches for it too, and is not a protocol.

(defn kind
  "The dispatch kind of `x`: one of `:nil :boolean :number :string :keyword
  :symbol :vector :map :set :list :fn :port :thread :atom :var :regex
  :exception :other`. A closed set, because flint has no types."
  [x]
  (flint.rt/kind x))


;; ---------------------------------------------------- tagged-literal access
;;
;; Clojure publishes the CONSTRUCTOR and leaves reading to the `:tag` and
;; `:form` keywords. These are named accessors for the same two, and being
;; named is the whole of what they add.

;; `:tag` and `:form`, the names Clojure's own `tagged-literal` answers to.
(defn tag [x] (flint.rt/get x :tag))

(defn form [x] (flint.rt/get x :form))


;; --------------------------------------------------------------------- bigdec
;;
;; ALWAYS FALSE, and here rather than omitted: flint has no BigDecimal tier,
;; and a program asking should get an answer instead of an unresolved symbol.

(defn bigdec? [x] false)
