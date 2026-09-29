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
