(ns flint.data.html
  "HTML, ours, and a **documented subset**.

  Same node shape as `flint.data.xml`:

      {:tag :div :attrs {:class \"x\"} :content [...]}

  with tag and attribute names lower-cased.

  ## What it handles
  Unquoted attribute values, bare `&`, mixed-case tags, attributes with no
  value (which become `{:disabled \"disabled\"}`), void elements that never take
  children, unclosed elements (closed at end of input), and an end tag closing
  the nearest matching open element -- so `<p>a<p>b` and `<ul><li>x<li>y` come
  out the way you expect.

  ## What it does NOT handle
  It is not a spec-complete HTML5 parser and does not try to be. Specifically:
  no implied `<html>`/`<head>`/`<body>`, no `<table>` foster parenting, no
  raw-text mode for `<script>` and `<style>` (their contents are tokenised as
  markup), no character-entity decoding beyond the tokenizer's, and no adoption
  agency algorithm for misnested inline elements. The README says the same thing
  in one place, and means it: if you need a conforming parse tree, this is the
  wrong tool and you should be told so before you start."
  (:require [flint.data.xml :as xml]
            [clojure.string :as str]))

(defn parse
  "Parse HTML. Returns a vector of top-level nodes."
  [s]
  (flint.rt/html-parse s))

(defn parse-one [s] (first (filter map? (parse s))))

(def element? xml/element?)
(def text xml/text)
(def elements xml/elements)
(def select xml/select)
(def attr xml/attr)
(def emit-str xml/emit-str)

(defn all-elements
  "Every element in the tree, depth first."
  [node]
  (cond
    (element? node) (cons node (mapcat all-elements (:content node)))
    (sequential? node) (mapcat all-elements node)
    :else []))

(defn find-by-id [node id]
  (first (filter (fn [e] (= id (attr e :id))) (all-elements node))))

(defn find-by-class [node cls]
  (filter (fn [e] (let [c (attr e :class)]
                    (and c (some (fn [x] (= x cls)) (str/split c " ")))))
          (all-elements node)))

(defn find-by-tag [node tag]
  (filter (fn [e] (= tag (:tag e))) (all-elements node)))
