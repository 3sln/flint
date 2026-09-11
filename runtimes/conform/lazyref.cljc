(ns lazyref
  "DELAYS, VOLATILES AND OPAQUE VALUES -- three of the seventeen builtins
  `bin/check-builtin-coverage` lists as unwatched.

  `flint/delay?`, `flint/realized?`, `flint/volatile?` and `flint/opaque`
  were reached by no conformance program. Each is a small surface with an
  edge that three hand-written implementations can differ on: whether a
  delay counts as realised before it is forced, what a delay whose thunk
  THREW remembers, whether a volatile is an atom to `deref`, and whether an
  opaque value prints and compares as itself.

  The delay-that-threw case is here because it has been wrong before: both
  ports cached a failed thunk's nil and answered it forever, where native
  left the delay unforced and retryable (`a-vec-of-values-is-not-a-root`, and the atoms work that
  followed it)."
  (:require [clojure.string :as str]
            [flint.core :refer [opaque opaque? opaque-label]]))

(defn- attempt [f]
  (try (str (f)) (catch Exception e (str "threw " (flint.rt/ex-kind e) " " (ex-message e)))))

(defn main [_]
  (let [d (delay (+ 1 2))
        forced (delay (+ 3 4))
        _ @forced
        bad (delay (throw (ex-info "no" {})))
        v (volatile! 1)
        o (opaque)
        lbl (opaque "a-label")]
    (pr-str
     {;; REALISED IS ABOUT THE THUNK, not about the value being present.
      :delay [(delay? d) (delay? forced) (delay? 1) (delay? nil)
              (realized? d) (realized? forced)]
      :force [(attempt #(deref d)) (realized? d) (attempt #(force forced))
              ;; `force` of a NON-delay answers the value itself.
              (attempt #(force 7))]
      ;; A THUNK THAT THREW. Whatever the answer, it must be the same answer
      ;; twice and the same on every runtime.
      :threw [(attempt #(deref bad)) (attempt #(deref bad)) (realized? bad)]
      :volatile [(volatile? v) (volatile? (atom 1)) (volatile? 1)
                 (attempt #(deref v)) (attempt #(vreset! v 2)) (attempt #(deref v))]
      ;; AN OPAQUE VALUE IS ITSELF AND NOTHING ELSE (`opaque-values`): minting one
      ;; grants nothing, two are never equal, and one equals itself.
      :opaque [(opaque? o) (opaque? lbl) (opaque? 1) (opaque? nil)
               (= o o) (= o lbl) (= o (opaque))
               (opaque-label o) (opaque-label lbl) (opaque-label 1)]
      ;; Printing an opaque value must not leak anything but the label.
      :opaque-print [(str/includes? (pr-str lbl) "a-label")
                     (attempt #(str o))]})))
