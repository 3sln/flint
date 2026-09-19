(ns flint.host
  "Asking the host for something, and the capability that gates it.

  A sandbox has no ambient anything. It holds logic, and the only way out is a
  port the host passed in (`DECISIONS.md#ports-are-the-hosts`). `flint.port/open` is one shape
  of asking -- \"lend me a port called this\" -- and it is the shape whose answer
  has to be a port, because a port is granted by id and never encoded.

  `request` is the general shape (`DECISIONS.md#workspace-capabilities` step 7). It asks for
  something and gets a VALUE back: a number, a map, nil, whatever the host chose
  to answer. Same system port, same park, same refusal.

  **Guarded.** Only a workspace granted `:host` may reference `request`, and
  that is checked where the reference is written, against the grants its own
  project declared (`DECISIONS.md#workspace-capabilities` step 8). The check emits nothing -- a
  guard is a compile-time construct and costs a run nothing.

  What it buys is coarse and worth being precise about: it answers \"may this
  workspace talk to the embedder AT ALL\", and the host answers the specific
  question per request, as it already does. Both are wanted. Without the guard a
  library nobody vetted opens a dialogue with the embedder silently; without the
  host's own check the guard would be all that stands between a declaration and
  the world. Neither is a substitute for the other.

  And what it does NOT buy: a workspace holding `:host` can wrap `request` in a
  function of its own and hand that to anyone. Authority is not transitive in
  name and is entirely transitive in effect. A guard makes the set of workspaces
  that ask DIRECTLY small and declared; it does not confine what they pass on."
  (:require [flint.wire :as wire]))

(defn ^{:flint/capabilities-guard [:host]} request
  "Ask the host for `what`, forwarding `args` verbatim, and return its answer.

      (request \"config\")
      (request \"config\" {:for :startup})

  Blocking, like every other port operation: the calling green thread parks and
  the rest of the sandbox keeps running, so a host may answer this while serving
  other calls. It throws `SecurityException` if the host refuses, and the same
  if this sandbox was given no system port -- saying so is more honest than
  parking for ever on an answer that cannot come.

  Anything in `args` that is an opaque value (`DECISIONS.md#opaque-values`) crosses
  carrying the host id it was ISSUED with, so a host recognises what it lent and
  nothing else. The runtime takes no view of what any of it means."
  ([what] (request what nil))
  ([what args]
   ;; BOTH HALVES ARE FLINT (`DECISIONS.md#the-codec-is-guest-code`). The
   ;; payload `[what & args]` is written here, and the answer comes back as a
   ;; live reader -- live meaning the bytes crossed the boundary -- which is
   ;; read here too. The runtime neither writes nor reads the format.
   ;;
   ;; `[what]` for the no-args call and `[what args]` otherwise, which is the
   ;; shape the host has always received: the builtin used to build it by
   ;; collecting its own arguments, and a host that routes an `open` routes
   ;; this the same way.
   ;;
   ;; A REFUSAL THROWS INSIDE THE BUILTIN, so there is no reader to read and
   ;; nothing here has to distinguish one -- which is the same reason the
   ;; runtime still wraps an answer: an answer may be any value at all, nil
   ;; included, so "answered" cannot be read off the value.
   ;; BOUND FIRST, THEN READ, which is how `flint.port/receive` does it and not
   ;; a matter of taste: `flint.rt/request` PARKS, and a parking call sitting
   ;; in an argument position is re-entered by the rewind with a partly built
   ;; frame under it.
   (let [r (flint.rt/request what (wire/encode (if (nil? args) [what] [what args])))]
     (wire/read-from r))))

(defn ^{:flint/capabilities-guard [:host]} ask
  "`request`, answering nil instead of throwing when the host refuses.

  For the case where not having the thing is an ordinary outcome -- an optional
  setting, a feature the embedder may not offer -- and writing the try around
  every call would be noise. A refusal for any OTHER reason is not distinguished
  here; if that matters, use `request` and read the exception."
  ([what] (ask what nil))
  ([what args]
   ;; THROUGH `request`, NOT THE BUILTIN. It used to call `flint.rt/request`
   ;; itself, which was harmless while the builtin took values and became a
   ;; crash the moment it took an ENCODING: a one-argument call left the
   ;; writer slot unread and the runtime indexed past the arguments it was
   ;; given (`DECISIONS.md#the-codec-is-guest-code`).
   (try (request what args)
        (catch SecurityException _ nil))))
