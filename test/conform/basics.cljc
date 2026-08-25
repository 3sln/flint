(ns conform.basics
  "Conformance: real Clojure expressions, checked against real Clojure answers.
  Each entry is [label expr-result expected]; the harness compares with = and
  reports every failure rather than stopping at the first.")

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

(defn cases []
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
   (c "juxt" ((juxt inc dec) 5) [6 4])])
