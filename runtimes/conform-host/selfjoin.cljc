(ns selfjoin
  "A thread joining ITSELF."
  (:require [flint.thread :as th]))

(defn main [_]
  (let [me (th/self)]
    (str "joined=" (pr-str (th/join me)))))
