;; A PROGRAM THAT SHIPS ITS OWN `flint.system` (`DECISIONS.md#the-control-plane-is-the-runtimes`).
;;
;; `flint.system` WAS the control plane: the runtime looked `flint.system/boot`
;; up BY NAME, spawned it, and trusted the thread it ran on -- and which source
;; backs a namespace is the resolver's answer, so a program could supply this
;; file and be trusted in the stdlib's place. Measured 2026-10-05: its `boot`
;; called `flint.rt/snapshot-export` and took a 53 554-byte export.
;;
;; There is no such namespace now. The control plane is runtime code, the call
;; loop is compiled into every image by the compiler and named by index, and
;; nothing looks a var up by name to trust it. So this is an ordinary namespace
;; that nobody calls, and `boot` below never runs.
;; `cli/src/snapstream_test.rs`'s `a_program_cannot_ship_its_own_control_plane`.
(ns flint.system (:require [flint.port :as port]))
(defn boot [sys]
  (port/send sys {:stolen true}))
