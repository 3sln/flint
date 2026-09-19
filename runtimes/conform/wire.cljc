(ns wire
  "The wire WRITER primitives, on every runtime
  (`DECISIONS.md#the-codec-is-guest-code`).

  ## What this can check, and what it cannot

  It cannot check the BYTES. A writer's contents are only observable by sending
  it on a bridge, and a conformance program has no host to send to -- that is
  the whole point of the type: a guest cannot ask for the bytes, because a guest
  that could would concatenate them with bytes of its own and forge a `K_PORT`
  tag. Byte-for-byte agreement with the runtime encoder is checked instead by
  `test/host_abi.mjs`, which has a host and compares the two encodings directly.

  What it CAN check is everything else, and that turned out to be the half with
  no coverage at all: the primitives exist on the JVM and the CLR, were at
  parity the day they were added, and nothing had ever called them. A primitive
  that throws on one runtime and not another is exactly the divergence this file
  is for, and it is invisible to a test that only runs on native."
  (:require [clojure.string :as str] [flint.bytes :as bytes] [flint.wire :as wire]
            [flint.table :as t] [flint.port :as port]))

(defn- kind-of
  "The exception kind, or `:no-throw`. Comparable across runtimes, where a
  message is not always."
  [f]
  (try (f) :no-throw (catch Exception e (flint.rt/ex-kind e))))

(defn- chains
  "EVERY PRIMITIVE ANSWERS THE WRITER, so an encoder reads as a chain. Checked
  by identity rather than by `=`: a writer is not a value and has no equality,
  so `=` would be asking a question the type does not answer.

  A WRITER EACH, because one writer holds ONE message. Written first as a single
  writer taking fifteen values in a row, which the format enforcement then
  refused -- correctly, and the test was the thing that was wrong."
  []
  (let [one (fn [f] (let [w (flint.rt/wire-writer)] (identical? w (f w))))]
    [(one (fn [w] (flint.rt/wire-nil w)))
     (one (fn [w] (flint.rt/wire-bool w true)))
     (one (fn [w] (flint.rt/wire-int w 1)))
     (one (fn [w] (flint.rt/wire-double w 1.5)))
     (one (fn [w] (flint.rt/wire-str w "s")))
     (one (fn [w] (flint.rt/wire-kw w nil "k")))
     (one (fn [w] (flint.rt/wire-kw w "ns" "k")))
     (one (fn [w] (flint.rt/wire-sym w nil "s")))
     (one (fn [w] (flint.rt/wire-vec w 0)))
     (one (fn [w] (flint.rt/wire-list w 0)))
     (one (fn [w] (flint.rt/wire-set w 0)))
     (one (fn [w] (flint.rt/wire-map w 0)))
     ;; A wrapper opens two values, so it chains and leaves the message open.
     (one (fn [w] (flint.rt/wire-meta w)))
     (one (fn [w] (flint.rt/wire-tagged w)))
     (one (fn [w] (flint.rt/wire-bytes w (flint.rt/str->b "b"))))]))

