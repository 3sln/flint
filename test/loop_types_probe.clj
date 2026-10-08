;; `bin/flint`, with the emitter counting what it specialises. Used by
;; `test/loop_types.clj`; run on its own it takes `bin/flint`'s arguments, as
;; its `FLINT_PRELOAD` (see that var's note in `bin/flint`):
;;
;;   PROBE_OUT=counts.edn FLINT_PRELOAD=test/loop_types_probe.clj ./bin/flint :src dir :fn ns/main :out x.wasm
;;
;; For every two-operand arithmetic or comparison builtin that HAS an integer
;; opcode, it records [enclosing-fn builtin outcome], where outcome is
;; `:specialised` or the operand tags that stopped it. Answers cannot show any
;; of this -- a missed specialisation computes the same number -- so this is
;; the only way a test can see the inference.
;;
;; THIS USED TO `load-file` `bin/flint` AFTER installing its hooks, relying on
;; `bin/flint` being Clojure source babashka could run in the SAME process as
;; this one. `bin/flint` is now a thin `sh` wrapper over a separate JVM
;; (`clojure -M -m flint.driver.main`, migration step 1.2) -- `load-file`ing
;; it is a read error on its first `#!`/`#` line, and babashka cannot run the
;; driver JVM at all. `FLINT_PRELOAD` is `bin/flint`'s replacement extension
;; point: `clojure -i`'s this file, in the driver's own JVM, before
;; `flint.driver.main` is required -- so the hooks below are installed before
;; the compile they watch ever runs, and `require`ing an already-loaded
;; `flint.emitter` from inside the driver does not reload it and undo them
;; (true of this file exactly as it was true of the old one).
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

;; `flint.driver.main`'s compile path ends in `System/exit`, so the counts
;; leave by a shutdown hook, exactly as they did for `bin/flint`'s own
;; `System/exit` before this driver existed.
(.addShutdownHook (Runtime/getRuntime)
                  (Thread. (fn [] (spit (System/getenv "PROBE_OUT") (pr-str @counts)))))
