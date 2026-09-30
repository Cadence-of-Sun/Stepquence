(ns stepquence.subs
  (:require
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [stepquence.day :as day]
   [stepquence.edit :as edit]
   [stepquence.run :as run]
   [stepquence.schema :as schema]
   [stepquence.share :as share]))

(re-frame/reg-sub
 ::content
 (fn [db]
   (:content db)))

(re-frame/reg-sub
 ::day
 (fn [db]
   (:day db)))

(re-frame/reg-sub
 ::parent?
 (fn [db]
   (get-in db [:ui :parent?])))

(re-frame/reg-sub
 ::pin-entry
 (fn [db]
   (get-in db [:ui :pin-entry])))

(re-frame/reg-sub
 ::screen
 (fn [db]
   (get-in db [:ui :screen])))

(re-frame/reg-sub
 ::nav-direction
 (fn [db]
   (get-in db [:ui :nav-direction] :forward)))

(re-frame/reg-sub
 ::theme-showing
 ;; The saved choice, else the system's.
 (fn [db]
   (or (get-in db [:settings :theme]) (get-in db [:ui :system-theme]))))

;; ---------------------------------------------------------------------------
;; Home

(re-frame/reg-sub
 ::today
 ;; Today's weekday (by the day boundary), or nil before storage loads.
 :<- [::day]
 (fn [{:keys [date]}]
   (some-> date day/weekday)))

(defn- annotate
  "A sequence with today's state: :done-today?, :done-count (steps done so
  far, for a resumable run), :today? (scheduled today) and :days-label."
  [{:keys [done resume]} today {:keys [id steps days] :as sequence}]
  (let [done? (contains? done id)]
    (assoc sequence
           :done-today? done?
           :done-count  (if done? (count steps) (get-in resume [id :done-count] 0))
           :today?      (or (nil? today) (contains? days today))
           :days-label  (edit/days-summary days))))

