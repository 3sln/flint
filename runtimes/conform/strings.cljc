(ns strings)
(defn main [_]
  (pr-str
   {:str [(str) (str "a") (str "a" 1 :k nil true)]
    :pr [(pr-str "a") (pr-str :k) (pr-str nil) (pr-str [1 "b"])]
    :count [(count "") (count "abc")]
    ;; Outside the BMP: flint is UTF-8 and the JVM is UTF-16, so `count` on an
    ;; astral character is exactly where two hosts disagree unless the
    ;; semantics are pinned to code points.
    :astral [(count "aéz") (count "hello")]
    :name [(name :a) (name :my.ns/a) (namespace :my.ns/a) (namespace :a)]

    ;; SEQ OVER A ROPE, which nothing here reached before.
    ;;
    ;; A big `str` is a rope (`doc/decisions/0011`), and a rope's body is not
    ;; string bytes. The native runtime built its string seq on BYTE OFFSETS
    ;; and read a rope as though it were flat, so `(first (seq rope))`
    ;; answered `" "` for `"x"` and a full walk of an 8 000-character rope
    ;; answered 5 386 characters -- silently, and only on native. Both ports
    ;; index by code point and were right.
    ;;
    ;; The sizes matter: the strings have to be long enough to BE a rope, and
    ;; the mixed case has to cross from an ASCII leaf into a non-ASCII one,
    ;; which is where a byte offset and a code-point index diverge.
    :rope-seq (let [a (apply str (repeat 4000 "x"))
                    b (apply str (repeat 4000 "y"))
                    r (str a b)
                    v (vec (seq r))]
                [(count r) (count v) (nth v 0) (nth v 3999) (nth v 4000) (nth v 7999)])
    :rope-seq-utf8 (let [a (apply str (repeat 4000 "x"))
                         b (apply str (repeat 4000 "\u00e9"))
                         r (str a b)
                         v (vec (seq r))]
                     [(count r) (count v) (nth v 0) (nth v 4000) (nth v 7999)
                      (apply str (take 3 (drop 3999 (seq r))))])}))
