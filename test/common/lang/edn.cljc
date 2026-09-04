(ns lang.edn
  "`clojure.edn`, flint's own reader, exercised as flint rather than as host code.

  It had no fixture here at all until the metadata question forced one: the
  only thing in the tree that required it was `flint.deps`, so every claim its
  docstring made was a reading rather than a measurement."
  (:require [clojure.edn :as edn]
            [flint.check :refer [expect]]))

(defn ^:flint.check/test reads-the-scalars []
  (expect = nil (edn/read-string "nil"))
  (expect = true (edn/read-string "true"))
  (expect = false (edn/read-string "false"))
  (expect = 42 (edn/read-string "42"))
  (expect = -7 (edn/read-string "-7"))
  (expect = "hi" (edn/read-string "\"hi\""))
  (expect = :k (edn/read-string ":k"))
  (expect = :n/k (edn/read-string ":n/k"))
  (expect = 'sym (edn/read-string "sym"))
  (expect = 'n/sym (edn/read-string "n/sym")))

(defn ^:flint.check/test reads-the-collections []
  (expect = [1 2] (edn/read-string "[1 2]"))
  (expect = '(1 2) (edn/read-string "(1 2)"))
  (expect = {:a 1} (edn/read-string "{:a 1}"))
  (expect = #{1 2} (edn/read-string "#{1 2}"))
  ;; `#_` drops the next form, including inside a collection.
  (expect = [1 3] (edn/read-string "[1 #_2 3]"))
  ;; Commas are whitespace.
  (expect = [1 2] (edn/read-string "[1, 2]")))

;; --------------------------------------------------------------- metadata
;;
;; METADATA IS NOT IN THE EDN SPEC, and `clojure.edn` reads it anyway. That
;; combination is the whole reason these tests exist.
;;
;; What was here before was neither of the two defensible answers. `^` is not
;; whitespace and not a delimiter, so it fell through to the token reader and
;; `^:int a` came back as the SYMBOL `^:int` -- with the `a` left unread behind
;; it. Clojure answers `a`. A reader that disagrees with Clojure in SILENCE is
;; worse than one that supports the form and worse than one that refuses it.

(defn ^:flint.check/test reads-metadata-like-clojure []
  ;; The value is the form, not the metadata -- this is the assertion that
  ;; failed before, answering the symbol `^:int` instead.
  (expect = 'a (edn/read-string "^:int a"))
  (expect = {:int true} (meta (edn/read-string "^:int a")))
  ;; The four spellings Clojure accepts.
  (expect = {:tag 'T} (meta (edn/read-string "^T a")))
  (expect = {:tag "T"} (meta (edn/read-string "^\"T\" a")))
  (expect = {:a 1} (meta (edn/read-string "^{:a 1} a")))
  ;; On a collection, and across a line break, which is what an annotation
  ;; payload spread over several comment lines actually looks like.
  (expect = [1 2] (edn/read-string "^:v\n[1 2]"))
  (expect = {:v true} (meta (edn/read-string "^:v\n[1 2]"))))

(defn ^:flint.check/test metadata-stacks-later-winning []
  (expect = {:a true :b true} (meta (edn/read-string "^:a ^:b x")))
  (expect = 'x (edn/read-string "^:a ^:b x")))

(defn ^:flint.check/test metadata-inside-a-collection []
  ;; The `a` must not be consumed by the metadata form and then lost.
  (expect = '[a b] (edn/read-string "[^:int a b]"))
  (expect = {:int true} (meta (first (edn/read-string "[^:int a b]"))))
  (expect = nil (meta (second (edn/read-string "[^:int a b]")))))

(defn ^:flint.check/test bad-metadata-is-refused-by-name []
  ;; A number is none of the four spellings. Attaching it as-is would make a
  ;; typo into a key instead of into a report.
  (expect = :edn (:type (ex-data (try (edn/read-string "^1 a") nil
                                      (catch Throwable e e)))))
  (expect = :edn (:type (ex-data (try (edn/read-string "^:a") nil
                                      (catch Throwable e e))))))

;; ---------------------------------------------------------- what it costs
;;
;; Kept as an assertion rather than a comment, because it is the reason a
;; payload should not carry meaning in metadata even though the reader now
;; reads it: metadata does not print and does not count for `=`.

(defn ^:flint.check/test metadata-does-not-survive-text []
  (let [x (edn/read-string "^:int a")]
    ;; `=` cannot see it ...
    (expect = x 'a)
    ;; ... and neither can `pr-str`, so a round trip through text loses it.
    (expect = "a" (pr-str x))
    (expect = nil (meta (edn/read-string (pr-str x))))))

(defn ^:flint.check/test tags-still-work []
  (expect = [1 2] (edn/read-string {:readers {'pair (fn [v] v)}} "#pair [1 2]"))
  (expect = :fallback (edn/read-string {:default (fn [_t _v] :fallback)} "#nope 1"))
  ;; A namespaced map, which is what `pr-str` writes for qualified keys.
  (expect = {:n/a 1} (edn/read-string "#:n{:a 1}")))

;; ------------------------------------------ the language is not the format
;;
;; Found by the same probe that found the `^` bug and it is the same bug: a
;; character that is neither whitespace nor a delimiter falls into the token
;; reader and comes back as a SYMBOL. `@x` read as the symbol `@x` is a deref
;; silently becoming a name. Clojure's own EDN reader refuses all three.

(defn ^:flint.check/test clojure-only-reader-macros-are-refused []
  (expect = :edn (:type (ex-data (try (edn/read-string "`(a b)") nil
                                      (catch Throwable e e)))))
  (expect = :edn (:type (ex-data (try (edn/read-string "@x") nil
                                      (catch Throwable e e)))))
  (expect = :edn (:type (ex-data (try (edn/read-string "~x") nil
                                      (catch Throwable e e)))))
  (expect = :edn (:type (ex-data (try (edn/read-string "~@x") nil
                                      (catch Throwable e e)))))
  ;; Not refused, because Clojure's EDN reader does not refuse it either: `'x`
  ;; is the symbol `'x` on both sides, and agreeing is the whole standard here.
  (expect = (symbol "'x") (edn/read-string "'x")))
