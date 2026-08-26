;; Tree shaking without a linker (`doc/decisions/0024`).
;;
;; The claim: a module that was linked ONCE, when flint was built, can be cut
;; down to what a particular program needs by a pass over its bytes -- no
;; `wasm-ld` at compile time. What makes that testable rather than plausible is
;; that the linker's own answer is available for the same program, so "how much
;; of the linker's result does this recover" is a number.
;;
;; The failure this file exists to catch is the dangerous one: a shake that
;; removes something reachable produces a module that is SMALLER and traps at
;; run time, and only running it says so.
(require '[babashka.classpath :as cp]) (cp/add-classpath "src")
(require '[flint.bundle :as bundle] '[flint.image :as img]
         '[flint.compiler :as compiler] '[flint.project :as project]
         '[flint.wasm :as w] '[flint.wasmshake :as ws] '[flint.shake :as shake]
         '[clojure.java.io :as io] '[clojure.edn :as edn] '[clojure.string :as str])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(println "shake: cutting a prebuilt module down, with no linker")

;; The mark phase is shared with every other target, so it is checked on a
;; graph rather than on wasm.
(let [g {:a [:b] :b [:c] :c [] :d [:a] :e [:e]}]
  (check "reachability follows edges" (set (shake/reachable [:a] g)) #{:a :b :c})
  (check "and stops at what nothing reaches" (set (shake/dead (keys g) [:a] g)) #{:d :e})
  (check "a cycle terminates" (set (shake/reachable [:e] g)) #{:e}))

(def slots (into {} (map (fn [[k v]] [(str k) v])
                         (edn/read-string (str/replace (slurp "dist/slots.json")
                                                       #"\"([^\"]+)\":" "\"$1\" ")))))
(def lib (into {} (for [f (file-seq (io/file "lib")) :when (.isFile f)
                        :let [p (subs (str f) 4)]] [p (slurp f)])))

(defn build-image [src entry]
  (let [all (merge lib src)
        find-src (fn [n] (let [b (project/ns->path n)]
                           (when-let [s (get all (str b ".cljc"))]
                             {:src s :file (str b ".cljc")})))
        {:keys [sources order]} (project/resolve-project find-src (symbol (namespace entry)) #{:flint})
        r (compiler/compile-image
           {:sources (into {} (map (fn [e] [(key e) {:src (:src (val e)) :file (:file (val e))}]) sources))
            :order (vec (filter sources order)) :entry entry
            :builtins (set (keys slots)) :features #{:flint}})]
    {:used (set (img/natives (:builder r))) :image (img/emit (:builder r) slots)}))

(def SRC "(ns hello (:require [clojure.string :as s]))\n(defn main [args] (s/upper-case (str \"shaken \" (reduce + 0 (range 10)) \" \" (count (filterv odd? (range 20))))))\n")
(def b (build-image {"hello.cljc" SRC} 'hello/main))
(def full (w/parse (java.nio.file.Files/readAllBytes (.toPath (io/file "dist/flint-runtime.wasm")))))
(def exp (w/exports full))
(def table (ws/table-entries full))

(check-that "the function table reads back" (> (count table) 100))
(check-that "the image names the builtins it imports" (> (count (:used b)) 10))

;; Roots: every entry point a host or the runtime uses, plus EXACTLY the table
;; slots this image imports. The second half is precision the linker could not
;; have had -- it was handed an export list before the program existed.
(def roots
  (into (into #{} (keep #(:index (get exp %))
                        (remove #(str/starts-with? % "flint_b_") (keys exp))))
        (keep #(get table %) (keep #(get slots %) (:used b)))))

(def shaken (ws/stub-dead full roots))
(def rep (second shaken))
(def module (w/emit (first shaken)))
(def spliced (bundle/into-module module (:image b) {:entry 'hello/main}))
(io/make-parents "out/x") 
(with-open [o (io/output-stream "out/shaken-hello.wasm")] (.write o spliced))

(println (format "    %d of %d functions kept, code %d -> %d (%.1f%% removed)"
                 (:kept (:functions rep)) (:total (:functions rep))
                 (:before (:bytes rep)) (:after (:bytes rep)) (* 100 (:share rep))))

(check-that "it removed something" (pos? (:removed (:functions rep))))
(check-that "and kept something" (pos? (:kept (:functions rep))))

;; The one that matters. A shake that cuts something reachable makes a SMALLER
;; module that traps, and only running it says so.
(let [r (sh "node" "host/flint.mjs" "out/shaken-hello.wasm")]
  (check "the shaken module still runs, and answers" (str/trim (:out r)) "SHAKEN 45 10"))

;; Against the linker's own answer for the same program.
(let [_ (io/make-parents "out/shake-src/hello.cljc")
      _ (spit "out/shake-src/hello.cljc" SRC)
      r (sh "./bin/flint" ":src" "out/shake-src" ":fn" "hello/main" ":out" "out/shake-linked.wasm")]
  (check-that "the linker builds the same program" (zero? (:exit r)))
  (let [linked (.length (io/file "out/shake-linked.wasm"))
        shook (count spliced)
        base (.length (io/file "dist/flint-runtime.wasm"))
        recovered (double (/ (- base shook) (- base linked)))]
    (println (format "    prebuilt %d, shaken %d, linked %d -- recovered %.0f%% of what lld removes"
                     base shook linked (* 100 recovered)))
    ;; Conservative by construction: the scan can invent an edge, never miss
    ;; one, so this recovers MOST of the linker's result and not all of it.
    ;; Both bounds are asserted, because a shake that suddenly recovered 100%
    ;; would mean the scan stopped being conservative.
    (check-that "it recovers most of what the linker removes" (> recovered 0.55))
    (check-that "and does not claim to beat the linker" (< recovered 1.0))))

(if (pos? @fails)
  (do (println "shake:" @fails "failed") (System/exit 1))
  (println "shake: ok"))
