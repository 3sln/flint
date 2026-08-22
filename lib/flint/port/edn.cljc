(ns flint.port.edn
  "The EDN codec for a host port.

      (:require [flint.port :as p] [flint.port.edn :as edn])
      (p/open \"thing\" {:codec edn/codec})

  EDN is flint's own notation, so this is the format that loses nothing: every
  value a port may carry round-trips, keywords, symbols and sets included."
  (:require [clojure.edn :as edn]))

(defn encode [v _opts] (pr-str v))
(defn decode [s _opts] (edn/read-string s))

(def codec {:format :edn :encode encode :decode decode})
