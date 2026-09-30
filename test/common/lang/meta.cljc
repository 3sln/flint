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
            [flint.protocols :as p]
            [flint.port :as port]))

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
   ['tagged (tagged-literal 'a/b [1])]
   ;; A PORT carries metadata on a HANDLE (`DECISIONS.md#ports-speak-protocols`):
   ;; a channel end is made in this heap, so no host is involved.
   ['port (first (port/channel))]])

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

;; A PORT'S METADATA IS ON A HANDLE, NEVER ON THE PORT (`ports-speak-protocols`).
;; A port is shared by every holder, so `with-meta` answers a second handle to
;; the same port -- and that handle has to BE the port in every other respect,
;; or annotating one would quietly break the code that uses it.
(defn ^:flint.check/test a-port-handle-is-the-port-it-holds []
  (let [[a b] (port/channel "handles")
        m {:flint/protocols #{'x/Speaks}}
        h (with-meta a m)]
    ;; The shared port is untouched; the handle carries the metadata.
    (expect nil? (meta a))
    (expect = m (meta h))
    ;; It is a port, of kind `:port`, and it is `=` to and hashes as its port.
    (expect true? (port/port? h))
    (expect = :port (flint.rt/kind h))
    (expect = a h)
    (expect = h a)
    (expect = (hash a) (hash h))
    (expect = :found (get {a :found} h))
    ;; It is not some OTHER port, however similar.
    (expect not= b h)
    ;; Every operation reads through it: send and receive both ways.
    (port/send h 42)
    (expect = 42 (port/receive b))
    (port/send b 7)
    (expect = 7 (port/receive h))
    (expect = (port/label a) (port/label h))
    (expect = (port/state a) (port/state h))
    ;; Metadata EXTENDS without touching an earlier handle.
    (let [h2 (vary-meta h assoc :more 1)]
      (expect = 1 (:more (meta h2)))
      (expect nil? (:more (meta h)))
      (expect = h h2))
    ;; And closing through a handle closes the port itself.
    (port/close h)
    (expect true? (port/closed? a))))

;; PROTOCOLS DISPATCH ON A HANDLE'S METADATA, as on any value's: the rule the
;; whole of `ports-speak-protocols` is built on, exercised on a port.
(defprotocol Speaks (speak [x]))

(defn ^:flint.check/test a-protocol-dispatches-on-a-port-handle []
  (let [[a _] (port/channel)
        h (with-meta a {`speak (fn [_] :from-the-handle)})]
    (expect = :from-the-handle (speak h))
    (expect false? (satisfies? Speaks a))))
