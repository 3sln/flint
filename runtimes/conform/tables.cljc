(ns tables
  "Tables on all three runtimes (`DECISIONS.md#tables`).

  Everything a table is: the closed schema and its refusals, the row ref that
  materialises nothing, the constant column encoding, the sharing slice, the
  column API, migration, the transient, and printing. Diffed rather than
  asserted -- this file states no expected values, because the point is that
  the three runtimes AGREE, and a shared expectation would let all three be
  wrong together."
  (:require [flint.table :as ft]))

(def S (ft/schema [[:id :int] [:name :string] [:tag :keyword]]))
(def N 600)
(defn- row [i] {:id i :name (str "n" i) :tag (if (even? i) :a :b)})
(def T (ft/table S (mapv row (range N))))
(def S2 (ft/schema [[:id :int] [:name :string] [:tag :keyword] [:extra :int]]))
(def S3 (ft/schema [[:id :int] [:tag :keyword]]))

(defn- msg [f] (try (do (f) "no throw") (catch Exception e (ex-message e))))

(defn main [_]
  (pr-str
   {;; --- the value ------------------------------------------------------
    :count       (count T)
    :table?      [(ft/table? T) (ft/table? [{:id 1}]) (ft/table? nil)]
    :kind        (flint.rt/kind T)
    :row-kind    (flint.rt/kind (get T 0))
    :row-is-map  (map? (get T 3))
    :row-eq-map  (= {:id 3 :name "n3" :tag :b} (get T 3))
    :row-hash-eq (= (hash {:id 3 :name "n3" :tag :b}) (hash (get T 3)))
    :not-a-vec   (= T (mapv row (range N)))
    :eq-itself   (= T (ft/table S (mapv row (range N))))
    :hash-eq     (= (hash T) (hash (ft/table S (mapv row (range N)))))
    ;; --- reading --------------------------------------------------------
    :get         [(:id (get T 0)) (:name (get T 599)) (:tag (get T 1))]
    :row-count   (count (get T 0))
    :seq-first   (:id (first (ft/rows T)))
    :seq-last    (:id (last (ft/rows T)))
    :seq-len     (count (ft/rows T))
    :into-map    (into {} (get T 5))
    ;; --- columns --------------------------------------------------------
    :column      [(nth (ft/column T :id) 0) (nth (ft/column T :id) 599)]
    :column-len  (count (ft/column T :tag))
    :reduce-col  (ft/reduce-column T :id + 0)
    :slice       (let [s (ft/slice T 100 500)]
                   [(count s) (:id (get s 0)) (:id (get s 399)) (:name (get s 7))])
    :slice2      (let [s (ft/slice (ft/slice T 100 500) 50 60)] [(count s) (:id (get s 0))])
    :select      (let [s (ft/select T [:tag :id])]
                   [(ft/columns (ft/table-schema s)) (:tag (get s 4)) (count s)])
    ;; --- writing --------------------------------------------------------
    :conj        (let [t (ft/add-row T {:id 600 :name "n600" :tag :a})]
                   [(count t) (:id (get t 600)) (count T)])
    :assoc       (:name (get (ft/set-row T 2 {:id 2 :name "z" :tag :a}) 2))
    :unmoved     (:name (get T 2))
    :update      (:id (get (ft/update-row T 9 (fn [r] (assoc (into {} r) :id 99))) 9))
    :ref-assoc   (let [m (assoc (get T 0) :name "q")] [(ft/table? m) (map? m) (:name m)])
    ;; --- migration ------------------------------------------------------
    :migrate-add (let [m (ft/migrate T S2 {:extra 7})]
                   [(count m) (:extra (get m 0)) (:extra (get m 599)) (:name (get m 3))])
    :migrate-drop (let [m (ft/migrate T S3)]
                    [(count m) (ft/columns (ft/table-schema m)) (:name (get m 0)) (:id (get m 5))])
    :migrate-fn  (let [m (ft/migrate T S2 (fn [r] (assoc (into {} r) :extra (* 2 (:id r)))))]
                   [(count m) (:extra (get m 4))])
    ;; --- the transient --------------------------------------------------
    :build       (let [t (ft/build S (range 700) row)] [(count t) (:id (get t 699)) (:tag (get t 2))])
    :build-eq    (= T (ft/build S (range N) row))
    ;; --- printing -------------------------------------------------------
    :print-small (pr-str (ft/table S [{:id 1 :name "a" :tag :x}]))
    :human-small (print-str (ft/table S [{:id 1 :name "a" :tag :x}]))
    ;; --- the refusals ---------------------------------------------------
    :e-missing   (msg (fn [] (ft/add-row T {:id 1 :name "a"})))
    :e-extra     (msg (fn [] (ft/add-row T {:id 1 :name "a" :tag :x :zz 1})))
    :e-type      (msg (fn [] (ft/add-row T {:id 1 :name :notastring :tag :x})))
    :e-notmap    (msg (fn [] (ft/add-row T [1 2 3])))
    :e-key       (msg (fn [] (assoc T :id 1)))
    :e-range     (msg (fn [] (ft/set-row T 9999 {:id 1 :name "a" :tag :x})))
    :e-col       (msg (fn [] (ft/column T :nope)))
    :e-slice     (msg (fn [] (ft/slice T 0 99999)))
    :e-schema    (msg (fn [] (ft/schema [[:a :nosuchtype]])))
    :e-dup       (msg (fn [] (ft/schema [[:a :int] [:a :int]])))
    :e-mig-none  (msg (fn [] (ft/migrate T S2)))
    :e-mig-type  (msg (fn [] (ft/migrate T (ft/schema [[:id :string]]))))}))
