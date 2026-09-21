(ns kwrow
  "Reading a table row BY KEYWORD, which is how a table is meant to be read.

  `(:name row)` and `(get row :name)` are the same lookup by two spellings.
  Native's `apply_keyword` has a `table_ref` arm and its own comment records
  that the arm used to fall through for everything that was not a map or a
  set, so `(get x :tag)` answered and `(:tag x)` did not. The ports' `lookup`
  received the `tagged` half of that fix and not the `table_ref` half."
  (:require [flint.table :as ft]))

(def S (ft/schema [[:id :int] [:name :string]]))

(defn main [_]
  (let [t (ft/build S [0 1] (fn [i] {:id i :name (str "n" i)}))
        row (first (ft/rows t))]
    (str "get=" (pr-str (get row :name))
         " kw=" (pr-str (:name row))
         " getid=" (pr-str (get row :id))
         " kwid=" (pr-str (:id row)))))
