;; Collections under collection pressure, with compound keys. See
;; test/gcstress.cljc for what this is guarding against.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[clojure.edn :as edn])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(println "gc stress: compound keys survive collection")
(let [b (sh "./bin/flint" ":src" "test" ":fn" "gcstress/main" ":out" "out/gcstress.wasm")]
  (when-not (zero? (:exit b))
    (println "build failed:" (:out b) (:err b)) (System/exit 1)))

(let [r (sh "node" "host/flint.mjs" "out/gcstress.wasm")]
  (when-not (zero? (:exit r))
    (println "  FAIL  the module trapped:" (str/trim (:err r))) (System/exit 1))
  (let [res (edn/read-string (str/trim (:out r)))
        fails (atom 0)
        check (fn [label ok] (if ok (println "  ok  " label)
                                 (do (swap! fails inc) (println "  FAIL" label))))]
    (doseq [k [:vec :mixed :deep :list :set :transient]]
      (let [[cnt missing] (get res k)]
        (check (format "%-10s %d entries, none unfindable" (name k) cnt)
               (and (pos? cnt) (zero? missing)))))
    (check "= with three compound arguments" (:eq3 res))
    (check "sort with compound elements" (:sorted res))
    (if (zero? @fails)
      (println "gc stress: ok")
      (do (println "gc stress:" @fails "FAILURES") (System/exit 1)))))
