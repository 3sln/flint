(ns conjgas
  "Variadic `conj` costs the same gas on every runtime.

  Native's `conj` builtin loops over the arguments calling `Rt::conj` per
  argument, so it re-reads the collection's TYPE TAG once per element. Both
  ports dispatch on the type ONCE and then loop calling `Vec.conj` directly.
  Two shapes for one operation -- the same thing `coll.rs` records having
  fixed for `map_conj_map`, where the two algorithms had two different gas
  costs that `bin/conform-hosts` could see in a total and could not attribute.

  SETTLED 2026-09-23, having been recorded here as an open question since
  2026-09-22. The answer is that the gap does not move, and the thing that
  looked like movement was THE NAME OF THE ENTRY POINT.

  The measurement that settled it. Four sizes, entry points named alike:

      n100  wasm 67 434   jvm 67 346    gap 88
      n120  wasm 77 744   jvm 77 656    gap 88
      n400  wasm 224 362  jvm 224 274   gap 88
      n500  wasm 276 762  jvm 276 674   gap 88

  A constant 88, which is what every other gas fixture here removes by
  comparing a DIFFERENCE of two workloads rather than a total: it is what the
  two runtimes spend getting started, and it is not the program's.

  CORROBORATED BY A FIXTURE THAT SHARES NOTHING WITH THIS ONE. `bin/conform-
  hosts` already runs `slicegap`, and its row reads "the gas gap does not
  scale with the work: 88 at both sizes". Different program, different
  workload, same 88 -- which is what a fixed cost of entering the two runtimes
  should look like, and what a per-program accident should not.

  WHY THE OLD FIGURES SAID 88 AND 86. The entry points were `small` and `big`,
  and on wasm the gas depends on how long the entry point's name is:

      qq (2)  big (3)  zzz (3)       wasm 224 360
      n400 (4)  m400 (4)  zzzzz (5)  wasm 224 362

  Two steps, thresholded between three characters and four, on programs whose
  code is identical -- `(churn 400)` in every one. The jvm bills 224 274 for
  all six. So `small` and `big` sit on opposite sides of that threshold, and
  comparing them mixed a real work difference with a two-step difference in
  what they are CALLED.

  That is a divergence of its own and a small one: the same program entered by
  a longer name costs two more steps on wasm and the same on the jvm. It is
  recorded here rather than fixed because it is about the entry path, not
  about `conj`, and because two steps on a 224 000-step program is the kind of
  thing that only matters when something else is being measured against it --
  which is exactly what happened.

  The entry points below are named `small` and `large`, five characters each,
  so the comparison is of the work and nothing else. Verified: 67 434/67 346
  and 224 362/224 274, gap 88 both."
  (:require [flint.thread :as th]))

(defn- churn [n]
  (loop [i 0 v []]
    (if (< i n)
      (recur (inc i) (conj v 1 2 3 4 5 6 7 8))
      (count v))))

(defn small [_] (str (churn 100)))
(defn large [_] (str (churn 400)))
