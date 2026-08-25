(ns construe.bench.xrt0
  "One point on the cross-runtime benchmark family (`doc/decisions/0018`).

  The iteration count is BAKED IN and the entry takes no arguments, because
  wasmtime and wasm3 invoke an export from the command line and cannot drive
  flint's `arg_alloc`/`arg_push` ABI. Every engine therefore runs the same
  module bytes with no host code at all, which is 0018's requirement -- a
  per-engine harness would be measuring harnesses."
  (:require [construe.bench.parse :as p]))

(defn main [_] (str (p/run 0)))
