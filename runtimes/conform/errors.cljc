(ns errors
  "WHAT GOES WRONG, and what it is called when it does.

  Two divergences turned up by accident while probing other things: `(long
  \"x\")` threw `ClassCastException` on native and `IllegalArgumentException`
  on both ports, and native's message described a second operand that did not
  exist. Neither was found by a test about errors, because there was no test
  about errors -- every conformance program here asserts what a runtime does
  when it WORKS.

  The class is the part a `catch` selects on, so a divergence in it changes
  which handler runs. The message is what a person reads. Both are recorded
  for every case, because the first version of the probe that found the
  `to-long` divergence compared only the message and saw half of it.

  These are the ORDINARY misuses -- wrong type, bad index, empty collection,
  divide by zero -- not exotic ones. If three runtimes disagree about what
  `(count 1)` throws, they disagree about something a program hits by
  accident on its first day."
  (:require [clojure.string :as str]))

(defn- boom
  "The class and the message, or the value if nothing was thrown."
  [f]
  (try (let [v (f)] (str "ok " (pr-str v)))
       (catch Exception e (str (flint.rt/ex-kind e) ": " (ex-message e)))))

(defn main [_]
  (pr-str
   {;; ARITHMETIC on things that are not numbers, and by zero.
    :arith [(boom #(+ 1 "x")) (boom #(- nil 1)) (boom #(* [1] 2))
            (boom #(/ 1 0)) (boom #(quot 1 0)) (boom #(rem 1 0))
            (boom #(/ 1.0 0))]
    ;; COLLECTIONS asked for what they do not have.
    :coll [(boom #(nth [1 2] 5)) (boom #(nth [1 2] -1))
           (boom #(count 1)) (boom #(conj 1 2))
           (boom #(assoc [1] 5 :x)) (boom #(first 1))]
    ;; STRINGS, where the index is in code points and the range is checked.
    :strings [(boom #(subs "abc" 1 9)) (boom #(subs "abc" 5))
              (boom #(subs "abc" 2 1)) (boom #(str/upper-case 1))]
    ;; CALLING something that is not callable, and with the wrong count.
    ;; AN ARITY ERROR IS AN EXCEPTION, which on both ports it was not: they
    ;; threw the fixnum -1 with a note saying the real message needed strings,
    ;; so `catch` did not catch it and the program died with "the program threw
    ;; an integer". And the CALL sites returned straight out of the loop after
    ;; a failed `enter` instead of unwinding, so even a real exception skipped
    ;; every handler the guest had installed.
    :calls [(boom #((fn [a] a))) (boom #((fn [a] a) 1 2))]
    ;; CALLING A NON-FUNCTION, message and all. Native named the value and the
    ;; call path while the ports said "object type inline" -- an implementation
    ;; detail for the value and nothing for the path. All three walk the frames
    ;; now and describe the value the same generated way, so the whole message
    ;; is comparable rather than just the class.
    :not-callable [(boom #(1 2)) (boom #(nil 1))]
    ;; REFERENCE types asked to be something else.
    :refs [(boom #(deref 1)) (boom #(swap! 1 inc)) (boom #(reset! [1] 2))]
    ;; MAPS and keywords.
    :maps [(boom #(get 1 :k)) (boom #(keys [1 2])) (boom #(vals "x"))]
    ;; AND THE THROW A PROGRAM MAKES ITSELF, which has to keep its data.
    :own [(boom #(throw (ex-info "mine" {:a 1})))
          (boom #(ex-data (ex-info "mine" {:a 1})))
          (boom #(throw (ex-info "plain" {})))]}))