(defn- refusals
  "A NON-WRITER IS REFUSED BY NAME, on every primitive that takes one. The kind
  has to agree across runtimes, or a guest encoder that catches one of these
  behaves differently depending on where it runs."
  []
  [(kind-of (fn [] (flint.rt/wire-int 7 1)))
   (kind-of (fn [] (flint.rt/wire-str nil "s")))
   (kind-of (fn [] (flint.rt/wire-vec "not a writer" 1)))
   ;; The ARGUMENT is checked too, not only the writer.
   (kind-of (fn [] (flint.rt/wire-int (flint.rt/wire-writer) "not an int")))
   (kind-of (fn [] (flint.rt/wire-str (flint.rt/wire-writer) 42)))
   (kind-of (fn [] (flint.rt/wire-kw (flint.rt/wire-writer) nil 42)))
   ;; A NEGATIVE COUNT is refused rather than written as a huge unsigned one,
   ;; which is what a reader would see if it were let through.
   (kind-of (fn [] (flint.rt/wire-vec (flint.rt/wire-writer) -1)))
   ;; THE TWO THAT MATTER: neither takes an id, so neither can be handed one.
   (kind-of (fn [] (flint.rt/wire-port (flint.rt/wire-writer) 3)))
   (kind-of (fn [] (flint.rt/wire-opaque (flint.rt/wire-writer) 3)))
   ;; A CHANNEL END IS NOT ENCODABLE, on any runtime. `check-sendable` refuses
   ;; one sent to the host, but that check runs on a VALUE and never sees an
   ;; encoding -- so without this the guest encoder is a way around it, and the
   ;; id of an object the host was never told about ends up in a message the
   ;; host reads. A bridge cannot be made here to sit beside it; the host-side
   ;; half of this row is `wireport` in `test/host_abi.mjs`, which checks that
   ;; a bridge port still encodes.
   (let [[a b] (port/channel "internal")
         k (kind-of (fn [] (flint.rt/wire-port (flint.rt/wire-writer) a)))]
     (port/close a) (port/close b) k)])

