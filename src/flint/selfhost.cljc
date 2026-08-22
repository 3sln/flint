(ns flint.selfhost
  "The compiler, as a flint program.

  Compiled by babashka once; from then on flint compiles flint. Input and output
  are strings because that is the whole module ABI: a vector of strings in, a
  string out. The image is binary, so it comes back base64-encoded and the host
  decodes and links it.

  What stays on the host is only what a flint module has no business doing:
  reading files and running `rust-lld`. The compiler itself -- reader, analyzer,
  emitter, macro evaluation -- is all in here."
  (:require [flint.compiler :as compiler]
            [flint.image :as img]
            [flint.reader :as reader]
            [flint.rt]))

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn base64 [bytes]
  (let [n (count bytes)]
    (flint.rt/str-join
     (loop [acc [] i 0]
       (if (>= i n)
         acc
         (let [b0 (nth bytes i)
               b1 (if (< (+ i 1) n) (nth bytes (+ i 1)) 0)
               b2 (if (< (+ i 2) n) (nth bytes (+ i 2)) 0)
               trip (bit-or (bit-shift-left b0 16) (bit-or (bit-shift-left b1 8) b2))
               c0 (nth b64-alphabet (bit-and (bit-shift-right trip 18) 63))
               c1 (nth b64-alphabet (bit-and (bit-shift-right trip 12) 63))
               c2 (if (< (+ i 1) n) (nth b64-alphabet (bit-and (bit-shift-right trip 6) 63)) "=")
               c3 (if (< (+ i 2) n) (nth b64-alphabet (bit-and trip 63)) "=")]
           (recur (conj acc c0 c1 c2 c3) (+ i 3))))))))

(defn compile-to-base64
  "`spec` is EDN: {:sources {ns {:src .. :file ..}} :order [..] :entry ns/fn
  :builtins #{..}}. Returns the base64 image, with native slots left at zero for
  the host to patch (`flint.image/patch-native-slots`)."
  [spec-edn]
  (let [spec (reader/read-one spec-edn)
        result (compiler/compile-image spec)
        builder (:builder result)
        bytes (img/emit builder {})]
    {:image (base64 bytes)
     :natives (img/natives builder)
     :stats (:stats result)}))

(defn main [args]
  (let [spec-edn (first args)
        r (compile-to-base64 spec-edn)]
    ;; One string out, so the shape is: base64 image, newline, then the native
    ;; import order (one per line) that the host needs in order to assign slots.
    (flint.rt/str-join
     (concat [(:image r) "\n"]
             (interpose "\n" (:natives r))))))
