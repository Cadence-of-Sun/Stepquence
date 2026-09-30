(ns stepquence.run-test
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [stepquence.run :as run]
   [stepquence.seed :as seed]))

(def content seed/content)

(defn- sequence-titled [title]
  (some #(when (= title (:title %)) %) (vals (:sequences content))))

(def teeth  (sequence-titled "Brush teeth"))   ; has requirements
(def snack  (sequence-titled "Snack time"))    ; step 2 is a choice
(def dressed (sequence-titled "Get dressed"))  ; plain

(defn- advance-n [r sequence n]
  (nth (iterate #(run/advance % sequence) r) n))

(deftest new-run-starts-at-requirements-when-present
  (is (= :requirements (:phase (run/new-run teeth nil))))
  (is (= :steps (:phase (run/new-run dressed nil)))))

(deftest ready-is-gated-on-all-requirements
  (let [r (run/new-run teeth nil)]
    (is (not (run/can-advance? r teeth content)))
    (is (run/can-advance? (assoc r :checked (set (:requirements teeth))) teeth content))))

(deftest done-advances-and-finishes
  (let [r (advance-n (run/new-run dressed nil) dressed 2)]
    (is (= [:steps 2 2] ((juxt :phase :index :done-count) r))))
  (let [n (count (:steps dressed))
        r (advance-n (run/new-run dressed nil) dressed n)]
    (is (= [:finished n] ((juxt :phase :done-count) r)))))

(deftest back-is-view-only
  (let [r (-> (run/new-run dressed nil)
              (advance-n dressed 3)
              (run/back dressed)
              (run/back dressed))]
    (is (= 1 (:index r)))
    (is (= 3 (:done-count r)) "going back never undoes completion")
    (testing "Done on a revisited step moves on without lowering done-count"
      (is (= [2 3] ((juxt :index :done-count) (run/advance r dressed)))))))

(deftest back-from-first-step
  (testing "returns to requirements when the sequence has them"
    (let [r (-> (run/new-run teeth nil) (run/advance teeth) (run/back teeth))]
      (is (= :requirements (:phase r)))))
  (testing "is not possible without requirements"
    (let [r (run/new-run dressed nil)]
      (is (not (run/can-go-back? r dressed)))
      (is (= r (run/back r dressed))))))

(deftest choice-gates-done
  (let [r      (advance-n (run/new-run snack nil) snack 1)
        step   (run/current-step r snack)
        option (first (run/choice-options content step))]
    (is (= :choice (:kind step)))
    (is (not (run/can-advance? r snack content)))
    (is (run/can-advance? (assoc-in r [:choices (:id step)] (:id option)) snack content))
    (testing "a choice whose collection is missing is not a dead end"
      (let [content (assoc content :collections {})]
        (is (run/can-advance? r snack content))))))

(deftest resume-picks-up-at-first-undone-step
  (let [r (run/new-run teeth {:done-count 2 :choices {}})]
    (is (= [:steps 2 2] ((juxt :phase :index :done-count) r)))
    (testing "clamped when steps were removed since"
      (is (= 5 (:index (run/new-run teeth {:done-count 99 :choices {}})))))))

(def bedtime (sequence-titled "Bedtime")) ; step 5 links to "Brush teeth"

(deftest revisited-positions-offer-continue
  (let [r (-> (run/new-run dressed nil) (advance-n dressed 2) (run/back dressed))]
    (is (run/revisit? r))
    (is (= :continue (run/primary-action r dressed content #{(:id dressed)}))))
  (testing "requirements after going back from step 1"
    (let [r (-> (run/new-run teeth nil) (run/advance teeth) (run/back teeth))]
      (is (= :continue (run/primary-action r teeth content #{(:id teeth)})))))
  (testing "a fresh position is :done / :ready"
    (is (= :done (run/primary-action (run/new-run dressed nil) dressed content #{})))
    (is (= :ready (run/primary-action (run/new-run teeth nil) teeth content #{})))))

(deftest link-steps
  (let [r    (advance-n (run/new-run bedtime nil) bedtime 4)
        step (run/current-step r bedtime)]
    (is (= :link (:kind step)))
    (is (= "Brush teeth" (run/step-title content step)))
    (is (= :start (run/primary-action r bedtime content #{(:id bedtime)})))
    (testing "a link into a sequence already in progress is a plain step (no cycles)"
      (is (= :done (run/primary-action r bedtime content #{(:id bedtime) (:id teeth)}))))
    (testing "a link to a missing sequence is a plain step"
      (let [content (update content :sequences dissoc (:id teeth))]
        (is (= :done (run/primary-action r bedtime content #{(:id bedtime)})))))))

(deftest finish-the-timer-first
  (let [sq   {:id :s :requirements []
              :steps [{:id :t1 :kind :task :title "Brush" :timer-seconds 60 :timer-required? true}
                      {:id :t2 :kind :task :title "Rinse" :timer-seconds 60}]}
        r    (run/new-run sq nil)]
    (is (not (run/can-advance? r sq content)) "Done waits for the timer")
    (is (run/can-advance? (run/timer-finished r :t1) sq content) "once finished, Done works")
    (testing "a timer that isn't required never blocks"
      (is (run/can-advance? (run/advance (run/timer-finished r :t1) sq) sq content)))
    (testing "a revisited step isn't locked"
      (let [back (-> r (run/timer-finished :t1) (run/advance sq) (run/back sq))]
        (is (run/can-advance? (assoc back :timers-done #{}) sq content))))))
