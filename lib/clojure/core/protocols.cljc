(ns clojure.core.protocols
  "The two protocols `clojure.datafy` dispatches on.

  They live in their own namespace, as they do in Clojure, because the
  metadata key a value attaches an implementation under is qualified by the
  namespace that DEFINED the protocol. Third-party code writes
  `:clojure.core.protocols/datafy` in a metadata map and expects it to be
  found; that only works if the protocol is defined here.

  In Clojure these two are `:extend-via-metadata true`, an opt-in. Here
  metadata is the main road -- flint has no classes -- so there is nothing to
  opt into.")

(defprotocol Datafiable
  "A value that can present itself as data."
  (datafy [o] "A representation of `o` as data. Identity by default."))

(defprotocol Navigable
  "A value whose entries can be followed further."
  (nav [coll k v] "The value `v`, in the context of `coll` and key `k`."))

;; Clojure gets these defaults from `Object` and `nil`. flint's kinds are a
;; closed set, so every one is written out: a protocol with no implementation
;; for a kind THROWS, and datafy's whole contract is that it is safe to call
;; on anything.
(doseq [k [:nil :boolean :number :string :keyword :symbol :vector :map :set
           :list :fn :port :thread :atom :var :regex :exception :other]]
  (extend Datafiable k {:clojure.core.protocols/datafy (fn [o] o)})
  (extend Navigable k {:clojure.core.protocols/nav (fn [_ _ v] v)}))
