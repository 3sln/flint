;; Byte strings (`doc/decisions/0024`).
;;
;; The logic under test is `test/bytes.cljc`, which runs inside the module.
;; What this file adds is the number the type exists for: the same bytes held
;; as a vector of integers and as a byte string.
(require '[clojure.string :as str] '[clojure.edn :as edn])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(println "bytes: a string of bytes, in two tiers")

(let [b (sh "./bin/flint" ":src" "test" ":fn" "bytes/main" ":out" "out/bytes.wasm")]
  (when-not (zero? (:exit b))
    (println "build failed:" (:out b) (:err b)) (System/exit 1)))
(def r (let [x (sh "node" "host/flint.mjs" "out/bytes.wasm")]
         (when-not (zero? (:exit x))
           (println "  FAIL the module trapped:" (str/trim (:err x))) (System/exit 1))
         (edn/read-string (str/trim (:out x)))))

(check "bytes? tells a byte string from everything else"
       (:bytes? r) [true false false false])
(check "a string decoded from one is not one" (:string-isnt r) false)

(check "count, flat"            (:count r) 6)
(check "index, flat"            (:at r) [104 32])
(check "concatenation"          (:cat r) "hello byte world")
(check "slice"                  (:slice r) "byte")
(check "the empty byte string"  (:empty r) 0)

(check "count, tree"            (:big-count r) 3200)
(check "index at both ends"     (:big-ends r) [48 102])
;; The case that catches an off-by-one in the descent.
(check "index across a child boundary" (:big-boundary r) [102 48])
(check "slice out of a tree"    (:big-slice r) "0123456789abcdef")

;; The tier has to be invisible, or a byte string works only while it is small.
(check "a tree slice equals a flat string"  (:eq-across r) true)
(check "and differing content is not equal" (:ne r) false)
(check "nor is differing length"            (:ne-length r) false)
(check "a byte string is a map key by CONTENT, across tiers" (:as-key r) :found)

(check "nth"    (:nth r) 104)
(check "count"  (:count-generic r) 16)
(check "get"    (:get-generic r) 101)

(check "vector round trip"     (:roundtrip r) [104 105])
(check "built from a vector"   (:vec->bytes r) "hi")
(check "a string's UTF-8, in bytes not characters" (:utf8 r) 2)

(println "bytes: the transient")

(check "all three ways of building agree, and on the size"
       (:built-agree r) [true true 640])
(check "a transient reports what it holds"  (:trans-grows r) [16 17])
(check "and can start from an existing one" (:trans-seed r) "seed")
(check-that "a dead transient refuses, rather than writing into a shared buffer"
            (str/includes? (str (:trans-dead r)) "no longer usable"))
;; The tree shape is not observable from the answers, so it is asserted
;; separately: without the right-spine descent it grew a level every sixteen
;; joins, and the recursive walk ran off the shadow stack.
(check "the tree stays shallow under repeated concatenation" (:depth-bounded r) true)

(check-that "an index past the end is refused"
            (str/includes? (str (:past-end r)) "out of range"))
(check-that "bytes that are not UTF-8 are refused as a string"
            (str/includes? (str (:not-utf8 r)) "not UTF-8"))

(println "bytes: what they were built for")

;; `flint.wasm` is the wasm binary reader and writer, and it used to be Java
;; byte arrays -- `aget`, `alength`, `ByteArrayOutputStream` -- which is why
;; the compiler compiled to wasm could emit a bytecode image and not a module.
;; It runs on byte strings now, so it compiles for flint too. This asserts
;; that, because it is the whole reason the type exists.
(let [x (sh "./bin/flint" ":src" "src" ":fn" "flint.wasm/parse" ":out" "/tmp/wasmport.wasm")]
  (check-that "flint.wasm compiles FOR flint, not just on the host"
              (zero? (:exit x))))
;; Host interop is allowed only INSIDE a reader conditional, where the `:flint`
;; branch beside it says what a module does instead. Anywhere else it is a line
;; that cannot compile for flint, and it would pass every other test in the
;; suite -- the file would simply stop being portable and nothing would say so.
;;
;; The count is asserted too, not just the shape. One host difference at one
;; site is a reader conditional; five of them is a file that has quietly gone
;; back to being host-only.
(let [lines (remove (fn [l] (re-find #"^\s*;" l))
                    (str/split-lines (slurp "src/flint/wasm.cljc")))
      interop (filter (fn [l] (re-find #"aget|alength|byte-array|Arrays/|String\.|ByteArrayOutput" l))
                      lines)
      guarded (filter (fn [l] (str/includes? l ":clj")) interop)]
  (check "host interop only ever appears in a :clj branch"
         (vec (remove (fn [l] (str/includes? l ":clj")) interop)) [])
  (check-that (str "and there is at most one such site (" (count guarded) ")")
              (<= (count guarded) 1)))

(if (pos? @fails)
  (do (println "bytes:" @fails "failed") (System/exit 1))
  (println "bytes: ok"))
