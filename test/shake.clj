;; Tree shaking without a linker (`DECISIONS.md#no-runtime-linking`).
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
        ;; STDERR IS DRAINED ON ITS OWN THREAD (`DECISIONS.md#the-codec-is-guest-code`,
        ;; "the test helper deadlocked"). Reading stdout to completion and
        ;; stderr after DEADLOCKS the moment a child writes more than a pipe
        ;; buffer to stderr: the child blocks writing, this blocks reading, and
        ;; neither moves again.
        err (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p))
        err @err]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(println "shake: cutting a prebuilt module down, with no linker")

;; The mark phase is shared with every other target, so it is checked on a
;; graph rather than on wasm.
(let [g {:a [:b] :b [:c] :c [] :d [:a] :e [:e]}]
  (check "reachability follows edges" (set (shake/reachable [:a] g)) #{:a :b :c})
  (check "and stops at what nothing reaches" (set (shake/dead (keys g) [:a] g)) #{:d :e})
  (check "a cycle terminates" (set (shake/reachable [:e] g)) #{:e}))

;; THE SCAN ITSELF, on bytes chosen to break it.
;;
;; `scan-calls` is a byte scan rather than an instruction decoder: every `0x10`
;; is taken for a call. Over-approximating is safe -- an immediate that happens
;; to contain `0x10` keeps a function nothing calls. Skipping the operand is
;; NOT: a `0x10` inside an immediate has no operand after it, so continuing
;; past one steps over whatever is really there, and a real call in those bytes
;; is never seen. The function it names is then stubbed with `unreachable`, and
;; the failure is a trap in a correct program at a call site the shaker decided
;; could not happen.
;;
;; The body below is exactly that shape: an `i64.const` whose LEB bytes contain
;; `0x10`, immediately followed by a genuine `call 7`. A scanner that skips
;; lands past the call and reports nothing.
;;
;; Found the hard way. A lock-free ring in `Rt::new_port` shifted the runtime's
;; bytes, one immediate landed on `0x10`, and the skip jumped a call reached
;; only when a program OPENS A BRIDGE -- so channels worked, every unshaken
;; path worked, and it looked like a port bug for an hour.
(let [;; i64.const with a multi-byte LEB containing 0x10, then `call 7`.
      body (byte-array (map unchecked-byte [0x42 0x90 0x10 0x10 0x07 0x0b]))
      found (ws/scan-calls body 64 {:start 0 :end (count body)})]
  (check-that "a call after a 0x10 inside an immediate is still found"
              (contains? found 7)))

;; And the over-approximation is deliberate, so it is stated rather than
;; discovered: the byte INSIDE the immediate is read as a call too. Keeping a
;; function nothing calls costs bytes; missing one costs a trap.
(let [body (byte-array (map unchecked-byte [0x42 0x90 0x10 0x10 0x07 0x0b]))
      found (ws/scan-calls body 64 {:start 0 :end (count body)})]
  (check-that "  ... and the false positive it implies is accepted"
              (contains? found 0x10)))

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

;; Roots: every entry point a host or the runtime uses, the LINKER's own
;; function pointers, and exactly the table slots this image imports. The last
;; is precision the linker could not have had -- it was handed an export list
;; before the program existed.
;;
;; THE LINKER'S POINTERS ARE ROOTS TOO, and this file used to leave them out.
;; Below `slots` the table holds what Rust compiled a closure or a trait object
;; to, and nothing here can tell which are reachable. `flint.selfhost` learned
;; that when stubbing them "stubbed the scheduler's own callbacks, and every
;; program using ports trapped inside `conc::scheduler`" -- and this file kept
;; the narrower rule, which was harmless only while a call did not use a port.
;; It does now (`DECISIONS.md#calls-are-ports`), so this shake stubbed the
;; scheduler under its own feet and the module answered `unreachable`.
(def builtin-slots (set (vals slots)))
(def roots
  (into (into (into #{} (keep #(:index (get exp %))
                              (remove #(str/starts-with? % "flint_b_") (keys exp))))
              (keep (fn [e] (when-not (contains? builtin-slots (key e)) (val e))) table))
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
(let [r (sh "node" "host/flint.mjs" "out/shaken-hello.wasm" "hello/main")]
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
    ;; one, so this recovers MUCH of the linker's result and not all of it.
    ;; Both bounds are asserted, because a shake that suddenly recovered 100%
    ;; would mean the scan stopped being conservative.
    ;;
    ;; RE-BASELINED 2026-08-30, from 0.55, and the reason is a trade rather
    ;; than drift. The comment above has always said the scan "can invent an
    ;; edge, never miss one" -- and the implementation did not match it. It
    ;; continued past the operand of every byte that looked like a call, so a
    ;; `0x10` inside an immediate made it step over the bytes after it, and a
    ;; real call in those bytes was missed. That is the unsafe direction: the
    ;; function is stubbed with `unreachable` and a correct program traps.
    ;;
    ;; It found one. A lock-free ring in `Rt::new_port` shifted the runtime's
    ;; bytes and the skip jumped a call reached only when a program opens a
    ;; bridge.
    ;;
    ;; Advancing one byte at a time makes the stated invariant true, and costs
    ;; about 34 KB on this program -- 55% recovered against 46%. Soundness for
    ;; 7% of a module is the right way round when the alternative is a silent
    ;; trap. A real instruction decoder would recover both, and would be exact
    ;; rather than conservative; it is not written because a decoder with one
    ;; wrong immediate width desynchronises and starts MISSING calls again,
    ;; which is the failure this just cost an hour to find.
    ;; DOWN FROM 55% TO 44% for the single entry point
    ;; (`DECISIONS.md#calls-are-ports`). The shaker now roots the linker's own
    ;; function pointers -- Rust's closures and trait objects, below `slots` --
    ;; because a call uses a port and a stubbed scheduler callback traps under
    ;; its own feet. That is the same lesson `flint.selfhost` already carried
    ;; and this file did not, which is why the row above went `unreachable`
    ;; before it went smaller.
    ;;
    ;; Kept conservative on purpose: a shake that cuts something reachable makes
    ;; a SMALLER module that traps, and only running it says so. 11% of a module
    ;; is the right price for that, the same way round as the byte-at-a-time
    ;; scan above.
    ;; DOWN AGAIN, from 44% to 37%, for the wire writer
    ;; (`DECISIONS.md#the-codec-is-guest-code`). Sixteen `wire-*` primitives
    ;; joined `flint-conc`, and with them the transient-byte machinery they
    ;; reach -- which this program never calls.
    ;;
    ;; THE SHAKE DID NOT GET WORSE; the module got bigger. Measured either side:
    ;; lld removes about the same (330 068 -> 332 324) and the shake removes
    ;; 53 KB LESS (177 243 -> 124 538), which is the new code being kept rather
    ;; than old code being missed.
    ;;
    ;; Kept because a BUILTIN TABLE is a data array of function pointers, and
    ;; the conservative scan above treats an address it finds as a call. So the
    ;; cost is per builtin ADDED to a unit, not per builtin USED by a program --
    ;; a property worth knowing before adding sixteen of anything.
    ;;
    ;; REVISIT WHEN THE FLINT ENCODER LANDS. These primitives are dead weight
    ;; today and become live the moment `port/send` encodes through them, at
    ;; which point this ratio is measuring something else and should be read
    ;; again rather than lowered again.
    (check-that "it recovers much of what the linker removes" (> recovered 0.35))
    (check-that "and does not claim to beat the linker" (< recovered 1.0))))

(if (pos? @fails)
  (do (println "shake:" @fails "failed") (System/exit 1))
  (println "shake: ok"))
