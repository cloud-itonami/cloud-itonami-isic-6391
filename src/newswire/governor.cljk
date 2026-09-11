(ns newswire.governor
  "Wire Governor -- the independent compliance layer that earns the
  Wire Advisor the right to commit (named `:wire-governor` in this
  repo's own `blueprint.edn`). The LLM has no notion of whether a story
  actually has a checkable source on file, whether the CURRENT moment
  is really past the story's own agreed embargo instant, whether a
  story's own legal-sensitivity screening has actually stayed
  unresolved, or when an act stops being a draft and becomes a
  real-world push onto the subscriber wire, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD -- the
  news-agency analog of `cloud-itonami-isco-3521`'s `media.governor`
  (this fleet's closest domain analog) and structurally modeled on
  `cloud-itonami-isic-6130`'s `satcom.governor`.

  Five checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past an
  unsourced story, an embargo that hasn't lapsed yet, a story that
  isn't even registered, or a double distribution/correction). The
  legal-sensitivity flag and the confidence/actuation gate are SOFT:
  they ask a human to look (and a human may approve past them) -- but
  see `newswire.phase`: `:actuation/issue-correction` NEVER auto-
  commits at any phase, a permanent structural fact, not a rollout
  milestone -- the actor never silently overwrites what it already
  distributed.

    1. Client provenance          -- is the bureau/wire-desk actually
                                      registered?
    2. No-actuation                -- proposal `:effect` must be
                                      `:propose`-shaped (never a direct
                                      write) -- the governor never
                                      pushes to the wire itself; it
                                      only gates what the actor may
                                      execute.
    3. Unknown/foreign story       -- for any op against a subject, the
                                      story must be REGISTERED and
                                      belong to this bureau.
    4. Source not verified         -- for `:actuation/distribute`, has
                                      the story actually been sourced
                                      with a full checklist on file
                                      (`newswire.store/source-
                                      verification-of`)? Never trust the
                                      advisor's self-reported confidence
                                      alone.
    5. Embargo violated            -- for `:actuation/distribute`,
                                      INDEPENDENTLY recompute whether
                                      `now` is still before the story's
                                      own recorded `:embargo-until`
                                      (`newswire.registry/embargo-
                                      violated?`) -- a pure ground-truth
                                      TEMPORAL check, needs no proposal
                                      inspection or stored-verdict
                                      lookup at all. A genuinely NEW
                                      check kind for this fleet (see
                                      `newswire.registry`'s own
                                      docstring).

  Two more guard pairs are enforced but not numbered above because they
  need no upstream comparison at all:
    - `already-distributed-violations` refuses to distribute the SAME
      story twice, off a dedicated `:distributed?` fact (never a
      `:status` value).
    - `not-yet-distributed-violations`/`already-retracted-violations`
      refuse to correct/retract a story that was never distributed, or
      retract one already retracted, off dedicated `:distributed?`/
      `:retracted?` facts.

  ESCALATION (SOFT, human MAY approve past it):
    - `legally-sensitive-violations` -- reported by THIS proposal
      itself (a `:sensitivity/screen` that just found sensitive
      subject matter), OR independently recomputed for
      `:actuation/distribute` directly off the story's own permanent
      `:legally-sensitive?` field -- deliberately NOT gated behind a
      prior committed `:sensitivity/screen` (no 'forgot to screen'
      loophole for a defamation/liability risk), the SAME ground-
      truth-recompute discipline `embargo-violated-violations` uses.
      Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
      screening op itself can escalate on its own finding -- mirrors
      `satcom.governor/coordination-dispute-unresolved-violations`
      (`cloud-itonami-isic-6130`)'s unconditional-evaluation
      discipline, but ESCALATE here, not HARD -- a human editor's
      sign-off is exactly what defamation/liability risk calls for,
      not a permanent, un-appealable block.
    - low confidence (< `confidence-floor`).
    - `:actuation/issue-correction` is ALWAYS in `high-stakes` --
      unlike `:actuation/distribute`, this op NEVER escapes to
      auto-commit at any phase (see `newswire.phase`)."
  (:require [newswire.registry :as registry]
            [newswire.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to ALWAYS require a human, even when clean.
  Unlike every prior dual-actuation sibling (`cloud-itonami-isic-6190`/
  `-6120`/`-6130`, where BOTH actuations are permanently excluded from
  auto-commit), this fleet's FIRST asymmetric dual-actuation shape:
  only `:actuation/issue-correction` is unconditionally high-stakes.
  `:actuation/distribute` is high-stakes only when the HARD/ESCALATE
  checks below actually find something -- a clean, sourced,
  unembargoed, non-sensitive story MAY reach phase-3 auto-commit (see
  `newswire.phase` and this repo's own `docs/adr/0001-architecture.md`
  Decision on why distribution and correction/retraction are not
  symmetric)."
  #{:actuation/issue-correction})

;; ----------------------------- checks -----------------------------

(defn- client-provenance-violations
  [{:keys [client-id]} st]
  (when (nil? (store/client st client-id))
    [{:rule :no-client :detail "未登録のbureau/wire-deskからの提案"}]))

(defn- no-actuation-violations
  "The advisor's `:effect` must be the literal `:propose` -- the fixed
  invariant this repo's own README/blueprint.edn state: the actor
  itself never pushes content to the wire, it only proposes to. Any
  other value (a raw direct-write bypassing this governor) is a HARD
  violation, independently re-checked here rather than trusted from
  the advisor's own output."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :no-actuation :detail "effect は :propose のみ許可（governor は直接配線しない）"}]))

(defn- unknown-or-foreign-story-violations
  [{:keys [op client-id subject]} st]
  (when (contains? #{:actuation/distribute :actuation/issue-correction} op)
    (let [story (store/story st subject)]
      (cond
        (nil? story)
        [{:rule :unknown-story :detail "未登録ストーリーへの提案は不可"}]

        (not= (:client-id story) client-id)
        [{:rule :story-wrong-client :detail "ストーリーが別bureauのもの"}]))))

(defn- source-not-verified-violations
  "For `:actuation/distribute`, the story's own sourcing/verification
  record must actually be on file and complete -- do not trust the
  advisor's self-reported confidence alone. This is the direct
  fulfillment of this repo's own governor invariant: a story must have
  a verified source record before distribution."
  [{:keys [op subject]} st]
  (when (= op :actuation/distribute)
    (let [verification (store/source-verification-of st subject)]
      (when-not (true? (:sourced? verification))
        [{:rule :source-not-verified
          :detail "情報源が確認されていない状態での配信提案（配信には検証済み情報源記録が必須）"}]))))

(defn- embargo-violated-violations
  "For `:actuation/distribute`, INDEPENDENTLY recompute whether `now`
  (injected via `context`, never read implicitly) is still before the
  story's own recorded `:embargo-until` via `newswire.registry/
  embargo-violated?` -- needs no proposal inspection at all, since its
  inputs are a permanent ground-truth field already on the story."
  [{:keys [op subject]} context st]
  (when (= op :actuation/distribute)
    (let [story (store/story st subject)
          now (:now context)]
      (when (and story now (registry/embargo-violated? story now))
        [{:rule :embargo-violated
          :detail (str subject " は embargo-until=" (:embargo-until story)
                       " が未到来（合意した解禁時刻より前の配信は不可）")}]))))

(defn- already-distributed-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/distribute)
    (when (store/story-already-distributed? st subject)
      [{:rule :already-distributed :detail (str subject " は既に配信済み")}])))

(defn- not-yet-distributed-violations
  "A correction/retraction of a story that was never distributed is
  nonsensical -- there is nothing on the wire to correct or retract."
  [{:keys [op subject]} st]
  (when (= op :actuation/issue-correction)
    (when-not (store/story-already-distributed? st subject)
      [{:rule :not-yet-distributed :detail (str subject " は未配信のため訂正/撤回不可")}])))

(defn- already-retracted-violations
  "Refuses to retract the SAME story twice, off a dedicated
  `:retracted?` fact (never a `:status` value)."
  [{:keys [op subject]} proposal st]
  (when (and (= op :actuation/issue-correction)
             (= :retraction (get-in proposal [:value :kind])))
    (when (store/story-already-retracted? st subject)
      [{:rule :already-retracted :detail (str subject " は既に撤回済み")}])))

(defn- legally-sensitive-violations
  "A legally-sensitive subject flag on the story -- reported by THIS
  proposal (e.g. a `:sensitivity/screen` that itself just found one),
  OR independently recomputed for `:actuation/distribute` directly off
  the story's own permanent `:legally-sensitive?` field (the SAME
  ground-truth-recompute discipline `embargo-violated-violations`
  uses, deliberately NOT gated behind a prior committed
  `:sensitivity/screen` -- a bureau that skips screening a story before
  attempting to distribute it must not thereby dodge this check; there
  is no 'forgot to screen' loophole for a defamation/liability risk) --
  is a SOFT, human-sign-off escalation, never a HARD un-overridable
  hold: an editor may review the risk and clear it for distribution.
  Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
  screening op itself can escalate on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :sensitive (get-in proposal [:value :verdict]))
        story (store/story st subject)
        hit-on-story? (and (= op :actuation/distribute) (true? (:legally-sensitive? story)))]
    (boolean (or hit-in-proposal? hit-on-story?))))

(defn check
  "Censors a Wire Advisor proposal against the governor rules. `context`
  carries `:now` (an ISO-8601 instant string) for the embargo check --
  injected, never read implicitly, so this stays testable/pure end to
  end. Returns {:ok? bool :violations [..] :confidence c :hard? bool
  :escalate? bool :high-stakes? bool}."
  [request context proposal st]
  (let [hard (into []
                   (concat (client-provenance-violations request st)
                           (no-actuation-violations proposal)
                           (unknown-or-foreign-story-violations request st)
                           (source-not-verified-violations request st)
                           (embargo-violated-violations request context st)
                           (already-distributed-violations request st)
                           (not-yet-distributed-violations request st)
                           (already-retracted-violations request proposal st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        sensitive? (legally-sensitive-violations request proposal st)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not sensitive?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? sensitive? stakes?))
     :sensitive?   sensitive?
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
