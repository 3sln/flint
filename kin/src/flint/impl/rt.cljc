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
  (:require [kin]
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

(def Ty
  "A HEAP TYPE TAG, as `ty` answers one -- `TY_ARRAYMAP` and friends.

  Same width as `Cat` on every target and a different thing: a category is one
  of four kinds a value belongs to, a type tag is which of thirty-odd layouts
  it has. Borrowing `Cat` for a `ty` result would have compiled everywhere and
  told a reader something false."
  {:name 'Ty :types {:rust "u8" :java "int" :csharp "int"} :methods {}})

(def Cat
  "A category: `CAT_SCALAR` and friends. Rust made it a `u8` and the other two
  an `int`, and nothing depends on the width."
  {:name 'Cat :types {:rust "u8" :java "int" :csharp "int"} :methods {}})

(def Bool {:name 'Bool :types {:rust "bool" :java "boolean" :csharp "bool"} :methods {}})
(def I32
  "A 32-bit integer whose high bit is never set: an index, a count, a shift.

  Rust spells it `u32` and the ports `int`, and while the high bit stays
  clear those agree about everything -- ordering, division, printing. Nearly
  every number in the runtime is one of these."
  {:name 'I32 :types {:rust "u32" :java "int" :csharp "int"} :methods {}})

(def U32
  "A 32-bit integer that MAY carry the high bit. A hash, principally.

  The same host types as `I32` -- `u32` and `int` -- and a different fact
  about the value, which is what a tag is for. Above 2^31 Rust's `u32` and
  the ports' signed `int` disagree about `<`, `>` and `/`: `0x80000001 < 5`
  is false in Rust and true on both ports, measured. So `<` on two of these
  has to emit an unsigned comparison on the ports, and `<` on two `I32`s
  must not, because `Integer.compareUnsigned` where a plain `<` would do is
  worse code than the hand-written runtime has.

  Distinguishing the two is the entire content of the difference. It cannot
  be a type, because the types are identical; it is a claim about the value,
  which is data, which is a tag."
  {:name 'U32 :types {:rust "u32" :java "int" :csharp "int"} :methods {}})

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
  ;; FULLY QUALIFIED on the two ports, and it has to be. The generated
  ;; module for `interns.kin` is itself called `Interns` -- `flint.rt.Interns`
  ;; on the JVM, `flint.rt.Interns` on the CLR -- and inside that file a bare
  ;; `Interns` is the generated class, not the runtime's table. Naming the
  ;; type in full is how a tag says which one it means, and it costs nothing
  ;; anywhere else.
  {:name 'Interns :types {:rust "InternTable"
                          :java "com.flint.rt.Interns"
                          :csharp "Flint.Rt.Interns"}
   :methods {}})

(def Addr
  "A raw heap address, BEFORE it becomes a `Value`.

  Distinct from `Value` on purpose: `alloc` answers an address, and the slots
  of a half-built object are written through it. Confusing the two is how a
  constructor comes to write a tagged value where the collector expects a
  pointer."
  {:name 'Addr :types {:rust "Addr" :java "long" :csharp "long"} :methods {}})

(def tags {'Rt Rt 'Value Value 'Cat Cat 'Ty Ty 'Bool Bool 'I32 I32 'U32 U32 'RootIx RootIx
               'F64 F64 'Addr Addr 'Idx Idx 'Bits Bits 'U32s U32s 'U64s U64s 'Interns Interns})

(defn- t [ctx] (:target ctx))

(defn own
  "A call to a function in the SAME class.

  Distinct from `sibling`: Rust still reaches it through `self`, but the JVM
  and CLR call it unqualified rather than through a class name, because it is
  theirs. `Maps.java` says `bnSetKey(rt, n, i, v)`, not `Maps.bnSetKey(..)`.

  `n` is how many arguments follow the receiver. `:static` means the function
  takes no receiver at all on any target -- `mask(h, shift)` is arithmetic on
  its arguments and reaches nothing."
  ([rust-name java-name n] (own rust-name java-name
                                (str (str/upper-case (subs java-name 0 1))
                                     (subs java-name 1)) n))
  ([rust-name java-name csharp-name n]
   (let [args (fn [from] (str/join ", " (map #(str "{" % "}") (range from (inc n)))))
         tail (fn [from] (if (zero? n) "" (str ", " (args from))))]
     (core/call {:rust (str "{0}." rust-name "(" (args 1) ")")
                 :java (str java-name "({0}" (tail 1) ")")
                 :csharp (str csharp-name "({0}" (tail 1) ")")}))))

(defn own-static
  "A same-class function that takes NO receiver on any target."
  ([rust-name java-name n] (own-static rust-name java-name
                                       (str (str/upper-case (subs java-name 0 1))
                                            (subs java-name 1)) n))
  ([rust-name java-name csharp-name n]
   (let [args (str/join ", " (map #(str "{" % "}") (range 0 n)))]
     (core/call {:rust (str rust-name "(" args ")")
                 :java (str java-name "(" args ")")
                 :csharp (str csharp-name "(" args ")")}))))

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
    TY_SYMBOL TY_BMNODE TY_COLLNODE])

(defn- csharp-tag
  "`TY_EMPTY_LIST` -> `Obj.TyEmptyList`."
  [sym]
  (str "Obj." (str/join (mapv str/capitalize (str/split (str/lower-case (str sym)) #"_")))))

(def value-names
  "Values spelled differently per target. `NIL` is bare in Rust and qualified
  on the other two, which is exactly why it is a NAME and not a form."
  (merge
   {'NIL {:rust "NIL" :java "Val.NIL" :csharp "Val.Nil"}
   'TRUE {:rust "TRUE" :java "Val.TRUE" :csharp "Val.True"}
   'FALSE {:rust "FALSE" :java "Val.FALSE" :csharp "Val.False"}
   'NOT_FOUND {:rust "NOT_FOUND" :java "Val.NOT_FOUND" :csharp "Val.NotFound"}
   ;; The lazy-seq slot indices. Rust and Java spell them SCREAMING_SNAKE and
   ;; C# pascalises, so they are names -- and they were missing from this
   ;; table, which meant they passed through VERBATIM and emitted `LS_THUNK`
   ;; into a C# file whose constant is `LsThunk`. The CLR has not compiled
   ;; since `seqs.kin` shipped.
   'RF_SCHEMA {:rust "crate::table::RF_SCHEMA"
               :java "Table.RF_SCHEMA" :csharp "Flint.Rt.Table.RF_SCHEMA"}
   'LS_THUNK {:rust "LS_THUNK" :java "LS_THUNK" :csharp "LsThunk"}
   'LS_SEQ {:rust "LS_SEQ" :java "LS_SEQ" :csharp "LsSeq"}}
  ;; The node and category constants. All three targets spell these
  ;; IDENTICALLY, so every entry below is three copies of one string -- and
  ;; they are written down anyway.
  ;;
  ;; Passing a constant through verbatim is only correct while every target
  ;; agrees, and that is a fact about the runtimes rather than a property of
  ;; the name. Leaving it unstated is what let `LS_THUNK` through: it was
  ;; indistinguishable from these until the C# stopped compiling. A table
  ;; entry is where the agreement is asserted, and where a future rename in
  ;; one target has somewhere to be recorded.
  (reduce (fn [m sym] (assoc m sym {:rust (str sym) :java (str sym)
                                    :csharp (str sym)}))
          {}
          '[CAT_SCALAR CAT_MAP CAT_SEQUENTIAL CAT_SET
            BN_DATAMAP BN_NODEMAP BN_BASE BN_EDIT
            CN_BASE CN_HASH CN_EDIT HASH_BITS
            ;; The ARRAY-MAP and HASH-MAP slot names, spelled identically by
            ;; all three. Declared rather than passed through for the reason
            ;; the whole table exists: agreement is a fact about the runtimes,
            ;; not a property of the name, and `LS_THUNK` agreed on two of
            ;; three until it did not.
            AM_BASE AM_META AM_HASH HM_CNT HM_ROOT HM_META HM_HASH
            ARRAY_MAP_MAX])))

(def names
  "Every type tag, spelled three ways. A NAME rather than a form, because a
  tag appears in a `case` label where a call cannot go."
  (reduce (fn [m sym] (assoc m sym {:rust (str sym) :java (str sym)
                                    :csharp (csharp-tag sym)}))
          value-names type-tags))

(defn by-arg-tags
  "A form that renders its arguments, LOOKS AT THEIR TAGS, and picks.

  `choose` is `(fn [tags] -> {:templates {target -> tmpl} :tag T})`. This is
  the vocabulary deciding: kin carried the tags here and has no opinion about
  what they mean, which is the whole of correction C1. Another vocabulary
  that wants a table, or a lattice, or to refuse an unknown combination,
  writes that here instead -- and a user who wants different behaviour from
  ours shadows the form by requiring their own vocabulary first.

  The arguments are rendered ONCE. Rendering them to get the tags and again
  to get the text would hoist any temporary twice."
  [choose]
  (fn [ctx form]
    (let [rs (mapv (fn [a] (kin/render-tagged ctx a)) (rest form))
          {:keys [templates tag]} (choose (mapv :tag rs))
          tmpl (or (get templates (t ctx))
                   (throw (ex-info (str "kin: `" (first form) "` has no template for "
                                        (t ctx))
                                   {:form (first form) :target (t ctx)})))
          code (core/fill tmpl (mapv :text rs))]
      (kin/tagged! ctx tag)
      (if (= :statement (kin/position ctx))
        (kin/emit! ctx (kin/indent-of ctx) code ";\n")
        (kin/emit! ctx code)))))

(defn- both-u32
  "The unsigned spelling when BOTH arguments are `U32`, the plain one
  otherwise.

  Both, not either. `(< hash 5)` compares a value that may carry the high bit
  against one that cannot, and the unsigned comparison is still the right
  one -- but a literal has no tag to say so, so requiring both would refuse
  the commonest case. Requiring both is nonetheless what this does, because
  the alternative is to guess: an untagged argument is not evidence of
  anything, and silently choosing unsigned because ONE side might be large
  is the kind of inference that produces a plausible answer. A source that
  means the unsigned comparison against a literal still has `u<`."
  [unsigned plain result]
  (by-arg-tags
   (fn [tags]
     {:templates (if (and (= 2 (count tags)) (every? #(= U32 %) tags))
                   unsigned plain)
      :tag result})))

(def every-target
  "One spelling for all three, which is what a plain operator is."
  (fn [op] {:rust op :java op :csharp op}))

(defn forms []
  (merge
   (core/forms {:default-tag Value})
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
              (kin/emit! ctx (kin/indent-of ctx)
                            (kin/render ctx a) "[" (kin/render ctx i) "] = "
                            (core/strip-parens (kin/render ctx v)) ";\n")))
    ;; A hash widened to an index. Rust's index type is `usize` and its hash
    ;; is `u32`, so mixing them is a compile error there and a no-op on the
    ;; other two -- one target needs a word and the others need nothing, which
    ;; is the ordinary shape of a divergence here.
    ;; A left shift. Identical on all three, unlike the RIGHT shift, whose
    ;; correctness depends on the tag's signedness -- which is why `kin.lang`
    ;; refuses to carry a `bit-shift-right` at all and a subject names `ushr`
    ;; and `sar` explicitly.
    'shl (core/call {:rust "({0} << {1})" :java "({0} << {1})" :csharp "({0} << {1})"})

    ;; --- UNSIGNED COMPARISON AND DIVISION -------------------------------
    ;;
    ;; The same argument as `ushr`/`sar`, one operator family over. An `I32`
    ;; is Rust's `u32` and the ports' signed `int`, so above 2^31 a value
    ;; prints differently and -- worse -- ORDERS and DIVIDES differently:
    ;; `0x80000001 < 5` is false in Rust and true on both ports.
    ;;
    ;; `kin.lang`'s generic `<`, `>` and `quot` stay available, because they
    ;; are correct for indices, counts and shifts, which is nearly every use.
    ;; These exist for the values that can actually carry the high bit -- a
    ;; HASH, principally -- and naming them is what makes the choice visible
    ;; at the use rather than assumed.
    ;;
    ;; Nothing in the port compares or divides a hash today. This is here so
    ;; that the first source that needs to has the right tool, rather than
    ;; reaching for `<` and getting three runtimes that disagree above 2^31 --
    ;; which is exactly how `fixnum` shipped widening the wrong way for
    ;; thirteen sources without a driver noticing.
    ;; --- THE COMPARISONS, WHICH NOW READ THEIR ARGUMENTS' TAGS ----------
    ;;
    ;; `<` on two `U32`s is the unsigned comparison; `<` on anything else is
    ;; the plain one. Same source, right answer per target, and the choice is
    ;; made HERE -- by this vocabulary, from data kin carried and did not
    ;; read.
    ;;
    ;; The six `u*` forms below stay. They are not redundant: `both-u32`
    ;; requires BOTH arguments to be tagged, and a literal carries no tag, so
    ;; `(< hash 5)` gets the plain comparison. Naming the unsigned one is how
    ;; a source says what it means where a tag cannot.
    '< (both-u32 {:rust "({0} < {1})"
                  :java "(Integer.compareUnsigned({0}, {1}) < 0)"
                  :csharp "((uint) {0} < (uint) {1})"}
                 (every-target "({0} < {1})") Bool)
    '> (both-u32 {:rust "({0} > {1})"
                  :java "(Integer.compareUnsigned({0}, {1}) > 0)"
                  :csharp "((uint) {0} > (uint) {1})"}
                 (every-target "({0} > {1})") Bool)
    '<= (both-u32 {:rust "({0} <= {1})"
                   :java "(Integer.compareUnsigned({0}, {1}) <= 0)"
                   :csharp "((uint) {0} <= (uint) {1})"}
                  (every-target "({0} <= {1})") Bool)
    '>= (both-u32 {:rust "({0} >= {1})"
                   :java "(Integer.compareUnsigned({0}, {1}) >= 0)"
                   :csharp "((uint) {0} >= (uint) {1})"}
                  (every-target "({0} >= {1})") Bool)
    'quot (both-u32 {:rust "({0} / {1})"
                     :java "Integer.divideUnsigned({0}, {1})"
                     :csharp "((int)((uint) {0} / (uint) {1}))"}
                    (every-target "({0} / {1})") U32)
    'rem (both-u32 {:rust "({0} % {1})"
                    :java "Integer.remainderUnsigned({0}, {1})"
                    :csharp "((int)((uint) {0} % (uint) {1}))"}
                   (every-target "({0} % {1})") U32)

    'u< (core/call {:rust "({0} < {1})"
                    :java "(Integer.compareUnsigned({0}, {1}) < 0)"
                    :csharp "((uint) {0} < (uint) {1})"})
    'u> (core/call {:rust "({0} > {1})"
                    :java "(Integer.compareUnsigned({0}, {1}) > 0)"
                    :csharp "((uint) {0} > (uint) {1})"})
    'u<= (core/call {:rust "({0} <= {1})"
                     :java "(Integer.compareUnsigned({0}, {1}) <= 0)"
                     :csharp "((uint) {0} <= (uint) {1})"})
    'u>= (core/call {:rust "({0} >= {1})"
                     :java "(Integer.compareUnsigned({0}, {1}) >= 0)"
                     :csharp "((uint) {0} >= (uint) {1})"})
    'uquot (core/call {:rust "({0} / {1})"
                       :java "Integer.divideUnsigned({0}, {1})"
                       :csharp "((int)((uint) {0} / (uint) {1}))"})
    'urem (core/call {:rust "({0} % {1})"
                      :java "Integer.remainderUnsigned({0}, {1})"
                      :csharp "((int)((uint) {0} % (uint) {1}))"})
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
    ;; The map's own helpers. `bn-set-*` and `cn-set` are themselves
    ;; generated, by `champ.kin`, which is what makes this a second layer
    ;; rather than a second copy.
    'cn-new (own "cn_new" "cnNew" 3)
    'bn-set-key (own "bn_set_key" "bnSetKey" 3)
    'bn-set-val (own "bn_set_val" "bnSetVal" 3)
    'bn-set-node (own "bn_set_node" "bnSetNode" 3)
    'hash-mask (own-static "mask" "mask" 2)
    'index-of (own-static "index_of" "indexOf" 2)
    'bitpos (own-static "bitpos" "bitpos" 2)

    ;; Functions kin itself GENERATED, in `merge.kin` and `copies.kin`. A
    ;; generated function is reachable from another source only through a
    ;; declaration like any other -- `defn` registers a name for its own file's
    ;; self-calls and nothing wider, which is what keeps one source from
    ;; silently depending on another's internals.
    ;; The COLLISION-NODE accessors. `cn-hash` did not exist in Rust -- the
    ;; three operations were written out at each use -- so the helper was
    ;; added there rather than the inline form taught to kin: the ports had
    ;; already named it, and a name is the portable half.
    'cn-hash (own "cn_hash" "cnHash" 1)
    'cn-count (own "cn_count" "cnCount" 1)
    'cn-key (own "cn_key" "cnKey" 2)
    'cn-val (own "cn_val" "cnVal" 2)
    'node-assoc (own "node_assoc" "nodeAssoc" 6)
    'coll-dissoc (own "coll_dissoc" "collDissoc" 3)
    ;; `category` is itself generated, by `kin/eq.kin`, and is reached from
    ;; inside its own class on the ports -- so it is `own`, not `sibling`.
    'category (own "category" "category" 1)
    ;; The TABLE unit, reached from the map layer. `map_count` needs it for a
    ;; ROW REF, which counts its COLUMNS -- the defensive arm both ports have
    ;; and Rust does not.
    ;;
    ;; C# spells it `schemaLen`, not `SchemaLen`: the port kept the Java
    ;; casing here, and a name table records what a runtime DOES rather than
    ;; what its convention would predict.
    ;; The EMPTY MAP singleton. Rust keeps it on `Rt`; the ports keep it on
    ;; `Maps` and reach it by a name that differs from Rust's.
    'empty-map (core/call {:rust "{0}.empty_map()"
                           :java "com.flint.rt.Maps.empty({0})"
                           :csharp "Flint.Rt.Maps.Empty({0})"})
    'ref-to-map (core/call {:rust "{0}.ref_to_map({1})"
                            :java "Table.refToMap({0}, {1})"
                            :csharp "Flint.Rt.Table.refToMap({0}, {1})"})
    'ref-get (core/call {:rust "{0}.ref_get({1}, {2}, {3})"
                         :java "Table.refGet({0}, {1}, {2}, {3})"
                         :csharp "Flint.Rt.Table.refGet({0}, {1}, {2}, {3})"})
    'schema-len (core/call {:rust "{0}.schema_len({1})"
                            :java "Table.schemaLen({0}, {1})"
                            :csharp "Flint.Rt.Table.schemaLen({0}, {1})"})
    ;; `eq_may_alloc` is GENERATED, by `eqalloc.kin`, so it is `own` rather
    ;; than a hand-written sibling -- and `own` is now exactly right for one:
    ;; a bare call, with the static import derived from the fact that this
    ;; source mentions the name. It used to be spelled `Eq.eqMayAlloc`,
    ;; because the region was spliced into `Eq.java`; it lives in
    ;; `flint.rt.Eqalloc` now and nothing needs to say so.
    'eq-may-alloc (own "eq_may_alloc" "eqMayAlloc" 1)
    'node-find-scalar (own "node_find_scalar" "nodeFindScalar" 4)
    'node-size-class (own "node_size_class" "nodeSizeClass" 1)
    'bn-copy-remove-entry (own "bn_copy_remove_entry" "bnCopyRemoveEntry" 3)
    'bn-node-to-inline (own "bn_node_to_inline" "bnNodeToInline" 5)
    'merge-two (own "merge_two" "mergeTwo" 8)
    'bn-copy-set-value (own "bn_copy_set_value" "bnCopySetValue" 4)
    'bn-copy-set-node (own "bn_copy_set_node" "bnCopySetNode" 4)
    'bn-copy-insert-entry (own "bn_copy_insert_entry" "bnCopyInsertEntry" 5)
    'bn-inline-to-node (own "bn_inline_to_node" "bnInlineToNode" 4)
    'is-bmnode (own "is_bmnode" "isBmnode" 1)
    'coll-assoc (own "coll_assoc" "collAssoc" 6)
    ;; `eq` and `hash-value` live in Eq on the ports, not in Maps -- a
    ;; SIBLING rather than one of our own, and the distinction is exactly what
    ;; the two helpers exist to keep straight.
    ;;
    ;; Written out rather than through `sibling` because both ports need the
    ;; class FULLY QUALIFIED here, for different reasons -- see the note on
    ;; `eq-may-alloc` above. `sibling` takes one class name for both ports
    ;; and cannot say either of them.
    'val-eq (core/call {:rust "{0}.eq({1}, {2})"
                        :java "com.flint.rt.Eq.eq({0}, {1}, {2})"
                        :csharp "Flint.Rt.Eq.Equal({0}, {1}, {2})"})
    'hash-value (core/call {:rust "{0}.hash_value({1})"
                            :java "com.flint.rt.Eq.hashValue({0}, {1})"
                            :csharp "Flint.Rt.Eq.HashValue({0}, {1})"})
    'bn-datamap (own "bn_datamap" "bnDatamap" 1)
    'bn-nodemap (own "bn_nodemap" "bnNodemap" 1)
    'bn-key (own "bn_key" "bnKey" 2)
    'bn-val (own "bn_val" "bnVal" 2)
    'bn-node (own "bn_node" "bnNode" 2)
    ;; Population count. Three intrinsics for one idea -- the shape a
    ;; vocabulary exists for.
    'popcount (core/call {:rust "{0}.count_ones()"
                          :java "Integer.bitCount({0})"
                          :csharp "System.Numerics.BitOperations.PopCount((uint)({0}))"})

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
    ;; ZERO-EXTENDED on the ports, and that is not cosmetic. kin's `I32` is
    ;; Rust's `u32`, so widening it to the 48-bit fixnum payload must not
    ;; sign-extend -- but Java and C# spell it `int`, which is signed, and
    ;; `Val.fixnum(h)` on a hash with the high bit set stores 48 bits of ones
    ;; where Rust stores 32. The mask is what makes `{0}` mean the same
    ;; number in all three.
    ;;
    ;; It went unnoticed because the only shipped use was a seq index, which
    ;; is small and non-negative -- so the wrong widening was unreachable
    ;; until `cn_new` came to store a 32-bit HASH. The hand-written ports had
    ;; the mask; the generator did not.
    'fixnum (core/call {:rust "Value::fixnum({0} as i64)"
                        :java "Val.fixnum({0} & 0xFFFFFFFFL)"
                        :csharp "Val.Fixnum({0} & 0xFFFFFFFFL)"})

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

;; --------------------------------------------------------- the vocabulary
;;
;; ONE VAR, holding one map, saying what this vocabulary IS and which targets
;; it can speak. It used to be three vars found by convention, which meant
;; kin assembled the vocabulary rather than this file declaring it -- and a
;; file that does not declare itself cannot say `:targets`, which is what
;; every question about "does this source generate for X" now rests on.

(def vocabulary
  "The runtime vocabulary: three targets, its tags, its names, its forms.

  `:targets` is the honest answer to what these forms can speak, and it is
  three because every template in this file has a `:rust`, a `:java` and a
  `:csharp` entry and nothing else. A fourth runtime would be a fourth entry
  in every template here, and until that exists, saying so is what keeps kin
  from generating a plausible-looking file for a target nobody has written."
  (kin/vocabulary :namespace 'flint.impl.rt
                 :targets #{:rust :java :csharp}
                 :tags tags
                 :names names
                 :forms (forms)))
