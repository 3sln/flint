(require '[flint.reader :as r] '[clojure.string :as str])
(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n     expected" (pr-str expected) "\n     got     " (pr-str actual)))))
(defn reads [s] (r/read-one s))

(println "reader")
(check "integer" (reads "42") 42)
(check "negative" (reads "-42") -42)
(check "hex" (reads "0xff") 255)
(check "double" (reads "1.5") 1.5)
(check "exponent" (reads "1e3") 1000.0)
(check "ratio-free double" (reads "-2.75") -2.75)
(check "bigdec suffix dropped" (reads "1.5M") 1.5)
(check "string" (reads "\"hi\\nthere\"") "hi\nthere")
(check "unicode escape" (reads "\"\\u0041\"") "A")
(check "char is a one-character string" (reads "\\a") "a")
(check "named char" (reads "\\newline") "\n")
(check "unicode char" (reads "\\u00e9") "é")
(check "keyword" (reads ":foo") :foo)
(check "ns keyword" (reads ":a/b") :a/b)
(check "symbol" (reads "foo") 'foo)
(check "ns symbol" (reads "a/b") 'a/b)
(check "nil/true/false" (reads "[nil true false]") [nil true false])
(check "list" (reads "(1 2 3)") '(1 2 3))
(check "vector" (reads "[1 2 3]") [1 2 3])
(check "map" (reads "{:a 1 :b 2}") {:a 1 :b 2})
(check "set" (reads "#{1 2}") #{1 2})
(check "nested" (reads "{:a [1 {:b #{2}}]}") {:a [1 {:b #{2}}]})
(check "quote" (reads "'x") '(quote x))
(check "deref" (reads "@x") '(clojure.core/deref x))
(check "var quote" (reads "#'x") '(var x))
(check "comment skipped" (r/read-all "; hi\n1 ; there\n2") [1 2])
(check "the eof sentinel cannot be forged from source" (r/read-all "::flint.reader/eof :x") [:flint.reader/eof :x])
(check "discard" (r/read-all "#_1 2") [2])
(check "discard in coll" (reads "[1 #_2 3]") [1 3])
(check "commas are whitespace" (reads "[1,2,3]") [1 2 3])
(check "special doubles" (reads "[##Inf ##-Inf]") [##Inf ##-Inf])
(check "NaN reads as NaN" (Double/isNaN (reads "##NaN")) true)
(check "metadata" (meta (reads "^:private x")) (merge {:private true} (meta (reads "^:private x"))))
(check "metadata value" (:private (meta (reads "^:private x"))) true)
(check "tag metadata" (:tag (meta (reads "^long x"))) 'long)
(check "regex literal" (reads "#\"a.c\"") {:flint/regex "a.c"})
(check "regex keeps escapes" (reads "#\"\\d+\"") {:flint/regex "\\d+"})
(check "tagged literal" (reads "#inst \"2020\"") {:flint/tagged 'inst :flint/value "2020"})
(check "anon fn" (reads "#(+ % 1)") '(fn* [p1__flint#] (+ p1__flint# 1)))
(check "anon fn %2" (reads "#(+ %1 %2)") '(fn* [p1__flint# p2__flint#] (+ p1__flint# p2__flint#)))
(check "line metadata" (:line (meta (r/read-one "\n\n(foo)"))) 3)

(check "quote is not terminating inside a token" (reads "acc'") (symbol "acc'"))
(check "hash is not terminating inside a token" (reads "x#") (symbol "x#"))
(check "percent is not terminating" (reads "%1") (symbol "%1"))
(check "quote still works at the start" (reads "'acc") '(quote acc))

(println "reader: syntax quote")
(let [st (r/reader "`(a ~b ~@c)" {:ns 'my.ns})]
  (check "syntax quote" (r/read-form st)
         '(clojure.core/seq (clojure.core/concat (clojure.core/list (quote my.ns/a))
                                                 (clojure.core/list b)
                                                 c))))
(let [st (r/reader "`x#" {:ns 'my.ns})
      f (r/read-form st)]
  (check "gensym form" (and (seq? f) (= 'quote (first f)) (str/starts-with? (name (second f)) "x__")) true))
(let [st (r/reader "`[x# x#]" {:ns 'my.ns})
      f (r/read-form st)
      syms (filter symbol? (tree-seq coll? seq f))]
  (check "gensym is stable within one syntax quote"
         (= 1 (count (distinct (filter #(str/starts-with? (name %) "x__") syms)))) true))
(let [st (r/reader "`:kw" {:ns 'my.ns})]
  (check "keywords are self-quoting" (r/read-form st) :kw))
(let [st (r/reader "`(1 :a \"s\")" {:ns 'my.ns})]
  (check "literals inside syntax quote"
         (r/read-form st)
         '(clojure.core/seq (clojure.core/concat (clojure.core/list 1) (clojure.core/list :a) (clojure.core/list "s")))))

(println "reader: reader conditionals")
(check "flint branch" (r/read-all "#?(:clj 1 :flint 2)" {:features #{:flint}}) [2])
(check "clj branch" (r/read-all "#?(:clj 1 :cljs 2)" {:features #{:clj}}) [1])
(check "default branch" (r/read-all "#?(:cljs 1 :default 9)" {:features #{:flint}}) [9])
(check "no branch matches" (r/read-all "#?(:cljs 1)" {:features #{:flint}}) [])

;; `#?@` SPLICES into the surrounding collection -- that is the whole difference
;; from `#?`, and it was not happening anywhere. A matched splice left the
;; marker map sitting in the collection and an unmatched one left a sentinel
;; Volatile, so `(ns s (:require [a] #?@(:cljs [[b]])))` asked for a namespace
;; literally called `[:flint.reader/splice [[b]]]`. Conditionally adding a
;; `:require` is how real `.cljc` is written, so this is on the common path.
(check "#?@ splices its elements in"
       (r/read-all "[:a #?@(:flint [1 2]) :z]" {:features #{:flint}}) [[:a 1 2 :z]])
(check "  ... and leaves nothing behind when no branch matches"
       (r/read-all "[:a #?@(:cljs [1 2]) :z]" {:features #{:flint}}) [[:a :z]])
(check "  ... in a list too"
       (r/read-all "(:a #?@(:flint [1 2]))" {:features #{:flint}}) ['(:a 1 2)])
(check "  ... which is how an ns form conditionally requires"
       (r/read-all "(ns s (:require [a] #?@(:flint [[b] [c]])))" {:features #{:flint}})
       ['(ns s (:require [a] [b] [c]))])
(check "  ... and how it conditionally does not"
       (r/read-all "(ns s (:require [a] #?@(:cljs [[b]])))" {:features #{:flint}})
       ['(ns s (:require [a]))])

(println "reader: auto-resolved keywords")
(let [st (r/reader "::foo" {:ns 'my.ns})]
  (check "::foo" (r/read-form st) :my.ns/foo))
(let [st (r/reader "::str/x" {:ns 'my.ns :aliases {'str 'clojure.string}})]
  (check "::alias/foo" (r/read-form st) :clojure.string/x))

(println "reader: errors are located")
(check "unterminated string throws"
       (try (reads "\"abc") :no-throw (catch Exception e (:type (ex-data e)))) :reader)
(check "unbalanced throws"
       (try (reads "(1 2") :no-throw (catch Exception e (:type (ex-data e)))) :reader)

;; A STANDING CHECK, not three fixes.
;;
;; flint's own sources are read with `#{:flint}`. A conditional that offers only
;; `:clj` and `:cljs` therefore selects NOTHING -- and a `defn` whose body
;; vanishes is still a `defn`, so `(defn f [x] #?(:clj ...))` becomes
;; `(defn f [x])`: a function returning nil, with no diagnostic anywhere.
;;
;; `flint.wasm/utf8-bytes` was exactly that, and got away with it only because
;; the self-hosted compiler does not link, so the namespace never shipped. It
;; will the moment the CLI links for itself. This asserts the shape rather than
;; waiting for the next one.
(println "reader: a conditional that matches nothing is recorded, not just dropped")
;; The form the conditional stood in VANISHES -- a function body becomes nil, a
;; :require becomes a dependency the compiler never learns about. Across 20 real
;; libraries, 16 of the 28 namespaces that compiled had been cut this way.
(let [st (r/reader "(defn f [x] #?(:clj (inc x)))" {:features #{:flint}})]
  (dorun (take-while (complement r/eof?) (repeatedly #(r/read-form st))))
  (check "an unmatched conditional is recorded with its line and what it offered"
         (mapv (juxt :line :offered) (r/elided st)) [[1 [:clj]]]))
(let [st (r/reader "#?(:cljs 1 :default 9)" {:features #{:flint}})]
  (dorun (take-while (complement r/eof?) (repeatedly #(r/read-form st))))
  (check "  ... and a :default branch is NOT an elision" (r/elided st) []))
(let [st (r/reader "#?(:flint 1 :clj 2)" {:features #{:flint}})]
  (dorun (take-while (complement r/eof?) (repeatedly #(r/read-form st))))
  (check "  ... nor is one that matches" (r/elided st) []))
(let [st (r/reader "(ns a #?@(:clj [(:require [x])]))" {:features #{:flint}})]
  (dorun (take-while (complement r/eof?) (repeatedly #(r/read-form st))))
  (check "  ... and the splicing form counts too, which is how a :require disappears"
         (count (r/elided st)) 1))

(println "reader: every conditional in flint's own sources selects something")
(let [srcs (->> (concat (file-seq (clojure.java.io/file "src"))
                        (file-seq (clojure.java.io/file "lib")))
                (filter #(.isFile %))
                (filter #(clojure.string/ends-with? (.getName %) ".cljc")))
      empties (for [f srcs
                    :let [forms (try (r/read-all (slurp f) {:features #{:flint}})
                                     (catch Exception _ nil))]
                    form forms
                    :when (and (seq? form)
                               (contains? '#{defn defn- defmacro} (first form)))
                    ;; `(defn f [args])` with nothing after the vector -- the
                    ;; only way a body disappears without a syntax error.
                    :when (let [tail (drop-while (complement vector?) form)]
                            (and (seq tail) (= 1 (count tail))))]
                (str (.getPath f) " " (second form)))]
  (check "no defn in src/ or lib/ has an empty body" (vec empties) []))
(check "  ... and the check can see one when it is there"
       (let [form (first (r/read-all "(defn f [x] #?(:clj 1))" {:features #{:flint}}))
             tail (drop-while (complement vector?) form)]
         (and (seq tail) (= 1 (count tail))))
       true)

(if (zero? @fails)
  (println "reader: ok")
  (do (println "reader:" @fails "FAILURES") (System/exit 1)))
