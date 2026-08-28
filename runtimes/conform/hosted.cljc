(ns hosted
  "The builtins a host has to supply itself: maths, code-point string work,
  byte strings, volatiles and dynamic bindings.

  Every one of these is a place where a host's own library is CLOSE to what
  flint means and not the same. `Math/rint` rounds half to even and
  `Math.Round` defaults to the same, but only because it was said explicitly;
  `IndexOf` finds \"a\" inside \"A\" under some cultures unless it is ordinal;
  and both hosts count UTF-16 units where flint counts code points. None of
  those is caught by the builtin merely existing, which is why they are
  exercised here rather than counted."
  (:require [clojure.math :as m]
            [clojure.string :as s]))

(def ^:dynamic *depth* :root)

(defn- nested []
  (binding [*depth* :inner] [*depth* (do-outer)]))
(defn- do-outer [] *depth*)

(defn main [_]
  (pr-str
   {:math [(m/sqrt 16) (m/floor 2.7) (m/ceil 2.1) (m/pow 2 10)
           (m/abs -3.5) (m/signum -2.0) (m/hypot 3 4)]
    ;; Half to EVEN, both ways: 2.5 rounds DOWN to 2.0 and 3.5 rounds UP to
    ;; 4.0. A host defaulting to half-away-from-zero answers 3.0 for the first.
    :rounding [(m/rint 2.5) (m/rint 3.5) (m/rint -2.5)]
    ;; Round trip through the bits, which pins the layout: the same double in,
    ;; the same double out, and the same integer in between on every host.
    :bits [(flint.rt/double-bits 1.5)
           (flint.rt/bits->double (flint.rt/double-bits 1.5))]

    ;; By code point. "é" is one code point in two UTF-8 bytes, so `subs` and
    ;; `str-bytes` must disagree with each other and agree across hosts.
    :subs [(subs "hello" 1 3) (subs "héllo" 1 3) (subs "abc" 2)]
    :index [(s/index-of "hello" "ll") (s/index-of "hello" "z")
            (s/index-of "abcabc" "b" 2)]
    :case [(s/upper-case "aBc") (s/lower-case "AbC")]
    :join [(s/join ["a" "b" "c"]) (s/join [])]
    :parse [(parse-long "42") (parse-long "nope") (parse-double "1.5")]

    ;; `str-bytes` answers the BYTES, not how many there are -- the compiler's
    ;; own image writer counts the result, so returning a count made it count a
    ;; number, and the error surfaced several frames away as "14 cannot be
    ;; counted". A non-ASCII character is what makes the byte count differ from
    ;; the character count, which is the whole point of asking.
    :bytes [(flint.rt/str-bytes "abc") (flint.rt/str-bytes "")
            (count (flint.rt/str-bytes "héllo"))
            (flint.rt/bytes->str (flint.rt/str-bytes "héllo ✓"))]

    ;; A volatile is an atom without the atomicity, and `vreset!` answers the
    ;; NEW value rather than the old one.
    :volatile (let [v (volatile! 1)]
                [@v (vreset! v 2) @v])

    ;; A dynamic binding is a stack: the inner value is visible to a function
    ;; called from inside the binding, and the root is back afterwards.
    :dynamic [(nested) *depth*]}))
