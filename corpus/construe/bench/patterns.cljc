(ns construe.bench.patterns
  "The construe workload as separable parts, so a total can be explained.

  These are the operations the seed interpreter and the annotator actually do:
  building nested constraint objects, reading keyword-keyed maps, folding over
  span sequences, merging maps, and — the one most likely to hurt — splitting
  and matching text.

  Compiled by BOTH flint and cherry from this same file.")

;; --- deep nested map/vector construction: a constraint object is nested ------
(defn build-nested [n]
  (loop [i 0 acc []]
    (if (< i n)
      (recur (inc i)
             (conj acc {:field {:name "ingredients" :family "set"}
                        :polarity :requires
                        :spans [{:start i :end (+ i 4) :atom "pasta"
                                 :ancestry ["pasta" "ingredient"]}]
                        :meta {:clause i :covered (+ i 4)}}))
      (count acc))))

;; --- keyword-keyed map access: the dominant operation in these scripts ------
(defn read-keys [n]
  (let [m {:start 0 :end 5 :kind "atom" :atom "pasta"
           :ancestry ["pasta" "ingredient"] :payload nil :arity nil}]
    (loop [i 0 acc 0]
      (if (< i n)
        (recur (inc i)
               (+ acc (:start m) (:end m) (count (:kind m)) (count (:ancestry m))))
        acc))))

;; --- reduce / into / transients over span sequences -------------------------
(defn fold-spans [n]
  (let [spans (mapv (fn [i] {:start i :end (+ i 3) :atom (str "a" (mod i 97))}) (range n))]
    (count (reduce (fn [acc s] (assoc acc (:atom s) (:end s))) {} spans))))

(defn into-spans [n]
  (let [spans (mapv (fn [i] [(str "a" i) i]) (range n))]
    (count (into {} spans))))

;; --- large map merge: the ClojureDart work was taken for this ---------------
(defn merge-maps [n]
  (let [a (into {} (mapv (fn [i] [(str "k" i) i]) (range n)))
        b (into {} (mapv (fn [i] [(str "k" (+ i (quot n 2))) i]) (range n)))]
    (count (merge a b))))

;; --- string split and regex: the annotator's shape --------------------------
(defn split-words [text n]
  (loop [i 0 acc 0]
    (if (< i n)
      (recur (inc i) (+ acc (count (clojure.string/split text #"\s+"))))
      acc)))

(defn split-literal [text n]
  (loop [i 0 acc 0]
    (if (< i n)
      (recur (inc i) (+ acc (count (clojure.string/split text " "))))
      acc)))

(def sample "vegan pasta with no nuts under 20 minutes and nothing with cashews")

(defn run [which n]
  (cond
    (= which "nested") (build-nested n)
    (= which "keys") (read-keys n)
    (= which "fold") (fold-spans n)
    (= which "into") (into-spans n)
    (= which "merge") (merge-maps n)
    (= which "regex") (split-words sample n)
    (= which "split") (split-literal sample n)
    :else -1))
