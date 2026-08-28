(ns control)
(defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(defn countdown [n] (loop [i n acc []] (if (= i 0) acc (recur (dec i) (conj acc i)))))
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
    :try-body (try 1 (catch Throwable e 2))}))
