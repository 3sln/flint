(ns flint.data.xml
  "XML, ours. An element is

      {:tag :name :attrs {:key \"value\"} :content [...]}

  which is `clojure.data.xml`'s shape closely enough to guess. `content` holds
  strings and child elements in document order.

  Parsing is adapted from the `xmlparser` crate (no_std, streaming); flint
  values are built as tokens arrive, with no intermediate document tree.

  See the README for what is dropped: the XML declaration, comments, DOCTYPE,
  processing instructions, and namespace *resolution* (a prefixed name comes
  through as `:prefix/local`, unresolved)."
  (:require [clojure.string :as str]))

(defn parse-str
  "Parse XML. Returns a vector of top-level nodes; use `parse-one` if you expect
  a single root element."
  [s]
  (flint.rt/xml-parse s))

(defn parse-one [s]
  (first (filter map? (parse-str s))))

(defn element? [x] (and (map? x) (contains? x :tag)))

(defn- escape [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- escape-attr [s]
  (-> (escape s) (str/replace "\"" "&quot;")))

(defn- tag-name [t]
  (if (keyword? t)
    (if (namespace t) (str (namespace t) ":" (name t)) (name t))
    (str t)))

(defn emit-str
  "Render a node (or a sequence of nodes) back to XML."
  [node]
  (cond
    (nil? node) ""
    (string? node) (escape node)
    (element? node)
    (let [n (tag-name (:tag node))
          attrs (flint.rt/str-join
                 (mapv (fn [e] (str " " (tag-name (key e)) "=\"" (escape-attr (str (val e))) "\""))
                       (:attrs node)))
          content (:content node)]
      (if (empty? content)
        (str "<" n attrs "/>")
        (flint.rt/str-join
         (concat [(str "<" n attrs ">")] (map emit-str content) [(str "</" n ">")]))))
    (sequential? node) (flint.rt/str-join (map emit-str node))
    :else (escape (str node))))

(defn element
  "Build an element node."
  ([tag] (element tag {} []))
  ([tag attrs] (element tag attrs []))
  ([tag attrs content] {:tag tag :attrs attrs :content (vec content)}))

(defn text
  "All character data under `node`, concatenated."
  [node]
  (cond
    (string? node) node
    (element? node) (flint.rt/str-join (map text (:content node)))
    (sequential? node) (flint.rt/str-join (map text node))
    :else ""))

(defn elements
  "Child elements of `node`, optionally filtered by tag."
  ([node] (filter element? (:content node)))
  ([node tag] (filter (fn [c] (and (element? c) (= tag (:tag c)))) (:content node))))

(defn select
  "Every element in the tree with `tag`, depth first."
  [node tag]
  (let [here (if (and (element? node) (= tag (:tag node))) [node] [])
        kids (cond
               (element? node) (:content node)
               (sequential? node) node
               :else [])]
    (concat here (mapcat (fn [c] (select c tag)) kids))))

(defn attr [node k] (get (:attrs node) k))
