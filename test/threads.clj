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
;; The floor moved in 0009, deliberately and by a known amount: the interpreter
;; loop is instantiated twice so that a run with no budget has no counter in it,
;; and the biggest function in the module is therefore in it twice. The point of
;; the bound here is that it is a BUDGET somebody chose, not a number that
;; drifts.
(check-that "the floor is within the budget 0009 traded for the free loop"
            (< pure-size 205000))

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
           "    (pr-str [(p/receive b) (p/state b) (p/receive b) (p/state b)\n"
           "             (p/closed? b) (try (p/send b 2) (catch Throwable e (ex-message e)))])))"))
(def closed-out (run! (build! "closed")))
(check-that "a half-closed port still drains what was already buffered"
            (str/starts-with? closed-out "[1 :half-closed nil"))
(check-that "  ... and then reads as end of stream, which closed? agrees with"
            (str/includes? closed-out "nil :half-closed true"))
(check-that "sending into a port whose peer has closed errors rather than parking"
            (str/includes? closed-out "the other end has closed"))

(src! "orphan"
      (str "(ns orphan (:require [flint.thread :as t] [flint.port :as p]))\n"
           "(defn only-b [] (let [[a b] (p/channel 1)] b))\n"
           "(defn main [_]\n"
           "  (let [b (only-b)]\n"
           "    (dotimes [i 400000] (str \"gc-padding-\" i))\n"
           "    (pr-str [(p/state b)\n"
           "             (try (p/receive b) (catch Throwable e (ex-message e)))\n"
           "             (try (p/send b 1) (catch Throwable e (ex-message e)))])))"))
(def orphan-out (run! (build! "orphan")))
(check-that "a port whose peer was collected reports :orphaned, not :closed"
            (str/includes? orphan-out ":orphaned"))
(check-that "  ... and receiving on it errors rather than reading as end of stream"
            (str/includes? orphan-out "receive: the other end of this port is gone"))
(check-that "  ... and so does sending" (str/includes? orphan-out "send: the other end is gone"))

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

;; ------------------------------------------------- parking through a value
;;
;; There are two ways into a native: the CALL_NATIVE opcode, and dynamic
;; dispatch through a value (a higher-order position, `apply`, a var). Only the
;; first handled parking, so a parking native reached the second way had its
;; arguments dropped out of the root set while the thread was parked -- and the
;; park was then handed to the unwinder as though it were a thrown error.
(src! "indirect"
      (str "(ns indirect (:require [flint.thread :as t] [flint.port :as p]))\n"
           "(defn main [_]\n"
           "  (let [[tx rx] (p/channel 1 \"probe\")\n"
           "        recv flint.rt/port-receive\n"
           "        _ (t/spawn (fn [] (flint.rt/port-send tx :hello) :sent))\n"
           "        direct (do (t/spawn (fn [] (flint.rt/port-send tx :a) nil)) (flint.rt/port-receive rx))\n"
           "        _ (t/spawn (fn [] (flint.rt/port-send tx :b) nil))\n"
           "        hof (recv rx)\n"
           "        _ (t/spawn (fn [] (flint.rt/port-send tx :c) nil))\n"
           "        applied (apply flint.rt/port-receive [rx])]\n"
           "    (pr-str [(flint.rt/port-receive rx) direct hof applied])))"))
(def indirect (run! (build! "indirect")))
(check-that "a parking native reached through a value returns its value"
            (not (str/includes? indirect "unprintable")))
(check "  ... the same as one reached through the opcode"
       ;; Bindings run in order, so each receive takes what the previous spawn
       ;; sent; the body's receive takes the last. The point is that all four
       ;; are the values sent, whichever path reached the native.
       indirect "[:c :hello :a :b]")

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
