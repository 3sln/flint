(ns flint.jar
  "A ZIP writer in flint, for the `:to :jvm` target.

  A JAR is a ZIP and nothing else, and a ZIP entry may be **STORED** -- the
  bytes verbatim, no compression. That is the whole reason this is fifty lines
  and not a DEFLATE implementation: nothing here compresses anything, and
  `java -jar` does not care.

  ## Why it APPENDS rather than builds

  The interpreter arrives as a prebuilt jar, the way the wasm runtime module
  arrives as a prebuilt `.wasm` (`DECISIONS.md#no-runtime-linking`): it was
  compiled once, when flint was built, by a `javac` that is not here. Its entries
  may be DEFLATEd, and reading them would mean an inflater.

  It does not need one. A ZIP's central directory sits at the END and its records
  carry absolute offsets into the file, so appending means: keep every local
  record byte for byte, write the new local records after them, copy the old
  central directory VERBATIM -- every offset in it is still right, because
  nothing moved -- then the new central directory records, then a new end
  record. The prebuilt jar is an opaque prefix.

  `flint.zip` does not exist and this is not a general reader: it finds the end
  record and nothing else."
  (:require [flint.rt]))

;; ---------------------------------------------------------------- bytes out

(defn u16 [n]
  (let [n (bit-and (long n) 0xFFFF)]
    [(bit-and n 0xff) (bit-and (bit-shift-right n 8) 0xff)]))

(defn u32 [n]
  (let [n (bit-and (long n) 0xFFFFFFFF)]
    [(bit-and n 0xff) (bit-and (bit-shift-right n 8) 0xff)
     (bit-and (bit-shift-right n 16) 0xff) (bit-and (bit-shift-right n 24) 0xff)]))

(defn bytes-of
  "A tree of byte values and byte strings, flattened to one byte string.

  A byte STRING is spliced whole rather than walked, which is why a megabyte of
  prebuilt classes costs a copy here instead of a million conses."
  [x]
  (let [parts (volatile! [])
        acc (volatile! [])
        flush! (fn [] (when (seq @acc)
                        (vswap! parts conj (flint.rt/vec->b @acc))
                        (vreset! acc [])))]
    ((fn walk [v]
       (cond
         (nil? v) nil
         (flint.rt/bytes? v) (do (flush!) (vswap! parts conj v))
         (number? v) (vswap! acc conj (bit-and (long v) 0xff))
         (sequential? v) (doseq [e v] (walk e))
         :else (throw (ex-info "not byte-able" {:v v}))))
     x)
    (flush!)
    (reduce flint.rt/b-concat (flint.rt/str->b "") @parts)))

;; ---------------------------------------------------------------- CRC-32
;;
;; The one piece of arithmetic a STORED entry still needs: the local header and
;; the central directory each carry a CRC-32 of the data, and `java -jar` checks
;; it. Reflected, polynomial 0xEDB88320, exactly `java.util.zip.CRC32`.

(def ^:private crc-table
  (mapv (fn [n]
          (loop [c (long n) k 0]
            (if (>= k 8)
              c
              (recur (if (= 1 (bit-and c 1))
                       (bit-xor 0xEDB88320 (unsigned-bit-shift-right c 1))
                       (unsigned-bit-shift-right c 1))
                     (inc k)))))
        (range 256)))

(defn crc32 [bs]
  (let [n (flint.rt/b-count bs)]
    (bit-and 0xFFFFFFFF
             (bit-xor 0xFFFFFFFF
                      (loop [i 0 c 0xFFFFFFFF]
                        (if (>= i n)
                          c
                          (recur (inc i)
                                 (bit-xor (nth crc-table
                                               (bit-and (bit-xor c (flint.rt/b-at bs i)) 0xff))
                                          (unsigned-bit-shift-right c 8)))))))))

;; ---------------------------------------------------------------- records
;;
;; MS-DOS time and date, which a ZIP carries and a JAR does not use. Fixed at
;; 1980-01-01 00:00 rather than taken from a clock, so the same program and the
;; same runtime produce the same jar byte for byte -- a build that differs only
;; in its timestamps cannot be compared, and comparing artifacts is how this
;; project checks anything.

(def ^:private DOS-TIME 0)
(def ^:private DOS-DATE 0x21)

(defn- local
  "The local file record: header, name, then the data."
  [e]
  [(u32 0x04034b50) (u16 10) (u16 0) (u16 0) (u16 DOS-TIME) (u16 DOS-DATE)
   (u32 (:crc e)) (u32 (:size e)) (u32 (:size e))
   (u16 (flint.rt/b-count (:name-bytes e))) (u16 0)
   (:name-bytes e) (:bytes e)])

