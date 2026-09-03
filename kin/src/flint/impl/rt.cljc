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
  (:require [kin :as sp]
            [kin.lang :as core]
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

(def F64
  "A double. The one place `Value` is unwrapped to a host float -- `range`
  compares its bounds numerically, and an integer range and a float range have
  to answer the same question."
  {:name 'F64 :types {:rust "f64" :java "double" :csharp "double"} :methods {}})

(def Idx
  "An index into a host array. `usize` in Rust, `int` in the other two."
  {:name 'Idx :types {:rust "usize" :java "int" :csharp "int"} :methods {}})

(def Bits
  "The raw 64 bits of a value, outside the `Value` newtype Rust wraps them in."
  {:name 'Bits :types {:rust "u64" :java "long" :csharp "long"} :methods {}})

(def U32s {:name 'U32s :types {:rust "Vec<u32>" :java "int[]" :csharp "int[]"} :methods {}})
(def U64s {:name 'U64s :types {:rust "Vec<u64>" :java "long[]" :csharp "long[]"} :methods {}})

(def Interns
  "An intern table: open-addressed, linear-probed, weak. Parallel `hashes`
  and `values` arrays plus a `count` -- one layout, on all three, since the
  measurement that closed that divergence."
  {:name 'Interns :types {:rust "InternTable" :java "Interns" :csharp "Interns"}
   :methods {}})

(def Addr
  "A raw heap address, BEFORE it becomes a `Value`.

  Distinct from `Value` on purpose: `alloc` answers an address, and the slots
  of a half-built object are written through it. Confusing the two is how a
  constructor comes to write a tagged value where the collector expects a
  pointer."
  {:name 'Addr :types {:rust "Addr" :java "long" :csharp "long"} :methods {}})

(def tags-for {'Rt Rt 'Value Value 'Cat Cat 'Bool Bool 'I32 I32 'RootIx RootIx
               'F64 F64 'Addr Addr 'Idx Idx 'Bits Bits 'U32s U32s 'U64s U64s 'Interns Interns})

(defn- t [ctx] (:target ctx))

(defn sibling
  "A call into another module. `(sibling \"vec_count\" \"Vec\" \"count\")` gives

      rust    {0}.vec_count({1}, ...)
      java    Vec.count({0}, {1}, ...)
      csharp  Vec.Count({0}, {1}, ...)

  `n` is how many arguments follow the receiver."
  ([rust-name cls java-name n] (sibling rust-name cls java-name
                                        (str (str/upper-case (subs java-name 0 1))
                                             (subs java-name 1))
                                        n))
  ([rust-name cls java-name csharp-name n]
   (let [args (fn [from] (str/join ", " (map #(str "{" % "}") (range from (inc n)))))
         tail (fn [from] (if (zero? n) "" (str ", " (args from))))]
     (core/call {:rust (str "{0}." rust-name "(" (args 1) ")")
                 :java (str cls "." java-name "({0}" (tail 1) ")")
                 :csharp (str cls "." csharp-name "({0}" (tail 1) ")")}))))

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

(def value-names
  "Values spelled differently per target. `NIL` is bare in Rust and qualified
  on the other two, which is exactly why it is a NAME and not a form."
  {'NIL {:rust "NIL" :java "Val.NIL" :csharp "Val.Nil"}
   'TRUE {:rust "TRUE" :java "Val.TRUE" :csharp "Val.True"}
   'FALSE {:rust "FALSE" :java "Val.FALSE" :csharp "Val.False"}
   'NOT_FOUND {:rust "NOT_FOUND" :java "Val.NOT_FOUND" :csharp "Val.NotFound"}})

(def names-for
  "Every type tag, spelled three ways. A NAME rather than a form, because a
  tag appears in a `case` label where a call cannot go."
  (reduce (fn [m sym] (assoc m sym {:rust (str sym) :java (str sym)
                                    :csharp (csharp-tag sym)}))
          value-names type-tags))

(defn forms-for []
  (merge
   (core/forms-for {:default-tag Value})
   {    ;; IS THIS VALUE ON THE HEAP? A method in Rust, a static in the other two,
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
    ;; A value as a host double, whatever numeric type it holds.
    'num-f64 (core/call {:rust "{0}.num_f64({1})"
                         :java "Num.f64({0}, {1})"
                         :csharp "Num.F64({0}, {1})"})
    'nil? (core/call {:rust "{1}.is_nil()" :java "Val.isNil({1})" :csharp "Val.IsNil({1})"})
    'as-fixnum (core/call {:rust "{0}.as_fixnum()"
                           :java "Val.asFixnum({0})"
                           :csharp "Val.AsFixnum({0})"})
    'to-i32 (core/call {:rust "({0} as u32)" :java "((int) {0})" :csharp "((int) {0})"})

    ;; --- HOST ARRAYS ----------------------------------------------------
    ;;
    ;; NOT an array subject, which two analyses in a row said was the wrong
    ;; shape for what phase 3 actually needs. Three templates: read, write,
    ;; length. Indexing is spelled identically everywhere and only the length
    ;; disagrees, which is the whole of what a host array costs.
    'aget (core/call {:rust "{0}[{1}]" :java "{0}[{1}]" :csharp "{0}[{1}]"})
    'aset (fn [ctx form]
            (let [[_ a i v] form]
              (sp/kin-emit! ctx (sp/indent-of ctx)
                            (sp/kin-render ctx a) "[" (sp/kin-render ctx i) "] = "
                            (core/strip-parens (sp/kin-render ctx v)) ";\n")))
    ;; A hash widened to an index. Rust's index type is `usize` and its hash
    ;; is `u32`, so mixing them is a compile error there and a no-op on the
    ;; other two -- one target needs a word and the others need nothing, which
    ;; is the ordinary shape of a divergence here.
    'as-idx (core/call {:rust "{0} as usize" :java "{0}" :csharp "{0}"})
    'alen (core/call {:rust "{0}.len()" :java "{0}.length" :csharp "{0}.Length"})
    ;; The raw bits of a value. Rust wraps them in a newtype; the others do not.
    'bits (core/call {:rust "{0}.0" :java "{0}" :csharp "{0}"})
    'of-bits (core/call {:rust "Value({0})" :java "{0}" :csharp "{0}"})

    ;; --- CROSS-MODULE CALLS ---------------------------------------------
    ;;
    ;; One pattern, not one decision per function: Rust reaches a sibling
    ;; module through `self`, the JVM and CLR through a class-qualified static
    ;; taking the runtime. So `sibling` writes all three templates from the
    ;; two names that actually differ, and this table grows by a LINE per
    ;; function rather than by four.
    ;;
    ;; It matters because the alternative -- writing three templates each --
    ;; is how a table of a hundred entries acquires a typo nobody reads.

    ;; --- ALLOCATION -----------------------------------------------------
    ;;
    ;; `alloc` answers a raw `Addr`, not a `Value`, in all three -- so a
    ;; constructor writes the half-built object's slots through `set-at` and
    ;; only wraps it with `heap` at the end. `set-at` is deliberately NOT
    ;; `set-slot`: that one takes a `Value` and inserts `asHeap` on two
    ;; targets, which is right for reading an existing object and wrong here.
    ;; Two questions wearing one name is the mistake `^:method` already made.
    ;; The siblings these files actually reach for.
    'vec-count (sibling "vec_count" "Vec" "count" 1)
    'vec-nth (sibling "vec_nth" "Vec" "nth" 2)
    'str-len (sibling "str_len" "Str" "byteLen" "ByteLen" 1)
    'char-len (sibling "char_count" "Str" "charLen" "CharLen" 1)
    'maps-eq (sibling "map_eq" "Maps" "eq" "Eq" 2)
    'seq-of (sibling "seq" "Seqs" "seq" 1)
    'first-of (sibling "first" "Seqs" "first" 1)
    'next-of (sibling "next" "Seqs" "next" 1)

    'alloc (core/call {:rust "{0}.alloc({1}, {2})"
                       :java "{0}.alloc({1}, {2})"
                       :csharp "{0}.Alloc({1}, {2})"})
    'set-at (core/call {:rust "{0}.set_slot({1}, {2}, {3})"
                        :java "{0}.setSlot({1}, {2}, {3})"
                        :csharp "{0}.SetSlot({1}, {2}, {3})"})
    'heap (core/call {:rust "Value::heap({0})" :java "Val.heap({0})" :csharp "Val.Heap({0})"})
    'fixnum (core/call {:rust "Value::fixnum({0} as i64)"
                        :java "Val.fixnum({0})" :csharp "Val.Fixnum({0})"})

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
