(ns bytes
  "Byte strings (`doc/decisions/0024`).

  The same two tiers as text: flat below the threshold, a shallow tree above
  it. What has to be true is that the TIER IS INVISIBLE -- a flat and a tree
  holding the same bytes are equal, hash alike, index alike, and are one map
  key. Every check below is written to cross that boundary, because a byte
  string that works only while it is small passes a careless test."
  (:require [flint.bytes :as b]))

(def ^:private piece "0123456789abcdef")

(defn- big
  "Past FLAT_MAX (1024), so this is a tree and not a copy."
  []
  (reduce b/cat (b/of-string "") (mapv (fn [_] (b/of-string piece)) (range 200))))

(defn caught [f] (try (f) (catch Exception e (str "!" (ex-message e)))))

(defn main [_]
  (let [a (b/of-string "hello ")
        c (b/cat a (b/of-string "byte world"))
        b (big)]
    (pr-str
     {;; --- what it is -----------------------------------------------------
      :bytes?        [(bytes? a) (bytes? "no") (bytes? [1 2]) (bytes? nil)]
      :string-isnt   (bytes? (b/to-string a))
      ;; --- flat tier ------------------------------------------------------
      :count         (b/size a)
      :at            [(b/at a 0) (b/at a 5)]
      :cat           (b/to-string c)
      :cat-count     (b/size c)
      :slice         (b/to-string (b/slice c 6 10))
      :empty         (b/size (b/of-string ""))
      ;; --- tree tier ------------------------------------------------------
      :big-count     (b/size b)
      :big-ends      [(b/at b 0) (b/at b 3199)]
      ;; Indexing has to descend, so a boundary between children is the case
      ;; that catches an off-by-one in the walk.
      :big-boundary  [(b/at b 15) (b/at b 16)]
      :big-slice     (b/to-string (b/slice b 16 32))
      ;; --- the tier is invisible ------------------------------------------
      :eq-across     (= (b/slice b 0 6) (b/of-string "012345"))
      :ne            (= (b/of-string "a") (b/of-string "b"))
      :ne-length     (= (b/of-string "ab") (b/of-string "abc"))
      :as-key        (get {(b/slice b 0 6) :found} (b/of-string "012345"))
      ;; --- the generic collection surface ---------------------------------
      :nth           (nth c 0)
      :count-generic (count c)
      :get-generic   (get c 1)
      ;; --- conversions ----------------------------------------------------
      :roundtrip     (b/to-vector (b/of-string "hi"))
      :vec->bytes    (b/to-string (b/of-vector [104 105]))
      :utf8          (b/size (b/of-string "é"))
      ;; --- refusals -------------------------------------------------------
      :past-end      (caught (fn [] (b/at a 99)))
      :not-utf8      (caught (fn [] (b/to-string (b/of-vector [255 254]))))})))
