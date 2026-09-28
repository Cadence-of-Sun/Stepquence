(ns stepquence.core
  (:require
   [reagent.dom :as rdom]
   [re-frame.core :as re-frame]
   [stepquence.events :as events]
   [stepquence.fx :as fx]
   [stepquence.views :as views]
   [stepquence.config :as config]
   ))


(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (let [root-el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [views/main-panel] root-el)))

(defn- on-visibility-change []
  (if (= "visible" (.-visibilityState js/document))
    (re-frame/dispatch [::events/app-visible])
    ;; Synchronous: the page may be closing, so save before returning.
    (re-frame/dispatch-sync [::events/app-hidden])))

(defn init []
  (re-frame/dispatch-sync [::events/initialize-db])
  (.addEventListener js/document "visibilitychange" on-visibility-change)
  (fx/install-activity-listeners!)
  (.addEventListener (fx/system-dark-query) "change"
                     #(re-frame/dispatch [::events/system-theme-changed (fx/system-theme)]))
  (dev-setup)
  (mount-root))
