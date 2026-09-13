(ns flint.io
  "Reading and writing as PROTOCOLS, not as capabilities.

  A program that needs to read something does not thereby need a filesystem.
  These four names are what a caller hands in when it wants a program to have
  input or produce output, and the whole point of them is that the thing handed
  in is ABSTRACT: a function, a port, an in-memory buffer, or a real file the
  caller opened on its own authority. The program cannot tell, and must not
  need to.

  This is what `flint.sdk` uses to give an inner sandbox IO without granting it
  any (`DECISIONS.md#flint-sdk`). A sandbox constructed with no sources and no
  sinks can run its logic and reach nothing, which is what confined should mean
  by default rather than something a host has to remember to withhold -- the
  same inversion `DECISIONS.md#ports-are-the-hosts` made for ports.

  ## Four, in two pairs

  |        | text          | binary          |
  |--------|---------------|-----------------|
  | in     | `TextSource`  | `BinarySource`  |
  | out    | `TextSink`    | `BinarySink`    |

  Text is characters and binary is bytes, and they are separate protocols
  rather than one with an encoding argument because a value that is sometimes
  a string and sometimes bytes is two things wearing one name -- the same
  reason `flint.sys.slurp` has both `slurp` and `slurp-bytes`.

  ## Closing is not here

  None of the four can be closed, and that is deliberate. The caller opened
  whatever this wraps and is the only one who knows what closing it would
  mean; a program handed a sink should no more be able to close the caller's
  file than to open one. A provider that needs closing exposes it on its own
  concrete thing."
  (:require [flint.bytes :as bytes]))

;; ---------------------------------------------------------------- the four

(defprotocol TextSource
  "Characters, in."
  (read-text [src] [src n]
    "The next `n` characters, or everything remaining when `n` is absent.
    `nil` at the end -- which is different from `\"\"`, a read that happened to
    return nothing and may return more later."))

(defprotocol TextSink
  "Characters, out."
  (write-text [sink s]
    "Write `s`. Answers the sink, so writes compose."))

(defprotocol BinarySource
  "Bytes, in."
  (read-bytes [src] [src n]
    "The next `n` bytes, or everything remaining when `n` is absent. `nil` at
    the end, for the reason `read-text` gives."))

(defprotocol BinarySink
  "Bytes, out."
  (write-bytes [sink bs]
    "Write `bs`. Answers the sink, so writes compose."))

;; --------------------------------------------------- making one from a fn
;;
;; By METADATA, which is how `defprotocol` dispatches before it looks at a
;; value's kind. That matters here: it means an implementation is a value
;; carrying functions, so a caller can hand in behaviour without defining a
;; type and without the receiver being able to tell what is behind it.

(defn text-source
  "A `TextSource` from `f`: `(f n)` for a sized read, `(f nil)` for the rest."
  [f]
  (with-meta {:flint.io/kind :text-source}
    {'flint.io/read-text (fn ([src] (f nil)) ([src n] (f n)))}))

(defn text-sink
  "A `TextSink` from `f`, called with each string written."
  [f]
  (with-meta {:flint.io/kind :text-sink}
    {'flint.io/write-text (fn [sink s] (f s) sink)}))

(defn binary-source
  "A `BinarySource` from `f`: `(f n)` for a sized read, `(f nil)` for the rest."
  [f]
  (with-meta {:flint.io/kind :binary-source}
    {'flint.io/read-bytes (fn ([src] (f nil)) ([src n] (f n)))}))

(defn binary-sink
  "A `BinarySink` from `f`, called with each byte string written."
  [f]
  (with-meta {:flint.io/kind :binary-sink}
    {'flint.io/write-bytes (fn [sink bs] (f bs) sink)}))

;; ------------------------------------------------------- the obvious ones

(defn string-source
  "A `TextSource` over `s`, which is the one every test wants."
  [s]
  (let [at (atom 0)]
    (text-source
     (fn [n]
       (let [i @at
             end (if n (min (count s) (+ i n)) (count s))]
         (when (< i (count s))
           (reset! at end)
           (subs s i end)))))))

(defn string-sink
  "A `TextSink` collecting into an atom, which `collected` reads back."
  []
  (let [acc (atom [])]
    (with-meta {:flint.io/kind :text-sink :flint.io/acc acc}
      {'flint.io/write-text (fn [sink s] (swap! acc conj s) sink)})))

(defn bytes-source
  "A `BinarySource` over `bs`."
  [bs]
  (let [at (atom 0)
        n* (bytes/size bs)]
    (binary-source
     (fn [n]
       (let [i @at
             end (if n (min n* (+ i n)) n*)]
         (when (< i n*)
           (reset! at end)
           (bytes/slice bs i end)))))))

(defn bytes-sink
  "A `BinarySink` collecting into an atom, which `collected` reads back."
  []
  (let [acc (atom [])]
    (with-meta {:flint.io/kind :binary-sink :flint.io/acc acc}
      {'flint.io/write-bytes (fn [sink bs] (swap! acc conj bs) sink)})))

(defn collected
  "Everything written to a `string-sink` or `bytes-sink`, in order.

  Only these two: a sink built from a function has nowhere to collect into, and
  answering `nil` for one would read as \"nothing was written\"."
  [sink]
  (if-let [acc (:flint.io/acc sink)]
    @acc
    (throw (ex-info "this sink does not collect; only string-sink and bytes-sink do"
                    {:kind (:flint.io/kind sink)}))))
