;; VM snapshots (doc/decisions/0015).
;;
;; The point of a snapshot is that it is a COPY, not a question: every ad-hoc
;; probe answers one thing and can answer it confidently wrong, while a snapshot
;; is raw state you interpret afterwards and re-interpret when the question
;; changes. So the assertions here are about the capture being complete and the
;; inspector being able to answer the questions the instruments could not.
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
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))
(defn build! [ns-name out]
  (let [r (sh "./bin/flint" ":src" d ":fn" (str ns-name "/main") ":out" out)]
    (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1))
    out))
(defn src! [name body] (spit (str d "/" name ".cljc") body))

(println "snapshots")

;; ------------------------------------------------------------- module size
;;
;; `0005`'s rule: none of this may grow a pure module. The snapshot surface is a
;; unit like any other, so a program that never asks for one does not carry it.
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
;; asking for snapshots is what costs, and not asking costs nothing -- which the
;; symbol checks above settle exactly, and the delta below bounds.
;; The bound moved from 25 000 to 45 000 when the LIVE-SET format landed, and
;; the delta is the whole of what that format is: a serialiser and a
;; deserialiser, against a `memcpy` and a `memcpy` back. Measured at 41 431
;; bytes against 19 586 before.
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

(let [r (sh "node" "test/snapshot.mjs")]
  (print (:all r)) (flush)
  (when-not (zero? (:exit r)) (swap! fails inc)))

(when-not (zero? @fails)
  (println "snapshots:" @fails "FAILURES")
  (System/exit 1))
