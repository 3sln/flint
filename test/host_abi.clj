;; Compiles the modules `test/host_abi.mjs` drives, then runs it. The assertions
;; live on the JavaScript side because they are about the *host's* view of the
;; ABI: tokens, one queue, and who may collect what.
(require '[babashka.fs :as fs] '[clojure.string :as str])

(def d (str (fs/create-temp-dir)))
(defn src! [n body] (spit (str d "/" n ".cljc") body))

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
;; (`DECISIONS.md#ports-are-the-hosts`): a bridge encodes and decodes in the runtime, and the
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

(src! "wiremeta"
      (str "(ns wiremeta (:require [flint.port :as p]))\n"
           ;; WHAT CROSSES IS WHAT `WireMeta` SELECTS, and the default is
           ;; nothing -- so this sends one value with an opt-in written in
           ;; metadata and one without, and the HOST reports which arrived
           ;; carrying what. A guest-side assertion would prove nothing about
           ;; the wire.
           "(defn main [_]\n"
           "  (p/with-open [w (p/open \"wire\")]\n"
           "    (p/send w (with-meta [1 2] {:dropped true}))\n"
           "    (p/receive w)\n"
           "    (p/send w (with-meta [3 4]\n"
           "                {:dropped true :kept :yes\n"
           "                 (quote flint.protocols/-wire-meta)\n"
           "                 (fn [x] {:kept (:kept (meta x))})}))\n"
           "    (p/receive w)\n"
           "    \"done\"))"))

(src! "wirediff"
      (str "(ns wirediff (:require [flint.port :as p] [flint.wire :as wire]\n"
           "                        [flint.table :as t]))\n"
           ;; EACH VALUE TWICE: once as a VALUE, so the RUNTIME encodes it, and
           ;; once through `flint.wire/encode`, so FLINT does. The host compares
           ;; the bytes. Identical is the whole claim of
           ;; `DECISIONS.md#the-codec-is-guest-code` -- the format does not
           ;; change, only who writes it, which is what lets the two be compared
           ;; directly instead of round-tripped and hoped over.
           "(defn- pair [w v]\n"
           "  (p/send w v) (p/receive w)\n"
           "  (p/send w (wire/encode v)) (p/receive w))\n"
           ;; NOT `with-open`: it expands to a try, and `doseq` cannot recur
           ;; across a try boundary.
           "(defn main [_]\n"
           "  (let [w (p/open \"wire\")]\n"
           "    (doseq [v [42 -7 0 1.5 -0.25 \"hi\" \"\"\n"
           ;; A QUALIFIED AND AN UNQUALIFIED NAME, because absent is not empty:
           ;; the namespace word is `ffffffff` for one and a length for the
           ;; other, and a codec that confused them passes every other row here.
           "               :op :my.ns/a (quote sym) (quote my.ns/s)\n"
           "               nil true false\n"
           "               [1 2 3] [] [[1] [2]]\n"
           ;; MULTI-ENTRY maps and sets, because a one-entry collection cannot
           ;; reveal a traversal-order disagreement -- and the two encoders walk
           ;; the CHAMP by separate code.
           "               #{1} #{1 2 3}\n"
           "               (list 1 2) (list)\n"
           "               {:a 1} {} {:a 1 :b 2 :c 3} {\"s\" 1 :k 2 [1 2] 3}\n"
           "               {:nested {:deep [1 #{2} (list 3)]}}\n"
           ;; A ROPE: `apply str` builds one, and `as_str` will not materialise
           ;; a rope -- the trap `str->b` records and a codec can fall into.
           "               (flint.rt/str->b \"bytes\")\n"
           "               (apply str (repeat 40 \"long-string-\"))\n"
           "               [(flint.rt/str->b \"a\") (flint.rt/str->b \"bb\")]\n"
           ;; A TAGGED LITERAL, which is a wrapper like metadata: one tag and
           ;; two ordinary values. Nested too, so it is reached through a
           ;; collection and not only at the top.
           "               (tagged-literal (quote my.ns/thing) [1 2])\n"
           "               (tagged-literal (quote a/b) \"s\")\n"
           "               [(tagged-literal (quote x/y) 1)]\n"
           ;; A TABLE, whose ROW COUNT sits after values -- the shape that
           ;; made the writer track structure. Byte equality here says the
           ;; extra state machine did not change the FORMAT: the guest
           ;; encoder lays a table down exactly as `codec.rs` does, counts
           ;; and column order included.
           "               (t/table (t/schema [[:id :int] [:nm :string]])\n"
           "                        [{:id 1 :nm \"ada\"} {:id 2 :nm \"alan\"}])\n"
           ;; EMPTY, where the row count is zero and there are no cells at
           ;; all -- the case where the writer completes on the count.
           "               (t/table (t/schema [[:id :int]]) [])\n"
           ;; NESTED, so a table is reached through a collection and its
           ;; completion has to hand back to the vector around it.
           "               [(t/table (t/schema [[:a :keyword] [:b :double]])\n"
           "                         [{:a :x :b 1.5}])]]]\n"
           "      (pair w v))\n"
           "    (p/close w)\n"
           "    \"done\"))"))

