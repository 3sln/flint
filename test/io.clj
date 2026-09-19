;; The four IO protocols (`DECISIONS.md#flint-ception`).
;;
;; The logic under test is `test/io.cljc`, which runs inside a module: these
;; are protocols a compiled program implements and dispatches on, so checking
;; them from babashka would check a different thing.
(require '[clojure.string :as str])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        ;; STDERR IS DRAINED ON ITS OWN THREAD, and that is not a style
        ;; choice. Reading stdout to completion first and stderr after
        ;; DEADLOCKS the moment the child writes more than a pipe buffer to
        ;; stderr: the child blocks writing, this blocks reading, and neither
        ;; moves again. Measured 2026-09-15 -- `bin/build-units --diagnostics`
        ;; emits 71 266 bytes of cargo warnings against a 64 KB buffer, and
        ;; `bin/test` sat in `ropes` for 45 minutes looking merely slow.
        err (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p))
        err @err]
    (.waitFor p) {:exit (.exitValue p) :out (str out err)}))

(println "io: sources and sinks, as protocols rather than capabilities")

(def flint "./target/release/flint")
(when-not (.exists (java.io.File. flint))
  (println "  (skipped: no target/release/flint -- `cargo build --release -p flint-cli`)")
  (System/exit 0))

(let [r (sh flint "run" ":path" "test" ":fn" "io/main")]
  (println (:out r))
  (let [fails (count (re-seq #"(?m)^  FAIL" (:out r)))]
    (cond
      (not (zero? (:exit r)))
      (do (println "io:" "the program did not run") (System/exit 1))

      (pos? fails)
      (do (println "io:" fails "FAILURES") (System/exit 1))

      ;; A run that printed nothing would pass the two tests above, so the row
      ;; count is asserted rather than assumed -- the failure this catches is a
      ;; test file that silently stopped running.
      (< (count (re-seq #"(?m)^  ok" (:out r))) 13)
      (do (println "io: fewer checks ran than there are rows") (System/exit 1))

      :else (println "io: ok"))))
