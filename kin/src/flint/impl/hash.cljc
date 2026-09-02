(ns flint.impl.hash
  "The vocabulary murmur3 is written in (`doc/goals/kin-port.md`).

  A better test of the bet than the codec was. The three hash files were
  written to agree DELIBERATELY -- `Hash.java` says so in its header, that
  using `String.hashCode()` would leave the two ports computing the same
  number by different routes, and so `the arithmetic is written out on both`.
  Three files kept in step by hand, with a comment explaining that they must
  be. That is precisely the cost this exists to remove.

  What differs between them is entirely spelling, and all of it is here:

      wrapping        `k1.wrapping_mul(C1)` / `k1 * C1` / `unchecked(k1 * C1)`
      unsigned shift  `h >> 16` / `h >>> 16` / `(int)((uint) h >> 16)`
      signed shift    `((seed as i32) >> 2) as u32` / `seed >> 2` / same
      a big constant  `0xe6546b64` / `0xe6546b64` / `unchecked((int) ...)`

  Rust carries `u32` where the other two carry a signed `int`, which is why
  every shift and every constant needs a word from one of them and nothing
  from the others. The numbers are identical; only the types disagree."
  (:require [kin :as sp]
            [kin.lang :as core]
            [clojure.string :as str]))

(def U32
  "A 32-bit hash. UNSIGNED in Rust and signed in the other two, which is the
  one type disagreement the whole file is arranged around. The bits are the
  same either way -- `hash` is observable from Clojure code and Clojure's is
  a signed `int`, so the answer is read as signed at the boundary and nowhere
  before it."
  {:name 'U32 :types {:rust "u32" :java "int" :csharp "int"} :methods {}})

(def I64 {:name 'I64 :types {:rust "i64" :java "long" :csharp "long"} :methods {}})

(def tags-for {'U32 U32 'I64 I64})

(defn- t [ctx] (:target ctx))

(defn- hex
  "A literal, spelled for the target's type.

  `0xe6546b64` does not fit a signed 32-bit int, so C# needs the cast and the
  `unchecked` -- and writing the number in the source rather than here produced
  `integer number too large` on two of the three. The FORM gets the number
  itself rather than a rendered argument, which is the only way it can know how
  big it is."
  [ctx n]
  (case (t ctx)
    :rust (format "0x%x" n)
    :java (format "0x%x" n)
    :csharp (if (> n 0x7fffffff) (format "unchecked((int) 0x%x)" n) (format "0x%x" n))))

(defn- hex-form [ctx form] (sp/kin-emit! ctx (hex ctx (second form))))

(defn forms-for []
  (merge
   ;; `mul32` and `add32` have a compound spelling on the JVM and the CLR and
   ;; none in Rust, where they are `wrapping_mul` and `wrapping_add`. Saying so
   ;; keeps `h1 *= 0x85ebca6b` in the Java exactly as it was hand-written.
   (core/forms-for {:default-tag U32
                    :compound {'mul32 {:java "*=" :csharp "*="}
                               'add32 {:java "+=" :csharp "+="}}})
   {'hex hex-form

    ;; WRAPPING ARITHMETIC. Murmur3 relies on it. Rust's `*` panics on
    ;; overflow in a debug build and so has to say `wrapping_mul`; Java's
    ;; `int` wraps and cannot be told not to; C#'s default is unchecked but
    ;; says so anyway, because the arithmetic elsewhere in that runtime is
    ;; deliberately checked and a reader should not have to know the default.
    'mul32 (core/call {:rust "{0}.wrapping_mul({1})"
                       :java "({0} * {1})"
                       :csharp "({0} * {1})"})
    'add32 (core/call {:rust "{0}.wrapping_add({1})"
                       :java "({0} + {1})"
                       :csharp "({0} + {1})"})

    ;; THE TWO SHIFTS. Which one a line wants is invisible in Java and C#,
    ;; where the TYPE is signed and the OPERATOR chooses -- and visible in
    ;; Rust, where the type chooses and the operator is always `>>`. Naming
    ;; both makes the intent survive the trip in either direction.
    ;; Remainder. One spelling everywhere, and needed by anything that walks
    ;; a structure in strides.
    'rem (core/call {:rust "({0} % {1})" :java "({0} % {1})" :csharp "({0} % {1})"})
    'shl (core/call {:rust "({0} << {1})" :java "({0} << {1})" :csharp "({0} << {1})"})
    'ushr (core/call {:rust "({0} >> {1})"
                      :java "({0} >>> {1})"
                      :csharp "((int)((uint) {0} >> {1}))"})
    'sar (core/call {:rust "(({0} as i32) >> {1}) as u32"
                     :java "({0} >> {1})"
                     :csharp "({0} >> {1})"})

    ;; A ROTATE. All three have an intrinsic for it and the hand-written code
    ;; only used Rust's -- `Hash.java` and `Hash.cs` spell it out as a shift
    ;; pair. Naming it here gets `rotl` on the Rust exactly as it stands today
    ;; and upgrades the other two, which is the not-worse rule paying rather
    ;; than costing for once.
    'rotl (core/call {:rust "{0}.rotate_left({1})"
                      :java "Integer.rotateLeft({0}, {1})"
                      :csharp "((int) BitOperations.RotateLeft((uint) {0}, {1}))"})

    ;; THE HALVES OF A 64-BIT VALUE, as `hash_long` reads them.
    'low32 (core/call {:rust "({0} as u32)" :java "((int) {0})" :csharp "((int) {0})"})
    'high32 (core/call {:rust "((({0} as u64) >> 32) as u32)"
                        :java "((int) ({0} >>> 32))"
                        :csharp "((int)((ulong) {0} >> 32))"})}))
