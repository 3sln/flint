(ns capability
  "Capabilities as host-minted opaque values (`doc/decisions/0021`, `0022`).

  The property under test is the one 0022 records as the hazard the whole
  generalisation creates: because guest code CAN mint opaque values, authority
  can never be `is it opaque`. It is the host recognising this specific object
  in its own grant table, and nothing else.

  Nothing here names a capability to the runtime. `p/open` forwards whatever it
  is given and the host decides, which is the point of the cutover: `open`
  takes arguments, and a capability is one of the things those arguments can
  be."
  (:require [flint.port :as p]
            [flint.core :refer [opaque]]))

(defn try-open [name opts]
  (try (do (p/open name opts) "opened") (catch Throwable e (ex-message e))))

(defn main [_]
  (pr-str
   {:no-capability      (try-open "fs" {})
    ;; Minting one is free -- any program can write this line -- so it cannot
    ;; be what grants anything. It reaches the host with a host id of 0.
    :with-a-forged-one  (try-open "fs" {:capability (opaque "fs")})
    ;; A forgery that guesses a plausible id. It is still 0 on the wire: the
    ;; guest cannot set that field, which is the entire mechanism.
    :with-a-guessed-id  (try-open "fs" {:capability (opaque "fs") :id 5})
    :ungranted          (try-open "net" {:capability (opaque "net")})}))

(defn granted
  "The POSITIVE case: a host that allows, an open that returns a real port, and
  a message that reaches the host over it.

  Asserted as the round trip rather than as \"open returned something\", because
  a port that cannot carry a message is not a granted capability."
  [_]
  (let [port (p/open "fs")]
    (p/send port "ping")
    (str "opened " (p/port? port))))
