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
      (str "(ns echo (:require [flint.port :as p]))\n"
           "(defn main [_]\n"
           "  (let [r (p/open \"echo\")\n"
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

;; ONE FORMAT, and it carries everything.
;;
;; This used to be three programs opening three ports with three codecs -- EDN,
;; JSON and Transit-over-msgpack -- because a port carried BYTES and the guest
;; chose how to write them. There is no codec to choose any more
;; (`doc/decisions/0027`): a bridge encodes and decodes in the runtime, and the
;; guest hands over a value and is handed one back.
;;
;; The values are the ones that used to separate the three: a set and a keyword,
;; which JSON refused by name, and a VECTOR AS A MAP KEY, which needed Transit.
;; All of them cross one port now, and the program says nothing about encoding.
(src! "formats"
      (str "(ns formats (:require [flint.port :as p]))\n"
           "(defn try-send [port v]\n"
           "  (try (p/send port v) (p/receive port) (catch Throwable ex (ex-message ex))))\n"
           "(defn main [_]\n"
           "  (p/with-open [w (p/open \"wire\")]\n"
           "    (pr-str [(try-send w {:a #{1 2} :b [:x]})\n"
           "             (try-send w {\"a\" [1 2]})\n"
           "             (try-send w :nope)\n"
           "             (try-send w #{1})\n"
           "             (try-send w {:a #{1 2} :b [:x] [1 2] :k})])))"))

(src! "query"
      (str "(ns query (:require [flint.port :as p]))\n"
           "(defn main [_]\n"
           "  (let [a (p/open \"sink\")\n"
           "        b (p/open \"sink\")]\n"
           "    (p/close a)\n"
           "    (p/send b \"still open\")\n"
           "    \"done\"))"))

(doseq [[n out] [["echo" "out/ha-echo.wasm"]
                 ["batch1" "out/ha-batch1.wasm"]
                 ["batch1000" "out/ha-batch1000.wasm"]
                 ["drop" "out/ha-drop.wasm"]
                 ["exit" "out/ha-exit.wasm"]
                 ["formats" "out/ha-formats.wasm"]
                 ["query" "out/ha-query.wasm"]]]
  (build! n out))

(let [r (sh "node" "test/host_abi.mjs")]
  (print (:all r))
  (flush)
  (System/exit (:exit r)))
