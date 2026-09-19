(ns lang.meta
  "Metadata as a PROTOCOL, alongside the builtin.

  `meta` and `with-meta` are direct builtin calls and stay that way -- they sit
  on hot paths and a dispatch per call buys nothing for a question the runtime
  answers from a type tag. `flint.protocols/Meta` and `WithMeta` exist beside
  them so that the question `does this carry metadata?` has an answer, and so
  that generic code can be written once against the protocol.

  WHAT THIS FILE IS FOR is the agreement between the two. The extension list in
  `flint.protocols` is written by hand and the runtime's own list is
  `kin/meta.kin`'s `has-meta`; nothing but this makes them say the same thing,
  and a disagreement is invisible in both directions -- `satisfies?` answering
  false for a kind that does carry metadata reads exactly like a kind that does
  not."
  (:require [flint.check :refer [expect]]
            [flint.protocols :as p]))

;; Every meta-capable kind, as a value of that kind. `with-meta` on a value that
;; CANNOT carry metadata answers the value unchanged rather than throwing, so
;; `(meta (with-meta x m))` being `m` is the test that actually separates them.
(def carriers
  [['symbol 'sym]
   ['vector [1 2]]
   ['map {:a 1}]
   ['set #{1}]
   ['list (list 1 2)]
   ['fn (fn [] nil)]
   ['atom (atom 1)]
   ['tagged (tagged-literal 'a/b [1])]])

(defn ^:flint.check/test every-carrier-satisfies-both []
  (doseq [[what x] carriers]
    (expect = [what true] [what (satisfies? p/Meta x)])
    (expect = [what true] [what (satisfies? p/WithMeta x)])))

(defn ^:flint.check/test the-protocol-agrees-with-the-builtin []
  (doseq [[what x] carriers]
    (let [tagged (with-meta x {:m what})]
      ;; The builtin's answer and the protocol's, on the same value.
      (expect = [what {:m what}] [what (meta tagged)])
      (expect = [what {:m what}] [what (p/-meta tagged)])
      ;; And the protocol's `with-meta` is the builtin's.
      (expect = [what {:m what}] [what (meta (p/-with-meta x {:m what}))]))))

;; THE OTHER DIRECTION, which is the half a one-sided list gets wrong. A kind
;; that cannot carry metadata must answer false, or `satisfies?` is decoration:
;; every value would satisfy every protocol and the question would stop meaning
;; anything.
(defn ^:flint.check/test a-kind-that-cannot-carry-says-so []
  (doseq [x [1 1.5 "s" :k true nil]]
    (expect = [x false] [x (satisfies? p/Meta x)])
    (expect = [x false] [x (satisfies? p/WithMeta x)])))

;; METADATA STILL BEATS KIND, which is the dispatch order these protocols
;; inherit. A value carrying its own `-meta` is asked first, exactly as for any
;; other protocol -- so extending `Meta` is not a special case in the machinery.
(defn ^:flint.check/test metadata-still-beats-kind []
  (let [v (with-meta [1] {'flint.protocols/-meta (fn [_] :mine)})]
    (expect = :mine (p/-meta v))))
