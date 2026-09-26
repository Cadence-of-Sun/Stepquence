(ns stepquence.events
  (:require
   [re-frame.core :as re-frame]
   [stepquence.config :as config]
   [stepquence.db :as db]
   [stepquence.fx]
   [stepquence.run :as run]
   [stepquence.schema :as schema]
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

(re-frame/reg-event-fx
 ::initialize-db
 [(re-frame/inject-cofx :theme/initial)]
 (fn-traced [cofx _]
   (let [{:keys [saved system]} (:theme/initial cofx)]
     {:db (-> db/default-db
              (assoc-in [:settings :theme] saved)
              (assoc-in [:ui :system-theme] system))
      :fx [[:theme/apply {:theme saved}]]})))

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
 ::app-visible
 (fn-traced [{:keys [db]} _]
   (when (= :walkthrough (get-in db [:ui :screen]))
     {:fx [[:wake-lock/request]]})))

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
                (assoc-in [:ui :screen] :walkthrough))
        :fx [[:wake-lock/request]]}))))

(defn- leave-home [db]
  {:db (-> db
           (assoc :run nil :run-stack [])
           (assoc-in [:ui :screen] :home))
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
