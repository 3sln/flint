;; VM snapshots (DECISIONS.md#snapshots).
;;
;; The point of a snapshot is that it is a COPY, not a question: every ad-hoc
;; probe answers one thing and can answer it confidently wrong, while a snapshot
;; is raw state you interpret afterwards and re-interpret when the question
;; changes. So the assertions here are about the capture being complete and the
;; inspector being able to answer the questions the instruments could not.
(require '[clojure.string :as str] '[babashka.fs :as fs])

;; SAY WHICH BUILD IS MISSING, rather than failing on whatever it reaches
;; first. This file needs a DIAGNOSTICS build of the units, which `bin/test`
;; makes before running it. Run by hand against production units it used to
;; die with `no such builtin: flint.rt/snapshot` -- which names a symptom, not a
;; cause, and five suites were once reported RED against a tree where every
;; one of them passed.
;;
;; `bin/build-units` records which build it made (`units/.build-mode`), so
;; this is one question rather than an inference. Absence is not failure: a
;; tree that has never built units has no stamp, which is not the same as
;; having the wrong one.
(when (= "production" (when (fs/exists? "units/.build-mode")
                        (str/trim (slurp "units/.build-mode"))))
  (println "this test needs a DIAGNOSTICS build of the units.")
  (println "  run ./bin/test-diagnostics, which builds them and puts the")
  (println "  production build back afterwards (which is what bin/test does).")
  (System/exit 1))

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
        ;; STDERR IS DRAINED ON ITS OWN THREAD (`DECISIONS.md#the-codec-is-guest-code`,
        ;; "the test helper deadlocked"). Reading stdout to completion and
        ;; stderr after DEADLOCKS the moment a child writes more than a pipe
        ;; buffer to stderr: the child blocks writing, this blocks reading, and
        ;; neither moves again.
        err (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p))
        err @err]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))
(defn build! [ns-name out]
  (let [r (sh "./bin/flint" ":src" d ":fn" (str ns-name "/main") ":out" out)]
    (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1))
    out))
(defn src! [name body] (spit (str d "/" name ".cljc") body))

(println "snapshots")

;; ------------------------------------------------------------- module size
;;
;; `threads-and-ports`'s rule covers ONLY the DIAGNOSTICS-ONLY VERBATIM MEMCPY
;; CAPTURE below (`flint.snapshot`, `flint_b_snapshot`): that surface is still
;; a unit like any other, so a program that never `:require`s it does not
;; carry it, and that is what the symbol checks right below settle.
;;
;; It does NOT cover the LIVE-SET export/import that backs a host-requested
;; snapshot (`DECISIONS.md#snapshots`) or the control plane that serves it
;; (`DECISIONS.md#the-control-plane-is-the-runtimes`). The maintainer decided
;; both serving a call and shelving a sandbox are unconditional, in every
;; module, with no opt-out -- a host may ask any sandbox to export regardless
;; of what the guest required, so reachability from the guest's own code was
;; the wrong signal to gate that machinery on. `test/threads.clj` and
;; `test/twobuilds.clj` carry that cost in the pure-module floor (raised
;; 2026-10-06, see the comment there for the measurement); this file only
;; measures the half that is still opt-in.
(src! "pure" "(ns pure)\n(defn main [_] \"nothing\")")
(src! "snapped"
      (str "(ns snapped (:require [flint.snapshot :as snap]))\n"
           "(defn main [_] (str (pos? (snap/snapshot!))))"))
(def pure-size (fs/size (build! "pure" "out/sn-pure.wasm")))
(def snap-size (fs/size (build! "snapped" "out/sn-snapped.wasm")))
(println (format "    pure module %d bytes, with snapshots %d (+%d)"
                 pure-size snap-size (- snap-size pure-size)))
(def pure-bytes (String. (fs/read-all-bytes "out/sn-pure.wasm") "ISO-8859-1"))
(doseq [sym ["flint_snapshot_capture" "flint_snapshot_restore" "flint_b_snapshot"]]
  (check (str "a pure module has no " sym) (str/includes? pure-bytes sym) false))

