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

(def tags-for {'Rt Rt 'Value Value 'Cat Cat 'Bool Bool 'I32 I32 'RootIx RootIx
               'F64 F64})

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
