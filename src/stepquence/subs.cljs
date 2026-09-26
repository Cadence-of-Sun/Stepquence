(ns stepquence.subs
  (:require
   [re-frame.core :as re-frame]
   [stepquence.run :as run]))

(re-frame/reg-sub
 ::content
 (fn [db]
   (:content db)))

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
 ::home-groups
 :<- [::content]
 (fn [{:keys [groups sequences]}]
   ;; Resolve ids defensively: dangling references are skipped. Sequences
   ;; without steps and empty groups are hidden from the child.
   (into []
         (keep (fn [group]
                 (let [seqs (into []
                                  (comp (keep sequences) (filter (comp seq :steps)))
                                  (:sequence-ids group))]
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
            :done?    ready?
            :current? (= :requirements phase)})

         step-marker
         (fn [i step]
           (let [marker {:id       (:id step)
                         :kind     (:kind step)
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
 ::link-steps
 ;; Steps of the sequence the current :link step points at.
 :<- [::content]
 :<- [::current-step]
 (fn [[content step]]
   (get-in content [:sequences (:sequence-id step) :steps])))