(defn- structure
  "THE WRITER ENFORCES THE FORMAT, identically on every runtime.

  A streaming encoder that lets the guest assert structure lets it put four raw
  bytes where a value is due -- and four bytes at a value position are a tag and
  its payload on the far side, where `0000000f` is `K_PORT` and an id. So the
  writer refuses instead, and refuses the same way everywhere or a program that
  catches one of these behaves differently depending on where it runs."
  []
  [;; The message was complete after the first value.
   (kind-of (fn [] (-> (flint.rt/wire-writer)
                       (flint.rt/wire-int 1)
                       (flint.rt/wire-int 2))))
   ;; A vector of two takes exactly two, and a third has nowhere to go.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-vec 2)
                       (flint.rt/wire-int 1) (flint.rt/wire-int 2)
                       (flint.rt/wire-int 3))))
   ;; A MAP OF ONE PAIR IS TWO VALUES. A writer counting pairs would call this
   ;; complete after the key and refuse the value.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-map 1)
                       (flint.rt/wire-kw nil "a") (flint.rt/wire-int 1))))
   ;; Nesting closes inward: the inner vector completes, then the outer.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-vec 2)
                       (flint.rt/wire-int 1)
                       (flint.rt/wire-vec 1) (flint.rt/wire-int 2))))
   ;; A wrapper takes TWO values, the metadata and the value.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-meta)
                       (flint.rt/wire-map 0) (flint.rt/wire-int 1))))
   ;; --- A TABLE, whose ROW COUNT sits after values ------------------------
   ;;
   ;; The case the enforcement was built for. Everywhere else a count comes
   ;; first and could be checked by position alone; here a count has to be
   ;; legal in the middle of a message and illegal one value either side of
   ;; that, or a guest writes four bytes where a VALUE is due -- and four bytes
   ;; there are a tag and its payload on the far side, `0000000f` being
   ;; `K_PORT` and an id it was never given.
   ;;
   ;; Well formed: one column, named and typed, then one row.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-table 1)
                       (flint.rt/wire-kw nil "id") (flint.rt/wire-kw nil "int")
                       (flint.rt/wire-table-rows 1) (flint.rt/wire-int 7))))
   ;; The count BEFORE the column pairs are done.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-table 1)
                       (flint.rt/wire-table-rows 1))))
   ;; The count where no table is open at all -- the forgery, written plainly.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-vec 1)
                       (flint.rt/wire-table-rows 2))))
   ;; A VALUE where the count is due: the other half of the same rule.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-table 1)
                       (flint.rt/wire-kw nil "id") (flint.rt/wire-kw nil "int")
                       (flint.rt/wire-int 1))))
   ;; A TABLE OF NO COLUMNS still takes its row count, and is complete after
   ;; it: the marker frame is there even when no pairs are.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-table 0)
                       (flint.rt/wire-table-rows 0) (flint.rt/wire-int 1))))
   ;; --- THE MARKER IS NOT REACHABLE BY ARITHMETIC ------------------------
   ;;
   ;; A fixnum is 48 bits, SIGN-EXTENDED, and the marker for "a row count is
   ;; due" is a negative frame. So a count of `2^47 + 1` opened a frame that
   ;; read back negative while writing `1` to the wire -- the reader told to
   ;; expect one value, the writer willing to take a raw four-byte COUNT in its
   ;; place. `0f 00 00 00` at a value position is `K_PORT` and the start of an
   ;; id: a capability minted from an integer, through the machinery added to
   ;; stop exactly that.
   ;;
   ;; The bound is what closes it, and it is not arbitrary -- `u32` is what the
   ;; format writes. These rows are the exploit, kept: each must refuse on
   ;; every runtime, and refuse the same way.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-vec 140737488355329))))
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-vec 140737488355329)
                       (flint.rt/wire-table-rows 15))))
   ;; A COUNT PAST `u32` at all, which is the rule stated plainly: the frame
   ;; and the four bytes written must mean the same thing.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-vec 4294967296))))
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-map 4294967296))))
   ;; A MAP OPENS TWICE ITS COUNT, so its doubling is the other way to overflow.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-map 4611686018427387904))))
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-table 4294967296))))
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-table 1)
                       (flint.rt/wire-kw nil "id") (flint.rt/wire-kw nil "int")
                       (flint.rt/wire-table-rows 4294967296))))
   ;; A BIG BUT LEGAL PRODUCT OPENS. Two columns and four billion rows is
   ;; 8.6e9 cells, which is under the cell bound, so it is accepted here and
   ;; refused at `send` for being unfinished -- the refusal has to be about the
   ;; structure and not about the number.
   ;;
   ;; The product bound itself is NOT reachable from flint: exceeding it needs
   ;; a column count in the billions, and a table cannot reach its row count
   ;; until twice that many values have actually been written. It is kept as
   ;; the arithmetic guard on a frame built by multiplying, and this row is
   ;; here to say that the guard does not fire on a table a program could
   ;; plausibly mean.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-table 2)
                       (flint.rt/wire-kw nil "a") (flint.rt/wire-kw nil "int")
                       (flint.rt/wire-kw nil "b") (flint.rt/wire-kw nil "int")
                       (flint.rt/wire-table-rows 4294967295))))
   ;; And a count at the bound itself opens, for the same reason.
   (kind-of (fn [] (-> (flint.rt/wire-writer) (flint.rt/wire-vec 4294967295))))])

(defn- not-a-value
  "A WRITER IS NOT A VALUE: no kind, and it is not any of the things a guest
  might mistake it for. `kind` answering something stable matters because
  protocol dispatch reads it."
  []
  (let [w (flint.rt/wire-writer)]
    [(boolean (some? w))
     (vector? w) (map? w) (set? w) (string? w) (fn? w)]))

