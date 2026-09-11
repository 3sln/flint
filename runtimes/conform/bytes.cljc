(ns bytes
  "BYTE STRINGS (`DECISIONS.md#no-runtime-linking`), which had no conformance program at
  all until this one.

  That is the gap the `subs` gas divergence came through, in a different
  place: `runtime/src/bytes.rs` is the largest hand-written surface left --
  27 functions against 6 in `strs.rs` and 4 each in `vector.rs` and
  `pike.rs` -- and nothing here ran a single one of them. The other twelve
  programs cover collections, maps, numbers, regex, strings and tables; a
  byte string was reachable from flint and checked on one runtime only.

  TWO TIERS, as `no-runtime-linking` requires, so the interesting cases are the ones that
  cross the boundary: a concatenation deep enough to be a tree, a slice that
  starts inside one chunk and ends inside another, and equality between the
  same bytes arranged two different ways.

  `FLAT_MAX` IS 1024 BYTES, which is the number this file has to beat and the
  first draft did not: forty short pieces joined to seventy bytes stayed flat,
  so every read below took the same path twice and the tree tier was still
  untested. `deep` builds past the threshold on purpose.")

(defn- b-of [& xs] (flint.rt/vec->b (vec xs)))

;; A TREE, not a flat run: `no-runtime-linking`'s two tiers mean a long concatenation stops
;; being a copy, and every read below has to work on both shapes.
(defn- chunk-text [i] (apply str (repeat 40 (str (mod i 10)))))
(defn- deep [n]
  (reduce (fn [acc i] (flint.rt/b-concat acc (flint.rt/str->b (chunk-text i))))
          (flint.rt/str->b "")
          (range n)))

(defn main [_]
  (let [flat (flint.rt/str->b "hello")
        tree (deep 40)
        same (flint.rt/str->b (apply str (map chunk-text (range 40))))
        utf8 (flint.rt/str->b "aéz")]
    (pr-str
     {:count [(flint.rt/b-count flat) (flint.rt/b-count tree)
              (flint.rt/b-count (flint.rt/str->b ""))]
      ;; BYTES AND NOT CHARACTERS: `é` is two of them, which is the whole
      ;; reason a byte string is not a string.
      :utf8 [(flint.rt/b-count utf8) (flint.rt/b-at utf8 1) (flint.rt/b-at utf8 2)]
      :at [(flint.rt/b-at flat 0) (flint.rt/b-at flat 4)
           (flint.rt/b-at tree 0) (flint.rt/b-at tree 1599)]
      ;; OUT OF RANGE on both tiers, because a tree answers it by descent and
      ;; a flat run by comparison, and those are two chances to be wrong.
      :at-edge [(try (flint.rt/b-at flat 5) (catch Exception e :threw))
                (try (flint.rt/b-at flat -1) (catch Exception e :threw))
                (try (flint.rt/b-at tree 100000) (catch Exception e :threw))]
      :roundtrip [(flint.rt/b->str flat) (flint.rt/b->str utf8)
                  (= (flint.rt/b->str tree) (apply str (map chunk-text (range 40))))]
      :vec [(flint.rt/b->vec flat) (flint.rt/b-count (flint.rt/vec->b [104 105]))
            (flint.rt/b->str (flint.rt/vec->b [104 105]))]
      ;; A SLICE THAT CROSSES A CHUNK, which is the read a tree makes hardest.
      :slice [(flint.rt/b->str (flint.rt/b-slice flat 1 3))
              (flint.rt/b->str (flint.rt/b-slice flat 0 0))
              (flint.rt/b->str (flint.rt/b-slice tree 5 25))
              (flint.rt/b-count (flint.rt/b-slice tree 0 (flint.rt/b-count tree)))]
      ;; THE SAME BYTES ARRANGED TWO WAYS. A tree and a flat run that spell
      ;; the same thing must be equal and must hash alike, or a byte string
      ;; cannot be a map key.
      :canonical [(= tree same) (= (hash tree) (hash same))
                  (= flat (flint.rt/str->b "hello"))
                  (= flat (flint.rt/str->b "hellp"))]
      :depth [(flint.rt/b-depth flat) (> (flint.rt/b-depth tree) 0)]
      :bytes? [(flint.rt/bytes? flat) (flint.rt/bytes? tree)
               (flint.rt/bytes? "hello") (flint.rt/bytes? nil)
               (flint.rt/bytes? [1 2])]
      ;; TRANSIENTS, which are a third path into the same bytes.
      :transient (let [t (flint.rt/b-transient (flint.rt/str->b "ab"))
                       t (flint.rt/b-conj! t 99)
                       t (flint.rt/b-append! t (flint.rt/str->b "de"))]
                   [(flint.rt/b-tcount t)
                    (flint.rt/b->str (flint.rt/b-persistent! t))])
      :transient-empty (let [t (flint.rt/b-transient (flint.rt/str->b ""))]
                         [(flint.rt/b-tcount t)
                          (flint.rt/b-count (flint.rt/b-persistent! t))])
      ;; BYTES THAT ARE NOT TEXT, which is where three hosts are most likely
      ;; to part company: Rust refuses invalid UTF-8, the JVM substitutes a
      ;; replacement character, and the CLR does something of its own. A byte
      ;; string is arbitrary bytes by definition, so `b->str` on a lone
      ;; continuation byte has to mean ONE thing across all four.
      :not-text [(try (flint.rt/b->str (b-of 0xFF)) (catch Exception e :threw))
                 (try (flint.rt/b->str (b-of 0x80)) (catch Exception e :threw))
                 (try (flint.rt/b->str (b-of 0xC3)) (catch Exception e :threw))
                 (try (flint.rt/b->str (b-of 0xC3 0xA9)) (catch Exception e :threw))]
      ;; THE WAYS UTF-8 IS INVALID THAT A DECODER CAN GET SUBTLY RIGHT. Every
      ;; one of these decodes to something under a lenient reading and to
      ;; nothing under a correct one, and they are the cases where three host
      ;; decoders are likeliest to part company even after all three are set
      ;; to refuse:
      ;;   an OVERLONG `/` -- two bytes spelling a code point that fits in one
      ;;   a SURROGATE half, which is UTF-16's business and not UTF-8's
      ;;   a FIVE-BYTE sequence, which UTF-8 has not had since 2003
      ;;   a sequence TRUNCATED by the end of the string
      ;;   a continuation byte too FEW, then one too MANY
      :utf8-edge [(try (flint.rt/b->str (b-of 0xC0 0xAF)) (catch Exception e :threw))
                  (try (flint.rt/b->str (b-of 0xED 0xA0 0x80)) (catch Exception e :threw))
                  (try (flint.rt/b->str (b-of 0xF8 0x88 0x80 0x80 0x80)) (catch Exception e :threw))
                  (try (flint.rt/b->str (b-of 0x61 0xE2 0x82)) (catch Exception e :threw))
                  (try (flint.rt/b->str (b-of 0xE2 0x82)) (catch Exception e :threw))
                  (try (flint.rt/b->str (b-of 0xE2 0x82 0xAC 0xAC)) (catch Exception e :threw))
                  ;; ...and the largest thing that IS valid, so this row is not
                  ;; simply "everything throws".
                  (try (flint.rt/b->str (b-of 0xF4 0x8F 0xBF 0xBF)) (catch Exception e :threw))]
      ;; A BYTE IS EIGHT BITS, and `vec->b` is the door values arrive through.
      :range [(try (flint.rt/b->vec (flint.rt/vec->b [0 255])) (catch Exception e :threw))
              (try (flint.rt/b->vec (flint.rt/vec->b [256])) (catch Exception e :threw))
              (try (flint.rt/b->vec (flint.rt/vec->b [-1])) (catch Exception e :threw))]
      ;; A SLICE THAT ASKS FOR NOTHING SENSIBLE.
      :slice-edge [(try (flint.rt/b-count (flint.rt/b-slice flat 3 1)) (catch Exception e :threw))
                   (try (flint.rt/b-count (flint.rt/b-slice flat 0 99)) (catch Exception e :threw))
                   (try (flint.rt/b-count (flint.rt/b-slice tree -1 3)) (catch Exception e :threw))]})))
