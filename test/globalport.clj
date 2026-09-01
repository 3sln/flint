;; Global ports (`doc/decisions/0027`), and the system port a host passes IN.
;;
;; The inversion this file exists to check: a sandbox no longer manufactures an
;; endpoint and offers it up. The host owns the port, keeps one end, and passes
;; the other in at construction -- so a sandbox given none can run its logic and
;; ask the world for nothing. That is what confined should mean by default,
;; rather than something a host has to remember to withhold.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def d (str (fs/create-temp-dir)))
(defn src! [n body] (spit (str d "/" n ".cljc") body))
(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :all (str out err)}))
(defn build! [n out]
  (let [r (sh "./bin/flint" ":src" d ":fn" (str n "/main") ":out" out)]
    (when-not (zero? (:exit r)) (println "build failed for" n ":" (:all r)) (System/exit 1))
    out))

;; Asks for the system port and uses it. Nothing here names a capability, and
;; nothing here calls `open`.
;; The system port is the TRANSPORT the sandbox is driven over, not a capability
;; the sandbox holds. Guest code cannot name it, and this is the check: a
;; program that tries must not compile.
;;
;; Asserted as a compile failure rather than by reading the source, because
;; "there is no such function" is only true while nobody adds one back. It was
;; briefly reachable as `flint.port/system`, and that was wrong for the reason
;; `0022` gives about capabilities: authority is never something the confined
;; thing can name for itself.
(src! "reach"
      (str "(ns reach (:require [flint.port :as p]))\n"
           "(defn main [_] (pr-str (p/system)))"))
(let [r (sh "./bin/flint" ":src" d ":fn" "reach/main" ":out" "out/gp-reach.wasm")]
  (if (and (not (zero? (:exit r)))
           (str/includes? (:all r) "unable to resolve"))
    (println "  ok   guest code cannot name the system port")
    (do (println "  FAIL guest code can still name the system port\n       " (:all r))
        (System/exit 1))))

;; A program that uses ports -- a LOCAL channel between two green threads, which
;; is what a sandbox can still make for itself. It must be undisturbed by the
;; host installing global ports it cannot see.
;;
;; It has to mention ports at all, or only-reachable-code-ships is right to
;; leave the whole port ABI out and there is nothing for the host to call.
(src! "sys"
      (str "(ns sys (:require [flint.port :as p] [flint.thread :as t]))\n"
           "(defn main [_]\n"
           "  (let [[tx rx] (p/channel 1 \"local\")]\n"
           "    (t/spawn (fn [] (p/send tx :hello)))\n"
           "    (pr-str {:ran true :local (p/receive rx)})))"))
(build! "sys" "out/gp-sys.wasm")

;; A program with REAL INITIALISERS, whose entry simply returns a value.
;;
;; `flint.cli` is here for its namespace initialisers and nothing else -- it is
;; the biggest one to hand, and the amount of work it does at load is the whole
;; point. Installing a port creates the scheduler, creating a scheduler ARMS A
;; PREEMPTION SLICE, and `run_program` ran the initialisers under it and threw
;; the yield away: the thread came back with `park_on` still set, `settle` read
;; it as a yield, saved a half-built state and never recorded the entry's value.
;; A program whose entry is a constant answered "the entry function did not
;; return a string".
;;
;; It needs enough initialiser work to cross a 4096-step slice, which is exactly
;; why it went unnoticed -- every small program finishes inside one.
(src! "init"
      (str "(ns init (:require [flint.cli :as cli] [flint.port :as p]))\n"
           "(defn main [_] (str \"constant\" (nil? cli/run) (p/port? 1)))"))
(build! "init" "out/gp-init.wasm")

(let [r (sh "node" "test/globalport.mjs")]
  (print (:all r)) (flush)
  (System/exit (:exit r)))
