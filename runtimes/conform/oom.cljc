(ns oom
  "The catchable memory-limit error (`DECISIONS.md#resource-limits`), proved
   across all four runtimes.

   Native already raises it: `test/limits.mjs` drives the wasm runtime
   through `set_memory_limit` and catches it there. `ROADMAP.md` recorded
   2026-10-05 that neither the JVM nor the CLR port raised anything -- a
   failed allocation read back as `nil`, so a program that filled its heap
   got a WRONG ANSWER instead of a catchable error
   (`DECISIONS.md#resource-limits`, \"a failed allocation must not read as
   nil\").

   `eat`/`work` are the SAME shapes `test/limits.mjs` already uses for the
   wasm side, so the fixture this namespace compiles is driven by three
   harnesses on a 2 MiB nursery / 6 MiB ceiling sandbox -- `cli/src/main.rs`'s
   `oom_tests` (native), `runtimes/jvm/test/RtOom.java` (the JVM, called
   `java -cp runtimes/jvm/classes RtOom <image> eat|work`) and the CLR's
   `Conform --rt-oom <image> eat|work` -- called directly, not through
   `bin/conform-hosts`, which this namespace is not wired into: see those
   three files for why.")

;; Large objects are born in the old generation, so this fills the cap
;; directly instead of making the collector copy a growing live set.
(defn chunk [] (flint.rt/str-join (mapv (fn [i] "0123456789abcdef") (range 4096))))
(defn eat [] (loop [acc [] i 0] (if (< i 4000) (recur (conj acc (chunk)) (inc i)) (count acc))))

;; The control: stays comfortably under a 6 MiB ceiling.
(defn work [] (loop [i 0 acc 0] (if (< i 1000) (recur (inc i) (+ acc i)) acc)))

(defn main [args]
  (let [what (first args)]
    (cond
      (= what "eat")
      (try (str (eat)) (catch Throwable e (str (ex-message e) " " (pr-str (ex-data e)))))
      (= what "work") (str (work))
      :else "?")))
