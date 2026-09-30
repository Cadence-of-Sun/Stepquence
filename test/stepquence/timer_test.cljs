(ns stepquence.timer-test
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [stepquence.timer :as timer]))

(deftest running-pausing-resuming
  (let [t (timer/start :s1 60 1000)]
    (is (= 60000 (timer/remaining t 1000)))
    (is (= 45000 (timer/remaining t 16000)))
    (testing "pausing banks the time; paused time doesn't count"
      (let [p (timer/toggle t :s1 60 16000)]
        (is (= :paused (:status p)))
        (is (= 45000 (timer/remaining p 99000)))
        (let [r (timer/toggle p :s1 60 100000)]
          (is (= :running (:status r)))
          (is (= 40000 (timer/remaining r 105000)))
          (is (not= (:token t) (:token r)) "a resumed run is a new segment"))))
    (is (= 0 (timer/remaining t 999999)) "never negative")
    (is (= 0.25 (timer/fraction-left t 46000)))))

(deftest starting-again
  (let [done (assoc (timer/start :s1 60 0) :status :done)]
    (is (= :running (:status (timer/toggle done :s1 60 5000))))
    (is (= 60000 (timer/remaining (timer/toggle done :s1 60 5000) 5000)))
    (is (= :s2 (:step-id (timer/toggle done :s2 30 5000))) "another step's timer starts fresh")))

(deftest display
  (is (= "1:00" (timer/clock 60000)))
  (is (= "0:05" (timer/clock 4200)) "rounds up")
  (is (= "0:00" (timer/clock 0)))
  (is (= "30 sec" (timer/label 30)))
  (is (= "1 min" (timer/label 60)))
  (is (= "1½ min" (timer/label 90)))
  (is (= "2 min 10 sec" (timer/label 130))))
