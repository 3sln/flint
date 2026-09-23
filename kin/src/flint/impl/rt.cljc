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

(def Space
  "THE HEAP ITSELF, passed as a value rather than reached through an `Rt`.

  Every other memory word here is rooted at `Rt` -- `read-u64` emits
  `{0}.gc.sp.read_u64({1})` -- and for most generated code that is right,
  because most generated code already has an `Rt` in hand. The object HEADER
  layer cannot: the collector calls `ty(&self.sp, a)` from `&mut self` methods
  of `Gc`, and `Gc` is a field of `Rt`, so borrowing the whole `Rt` there does
  not compile. The space-first signature is load-bearing and this tag is what
  lets kin spell it.

  Rust takes it by SHARED reference and that is not a compromise: `Space`'s
  writers are `&self` too, because the space is an arena whose interior
  mutability lives below this level. So one tag serves reads and writes alike.

  Both ports name the class directly, having no such distinction to make."
  {:name 'Space :types {:rust "&Space" :java "Space" :csharp "Space"} :methods {}})

(def Gc
  "THE COLLECTOR'S STATE, passed as a value the way `Space` is.

  The young generation's bounds live here -- `from`, `bump`, `half`,
  `young-base` -- and the predicates over them are asked from inside the
  collector, so they take the `Gc` rather than reaching it through an `Rt`.
  Same reason as `Space`, one level up.

  Rust takes it by SHARED reference: every predicate built on these fields
  only reads them."
  {:name 'Gc :types {:rust "&Gc" :java "Gc" :csharp "Gc"} :methods {}})

(def Bool {:name 'Bool :types {:rust "bool" :java "boolean" :csharp "bool"} :methods {}})

(def I64
  "A SIGNED 64-bit integer, and the only signed number type here.

  `I32` promises its high bit is clear, which is what lets Rust spell it `u32`
  where the ports say `int`. A row index that a program supplied has made no
  such promise: `(assoc t -1 row)` is a thing to REFUSE, and refusing it means
  being able to hold it. Rust `i64`, both ports `long`, and the three agree
  about ordering, division and printing over the whole range."
  {:name 'I64 :types {:rust "i64" :java "long" :csharp "long"} :methods {}})
(def Cmp
  "THE RESULT OF A COMPARISON: negative, zero or positive.

  Rust `i32` and both ports `int`. It is NOT `I32`, which promises its high bit
  is clear and so is spelled `u32` on Rust -- and a comparison that can answer
  -1 has made no such promise. `compare` returning `u32` is how -1 becomes four
  billion, which is a sort order rather than an error."
  {:name 'Cmp :types {:rust "i32" :java "int" :csharp "int"} :methods {}})
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
(def StaticText
  "A BORROWED host string that outlives everything: a literal, and only ever a
  literal.

  Rust spells it `&'static str` and both ports spell it `String`/`string`, the
  same as `Text` -- so this tag costs the ports nothing and saves Rust an
  allocation and a copy per literal. It is NOT hole 6's borrowed type: a
  `&'static` borrows from the binary, not from `self`, so it carries no
  lifetime a generated signature would have to name."
  {:name 'StaticText
   :types {:rust "&'static str" :java "String" :csharp "string"}
   :methods {}})

(def Sink
  "A GROWABLE BYTE BUFFER, named by an index into the runtime's own list.

  `doc/goals/kin-port.md`'s hole 5. Each runtime already had a growable byte
  type and no two were the same -- `Vec<u8>`, `ByteArrayOutputStream`,
  `MemoryStream` -- so the operations that used one could not be written once.
  What they DO have in common is an integer, so the buffer lives on the runtime
  and this names which one. The lifetime is the runtime's rather than a
  borrow's, which is also what keeps it clear of hole 6.

  `sink-open`/`sink-close` are `mark`/`pop-to` for buffers, and the caller that
  opened one closes it."
  {:name 'Sink :types {:rust "u32" :java "int" :csharp "int"} :methods {}})

(def Cps
  "A CODE-POINT BUFFER being filled, named by an index into the runtime's own
  list -- the same shape as `Sink`, and for the same reason: the buffer is the
  host's, so a generated source names one rather than holding it.

  It exists because the regex engine wants RANDOM ACCESS to code points, and
  the alternative was materialising the subject into contiguous bytes first.
  For a rope that is a FLATTEN, which is what both ports did on every regex
  call while native walked the leaves -- same answers, different work, and
  `strings-and-matching` is explicit that this is the thing to count rather than hope about."
  {:name 'Cps :types {:rust "u32" :java "int" :csharp "int"} :methods {}})

(def Walk
  "A TREE WALK IN PROGRESS, named by an index into the runtime's own list.

  The `Sink`'s sibling and the same argument: an explicit stack of (node, how
  many children taken) is what walking two trees in step needs, and no two
  runtimes spell a stack of pairs the same way. An index they do.

  ONE WALK FOR BOTH TREES. A rope node and a byte-rope node put their children
  at the same offset and count them the same way, so the only thing that
  differed was which tag says `this is a node`."
  {:name 'Walk :types {:rust "u32" :java "int" :csharp "int"} :methods {}})

(def Text
  "A HOST string, which is not a flint string VALUE.

  The message an error carries, and nothing else -- `Value` is what a program
  sees, and a `Text` never reaches one. The two are different types in all
  three targets and mixing them would compile in Java and C#, where both are
  objects, and not in Rust, which is the direction a divergence should fail."
  {:name 'Text
   :types {:rust "alloc::string::String" :java "String" :csharp "string"}
   :methods {}})

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

(def Bytes
  "A BORROWED run of bytes: the argument a text hash is taken over.

  `&[u8]` and not `Vec<u8>`, which is the one place this differs from `U32s`
  below. Every Rust caller already holds a slice -- `s.as_bytes()`,
  `str_bytes(&self.gc.sp, a)`, `v.inline_bytes(&mut b)` -- and taking a `Vec`
  would make each of them COPY, on the path that hashes map keys. Java and C#
  pass their arrays by reference already, so the borrow costs them nothing and
  is not spelled."
  {:name 'Bytes :types {:rust "&[u8]" :java "byte[]" :csharp "byte[]"} :methods {}})

(def U32s
  "A flat array of 32-bit words -- a compiled regex program, and whatever else
  wants words rather than `Value`s.

  IT DECLARES ITS ALIASING, which makes answering compulsory wherever it is
  used. `Vec<u32>` is COPIED in Rust and `int[]` is SHARED in Java and C#, so
  an unmarked parameter of this type means two different things and every
  target compiles: a callee's writes land on three runtimes and not on the
  fourth. That is the divergence that kept `pike.rs` written three times
  (`DECISIONS.md#the-pike-vm-is-the-last-triplicate`), and it was sitting in
  this table as three spellings with nothing said about what they meant.

  `:shared` is a BORROW and not a mutable one: the program is read and never
  written, so Rust takes `&[u32]` and pays nothing for the indexing -- which
  is the whole reason not to reach for a runtime-owned buffer here, where
  every read would be a bounds-checked double indirection instead."
  {:name 'U32s
   :types {:rust "Vec<u32>" :java "int[]" :csharp "int[]"}
   :shared {:rust "&[u32]" :java "int[]" :csharp "int[]"}
   ;; `&*(x)` AND NOT `&x`, because the argument may itself already be a
   ;; borrow. `&x` on a `Vec<u32>` local is right, and on a `&[u32]` PARAMETER
   ;; it is a borrow-of-a-borrow Rust will not reborrow implicitly -- which is
   ;; precisely what a recursive function passing its own parameter onward
   ;; does, and how `add-thread` found this. The deref form is right for both,
   ;; and the other three spend nothing either way.
   :shared-arg {:rust "&*({0})" :java "{0}" :csharp "{0}"}
   :methods {}})
(def U64s {:name 'U64s :types {:rust "Vec<u64>" :java "long[]" :csharp "long[]"} :methods {}})

(def Layout
  "WHICH OF THE THREE SHAPES an object has: slots, string bytes, raw bytes.

  An ENUM on Rust and a bare `int` on the two ports, which is why it needs an
  entry rather than riding on `I32`: `layout_of` answers `Layout::Vals` there
  and `Obj.VALS` here, and a tag that called both `int` would emit an integer
  where Rust wants a variant. The enum derives `PartialEq`, so `==` is the
  comparison on every target."
  {:name 'Layout
   :types {:rust "crate::obj::Layout" :java "int" :csharp "int"}
   :methods {}})

(def Values
  "A flat run of VALUES the caller owns, read and never written.

  NOT `U64s`, though the two ports spell both `long[]`. A `Value` is a newtype
  on the Rust side and a bare `long` on the other two, so a slice of them is
  `&[Value]` there and `long[]` here -- and passing a `Vec<u64>` where
  `&[Value]` is wanted does not compile. One spelling per target is exactly
  what this table is for.

  `:shared` and a BORROW, like `U32s` above: every caller of `make-closure`
  hands over a run it already has -- an upvalue list the emitter built, or the
  empty one a host driver passes to enter a program -- and none of them wants
  it back changed."
  {:name 'Values
   ;; `Value` UNQUALIFIED, as the `Value` type above spells itself. A
   ;; crate-qualified path is right inside the runtime and unresolvable in the
   ;; self-contained probe `kin/scripts/verify` builds, which is the one place
   ;; a type has to stand on its own.
   :types {:rust "Vec<Value>" :java "long[]" :csharp "long[]"}
   :shared {:rust "&[Value]" :java "long[]" :csharp "long[]"}
   ;; `&*({0})` for the same reason `U32s` gives: the argument may already be
   ;; a borrow, and `&x` on one is a borrow-of-a-borrow Rust will not reborrow.
   :shared-arg {:rust "&*({0})" :java "{0}" :csharp "{0}"}
   :methods {}})

(def I32Buf
  "A flat SIGNED word buffer the caller owns and the callee WRITES INTO.

  `:shared` is the only way it is ever passed, and it is a MUTABLE borrow:
  Rust takes `&mut [i32]`, the other three take their array type, which is
  already a reference for them. A fixed slice rather than a `Vec` because
  nothing generated grows one -- the Pike VM's thread list is bounded by the
  instruction count, since `seen` admits each pc once per character.

  THE ARGUMENT MUST BE A LOCAL OR A PARAMETER, never a field reached through
  another argument. `f(&mut self.buf, self)` is fine in Java and C# and
  rejected in Rust, which is the one way this tag could be sound on three
  targets and not the fourth."
  {:name 'I32Buf
   :types {:rust "Vec<i32>" :java "int[]" :csharp "int[]"}
   :shared {:rust "&mut [i32]" :java "int[]" :csharp "int[]"}
   :shared-arg {:rust "&mut *({0})" :java "{0}" :csharp "{0}"}
   :methods {}})

(def Flags
  "A flat boolean buffer, written by the callee. `seen` in the Pike VM, and
  nothing else yet.

  Its own tag rather than `I32Buf` with a convention, because the element type
  is what the three targets spell differently -- `bool`, `boolean`, `bool` --
  and a tag that lied about it would be a cast at every read."
  {:name 'Flags
   :types {:rust "Vec<bool>" :java "boolean[]" :csharp "bool[]"}
   :shared {:rust "&mut [bool]" :java "boolean[]" :csharp "bool[]"}
   :shared-arg {:rust "&mut *({0})" :java "{0}" :csharp "{0}"}
   :methods {}})

(def I32s
  "A flat signed word array the callee only READS. Rust borrows it; the other
  three pass their array, which is the same thing for them."
  {:name 'I32s
   :types {:rust "Vec<i32>" :java "int[]" :csharp "int[]"}
   :shared {:rust "&[i32]" :java "int[]" :csharp "int[]"}
   :shared-arg {:rust "&*({0})" :java "{0}" :csharp "{0}"}
   :methods {}})

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
                          :csharp "global::Flint.Rt.Interns"}
   :methods {}})

(def Addr
  "A raw heap address, BEFORE it becomes a `Value`.

  Distinct from `Value` on purpose: `alloc` answers an address, and the slots
  of a half-built object are written through it. Confusing the two is how a
  constructor comes to write a tagged value where the collector expects a
  pointer."
  {:name 'Addr :types {:rust "Addr" :java "long" :csharp "long"} :methods {}})

(def tags {'Rt Rt 'Value Value 'Cat Cat 'Ty Ty 'Bool Bool 'I32 I32 'I64 I64 'Cmp Cmp 'U32 U32 'RootIx RootIx
               'Space Space
               'Gc Gc
               'Text Text 'StaticText StaticText 'Sink Sink 'Walk Walk 'Cps Cps
               'F64 F64 'Addr Addr 'Idx Idx 'Bits Bits 'Bytes Bytes
               'U32s U32s 'U64s U64s 'Values Values 'Layout Layout 'I32Buf I32Buf 'Flags Flags 'I32s I32s
               'Interns Interns})

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
  '[TY_CONS TY_EMPTY_LIST TY_LAZYSEQ TY_VECSEQ TY_STRSEQ TY_RANGE TY_VEC TY_NODE
    TY_TVEC TY_VOLATILE TY_TABLE TY_SCHEMA TY_TTABLE
    TY_MAPENTRY TY_ARRAYMAP TY_HASHMAP TY_TABLEREF TY_SET TY_STR
    ;; `TY_SYM` and `TY_KW`, and they were listed here as `TY_SYMBOL` and
    ;; `TY_KEYWORD` -- names NO target defines. Nothing had used them, so
    ;; nothing broke; the first source to name a symbol's tag would have
    ;; emitted an undefined constant into all three at once. Exactly the shape
    ;; `LS_THUNK` had, and `vec-nth`'s stale arity, and the reason
    ;; `kin/scripts/check-names` now refuses a name no target defines.
    TY_SYM TY_KW
    TY_BMNODE TY_COLLNODE
    ;; What `describe` dispatches over, beyond the above.
    TY_CLOSURE TY_NATIVEFN TY_TMAP TY_TSET TY_ROPE TY_BYTES TY_RECORD
    TY_ATOM TY_VAR TY_DELAY TY_REGEX TY_MULTIFN TY_REDUCED TY_EXINFO
    ;; The byte-string tiers (`DECISIONS.md#strings-and-matching`'s rope argument, applied to
    ;; bytes): a flat leaf, a B-tree node over leaves, and the transient.
    TY_BROPE TY_TBYTES
    ;; THE THREE THE ALLOCATOR NEEDS AND NO GUEST EVER SEES. `TY_FREE` is a
    ;; hole in the old space, `TY_FWD` an object that has moved, and `TY_RAW`
    ;; opaque bytes -- none is a `kind-of` answer, and `size-for` has to name
    ;; all three because they are the RAW-layout arm of the size rule.
    TY_RAW TY_FREE TY_FWD
    ;; The rest of what `kind-of` dispatches over. `threads-and-ports` says a value a guest
    ;; can hold needs a KIND of its own or it cannot be dispatched on at all,
    ;; so the closed set has to name every tag -- these are the ones no source
    ;; had needed until it.
    TY_BIGINT TY_ITERSEQ TY_CHUNKSEQ TY_PORT TY_THREAD TY_SCHED TY_TAGGED TY_OPAQUE
    ;; The wire codec's two (`DECISIONS.md#the-codec-is-guest-code`). A writer
    ;; is opaque to a guest and a reader is not, and the asymmetry is the whole
    ;; safety rule -- see `kin/wire.kin`.
    TY_WRITER TY_READER
    ;; What a closure is. `kind-of` never needed it -- a guest sees a function
    ;; and not its representation -- but `make-closure` BUILDS one.
    TY_CLOSURE])

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
   ;; THE FIXNUM BOUNDARY, named on all three runtimes -- so this word NAMES
   ;; them rather than spelling the value out. It emitted `((1L << 47) - 1)`
   ;; for a few minutes and `check-kin` refused it: "no file under
   ;; runtimes/jvm/src/com/flint/rt defines `((1L << 47) - 1)`". That check
   ;; exists for exactly this, and it caught the vocabulary committing the
   ;; same sin the function below was written to remove. `value.rs:82`,
   ;; `Val.java:74` and `Val.cs:51` each declare it; both ports' `Num.integer`
   ;; then inlined the bound as a literal anyway (`n >= -(1L << 47) && n <
   ;; (1L << 47)`, the same test the other way round). The two PORTS' copies
   ;; are gated against each other by `bin/check-port-consts`; native's is
   ;; compared with nothing, that checker being port-versus-port.
   ;; QUALIFIED on rust, as `HDR` is: a generated module's preamble imports a
   ;; fixed set from `crate::value`, and these are not in it. The path is what
   ;; makes the name resolve without touching that preamble -- and the probe
   ;; harness answers it the same way `objsize.drivers` answers `crate::obj`,
   ;; with a module of that name.
   ;; THE TAG LAYOUT. A value is a 16-bit tag over a 48-bit payload, and these
   ;; three are what every constructor and accessor in `Val` is built from.
   ;; Named on all three runtimes -- `PAYLOAD` only since 2026-09-22, when
   ;; native's `heap` was found omitting the mask the ports both apply, which
   ;; is far easier to miss against a bare `0x0000_FFFF_FFFF_FFFF` than
   ;; against a name.
   'TAG_MIN_BOXED {:rust "crate::value::TAG_MIN_BOXED" :java "Val.TAG_MIN_BOXED" :csharp "Val.TagMinBoxed"}
   'CANONICAL_NAN {:rust "crate::value::CANONICAL_NAN" :java "Val.CANONICAL_NAN" :csharp "Val.CanonicalNan"}
   'TAG_STR {:rust "crate::value::TAG_STR" :java "Val.TAG_STR" :csharp "Val.TagStr"}
   'TAG_KW {:rust "crate::value::TAG_KW" :java "Val.TAG_KW" :csharp "Val.TagKw"}
   'TAG_HEAP {:rust "crate::value::TAG_HEAP" :java "Val.TAG_HEAP" :csharp "Val.TagHeap"}
   'TAG_FIXNUM {:rust "crate::value::TAG_FIXNUM" :java "Val.TAG_FIXNUM" :csharp "Val.TagFixnum"}
   'PAYLOAD {:rust "crate::value::PAYLOAD" :java "Val.PAYLOAD" :csharp "Val.Payload"}
   'FIXNUM_MIN {:rust "crate::value::FIXNUM_MIN" :java "Val.FIXNUM_MIN" :csharp "Val.FixnumMin"}
   'FIXNUM_MAX {:rust "crate::value::FIXNUM_MAX" :java "Val.FIXNUM_MAX" :csharp "Val.FixnumMax"}
   ;; The lazy-seq slot indices. These USED to need an entry here: the CLR
   ;; spelled them `LsThunk` and `LsSeq` while the other two spelled them
   ;; SCREAMING_SNAKE, so passing them through verbatim emitted `LS_THUNK`
   ;; into a file whose constant was `LsThunk` -- and the CLR did not compile
   ;; for as long as that went unnoticed.
   ;;
   ;; The CLR is renamed now, as its `Vec` constants and its cons slots were,
   ;; for the same reason each time: one runtime disagreeing with the other
   ;; two is worth less than its own casing convention. They stay listed
   ;; because the table is where agreement is ASSERTED, and an agreement
   ;; nobody wrote down is one the next rename can quietly break.
   'RF_SCHEMA {:rust "crate::table::RF_SCHEMA"
               :java "Table.RF_SCHEMA" :csharp "global::Flint.Rt.Table.RF_SCHEMA"}

   ;; THE SCHEDULER'S SLOT LAYOUT, for `kin/sched.kin`.
   ;;
   ;; Spelled identically by all three -- `conc.rs` bare, `Conc` as a class
   ;; constant on the ports -- and listed anyway, because the table is where
   ;; agreement is ASSERTED and an agreement nobody wrote down is one the next
   ;; rename can quietly break.
   'SC_THREADS {:rust "crate::conc::SC_THREADS"
               :java "Conc.SC_THREADS" :csharp "Conc.SC_THREADS"}
   'SC_CURRENT {:rust "crate::conc::SC_CURRENT"
               :java "Conc.SC_CURRENT" :csharp "Conc.SC_CURRENT"}
   'SC_EVENTS {:rust "crate::conc::SC_EVENTS"
              :java "Conc.SC_EVENTS" :csharp "Conc.SC_EVENTS"}
   'SC_EHEAD {:rust "crate::conc::SC_EHEAD"
             :java "Conc.SC_EHEAD" :csharp "Conc.SC_EHEAD"}
   ;; THE WAITER TABLE. `SC_WAITERS` is the vector of waiter objects and
   ;; `SC_WFREE` the head of the free chain through their `W_NEXT` -- a
   ;; fixnum index, or -1 for none. The vector only ever grows: freeing
   ;; returns a slot to the chain rather than shortening it, which is why
   ;; the population has to be walked rather than read off the length.
   'SC_WAITERS {:rust "crate::conc::SC_WAITERS"
               :java "Conc.SC_WAITERS" :csharp "Conc.SC_WAITERS"}
   'SC_WFREE {:rust "crate::conc::SC_WFREE"
             :java "Conc.SC_WFREE" :csharp "Conc.SC_WFREE"}
   ;; A waiter's own slots. `W_GEN` is the reuse counter a token carries in
   ;; its high bits, and `W_THREAD` is the one that says the slot is LIVE:
   ;; nil there means freed, whatever else the row holds.
   'W_GEN {:rust "crate::conc::W_GEN"
          :java "Conc.W_GEN" :csharp "Conc.W_GEN"}
   'W_THREAD {:rust "crate::conc::W_THREAD"
             :java "Conc.W_THREAD" :csharp "Conc.W_THREAD"}
   'W_PORT {:rust "crate::conc::W_PORT"
           :java "Conc.W_PORT" :csharp "Conc.W_PORT"}
   'W_NEXT {:rust "crate::conc::W_NEXT"
           :java "Conc.W_NEXT" :csharp "Conc.W_NEXT"}
   'TH_STATUS {:rust "crate::conc::TH_STATUS"
              :java "Conc.TH_STATUS" :csharp "Conc.TH_STATUS"}
   'TH_PARK_ON {:rust "crate::conc::TH_PARK_ON"
               :java "Conc.TH_PARK_ON" :csharp "Conc.TH_PARK_ON"}
   ;; The rest of a thread's own slots, as `run-one` reads them. `TH_STACK`
   ;; is the saved value stack -- present exactly when the thread is parked,
   ;; which is what makes "is there a stack to put back?" the test for
   ;; whether this is a start or a resume.
   'TH_BINDINGS {:rust "crate::conc::TH_BINDINGS"
                :java "Conc.TH_BINDINGS" :csharp "Conc.TH_BINDINGS"}
   'TH_ENTRY {:rust "crate::conc::TH_ENTRY"
             :java "Conc.TH_ENTRY" :csharp "Conc.TH_ENTRY"}
   'TH_STACK {:rust "crate::conc::TH_STACK"
             :java "Conc.TH_STACK" :csharp "Conc.TH_STACK"}
   ;; The saved FRAME stack, the other half of what a park puts away. It
   ;; travels with `TH_STACK` everywhere it is written: a thread holding one
   ;; and not the other is a thread that would be resumed onto frames that do
   ;; not match its operands.
   'TH_FRAMES {:rust "crate::conc::TH_FRAMES"
              :java "Conc.TH_FRAMES" :csharp "Conc.TH_FRAMES"}
   ;; A throw the SCHEDULER owes this thread, delivered when it next runs
   ;; rather than at the moment it was decided.
   'TH_FAIL {:rust "crate::conc::TH_FAIL"
            :java "Conc.TH_FAIL" :csharp "Conc.TH_FAIL"}
   ;; What the thread ANSWERED -- its value when it is DONE, and the throw that
   ;; ended it when it is FAILED. One slot for both, because which it holds is
   ;; exactly what `TH_STATUS` already says.
   'TH_RESULT {:rust "crate::conc::TH_RESULT"
              :java "Conc.TH_RESULT" :csharp "Conc.TH_RESULT"}
   ;; The waiter token this thread is parked on, or -1. Cleared when it wakes,
   ;; so a late answer on the same token finds nothing to wake.
   'TH_TOKEN {:rust "crate::conc::TH_TOKEN"
             :java "Conc.TH_TOKEN" :csharp "Conc.TH_TOKEN"}
   ;; A waiter's remaining slots. `W_LEN` is how many a waiter has, which is
   ;; what `new-obj` is asked for.
   'W_KIND {:rust "crate::conc::W_KIND"
           :java "Conc.W_KIND" :csharp "Conc.W_KIND"}
   'W_LEN {:rust "crate::conc::W_LEN"
          :java "Conc.W_LEN" :csharp "Conc.W_LEN"}
   'PT_KIND {:rust "crate::conc::PT_KIND"
            :java "Conc.PT_KIND" :csharp "Conc.PT_KIND"}
   ;; THE RING. `PT_RING` is its capacity, `PT_INBOX` the slot array, and the
   ;; two cursors only ever grow -- the INDEX wraps, by `rem`, and the cursor
   ;; does not.
   'PT_RING {:rust "crate::conc::PT_RING"
            :java "Conc.PT_RING" :csharp "Conc.PT_RING"}
   'PT_INBOX {:rust "crate::conc::PT_INBOX"
             :java "Conc.PT_INBOX" :csharp "Conc.PT_INBOX"}
   'PT_WRITE {:rust "crate::conc::PT_WRITE"
             :java "Conc.PT_WRITE" :csharp "Conc.PT_WRITE"}
   'PT_READ {:rust "crate::conc::PT_READ"
            :java "Conc.PT_READ" :csharp "Conc.PT_READ"}
   ;; The rest of a port's own slots, as `new-port` fills them. `PT_LEN` is how
   ;; many a port has.
   'PT_LEN {:rust "crate::conc::PT_LEN"
           :java "Conc.PT_LEN" :csharp "Conc.PT_LEN"}
   ;; How many slots a THREAD has, and its own id. A thread's id comes from the
   ;; SAME counter a port's does -- `SC_NEXTID` is the sandbox's, not the port
   ;; table's -- so the two never collide and neither is an index into anything.
   'TH_LEN {:rust "crate::conc::TH_LEN"
           :java "Conc.TH_LEN" :csharp "Conc.TH_LEN"}
   'TH_ID {:rust "crate::conc::TH_ID"
          :java "Conc.TH_ID" :csharp "Conc.TH_ID"}
   ;; WHAT A PARKED THREAD IS WAITING FOR, left there by the host and read when
   ;; the thread next runs. `port-open` re-executes its call on resume and
   ;; finds the answer here rather than making a second request.
   'TH_PENDING {:rust "crate::conc::TH_PENDING"
               :java "Conc.TH_PENDING" :csharp "Conc.TH_PENDING"}
   ;; THE BOUND, and it means two different things by KIND: a channel's is a
   ;; count of MESSAGES and is what its ring is sized to; a bridge's is a
   ;; budget in BYTES, and its ring is `RING_MESSAGES` instead. Sizing a
   ;; bridge's ring from its cap would allocate one slot per byte allowed.
   'PT_CAP {:rust "crate::conc::PT_CAP"
           :java "Conc.PT_CAP" :csharp "Conc.PT_CAP"}
   'PT_BYTES {:rust "crate::conc::PT_BYTES"
             :java "Conc.PT_BYTES" :csharp "Conc.PT_BYTES"}
   ;; PEERS ARE LINKED BY ID, never by object: a field holding the peer would
   ;; keep it alive, and an unreachable flint end is exactly what says the
   ;; script is finished with it. `-1` is "no peer".
   'PT_PEER {:rust "crate::conc::PT_PEER"
            :java "Conc.PT_PEER" :csharp "Conc.PT_PEER"}
   'PT_LABEL {:rust "crate::conc::PT_LABEL"
             :java "Conc.PT_LABEL" :csharp "Conc.PT_LABEL"}
   'K_CHANNEL {:rust "crate::conc::K_CHANNEL"
              :java "Conc.K_CHANNEL" :csharp "Conc.K_CHANNEL"}
   'K_BRIDGE {:rust "crate::conc::K_BRIDGE"
             :java "Conc.K_BRIDGE" :csharp "Conc.K_BRIDGE"}
   ;; A BRIDGE'S RING, in messages. Its `PT_CAP` is a byte budget and a
   ;; different question -- see `kin/portbytes.kin`.
   'DEFAULT_BRIDGE_CAP {:rust "crate::conc::DEFAULT_BRIDGE_CAP"
                       :java "Conc.DEFAULT_BRIDGE_CAP"
                       :csharp "Conc.DEFAULT_BRIDGE_CAP"}
   ;; THE HOST IS NOW HOLDING THIS PORT. One `EV_RETAIN` per handle handed
   ;; out, matched by one `EV_RELEASE` when it goes.
   'EV_RETAIN {:rust "crate::conc::EV_RETAIN"
              :java "Conc.EV_RETAIN" :csharp "Conc.EV_RETAIN"}
   ;; PASCALISED ON THE CLR ALONE, and it is the only constant in that file
   ;; that is -- `PT_LEN`, `K_CHANNEL` and the rest are all SCREAMING there.
   ;; Checked in all three before this entry was written, because a name table
   ;; that guesses a spelling emits an undefined constant into one target.
   'RING_MESSAGES {:rust "crate::conc::RING_MESSAGES"
                  :java "Conc.RING_MESSAGES" :csharp "Conc.RingMessages"}
   'SC_NEXTID {:rust "crate::conc::SC_NEXTID"
              :java "Conc.SC_NEXTID" :csharp "Conc.SC_NEXTID"}
   ;; Both directions of every channel pairing, as two-element vectors. A
   ;; lookup walks it, so a pairing recorded one way only answers one way.
   'SC_PAIRS {:rust "crate::conc::SC_PAIRS"
             :java "Conc.SC_PAIRS" :csharp "Conc.SC_PAIRS"}
   ;; What a port's state says, and the two states `reap-ports` must not
   ;; overwrite: a tidy close and a peer that vanished are different things to
   ;; have happened, and only the second is an orphaning.
   'PT_STATE {:rust "crate::conc::PT_STATE"
             :java "Conc.PT_STATE" :csharp "Conc.PT_STATE"}
   'P_CLOSED {:rust "crate::conc::P_CLOSED"
             :java "Conc.P_CLOSED" :csharp "Conc.P_CLOSED"}
   'P_ORPHANED {:rust "crate::conc::P_ORPHANED"
               :java "Conc.P_ORPHANED" :csharp "Conc.P_ORPHANED"}
   ;; OPEN, and HALF-closed. A close makes the PEER half-closed rather than
   ;; closed: it may still drain what is already in its buffer and only then
   ;; reads end-of-stream, so the channel is not finished until both ends are.
   ;; Only an OPEN peer is moved -- a peer already closed or orphaned has had
   ;; something more specific happen to it, and `P_HALF` would overwrite it.
   'P_OPEN {:rust "crate::conc::P_OPEN"
           :java "Conc.P_OPEN" :csharp "Conc.P_OPEN"}
   'P_HALF {:rust "crate::conc::P_HALF"
           :java "Conc.P_HALF" :csharp "Conc.P_HALF"}
   ;; A port's own id, which is what the HOST knows it by: every event carries
   ;; this rather than the address, because an address is this heap's business.
   'PT_ID {:rust "crate::conc::PT_ID"
          :java "Conc.PT_ID" :csharp "Conc.PT_ID"}
   ;; The scheduler's two lists of live ends. `SC_BRIDGES` is host-facing and
   ;; `SC_PORTS` is channels; a collection of either end is what `reap-ports`
   ;; notices.
   'SC_BRIDGES {:rust "crate::conc::SC_BRIDGES"
               :java "Conc.SC_BRIDGES" :csharp "Conc.SC_BRIDGES"}
   'SC_PORTS {:rust "crate::conc::SC_PORTS"
             :java "Conc.SC_PORTS" :csharp "Conc.SC_PORTS"}
   ;; The system port, if this sandbox has one. Its own slot rather than a
   ;; search, because the control plane is found on every `drive`.
   'SC_SYSTEM {:rust "crate::conc::SC_SYSTEM"
              :java "Conc.SC_SYSTEM" :csharp "Conc.SC_SYSTEM"}
   ;; HOW MANY SLOTS A SCHEDULER HAS. NOT `SC_LEN`, which is the table
   ;; module's and is 5 -- see the note there.
   'SCHED_LEN {:rust "crate::conc::SC_LEN"
              :java "Conc.SC_LEN" :csharp "Conc.SC_LEN"}
   ;; One `EV_RELEASE` per `EV_RETAIN`, which is what makes the host's count a
   ;; count of holders rather than of arrivals (`DECISIONS.md#ports-are-the-hosts`).
   'EV_CLOSED {:rust "crate::conc::EV_CLOSED"
              :java "Conc.EV_CLOSED" :csharp "Conc.EV_CLOSED"}
   'EV_RELEASE {:rust "crate::conc::EV_RELEASE"
               :java "Conc.EV_RELEASE" :csharp "Conc.EV_RELEASE"}
   ;; An unpublished slot. The handshake between a writer that has reserved an
   ;; index and a reader that has reached it.
   'EMPTY {:rust "crate::value::EMPTY" :java "Val.EMPTY" :csharp "Val.Empty"}
   ;; The sentinel a parked thread leaves in `thrown`.
   'PARK {:rust "crate::value::PARK" :java "Val.PARK" :csharp "Val.Park"}
   'ST_NEW {:rust "crate::conc::ST_NEW"
           :java "Conc.ST_NEW" :csharp "Conc.ST_NEW"}
   ;; A COURTESY YIELD, and it is a FIXNUM rather than a heap value on
   ;; purpose: `settle` compares against it by BITS, which is what native's
   ;; copy already did. A heap sentinel would need a root and would turn the
   ;; comparison into an identity question.
   'PARK_YIELD {:rust "crate::conc::PARK_YIELD"
                :java "Conc.PARK_YIELD" :csharp "Conc.PARK_YIELD"}
   ;; THE WAIT KIND for a send that found the ring full -- a thread waiting
   ;; for SPACE. Its mirror is `WK_RECEIVE` below: the two park on the same
   ;; port for opposite reasons, and `wake-on` wakes both because either
   ;; event can be the one the other was waiting for.
   'WK_SEND {:rust "crate::conc::WK_SEND"
             :java "Conc.WK_SEND" :csharp "Conc.WK_SEND"}
   ;; THE WAIT KIND for a receive that found the ring empty. Distinct from
   ;; `WK_SEND`, which is a thread waiting for SPACE: the two park on the same
   ;; port for opposite reasons, and `wake-on` wakes both because either event
   ;; can be the one the other was waiting for.
   'WK_RECEIVE {:rust "crate::conc::WK_RECEIVE"
                :java "Conc.WK_RECEIVE" :csharp "Conc.WK_RECEIVE"}
   ;; THE WAIT KIND for a join. A thread is a wake key like any port, which
   ;; is why `wake-on` takes a value rather than a port and why joining needs
   ;; no machinery of its own.
   'WK_JOIN {:rust "crate::conc::WK_JOIN"
             :java "Conc.WK_JOIN" :csharp "Conc.WK_JOIN"}
   'ST_RUNNABLE {:rust "crate::conc::ST_RUNNABLE"
                :java "Conc.ST_RUNNABLE" :csharp "Conc.ST_RUNNABLE"}
   'ST_PARKED {:rust "crate::conc::ST_PARKED"
              :java "Conc.ST_PARKED" :csharp "Conc.ST_PARKED"}
   'ST_DONE {:rust "crate::conc::ST_DONE"
             :java "Conc.ST_DONE" :csharp "Conc.ST_DONE"}
   'ST_FAILED {:rust "crate::conc::ST_FAILED"
               :java "Conc.ST_FAILED" :csharp "Conc.ST_FAILED"}

   ;; THE WIRE CODEC'S SLOT LAYOUT (`DECISIONS.md#the-codec-is-guest-code`).
   ;; The LOGIC over these slots is `kin/wire.kin`; what stays in each runtime
   ;; is the layout itself and the thin builtin that registers a name, exactly
   ;; as `Table` keeps its own layout for `tableref.kin` to walk.
   'WR_BUF {:rust "crate::codec::WR_BUF"
            :java "Wire.WR_BUF" :csharp "Wire.WR_BUF"}
   'WR_LIVE {:rust "crate::codec::WR_LIVE"
             :java "Wire.WR_LIVE" :csharp "Wire.WR_LIVE"}
   'WR_NEED {:rust "crate::codec::WR_NEED"
             :java "Wire.WR_NEED" :csharp "Wire.WR_NEED"}
   'WR_LEN {:rust "crate::codec::WR_LEN"
            :java "Wire.WR_LEN" :csharp "Wire.WR_LEN"}
   'RD_BYTES {:rust "crate::codec::RD_BYTES"
              :java "Wire.RD_BYTES" :csharp "Wire.RD_BYTES"}
   'RD_POS {:rust "crate::codec::RD_POS"
            :java "Wire.RD_POS" :csharp "Wire.RD_POS"}
   'RD_LIVE {:rust "crate::codec::RD_LIVE"
             :java "Wire.RD_LIVE" :csharp "Wire.RD_LIVE"}
   'RD_LEN {:rust "crate::codec::RD_LEN"
            :java "Wire.RD_LEN" :csharp "Wire.RD_LEN"}
   ;; The bound on a table's `ncols * nrows`, which is the one frame built by
   ;; multiplying. `MAX_COUNT` is NOT here: it is the callers' rule, checked in
   ;; each runtime's shim beside the other argument checks.
   'MAX_CELLS {:rust "crate::codec::MAX_CELLS"
               :java "Codec.MAX_CELLS" :csharp "Codec.MAX_CELLS"}
   ;; `NO_NS` MEANS THE NAMESPACE IS ABSENT, which is not the same as empty: it
   ;; is followed by no bytes at all, so a walk that treated it as a length
   ;; would skip four billion.
   'NO_NS {:rust "crate::codec::NO_NS"
           :java "Codec.NO_NS" :csharp "Codec.NO_NS"}

   ;; THE TABLE'S SLOT LAYOUT. All three targets spell these identically --
   ;; Rust bare on `crate::table`, both ports as constants on their `Table`
   ;; class -- so each entry is three copies of one word. They are listed
   ;; anyway, for the reason `RF_SCHEMA` above is: the table is where
   ;; agreement is ASSERTED, and an agreement nobody wrote down is one the
   ;; next rename can quietly break.
   'SC_NAMES {:rust "crate::table::SC_NAMES"
             :java "Table.SC_NAMES" :csharp "global::Flint.Rt.Table.SC_NAMES"}
   'SC_TYPES {:rust "crate::table::SC_TYPES"
             :java "Table.SC_TYPES" :csharp "global::Flint.Rt.Table.SC_TYPES"}
   'SC_INDEX {:rust "crate::table::SC_INDEX"
             :java "Table.SC_INDEX" :csharp "global::Flint.Rt.Table.SC_INDEX"}
   'SC_IDS {:rust "crate::table::SC_IDS"
           :java "Table.SC_IDS" :csharp "global::Flint.Rt.Table.SC_IDS"}
   'SC_WIDTH {:rust "crate::table::SC_WIDTH"
             :java "Table.SC_WIDTH" :csharp "global::Flint.Rt.Table.SC_WIDTH"}
   ;; A SCHEMA's length, and note the prefix clash: `SC_*` names the TABLE
   ;; module here and the SCHEDULER module everywhere else in this file. The
   ;; scheduler's own length is `SCHED_LEN` below and deliberately not `SC_LEN`,
   ;; because this name was taken first and a source reaching for the obvious
   ;; one gets a 5 where it wanted an 11. Rust's module paths caught that; on
   ;; the ports both constants exist under different classes and the generated
   ;; code would have compiled a five-slot scheduler.
   'SC_LEN {:rust "crate::table::SC_LEN"
           :java "Table.SC_LEN" :csharp "global::Flint.Rt.Table.SC_LEN"}
   'TB_SCHEMA {:rust "crate::table::TB_SCHEMA"
              :java "Table.TB_SCHEMA" :csharp "global::Flint.Rt.Table.TB_SCHEMA"}
   'TB_CHUNKS {:rust "crate::table::TB_CHUNKS"
              :java "Table.TB_CHUNKS" :csharp "global::Flint.Rt.Table.TB_CHUNKS"}
   'TB_COUNT {:rust "crate::table::TB_COUNT"
             :java "Table.TB_COUNT" :csharp "global::Flint.Rt.Table.TB_COUNT"}
   'TB_OFFSET {:rust "crate::table::TB_OFFSET"
              :java "Table.TB_OFFSET" :csharp "global::Flint.Rt.Table.TB_OFFSET"}
   'TB_LEN {:rust "crate::table::TB_LEN"
           :java "Table.TB_LEN" :csharp "global::Flint.Rt.Table.TB_LEN"}
   'CH_ROWS {:rust "crate::table::CH_ROWS"
            :java "Table.CH_ROWS" :csharp "global::Flint.Rt.Table.CH_ROWS"}
   'CH_ENC {:rust "crate::table::CH_ENC"
           :java "Table.CH_ENC" :csharp "global::Flint.Rt.Table.CH_ENC"}
   'CH_BASE {:rust "crate::table::CH_BASE"
            :java "Table.CH_BASE" :csharp "global::Flint.Rt.Table.CH_BASE"}
   'ENC_FLAT {:rust "crate::table::ENC_FLAT"
             :java "Table.ENC_FLAT" :csharp "global::Flint.Rt.Table.ENC_FLAT"}
   'ENC_CONST {:rust "crate::table::ENC_CONST"
              :java "Table.ENC_CONST" :csharp "global::Flint.Rt.Table.ENC_CONST"}
   'RF_CHUNK {:rust "crate::table::RF_CHUNK"
             :java "Table.RF_CHUNK" :csharp "global::Flint.Rt.Table.RF_CHUNK"}
   'RF_ROW {:rust "crate::table::RF_ROW"
           :java "Table.RF_ROW" :csharp "global::Flint.Rt.Table.RF_ROW"}
   'RF_LEN {:rust "crate::table::RF_LEN"
           :java "Table.RF_LEN" :csharp "global::Flint.Rt.Table.RF_LEN"}
   'CHUNK {:rust "crate::table::CHUNK"
          :java "Table.CHUNK" :csharp "global::Flint.Rt.Table.CHUNK"}
   'CHUNK_SHIFT {:rust "crate::table::CHUNK_SHIFT"
                :java "Table.CHUNK_SHIFT" :csharp "global::Flint.Rt.Table.CHUNK_SHIFT"}

   ;; A TRANSIENT table's slots, spelled the same by all three.
   'TT_SCHEMA {:rust "crate::table::TT_SCHEMA"
              :java "Table.TT_SCHEMA" :csharp "global::Flint.Rt.Table.TT_SCHEMA"}
   'TT_CHUNKS {:rust "crate::table::TT_CHUNKS"
              :java "Table.TT_CHUNKS" :csharp "global::Flint.Rt.Table.TT_CHUNKS"}
   'TT_COUNT {:rust "crate::table::TT_COUNT"
             :java "Table.TT_COUNT" :csharp "global::Flint.Rt.Table.TT_COUNT"}
   'TT_OPEN {:rust "crate::table::TT_OPEN"
            :java "Table.TT_OPEN" :csharp "global::Flint.Rt.Table.TT_OPEN"}
   'TT_LIVE {:rust "crate::table::TT_LIVE"
            :java "Table.TT_LIVE" :csharp "global::Flint.Rt.Table.TT_LIVE"}
   'TT_LEN {:rust "crate::table::TT_LEN"
           :java "Table.TT_LEN" :csharp "global::Flint.Rt.Table.TT_LEN"}

   ;; A SET's slots. A set IS a map whose values are its keys, so the map
   ;; is a slot rather than the object.
   'S_MAP {:rust "crate::set::S_MAP"
          :java "Sets.S_MAP" :csharp "global::Flint.Rt.Sets.S_MAP"}
   ;; THE EXCEPTION SLOTS. Four, and `EX_CAUSE` is the one that makes an
   ;; exception a chain rather than a leaf.
   'EX_KIND {:rust "crate::err::EX_KIND"
             :java "Rt.EX_KIND" :csharp "global::Flint.Rt.Rt.ExKindSlot"}
   'EX_MSG {:rust "crate::err::EX_MSG"
            :java "Rt.EX_MSG" :csharp "global::Flint.Rt.Rt.ExMsgSlot"}
   'EX_DATA {:rust "crate::err::EX_DATA"
             :java "Rt.EX_DATA" :csharp "global::Flint.Rt.Rt.ExDataSlot"}
   'EX_CAUSE {:rust "crate::err::EX_CAUSE"
              :java "Rt.EX_CAUSE" :csharp "global::Flint.Rt.Rt.ExCauseSlot"}
   'S_META {:rust "crate::set::S_META"
           :java "Sets.S_META" :csharp "global::Flint.Rt.Sets.S_META"}
   'S_HASH {:rust "crate::set::S_HASH"
           :java "Sets.S_HASH" :csharp "global::Flint.Rt.Sets.S_HASH"}
   ;; THE LONGEST STRING THAT IS INTERNED, 32 bytes in all three. Two interned
   ;; strings that are not bit-equal are not equal, and that is the whole of
   ;; the fast path -- no bytes are looked at at all.
   ;; FULLY QUALIFIED ON THE JVM, and not optional: a GENERATED `Interns`
   ;; lives in `com._3sln.flint.kgen.rt`, so a bare `Interns` inside another
   ;; generated module binds to that one and not to the runtime's. The same
   ;; shadowing `seq-of` is qualified against.
   ;; THE MOST NEGATIVE INTEGER. Its negation and its division by -1 are the
   ;; two integer operations that OVERFLOW, and naming the bound is how the
   ;; guard gets written once instead of three host idioms deep.
   'I64_MIN {:rust "i64::MIN" :java "Long.MIN_VALUE" :csharp "long.MinValue"}
   'I64_MAX {:rust "i64::MAX" :java "Long.MAX_VALUE" :csharp "long.MaxValue"}
   ;; THE TWO DOUBLES WITH NO LITERAL. `1.0 / 0.0` and `0.0 / 0.0` produce
   ;; them on every one of these hosts, but writing that is a puzzle where a
   ;; name will do, and one of the three spells division by zero as an error
   ;; for integers -- so a reader has to stop and check which this is.
   'F64_INF {:rust "f64::INFINITY" :java "Double.POSITIVE_INFINITY"
             :csharp "double.PositiveInfinity"}
   ;; NOT THE HOST'S NaN. This was `f64::NAN` / `Double.NaN` / `double.NaN`,
   ;; and .NET's is the NEGATIVE quiet NaN -- FFF8000000000000 against
   ;; 7FF8000000000000 on the other two. It is the `##NaN` READER LITERAL, so
   ;; the divergence was one character of ordinary flint source away, and the
   ;; bits reach `hash-double`, the snapshot and the wire codec. `=` could
   ;; never see it: two NaNs are unequal whichever bits they carry.
   ;;
   ;; Reinterpreting `CANONICAL_NAN` makes the three the same NUMBER rather
   ;; than three hosts' idea of one. `longBitsToDouble` is the safe half of
   ;; the jvm pair -- it hands back the pattern it was given, where
   ;; `doubleToLongBits` canonicalises and caused a divergence of its own.
   'F64_NAN {:rust "f64::from_bits(crate::value::CANONICAL_NAN)"
             :java "Double.longBitsToDouble(Val.CANONICAL_NAN)"
             :csharp "System.BitConverter.Int64BitsToDouble(Val.CanonicalNan)"}
   'INTERN_MAX {:rust "crate::strs::INTERN_MAX"
                :java "com.flint.rt.Interns.INTERN_MAX"
                :csharp "global::Flint.Rt.Interns.InternMax"}
   ;; The TRANSIENT map and set slots. Written out rather than declared bare
   ;; because they do NOT share a home: both ports hang them off `Maps` and
   ;; `Sets`, and native had no names for them at all until this entry wanted
   ;; some.
   'TM_CNT {:rust "crate::map::TM_CNT"
            :java "Maps.TM_CNT" :csharp "global::Flint.Rt.Maps.TM_CNT"}
   'TM_ROOT {:rust "crate::map::TM_ROOT"
             :java "Maps.TM_ROOT" :csharp "global::Flint.Rt.Maps.TM_ROOT"}
   'TM_EDIT {:rust "crate::map::TM_EDIT"
             :java "Maps.TM_EDIT" :csharp "global::Flint.Rt.Maps.TM_EDIT"}
   'TS_MAP {:rust "crate::set::TS_MAP"
            :java "Sets.TS_MAP" :csharp "global::Flint.Rt.Sets.TS_MAP"}
   'TS_EDIT {:rust "crate::set::TS_EDIT"
             :java "Sets.TS_EDIT" :csharp "global::Flint.Rt.Sets.TS_EDIT"}
   'LS_THUNK {:rust "LS_THUNK" :java "LS_THUNK" :csharp "LS_THUNK"}
   'LS_SEQ {:rust "LS_SEQ" :java "LS_SEQ" :csharp "LS_SEQ"}
   ;; A byte rope's header, and a transient byte string's. All three targets
   ;; spell these identically -- Rust has them bare on `crate::bytes`, and both
   ;; ports as constants on their `Bytes` class -- so each entry is three copies
   ;; of one string, asserted rather than assumed.
   'BB_BYTES {:rust "crate::bytes::BB_BYTES" :java "Bytes.BB_BYTES" :csharp "global::Flint.Rt.Bytes.BB_BYTES"}
   'BB_DEPTH {:rust "crate::bytes::BB_DEPTH" :java "Bytes.BB_DEPTH" :csharp "global::Flint.Rt.Bytes.BB_DEPTH"}
   'TB_TREE {:rust "crate::bytes::TB_TREE" :java "Bytes.TB_TREE" :csharp "global::Flint.Rt.Bytes.TB_TREE"}
   'TB_FILL {:rust "crate::bytes::TB_FILL" :java "Bytes.TB_FILL" :csharp "global::Flint.Rt.Bytes.TB_FILL"}
   'TB_LIVE {:rust "crate::bytes::TB_LIVE" :java "Bytes.TB_LIVE" :csharp "global::Flint.Rt.Bytes.TB_LIVE"}
   'TB_TAIL {:rust "crate::bytes::TB_TAIL" :java "Bytes.TB_TAIL" :csharp "global::Flint.Rt.Bytes.TB_TAIL"}
   ;; How big an open tail is. `FLAT_MAX` on all three -- the tail is exactly
   ;; the largest flat leaf, so a full one is handed to the tree WHOLE rather
   ;; than copied.
   'TAIL_CAP {:rust "crate::bytes::TAIL_CAP" :java "Bytes.TAIL_CAP" :csharp "global::Flint.Rt.Bytes.TAIL_CAP"}
   'BB_FLAT {:rust "crate::bytes::BB_FLAT" :java "Bytes.BB_FLAT" :csharp "global::Flint.Rt.Bytes.BB_FLAT"}
   'BB_HASH {:rust "crate::bytes::BB_HASH" :java "Bytes.BB_HASH" :csharp "global::Flint.Rt.Bytes.BB_HASH"}
   'BB_KIDS {:rust "crate::bytes::BB_KIDS" :java "Bytes.BB_KIDS" :csharp "global::Flint.Rt.Bytes.BB_KIDS"}
   ;; The two tier numbers. Rust keeps ONE copy, on `rope`, and `Bytes` uses
   ;; it from there; both ports keep a second copy on `Bytes` beside the one on
   ;; `Str`. So there are two homes in the ports for one number, and this table
   ;; has to pick -- it picks `Str`, which is where Rust's lives, so the name
   ;; means the same thing in all three. Pointing it at `Bytes` compiled for as
   ;; long as only byte sources used it and broke the first time a rope one did.
   'FLAT_MAX {:rust "crate::rope::FLAT_MAX" :java "Str.FLAT_MAX" :csharp "global::Flint.Rt.Str.FLAT_MAX"}
   ;; What may be COPIED to avoid making a new leaf -- see `rope.rs`. Not
   ;; `FLAT_MAX`: that bound is paid once for a result, this one is paid on
   ;; every append into the same leaf.
   'MERGE_MAX {:rust "crate::rope::MERGE_MAX" :java "Str.MERGE_MAX" :csharp "global::Flint.Rt.Str.MERGE_MAX"}
   'FANOUT {:rust "crate::rope::FANOUT" :java "Str.FANOUT" :csharp "global::Flint.Rt.Str.FANOUT"}
   ;; The object header's width. A leaf's bytes begin `HDR` past its address,
   ;; which is the one place a generated source does address arithmetic.
   'HDR {:rust "crate::obj::HDR" :java "Obj.HDR" :csharp "Obj.Hdr"}
   ;; THE LARGE-OBJECT THRESHOLD, which is a NAME and not a form: kin refuses
   ;; a constant-shaped symbol that no name table spells, on the ground that
   ;; passing it through verbatim is only right when every target agrees --
   ;; and here they do not. Native declares it a `u32` and both ports a
   ;; `long`, so the Rust spelling widens, because everything compared against
   ;; it is a size and a size is an `Addr`.
   'LARGE_OBJECT {:rust "(crate::gc::LARGE_OBJECT as Addr)"
                  :java "Gc.LARGE_OBJECT" :csharp "Gc.LARGE_OBJECT"}
   ;; Where a FLAT STRING's bytes begin, which is not `HDR`: a `TY_STR` carries
   ;; a header of its own before them.
   'STR_DATA {:rust "crate::obj::STR_DATA" :java "Obj.STR_DATA" :csharp "Obj.StrData"}
   ;; THE THREE LAYOUTS. A variant on Rust and an `int` on the ports -- see
   ;; the `Layout` tag. The clr prefixes them because `Str` is a class there
   ;; and `Obj.STR` would collide with it.
   'L_VALS {:rust "crate::obj::Layout::Vals" :java "Obj.VALS" :csharp "Obj.LVals"}
   'L_STR {:rust "crate::obj::Layout::Str" :java "Obj.STR" :csharp "Obj.LStr"}
   'L_RAW {:rust "crate::obj::Layout::Raw" :java "Obj.RAW" :csharp "Obj.LRaw"}
   ;; A slice smaller than this COPIES rather than shares. It is `Str`'s
   ;; constant in both ports and `rope`'s in Rust -- the same number in a
   ;; different home, which is what this table is for.
   'SLICE_MIN {:rust "crate::rope::SLICE_MIN" :java "Str.SLICE_MIN" :csharp "global::Flint.Rt.Str.SLICE_MIN"}
   ;; A rope's header. Rust keeps these on `obj` with every other layout
   ;; constant; both ports keep them on `Str`. Same numbers, different home.
   'RP_BYTES {:rust "crate::obj::RP_BYTES" :java "Str.RP_BYTES" :csharp "global::Flint.Rt.Str.RP_BYTES"}
   'RP_CPS {:rust "crate::obj::RP_CPS" :java "Str.RP_CPS" :csharp "global::Flint.Rt.Str.RP_CPS"}
   'RP_FLAT {:rust "crate::obj::RP_FLAT" :java "Str.RP_FLAT" :csharp "global::Flint.Rt.Str.RP_FLAT"}
   'RP_HASH {:rust "crate::obj::RP_HASH" :java "Str.RP_HASH" :csharp "global::Flint.Rt.Str.RP_HASH"}
   'RP_KIDS {:rust "crate::obj::RP_KIDS" :java "Str.RP_KIDS" :csharp "global::Flint.Rt.Str.RP_KIDS"}}
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
            ARRAY_MAP_MAX
            ;; THE VECTOR's trie shape and slot names. They did NOT agree when
            ;; this entry was written: the CLR spelled them `Bits`, `Width`,
            ;; `Mask`, `VCnt` .. `VHash` while its own `Maps` used `HM_ROOT`
            ;; and `AM_META` two files away. One runtime disagreeing with the
            ;; other two AND with itself is worth less than C# casing
            ;; convention, so the CLR was renamed to match.
            ;;
            ;; `V_HASH` is here because it now exists everywhere. It was a
            ;; native-only slot -- six slots on Rust, five on both ports --
            ;; and the ports could not cache a vector's hash for want of
            ;; somewhere to put it.
            BITS WIDTH MASK
            V_CNT V_SHIFT V_ROOT V_TAIL V_META V_HASH
            T_CNT T_SHIFT T_ROOT T_TAIL T_EDIT
            ;; The CONS slots. The CLR spelled these `CFirst` .. `CCount`,
            ;; the same PascalCase divergence its `Vec` constants had, and it
            ;; was renamed to match for the same reason: one runtime
            ;; disagreeing with the other two is worth less than casing
            ;; convention.
            C_FIRST C_REST C_META C_COUNT])))

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
    ;; AN INLINE KEYWORD AS THE INLINE STRING OF ITS NAME. A tag swap: the
    ;; payload and the length are already in the right places.
    'take-opaque-id (core/call {:rust "{0}.take_opaque_id()"
                                :java "{0}.takeOpaqueId()"
                                :csharp "{0}.TakeOpaqueId()"}
                               {:tag I64})
    'kw-to-str (core/call {:rust "{1}.kw_to_str()"
                           :java "Val.kwToStr({1})" :csharp "Val.KwToStr({1})"}
                          {:tag Value})
    ;; THE INTEGER VALUE, given it IS one. `as-i64` answers `Option`/`Long` in
    ;; the three runtimes and the shapes do not converge; this is the total
    ;; form its callers actually want, and it answers 0 for a non-integer
    ;; because every one of them has asked `is-int` first.
    'i64-of (core/call {:rust "{0}.i64_of({1})"
                        :java "Num.i64Of({0}, {1})" :csharp "Num.I64Of({0}, {1})"}
                       {:tag I64})
    'to-i64 (core/call {:rust "({0} as i64)" :java "((long) {0})" :csharp "((long) {0})"}
                       {:tag I64})
    'is-bool (core/call {:rust "{1}.is_bool()"
                         :java "Val.isBool({1})" :csharp "Val.IsBool({1})"}
                        {:tag Bool})
    ;; A KEYWORD FROM A LITERAL, with no namespace. `kind-of` and `type-ok` are
    ;; written entirely in these, and until now the vocabulary could not spell
    ;; one -- which is why both stayed hand-written while everything around
    ;; them was generated.
    'bare-kw (core/call {:rust "{0}.keyword(None, {1})"
                         :java "Str.keyword({0}, null, {1})"
                         :csharp "Str.Keyword({0}, null, {1})"}
                        {:tag Value})
    'is-double (core/call {:rust "{1}.is_double()"
                           :java "Val.isDouble({1})" :csharp "Val.IsDouble({1})"})
    'is-inline-str (core/call {:rust "{1}.is_inline_str()"
                               :java "Val.isInlineStr({1})" :csharp "Val.IsInlineStr({1})"})
    'is-inline-kw (core/call {:rust "{1}.is_inline_kw()"
                              :java "Val.isInlineKw({1})" :csharp "Val.IsInlineKw({1})"})
    ;; Is this value a FIXNUM? The companion to `nil?`, and needed wherever a
    ;; slot holds "a number or nothing" -- a cons's cached count, a vector's
    ;; cached hash. Same receiver-first shape as `nil?`.
    'is-fixnum (core/call {:rust "{1}.is_fixnum()"
                           :java "Val.isFixnum({1})"
                           :csharp "Val.IsFixnum({1})"})
    'as-fixnum (core/call {:rust "{0}.as_fixnum()"
                           :java "Val.asFixnum({0})"
                           :csharp "Val.AsFixnum({0})"})
    'to-i32 (core/call {:rust "({0} as u32)" :java "((int) {0})" :csharp "((int) {0})"})
    ;; A NUMBER NARROWED TO A TYPE TAG. Rust spells a `Ty` `u8` and both ports
    ;; call it an `int`, so this is a real cast on one target and nothing on
    ;; the other two -- which is the whole reason it is a word rather than a
    ;; bare expression. `obj-size-of` is the first source to take a tag out of
    ;; a header word and then hand it to something typed `Ty`.
    'to-ty (core/call {:rust "({0} as u8)" :java "{0}" :csharp "{0}"}
                      {:tag Ty})
    ;; WRAPPING ARITHMETIC, which a hash needs and plain `*` cannot give: Rust
    ;; PANICS on overflow in a debug build, so `h * 31` would be correct in
    ;; release and a crash in the build that runs the tests. Java's `int` wraps
    ;; on its own; C# is unchecked by default but says so here, because the
    ;; default is a compiler setting and this must not depend on one.
    'wmul (core/call {:rust "{0}.wrapping_mul({1})"
                      :java "({0} * {1})"
                      :csharp "unchecked({0} * {1})"})
    ;; WRAPPING SUBTRACTION, which the young-generation predicates are built
    ;; on: `(addr - from) <u (half)` is in range exactly when `addr` is, and
    ;; an address BELOW `from` is meant to wrap to something enormous rather
    ;; than panic. Rust would panic in a debug build without this, which is
    ;; why native already spells it `wrapping_sub` by hand.
    'wsub (core/call {:rust "{0}.wrapping_sub({1})"
                      :java "({0} - {1})"
                      :csharp "unchecked({0} - {1})"})
    'wadd (core/call {:rust "{0}.wrapping_add({1})"
                      :java "({0} + {1})"
                      :csharp "unchecked({0} + {1})"})
    ;; A BOOL AS ITS BIT, which is not the same form as `to-i32` and cannot be.
    ;; Rust casts a `bool` to an integer with `as`; Java and C# both REFUSE the
    ;; cast outright, so each needs a conditional. `to-i32` on a `Bool` emitted
    ;; `((int) ascii)` into both ports -- code that does not compile in either,
    ;; and that no name check can see because every name in it exists.
    'bool-bit (core/call {:rust "({0} as u32)"
                          :java "({0} ? 1 : 0)"
                          :csharp "({0} ? 1 : 0)"})

    ;; --- HOST ARRAYS ----------------------------------------------------
    ;;
    ;; NOT an array subject, which two analyses in a row said was the wrong
    ;; shape for what phase 3 actually needs. Three templates: read, write,
    ;; length. Indexing is spelled identically everywhere and only the length
    ;; disagrees, which is the whole of what a host array costs.
    'aget (core/call {:rust "{0}[{1}]" :java "{0}[{1}]" :csharp "{0}[{1}]"})

    ;; ONE BYTE OF A `Bytes`, AS A NUMBER IN 0..255 -- which is not `aget`, and
    ;; the reason is the single ugliest portability trap in this file.
    ;;
    ;;     rust     `u8`, unsigned              `b[i] as u32`     0..255
    ;;     java     `byte`, SIGNED              `b[i]`            -128..127
    ;;     csharp   `byte`, unsigned            `b[i]`            0..255
    ;;
    ;; Java is the odd one and it is odd SILENTLY: `h * 31 + b[i]` compiles,
    ;; runs, and agrees with the other two on every ASCII byte, because ASCII
    ;; stops at 0x7F. It diverges on the first byte with the top bit set --
    ;; which is to say on the first non-ASCII character anybody hashes. The
    ;; hand-written `Hash.java` had the `& 0xFF` and the hand-written `Hash.cs`
    ;; had no cast at all; both were right, and both were right BY HAND.
    ;;
    ;; The index is widened for Rust only: an integer is not a `usize`.
    'byte-at (core/call {:rust "({0}[{1} as usize] as u32)"
                         :java "({0}[{1}] & 0xFF)"
                         :csharp "((int) {0}[{1}])"}
                        {:tag U32})

    ;; A DOUBLE'S BITS, as an `I64`. Not a reinterpretation any of the three
    ;; spells the same way, and not one any of them will do with a cast: `as`
    ;; in Rust CONVERTS the number, `(long) d` in Java and C# truncates it.
    ;; Each host has a named intrinsic and the three names share nothing.
    ;; RAW BITS, and the java spelling matters. This emitted
    ;; `Double.doubleToLongBits`, which COLLAPSES every NaN to the canonical
    ;; one, where `to_bits()` and `DoubleToInt64Bits` preserve the payload --
    ;; so one kin source meant two different things. Measured 2026-09-22:
    ;; `hash-double` of a negative NaN answered 2146959360 on the jvm and
    ;; -524288 on native and the clr. A word that does not mean the same in
    ;; three languages is the one failure kin exists to prevent, and no
    ;; drivers file caught it because none passes a non-canonical NaN.
    'f64-bits (core/call {:rust "({0}.to_bits() as i64)"
                          :java "Double.doubleToRawLongBits({0})"
                          :csharp "System.BitConverter.DoubleToInt64Bits({0})"}
                         {:tag I64})

    ;; AND THE REVERSE, which is a REINTERPRETATION and not a conversion --
    ;; `to-f64` is `as f64`, which turns the integer 1 into 1.0, where this
    ;; turns the bits of 1 into 5e-324. Two different operations that a
    ;; careless reading of the names would swap.
    'f64-of-bits (core/call {:rust "f64::from_bits({0} as u64)"
                              :java "Double.longBitsToDouble({0})"
                              :csharp "System.BitConverter.Int64BitsToDouble({0})"}
                             {:tag F64})

    ;; THE LOGICAL RIGHT SHIFT AT 64 BITS. `ushr` next door is the 32-bit one
    ;; and its C# spelling casts through `uint`, which would take the top half
    ;; off a `long` before shifting it -- a wrong answer rather than a compile
    ;; error, on exactly the negative doubles `hash-double` exists for.
    'ushr64 (core/call {:rust "((({0} as u64) >> {1}) as i64)"
                        :java "({0} >>> {1})"
                        :csharp "((long)((ulong) {0} >> {1}))"}
                       {:tag I64})
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

    ;; THE RIGHT SHIFTS, which the comment above and two in `kin.lang` have
    ;; been describing as though they existed. They did not. `shl` stood here
    ;; alone, explaining the absence of its counterpart as though the
    ;; counterpart were somewhere else, and nothing had needed a right shift
    ;; yet -- `Vec` needs one in `tailOff`, `arrayFor`, `newPath`, `pushTail`
    ;; and `popTail`, which is nearly every path in the file.
    ;;
    ;; NEITHER takes a tag, and that is worth saying because the comparisons
    ;; above do. The reason the shifts are simpler is that each template is
    ;; already right for both tags:
    ;;
    ;;   `ushr`  Rust's host type is `u32` for `I32` and `U32` alike, so `>>`
    ;;           is already the logical shift. Java's `>>>` is logical whatever
    ;;           the high bit holds, and agrees with `>>` when it is clear.
    ;;   `sar`   Java and C# spell `int` and `>>` is already arithmetic. Rust
    ;;           has to go through `i32` and come back, because its host type
    ;;           is unsigned and the OPERATOR does not choose there.
    ;;
    ;; So the tag decides nothing at the use; the target does, once. Naming the
    ;; two separately is still what makes a reader's intent survive the trip --
    ;; a single `>>` would be three runtimes silently disagreeing above 2^31.
    'ushr (core/call {:rust "({0} >> {1})"
                      :java "({0} >>> {1})"
                      :csharp "((int)((uint) {0} >> {1}))"})
    'sar (core/call {:rust "((({0} as i32) >> {1}) as u32)"
                     :java "({0} >> {1})"
                     :csharp "({0} >> {1})"})

    ;; THE 64-BIT ARITHMETIC SHIFT, which `sar` is not: that one narrows to
    ;; `i32` on the way through, which is right for a 32-bit field and wrong
    ;; for sign-extending a 48-bit fixnum payload -- it would cut the value in
    ;; half before propagating anything. `ushr64` had no arithmetic twin until
    ;; `valtag` needed one.
    'sar64 (core/call {:rust "((({0} as i64) >> {1}) as i64)"
                        :java "({0} >> {1})"
                        :csharp "({0} >> {1})"}
                       {:tag I64})

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
    ;; A CAPTURE SLOT IS SIGNED, because -1 means "not captured" and every
    ;; other index is non-negative. `I32` is UNSIGNED on Rust and signed on
    ;; the other three, so storing a pc or a position into a slot buffer needs
    ;; the cast said out loud on the one target that distinguishes them.
    'to-slot (core/call {:rust "({0} as i32)" :java "{0}" :csharp "{0}"})
    ;; And back: a slot read as an index, for a pc taken out of a thread row.
    'slot-idx (core/call {:rust "({0} as u32)" :java "{0}" :csharp "{0}"})
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

    ;; Functions kin itself GENERATED, in `merge.kin` and `copies.kin`. A
    ;; generated function is reachable from another source only through a
    ;; declaration like any other -- `defn` registers a name for its own file's
    ;; self-calls and nothing wider, which is what keeps one source from
    ;; silently depending on another's internals.
    ;; The COLLISION-NODE accessors. `cn-hash` did not exist in Rust -- the
    ;; three operations were written out at each use -- so the helper was
    ;; added there rather than the inline form taught to kin: the ports had
    ;; already named it, and a name is the portable half.
    ;; `category` is itself generated, by `kin/eq.kin`, and is reached from
    ;; inside its own class on the ports -- so it is `own`, not `sibling`.
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
                           :csharp "global::Flint.Rt.Maps.Empty({0})"})
    ;; The shared EMPTY VECTOR singleton, alongside `empty-map`. An empty
    ;; vector is three objects -- a root node, a tail node and the header --
    ;; so it is built once at startup and handed out, not rebuilt per `pop`
    ;; of a one-element vector.
    ;; SHORT names, unlike `empty-map` next door, and the difference is worth
    ;; stating. Every generated Java module imports `com.flint.rt.*` and every
    ;; generated C# one has `using Flint.Rt;`, so `Vec` resolves in both --
    ;; and a probe fixture in `kin/*.drivers` is one file in the default
    ;; package, where a fully qualified `com.flint.rt.Vec` cannot be faked at
    ;; all. `empty-map` is qualified because it was written that way, not
    ;; because it has to be.
    ;;
    ;; The risk the qualification guards against is a kin source named `vec`
    ;; generating a `flint.rt.Vec` that shadows this. There is none, and one
    ;; would be a compile error rather than a silent wrong call.
    ;; The EMPTY LIST singleton, and a zero-argument INVOKE.
    ;;
    ;; `invoke-thunk` is how generated code calls back into guest code -- a
    ;; lazy seq's thunk, and nothing else so far. It takes no arguments, so
    ;; each target spells its empty argument list its own way and the source
    ;; says none of it.
    'empty-list (core/call {:rust "{0}.empty_list()"
                            :java "com.flint.rt.Seqs.emptyList({0})"
                            :csharp "global::Flint.Rt.Seqs.EmptyList({0})"})
    'invoke-thunk (core/call {:rust "{0}.invoke({1}, &[])"
                              :java "{0}.call({1}, new long[0])"
                              :csharp "{0}.Call({1}, System.Array.Empty<long>())"})
    'empty-vec (core/call {:rust "{0}.empty_vec()"
                           :java "Vec.empty({0})"
                           :csharp "Vec.Empty({0})"})
    ;; The shared EMPTY SET, alongside `empty-map` and `empty-vec`.
    'empty-set (core/call {:rust "{0}.empty_set()"
                           :java "Sets.empty({0})"
                           :csharp "global::Flint.Rt.Sets.Empty({0})"})
    ;; `ref-get` and `ref-to-map` are the same shape one tier up, and stay for
    ;; the same reason: a row ref answers `get` like a map, so `mapread`
    ;; reaches for them -- and `ref-get` finds its column THROUGH a map, so
    ;; `tableref` reaches back. Both are generated, in `flint.rt.tableref`;
    ;; the templates below name the host facade that forwards to them, which
    ;; is the one hop a declaration cannot avoid here.
    'ref-to-map (core/call {:rust "{0}.ref_to_map({1})"
                            :java "Table.refToMap({0}, {1})"
                            :csharp "global::Flint.Rt.Table.refToMap({0}, {1})"})
    'ref-get (core/call {:rust "{0}.ref_get({1}, {2}, {3})"
                         :java "Table.refGet({0}, {1}, {2}, {3})"
                         :csharp "global::Flint.Rt.Table.refGet({0}, {1}, {2}, {3})"})
    ;; STILL HAND-WRITTEN, and reached as a call rather than a sibling until
    ;; it is not: `check-row` builds the refusal messages that `checks` is about,
    ;; and those are the last part of `Table` to port.
    ;; `eq_may_alloc` is GENERATED, by `eqalloc.kin`, so it is `own` rather
    ;; than a hand-written sibling -- and `own` is now exactly right for one:
    ;; a bare call, with the static import derived from the fact that this
    ;; source mentions the name. It used to be spelled `Eq.eqMayAlloc`,
    ;; because the region was spliced into `Eq.java`; it lives in
    ;; `flint.rt.Eqalloc` now and nothing needs to say so.
    ;; `eq` and `hash-value` live in Eq on the ports, not in Maps -- a
    ;; SIBLING rather than one of our own, and the distinction is exactly what
    ;; the two helpers exist to keep straight.
    ;;
    ;; Written out rather than through `sibling` because both ports need the
    ;; class FULLY QUALIFIED here, for different reasons -- see the note on
    ;; `eq-may-alloc` above. `sibling` takes one class name for both ports
    ;; and cannot say either of them.
    ;; THE TWO THAT STAY, and why they are not a shortcut.
    ;;
    ;; A forward reference WITHIN a file is `declare`'s job now, and every
    ;; entry that was here for that reason is gone -- `coll-assoc`,
    ;; `coll-dissoc` and `val-cmp` each declare themselves in their own
    ;; source and need nothing here.
    ;;
    ;; These two are a different thing. `val-eq` and `hash-value` are the
    ;; UNIVERSAL DISPATCHERS: every collection needs them to compare and to
    ;; hash a key, and they in turn need every collection to compare or hash
    ;; one. Reached through requires, `valeq` and `valhash` land in one knot
    ;; with `mapread`, `mapwrite`, `assoc`, `dissoc`, `find`, `collhash`,
    ;; `setcore`, `seqwalk` and `tableref` -- and kin refuses a namespace
    ;; cycle, as Clojure does.
    ;;
    ;; `declare` does not reach it: that breaks a cycle inside ONE namespace,
    ;; and this one runs across eleven. So the declaration lives here, which
    ;; is what a declaration is for. Merging the eleven would be the only
    ;; other honest answer, and they are eleven different subjects.
    ;; Population count. Three intrinsics for one idea -- the shape a
    ;; vocabulary exists for.
    ;; ROUND UP TO EIGHT. Every object starts on an eight-byte boundary, so
    ;; the two layouts whose size is a BYTE count round and the one measured
    ;; in slots does not. It is `(n + 7) & ~7` in all three and has been for
    ;; as long as there have been three.
    'align8 (core/call {:rust "crate::obj::align8({0})"
                         :java "Obj.align8({0})"
                         :csharp "Obj.Align8({0})"}
                        {:tag Addr})
    ;; BITWISE COMPLEMENT, for clearing a field: `w & ~(7 << 21)`. Rust spells
    ;; it `!` where both ports say `~`, which is the whole reason it is a word
    ;; -- and the reason to be careful with it, since Rust's `!` on a `bool` is
    ;; LOGICAL not. Every caller here applies it to a mask.
    ;;
    ;; No kin source had needed one until the header writers: nothing else
    ;; generated clears a field in place.
    'bit-not (core/call {:rust "(!{0})" :java "(~{0})" :csharp "(~{0})"})
    'popcount (core/call {:rust "{0}.count_ones()"
                          :java "Integer.bitCount({0})"
                          :csharp "System.Numerics.BitOperations.PopCount((uint)({0}))"})

    ;; The siblings these files actually reach for.
    ;; THREE arguments after the receiver, not two. `nth` gained a `dflt`
    ;; when absence stopped being a sentinel each caller had to know; this
    ;; entry said 2 for a while after the function said 3, which would have
    ;; generated a call that does not compile the moment a source used it.
    ;; The CHARACTER lookup, with the same `dflt` shape. Rust calls it
    ;; `char_at`; both ports call it `Str.nth`, camel on the JVM and Pascal on
    ;; the CLR, which is what the four-argument `sibling` is for.
    ;; TABLES. Both ports spell these camelCase -- `tableRef`, not `TableRef`
    ;; -- so the C# name is given explicitly rather than pascalised.
    ;; A TAGGED INTEGER from a host `i64`: a fixnum when it fits and a boxed
    ;; bigint when it does not, which is what makes integers CANONICAL.
    ;; A DOUBLE as a value. Takes the `f64` and not a value, so it is a `call`
    ;; rather than a sibling: there is no receiver.
    'of-double (core/call {:rust "Value::from_f64({0})"
                           :java "Val.ofDouble({0})"
                           :csharp "Val.OfDouble({0})"}
                          {:tag Value})
    ;; TOWARD ZERO. Native has it in `fmath`; both ports spelled the
    ;; `ceil`-or-`floor` branch out at each of two call sites.
    'f-trunc (core/call {:rust "crate::fmath::trunc({0})"
                         :java "Num.trunc({0})"
                         :csharp "Num.Trunc({0})"}
                        {:tag F64})
    ;; THE THREE SPECIALS, asked of the host because every one of them has
    ;; the question built in. `(!= d d)` would answer the first and a compare
    ;; against the largest finite double would answer the others, but both are
    ;; the kind of clever that reads as a bug.
    'f-nan? (core/call {:rust "{0}.is_nan()"
                        :java "Double.isNaN({0})"
                        :csharp "double.IsNaN({0})"}
                       {:tag Bool})
    'f-inf? (core/call {:rust "{0}.is_infinite()"
                        :java "Double.isInfinite({0})"
                        :csharp "double.IsInfinity({0})"}
                       {:tag Bool})
    ;; THE SHORTEST DECIMAL that reads back as the same double, pushed into a
    ;; code-point buffer as the characters of whatever notation the host
    ;; prefers. This is the one part of number formatting worth borrowing from
    ;; a host library -- getting it right is Ryu-shaped work -- and the
    ;; notation it arrives in is deliberately NOT trusted: `dblstr.kin` reads
    ;; the digits back out and re-renders them, because the three hosts spell
    ;; one double `1e20`, `1.0E20` and `1E+20`.
    ;;
    ;; INTO A BUFFER rather than into a string, which is worth 857 bytes in
    ;; every module that prints a number -- MEASURED, by building the units
    ;; both ways: 315 026 with the string, 314 169 with this. A string would
    ;; have to be walked back out with `code-points`, a UTF-8 decoder over
    ;; three string tiers, and every character here is a digit, a point, an
    ;; `e` or a sign. Handing over the characters skips both the string and
    ;; the decoder.
    'f64-digits (core/call {:rust "{0}.f64_digits({1}, {2})"
                            :java "Str.f64Digits({0}, {1}, {2})"
                            :csharp "Str.F64Digits({0}, {1}, {2})"})
    ;; A VALIDATED DECIMAL AS A DOUBLE, over the code-point range `from`..`to`.
    ;; The mirror of `f64-digits`, and the same bargain: correctly rounded
    ;; decimal-to-binary conversion is worth borrowing and nothing else is.
    ;; `strnum.kin` has already decided the run is a number, so the host is
    ;; never asked what one looks like -- which matters, because Rust accepts
    ;; `inf` and `infinity`, Java accepts a trailing `d`, and the CLR accepts
    ;; a third set. The range is in code points and the run is ASCII by
    ;; construction, so those are byte offsets too.
    'f64-of-str (core/call {:rust "{0}.f64_of_str({1}, {2}, {3})"
                            :java "Str.f64OfStr({0}, {1}, {2}, {3})"
                            :csharp "Str.F64OfStr({0}, {1}, {2}, {3})"}
                           {:tag F64})
    ;; A STRING FROM A LITERAL. The vocabulary could not spell one, so a
    ;; constant answer like `##NaN` had to be assembled byte by byte through a
    ;; sink -- which is part of why each runtime kept its own copy of the
    ;; special-double names, and why two of them printed `Infinity`.
    'str-const (core/call {:rust "{0}.string({1})"
                           :java "Str.of({0}, {1})"
                           :csharp "Str.Of({0}, {1})"}
                          {:tag Value})
    ;; A HOST INTEGER AS A DOUBLE, for the arms that promote.
    'to-f64 (core/call {:rust "({0} as f64)" :java "((double) {0})"
                        :csharp "((double) {0})"}
                       {:tag F64})
    ;; FLOAT DIVISION. `quot` and `rem` in this vocabulary are the INTEGER
    ;; ones, and on two `U32`s they are the unsigned pair -- so the float
    ;; operator gets its own name rather than overloading either.
    'fdiv (core/call {:rust "({0} / {1})" :java "({0} / {1})"
                      :csharp "({0} / {1})"}
                     {:tag F64})
    ;; "not a number: X and Y", naming BOTH sides -- the refusal every
    ;; arithmetic operation shares.
    'throw-not-a-number (core/call {:rust "{0}.throw_not_a_number({1}, {2})"
                                    :java "Num.notNumber({0}, {1}, {2})"
                                    :csharp "Num.NotNumber({0}, {1}, {2})"}
                                   {:tag Value})
    ;; `set-eq` STAYS HAND-WRITTEN because it walks the set's elements, and
    ;; walking needs a callback -- the closure hole. `map-eq` is here for the
    ;; same reason and neither is a candidate until that is answered.
    ;; `hash-map` and `hash-set` walk their contents through a callback, so
    ;; they stay hand-written for the same reason `map-eq` and `set-eq` do.
    ;; THE STRING AND KEYWORD HASHES, each of which READS A CACHE. Both ports
    ;; had the cache and used neither: `strHash` was written during interning
    ;; and never read, and a keyword's slot 2 was set to nil rather than to the
    ;; hash. So a heap string paid its length per lookup and a keyword paid its
    ;; ns and name -- on the two commonest map keys there are. The bodies are
    ;; host work (UTF-8 decode); reading the cache is not, and now all three do.
    ;; TWO STRINGS IN UTF-16 CODE UNIT ORDER, across all three tiers. Native
    ;; read `str_bytes` inline, which debug-asserts `TY_STR` -- so in a release
    ;; build comparing anything past `FLAT_MAX` read a rope's SLOTS as UTF-8.
    ;; Measured: two unequal 1 400-byte strings compared as 0, and `sort` over
    ;; them was silently wrong on wasm.
    ;; `val-cmp` IS `flint.rt.valcmp`'s OWN, for the reason `val-eq` and
    ;; `hash-value` are theirs: its two helpers call it from above its
    ;; definition. The RUST spelling stays `compare`, which is what every
    ;; native caller already says.
    ;; `str-cmp` WAS HERE, naming a hand-written function on each runtime. All
    ;; three flattened both operands to compare them -- `strings-and-matching` lists comparison
    ;; among the operations that must WALK -- and synthesised UTF-16 out of
    ;; UTF-8 to reproduce `String.compareTo`'s ordering. `kin/ropecmp.kin`
    ;; walks and compares bytes, which is code point order already.
    ;; NUMBERS: `is-number` is either tier, and `num-cmp` orders across them.
    'num-cmp (core/call {:rust "{0}.num_cmp({1}, {2})"
                         :java "Num.cmp({0}, {1}, {2})"
                         :csharp "Num.Cmp({0}, {1}, {2})"}
                        {:tag Cmp})
    ;; `hash-double` WAS HERE, as a link to three hand-written `Hash` classes,
    ;; and it is gone: `kin/hashtext.kin` generates it, so `numarith.kin` and
    ;; `valhash.kin` require it from there like any other sibling. A form in
    ;; this file stands for something the host owns; that one stood for
    ;; arithmetic written out three times, which is the thing kin removes.
    ;; FULLY QUALIFIED, and this one is not optional. A generated module lives
    ;; in `flint.rt`, and `seqs.kin` generates a `flint.rt.Seqs` there -- so a
    ;; bare `Seqs.seq` inside another generated module binds to the GENERATED
    ;; class, which has `vecseq` and `range` and no `seq` at all. C# said
    ;; "'Seqs' does not contain a definition for 'Seq'" and was right.
    ;;
    ;; This is the collision `java-emit` already documents as the reason the
    ;; generated package had to move; it reaches the call sites too, wherever a
    ;; hand-written class and a generated one share a name. `Maps` has the same
    ;; shape and `empty-map` was already written qualified for it.
    ;; Written out rather than built by `sibling`, because the CLASS differs
    ;; per target here and `sibling` varies only the method name.
    ;; `seq-of` and `next-of` NAME THE GENERATED CLASS, for the reason
    ;; `first-of` does: the port's `Seqs` copies were shims over exactly these,
    ;; and a second definition under a name generated code reaches stops the
    ;; CLR compiling the moment one generated file has both in scope.
    'seq-of (core/call {:rust "{0}.seq({1})"
                        :java "com._3sln.flint.kgen.rt.Seqwalk.seq({0}, {1})"
                        :csharp "global::_3sln.Flint.Kgen.Rt.Seqwalk.Seq({0}, {1})"})
    ;; `first-of` NAMES THE GENERATED CLASS, not the port's `Seqs`. The shim
    ;; there existed only so this entry could keep its old spelling -- and a
    ;; second definition under a name generated code also reaches is not free:
    ;; the CLR refused to compile a generated file that had both in scope.
    'first-of (core/call {:rust "{0}.first({1})"
                          :java "com._3sln.flint.kgen.rt.Seqwalk.first({0}, {1})"
                          :csharp "global::_3sln.Flint.Kgen.Rt.Seqwalk.First({0}, {1})"})
    'next-of (core/call {:rust "{0}.next({1})"
                         :java "com._3sln.flint.kgen.rt.Seqwalk.next({0}, {1})"
                         :csharp "global::_3sln.Flint.Kgen.Rt.Seqwalk.Next({0}, {1})"})

    ;; --- STRING BUILDING, hole 5 -----------------------------------------
    ;;
    ;; THE FIRST FORM HERE WHOSE EMIT IS CODE RATHER THAN DATA, and that is the
    ;; whole of what hole 5 was. Every other form is a template with numbered
    ;; slots, which works because the arity is fixed. `str-cat` is VARIADIC,
    ;; and Rust's `format!` needs a LITERAL format string -- so the Rust side
    ;; has to build `"{}{}{}"` from the argument count, which no template can
    ;; do. kin already allowed this: a form is `(fn [ctx form] ...)`, which is
    ;; how `for` and `while` are written. Nothing in kin had to change.
    ;;
    ;; EVERY PIECE IS AN ARGUMENT, including the literals: Rust gets
    ;; `format!("{}{}", "col :", x)` and never `format!("col :{}", x)`. Putting
    ;; a literal into the format string would mean escaping every `{` a message
    ;; happens to contain, and a message that says `{` is not hypothetical --
    ;; `describe` prints map syntax. The inline form is prettier and the
    ;; argument form cannot be wrong.
    ;; A LITERAL as a `Text`. The ports return the literal itself -- both spell
    ;; a string constant as a `String`/`string` already -- and Rust's literal is
    ;; a `&'static str`, which is not the `String` the function returns. So the
    ;; conversion lives on the Rust side only, and it allocates: `describe` is
    ;; an error path, called when a program is already about to be told it did
    ;; something wrong, and paying an allocation there to have ONE source is
    ;; the trade this port makes everywhere.
    ;; A LITERAL, kept borrowed. Every target passes it straight through --
    ;; Rust's string literal already IS a `&'static str`, and the ports' already
    ;; is a `String`. The point is what does NOT happen: no allocation and no
    ;; copy at each of the forty places a literal is answered.
    'static-text (core/call {:rust "{0}" :java "{0}" :csharp "{0}"}
                            {:tag StaticText})
    ;; ... and the ONE conversion, where a borrowed literal becomes the owned
    ;; string a caller can keep.
    'own-text (core/call {:rust "alloc::string::String::from({0})"
                          :java "{0}" :csharp "{0}"}
                         {:tag Text})
    ;; Is a borrowed literal the empty one? Used as "I have no literal for
    ;; this", which is the only case that has to build a string.
    'text-empty (core/call {:rust "{1}.is_empty()"
                            :java "{1}.isEmpty()"
                            :csharp "({1}.Length == 0)"})

    ;; A STRING VALUE as a host string. Both ports spell it `Str.text`; Rust
    ;; grew `value_text` for it, because `as_str` borrows and so cannot flatten
    ;; a rope nobody has materialised.
    'value-text (core/call {:rust "{0}.value_text({1})"
                            :java "Str.text({0}, {1})"
                            :csharp "Str.Text({0}, {1})"}
                           {:tag Text})
    ;; SLOT `i` OF A MAP ENTRY, or element `i` of anything else. Rust had it
    ;; and both ports read slot 0 unconditionally, which is a garbage key when
    ;; a row is written as `[[:a 1] [:b 2]]`.
    'slot-or-nth (core/call {:rust "{0}.slot_or_nth_pub({1}, {2})"
                             :java "{0}.slotOrNth({1}, {2})"
                             :csharp "{0}.SlotOrNth({1}, {2})"}
                            {:tag Value})
    'name-of (core/call {:rust "{0}.name_of({1})"
                         :java "{0}.nameOf({1})" :csharp "{0}.NameOf({1})"}
                        {:tag Value})
    ;; STILL HAND-WRITTEN, and reached as calls until they are not. `kind-of`
    ;; is the closed set of `threads-and-ports` and `type-ok` the schema's type check; both
    ;; want a keyword built from a literal, which the vocabulary cannot spell
    ;; yet.
    'kind-of (core/call {:rust "{0}.kind_of({1})"
                         :java "{0}.kindOf({1})" :csharp "{0}.KindOf({1})"}
                        {:tag Value})
    'text (core/call {:rust "alloc::string::String::from({0})"
                      :java "{0}" :csharp "{0}"}
                     {:tag Text})

    'str-cat
    (fn [ctx form]
      (let [args (mapv (fn [f] (kin/render ctx f)) (rest form))
            code (if (= :rust (core/t ctx))
                   (str "alloc::format!(\"" (str/join (repeat (count args) "{}"))
                        "\", " (str/join ", " args) ")")
                   ;; Java and C# concatenate, and the parens matter: this may
                   ;; sit inside a larger expression, and `+` binds looser than
                   ;; the call it might land in.
                   (str "(" (str/join " + " args) ")"))]
        (kin/tagged! ctx Text)
        (if (= :statement (kin/position ctx))
          (kin/emit! ctx (kin/indent-of ctx) code ";\n")
          (kin/emit! ctx code))))

    ;; `throw-str` takes a BUILT message, hoisted into a `let` by the source.
    ;; Rust's `throw_str` wants `&str` and `str-cat` answers a `String`, so the
    ;; `&` lives here; and the hoist is not optional there, because building
    ;; the message calls `&mut self` methods and so does `throw_str`.
    'throw-str (core/call {:rust "{0}.throw_str({1}, &{2})"
                           :java "{0}.throwStr({1}, {2})"
                           :csharp "{0}.ThrowStr({1}, {2})"}
                          {:tag Value})


    ;; THE THREE STRING MEASUREMENTS, hand-written in all three and staying
    ;; that way. Each is O(1) for a rope -- the header carries it -- and each
    ;; has to decode UTF-8 for the other two tiers, which needs a host slice.
    ;; `s-count` and `s-ascii` are exactly the numbers a rope node caches, so a
    ;; generated `rope-node` asks for them rather than recomputing them.
    ;; `count` ON A STRING IS IN CODE POINTS, not UTF-16 code units. Clojure
    ;; counts UTF-16, so an astral character counts 2 there and 1 here -- a
    ;; deliberate divergence, recorded in the README.
    ;;
    ;; THE ONLY WORD FOR THIS. There were three -- `char-count`, `char-len`
    ;; and this -- and the first two reached a hand-written function on the
    ;; ports that walked the whole rope where this reads a slot. An alias is
    ;; how the implementations got to differ; one word is how they cannot.
    ;; The empty string is INTERNED, not allocated -- an inline value with
    ;; nothing on the heap -- so unlike `b-empty` the generated half cannot
    ;; build one and asks for it.
    ;; The other hand-written half: copying a RANGE out into a fresh string,
    ;; which needs a byte sink AND a UTF-8 decode.

    ;; ONE BYTE out of the heap, at an absolute address. Rust reads a leaf
    ;; through `raw_bytes`, which hands back a borrowed slice -- hole 6, and
    ;; REFUSED on measurement. Both ports already read the byte directly, and
    ;; Rust has the same primitive one layer down, so this converges onto the
    ;; spelling all three can say rather than the one only Rust can.
    ;; --- THE WALK, the sink's sibling ---------------------------------
    ;; THE CODE-POINT BUFFER, read and write. `cps-at` and `cps-len` are the
    ;; read half that `Sink` still lacks -- a sink can be filled and handed
    ;; away but not inspected, which is why the text constructors could not be
    ;; generated. This buffer has both from the start.
    ;; The two edges: a code point arrives as `I32` and leaves as one.
    'cp-signed (core/call {:rust "({1} as i32)" :java "{1}" :csharp "{1}"} {:tag Cmp})
    'cp-unsigned (core/call {:rust "({1} as u32)" :java "{1}" :csharp "{1}"} {:tag I32})
    'cps-open (core/call {:rust "{0}.cps_open()"
                          :java "{0}.cpsOpen()" :csharp "{0}.CpsOpen()"}
                         {:tag Cps})
    'cps-close (core/call {:rust "{0}.cps_close({1})"
                           :java "{0}.cpsClose({1})" :csharp "{0}.CpsClose({1})"})
    'cps-put (core/call {:rust "{0}.cps_put({1}, {2})"
                         :java "{0}.cpsPut({1}, {2})" :csharp "{0}.CpsPut({1}, {2})"})
    'cps-len (core/call {:rust "{0}.cps_len({1})"
                         :java "{0}.cpsLen({1})" :csharp "{0}.CpsLen({1})"}
                        {:tag I32})
    'cps-at (core/call {:rust "{0}.cps_at({1}, {2})"
                        :java "{0}.cpsAt({1}, {2})" :csharp "{0}.CpsAt({1}, {2})"}
                       {:tag I32})
    'walk-open (core/call {:rust "{0}.walk_open({1})"
                           :java "{0}.walkOpen({1})" :csharp "{0}.WalkOpen({1})"}
                          {:tag Walk})
    'walk-close (core/call {:rust "{0}.walk_close({1})"
                            :java "{0}.walkClose({1})" :csharp "{0}.WalkClose({1})"})
    'walk-dup (core/call {:rust "{0}.walk_dup({1})"
                          :java "{0}.walkDup({1})" :csharp "{0}.WalkDup({1})"}
                         {:tag Walk})
    'walk-take (core/call {:rust "{0}.walk_take({1}, {2})"
                           :java "{0}.walkTake({1}, {2})" :csharp "{0}.WalkTake({1}, {2})"})
    'walk-next (core/call {:rust "{0}.walk_next({1})"
                           :java "{0}.walkNext({1})" :csharp "{0}.WalkNext({1})"}
                          {:tag Value})
    'walk-done (core/call {:rust "{0}.walk_done({1})"
                            :java "{0}.walkDone({1})" :csharp "{0}.WalkDone({1})"}
                           {:tag Bool})
    ;; A RUN comparison rather than a byte loop: this is `b-eq`'s inner loop
    ;; over two 500 KB sections, and one `memcmp` against half a million calls
    ;; is the whole cost of the operation.
    ;; `run-eq`'s sibling, for the same reason one function along: a scan that
    ;; asked `leaf-byte` per byte cost about 9ns a byte -- every one a call
    ;; that branches on the leaf's tier -- and made `index-of` on a 34 KB rope
    ;; six times slower than the flatten it replaced. This decides the tier
    ;; ONCE and scans the backing store, so the per-byte cost is paid only at
    ;; positions that can start a match.
    'leaf-find (core/call {:rust "{0}.leaf_find({1}, {2}, {3})"
                           :java "{0}.leafFind({1}, {2}, {3})"
                           :csharp "{0}.LeafFind({1}, {2}, {3})"}
                          {:tag I32})
    'run-eq (core/call {:rust "{0}.run_eq({1}, {2}, {3})"
                        :java "{0}.runEq({1}, {2}, {3})"
                        :csharp "{0}.RunEq({1}, {2}, {3})"}
                       {:tag Bool})

    ;; --- THE SINK, hole 5's other half --------------------------------
    'sink-open (core/call {:rust "{0}.sink_open()"
                           :java "{0}.sinkOpen()" :csharp "{0}.SinkOpen()"}
                          {:tag Sink})
    'sink-close (core/call {:rust "{0}.sink_close({1})"
                            :java "{0}.sinkClose({1})" :csharp "{0}.SinkClose({1})"})
    'sink-len (core/call {:rust "{0}.sink_len({1})"
                          :java "{0}.sinkLen({1})" :csharp "{0}.SinkLen({1})"}
                         {:tag I32})
    'sink-put (core/call {:rust "{0}.sink_put({1}, {2})"
                          :java "{0}.sinkPut({1}, {2})" :csharp "{0}.SinkPut({1}, {2})"})
    ;; A RUN of heap bytes. The read and the write touch different fields of
    ;; the runtime, which is why this is one operation rather than a loop over
    ;; `read-u8` -- and why it is the only one that has to know an address.
    'sink-put-run (core/call {:rust "{0}.sink_put_run({1}, {2}, {3})"
                              :java "{0}.sinkPutRun({1}, {2}, {3})"
                              :csharp "{0}.SinkPutRun({1}, {2}, {3})"})
    ;; The other direction: bytes OUT of a sink and into the heap, which is
    ;; how a whole byte string is appended into a transient's open tail.
    ;; An INLINE string's bytes, which live in the value rather than the heap
    ;; -- so there is no address for `sink-put-run` and unpacking one is
    ;; per-target work.
    ;; A LEAF, whichever tier it is. An inline string keeps its bytes and its
    ;; length in the VALUE; a flat string keeps them at `STR_DATA`; a byte leaf
    ;; at `HDR`. One question, three places to look -- so it is asked once here
    ;; rather than branched on in every source that walks leaves.
    ;; GAS. Charging is a mutation of the runtime, not of the value, and a
    ;; generated source has to be able to do it: `resource-limits` says gas is
    ;; proportional to work, and a loop that scans a leaf has done work whether
    ;; it was written by hand or not. `charge-bytes` is the same charge divided
    ;; by eight, which every runtime already spells for itself.
    ;; CHARGE AND REFUSE. `charge-work` cannot fail; this one answers false
    ;; when the budget is gone, and the caller unwinds. `resource-limits` wants the two
    ;; kept apart: a scan that is bounded charges, and one that is not has to
    ;; be stoppable.
    ;; CALL A FLINT CLOSURE, with the arguments taken from a contiguous run of
    ;; shadow-stack roots. The `(base, n)` convergence again: a generated source
    ;; cannot hold a host array, and the callers that would have built one were
    ;; building it once per iteration.
    ;; STILL HAND-WRITTEN. `map-entry-vector` is one of the five functions the
    ;; closure hole genuinely blocks -- it walks a CHAMP with a host callback --
    ;; and `set-element-vector` is its twin. Reached as calls until that hole
    ;; closes.
    ;; `map-entry-as-vec` USED TO BE DECLARED HERE, as a call into three
    ;; hand-written bodies. `kin/vecroots.kin` generates it now, so a source
    ;; that wants it REQUIRES it -- which also puts the warning where it can
    ;; be read: it is NOT `map-entry-vector`, and the two are one letter
    ;; apart in use. That one turns a MAP into a vector OF entries; this one
    ;; turns ONE entry into a vector of its key and value. Reaching for the
    ;; wrong one gave `(assoc [:a 1] 0 :z)` the answer `[:z]` on the JVM.
    'invoke-roots (core/call {:rust "{0}.invoke_roots({1}, {2}, {3})"
                              :java "{0}.invokeRoots({1}, {2}, {3})"
                              :csharp "{0}.InvokeRoots({1}, {2}, {3})"}
                             {:tag Value})
    ;; IS AN EXCEPTION IN FLIGHT? A native caller that invokes guest code has to
    ;; ask after every call: the throw does not unwind the host stack, it sets
    ;; a field (`tables`).
    'is-thrown (core/call {:rust "!{0}.thrown.is_nil()"
                           :java "!Val.isNil({0}.thrown)"
                           :csharp "!Val.IsNil({0}.thrown)"}
                          {:tag Bool})
    'charge-tick (core/call {:rust "{0}.charge_tick({1} as u64, {2} as u64, {3})"
                             :java "{0}.chargeTick({1}, {2}, {3})"
                             :csharp "{0}.ChargeTick({1}, {2}, {3})"}
                            {:tag Bool})
    'charge-checked (core/call {:rust "{0}.charge_checked({1} as u64, {2})"
                                :java "{0}.chargeChecked({1}, {2})"
                                :csharp "{0}.ChargeChecked({1}, {2})"}
                               {:tag Bool})
    'charge-work (core/call {:rust "{0}.charge_work({1} as u64)"
                             :java "{0}.chargeWork({1})" :csharp "{0}.ChargeWork({1})"})
    'charge-bytes (core/call {:rust "{0}.charge_bytes({1})"
                              :java "{0}.chargeBytes({1})" :csharp "{0}.ChargeBytes({1})"})
    ;; THE ASCII BIT a flat string carries in its header. Set once when the
    ;; string is built, so `s-ascii` is a read rather than a scan -- and each
    ;; runtime spells the header differently enough that this cannot be
    ;; expressed as a `read-u8`.
    'str-is-ascii (core/call {:rust "crate::obj::str_is_ascii(&{0}.gc.sp, {1})"
                              :java "Obj.strIsAscii({0}.gc.sp, {1})"
                              :csharp "Obj.StrIsAscii({0}.gc.sp, {1})"}
                             {:tag Bool})
    'leaf-len (core/call {:rust "{0}.leaf_len({1})"
                          :java "{0}.leafLen({1})" :csharp "{0}.LeafLen({1})"}
                         {:tag I32})
    'leaf-byte (core/call {:rust "{0}.leaf_byte({1}, {2})"
                           :java "{0}.leafByte({1}, {2})" :csharp "{0}.LeafByte({1}, {2})"}
                          {:tag I32})
    'sink-put-inline (core/call {:rust "{0}.sink_put_inline({1}, {2})"
                                 :java "{0}.sinkPutInline({1}, {2})"
                                 :csharp "{0}.SinkPutInline({1}, {2})"})
    'sink-copy-out (core/call {:rust "{0}.sink_copy_out({1}, {2}, {3}, {4})"
                               :java "{0}.sinkCopyOut({1}, {2}, {3}, {4})"
                               :csharp "{0}.SinkCopyOut({1}, {2}, {3}, {4})"})
    'sink-bytes (core/call {:rust "{0}.sink_bytes({1})"
                            :java "{0}.sinkBytes({1})" :csharp "{0}.SinkBytes({1})"}
                           {:tag Value})
    ;; CONTIGUOUS, not tiered. `sink-string` may answer a rope for a big
    ;; enough result; a flatten that did would put a rope in `RP_FLAT` and hand
    ;; every caller a tree in place of the contiguous bytes it asked for.
    'sink-contiguous (core/call {:rust "{0}.sink_contiguous({1})"
                                 :java "{0}.sinkContiguous({1})"
                                 :csharp "{0}.SinkContiguous({1})"}
                                {:tag Value})
    'sink-string (core/call {:rust "{0}.sink_string({1})"
                             :java "{0}.sinkString({1})" :csharp "{0}.SinkString({1})"}
                            {:tag Value})

    ;; ONE BYTE INTO THE HEAP, at an absolute address. The mirror of `read-u8`,
    ;; and the whole of what appending to a transient's open tail is.
    'write-u8 (core/call {:rust "{0}.gc.sp.write_u8({1}, {2} as u8)"
                          :java "{0}.gc.sp.writeU8({1}, {2})"
                          :csharp "{0}.gc.sp.WriteU8({1}, {2})"})

    ;; RAW 64-BIT ACCESS, which is how a BIGINT carries its payload: eight
    ;; bytes at `HDR`, no tag. Every runtime already has the pair -- `mem.rs`
    ;; `read_u64`/`write_u64`, `Space.java` `readU64`/`writeU64` and the clr's
    ;; twin -- and all three use NATIVE byte order, which is little-endian on
    ;; every target flint builds for. Native's hand-written `integer` spelled
    ;; it `to_le_bytes` and `from_le_bytes`, making explicit what the other
    ;; two left implicit; the three agree, and this word is where that stops
    ;; being a coincidence of three spellings.
    ;; SPACE-ROOTED, for code handed a `Space` rather than an `Rt` -- see the
    ;; `Space` tag. Argument zero is the space itself, where in the four words
    ;; below it is the `Rt` the space is reached through.
    ;;
    ;; ONE WORD AND NOT FOUR. The write and 64-bit forms were written at the
    ;; same time as this one, on the reasoning that a space-rooted layer would
    ;; obviously want them, and `bin/check-vocab-used` refused all three the
    ;; same afternoon: nothing called them, so their nine templates had never
    ;; run. That is the gate doing exactly its job -- `uquot` and `urem` got
    ;; into the vocabulary the same way, by being obviously useful. The header
    ;; words are 32 bits, so the 64-bit pair had no caller in prospect either.
    ;; `sp-write-u32` comes back with the header WRITERS, in the change that
    ;; calls it.
    ;; UNSIGNED LESS-THAN AT 64 BITS. `<` is unsigned-aware at 32 only --
    ;; `Integer.compareUnsigned` on the jvm -- and an ADDRESS comparison needs
    ;; the wider one. Rust gets it free because `Addr` is a `u64`; both ports
    ;; spell a `long` signed and have to say so.
    'ult64 (core/call {:rust "({0} < {1})"
                       :java "(Long.compareUnsigned({0}, {1}) < 0)"
                       :csharp "((ulong) {0} < (ulong) {1})"}
                      {:tag Bool})
    ;; THE `Gc`'S YOUNG-GENERATION BOUNDS, read. The names line up across all
    ;; three runtimes, which is why these are five one-line words and not a
    ;; negotiation.
    'gc-young-base (core/call {:rust "{0}.young_base"
                               :java "{0}.youngBase" :csharp "{0}.youngBase"}
                              {:tag Addr})
    'gc-half (core/call {:rust "{0}.half" :java "{0}.half" :csharp "{0}.half"}
                        {:tag Addr})
    'gc-from (core/call {:rust "{0}.from" :java "{0}.from" :csharp "{0}.from"}
                        {:tag Addr})
    'gc-bump (core/call {:rust "{0}.bump" :java "{0}.bump" :csharp "{0}.bump"}
                        {:tag Addr})
    'gc-old-live (core/call {:rust "{0}.old_live"
                             :java "{0}.oldLive" :csharp "{0}.oldLive"}
                            {:tag Addr})
    'sp-read-u32 (core/call {:rust "{0}.read_u32({1})"
                             :java "{0}.readU32({1})" :csharp "{0}.ReadU32({1})"}
                            {:tag I32})
    ;; BACK, with a caller this time. It was written alongside `sp-read-u32`,
    ;; refused by `bin/check-vocab-used` for having none, and removed; the
    ;; header WRITERS are the change that calls it.
    'sp-write-u32 (core/call {:rust "{0}.write_u32({1}, {2})"
                              :java "{0}.writeU32({1}, {2})" :csharp "{0}.WriteU32({1}, {2})"})
    ;; The 64-bit write, for a SLOT. Its read counterpart is deliberately
    ;; absent: `slot` is the only thing that would want one, and `slot` is not
    ;; portable -- native guards it with a `debug_assertions` check that a
    ;; forwarded pointer is never read outside the collector, which kin has no
    ;; way to spell and neither port has at all.
    'sp-write-u64 (core/call {:rust "{0}.write_u64({1}, {2} as u64)"
                              :java "{0}.writeU64({1}, {2})" :csharp "{0}.WriteU64({1}, {2})"})
    'write-u64 (core/call {:rust "{0}.gc.sp.write_u64({1}, {2} as u64)"
                            :java "{0}.gc.sp.writeU64({1}, {2})"
                            :csharp "{0}.gc.sp.WriteU64({1}, {2})"})
    'read-u64 (core/call {:rust "({0}.gc.sp.read_u64({1}) as i64)"
                           :java "{0}.gc.sp.readU64({1})"
                           :csharp "{0}.gc.sp.ReadU64({1})"}
                          {:tag I64})
    'read-u8 (core/call {:rust "({0}.gc.sp.read_u8({1}) as u32)"
                         :java "{0}.gc.sp.readU8({1})"
                         :csharp "{0}.gc.sp.ReadU8({1})"})
    ;; An index widened to an ADDRESS. `Addr` is `u64` in Rust and `long` in
    ;; both ports; `as-idx` widens to `usize`, which is 32 bits on wasm and
    ;; would silently be the wrong type here.
    'to-addr (core/call {:rust "({0} as Addr)" :java "{0}" :csharp "{0}"})
    ;; A 32-BIT WORD WIDENED TO AN ADDRESS, ZERO-EXTENDED. Not `to-addr`,
    ;; which is the trap: on both ports `to-addr` emits NOTHING, so a value
    ;; the host is holding in an `int` widens with its SIGN, and a low word at
    ;; or above 2^31 becomes a 64-bit address with its top 32 bits set.
    ;;
    ;; The three runtimes each spell the correct thing differently -- Rust's
    ;; `as Addr` from a `u32` zero-extends on its own, the jvm needs
    ;; `Integer.toUnsignedLong` and the clr a trip through `uint` -- which is
    ;; exactly the shape a vocabulary word exists for. All three hand-written
    ;; `forward_target`s already did this correctly and by three different
    ;; routes; the word is what stops the fourth one getting it wrong.
    'addr-of-u32 (core/call {:rust "({0} as Addr)"
                             :java "Integer.toUnsignedLong({0})"
                             :csharp "((long)(uint) {0})"}
                            {:tag Addr})

    ;; ALLOCATE AND WRAP, answering NIL when the heap refused. `alloc` hands
    ;; back a raw address and 0 for a failure, which every caller then has to
    ;; test and wrap identically -- so they do it here instead. Both ports kept
    ;; TWO copies of this, one on `Conc` and one on `Table`.
    ;; WHAT `drive` CALLS. Each is a one-liner in every runtime and names
    ;; flint's own internals, so all of it is project-local and none of it is a
    ;; kin capability. `drive` itself is `kin/sched.kin`: its ORDER is the part
    ;; that had diverged, and an order can only be single-sourced if the things
    ;; it orders are reachable from the source.
    ;;
    ;; `report-deadlock` stays three implementations on purpose -- it builds a
    ;; host string naming each stuck thread, and a diagnostic message is the
    ;; wrong thing to force through a generator.
    'boot-system-thread-once (core/call {:rust "{0}.boot_system_thread_once()"
                                         :java "Conc.bootSystemThreadOnce({0})"
                                         :csharp "Conc.BootSystemThreadOnce({0})"})
    'reap-ports (core/call {:rust "{0}.reap_ports()"
                            :java "Conc.reapPorts({0})"
                            :csharp "Conc.ReapPorts({0})"})
    ;; --- what `reap-ports` itself is written in ---------------------------
    'port-by-id (core/call {:rust "{0}.port_by_id({1})"
                            :java "Conc.portById({0}, {1})"
                            :csharp "Conc.PortById({0}, {1})"})
    'push-event (core/call {:rust "{0}.push_event({1}, {2}, {3}, {4})"
                            :java "Conc.pushEvent({0}, {1}, {2}, {3}, {4})"
                            :csharp "Conc.PushEvent({0}, {1}, {2}, {3}, {4})"})
    'wake-on (core/call {:rust "{0}.wake_on({1})"
                         :java "Conc.wakeOn({0}, {1})"
                         :csharp "Conc.WakeOn({0}, {1})"})
    ;; THE OTHER END, while it still exists -- `peer-id-of-dead` below is the
    ;; same question asked after the collector has taken it.
    'peer-of (core/call {:rust "{0}.peer_of({1})"
                         :java "Conc.peerOf({0}, {1})"
                         :csharp "Conc.PeerOf({0}, {1})"})
    ;; DROP AN ID FROM THE HELD LIST, so the collector's sweep does not release
    ;; a bridge the program has already let go of. It rebuilds a vector, which
    ;; is the runtime's own bookkeeping rather than a decision.
    ;; PUT A PORT IN THE REGISTRY. Stays a word: it takes the interns lock and
    ;; looks up through a PREDICATE, and a closure is the one shape kin has no
    ;; answer for.
    'register-port (core/call {:rust "{0}.register_port({1})"
                               :java "Conc.registerPort({0}, {1})"
                               :csharp "Conc.RegisterPort({0}, {1})"})
    'forget-bridge (core/call {:rust "{0}.forget_bridge({1})"
                               :java "Conc.forgetBridge({0}, {1})"
                               :csharp "Conc.ForgetBridge({0}, {1})"})
    ;; The object is gone by the time we notice, so the pairing is recorded
    ;; separately and looked up by id.
    'peer-id-of-dead (core/call {:rust "{0}.peer_id_of_dead({1})"
                                 :java "Conc.peerIdOfDead({0}, {1})"
                                 :csharp "Conc.PeerIdOfDead({0}, {1})"})
    ;; THE MESSAGE IS THE VOCABULARY'S, not the source's, for the reason
    ;; `report-deadlock` gives: a host string is a diagnostic rather than a
    ;; decision, and kin has no string type to carry one. All three runtimes
    ;; already spelled it identically -- checked before it was moved here, so
    ;; this entry preserves the wording rather than choosing it.
    'fail-waiters-unreachable
    (core/call {:rust "{0}.fail_waiters_on({1}, \"the other end of this port is unreachable, so this can never complete\")"
                :java "Conc.failWaitersOn({0}, {1}, \"the other end of this port is unreachable, so this can never complete\")"
                :csharp "Conc.FailWaitersOn({0}, {1}, \"the other end of this port is unreachable, so this can never complete\")"})
    'run-one (core/call {:rust "crate::conc::run_one({0}, {1})"
                         :java "Conc.runOne({0}, {1})"
                         :csharp "Conc.RunOne({0}, {1})"})
    ;; `close-all-bridges` is still a WORD, and its body is now generated from
    ;; `kin/reapports.kin`: each target's `Conc.closeAllBridges` is a one-line
    ;; delegation, exactly as `reap-ports` delegates to `reap-all`. The word
    ;; stays because `sched-drive` calls it and the drivers' toy scheduler has
    ;; no port list to walk -- a stub there is the right model of it.
    'close-all-bridges (core/call {:rust "{0}.close_all_bridges()"
                                   :java "Conc.closeAllBridges({0})"
                                   :csharp "Conc.CloseAllBridges({0})"})
    ;;
    ;;
    ;; EVERYTHING THAT FOLLOWS FROM AN END CLOSING: tell the host if this was a
    ;; bridge, drop it from `SC_BRIDGES`, and wake both sides. It pushes events
    ;; and touches the bridge registry, which is the runtime's own bookkeeping
    ;; rather than a decision, so it stays one line per target.
    'close-side-effects (core/call {:rust "{0}.close_side_effects({1})"
                                    :java "Conc.closeSideEffects({0}, {1})"
                                    :csharp "Conc.CloseSideEffects({0}, {1})"})
    'set-status (core/call {:rust "{0}.status = ({1} as i32)"
                            :java "{0}.status = (int) {1}"
                            :csharp "{0}.status = (int) {1}"})
    ;; HAS THE GATE ESCAPED EVERY HANDLER? -- `gas_trips > 1`, which is a
    ;; question all three already answer the same way and none of them exposed.
    ;;
    ;; It is READ rather than stored because the counter is already there and
    ;; already snapshotted: a second flag would be a second thing to keep in
    ;; step, and `set_gas_limit` resetting the counter is exactly the "a host
    ;; may raise the budget and carry on" behaviour a flag would have had to
    ;; reimplement.
    'gate-escaped? (core/call {:rust "({0}.gas_trips > 1)"
                               :java "({0}.gasTrips > 1)"
                               :csharp "({0}.gasTrips > 1)"}
                              {:tag Bool})
    'settled-answer (core/call {:rust "crate::conc::settled_answer({0})"
                                :java "Conc.settledAnswer({0})"
                                :csharp "Conc.SettledAnswer({0})"}
                               {:tag Value})
    'report-deadlock (core/call {:rust "crate::conc::report_deadlock({0})"
                                 :java "Conc.reportDeadlock({0})"
                                 :csharp "Conc.ReportDeadlock({0})"})
    ;; --- THE RESUME PATH, which `run-one` orchestrates -------------------
    ;;
    ;; Each of these is one line in every target and names flint's own
    ;; internals, so all of it is project-local. What they have in common is
    ;; that they touch the INTERPRETER's own state -- its frame stack, its
    ;; handler stack, its value stack -- which is a host structure in all
    ;; three and is the reason the resume path stayed hand-written while the
    ;; scheduler's decisions moved into `sched.kin`.
    'install-bindings (core/call
                       {:rust "{0}.install_bindings({1})"
                        :java "Conc.installBindings({0}, {1})"
                        :csharp "Conc.InstallBindings({0}, {1})"})
    ;; THE READ SIDE of the same singleton. A spawned thread INHERITS the
    ;; bindings live at the moment of the spawn -- that is what makes a dynamic
    ;; binding dynamic across a `spawn` -- and `install-bindings` above is what
    ;; puts a thread's own back when it is scheduled.
    ;;
    ;; PASCALISED ON THE CLR, like `RingMessages`: `Rt.SingBindings` against
    ;; `Rt.SING_BINDINGS` on the other two. Checked in all three before this
    ;; entry was written.
    ;; A CALL AND NOT A FIELD PATH, deliberately. Spelling it
    ;; `roots.shared.singletons[SING_BINDINGS]` would make every fixture mirror
    ;; an internal layout to satisfy a word, and would pin that layout in three
    ;; targets at once. `install-bindings` beside it is a call for the same
    ;; reason; the pair should read the same way.
    ;; MAKE THE SCHEDULER IF THERE IS NOT ONE. Idempotent, and it ALLOCATES --
    ;; which is why it is a word rather than something a caller does first: a
    ;; caller that runs it before rooting its arguments leaves a host local
    ;; pointing into the abandoned semispace, and `spawn` carried a comment
    ;; saying exactly that. Made a word so the ORDER lives in the one source
    ;; instead of in three wrappers.
    ;; INSTALL THE SCHEDULER as the singleton the collector already traces.
    ;; A call and not a field path, for the reason `current-bindings` gives.
    'install-sched (core/call {:rust "{0}.install_sched({1})"
                               :java "Conc.installSched({0}, {1})"
                               :csharp "Conc.InstallSched({0}, {1})"})
    ;; IS A PROGRAM ACTUALLY RUNNING? An empty frame stack says the scheduler
    ;; is being built before anything has started -- a host that installs a
    ;; port at construction does that -- and thread 0 then represents no stack
    ;; at all. It decides whether thread 0 is RUNNABLE or already DONE.
    'something-running (core/call {:rust "{0}.something_running()"
                                   :java "Conc.somethingRunning({0})"
                                   :csharp "Conc.SomethingRunning({0})"})
    'ensure-sched (core/call {:rust "{0}.ensure_sched()"
                              :java "Conc.ensureSched({0})"
                              :csharp "Conc.EnsureSched({0})"})
    'current-bindings (core/call {:rust "{0}.current_bindings()"
                                  :java "Conc.currentBindings({0})"
                                  :csharp "Conc.CurrentBindings({0})"})
    ;; THE EMPTY MAP, which is a shared singleton and allocates nothing.
    'maps-empty (core/call {:rust "{0}.empty_map()"
                            :java "Maps.empty({0})"
                            :csharp "Maps.Empty({0})"})
    ;; The slice is what makes preemption happen at all: a thread runs until
    ;; `steps` reaches this, then yields. Set from the CURRENT step count, so
    ;; every thread gets the same size turn however long the last one ran.
    'begin-slice (core/call {:rust "{0}.begin_slice()"
                             :java "Conc.beginSlice({0})"
                             :csharp "Conc.BeginSlice({0})"})
    ;; Frame stack, handler stack and value stack, all emptied together: a NEW
    ;; thread starts on a clean interpreter or it inherits whatever the last
    ;; one left, which is a use-after-free with extra steps.
    'reset-exec-state (core/call {:rust "{0}.reset_exec_state()"
                                  :java "Conc.resetExecState({0})"
                                  :csharp "Conc.ResetExecState({0})"})
    'run-entry (core/call {:rust "crate::conc::run_entry({0}, {1})"
                           :java "Conc.runEntry({0}, {1})"
                           :csharp "Conc.RunEntry({0}, {1})"})
    'restore-state (core/call {:rust "{0}.restore_state({1})"
                               :java "Conc.restoreState({0}, {1})"
                               :csharp "Conc.RestoreState({0}, {1})"})
    'vm-run (core/call {:rust "{0}.run(0)" :java "{0}.run(0)" :csharp "{0}.Run(0)"})
    ;; A PARK IS TWO WRITES, and both are necessary. `park_on` says WHAT the
    ;; thread is waiting for, so the scheduler can decide whether anything can
    ;; wake it; `thrown = PARK` is what unwinds every frame between the
    ;; builtin and the top, since a park has to leave the interpreter the same
    ;; way a throw does.
    'set-park-on (core/call {:rust "{0}.park_on = {1}"
                             :java "{0}.parkOn = {1}"
                             :csharp "{0}.parkOn = {1}"})
    'set-thrown (core/call {:rust "{0}.thrown = {1}"
                            :java "{0}.thrown = {1}"
                            :csharp "{0}.thrown = {1}"})
    ;; THE OTHER HALF OF THOSE TWO WRITES, read back. `settle` is the one
    ;; place that has to ask what a thread parked ON before deciding what to
    ;; record, and `is-parked` is deliberately separate from `park-on`: the
    ;; test comes first and the read only happens on the branch that took it.
    'is-parked (core/call {:rust "!{0}.park_on.is_nil()"
                            :java "!Val.isNil({0}.parkOn)"
                            :csharp "!Val.IsNil({0}.parkOn)"}
                           {:tag Bool})
    'park-on (core/call {:rust "{0}.park_on"
                          :java "{0}.parkOn"
                          :csharp "{0}.parkOn"}
                         {:tag Value})
    'thrown (core/call {:rust "{0}.thrown"
                         :java "{0}.thrown"
                         :csharp "{0}.thrown"}
                        {:tag Value})
    ;; SAVE THE CONTINUATION. Allocates -- which is exactly why `park_on` is
    ;; cleared before this is reached: `park_on` is not a root on any runtime.
    'save-current-state (core/call {:rust "{0}.save_current_state({1})"
                                     :java "Conc.saveCurrentState({0}, {1})"
                                     :csharp "Conc.SaveCurrentState({0}, {1})"})
    ;; THE INTERPRETER, EMPTIED. Three writes everywhere, and they are only
    ;; ever done together: a thread that is finished has no frames, no
    ;; handlers, and nothing rooted on the value stack. Named as one act so
    ;; that a copy cannot forget the third.
    'cut-stacks (core/call {:rust "{ {0}.frames.clear(); {0}.handlers.clear(); {0}.roots.stack_top = 0; }"
                             :java "{ {0}.frames.clear(); {0}.handlers.clear(); {0}.roots.stackTop = 0; }"
                             :csharp "{ {0}.frames.Clear(); {0}.handlers.Clear(); {0}.roots.StackTop = 0; }"})
    ;; NAMED `unwind-to-handler` and not `unwind`: native spells the public
    ;; entry `unwind_from_resume`, which is a `pub` wrapper over the same
    ;; `unwind` both ports expose directly. One act, three spellings.
    'unwind-to-handler (core/call {:rust "{0}.unwind_from_resume()"
                                   :java "{0}.unwind()"
                                   :csharp "{0}.Unwind()"})
    ;; THE THREAD THAT IS RUNNING, or nil when there is no scheduler. Every
    ;; runtime already had it; naming it is what asserts they agree.
    'current-thread (core/call {:rust "{0}.current_thread()"
                                :java "Conc.currentThread({0})"
                                :csharp "Conc.CurrentThread({0})"})
    'settle (core/call {:rust "crate::conc::settle({0}, {1})"
                        :java "Conc.settle({0}, {1})"
                        :csharp "Conc.Settle({0}, {1})"})
    ;; --- THE PORT RING, and the words that make it atomic ----------------
    ;;
    ;; A cursor and a sequence word are fixnums like any other slot -- the
    ;; collector sees nothing unusual -- and the atomic operates on the TAGGED
    ;; word, so a compare-and-swap compares tagged bits. All three already
    ;; spelled these identically; listing them is what asserts that.
    'slot-atomic (core/call {:rust "{0}.slot_atomic({1}, {2})"
                             :java "Conc.slotAtomic({0}, {1}, {2})"
                             :csharp "Conc.SlotAtomic({0}, {1}, {2})"})
    'cas-slot (core/call {:rust "{0}.cas_slot({1}, {2}, {3}, {4})"
                          :java "Conc.casSlot({0}, {1}, {2}, {3}, {4})"
                          :csharp "Conc.CasSlot({0}, {1}, {2}, {3}, {4})"})
    ;; BARRIERED, and it stays hand-written on every target: it reaches the
    ;; collector's remembered set, which is on the never-generate list. One
    ;; vocabulary entry, three implementations, and the ring above it is one.
    'cas-slot-barriered (core/call {:rust "{0}.cas_slot_barriered({1}, {2}, {3}, {4})"
                                    :java "Conc.casSlotBarriered({0}, {1}, {2}, {3}, {4})"
                                    :csharp "Conc.CasSlotBarriered({0}, {1}, {2}, {3}, {4})"})
    ;; A HINT AND NOTHING ELSE. It tells the processor this is a spin so it
    ;; can back off; removing it changes no answer. Native had one and the two
    ;; ports did not, which is the kind of difference that is invisible until
    ;; somebody asks why one runtime is slower under contention.
    ;; `:arity 1` BECAUSE IT TAKES THE RECEIVER AND DOES NOT SPELL IT. This
    ;; is the one word in the table whose text names no argument at all, and
    ;; it is still written `(spin-hint rt)` -- every other word is
    ;; receiver-first and an exception is a trap rather than a saving. Saying
    ;; so here is what lets the arity check hold with no special cases: read
    ;; off the template alone this would demand `(spin-hint)`.
    'spin-hint (core/call {:rust "core::hint::spin_loop()"
                           :java "java.lang.Thread.onSpinWait()"
                           :csharp "System.Threading.Thread.SpinWait(1)"}
                          {:arity 1})


    ;; THE SCHEDULER OBJECT, and the two predicates over it that every
    ;; runtime wrote for itself. `kin/sched.kin` is the one source now.
    'sched (core/call {:rust "{0}.sched()"
                       :java "Conc.sched({0})"
                       :csharp "Conc.Sched({0})"}
                      {:tag Value})
    'is-port (core/call {:rust "{0}.is_port({1})"
                         :java "Conc.isPort({0}, {1})"
                         :csharp "Conc.IsPort({0}, {1})"})
    'is-thread (core/call {:rust "{0}.is_thread({1})"
                           :java "Conc.isThread({0}, {1})"
                           :csharp "Conc.IsThread({0}, {1})"}
                          {:tag Bool})
    ;; A PORT KIND that crosses a heap -- a bridge, never a channel.
    ;;
    ;; IT IS ANSWERED FROM THE KIND, not from the port, so the runtime has
    ;; nothing to do here -- but `rt` IS STILL PASSED, as the ignored `{0}`,
    ;; because every word in this table is called receiver-first and one
    ;; exception is a trap rather than a saving. Written `(crosses-a-heap rt
    ;; kind)`.
    ;;
    ;; Calling it `(crosses-a-heap kind)` does not fail. The template is
    ;; POSITIONAL and unchecked, so the kind lands in `{0}`, nothing fills
    ;; `{1}`, and the generated line carries the literal text `{1}` into a
    ;; Rust file -- a compile error two steps later, blaming the generated
    ;; code rather than the call. That happened on 2026-09-20 in
    ;; `kin/portrecv.kin`.
    'crosses-a-heap (core/call {:rust "crate::conc::crosses_a_heap({1})"
                                :java "Conc.crossesAHeap({1})"
                                :csharp "Conc.CrossesAHeap({1})"})

    ;; INSTALL A BRIDGE PORT BY HOST ID, answering NIL when this sandbox has
    ;; no ports to install one into. The one runtime-specific step in the walk
    ;; that `kin/wirescan.kin` does over an arriving message.
    'mint-bridge-port (core/call {:rust "{0}.mint_bridge_port({1})"
                                  :java "Conc.mintBridgePort({0}, {1})"
                                  :csharp "Conc.MintBridgePort({0}, {1})"}
                                 {:tag Value})

    'new-obj (core/call {:rust "{0}.new_obj({1}, {2})"
                         :java "Conc.newObj({0}, {1}, {2})"
                         :csharp "Conc.NewObj({0}, {1}, {2})"}
                        {:tag Value})
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

    ;; THE SAME THING FOR AN `I64`, AND THE MASK WOULD RUIN IT.
    ;;
    ;; `fixnum` above zero-extends because its argument is an `I32` -- kin's
    ;; unsigned 32-bit -- and Java and C# spell that `int`, which is signed. A
    ;; SIGNED 64-bit value is the opposite case: masking it to 32 bits turns
    ;; `-2` into `4294967294` and any value past `2^32` into its low half.
    ;;
    ;; The first source to pass a wide negative was `kin/wire.kin`, whose frame
    ;; stack uses a NEGATIVE entry to mean "a row count is due here". Rust kept
    ;; the sign and both ports lost it, so the marker existed on one target and
    ;; not on the other two: a well-formed table was refused and a VALUE was
    ;; accepted where a count was due -- the safety property the frames exist
    ;; for, gone on two runtimes out of three. `runtimes/conform/wire.cljc`
    ;; caught it on the first run after the port.
    'fixnum64 (core/call {:rust "Value::fixnum({0})"
                          :java "Val.fixnum({0})"
                          :csharp "Val.Fixnum({0})"})

    ;; --- ROOTING --------------------------------------------------------
    ;;
    ;; The shadow stack (`DECISIONS.md#a-vec-of-values-is-not-a-root`): a value in a host local does
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
