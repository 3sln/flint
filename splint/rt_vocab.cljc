(ns flint.impl.rt
  "The vocabulary the runtime's value operations are written in.

  Where the codec had a byte sink and murmur had integers, this has the
  RUNTIME: a `Value`, a heap, type tags, and the methods that read them. It is
  the vocabulary the bulk of `doc/goals/splint-port.md` needs, so it is built
  around the two differences that kept the bulk out of reach.

  **The receiver.** Rust puts these on `impl Rt` and reaches the runtime as
  `self`; the JVM and CLR make them statics that take an `Rt` argument. Same
  function, three framings. `^:method` in `flint.impl.core` says so once.

  **The type tags.** `TY_CONS` is imported unqualified in Rust and Java and is
  `Obj.TyCons` in C#, which pascalises. There are a dozen of them and they
  appear all over the runtime, so they are named here once rather than at
  every use."
  (:require [flint.splint :as sp]
            [flint.impl.core :as core]
            [clojure.string :as str]))

(def Rt
  "The runtime. A receiver in all three -- explicit on the JVM and CLR, `self`
  in Rust -- which is what `^:method` exists to hide."
  {:name 'Rt :types {:rust "Rt" :java "Rt" :csharp "Rt"} :methods {}})

(def Value
  "A NaN-boxed value: 64 bits everywhere, a newtype in Rust and a bare `long`
  in the other two."
  {:name 'Value :types {:rust "Value" :java "long" :csharp "long"} :methods {}})

(def Cat
  "A category: `CAT_SCALAR` and friends. Rust made it a `u8` and the other two
  an `int`, and nothing depends on the width."
  {:name 'Cat :types {:rust "u8" :java "int" :csharp "int"} :methods {}})

(def Bool {:name 'Bool :types {:rust "bool" :java "boolean" :csharp "bool"} :methods {}})
(def I32 {:name 'I32 :types {:rust "u32" :java "int" :csharp "int"} :methods {}})

(def tags-for {'Rt Rt 'Value Value 'Cat Cat 'Bool Bool 'I32 I32})

(defn- t [ctx] (:target ctx))

(def type-tags
  "The heap type tags, and how each target spells one.

  Rust and Java import them unqualified; C# pascalises them onto `Obj`. This
  is a NAME table rather than a form table -- they are values, not calls, and
  the difference matters because a name can appear in a `case` label where a
  call cannot."
  '[TY_CONS TY_EMPTY_LIST TY_LAZYSEQ TY_VECSEQ TY_STRSEQ TY_RANGE TY_VEC
    TY_MAPENTRY TY_ARRAYMAP TY_HASHMAP TY_TABLEREF TY_SET TY_STR TY_KEYWORD
    TY_SYMBOL])

(defn- csharp-tag
  "`TY_EMPTY_LIST` -> `Obj.TyEmptyList`."
  [sym]
  (str "Obj." (str/join (mapv str/capitalize (str/split (str/lower-case (str sym)) #"_")))))

(def names-for
  "Every type tag, spelled three ways. A NAME rather than a form, because a
  tag appears in a `case` label where a call cannot go."
  (reduce (fn [m sym] (assoc m sym {:rust (str sym) :java (str sym)
                                    :csharp (csharp-tag sym)}))
          {} type-tags))

(defn- case-form
  "`(case expr [tags...] value ... :else value)`. A form that RETURNS.

  The arms are VALUES, not statements, and that is forced rather than chosen.
  A Rust match arm is an expression, so writing `return X;` inside one emits
  `=> return CAT_MAP;,` -- which is what the first attempt did. The JVM and
  CLR need the opposite: a `case` label cannot yield a value, so each arm has
  to `return` for itself.

  So the source says the VALUE and each target spends what it must: Rust wraps
  the whole match in one `return`, the other two put a `return` in every arm.
  This is the statement/expression split the design anticipated, arriving in
  the first place it actually bites."
  [ctx form]
  (let [[_ subject & clauses] form
        ;; A `(comment ...)` between arms is emitted where it stands and does
        ;; NOT consume an arm. Without this a comment could only sit outside
        ;; the switch, which is not where it explains anything.
        pairs (loop [cs clauses acc []]
                (cond (empty? cs) acc
                      (and (seq? (first cs)) (= 'comment (first (first cs))))
                      (recur (rest cs) (conj acc [:comment (first cs)]))
                      :else (recur (drop 2 cs) (conj acc [(first cs) (second cs)]))))
        scrut (core/strip-parens (sp/splint-render ctx subject))]
    (sp/splint-emit! ctx (sp/indent-of ctx)
                     (if (= :rust (t ctx))
                       (str "return match " scrut " {\n")
                       (str "switch (" scrut ") {\n")))
    (sp/splint-scoped
     ctx {:key :in-case :value true :indent 1}
     (fn [inner]
       (doseq [[labels body] pairs]
         (if (= :comment labels)
           (core/comment-form inner body)
           (let [else? (= :else labels)
               ls (when-not else? (mapv (fn [l] (sp/splint-render inner l)) labels))]
           (case (t inner)
             :rust (sp/splint-emit! inner (sp/indent-of inner)
                                    (if else? "_" (str/join " | " ls)) " => ")
             ;; FOUR LABELS TO A LINE, which is what the hand-written files
             ;; do. A one-per-arm line for eight tags runs past 150 columns,
             ;; and the not-worse rule covers what a diff reads like as much
             ;; as what it compiles to.
             (if else?
               (sp/splint-emit! inner (sp/indent-of inner) "default:\n")
               (doseq [chunk (partition-all 4 ls)]
                 (sp/splint-emit! inner (sp/indent-of inner)
                                  (str/join " " (mapv (fn [l] (str "case " l ":")) chunk))
                                  "\n"))))
           (if (= :rust (t inner))
             (sp/splint-emit! inner (core/strip-parens (sp/splint-render inner body)) ",\n")
             (sp/splint-emit! inner (sp/indent-of inner) "    return "
                              (core/strip-parens (sp/splint-render inner body)) ";\n")))))))
    (sp/splint-emit! ctx (sp/indent-of ctx) (if (= :rust (t ctx)) "};\n" "}\n"))))

(defn forms-for []
  (merge
   (core/forms-for {:default-tag Value})
   {'case case-form
    ;; IS THIS VALUE ON THE HEAP? A method in Rust, a static in the other two,
    ;; which is the same split `^:method` handles for generated functions --
    ;; here it is a hand-written one, so the vocabulary spells it.
    'is-heap (core/call {:rust "{0}.is_heap()" :java "Val.isHeap({0})" :csharp "Val.IsHeap({0})"})
    'as-heap (core/call {:rust "{0}.as_heap()" :java "Val.asHeap({0})" :csharp "Val.AsHeap({0})"})
    ;; THE TYPE OF A HEAP OBJECT. Rust reaches the space by reference; the
    ;; other two pass it and C# qualifies the call.
    'ty (core/call {:rust "ty(&{0}.gc.sp, {1})"
                    :java "ty({0}.gc.sp, {1})"
                    :csharp "Obj.Ty({0}.gc.sp, {1})"})}))
