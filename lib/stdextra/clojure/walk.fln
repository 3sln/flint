(ns clojure.walk
  "clojure.walk. `walk` and its family, minus `macroexpand-all` -- a flint module
  carries no compiler, so there is nothing to expand with.")

(defn walk [inner outer form]
  (cond
    (list? form) (outer (apply list (map inner form)))
    (map? form) (outer (into {} (map (fn [e] [(inner (key e)) (inner (val e))]) form)))
    (vector? form) (outer (mapv inner form))
    (set? form) (outer (set (map inner form)))
    (seq? form) (outer (doall (map inner form)))
    :else (outer form)))

(defn postwalk [f form] (walk (fn [x] (postwalk f x)) f form))
(defn prewalk [f form] (walk (fn [x] (prewalk f x)) identity (f form)))

(defn postwalk-replace [smap form]
  (postwalk (fn [x] (if (contains? smap x) (get smap x) x)) form))
(defn prewalk-replace [smap form]
  (prewalk (fn [x] (if (contains? smap x) (get smap x) x)) form))

(defn keywordize-keys [m]
  (postwalk (fn [x] (if (map? x)
                      (into {} (map (fn [e] [(if (string? (key e)) (keyword (key e)) (key e))
                                             (val e)]) x))
                      x))
            m))

(defn stringify-keys [m]
  (postwalk (fn [x] (if (map? x)
                      (into {} (map (fn [e] [(if (keyword? (key e)) (name (key e)) (key e))
                                             (val e)]) x))
                      x))
            m))
