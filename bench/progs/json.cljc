(ns json (:require [flint.data.json :as json]))
(defn payload [n]
  (json/write-str (mapv (fn [i] {:id i :name (str "item-" i) :tags ["a" "b"] :ok (even? i)})
                        (range n))))
(defn main [args]
  (let [n (if (seq args) (parse-long (first args)) 2000)
        s (payload n)
        parsed (json/read-str s :key-fn keyword)]
    (str (count parsed) " " (:name (nth parsed (quot n 2))) " " (count s))))
