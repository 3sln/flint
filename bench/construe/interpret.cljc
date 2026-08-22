
(ns construe.search.seed)

;; A field declares the PARENT ATOMS that land in it; an atom lands in the first
;; field it descends from. This replaced a class-to-field map, which could only
;; ever put an atom in one field because an atom only had one class (4.5.4b) --
;; so an ingredient that is also an allergen was one or the other, never both,
;; and which one depended on who wrote the vocabulary.
(defn field-for [fields span]
  (let [under (set (or (:ancestry span) []))]
    (:name (first (filter (fn [f] (some under (or (:atoms f) []))) fields)))))

;; The unit a scalar field is measured in, as a lookup from unit to field name.
(defn units-of [fields]
  (reduce
    (fn [out f] (if (:unit f) (assoc out (:unit f) (:name f)) out))
    {}
    fields))

;; ADDING TO A FIELD IS A NO-OP WITHOUT ONE, which is what keeps every caller
;; from asking. An atom whose ancestry reaches no declared field is not this
;; parser's business, and the harness would drop it anyway (4.2.9).
(defn put [out name polarity atom]
  (if (nil? name)
    out
    (let [have (get-in out [name polarity] [])]
      (if (some (fn [a] (= a atom)) have)
        out
        (assoc-in out [name polarity] (conj have atom))))))

(defn scalar [out name comparator value]
  (if (nil? name) out (assoc-in out [name comparator] value)))

;; Does this node carry a reading of the given kind, and of the given payload if
;; one is asked for?
(defn reads [node kind payload]
  (boolean
    (some
      (fn [o] (and (= (:kind o) kind) (or (nil? payload) (= (:payload o) payload))))
      (:options node))))

;; The first atom reading on a node, or nil.
(defn atom-in [node]
  (first (filter (fn [o] (= (:kind o) "atom")) (:options node))))

;; ANCESTRY IS SELF-INCLUSIVE, so two readings of the SAME atom share it
;; trivially and that is correct: "no dairy, milk" is one list.
(defn shares-ancestor [a b]
  (boolean (some (set (or (:ancestry a) [])) (or (:ancestry b) []))))

;; ── DOES POLARITY SURVIVE THIS COMMA? ────────────────────────────────────────
;;
;; A comma arrives with two readings and no preference, because the annotator no
;; longer decides: it is a list separator in "no dairy, gluten, or shellfish" and
;; a clause break in "chicken, no dairy", and the same character does both jobs
;; in the same language.
;;
;; THERE ARE TWO KINDS OF EVIDENCE AND EITHER IS ENOUGH. Both scans stop at the
;; first operator, because an operator starts a new scope and what follows
;; belongs to that one rather than to this run.
;;
;; Getting this wrong in the list direction is the failure that matters: three
;; allergies typed the natural way used to come back REQUIRING two of them.
;;
;; ONE -- A COORDINATING CONNECTIVE LATER IN THE SEGMENT. A run that ends in "or"
;; or "and" is a list; one that does not is not evidence either way.
(defn separates-a-list [nodes from]
  (loop [rest-nodes (drop (inc from) nodes)]
    (if (empty? rest-nodes)
      false
      (let [decisive
            (first
              (filter
                (fn [o]
                  (or (= (:kind o) "operator")
                      (and (= (:kind o) "connective")
                           (or (= (:payload o) "and") (= (:payload o) "or")))))
                (:options (first rest-nodes))))]
        (if decisive
          (= (:kind decisive) "connective")
          (recur (rest rest-nodes)))))))

;; TWO -- THE THINGS ON EITHER SIDE ARE THE SAME KIND OF THING. A list is
;; parallel and two clauses are not, and the vocabulary already says which is
;; which: dairy and gluten are both under allergen, chicken is under protein.
;;
;; THE CONNECTIVE TEST ALONE LEFT A HOLE AT TWO ITEMS. "no dairy, gluten, or
;; shellfish" has an "or" and worked; "no dairy, gluten" has nothing after it and
;; came back EXCLUDING dairy and REQUIRING gluten -- the same confident wrong
;; answer in the same unrecoverable direction, one item shorter. Nothing lexical
;; separates it from "no dairy, chicken", which really is two clauses and really
;; does want the chicken, so the discrimination has to come from what the words
;; MEAN, and the ancestry on the span is where that lives.
;;
;; IT CAN ONLY EVER TURN A RESET INTO A CONTINUATION, never the reverse, so it
;; moves strictly toward keeping a negative scope alive across a comma.
(defn same-kind-across [nodes from last-atom]
  (if (neg? last-atom)
    false
    (let [before (atom-in (nth nodes last-atom))]
      (if (nil? before)
        false
        (loop [rest-nodes (drop (inc from) nodes)]
          (if (empty? rest-nodes)
            false
            (let [node (first rest-nodes)]
              (if (some (fn [o] (= (:kind o) "operator")) (:options node))
                false
                (let [after (atom-in node)]
                  (if after
                    (shares-ancestor before after)
                    (recur (rest rest-nodes))))))))))))

;; Pick the reading whose class this workflow actually declares a field for. A
;; prompt saying "stock" in a recipe workflow means broth, and the field table is
;; the only evidence available at this point.
(defn pick-span [fields node]
  (or (first (filter (fn [o] (and (= (:kind o) "atom") (field-for fields o)))
                     (:options node)))
      (first (:options node))))

