;; `bin/flint`, with the emitter counting what it specialises. Used by
;; `test/loop_types.clj`; run on its own it takes `bin/flint`'s arguments:
;;
;;   PROBE_OUT=counts.edn bb test/loop_types_probe.clj :src dir :fn ns/main :out x.wasm
;;
;; For every two-operand arithmetic or comparison builtin that HAS an integer
;; opcode, it records [enclosing-fn builtin outcome], where outcome is
;; `:specialised` or the operand tags that stopped it. Answers cannot show any
;; of this -- a missed specialisation computes the same number -- so this is
;; the only way a test can see the inference.
;;
;; The hook goes in BEFORE `bin/flint` is loaded, and `bin/flint`'s own require
;; of an already-loaded namespace does not reload it, so the real driver runs
;; with no copy of it here to drift.
(require '[babashka.fs :as fs])
(def root (str (fs/parent (fs/parent (fs/real-path *file*)))))
(babashka.classpath/add-classpath (str root "/src:" root "/lib"))
(require '[flint.emitter :as em])

(def counts (atom {}))
(def fn-stack (atom ()))

(alter-var-root #'em/emit-fn-object
  (fn [f] (fn [ctx node]
            (swap! fn-stack #(cons (if (and (:name node) (not= "fn" (str (:name node))))
                                     (str (:name node)) (first %)) %))
            (try (f ctx node) (finally (swap! fn-stack rest))))))

(alter-var-root #'em/emit
  (fn [f] (fn [ctx buf node tail?]
            (when (and (= :native (:op node)) (= 2 (count (:args node)))
                       (get em/int-specialised (:name node)))
              (let [ts (mapv :tag (:args node))]
                (swap! counts update [(first @fn-stack) (:name node)
                                      (if (every? #(= :int %) ts) :specialised ts)]
                       (fnil inc 0))))
            (f ctx buf node tail?))))

;; `bin/flint` ends in System/exit, so the counts leave by a shutdown hook.
(.addShutdownHook (Runtime/getRuntime)
                  (Thread. (fn [] (spit (System/getenv "PROBE_OUT") (pr-str @counts)))))

(load-file (str root "/bin/flint"))
