(ns flint.snapshot
  "Capture the whole VM state, and put it back (`doc/decisions/0015`).

  A snapshot is a **copy, not a question**. Every ad-hoc probe answers one
  thing, and can answer it confidently wrong; a snapshot is raw state you
  interpret afterwards, re-interpret when the question changes, and diff against
  another. Capture is a memcpy of the heap plus the runtime's own bookkeeping —
  nothing in it walks the object graph, because a walk that missed an edge would
  produce a snapshot missing an object and you would be debugging the capture.

  Requiring this namespace is what links the unit, so a program that never asks
  for a snapshot does not carry one.

      (snapshot!)        ; => byte count, held runtime-side
      (restore!)         ; => true, or nil if refused

  The host reads and writes the bytes through `flint_snapshot_ptr`,
  `flint_snapshot_alloc` and `flint_snapshot_restore`; see the decision for the
  format's version stamp and why a mismatch is refused rather than read.")

(defn snapshot!
  "Capture the whole VM state. Returns the size in bytes; the bytes stay
  runtime-side until a host asks for them."
  []
  (flint.rt/snapshot))

(defn size
  "Bytes in the snapshot currently held."
  []
  (flint.rt/snapshot-size))

(defn restore!
  "Put the held snapshot back. `nil` if it is refused — a snapshot from another
  runtime layout is rejected by version rather than read as a plausible heap."
  []
  (flint.rt/snapshot-restore))
