(ns stepquence.db
  "Initial app-db. Top-level keys are organized by lifetime; see
  stepquence.schema/AppDb."
  (:require
   [stepquence.schema :as schema]
   [stepquence.seed :as seed]))

(def default-settings
  {:reward-level           :calm
   :sound?                 false
   :day-boundary-hour      3
   :pin                    nil
   :parent-timeout-seconds 120})

(def default-db
  {;; persistent
   :content seed/content
   :settings default-settings
   :meta {:schema-version schema/schema-version
          :last-backup-at nil}
   ;; per-day
   :day {:date nil :done #{} :resume {}}
   ;; per-run
   :run nil
   ;; ephemeral
   :ui {:screen :home
        :parent? false
        :overview? false}})
