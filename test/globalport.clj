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
(src! "sys"
      (str "(ns sys (:require [flint.port :as p] [flint.port.edn :as edn]))\n"
           "(defn main [_]\n"
           "  (if-let [s (p/system)]\n"
           "    (do (p/set-codec s edn/codec)\n"
           "        (p/send s {:op :ping})\n"
           "        (pr-str {:got (p/receive s) :label (p/label s)}))\n"
           "    (pr-str {:system :none})))"))
(build! "sys" "out/gp-sys.wasm")

;; The same program, and the host gives it nothing. It must still RUN.
(build! "sys" "out/gp-none.wasm")

(let [r (sh "node" "test/globalport.mjs")]
  (print (:all r)) (flush)
  (System/exit (:exit r)))
