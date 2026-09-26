(ns stepquence.events
  (:require
   [re-frame.core :as re-frame]
   [stepquence.config :as config]
   [stepquence.db :as db]
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

(re-frame/reg-event-db
 ::initialize-db
 (fn-traced [_ _]
   db/default-db))