(re-frame/reg-sub
 ::all-groups
 ;; Every group with all its sequences (dangling ids skipped), annotated.
 :<- [::content]
 :<- [::day]
 :<- [::today]
 (fn [[{:keys [groups sequences]} d today]]
   (let [n (count groups)]
     (into []
           (map-indexed
            (fn [i group]
              (assoc group
                     :first?     (zero? i)
                     :last?      (= i (dec n))
                     :ungrouped? (= (:id group) schema/ungrouped-id)
                     :sequences  (into [] (comp (keep sequences) (map #(annotate d today %)))
                                       (:sequence-ids group)))))
           groups))))

(re-frame/reg-sub
 ::home-groups
 ;; What the child sees: sequences scheduled today (with steps), in groups
 ;; that have any.
 :<- [::all-groups]
 (fn [groups]
   (into []
         (keep (fn [group]
                 (let [seqs (filterv #(and (:today? %) (seq (:steps %))) (:sequences group))]
                   (when (seq seqs)
                     (assoc group :sequences seqs)))))
         groups)))

;; ---------------------------------------------------------------------------
;; Walkthrough

(re-frame/reg-sub
 ::run
 (fn [db]
   (:run db)))

(re-frame/reg-sub
 ::run-stack
 (fn [db]
   (:run-stack db)))

(re-frame/reg-sub
 ::run-sequence
 :<- [::content]
 :<- [::run]
 (fn [[content r]]
   (get-in content [:sequences (:sequence-id r)])))

(re-frame/reg-sub
 ::active-ids
 :<- [::run]
 :<- [::run-stack]
 (fn [[r stack]]
   (into #{} (map :sequence-id) (cons r stack))))

(re-frame/reg-sub
 ::parent-title
 ;; Title of the sequence that linked the current one, if any.
 :<- [::content]
 :<- [::run-stack]
 (fn [[content stack]]
   (when-let [parent (peek stack)]
     (get-in content [:sequences (:sequence-id parent) :title]))))

(re-frame/reg-sub
 ::phase
 :<- [::run]
 (fn [r]
   (:phase r)))

(re-frame/reg-sub
 ::current-step
 ;; With :title resolved (link steps show the linked sequence's title).
 :<- [::content]
 :<- [::run]
 :<- [::run-sequence]
 (fn [[content r sequence]]
   (when-let [step (run/current-step r sequence)]
     (assoc step :title (run/step-title content step)))))

(re-frame/reg-sub
 ::card-key
 ;; Changes on every move, including into and out of linked sequences.
 :<- [::run]
 (fn [{:keys [sequence-id phase index]}]
   (str sequence-id "-" (name phase) "-" index)))

(re-frame/reg-sub
 ::requirements
 :<- [::content]
 :<- [::run-sequence]
 :<- [::run]
 (fn [[content sequence r]]
   (for [item (run/requirement-items content sequence)]
     (assoc item :checked? (contains? (:checked r) (:id item))))))

(re-frame/reg-sub
 ::choice-options
 :<- [::content]
 :<- [::current-step]
 :<- [::run]
 (fn [[content step r]]
   (let [chosen (get-in r [:choices (:id step)])]
     (for [item (run/choice-options content step)]
       (assoc item :chosen? (= chosen (:id item)))))))

(re-frame/reg-sub
 ::primary
 ;; The primary button: {:action, :label, :enabled?, :gated?}.
 ;; Gated buttons (checklist, choice) start as "not yet".
 :<- [::run]
 :<- [::run-sequence]
 :<- [::content]
 :<- [::active-ids]
 :<- [::run-stack]
 (fn [[r sequence content active-ids stack]]
   (let [action (run/primary-action r sequence content active-ids)
         step   (run/current-step r sequence)]
     {:action   action
      :label    (case action
                  :ready    "Ready"
                  :done     "Done"
                  :start    "Start"
                  :continue "Continue"
                  :finish   (if (seq stack) "Continue" "Exit"))
      :arrow?   (or (= :continue action)
                    (and (= :finish action) (seq stack)))
      :enabled? (run/can-advance? r sequence content)
      :gated?   (and (#{:ready :done} action)
                     (or (= :requirements (:phase r))
                         (= :choice (:kind step))))})))

(re-frame/reg-sub
 ::can-go-back?
 :<- [::run]
 :<- [::run-sequence]
 (fn [[r sequence]]
   (run/can-go-back? r sequence)))

(re-frame/reg-sub
 ::position-label
 :<- [::run]
 :<- [::run-sequence]
 (fn [[{:keys [phase index]} sequence]]
   (case phase
     :requirements "Get ready"
     :steps        (str (inc index) " of " (count (:steps sequence)))
     nil)))

(re-frame/reg-sub
 ::up-next
 ;; What the "Next:" slot shows: the upcoming step only, or the finish
 ;; marker on the last step.
 :<- [::content]
 :<- [::run]
 :<- [::run-sequence]
 (fn [[content {:keys [phase index]} sequence]]
   (let [steps (:steps sequence)
         hint  (fn [step] {:kind :step :title (run/step-title content step)})]
     (case phase
       :requirements (hint (first steps))
       :steps        (if-let [step (get steps (inc index))]
                       (hint step)
                       {:kind :finish})
       nil))))

(re-frame/reg-sub
 ::progress-markers
 ;; One marker per position in the bar. Shared with the overview later.
 ;;   {:kind :requirements|:task|:choice|:link, :done? bool, :current? bool,
 ;;    :options [{:id, :state :chosen|:muted|:open}] for choices}
 :<- [::content]
 :<- [::run]
 :<- [::run-sequence]
 (fn [[content {:keys [phase index done-count ready? choices]} sequence]]
   (let [requirements-marker
         (when (seq (:requirements sequence))
           {:id       :requirements
            :kind     :requirements
            :target   :requirements
            :done?    ready?
            :current? (= :requirements phase)})

         step-marker
         (fn [i step]
           (let [marker {:id       (:id step)
                         :kind     (:kind step)
                         :target   i
                         :done?    (< i done-count)
                         :current? (and (= :steps phase) (= i index))}]
             (if (= :choice (:kind step))
               (let [chosen (get choices (:id step))]
                 (assoc marker :options
                        (for [item (run/choice-options content step)]
                          {:id    (:id item)
                           :state (cond
                                    (nil? chosen)          :open
                                    (= chosen (:id item)) :chosen
                                    :else                  :muted)})))
               marker)))]
     (cond-> []
       requirements-marker (conj requirements-marker)
       true                (into (map-indexed step-marker) (:steps sequence))))))

(re-frame/reg-sub
 ::overview?
 (fn [db]
   (get-in db [:ui :overview?])))

(re-frame/reg-sub
 ::overview-rows
 ;; The progress markers, each with its step's title; a choice row also
 ;; carries the chosen option's title.
 :<- [::progress-markers]
 :<- [::content]
 :<- [::run-sequence]
 :<- [::run]
 (fn [[markers content sequence r]]
   (let [steps (into {} (map (juxt :id identity)) (:steps sequence))]
     (for [{:keys [id kind] :as m} markers
           :let [step (steps id)]]
       (assoc m
              :title           (if (= :requirements kind)
                                 "What you need"
                                 (run/step-title content step))
              :description     (:description step)
              :image-id        (:image-id step)
              :detail          (when (= :choice kind)
                                 (get-in content [:items (get-in r [:choices id]) :title]))
              ;; For the editor:
              :collection-name (when (= :choice kind)
                                 (get-in content [:collections (:collection-id step) :name]))
              :item-count      (when (= :requirements kind)
                                 (count (:requirements sequence))))))))

(re-frame/reg-sub
 ::sequence-settings
 ;; For the editor's "Sequence" section.
 :<- [::content]
 :<- [::run-sequence]
 (fn [[content {:keys [id days image-id]}]]
   (when id
     {:id       id
      :image-id image-id
      :days     days
      :group-id (some #(when (some #{id} (:sequence-ids %)) (:id %)) (:groups content))
      :groups   (mapv #(select-keys % [:id :name]) (:groups content))
      :links-to (vec (edit/links-to content id))})))

(re-frame/reg-sub
 ::sequence-count
 :<- [::content]
 (fn [content]
   (count (:sequences content))))

(re-frame/reg-sub
 ::link-candidates
 :<- [::content]
 :<- [::run-sequence]
 (fn [[content {:keys [id]}]]
   (when id
     (mapv #(select-keys % [:id :title :steps]) (edit/linkable content id)))))

(re-frame/reg-sub
 ::can-add-step?
 :<- [::run-sequence]
 (fn [sequence]
   (edit/can-add-step? sequence)))

(re-frame/reg-sub
 ::can-delete-step?
 :<- [::run-sequence]
 (fn [sequence]
   (edit/can-delete-step? sequence)))

(re-frame/reg-sub
 ::selection
 (fn [db]
   (get-in db [:ui :selection])))

(re-frame/reg-sub
 ::selection-extras
 ;; Sequences that will come along because a selected one links to them.
 :<- [::content]
 :<- [::selection]
 (fn [[content selection]]
   (when (seq selection)
     (->> (remove selection (share/closure content selection))
          (keep #(get-in content [:sequences % :title]))
          sort
          vec))))

(re-frame/reg-sub
 ::picture-editor-state
 (fn [db]
   (get-in db [:ui :picture-editor])))

(re-frame/reg-sub
 ::picture-editor
 ;; The open picture dialog, with what it edits: a step of the previewed
 ;; sequence, or a sequence (card picture).
 :<- [::picture-editor-state]
 :<- [::run-sequence]
 :<- [::content]
 (fn [[{:keys [target] :as state} run-seq content]]
   (when state
     (assoc state :subject
            (if-let [step-id (:step-id target)]
              (some #(when (= step-id (:id %)) %) (:steps run-seq))
              (get-in content [:sequences (:sequence-id target)]))))))

(re-frame/reg-sub
 ::image-library
 ;; Every picture in the library, by name, with how many steps use it.
 :<- [::content]
 (fn [content]
   (->> (vals (:images content))
        (sort-by (comp str/lower-case :name))
        (mapv #(merge % {:uses (edit/image-uses content (:id %))}
                      (let [{:keys [steps sequences]} (edit/image-usage content (:id %))]
                        {:usage-steps steps :usage-sequences sequences}))))))

(re-frame/reg-sub
 ::library-status
 (fn [db]
   {:busy  (get-in db [:ui :library-busy] 0)
    :error (get-in db [:ui :library-error])}))

(re-frame/reg-sub
 ::selection-blockers
 ;; Selected sequences linked from outside the selection (can't delete).
 :<- [::content]
 :<- [::selection]
 (fn [[content selection]]
   (when (seq selection)
     (edit/delete-blockers content selection))))

(re-frame/reg-sub
 ::group-options
 :<- [::content]
 (fn [content]
   (mapv #(select-keys % [:id :name]) (:groups content))))

(re-frame/reg-sub
 ::link-closure-titles
 ;; Titles of the sequences that sharing `id` would bring along.
 :<- [::content]
 (fn [content [_ id]]
   (->> (disj (share/closure content [id]) id)
        (keep #(get-in content [:sequences % :title]))
        sort
        vec)))

(re-frame/reg-sub
 ::export
 (fn [db]
   (get-in db [:ui :export])))

(re-frame/reg-sub
 ::app-version
 (fn [db]
   (get-in db [:ui :app-version])))

(re-frame/reg-sub
 ::last-backup-at
 (fn [db]
   (get-in db [:meta :last-backup-at])))

(re-frame/reg-sub
 ::import
 (fn [db]
   (get-in db [:ui :import])))

(re-frame/reg-sub
 ::undo
 (fn [db]
   (get-in db [:ui :undo])))

(re-frame/reg-sub
 ::link-steps
 ;; Steps of the sequence the current :link step points at.
 :<- [::content]
 :<- [::current-step]
 (fn [[content step]]
   (get-in content [:sequences (:sequence-id step) :steps])))
