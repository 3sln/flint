(ns control)
(defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(defn countdown [n] (loop [i n acc []] (if (= i 0) acc (recur (dec i) (conj acc i)))))

;; MUTUAL tail recursion, at a depth no host stack survives one frame per hop.
;; A tail call is a tail call whoever the callee is; a port that only optimises
;; SELF-recursion runs this until it overflows, and it takes minutes and a
;; multi-gigabyte stack to find out. That is how it presented on the JVM: the
;; flint compiler tail-calls between three of its own functions, so it ran for
;; minutes and then died in a trace of a million identical frames.
(declare odd-by-mutual?)
(defn- even-by-mutual? [n] (if (= n 0) true (odd-by-mutual? (dec n))))
(defn- odd-by-mutual? [n] (if (= n 0) false (even-by-mutual? (dec n))))
(defn main [_]
  (pr-str
   {:if [(if true :y :n) (if false :y :n) (if nil :y :n) (if 0 :y :n)]
    :recursion (fib 15)
    :loop (countdown 5)
    :higher [(mapv inc [1 2 3]) (filterv odd? [1 2 3 4]) (reduce + 0 [1 2 3])]
    :closure ((fn [a] (fn [b] (+ a b))) 10)
    :apply (apply + [1 2 3])
    :caught (try (throw (ex-info "boom" {:a 1})) (catch Throwable e (ex-message e)))
    :ex-data (try (throw (ex-info "b" {:a 1})) (catch Throwable e (ex-data e)))
    :try-body (try 1 (catch Throwable e 2))
    :mutual-tail [(even-by-mutual? 300000) (odd-by-mutual? 300001)]

    ;; `seq?` is not `seqable?`. A vector, a map, a set and a string are all
    ;; seqABLE and none of them IS a seq. Conflating the two is invisible until
    ;; something dispatches on it: flint's own analyzer tests `seq?` before
    ;; `vector?`, so an argument vector was read as a CALL and the compiler
    ;; descended into its own head forever.
    :seq? [(seq? [1 2]) (seq? {:a 1}) (seq? #{1}) (seq? "ab") (seq? nil)
           (seq? '(1 2)) (seq? (seq [1 2])) (seq? (rest [1 2 3])) (seq? (map inc [1]))]
    :sequential? [(sequential? [1 2]) (sequential? '(1 2)) (sequential? {:a 1})
                  (sequential? #{1}) (sequential? "ab") (sequential? nil)]}))
