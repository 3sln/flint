(ns regex
  "The Pike VM (`doc/decisions/0012`), which each port implements itself.

  The PATTERN is parsed by `flint.nfa`, in cljc, so every host runs the same
  parser; only the matcher is ported. These cases are chosen where a host's own
  engine would disagree if one had been bolted on instead:

  * `.` and newline. Java excludes it without DOTALL and so does flint; some
    engines do not.
  * Leftmost-FIRST, not leftmost-longest. `(a|ab)` against \"ab\" finds \"a\"
    when searching, and `ab` under `re-matches`, which must reach the end.
  * An empty match still advances, or `split` never terminates.
  * A group that did not participate is nil, not an empty string.
  * Code points, not UTF-16 units: a span is what a caller slices with."
  (:require [clojure.string :as s]))

(defn main [_]
  (pr-str
   {:find      [(re-find #"b+" "abbbc") (re-find #"z" "abc")]
    :groups    [(re-find #"(\d+)-(\d+)" "x 12-34 y")
                (re-find #"(a)?(b)" "b")]
    :matches   [(re-matches #"a|ab" "ab") (re-matches #"a|ab" "a")
                (re-matches #"\d+" "123") (re-matches #"\d+" "12x")]
    :seq       [(vec (re-seq #"\w+" "one two  three"))
                (vec (re-seq #"a*" "bab"))]
    :split     [(s/split "a1b22c" #"\d+") (s/split "a,b,,c" #",")
                (s/split "abc" #"")]
    :replace   [(s/replace "a1b2" #"\d" "#")
                (s/replace "hello world" #"o" "0")]
    ;; `.` stops at a newline; `[\s\S]` does not. This is the case a host
    ;; engine defaulting to DOTALL would get wrong.
    :dot       [(re-find #"a.c" "a\nc") (re-find #"a.c" "abc")
                (re-find #"a[\s\S]c" "a\nc")]
    :anchors   [(re-find #"^ab" "abc") (re-find #"^bc" "abc")
                (re-find #"bc$" "abc") (re-find #"\bcat\b" "a cat here")
                (re-find #"\bcat\b" "concatenate")]
    :classes   [(re-find #"[a-c]+" "xxabcxx") (re-find #"[^a-c]+" "abxyab")
                (re-find #"\s+" "a \t b") (re-find #"\S+" "  hi  ")]
    :repeat    [(re-find #"a{2,3}" "aaaa") (re-find #"a+?b" "aaab")
                (re-find #"(ab)+" "ababab")]
    ;; Linear, not exponential: a backtracker takes exponential time on this.
    :nested    [(boolean (re-find #"(a+)+b" "aaaaaaaaaaaaaaaaaaaaaaaac"))]
    ;; Past the BMP, where a UTF-16 offset and a code-point index differ.
    :astral    [(re-find #"\w+" "aé z") (count (re-find #"." "é"))]}))
