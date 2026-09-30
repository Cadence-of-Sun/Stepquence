(ns stepquence.share-test
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [stepquence.db]
   [stepquence.schema :as schema]
   [stepquence.seed :as seed]
   [stepquence.share :as share]))

(def content seed/content)
(def now (.getTime (js/Date. 2026 8 29 12 0)))

(defn- id-titled [content title]
  (some #(when (= title (:title %)) (:id %)) (vals (:sequences content))))

(def bedtime (id-titled content "Bedtime"))     ; links to Brush teeth
(def teeth (id-titled content "Brush teeth"))   ; requires 3 items
(def snack (id-titled content "Snack time"))    ; choice from Snacks

(defn- counter []
  (let [n (atom 0)]
    #(uuid (str "99999999-0000-4000-8000-" (.padStart (str (swap! n inc)) 12 "0")))))

(deftest exporting
  (testing "linked sequences come along"
    (is (= #{bedtime teeth} (share/closure content [bedtime]))))
  (let [data (share/export-data content [bedtime] now)]
    (is (= #{bedtime teeth} (set (keys (:sequences data)))))
    (is (= 3 (count (:items data))) "Brush teeth's requirements")
    (is (every? #(every? (set (keys (:sequences data))) (:sequence-ids %)) (:groups data))
        "groups hold only exported sequences"))
  (let [data (share/export-data content [snack] now)]
    (is (= 1 (count (:collections data))))
    (is (= 3 (count (:items data))) "the collection's items"))
  (is (= "stepquence-snack-time.edn" (share/filename content [snack] now)))
  (is (= "stepquence-sequences-2026-09-29.edn" (share/filename content [snack teeth] now))))

(deftest file-kinds
  (is (= :sequences (share/file-kind (share/encode content [snack] now))))
  (is (= :backup (share/file-kind (pr-str {:stepquence/backup 1}))))
  (is (nil? (share/file-kind "hello"))))

(deftest merging-into-an-empty-device
  (let [data  (:data (share/decode (share/encode content [bedtime] now)))
        empty {:sequences {} :groups [{:id schema/ungrouped-id :name "Ungrouped" :sequence-ids []}]
               :items {} :collections {} :images {}}
        {:keys [content added]} (share/merge-into empty data {} (counter))]
    (is (= 2 added))
    (is (= #{"Morning" "Evening" "Ungrouped"} (set (map :name (:groups content))))
        "their groups are created")
    (is (= 3 (count (:items content))))))

(deftest merging-sequences-already-here
  (let [data     (:data (share/decode (share/encode content [bedtime] now)))
        ;; This device renamed Bedtime and changed its schedule since.
        local    (-> content
                     (assoc-in [:sequences bedtime :title] "Night")
                     (assoc-in [:sequences bedtime :days] #{:sat}))]
    (testing "update: replaced in place, keeping this device's schedule"
      (let [{:keys [content updated]} (share/merge-into local data {} (counter))]
        (is (= 2 updated))
        (is (= "Bedtime" (get-in content [:sequences bedtime :title])))
        (is (= #{:sat} (get-in content [:sequences bedtime :days])))
        (is (= (count (:sequences local)) (count (:sequences content))))))
    (testing "keep both: copies with fresh ids, links pointing at the copies"
      (let [{:keys [content copied]} (share/merge-into local data {bedtime :copy teeth :copy} (counter))
            copy-bed   (some #(when (and (= "Bedtime" (:title %)) (not= bedtime (:id %))) %) (vals (:sequences content)))
            copy-teeth (some #(when (and (= "Brush teeth" (:title %)) (not= teeth (:id %))) %) (vals (:sequences content)))
            link       (some #(when (= :link (:kind %)) %) (:steps copy-bed))]
        (is (= 2 copied))
        (is (= "Night" (get-in content [:sequences bedtime :title])) "the original is untouched")
        (is (= (:id copy-teeth) (:sequence-id link)))
        (is (not-any? (set (map :id (get-in local [:sequences bedtime :steps]))) (map :id (:steps copy-bed)))
            "copied steps get fresh ids")
        (is (nil? (schema/explain-app-db (assoc stepquence.db/default-db :content content))))))))

(deftest rejects-other-files
  (is (re-find #"isn't a Stepquence file" (:error (share/decode "hello"))))
  (is (re-find #"damaged" (:error (share/decode (pr-str {:stepquence/sequences 1 :sequences {1 2}}))))))

(deftest carries-only-the-pictures-in-use
  (let [img-a   (random-uuid)
        img-b   (random-uuid)
        content (-> content
                    (assoc :images {img-a {:id img-a :name "a" :source :local :width 4 :height 3}
                                    img-b {:id img-b :name "b" :source :local :width 4 :height 3}})
                    (assoc-in [:sequences teeth :steps 0 :image-id] img-a)
                    (assoc-in [:sequences snack :image-id] img-b))]
    (testing "a linked sequence's step pictures come along"
      (is (= #{img-a} (share/used-images content [bedtime]))))
    (testing "a sequence's own card picture"
      (is (= #{img-b} (share/used-images content [snack]))))
    (let [data (:data (share/decode (share/encode content [bedtime] now {img-a {:full "data:image/webp;base64,AA" :thumb "data:image/webp;base64,BB"}})))]
      (is (= #{img-a} (set (keys (:images data)))))
      (testing "merging adds the picture records"
        (let [empty {:sequences {} :groups [] :items {} :collections {} :images {}}]
          (is (contains? (:images (:content (share/merge-into empty data {} (counter)))) img-a)))))))