(src! "wiretable"
      (str "(ns wiretable (:require [flint.port :as p] [flint.table :as t]))\n"
           ;; A TABLE THROUGH A JS HOST AND BACK. The guest sends one, the host
           ;; decodes it, rebuilds it with `codec.from` and sends it back --
           ;; so both halves of the JS codec are exercised against the real
           ;; format rather than against each other.
           ;;
           ;; Compared with `=` on the way back, because a table that arrives
           ;; with its columns in a different order, or its ints as doubles, is
           ;; a table the host rebuilt WRONG and a shape-only check would pass.
           "(defn main [_]\n"
           "  (p/with-open [w (p/open \"wire\")]\n"
           "    (let [tb (t/table (t/schema [[:id :int] [:nm :string]])\n"
           "                      [{:id 1 :nm \"ada\"} {:id 2 :nm \"alan\"}])\n"
           "          _ (p/send w tb)\n"
           "          back (p/receive w)\n"
           "          e (t/table (t/schema [[:id :int]]) [])\n"
           "          _ (p/send w e)\n"
           "          eback (p/receive w)]\n"
           "      (pr-str [(= tb back) (= e eback)\n"
           "               (t/columns (t/table-schema back))\n"
           "               (t/types (t/table-schema back))\n"
           "               (t/column back :nm)]))))"))

(src! "wireport"
      (str "(ns wireport (:require [flint.port :as p] [flint.wire :as wire]))\n"
           ;; THE ASYMMETRY, PROBED. `check_sendable_via` refuses a CHANNEL end
           ;; sent to the host: both its ends live in this heap, the host was
           ;; never told it exists, so its id names one of our objects from
           ;; outside -- the integer-to-port conversion the whole design exists
           ;; to prevent. That check runs on a VALUE, and a guest-encoded
           ;; message is not a value by the time it reaches it.
           ;;
           ;; So the question this asks is whether the guest encoder is a way
           ;; around it. Both are sent: the channel end as a value, which must
           ;; be refused, and the same end through `flint.wire/encode`, which
           ;; must be refused too or `send` cannot switch to the guest encoder.
           "(defn- kind-of [f] (try (f) \"NO THROW\" (catch Throwable e (flint.rt/ex-kind e))))\n"
           "(defn main [_]\n"
           "  (let [w (p/open \"wire\")\n"
           "        [a b] (p/channel \"internal\")\n"
           "        r (str \"value=\" (kind-of (fn [] (p/send w a)))\n"
           "               \" encoded=\" (kind-of (fn [] (p/send w (wire/encode a))))\n"
           ;; AND THE BRIDGE ITSELF, which MAY be written: its id is the host's
           ;; own and already means something over there. A guard that refused
           ;; every port would break delegation, which is the feature.
           "               \" bridge=\" (kind-of (fn [] (p/send w (wire/encode w)))))]\n"
           "    (p/close a) (p/close b) (p/close w)\n"
           "    r))"))

(src! "wirebad"
      (str "(ns wirebad (:require [flint.port :as p]))\n"
           ;; THE WRITER ENFORCES THE FORMAT (`DECISIONS.md#the-codec-is-guest-code`).
           ;; A streaming encoder that lets a guest assert structure lets it put
           ;; four raw bytes where a value is due, and four bytes at a value
           ;; position are a tag and its payload on the far side -- `0000000f`
           ;; is `K_PORT` and an id. So the writer refuses instead.
           "(defn- kind-of [f] (try (f) \"NO THROW\" (catch Throwable e (flint.rt/ex-kind e))))\n"
           "(defn main [_]\n"
           "  (let [w (p/open \"wire\")\n"
           ;; REFUSED WHERE IT HAPPENS: the message was complete after the first
           ;; value, so the second has nowhere to go.
           "        r (str \"second=\" (kind-of (fn [] (-> (flint.rt/wire-writer)\n"
           "                                              (flint.rt/wire-int 1)\n"
           "                                              (flint.rt/wire-int 2))))\n"
           ;; REFUSED AT SEND: a container opened and not filled is a message no
           ;; reader can read, and shipping it reports the fault at the end that
           ;; did not commit it.
           "               \" unfinished=\" (kind-of (fn [] (p/send w (-> (flint.rt/wire-writer)\n"
           "                                                           (flint.rt/wire-vec 3)\n"
           "                                                           (flint.rt/wire-int 1)))))\n"
           ;; A MAP OF ONE PAIR IS TWO VALUES. A codec counting pairs would call
           ;; this complete half way through.
           "               \" maphalf=\" (kind-of (fn [] (p/send w (-> (flint.rt/wire-writer)\n"
           "                                                        (flint.rt/wire-map 1)\n"
           "                                                        (flint.rt/wire-int 1)))))\n"
           "               \" ok=\" (kind-of (fn [] (p/send w (-> (flint.rt/wire-writer)\n"
           "                                                   (flint.rt/wire-vec 1)\n"
           "                                                   (flint.rt/wire-int 1))))))]\n"
           "    (p/close w) r))"))

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
                 ["wiremeta" "out/ha-wiremeta.wasm"]
                 ["wirediff" "out/ha-wirediff.wasm"]
                 ["wirebad" "out/ha-wirebad.wasm"]
                 ["wireport" "out/ha-wireport.wasm"]
                 ["wiretable" "out/ha-wiretable.wasm"]
                 ["query" "out/ha-query.wasm"]]]
  (build! n out))

(let [r (sh "node" "test/host_abi.mjs")]
  (print (:all r))
  (flush)
  (System/exit (:exit r)))
