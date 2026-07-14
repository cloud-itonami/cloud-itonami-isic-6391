(ns newswire.registry
  "Pure-function distribution + correction/retraction record
  construction -- an append-only wire-service book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a distribution or correction/
  retraction reference number -- every bureau/wire-service assigns its
  own reference format. This namespace does NOT invent one; it builds a
  client-scoped sequence number and validates the record's required
  fields, the same honest, non-fabricating discipline every sibling
  actor's registry namespace uses.

  `embargo-violated?` is a genuinely NEW check kind for this fleet: a
  pure ground-truth TEMPORAL check (an ISO-8601 instant comparison)
  rather than a structural/syntactic-format check like `telecom.
  registry/e164-invalid-format?` (`cloud-itonami-isic-6190`),
  `wirelesstelecom.registry/msisdn-invalid-format?` (`cloud-itonami-
  isic-6120`) or `satcom.registry/satellite-number-invalid-format?`
  (`cloud-itonami-isic-6130`) -- those recompute whether an identifier
  is well-formed; this recomputes whether the CURRENT moment is still
  before a story's own recorded embargo instant. See this repo's own
  `docs/adr/0001-architecture.md`.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real subscriber wire feed. It builds the RECORD a bureau
  would keep, not the act of actually pushing bytes onto the wire
  itself (that is `newswire.actor`'s `:actuation/distribute`/
  `:actuation/issue-correction`, always human-gated for correction/
  retraction and gated on the embargo/sourcing/sensitivity checks
  above for distribution -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  bureau's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn embargo-violated?
  "Is `now` (an ISO-8601 instant string, injected -- never wall-clock
  read implicitly, so this stays a pure function) strictly BEFORE
  `story`'s own recorded `:embargo-until`? nil `:embargo-until` means
  no embargo -- never violated. A pure ground-truth check against the
  story's own permanent field -- no upstream comparison needed, the
  TEMPORAL analog of this fleet's format/syntactic-validity check
  family (see ns docstring)."
  [{:keys [embargo-until]} now]
  (boolean (and embargo-until (neg? (compare now embargo-until)))))

(defn register-distribution
  "Validate + construct the DISTRIBUTION registration DRAFT -- the
  bureau's own act of pushing a story onto the real subscriber wire.
  Pure function -- does not touch any real subscriber feed; it builds
  the RECORD a bureau would keep. `newswire.governor` independently
  re-verifies sourcing completeness and embargo-instant validity, and
  blocks a double-distribution for the same story, before this is ever
  allowed to commit."
  [story-id client-id sequence]
  (when-not (and story-id (not= story-id ""))
    (throw (ex-info "distribution: story_id required" {})))
  (when-not (and client-id (not= client-id ""))
    (throw (ex-info "distribution: client_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "distribution: sequence must be >= 0" {})))
  (let [distribution-number (str (str/upper-case client-id) "-DIST-" (zero-pad sequence 6))
        record {"record_id" distribution-number
                "kind" "distribution-draft"
                "story_id" story-id
                "client_id" client-id
                "immutable" true}]
    {"record" record "distribution_number" distribution-number
     "certificate" (unsigned-certificate "WireDistribution" distribution-number distribution-number)}))

(defn register-correction
  "Validate + construct the CORRECTION/RETRACTION registration DRAFT --
  the bureau's own act of issuing a correction or retraction notice for
  a story it has ALREADY distributed. `kind` is `:correction` (revises,
  story stays live) or `:retraction` (withdraws, story is dead --
  guarded against being issued twice by `newswire.governor`). Pure
  function -- does not touch any real subscriber feed; it builds the
  RECORD a bureau would keep. Unlike every prior sibling's negative
  actuation (which WITHHOLDS something, e.g. `satcom.registry/register-
  service-suspension`'s `:actuation/suspend-service`), this is a
  POSITIVE act -- it ISSUES a new, distinct notice rather than
  silently overwriting or un-sending the original story. See this
  repo's own `docs/adr/0001-architecture.md`."
  [story-id client-id sequence kind]
  (when-not (and story-id (not= story-id ""))
    (throw (ex-info "correction: story_id required" {})))
  (when-not (and client-id (not= client-id ""))
    (throw (ex-info "correction: client_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "correction: sequence must be >= 0" {})))
  (when-not (contains? #{:correction :retraction} kind)
    (throw (ex-info "correction: kind must be :correction or :retraction" {:kind kind})))
  (let [correction-number (str (str/upper-case client-id) "-CORR-" (zero-pad sequence 6))
        record {"record_id" correction-number
                "kind" (name kind)
                "story_id" story-id
                "client_id" client-id
                "immutable" true}]
    {"record" record "correction_number" correction-number
     "certificate" (unsigned-certificate "WireCorrection" correction-number correction-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
