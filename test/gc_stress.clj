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

;; --- the generational invariant, as a standing assertion --------------------
;;
;; **Every old object pointing at a young one must be in the remembered set.**
;; That is not a question about any particular bug; it is the invariant a
;; generational collector rests on. Violating it means a young object is never
;; traced, dies, and leaves a stale pointer in something still live -- silent,
;; and it surfaces somewhere else entirely. A dozen sessions of one such bug is
;; what this assertion exists to prevent a repeat of.
;;
;; It is read-only and allocates nothing, so unlike a snapshot it cannot perturb
;; the run it is checking. Production carries none of it (doc/decisions/0016).
(println "gc: the generational invariant holds")
(let [r (sh "node" "-e"
            (str "import('./host/flint.mjs').then(async (m) => {"
                 "const {module} = await m.load('out/gcstress.wasm');"
                 "const i = m.instantiate(module);"
                 "i.exports.set_gc_verify_remset(1);"
                 "i.main();"
                 "console.log(JSON.stringify({start: i.exports.stat_remset_violations(),"
                 " end: i.exports.stat_remset_end_violations(),"
                 " collections: Number(i.exports.stat_collections())}));})"))
      out (str/trim (str (:out r) (:err r)))
      ]
  (println (str "    " out))
  (if (and (str/includes? out "\"start\":0") (str/includes? out "\"end\":0"))
    (println "  ok   no old object points at a young one without being remembered")
    (do (println "  FAIL the generational invariant was violated") (System/exit 1))))
