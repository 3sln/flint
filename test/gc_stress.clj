;; Collections under collection pressure, with compound keys. See
;; test/gcstress.cljc for what this is guarding against.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[clojure.edn :as edn])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(println "gc stress: compound keys survive collection")
(let [b (sh "./bin/flint" ":src" "test" ":fn" "gcstress/main" ":out" "out/gcstress.wasm")]
  (when-not (zero? (:exit b))
    (println "build failed:" (:out b) (:err b)) (System/exit 1)))

(let [r (sh "node" "host/flint.mjs" "out/gcstress.wasm")]
  (when-not (zero? (:exit r))
    (println "  FAIL  the module trapped:" (str/trim (:err r))) (System/exit 1))
  (let [res (edn/read-string (str/trim (:out r)))
        fails (atom 0)
        check (fn [label ok] (if ok (println "  ok  " label)
                                 (do (swap! fails inc) (println "  FAIL" label))))]
    (doseq [k [:vec :mixed :deep :list :set :transient]]
      (let [[cnt missing] (get res k)]
        (check (format "%-10s %d entries, none unfindable" (name k) cnt)
               (and (pos? cnt) (zero? missing)))))
    (check "= with three compound arguments" (:eq3 res))
    (check "sort with compound elements" (:sorted res))
    (if (zero? @fails)
      (println "gc stress: ok")
      (do (println "gc stress:" @fails "FAILURES") (System/exit 1)))))

;; --- the generational invariant, as a standing assertion --------------------
;;
;; **Every old object pointing at a young one must be in the remembered set.**
;; That is not a question about any particular bug; it is the invariant a
;; generational collector rests on. Violating it means a young object is never
;; traced, dies, and leaves a stale pointer in something still live -- silent,
;; and it surfaces somewhere else entirely. A dozen sessions of one such bug is
;; what this assertion exists to prevent a repeat of.
;;
;; It is read-only and allocates nothing, so unlike a snapshot it cannot perturb
;; the run it is checking. Production carries none of it (doc/decisions/0016).
(println "gc: the generational invariant holds")
(let [r (sh "node" "-e"
            (str "import('./host/flint.mjs').then(async (m) => {"
                 "const {module} = await m.load('out/gcstress.wasm');"
                 "const i = m.instantiate(module);"
                 "i.exports.set_gc_verify_remset(1);"
                 "i.main();"
                 "console.log(JSON.stringify({start: i.exports.stat_remset_violations(),"
                 " end: i.exports.stat_remset_end_violations(),"
                 " dead: i.exports.stat_dead_half(99,0),"
                 " staleWrite: i.exports.stat_stale_set(0),"
                 " stalePush: i.exports.stat_stale_push(0),"
                 " pushes: i.exports.stat_stale_push(3),"
                 " staleRoot: i.exports.stat_stale_root(0),"
                 " rootWalks: i.exports.stat_stale_root(5),"
                 " rootSlots: i.exports.stat_stale_root(6),"
                 " collections: Number(i.exports.stat_collections())}));})"))
      out (str/trim (str (:out r) (:err r)))
      ]
  (println (str "    " out))
  (if (and (str/includes? out "\"start\":0") (str/includes? out "\"end\":0"))
    (println "  ok   no old object points at a young one without being remembered")
    (do (println "  FAIL the generational invariant was violated") (System/exit 1)))
  ;; The second half of the same idea, and the one that took a dozen sessions to
  ;; need: `is_young` spans BOTH semispaces, so a pointer left from before a flip
  ;; still tests young. Nothing that only asks `is_young` -- not the write
  ;; barrier, not the check above -- can tell it from a live one.
  (if (str/includes? out "\"dead\":0")
    (println "  ok   and no live object points into the DEAD half")
    (do (println "  FAIL a live object holds a pre-flip pointer") (System/exit 1)))
  ;; A scan finds a stale pointer somewhere; only a check AT THE WRITE names the
  ;; code that put it there. This one caught `port_send` calling `check_sendable`
  ;; -- which allocates -- with an unrooted Rust local, and then pushing the
  ;; result of that stale local as a root.
  (if (str/includes? out "\"staleWrite\":0")
    (println "  ok   and no stale pointer is ever WRITTEN into an object")
    (do (println "  FAIL a stale pointer was written into a live object") (System/exit 1)))
  ;; And one step earlier than the write: rooting a stale value is the exact
  ;; signature of a Rust local carried across an allocation, which is the bug
  ;; this whole family came from.
  (let [pushes (some-> (re-find #"\"pushes\":(\d+)" out) second parse-long)]
    (cond
      (or (nil? pushes) (zero? pushes))
      (do (println "  FAIL no push was ever checked, so its zero means nothing") (System/exit 1))
      (str/includes? out "\"stalePush\":0")
      (println (str "  ok   and no stale value is ever ROOTED (" pushes " pushes checked)"))
      :else
      (do (println "  FAIL a Rust local was carried across an allocation and then rooted")
          (System/exit 1))))
  ;; And the other end of the same question: a collection must leave no root
  ;; pointing into the half it just abandoned. Coverage is asserted before the
  ;; zero is believed -- a walk that never ran also reports zero.
  (let [walks (some-> (re-find #"\"rootWalks\":(\d+)" out) second parse-long)
        slots (some-> (re-find #"\"rootSlots\":(\d+)" out) second parse-long)]
    (cond
      (or (nil? walks) (zero? walks) (nil? slots) (zero? slots))
      (do (println "  FAIL the root walk never ran, so its zero means nothing") (System/exit 1))
      (str/includes? out "\"staleRoot\":0")
      (println (str "  ok   and no collection leaves a root in the dead half"
                    " (" walks " collections, " slots " root slots)"))
      :else
      (do (println "  FAIL a collection left a root pointing into the dead half")
          (System/exit 1)))))
