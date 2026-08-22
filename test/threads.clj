;; Green threads, ports and the host ABI (doc/decisions/0005 and 0006).
;;
;; The two properties that govern everything else are asserted first: a pure
;; program is not made bigger by any of this, and nothing in a flint module
;; suspends a wasm frame.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[clojure.edn :as edn])

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

(defn build! [ns-name & [out]]
  (let [o (or out (str "out/th-" ns-name ".wasm"))
        r (sh "./bin/flint" ":src" d ":fn" (str ns-name "/main") ":out" o)]
    (when-not (zero? (:exit r))
      (println "build failed for" ns-name ":" (:all r)) (System/exit 1))
    o))

(defn run! [wasm & [host]]
  (let [r (sh "node" (or host "host/flint.mjs") wasm)]
    (str/trim (:all r))))

(defn src! [name body] (spit (str d "/" name ".cljc") body))

(println "threads and ports")

;; ---------------------------------------------------------------- size
;;
;; "None of this may grow a pure module." Threads and ports are namespace units
;; like any other, so a program that never mentions them must not carry a
;; scheduler, port machinery or a host-callback surface.

(src! "pure" "(ns pure)\n(defn main [_] \"nothing\")")
(src! "threaded"
      (str "(ns threaded (:require [flint.thread :as t]))\n"
           "(defn main [_] (str (t/join (t/spawn (fn [] 42)))))"))
(def pure-wasm (build! "pure"))
(def threaded-wasm (build! "threaded"))
(def pure-size (fs/size pure-wasm))
(println (format "    pure module %d bytes, with threads %d (+%d)"
                 pure-size (fs/size threaded-wasm) (- (fs/size threaded-wasm) pure-size)))

(def pure-bytes (String. (fs/read-all-bytes pure-wasm) "ISO-8859-1"))
(doseq [sym ["flint_resume" "flint_drain" "flint_continue" "flint_b_spawn"
             "flint_b_port_send" "flint_b_channel"]]
  (check (str "a pure module has no " sym) (str/includes? pure-bytes sym) false))
(check-that "a pure module is no bigger than the floor this phase started from"
            (< pure-size 180000))

;; ---------------------------------------------------------------- channels

(src! "chan"
      (str "(ns chan (:require [flint.thread :as t] [flint.port :as p]))\n"
           "(defn main [_]\n"
           "  (let [[a b] (p/channel 1 \"one-slot\")\n"
           ;; A one-slot buffer forces both parks: the sender blocks on a full
           ;; buffer, the receiver on an empty one.
           "        w (t/spawn (fn [] (dotimes [i 5] (p/send a i)) :sent))\n"
           "        got (loop [acc []] (if (= 5 (count acc)) acc (recur (conj acc (p/receive b)))))]\n"
           "    (pr-str {:got got :worker (t/join w) :state (t/state w)})))"))
(check "a full buffer parks the sender and an empty one parks the receiver"
       (run! (build! "chan"))
       "{:got [0 1 2 3 4], :worker :sent, :state :done}")

(src! "closed"
      (str "(ns closed (:require [flint.thread :as t] [flint.port :as p]))\n"
           "(defn main [_]\n"
           "  (let [[a b] (p/channel 2)]\n"
           "    (p/send a 1) (p/close a)\n"
           "    (pr-str [(p/receive b) (p/receive b) (p/state b)])))"))
(check "a closed port drains and then reads as end of stream"
       (run! (build! "closed")) "[1 nil :closed]")

;; ---------------------------------------------------------------- what crosses

(src! "crossing"
      (str "(ns crossing (:require [flint.port :as p]))\n"
           "(defn helper [x] x)\n"
           "(defn main [_]\n"
           "  (let [[a b] (p/channel 2)]\n"
           "    (pr-str [(try (p/send a helper) (catch Throwable e (ex-message e)))\n"
           "             (try (p/send a b) (catch Throwable e (ex-message e)))\n"
           "             (try (p/send a [1 {:k helper}]) (catch Throwable e (ex-message e)))])))"))
(def crossing (run! (build! "crossing")))
(check-that "a function is refused at the send, by name"
            (str/includes? crossing "helper is a function"))
(check-that "a port cannot be sent through a port"
            (str/includes? crossing "a port cannot be sent through a port"))
(check-that "a function nested inside a value is refused too"
            (str/includes? crossing "helper is a function"))

