(ns lang.strings
  "Strings, ropes and code points.

  flint stores a string in one of four shapes -- inline in the value, interned
  on the heap, plain on the heap, or a rope -- and which one you get depends on
  length and on how the string was built. Nothing in the language exposes that
  choice, and these checks are the statement that nothing does: the same
  question asked of a 3-byte string and of a 4000-byte rope has to get the same
  KIND of answer."
  (:require [flint.check :refer [expect]]))

(defn- long-string [n]
  (loop [i 0 acc ""] (if (< i n) (recur (inc i) (str acc "abcdefghij")) acc)))

(defn ^:flint.check/test str-concatenation []
  (expect = "" (str))
  (expect = "a" (str "a"))
  ;; `nil` contributes NOTHING, which is the one that catches people: `str`
  ;; is not `pr-str` and never renders a nil as "nil".
  (expect = "a1:k-true" (str "a" 1 ":k" nil "-" true))
  (expect = "" (str nil))
  (expect = "nil" (pr-str nil))
  (expect = "abc" (str "a" "b" "c")))

(defn ^:flint.check/test count-is-code-points []
  ;; Not bytes and not UTF-16 units. flint is UTF-8 and the JVM is UTF-16, so
  ;; an astral character is exactly where two hosts disagree unless the
  ;; semantics are pinned to code points -- which is why this is here and not
  ;; in a JVM-specific file.
  (expect = 0 (count ""))
  (expect = 3 (count "abc"))
  (expect = 3 (count "aez"))
  (expect = 5 (count "hello")))

(defn ^:flint.check/test tiers-are-invisible []
  ;; The same string built two ways -- as a literal, and by concatenation past
  ;; every tier boundary -- has to be equal, count the same and hash the same.
  ;; A rope that compared by identity, or an interned string that compared by
  ;; pointer against a plain one, would fail exactly here and nowhere else.
  (let [built (str "hello" " " "world")]
    (expect = "hello world" built)
    (expect = (count "hello world") (count built))
    (expect = (hash "hello world") (hash built)))
  ;; Past FLAT_MAX (1024), where concatenation stops producing a flat string
  ;; and starts producing a rope.
  (let [big (long-string 300)]
    (expect = 3000 (count big))
    (expect = big (str big))
    (expect = (hash big) (hash (str "" big)))
    (expect = "a" (subs big 0 1))
    (expect = "j" (subs big 2999 3000))))

(defn ^:flint.check/test indexing-a-rope-is-indexing []
  ;; A rope is a tree, so `nth` has to descend it rather than walk it. The
  ;; assertion here is about the ANSWER; `test/scaling.clj` is what pins the
  ;; cost. Both are needed: a correct O(n) index passes this and a fast wrong
  ;; one passes that.
  (let [big (long-string 300)]
    (expect = \a (nth big 0))
    (expect = \e (nth big 1504))
    (expect = \j (nth big 2999))))

(defn ^:flint.check/test subs-boundaries []
  (expect = "" (subs "abc" 0 0))
  (expect = "abc" (subs "abc" 0 3))
  (expect = "bc" (subs "abc" 1))
  (expect = "b" (subs "abc" 1 2)))

(defn ^:flint.check/test printing-quotes-only-when-asked []
  (expect = "a" (str "a"))
  (expect = "\"a\"" (pr-str "a"))
  (expect = ":k" (pr-str :k))
  (expect = "nil" (pr-str nil))
  (expect = "[1 \"b\"]" (pr-str [1 "b"])))

(defn ^:flint.check/test names-and-namespaces []
  (expect = "a" (name :a))
  (expect = "a" (name :my.ns/a))
  (expect = "my.ns" (namespace :my.ns/a))
  (expect nil? (namespace :a)))
