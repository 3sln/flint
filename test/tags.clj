;; Reader tags are bound per PROJECT (`doc/decisions/0035`).
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
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(println "tags: a reader tag is bound per project (0035)")

(def root (str (fs/create-temp-dir)))
(defn proj! [name deps src-name src]
  (let [d (str root "/" name)]
    (fs/create-dirs (str d "/src"))
    (spit (str d "/deps.edn") deps)
    (spit (str d "/src/" src-name ".cljc") src)
    (str d "/src")))

;; TWO projects, each binding `#pt` to its OWN reader, and each using it.
(def a (proj! "a" "{:paths [\"src\"] :flint/tag-readers {pt a/point}}" "a"
              (str "(ns a)\n"
                   "(defn point [v] {:kind :a :x (nth v 0)})\n"
                   "(defn make [] #pt [1 2])\n")))
(def b (proj! "b" "{:paths [\"src\"] :flint/tag-readers {pt b/vec2}}" "b"
              (str "(ns b (:require [a]))\n"
                   "(defn vec2 [v] {:kind :b :y (nth v 1)})\n"
                   "(defn main [_] (pr-str [(a/make) #pt [7 8]]))\n")))

(let [r (sh "./bin/flint" ":src" b ":src" a ":fn" "b/main" ":out" "out/tags.wasm")]
  (check-that "two projects can each bind the same tag name to their own reader"
              (zero? (:exit r)) (:all r)))
(let [r (sh "node" "host/flint.mjs" "out/tags.wasm")]
  ;; `a`'s `#pt` made an `:a`; `b`'s made a `:b`. One name, two readers, no
  ;; collision -- which is the whole design, and is exactly what a global
  ;; `data_readers.clj` cannot do.
  (check "  ... and each source reads under its own"
         (str/trim (:all r)) "[{:kind :a, :x 1} {:kind :b, :y 8}]"))

;; And the opt-in half: a tag `a` binds is NOT in scope for `c`, which binds
;; none. Using a library must not quietly add reader syntax to your files.
(def c (proj! "c" "{:paths [\"src\"]}" "c"
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

(if (pos? @fails)
  (do (println "tags:" @fails "FAILURES") (System/exit 1))
  (println "tags: ok"))
