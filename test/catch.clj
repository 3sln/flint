;; `catch` clause matching (`runtime/src/err.rs`).
;;
;; flint has no class hierarchy: an exception carries a kind STRING. A catch
;; used to compare that string for equality, which meant the commonest form in
;; real Clojure -- `(catch Exception e ...)` -- matched NOTHING, because every
;; kind flint raises is `ExceptionInfo`, `ClassCastException`, and so on. A
;; ported program's error handling silently did not run, and every catch case in
;; the conformance file used `Throwable`, so nothing noticed.
;;
;; The shared conformance file cannot carry the bare-name cases, because
;; `ExceptionInfo` does not resolve in real Clojure. They live here.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(def d (str (fs/create-temp-dir)))
(spit (str d "/c.cljc")
      (str "(ns c)\n"
           "(defn main [_]\n"
           "  (pr-str\n"
           "   [(try (throw (ex-info \"b\" {})) (catch ExceptionInfo e :exinfo))\n"
           "    (try (/ 1 0) (catch ArithmeticException e :arith))\n"
           "    (try (nth [1] 9) (catch Exception e :broad))\n"
           "    (try (throw (ex-info \"b\" {})) (catch ClassCastException e :wrong)\n"
           "                                   (catch ExceptionInfo e :specific))\n"
           ;; The distinction Java draws and the one a program catching broadly
           ;; still wants: a stack overflow should not be swallowed by a
           ;; `catch Exception` wrapped around a parser.
           ;; NOT a tail call: `(inc (f ...))` keeps a frame, so this reaches
           ;; MAX_FRAMES and raises. `(f (inc n))` would be a tail call, which is
           ;; constant-space and simply never returns.
           "    (try (try ((fn f [n] (inc (f (inc n)))) 0)\n"
           "              (catch Exception e :swallowed))\n"
           "         (catch Throwable e :error-escaped-Exception))]))\n"))

(println "catch: Exception means what it means in Clojure")
(let [r (sh "./bin/flint" ":src" d ":fn" "c/main" ":out" "out/catch.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1)))
(let [got (str/trim (:out (sh "node" "host/flint.mjs" "out/catch.wasm")))]
  (println (str "    " got))
  (check "the bare kind names match, and the specific clause wins"
         got "[:exinfo :arith :broad :specific :error-escaped-Exception]"))

(println (if (zero? @fails) "catch: ok" (str "catch: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
