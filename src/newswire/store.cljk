(ns newswire.store
  "SSoT for the ISIC 6391 news-agency wire-service actor (itonami actor
  pattern, ADR-2607011000 / CLAUDE.md Actors section), behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every `cloud-itonami-isic-*`/`cloud-itonami-isco-*` actor in this
  fleet uses. Modeled most closely on `cloud-itonami-isco-3521`'s
  `media.store` (the fleet's closest domain analog -- media content
  moving from creation/verification to distribution) rather than the
  telecom actors' line-provisioning shape; `MemStore` only, the same
  scope 3521 keeps (no `DatomicStore` -- see this repo's own
  `docs/adr/0001-architecture.md`).

  Domain:

    client  — a registered subscriber bureau/wire desk operating this
              actor instance (:client-id, :name).
    story   — the primary entity, a news item this bureau is drafting
              or has drafted for wire distribution: {:story-id
              :client-id :headline :embargo-until :legally-sensitive?
              :distributed? :retracted?}. `:embargo-until` and
              `:legally-sensitive?` are both PERMANENT ground-truth
              fields on the story itself, independently re-derived by
              the governor at `:actuation/distribute` time (never
              trusted from a proposal alone) -- `newswire.governor`'s
              `embargo-violated-violations`/`legally-sensitive-
              violations` recompute directly against these two fields,
              the same discipline `satcom.registry`'s satellite-number-
              format check applies to a terminal's own recorded number
              (`cloud-itonami-isic-6130`). `:distributed?`/
              `:retracted?` are dedicated booleans set ONLY by a
              committed `:story/mark-distributed`/`:story/mark-
              retracted`, never inferred from a single `:status` value
              (the discipline every prior sibling governor's guards
              establish, informed by `cloud-itonami-isic-6492`'s
              status-lifecycle bug, ADR-2607071320). Sourcing-
              verification and legal-sensitivity-screening VERDICTS
              (as opposed to the ground-truth fields above) are
              committed to SEPARATE per-story audit maps
              (`source-verification-of`/`sensitivity-screen-of`) --
              `source-verification-of` IS what the governor checks for
              sourcing completeness (no ground-truth shortcut exists
              for whether a story was actually sourced);
              `sensitivity-screen-of` is an audit record of what a
              `:sensitivity/screen` op
              found, kept for the ledger even though the governor's own
              distribution-time check reads the story's
              `:legally-sensitive?` field directly instead (see
              `newswire.governor`'s own docstring for why).
    record  — a committed operating record (a distribution or a
              correction/retraction notice) — written ONLY via
              commit-record!.
    ledger  — append-only audit trail, commit/hold/escalation."
  (:require [newswire.registry :as registry]))

