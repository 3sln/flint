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
;; `flint.compiler.emitter` from inside the driver does not reload it and undo them
;; (true of this file exactly as it was true of the old one).
;;
;; THE SHUTDOWN HOOK USED TO WRITE `(pr-str @counts)` ALONE. A JVM-wide
;; shutdown hook fires on every exit path -- a clean compile, a compile the
;; driver itself rejects with `System/exit 1`/`2`, or `require`ing
;; `flint.compiler.emitter` throwing because this file's own hooks never attach -- and
;; in every one of those failure cases `counts` is still `{}`, the same value
;; a program with no integer-opcode arithmetic would leave. `test/loop_types.clj`
;; read that `{}` as "every row missed its specialisation" and failed nine
;; checks that all blamed loop-type inference, when the actual fault was this
;; file never getting to watch a compile at all. So the hook now writes
;; `{:ran? :error :counts}`: `:ran?` is true only once the hooks are actually
;; installed, `:error` carries the first `Throwable` seen (hook installation
;; or any later uncaught exception in this JVM, since `flint.driver.main`'s
;; own compile errors are caught internally and never reach here), and
;; `:counts` is `@counts` as before. The shutdown hook is registered as the
;; very FIRST form in this file, before the `require` that can itself throw,
;; so a failure that early is still reported rather than leaving `PROBE_OUT`
;; unwritten and `test/loop_types.clj`'s `slurp` throwing a bare
;; `FileNotFoundException` that names neither this file nor the cause.
(def ran? (atom false))
(def probe-error (atom nil))
(def counts (atom {}))
(def fn-stack (atom ()))

(.addShutdownHook (Runtime/getRuntime)
                  (Thread. (fn []
                             (spit (System/getenv "PROBE_OUT")
                                   (pr-str {:ran? @ran? :error @probe-error
                                           :counts @counts})))))

;; Any uncaught exception in this JVM after this point -- in hook
;; installation below, or anywhere in the compile the hooks go on to watch --
;; is captured here rather than only appearing as a stack trace on stderr the
;; shutdown hook's own `PROBE_OUT` says nothing about.
(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ _ ex]
      (reset! probe-error (str ex)))))

;; A TOP-LEVEL FORM OF ITS OWN, not nested inside the `try` below. `em/foo`
;; is resolved by the COMPILER against the current namespace's aliases, at
;; the time THAT FORM is compiled -- `clojure -i` compiles and runs one
;; top-level form at a time, same as `load-file`, so the alias this creates
;; is visible to the forms after it only because it is not also INSIDE the
;; form that creates it. Wrapping this `require` in the same `try` as the
;; `alter-var-root` calls below (an earlier version of this file did) made
;; the whole `try` ONE form the compiler analyses before running ANY of it,
;; so `em` did not exist yet when `em/emit-fn-object` was being resolved --
;; "Unable to resolve var: em/emit-fn-object in this context", a compile-time
;; error `reset!`-ing nothing, caught only by the top-level try below
;; swallowing it as a generic failure. Found by breaking this probe on
;; purpose (nesting the require again) and watching `test/loop_types.clj`
;; report "PROBE DID NOT RUN" with that exact message instead of nine
;; inference-looking failures.
(try
  (require '[flint.compiler.emitter :as em])
  (catch Throwable t
    (reset! probe-error (str t))))

(try
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

  (reset! ran? true)
  (catch Throwable t
    (reset! probe-error (str t))))

;; `flint.driver.main`'s compile path ends in `System/exit`, so the counts
;; leave by the shutdown hook above, exactly as they did for `bin/flint`'s own
;; `System/exit` before this driver existed.
