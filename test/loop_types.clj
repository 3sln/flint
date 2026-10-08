;; Loop slots are typed per slot, floats are floats, and `(* a b c)` is typed.
;;
;; Three inference defects, found by probing the corpus (ROADMAP.md, "Loop
;; type inference"), each of which cost a specialisation while computing the
;; right answer -- so no answer-checking suite could see them:
;;
;; * ALL OR NOTHING. A loop slot's type is hypothesised from its initialiser
;;   and kept if every `recur` proves it. When one slot failed, every slot was
;;   withdrawn, so `(loop [z 0.0 i 0] ..)` lost the int counter `i` because the
;;   float `z` beside it was not proved.
;; * FLOATS WERE `:number`. `(* z z)` on floats had no float result type, so a
;;   `0.0` slot could never be proved to stay a float.
;; * VARIADIC ARITHMETIC WAS UNTYPED. `(* 2.0 zr zi)` went through `reduce`.
;;
;; Each row below is a function and what the emitter must do in it, counted by
;; `test/loop_types_probe.clj`. The CONTROLS are shapes that must NOT
;; specialise, so a probe that reported everything as specialised fails here.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.edn :as edn])

(def fails (atom 0))
(defn check [label ok]
  (if ok (println "  ok  " label) (do (println "  FAIL" label) (swap! fails inc))))

(def src "
(ns p)
(defn counter [] (loop [i 0 acc 0] (if (< i 1000) (recur (inc i) (+ acc i)) acc)))
(defn float-beside [] (loop [z 0.0 i 0] (if (< i 50) (recur (+ (* z z) 0.25) (inc i)) i)))
(defn untyped-beside [c] (loop [z 0.0 i 0] (if (< i 50) (recur (+ (* z z) c) (inc i)) i)))
(defn three-args [^long a ^long b ^long c] (* a b c))
(defn float-three [^double x ^double y] (loop [z 0.0 k 0] (if (< k 3) (recur (* 2.0 z x) (inc k)) (+ z y))))
(defn widens [] (loop [x 0 n 0] (if (< n 3) (recur (+ x 0.5) (inc n)) x)))
(defn untyped [a b] (+ a b))
(defn main [_]
  (str [(counter) (float-beside) (untyped-beside 0.1) (three-args 2 3 4)
        (float-three 1.5 1.0) (widens) (untyped 1 2) (max 3 2.0) (min 2.0 3)]))
")

(let [dir (str (fs/create-temp-dir))
      out (str dir "/counts.edn")]
  (spit (str dir "/p.cljc") src)
  (let [r @(p/process ["./bin/flint" ":src" dir ":fn" "p/main" ":out" (str dir "/p.wasm")]
                      {:out :string :err :string
                       :extra-env {"PROBE_OUT" out "FLINT_PRELOAD" "test/loop_types_probe.clj"}})
        data (try (edn/read-string (slurp out))
                  (catch Exception e
                    {:ran? false :error (str "PROBE_OUT unreadable: " e)}))
        ;; A STRUCTURED FAILURE IS CHECKED ONCE, NOT NINE TIMES. The probe's
        ;; shutdown hook used to write `(pr-str @counts)` alone, so a probe
        ;; that never got to watch a compile (hooks failed to install, or any
        ;; later uncaught exception in that JVM) left `counts` at `{}` --
        ;; indistinguishable from a program with no integer-opcode arithmetic
        ;; at all. Every row below then read its own missing counts as a
        ;; regression in loop-type inference and failed, nine symptoms of one
        ;; cause. `test/loop_types_probe.clj` now reports `{:ran? :error
        ;; :counts}` explicitly, so a probe failure is one loud, named check
        ;; here and the per-row checks are skipped rather than restating it.
        probe-ran? (and (map? data) (true? (:ran? data)) (nil? (:error data)))
        c (if probe-ran? (:counts data) {})
        n (fn [f op outcome] (get c [f op outcome] 0))
        missed (fn [f] (reduce + (for [[[g _ o] k] c :when (and (= g f) (not= o :specialised))] k)))]
    (check "the probe compiled the program" (zero? (:exit r)))
    (check (str "the probe actually ran and watched the compile"
                (when-not probe-ran?
                  (str " -- PROBE DID NOT RUN: " (or (:error data) data) " / stderr: " (:err r))))
           probe-ran?)
    (when probe-ran?
      (check "counter: both adds and the compare specialise, none missed"
             (and (= 2 (n "counter" "flint/add" :specialised))
                  (= 1 (n "counter" "flint/lt" :specialised))
                  (zero? (missed "counter"))))
      (check "float-beside: the int counter survives a float slot beside it"
             (and (= 1 (n "float-beside" "flint/add" :specialised))
                  (= 1 (n "float-beside" "flint/lt" :specialised))))
      (check "float-beside: the float slot is proved a float, so (* z z) is [float float]"
             (= 1 (n "float-beside" "flint/mul" [:float :float])))
      (check "untyped-beside: the counter survives a slot an untyped parameter spoils"
             (= 1 (n "untyped-beside" "flint/lt" :specialised)))
      (check "untyped-beside, CONTROL: that slot is NOT claimed, so (* z z) stays untyped"
             (= 1 (n "untyped-beside" "flint/mul" [nil nil])))
      (check "three-args: (* a b c) is two typed multiplies"
             (= 2 (n "three-args" "flint/mul" :specialised)))
      (check "float-three: (* 2.0 z x) keeps z a float through the fold"
             (= 2 (n "float-three" "flint/mul" [:float :float])))
      (check "widens, CONTROL: a slot that starts int and recurs a float is not an int"
             (pos? (n "widens" "flint/add" [nil :float])))
      (check "untyped, CONTROL: unknown operands do not specialise"
             (= 1 (n "untyped" "flint/add" [nil nil])))))
  ;; The ANSWER, through the real CLI, since everything above is about code
  ;; that must compute the same thing whether or not it specialised.
  (let [r @(p/process ["target/release/flint" "run" ":path" dir ":fn" "p/main"] {:out :string :err :string})]
    (check (str "the answers are unchanged: " (:out r) (:err r))
           (= "[499500 50 50 24 1.0 1.5 3 3 2.0]" (:out r)))))

(println)
(if (zero? @fails) (println "loop types: all green") (do (println "loop types:" @fails "FAILED") (System/exit 1)))
