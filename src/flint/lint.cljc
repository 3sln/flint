(ns flint.lint
  "What was written for flint, and does nothing.

  Metadata is an OPEN MAP. Anyone may put anything in it -- another library,
  a documentation generator, a project that has its own idea of what
  `:result-inverts` means -- so the compiler reads the keys it owns and is
  silent about the rest. That is the right default and it has a cost: a
  declaration aimed at flint and spelled slightly wrong is carried, never read,
  and never complained about. The program stays correct and merely misses the
  optimisation, which is indistinguishable from the optimisation working.

  This is the opt-in half. `flint check` walks the source and reports what
  looks meant for flint and is not doing anything. It is deliberately a
  separate pass rather than a compiler warning: warnings in a build that prints
  other things are warnings nobody reads, and this one is allowed to be wrong
  about intent, which a compiler is not."
  (:require [flint.types :as ty]))

(def flint-keys
  "The function metadata flint reads. Namespaced because it is flint's own."
  #{:flint/result-projected-meta :flint/result-inverts})

(def ^:private bare
  "The un-namespaced spelling of each, which flint does NOT read. Someone else
  may legitimately own these names, so this is a suspicion, not a verdict --
  which is exactly why it is reported here and not thrown by the compiler."
  {:result-projected-meta :flint/result-projected-meta
   :result-inverts        :flint/result-inverts})

(defn- meta-of [x]
  (when (or (symbol? x) (seq? x) (vector? x) (map? x) (set? x)) (meta x)))

(defn- findings-for-meta [m file line]
  (when (map? m)
    (let [at {:file file :line line}]
      (concat
       ;; A tag flint does not know is NOT necessarily a mistake. A `.cljc`
       ;; file that Clojure compiles too carries hints for THAT host -- flint's
       ;; own source has twenty-nine `^bytes` for babashka -- and they are
       ;; correct there and meaningless here. So this is a NOTE rather than a
       ;; finding and does not fail the check. The checker cannot know intent,
       ;; and pretending otherwise buries the half that is unambiguous.
       (when-let [t (:tag m)]
         (when-not (ty/tag t)
           [{:kind :host-tag :note? true
             :what (str "^" (pr-str t))
             :why (str "flint does not know this type, so the annotation is "
                       "carried and never checked here.")
             :at at}]))
       ;; The bare spelling of a key flint owns a namespaced version of.
       ;; Someone else may legitimately own the name, so it is reported rather
       ;; than thrown -- but it is reported, because if it WAS meant for flint
       ;; nothing else will ever say so.
       (for [[k q] bare :when (contains? m k)]
         {:kind :bare-key
          :what (str k)
          :why (str "flint reads " q ", not this. If it was meant for flint it "
                    "is doing nothing; if it belongs to another tool, ignore "
                    "this.")
          :at at})
       ;; A typo inside flint's own namespace, which cannot belong to anyone
       ;; else. This is the unambiguous one.
       (for [k (keys m)
             :when (and (keyword? k) (= "flint" (namespace k))
                        (not (contains? flint-keys k)))]
         {:kind :unknown-flint-key
          :what (str k)
          :why (str "flint reads no such key. It reads: "
                    (clojure.string/join " " (sort (map str flint-keys))))
          :at at})))))

(defn- walk [form file line acc]
  ;; The line is carried DOWN rather than read off the form that carries the
  ;; finding: the reader records a line on the enclosing form, and the metadata
  ;; map on a `defn`'s name symbol has none of its own. Without this every
  ;; finding reports the file and nothing more, which for a checker is most of
  ;; the value gone.
  (let [line (or (:line (meta-of form)) line)
        acc (into acc (findings-for-meta (meta-of form) file line))]
    (cond
      (or (seq? form) (vector? form) (set? form))
      (reduce (fn [a x] (walk x file line a)) acc form)
      (map? form)
      (reduce (fn [a e] (walk (val e) file line (walk (key e) file line a))) acc form)
      :else acc)))

(defn finding?
  "A FINDING names flint, or nearly does, and is something to act on. A NOTE is
  something flint ignores which may well be meant for another host."
  [f]
  (not (:note? f)))

(defn check
  "Findings and notes for one file's forms."
  [forms file]
  (reduce (fn [acc f] (walk f file nil acc)) [] forms))
