(ns newswire.phase
  "Phase 0->3 staged rollout -- the news-agency analog of
  `cloud-itonami-isic-6130`'s `satcom.phase`.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- story intake allowed, every write
                                   needs human approval.
    Phase 2  assisted-verify    -- adds sourcing verification + legal-
                                   sensitivity screening writes, still
                                   approval.
    Phase 3  supervised auto    -- governor-clean, high-confidence,
                                   NON-SENSITIVE `:story/intake` and
                                   `:actuation/distribute` may
                                   auto-commit. `:actuation/issue-
                                   correction` NEVER auto-commits, at
                                   any phase.

  THIS is this fleet's FIRST asymmetric dual-actuation phase table:
  every prior dual-actuation sibling (`cloud-itonami-isic-6190`/`-6120`/
  `-6130`) permanently excludes BOTH actuations from every phase's
  `:auto` set. Here only `:actuation/issue-correction` is permanently
  excluded -- a story's ROUTINE, clean, sourced, unembargoed,
  non-sensitive distribution IS this business's ordinary continuous
  operation (a real wire service does not put a human in the loop on
  every single clean story), but correcting or retracting a story it
  ALREADY put on the wire is a distinct, always-auditable,
  always-human-signoff act -- the actor never silently overwrites what
  it already distributed. `newswire.governor`'s `high-stakes` set
  enforces the SAME asymmetric invariant independently -- two layers,
  not one, agree on this (see this repo's own
  `docs/adr/0001-architecture.md`).

  A story the governor flags legally-sensitive, or whose embargo has
  not lapsed, or with no verified source on file, is NEVER eligible for
  phase-3 auto-commit either way -- `newswire.governor/check` returns
  `:hold`/`:escalate` for those cases before this gate is even
  consulted (see `verdict->disposition`)." )

(def read-ops  #{})
(def write-ops #{:story/intake :source/verify :sensitivity/screen
                 :actuation/distribute :actuation/issue-correction})

;; NOTE the invariant: `:actuation/issue-correction` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                              :auto #{}}
   1 {:label "assisted-intake"  :writes #{:story/intake}                                                  :auto #{}}
   2 {:label "assisted-verify"  :writes #{:story/intake :source/verify :sensitivity/screen}                :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:story/intake :actuation/distribute}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:actuation/issue-correction` is never auto-eligible at any phase,
    so it always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Wire Governor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
