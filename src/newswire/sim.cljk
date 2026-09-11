(ns newswire.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean story through
  intake -> source verification -> sensitivity screening -> distribution
  (auto-commits at phase 3: clean, sourced, unembargoed, non-sensitive)
  -> a correction, then a retraction (both ALWAYS escalate -- human
  sign-off), then shows five HARD holds (an unsourced distribution
  attempt, an embargoed distribution attempt, a legally-sensitive
  story's distribution ESCALATING rather than auto-publishing, a
  double distribution, and a correction attempt against a
  never-distributed story) and prints the audit ledger + the draft
  distribution and correction/retraction records."
  (:require [langgraph.graph :as g]
            [newswire.store :as store]
            [newswire.actor :as actor]))

(def cid "bureau-1")
(def editor {:actor-id "ed-1" :actor-role :wire-editor :phase 3 :now "2020-01-01T00:00:00Z"})

(defn- exec! [graph tid request context]
  (g/run* graph {:request (assoc request :client-id cid) :context context} {:thread-id tid}))

(defn- approve! [graph tid]
  (g/run* graph {:approval {:status :approved :by "ed-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        graph (actor/build-graph db)]
    (println "== story/intake story-1 (clean, unembargoed, non-sensitive) ==")
    (println (exec! graph "t1" {:op :story/intake :subject "story-1"
                                :patch {:story-id "story-1" :headline "Clean, unembargoed, non-sensitive story"}}
                    editor))

    (println "== source/verify story-1 (escalates -- human approves) ==")
    (println (exec! graph "t2" {:op :source/verify :subject "story-1" :sources ["on-the-record spokesperson"]} editor))
    (println (approve! graph "t2"))

    (println "== sensitivity/screen story-1 (clear; escalates -- human approves) ==")
    (println (exec! graph "t3" {:op :sensitivity/screen :subject "story-1"} editor))
    (println (approve! graph "t3"))

    (println "== actuation/distribute story-1 (clean + sourced + non-sensitive -> phase-3 AUTO-COMMIT) ==")
    (println (exec! graph "t4" {:op :actuation/distribute :subject "story-1"} editor))

    (println "== actuation/issue-correction story-1 (ALWAYS escalates -- never auto, any phase) ==")
    (let [r (exec! graph "t5" {:op :actuation/issue-correction :subject "story-1" :kind :correction} editor)]
      (println r)
      (println "-- human editor approves the correction --")
      (println (approve! graph "t5")))

    (println "== actuation/issue-correction story-1 AGAIN, kind :retraction (ALWAYS escalates) ==")
    (let [r (exec! graph "t6" {:op :actuation/issue-correction :subject "story-1" :kind :retraction} editor)]
      (println r)
      (println "-- human editor approves the retraction --")
      (println (approve! graph "t6")))

    (println "== actuation/distribute story-2 (embargo not lapsed -> HARD hold) ==")
    (println (exec! graph "t7" {:op :source/verify :subject "story-2" :sources ["wire copy"]} editor))
    (println (approve! graph "t7"))
    (println (exec! graph "t8" {:op :actuation/distribute :subject "story-2"} editor))

    (println "== actuation/distribute story-3 (no verified source -> HARD hold) ==")
    (println (exec! graph "t9" {:op :actuation/distribute :subject "story-3"} editor))

    (println "== sensitivity/screen story-3 (sensitive -> ESCALATE, never auto-publish) ==")
    (println (exec! graph "t10" {:op :sensitivity/screen :subject "story-3"} editor))

    (println "== actuation/distribute story-1 AGAIN (double-distribution -> HARD hold) ==")
    (println (exec! graph "t11" {:op :actuation/distribute :subject "story-1"} editor))

    (println "== actuation/issue-correction story-2 (never distributed -> HARD hold) ==")
    (println (exec! graph "t12" {:op :actuation/issue-correction :subject "story-2" :kind :correction} editor))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft distribution records ==")
    (doseq [r (store/distribution-history db)] (println r))

    (println "== draft correction/retraction records ==")
    (doseq [r (store/correction-history db)] (println r))))
