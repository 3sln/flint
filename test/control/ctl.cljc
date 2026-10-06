;; THE CONTROL PLANE'S PROTOCOL, driven by a host (`DECISIONS.md#the-control-plane-is-the-runtimes`).
;; `cli/src/control_test.rs` binds a port, calls these, and unbinds and closes.
;; It was `test/system.cljc`, which drove `flint.system/serve` over a local
;; channel; there is no `flint.system` now, so the protocol is asked of the
;; RUNTIME, at the boundary a host sees.
(ns ctl)
(defn greet [a b] (str "hi " a " and " b))
(defn boom [] (throw (ex-info "inner blew up" {})))
(defn tally [] 7)
;; Named so the shake keeps them: a string does not keep a var alive.
(defn main [_] (str (count [greet boom tally])))
