(ns stepquence.edit
  "Pure content edits used by parent mode. Each takes and returns a
  sequence (or content) so they can be tested without re-frame."
  (:require
   [clojure.string :as str]
   [stepquence.schema :as schema]))

(defn- clip [s max]
  (if (> (count s) max) (subs s 0 max) s))

(defn- index-of-step [sequence step-id]
  (first (keep-indexed (fn [i s] (when (= step-id (:id s)) i)) (:steps sequence))))

(defn- update-step [sequence step-id f]
  (if-let [i (index-of-step sequence step-id)]
    (update-in sequence [:steps i] f)
    sequence))

(defn set-step-title
  "Blank titles are refused (the step keeps its old title)."
  [sequence step-id title]
  (let [title (clip (str/trim title) schema/max-step-title)]
    (if (str/blank? title)
      sequence
      (update-step sequence step-id #(assoc % :title title)))))

(defn set-step-description
  "A blank description removes it."
  [sequence step-id description]
  (let [description (clip (str/trim description) schema/max-description)]
    (update-step sequence step-id
                 #(if (str/blank? description)
                    (dissoc % :description)
                    (assoc % :description description)))))

(defn set-sequence-title [sequence title]
  (let [title (clip (str/trim title) schema/max-name)]
    (if (str/blank? title)
      sequence
      (assoc sequence :title title))))

(defn can-add-step? [sequence]
  (< (count (:steps sequence)) schema/max-steps))

(defn add-task-step
  "Appends a task step (unless the sequence is full)."
  [sequence step-id title]
  (if (can-add-step? sequence)
    (update sequence :steps conj {:id step-id :kind :task :title title})
    sequence))

(defn can-delete-step?
  "A sequence keeps at least one step."
  [sequence]
  (> (count (:steps sequence)) 1))

(defn delete-step
  "Returns [sequence' {:index i :step removed}], or [sequence nil] when the
  step can't be deleted. The second value is what undo needs."
  [sequence step-id]
  (let [i (index-of-step sequence step-id)]
    (if (and i (can-delete-step? sequence))
      [(update sequence :steps #(into (subvec % 0 i) (subvec % (inc i))))
       {:index i :step (get-in sequence [:steps i])}]
      [sequence nil])))

(defn restore-step
  "Undo a delete: put `step` back at `index` (clamped). Refused if the
  sequence has filled up since."
  [sequence {:keys [index step]}]
  (if (can-add-step? sequence)
    (let [steps (:steps sequence)
          i     (min index (count steps))]
      (assoc sequence :steps (into (conj (subvec steps 0 i) step) (subvec steps i))))
    sequence))

(defn index-after-move
  "Where the step at `index` ends up after moving `from` to `to`."
  [index from to]
  (cond
    (= index from)                    to
    (and (< from index) (<= index to)) (dec index)
    (and (<= to index) (< index from)) (inc index)
    :else                             index))

(defn index-after-delete [index deleted]
  (if (> index deleted) (dec index) index))

(defn index-after-insert [index inserted]
  (if (>= index inserted) (inc index) index))

(defn move-step
  "Move the step at index `from` to index `to`."
  [sequence from to]
  (let [steps (:steps sequence)
        n     (count steps)]
    (if (and (< -1 from n) (< -1 to n) (not= from to))
      (let [step    (get steps from)
            without (into (subvec steps 0 from) (subvec steps (inc from)))]
        (assoc sequence :steps (into (conj (subvec without 0 to) step) (subvec without to))))
      sequence)))
