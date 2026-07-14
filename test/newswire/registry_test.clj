(ns newswire.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [newswire.registry :as registry]))

(deftest embargo-violated-is-a-pure-ground-truth-temporal-check
  (testing "no embargo -- never violated"
    (is (false? (registry/embargo-violated? {:embargo-until nil} "2025-01-01T00:00:00Z"))))
  (testing "now strictly before embargo-until -- violated"
    (is (true? (registry/embargo-violated? {:embargo-until "2025-06-01T00:00:00Z"} "2025-01-01T00:00:00Z"))))
  (testing "now exactly at embargo-until -- inclusive boundary, not violated"
    (is (false? (registry/embargo-violated? {:embargo-until "2025-06-01T00:00:00Z"} "2025-06-01T00:00:00Z"))))
  (testing "now strictly after embargo-until -- not violated"
    (is (false? (registry/embargo-violated? {:embargo-until "2025-06-01T00:00:00Z"} "2025-12-31T00:00:00Z")))))

(deftest register-distribution-builds-an-unsigned-draft
  (let [{:strs [record distribution_number certificate]} (registry/register-distribution "S-1" "bureau-1" 0)]
    (is (= "BUREAU-1-DIST-000000" distribution_number))
    (is (= "S-1" (get record "story_id")))
    (is (true? (get record "immutable")))
    (is (= "draft-unsigned" (get certificate "status")))
    (is (nil? (get certificate "proof")))))

(deftest register-distribution-requires-story-and-client
  (is (thrown? Exception (registry/register-distribution nil "bureau-1" 0)))
  (is (thrown? Exception (registry/register-distribution "S-1" nil 0)))
  (is (thrown? Exception (registry/register-distribution "S-1" "bureau-1" -1))))

(deftest register-correction-builds-a-correction-or-retraction-draft
  (let [{:strs [record correction_number]} (registry/register-correction "S-1" "bureau-1" 0 :correction)]
    (is (= "BUREAU-1-CORR-000000" correction_number))
    (is (= "correction" (get record "kind"))))
  (let [{:strs [record correction_number]} (registry/register-correction "S-1" "bureau-1" 1 :retraction)]
    (is (= "BUREAU-1-CORR-000001" correction_number))
    (is (= "retraction" (get record "kind")))))

(deftest register-correction-rejects-an-unknown-kind
  (is (thrown? Exception (registry/register-correction "S-1" "bureau-1" 0 :bogus))))

(deftest append-conj-s-the-record-onto-history
  (let [result (registry/register-distribution "S-1" "bureau-1" 0)]
    (is (= [(get result "record")] (registry/append [] result)))))