;; The absolute floor belongs to `test/twobuilds.clj`, and only there: this file
;; runs against a DIAGNOSTICS build, where the module carries every instrument
;; in the runtime and its size measures how much instrumentation exists rather
;; than what 0005 claims. What is measurable here is the claim itself -- that
;; asking for the DIAGNOSTICS-ONLY capture is what costs, and not asking costs
;; nothing for THAT half -- which the symbol checks above settle exactly, and
;; the delta below bounds.
;;
;; The bound was 45 000 when the delta was the whole live-set serialiser
;; against a bare `memcpy` (41 431 bytes measured, back when `pure` carried
;; neither). It does not mean that any more: since the maintainer's decision
;; that serving and shelving are unconditional
;; (`DECISIONS.md#the-control-plane-is-the-runtimes`, `DECISIONS.md#snapshots`,
;; `test/threads.clj`'s EIGHTH RAISE), `pure` already carries `export_live`/
;; `import_live`, so the delta here is only the diagnostics unit's thin
;; wrapper -- `flint_b_snapshot`, `flint_snapshot_capture`/`restore`/`alloc`/
;; `ptr` and friends, plus `capture_into`'s own verbatim-memcpy path, which
;; `export_live` does not share. MEASURED 2026-10-06, host macOS 14.6.1
;; (arm64), commit `1483a511`, command `bb test/snapshot.clj`: pure 611 067
;; bytes, snapped 629 904, so +18 837 -- well under 45 000, and the bound is
;; left as-is rather than tightened, since this file does not otherwise pin
;; the diagnostics module's absolute size.
;;
;; Worth it, and the number that says so is on the other side: the same program's
;; state exports at 38 524 bytes as a live set against 5 275 808 verbatim. The
;; module pays 22 KB once; every snapshot after that is 0.7% of the size, and can
;; be imported into an instance that has never run.
(check-that "the snapshot surface costs only the program that asks for it"
            (< (- snap-size pure-size) 45000))

;; The program the inspector is pointed at: it allocates, snapshots, allocates
;; more, and snapshots again, so the two can be diffed across real work.
(src! "work"
      (str "(ns work (:require [flint.snapshot :as snap]))\n"
           "(defn build [n] (reduce (fn [m i] (assoc m i (str \"value-\" i))) {} (range n)))\n"
           "(defn main [_]\n"
           "  (let [m (build 400)]\n"
           "    (pr-str {:n (count m) :snap (pos? (snap/snapshot!))})))"))
(build! "work" "out/sn-work.wasm")

;; A DIFFERENT program, carrying the same snapshot surface. It exists so the
;; test can try to restore one program's state into another -- which is not an
;; exotic mistake once snapshots are files that outlive a process, and which
;; used to succeed silently. Every frame ip, constant index and var slot in a
;; snapshot is an index into an IMAGE, so the wrong image does not fail, it
;; means something else.
(src! "other"
      (str "(ns other (:require [flint.snapshot :as snap]))\n"
           "(defn tally [n] (reduce + 0 (map (fn [i] (* i i)) (range n))))\n"
           "(defn main [_]\n"
           "  (pr-str {:n (tally 300) :snap (pos? (snap/snapshot!))}))"))
(build! "other" "out/sn-other.wasm")

;; A program with a PARKED GREEN THREAD in it when the snapshot is taken.
;;
;; Nothing in this file exercised threads before, so "a snapshot captures the
;; threads" was structurally plausible and never checked: a parked thread's
;; saved stack and frames are ordinary heap objects hanging off the scheduler
;; singleton, so they should travel with the heap. Should is not a measurement.
(src! "parked"
      (str "(ns parked (:require [flint.thread :as t] [flint.port :as p]\n"
           "                     [flint.snapshot :as snap]))\n"
           "(defn main [_]\n"
           "  (let [[tx rx] (p/channel 1 \"c\")\n"
           ;; Two workers, both parked on an empty channel when the snapshot is
           ;; taken. Two rather than one so a count can tell "captured them"
           ;; from "captured a thread".
           "        a (t/spawn (fn [] (+ 40 (p/receive rx))))\n"
           "        b (t/spawn (fn [] (* 2 (p/receive rx))))\n"
           "        n (snap/snapshot!)]\n"
           "    (p/send tx 2)\n"
           "    (p/send tx 3)\n"
           "    (pr-str {:snap n :a (t/join a) :b (t/join b)})))"))
(build! "parked" "out/sn-parked.wasm")

(let [r (sh "node" "test/snapshot.mjs")]
  (print (:all r)) (flush)
  (when-not (zero? (:exit r)) (swap! fails inc)))

(when-not (zero? @fails)
  (println "snapshots:" @fails "FAILURES")
  (System/exit 1))
