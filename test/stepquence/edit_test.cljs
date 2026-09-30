(ns stepquence.edit-test
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [stepquence.edit :as edit]
   [stepquence.schema :as schema]))

(defn- steps [n]
  (vec (for [i (range n)] {:id (keyword (str "s" i)) :kind :task :title (str "Step " i)})))

(def sq {:id :seq :title "Seq" :steps (steps 3)})

(defn- titles [sequence] (mapv :title (:steps sequence)))

(deftest step-titles
  (is (= "Hello" (-> (edit/set-step-title sq :s1 "  Hello ") :steps (get 1) :title)))
  (testing "blank is refused"
    (is (= sq (edit/set-step-title sq :s1 "   "))))
  (testing "clipped to the cap"
    (is (= schema/max-step-title
           (count (-> (edit/set-step-title sq :s0 (apply str (repeat 99 "x"))) :steps first :title))))))

(deftest descriptions
  (let [with (edit/set-step-description sq :s0 "Gently")]
    (is (= "Gently" (-> with :steps first :description)))
    (is (not (contains? (-> (edit/set-step-description with :s0 " ") :steps first) :description)))))

(deftest adding-respects-the-cap
  (is (= 4 (count (:steps (edit/add-task-step sq :new "New step")))))
  (let [full {:steps (steps schema/max-steps)}]
    (is (not (edit/can-add-step? full)))
    (is (= full (edit/add-task-step full :new "New step")))))

(deftest delete-and-undo
  (let [[after undo] (edit/delete-step sq :s1)]
    (is (= ["Step 0" "Step 2"] (titles after)))
    (is (= {:index 1 :step (get (:steps sq) 1)} undo))
    (is (= sq (edit/restore-step after undo))))
  (testing "the last step can't be deleted"
    (let [one {:steps (steps 1)}]
      (is (= [one nil] (edit/delete-step one :s0))))))

(deftest moving
  (is (= ["Step 1" "Step 2" "Step 0"] (titles (edit/move-step sq 0 2))))
  (is (= ["Step 2" "Step 0" "Step 1"] (titles (edit/move-step sq 2 0))))
  (is (= sq (edit/move-step sq 1 1)))
  (is (= sq (edit/move-step sq 0 7))))

(deftest following-a-step-through-edits
  (testing "moves"
    (is (= 2 (edit/index-after-move 0 0 2)))
    (is (= 0 (edit/index-after-move 1 0 2)))
    (is (= 2 (edit/index-after-move 1 2 0)))
    (is (= 3 (edit/index-after-move 3 0 2))))
  (testing "deletes and inserts"
    (is (= 1 (edit/index-after-delete 2 0)))
    (is (= 2 (edit/index-after-delete 2 2)))
    (is (= 3 (edit/index-after-insert 2 1)))
    (is (= 1 (edit/index-after-insert 1 3)))))

;; Sequences and groups

(def ungrouped stepquence.schema/ungrouped-id)

(def content
  {:sequences {:a {:id :a :title "A" :steps [{:id :a1 :kind :task :title "x"}]}
               :b {:id :b :title "B" :steps [{:id :b1 :kind :link :sequence-id :a}]}
               :c {:id :c :title "C" :steps [{:id :c1 :kind :task :title "y"}]}}
   :groups    [{:id :g1 :name "Morning" :sequence-ids [:a :b]}
               {:id :g2 :name "Evening" :sequence-ids [:c]}
               {:id ungrouped :name "Ungrouped" :sequence-ids []}]})

(defn- group-ids [content] (mapv (juxt :id :sequence-ids) (:groups content)))

