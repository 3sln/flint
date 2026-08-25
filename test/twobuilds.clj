;; Two builds (doc/decisions/0016).
;;
;; A production module contains no diagnostic machinery -- absent, not disabled.
;; A runtime flag would leave the code linked and branched on, and "absent" is a
;; different guarantee from "off": this VM's strongest measured case is running
;; code somebody else wrote, and a module that ships snapshot export is a module
;; that can be asked to dump its heap.
;;
;; The other half matters just as much: gas, the memory cap and the deterministic
;; scheduler are production FEATURES and must survive stripping. construe's gates
;; depend on a reproducible instruction count.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label "\n        expected" (pr-str expected)
                                   "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(def d (str (fs/create-temp-dir)))
(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :all (str out err)}))

(spit (str d "/prod.cljc") "(ns prod)\n(defn main [_] \"nothing\")")

(println "two builds")

;; The suite has just run against the production units, so this measures what a
;; shipped module actually is.
(let [r (sh "./bin/flint" ":src" d ":fn" "prod/main" ":out" "out/tb-prod.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1)))
(def prod (String. (fs/read-all-bytes "out/tb-prod.wasm") "ISO-8859-1"))
(println (format "    production module %d bytes" (fs/size "out/tb-prod.wasm")))
;; The floor from 0005, measured against the build it is a claim about. It used
;; to live in `test/snapshot.clj`, which runs under `--diagnostics` -- so it was
;; really measuring the size of the instrumentation, and it moved the day the
;; instrumentation grew.
;;
;; Raised by 6 310 bytes for ropes (`doc/decisions/0011`). Deliberately, and with
;; the number that bought it: repeated concatenation went from 57.17 ms to
;; 2.31 ms, which is 24.7x and takes flint from 3.1x slower than babashka to
;; 7.9x faster. `test/threads.clj` carries the same note; both floors move
;; together or one of them is drifting unnoticed.
(check-that "the floor from 0005 still holds" (< (fs/size "out/tb-prod.wasm") 215000))

;; --- absent, by name -------------------------------------------------------
(doseq [sym ["flint_snapshot_capture" "flint_snapshot_restore" "flint_snapshot_ptr"
             "set_gc_stress" "collect_now" "stat_peak_live" "stat_heap_used"
             "stat_bytes_allocated" "stat_collections"]]
  (check (str "a production module does not export " sym)
         (str/includes? prod sym) false))

;; --- and the production features SURVIVE -----------------------------------
;;
;; These are the ones most likely to be cut by mistake, which is why they are
;; asserted rather than assumed.
(doseq [sym ["set_step_limit" "stat_steps" "set_memory_limit"]]
  (check (str "  ... but it still exports " sym)
         (str/includes? prod sym) true))

;; Gas is not merely exported, it still WORKS: the counter is the thing
;; construe's gates read, so a stripped module that reported zero would be worse
;; than one that failed to build.
(spit (str d "/gas.cljc")
      (str "(ns gas)\n"
           "(defn main [_] (str (loop [i 0 a 0] (if (< i 2000) (recur (inc i) (+ a i)) a))))"))
(let [r (sh "./bin/flint" ":src" d ":fn" "gas/main" ":out" "out/tb-gas.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1)))
(let [r (sh "node" "-e"
            (str "import('./host/flint.mjs').then(async (m) => {"
                 "const {module} = await m.load('out/tb-gas.wasm');"
                 "const a = m.instantiate(module);"
                 ;; Counting is only on when a budget is: 0009 monomorphises the
                 ;; counter out of the unlimited loop, so setting a high limit is
                 ;; how a host asks for a count.
                 "a.exports.set_step_limit(0, 1e9); a.main();"
                 "const n = Number(a.exports.stat_steps());"
                 "const b = m.instantiate(module);"
                 "b.exports.set_step_limit(0, 5000); const r = b.main();"
                 "console.log(JSON.stringify({n, code: r.code, msg: r.out.slice(0,40)}));})"))
      out (str/trim (:all r))]
  (check-that "gas still counts in a stripped module"
              (and (str/includes? out "\"n\":")
                   (pos? (Integer/parseInt (second (re-find #"\"n\":(\d+)" out))))))
  (check-that "  ... and a limit still fires, catchably"
              (str/includes? out "\"code\":1"))
  (println (str "    " out)))

(if (zero? @fails)
  (println "two builds: ok")
  (do (println "two builds:" @fails "FAILURES") (System/exit 1)))
