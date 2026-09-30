(ns stepquence.events
  (:require
   [re-frame.core :as re-frame]
   [stepquence.config :as config]
   [stepquence.backup :as backup]
   [stepquence.day :as day]
   [stepquence.db :as db]
   [stepquence.edit :as edit]
   [stepquence.fx]
   [stepquence.images]
   [stepquence.run :as run]
   [stepquence.schema :as schema]
   [stepquence.share :as share]
   [stepquence.storage :as storage]
   [stepquence.sound]
   [stepquence.timer :as timer]
   [stepquence.transfer]
   [day8.re-frame.tracing :refer-macros [fn-traced]]))

(def check-app-db
  "Dev only: report schema violations after every event."
  (re-frame/->interceptor
   :id :check-app-db
   :after (fn [context]
            (let [event (get-in context [:coeffects :event])
                  db    (get-in context [:effects :db])]
              (when-let [errors (and db (schema/explain-app-db db))]
                (js/console.error "app-db invalid after" (pr-str event) (clj->js errors))))
            context)))

(when config/debug?
  (re-frame/reg-global-interceptor check-app-db))

(def persist
  "Schedule a (debounced) save whenever an event changes a persisted
  slice. Inactive until storage has loaded, so defaults can never
  overwrite stored data."
  (re-frame/->interceptor
   :id :persist
   :after (fn [context]
            (let [before (get-in context [:coeffects :db])
                  after  (get-in context [:effects :db])]
              (if (and after
                       (get-in after [:ui :persist?])
                       (not-every? #(identical? (get before %) (get after %))
                                   schema/persisted-keys))
                (update-in context [:effects :fx] (fnil conj []) [:storage/save after])
                context)))))

(re-frame/reg-global-interceptor persist)

(defn- roll-day
  "Start a fresh per-day slice if the date (by the day boundary) changed."
  [db now]
  (update db :day day/roll-over (day/today now (get-in db [:settings :day-boundary-hour]))))

;; ---------------------------------------------------------------------------
;; Boot: loading state -> read storage -> ::loaded

(re-frame/reg-event-fx
 ::initialize-db
 [(re-frame/inject-cofx :theme/initial) (re-frame/inject-cofx :app-version)]
 (fn-traced [cofx _]
   (let [{:keys [saved system]} (:theme/initial cofx)]
     {:db (-> db/default-db
              (assoc-in [:settings :theme] saved)
              (assoc-in [:ui :system-theme] system)
              (assoc-in [:ui :app-version] (:app-version cofx))
              (assoc-in [:ui :screen] :loading))
      :fx [[:theme/apply {:theme saved}]
           [:storage/load {:on-loaded [::loaded]}]
           [:storage/request-persistence]]})))

(re-frame/reg-event-fx
 ::loaded
 ;; result: {:data …} | {:empty true} (first run: seed content) |
 ;; {:error …} (unusable data, already backed up: start fresh) |
 ;; {:error … :unavailable true} (no IndexedDB: run without saving).
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ {:keys [data error unavailable]}]]
   (when error
     (js/console.error "Stored data not loaded:" error))
   (let [;; The theme is a device preference owned by localStorage (it has
         ;; to apply before first paint), so it wins over the stored copy.
         theme (get-in db [:settings :theme])
         db    (cond-> db
                 data (merge data)
                 true (-> (assoc-in [:settings :theme] theme)
                          (roll-day now)
                          (assoc-in [:ui :screen] :home)
                          (assoc-in [:ui :persist?] (not unavailable))))]
     {:db db
      ;; Write back straight away: the first run's defaults, a migrated
      ;; shape, or a rolled-over day.
      :fx (when-not unavailable [[:storage/save db]])})))

(re-frame/reg-event-db
 ::system-theme-changed
 (fn-traced [db [_ theme]]
   (assoc-in db [:ui :system-theme] theme)))

(re-frame/reg-event-fx
 ::toggle-theme
 ;; Until first pressed, the theme follows the system. Pressing switches
 ;; away from whatever is showing, and saves that choice.
 (fn-traced [{:keys [db]} _]
   (let [showing (or (get-in db [:settings :theme]) (get-in db [:ui :system-theme]))
         theme   (if (= :dark showing) :light :dark)]
     {:db (assoc-in db [:settings :theme] theme)
      :fx [[:theme/apply {:theme theme :save? true}]]})))

(re-frame/reg-event-fx
 ::dev-reset-day
 ;; Dev tool: forget today's done set and resume positions.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} _]
   {:db (-> db (assoc :day (day/fresh nil)) (roll-day now))}))

(re-frame/reg-event-fx
 ::app-visible
 ;; PWAs stay open for days: check the date on every return.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} _]
   (when (not= :loading (get-in db [:ui :screen]))
     {:db (roll-day db now)
      :fx (when (= :walkthrough (get-in db [:ui :screen]))
            [[:wake-lock/request]])})))

(re-frame/reg-event-fx
 ::toggle-overview
 ;; Between one step and all steps. The progress markers are shared
 ;; elements, so they travel between the bar and the list.
 (fn-traced [{:keys [db]} _]
   {:fx [[:view-transition/dispatch
          {:event            [::set-overview (not (get-in db [:ui :overview?]))]
           :scroll-into-view ".overview-row.is-current"}]]}))

(re-frame/reg-event-db
 ::set-overview
 ;; Not a move: no slide-in, no arrow when the step view remounts.
 (fn-traced [db [_ overview?]]
   (-> db
       (assoc-in [:ui :overview?] overview?)
       (assoc-in [:ui :nav-direction] :none))))

(re-frame/reg-event-fx
 ::app-hidden
 ;; Save now (the page may be closing) and lock parent mode.
 (fn-traced [_ _]
   {:fx [[:storage/flush]
         [:dispatch [::lock-parent]]]}))

;; ---------------------------------------------------------------------------
;; Walkthrough

(def input-lock-ms
  "After advancing or going back, ignore walkthrough input for this long so
  a double tap can't skip a step (or land on the next screen's controls)."
  700)

(defn- locked? [db now]
  (< now (get-in db [:ui :input-locked-until] 0)))

(defn- lock-input [db now]
  (assoc-in db [:ui :input-locked-until] (+ now input-lock-ms)))

