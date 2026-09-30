(ns stepquence.share
  "Sharing chosen sequences between devices: export a selection (e.g. for a
  teacher or therapist) and merge one into this device's content.

  A shared file is self-contained: the chosen sequences, every sequence
  they link to (at any depth, so no link can dangle), the items and
  collections they use, and the groups they sit in. Pictures will travel
  the same way once they exist.

  Merging: ids are random UUIDs, so an incoming sequence whose id is
  already here *is* the same sequence (exported from here earlier, or
  shared back and forth). For each of those the parent chooses:
    :update  replace it in place (title, steps, requirements), keeping this
             device's group and schedule for it
    :copy    keep both: import it as a copy with fresh ids
  New sequences are simply added. Items and collections are added if
  missing; ones already here keep this device's version."
  (:require
   [cljs.reader :as reader]
   [malli.core :as m]
   [malli.error :as me]
   [stepquence.day :as day]
   [stepquence.edit :as edit]
   [stepquence.schema :as schema]))

(def format-version 1)

;; ---------------------------------------------------------------------------
;; Export

(defn- linked-targets [sequence]
  (keep #(when (= :link (:kind %)) (:sequence-id %)) (:steps sequence)))

(defn closure
  "`ids` plus every sequence they link to, at any depth (existing ones only)."
  [content ids]
  (loop [queue (vec ids) seen #{}]
    (if-let [id (first queue)]
      (if (or (seen id) (not (get-in content [:sequences id])))
        (recur (subvec queue 1) seen)
        (recur (into (subvec queue 1) (linked-targets (get-in content [:sequences id])))
               (conj seen id)))
      seen)))

(defn- used-collections [sequences]
  (into #{} (comp (mapcat :steps) (filter #(= :choice (:kind %))) (map :collection-id)) sequences))

(defn- used-items [content sequences]
  (into (set (mapcat :requirements sequences))
        (mapcat #(get-in content [:collections % :item-ids]))
        (used-collections sequences)))

(defn export-data
  "The shared file for sequences `ids` (and what they link to)."
  [content ids now]
  (let [all       (closure content ids)
        sequences (select-keys (:sequences content) all)]
    {:stepquence/sequences format-version
     :schema-version       schema/schema-version
     :exported-at          now
     :sequences            sequences
     ;; Only their groups, holding only them, in list order.
     :groups               (into []
                                 (keep (fn [g]
                                         (let [ids (filterv all (:sequence-ids g))]
                                           (when (seq ids) (assoc g :sequence-ids ids)))))
                                 (:groups content))
     :items                (select-keys (:items content) (used-items content (vals sequences)))
     :collections          (select-keys (:collections content) (used-collections (vals sequences)))
     :images               {}}))

(defn encode [content ids now]
  (pr-str (export-data content ids now)))

(defn- slug [s]
  (let [s (-> s .toLowerCase (.replace (js/RegExp. "[^a-z0-9]+" "g") "-") (.replace (js/RegExp. "^-|-$" "g") ""))]
    (if (seq s) s "sequence")))

(defn filename
  "Named after the sequence when there's one, else by date."
  [content ids now]
  (if (= 1 (count ids))
    (str "stepquence-" (slug (get-in content [:sequences (first ids) :title] "")) ".edn")
    (str "stepquence-sequences-" (day/today now 0) ".edn")))

;; ---------------------------------------------------------------------------
;; Import

(def ^:private Shared
  [:map
   [:sequences [:map-of :uuid schema/Sequence]]
   [:groups [:vector schema/Group]]
   [:items [:map-of :uuid schema/Item]]
   [:collections [:map-of :uuid schema/Collection]]])

(def ^:private shared-explainer (m/explainer Shared))

(defn file-kind
  "What an imported file is: :backup, :sequences, or nil."
  [text]
  (let [data (try (reader/read-string text) (catch :default _ nil))]
    (when (map? data)
      (cond
        (contains? data :stepquence/sequences)                                   :sequences
        (or (contains? data :stepquence/backup) (contains? data :stepwise/backup)) :backup))))

(defn decode
  "{:data …} for a usable shared-sequences file, else {:error msg}."
  [text]
  (let [data (try (reader/read-string text) (catch :default _ nil))]
    (cond
      (not (number? (:stepquence/sequences data)))
      {:error "That file isn't a Stepquence file."}

      (> (:stepquence/sequences data) format-version)
      {:error "That file comes from a newer version of Stepquence."}

      :else
      (if-let [problems (some-> (shared-explainer data) me/humanize)]
        (do (js/console.error "Shared file failed validation" (pr-str problems))
            {:error "That file is damaged or incomplete, so nothing was changed."})
        {:data data}))))

(defn plan
  "One row per incoming sequence, in the file's group order: whether it's
  already here (and under what title)."
  [content data]
  (let [ordered (distinct (concat (mapcat :sequence-ids (:groups data))
                                  (keys (:sequences data))))]
    (vec (for [id ordered
               :let [s (get-in data [:sequences id])]
               :when s]
           {:id          id
            :title       (:title s)
            :exists?     (contains? (:sequences content) id)
            :local-title (get-in content [:sequences id :title])}))))

(defn- remap-links [sequence id-map]
  (update sequence :steps
          (fn [steps]
            (mapv #(if (and (= :link (:kind %)) (id-map (:sequence-id %)))
                     (update % :sequence-id id-map)
                     %)
                  steps))))

(defn- fresh-step-ids [sequence new-id]
  (update sequence :steps (fn [steps] (mapv #(assoc % :id (new-id)) steps))))

(defn- group-for
  "Where a new or copied sequence goes: its file group (by id) if that group
  is here, else a new group made from the file's, else Ungrouped. Returns
  [content' group-id]."
  [content data sequence-id]
  (let [g (some #(when (some #{sequence-id} (:sequence-ids %)) %) (:groups data))]
    (cond
      (nil? g)                                        [(edit/ensure-ungrouped content) schema/ungrouped-id]
      (some #(= (:id g) (:id %)) (:groups content))  [content (:id g)]
      :else                                           [(edit/add-group content (:id g) (:name g)) (:id g)])))

(defn merge-into
  "Merge shared `data` into `content`. `choices` maps an existing
  sequence's id to :update or :copy (default :update). `new-id` makes
  fresh uuids. Returns {:content … :added n :updated n :copied n}."
  [content data choices new-id]
  (let [rows     (plan content data)
        choice   (fn [{:keys [id exists?]}]
                   (if exists? (get choices id :update) :new))
        ;; Copies get fresh ids; links inside the file then point at them.
        id-map   (into {} (keep (fn [row] (when (= :copy (choice row)) [(:id row) (new-id)]))) rows)
        content  (-> content
                     (update :items #(merge (:items data) %))
                     (update :collections #(merge (:collections data) %)))]
    (reduce
     (fn [{:keys [content] :as acc} {:keys [id] :as row}]
       (let [incoming (remap-links (get-in data [:sequences id]) id-map)]
         (case (choice row)
           :update (-> acc
                       (assoc-in [:content :sequences id]
                                 (assoc incoming :days (get-in content [:sequences id :days])))
                       (update :updated inc))
           :new    (let [[content gid] (group-for content data id)]
                     (-> acc
                         (assoc :content (edit/add-sequence content gid incoming))
                         (update :added inc)))
           :copy   (let [[content gid] (group-for content data id)
                         copy          (-> incoming
                                           (assoc :id (id-map id))
                                           (fresh-step-ids new-id))]
                     (-> acc
                         (assoc :content (edit/add-sequence content gid copy))
                         (update :copied inc))))))
     {:content content :added 0 :updated 0 :copied 0}
     rows)))
