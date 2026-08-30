(ns lang.hardening
  "The standard library refusing bad usage, and saying what was bad about it.

  Every check here is inside `#?(:flint/check ...)` in `clojure.core`, so none
  of it exists under `:optimize [perf]`. These are the assertions that it
  exists in a build that keeps it, and that what it says is worth reading --
  the failures were all silent or nearly silent before, which is the kind of
  bug that costs an afternoon and leaves no trace of where it started."
  (:require [flint.check :refer [expect]]))

(defn- boom [f]
  (try (f) nil (catch Throwable e (ex-message e))))

(defn ^:flint.check/test subs-says-which-end-was-wrong []
  ;; `bad substring range` was true and useless. The two things a reader needs
  ;; are which end was out and what the length actually was.
  (expect = "subs start is negative: -1" (boom (fn [] (subs "abc" -1 2))))
  (expect = "subs end is 9, past the end of a string of 5 characters"
          (boom (fn [] (subs "hello" 0 9))))
  (expect = "subs start 4 is past its end 2" (boom (fn [] (subs "hello" 4 2))))
  (expect = "subs start must be an integer, got keyword"
          (boom (fn [] (subs "hello" :a))))
  ;; Singular, because a message that says "1 characters" is a message nobody
  ;; proofread.
  (expect = "subs end is 3, past the end of a string of 1 character"
          (boom (fn [] (subs "a" 0 3)))))

(defn ^:flint.check/test partition-refuses-a-size-that-never-ends []
  ;; `(partition 0 coll)` took nothing, found nothing missing, dropped nothing
  ;; and recurred: an infinite sequence of nils, produced lazily, so the
  ;; symptom appeared wherever it was finally realised rather than here.
  (expect some? (boom (fn [] (vec (take 3 (partition 0 [1 2 3]))))))
  (expect some? (boom (fn [] (vec (take 3 (partition-all 0 [1 2 3]))))))
  ;; A step of zero re-reads the same prefix forever, which is the same bug
  ;; wearing the other argument.
  (expect some? (boom (fn [] (vec (take 3 (partition 2 0 [1 2 3]))))))
  (expect true? (clojure.string/includes?
                 (boom (fn [] (vec (take 3 (partition 0 [1 2])))))
                 "would never consume the collection")))

(defn ^:flint.check/test the-checks-do-not-change-good-answers []
  ;; The other half, and the half a check is most likely to break: everything
  ;; legal still works, boundaries included.
  (expect = "el" (subs "hello" 1 3))
  (expect = "" (subs "hello" 5))
  (expect = "" (subs "" 0 0))
  (expect = "hello" (subs "hello" 0 5))
  (expect = [[1 2] [3 4]] (mapv vec (partition 2 [1 2 3 4])))
  (expect = [[1 2] [3 4]] (mapv vec (partition 2 [1 2 3 4 5])))
  (expect = [[1 2] [3 4] [5]] (mapv vec (partition-all 2 [1 2 3 4 5])))
  (expect = [[1 2] [2 3]] (mapv vec (partition 2 1 [1 2 3]))))
