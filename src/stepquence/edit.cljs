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

(defn image-usage
  "{:steps n :sequences m :items k}: steps showing picture `image-id`,
  sequences using it as their card picture, and things (items) shown with
  it."
  [content image-id]
  {:steps     (image-uses content image-id)
   :sequences (count (filter #(= image-id (:image-id %)) (vals (:sequences content))))
   :items     (count (filter #(= image-id (:image-id %)) (vals (:items content))))})

(defn- map-items [content f]
  (cond-> content
    (:items content) (update :items #(into {} (map (fn [[id item]] [id (f item)])) %))))

(defn set-sequence-image [sequence image-id]
  (assoc sequence :image-id image-id))

(defn clear-sequence-image [sequence]
  (dissoc sequence :image-id))

(defn rename-image [content image-id name]
  (let [name (clip (str/trim name) schema/max-name)]
    (if (and (get-in content [:images image-id]) (not (str/blank? name)))
      (assoc-in content [:images image-id :name] name)
      content)))

(defn- map-sequences [content f]
  (update content :sequences #(into {} (map (fn [[id s]] [id (f s)])) %)))

(defn replace-image
  "Point every use of picture `from` (steps, sequence cards, things) at
  `to`. A step keeps its fit; a fill focus point is dropped (it was for the
  old picture)."
  [content from to]
  (if (and (not= from to) (get-in content [:images to]))
    (-> content
        (map-sequences (fn [s]
                         (cond-> (update s :steps
                                         (fn [steps]
                                           (mapv #(if (= from (:image-id %))
                                                    (-> % (assoc :image-id to) (dissoc :image-position))
                                                    %)
                                                 steps)))
                           (= from (:image-id s)) (assoc :image-id to))))
        (map-items #(cond-> % (= from (:image-id %)) (assoc :image-id to))))
    content))

(defn remove-image
  "Remove picture `image-id` from the library; the steps and cards that
  used it lose it (and fall back to text). Returns [content' undo]."
  [content image-id]
  (if-let [image (get-in content [:images image-id])]
    (let [step-refs (vec (for [s (vals (:sequences content))
                               step (:steps s)
                               :when (= image-id (:image-id step))]
                           [(:id s) (:id step) (select-keys step [:image-fit :image-position])]))
          seq-refs  (vec (keep #(when (= image-id (:image-id %)) (:id %)) (vals (:sequences content))))
          item-refs (vec (keep #(when (= image-id (:image-id %)) (:id %)) (vals (:items content))))]
      [(-> content
           (update :images dissoc image-id)
           (map-sequences (fn [s]
                            (cond-> (update s :steps
                                            (fn [steps]
                                              (mapv #(if (= image-id (:image-id %))
                                                       (dissoc % :image-id :image-fit :image-position)
                                                       %)
                                                    steps)))
                              (= image-id (:image-id s)) (dissoc :image-id))))
           (map-items #(cond-> % (= image-id (:image-id %)) (dissoc :image-id))))
       {:image image :step-refs step-refs :seq-refs seq-refs :item-refs item-refs}])
    [content nil]))

(defn restore-image
  "Undo remove-image: the picture returns, and so do its uses (where the
  step or sequence still exists)."
  [content {:keys [image step-refs seq-refs item-refs]}]
  (let [id      (:id image)
        content (assoc-in content [:images id] image)
        content (reduce (fn [c [sid step-id framing]]
                          (if (get-in c [:sequences sid])
                            (update-in c [:sequences sid] update-step step-id
                                       #(merge (assoc % :image-id id) framing))
                            c))
                        content step-refs)]
    (as-> content c
      (reduce (fn [c sid] (cond-> c (get-in c [:sequences sid]) (assoc-in [:sequences sid :image-id] id)))
              c seq-refs)
      (reduce (fn [c iid] (cond-> c (get-in c [:items iid]) (assoc-in [:items iid :image-id] id)))
              c item-refs))))

;; ---------------------------------------------------------------------------
;; Things (items) and lists (collections)
;;
;; An "item list" is an ordered vector of item ids with an owner: a
;; sequence's checklist ({:requirements sequence-id}) or a list used by
;; choice steps ({:collection collection-id}).

(defn- list-path [{:keys [requirements collection]}]
  (cond
    requirements [:sequences requirements :requirements]
    collection   [:collections collection :item-ids]))

(defn item-list [content target]
  (when-let [path (list-path target)]
    (get-in content path [])))

(defn- update-item-list [content target f]
  (let [path (list-path target)]
    (if (and path (get-in content (butlast path)))
      (update-in content path (fnil f []))
      content)))

(defn add-new-item
  "A new thing titled `title`, appended to the list."
  [content target item-id title]
  (-> content
      (assoc-in [:items item-id] {:id item-id :title title})
      (update-item-list target #(conj % item-id))))

(defn add-existing-item
  "Append an existing thing (not twice)."
  [content target item-id]
  (if (get-in content [:items item-id])
    (update-item-list content target #(if (some #{item-id} %) % (conj % item-id)))
    content))

(defn remove-from-list
  "Take a thing off the list; the thing itself stays."
  [content target item-id]
  (update-item-list content target #(into [] (remove #{item-id}) %)))

(defn move-in-list [content target from to]
  (update-item-list content target
                    (fn [ids]
                      (let [n (count ids)]
                        (if (and (< -1 from n) (< -1 to n) (not= from to))
                          (let [x       (get ids from)
                                without (into (subvec ids 0 from) (subvec ids (inc from)))]
                            (into (conj (subvec without 0 to) x) (subvec without to)))
                          ids)))))

(defn rename-item [content item-id title]
  (let [title (clip (str/trim title) schema/max-name)]
    (if (and (get-in content [:items item-id]) (not (str/blank? title)))
      (assoc-in content [:items item-id :title] title)
      content)))

(defn set-item-image [content item-id image-id]
  (if (get-in content [:items item-id])
    (if image-id
      (assoc-in content [:items item-id :image-id] image-id)
      (update-in content [:items item-id] dissoc :image-id))
    content))

(defn add-collection [content collection-id name]
  (assoc-in content [:collections collection-id] {:id collection-id :name name :item-ids []}))

(defn rename-collection [content collection-id name]
  (let [name (clip (str/trim name) schema/max-name)]
    (if (and (get-in content [:collections collection-id]) (not (str/blank? name)))
      (assoc-in content [:collections collection-id :name] name)
      content)))

(defn collection-uses
  "Choice steps using list `collection-id`: [{:title sequence :step n}]."
  [content collection-id]
  (vec (for [s (vals (:sequences content))
             [i step] (map-indexed vector (:steps s))
             :when (and (= :choice (:kind step)) (= collection-id (:collection-id step)))]
         {:title (:title s) :step (inc i)})))

(defn item-uses
  "How many checklists and lists a thing is on."
  [content item-id]
  {:checklists (count (filter #(some #{item-id} (:requirements %)) (vals (:sequences content))))
   :lists      (count (filter #(some #{item-id} (:item-ids %)) (vals (:collections content))))})

(defn set-choice-collection [sequence step-id collection-id]
  (update-step sequence step-id #(assoc % :collection-id collection-id)))

(defn add-choice-step
  "Appends a choice step offering list `collection-id` (unless full)."
  [sequence step-id collection-id title]
  (if (can-add-step? sequence)
    (update sequence :steps conj {:id step-id :kind :choice :title title :collection-id collection-id})
    sequence))

(defn- remove-at [v i]
  (into (subvec v 0 i) (subvec v (inc i))))

(defn- insert-at-index [v i x]
  (let [i (max 0 (min i (count v)))]
    (into (conj (subvec v 0 i) x) (subvec v i))))

(defn remove-item
  "Delete a thing: it comes off every checklist and list that has it.
  Returns [content' undo] (undo remembers where it was on each)."
  [content item-id]
  (if-let [item (get-in content [:items item-id])]
    (let [req-refs  (vec (for [s (vals (:sequences content))
                               :let [i (.indexOf (or (:requirements s) []) item-id)]
                               :when (>= i 0)]
                           [(:id s) i]))
          coll-refs (vec (for [c (vals (:collections content))
                               :let [i (.indexOf (or (:item-ids c) []) item-id)]
                               :when (>= i 0)]
                           [(:id c) i]))]
      [(as-> content c
         (update c :items dissoc item-id)
         (reduce (fn [c [sid i]] (update-in c [:sequences sid :requirements] remove-at i)) c req-refs)
         (reduce (fn [c [cid i]] (update-in c [:collections cid :item-ids] remove-at i)) c coll-refs))
       {:item item :req-refs req-refs :coll-refs coll-refs}])
    [content nil]))

(defn restore-item
  "Undo remove-item: the thing returns to each list at its position (where
  the sequence or list still exists)."
  [content {:keys [item req-refs coll-refs]}]
  (let [id (:id item)]
    (as-> (assoc-in content [:items id] item) c
      (reduce (fn [c [sid i]]
                (cond-> c (get-in c [:sequences sid])
                        (update-in [:sequences sid :requirements] insert-at-index i id)))
              c req-refs)
      (reduce (fn [c [cid i]]
                (cond-> c (get-in c [:collections cid])
                        (update-in [:collections cid :item-ids] insert-at-index i id)))
              c coll-refs))))

(defn remove-collection
  "Delete a list; refused while a choice step uses it (per the deletion
  policy: choose another list for that step first). [content' undo] or
  [content nil]."
  [content collection-id]
  (let [coll (get-in content [:collections collection-id])]
    (if (and coll (empty? (collection-uses content collection-id)))
      [(update content :collections dissoc collection-id) {:collection coll}]
      [content nil])))

(defn restore-collection [content {:keys [collection]}]
  (assoc-in content [:collections (:id collection)] collection))
