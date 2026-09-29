(ns stepquence.day-test
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [stepquence.day :as day]))

(defn- at [y mo d h mi]
  (.getTime (js/Date. y (dec mo) d h mi)))

(deftest today-respects-the-day-boundary
  (testing "before the boundary still counts as the previous day"
    (is (= "2026-09-26" (day/today (at 2026 9 27 2 59) 3))))
  (testing "from the boundary on, it is the new day"
    (is (= "2026-09-27" (day/today (at 2026 9 27 3 0) 3))))
  (testing "a midnight boundary"
    (is (= "2026-09-27" (day/today (at 2026 9 27 0 0) 0))))
  (testing "month and year roll back correctly"
    (is (= "2025-12-31" (day/today (at 2026 1 1 1 0) 3)))))

(deftest roll-over
  (let [d {:date "2026-09-26" :done #{(random-uuid)} :resume {}}]
    (is (identical? d (day/roll-over d "2026-09-26")))
    (is (= (day/fresh "2026-09-27") (day/roll-over d "2026-09-27")))))

(deftest weekday
  (is (= :mon (day/weekday "2026-09-28")))
  (is (= :sun (day/weekday "2026-09-27"))))