(deftest schedule-labels
  (is (= "Every day" (edit/days-summary edit/every-day)))
  (is (= "Weekdays" (edit/days-summary #{:mon :tue :wed :thu :fri})))
  (is (= "Mon, Wed" (edit/days-summary #{:wed :mon})))
  (is (= "No days" (edit/days-summary #{}))))

(deftest removing-sequences
  (testing "a linked sequence can't be removed"
    (is (= [{:title "B" :step 1}] (vec (edit/links-to content :a))))
    (is (= [content nil] (edit/remove-sequence content :a))))
  (let [[after undo] (edit/remove-sequence content :b)]
    (is (not (contains? (:sequences after) :b)))
    (is (= [:a] (-> after :groups first :sequence-ids)))
    (is (= content (edit/restore-sequence after undo))))
  (testing "restoring when its group has gone lands in Ungrouped"
    (let [[after undo] (edit/remove-sequence content :c)
          [after _]    (edit/remove-group after :g2)]
      (is (= [:c] (-> (edit/restore-sequence after undo) :groups last :sequence-ids))))))

(deftest groups
  (is (= [:g2 :new ungrouped]
         (mapv :id (:groups (edit/add-group (update content :groups subvec 1) :new "New"))))
      "new groups go before Ungrouped")
  (is (= "Mornings" (-> (edit/rename-group content :g1 " Mornings ") :groups first :name)))
  (is (= content (edit/rename-group content :g1 "  ")))
  (is (= [:g2 :g1 ungrouped] (mapv :id (:groups (edit/move-group content :g1 1)))))
  (is (= content (edit/move-group content :g1 -1)))
  (testing "removing a group moves its sequences to Ungrouped; undo moves them back"
    (let [[after undo] (edit/remove-group content :g1)]
      (is (= [[:g2 [:c]] [ungrouped [:a :b]]] (group-ids after)))
      (is (= (group-ids content) (group-ids (edit/restore-group after undo))))))
  (is (= [content nil] (edit/remove-group content ungrouped)) "Ungrouped stays")
  (is (= [[:g1 [:a :b :c]] [:g2 []] [ungrouped []]]
         (group-ids (edit/move-sequence-to-group content :c :g1)))))

(deftest linking
  ;; In `content`, :b links to :a.
  (is (edit/reaches? content :b :a))
  (is (not (edit/reaches? content :a :b)))
  (is (= [:a :c] (mapv :id (edit/linkable content :b))))
  (testing "no loops: :a can't link to :b, which already leads back to :a"
    (is (= [:c] (mapv :id (edit/linkable content :a)))))
  (testing "deeper loops too: c -> b -> a, so :a can't link to :c either"
    (let [content (update-in content [:sequences :c :steps] conj {:id :c2 :kind :link :sequence-id :b})]
      (is (= [] (mapv :id (edit/linkable content :a)))))))

(deftest several-sequences
  ;; In `content`, :b links to :a.
  (testing "a sequence linked from outside the selection blocks deleting"
    (is (= [{:title "A" :linked-from ["B"]}] (edit/delete-blockers content [:a :c])))
    (is (= [content nil] (edit/remove-sequences content [:a :c]))))
  (testing "linker and linked selected together can go"
    (let [[after undos] (edit/remove-sequences content [:a :b])]
      (is (= [:c] (keys (:sequences after))))
      (is (= 2 (count undos)))
      (is (= content (edit/restore-sequences after undos)) "undo puts them back exactly")))
  (is (= [[:g1 [:b]] [:g2 [:c :a]] [ungrouped []]]
         (group-ids (edit/move-sequences-to-group content [:a] :g2))))
  (is (= [[:g1 []] [:g2 []] [ungrouped [:a :b :c]]]
         (group-ids (edit/move-sequences-to-group content [:c :a :b] ungrouped)))
      "list order is kept"))

(deftest step-pictures
  (let [with (edit/set-step-image sq :s1 :img)]
    (is (= {:image-id :img :image-fit :contain} (select-keys (get-in with [:steps 1]) [:image-id :image-fit :image-position])))
    (is (= :cover (-> (edit/set-step-image-fit with :s1 :cover) :steps (get 1) :image-fit)))
    (is (= "30% 100%" (-> (edit/set-step-image-position with :s1 29.6 140) :steps (get 1) :image-position))
        "rounded, clamped")
    (is (= (get-in sq [:steps 1]) (get-in (edit/clear-step-image with :s1) [:steps 1])))
    (testing "a new picture starts fit, dropping an old focus point"
      (let [focused (edit/set-step-image-position with :s1 10 10)]
        (is (not (contains? (get-in (edit/set-step-image focused :s1 :img2) [:steps 1]) :image-position)))))))
