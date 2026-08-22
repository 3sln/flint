(ns flint.imgread
  "Decodes a program image far enough to diff two of them. Exists because
  'the images differ at byte N' is not actionable, and the self-hosting test
  needs to say WHICH constant or function diverged."
  (:require [clojure.string :as str]))

(defn- u32 [b i] (+ (nth b i) (* 256 (nth b (+ i 1))) (* 65536 (nth b (+ i 2)))
                    (* 16777216 (nth b (+ i 3)))))

(defn parse [b]
  (let [nnat (u32 b 12)
        after-nat (+ 16 (* 8 nnat))
        nconsts (u32 b after-nat)]
    (loop [i (+ after-nat 4) k 0 consts []]
      (if (= k nconsts)
        {:natives nnat :consts consts :consts-end i}
        (let [tag (nth b i)]
          (cond
            (<= tag 2) (recur (inc i) (inc k) (conj consts [i tag]))
            (= tag 3) (recur (+ i 9) (inc k) (conj consts [i :int]))
            (= tag 4) (recur (+ i 9) (inc k) (conj consts [i :double]))
            (= tag 5) (let [n (u32 b (inc i))]
                        (recur (+ i 5 n) (inc k)
                               (conj consts [i :string
                                             (apply str (map char (subvec (vec b) (+ i 5) (+ i 5 n))))])))
            (or (= tag 6) (= tag 7))
            (recur (+ i 9) (inc k) (conj consts [i (if (= tag 6) :keyword :symbol)
                                                 (u32 b (inc i)) (u32 b (+ i 5))]))
            (or (= tag 8) (= tag 9) (= tag 11))
            (let [n (u32 b (inc i))]
              (recur (+ i 5 (* 4 n)) (inc k)
                     (conj consts [i ({8 :vector 9 :list 11 :set} tag)
                                   (mapv #(u32 b (+ i 5 (* 4 %))) (range n))])))
            (= tag 10) (let [n (u32 b (inc i))]
                         (recur (+ i 5 (* 8 n)) (inc k)
                                (conj consts [i :map (mapv #(u32 b (+ i 5 (* 4 %))) (range (* 2 n)))])))
            (= tag 12) (recur (+ i 5) (inc k) (conj consts [i :fn (u32 b (inc i))]))
            (= tag 13) (recur (+ i 9) (inc k) (conj consts [i :native (u32 b (inc i))]))
            :else (throw (ex-info "bad const tag" {:tag tag :at i}))))))))

(defn parse-fns
  "Function table entries, starting at the offset `parse` reported."
  [b at]
  (let [n (u32 b at)]
    (loop [i (+ at 4) k 0 out []]
      (if (= k n)
        {:fns out :fns-end i}
        (let [namec (u32 b i)
              nup (nth b (+ i 4))
              na (nth b (+ i 5))
              arities (mapv (fn [j]
                              (let [o (+ i 6 (* j 12))]
                                {:argc (nth b o) :variadic (nth b (+ o 1))
                                 :nlocals (+ (nth b (+ o 2)) (* 256 (nth b (+ o 3))))
                                 :off (u32 b (+ o 4)) :len (u32 b (+ o 8))}))
                            (range na))]
          (recur (+ i 6 (* na 12)) (inc k)
                 (conj out {:name namec :nupvals nup :arities arities})))))))

(defn code-bytes
  "The bytecode section, given the offset just past the function table."
  [b at]
  (let [nvars (u32 b at)
        after-vars (+ at 4 (* 4 nvars))
        codelen (u32 b after-vars)]
    (subvec (vec b) (+ after-vars 4) (+ after-vars 4 codelen))))

(defn render [consts idx]
  (let [c (nth consts idx nil)]
    (if (nil? c)
      (str "?" idx)
      (let [t (second c)]
        (cond
          (= t :string) (pr-str (nth c 2))
          (or (= t :keyword) (= t :symbol))
          (str (if (= t :keyword) ":" "")
               (when (not= 4294967295 (nth c 2)) (str (render consts (nth c 2)) "/"))
               (render consts (nth c 3)))
          (= t 0) "nil" (= t 1) "true" (= t 2) "false"
          :else (str t))))))

(defn diff [a b]
  (let [pa (parse a) pb (parse b)
        ca (:consts pa) cb (:consts pb)
        n (min (count ca) (count cb))]
    {:count-a (count ca) :count-b (count cb)
     :first-differing
     (first (for [i (range n)
                  :let [x (nth ca i) y (nth cb i)]
                  :when (not= (rest x) (rest y))]
              {:index i
               :a (str (second x) " " (render ca i))
               :b (str (second y) " " (render cb i))}))
     :fns
     (let [fa (parse-fns a (:consts-end pa))
           fb (parse-fns b (:consts-end pb))
           la (:fns fa) lb (:fns fb)
           k (min (count la) (count lb))]
       {:count-a (count la) :count-b (count lb)
        :first (first (for [i (range k) :when (not= (nth la i) (nth lb i))]
                        {:index i
                         :name-a (render ca (:name (nth la i)))
                         :name-b (render cb (:name (nth lb i)))
                         :a (nth la i) :b (nth lb i)}))})}))
