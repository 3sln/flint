(ns conform.basics
  "Conformance: real Clojure expressions, checked against real Clojure answers.
  Each entry is [label expr-result expected]; the harness compares with = and
  reports every failure rather than stopping at the first."
  (:require [clojure.zip :as z]
            [clojure.data :as dt]
            [clojure.datafy :as df]))

(defmacro c
  "One case. The expression is wrapped in a thunk so that a case which throws is
  reported as one failure rather than taking the whole suite with it."
  [label expr expected]
  `{:label ~label :thunk (fn [] ~expr) :expected ~expected})

(defmacro d
  "A case where flint deliberately differs from Clojure. Both answers are
  recorded, so the differential test against real Clojure stays meaningful and
  the README's divergence list has a source that cannot go stale."
  [label expr flint-expected clojure-expected]
  `{:label ~label :thunk (fn [] ~expr) :expected ~flint-expected
    :clojure ~clojure-expected :divergence true})

(defmacro r
  "A case where the two READERS see different source, and both are right. `#?@`
  is the only one: this file is read with `#{:flint}` by flint and with
  `#{:clj :bb}` by babashka, so a `:flint` branch splices on one side and
  vanishes on the other. That is the reader conditional working, not a
  divergence -- so it is NOT recorded as one, and stays out of the README's
  divergence list."
  [label expr flint-expected clojure-expected]
  `{:label ~label :thunk (fn [] ~expr) :expected ~flint-expected
    :clojure ~clojure-expected :two-readers true})

(defn cases []
  (let [zv (z/vector-zip [1 [2 [3 4] 5] 6])
        zs (z/seq-zip '(a (b c) d))
        zd (z/down zv)
        zm (z/right zd)
        zx (z/xml-zip {:tag :a :attrs {:id "1"}
                       :content [{:tag :b :attrs nil :content ["hi"]}
                                 {:tag :c :attrs nil :content nil}]})]
  [(c "arith +" (+ 1 2 3) 6)
   (c "arith -" (- 10 3 2) 5)
   (c "arith *" (* 2 3 4) 24)
   (c "arith unary -" (- 5) -5)
   (c "quot/rem" [(quot 7 2) (rem 7 2) (quot -7 2) (rem -7 2)] [3 1 -3 -1])
   (c "mod" [(mod 7 3) (mod -7 3) (mod 7 -3)] [1 2 -2])
   (c "compare ops" [(< 1 2 3) (< 1 3 2) (<= 1 1 2) (> 3 2 1) (>= 2 2)] [true false true true true])
   (c "== crosses types" [(== 1 1.0) (= 1 1.0)] [true false])
   (c "inc/dec" [(inc 1) (dec 1)] [2 0])
   (c "min/max" [(min 3 1 2) (max 3 1 2)] [1 3])
   (c "abs" [(abs -3) (abs 3)] [3 3])
   (c "even?/odd?" [(even? 2) (odd? 2)] [true false])
   (c "zero?/pos?/neg?" [(zero? 0) (pos? 1) (neg? -1)] [true true true])

   (c "if truthiness" [(if 0 :y :n) (if "" :y :n) (if nil :y :n) (if false :y :n)] [:y :y :n :n])
   (c "and" [(and) (and 1) (and 1 2) (and 1 nil 2) (and false (throw 1))] [true 1 2 nil false])
   (c "or" [(or) (or nil 1) (or nil nil) (or 1 (throw 1))] [nil 1 nil 1])
   (c "not" [(not nil) (not false) (not 0)] [true true false])
   (c "when/when-not" [(when true :y) (when false :y) (when-not false :y)] [:y nil :y])
   (c "cond" (cond false :a nil :b :else :c) :c)
   (c "cond no match" (cond false :a) nil)
   (c "if-let" [(if-let [x 1] x :no) (if-let [x nil] x :no)] [1 :no])
   (c "when-let" [(when-let [x 5] (inc x)) (when-let [x nil] :y)] [6 nil])
   (c "case" [(case 2 1 :one 2 :two :other) (case 9 1 :one :other)] [:two :other])
   (c "case with keywords" (case :b :a 1 :b 2 3) 2)
   (c "case with a list of keys" (case 3 (1 2) :low (3 4) :high :other) :high)
   (c "threading ->" (-> 5 inc (* 2)) 12)
   (c "threading ->>" (->> [1 2 3] (map inc) (reduce +)) 9)
   (c "some->" [(some-> 1 inc) (some-> nil inc)] [2 nil])
   (c "dotimes" (let [a (atom 0)] (dotimes [i 5] (reset! a (+ @a i))) @a) 10)

   (c "let" (let [a 1 b (+ a 1)] [a b]) [1 2])
   (c "let shadowing" (let [x 1] (let [x 2] x)) 2)
   (c "loop/recur" (loop [i 0 acc []] (if (< i 3) (recur (inc i) (conj acc i)) acc)) [0 1 2])
   (c "recur is simultaneous" (loop [a 1 b 2 n 0] (if (< n 1) (recur b a (inc n)) [a b])) [2 1])
   (c "fn" ((fn [x] (* x x)) 6) 36)
   (c "fn multi-arity" (let [f (fn ([] 0) ([a] a) ([a b] (+ a b)))] [(f) (f 1) (f 1 2)]) [0 1 3])
   (c "fn variadic" (let [f (fn [a & r] [a r])] (f 1 2 3)) [1 '(2 3)])
   (c "closure captures" (let [n 10 f (fn [x] (+ x n))] (f 5)) 15)
   (c "nested closures" ((((fn [a] (fn [b] (fn [c] (+ a b c)))) 1) 2) 3) 6)
   (c "anon fn literal" (map #(* % 2) [1 2 3]) '(2 4 6))
   (c "self recursion" (let [f (fn fact [n] (if (< n 2) 1 (* n (fact (dec n)))))] (f 10)) 3628800)

   (c "destructure vector" (let [[a b] [1 2]] [a b]) [1 2])
   (c "destructure nested" (let [[a [b c]] [1 [2 3]]] [a b c]) [1 2 3])
   (c "destructure rest" (let [[a & r] [1 2 3]] [a r]) [1 '(2 3)])
   (c "destructure as" (let [[a :as all] [1 2]] [a all]) [1 [1 2]])
   (c "destructure map" (let [{:keys [a b]} {:a 1 :b 2}] [a b]) [1 2])
   (c "destructure map default" (let [{:keys [a] :or {a 9}} {}] a) 9)
   (c "destructure map key" (let [{x :a} {:a 7}] x) 7)
   (c "destructure fn params" ((fn [[a b] {:keys [c]}] [a b c]) [1 2] {:c 3}) [1 2 3])
   (c "destructure missing" (let [[a b c] [1]] [a b c]) [1 nil nil])

   (c "vector ops" [(conj [1] 2) (count [1 2]) (nth [1 2] 1) (peek [1 2]) (pop [1 2])]
      [[1 2] 2 2 2 [1]])
   (c "vector assoc" (assoc [1 2 3] 1 :x) [1 :x 3])
   (c "get out of range" [(get [1] 5) (get [1] 5 :d) (nth [1] 5 :d)] [nil :d :d])
   (c "list ops" [(conj '(2) 1) (first '(1 2)) (rest '(1 2))] ['(1 2) 1 '(2)])
   (c "map ops" [(get {:a 1} :a) (get {:a 1} :b :d) (assoc {} :a 1) (dissoc {:a 1 :b 2} :a)]
      [1 :d {:a 1} {:b 2}])
   (c "map as fn" [({:a 1} :a) ({:a 1} :b :d)] [1 :d])
   (c "keyword as fn" [(:a {:a 1}) (:b {:a 1} :d)] [1 :d])
   (c "set ops" [(conj #{1} 2) (contains? #{1} 1) (disj #{1 2} 1) (#{1 2} 1)]
      [#{1 2} true #{2} 1])
   (c "count" [(count []) (count [1 2]) (count {:a 1}) (count #{1}) (count "abc") (count nil)]
      [0 2 1 1 3 0])
   (c "empty?" [(empty? []) (empty? [1]) (empty? nil)] [true false true])
   (c "into" [(into [] '(1 2)) (into #{} [1 1 2]) (into {} [[:a 1]])] [[1 2] #{1 2} {:a 1}])
   (c "keys/vals" [(sort (keys {:a 1 :b 2})) (sort (vals {:a 1 :b 2}))] ['(:a :b) '(1 2)])
   (c "merge" (merge {:a 1} {:b 2} {:a 3}) {:a 3 :b 2})
   (c "select-keys" (select-keys {:a 1 :b 2 :c 3} [:a :c]) {:a 1 :c 3})
   (c "update" (update {:a 1} :a inc) {:a 2})
   (c "get-in/assoc-in" [(get-in {:a {:b 1}} [:a :b]) (assoc-in {} [:a :b] 1)] [1 {:a {:b 1}}])
   (c "contains?" [(contains? {:a 1} :a) (contains? [1 2] 0) (contains? [1 2] 5)] [true true false])

   (c "seq nil-punning" [(seq []) (seq nil) (seq [1])] [nil nil '(1)])
   (c "first/rest/next on empty" [(first []) (rest []) (next [])] [nil '() nil])
   ;; WHAT EVERY SEQ FUNCTION ANSWERS FOR AN EMPTY INPUT. Clojure is not
   ;; uniform here -- `(keys {})` is nil while `(map inc [])` is `()` -- so
   ;; this cannot be reasoned out, only asked. Several of these were nil in
   ;; flint until recently because they ended in `(seq acc)`.
   (c "empty in, seq functions out"
      [(map inc []) (filter even? []) (remove even? []) (keep identity [])
       (mapcat vector []) (take 3 []) (drop 3 []) (take-while even? [])
       (drop-while even? []) (distinct []) (dedupe []) (reverse [])
       (partition 2 []) (partition-by odd? []) (interpose :x [])
       (interleave [] []) (map-indexed vector []) (keep-indexed vector [])]
      ['() '() '() '() '() '() '() '() '() '() '() '() '() '() '() '() '() '()])
   (c "empty in, the ones Clojure answers nil for"
      [(keys {}) (vals {}) (seq []) (next [1])] [nil nil nil nil])
   (c "empty in, sort" (sort []) '())
   ;; NIL PUNNING. Clojure treats nil as an empty collection in most seq
   ;; positions and NOT in others, and the pattern is not derivable -- it is
   ;; a set of decisions. Asked rather than assumed.
   (c "nil in, seq functions out"
      [(map inc nil) (filter even? nil) (remove even? nil) (mapcat vector nil)
       (take 3 nil) (drop 3 nil) (distinct nil) (dedupe nil)
       (reverse nil) (partition 2 nil) (interpose :x nil)
       (map-indexed vector nil) (keep-indexed vector nil) (partition-by odd? nil)]
      ['() '() '() '() '() '() '() '() '() '() '() '() '() '()])
   (c "nil in, the scalar answers"
      [(count nil) (first nil) (rest nil) (next nil) (seq nil) (empty? nil)
       (keys nil) (vals nil) (sort nil) (last nil)]
      [0 nil '() nil nil true nil nil '() nil])
   (c "nil in, the building ones"
      [(conj nil 1) (into nil [1 2]) (assoc nil :a 1) (get nil :a)
       (contains? nil :a) (nth nil 0 :d) (merge nil {:a 1}) (concat nil [1])]
      ['(1) '(2 1) {:a 1} nil false :d {:a 1} '(1)])
   ;; NIL AND FALSE AS KEYS AND VALUES, which is where `get` with a default
   ;; and `contains?` stop agreeing with each other.
   (c "nil and false in maps"
      [(get {:a nil} :a :d) (get {:a nil} :b :d) (contains? {:a nil} :a)
       (get {nil 1} nil) (contains? {nil 1} nil)
       (get {false 1} false) (contains? {false 1} false)
       (get #{nil} nil :d) (contains? #{false} false)]
      [nil :d true 1 true 1 true nil true])
   ;; WHAT KIND OF THING COMES BACK. The empty-input sweep found six wrong
   ;; answers and every one of them was about the RETURN, not the argument --
   ;; so this asks the return question directly.
   (c "what the seq functions return"
      [(seq? (map inc [1])) (seq? (filter even? [2])) (seq? (range 3))
       (seq? (rest [1 2])) (seq? (keys {:a 1})) (seq? (sort [2 1]))
       (vector? (into [] [1])) (vector? (mapv inc [1])) (vector? (subvec [1 2 3] 0 2))
       (map? (select-keys {:a 1} [:a])) (map? (merge {} {:a 1}))
       (set? (into #{} [1])) (set? (disj #{1 2} 1))
       (string? (subs "abc" 1)) (string? (str 1 2))]
      [true true true true true true true true true true true true true true true])
   ;; A SEQ IS NOT A LIST AND NOT A VECTOR, and the consequences show up in
   ;; `conj`, which adds where the collection is cheap to add to.
   (c "conj follows the collection, not the contents"
      [(conj [1 2] 3) (conj '(1 2) 3) (conj (map inc [1 2]) 0)
       (conj #{1} 2) (conj {:a 1} [:b 2]) (conj nil 1)]
      [[1 2 3] '(3 1 2) '(0 2 3) #{1 2} {:a 1 :b 2} '(1)])
   ;; EQUALITY ACROSS THE SHAPES: sequential things compare by contents, sets
   ;; and maps do not compare equal to them.
   (c "equality across shapes"
      [(= [1 2] (map inc [0 1])) (= '(1 2) [1 2]) (= [1 2] (seq [1 2]))
       (= #{1 2} [1 2]) (= {:a 1} [[:a 1]]) (= (range 3) '(0 1 2))]
      [true true true false false true])
   ;; EMPTY gives back the same KIND, emptied.
   (c "empty keeps the kind"
      [(empty [1 2]) (empty '(1 2)) (empty {:a 1}) (empty #{1})
       (vector? (empty [1])) (map? (empty {:a 1})) (set? (empty #{1}))]
      [[] '() {} #{} true true true])
   ;; METADATA SURVIVES THE OPERATIONS THAT COPY, and is not invented.
   (c "metadata"
      [(meta (with-meta [1] {:a 1})) (meta [1])
       (meta (conj (with-meta [1] {:a 1}) 2))
       (meta (with-meta '(1) {:a 1}))]
      [{:a 1} nil {:a 1} {:a 1}])
   ;; BOUNDARY ARITIES: an incomplete last group, a negative or zero count.
   (c "boundary arities"
      [(partition 3 [1 2]) (partition-all 3 [1 2]) (partition 3 3 [1 2 3 4])
       (take -1 [1 2]) (take 0 [1 2]) (drop -1 [1 2])
       (repeat 0 :x) (range 5 0) (nth [] 0 :d)]
      ['() '((1 2)) '((1 2 3)) '() '() '(1 2) '() '() :d])
   (c "map" (map inc [1 2 3]) '(2 3 4))
   (c "map two colls" (map + [1 2] [10 20]) '(11 22))
   (c "filter/remove" [(filter even? [1 2 3 4]) (remove even? [1 2 3 4])] ['(2 4) '(1 3)])
   (c "reduce" [(reduce + [1 2 3]) (reduce + 10 [1 2 3]) (reduce + [])] [6 16 0])
   (c "take/drop" [(take 2 [1 2 3]) (drop 2 [1 2 3])] ['(1 2) '(3)])
   (c "take-while/drop-while" [(take-while even? [2 4 5]) (drop-while even? [2 4 5])] ['(2 4) '(5)])
   (c "range" [(range 3) (range 1 4) (range 0 6 2)] ['(0 1 2) '(1 2 3) '(0 2 4)])
   (c "reverse" (reverse [1 2 3]) '(3 2 1))
   (c "concat" (concat [1] [2 3]) '(1 2 3))
   (c "mapcat" (mapcat (fn [x] [x x]) [1 2]) '(1 1 2 2))
   (c "every?/some" [(every? even? [2 4]) (every? even? [2 3]) (some even? [1 2]) (some even? [1 3])]
      [true false true nil])
   (c "sort" [(sort [3 1 2]) (sort > [1 3 2])] ['(1 2 3) '(3 2 1)])
   (c "sort-by" (sort-by count ["aaa" "a" "aa"]) '("a" "aa" "aaa"))
   (c "distinct" (distinct [1 2 1 3 2]) '(1 2 3))
   (c "frequencies" (frequencies [:a :b :a]) {:a 2 :b 1})
   (c "group-by" (group-by even? [1 2 3 4]) {false [1 3] true [2 4]})
   (c "partition" (partition 2 [1 2 3 4 5]) '((1 2) (3 4)))
   (c "interleave" (interleave [1 2] [:a :b]) '(1 :a 2 :b))
   (c "interpose" (interpose :x [1 2 3]) '(1 :x 2 :x 3))
   (c "zipmap" (zipmap [:a :b] [1 2]) {:a 1 :b 2})
   (c "last/butlast" [(last [1 2 3]) (butlast [1 2 3])] [3 '(1 2)])
   (c "iterate+take" (take 4 (iterate inc 0)) '(0 1 2 3))
   (c "lazy infinite" (take 3 (range)) '(0 1 2))
   (c "repeat" (repeat 3 :x) '(:x :x :x))

   (c "str" [(str) (str 1) (str "a" "b") (str :a) (str nil) (str 1 :a "b")] ["" "1" "ab" ":a" "" "1:ab"])
   (c "str of collections" (str [1 2]) "[1 2]")
   (c "subs" [(subs "hello" 1) (subs "hello" 1 3)] ["ello" "el"])
   (c "name/namespace" [(name :a/b) (namespace :a/b) (name :a) (namespace :a)] ["b" "a" "a" nil])
   (c "keyword/symbol" [(keyword "a") (symbol "a") (keyword "a" "b")] [:a 'a :a/b])
   ;; --- deliberate divergences from Clojure ------------------------------
   (d "chars are one-character strings" (nth "abc" 1) "b" \b)
   (d "char literals read as strings" \a "a" \a)
   (d "str of a char" (str \a) "a" "a")
   (d "count on a string is code points, not UTF-16 units"
      (count "a\u00e9\ud83d\ude00") 3 4)
   (d "inexact integer division is a double, not a Ratio" (str (/ 1 2)) "0.5" "1/2")
   (d "hash of a char is the hash of its string" (hash \a) 1455541201 97)
   (d "no char type, so char? is string-of-length-1" (char? \a) true true)
   (c "parse-long/double" [(parse-long "42") (parse-long "x") (parse-double "1.5")] [42 nil 1.5])

   (c "= across types" [(= [1 2] '(1 2)) (= [1 2] #{1 2}) (= {} []) (= "a" :a)] [true false false false])
   (c "= nested" (= {:a [1 {:b #{2}}]} {:a [1 {:b #{2}}]}) true)
   (c "hash agrees with =" (= (hash [1 2 3]) (hash '(1 2 3))) true)
   (c "compare" [(compare 1 2) (compare "a" "b") (compare :a :b) (compare [1] [1 2])] [-1 -1 -1 -1])
   (c "identical?" [(identical? :a :a) (identical? [1] [1])] [true false])

   (c "pr-str" [(pr-str nil) (pr-str 1) (pr-str "a") (pr-str :a) (pr-str [1 "a"])]
      ["nil" "1" "\"a\"" ":a" "[1 \"a\"]"])
   (c "pr-str escapes" (pr-str "a\"b\nc") "\"a\\\"b\\nc\"")
   (c "pr-str map" (pr-str {:a 1}) "{:a 1}")
   (c "pr-str doubles" [(pr-str 1.0) (pr-str 1.5) (pr-str 0.1)] ["1.0" "1.5" "0.1"])

   (c "throw/catch" (try (throw (ex-info "boom" {:a 1})) (catch Throwable e (ex-message e))) "boom")
   (c "ex-data" (try (throw (ex-info "b" {:a 1})) (catch Throwable e (ex-data e))) {:a 1})
   (c "try returns body" (try 1 (catch Throwable e 2)) 1)
   (c "finally runs" (let [a (atom 0)] (try 1 (finally (reset! a 9))) @a) 9)
   (c "catch nested" (try (try (throw (ex-info "x" {})) (catch Throwable e (throw (ex-info "y" {}))))
                          (catch Throwable e (ex-message e))) "y")

   ;; Every catch case above uses `Throwable`, which is why nobody noticed that
   ;; `(catch Exception e ...)` -- the commonest form in real Clojure -- matched
   ;; NOTHING. flint has no class hierarchy, so a catch compared the exception's
   ;; kind string for equality, and no kind flint raises is spelled `Exception`.
   ;; A ported program's error handling silently did not run.
   (c "catch Exception catches ex-info"
      (try (throw (ex-info "boom" {})) (catch Exception e (ex-message e))) "boom")
   (c "catch Exception catches a runtime failure"
      (try (/ 1 0) (catch Exception e :caught)) :caught)
   (c "catch RuntimeException too"
      (try (throw (ex-info "b" {})) (catch RuntimeException e :caught)) :caught)
   (c "a more specific clause is tried first"
      (try (throw (ex-info "b" {}))
           (catch ArithmeticException e :wrong)
           (catch Exception e :right)) :right)
   ;; `(catch ExceptionInfo e ...)` also works in flint, but the bare name does
   ;; not resolve in real Clojure -- it is `clojure.lang.ExceptionInfo` there --
   ;; and this file has to LOAD under both. flint accepting the short name is a
   ;; spelling convenience, not a behavioural difference, so it is exercised in
   ;; `test/catch.clj` where only flint reads it.

   ;; `reduced` never worked either: it is a one-element vector with a marker in
   ;; its metadata, and `reduce` unwrapped it with `deref`, which knows about
   ;; atoms and volatiles and delays. Every short-circuiting reduce raised.
   (c "reduced short-circuits"
      (reduce (fn [a x] (if (> x 2) (reduced a) (+ a x))) 0 [1 2 3 4]) 3)
   (c "unreduced" (unreduced (reduced 7)) 7)
   (c "reduced keeps the accumulator"
      (reduce (fn [a x] (if (= x :stop) (reduced a) (conj a x))) [] [:a :b :stop :c]) [:a :b])

   ;; `#?@` splices into the surrounding collection. flint reads its own sources
   ;; with #{:flint}, so this is the branch that must both splice and vanish.
   (r "#?@ splices" [:a #?@(:flint [1 2]) :z] [:a 1 2 :z] [:a :z])
   (c "  ... and vanishes when no branch matches" [:a #?@(:cljs [1 2]) :z] [:a :z])

   ;; `print-str` is NOT `pr-str` with spaces. Clojure's print semantics drop
   ;; the quotes at EVERY level, not just the top -- flint's shared one printer
   ;; with `pr-str`, so `(print-str ["x" 1])` came back `["x" 1]`.
   (c "print-str is unreadable printing" (print-str "a" 1 :k) "a 1 :k")
   (c "  ... recursively, inside collections" (print-str ["x" 1] {:a "b"}) "[x 1] {:a b}")
   (c "  ... while pr-str stays readable" (pr-str ["x" 1]) "[\"x\" 1]")
   (c "  ... and nil still prints as nil" (print-str nil "x") "nil x")

   ;; `#:ns{...}`: standard EDN since 1.9, and the form `pr-str` produces for
   ;; ANY map with qualified keys -- so a `deps.edn` written by a Clojure tool
   ;; round-tripped into something flint could not read.
   (c "namespaced map literal" #:git{:url "u" :sha "s"} {:git/url "u" :git/sha "s"})
   (c "  ... and a qualified key inside one keeps its own namespace"
      #:a{:b 1 :c/d 2} {:a/b 1 :c/d 2})

   (c "atom" (let [a (atom 1)] (swap! a inc) (swap! a + 10) @a) 12)
   (c "meta" (let [v (with-meta [1] {:a 1})] [(meta v) v]) [{:a 1} [1]])
   (c "meta not in =" (= (with-meta [1] {:a 1}) [1]) true)

   (c "transients" (persistent! (reduce conj! (transient []) [1 2 3])) [1 2 3])
   (c "transient map" (persistent! (assoc! (transient {}) :a 1)) {:a 1})

   (c "apply" [(apply + [1 2 3]) (apply + 1 [2 3]) (apply str ["a" "b"])] [6 6 "ab"])
   (c "comp" ((comp inc inc) 1) 3)
   (c "partial" ((partial + 1) 2) 3)
   (c "constantly" ((constantly 7) 1 2) 7)
   (c "complement" ((complement even?) 2) false)
   (c "juxt" ((juxt inc dec) 5) [6 4])

   ;; --- clojure.zip ------------------------------------------------------
   ;; Huet zippers. Every expectation here is real Clojure's answer, taken
   ;; from babashka and kept honest by `test/conform_vs_clojure.clj`.
   (c "zip node" (z/node zv)
      '[1 [2 [3 4] 5] 6])
   (c "zip down" (z/node zd)
      '1)
   (c "zip down-down" (z/node (z/down zm))
      '2)
   (c "zip right" (z/node zm)
      '[2 [3 4] 5])
   (c "zip left" (z/node (z/left zm))
      '1)
   (c "zip up" (z/node (z/up zm))
      '[1 [2 [3 4] 5] 6])
   (c "zip up-nil" (z/up zv)
      'nil)
   (c "zip path" (z/path (z/down zm))
      '[[1 [2 [3 4] 5] 6] [2 [3 4] 5]])
   (c "zip lefts" (z/lefts zm)
      '(1))
   (c "zip rights" (z/rights zm)
      '(6))
   (c "zip lefts-empty" (z/lefts zd)
      'nil)
   (c "zip rightmost" (z/node (z/rightmost zd))
      '6)
   (c "zip leftmost" (z/node (z/leftmost (z/rightmost zd)))
      '1)
   (c "zip rightmost-idem" (z/node (z/rightmost (z/rightmost zd)))
      '6)
   (c "zip branch?" [(z/branch? zv) (z/branch? zd) (z/branch? zm)]
      '[true false true])
   (c "zip children" (z/children zm)
      '(2 [3 4] 5))
   (c "zip root-unchanged" (z/root (z/down (z/right (z/down zm))))
      '[1 [2 [3 4] 5] 6])
   (c "zip edit" (z/root (z/edit zd inc))
      '[2 [2 [3 4] 5] 6])
   (c "zip edit-deep" (z/root (z/edit (z/down (z/right (z/down zm))) inc))
      '[1 [2 [4 4] 5] 6])
   (c "zip replace" (z/root (z/replace zm :x))
      '[1 :x 6])
   (c "zip insert-left" (z/root (z/insert-left zm :L))
      '[1 :L [2 [3 4] 5] 6])
   (c "zip insert-right" (z/root (z/insert-right zm :R))
      '[1 [2 [3 4] 5] :R 6])
   (c "zip insert-child" (z/root (z/insert-child zm :C))
      '[1 [:C 2 [3 4] 5] 6])
   (c "zip append-child" (z/root (z/append-child zm :A))
      '[1 [2 [3 4] 5 :A] 6])
   (c "zip remove-mid" (z/root (z/remove zm))
      '[1 6])
   (c "zip remove-first" (z/root (z/remove zd))
      '[[2 [3 4] 5] 6])
   (c "zip remove-lands" (z/node (z/remove zm))
      '1)
   (c "zip remove-lands2" (z/node (z/remove (z/rightmost zd)))
      '5)
   (c "zip seq-node" (z/node zs)
      '(a (b c) d))
   (c "zip seq-down" (z/node (z/down zs))
      'a)
   (c "zip seq-edit" (z/root (z/edit (z/down zs) name))
      '("a" (b c) d))
   (c "zip walk" (loop [l zv acc []] (if (z/end? l) acc (recur (z/next l) (conj acc (z/node l)))))
      '[[1 [2 [3 4] 5] 6] 1 [2 [3 4] 5] 2 [3 4] 3 4 5 6])
   (c "zip walk-seq" (loop [l zs acc []] (if (z/end? l) acc (recur (z/next l) (conj acc (z/node l)))))
      '[(a (b c) d) a (b c) b c d])
   (c "zip prev-walk" (loop [l (z/rightmost (z/down zv)) acc []] (if (nil? l) acc (recur (z/prev l) (conj acc (z/node l)))))
      '[6 5 4 3 [3 4] 2 [2 [3 4] 5] 1 [1 [2 [3 4] 5] 6]])
   (c "zip next-end-stays" (let [e (loop [l zv] (if (z/end? l) l (recur (z/next l))))] [(z/end? e) (z/node e) (z/node (z/next e))])
      '[true [1 [2 [3 4] 5] 6] [1 [2 [3 4] 5] 6]])
   (c "zip down-leaf" (z/down zd)
      'nil)
   (c "zip down-empty" (z/down (z/vector-zip []))
      'nil)
   (c "zip right-nil" (z/right (z/rightmost zd))
      'nil)
   (c "zip left-nil" (z/left zd)
      'nil)
   (c "zip edit-then-walk" (z/root (z/next (z/edit zd inc)))
      '[2 [2 [3 4] 5] 6])

   ;; `xml-zip`, over `clojure.xml`'s node shape -- which is the shape
   ;; `flint.data.xml` parses into, so the two compose.
   (c "zip xml down" (z/node (z/down zx))
      '{:tag :b, :attrs nil, :content ["hi"]})
   (c "zip xml text leaf" (z/node (z/down (z/down zx)))
      "hi")
   (c "zip xml right" (:tag (z/node (z/right (z/down zx))))
      :c)
   (c "zip xml edit" (z/root (z/edit (z/down zx) assoc :attrs {:k 1}))
      '{:tag :a, :attrs {:id "1"}, :content [{:tag :b, :attrs {:k 1}, :content ["hi"]} {:tag :c, :attrs nil, :content nil}]})
   (c "zip xml string is a leaf" (z/branch? (z/down (z/down zx)))
      false)
   (c "zip xml children" (z/children zx)
      '({:tag :b, :attrs nil, :content ["hi"]} {:tag :c, :attrs nil, :content nil}))
   (c "zip xml append-child" (z/root (z/append-child zx {:tag :d :attrs nil :content nil}))
      '{:tag :a, :attrs {:id "1"}, :content [{:tag :b, :attrs nil, :content ["hi"]} {:tag :c, :attrs nil, :content nil} {:tag :d, :attrs nil, :content nil}]})
   (c "zip xml empty content" (z/down (z/right (z/down zx)))
      nil)

   ;; --- clojure.data -----------------------------------------------------
   ;; `diff`: what each side has to itself and what they share. Expectations
   ;; are real Clojure's, including its shapes -- a seq for maps, a vector for
   ;; sequentials -- so a mismatch shows up rather than being papered over.
   (c "data ints" (dt/diff 1 1)
      '[nil nil 1])
   (c "data ints-ne" (dt/diff 1 2)
      '[1 2 nil])
   (c "data nil-nil" (dt/diff nil nil)
      '[nil nil nil])
   (c "data nil-1" (dt/diff nil 1)
      '[nil 1 nil])
   (c "data str" (dt/diff "a" "b")
      '["a" "b" nil])
   (c "data kw" (dt/diff :a :a)
      '[nil nil :a])
   (c "data map-same" (dt/diff {:a 1} {:a 1})
      '[nil nil {:a 1}])
   (c "data map-add" (dt/diff {:a 1} {:a 1 :b 2})
      '(nil {:b 2} {:a 1}))
   (c "data map-del" (dt/diff {:a 1 :b 2} {:a 1})
      '({:b 2} nil {:a 1}))
   (c "data map-chg" (dt/diff {:a 1} {:a 2})
      '({:a 1} {:a 2} nil))
   (c "data map-nested" (dt/diff {:a {:b 1 :c 2}} {:a {:b 1 :c 3}})
      '({:a {:c 2}} {:a {:c 3}} {:a {:b 1}}))
   (c "data map-nil-val" (dt/diff {:a nil} {:a nil})
      '[nil nil {:a nil}])
   (c "data map-nil-vs-missing" (dt/diff {:a nil} {})
      '({:a nil} nil nil))
   (c "data map-nil-vs-1" (dt/diff {:a nil} {:a 1})
      '({:a nil} {:a 1} nil))
   (c "data map-empty" (dt/diff {} {})
      '[nil nil {}])
   (c "data map-vs-nil" (dt/diff {:a 1} nil)
      '[{:a 1} nil nil])
   (c "data vec-same" (dt/diff [1 2] [1 2])
      '[nil nil [1 2]])
   (c "data vec-longer" (dt/diff [1 2] [1 2 3])
      '[nil [nil nil 3] [1 2]])
   (c "data vec-shorter" (dt/diff [1 2 3] [1 2])
      '[[nil nil 3] nil [1 2]])
   (c "data vec-chg" (dt/diff [1 2 3] [1 9 3])
      '[[nil 2] [nil 9] [1 nil 3]])
   (c "data vec-first" (dt/diff [1 2] [9 2])
      '[[1] [9] [nil 2]])
   (c "data vec-empty" (dt/diff [] [])
      '[nil nil []])
   (c "data vec-vs-empty" (dt/diff [1] [])
      '[[1] nil nil])
   (c "data vec-nested" (dt/diff [1 [2 3]] [1 [2 4]])
      '[[nil [nil 3]] [nil [nil 4]] [1 [2]]])
   (c "data vec-nil-hole" (dt/diff [nil 1] [nil 2])
      '[[nil 1] [nil 2] [nil]])
   (c "data list-vec" (dt/diff '(1 2) [1 2])
      '[nil nil (1 2)])
   (c "data list-list" (dt/diff '(1 2) '(1 3))
      '[[nil 2] [nil 3] [1]])
   (c "data seq" (dt/diff (range 3) [0 1 9])
      '[[nil nil 2] [nil nil 9] [0 1]])
   (c "data set-same" (dt/diff #{1 2} #{1 2})
      '[nil nil #{1 2}])
   (c "data set-add" (dt/diff #{1} #{1 2})
      '[nil #{2} #{1}])
   (c "data set-disjoint" (dt/diff #{1} #{2})
      '[#{1} #{2} nil])
   (c "data set-empty" (dt/diff #{} #{})
      '[nil nil #{}])
   (c "data map-vs-vec" (dt/diff {:a 1} [1])
      '[{:a 1} [1] nil])
   (c "data set-vs-vec" (dt/diff #{1} [1])
      '[#{1} [1] nil])
   (c "data vec-vs-num" (dt/diff [1] 1)
      '[[1] 1 nil])
   (c "data deep" (dt/diff {:a [1 {:b #{1 2}}]} {:a [1 {:b #{2 3}}]})
      '({:a [nil {:b #{1}}]} {:a [nil {:b #{3}}]} {:a [1 {:b #{2}}]}))
   (c "data map-keys-mixed" (dt/diff {1 :a "s" :b} {1 :a "s" :c})
      '({"s" :b} {"s" :c} {1 :a}))
   (c "data vec-of-maps" (dt/diff [{:a 1}] [{:a 2}])
      '[[{:a 1}] [{:a 2}] nil])
   (c "data nested-all-same" (dt/diff {:a {:b [1 2]}} {:a {:b [1 2]}})
      '[nil nil {:a {:b [1 2]}}])
   (c "data part-eq" [(dt/equality-partition {}) (dt/equality-partition []) (dt/equality-partition #{}) (dt/equality-partition 1) (dt/equality-partition nil) (dt/equality-partition '())]
      '[:map :sequential :set :atom :atom :sequential])

   ;; --- clojure.datafy ---------------------------------------------------
   ;; The default path: `datafy` is identity and `nav` returns the value it
   ;; was given. What a value can say for ITSELF is metadata-keyed, and those
   ;; cases live below with the rest of the protocol-metadata corpus.
   (c "datafy number" (df/datafy 1) 1)
   (c "datafy nil" (df/datafy nil) nil)
   (c "datafy map" (df/datafy {:a 1}) {:a 1})
   (c "datafy string" (df/datafy "hi") "hi")
   (c "datafy keyword" (df/datafy :k) :k)
   (c "datafy vector" (df/datafy [1 2]) [1 2])
   (c "nav map" (df/nav {:a 1} :a 1) 1)
   (c "nav vector" (df/nav [1 2] 0 1) 1)
   (c "nav seq, no key" (df/nav '(1 2) nil 1) 1)]))
