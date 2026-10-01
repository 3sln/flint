(ns hello
  "The smallest possible subject for `bin/check-resources`: no stdlib calls of
  its own, so whatever it costs in memory or CPU is the floor every other
  subject sits on top of. Kept in a directory of its own so `:path` resolves
  to exactly one namespace.")
(defn main [_] "hi")