(defn- central
  "The central-directory record, which is where the OFFSET of the local one
  lives. Nothing in the local record says where it is."
  [e]
  [(u32 0x02014b50) (u16 20) (u16 10) (u16 0) (u16 0) (u16 DOS-TIME) (u16 DOS-DATE)
   (u32 (:crc e)) (u32 (:size e)) (u32 (:size e))
   (u16 (flint.rt/b-count (:name-bytes e))) (u16 0) (u16 0)
   (u16 0) (u16 0) (u32 0) (u32 (:at e))
   (:name-bytes e)])

(defn- eocd [n cd-size cd-at]
  [(u32 0x06054b50) (u16 0) (u16 0) (u16 n) (u16 n) (u32 cd-size) (u32 cd-at) (u16 0)])

(defn- prepare
  "Names to byte strings, sizes and CRCs, and each entry's local-record offset.

  `at` starts wherever the caller says, which for an append is the old central
  directory's offset: that is exactly where the last local record ended."
  [entries at]
  (loop [es entries at at out []]
    (if (empty? es)
      {:entries out :end at}
      (let [e (first es)
            nb (flint.rt/str->b (:name e))
            bs (:bytes e)
            rec {:name-bytes nb :bytes bs :size (flint.rt/b-count bs)
                 :crc (crc32 bs) :at at}]
        (recur (rest es) (+ at 30 (flint.rt/b-count nb) (:size rec)) (conj out rec))))))

;; ---------------------------------------------------------------- the end record

(def ^:private EOCD-LEN 22)

(defn- find-eocd
  "The end-of-central-directory record's offset, scanning back from the end.

  BACKWARDS, and only as far as a comment can reach. A ZIP's comment is up to
  65535 bytes and may contain the signature, so the LAST match is the real one --
  scanning forwards finds a comment's copy of it and reads sixteen bytes of
  somebody's text as offsets."
  [bs]
  (let [n (flint.rt/b-count bs)
        lowest (max 0 (- n EOCD-LEN 65535))]
    (loop [i (- n EOCD-LEN)]
      (cond
        (< i lowest) nil
        (and (= 0x50 (flint.rt/b-at bs i))
             (= 0x4b (flint.rt/b-at bs (+ i 1)))
             (= 0x05 (flint.rt/b-at bs (+ i 2)))
             (= 0x06 (flint.rt/b-at bs (+ i 3)))) i
        :else (recur (dec i))))))

(defn- rd16 [bs at] (+ (flint.rt/b-at bs at) (bit-shift-left (flint.rt/b-at bs (+ at 1)) 8)))
(defn- rd32 [bs at] (+ (rd16 bs at) (bit-shift-left (rd16 bs (+ at 2)) 16)))

(defn read-dir
  "What the end record says: how many entries, and where the central directory
  is. Nothing else about the zip is read."
  [bs]
  (let [at (find-eocd bs)]
    (when-not at (throw (ex-info "not a zip: no end-of-central-directory record"
                                 {:bytes (flint.rt/b-count bs)})))
    {:count (rd16 bs (+ at 10)) :cd-size (rd32 bs (+ at 12)) :cd-at (rd32 bs (+ at 16))}))

;; ---------------------------------------------------------------- the two doors

(defn write
  "A fresh jar from `entries`, each `{:name \"a/b.class\" :bytes <byte string>}`."
  [entries]
  (let [p (prepare entries 0)
        recs (:entries p)
        cd (bytes-of (mapv central recs))]
    (bytes-of [(mapv local recs) cd
               (eocd (count recs) (flint.rt/b-count cd) (:end p))])))

(defn append
  "`entries` added to the jar in `base`, whose own entries are copied verbatim.

  The prefix is `base` up to its central directory -- every local record, byte
  for byte, including whatever compression they used. This never inflates
  anything and never needs to."
  [base entries]
  (let [dir (read-dir base)
        cd-at (:cd-at dir)
        cd-size (:cd-size dir)
        prefix (flint.rt/b-slice base 0 cd-at)
        old-cd (flint.rt/b-slice base cd-at (+ cd-at cd-size))
        p (prepare entries cd-at)
        new-locals (bytes-of (mapv local (:entries p)))
        new-cd (bytes-of (mapv central (:entries p)))
        total (+ (:count dir) (count entries))]
    (bytes-of [prefix new-locals old-cd new-cd
               (eocd total (+ cd-size (flint.rt/b-count new-cd)) (:end p))])))
