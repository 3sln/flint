;; THE READER, through the one there is (`DECISIONS.md#one-reader-and-no-other`).
;;
;; This suite used to test `flint.compiler.reader`, the compiler's own reader,
;; which is gone: every host reads with the kin-generated reader and the
;; compiler only decodes. The checks below are the same claims about what
;; source reads as, made of the kin reader (`test/hostread.clj`, the wasm module
;; the JavaScript SDK reads with), plus the compiler's half -- resolving a
;; deferred read for a compile's features, decoding, and what the analyzer does
;; with what the reader leaves -- which still lives in `src/`.
;;
;; `r/` below is a small shim with the old reader's calling shape, so each check
;; still reads as the claim it always made. What the kin reader has no shape
;; for -- a caller's namespace handed to a read (`::foo` takes the FILE's own
;; `ns` form), a stateful reader, the forgeable-EOF sentinel -- was rewritten to
;; the file-level form or dropped, and says so where it was.
(require '[clojure.string :as str])
(load-file "test/hostread.clj")
(require '[flint.compiler.forms :as ff])

(ns r (:require [flint.compiler.forms :as ff]))
(defn- read-bytes [src {:keys [file features tags dialect]}]
  (first (hostread/read-texts [{:file (or file "<string>") :text src
                                :dialect (or dialect :flint) :tags tags :features features}])))
(defn- refuse [{:keys [message line column]} file]
  (throw (ex-info message {:type :reader :line line :column column :file file})))
(def default-features ff/default-features)
(defn read-deferred
  "The forms of a DEFERRED read: `{:opts :forms :conds}`."
  ([src] (read-deferred src {}))
  ([src opts]
   (let [a (read-bytes src (assoc opts :features nil))]
     (if-let [e (:read-error a)] (refuse e (:file opts)) (ff/decode (:preread a))))))
(defn read-all
  "An EAGER read under `:features` (default `default-features`)."
  ([src] (read-all src {}))
  ([src opts]
   (let [features (or (:features opts) default-features)
         a (read-bytes src (assoc opts :features features))]
     (if-let [e (:read-error a)]
       (refuse e (:file opts))
       (:forms (ff/decode (:preread a)))))))
(defn read-one [src] (first (read-all src)))
(defn resolve-conditionals
  ([d features file] (ff/resolve-conditionals d features file))
  ([d features file sink] (ff/resolve-conditionals d features file sink)))
(defn elided
  "The conditionals that matched nothing when `src` is read under `features`:
  a deferred read resolved with a sink, the way `bin/flint` collects them."
  [src features]
  (let [sink (volatile! [])]
    (resolve-conditionals (read-deferred src {:file "<string>"}) features "<string>" sink)
    @sink))

(ns user (:require [clojure.string :as str] [flint.compiler.forms :as ff] [r]))
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
;; A tagged literal is a VALUE, not a two-key map (`DECISIONS.md#tagged-literals`) -- but
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
(let [form (r/read-one "`(a ~b ~@c)")]
  ;; A symbol is LEFT for the analyzer to resolve (`flint.reader/syntax-quoted`):
  ;; what `a` names is compile state, and the reader does not depend on it.
  (check "syntax quote" form
         '(clojure.core/seq (clojure.core/concat (clojure.core/list (flint.reader/syntax-quoted a))
                                                 (clojure.core/list b)
                                                 c))))
(check "  ... and reads the same whatever namespace the file declares"
       (second (r/read-all "(ns my.ns (:require [other.ns :as x])) `(a x/b)"))
       (r/read-one "`(a x/b)"))
(check "  ... while what needs no context is decided here"
       (r/read-one "`(if .m &)")
       '(clojure.core/seq (clojure.core/concat (clojure.core/list (quote if))
                                               (clojure.core/list (quote .m))
                                               (clojure.core/list (quote &)))))
(check "  ... and a nested syntax quote's symbol is quoted data to the outer one"
       (r/read-one "``a")
       '(clojure.core/seq (clojure.core/concat (clojure.core/list (quote quote))
                                               (clojure.core/list (flint.reader/syntax-quoted a)))))