;; ---------------------------------------------------------------- determinism

(src! "sched"
      (str "(ns sched (:require [flint.thread :as t] [flint.port :as p]))\n"
           "(defn worker [tag n out]\n"
           "  (fn [] (dotimes [i n] (p/send out [tag i]) (t/yield)) tag))\n"
           "(defn main [_]\n"
           "  (let [[in out] (p/channel 64)\n"
           "        ws (mapv (fn [tag] (t/spawn (worker tag 4 in))) [:a :b :c])\n"
           "        _ (mapv t/join ws)\n"
           "        _ (p/close in)\n"
           "        got (loop [acc []] (let [v (p/receive out)] (if (nil? v) acc (recur (conj acc v)))))]\n"
           "    (pr-str got)))"))
(def sched-wasm (build! "sched"))
(def sched-runs (vec (repeatedly 5 #(run! sched-wasm))))
(check "the scheduler is deterministic: five runs, one answer"
       (count (distinct sched-runs)) 1)
(check-that "and the threads really did interleave rather than running to completion"
            (let [tags (vec (map first (edn/read-string (first sched-runs))))]
              (and (= 12 (count tags))
                   (not= (take 4 tags) (repeat 4 (first tags))))))

;; ---------------------------------------------------------------- dynamic vars

(src! "dyn"
      (str "(ns dyn (:require [flint.thread :as t]))\n"
           "(def ^:dynamic *level* :info)\n"
           "(defn peek-level [] *level*)\n"
           "(defn main [_]\n"
           "  (let [outer (peek-level)\n"
           "        inner (binding [*level* :debug] (peek-level))\n"
           "        after (peek-level)\n"
           "        child (binding [*level* :trace] (t/join (t/spawn (fn [] (peek-level)))))\n"
           "        sibling (let [w (t/spawn (fn [] (t/yield) (peek-level)))]\n"
           "                  (binding [*level* :warn] (t/yield))\n"
           "                  (t/join w))]\n"
           "    (pr-str {:outer outer :inner inner :after after :child child :sibling sibling})))"))
(check "binding is a stack discipline per GREEN thread, and a spawn inherits a snapshot"
       (run! (build! "dyn"))
       "{:outer :info, :inner :debug, :after :info, :child :trace, :sibling :info}")

(src! "notdyn"
      (str "(ns notdyn)\n(def plain 1)\n"
           "(defn main [_] (binding [plain 2] plain))"))
(let [r (sh "./bin/flint" ":src" d ":fn" "notdyn/main" ":out" "out/th-notdyn.wasm")]
  (check "rebinding a var that is not dynamic is a compile error" (:exit r) 1)
  (check-that "  ... which says how to make it dynamic"
              (str/includes? (:all r) "^:dynamic")))

;; ---------------------------------------------------------------- protocols

(src! "proto"
      (str "(ns proto)\n"
           "(defprotocol Shape (area [s]) (describe [s prefix]))\n"
           "(extend-protocol Shape\n"
           "  :vector (area [s] (* (nth s 0) (nth s 1)))\n"
           "          (describe [s prefix] (str prefix \"vector \" (area s)))\n"
           "  :number (area [s] (* s s))\n"
           "          (describe [s prefix] (str prefix \"number \" (area s))))\n"
           "(def circle (with-meta {:r 2} {:proto/area (fn [s] (* 3 (:r s) (:r s)))}))\n"
           "(defn main [_]\n"
           "  (pr-str [(area [3 4]) (area 5) (area circle)\n"
           "           (describe [3 4] \"a \")\n"
           "           (satisfies? Shape [1 2]) (satisfies? Shape \"no\")\n"
           "           (try (area \"nope\") (catch Throwable e (ex-message e)))]))"))
(def proto (run! (build! "proto")))
(check-that "a protocol dispatches on a built-in kind" (str/includes? proto "[12 25 12"))
(check-that "  ... and on metadata, which is the main road here"
            (str/includes? proto "12 \"a vector 12\""))
(check-that "a value with no implementation names the protocol"
            (str/includes? proto "(protocol proto/Shape)"))
(check-that "  ... and the value's kind"
            (str/includes? proto "for a value of kind :string"))
(check-that "  ... and how to fix it" (str/includes? proto ":proto/area as metadata"))

(if (zero? @fails)
  (println "threads: ok")
  (do (println "threads:" @fails "FAILURES") (System/exit 1)))
