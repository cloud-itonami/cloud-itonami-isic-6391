(ns newswire.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [newswire.store :as store]
            [newswire.governor :as governor]))

(def ^:private now "2025-06-01T00:00:00Z")

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "bureau-1" :name "Kobo Wire Desk"})
    (store/register-story! st {:story-id "S-1" :client-id "bureau-1"
                               :headline "Clean story" :embargo-until nil
                               :legally-sensitive? false :distributed? false :retracted? false})
    st))

(defn- verified! [st story-id]
  (store/commit-record! st {:effect :verification/set :path [story-id]
                            :payload {:story-id story-id :checklist ["on-the-record source"] :sourced? true}})
  st)

(defn- distribute-op [subject]
  {:op :actuation/distribute :effect :propose :action :story/mark-distributed
   :value {:story-id subject} :cites [subject] :confidence 0.9 :stake :actuation/distribute})

(defn- correction-op [subject kind]
  {:op :actuation/issue-correction :effect :propose :action :story/mark-retracted
   :value {:story-id subject :kind kind} :cites [subject] :confidence 0.9 :stake :actuation/issue-correction})

(def ^:private req {:client-id "bureau-1" :subject "S-1"})
(def ^:private ctx {:now now})

(deftest ok-clean-sourced-unembargoed-non-sensitive-distribution
  (let [st (verified! (fresh-store) "S-1")
        v (governor/check (assoc req :op :actuation/distribute) ctx (distribute-op "S-1") st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-source-not-verified
  (testing "a story must have a verified source record before distribution"
    (let [st (fresh-store) ; never verified
          v (governor/check (assoc req :op :actuation/distribute) ctx (distribute-op "S-1") st)]
      (is (:hard? v))
      (is (some #(= :source-not-verified (:rule %)) (:violations v))))))

(deftest hard-on-embargo-violated
  (testing "distributing before the story's own recorded embargo instant is a HARD violation, not just risky"
    (let [st (fresh-store)]
      (store/register-story! st {:story-id "S-1" :client-id "bureau-1" :headline "Embargoed"
                                 :embargo-until "2025-12-31T00:00:00Z" :legally-sensitive? false
                                 :distributed? false :retracted? false})
      (verified! st "S-1")
      (let [v (governor/check (assoc req :op :actuation/distribute) ctx (distribute-op "S-1") st)]
        (is (:hard? v))
        (is (some #(= :embargo-violated (:rule %)) (:violations v)))))))

(deftest ok-when-embargo-has-lapsed
  (testing "the embargo boundary is inclusive -- now == embargo-until is not a violation"
    (let [st (fresh-store)]
      (store/register-story! st {:story-id "S-1" :client-id "bureau-1" :headline "Just lapsed"
                                 :embargo-until now :legally-sensitive? false
                                 :distributed? false :retracted? false})
      (verified! st "S-1")
      (let [v (governor/check (assoc req :op :actuation/distribute) ctx (distribute-op "S-1") st)]
        (is (:ok? v))))))

(deftest hard-on-unknown-story
  (let [st (verified! (fresh-store) "S-1")
        v (governor/check (assoc req :op :actuation/distribute :subject "S-ghost") ctx
                          (distribute-op "S-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-story (:rule %)) (:violations v)))))

(deftest hard-on-foreign-story
  (let [st (verified! (fresh-store) "S-1")]
    (store/register-client! st {:client-id "bureau-2" :name "Other Desk"})
    (let [v (governor/check {:client-id "bureau-2" :subject "S-1" :op :actuation/distribute} ctx
                            (distribute-op "S-1") st)]
      (is (:hard? v))
      (is (some #(= :story-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (verified! (fresh-store) "S-1")
        v (governor/check {:client-id "nobody" :subject "S-1" :op :actuation/distribute} ctx
                          (distribute-op "S-1") st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (testing "effect must be :propose -- the actor never itself pushes content to the wire"
    (let [st (verified! (fresh-store) "S-1")
          v (governor/check (assoc req :op :actuation/distribute) ctx
                            (assoc (distribute-op "S-1") :effect :direct-write) st)]
      (is (:hard? v))
      (is (some #(= :no-actuation (:rule %)) (:violations v))))))

(deftest hard-on-already-distributed
  (let [st (verified! (fresh-store) "S-1")]
    (store/commit-record! st {:effect :story/mark-distributed :path ["S-1"] :value {} :payload {}})
    (let [v (governor/check (assoc req :op :actuation/distribute) ctx (distribute-op "S-1") st)]
      (is (:hard? v))
      (is (some #(= :already-distributed (:rule %)) (:violations v))))))

(deftest hard-on-not-yet-distributed-correction
  (testing "a correction/retraction of a story never distributed is nonsensical"
    (let [st (fresh-store) ; S-1 never distributed
          v (governor/check (assoc req :op :actuation/issue-correction) ctx
                            (correction-op "S-1" :correction) st)]
      (is (:hard? v))
      (is (some #(= :not-yet-distributed (:rule %)) (:violations v))))))

(deftest hard-on-already-retracted
  (let [st (verified! (fresh-store) "S-1")]
    (store/commit-record! st {:effect :story/mark-distributed :path ["S-1"] :value {} :payload {}})
    (store/commit-record! st {:effect :story/mark-retracted :path ["S-1"] :value {:kind :retraction} :payload {}})
    (let [v (governor/check (assoc req :op :actuation/issue-correction) ctx
                            (correction-op "S-1" :retraction) st)]
      (is (:hard? v))
      (is (some #(= :already-retracted (:rule %)) (:violations v))))))

(deftest correction-after-retraction-is-hard-but-plain-correction-repeats-are-not
  (testing "a plain :correction after a story was already CORRECTED (not retracted) is not double-guarded"
    (let [st (verified! (fresh-store) "S-1")]
      (store/commit-record! st {:effect :story/mark-distributed :path ["S-1"] :value {} :payload {}})
      (store/commit-record! st {:effect :story/mark-retracted :path ["S-1"] :value {:kind :correction} :payload {}})
      (let [v (governor/check (assoc req :op :actuation/issue-correction) ctx
                              (correction-op "S-1" :correction) st)]
        (is (not (:hard? v)))))))

(deftest escalates-legally-sensitive-distribution
  (testing "a legally-sensitive story requires human sign-off, but is NOT an absolute block -- and
            this is independently recomputed off the story's own field, with NO prerequisite
            :sensitivity/screen call needed (no 'forgot to screen' loophole)"
    (let [st (fresh-store)]
      (store/register-story! st {:story-id "S-1" :client-id "bureau-1" :headline "Sensitive"
                                 :embargo-until nil :legally-sensitive? true
                                 :distributed? false :retracted? false})
      (verified! st "S-1")
      (let [v (governor/check (assoc req :op :actuation/distribute) ctx (distribute-op "S-1") st)]
        (is (not (:hard? v)))
        (is (:escalate? v))
        (is (:sensitive? v))))))

(deftest sensitivity-screen-op-can-itself-escalate-on-its-own-finding
  (testing "evaluated unconditionally, so :sensitivity/screen itself escalates on a fresh finding"
    (let [st (fresh-store)
          screen-proposal {:op :sensitivity/screen :effect :propose :action :sensitivity-screen/set
                           :value {:story-id "S-1" :verdict :sensitive} :cites [] :confidence 0.95 :stake nil}
          v (governor/check (assoc req :op :sensitivity/screen) ctx screen-proposal st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest always-escalates-issue-correction-even-at-high-confidence
  (testing "issuing a correction or retraction is a distinct, always-human-signoff action"
    (let [st (verified! (fresh-store) "S-1")]
      (store/commit-record! st {:effect :story/mark-distributed :path ["S-1"] :value {} :payload {}})
      (let [v (governor/check (assoc req :op :actuation/issue-correction) ctx
                              (assoc (correction-op "S-1" :correction) :confidence 0.99) st)]
        (is (not (:hard? v)))
        (is (:escalate? v))
        (is (:high-stakes? v))))))

(deftest escalates-low-confidence
  (let [st (verified! (fresh-store) "S-1")
        v (governor/check (assoc req :op :actuation/distribute) ctx
                          (assoc (distribute-op "S-1") :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
