(ns newswire.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [newswire.phase :as phase]))

(deftest issue-correction-never-auto-at-any-phase
  (testing "this fleet's FIRST asymmetric dual-actuation shape: only
            :actuation/issue-correction is permanently excluded from
            every phase's :auto set"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/issue-correction))
          (str "phase " n " must never auto-commit a correction/retraction")))))

(deftest distribute-is-auto-eligible-only-at-phase-3
  (is (not (contains? (:auto (get phase/phases 0)) :actuation/distribute)))
  (is (not (contains? (:auto (get phase/phases 1)) :actuation/distribute)))
  (is (not (contains? (:auto (get phase/phases 2)) :actuation/distribute)))
  (is (contains? (:auto (get phase/phases 3)) :actuation/distribute)))

(deftest a-governor-hold-always-stays-hold-regardless-of-phase
  (doseq [n (keys phase/phases)]
    (is (= {:disposition :hold :reason nil}
           (phase/gate n {:op :actuation/distribute} :hold)))))

(deftest a-write-op-disabled-in-this-phase-holds
  (is (= :phase-disabled (:reason (phase/gate 0 {:op :story/intake} :commit)))))

(deftest a-clean-commit-for-a-non-auto-op-still-escalates
  (is (= {:disposition :escalate :reason :phase-approval}
         (phase/gate 1 {:op :story/intake} :commit))))

(deftest a-clean-auto-eligible-op-at-phase-3-stays-commit
  (is (= {:disposition :commit :reason nil}
         (phase/gate 3 {:op :actuation/distribute} :commit)))
  (is (= {:disposition :commit :reason nil}
         (phase/gate 3 {:op :story/intake} :commit))))

(deftest issue-correction-always-escalates-once-clean-at-phase-3
  (is (= {:disposition :escalate :reason :phase-approval}
         (phase/gate 3 {:op :actuation/issue-correction} :commit))))

(deftest verdict->disposition-maps-hard-and-escalate-and-clean
  (is (= :hold (phase/verdict->disposition {:hard? true})))
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
