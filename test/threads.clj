;; Green threads, ports and the host ABI (DECISIONS.md#threads-and-ports and 0006).
;;
;; The two properties that govern everything else are asserted first: a pure
;; program is not made bigger by any of this, and nothing in a flint module
;; suspends a wasm frame.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[clojure.edn :as edn])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label "\n        expected" (pr-str expected)
                                   "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(def d (str (fs/create-temp-dir)))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(defn build! [ns-name & [out & flags]]
  (let [o (or out (str "out/th-" ns-name ".wasm"))
        r (apply sh "./bin/flint" ":src" d ":fn" (str ns-name "/main") ":out" o flags)]
    (when-not (zero? (:exit r))
      (println "build failed for" ns-name ":" (:all r)) (System/exit 1))
    ;; The artifact AND the function to call. Nothing is called automatically
    ;; (`DECISIONS.md#structured-ports` step 5), so a runner has to name one, and `build!`
    ;; is the only place that knows the namespace.
    [o (str ns-name "/main")]))

(defn wasm-of [b] (if (vector? b) (first b) b))
(defn fn-of [b] (if (vector? b) (second b) nil))

(defn run! [b & [host]]
  (let [r (sh "node" (or host "host/flint.mjs") (wasm-of b) (fn-of b))]
    (str/trim (:all r))))

(defn src! [name body] (spit (str d "/" name ".cljc") body))

(println "threads and ports")

;; ---------------------------------------------------------------- size
;;
;; "None of this may grow a pure module." Threads and ports are namespace units
;; like any other, so a program that never mentions them must not carry a
;; scheduler, port machinery or a host-callback surface.

(src! "pure" "(ns pure)\n(defn main [_] \"nothing\")")
(src! "threaded"
      (str "(ns threaded (:require [flint.thread :as t]))\n"
           "(defn main [_] (str (t/join (t/spawn (fn [] 42)))))"))
(def pure-wasm (build! "pure"))
(def threaded-wasm (build! "threaded"))
;; The FLOOR is what ships, and what ships has no checks in it: `:optimize
;; [perf]` removes `:flint/check` before the source is read
;; (`DECISIONS.md#checks`). Measuring the default build against a shipping floor
;; charges the production budget for development machinery, which is how this
;; guard came to be 9 KB over its budget without anyone being told.
;;
;; `:features [flint]` rather than `:optimize [perf]` because perf ALSO
;; compiles every arity, and a floor that moved for two reasons at once
;; measures neither.
(def shipped-wasm (build! "pure" "out/th-pure-shipped.wasm" ":features" "[flint]"))
(def pure-size (fs/size (wasm-of shipped-wasm)))
(def check-cost (- (fs/size (wasm-of pure-wasm)) pure-size))
(println (format "    pure module %d bytes shipped, +%d with checks, with threads %d (+%d)"
                 pure-size check-cost (fs/size (wasm-of threaded-wasm))
                 (- (fs/size (wasm-of threaded-wasm)) (fs/size (wasm-of pure-wasm)))))

;; THE PORT ABI IS IN EVERY MODULE NOW, and the SCHEDULER'S WORK is not
;; (`DECISIONS.md#calls-are-ports`). A sandbox is reached only through its
;; system port -- a call in, a request out, driving -- so a module without the
;; port machinery is a module nothing can call. `flint.conc` is therefore in
;; the link closure unconditionally.
;;
;; What a pure module still does NOT carry is the part it does not use:
;; spawning, channels, sending. Those remain reachability-linked, which is what
;; keeps this row meaningful rather than a tautology -- it would pass trivially
;; if it only asserted what is present.
(def pure-bytes (String. (fs/read-all-bytes (wasm-of pure-wasm)) "ISO-8859-1"))
(doseq [sym ["flint_install_port" "flint_system_port" "flint_in_alloc"]]
  (check (str "every module carries " sym) (str/includes? pure-bytes sym) true))
(doseq [sym ["flint_b_spawn" "flint_b_port_send" "flint_b_channel"]]
  (check (str "a pure module still has no " sym) (str/includes? pure-bytes sym) false))
