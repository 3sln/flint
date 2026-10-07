(ns selfjoin-min
  "`selfjoin`, with nothing else in `main`'s body.

  No `str`, no `pr-str` -- just the call that must be refused. `selfjoin`
  requires `flint.thread` too, so this does not shrink the initialiser cost
  that bounds the fixture; it exists to keep `main` itself from adding any
  margin of its own, so this is as small a program as this vocabulary lets
  self-join be exercised in. See `bin/conform-hosts` and
  `fix_adopted_thread_zero` (`runtime/src/vm.rs`) for why the margin matters:
  a self-join refusal that depends on an initialiser being big enough to
  trip a checkpoint is one accident away from silently not firing."
  (:require [flint.thread :as th]))

(defn main [_] (th/join (th/self)))
