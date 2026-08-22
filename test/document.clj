;; Compiles the scripts `test/document.mjs` drives, then runs it. The
;; assertions live on the JavaScript side because they are measurements of the
;; host's traffic and of the module's memory, which is where 0008's claims are.
(require '[babashka.fs :as fs])

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

(def preamble
  (str "(:require [flint.port :as p] [flint.port.edn :as edn] [flint.doc :as doc]))\n"
       "(defn open-doc [] (doc/open (p/open \"doc\" {:codec edn/codec})))\n"))

;; A full structure walk, and nothing else. Every one of these calls is ordinary
;; in-memory Clojure; if any of them reached the port this would show up as
;; extra messages on the host side.
(src! "walk"
      (str "(ns walk " preamble
           "(defn main [_]\n"
           "  (let [d (open-doc)\n"
           "        all (doc/descendants d (doc/root d))\n"
           "        leaves (doc/select d (fn [n] (= \"leaf\" (:type n))))\n"
           "        kids (reduce + 0 (map (fn [n] (count (doc/children d n))) all))]\n"
           "    (pr-str {:walked (count all) :leaves (count leaves) :kids kids})))"))

;; The same fetch, asked for in one call or one node at a time. This is the
;; failure mode 0008 exists to avoid, written down so it can be measured.
(src! "batch"
      (str "(ns batch " preamble
           "(defn main [args]\n"
           "  (let [d (open-doc)\n"
           "        ids (mapv :id (doc/select d (fn [n] (= \"leaf\" (:type n)))))\n"
           "        got (if (= \"single\" (first args))\n"
           "              (reduce (fn [m id] (merge m (doc/content d [id]))) {} ids)\n"
           "              (doc/content d ids))]\n"
           "    (pr-str {:n (count got) :bytes (reduce + 0 (map count (vals got)))})))"))

;; Two leaves at opposite ends: the planner coalesces the whole span, and the
;; bytes in between must be dropped at the boundary rather than crossing it.
(src! "ends"
      (str "(ns ends " preamble
           "(defn main [args]\n"
           "  (let [d (open-doc)\n"
           "        a (flint.rt/str->num (first args))\n"
           "        b (flint.rt/str->num (second args))\n"
           "        got (doc/content d [a b])]\n"
           "    (pr-str {:n (count got) :bytes (reduce + 0 (map count (vals got)))})))"))

;; An ask far larger than the host's budget. `content-each` releases each wave
;; before the next arrives, which is the whole point of waves.
(src! "waves"
      (str "(ns waves " preamble
           "(defn main [_]\n"
           "  (let [d (open-doc)\n"
           "        ids (mapv :id (doc/select d (fn [n] (= \"leaf\" (:type n)))))\n"
           "        seen (atom 0)\n"
           "        waves (doc/content-each d ids (fn [wave]\n"
           "                (swap! seen + (reduce + 0 (map (fn [e] (count (second e))) wave))))) ]\n"
           "    (pr-str {:waves waves :bytes @seen})))"))

;; Structure, then one percent of the content, and nothing retained.
(src! "onepct"
      (str "(ns onepct " preamble
           "(defn main [_]\n"
           "  (let [d (open-doc)\n"
           "        ids (mapv :id (doc/select d (fn [n] (= \"leaf\" (:type n)))))\n"
           "        want (vec (take (max 1 (quot (count ids) 100)) ids))\n"
           "        n (atom 0)\n"
           "        _ (doc/content-each d want (fn [wave]\n"
           "            (swap! n + (reduce + 0 (map (fn [e] (count (second e))) wave)))))]\n"
           "    (pr-str {:read @n})))"))

(doseq [[n out] [["walk" "out/doc-walk.wasm"]
                 ["batch" "out/doc-batch.wasm"]
                 ["ends" "out/doc-ends.wasm"]
                 ["waves" "out/doc-waves.wasm"]
                 ["onepct" "out/doc-onepct.wasm"]]]
  (build! n out))

(let [r (sh "node" "test/document.mjs")]
  (print (:all r))
  (flush)
  (System/exit (:exit r)))
