#!/usr/bin/env bb
;; `bin/check-dist` must FAIL on a compiler built from another tree.
;;
;; Its slot check passed against a `dist/flintc.bytecode` from before the
;; `split` entry, and four gate sections went red as if the change were broken
;; (`bin/check-dist` has the measurement). A freshness check fails open: a
;; broken one prints the same `ok` as a working one. So it is run here against a
;; stamp that is wrong in exactly one way -- one character -- with a control
;; that is the tree's own digest, so a failure for an unrelated reason cannot
;; read as the check working.
(require '[babashka.process :as p] '[babashka.fs :as fs] '[clojure.string :as str])

(def fails (atom 0))
(defn check [label ok]
  (println (if ok "  ok  " "  FAIL") label)
  (when-not ok (swap! fails inc)))

(defn run [stamp-body]
  (let [f (str (fs/create-temp-file {:prefix "flint-stamp"}))]
    (when stamp-body (spit f stamp-body))
    (when-not stamp-body (fs/delete f))
    (try @(p/process ["bin/check-dist"] {:out :string :err :string
                                         :extra-env {"FLINT_DIST_STAMP" f}})
         (finally (fs/delete-if-exists f)))))

(let [digest (str/trim (:out @(p/process ["bin/check-dist" "--digest"] {:out :string})))
      other (str (if (= \0 (first digest)) \1 \0) (subs digest 1))]
  (check "the digest is a sha-256" (= 64 (count digest)))
  (check "CONTROL: the tree's own digest passes" (zero? (:exit (run (str digest "\n")))))
  (let [r (run (str other "\n"))]
    (check "a stamp from another tree fails" (= 1 (:exit r)))
    (check "... and says the compiler is the stale part"
           (str/includes? (:out r) "flintc.bytecode was built from a different src/")))
  (check "a missing stamp fails" (= 1 (:exit (run nil)))))

(println)
(if (zero? @fails)
  (println "check-dist: all green")
  (do (println "check-dist:" @fails "FAILED") (System/exit 1)))
