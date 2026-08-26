(ns flint.macros
  "The bootstrap macros: the ones the compiler must know before any Clojure has
  been compiled.

  Everything else -- `when`, `cond`, `->`, `for`, `doseq`, `defmulti` and the
  rest -- is an ordinary `defmacro` in `clojure/core.cljc`, expanded by running
  the macro function through `flint.eval`. Only what is needed to compile
  `defmacro` itself lives here, which keeps the compiler small and puts the
  language in the language.

  `destructure` is here because `let`, `fn` and `loop` all need it and it is
  pure form-to-form work."
  (:require [clojure.string :as str]
            [flint.canon :as canon]))

(defn- gsym [prefix] (gensym (str prefix "__")))

;; ---------------------------------------------------------- destructuring
;;
;; Follows clojure.core/destructure: sequential binding walks with nth/nthnext,
;; associative binding uses get, and :as/:or/:keys/:strs/:syms are supported.

(declare destructure-binding)

(defn- pb-vector [bvec b val]
  (let [gvec (gsym "vec")
        as (second (drop-while #(not= :as %) b))
        b* (take-while #(not= :as %) b)
        amp (count (take-while #(not= '& %) b*))
        fixed (vec (take amp b*))
        rest-b (when (< amp (count b*)) (nth (vec b*) (inc amp)))
        bvec (conj bvec gvec val)
        bvec (reduce (fn [acc [i sub]]
                       (destructure-binding acc sub (list 'clojure.core/nth gvec i nil)))
                     bvec
                     (map-indexed vector fixed))
        bvec (if rest-b
               (destructure-binding bvec rest-b (list 'clojure.core/nthnext gvec amp))
               bvec)]
    (if as (conj bvec as gvec) bvec)))

(defn- pb-map [bvec b val]
  (let [gmap (gsym "map")
        defaults (:or b)
        as (:as b)
        bvec (conj bvec gmap val)
        ;; Clojure allows destructuring a seq of key/value pairs as a map.
        bvec (conj bvec gmap (list 'if (list 'clojure.core/seq? gmap)
                                   (list 'clojure.core/apply 'clojure.core/hash-map gmap)
                                   gmap))
        bvec (if as (conj bvec as gmap) bvec)
        expand-key (fn [acc bk bv]
                     (cond
                       (= bk :keys)
                       (reduce (fn [a k]
                                 (let [s (symbol (name k))
                                       kw (if (namespace k) (keyword (namespace k) (name k))
                                              (keyword (name k)))]
                                   (destructure-binding
                                    a s (if (contains? defaults s)
                                          (list 'clojure.core/get gmap kw (get defaults s))
                                          (list 'clojure.core/get gmap kw)))))
                               acc bv)
                       (= bk :strs)
                       (reduce (fn [a k]
                                 (let [s (symbol (name k))]
                                   (destructure-binding
                                    a s (if (contains? defaults s)
                                          (list 'clojure.core/get gmap (name k) (get defaults s))
                                          (list 'clojure.core/get gmap (name k))))))
                               acc bv)
                       (= bk :syms)
                       (reduce (fn [a k]
                                 (let [s (symbol (name k))]
                                   (destructure-binding
                                    a s (if (contains? defaults s)
                                          (list 'clojure.core/get gmap (list 'quote k) (get defaults s))
                                          (list 'clojure.core/get gmap (list 'quote k))))))
                               acc bv)
                       (#{:or :as} bk) acc
                       :else
                       (destructure-binding
                        acc bk (if (and (symbol? bk) (contains? defaults bk))
                                 (list 'clojure.core/get gmap bv (get defaults bk))
                                 (list 'clojure.core/get gmap bv)))))]
    ;; Canonical order, not map order: the sequence of generated bindings fixes
    ;; local slot numbers, and slot numbers reach the output bytes. Two hosts
    ;; iterating this map differently is enough to break the fixpoint test.
    (reduce (fn [acc p] (expand-key acc (first p) (second p))) bvec (canon/sorted-entries b))))

(defn destructure-binding
  "Append bindings for pattern `b` bound to expression `val`."
  [bvec b val]
  (cond
    (symbol? b) (conj bvec b val)
    (vector? b) (pb-vector bvec b val)
    (map? b) (pb-map bvec b val)
    :else (throw (ex-info "unsupported binding form" {:form b}))))

(defn destructure
  "clojure.core/destructure: a binding vector with patterns to one with symbols."
  [bindings]
  (let [pairs (partition 2 bindings)]
    (if (every? symbol? (map first pairs))
      (vec bindings)
      (reduce (fn [acc [b v]] (destructure-binding acc b v)) [] pairs))))

;; --------------------------------------------------------------- fn params

(defn- simple-params? [params]
  (every? symbol? params))

(defn expand-fn-arity
  "Rewrite one `(params & body)` arity so its parameters are plain symbols,
  moving any destructuring into a `let*` around the body."
  [[params & body]]
  (if (simple-params? params)
    (cons (vec params) body)
    (let [gs (mapv (fn [p] (if (symbol? p) p (gsym "p"))) params)
          binds (reduce (fn [acc [p g]]
                          (if (symbol? p) acc (destructure-binding acc p g)))
                        []
                        (map vector params gs))]
      (if (seq binds)
        (list (vec gs) (list* 'let* binds body))
        (cons (vec gs) body)))))

(defn- split-variadic
  "Split `[a b & rest]` into [fixed rest-symbol]."
  [params]
  (let [i (count (take-while #(not= '& %) params))]
    (if (< i (count params))
      [(vec (take i params)) (nth (vec params) (inc i))]
      [(vec params) nil])))

(defn normalise-fn
  "Turn any `fn`/`fn*` form into `(fn* name? (params body...) ...)` with plain
  symbol parameters and `&` still marking the variadic tail."
  [form]
  (let [[_ & more] form
        [fname more] (if (symbol? (first more)) [(first more) (next more)] [nil more])
        arities (if (vector? (first more)) (list more) more)
        arities (map (fn [a]
                       (let [[params & body] a
                             ;; Checked, because the thing that gets here
                             ;; malformed is not usually a typo. A reader
                             ;; conditional selecting nothing DELETES the form
                             ;; it stood in, and rewrite-clj writes
                             ;; `(defn- f #?(:clj ^String [node] :cljs ...) ..)`
                             ;; -- so the argument vector itself vanishes and
                             ;; `(defn- f (let [..] ..))` reaches here. Without
                             ;; this the host threw `Don't know how to create
                             ;; ISeq from: clojure.lang.Symbol`, which names
                             ;; nothing a reader can act on.
                             _ (when-not (vector? params)
                                 (throw (ex-info
                                         (str "a fn arity needs an argument vector, got "
                                              (if (nil? params) "nothing" (pr-str params)))
                                         {:type :compile :form form :got params})))
                             [fixed restp] (split-variadic params)
                             all (if restp (conj fixed '& restp) fixed)
                             [p2 & b2] (expand-fn-arity (cons (vec (remove #{'&} all)) body))
                             p2 (if restp (conj (vec (butlast p2)) '& (last p2)) p2)]
                         (cons p2 b2)))
                     arities)]
    (if fname
      (list* 'fn* fname arities)
      (list* 'fn* arities))))

;; ---------------------------------------------------------- bootstrap macros
;;
;; Each takes the whole form and returns a form. `env` carries the namespace so
;; a macro can qualify what it emits.

(defn- m-let [[_ bindings & body] _]
  (list* 'let* (destructure bindings) body))

(defn- m-loop [[_ bindings & body] _]
  (let [pairs (partition 2 bindings)]
    (if (every? symbol? (map first pairs))
      (list* 'loop* (vec bindings) body)
      ;; Destructured loop bindings: bind the patterns outside, loop on gensyms.
      (let [gs (mapv (fn [_] (gsym "loop")) pairs)
            outer (vec (mapcat (fn [g [_ v]] [g v]) gs pairs))
            inner (vec (mapcat (fn [g [b _]] [b g]) gs pairs))]
        (list 'let* outer
              ;; `list`, not `list*`: the body here is ONE form, and list* would
              ;; splice its elements into the loop body.
              (list 'loop* (vec (mapcat (fn [g _] [g g]) gs pairs))
                    (list* 'let* (destructure inner) body)))))))

(defn- m-fn [form _] (normalise-fn form))

(defn- m-defn [[_ name & more] _]
  (let [[doc more] (if (string? (first more)) [(first more) (next more)] [nil more])
        [attrs more] (if (and (map? (first more)) (next more)) [(first more) (next more)] [nil more])
        m (cond-> (or attrs {}) doc (assoc :doc doc))
        m (merge m (meta name))
        fform (normalise-fn (list* 'fn name more))
        ;; `(defn ^int f ...)` is a claim about every arity's RETURN, and the
        ;; analyzer reads return tags off the argument vector -- so the tag is
        ;; pushed down here, once, rather than threaded through analysis. An
        ;; arity that states its own tag keeps it: `(defn ^number f (^int [x] ..)
        ;; (^float [x y] ..))` is two different, more precise claims.
        fform (if-let [t (:tag m)]
                (let [[hd & arities] fform
                      [nm arities] (if (symbol? (first arities))
                                     [[(first arities)] (rest arities)]
                                     [[] arities])]
                  (concat [hd] nm
                          (map (fn [[params & body]]
                                 (cons (if (:tag (meta params))
                                         params
                                         (vary-meta params assoc :tag t))
                                       body))
                               arities)))
                fform)]
    (list 'def (with-meta name m) fform)))

(defn- m-defn- [[_ name & more] env]
  (m-defn (list* 'defn (vary-meta name assoc :private true) more) env))

(defn- m-defmacro [[_ name & more] _]
  (let [[doc more] (if (string? (first more)) [(first more) (next more)] [nil more])
        m (cond-> (merge {} (meta name)) doc (assoc :doc doc))
        m (assoc m :macro true)
        ;; A macro function receives (&form &env args...).
        arities (if (vector? (first more)) (list more) more)
        arities (map (fn [[params & body]] (list* (into '[&form &env] params) body)) arities)
        fform (normalise-fn (list* 'fn name arities))]
    (list 'def (with-meta name m) fform)))

(defn- m-when [[_ test & body] _] (list 'if test (cons 'do body)))
(defn- m-comment [_ _] nil)

(def bootstrap
  "Macros the compiler knows without compiling anything."
  {'let m-let
   'loop m-loop
   'fn m-fn
   'defn m-defn
   'defn- m-defn-
   'defmacro m-defmacro
   'when m-when
   'comment m-comment})