;; The floor moved in 0009, deliberately and by a known amount: the interpreter
;; loop is instantiated twice so that a run with no budget has no counter in it,
;; and the biggest function in the module is therefore in it twice. The point of
;; the bound here is that it is a BUDGET somebody chose, not a number that
;; drifts.
;;
;; It moved again for ropes (`DECISIONS.md#strings-and-matching`), by 6 310 bytes, and that is
;; also a budget rather than drift: repeated concatenation -- the case 0011
;; names as quadratic with flat strings -- went from 57.17 ms to 2.31 ms on
;; `bench/progs/concat.cljc`, which is 24.7x, and from 3.1x slower than babashka
;; to 7.9x faster. A tree join is not free in bytes and it is worth these ones.
;; And again for type specialisation, by 5 172 bytes -- MEASURED, by building
;; the same module with the eight arms removed and with them in (213 243 vs
;; 218 415), not attributed. The interpreter loop is instantiated twice, so
;; everything in it is paid for twice, which is why the slow half of each
;; operation is `#[inline(never)]`: moving it out of line gave back 1 311 of
;; those bytes and cost nothing measurable.
;;
;; What it buys, on `bench/progs/spec.cljc`: the same loop, the same answer and
;; THE SAME INSTRUCTION COUNT runs 1.90x faster when the compiler could prove
;; the operands were integers -- 22.4 ns per arithmetic operation, which is the
;; cost of reaching a builtin through the table and having it re-read its
;; arguments off the value stack.
;; And 2 360 bytes for byte strings (`DECISIONS.md#no-runtime-linking`), which a program
;; using none of them still pays: `count`, `nth`, `=` and `hash` all reference
;; the byte paths, so the type is pinned by the generic collection surface
;; rather than by anyone calling it. What it buys, on 200 000 bytes: 43.5 MB
;; and 28 collections held as a vector of integers, against 0.2 MB and none
;; held as a byte string.
;; And 20 589 bytes for `flint_call` (`DECISIONS.md#structured-ports`), MEASURED by
;; building the same module with the export and without it (221 082 against
;; 241 671). It is the wire codec and the map building an error reply needs,
;; and every module carries it because every module can be called.
;;
;; What it buys is the reason an image is a set of callable functions rather
;; than a program with one way in: a sandbox serves many calls, and a host
;; chooses which. That is the whole of 0025's Image/Sandbox split, and it does
;; not work without a way in that takes a name.
;;
;; It went DOWN by 2 580 bytes when the grant table stopped being a `static mut`
;; and moved onto the `Rt` (`DECISIONS.md#opaque-values`). That change was made because
;; the static leaked capabilities between native sandboxes; it being smaller
;; too is a bonus and not the reason.
;;
;; Several executors in one sandbox (`DECISIONS.md#drivers`) costs 1 056 bytes of
;; root-scanning loop, MEASURED by building this module with it and without
;; (245 901 against 244 845). It is behind the `parallel` feature and so is
;; ABSENT here rather than disabled (`DECISIONS.md#two-builds`): wasm cannot have a
;; second executor until it has the threads proposal and a shared memory, and a
;; module that can only ever have one should not carry the loop that walks the
;; others.
;;
;; And 5 040 bytes for the WRITE BARRIER carrying its remembered set, MEASURED
;; the same way (249 490 against 244 450). This one is NOT behind the feature,
;; and the reason is worth stating because it is the opposite call to the one
;; above.
;;
;; The barrier is not machinery for a feature this module cannot use -- every
;; module runs it. What is parallel-specific is only WHICH list it appends to,
;; and making that a compile-time fork means two copies of a twenty-five line
;; function. `flint.strs` records what that costs: `symbol` and `keyword` had
;; the same four lines and the same rooting bug, and only one of them surfaced.
;; Two copies of the write barrier is a worse trade than 5 040 bytes.
;; Raised again for the TAGGED LITERAL type (`DECISIONS.md#tagged-literals`): the shipped
;; floor measured 264 997 where it had been 261 363. That net is not all of it
;; -- the ring simplifications in `8f33c76` and `4b4bfb6` moved it DOWN in
;; between -- but the direction and the reason are clear: a new heap type puts
;; branches in `eq`, `hash`, `get`, `assoc` and `kind`, and those live in the
;; runtime where nothing shakes them out. A value type that only some programs
;; use still costs every program, which is the trade a language type is.