(let [f (r/read-one "`x#")]
  (check "gensym form" (and (seq? f) (= 'quote (first f)) (str/starts-with? (name (second f)) "x__")) true))
(let [f (r/read-one "`[x# x#]")
      syms (filter symbol? (tree-seq coll? seq f))]
  (check "gensym is stable within one syntax quote"
         (= 1 (count (distinct (filter #(str/starts-with? (name %) "x__") syms)))) true))
(check "keywords are self-quoting" (r/read-one "`:kw") :kw)
(let [form (r/read-one "`(1 :a \"s\")")]
  (check "literals inside syntax quote"
         form
         '(clojure.core/seq (clojure.core/concat (clojure.core/list 1) (clojure.core/list :a) (clojure.core/list "s")))))

(println "reader: quote resolves syntax-quoted markers nested in quoted data")
;; `'`x` is QUOTED, so it is never analysed as code -- the reader leaves the
;; marker `(flint.reader/syntax-quoted x)` exactly as `` `x `` alone would, and
;; `flint.compiler.analyzer/resolve-quoted-syntax-quotes` (private, reached below via
;; `resolve`) is what turns it into `(quote my.ns/x)` wherever it sits inside
;; the quoted structure. It already walked into lists and vectors; these check
;; map keys, map values, sets, and a map nested inside a vector, which it did
;; not.
(require '[flint.compiler.analyzer])
(def resolve-quoted (deref (resolve 'flint.compiler.analyzer/resolve-quoted-syntax-quotes)))
(def rq-env {:ns 'my.ns :cc (atom {:namespaces {'my.ns {:aliases {}}} :declared {}})})
(defn rq [src]
  (let [form (r/read-one src)] ; (quote <data>)
    (resolve-quoted rq-env (second form))))
;; already working (list / vector), pinned alongside the new cases
(check "quote resolves a marker inside a quoted list" (rq "'(`x)") '((quote my.ns/x)))
(check "quote resolves a marker inside a quoted vector" (rq "'[`x]") '[(quote my.ns/x)])
;; the new cases
(check "quote resolves a marker as a quoted map VALUE" (rq "'{:a `x}") '{:a (quote my.ns/x)})
(check "quote resolves a marker as a quoted map KEY" (rq "'{`x :a}") '{(quote my.ns/x) :a})
(check "quote resolves a marker inside a quoted set" (rq "'#{`x}") '#{(quote my.ns/x)})
(check "quote resolves a marker in a map nested inside a quoted vector"
       (rq "'[{:a `x}]") '[{:a (quote my.ns/x)}])

(println "reader: syntax quote resolves symbols the way Clojure does (DECISIONS.md#context-free-reader)")
;; `flint.compiler.analyzer/resolve-syntax-quoted` (private, reached via `resolve`) is
;; what `(flint.reader/syntax-quoted sym)` resolves to once the namespace it
;; sits in is being ANALYSED -- the reader cannot answer this (it is
;; context-free), so it leaves the marker for here. `sq` below builds the
;; `cc` state Clojure's own namespace mapping would have, and calls the
;; function directly rather than through a whole compile.
(def resolve-sq (deref (resolve 'flint.compiler.analyzer/resolve-syntax-quoted)))
(defn sq
  "`` `sym `` resolved in namespace `my.ns`, given `:vars`, `:declared`,
  `:aliases` and `:refers` -- each defaulting to empty, as a fresh namespace's
  would be."
  [sym {:keys [vars declared aliases refers]}]
  (resolve-sq {:ns 'my.ns
               :cc (atom {:namespaces {'my.ns {:aliases (or aliases {}) :refers (or refers {})}}
                          :vars (or vars {})
                          :declared (or declared {})})}
              sym))
(check "an unknown name resolves to the current namespace"
       (sq 'frobnicate {}) 'my.ns/frobnicate)
(check "a core name not shadowed resolves to clojure.core"
       (sq 'map {:vars {'clojure.core/map true}}) 'clojure.core/map)
(check "a bootstrap macro -- no :vars/:declared entry of its own -- is still clojure.core's"
       (sq 'fn {}) 'clojure.core/fn)
(check "an alias-qualified symbol expands to the alias's full namespace"
       (sq 'str/join {:aliases {'str 'clojure.string}})
       'clojure.string/join)
(check "  ... and a namespace part that names no alias is unchanged"
       (sq 'other.ns/x {}) 'other.ns/x)
;; THE TWO ARMS THE OLD READER HOOK COULD NEVER REACH, because it ran before
;; any namespace was analysed (`DECISIONS.md#context-free-reader`'s "Open, for
;; the maintainer"). Both are Clojure's behaviour, and both FAIL against the
;; rule this change replaces -- confirmed by stashing just the
;; `resolve-syntax-quoted` edit (keeping this file) and running it against the
;; PRE-change `(map? (get-in cc [:declared core]))`-or-bootstrap-else-current-ns
;; rule: it answered `my.ns/map` for a REFERRED name, because that rule never
;; looks at `:refers` at all, and `clojure.core/map` for a name that SHADOWS a
;; core one, because that rule only ever asks whether `clojure.core` declares
;; the name -- it has no arm for "or did this namespace define its own". Both
;; are wrong beside Clojure, and both are exactly backwards from what they are
;; below.
(check "a REFERRED name keeps its source namespace, not this one"
       (sq 'map {:refers {'map 'clojure.set/map-invert}})
       'clojure.set/map-invert)
(check "a local def SHADOWING a core name resolves to this namespace"
       ;; `:vars` carries the shadowing OWN def; `:declared` carries
       ;; `clojure.core/map` as the OLD rule's `map?` check wants it, so a
       ;; revert of just the resolution logic reproduces the pre-fix failure
       ;; exactly against this same fixture.
       (sq 'map {:vars {'my.ns/map true} :declared {'clojure.core/map {}}})
       'my.ns/map)
(check "  ... and an own name nothing has compiled yet still resolves to this namespace"
       (sq 'helper {:declared {'my.ns/helper true}})
       'my.ns/helper)

(println "reader: reader conditionals")
(check "flint branch" (r/read-all "#?(:clj 1 :flint 2)" {:features #{:flint}}) [2])
(check "clj branch" (r/read-all "#?(:clj 1 :cljs 2)" {:features #{:clj}}) [1])
(check "default branch" (r/read-all "#?(:cljs 1 :default 9)" {:features #{:flint}}) [9])
(check "no branch matches" (r/read-all "#?(:cljs 1)" {:features #{:flint}}) [])

;; `#?@` SPLICES into the surrounding collection -- that is the whole difference
;; from `#?`, and it was not happening anywhere. A matched splice left the
;; marker map sitting in the collection and an unmatched one left a sentinel
;; Volatile, so `(ns s (:require [a] #?@(:cljs [[b]])))` asked for a namespace
;; literally called `[:flint.compiler.reader/splice [[b]]]`. Conditionally adding a
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
;; The FILE's `ns` decides; the kin reader takes no namespace from its caller.
(check "::foo" (second (r/read-all "(ns my.ns) ::foo")) :my.ns/foo)
(check "::alias/foo" (second (r/read-all "(ns my.ns (:require [clojure.string :as str])) ::str/x"))
       :clojure.string/x)
;; The FILE's `ns` form sets them, inside the reader: the compiler used to, so
;; the read that finds requires saw `:user/foo` where the compile saw the right
;; one, and the two reads could not be one.
(check "::foo after an ns form, with no caller's help"
       (r/read-all "(ns my.ns (:require [clojure.string :as str])) ::foo ::str/x #::{:a 1}")
       ['(ns my.ns (:require [clojure.string :as str])) :my.ns/foo :clojure.string/x {:my.ns/a 1}])

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
;; `flint.compiler.wasm/utf8-bytes` was exactly that, and got away with it only because
;; the self-hosted compiler does not link, so the namespace never shipped. It
;; will the moment the CLI links for itself. This asserts the shape rather than
;; waiting for the next one.
(println "reader: a conditional that matches nothing is recorded, not just dropped")
;; The form the conditional stood in VANISHES -- a function body becomes nil, a
;; :require becomes a dependency the compiler never learns about. Across 20 real
;; libraries, 16 of the 28 namespaces that compiled had been cut this way.
(check "an unmatched conditional is recorded with its line and what it offered"
       (mapv (juxt :line :offered) (r/elided "(defn f [x] #?(:clj (inc x)))" #{:flint})) [[1 [:clj]]])
(check "  ... and a :default branch is NOT an elision" (r/elided "#?(:cljs 1 :default 9)" #{:flint}) [])
(check "  ... nor is one that matches" (r/elided "#?(:flint 1 :clj 2)" #{:flint}) [])
(check "  ... and the splicing form counts too, which is how a :require disappears"
       (count (r/elided "(ns a #?@(:clj [(:require [x])]))" #{:flint})) 1)

(println "reader: every conditional in flint's own sources selects something")
(let [srcs (->> (concat (file-seq (clojure.java.io/file "src"))
                        (file-seq (clojure.java.io/file "lib"))
                        (file-seq (clojure.java.io/file "cli/lib")))
                (filter #(.isFile %))
                (filter #(re-find #"\.(cljc|fln)$" (.getName %))))
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
  (check "no defn in src/, lib/ or cli/lib/ has an empty body" (vec empties) []))
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
;; things somebody who wrote this actually wanted (`DECISIONS.md#checks`).
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

;; --- the shebang (`DECISIONS.md#standalone-scripts`) ------------------------
;;
;; BYTE ONE AND NOWHERE ELSE. `#!` is a kernel convention about the first two
;; bytes of an executable file, and reading it as a comment marker wherever it
;; appears would invent a second comment syntax out of it.
(check "a #! first line is skipped" (r/read-all "#!/usr/bin/env flint\n(ns a)\n42")
       ['(ns a) 42])
(check "  ... and the line numbers below it are unchanged"
       (:line (meta (first (r/read-all "#!/usr/bin/env flint\n(ns a)")))) 2)
(check "  ... a file that is ONLY a shebang reads as nothing"
       (r/read-all "#!/usr/bin/env flint") [])
(check "  ... and one with no trailing newline still reads"
       (r/read-all "#!/usr/bin/env flint\n1") [1])
;; `#!` anywhere else is what it always was: a `#` dispatch on `!`, which is not
;; a tag anyone bound. The message is about the tag, not about a comment.
(check "a #! that is not on line one is NOT a comment"
       (try (do (r/read-all "(ns a)\n#!nope\n42") :read)
            (catch Exception e (if (str/includes? (ex-message e) "no reader for the tag")
                                 :refused (ex-message e))))
       :refused)

;; --- the dialect (`DECISIONS.md#dialects-and-preludes`) ---------------------
;;
;; A flint-only reader tag makes the FILE it is in non-portable, and that is
;; known at read time for the file being read -- which is where the check
;; belongs and where the answer is local.
(defn tag-read [dialect]
  (try (do (r/read-all "#pt [1 2]" {:tags {'pt 'my.ns/point} :dialect dialect}) :read)
       (catch Exception e (ex-message e))))
(check "a flint dialect reads a project tag" (tag-read :flint) :read)
(check "  ... and a caller that says nothing gets the flint dialect" (tag-read nil) :read)
(check "  ... and a PORTABLE file is refused"
       (str/includes? (str (tag-read :portable)) "flint-only reader tag") true)
(check "  ... naming the extension that fixes it"
       (str/includes? (str (tag-read :portable)) ".fln") true)
;; The UNBOUND tag keeps its own error, and that ordering is deliberate: a tag
;; this project cannot read is broken in flint too, so "no reader for #pt" is
;; the complaint that leads somewhere.
(check "an unbound tag in a portable file still says there is no reader"
       (try (do (r/read-all "#pt [1 2]" {:dialect :portable}) :read)
            (catch Exception e (if (str/includes? (ex-message e) "no reader for the tag")
                                 :no-reader (ex-message e))))
       :no-reader)

;; --- reader conditionals kept as data (`DECISIONS.md#stdlib-preread`) --------
;;
;; `read-deferred` reads without choosing a branch and `resolve-conditionals`
;; chooses later, so one read serves every feature set. The claim is EQUALITY
;; WITH THE READER'S OWN CHOICE -- forms and metadata, child positions
;; included -- so every check below compares the two with `*print-meta*` on.
;; Before the split there was no deferred read at all: run against c0eb81cb's
;; reader, this section does not even load ("Could not resolve symbol:
;; r/resolve-conditionals"), so the suite is red rather than quietly green.
(defn pm [x] (binding [*print-meta* true] (pr-str x)))
(defn deferred [src features]
  (try (r/resolve-conditionals (r/read-deferred src {:file "t.cljc"}) features "t.cljc")
       (catch clojure.lang.ExceptionInfo e [:refused (ex-message e)])
       (catch Exception e [:failed (ex-message e)])))
;; A read that FAILS agrees with one that fails: the two report the position
;; they know -- the reader where it stopped, the resolver the conditional --
;; so the comparison is of the outcome, not of the message.
(defn agrees? [src features]
  (let [e (try (r/read-all src {:file "t.cljc" :features features})
               (catch clojure.lang.ExceptionInfo _ :read-error))
        d (deferred src features)]
    (= (pm e) (pm (if (and (vector? d) (= :refused (first d))
                           (str/starts-with? (second d) "read error: map literal"))
                    :read-error d)))))
(println "reader: a deferred read resolves to what the reader would have chosen")
(doseq [[label src] [["a plain conditional, matched and not" "(f #?(:clj 1 :flint 2) #?(:clj 5) x)"]
                     ["a splice, matched and not" "[a #?@(:flint [b c] :clj [d]) #?@(:clj [e]) f]"]
                     [":default" "(g #?(:cljs 1 :default 9))"]
                     ["nested conditionals" "(h #?(:flint #?(:flint/check (expect a) :default b)) z)"]
                     ["a conditional key in a map" "{#?(:flint :a :clj :b) 1 :c 2}"]
                     ["a splice in a map" "{:a 1 #?@(:flint [:b 2])}"]
                     ["an unmatched value in a map, which reads as an odd count"
                      "{:a #?(:clj 1) :b}"]
                     ["a conditional value in metadata"
                      "(defn ^{:m #?(:flint/check {:x 1} :default nil)} f [x] x)"]
                     ["a conditional in a set" "#{1 #?(:flint 2 :clj 3)}"]
                     ;; With a :default: an UNMATCHED one under a quote reads as `(quote <EOF>)`
                     ;; when the reader chooses -- the sentinel leaks -- and as `(quote)` when
                     ;; it is resolved, and neither is a program anyone means.
                     ["a quoted conditional" "'#?(:flint (a b) :default c)"]
                     ["an empty list chosen" "(k #?(:flint ()))"]
                     ["top level, matched, unmatched and spliced"
                      "#?(:flint (def a 1)) #?(:clj (def b 2)) (def c 3)"]]
        features [#{:flint :flint/check} #{:flint} #{:clj}]]
  (check (str "  " label " " (pr-str features)) (agrees? src features) true))
(check "  ... and the whole of lib/, cli/lib/ and src/ that holds a conditional, both feature sets"
       (vec (for [f (->> (concat (file-seq (clojure.java.io/file "lib"))
                                 (file-seq (clojure.java.io/file "cli/lib"))
                                 (file-seq (clojure.java.io/file "src")))
                         (filter #(.isFile %))
                         (filter #(re-find #"\.(cljc|fln)$" (.getName %)))
                         (filter #(str/includes? (slurp %) "#?")))
                  features [r/default-features #{:flint :flint/nested}]
                  :let [src (slurp f) path (.getPath f)]
                  :when (not= (pm (r/read-all src {:file path :features features}))
                              (pm (try (r/resolve-conditionals
                                        (r/read-deferred src {:file path}) features path)
                                       (catch Exception e (ex-message e)))))]
              [path features]))
       [])
;; The comparison can see a difference: drop the metadata resolution and the
;; `:flint/value-meta` conditionals in `clojure.core` stop agreeing.
(check "  ... and the comparison is not blind"
       (agrees? "(defn ^{:m #?(:flint {:x 1} :default nil)} f [x] x)" #{:clj :flint})
       true)
(check "  ... (one read, two answers)"
       (let [d (r/read-deferred "(f #?(:flint/check (chk x)) x)" {:file "t.cljc"})]
         [(r/resolve-conditionals d #{:flint :flint/check} "t.cljc")
          (r/resolve-conditionals d #{:flint} "t.cljc")])
       ['((f (chk x) x)) '((f x))])
(println "reader: a deferred read refuses what it cannot keep as data")
(doseq [[label src] [["syntax quote" "`(a #?(:flint b))"]
                     ["#()" "#(f #?(:flint %))"]
                     ["a reader tag's argument" "#flint/table #?(:flint [])"]
                     ["metadata that is a conditional" "^#?(:flint {:a 1}) x"]
                     ["an ns form chosen by a conditional" "#?(:flint (ns a)) ::x"]
                     ["an alias an ns conditional may declare"
                      "(ns a (:require #?(:flint [b.c :as b]))) ::b/x"]]]
  (check (str "  " label)
         (let [r (deferred src #{:flint})]
           (and (vector? r) (= :refused (first r))
                (str/includes? (second r) "features are known")))
         true))
(check "  ... while an alias declared outside the conditional still resolves"
       (deferred "(ns a (:require [b.c :as b] #?(:clj [x]))) ::b/x" #{:flint})
       ['(ns a (:require [b.c :as b])) :b.c/x])

;; --- read forms, encoded (`flint.compiler.forms`, `DECISIONS.md#stdlib-preread`) -------
;;
;; Every door ships source as `flint.forms` bytes the KIN encoder wrote, and the
;; compiler decodes them. The contract is EXACTNESS: what the decoder rebuilds
;; from the kin reader's bytes, `flint.compiler.forms/encode` -- kept as the
;; encoder's specification, which `kin/formsenc.kin` follows "rule for rule" --
;; encodes to bytes that decode to the SAME forms, metadata and key order
;; included. Every file in the shipped roots.
;;
;; NOT byte for byte against the kin reader's own bytes, which was tried first:
;; under babashka a decoded map is babashka's, which iterates in a different
;; order from the flint runtime's CHAMP for more than eight keys, so `encode`
;; here writes `clojure.edn`'s big maps in another order than the kin encoder
;; did. That is the host's business (`pos-map`'s own note), not a disagreement.
(println "reader: the compiler's decoder and the encoder agree, metadata and all")
(let [lib (->> (concat (file-seq (clojure.java.io/file "lib"))
                       (file-seq (clojure.java.io/file "cli/lib")))
               (filter #(.isFile %))
               (filter #(re-find #"\.(cljc|fln)$" (.getName %))))
      sizes (atom [0 0])
      read (hostread/read-texts (mapv (fn [f] {:file (.getPath f) :text (slurp f)
                                               :dialect (hostread/dialect-of (.getPath f))})
                                      lib))
      bad (vec (for [[f a] (map vector lib read)
                     :let [b (:preread a)
                           _ (swap! sizes (fn [[x y]] [(+ x (count (slurp f))) (+ y (count b))]))
                           d (ff/decode b)
                           back (ff/decode (ff/encode d))]
                     :when (not= (pm [(:opts d) (:conds d) (:forms d)])
                                 (pm [(:opts back) (:conds back) (:forms back)]))]
                 (.getPath f)))]
  (check (str "  every file in lib/ and cli/lib/ round-trips (" (count lib) " files)") bad [])
  (check (str "  ... and the encoding is smaller than the text (" (second @sizes) " < "
              (first @sizes) " bytes)")
         (< (second @sizes) (first @sizes)) true))
(check "  ... and a value the compact form cannot say exactly still round-trips"
       (let [x (with-meta '(a b) {:line 1 :column 1 :file "elsewhere.cljc" :z 1})
             back (:forms (ff/decode (ff/encode {:opts {:file "t.cljc"} :conds [] :forms [x ##NaN ##-Inf -5 (Math/pow 2 50) 1.5]})))]
         [(pm (first back)) (Double/isNaN (second back)) (drop 2 back)])
       [(pm (with-meta '(a b) {:line 1 :column 1 :file "elsewhere.cljc" :z 1})) true
        [##-Inf -5 (Math/pow 2 50) 1.5]])
(check "  ... and a corrupted encoding does not decode to the same forms"
       (let [src "(defn f [x] (inc x))"
             b (:preread (first (hostread/read-texts [{:file "t.cljc" :text src}])))
             i (- (count b) 3)
             b2 (doto (aclone b) (aset-byte i (byte (bit-xor (aget b i) 1))))]
         (= (pm (:forms (ff/decode b)))
            (pm (try (:forms (ff/decode b2)) (catch Exception _ :refused)))))
       false)

(if (zero? @fails)
  (println "reader: ok")
  (do (println "reader:" @fails "FAILURES") (System/exit 1)))
