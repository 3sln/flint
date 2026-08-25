(ns flint.modmeta
  "What a module says about itself (`doc/decisions/0020`, part 1).

  A runner handed a pre-built `.wasm` needs two different things from it, and
  0020 is emphatic that conflating them is the trap:

  **Compatibility keys** decide whether the module can be loaded at all -- the
  ABI, the memory model, whether gas is compiled into AOT'd code. These are
  reduced to one hash, and a mismatch is a refusal.

  **Capability descriptors** are how a runner wires itself up -- what the module
  exports, which host imports it needs, whether diagnostics or snapshots are
  present. These must NOT gate compatibility.

  The trap is concrete: `0016` ships two builds that differ only in diagnostics.
  If the compatibility check were equality over one flat config blob, turning
  diagnostics on would invalidate every shard for no reason. So the key hashes
  the ABI-affecting subset ONLY, and everything else rides beside it
  descriptively.

  A hash rather than a version number because a version number is bumped by hand
  and a layout change forgets. Both are carried: the version for a legible
  message, the hash to catch the drift the version missed."
  (:require [clojure.string :as str]
            [flint.rt]))

(def section-name
  "The wasm custom section this lives in. Custom sections are ignored by every
  engine and readable straight from the bytes, which is what lets a runner
  decide whether to instantiate at all."
  "flint")

(def format-version 1)

(defn canonical
  "A value printed with map keys in sorted order, so the same facts always hash
  to the same bytes. `pr-str` alone would not: map iteration order is not part
  of the value, and hashing it would make the key depend on how the map was
  built."
  [x]
  (cond
    (map? x) (str "{" (str/join "," (map (fn [k] (str (canonical k) " " (canonical (get x k))))
                                         (sort-by pr-str (keys x))))
                  "}")
    (set? x) (str "#{" (str/join "," (sort (map canonical x))) "}")
    (sequential? x) (str "[" (str/join "," (map canonical x)) "]")
    :else (pr-str x)))

(defn fnv1a
  "FNV-1a over the UTF-8 bytes, as 8 lowercase hex digits.

  Written here in cljc rather than taken from a host, because the compiler runs
  on babashka and on flint and the two must produce the same key for the same
  module -- a hash that differs by host would refuse every self-hosted build."
  [s]
  (let [h (reduce (fn [h c]
                    (let [h (bit-xor h (bit-and c 0xff))]
                      (bit-and (* h 16777619) 0xffffffff)))
                  2166136261
                  (flint.rt/str-bytes s))]
    (let [hex (fn [n] (str/join (map (fn [i]
                                       (nth "0123456789abcdef"
                                            (bit-and (bit-shift-right n (* 4 (- 7 i))) 15)))
                                     (range 8))))]
      (hex h))))

(defn compat-key
  "The hash a loader compares. Over `:compat` and nothing else."
  [meta]
  (fnv1a (canonical (:compat meta))))

(defn describe
  "Build the metadata map. `compat` is the ABI-affecting subset; everything else
  is descriptive."
  [{:keys [abi memory gas-in-aot version exports imports units features entry builtins]}]
  (let [compat {:abi abi
                :memory (or memory :unshared)
                :gas-in-aot (boolean gas-in-aot)}
        m {:flint/module format-version
           :version version
           :compat compat
           ;; Descriptive from here down. Nothing below this line may change the
           ;; compatibility key, and `test/modmeta.clj` asserts it.
           :entry entry
           :exports (vec (sort exports))
           :builtins builtins
           :imports (vec (sort imports))
           :units (vec (sort-by :name units))
           :features features}]
    (assoc-in m [:compat :key] (compat-key m))))

(defn compatible?
  "Whether a module described by `a` can be loaded by a program described by
  `b`. Compares the KEY, so a diagnostics build and a production build of the
  same ABI are compatible."
  [a b]
  (= (get-in a [:compat :key]) (get-in b [:compat :key])))

(defn why-not
  "A legible reason, for when `compatible?` is false. The version is carried for
  exactly this: a hash mismatch alone tells a reader nothing they can act on."
  [a b]
  (cond
    (compatible? a b) nil
    (not= (get-in a [:compat :abi]) (get-in b [:compat :abi]))
    (str "built against a different runtime ABI: " (pr-str (get-in a [:compat :abi]))
         " against " (pr-str (get-in b [:compat :abi])))
    (not= (get-in a [:compat :memory]) (get-in b [:compat :memory]))
    (str "built for " (name (get-in a [:compat :memory])) " memory, but this program is "
         (name (get-in b [:compat :memory])))
    (not= (get-in a [:compat :gas-in-aot]) (get-in b [:compat :gas-in-aot]))
    "built with gas compiled into AOT code, against a program without it (or the reverse)"
    :else
    (str "compatibility key " (get-in a [:compat :key]) " against "
         (get-in b [:compat :key])
         " -- same declared fields, so something ABI-affecting changed without "
         "being declared. That is the drift the hash exists to catch.")))