;; RAISED 2026-09-05, from 304 000, for `describe` REACHING THE MESSAGES.
;;
;; Measured: 301 729 before, 304 481 after -- 2 752 bytes. What they buy is
;; that the three runtimes tell a program the same thing when they refuse it.
;; Native named the value in NONE of its refusals and both ports named it in
;; twenty-five, so `(deref 1)` said "cannot deref this value" on one runtime
;; and "cannot deref an integer" on the others: a difference a program could
;; catch and read, and one no gate was asking about.
;;
;; The cost is nearly all FIXED rather than per-site. Linking `describe` and
;; the first `format!` is 1 569 of it; the ten sites after that are 1 183
;; between them, so naming the value at the remaining call sites is close to
;; free. It was 2 935 for a SINGLE site until `describe` stopped building a
;; `String` for each of its forty literals -- that shape change is what made
;; this affordable, rather than the budget being generous.
;; It moved again by 2 076 bytes when map hashing and `seq` over a map became
;; GENERATED walks (`kin/collhash.kin`, `kin/collvec.kin`), and that number is
;; the price of a decision rather than drift.
;;
;; What it bought: four functions came off the list the closure hole blocks, so
;; equality, hashing, `seq` and set elements are one source compiled three ways
;; instead of three hand-written copies -- and both ports stopped walking a map
;; by pushing every key AND value onto the shadow stack first, which is 2n
;; roots against O(depth).
;;
;; WHY IT COSTS ANYTHING is worth recording, because it is an argument about
;; the closure hole and not about these two files. Each module needs a PAIR of
;; near-identical walks -- one summing entry hashes and one summing key
;; hashes, one collecting entries and one collecting keys -- and they cannot
;; share, because the only difference is what to do per entry, and passing
;; that IS a closure. Four walks where two would do is most of the 2 076.
;; IT MOVED AGAIN, by 1 934 bytes, when A PLAIN VECTOR STOPPED BEING WALKED AS
;; A SEQ -- once for `=` and once for `hash` (`kin/valeq.kin`,
;; `kin/valhash.kin`). As with the 2 076 above, the number is the price of a
;; decision and not drift, so here is the decision.
;;
;; WHAT IT COSTS: 315 386 -> 317 320 on this floor, which is 0.61%, in two
;; steps of 1 093 and 841. It lands on EVERY module including this one --
;; `(defn main [_] "nothing")` -- because `=` and `hash` are in the floor and
;; now reach `vec-nth`'s trie descent. A program that never touches a vector
;; still pays. The second step is smaller than the first because the descent
;; was already linked by then; the two fixes share it.
;;
;; WHAT IT BUYS, measured by `bench/equiv.mjs`:
;;
;;     operation              before        after     allocations
;;     = on 32 elements      2 061 ns      330 ns       64 -> 0
;;     = on 2 000          127 005 ns   20 098 ns    4 000 -> 0
;;     = on 20 000       1 273 190 ns  203 818 ns   40 000 -> 0
;;     hash of 2                80 ns       13 ns        2 -> 0
;;     hash of 2 000        74 927 ns   19 181 ns    2 000 -> 0
;;
;; The clock is the smaller half. The seq walk allocated a cell PER ELEMENT --
;; two for `=`, one per side, and one for `hash` -- so comparing two
;; 32-element vectors, an ordinary thing to do, allocated 64 objects. That is
;; the shape `bench/progs/colls.cljc` exists to catch and wall-clock cannot
;; see, and in a 128 MiB isolate it is entirely real.
;;
;; WHY IT WAS THERE AT ALL: `mapeq` was given a structural comparison and the
;; SEQUENTIAL paths were left generic, so the INDEXABLE case was the slow one
;; -- `=` on 20 000 vector elements cost 1.27ms against 0.42ms for a
;; 20 000-entry MAP. For `hash` the cache hid it: a vector used repeatedly as
;; a map key hashes once. But the first hash still walked, and a vector hashed
;; exactly once -- every element going into a set -- never reached the cache.
;;
;; NEITHER GATE HERE WOULD HAVE CAUGHT IT, which is the part worth keeping.
;; Both operations were correct, cross-runtime identical and inside every
;; budget; they were only slow and allocating, and nothing in the tree was
;; asking that question until `bench/equiv.mjs` existed.
;;
;; TO REVERSE IT: drop `vec-eq-indexed` from `kin/valeq.kin` and
;; `hash-vec-indexed` from `kin/valhash.kin`, and put this number back to
;; 316 000. The gate is the only thing holding the decision.
;;
;; AND AGAIN, by 984 bytes, when `str-index-of` became `kin/ropefind.kin` --
;; ONE implementation replacing three. 317 320 -> 318 304.
;;
;; WHAT WAS THERE: native flattened the rope and searched UTF-8, the JVM built
;; a `java.lang.String` and searched UTF-16, and the CLR built a .NET `string`
;; and then allocated `h.Substring(0, i)` purely to count code points. Three
;; algorithms over three representations, agreeing on the answer and on
;; nothing else -- so the same program cost DIFFERENT GAS on each, and no
;; charge could be moved to fix it because they were not doing the same work.
;;
;; WHAT IT BUYS, all of it measured:
;;
;;   * gas agrees. The same search cost 897 steps native against 21 on the
;;     JVM; it is now 113 on both, and the same on a flat string as on a
;;     three-leaf rope holding the same bytes.
;;   * the haystack is never materialised. `strings-and-matching` lists `index-of` under what
;;     must WALK rather than flatten; an early match on a 34 KB rope now costs
;;     0.06ms and ZERO allocations, where flattening copied all 34 KB before
;;     looking at the first byte.
;;   * a real divergence died with it. `(str/index-of "abc" "" 100)` was nil
;;     on native's ASCII path, the count on its non-ASCII path, and the count
;;     on both ports -- the answer depended on whether the haystack happened
;;     to be ASCII, and native contradicted its own ports. Clojure says 3.
;;     Found by differencing 1 560 cases; 1 536 were byte-identical.
;;
;; WHAT IT COSTS BESIDES THE BYTES: a full scan is ~1.7x the old
;; flatten-and-search (41us against 25us on 34 KB). It started at 158us and
;; came down in three measured steps -- an ASCII skip, dropping the cursor
;; clone for matches that stay inside one leaf, and `run-eq` for the compare.
;; The remaining gap is the price of not copying the haystack.
;;
;; TO REVERSE IT: the three builtins would each need their own search back,
;; which is the thing worth not doing.
;;
;; AND AGAIN, by 245 bytes, for two rope fixes at the PREPEND end --
;; 318 304 -> 318 549 -- which is the smallest entry here and was very nearly
;; the largest.
;;
;; RAGGED TREES. `s-concat` lifted `b` by `rope-height(a)`, right only when
;; `b` is a LEAF -- the append case. Reversed it lifts the TALL side by zero
;; and builds `[leaf, deep-rope]`: children at different heights, breaking the
;; invariant `rope-height` states it depends on ("exact because a node's
;; children are all the same height"). It was reading child zero and reporting
;; 1 for a chain twelve deep. No measurement justifies this half and none was
;; looked for; an invariant either holds or the code trusting it is wrong.
;;
;; LINEAR DEPTH. There was no left-spine counterpart to `rope-append`, so
;; every prepend added a level: twelve prepends gave depth 12 where twelve
;; appends give 3.
;;
;; HOW IT CAME TO COST ALMOST NOTHING, which is the part worth keeping. The
;; prepend was first written as its own mirror of the append -- a second
;; descent, a second set of rebuilds, a second lift -- and cost 2 090 bytes.
;; Measured against the shape that actually occurs, it earned none of them:
;; printing a WIDE structure (2 000 elements at depth 4, 500 at 8, 100 at 12)
;; was within 0.03% with it and without, because each level prepends ONCE onto
;; its own content. It pays only past ~400 levels of nesting, where depth 800
;; printed in 1.01ms against 5.94ms. So it was removed.
;;
;; Then the two directions were made ONE WALK -- `rope-graft`, with a `front`
;; flag choosing which child to descend, which way the merge concatenates and
;; which end a sibling joins. The second direction is now a handful of
;; branches, and the shared function is SMALLER THAN THE OLD `rope-append`
;; ALONE: 318 549 against 318 636 with no prepend at all. A feature that cost
;; 2 090 bytes as a copy costs -87 as a parameter.
;;
;;
;; AND AGAIN, by 481 bytes, for ONE STRING HASH INSTEAD OF THREE.
;; 318 549 -> 319 030.
;;
;; There were three bases in play, not two. A flat string walked UTF-16 UNITS
;; to match Java's `String.hashCode`; a tree walked BYTES; and an INTERNED
;; string carried a UTF-16 hash written into its header at intern time. So a
;; non-ASCII string hashed one way while short, another once past FLAT_MAX,
;; and the intern path decided which for anything short enough to intern.
;; Measured with `\u00e9` repeated: 1 024 bytes agreed with Clojure, 1 026 did
;; not. Nothing caught it -- all four runtimes were wrong together, and no
;; short string is EQUAL to a long one, so flint's own tier rule held.
;;
;; IT IS BYTES NOW, EVERYWHERE. Matching Clojure's NUMBERS is not a contract
;; Clojure offers -- it changed its own hash in 1.6 and documents no stability
;; -- and the UTF-16 basis cost more than it bought: a derivation on every
;; hash, and no per-node caching, because `pow31` composition needs the
;; child's length in the units the walk counts and no node carries a UTF-16
;; count. Bytes compose. ASCII still agrees with Clojure exactly, because
;; there a byte IS a unit; of the five pinned string values -- now in
;; `kin/hashtext.drivers`, previously in `RtHash.java` -- only the non-ASCII
;; one moved.
;;
;; WHY IT COSTS ANYTHING AT ALL, since the byte walk is the simpler function.
;; Interning must hash a byte SLICE to probe the table before a Value exists,
;; so a slice walk ships beside the kin tree walk. Removing the dead UTF-16
;; string functions bought nothing back -- the shaker was never shipping them.
;; The UTF-16 machinery still ships because a SYMBOL's hash combines
;; `java_string_hash` of the namespace with the murmur of the name, and that
;; is a different question from a string's content.
;;
;; SIXTH RAISE, and the first where the PREDICTION IN THIS FILE WAS WRONG.
;;
;; The note above said giving symbols and keywords the same byte treatment as
;; strings "would delete `Utf16Units`, `java_string_hash` and
;; `hash_unencoded_chars` outright" and pay the budget back. It was done. It
;; cost 901 bytes: 319 528 -> 320 429 on the same units.
;;
;; WHY THE PAYBACK WAS ZERO. The note two paragraphs up already knew, about the
;; string half: "the shaker was never shipping them". Deleting code that does
;; not ship saves nothing, and the symbol half was the same. What the change
;; ADDED is `hash-bytes` reached from two more call sites, walk and murmur
;; finaliser both, where the old path reached a bare 31-walk for the namespace
;; and murmur for the name.
;;
;; A closure was suspected first, since this file records one costing far more
;; in the printer -- `ns.map_or(0, |s| ..)` is a `call_indirect` target and the
;; shaker roots those conservatively. Measured: 16 bytes. Not it.
;;
;; WHAT IT BOUGHT IS NOT SIZE. Keyword hashing no longer decodes UTF-8 into
;; UTF-16 on the hot path -- on the JVM it ALLOCATED an int array per call, and
;; keywords are what map keys are made of. And the numbers stop being fitted to
;; somebody else's implementation: the old namespace/name asymmetry was, in its
;; own words, "found by solving for it against `'foo/bar`".
;;
;; DISTRIBUTION WAS THE STATED RESERVATION, and it is answered by measurement
;; rather than argument. `hash-bytes` is not a bare 31-walk: it ends in
;; `hash-int`, which is murmur's full avalanche. Over 930 keyword-shaped names
;; (`:id`, `:name`, `:parent-id`, ...) it gives 930 distinct hashes, and at
;; every 5-bit CHAMP level all 32 buckets are occupied, worst 41 against an
;; ideal of 29 -- ordinary variance for a random hash.
;;
;; FIFTH RAISE IN ONE SESSION. The four before it are above.
;; THE SESSION TOTAL, because a budget raised once per fix is not a budget:
;; 315 386 -> 319 030 is 3 644 bytes, 1.16%, over four entries. The question
;; worth asking is whether they are worth it together, not whether the next
;; one is worth its own -- which is the question that turned 2 090 bytes into
;; nothing.
(check-that "the floor is within the budget 0009, 0011, specialisation, bytes and call chose"
;; TABLES (`DECISIONS.md#tables`) cost 22 857 bytes here when they landed --
;; 287 854 shipped against 264 997 -- and then gave 15 832 of it back, which is
;; the more interesting number and the reason both are recorded.
;;
;; The cost was NOT the value type. It was one line in the printer:
;;
;;     (flint.rt/table? x) (... (mapv (fn [i] (get x i)) (range (count x))))
;;
;; `pr-str*` is linked by anything that prints, so that branch made every module
;; know what a table is -- and the closure inside it is a `call_indirect`
;; target, which the shaker roots CONSERVATIVELY, so it dragged `get`-on-a-table
;; and the whole chunk descent in behind it. Measured by removing it: 287 751
;; against 271 919 on the same units.
;;
;; What replaced it is a `Printable` protocol whose `:table` implementation
;; lives in `flint.table`, so the type specialises its own printing and a
;; program that never requires that namespace never carries any of it. That is
;; what protocols are for, and the 15 832 bytes are what the coupling was
;; costing while the branch merely looked convenient.
;;
;; The 6 922 bytes tables still cost every module are the runtime arms in `eq`,
;; `hash`, `kind`, `get`, `count`, `map_get` and `seq`, which are Rust and do
;; not shake. A table remains a candidate for a UNIT (`DECISIONS.md#construe-integration-bar`); the
;; printer is no longer the thing standing in the way.
;; RAISED 2026-09-08, from 312 000, for NUMBER FORMATTING becoming generated.
;;
;; MEASURED by building the units with `kin/dblstr.kin` applied and again at
;; the previous commit: 314 169 against 311 268, so 2 901 bytes.
;;
;; What the bytes buy is the end of a four-way disagreement about what a
;; double looks like. `(str 1e14)` was "100000000000000.0" on all three
;; runtimes and "1.0E14" in Clojure; `(str 1e20)` was a different string on
;; each of the three; and both ports printed the host's "Infinity" where
;; native printed "##Inf". Three hand-written formatters, three thresholds,
;; and none of them Clojure's.
;;
;; The double path is 6 873 of the module and the integer path GAVE BACK
;; 3 972, which is the more interesting pair. Decomposed by stubbing each
;; part and rebuilding:
;;
;;     307 296   integers only -- no double formatting linked at all
;;     310 290   + the host's shortest-decimal digits (Rust `format!`)
;;     314 169   + the walk that re-renders them under Clojure's rule
;;
;; So the generated integer path is 3 972 bytes SMALLER than the hand-written
;; `number_to_string` it replaced, and what the change actually costs is the
;; double walk. Handing the digits over in a code-point buffer rather than a
;; string saves 857 of it by not linking the UTF-8 decoder to read back
;; characters that are all ASCII by construction.
;;
;; RAISED 2026-09-06, from 307 000, for the ATOM OPERATIONS becoming generated.
;;
;; MEASURED by building the shipped module with `kin/atoms.kin` applied and
;; again with the hand-written `coll.rs` bodies, same units both times:
;; 307 239 against 305 515, so 1 724 bytes.
;;
;; The first attempt at that measurement was WRONG and the way it was wrong is
;; worth recording: `bb test/threads.clj` on its own reuses whatever units
;; `bin/build-units` last produced, so both arms read the SAME stale artefact
;; and agreed to the byte. A measurement that cannot tell the two arms apart is
;; not evidence that they are equal.
;;
;; What the bytes buy is three fixes and one convergence. Both ports' `deref`
;; stored a delay's thunk result WITHOUT asking whether it threw, so a delay
;; whose thunk failed cached nil forever where native left it unforced and
;; retryable. Native's own `deref` held the delay in a HOST LOCAL across the
;; thunk call and then wrote through it -- and running a thunk can collect, so
;; the box it wrote to may have moved (`DECISIONS.md#a-vec-of-values-is-not-a-root`). And both ports
;; implemented atoms inline in `Builtins` as lambdas, which is why neither bug
;; was visible next to the other two runtimes.
;;
;; Roughly half the cost is `invoke_roots` being linked: the generated `deref`
;; calls the thunk through a run of shadow-stack roots rather than a host
;; slice, which is what makes the rooting above expressible in all three at
;; once. It is a fixed cost, and the next generated caller of a value pays none
;; of it.
            ;; SIXTH RAISE, and the largest by far: +106 525 bytes for the
            ;; single entry point (`DECISIONS.md#calls-are-ports`), measured
            ;; 376 644 against 270 119. A sandbox is reached only through its
            ;; system port, so `flint.conc` is linked unconditionally -- every
            ;; module is CALLED, so every module needs it. The green thread is
            ;; what costs and it is not severable: a call runs as one precisely
            ;; so the called function can park.
            ;;
            ;; This one is not "worth it together" with the four small ones
            ;; above; it is a different kind of entry. Those were fixes paying
            ;; their way in bytes. This is a boundary decision that was taken
            ;; knowing the price, and the price turned out to be three times the
            ;; 34 519 the estimate on record predicted.
            (< pure-size 430000))

;; RE-BASELINED AGAIN, and this one is a decision rather than a drift:
;; 300 281 against 280 781, and 18 917 of it is ONE ARM IN THE WIRE CODEC.
;;
;; MEASURED by building the same fixture with the `K_TABLE` arm and without
;; (300 281 against 281 364). The mechanism is not subtle: `flint_call` is an
;; unconditional export (`link.cljc`'s root list), the decoder hangs off it, and
;; the decoder names the table constructor -- so every module that can be CALLED
;; carries the machinery to build a table, whether or not it has ever heard of
;; one.
;;
;; That is the correct trade for soundness and it is what Ray asked for: a
;; program that takes from a port MIGHT be handed a table, and a shaker that
;; removed the constructor would turn that into a crash. `sdks/rust/tests/sdk.rs`
;; pins it -- a module that never requires `flint.table` receives one and reads
;; it.
;;
;; It is also 6.7% of a module, paid by every program in the world that never
;; uses a table, and that is the strongest argument yet for the UNIT this file
;; has been recommending since tables landed (`DECISIONS.md#construe-integration-bar`). The shape
;; is clear: a module that does not link the table unit refuses a `K_TABLE` on
;; the wire with a message naming what it cannot decode, which is honest to the
;; sender and free to everyone else. Recorded with the number attached rather
;; than absorbed, because 18 917 bytes is what should force that conversation.

;; RE-BASELINED from 276 000: 280 781 shipped against 272 129 at the previous
;; commit, so the work below costs 8 652 bytes in every module. MEASURED by
;; building the same fixture at both revisions, and worth writing down as one
;; number because it is one decision -- three bounds this runtime claimed and
;; did not have:
;;
;; * THE TRANSIENT TABLE (`tables` step 7). Appending 20 000 rows through the
;;   persistent path allocated 49 061 464 bytes and collected 23 times, against
;;   3 082 984 and once through the transient.
;;
;; * GAS THAT BOUNDS RATHER THAN BILLS (`resource-limits`). `charge_tick`/`charge_checked`
;;   at every loop a guest can make big, plus a charge on allocation itself.
;;   Thirteen operations ran past an exhausted budget before this -- `apply` by
;;   2 698 032 steps, `str-bytes` by 11 937 109 -- and `resource-limits` had said natives
;;   must charge since it was written.
;;
;; * ROPES THAT SHARE (`strings-and-matching`). `subs` copied every byte on both paths and
;;   flattened on one; equality copied BOTH sides in full before comparing a
;;   byte; hashing flattened to get a cache. `SLICE_MIN` -- the constant naming
;;   the sharing policy -- had one reference in the tree, and it was
;;   `let _ = SLICE_MIN;`, silencing the warning that said it was unimplemented.
;;
;; Every one of those is a property the project already claimed. The bytes buy
;; back claims rather than features, which is the only reason this is a
;; re-baseline and not a refusal.

;; RE-BASELINED 2026-08-30, from 252 000, and the honest version of why: the
;; guard was measuring the DEFAULT build against a shipping floor, and it had
;; been over its budget by 9 363 bytes before anyone looked. The accounting
;; above covers the moves it was written for and not the 159 commits since, so
;; this number is today's measurement (261 363) plus room, and its value is in
;; the DELTA it catches from here rather than in the absolute.
;;
;; What the split buys is that the two now move independently: development
;; machinery growing cannot spend the production budget.

;; And what checks cost, as a number rather than as a claim. `checks` says a
;; check costs nothing in the build that ships; this is the assertion of it,
;; and it caught a real violation the first time it ran -- the fifteen core
;; predicates carried their ``flint.check/explain`` UNCONDITIONALLY, which was
;; 3 578 bytes in a module that calls none of them -- present in the shipping
;; build too, because metadata attached unconditionally is not something
;; `:optimize [perf]` can take away. Behind the reader conditional they cost
;; that only where they are read, which is what the number below now bounds.
(check-that "checks cost nothing in the module that ships"
            (< check-cost 8000))

;; ---------------------------------------------------------------- channels

(src! "chan"
      (str "(ns chan (:require [flint.thread :as t] [flint.port :as p]))\n"
           "(defn main [_]\n"
           "  (let [[a b] (p/channel 1 \"one-slot\")\n"
           ;; A one-slot buffer forces both parks: the sender blocks on a full
           ;; buffer, the receiver on an empty one.
           "        w (t/spawn (fn [] (dotimes [i 5] (p/send a i)) :sent))\n"
           "        got (loop [acc []] (if (= 5 (count acc)) acc (recur (conj acc (p/receive b)))))]\n"
           "    (pr-str {:got got :worker (t/join w) :state (t/state w)})))"))
(check "a full buffer parks the sender and an empty one parks the receiver"
       (run! (build! "chan"))
       "{:got [0 1 2 3 4], :worker :sent, :state :done}")

;; DELEGATION (`DECISIONS.md#structured-ports`), which `host-abi` refused: an endpoint sent
;; through a channel and then USED by whoever received it.
;;
;; Between green threads no encoding is involved -- both ends are in one heap,
;; so the port that arrives is the port that was sent. The test is that the
;; receiver can send through an endpoint it never opened, and that what comes
;; back out the far end is what the receiver put in. A port that arrived as a
;; copy, or as a handle to nothing, fails here rather than merely looking
;; wrong.
(src! "delegate"
      (str "(ns delegate (:require [flint.thread :as t] [flint.port :as p] [flint.core :refer [opaque]]))\n"
           "(defn main [_]\n"
           "  (let [[oa ob] (p/channel 4 \"outer\")\n"
           "        [ia ib] (p/channel 4 \"inner\")\n"
           "        w (t/spawn (fn [] (let [got (p/receive ob)]\n"
           "                            (p/send got :delegated)\n"
           "                            [(p/port? got) (p/label got)])))]\n"
           "    (p/send oa ia)\n"
           "    (pr-str [(p/receive ib) (t/join w)])))"))
(check "an endpoint can be sent through a channel, and used by whoever gets it"
       (run! (build! "delegate"))
       "[:delegated [true \"inner\"]]")

(src! "closed"
      (str "(ns closed (:require [flint.thread :as t] [flint.port :as p] [flint.core :refer [opaque]]))\n"
           "(defn main [_]\n"
           "  (let [[a b] (p/channel 2)]\n"
           "    (p/send a 1) (p/close a)\n"
           "    (pr-str [(p/receive b) (p/state b) (p/receive b) (p/state b)\n"
           "             (p/closed? b) (try (p/send b 2) (catch Throwable e (ex-message e)))])))"))
(def closed-out (run! (build! "closed")))
(check-that "a half-closed port still drains what was already buffered"
            (str/starts-with? closed-out "[1 :half-closed nil"))
(check-that "  ... and then reads as end of stream, which closed? agrees with"
            (str/includes? closed-out "nil :half-closed true"))
(check-that "sending into a port whose peer has closed errors rather than parking"
            (str/includes? closed-out "the other end has closed"))

(src! "orphan"
      (str "(ns orphan (:require [flint.thread :as t] [flint.port :as p] [flint.core :refer [opaque]]))\n"
           "(defn only-b [] (let [[a b] (p/channel 1)] b))\n"
           "(defn main [_]\n"
           "  (let [b (only-b)]\n"
           "    (dotimes [i 400000] (str \"gc-padding-\" i))\n"
           "    (pr-str [(p/state b)\n"
           "             (try (p/receive b) (catch Throwable e (ex-message e)))\n"
           "             (try (p/send b 1) (catch Throwable e (ex-message e)))])))"))
(def orphan-out (run! (build! "orphan")))
(check-that "a port whose peer was collected reports :orphaned, not :closed"
            (str/includes? orphan-out ":orphaned"))
(check-that "  ... and receiving on it errors rather than reading as end of stream"
            (str/includes? orphan-out "receive: the other end of this port is gone"))
(check-that "  ... and so does sending" (str/includes? orphan-out "send: the other end is gone"))

;; ---------------------------------------------------------------- what crosses

(src! "crossing"
      (str "(ns crossing (:require [flint.port :as p] [flint.core :refer [opaque]]))\n"
           "(defn helper [x] x)\n"
           "(defn- try! [f] (try (f) (catch Throwable e (ex-message e))))\n"
           "(defn main [_]\n"
           "  (let [[a b] (p/channel 8)\n"
           "        h (p/open \"thing\")]\n"
           "    (pr-str\n"
           "     {:fn (try! (fn [] (p/send a helper)))\n"
           "      :nested-fn (try! (fn [] (p/send a [1 {:k helper}])))\n"
           ;; The four cells of the matrix. A channel encodes nothing, so
           ;; anything with an identity may cross one; a bridge encodes with the
           ;; wire codec, so only what means something on the far side may.
           "      :chan-through-chan (try! (fn [] (p/send a b) :sent))\n"
           "      :bridge-through-chan (try! (fn [] (p/send a h) :sent))\n"
           "      :bridge-through-bridge (try! (fn [] (p/send h h) :sent))\n"
           "      :chan-through-bridge (try! (fn [] (p/send h b) :sent))\n"
           "      :opaque-through-chan (try! (fn [] (p/send a (opaque \"fs\")) :sent))\n"
           "      :opaque-through-bridge (try! (fn [] (p/send h {:cap (opaque \"fs\")}) :sent))})))"))
;; Run under a host that GRANTS a port, because half the matrix is about what
;; may cross one -- and the default host refuses every `open`, which would end
;; the program before the interesting sends happen.
(def crossing (run! (build! "crossing") "test/delegate.mjs"))

;; A FUNCTION never crosses, on any port: a closure's meaning is its
;; environment, and that does not travel. This is the part `structured-ports` did not
;; change, and it is checked by NAME so the message stays useful.
(check-that "a function is refused at the send, by name"
            (str/includes? crossing "helper is a function"))
(check-that "  ... and nested inside a value too"
            (= 2 (count (re-seq #"helper is a function" crossing))))

;; DELEGATION (`structured-ports` reversing `host-abi`): an endpoint is something a program can
;; hand on. Which endpoint may go where is not symmetric, and the asymmetry is
;; the point rather than an omission -- see the table in `check_sendable_at`.
(check-that "a channel carries a channel endpoint"
            (str/includes? crossing ":chan-through-chan :sent"))
(check-that "a channel carries a bridge"
            (str/includes? crossing ":bridge-through-chan :sent"))
(check-that "a bridge carries a bridge, because its id is the host's own"
            (str/includes? crossing ":bridge-through-bridge :sent"))
;; THE ONE THAT MUST NOT WORK. A channel's ends both live in this heap and the
;; host was never told it exists, so sending one out would hand the host an id
;; naming one of our objects -- and a host that sent it back would be the
;; integer-to-port conversion the whole design forbids.
(check-that "a channel endpoint cannot leave the sandbox"
            (str/includes? crossing "a channel endpoint cannot be sent to the host"))

;; An opaque value is identity (`opaque-values`). It crosses for the same reason a port
;; does: the guest hands over a VALUE and the runtime encodes it, so holding it
;; is the proof, and no decoder for the wire format is reachable from guest
;; code.
(check-that "an opaque value crosses a channel" (str/includes? crossing ":opaque-through-chan :sent"))
(check-that "  ... and a bridge, which is how a capability is handed on"
            (str/includes? crossing ":opaque-through-bridge :sent"))

;; ------------------------------------------------- parking through a value
;;
;; There are two ways into a native: the CALL_NATIVE opcode, and dynamic
;; dispatch through a value (a higher-order position, `apply`, a var). Only the
;; first handled parking, so a parking native reached the second way had its
;; arguments dropped out of the root set while the thread was parked -- and the
;; park was then handed to the unwinder as though it were a thrown error.
(src! "indirect"
      (str "(ns indirect (:require [flint.thread :as t] [flint.port :as p]))\n"
           "(defn main [_]\n"
           "  (let [[tx rx] (p/channel 1 \"probe\")\n"
           "        recv flint.rt/port-receive\n"
           "        _ (t/spawn (fn [] (flint.rt/port-send tx :hello) :sent))\n"
           "        direct (do (t/spawn (fn [] (flint.rt/port-send tx :a) nil)) (flint.rt/port-receive rx))\n"
           "        _ (t/spawn (fn [] (flint.rt/port-send tx :b) nil))\n"
           "        hof (recv rx)\n"
           "        _ (t/spawn (fn [] (flint.rt/port-send tx :c) nil))\n"
           "        applied (apply flint.rt/port-receive [rx])]\n"
           "    (pr-str [(flint.rt/port-receive rx) direct hof applied])))"))
(def indirect (run! (build! "indirect")))
(check-that "a parking native reached through a value returns its value"
            (not (str/includes? indirect "unprintable")))
(check "  ... the same as one reached through the opcode"
       ;; Bindings run in order, so each receive takes what the previous spawn
       ;; sent; the body's receive takes the last. The point is that all four
       ;; are the values sent, whichever path reached the native.
       indirect "[:c :hello :a :b]")

;; ---------------------------------------------------------------- determinism

(src! "sched"
      (str "(ns sched (:require [flint.thread :as t] [flint.port :as p]))\n"
           "(defn worker [tag n out]\n"
           "  (fn [] (dotimes [i n] (p/send out [tag i]) (t/yield)) tag))\n"
           "(defn main [_]\n"
           "  (let [[in out] (p/channel 64)\n"
           "        ws (mapv (fn [tag] (t/spawn (worker tag 4 in))) [:a :b :c])\n"
           "        _ (mapv t/join ws)\n"
           "        _ (p/close in)\n"
           "        got (loop [acc []] (let [v (p/receive out)] (if (nil? v) acc (recur (conj acc v)))))]\n"
           "    (pr-str got)))"))
(def sched-wasm (build! "sched"))
(def sched-runs (vec (repeatedly 5 #(run! sched-wasm))))
(check "the scheduler is deterministic: five runs, one answer"
       (count (distinct sched-runs)) 1)
(check-that "and the threads really did interleave rather than running to completion"
            (let [tags (vec (map first (edn/read-string (first sched-runs))))]
              (and (= 12 (count tags))
                   (not= (take 4 tags) (repeat 4 (first tags))))))

;; ---------------------------------------------------------------- dynamic vars

(src! "dyn"
      (str "(ns dyn (:require [flint.thread :as t]))\n"
           "(def ^:dynamic *level* :info)\n"
           "(defn peek-level [] *level*)\n"
           "(defn main [_]\n"
           "  (let [outer (peek-level)\n"
           "        inner (binding [*level* :debug] (peek-level))\n"
           "        after (peek-level)\n"
           "        child (binding [*level* :trace] (t/join (t/spawn (fn [] (peek-level)))))\n"
           "        sibling (let [w (t/spawn (fn [] (t/yield) (peek-level)))]\n"
           "                  (binding [*level* :warn] (t/yield))\n"
           "                  (t/join w))]\n"
           "    (pr-str {:outer outer :inner inner :after after :child child :sibling sibling})))"))
(check "binding is a stack discipline per GREEN thread, and a spawn inherits a snapshot"
       (run! (build! "dyn"))
       "{:outer :info, :inner :debug, :after :info, :child :trace, :sibling :info}")

(src! "notdyn"
      (str "(ns notdyn)\n(def plain 1)\n"
           "(defn main [_] (binding [plain 2] plain))"))
(let [r (sh "./bin/flint" ":src" d ":fn" "notdyn/main" ":out" "out/th-notdyn.wasm")]
  (check "rebinding a var that is not dynamic is a compile error" (:exit r) 1)
  (check-that "  ... which says how to make it dynamic"
              (str/includes? (:all r) "^:dynamic")))

;; ---------------------------------------------------------------- protocols

(src! "proto"
      (str "(ns proto)\n"
           "(defprotocol Shape (area [s]) (describe [s prefix]))\n"
           "(extend-protocol Shape\n"
           "  :vector (area [s] (* (nth s 0) (nth s 1)))\n"
           "          (describe [s prefix] (str prefix \"vector \" (area s)))\n"
           "  :number (area [s] (* s s))\n"
           "          (describe [s prefix] (str prefix \"number \" (area s))))\n"
           "(def circle (with-meta {:r 2} {`area (fn [s] (* 3 (:r s) (:r s)))}))\n"
           "(defn main [_]\n"
           "  (pr-str [(area [3 4]) (area 5) (area circle)\n"
           "           (describe [3 4] \"a \")\n"
           "           (satisfies? Shape [1 2]) (satisfies? Shape \"no\")\n"
           "           (try (area \"nope\") (catch Throwable e (ex-message e)))]))"))
(def proto (run! (build! "proto")))
(check-that "a protocol dispatches on a built-in kind" (str/includes? proto "[12 25 12"))

;; A MISS DURING LOAD, which is a different phase from the one above and used
;; to give a different answer. `defprotocol` expands to a call on
;; `flint.protocols/protocol-miss` INTO the using namespace, which never
;; requires it -- and the load order is built from `:require` EDGES, so a
;; namespace with no requires sorted BEFORE `flint.protocols` and reached a var
;; that was still nil. The function whose whole job is to explain a missing
;; implementation was unreachable in exactly the phase where the fallback
;; message is useless:
;;
;;   during load  "value is not a function (nil, 3 args)"
;;   after load   "no implementation of pmiss/greet (protocol pmiss/Greet) .."
;;
;; `core-first` pins `flint.protocols` now, and `flint.core` with it, because
;; `protocol-miss` itself calls `kind`.
(src! "pmiss"
      (str "(ns pmiss)
"
           "(defprotocol Greet (greet [x]))
"
           ;; TOP LEVEL, not inside main: this is the whole point of the row.
           "(def at-load
"
           "  (try (greet 1) (catch Throwable e (ex-message e))))
"
           "(defn main [_] (pr-str at-load))"))
(def pmiss (run! (build! "pmiss")))
(check-that "a protocol miss DURING LOAD says which implementation is missing"
            (str/includes? pmiss "no implementation of pmiss/greet"))
(check-that "and does not fall back to the nil-callee message"
            (not (str/includes? pmiss "is not a function")))

;; A REGEX LITERAL AT TOP LEVEL, which is the same defect on ordinary code.
;; `#"a+b"` compiles to `(flint.regex/pattern "a+b")` -- a call into a
;; namespace the source never required -- and the load order is built from
;; `:require` EDGES, so this died as "value is not a function (nil, 1 args)"
;; in any namespace that had no requires.
;;
;; Not pinned: `flint.regex` pulls in `clojure.string` and `flint.nfa`, and a
;; pinned list that grows with every emitted reference is a hand-written prefix
;; of the load order. `implied-requires` derives the edge from the literal
;; instead, in the read pre-pass, which already walks every form.
(src! "relit"
      (str "(ns relit)\n"
           ;; TOP LEVEL and NO requires: both halves matter.
           "(def re #\"a+b\")\n"
           "(defn main [_] (pr-str [(boolean re) (re-find re \"xaabz\")]))"))
(def relit (run! (build! "relit")))
(check-that "a regex literal at top level works with no require"
            (str/includes? relit "[true"))
(check-that "and the pattern it built is the one that was written"
            (str/includes? relit "aab"))
(check-that "  ... and on metadata, which is the main road here"
            (str/includes? proto "12 \"a vector 12\""))
(check-that "a value with no implementation names the protocol"
            (str/includes? proto "(protocol proto/Shape)"))
(check-that "  ... and the value's kind"
            (str/includes? proto "for a value of kind :string"))
(check-that "  ... and how to fix it" (str/includes? proto "proto/area as metadata"))

(if (zero? @fails)
  (println "threads: ok")
  (do (println "threads:" @fails "FAILURES") (System/exit 1)))
