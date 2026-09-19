(ns hostreq
  "The REQUEST/RESPONSE half of the host ABI, which nothing else exercises.

  `flint.host/request` is the other thing a sandbox can ask its host for: not a
  port, but an ANSWER (`DECISIONS.md#workspace-capabilities` step 7). The
  runtime raises an `EV_REQUEST` and parks the calling thread; the host answers
  with encoded bytes and the thread wakes with the value.

  Until this file, no host in the tree answered one -- `bin/check-builtin-coverage`
  says as much about `flint/request`, and neither CLI handles the event. So the
  path was reachable from guest code and served by nobody, which means the two
  runtime codec uses it still carries could not be moved with anything watching
  (`DECISIONS.md#the-codec-is-guest-code`).

  ## What this transcript asserts, and what it does not

  It prints what CROSSED and what came BACK, and no internal ids. The token a
  request carries is an opaque handle -- the host echoes it and never reads it
  -- so three independent implementations agreeing on its numeric value is not
  part of the contract, and asserting it would pin a waiter index rather than a
  behaviour."
  (:require [flint.host :as host]))

(defn main [_]
  (let [;; A plain request, answered.
        a (host/request "clock" ["utc"])
        ;; A SECOND one, to show the token is not reused while the first is
        ;; outstanding and that the answer goes to the thread that asked.
        b (host/request "clock" ["local"])
        ;; REFUSED is an exception, not a nil: `ask` is the wrapper that turns
        ;; it into nil, and the difference is the whole reason both exist.
        c (try (host/request "nope" []) (catch SecurityException e :refused))
        d (host/ask "nope" [])]
    (str "[" (pr-str a) " " (pr-str b) " " (pr-str c) " " (pr-str d) "]")))
