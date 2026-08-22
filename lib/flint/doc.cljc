(ns flint.doc
  "A document resource: structure in memory, content on demand.

  Paging a document assumes extraction is a linear scan. It is not — you read
  the structure, find the table, and read *that* table's cells. Most of a fifty
  page scan is irrelevant to `{merchant, total, lines}`, and paging pays for all
  of it (`doc/decisions/0008`).

  So the division is:

  * **Structure is loaded once, into flint memory.** Node id, type, page, box,
    parent, children. It is a few percent of the bytes, and every interesting
    query runs against it — so `children`, `select` and a whole tree walk are
    **ordinary in-memory Clojure at full speed and generate zero port traffic**.
    Making `children` a message would turn a walk over five hundred nodes into
    five hundred round trips.
  * **Content is fetched only when something asks for it**, and always in
    batches.

  ```clojure
  (let [d (doc/open port)]
    (doc/select d (fn [n] (= \"td\" (:type n))))   ; no port traffic at all
    (doc/content d (map :id cells)))              ; one batched request
  ```

  ## Ask for everything you need at once

  `(doc/content d ids)` is plural from the start because behind the host is
  object storage, and **a fetch per node is a network round trip per node**.
  Gathering what a pass needs before asking is the difference between one
  request and four hundred. Requesting one node at a time is the failure mode
  this design exists to avoid, and it is the easy thing to write.

  ## The caller states intent; the HOST plans

  You ask for *pieces*. You do not ask for byte ranges and you do not decide how
  many requests to make: only the host knows the storage's latency and bandwidth
  and the memory budget, so only the host can plan. The same script then runs
  well against object storage, a local disk or a fixture, without knowing which.

  ## Memory is proportional to what you keep

  The driver **does not cache**. Nothing is retained for you, so peak memory
  follows the content you hold on to, not the size of the document. There is
  deliberately no weak-reference cache either: a cache whose contents depend on
  when a collection ran would make behaviour depend on GC timing, and that costs
  the determinism the scheduler was built to have.

  An ask larger than the host's budget is answered **in waves**. `content-each`
  is the shape that keeps peak memory to one wave; `content` collects them all
  and is therefore only for asks you know are small."
  (:require [flint.rpc :as rpc]))

(defn open
  "Load the document's structure through `port` and return a handle. One request;
  everything after it that only looks at structure costs nothing."
  [port]
  (let [c (rpc/client port)
        s (rpc/call c {:op :structure})
        by-id (reduce (fn [m n] (assoc m (:id n) n)) {} (:nodes s))]
    {:client c
     :root (:root s)
     :by-id by-id
     :order (mapv :id (:nodes s))}))

(defn close [d] (rpc/close (:client d)) nil)

;; --- structure: all of this is in memory, and none of it touches the port ---

(defn- id-of
  "Accept a node or an id, because passing `(root d)` to `children` is the
  obvious thing to write and silently returning nothing would be a poor answer."
  [x]
  (if (map? x) (:id x) x))

(defn node [d id] (get (:by-id d) (id-of id)))
(defn root [d] (node d (:root d)))
(defn node-count [d] (count (:by-id d)))

(defn children
  "The child nodes of `id`, in document order."
  [d id]
  (mapv (fn [c] (node d c)) (:children (node d id))))

(defn descendants
  "Every node under `id`, depth first, `id` first."
  [d id]
  (loop [stack [(id-of id)] out []]
    (if (empty? stack)
      out
      (let [cur (peek stack)
            n (node d cur)]
        (recur (into (pop stack) (reverse (:children n))) (conj out n))))))

(defn select
  "Every node the predicate accepts, in document order."
  [d pred]
  (filterv pred (map (fn [id] (node d id)) (:order d))))

;; --- content: batched, planned by the host, delivered in waves --------------

(defn content-each
  "Fetch the content of `ids` and call `f` with each **wave** — a vector of
  `[id text]` pairs, in the order asked — releasing it before the next arrives.
  Peak memory is one wave, not the whole answer. Returns the number of waves."
  [d ids f]
  (rpc/call-each (:client d) {:op :content :nodes (vec ids)} f))

(defn content
  "Fetch the content of `ids` as one map of id to text.

  Collects every wave, so this holds the whole answer at once: use it when you
  know the ask is small, and `content-each` when the point is to stay inside a
  budget."
  [d ids]
  (reduce (fn [m wave] (reduce (fn [m pair] (assoc m (first pair) (second pair))) m wave))
          {}
          (rpc/call-seq (:client d) {:op :content :nodes (vec ids)})))

(defn text
  "The text of one node. A convenience for exploration — in a real pass, gather
  the ids and ask once, because one request per node is one round trip per node."
  [d id]
  (get (content d [id]) id))
