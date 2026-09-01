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
;; A tagged literal is a VALUE, not a two-key map (`doc/decisions/0034`) -- but
;; the SOURCE READER does not make one, because an unknown tag is an error here
;; as it is in Clojure. That is asserted at the bottom of this file. What a
;; tagged literal is, and that it keeps its namespace, is `lang.tagged`, which
;; constructs them rather than reading them.
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


;; --------------------------------------------------------- unknown tags
;;
;; An unknown reader tag is an ERROR, as it is in canonical Clojure. The reader
;; used to build a tagged literal out of any `#foo/bar` it met, which meant a
;; typo in a tag -- `#inst` for `#instant`, a namespace misremembered -- read as
;; a perfectly good value and failed somewhere else entirely, or silently did
;; the wrong thing. A tag is a request for a reader, not permission to invent a
;; value.
(defn- read-err [src]
  (try (r/read-all src {:features #{:flint}}) nil
       (catch Exception e (ex-message e))))

(check "an unknown tag in source is refused" (some? (read-err "#a/b [1]")) true)
(check "  ... and the message names the tag"
       (some? (re-find #"no reader for the tag #a/b" (read-err "#a/b [1]"))) true)
;; A refusal that only says no is half a message: this one says how to get a
;; tagged literal as a VALUE, and how to read one from DATA, which are the two
;; things somebody who wrote this actually wanted (`doc/decisions/0032`).
(check "  ... and says how to make one as a value"
       (some? (re-find #"tagged-literal 'a/b" (read-err "#a/b [1]"))) true)
(check "  ... and how to read one from data"
       (some? (re-find #"clojure.edn/read-string" (read-err "#a/b [1]"))) true)
;; QUOTED is not an exemption. `'#a/b [1]` is still a read, and Clojure refuses
;; it too -- quoting defers evaluation, not reading.
(check "quoting does not exempt it" (some? (read-err "'#a/b [1]")) true)
(check "  ... nor does syntax-quote" (some? (read-err "`#a/b [1]")) true)
(check "  ... nor being nested in a collection" (some? (read-err "[1 #a/b [1]]")) true)
;; And the syntax that only LOOKS like a tag is untouched.
(check "a set is not a tag" (read-err "#{1 2}") nil)
(check "an anonymous fn is not a tag" (read-err "#(inc %)") nil)
(check "a regex is not a tag" (read-err "#\"a+\"") nil)
(check "a discard is not a tag" (read-err "[1 #_2 3]") nil)
(check "a namespaced map is not a tag" (read-err "#:a{:b 1}") nil)
(check "a reader conditional is not a tag" (read-err "#?(:flint 1)") nil)


;; --- what a rewrite remembers ---------------------------------------------
;;
;; `#x form` becomes `(the-var form)`. A rewrite that forgets its origin reports
;; errors against code nobody wrote -- which is the `#?` bug one layer down,
;; where a conditional relabelled its result with its own position and every
;; failure inside pointed at the `#?`.
(def tagged-form
  (first (r/read-all "#pt [1 2]" {:features #{:flint} :tags {'pt 'my.ns/point}})))

(check "a tag rewrites to a call on the var it names" tagged-form '(my.ns/point [1 2]))
(check "  ... and remembers the form as WRITTEN, as a tagged literal"
       (pr-str (:flint/read-form (meta tagged-form))) "#pt [1 2]")
(check "  ... and which var it resolved to"
       (:flint/read-var (meta tagged-form)) 'my.ns/point)
(check "  ... and where it was, on the same metadata map"
       [(:line (meta tagged-form)) (:file (meta tagged-form))] [1 "<string>"])
;; The tag as WRITTEN, not the var: `#pt` is what the person typed and what they
;; will search for. The var is beside it because "no reader for #pt" and
;; "my.ns/point threw" name different things.
(check "  ... keeping the tag the person typed"
       (:flint/read-tag (meta tagged-form)) 'pt)

(if (zero? @fails)
  (println "reader: ok")
  (do (println "reader:" @fails "FAILURES") (System/exit 1)))