;; "dairy-free", "nut free": the operator flips what came BEFORE it.
;;
;; A POSTFIX OPERATOR DOES NOT CHANGE WHAT FOLLOWS IT. "dairy-free pasta"
;; excludes dairy and requires pasta; carrying the negation forward excludes the
;; pasta too, which is the same sentence read backwards. Prefix operators scope
;; forward, postfix ones scope back, and that asymmetry is the whole reason arity
;; is on the span.
(defn flip-back [out fields nodes last-atom]
  (if (neg? last-atom)
    out
    (let [target (first (:options (nth nodes last-atom)))
          name (field-for fields target)
          had (get-in out [name :requires])
          ;; put DEDUPES, so there is at most one of these to take out.
          kept (vec (remove (fn [a] (= a (:atom target))) (or had [])))
          cleared (cond
                    (nil? had) out
                    (seq kept) (assoc-in out [name :requires] kept)
                    :else (assoc out name (dissoc (get out name) :requires)))]
      (put cleared name :excludes (:atom target)))))

;; What one node does to the state carried across a clause.
(defn step [fields by-unit nodes state entry]
  (let [i (first entry)
        node (second entry)]
    (if (< (:start node) (:covered state))
      ;; AN OVERLAPPED NODE IS ALREADY SPOKEN FOR. Under all-match "peanut
      ;; butter" arrives as three nodes -- the pair and each half -- so taking
      ;; every one of them would emit peanut and butter alongside the compound.
      ;; Longest-first is the sort order, so a node inside one already read is
      ;; skipped. 2.6 invariant 5 decides the tie the other way for a negative
      ;; scope: the reading that excludes MORE is the safe one, and the compound
      ;; is the broader exclusion.
      ;;
      ;; A HIGH-WATER MARK, NOT A MAP KEYED BY START, and the difference is the
      ;; whole of the rule. Keyed by start, only the half that BEGINS where the
      ;; compound begins was recognised as inside it: "peanut butter" skipped
      ;; peanut at offset 0 and emitted butter at offset 7, so a requirement
      ;; carried a spurious second conjunct and "no peanut butter" excluded plain
      ;; butter along with it -- a buttered roll refused for a peanut allergy.
      state
      (let [span (pick-span fields node)
            kind (:kind span)]
        (cond
          (or (= kind "delimiter") (reads node "delimiter" nil))
          ;; A sentence delimiter never reaches here -- the lattice segmented on
          ;; it. A list separator keeps the polarity; a clause break resets it.
          ;; An ambiguous one asks the sentence.
          (let [list-like (and (reads node "delimiter" "list")
                               (or (not (reads node "delimiter" "clause"))
                                   (separates-a-list nodes i)
                                   (same-kind-across nodes i (:last state))))]
            (if list-like state (assoc state :polarity :requires :exempting nil)))

          (= kind "operator")
          (cond
            (= (:arity span) "postfix")
            (assoc state :out (flip-back (:out state) fields nodes (:last state)))

            (= (:payload span) "except")
            ;; THE EXEMPTION INVERTS THE SCOPE IT LANDS IN, and carries no
            ;; polarity of its own. What follows is carved out of whatever is in
            ;; force: out of an exclusion after "no nuts", out of a requirement
            ;; after "with nuts". A bare "except" with nothing in force is
            ;; meaningless and is read as an exclusion, which is the 2.6
            ;; direction.
            (assoc state :exempting
              (if (= (:polarity state) :excludes) :excludesExcept :requiresExcept))

            :else
            (assoc state
              :polarity (if (= (:payload span) "neg.hard") :excludes :requires)
              :exempting nil))

          (= kind "comparator") (assoc state :comparator (:payload span))
          (= kind "number") (assoc state :number (:value span))

          (= kind "unit")
          (let [name (get by-unit (:payload span))]
            (assoc
              ;; A bare number with a unit and no comparator is an equality:
              ;; "4 servings" means four, not "at most four".
              (if (and name (not (nil? (:number state))))
                (assoc state :out
                  (scalar (:out state) name (or (:comparator state) "eq") (:number state)))
                state)
              :comparator nil
              :number nil))

          (= kind "atom")
          ;; An exemption stays in force across a list, so "no nuts except
          ;; cashews or almonds" carves out both.
          (assoc state
            :out (put (:out state) (field-for fields span)
                      (or (:exempting state) (:polarity state)) (:atom span))
            :covered (max (:covered state) (:end node))
            :last i)

          ;; "hedge", "quantifier", "connective" and "unknown" carry no
          ;; constraint in the seed. A hedge deliberately does NOT soften
          ;; anything: 5.4 says a hedged negation on a protected atom stays a
          ;; hard exclusion, and the cheapest way for a seed to honour that is to
          ;; ignore hedges entirely.
          :else state)))))

;; A trailing number and unit-less comparator: "under 30" in a workflow with
;; exactly one scalar field is unambiguous, and refusing to read it makes the
;; parser look broken on the most common phrasing there is.
(defn trailing [fields state]
  (if (and (not (nil? (:number state))) (:comparator state))
    (let [scalars (map :name (filter (fn [f] (= (:family f) "scalar")) fields))]
      (if (= (count scalars) 1)
        (scalar (:out state) (first scalars) (:comparator state) (:number state))
        (:out state)))
    (:out state)))

(defn read-clause [fields by-unit out clause]
  (let [nodes (vec (:nodes clause))
        ;; Polarity is sticky within a segment: "no dairy or gluten" excludes
        ;; both, because the operator scopes over what follows rather than over
        ;; one word.
        start {:out out :polarity :requires :comparator nil :number nil
               :last -1 :exempting nil :covered -1}
        ended (reduce
                (fn [state entry] (step fields by-unit nodes state entry))
                start
                (map-indexed (fn [i node] [i node]) nodes))]
    (trailing fields ended)))

(defn interpret [input]
  (let [fields (or (:fields input) [])
        by-unit (units-of fields)]
    (reduce
      (fn [out clause] (read-clause fields by-unit out clause))
      {}
      (or (:clauses input) []))))
