(ns clojure.zip
  "Functional zippers, after Huet. A LOCATION is a vector `[node path]` whose
  METADATA carries the three functions that make a tree a tree: is this a
  branch, what are its children, and how do I rebuild one.

  Ported because the Clojars survey named it: `clojure.zip` unblocks more
  third-party code than transitive maven resolution would have, which is why
  that half of `0021` is cancelled and this is here instead.

  The shape is Clojure's, deliberately, and `test/conform/basics.cljc` checks
  it against real Clojure's answers rather than against a reading of the
  docstrings.")

(defn zipper
  "A zipper over `root`, given how to ask whether a node is a branch, how to
  get its children as a seq, and how to build one from a node and children."
  [branch? children make-node root]
  (with-meta [root nil]
             {:zip/branch? branch? :zip/children children :zip/make-node make-node}))

(defn seq-zip
  "A zipper over a nested sequence."
  [root]
  (zipper seq?
          identity
          (fn [node children] (with-meta children (meta node)))
          root))

(defn vector-zip
  "A zipper over a nested vector."
  [root]
  (zipper vector?
          seq
          (fn [node children] (with-meta (vec children) (meta node)))
          root))

(defn xml-zip
  "A zipper over an XML tree in `clojure.xml`'s shape: a node is a map with
  `:tag`, `:attrs` and `:content`, and a string is a leaf.

  `flint.data.xml` parses into that shape, so the two compose without either
  knowing about the other."
  [root]
  (zipper (complement string?)
          (comp seq :content)
          (fn [node children] (assoc node :content (and children (vec children))))
          root))

(defn node "The node at this location." [loc] (nth loc 0))

(defn branch? "Is the node at this location a branch?" [loc]
  ((:zip/branch? (meta loc)) (node loc)))

(defn children "The children of the branch at this location." [loc]
  (if (branch? loc)
    ((:zip/children (meta loc)) (node loc))
    (throw (ex-info "called children on a leaf node" {}))))

(defn make-node "A branch like `node` but with `children`." [loc node children]
  ((:zip/make-node (meta loc)) node children))

(defn path "The nodes from the root down to, but not including, this one." [loc]
  (:pnodes (nth loc 1)))

(defn lefts "The siblings to the left, in order." [loc]
  (seq (:l (nth loc 1))))

(defn rights "The siblings to the right, in order." [loc]
  (:r (nth loc 1)))

(defn down "The leftmost child, or nil at a leaf or an empty branch." [loc]
  (when (branch? loc)
    (let [cs (children loc)
          p (nth loc 1)]
      (when (seq cs)
        (with-meta [(first cs)
                    {:l [] :pnodes (if p (conj (:pnodes p) (node loc)) [(node loc)])
                     :ppath p :r (next cs)}]
                   (meta loc))))))

(defn up "The parent, rebuilt if anything below changed; nil at the root." [loc]
  (let [p (nth loc 1)]
    (when (and p (:pnodes p))
      (let [pnode (peek (:pnodes p))
            ppath (:ppath p)]
        (with-meta
          (if (:changed? p)
            [(make-node loc pnode (concat (:l p) (cons (node loc) (:r p))))
             (if ppath (assoc ppath :changed? true) nil)]
            [pnode ppath])
          (meta loc))))))

(defn root "Rewind to the top and return the whole tree." [loc]
  (if (= :end (nth loc 1))
    (node loc)
    (let [p (up loc)]
      (if p (root p) (node loc)))))

(defn right "The sibling to the right, or nil." [loc]
  (let [p (nth loc 1) r (:r p)]
    (when (and p (seq r))
      (with-meta [(first r)
                  (assoc p :l (conj (:l p) (node loc)) :r (next r))]
                 (meta loc)))))

(defn left "The sibling to the left, or nil." [loc]
  (let [p (nth loc 1) l (:l p)]
    (when (and p (seq l))
      (with-meta [(peek l)
                  (assoc p :l (pop l) :r (cons (node loc) (:r p)))]
                 (meta loc)))))

(defn rightmost "The last sibling, or this location if already there." [loc]
  (let [p (nth loc 1) r (:r p)]
    (if (and p (seq r))
      (with-meta [(last r)
                  (assoc p :l (apply conj (:l p) (node loc) (butlast r)) :r nil)]
                 (meta loc))
      loc)))

(defn leftmost "The first sibling, or this location if already there." [loc]
  (let [p (nth loc 1) l (:l p)]
    (if (and p (seq l))
      (with-meta [(first l)
                  (assoc p :l [] :r (concat (rest l) [(node loc)] (:r p)))]
                 (meta loc))
      loc)))

(defn insert-left "A new sibling to the left. Stays where it is." [loc item]
  (let [p (nth loc 1)]
    (if (nil? p)
      (throw (ex-info "insert-left at the root" {}))
      (with-meta [(node loc) (assoc p :l (conj (:l p) item) :changed? true)]
                 (meta loc)))))

(defn insert-right "A new sibling to the right. Stays where it is." [loc item]
  (let [p (nth loc 1)]
    (if (nil? p)
      (throw (ex-info "insert-right at the root" {}))
      (with-meta [(node loc) (assoc p :r (cons item (:r p)) :changed? true)]
                 (meta loc)))))

(defn replace "This node, replaced." [loc n]
  (let [p (nth loc 1)]
    (with-meta [n (if p (assoc p :changed? true) p)] (meta loc))))

(defn edit "This node, replaced by `(f node args)`." [loc f & args]
  (replace loc (apply f (node loc) args)))

(defn insert-child "A new leftmost child of this branch." [loc item]
  (replace loc (make-node loc (node loc) (cons item (children loc)))))

(defn append-child "A new rightmost child of this branch." [loc item]
  (replace loc (make-node loc (node loc) (concat (children loc) [item]))))

(defn end? "Has a depth-first walk finished?" [loc]
  (= :end (nth loc 1)))

(defn next
  "Depth-first, left to right. At the end the location answers `end?` and
  stays put, so a `loop` that does not check terminates anyway."
  [loc]
  (if (end? loc)
    loc
    (or (and (branch? loc) (down loc))
        (right loc)
        (loop [p loc]
          (if-let [u (up p)]
            (or (right u) (recur u))
            (with-meta [(node p) :end] (meta p)))))))

(defn prev
  "Depth-first in reverse: the rightmost descendant of the left sibling, or
  the parent."
  [loc]
  (if-let [lloc (left loc)]
    (loop [l lloc]
      (if-let [child (and (branch? l) (down l))]
        (recur (rightmost child))
        l))
    (up loc)))

(defn remove
  "Remove this node, landing on the one a depth-first walk would have
  reached before it."
  [loc]
  (let [p (nth loc 1)]
    (if (nil? p)
      (throw (ex-info "remove at the root" {}))
      (if (seq (:l p))
        (loop [l (with-meta [(peek (:l p)) (assoc p :l (pop (:l p)) :changed? true)]
                            (meta loc))]
          (if-let [child (and (branch? l) (down l))]
            (recur (rightmost child))
            l))
        (with-meta [(make-node loc (peek (:pnodes p)) (:r p))
                    (if (:ppath p) (assoc (:ppath p) :changed? true) (:ppath p))]
                   (meta loc))))))
