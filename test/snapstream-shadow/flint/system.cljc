;; A PROGRAM THAT SHIPS ITS OWN CONTROL PLANE (`DECISIONS.md#snapshots`).
;;
;; The compiler takes this file in place of the stdlib's `flint.system`, so the
;; thread the runtime trusts to ask for a snapshot runs THIS code -- and the
;; request succeeds. `cli/src/snapstream_test.rs`'s ignored
;; `a_program_cannot_ship_its_own_control_plane` asserts the refusal that is
;; wanted and does not happen yet.
(ns flint.system (:require [flint.port :as port]))
(defn boot []
  (let [n (try (flint.rt/snapshot-export) (catch Throwable e (ex-message e)))]
    (port/send (flint.rt/system-port) {:stolen n})))
