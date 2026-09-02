(ns flint.impl.rt
  "The vocabulary the runtime's value operations are written in.

  Where the codec had a byte sink and murmur had integers, this has the
  RUNTIME: a `Value`, a heap, type tags, and the methods that read them. It is
  the vocabulary the bulk of `doc/goals/kin-port.md` needs, so it is built
  around the two differences that kept the bulk out of reach.

  **The receiver.** Rust puts these on `impl Rt` and reaches the runtime as
  `self`; the JVM and CLR make them statics that take an `Rt` argument. Same
  function, three framings. `^:method` in `flint.impl.core` says so once.

  **The type tags.** `TY_CONS` is imported unqualified in Rust and Java and is
  `Obj.TyCons` in C#, which pascalises. There are a dozen of them and they
  appear all over the runtime, so they are named here once rather than at
  every use."
  (:require [flint.kin :as sp]
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

(def RootIx
  "An index into the shadow stack. `usize` in Rust, `int` in the other two."
  {:name 'RootIx :types {:rust "usize" :java "int" :csharp "int"} :methods {}})

(def tags-for {'Rt Rt 'Value Value 'Cat Cat 'Bool Bool 'I32 I32 'RootIx RootIx})

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
        scrut (core/strip-parens (sp/kin-render ctx subject))]
    (sp/kin-emit! ctx (sp/indent-of ctx)
                     (if (= :rust (t ctx))
                       (str "return match " scrut " {\n")
                       (str "switch (" scrut ") {\n")))
    (sp/kin-scoped
     ctx {:key :in-case :value true :indent 1}
     (fn [inner]
       (doseq [[labels body] pairs]
         (if (= :comment labels)
           (core/comment-form inner body)
           (let [else? (= :else labels)
               ls (when-not else? (mapv (fn [l] (sp/kin-render inner l)) labels))]
           (case (t inner)
             :rust (sp/kin-emit! inner (sp/indent-of inner)
                                    (if else? "_" (str/join " | " ls)) " => ")
             ;; FOUR LABELS TO A LINE, which is what the hand-written files
             ;; do. A one-per-arm line for eight tags runs past 150 columns,
             ;; and the not-worse rule covers what a diff reads like as much
             ;; as what it compiles to.
             (if else?
               (sp/kin-emit! inner (sp/indent-of inner) "default:\n")
               (doseq [chunk (partition-all 4 ls)]
                 (sp/kin-emit! inner (sp/indent-of inner)
                                  (str/join " " (mapv (fn [l] (str "case " l ":")) chunk))
                                  "\n"))))
           (if (= :rust (t inner))
             (sp/kin-emit! inner (core/strip-parens (sp/kin-render inner body)) ",\n")
             (sp/kin-emit! inner (sp/indent-of inner) "    return "
                              (core/strip-parens (sp/kin-render inner body)) ";\n")))))))
    (sp/kin-emit! ctx (sp/indent-of ctx) (if (= :rust (t ctx)) "};\n" "}\n"))))

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
                    :csharp "Obj.Ty({0}.gc.sp, {1})"})
    'heap-ty? (core/call {:rust "{0}.is_heap_ty({1}, {2})"
                          :java "{0}.isHeapTy({1}, {2})"
                          :csharp "{0}.IsHeapTy({1}, {2})"})

    ;; --- HEAP SLOTS -----------------------------------------------------
    ;;
    ;; What phase 3 calls an array. `Maps`, `Vec`, `Table` and `Snap` are
    ;; walks over the slots of a heap object, not over a native array -- Rust
    ;; reads them through `Obj::slot(sp, addr, i)` and the other two through
    ;; `Rt.slot`, and all three already agree on the shape. So the "array
    ;; primitives" the codec spike ranked as the real gate on phase 3 turn out
    ;; to be three calls, not an array subject.
    'slot (core/call {:rust "{0}.slot({1}, {2})"
                      :java "{0}.slot({1}, {2})"
                      :csharp "{0}.Slot({1}, {2})"})
    ;; Rust's `set` takes a Value and unwraps it; the other two want the
    ;; address, so the `asHeap` lives in the template rather than at every use.
    'set-slot (core/call {:rust "{0}.set({1}, {2}, {3})"
                          :java "{0}.setSlot(Val.asHeap({1}), {2}, {3})"
                          :csharp "{0}.SetSlot(Val.AsHeap({1}), {2}, {3})"})
    ;; NOT symmetrical: `olen` is a method on Rust's `Rt` and a file-local
    ;; static on the other two. The receiver still comes first in the source.
    'olen (core/call {:rust "{0}.olen({1})" :java "olen({0}, {1})" :csharp "Olen({0}, {1})"})
    'as-fixnum (core/call {:rust "{0}.as_fixnum()"
                           :java "Val.asFixnum({0})"
                           :csharp "Val.AsFixnum({0})"})
    'to-i32 (core/call {:rust "({0} as u32)" :java "((int) {0})" :csharp "((int) {0})"})

    ;; --- ROOTING --------------------------------------------------------
    ;;
    ;; The shadow stack (`doc/decisions/0031`): a value in a host local does
    ;; not survive an allocation. These ride the `^:method` receiver, so `{0}`
    ;; is `self` in Rust and `rt` in the other two and no branch is needed.
    'mark (core/call {:rust "{0}.mark()" :java "{0}.mark()" :csharp "{0}.Mark()"})
    'push (core/call {:rust "{0}.push({1})" :java "{0}.push({1})" :csharp "{0}.Push({1})"})
    'r (core/call {:rust "{0}.r({1})" :java "{0}.r({1})" :csharp "{0}.R({1})"})
    'set-r (core/call {:rust "{0}.set_r({1}, {2})"
                       :java "{0}.setR({1}, {2})"
                       :csharp "{0}.SetR({1}, {2})"})
    'pop-to (core/call {:rust "{0}.pop_to({1})"
                        :java "{0}.popTo({1})"
                        :csharp "{0}.PopTo({1})"})}))
