(ns stepquence.events
  (:require
   [re-frame.core :as re-frame]
   [stepquence.config :as config]
   [stepquence.day :as day]
   [stepquence.db :as db]
   [stepquence.fx]
   [stepquence.run :as run]
   [stepquence.schema :as schema]
   [stepquence.storage :as storage]
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
 [(re-frame/inject-cofx :theme/initial)]
 (fn-traced [cofx _]
   (let [{:keys [saved system]} (:theme/initial cofx)]
     {:db (-> db/default-db
              (assoc-in [:settings :theme] saved)
              (assoc-in [:ui :system-theme] system)
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

(defn- unlock [db]
  {:db (-> db
           (assoc-in [:ui :parent?] true)
           (assoc-in [:ui :pin-entry] nil))
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
                         (assoc-in [:ui :pin-entry] nil))]
     (-> (if previewing? (leave-home db) {:db db})
         (update :fx (fnil conj []) [:parent/idle-lock nil])))))
