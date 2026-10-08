(ns lang.extending
  "Extending a protocol from a namespace that did not define it.

  This is the case the whole mechanism exists for -- a type specialising itself
  for a protocol somebody else wrote -- and it was a SILENT NO-OP. A method key
  is qualified by the namespace that DEFINED the protocol; `extend-protocol`
  built the key from the namespace doing the EXTENDING, so the implementation
  landed under a key nothing ever looked up and dispatch fell through to
  `protocol-miss`.

  Nothing caught it because every protocol test in this suite defined and
  extended in one file, where the two namespaces are the same one. It surfaced
  when `clojure.core`'s printer was moved onto a protocol so that `flint.table`
  could print a table without the printer knowing what a table is."
  (:require [flint.check :refer [expect]]
            [lang.protocols :as p]
            ;; `extend-method` MOVED OUT OF `clojure.core`, where the implicit
            ;; refer used to find it. Clojure has no such name, so a port that
            ;; publishes one there is publishing under somebody else's name --
            ;; `doc/manifest.edn` had it as that namespace's only `:extra`.
            ;; A direct caller names it here now.
            [flint.protocols :refer [extend-method]]))

(extend-protocol p/Describe
  :keyword (describe [k] (str "a keyword named " (name k)))
  :set (describe [s] (str "a set of " (count s))))

(defn ^:flint.check/test extends-across-namespaces []
  (expect = "a keyword named k" (p/describe :k))
  (expect = "a set of 2" (p/describe #{1 2})))

(defn ^:flint.check/test extend-merges-rather-than-replaces []
  ;; Two kinds extended from here, and both survive: `extend` merges into the
  ;; protocol's table. A replace would leave only the last one.
  (expect = "a keyword named k" (p/describe :k))
  (expect = "a set of 0" (p/describe #{}))
  ;; And the protocol the same namespace did NOT extend is untouched, which is
  ;; the other half of "the table is per protocol".
  (expect = "hello world" (p/greet "world")))

(defn ^:flint.check/test satisfies-sees-a-foreign-extension []
  ;; `satisfies?` reads the same table by the same keys, so it agreeing is what
  ;; proves the key is the protocol's own and not the extender's.
  (expect = true (satisfies? p/Describe :k))
  (expect = false (satisfies? p/Describe 'sym)))

(defn ^:flint.check/test a-method-that-is-not-the-protocols-is-refused []
  ;; The key is resolved against the protocol, so a name it does not have is a
  ;; named error at extend time rather than a miss at call time -- which is the
  ;; failure mode this whole file is about.
  (let [m (try (extend-method p/Describe :symbol "farewell" (fn [_] "bye"))
               (catch Exception e (ex-message e)))]
    (expect = true (some? (re-find #"has no method named farewell" m)))
    (expect = true (some? (re-find #"its methods are" m)))))