(defn- run-sequence [db]
  (get-in db [:content :sequences (get-in db [:run :sequence-id])]))

(defn- active-ids
  "Sequences in progress: the current run and every run waiting on it."
  [db]
  (into #{} (map :sequence-id) (cons (:run db) (:run-stack db))))

(defn- recording?
  "Parent-mode navigation is preview only: it never records progress."
  [db]
  (not (get-in db [:ui :parent?])))

(defn- record-progress
  "Mirror the run into the per-day slice: resume position while under way,
  the done set once finished."
  [db]
  (let [{:keys [sequence-id phase] :as r} (:run db)]
    (cond
      (not (recording? db)) db
      (= :finished phase)   (-> db
                                (update-in [:day :done] conj sequence-id)
                                (update-in [:day :resume] dissoc sequence-id))
      (pos? (:done-count r)) (assoc-in db [:day :resume sequence-id] (run/resume-entry r))
      :else                 db)))

(defn- walkthrough-input
  "Wrap a walkthrough input handler: drops the event while input is locked
  or no run is active."
  [handler]
  (fn [{:keys [db now]} event]
    (when (and (:run db) (not (locked? db now)))
      (handler db now event))))

(defn- begin-run
  "Make a fresh (or same-day resumed) run of `sequence` current."
  [db sequence]
  (let [resume (when (recording? db) (get-in db [:day :resume (:id sequence)]))]
    (-> db
        (assoc :run (run/new-run sequence resume))
        (assoc-in [:ui :nav-direction] :forward))))

(re-frame/reg-event-fx
 ::start-sequence
 (fn-traced [{:keys [db]} [_ sequence-id]]
   (when-let [sequence (get-in db [:content :sequences sequence-id])]
     (when (seq (:steps sequence))
       {:db (-> db
                (assoc :run-stack [])
                (begin-run sequence)
                (assoc-in [:ui :screen] :walkthrough)
                (assoc-in [:ui :overview?] false))
        :fx [[:wake-lock/request]]}))))

(defn- leave-home [db]
  {:db (-> db
           (assoc :run nil :run-stack [])
           (assoc-in [:ui :screen] :home)
           (assoc-in [:ui :overview?] false))
   :fx [[:wake-lock/release]]})

(def keep-consistent
  "After any event: the screen must point at real data. If the open
  sequence (or one waiting on it through a link) no longer exists, e.g.
  an undo removed it while it was open, return to the list. Selected ids
  that no longer exist drop out of the selection."
  (re-frame/->interceptor
   :id :keep-consistent
   :after (fn [context]
            (let [db (get-in context [:effects :db])]
              (if-not db
                context
                (let [sequences (get-in db [:content :sequences])
                      gone?     (fn [r] (and r (not (contains? sequences (:sequence-id r)))))
                      stranded? (and (= :walkthrough (get-in db [:ui :screen]))
                                     (or (gone? (:run db)) (some gone? (:run-stack db))))
                      run       (:run db)
                      current   (when (= :steps (:phase run))
                                  (get-in sequences [(:sequence-id run) :steps (:index run) :id]))
                      db        (cond-> db
                                  (get-in db [:ui :selection])
                                  (update-in [:ui :selection] #(into #{} (filter sequences) %))
                                  ;; A timer belongs to the step on screen; leaving it clears it.
                                  (and (get-in db [:ui :timer])
                                       (not= current (get-in db [:ui :timer :step-id])))
                                  (assoc-in [:ui :timer] nil))]
                  (if stranded?
                    (let [{db' :db fx :fx} (leave-home db)]
                      (-> context
                          (assoc-in [:effects :db] db')
                          (update-in [:effects :fx] (fnil into []) fx)))
                    (assoc-in context [:effects :db] db))))))))

(re-frame/reg-global-interceptor keep-consistent)

(defn- pop-run
  "Return to the run waiting on the current (linked) one."
  [db]
  (-> db
      (assoc :run (peek (:run-stack db)))
      (update :run-stack pop)))

(re-frame/reg-event-fx
 ::exit
 ;; Inside a linked sequence, Exit goes up one level to the step that
 ;; linked it, leaving that step not done.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} _]
   (if (seq (:run-stack db))
     {:db (-> db
              pop-run
              (assoc-in [:ui :nav-direction] :back)
              (lock-input now))}
     (leave-home db))))

(defn- advance-run [db]
  (-> db
      (update :run run/advance (run-sequence db))
      (assoc-in [:ui :nav-direction] :forward)
      record-progress))

(re-frame/reg-event-fx
 ::advance
 ;; The primary button. What it does depends on the position; see
 ;; run/primary-action.
 [(re-frame/inject-cofx :now)]
 (walkthrough-input
  (fn [db now _]
    (let [r        (:run db)
          sequence (run-sequence db)
          content  (:content db)]
      (when (run/can-advance? r sequence content)
        (case (run/primary-action r sequence content (active-ids db))
          :start  (let [target (run/linked-sequence content (run/current-step r sequence) (active-ids db))]
                    {:db (-> db
                             (update :run-stack conj r)
                             (begin-run target)
                             (lock-input now))})
          ;; Finishing a linked sequence completes the step that linked it.
          :finish (if (seq (:run-stack db))
                    {:db (-> db pop-run advance-run (lock-input now))}
                    (leave-home db))
          {:db (-> db advance-run (lock-input now))}))))))

(re-frame/reg-event-fx
 ::back
 [(re-frame/inject-cofx :now)]
 (walkthrough-input
  (fn [db now _]
    {:db (-> db
             (update :run run/back (run-sequence db))
             (assoc-in [:ui :nav-direction] :back)
             (lock-input now))})))

(re-frame/reg-event-fx
 ::toggle-requirement
 [(re-frame/inject-cofx :now)]
 (walkthrough-input
  (fn [db _ [_ item-id]]
    {:db (update-in db [:run :checked]
                    (fn [checked]
                      (if (contains? checked item-id)
                        (disj checked item-id)
                        (conj checked item-id))))})))

(re-frame/reg-event-fx
 ::choose
 [(re-frame/inject-cofx :now)]
 (walkthrough-input
  (fn [db _ [_ step-id item-id]]
    {:db (assoc-in db [:run :choices step-id] item-id)})))

;; ---------------------------------------------------------------------------
;; Parent mode: hidden gesture + PIN; locks when idle or backgrounded.

(def pin-min 4)
(def pin-max 8)

(re-frame/reg-event-db
 ::parent-gesture
 ;; The hidden long press. With no PIN yet, the parent chooses one first.
 (fn-traced [db _]
   (if (get-in db [:ui :parent?])
     db
     (assoc-in db [:ui :pin-entry]
               {:mode   (if (get-in db [:settings :pin]) :unlock :set)
                :digits ""}))))

(re-frame/reg-event-db
 ::pin-digit
 (fn-traced [db [_ digit]]
   (update-in db [:ui :pin-entry]
              (fn [entry]
                (when entry
                  (cond-> (assoc entry :message nil)
                    (< (count (:digits entry)) pin-max) (update :digits str digit)))))))

(re-frame/reg-event-db
 ::pin-backspace
 (fn-traced [db _]
   (update-in db [:ui :pin-entry]
              (fn [entry]
                (when entry
                  (update entry :digits #(subs % 0 (max 0 (dec (count %))))))))))

(re-frame/reg-event-db
 ::pin-cancel
 (fn-traced [db _]
   (assoc-in db [:ui :pin-entry] nil)))

(defn- unlock
  "Parent mode starts clean: nothing transient carries over from before."
  [db]
  {:db (-> db
           (assoc-in [:ui :parent?] true)
           (assoc-in [:ui :pin-entry] nil)
           (assoc-in [:ui :undo] nil)
           (assoc-in [:ui :selection] nil)
           (assoc-in [:ui :import] nil))
   :fx [[:parent/idle-lock {:timeout-ms (* 1000 (get-in db [:settings :parent-timeout-seconds]))
                            :on-idle    [::lock-parent]}]]})

(re-frame/reg-event-fx
 ::pin-submit
 (fn-traced [{:keys [db]} _]
   (let [{mode :mode digits :digits first-entry :first :as entry} (get-in db [:ui :pin-entry])
         retry (fn [mode message]
                 {:db (assoc-in db [:ui :pin-entry] {:mode mode :digits "" :message message})})]
     (when (and entry (<= pin-min (count digits)))
       (case mode
         :unlock  (if (= digits (get-in db [:settings :pin]))
                    (unlock db)
                    (retry :unlock "That's not it. Try again."))
         :set     {:db (assoc-in db [:ui :pin-entry] {:mode :confirm :digits "" :first digits})}
         :confirm (if (= digits first-entry)
                    (unlock (assoc-in db [:settings :pin] digits))
                    (retry :set "Those didn't match. Choose a PIN again.")))))))

(re-frame/reg-event-fx
 ::lock-parent
 ;; Also closes an open PIN pad. A walkthrough previewed in parent mode
 ;; ends: it must never continue as a real run (the steps the parent
 ;; clicked through would count as done).
 (fn-traced [{:keys [db]} _]
   (let [previewing? (and (get-in db [:ui :parent?])
                          (= :walkthrough (get-in db [:ui :screen])))
         db          (-> db
                         (assoc-in [:ui :parent?] false)
                         (assoc-in [:ui :pin-entry] nil)
                         (assoc-in [:ui :undo] nil)
                         (assoc-in [:ui :selection] nil)
                         (assoc-in [:ui :import] nil)
                         (assoc-in [:ui :picture-editor] nil)
                         (assoc-in [:ui :export] nil)
                         (assoc-in [:ui :list-editor] nil)
                         (cond-> (#{:library :things} (get-in db [:ui :screen])) (assoc-in [:ui :screen] :home)))]
     (-> (if previewing? (leave-home db) {:db db})
         (update :fx (fnil conj []) [:parent/idle-lock nil])))))

;; ---------------------------------------------------------------------------
;; Editing (parent mode only). Edits apply to the sequence being previewed,
;; from the overview, which is the editor in parent mode.

(def new-step-title "New step")

(def undo-ms
  "How long a delete can be undone."
  6000)

(defn- reconcile-run
  "Keep the preview run's position valid after the steps change."
  [db]
  (let [n (count (get-in db [:content :sequences (get-in db [:run :sequence-id]) :steps]))]
    (if (and (:run db) (pos? n))
      (update db :run #(-> %
                           (update :index min (dec n))
                           (update :done-count min n)))
      db)))

(defn- edit-sequence
  "Apply `f` to the previewed sequence, in parent mode only. Structural
  edits (add, delete, move) also drop today's resume position for it,
  which would otherwise point at the wrong step."
  ([db f] (edit-sequence db f false))
  ([db f structural?]
   (let [seq-id (get-in db [:run :sequence-id])]
     (if (and seq-id (get-in db [:ui :parent?]))
       (cond-> (update-in db [:content :sequences seq-id] f)
         structural? (-> (update-in [:day :resume] dissoc seq-id)
                         reconcile-run))
       db))))

(defn- offer-undo
  "Add an undo offer to effects map `m`: pushed onto the undo stack for
  undo-ms, with `restore` the event that reverses the change. Several
  deletes in a row can each be undone, most recent first."
  [m now message restore]
  (let [token (+ now (count (get-in m [:db :ui :undo])) (rand))]
    (-> m
        (update-in [:db :ui :undo] (fnil conj []) {:message message :restore restore :token token})
        (update :fx (fnil conj []) [:dispatch-later {:ms undo-ms :dispatch [::dismiss-undo token]}]))))

(defn- follow-step
  "Keep the preview on the same step (not the same slot) after an edit."
  [db db' f]
  (if (and (:run db') (not (identical? db db')))
    (update-in db' [:run :index] f)
    db'))

(re-frame/reg-event-db
 ::edit-sequence-title
 (fn-traced [db [_ title]]
   (edit-sequence db #(edit/set-sequence-title % title))))

(re-frame/reg-event-db
 ::edit-step-title
 (fn-traced [db [_ step-id title]]
   (edit-sequence db #(edit/set-step-title % step-id title))))

(re-frame/reg-event-db
 ::edit-step-description
 (fn-traced [db [_ step-id description]]
   (edit-sequence db #(edit/set-step-description % step-id description))))

(re-frame/reg-event-fx
 ::add-step
 ;; A task step titled "New step", with its title focused and selected so
 ;; typing replaces it.
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} _]
   (let [db' (edit-sequence db #(edit/add-task-step % new-id new-step-title) true)]
     (when-not (identical? db db')
       {:db db'
        :fx [[:focus {:selector (str "[data-title-for=\"" new-id "\"]") :select? true}]]}))))

(re-frame/reg-event-fx
 ::add-link-step
 ;; Only to a sequence that can't lead back here (see edit/linkable).
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} [_ target-id]]
   (let [seq-id (get-in db [:run :sequence-id])]
     (when (some #(= target-id (:id %)) (edit/linkable (:content db) seq-id))
       {:db (edit-sequence db #(edit/add-link-step % new-id target-id) true)}))))

(re-frame/reg-event-fx
 ::delete-step
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ step-id]]
   (let [seq-id  (get-in db [:run :sequence-id])
         removed (volatile! nil)
         db'     (edit-sequence db
                                (fn [sequence]
                                  (let [[sequence' undo] (edit/delete-step sequence step-id)]
                                    (vreset! removed undo)
                                    sequence'))
                                true)]
     (when @removed
       (offer-undo {:db (-> (follow-step db db' #(edit/index-after-delete % (:index @removed)))
                            reconcile-run)}
                   now "Step deleted" [::restore-step seq-id @removed])))))

(re-frame/reg-event-db
 ::restore-step
 (fn-traced [db [_ sequence-id restore]]
   (cond-> db
     (get-in db [:content :sequences sequence-id])
     (-> (update-in [:content :sequences sequence-id] edit/restore-step restore)
         (update-in [:day :resume] dissoc sequence-id)
         (cond-> (= sequence-id (get-in db [:run :sequence-id]))
           (update-in [:run :index] edit/index-after-insert (:index restore)))
         reconcile-run))))

(re-frame/reg-event-fx
 ::undo
 (fn-traced [{:keys [db]} _]
   ;; The most recent delete; earlier ones stay on offer until they expire.
   (let [{:keys [restore]} (peek (get-in db [:ui :undo]))]
     (when restore
       {:db (update-in db [:ui :undo] pop)
        :fx (when (get-in db [:ui :parent?])
              [[:dispatch restore]])}))))

(re-frame/reg-event-db
 ::dismiss-undo
 ;; Only the delete that scheduled it; a newer one keeps its own timer.
 (fn-traced [db [_ token]]
   (cond-> db
     (get-in db [:ui :undo])
     (update-in [:ui :undo] #(into [] (remove (fn [u] (= token (:token u)))) %)))))

(re-frame/reg-event-fx
 ::move-step
 ;; `refocus` (a selector) keeps keyboard focus on the moved row's handle.
 (fn-traced [{:keys [db]} [_ from to refocus]]
   (let [db' (edit-sequence db #(edit/move-step % from to) true)]
     (cond-> {:db (follow-step db db' #(edit/index-after-move % from to))}
       refocus (assoc :fx [[:focus {:selector refocus}]])))))

(defn- position-of
  "Linear position of a run, for telling which way a jump goes."
  [{:keys [phase index]} n]
  (case phase :requirements -1 :steps index :finished n))

(re-frame/reg-event-db
 ::jump
 ;; Parent preview: go straight to a position (:requirements or a step
 ;; index), from the progress dots or an overview row. Never records.
 (fn-traced [db [_ target]]
   (let [r (:run db)
         n (count (get-in db [:content :sequences (:sequence-id r) :steps]))]
     (if (and r (get-in db [:ui :parent?]))
       (let [r' (if (= :requirements target)
                  (assoc r :phase :requirements)
                  (assoc r :phase :steps :index (min target (dec n))))]
         (-> db
             (assoc :run r')
             (assoc-in [:ui :nav-direction]
                       (let [a (position-of r n) b (position-of r' n)]
                         (cond (< a b) :forward (> a b) :back :else :none)))
             (assoc-in [:ui :overview?] false)))
       db))))

(re-frame/reg-event-fx
 ::preview-step
 ;; From an overview row: jump there and show the step view, with the same
 ;; view transition as the toggle.
 (fn-traced [_ [_ target]]
   {:fx [[:view-transition/dispatch {:event [::jump target]}]]}))

;; ---------------------------------------------------------------------------
;; Sequences and groups (parent mode only)

(defn- parent? [db]
  (get-in db [:ui :parent?]))

(defn- edit-content
  "Apply `f` to the content, in parent mode only."
  [db f]
  (cond-> db (parent? db) (update :content f)))

(re-frame/reg-event-fx
 ::new-sequence
 ;; Creates it in `group-id` and opens it in the editor with its title
 ;; selected, ready to be typed over.
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} [_ group-id]]
   (when (parent? db)
     (let [sequence (edit/new-sequence new-id (random-uuid))]
       {:db (-> db
                (update :content edit/add-sequence group-id sequence)
                (assoc :run-stack [])
                (begin-run sequence)
                (assoc-in [:ui :screen] :walkthrough)
                (assoc-in [:ui :overview?] true))
        :fx [[:wake-lock/request]
             [:focus {:selector ".sequence-title-input" :select? true}]]}))))

(re-frame/reg-event-fx
 ::delete-sequence
 ;; Refused while other sequences link to it (the editor says which).
 ;; Leaves the walkthrough if it was the one open.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ sequence-id]]
   (when (parent? db)
     (let [[content undo] (edit/remove-sequence (:content db) sequence-id)]
       (when undo
         (let [db      (-> db
                           (assoc :content content)
                           (update-in [:day :resume] dissoc sequence-id)
                           (update-in [:day :done] disj sequence-id))
               leaving (= sequence-id (get-in db [:run :sequence-id]))]
           (offer-undo (if leaving (leave-home db) {:db db})
                       now "Sequence deleted" [::restore-sequence undo])))))))

(re-frame/reg-event-db
 ::restore-sequence
 (fn-traced [db [_ undo]]
   (edit-content db #(edit/restore-sequence % undo))))

(re-frame/reg-event-db
 ::set-sequence-group
 (fn-traced [db [_ sequence-id group-id]]
   (edit-content db #(edit/move-sequence-to-group % sequence-id group-id))))

(re-frame/reg-event-db
 ::set-sequence-days
 (fn-traced [db [_ sequence-id days]]
   (edit-content db #(update-in % [:sequences sequence-id] edit/set-days days))))

(re-frame/reg-event-fx
 ::new-group
 ;; Added before Ungrouped, its name selected for typing over.
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} _]
   (when (parent? db)
     {:db (edit-content db #(edit/add-group % new-id "New group"))
      :fx [[:focus {:selector (str "[data-group-name-for=\"" new-id "\"]") :select? true}]]})))

(re-frame/reg-event-db
 ::rename-group
 (fn-traced [db [_ group-id name]]
   (edit-content db #(edit/rename-group % group-id name))))

(re-frame/reg-event-fx
 ::move-group
 ;; `refocus` keeps keyboard focus on the button that was pressed.
 (fn-traced [{:keys [db]} [_ group-id delta refocus]]
   (cond-> {:db (edit-content db #(edit/move-group % group-id delta))}
     refocus (assoc :fx [[:focus {:selector refocus}]]))))

(re-frame/reg-event-fx
 ::delete-group
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ group-id]]
   (when (parent? db)
     (let [[content undo] (edit/remove-group (:content db) group-id)]
       (when undo
         (offer-undo {:db (assoc db :content content)}
                     now "Group deleted" [::restore-group undo]))))))

(re-frame/reg-event-db
 ::restore-group
 (fn-traced [db [_ undo]]
   (edit-content db #(edit/restore-group % undo))))

;; ---------------------------------------------------------------------------
;; Backup: export, and import as a full restore (parent mode only)

(def ^:private export-callbacks
  {:on-prompt [::export-prompt]
   :on-error  [::export-failed]})

(re-frame/reg-event-fx
 ::export-backup
 ;; Dispatched with dispatch-sync from the click, so preparing starts
 ;; within the tap (see stepquence.transfer for how sharing follows).
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} _]
   (when (parent? db)
     {:db (assoc-in db [:ui :export] {:status :preparing})
      :fx [[:transfer/export (merge export-callbacks
                                    {:kind :backup :db db :now now
                                     :on-done [::backup-exported now]})]]})))

(re-frame/reg-event-db
 ::backup-exported
 (fn-traced [db [_ at]]
   (-> db
       (assoc-in [:meta :last-backup-at] at)
       (assoc-in [:ui :export] nil))))

(re-frame/reg-event-db
 ::export-prompt
 ;; Prepared, but the tap no longer counts for sharing: ask for one more.
 (fn-traced [db _]
   (assoc-in db [:ui :export] {:status :ready})))

(re-frame/reg-event-fx
 ::share-prepared
 ;; The prompt's Share button (dispatch-sync, within its click).
 (fn-traced [{:keys [db]} _]
   {:db (assoc-in db [:ui :export] nil)
    :fx [[:transfer/share-pending]]}))

(re-frame/reg-event-fx
 ::export-cancel
 (fn-traced [{:keys [db]} _]
   {:db (assoc-in db [:ui :export] nil)
    :fx [[:transfer/drop-pending]]}))

(re-frame/reg-event-db
 ::export-failed
 (fn-traced [db _]
   (assoc-in db [:ui :export] {:status :error})))

(re-frame/reg-event-db
 ::export-done
 (fn-traced [db _]
   (assoc-in db [:ui :export] nil)))

(re-frame/reg-event-db
 ::import-read
 ;; A chosen file's text, either a full backup (restore) or shared
 ;; sequences (merge); validated before anything changes.
 (fn-traced [db [_ text]]
   (let [kind  (share/file-kind text)
         entry (case kind
                 :backup    (let [{:keys [data error]} (backup/decode text)]
                              (if error
                                {:error error}
                                {:kind :backup :data data :summary (backup/summary data)}))
                 :sequences (let [{:keys [data error]} (share/decode text)]
                              (if error
                                {:error error}
                                {:kind    :sequences
                                 :data    data
                                 :rows    (share/plan (:content db) data)
                                 :choices {}}))
                 {:error "That file isn't a Stepquence backup or shared sequences."})]
     (cond-> db
       (parent? db) (assoc-in [:ui :import] entry)))))

(re-frame/reg-event-db
 ::import-cancel
 (fn-traced [db _]
   (assoc-in db [:ui :import] nil)))

(re-frame/reg-event-fx
 ::import-confirm
 ;; The backup's pictures are stored first; only then is anything changed.
 (fn-traced [{:keys [db]} _]
   (when-let [data (and (parent? db) (get-in db [:ui :import :data]))]
     {:db (assoc-in db [:ui :import :busy?] true)
      :fx [[:images/import-data {:image-data (:image-data data)
                                 :on-done    [::import-commit]
                                 :on-error   [::import-failed]}]]})))

(re-frame/reg-event-db
 ::import-failed
 (fn-traced [db [_ message]]
   (assoc-in db [:ui :import] {:error message})))

(re-frame/reg-event-fx
 ::import-commit
 ;; Keeps a copy of the current data first, then replaces everything. Can
 ;; also be undone for a few seconds.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} _]
   (when-let [data (and (parent? db) (get-in db [:ui :import :data]))]
     (let [before (select-keys db [:content :settings :meta])]
       (offer-undo {:db (-> (backup/restore db data now)
                            (assoc-in [:ui :import] nil)
                            (assoc-in [:ui :screen] :home)
                            (assoc-in [:ui :overview?] false))
                    :fx [[:storage/save-copy {:key (str storage/storage-key ".before-import-" now)
                                              :db  db}]
                         [:wake-lock/release]]}
                   now "Backup restored" [::restore-snapshot before])))))

(re-frame/reg-event-db
 ::restore-snapshot
 ;; Undo an import: put back the content, settings and meta from before.
 (fn-traced [db [_ snapshot]]
   (cond-> db
     (parent? db) (merge snapshot))))

;; ---------------------------------------------------------------------------
;; Sharing chosen sequences: select on the list, export; merge on import

(re-frame/reg-event-db
 ::start-selecting
 (fn-traced [db _]
   (cond-> db (parent? db) (assoc-in [:ui :selection] #{}))))

(re-frame/reg-event-db
 ::stop-selecting
 (fn-traced [db _]
   (assoc-in db [:ui :selection] nil)))

(re-frame/reg-event-db
 ::toggle-selected
 (fn-traced [db [_ sequence-id]]
   (update-in db [:ui :selection]
              #(when % ((if (contains? % sequence-id) disj conj) % sequence-id)))))

(re-frame/reg-event-fx
 ::export-sequences
 ;; Dispatched with dispatch-sync from the click, so the share sheet opens
 ;; within the user gesture. Linked sequences come along (share/closure).
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ ids]]
   (when (and (parent? db) (seq ids))
     {:db (assoc-in db [:ui :export] {:status :preparing})
      :fx [[:transfer/export (merge export-callbacks
                                    {:kind :sequences :db db :ids ids :now now
                                     :on-done [::sequences-exported]})]]})))

(re-frame/reg-event-db
 ::sequences-exported
 (fn-traced [db _]
   (-> db
       (assoc-in [:ui :export] nil)
       (assoc-in [:ui :selection] nil))))

(re-frame/reg-event-db
 ::merge-choice
 (fn-traced [db [_ sequence-id choice]]
   (assoc-in db [:ui :import :choices sequence-id] choice)))

(defn- merge-message [{:keys [added updated copied]}]
  (let [part (fn [n word] (when (pos? n) (str n " " word)))]
    (or (some->> [(part added "added") (part updated "updated") (part copied "copied")]
                 (remove nil?) seq (interpose ", ") (apply str))
        "Nothing imported")))

(re-frame/reg-event-fx
 ::merge-confirm
 ;; The file's pictures are stored first; only then is anything changed.
 (fn-traced [{:keys [db]} _]
   (let [{:keys [kind data]} (get-in db [:ui :import])]
     (when (and (parent? db) (= :sequences kind))
       {:db (assoc-in db [:ui :import :busy?] true)
        :fx [[:images/import-data {:image-data (:image-data data)
                                   :on-done    [::merge-commit]
                                   :on-error   [::import-failed]}]]}))))

(re-frame/reg-event-fx
 ::merge-commit
 ;; Adds the shared sequences (and their items, collections, groups);
 ;; can be undone for a few seconds.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} _]
   (let [{:keys [kind data rows choices]} (get-in db [:ui :import])]
     (when (and (parent? db) (= :sequences kind))
       (let [result  (share/merge-into (:content db) data choices random-uuid)
             ;; Existing sequences default to :update.
             updated (into [] (keep (fn [{:keys [id exists?]}]
                                      (when (and exists? (= :update (get choices id :update))) id)))
                           rows)
             before  (select-keys db [:content])]
         (offer-undo {:db (-> db
                              (assoc :content (:content result))
                              ;; Updated sequences may have different steps.
                              (update-in [:day :resume] #(apply dissoc % updated))
                              (assoc-in [:ui :import] nil))}
                     now (merge-message result) [::restore-snapshot before]))))))

(re-frame/reg-event-fx
 ::delete-selected
 ;; All at once, one undo for all. Refused while a selected sequence is
 ;; linked from outside the selection (the bar says which).
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} _]
   (let [ids (get-in db [:ui :selection])]
     (when (and (parent? db) (seq ids))
       (let [[content undos] (edit/remove-sequences (:content db) ids)]
         (when undos
           (offer-undo {:db (-> db
                                (assoc :content content)
                                (update-in [:day :resume] #(apply dissoc % ids))
                                (update-in [:day :done] #(apply disj % ids))
                                (assoc-in [:ui :selection] nil))}
                       now
                       (if (= 1 (count ids)) "Sequence deleted" (str (count ids) " sequences deleted"))
                       [::restore-sequences undos])))))))

(re-frame/reg-event-db
 ::restore-sequences
 (fn-traced [db [_ undos]]
   (edit-content db #(edit/restore-sequences % undos))))

(re-frame/reg-event-db
 ::move-selected
 (fn-traced [db [_ group-id]]
   (let [ids (get-in db [:ui :selection])]
     (cond-> db
       (and (parent? db) (seq ids))
       (-> (update :content edit/move-sequences-to-group ids group-id)
           (assoc-in [:ui :selection] nil))))))

(re-frame/reg-event-fx
 ::move-selected-to-new-group
 ;; Creates "New group", moves the selection into it, and puts its name up
 ;; for editing.
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} _]
   (let [ids (get-in db [:ui :selection])]
     (when (and (parent? db) (seq ids))
       {:db (-> db
                (update :content edit/add-group new-id "New group")
                (update :content edit/move-sequences-to-group ids new-id)
                (assoc-in [:ui :selection] nil))
        :fx [[:focus {:selector (str "[data-group-name-for=\"" new-id "\"]") :select? true}]]}))))

;; ---------------------------------------------------------------------------
;; Pictures (parent mode). The picture dialog edits one target: a step of
;; the previewed sequence ({:step-id …}) or a sequence's card picture
;; ({:sequence-id …}). Files are processed and stored by :images/import.

(defn- apply-picture
  "Put library picture `image-id` on `target` (nil removes it)."
  [db {:keys [step-id sequence-id item-id]} image-id]
  (cond
    item-id     (edit-content db #(edit/set-item-image % item-id image-id))
    step-id     (edit-sequence db #(if image-id
                                     (edit/set-step-image % step-id image-id)
                                     (edit/clear-step-image % step-id)))
    sequence-id (edit-content db #(update-in % [:sequences sequence-id]
                                             (fn [s] (when s (if image-id
                                                               (edit/set-sequence-image s image-id)
                                                               (edit/clear-sequence-image s))))))
    :else       db))

(re-frame/reg-event-db
 ::open-picture-editor
 (fn-traced [db [_ target]]
   (cond-> db (parent? db) (assoc-in [:ui :picture-editor] {:target target}))))

(re-frame/reg-event-db
 ::close-picture-editor
 (fn-traced [db _]
   (assoc-in db [:ui :picture-editor] nil)))

(re-frame/reg-event-fx
 ::picture-file-chosen
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} [_ target file]]
   (when (parent? db)
     {:db (assoc-in db [:ui :picture-editor] {:target target :busy? true})
      :fx [[:images/import {:file     file
                            :id       new-id
                            :on-done  [::picture-imported target]
                            :on-error [::picture-import-failed target]}]]})))

(re-frame/reg-event-db
 ::picture-imported
 ;; Into the library, and onto the target.
 (fn-traced [db [_ target image]]
   (-> db
       (edit-content #(assoc-in % [:images (:id image)] image))
       (apply-picture target (:id image))
       (assoc-in [:ui :picture-editor] (when (get-in db [:ui :picture-editor]) {:target target})))))

(re-frame/reg-event-db
 ::picture-import-failed
 (fn-traced [db [_ target message]]
   (assoc-in db [:ui :picture-editor] {:target target :error message})))

(re-frame/reg-event-db
 ::use-library-image
 (fn-traced [db [_ target image-id]]
   (apply-picture db target image-id)))

(re-frame/reg-event-db
 ::remove-picture
 ;; The picture stays in the library for reuse.
 (fn-traced [db [_ target]]
   (apply-picture db target nil)))

(re-frame/reg-event-db
 ::set-step-image-fit
 (fn-traced [db [_ step-id fit]]
   (edit-sequence db #(edit/set-step-image-fit % step-id fit))))

(re-frame/reg-event-db
 ::set-step-image-position
 (fn-traced [db [_ step-id x y]]
   (edit-sequence db #(edit/set-step-image-position % step-id x y))))

;; ---------------------------------------------------------------------------
;; The Pictures screen: the whole library

(re-frame/reg-event-db
 ::open-library
 (fn-traced [db _]
   (cond-> db
     (parent? db) (-> (assoc-in [:ui :screen] :library)
                      (assoc-in [:ui :selection] nil)))))

(re-frame/reg-event-db
 ::open-things
 (fn-traced [db _]
   (cond-> db
     (parent? db) (-> (assoc-in [:ui :screen] :things)
                      (assoc-in [:ui :selection] nil)))))

(re-frame/reg-event-db
 ::close-library
 (fn-traced [db _]
   (assoc-in db [:ui :screen] :home)))

(re-frame/reg-event-fx
 ::library-files-chosen
 ;; Several at once; each is processed and added on its own.
 (fn-traced [{:keys [db]} [_ files]]
   (when (and (parent? db) (seq files))
     {:db (-> db
              (update-in [:ui :library-busy] (fnil + 0) (count files))
              (assoc-in [:ui :library-error] nil))
      :fx (vec (for [file files]
                 [:images/import {:file     file
                                  :id       (random-uuid)
                                  :on-done  [::library-picture-imported]
                                  :on-error [::library-import-failed]}]))})))

(defn- one-less-busy [db]
  (update-in db [:ui :library-busy] #(max 0 (dec (or % 1)))))

(re-frame/reg-event-db
 ::library-picture-imported
 (fn-traced [db [_ image]]
   (-> db
       (edit-content #(assoc-in % [:images (:id image)] image))
       one-less-busy)))

(re-frame/reg-event-db
 ::library-import-failed
 (fn-traced [db [_ message]]
   (-> db one-less-busy (assoc-in [:ui :library-error] message))))

(re-frame/reg-event-db
 ::rename-image
 (fn-traced [db [_ image-id name]]
   (edit-content db #(edit/rename-image % image-id name))))

(re-frame/reg-event-fx
 ::replace-image
 ;; Every step and card using `from` uses `to` instead; undoable.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ from to]]
   (when (parent? db)
     (offer-undo {:db (update db :content edit/replace-image from to)}
                 now "Picture replaced" [::restore-snapshot (select-keys db [:content])]))))

(re-frame/reg-event-fx
 ::delete-image
 ;; Off the library and everything that used it (they show text only).
 ;; Undoable; the file itself is deleted once the undo has expired, and
 ;; only if the picture wasn't restored.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ image-id]]
   (when (parent? db)
     (let [[content undo] (edit/remove-image (:content db) image-id)]
       (when undo
         (-> (offer-undo {:db (assoc db :content content)}
                         now "Picture deleted" [::restore-image undo])
             (update :fx conj [:dispatch-later {:ms (+ undo-ms 2000) :dispatch [::purge-image image-id]}])))))))

(re-frame/reg-event-db
 ::restore-image
 (fn-traced [db [_ undo]]
   (edit-content db #(edit/restore-image % undo))))

(re-frame/reg-event-fx
 ::purge-image
 (fn-traced [{:keys [db]} [_ image-id]]
   (when-not (get-in db [:content :images image-id])
     {:fx [[:images/delete image-id]]})))

;; ---------------------------------------------------------------------------
;; Things and lists (parent mode). The list editor edits one item list:
;; the previewed sequence's checklist ({:requirements sequence-id}) or a
;; choice step's list ({:step-id …}, whose list can also be switched).

(defn- list-target
  "The item list an editor target means: a checklist as is; a choice step
  means its current list."
  [db {:keys [step-id] :as target}]
  (if step-id
    (let [seq-id (get-in db [:run :sequence-id])
          step   (some #(when (= step-id (:id %)) %) (get-in db [:content :sequences seq-id :steps]))]
      (when-let [cid (:collection-id step)] {:collection cid}))
    target))

(re-frame/reg-event-db
 ::open-list-editor
 (fn-traced [db [_ target]]
   (cond-> db (parent? db) (assoc-in [:ui :list-editor] {:target target}))))

(re-frame/reg-event-db
 ::close-list-editor
 (fn-traced [db _]
   (assoc-in db [:ui :list-editor] nil)))

(re-frame/reg-event-fx
 ::list-add-new
 ;; A new thing, its name selected for typing over.
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} [_ target]]
   (when-let [lt (and (parent? db) (list-target db target))]
     {:db (update db :content edit/add-new-item lt new-id "New thing")
      :fx [[:focus {:selector (str "[data-item-for=\"" new-id "\"]") :select? true}]]})))

(re-frame/reg-event-db
 ::list-add-existing
 (fn-traced [db [_ target item-id]]
   (if-let [lt (and (parent? db) (list-target db target))]
     (update db :content edit/add-existing-item lt item-id)
     db)))

(re-frame/reg-event-db
 ::list-remove
 (fn-traced [db [_ target item-id]]
   (if-let [lt (and (parent? db) (list-target db target))]
     (update db :content edit/remove-from-list lt item-id)
     db)))

(re-frame/reg-event-fx
 ::list-move
 (fn-traced [{:keys [db]} [_ target from to refocus]]
   (when-let [lt (and (parent? db) (list-target db target))]
     (cond-> {:db (update db :content edit/move-in-list lt from to)}
       refocus (assoc :fx [[:focus {:selector refocus}]])))))

(re-frame/reg-event-db
 ::rename-item
 (fn-traced [db [_ item-id title]]
   (edit-content db #(edit/rename-item % item-id title))))

(re-frame/reg-event-db
 ::set-step-collection
 (fn-traced [db [_ step-id collection-id]]
   (edit-sequence db #(edit/set-choice-collection % step-id collection-id))))

(re-frame/reg-event-fx
 ::new-list-for-step
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} [_ step-id]]
   (when (parent? db)
     {:db (-> db
              (update :content edit/add-collection new-id "New list")
              (edit-sequence #(edit/set-choice-collection % step-id new-id)))
      :fx [[:focus {:selector ".list-name-input" :select? true}]]})))

(re-frame/reg-event-db
 ::rename-collection
 (fn-traced [db [_ collection-id name]]
   (edit-content db #(edit/rename-collection % collection-id name))))

(re-frame/reg-event-fx
 ::add-choice-step
 ;; A "Choose one" step with a new, empty list, opened for filling in.
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} _]
   (when (parent? db)
     (let [collection-id (random-uuid)
           db'           (-> db
                             (update :content edit/add-collection collection-id "Choices")
                             (edit-sequence #(edit/add-choice-step % new-id collection-id "Choose one") true))]
       (when (some #(= new-id (:id %)) (get-in db' [:content :sequences (get-in db [:run :sequence-id]) :steps]))
         {:db (assoc-in db' [:ui :list-editor] {:target {:step-id new-id}})})))))

;; ---------------------------------------------------------------------------
;; The Things & lists screen

(re-frame/reg-event-fx
 ::new-list
 ;; An empty list, opened for filling in (its name selected).
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} _]
   (when (parent? db)
     {:db (-> db
              (update :content edit/add-collection new-id "New list")
              (assoc-in [:ui :list-editor] {:target {:collection new-id}}))
      :fx [[:focus {:selector ".list-name-input" :select? true}]]})))

(re-frame/reg-event-fx
 ::new-thing
 ;; A thing on no list yet, its name selected for typing over.
 [(re-frame/inject-cofx :new-id)]
 (fn-traced [{:keys [db new-id]} _]
   (when (parent? db)
     {:db (update db :content assoc-in [:items new-id] {:id new-id :title "New thing"})
      :fx [[:focus {:selector (str "[data-item-for=\"" new-id "\"]") :select? true}]]})))

(re-frame/reg-event-fx
 ::delete-item
 ;; Off every checklist and list (the deletion policy); undoable.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ item-id]]
   (when (parent? db)
     (let [[content undo] (edit/remove-item (:content db) item-id)]
       (when undo
         (offer-undo {:db (assoc db :content content)} now "Thing deleted" [::restore-item undo]))))))

(re-frame/reg-event-db
 ::restore-item
 (fn-traced [db [_ undo]]
   (edit-content db #(edit/restore-item % undo))))

(re-frame/reg-event-fx
 ::delete-collection
 ;; Refused while a choice step uses it; undoable.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ collection-id]]
   (when (parent? db)
     (let [[content undo] (edit/remove-collection (:content db) collection-id)]
       (when undo
         (offer-undo {:db (assoc db :content content)} now "List deleted" [::restore-collection undo]))))))

(re-frame/reg-event-db
 ::restore-collection
 (fn-traced [db [_ undo]]
   (edit-content db #(edit/restore-collection % undo))))

;; ---------------------------------------------------------------------------
;; Step timers (see stepquence.timer). The child's taps; never automatic.

(defn- check-later
  "Schedule the finish check for a running timer."
  [{:keys [status token] :as t} now]
  (when (= :running status)
    [:dispatch-later {:ms (timer/remaining t now) :dispatch [::timer-check token]}]))

(re-frame/reg-event-fx
 ::timer-toggle
 ;; Start, pause or resume the current step's timer.
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ step-id seconds]]
   (let [t (timer/toggle (get-in db [:ui :timer]) step-id seconds now)]
     {:db (assoc-in db [:ui :timer] t)
      :fx (cond-> [(check-later t now)]
            (get-in db [:settings :sound?]) (conj [:sound/unlock]))})))

(re-frame/reg-event-fx
 ::timer-check
 ;; Only the run segment that scheduled it counts (a pause or reset since
 ;; makes it stale).
 [(re-frame/inject-cofx :now)]
 (fn-traced [{:keys [db now]} [_ token]]
   (let [t (get-in db [:ui :timer])]
     (when (and t (= :running (:status t)) (= token (:token t)))
       (if (pos? (timer/remaining t now))
         {:fx [(check-later t now)]}
         {:db (cond-> (assoc-in db [:ui :timer :status] :done)
                ;; Remembered for the run: an opt-in "finish first" step
                ;; can now be done (going back and forth won't re-lock it).
                (:run db) (update :run run/timer-finished (:step-id t)))
          :fx (when (get-in db [:settings :sound?]) [[:sound/chime]])})))))

(re-frame/reg-event-db
 ::timer-reset
 (fn-traced [db _]
   (assoc-in db [:ui :timer] nil)))

(re-frame/reg-event-db
 ::set-step-timer
 ;; Parent mode: a length in seconds, or nil for no timer.
 (fn-traced [db [_ step-id seconds]]
   (edit-sequence db #(edit/set-step-timer % step-id seconds))))

(re-frame/reg-event-db
 ::set-step-timer-required
 (fn-traced [db [_ step-id required?]]
   (edit-sequence db #(edit/set-step-timer-required % step-id required?))))

(re-frame/reg-event-fx
 ::toggle-sound
 (fn-traced [{:keys [db]} _]
   (when (parent? db)
     (let [on? (not (get-in db [:settings :sound?]))]
       {:db (assoc-in db [:settings :sound?] on?)
        ;; This tap unlocks audio, and a sample lets the parent hear it.
        :fx (when on? [[:sound/chime]])}))))
