(ns stepquence.run
  "Pure walkthrough logic: starting a run and moving through it.
  See stepquence.schema/Run for the shape.")

(defn- step-count [sequence]
  (count (:steps sequence)))

(defn new-run
  "A fresh run of `sequence`. With a same-day `resume` entry, picks up at
  the first step not yet done and skips the requirements checklist."
  [sequence resume]
  (let [n          (step-count sequence)
        done-count (min (:done-count resume 0) n)]
    (cond
      (pos? done-count)
      {:sequence-id (:id sequence)
       :phase       :steps
       :index       (min done-count (dec n))
       :done-count  done-count
       :ready?      true
       :checked     #{}
       :choices     (:choices resume {})}

      :else
      (let [requirements? (boolean (seq (:requirements sequence)))]
        {:sequence-id (:id sequence)
         :phase       (if requirements? :requirements :steps)
         :index       0
         :done-count  0
         :ready?      (not requirements?)
         :checked     #{}
         :choices     {}}))))

(defn current-step [run sequence]
  (when (= :steps (:phase run))
    (get (:steps sequence) (:index run))))

(defn requirement-items
  "Requirement items that exist. Dangling ids are skipped."
  [content sequence]
  (into [] (keep (:items content)) (:requirements sequence)))

(defn choice-options
  "Items offered by a choice step. Dangling ids are skipped."
  [content step]
  (let [collection (get-in content [:collections (:collection-id step)])]
    (into [] (keep (:items content)) (:item-ids collection))))

(defn linked-sequence
  "The sequence a :link step points at, if it can be entered: it exists,
  has steps, and isn't already in progress in `active-ids` (no cycles).
  Otherwise the step behaves as a plain step."
  [content step active-ids]
  (when (= :link (:kind step))
    (let [target (get-in content [:sequences (:sequence-id step)])]
      (when (and (seq (:steps target))
                 (not (contains? active-ids (:id target))))
        target))))

(defn step-title
  "Display title. Link steps show the linked sequence's title."
  [content step]
  (if (= :link (:kind step))
    (get-in content [:sequences (:sequence-id step) :title] "")
    (:title step)))

(defn revisit?
  "Showing a position that is already complete (after going back)."
  [run]
  (case (:phase run)
    :requirements (:ready? run)
    :steps        (< (:index run) (:done-count run))
    false))

(defn can-advance?
  "Whether the primary button is enabled."
  [run sequence content]
  (case (:phase run)
    :requirements (every? (:checked run) (map :id (requirement-items content sequence)))
    :steps        (let [step (current-step run sequence)]
                    (or (not= :choice (:kind step))
                        (contains? (:choices run) (:id step))
                        ;; A choice with no options must not become a dead end.
                        (empty? (choice-options content step))))
    :finished     true
    false))

(defn primary-action
  "What the primary button does at this position:
    :ready    pass the requirements checklist
    :done     complete the step and move on
    :start    enter a linked sequence
    :continue move on past an already-completed position
    :finish   leave a finished run"
  [run sequence content active-ids]
  (cond
    (= :finished (:phase run))     :finish
    (revisit? run)                 :continue
    (= :requirements (:phase run)) :ready
    (linked-sequence content (current-step run sequence) active-ids) :start
    :else                          :done))

(defn advance
  "Ready (requirements) or Done (marks the current step complete and moves on)."
  [run sequence]
  (case (:phase run)
    :requirements (assoc run :phase :steps :index 0 :ready? true)
    :steps        (let [next-index (inc (:index run))
                        run        (update run :done-count max next-index)]
                    (if (< next-index (step-count sequence))
                      (assoc run :index next-index)
                      (assoc run :phase :finished)))
    run))

(defn can-go-back? [run sequence]
  (and (= :steps (:phase run))
       (or (pos? (:index run))
           (boolean (seq (:requirements sequence))))))

(defn back
  "View-only: never lowers :done-count."
  [run sequence]
  (cond
    (not (can-go-back? run sequence)) run
    (pos? (:index run))               (update run :index dec)
    :else                             (assoc run :phase :requirements)))

(defn resume-entry [run]
  {:done-count (:done-count run)
   :choices    (:choices run)})
