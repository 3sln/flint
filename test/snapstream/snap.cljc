;; THE PROGRAM A HOST-REQUESTED SNAPSHOT IS TAKEN OF (`DECISIONS.md#snapshots`),
;; one source for every runtime: `cli/src/snapstream_test.rs` (native),
;; `runtimes/jvm/test/RtSnapStream.java` and the CLR's `--rt-snap-stream`.
;;
;; `counter` is the state that must survive the trip; `ballast` makes the live
;; set several 64 KiB chunks long; the `steal` family is the guest-bypass probe,
;; each a different ROUTE to the same builtin -- direct, value position, and
;; from a thread the guest spawned -- and every one must be refused.
(ns snap (:require [flint.port :as port] [flint.thread :as thread]))
(def counter (atom 0))
;; BALLAST, so the live set is several chunks long rather than one.
(def ballast (vec (range 30000)))
(defn bump [] (swap! counter inc))
(defn sizes [] (count ballast))
(defn- refused [f] (try (f) :taken (catch Throwable e (ex-message e))))
(defn steal [] (refused (fn [] (flint.rt/snapshot-export))))
(defn steal-chunk [] (refused (fn [] (flint.rt/snapshot-chunk 0 16))))
(defn steal-by-value [] (let [f flint.rt/snapshot-export] (refused f)))
(defn steal-on-a-thread []
  (thread/join (thread/spawn (fn [] (refused (fn [] (flint.rt/snapshot-export)))))))
(defn ask-on-the-system-port []
  (port/send (flint.rt/system-port) {:op :snapshot :port (flint.rt/system-port)})
  :sent)
;; NAMES every function a host calls, so reachability keeps them in the image.
(defn main [_]
  (str (count [bump sizes steal steal-chunk steal-by-value steal-on-a-thread
               ask-on-the-system-port])))
