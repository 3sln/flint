;; A NAMESPACE CYCLE IS REFUSED, AND THE MESSAGE NAMES THE LOOP.
;;
;; It used to be tolerated. `topo-order` picked one of the pending namespaces
;; and carried on, justified by "Clojure allows mutual reference through vars
;; and refusing here would refuse programs that work". That is true WITHIN a
;; namespace -- which is what `declare` is for -- and false ACROSS them:
;; Clojure refuses a namespace-level require cycle outright and names it,
;;
;;     Cyclic load dependency: [ /aa ]->/bb->[ /aa ]
;;
;; so carrying on was not matching Clojure, it was diverging from it.
;;
;; WHAT IT COST, which is why this file exists rather than a comment. Giving
;; `flint.protocols` a `(:require [clojure.core])` while `clojure.core`
;; required it back produced no error and no hang. It produced an EMPTY
;; PROTOCOL MAP, which surfaced four hundred lines away as
;;
;;     the protocol  has no method named print-data; its methods are []
;;
;; A silent cycle does not stay silent. It re-emerges somewhere else with no
;; information attached, and the reader pays for the tolerance twice.
;;
;; The third case is the one that makes this a test and not an assertion: a
;; DAG must still build. A check that refuses cycles by refusing everything
;; would pass the first two and be worthless.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check [label ok?]
  (if ok?
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label))))

(defn build [files fn-name]
  (let [d (fs/create-temp-dir)]
    (doseq [[n src] files] (spit (str d "/" n) src))
    (let [p (.start (ProcessBuilder. (into-array String ["./bin/flint" ":src" (str d)
                                                         ":fn" fn-name
                                                         ":out" (str d "/out.wasm")])))
          out (slurp (.getInputStream p))
          err (slurp (.getErrorStream p))]
      {:exit (.waitFor p) :text (str out err)})))

(println "\ncycles: a namespace loop is refused, and named\n")

;; 1. TWO NAMESPACES. The smallest cycle there is.
(let [r (build {"a.cljc" "(ns a (:require [b]))\n(defn main [_] (str (b/g)))"
                "b.cljc" "(ns b (:require [a]))\n(defn g [] 1)"}
               "a/main")]
  (check "1. a two-namespace cycle is refused" (not= 0 (:exit r)))
  (check "1. and the message says it is a cycle"
         (str/includes? (:text r) "cyclic namespace dependency"))
  ;; NAMING THE LOOP is the whole point. "there is a cycle" sends a reader to
  ;; find it by hand; `a -> b -> a` is the answer.
  (check "1. and names both namespaces in the loop"
         (and (str/includes? (:text r) "a") (str/includes? (:text r) "b")
              (str/includes? (:text r) "->"))))

;; 2. THREE NAMESPACES, none of which requires itself. A check that only
;; noticed self-requires or two-cycles would pass case 1 and miss this.
(let [r (build {"p.cljc" "(ns p (:require [q]))\n(defn main [_] (str (q/g)))"
                "q.cljc" "(ns q (:require [r]))\n(defn g [] (r/h))"
                "r.cljc" "(ns r (:require [p]))\n(defn h [] 1)"}
               "p/main")]
  (check "2. a three-namespace cycle is refused" (not= 0 (:exit r)))
  (check "2. and the loop it names is three long"
         (>= (count (re-seq #"->" (:text r))) 2)))

;; 3. AND A DAG STILL BUILDS. Without this, refusing everything would pass.
(let [r (build {"x.cljc" "(ns x (:require [y]))\n(defn main [_] (str (y/g)))"
                "y.cljc" "(ns y)\n(defn g [] 1)"}
               "x/main")]
  (check "3. a DAG is untouched -- it still builds" (= 0 (:exit r)))
  (check "3. and says nothing about cycles"
         (not (str/includes? (:text r) "cyclic"))))

(println)
(if (zero? @fails)
  (println "cycles: refused, named, and a DAG still builds\n")
  (do (println (format "cycles: %d FAILURE(S)\n" @fails)) (System/exit 1)))
