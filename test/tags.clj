;; Reader tags are bound per PROJECT (`DECISIONS.md#reader-tags`).
;;
;; The case the mechanism exists for is two libraries that both want `#x`. That
;; only works if a tag a project binds is in scope for ITS sources and nobody
;; else's -- so the test that matters is not "a tag works", it is "a tag does
;; NOT work in the project next door".
(require '[babashka.fs :as fs] '[clojure.string :as str])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label "\n        expected" (pr-str expected)
                                   "\n        got     " (pr-str actual)))))
(defn check-that [label ok extra]
  (if ok (println "  ok  " label)
      (do (swap! fails inc) (println "  FAIL" label (str "\n        " extra)))))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        ;; STDERR IS DRAINED ON ITS OWN THREAD (`DECISIONS.md#the-codec-is-guest-code`,
        ;; "the test helper deadlocked"). Reading stdout to completion and
        ;; stderr after DEADLOCKS the moment a child writes more than a pipe
        ;; buffer to stderr: the child blocks writing, this blocks reading, and
        ;; neither moves again.
        err (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p))
        err @err]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(println "tags: a reader tag is bound per project (0035)")

(def root (str (fs/create-temp-dir)))
(defn proj!
  "A one-file project. `src-name` carries its own extension, because the
  DIALECT is part of what is being tested: a project tag is a flint-only tag,
  so a file using one is a `.fln` and a `.cljc` using one is refused
  (`DECISIONS.md#dialects-and-preludes`)."
  [name deps src-name src]
  (let [d (str root "/" name)]
    (fs/create-dirs (str d "/src"))
    (spit (str d "/deps.edn") deps)
    (spit (str d "/src/" src-name) src)
    (str d "/src")))

;; TWO projects, each binding `#pt` to its OWN reader, and each using it.
(def a (proj! "a" "{:paths [\"src\"] :flint/tag-readers {pt a/point}}" "a.fln"
              (str "(ns a)\n"
                   "(defn point [v] {:kind :a :x (nth v 0)})\n"
                   "(defn make [] #pt [1 2])\n")))
(def b (proj! "b" "{:paths [\"src\"] :flint/tag-readers {pt b/vec2}}" "b.fln"
              (str "(ns b (:require [a]))\n"
                   "(defn vec2 [v] {:kind :b :y (nth v 1)})\n"
                   "(defn main [_] (pr-str [(a/make) #pt [7 8]]))\n")))

(let [r (sh "./bin/flint" ":src" b ":src" a ":fn" "b/main" ":out" "out/tags.wasm")]
  (check-that "two projects can each bind the same tag name to their own reader"
              (zero? (:exit r)) (:all r)))
(let [r (sh "node" "host/flint.mjs" "out/tags.wasm" "b/main")]
  ;; `a`'s `#pt` made an `:a`; `b`'s made a `:b`. One name, two readers, no
  ;; collision -- which is the whole design, and is exactly what a global
  ;; `data_readers.clj` cannot do.
  (check "  ... and each source reads under its own"
         (str/trim (:all r)) "[{:kind :a, :x 1} {:kind :b, :y 8}]"))

;; And the opt-in half: a tag `a` binds is NOT in scope for `c`, which binds
;; none. Using a library must not quietly add reader syntax to your files.
(def c (proj! "c" "{:paths [\"src\"]}" "c.cljc"
              (str "(ns c (:require [a]))\n"
                   "(defn main [_] (pr-str #pt [1 2]))\n")))
(let [r (sh "./bin/flint" ":src" c ":src" a ":fn" "c/main" ":out" "out/tags-c.wasm")]
  (check-that "a dependency's tag is NOT in scope for the project using it"
              (not (zero? (:exit r)))
              "c compiled, so requiring `a` silently brought `a`'s reader syntax with it")
  (check-that "  ... and the refusal says a tag is bound per project"
              (str/includes? (:all r) "bound per PROJECT")
              (:all r))
  ;; It lists what IS readable here -- the built-ins -- and `#pt` is not among
  ;; them, which is the whole claim. Requiring a library must not quietly add
  ;; reader syntax to your files.
  (check-that "  ... and lists what this project CAN read, which is the built-ins"
              (and (str/includes? (:all r) "this project can read #flint/table")
                   (not (str/includes? (:all r) "can read #pt")))
              (:all r)))

;; ------------------------------------------------- the DIALECT half (0038)
;;
;; A tag is flint-only however it came to be bound, so a `.cljc` using one is
;; not portable and is refused AT THE READER, for the file being read
;; (`DECISIONS.md#dialects-and-preludes`).
;;
;; The control is the SAME SOURCE under the other extension. Two runs differing
;; in one character of a filename is what makes a failure attributable: a
;; refusal that also happens for an unbound tag, a missing require or a typo
;; would prove nothing about the dialect.
(def dsrc (str "(ns d)\n(defn point [v] {:x (nth v 0)})\n(defn main [_] (pr-str #pt [1 2]))\n"))
(def d-cljc (proj! "dcljc" "{:paths [\"src\"] :flint/tag-readers {pt d/point}}" "d.cljc" dsrc))
(def d-fln (proj! "dfln" "{:paths [\"src\"] :flint/tag-readers {pt d/point}}" "d.fln" dsrc))

(let [r (sh "./bin/flint" ":src" d-cljc ":fn" "d/main" ":out" "out/tags-d.wasm")]
  (check-that "a project tag in a .cljc is refused: the file is not portable"
              (not (zero? (:exit r)))
              (str "d.cljc compiled with #pt in it: " (:all r)))
  (check-that "  ... and the refusal says it is flint-only, and names .fln"
              (and (str/includes? (:all r) "flint-only reader tag")
                   (str/includes? (:all r) ".fln"))
              (:all r)))
(let [r (sh "./bin/flint" ":src" d-fln ":fn" "d/main" ":out" "out/tags-d.wasm")]
  (check-that "  ... and the SAME source as a .fln compiles"
              (zero? (:exit r)) (:all r)))

;; `#flint/table` is bound by the reader itself -- always available, never
;; declared -- and Clojure cannot read it either. The rule is about the TAG, not
;; about where it was bound from, so this one is refused in a `.cljc` too.
(def tbl (proj! "tbl" "{:paths [\"src\"]}" "t.cljc"
                (str "(ns t (:require [flint.table :as ft]))\n"
                     "(defn main [_] (pr-str #flint/table {:schema [[:id :int]] :rows [{:id 1}]}))\n")))
(let [r (sh "./bin/flint" ":src" tbl ":fn" "t/main" ":out" "out/tags-t.wasm")]
  (check-that "a BUILT-IN flint tag in a .cljc is refused too"
              (and (not (zero? (:exit r)))
                   (str/includes? (:all r) "flint-only reader tag"))
              (:all r)))

(if (pos? @fails)
  (do (println "tags:" @fails "FAILURES") (System/exit 1))
  (println "tags: ok"))
