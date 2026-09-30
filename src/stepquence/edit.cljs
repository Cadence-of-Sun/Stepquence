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

;; ---------------------------------------------------------------------------
;; Sequences and groups. These take and return the whole content map.

(def every-day #{:mon :tue :wed :thu :fri :sat :sun})
(def weekdays #{:mon :tue :wed :thu :fri})
(def weekends #{:sat :sun})

(defn days-summary
  "Short label for a schedule."
  [days]
  (cond
    (= days every-day) "Every day"
    (= days weekdays)  "Weekdays"
    (= days weekends)  "Weekends"
    (empty? days)      "No days"
    :else              (->> [:mon :tue :wed :thu :fri :sat :sun]
                            (filter days)
                            (map #(str/capitalize (name %)))
                            (str/join ", "))))

(defn new-sequence
  "A fresh sequence: one step, every day."
  [sequence-id step-id]
  {:id           sequence-id
   :title        "New sequence"
   :days         every-day
   :requirements []
   :steps        [{:id step-id :kind :task :title "New step"}]})

(defn- group-index [content group-id]
  (first (keep-indexed (fn [i g] (when (= group-id (:id g)) i)) (:groups content))))

(defn- group-index-of-sequence [content sequence-id]
  (first (keep-indexed (fn [i g] (when (some #{sequence-id} (:sequence-ids g)) i))
                       (:groups content))))

(defn- remove-id [ids id]
  (into [] (remove #{id}) ids))

(defn- insert-at [v i x]
  (let [i (max 0 (min i (count v)))]
    (into (conj (subvec v 0 i) x) (subvec v i))))

(defn ensure-ungrouped
  "The Ungrouped group always exists (imports may lack it); it goes last."
  [content]
  (if (group-index content schema/ungrouped-id)
    content
    (update content :groups conj {:id schema/ungrouped-id :name "Ungrouped" :sequence-ids []})))

(defn add-sequence
  "Add `sequence` at the end of group `group-id` (Ungrouped if missing)."
  [content group-id sequence]
  (let [content (ensure-ungrouped content)
        gi      (or (group-index content group-id) (group-index content schema/ungrouped-id))]
    (-> content
        (assoc-in [:sequences (:id sequence)] sequence)
        (update-in [:groups gi :sequence-ids] conj (:id sequence)))))

(defn links-to
  "Steps in other sequences that link to `sequence-id`: [{:title :step}]
  with a 1-based step number. Deleting a linked sequence would leave these
  dangling, so deletion is refused while there are any."
  [content sequence-id]
  (for [[_ s] (:sequences content)
        :when (not= sequence-id (:id s))
        [i step] (map-indexed vector (:steps s))
        :when (and (= :link (:kind step)) (= sequence-id (:sequence-id step)))]
    {:title (:title s) :step (inc i)}))

(defn- remove-sequence-unchecked
  "Remove without checking links. [content' undo], or [content nil] if
  there's no such sequence."
  [content sequence-id]
  (let [sequence (get-in content [:sequences sequence-id])
        gi       (group-index-of-sequence content sequence-id)]
    (if sequence
      [(cond-> (update content :sequences dissoc sequence-id)
         gi (update-in [:groups gi :sequence-ids] remove-id sequence-id))
       {:sequence sequence
        :group-id (when gi (get-in content [:groups gi :id]))
        :index    (when gi (.indexOf (get-in content [:groups gi :sequence-ids]) sequence-id))}]
      [content nil])))

(defn remove-sequence
  "Returns [content' undo], or [content nil] if it can't be removed."
  [content sequence-id]
  (if (empty? (links-to content sequence-id))
    (remove-sequence-unchecked content sequence-id)
    [content nil]))

(defn restore-sequence
  "Undo remove-sequence. Back into its group at its position, or into
  Ungrouped if that group has gone since."
  [content {:keys [sequence group-id index]}]
  (let [content (ensure-ungrouped content)
        gi      (or (group-index content group-id) (group-index content schema/ungrouped-id))]
    (-> content
        (assoc-in [:sequences (:id sequence)] sequence)
        (update-in [:groups gi :sequence-ids] insert-at (or index 0) (:id sequence)))))

(defn move-sequence-to-group
  "Move a sequence to the end of another group."
  [content sequence-id group-id]
  (let [from (group-index-of-sequence content sequence-id)
        to   (group-index content group-id)]
    (if (and to (not= from to))
      (cond-> content
        from (update-in [:groups from :sequence-ids] remove-id sequence-id)
        true (update-in [:groups to :sequence-ids] conj sequence-id))
      content)))

(defn set-days [sequence days]
  (assoc sequence :days (set (filter every-day days))))

(defn add-group
  "Adds a group before Ungrouped (which stays last)."
  [content group-id name]
  (let [content (ensure-ungrouped content)
        gi      (group-index content schema/ungrouped-id)]
    (update content :groups insert-at gi {:id group-id :name name :sequence-ids []})))

(defn rename-group [content group-id name]
  (let [name (clip (str/trim name) schema/max-name)
        gi   (group-index content group-id)]
    (if (and gi (not (str/blank? name)))
      (assoc-in content [:groups gi :name] name)
      content)))

(defn move-group
  "Move a group up (-1) or down (+1)."
  [content group-id delta]
  (let [groups (:groups content)
        i      (group-index content group-id)
        j      (when i (+ i delta))]
    (if (and j (< -1 j (count groups)))
      (assoc content :groups (-> groups (assoc i (groups j)) (assoc j (groups i))))
      content)))

(defn remove-group
  "Its sequences move to the end of Ungrouped (which can't be removed).
  Returns [content' undo] or [content nil]."
  [content group-id]
  (let [content (ensure-ungrouped content)
        gi      (group-index content group-id)]
    (if (and gi (not= group-id schema/ungrouped-id))
      (let [group   (get-in content [:groups gi])
            content (update content :groups #(into (subvec % 0 gi) (subvec % (inc gi))))
            ui      (group-index content schema/ungrouped-id)]
        [(update-in content [:groups ui :sequence-ids] into (:sequence-ids group))
         {:group group :index gi}])
      [content nil])))

(defn restore-group
  "Undo remove-group: the group returns at its position and takes back
  those of its sequences that are still in Ungrouped."
  [content {:keys [group index]}]
  (let [content  (ensure-ungrouped content)
        ui       (group-index content schema/ungrouped-id)
        in-ungrp (set (get-in content [:groups ui :sequence-ids]))
        back     (filterv in-ungrp (:sequence-ids group))]
    (-> content
        (update-in [:groups ui :sequence-ids] #(into [] (remove (set back)) %))
        (update :groups insert-at index (assoc group :sequence-ids back)))))

;; ---------------------------------------------------------------------------
;; Link steps

(defn- linked-ids
  "Sequences that `sequence-id` links to directly."
  [content sequence-id]
  (into #{} (comp (filter #(= :link (:kind %))) (map :sequence-id))
        (get-in content [:sequences sequence-id :steps])))

(defn reaches?
  "Whether walking `from` can lead into `to` through link steps (at any
  depth)."
  [content from to]
  (loop [queue [from] seen #{}]
    (if-let [id (first queue)]
      (cond
        (= id to)           true
        (contains? seen id) (recur (rest queue) seen)
        :else               (recur (concat (rest queue) (linked-ids content id)) (conj seen id)))
      false)))

(defn linkable
  "Sequences a step of `sequence-id` may link to, in list order: not itself,
  and none that already lead back to it (that would make a loop)."
  [content sequence-id]
  (into []
        (comp (mapcat :sequence-ids)
              (keep (:sequences content))
              (remove #(= sequence-id (:id %)))
              (remove #(reaches? content (:id %) sequence-id)))
        (:groups content)))

(defn add-link-step
  "Appends a step that walks through `target-id` (unless full)."
  [sequence step-id target-id]
  (if (can-add-step? sequence)
    (update sequence :steps conj {:id step-id :kind :link :sequence-id target-id})
    sequence))

;; ---------------------------------------------------------------------------
;; Several sequences at once (select mode)

(defn delete-blockers
  "Selected sequences that can't be deleted because a sequence outside the
  selection links to them: [{:title … :linked-from [titles]}]."
  [content ids]
  (let [ids (set ids)]
    (vec (for [id ids
               :let [from (->> (:sequences content)
                               vals
                               (remove #(ids (:id %)))
                               (filter #(some (fn [st] (and (= :link (:kind st)) (= id (:sequence-id st))))
                                              (:steps %)))
                               (map :title)
                               sort
                               vec)]
               :when (seq from)]
           {:title (get-in content [:sequences id :title]) :linked-from from}))))

(defn remove-sequences
  "Remove all of `ids` at once. Links among them don't block, since both
  ends go. Returns [content' undos] (undos in removal order), or
  [content nil] if any is linked from outside the selection."
  [content ids]
  (if (seq (delete-blockers content ids))
    [content nil]
    (reduce (fn [[c undos] id]
              (let [[c' undo] (remove-sequence-unchecked c id)]
                [c' (cond-> undos undo (conj undo))]))
            [content []]
            (sort-by str ids))))

(defn restore-sequences
  "Undo remove-sequences: restore in reverse order."
  [content undos]
  (reduce restore-sequence content (reverse undos)))

(defn move-sequences-to-group
  "Move several sequences to the end of a group, keeping their list order."
  [content ids group-id]
  (let [ids     (set ids)
        ordered (filter ids (mapcat :sequence-ids (:groups content)))]
    (reduce #(move-sequence-to-group %1 %2 group-id) content ordered)))

;; ---------------------------------------------------------------------------
;; Step pictures

(defn set-step-image
  "Point a task step at library picture `image-id`, framed to fit (the
  whole picture shows) until the parent chooses fill."
  [sequence step-id image-id]
  (update-step sequence step-id
               #(-> %
                    (assoc :image-id image-id :image-fit :contain)
                    (dissoc :image-position))))

(defn clear-step-image [sequence step-id]
  (update-step sequence step-id #(dissoc % :image-id :image-fit :image-position)))

(defn set-step-image-fit
  "`fit` is :contain (fit: whole picture) or :cover (fill: frame filled,
  edges cropped around :image-position)."
  [sequence step-id fit]
  (update-step sequence step-id #(assoc % :image-fit fit)))

(defn set-step-image-position
  "Where a filled picture is centered, as percentages (0-100), e.g. the
  point tapped on the preview."
  [sequence step-id x y]
  (let [pct #(str (js/Math.round (max 0 (min 100 %))) "%")]
    (update-step sequence step-id #(assoc % :image-position (str (pct x) " " (pct y))))))

(defn image-uses
  "How many steps use picture `image-id`, across all sequences."
  [content image-id]
  (count (for [s (vals (:sequences content))
               step (:steps s)
               :when (= image-id (:image-id step))]
           step)))
