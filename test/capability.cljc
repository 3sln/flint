(ns capability
  "Capabilities as host-minted opaque values (`doc/decisions/0021`, `0022`).

  The property under test is the one 0022 records as the hazard the whole
  generalisation creates: because guest code CAN mint opaque values, authority
  can never be `is it opaque`. It is the host recognising this specific object
  in its own grant table, and nothing else."
  (:require [flint.port :as p]))
(defn try-open [name opts]
  (try (do (p/open name opts) "opened") (catch Throwable e (ex-message e))))
(defn main [_args caps]
  (pr-str
   {:no-capability        (try-open "fs" {})
    :with-the-real-one    (try-open "fs" {:capability (:fs caps)})
    ;; Minting one is free, so it cannot be what grants anything.
    :with-a-forged-one    (try-open "fs" {:capability (opaque "fs")})
    ;; And the right kind of capability for the wrong name.
    :with-the-wrong-one   (try-open "fs" {:capability (:http caps)})
    :ungranted            (try-open "net" {:capability (:fs caps)})}))
