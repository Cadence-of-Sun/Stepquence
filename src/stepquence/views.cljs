(ns stepquence.views
  (:require
   [re-frame.core :as re-frame]
   [stepquence.subs :as subs]
   [stepquence.views.home :as home]
   [stepquence.views.library :as library]
   [stepquence.views.parent :as parent]
   [stepquence.views.walkthrough :as walkthrough]))

(defn main-panel []
  [:div.app
   [parent/banner]
   [:div.screen
    (case @(re-frame/subscribe [::subs/screen])
      ;; Usually a few milliseconds: just the background, no spinner.
      :loading     [:div.loading]
      :walkthrough [walkthrough/walkthrough]
      :library     [library/library]
      [home/home])]
   [parent/undo-toast]
   [parent/import-dialog]
   [parent/export-status]
   [parent/pin-pad]])
