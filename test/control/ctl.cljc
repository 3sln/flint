;; THE CONTROL PLANE'S PROTOCOL, driven by a host (`DECISIONS.md#the-control-plane-is-the-runtimes`).
;; `cli/src/control_test.rs` binds a port, calls these, and unbinds and closes.
;; It was `test/system.cljc`, which drove `flint.system/serve` over a local
;; channel; there is no `flint.system` now, so the protocol is asked of the
;; RUNTIME, at the boundary a host sees.
(ns ctl)
(defn greet [a b] (str "hi " a " and " b))
(defn boom [] (throw (ex-info "inner blew up" {})))
(defn tally [] 7)
;; A reply CARRYING METADATA (`DECISIONS.md#the-control-plane-is-the-runtimes`
;; amendment, `DECISIONS.md#the-codec-is-guest-code`). Per-value, through
;; `WireMeta`'s metadata route rather than `extend-method`, so this proves the
;; general mechanism rather than one kind's default. `flint.port/send` asks
;; `flint.protocols/-wire-meta`, which reads this value's own metadata first;
;; the call loop answering through `flint.port/send` (and not its own
;; builtin re-implementation) is what makes the metadata cross at all.
(defn tagged []
  (with-meta {:a 1} {'flint.protocols/-wire-meta (fn [_] {:origin "ctl"})}))
;; THE CONTROL: the same shape, no metadata. If a host saw metadata here, the
;; test would be asserting something `WireMeta`'s default does not promise.
(defn untagged [] {:a 1})
;; Named so the shake keeps them: a string does not keep a var alive.
(defn main [_] (str (count [greet boom tally tagged untagged])))
