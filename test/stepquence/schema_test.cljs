(ns stepquence.schema-test
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [stepquence.db :as db]
   [stepquence.schema :as schema]))

(deftest default-db-is-valid
  (is (nil? (schema/explain-app-db db/default-db))))

(deftest step-cap-is-enforced
  (let [seq-id (-> db/default-db :content :sequences keys first)
        steps  (vec (for [_ (range (inc schema/max-steps))]
                      {:id (random-uuid) :kind :task :title "Step"}))
        db     (assoc-in db/default-db [:content :sequences seq-id :steps] steps)]
    (is (some? (schema/explain-app-db db)))))

(deftest steps-dispatch-on-kind
  (testing "a choice step must reference a collection"
    (let [seq-id (-> db/default-db :content :sequences keys first)
          db     (assoc-in db/default-db [:content :sequences seq-id :steps]
                           [{:id (random-uuid) :kind :choice :title "Pick one"}])]
      (is (some? (schema/explain-app-db db))))))

(deftest text-length-caps
  (let [seq-id (-> db/default-db :content :sequences keys first)
        path   [:content :sequences seq-id :steps 0]]
    (is (some? (schema/explain-app-db
                (assoc-in db/default-db (conj path :title)
                          (apply str (repeat (inc schema/max-step-title) "x"))))))
    (is (some? (schema/explain-app-db
                (assoc-in db/default-db (conj path :description)
                          (apply str (repeat (inc schema/max-description) "x"))))))))
