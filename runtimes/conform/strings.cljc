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
    :name [(name :a) (name :my.ns/a) (namespace :my.ns/a) (namespace :a)]}))
