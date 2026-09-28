(ns stepquence.storage-test
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [stepquence.db :as db]
   [stepquence.storage :as storage]))

(deftest round-trip
  (let [db  (-> db/default-db
                (assoc :day {:date "2026-09-27"
                             :done #{(random-uuid)}
                             :resume {(random-uuid) {:done-count 2 :choices {(random-uuid) (random-uuid)}}}}))
        out (storage/decode (storage/encode db))]
    (is (nil? (:error out)))
    (is (= (storage/persisted-slice db) (:data out)))
    (testing "only persisted slices are written"
      (is (not (contains? (:data out) :ui)))
      (is (not (contains? (:data out) :run))))))

(deftest unusable-data-is-rejected
  (is (re-find #"^unreadable" (:error (storage/decode "{:content"))))
  (is (re-find #"^invalid" (:error (storage/decode (pr-str {:content {}}))))))
