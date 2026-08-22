(ns clojure.set
  "clojure.set. Complete except for `index`, `rename` and `rename-keys`'s
  relational cousins -- see the manifest.")

(defn union
  ([] #{})
  ([s] s)
  ([s1 s2] (if (< (count s1) (count s2)) (reduce conj s2 s1) (reduce conj s1 s2)))
  ([s1 s2 & sets] (reduce union (union s1 s2) sets)))

(defn intersection
  ([s] s)
  ([s1 s2] (let [[small large] (if (< (count s1) (count s2)) [s1 s2] [s2 s1])]
             (reduce (fn [acc x] (if (contains? large x) acc (disj acc x))) small small)))
  ([s1 s2 & sets] (reduce intersection (intersection s1 s2) sets)))

(defn difference
  ([s] s)
  ([s1 s2] (reduce (fn [acc x] (if (contains? s2 x) (disj acc x) acc)) s1 s1))
  ([s1 s2 & sets] (reduce difference (difference s1 s2) sets)))

(defn select [pred s] (reduce (fn [acc x] (if (pred x) acc (disj acc x))) s s))

(defn project [xrel ks] (set (map (fn [m] (select-keys m ks)) xrel)))

(defn rename-keys [m kmap]
  (reduce-kv (fn [acc k v] (if (contains? kmap k) (assoc acc (get kmap k) v) (assoc acc k v)))
             {} m))

(defn rename [xrel kmap] (set (map (fn [m] (rename-keys m kmap)) xrel)))

(defn map-invert [m] (reduce-kv (fn [acc k v] (assoc acc v k)) {} m))

(defn index [xrel ks]
  (reduce (fn [acc x] (let [k (select-keys x ks)] (assoc acc k (conj (get acc k #{}) x))))
          {} xrel))

(defn join
  ([xrel yrel]
   (if (and (seq xrel) (seq yrel))
     (let [ks (vec (intersection (set (keys (first xrel))) (set (keys (first yrel)))))
           idx (index yrel ks)]
       (reduce (fn [acc x]
                 (let [found (get idx (select-keys x ks))]
                   (if found (reduce (fn [a y] (conj a (merge y x))) acc found) acc)))
               #{} xrel))
     #{}))
  ([xrel yrel km]
   (let [ks (vec (keys km))
         idx (index yrel (vec (vals km)))]
     (reduce (fn [acc x]
               (let [found (get idx (rename-keys (select-keys x ks) km))]
                 (if found (reduce (fn [a y] (conj a (merge y x))) acc found) acc)))
             #{} xrel))))

(defn subset? [s1 s2] (and (<= (count s1) (count s2)) (every? (fn [x] (contains? s2 x)) s1)))
(defn superset? [s1 s2] (subset? s2 s1))
