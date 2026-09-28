(ns stepquence.views
  (:require
   [re-frame.core :as re-frame]
   [stepquence.subs :as subs]
   [stepquence.views.home :as home]
   [stepquence.views.walkthrough :as walkthrough]))

(defn main-panel []
  (case @(re-frame/subscribe [::subs/screen])
    ;; Usually a few milliseconds: just the background, no spinner.
    :loading     [:div.loading]
    :walkthrough [walkthrough/walkthrough]
    [home/home]))
