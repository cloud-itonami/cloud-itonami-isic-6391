(ns newswire.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [newswire.actor :as actor]
            [newswire.store :as store]))

(def ^:private ctx {:actor-id "ed-1" :phase 3 :now "2025-06-01T00:00:00Z"})
(def ^:private cid "bureau-1")

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id cid :name "Kobo Wire Desk"})
    (store/register-story! st {:story-id "S-1" :client-id cid
                               :headline "Clean story" :embargo-until nil
                               :legally-sensitive? false :distributed? false :retracted? false})
    (store/register-story! st {:story-id "S-2" :client-id cid
                               :headline "Embargoed story" :embargo-until "2999-01-01T00:00:00Z"
                               :legally-sensitive? false :distributed? false :retracted? false})
    (store/register-story! st {:story-id "S-3" :client-id cid
                               :headline "Sensitive story" :embargo-until nil
                               :legally-sensitive? true :distributed? false :retracted? false})
    st))

(deftest source-not-verified-story-holds-on-distribution-attempt
  (let [st (fresh-store)
        graph (actor/build-graph st)
        result (actor/run-request! graph {:client-id cid :op :actuation/distribute :subject "S-1"} ctx "t1")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/distribution-history st)))))

(deftest full-lifecycle-clean-story-auto-commits-distribution-at-phase-3
  (let [st (fresh-store)
        graph (actor/build-graph st)]
    ;; source/verify escalates -- human approves
    (let [interrupted (actor/run-request! graph {:client-id cid :op :source/verify :subject "S-1"
                                                 :sources ["on-the-record spokesperson"]} ctx "t2")]
      (is (= :interrupted (:status interrupted)))
      (let [resumed (actor/approve! graph "t2")]
        (is (= :done (:status resumed)))))
    ;; sensitivity/screen escalates -- human approves
    (let [interrupted (actor/run-request! graph {:client-id cid :op :sensitivity/screen :subject "S-1"} ctx "t3")]
      (is (= :interrupted (:status interrupted)))
      (actor/approve! graph "t3"))
    ;; a clean, sourced, unembargoed, non-sensitive distribution AUTO-COMMITS at phase 3
    (let [result (actor/run-request! graph {:client-id cid :op :actuation/distribute :subject "S-1"} ctx "t4")]
      (is (= :done (:status result)))
      (is (some? (get-in result [:state :record])))
      (is (= 1 (count (store/distribution-history st))))
      (is (true? (:distributed? (store/story st "S-1")))))))

(deftest embargoed-story-holds-even-when-sourced-and-non-sensitive
  (testing "distributing before the story's own agreed embargo instant is a HARD violation"
    (let [st (fresh-store)
          graph (actor/build-graph st)]
      (actor/run-request! graph {:client-id cid :op :source/verify :subject "S-2" :sources ["wire copy"]} ctx "t5")
      (actor/approve! graph "t5")
      (let [result (actor/run-request! graph {:client-id cid :op :actuation/distribute :subject "S-2"} ctx "t6")]
        (is (= :hold (:disposition (:state result))))
        (is (empty? (store/distribution-history st)))))))

(deftest sensitive-story-distribution-escalates-rather-than-auto-publishing
  (let [st (fresh-store)
        graph (actor/build-graph st)]
    (actor/run-request! graph {:client-id cid :op :source/verify :subject "S-3" :sources ["confidential source"]} ctx "t7")
    (actor/approve! graph "t7")
    (let [interrupted (actor/run-request! graph {:client-id cid :op :actuation/distribute :subject "S-3"} ctx "t8")]
      (is (= :interrupted (:status interrupted)))
      (is (empty? (store/distribution-history st)))
      (let [resumed (actor/approve! graph "t8")]
        (is (= :done (:status resumed)))
        (is (= 1 (count (store/distribution-history st))))))))

(deftest correction-always-interrupts-then-approves-never-auto
  (testing "issuing a correction is a distinct, always-human-signoff action -- never auto, any phase"
    (let [st (fresh-store)
          graph (actor/build-graph st)]
      (actor/run-request! graph {:client-id cid :op :source/verify :subject "S-1" :sources ["spokesperson"]} ctx "t9")
      (actor/approve! graph "t9")
      (actor/run-request! graph {:client-id cid :op :actuation/distribute :subject "S-1"} ctx "t10")
      (let [interrupted (actor/run-request! graph {:client-id cid :op :actuation/issue-correction :subject "S-1"
                                                    :kind :correction} ctx "t11")]
        (is (= :interrupted (:status interrupted)))
        (let [resumed (actor/approve! graph "t11")]
          (is (= :done (:status resumed)))
          (is (= 1 (count (store/correction-history st))))
          ;; a :correction does not retract -- the story stays live
          (is (false? (:retracted? (store/story st "S-1")))))))))

(deftest retraction-marks-the-story-retracted-after-approval
  (let [st (fresh-store)
        graph (actor/build-graph st)]
    (actor/run-request! graph {:client-id cid :op :source/verify :subject "S-1" :sources ["spokesperson"]} ctx "t12")
    (actor/approve! graph "t12")
    (actor/run-request! graph {:client-id cid :op :actuation/distribute :subject "S-1"} ctx "t13")
    (actor/run-request! graph {:client-id cid :op :actuation/issue-correction :subject "S-1" :kind :retraction} ctx "t14")
    (actor/approve! graph "t14")
    (is (true? (:retracted? (store/story st "S-1"))))))

(deftest rejected-approval-holds-instead-of-committing
  (let [st (fresh-store)
        graph (actor/build-graph st)]
    (actor/run-request! graph {:client-id cid :op :source/verify :subject "S-1" :sources ["spokesperson"]} ctx "t15")
    (actor/approve! graph "t15")
    (actor/run-request! graph {:client-id cid :op :actuation/distribute :subject "S-1"} ctx "t16")
    (actor/run-request! graph {:client-id cid :op :actuation/issue-correction :subject "S-1" :kind :correction} ctx "t17")
    (let [resumed (actor/approve! graph "t17" {:status :rejected :by "ed-1"})]
      (is (= :done (:status resumed)))
      (is (= :hold (:disposition (:state resumed))))
      (is (empty? (store/correction-history st))))))
