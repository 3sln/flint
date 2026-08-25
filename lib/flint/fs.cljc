(ns flint.fs
  "The filesystem, as a capability (`doc/decisions/0021`).

  Nothing here is privileged. It is an ordinary port opened by name, so a
  program the host granted nothing simply cannot open it -- which is the whole
  of the mechanism and the reason `0021` calls capabilities nearly free.

  Every path is relative to the ROOT the host granted. A path that escapes it
  comes back as an error rather than being clamped, because quietly rewriting a
  path answers a question nobody asked."
  (:require [flint.rpc :as rpc]
            [flint.port :as p]
            [flint.port.edn :as edn]))

(defn open
  "Open the filesystem capability `name`, or throw if the host refuses."
  ([] (open "fs"))
  ([name] {:client (rpc/client (p/open name {:codec edn/codec}))}))

(defn- ask
  "`rpc/call` already returns the reply's `:body` and already throws on an
  `:error`, so there is nothing to unwrap or to check here. Doing it anyway
  called `get` on a string and failed with `not a string`, which is a long way
  from what had gone wrong."
  [h req]
  (rpc/call (:client h) req))

(defn read-file [h path] (ask h {:op :read :path path}))
(defn exists? [h path] (ask h {:op :exists :path path}))
(defn list-dir [h path] (ask h {:op :list :path path}))
(defn write-file [h path body] (ask h {:op :write :path path :body body}))
(defn root [h] (ask h {:op :root}))

(defn walk
  "Every file under `path`, depth first, as root-relative paths. The host marks
  directories, so this does not have to ask again per entry."
  [h path]
  (loop [todo [path] out []]
    (if (empty? todo)
      out
      (let [d (first todo) es (list-dir h d)
            join (fn [n] (if (= d "") n (str d "/" n)))]
        (recur (into (vec (rest todo))
                     (mapv (fn [e] (join (:name e))) (filterv :dir es)))
               (into out (mapv (fn [e] (join (:name e))) (filterv (fn [e] (not (:dir e))) es))))))))
