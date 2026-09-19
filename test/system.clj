;; The sandbox control plane (`DECISIONS.md#bridges-are-the-only-door`).
;;
;; The logic under test is `test/system.cljc`, which runs inside a module:
;; these are green threads parked on ports, and checking them from babashka
;; would check a different thing.
(require '[clojure.string :as str])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        ;; STDERR IS DRAINED ON ITS OWN THREAD (`DECISIONS.md#the-codec-is-guest-code`,
        ;; "the test helper deadlocked"). Reading stdout to completion and
        ;; stderr after DEADLOCKS the moment a child writes more than a pipe
        ;; buffer to stderr: the child blocks writing, this blocks reading, and
        ;; neither moves again.
        err (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p))
        err @err]
    (.waitFor p) {:exit (.exitValue p) :out (str out err)}))

(println "system: bind, call, unbind, close -- the only door into a sandbox")

(def flint "./target/release/flint")
(when-not (.exists (java.io.File. flint))
  (println "  (skipped: no target/release/flint)")
  (System/exit 0))

;; `:vars` because the call loop resolves a function by name
;; (`DECISIONS.md#vars-is-its-own-grant`). The stdlib holds it; a test project
;; naming `flint.system` needs it too.
(def proj (str (babashka.fs/create-temp-dir)))
(spit (str proj "/deps.edn")
      "{:flint/workspace test/system\n :flint/capabilities-grant [:vars]}\n")
(babashka.fs/copy "test/system.cljc" (str proj "/system.cljc"))

(let [r (sh flint "run" ":path" proj ":fn" "system/main")
      rows (->> (str/split-lines (:out r)) (remove str/blank?))]
  (doseq [row rows] (println "  " (if (str/includes? row "FAIL") "FAIL" "ok  ") row))
  (cond
    (not (zero? (:exit r)))
    (do (println "system: the program did not run\n" (:out r)) (System/exit 1))

    (some #(str/includes? % "FAIL") rows)
    (do (println "system:" (count (filter #(str/includes? % "FAIL") rows)) "FAILURES")
        (System/exit 1))

    ;; A run that printed nothing would pass both tests above, so the row count
    ;; is asserted rather than assumed.
    (< (count rows) 6)
    (do (println "system: only" (count rows) "of 6 rows came back") (System/exit 1))

    :else (println "system: ok")))
