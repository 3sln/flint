;; Opaque values (`doc/decisions/0022`).
;;
;; The logic under test is in `test/opaque.cljc`, which runs inside the module;
;; this drives it and checks the answers. Sending one through a port is checked
;; in `test/threads.clj`, where the rest of the sendability rules live.
(require '[clojure.string :as str] '[clojure.edn :as edn])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(println "opaque: identity without structure (0022)")
(let [b (sh "./bin/flint" ":src" "test" ":fn" "opaque/main" ":out" "out/opaque.wasm")]
  (when-not (zero? (:exit b))
    (println "build failed:" (:out b) (:err b)) (System/exit 1)))
(def r (let [x (sh "node" "host/flint.mjs" "out/opaque.wasm")]
         (when-not (zero? (:exit x))
           (println "  FAIL the module trapped:" (str/trim (:err x))) (System/exit 1))
         (edn/read-string (str/trim (:out x)))))

(check "equality is identity" [(:self-equal r) (:distinct r) (:identical r)] [true true true])
(check "the label plays no part in identity" (:label-is-not-identity r) true)
(check "opaque? is true only of one" (:is-opaque r) [true false false false])

;; The idiom the type exists for. flint has no `(Object.)`, so before this there
;; was no way to tell a key that is absent from one that is present and nil.
(check "absent is distinguishable from present-and-nil" (:absent-vs-nil r) [false true])

;; No read syntax, deliberately: a printed form that reads back is forgeable.
(check "printing shows the label and nothing else"
       (:printing r) ["#<opaque>" "#<opaque fs>" "#<opaque fs>"])

;; 0022 names this the single most likely thing to get wrong, because it fails
;; intermittently and under load. The nursery is a copying collector, so an
;; identity hash derived from the address would change when the object moved and
;; the key would stop finding its own entry.
(let [[cnt missing minors copied] (:survives-collection r)]
  (check "300 opaque keys are all still findable after a collection" [cnt missing] [300 0])
  ;; A zero from a heap that never moved anything is not evidence.
  (check-that "  ... and a collection really ran, and really moved things"
              (and (pos? minors) (> copied 20000))))

(let [[cnt missing bad-labels majors minors] (:survives-major r)]
  (check "400 survive a MAJOR collection, with their labels" [cnt missing bad-labels] [400 0 0])
  (check-that "  ... and a major really ran" (and (pos? majors) (pos? minors))))

(let [[cnt missing] (:survives-in-set r)]
  (check "and in a set, which hashes by the same path" [cnt missing] [200 0]))

;; A per-type constant hash would be CORRECT -- equality is identity, so
;; collisions only cost time -- but it would put every opaque value in one
;; bucket, which defeats the point of using one as a key.
(check "the stored hash is distinct per value" (:hashes-differ r) true)
(check "  ... and stable across a collection" (:hash-stable r) true)

(println (if (zero? @fails) "opaque: ok" (str "opaque: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