(defprotocol Store
  (client [s client-id])
  (all-clients [s])
  (story [s story-id])
  (all-stories [s client-id])
  (source-verification-of [s story-id] "committed sourcing/verification checklist for a story, or nil")
  (sensitivity-screen-of [s story-id] "committed legal-sensitivity screening verdict for a story, or nil")
  (ledger [s])
  (distribution-history [s] "the append-only distribution history (newswire.registry drafts)")
  (correction-history [s] "the append-only correction/retraction history (newswire.registry drafts)")
  (next-distribution-sequence [s client-id])
  (next-correction-sequence [s client-id])
  (story-already-distributed? [s story-id])
  (story-already-retracted? [s story-id])
  (register-client! [s client])
  (register-story! [s story])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact"))

;; ----------------------------- shared commit logic -----------------------------

(defn- distribute!
  "Backend-agnostic `:story/mark-distributed` -- looks up the story via
  the protocol and drafts the distribution record, and returns
  {:result .. :story-patch ..} for the caller to persist."
  [s story-id]
  (let [st (story s story-id)
        client-id (:client-id st)
        seq-n (next-distribution-sequence s client-id)
        result (registry/register-distribution story-id client-id seq-n)]
    {:result result
     :story-patch {:distributed? true
                   :distribution-number (get result "distribution_number")}}))

(defn- correct!
  "Backend-agnostic `:story/mark-retracted` -- looks up the story via the
  protocol and drafts the correction/retraction record, and returns
  {:result .. :story-patch ..} for the caller to persist. `kind` is
  `:correction` (story stays live) or `:retraction` (story is dead)."
  [s story-id kind]
  (let [st (story s story-id)
        client-id (:client-id st)
        seq-n (next-correction-sequence s client-id)
        result (registry/register-correction story-id client-id seq-n kind)]
    {:result result
     :story-patch (cond-> {:correction-number (get result "correction_number")}
                    (= kind :retraction) (assoc :retracted? true))}))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained story set covering both actuation lifecycles
  (distribution, correction/retraction) so the actor + tests run
  offline."
  []
  {:clients {"bureau-1" {:client-id "bureau-1" :name "Kobo Wire Desk"}}
   :stories
   {"story-1" {:story-id "story-1" :client-id "bureau-1"
               :headline "Clean, unembargoed, non-sensitive story"
               :embargo-until nil :legally-sensitive? false
               :distributed? false :retracted? false}
    "story-2" {:story-id "story-2" :client-id "bureau-1"
               :headline "Story still under embargo"
               :embargo-until "2999-01-01T00:00:00Z" :legally-sensitive? false
               :distributed? false :retracted? false}
    "story-3" {:story-id "story-3" :client-id "bureau-1"
               :headline "Story touching ongoing litigation"
               :embargo-until nil :legally-sensitive? true
               :distributed? false :retracted? false}
    "story-4" {:story-id "story-4" :client-id "bureau-1"
               :headline "Already-distributed story"
               :embargo-until nil :legally-sensitive? false
               :distributed? true :retracted? false}}})

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (all-clients [_] (sort-by :client-id (vals (:clients @a))))
  (story [_ story-id] (get-in @a [:stories story-id]))
  (all-stories [_ client-id]
    (->> (:stories @a) vals (filter #(= client-id (:client-id %))) (sort-by :story-id)))
  (source-verification-of [_ story-id] (get-in @a [:verifications story-id]))
  (sensitivity-screen-of [_ story-id] (get-in @a [:sensitivity-screens story-id]))
  (ledger [_] (:ledger @a))
  (distribution-history [_] (:distributions @a))
  (correction-history [_] (:corrections @a))
  (next-distribution-sequence [_ client-id] (get-in @a [:distribution-sequences client-id] 0))
  (next-correction-sequence [_ client-id] (get-in @a [:correction-sequences client-id] 0))
  (story-already-distributed? [_ story-id] (boolean (get-in @a [:stories story-id :distributed?])))
  (story-already-retracted? [_ story-id] (boolean (get-in @a [:stories story-id :retracted?])))
  (register-client! [s cl] (swap! a assoc-in [:clients (:client-id cl)] cl) s)
  (register-story! [s st] (swap! a assoc-in [:stories (:story-id st)] st) s)
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :story/upsert
      (swap! a update-in [:stories (:story-id value)] merge value)

      :verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :sensitivity-screen/set
      (swap! a assoc-in [:sensitivity-screens (first path)] payload)

      :story/mark-distributed
      (let [story-id (first path)
            {:keys [result story-patch]} (distribute! s story-id)
            client-id (:client-id (story s story-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:distribution-sequences client-id] (fnil inc 0))
                       (update-in [:stories story-id] merge story-patch)
                       (update :distributions registry/append result))))
        result)

      :story/mark-retracted
      (let [story-id (first path)
            {:keys [result story-patch]} (correct! s story-id (:kind value))
            client-id (:client-id (story s story-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:correction-sequences client-id] (fnil inc 0))
                       (update-in [:stories story-id] merge story-patch)
                       (update :corrections registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger (fnil conj []) fact) fact))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:clients {} :stories {} :verifications {} :sensitivity-screens {}
                             :ledger [] :distribution-sequences {} :distributions []
                             :correction-sequences {} :corrections []}
                            seed)))))

(defn seed-db
  "A MemStore seeded with the demo client + story set. The deterministic
  default for dev/tests/demo."
  []
  (mem-store (demo-data)))
