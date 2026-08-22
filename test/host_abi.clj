;; Compiles the modules `test/host_abi.mjs` drives, then runs it. The assertions
;; live on the JavaScript side because they are about the *host's* view of the
;; ABI: tokens, one queue, and who may collect what.
(require '[babashka.fs :as fs] '[clojure.string :as str])

(def d (str (fs/create-temp-dir)))
(defn src! [n body] (spit (str d "/" n ".cljc") body))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :all (str out err)}))

(defn build! [ns-name out]
  (let [r (sh "./bin/flint" ":src" d ":fn" (str ns-name "/main") ":out" out)]
    (when-not (zero? (:exit r))
      (println "build failed for" ns-name ":" (:all r)) (System/exit 1))))

(src! "echo"
      (str "(ns echo (:require [flint.port :as p] [flint.port.edn :as edn]))\n"
           "(defn main [_]\n"
           "  (let [r (p/open \"echo\" {:codec edn/codec})\n"
           "        _ (p/send r {:hello 1})\n"
           "        back (p/receive r)\n"
           "        refused (try (p/open \"secret\") (catch Throwable e (ex-message e)))]\n"
           "    (p/close r)\n"
           "    (pr-str {:back back :refused refused :state (p/state r)})))"))

;; One message, and a thousand, so "batched" can be a number rather than a
;; claim. Both drain in a single call.
(doseq [[n out] [[1 "batch1"] [1000 "batch1000"]]]
  (src! out
        (str "(ns " out " (:require [flint.port :as p]))\n"
             "(defn main [_]\n"
             "  (let [r (p/open \"sink\")]\n"
             "    (dotimes [i " n "] (p/send r \"a message of some ordinary length\"))\n"
             "    \"sent\"))")))

;; A port dropped without being closed, then enough allocation to make the
;; collector notice, then a second capability so the pump runs again while the
;; program is still going.
(src! "drop"
      (str "(ns drop (:require [flint.port :as p]))\n"
           "(defn use-and-drop [] (let [r (p/open \"sink\")] (p/send r \"one\") :dropped))\n"
           "(defn main [_]\n"
           "  (let [a (use-and-drop)]\n"
           "    (dotimes [i 400000] (str \"gc-padding-\" i))\n"
           "    (let [r2 (p/open \"sink\")]\n"
           "      (p/send r2 \"two\")\n"
           "      (p/close r2)\n"
           "      (pr-str [a :done]))))"))

;; Three ports, none of them closed by the program.
(src! "exit"
      (str "(ns exit (:require [flint.port :as p]))\n"
           "(defn main [_]\n"
           "  (let [ps (mapv (fn [i] (p/open \"sink\")) [1 2 3])]\n"
           "    (doseq [x ps] (p/send x \"hi\"))\n"
           "    \"left open\"))"))

(src! "formats"
      (str "(ns formats (:require [flint.port :as p]\n"
           "                      [flint.port.edn :as e]\n"
           "                      [flint.port.json :as j]\n"
           "                      [flint.port.transit :as tr]))\n"
           "(defn try-send [port v]\n"
           "  (try (p/send port v) (p/receive port) (catch Throwable ex (ex-message ex))))\n"
           "(defn main [_]\n"
           "  (p/with-open [ep (p/open \"edn\" {:codec e/codec})\n"
           "                jp (p/open \"json\" {:codec j/codec})\n"
           "                tp (p/open \"transit\" {:codec tr/codec})]\n"
           "    (pr-str [(try-send ep {:a #{1 2} :b [:x]})\n"
           "             (try-send jp {\"a\" [1 2]})\n"
           "             (try-send jp :nope)\n"
           "             (try-send jp #{1})\n"
           "             (try-send tp {:a #{1 2} :b [:x] [1 2] :k})])))"))

(doseq [[n out] [["echo" "out/ha-echo.wasm"]
                 ["batch1" "out/ha-batch1.wasm"]
                 ["batch1000" "out/ha-batch1000.wasm"]
                 ["drop" "out/ha-drop.wasm"]
                 ["exit" "out/ha-exit.wasm"]
                 ["formats" "out/ha-formats.wasm"]]]
  (build! n out))

(let [r (sh "node" "test/host_abi.mjs")]
  (print (:all r))
  (flush)
  (System/exit (:exit r)))