(defn- decodes
  "THE READER, over bytes built BY HAND. A conformance program cannot get bytes
  out of a writer -- that is the writer working as designed -- but it can make
  an encoding itself and read it, which exercises every reading primitive and
  pins the exact value each runtime produces.

  Little-endian throughout, matching the writer."
  []
  (let [dec (fn [v] (try (pr-str (wire/decode (bytes/of-vector v)))
                         (catch Exception e (flint.rt/ex-kind e))))]
    [;; K_INT 42
     (dec [3 42 0 0 0 0 0 0 0])
     ;; K_INT -1, which is every byte set: a decoder that read it unsigned
     ;; would answer 18446744073709551615 and agree with nobody.
     (dec [3 255 255 255 255 255 255 255 255])
     ;; K_STRING "hi"
     (dec [5 2 0 0 0 104 105])
     ;; K_KEYWORD, ABSENT namespace (ffffffff), name "op"
     (dec [6 255 255 255 255 2 0 0 0 111 112])
     ;; K_KEYWORD, namespace "n", name "a" -- absent is not empty, and this is
     ;; the row that tells the two apart.
     (dec [6 1 0 0 0 110 1 0 0 0 97])
     ;; K_VECTOR of two ints
     (dec [8 2 0 0 0 3 1 0 0 0 0 0 0 0 3 2 0 0 0 0 0 0 0])
     ;; K_NIL, K_TRUE, K_FALSE
     (dec [0]) (dec [1]) (dec [2])
     ;; K_BYTES "ab" -- printed as a count, since bytes have no readable form
     (str (bytes/size (wire/decode (bytes/of-vector [14 2 0 0 0 97 98]))))
     ;; TRUNCATED: an int tag with no payload. Read as a short value it would
     ;; build something nobody sent, so it throws.
     (dec [3 1 2])
     ;; TRAILING: a whole value and then a stray byte. The sender and this
     ;; disagree about the message, which is not something to ignore.
     (dec [0 0])
     ;; UNKNOWN TAG.
     (dec [99])
     ;; THE SECURITY CASE: a port tag in bytes the PROGRAM supplied. A reader
     ;; made from a guest's own bytes may not mint, so this is refused -- the
     ;; rule `decode_guest` enforced by refusing tags, as a flag on the reader.
     (dec [15 7 0 0 0])
     ;; And an opaque, for the same reason.
     (dec [16 7 0 0 0 0 0 0 0 0 0 0 0])
     ;; A TABLE, READ FOR ITS CONTENTS rather than printed. `pr-str` of a
     ;; table is `#<unprintable>` unless something in the program keeps
     ;; `flint.table`'s printer alive, and a row that prints the same nothing
     ;; on all three runtimes agrees without checking anything. Reading the
     ;; schema and the column says what actually arrived.
     (let [tb (wire/decode (bytes/of-vector [18, 1, 0, 0, 0, 6, 255, 255, 255, 255, 2, 0, 0, 0, 105, 100, 6, 255, 255, 255, 255, 3, 0, 0, 0, 105, 110, 116, 2, 0, 0, 0, 3, 1, 0, 0, 0, 0, 0, 0, 0, 3, 2, 0, 0, 0, 0, 0, 0, 0]))
           s (t/table-schema tb)]
       (str (pr-str (t/columns s)) " " (pr-str (t/types s)) " "
            (pr-str (t/column tb :id))))
     ;; NO ROWS is a table, not an absence of one.
     (let [tb (wire/decode (bytes/of-vector [18, 1, 0, 0, 0, 6, 255, 255, 255, 255, 2, 0, 0, 0, 105, 100, 6, 255, 255, 255, 255, 3, 0, 0, 0, 105, 110, 116, 0, 0, 0, 0]))]
       (str (count tb) " " (pr-str (t/columns (t/table-schema tb)))))
     ;; THE FORGERY FROM THE READING SIDE: a row count written one value early,
     ;; which is what the writer refuses to emit. Its four bytes land where a
     ;; column TYPE was due, so they are read as a tag -- and the read fails
     ;; rather than quietly building a table nobody sent.
     (dec [18, 1, 0, 0, 0, 6, 255, 255, 255, 255, 2, 0, 0, 0, 105, 100, 2, 0, 0, 0, 6, 255, 255, 255, 255, 3, 0, 0, 0, 105, 110, 116, 3, 1, 0, 0, 0, 0, 0, 0, 0, 3, 2, 0, 0, 0, 0, 0, 0, 0])]))

(defn main [_]
  (pr-str {:chains (chains)
           :refusals (refusals)
           :not-a-value (not-a-value)
           :structure (structure)
           :decodes (decodes)}))
