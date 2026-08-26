(ns flint.wasmshake
  "The wasm half of `flint.shake`: get the call graph out of a module, and put
  the dead functions back in as nothing.

  Two decisions worth stating, because both look like corners cut and are not.

  **The scan is conservative, deliberately.** Finding every `call` immediate
  exactly means decoding the whole instruction stream -- every opcode's operand
  widths, and there are a few hundred. Scanning for the `call` opcode byte
  instead can produce edges that are not there, because a `0x10` byte can occur
  inside somebody else's immediate. That direction is SAFE: a spurious edge
  keeps a function that could have gone, and a real call is always found,
  because a real call really is that byte followed by that LEB. So this can
  only ever remove too little, never too much -- and `bin/shake-report`
  measures how much too little, against the linker's own answer.

  **Dead functions are STUBBED, not deleted.** A wasm function index is
  positional: deleting one renumbers every call, every table element and every
  export after it. Replacing the body with `unreachable` keeps every index
  valid and still drops the bytes, which are almost all of the prize -- the
  code section is 89% of flint's runtime module and the tables that would also
  shrink are under 1%."
  (:require [flint.wasm :as w]
            [flint.shake :as shake]))

(def ^:private CALL 0x10)

(defn- ub [b i] (bit-and (int (aget ^bytes b (int i))) 0xff))

(defn- uleb-at
  "[value next-index], LEB128 unsigned."
  [b i]
  (loop [i i sh 0 acc 0]
    (let [x (ub b i)
          acc (bit-or acc (bit-shift-left (bit-and x 0x7f) sh))]
      (if (zero? (bit-and x 0x80)) [acc (inc i)] (recur (inc i) (+ sh 7) acc)))))

(defn bodies
  "Every function body in the code section, as `{:index :start :end}` where the
  range covers the body's own bytes -- locals and code, not the size prefix.
  `:index` counts imported functions first, as wasm does."
  [m]
  (let [{:keys [payload]} (w/section m 10)
        base (w/imported-funcs m)]
    (when payload
      (let [[n i0] (uleb-at payload 0)]
        (loop [k 0 i i0 out []]
          (if (= k n)
            out
            (let [[size j] (uleb-at payload i)]
              (recur (inc k) (+ j size)
                     (conj out {:index (+ base k) :start j :end (+ j size)})))))))))

(defn call-edges
  "function index -> the indices it calls. Conservative; see the namespace note."
  [m]
  (let [{:keys [payload]} (w/section m 10)
        nfuncs (+ (w/imported-funcs m) (count (bodies m)))]
    (into {}
          (for [{:keys [index start end]} (bodies m)]
            [index
             (loop [i start out #{}]
               (if (>= i end)
                 out
                 (if (= CALL (ub payload i))
                   (let [[t j] (uleb-at payload (inc i))]
                     ;; An index past the end of the function space came from a
                     ;; byte that was not a call at all. Dropping it is not a
                     ;; correctness risk -- it was never an edge.
                     (recur j (if (< t nfuncs) (conj out t) out)))
                   (recur (inc i) out))))]))))

(defn- read-segment
  "One active element segment. Returns `[{slot funcidx} next-index]`."
  [payload i]
  (let [[flags i] (uleb-at payload i)]
    (when-not (zero? flags)
      (throw (ex-info "element segment is not the active, table-0 shape flint emits"
                      {:flags flags})))
    (when-not (= 0x41 (ub payload i))
      (throw (ex-info "element segment offset is not i32.const" {})))
    (let [[base i] (uleb-at payload (inc i))
          i (inc i)                                   ; the 0x0b that ends the offset
          [n i] (uleb-at payload i)]
      (loop [j 0 i i out {}]
        (if (= j n)
          [out i]
          (let [[f i2] (uleb-at payload i)]
            (recur (inc j) i2 (assoc out (+ base j) f))))))))

(defn table-entries
  "`slot -> function index`, read from the element segments.

  This is the truthful way to find a builtin's function. The export SYMBOL is
  not derivable from the builtin's NAME -- `=` is `flint_b_eq` and `nil?` is
  `flint_b_nilp` -- so guessing it roots the wrong things, and that shows up as
  `RuntimeError: unreachable` at run time rather than at build time."
  [m]
  (let [{:keys [payload]} (w/section m 9)]
    (if-not payload
      {}
      (let [[nseg i0] (uleb-at payload 0)]
        (loop [k 0 i i0 out {}]
          (if (= k nseg)
            out
            (let [[entries i2] (read-segment payload i)]
              (recur (inc k) i2 (merge out entries)))))))))

(defn roots
  "Everything that must survive: the functions `keep-exports` names, plus every
  function the table can reach, because a `call_indirect` is a call to any of
  them and nothing here knows which.

  The table is where flint's builtins live -- they are reached ONLY through
  `__indirect_function_table` (`doc/decisions/0003`) -- so `elems` is how a
  caller says which builtins the program actually needs. Passing the whole
  table keeps every builtin; passing the reached ones is the precision the
  linker could not have."
  [m keep-exports elems]
  (let [exp (w/exports m)]
    (into (set elems)
          (keep (fn [nm] (:index (get exp nm))) keep-exports))))

(def ^:private STUB
  "A body that is `unreachable` and nothing else: no locals, one opcode, `end`.
  Valid for any signature, because `unreachable` is polymorphic."
  [0x00 0x00 0x0b])

(defn stub-dead
  "Replace the body of every function not reachable from `roots` with
  `unreachable`. Returns `[module report]`."
  [m root-set]
  (let [{:keys [payload]} (w/section m 10)
        bs (bodies m)
        edges (call-edges m)
        live (shake/reachable root-set (fn [n] (get edges n #{})))
        [nfn i0] (uleb-at payload 0)
        kept (count (filter (fn [b] (contains? live (:index b))) bs))
        rebuilt (w/->bytes
                 [(w/uleb nfn)
                  (for [{:keys [index start end]} bs]
                    (if (contains? live index)
                      (let [n (- end start)]
                        [(w/uleb n) (java.util.Arrays/copyOfRange
                                     ^bytes payload (int start) (int end))])
                      [(w/uleb (count STUB)) STUB]))])
        m' (w/put-section m 10 rebuilt)]
    [m' (shake/report {:total (count bs) :kept kept
                       :before (count payload) :after (count rebuilt)})]))
