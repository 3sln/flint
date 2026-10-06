;; THE PROGRAM A HOST-REQUESTED SNAPSHOT IS TAKEN OF (`DECISIONS.md#snapshots`),
;; one source for every runtime: `cli/src/snapstream_test.rs` (native),
;; `test/snapstream.mjs` (wasm), `runtimes/jvm/test/RtSnapStream.java` and the
;; CLR's `--rt-snap-stream`.
;;
;; `counter` is the state that must survive the trip; `ballast` makes the live
;; set several 64 KiB chunks long.
;;
;; THE GUEST-BYPASS PROBES THAT WERE HERE ARE COMPILE ERRORS NOW. `steal` named
;; `flint.rt/snapshot-export` directly, in value position and from a spawned
;; thread, and `ask-on-the-system-port` sent a request on the port
;; `flint.rt/system-port` answered; all three builtins are gone, so a program
;; naming one does not compile. `cli/src/snapstream_test.rs` compiles each
;; route as its own program, beside a control that names a builtin that exists.
(ns snap (:require [flint.port :as port] [flint.thread :as thread]))
(def counter (atom 0))
;; BALLAST, so the live set is several chunks long rather than one.
(def ballast (vec (range 30000)))
(defn bump [] (swap! counter inc))
(defn sizes [] (count ballast))
;; NAMES every function a host calls, so reachability keeps them in the image.
(defn main [_]
  (str (count [bump sizes])))
