(ns flint.impl.codec
  "The vocabulary the wire codec is written in (`doc/goals/splint-port.md`).

  Separate from `flint.impl.vm` because a source should ask for what it needs
  and no more: the codec has no value stack and no frames, and a file that
  cannot name `vpush` cannot reach for it by accident.

  Everything here is naming. The three codecs already agree on structure -- what
  differs is `out.write` against `out.WriteByte((byte) x)`, `>>>` against
  `((ulong) n >> 32)`, `getBytes(UTF_8)` against `Encoding.UTF8.GetBytes`. That
  is a vocabulary, which is the whole bet.

  The SHAPE of a program -- `defn`, `let`, `if`, `return` -- is not here. It
  moved to `flint.impl.core` the moment a second source needed it, and is
  merged in below: a vocabulary says what its subject is called, not what a
  function declaration looks like."
  (:require [flint.splint :as sp]
            [flint.impl.core :as core]
            [clojure.string :as str]))

(def I32 {:name 'I32 :types {:rust "u32" :java "int" :csharp "int"} :methods {}})
(def I64 {:name 'I64 :types {:rust "u64" :java "long" :csharp "long"} :methods {}})
(def Text {:name 'Text :types {:rust "&str" :java "String" :csharp "string"} :methods {}})
(def Bytes {:name 'Bytes :types {:rust "Vec<u8>" :java "byte[]" :csharp "byte[]"} :methods {}})

(def Sink
  "The byte sink. Three types for one idea, which is why it is a tag."
  {:name 'Sink
   :types {:rust "&mut Vec<u8>" :java "ByteArrayOutputStream" :csharp "MemoryStream"}
   :methods {}})

(def MaybeText
  "A string that may be ABSENT, which is not the same as empty -- that is what
  distinguishes `:kw` from `:/kw`.

  Rust says so in the type and the other two use null, which is the second
  divergence in this port that is not naming. It rides the TAG rather than a
  mark on the function, because absence is a property of the value."
  {:name 'MaybeText
   :types {:rust "Option<String>" :java "String" :csharp "string"}
   :methods {}})

(def Reader
  {:name 'Reader
   :types {:rust "&mut Reader" :java "Reader" :csharp "Reader"}
   :methods {}})

(def tags-for {'I32 I32 'I64 I64 'Text Text 'Bytes Bytes 'Sink Sink 'Reader Reader 'MaybeText MaybeText})

(def call core/call)

(defn forms-for []
  (merge
   ;; The shape of a program. An untagged name is an `I64` here, which is the
   ;; codec's default and nobody else's -- hence the argument.
   (core/forms-for {:default-tag I64})
   {;; THE BYTE SINK. One byte, and the cast the CLR needs lives here rather
    ;; than in every call.
    'write-byte (call {:rust "{0}.push({1} as u8)"
                       :java "{0}.write({1})"
                       :csharp "{0}.WriteByte((byte) {1})"})
    'write-bytes (call {:rust "{0}.extend_from_slice(&{1})"
                        :java "{0}.write({1}, 0, {1}.length)"
                        :csharp "{0}.Write({1}, 0, {1}.Length)"})
    'byte-count (call {:rust "{0}.len() as u32" :java "{0}.length" :csharp "{0}.Length"})
    ;; An UNSIGNED shift. `>>>` in Java, a cast in C#, and plain `>>` in Rust
    ;; where the type already says unsigned -- three spellings of one idea.
    'ushr (call {:rust "({0} >> {1})" :java "({0} >>> {1})" :csharp "((ulong) {0} >> {1})"})
    'to-i32 (call {:rust "({0} as u32)" :java "((int) {0})" :csharp "((int) {0})"})
    'utf8 (call {:rust "{0}.as_bytes().to_vec()"
                 :java "{0}.getBytes(StandardCharsets.UTF_8)"
                 :csharp "Encoding.UTF8.GetBytes({0})"})
    'u32 (call {:rust "u32({0}, {1})" :java "u32({0}, {1})" :csharp "U32({0}, {1})"})
    ;; THE READER SIDE.
    ;;
    ;; A byte out of a slice, unsigned. Java and C# have signed bytes and need
    ;; the mask; Rust's `u8` does not, which is one idea and three spellings --
    ;; exactly what a vocabulary is for.
    'byte-at (call {:rust "({0}[{1} as usize] as u32)"
                    :java "({0}[{1}] & 0xff)"
                    :csharp "({0}[{1}] & 0xff)"})
    'len (call {:rust "({0}.len() as u32)" :java "{0}.length" :csharp "{0}.Length"})
    'shl (call {:rust "({0} << {1})" :java "({0} << {1})" :csharp "({0} << {1})"})
    'bit-or (call {:rust "({0} | {1})" :java "({0} | {1})" :csharp "({0} | {1})"})
    'to-i64 (call {:rust "({0} as u64)" :java "((long) {0} & 0xffffffffL)"
                   :csharp "((long) {0} & 0xffffffffL)"})
    'refuse (call {:rust "return Err(String::from({0}))"
                   :java "throw new Refused({0})"
                   :csharp "throw new Refused({0})"})
    ;; Calling a function that CAN FAIL. Rust propagates with `?`; the other
    ;; two do nothing, because an exception needs nothing at the call site.
    ;; Same shape as `^:throws` on the declaration -- the mark is in the source
    ;; and only the target that cares reads it.
    'try-u32 (call {:rust "u32({0})?" :java "u32({0})" :csharp "U32({0})"})
    'mask32 (call {:rust "({0} as u64)"
                   :java "((long) {0} & 0xffffffffL)"
                   :csharp "((long) {0} & 0xffffffffL)"})
    'shl64 (call {:rust "({0} << {1})" :java "({0} << {1})" :csharp "({0} << {1})"})
    ;; A CONSTANT CAN DIFFER PER TARGET when the types do. The absent-namespace
    ;; marker is the same 32 bits everywhere and is spelled `u32::MAX` in Rust
    ;; and `-1` in Java and C#, whose ints are signed -- writing `4294967295` in
    ;; the source produced `integer number too large` on two of the three.
    'no-ns (call {:rust "u32::MAX" :java "NO_NS" :csharp "NO_NS"})

    ;; ABSENCE. Rust has a type for it and the other two have null.
    'absent (call {:rust "None" :java "null" :csharp "null"})
    'present (call {:rust "Some({0})" :java "{0}" :csharp "{0}"})
    'utf8-str (call {:rust "String::from_utf8_lossy(&{0}[{1} as usize..({1} + {2}) as usize]).into_owned()"
                     :java "new String({0}, {1}, {2}, StandardCharsets.UTF_8)"
                     :csharp "Encoding.UTF8.GetString({0}, {1}, {2})"})}))
