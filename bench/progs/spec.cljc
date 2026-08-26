(ns spec
  "The same arithmetic twice: once where the compiler can prove the operands
  are integers, and once where it cannot. Nothing else differs -- same shape,
  same number of operations, same answer -- so the difference is the
  specialisation and not the program.

  The unproven side is not a straw man. It is what every Clojure program looks
  like today: a parameter with no annotation, arithmetic on it, and a compiler
  that has to assume the worst at each step.")

(defn generic [n]
  (loop [i 0 acc 0]
    (if (flint.rt/lt i n)
      (recur (flint.rt/add i 1)
             (flint.rt/sub (flint.rt/add acc (flint.rt/mul i 3)) 1))
      acc)))

(defn specialised [^int n]
  (loop [^int i 0 ^int acc 0]
    (if (flint.rt/lt i n)
      (recur (flint.rt/add i 1)
             (flint.rt/sub (flint.rt/add acc (flint.rt/mul i 3)) 1))
      acc)))

(defn main [args]
  (let [what (first args)
        n (flint.rt/str->num (second args))]
    (cond
      (= what "generic") (flint.rt/num->str (generic n))
      (= what "specialised") (flint.rt/num->str (specialised n))
      :else (flint.rt/num->str (if (= (generic 1000) (specialised 1000)) 1 0)))))
