(ns io
  "The four IO protocols (`DECISIONS.md#flint-ception`), from inside a module.

  What matters here is that an implementation is a VALUE CARRYING FUNCTIONS and
  not a type: that is what lets `flint.ception` give an inner sandbox input and
  output without granting it any, and what lets a caller substitute a buffer
  for a file without the program noticing."
  (:require [flint.protocols.io :as io]
            [flint.bytes :as b]
            [clojure.string :as str]))

(defn- rows []
  (let [src (io/string-source "hello world")
        first-5 (io/read-text src 5)
        rest* (io/read-text src)
        past (io/read-text src)

        sink (io/string-sink)
        _ (io/write-text sink "a")
        _ (io/write-text sink "b")

        bsrc (io/bytes-source (b/of-string "abcdef"))
        b3 (io/read-bytes bsrc 3)
        brest (io/read-bytes bsrc)
        bsink (io/bytes-sink)
        _ (io/write-bytes bsink (b/of-string "xy"))

        ;; THE ABSTRACTION POINT. Nothing about this reads a file, and the
        ;; reader cannot tell it from one.
        counted (atom 0)
        fn-src (io/text-source (fn [n] (swap! counted inc) (when (< @counted 2) "tick")))]
    [["a sized read takes exactly that many" first-5 "hello"]
     ["an unsized read takes the rest" rest* " world"]
     ["past the end is nil, not empty string" (pr-str past) "nil"]
     ["a sink collects in order" (str/join (io/collected sink)) "ab"]
     ["a sized binary read" (b/to-string b3) "abc"]
     ["an unsized binary read takes the rest" (b/to-string brest) "def"]
     ["a binary sink collects" (b/to-string (first (io/collected bsink))) "xy"]
     ["a source built from a function reads" (io/read-text fn-src) "tick"]
     ["  ... and ends when the function says so" (pr-str (io/read-text fn-src)) "nil"]
     ;; `satisfies?` has to DISCRIMINATE, or it says yes to everything and the
     ;; protocol is decoration. A sink is not a source.
     ["a source satisfies TextSource" (pr-str (satisfies? io/TextSource src)) "true"]
     ["a sink does not" (pr-str (satisfies? io/TextSource sink)) "false"]
     ["a text source is not a binary one" (pr-str (satisfies? io/BinarySource src)) "false"]
     ["a binary source satisfies BinarySource" (pr-str (satisfies? io/BinarySource bsrc)) "true"]]))

(defn main [args]
  (str/join "\n"
            (map (fn [[label got want]]
                   (str (if (= got want) "  ok   " "  FAIL ") label
                        (when-not (= got want)
                          (str "\n         expected " (pr-str want) " got " (pr-str got)))))
                 (rows))))
