;; What a module says about itself (`doc/decisions/0020`, part 1).
;;
;; A runner handed a pre-built `.wasm` needs to decide whether it can load it at
;; all, and how to build its glue. 0018 made that concrete: five engines now
;; pick up the same bytes, and `flint run` has to tell a module from an image.
(require '[clojure.string :as str] '[clojure.edn :as edn] '[babashka.fs :as fs])
(babashka.classpath/add-classpath "src")
(require '[flint.modmeta :as mm] '[flint.wasm :as w])

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
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(println "modmeta: what a module says about itself (0020)")

(def d (str (fs/create-temp-dir)))
(spit (str d "/m.cljc") "(ns m)\n(defn main [_] \"ok\")\n")
(doseq [[o extra] [["out/mm.wasm" []] ["out/mm-loader.wasm" ["--loader"]]]]
  (let [r (apply sh (concat ["./bin/flint" ":src" d ":fn" "m/main" ":out" o] extra))]
    (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1))))

;; Read from the BYTES. Not `WebAssembly.compile`, not an export: a runner has
;; to decide whether to instantiate, and that cannot depend on having done it.
(defn meta-of [f]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath (clojure.java.io/file f)))
        sec (w/custom-section (w/parse bytes) mm/section-name)]
    {:meta (when sec (edn/read-string (String. ^bytes sec "UTF-8")))
     :size (alength bytes)
     :offset (loop [i 8]
               (if (>= i (alength bytes))
                 nil
                 (let [id (aget bytes i)
                       [size i2] (#'w/rd-uleb bytes (inc i))]
                   (if (zero? id) i (recur (+ i2 size))))))}))

(def prod (meta-of "out/mm.wasm"))

(check-that "a module carries a metadata section, readable without instantiating"
            (some? (:meta prod)))
(check "  ... in the format this reader understands" (:flint/module (:meta prod)) 1)
(check "  ... naming the entry point" (:entry (:meta prod)) "m/main")
;; Early, so a streaming runner has it before the code section rather than after
;; a megabyte of body.
(check-that "  ... early in the byte stream, under 1% in"
            (< (/ (:offset prod) (double (:size prod))) 0.01))

;; The descriptors are derived from the ARTIFACT, so they cannot drift from it.
(check-that "the export list matches the module's real exports"
            (= (set (:exports (:meta prod)))
               (set (remove #(str/starts-with? % "flint_b_")
                            (keys (w/exports (w/parse (java.nio.file.Files/readAllBytes
                                                       (.toPath (clojure.java.io/file "out/mm.wasm")))))))))) 
(check-that "a program module declares no imports, which is why it runs anywhere"
            (empty? (:imports (:meta prod))))

;; Features are capability DESCRIPTORS: what a tool may ask for.
(def loader (meta-of "out/mm-loader.wasm"))
(check "a loader build says it can load images"
       [(get-in prod [:meta :features :loader]) (get-in loader [:meta :features :loader])]
       [false true])
;; ... and they do NOT gate compatibility. A loader is still the same ABI.
(check-that "  ... and that does not change the compatibility key"
            (mm/compatible? (:meta prod) (:meta loader)))

;; 0020's stated trap, and the case that proves the key is drawn correctly.
;; `0016` ships two builds differing only in diagnostics. If turning diagnostics
;; on invalidated shards, the compatibility key would be over the wrong subset.
(println "  (building the DIAGNOSTICS runtime to check 0016's two builds agree)")
(when-not (zero? (:exit (sh "./bin/build-units" "--diagnostics")))
  (println "  FAIL could not build the diagnostics units") (System/exit 1))
(let [r (sh "./bin/flint" ":src" d ":fn" "m/main" ":out" "out/mm-diag.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1)))
(def diag (meta-of "out/mm-diag.wasm"))
(sh "./bin/build-units")

(check "the diagnostics build says diagnostics are present"
       [(get-in prod [:meta :features :diagnostics]) (get-in diag [:meta :features :diagnostics])]
       [false true])
(check-that "  ... and the two builds of 0016 remain compatible"
            (mm/compatible? (:meta prod) (:meta diag)))
(check "  ... because the key is over the ABI subset, which is identical"
       (get-in prod [:meta :compat :abi]) (get-in diag [:meta :compat :abi]))

;; A mismatch is refused BY NAME, not by an opaque hash comparison. A hash alone
;; tells a reader nothing they can act on, which is why the version and the ABI
;; ride alongside it.
(let [other (assoc-in (:meta prod) [:compat :abi :runtime] 99)
      other (assoc-in other [:compat :key] (mm/compat-key other))]
  (check-that "an incompatible module is refused" (not (mm/compatible? (:meta prod) other)))
  (check-that "  ... with a reason a reader can act on"
              (str/includes? (mm/why-not other (:meta prod)) "different runtime ABI")))

;; The hash exists to catch what a hand-bumped version forgets.
(let [drifted (assoc-in (:meta prod) [:compat :memory] :shared)
      drifted (assoc-in drifted [:compat :key] (mm/compat-key drifted))]
  (check-that "a memory-model change is caught even at the same version"
              (not (mm/compatible? (:meta prod) drifted)))
  (check-that "  ... and named" (str/includes? (mm/why-not drifted (:meta prod)) "shared")))

;; The key must be reproducible across hosts, or a self-hosted build would
;; refuse a bb-built one.
(check "the key is a pure function of the compat subset, not of map order"
       (mm/compat-key {:compat (into (sorted-map) (get-in prod [:meta :compat]))})
       (mm/compat-key {:compat (get-in prod [:meta :compat])}))

(println (if (zero? @fails) "modmeta: ok" (str "modmeta: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
