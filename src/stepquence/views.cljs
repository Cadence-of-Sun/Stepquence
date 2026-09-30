(ns stepquence.views
  (:require
   [re-frame.core :as re-frame]
   [stepquence.subs :as subs]
   [stepquence.views.editor :as editor]
   [stepquence.views.home :as home]
   [stepquence.views.library :as library]
   [stepquence.views.things :as things]
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
      :things      [things/things]
      [home/home])]
   ;; Editing dialogs (from the sequence editor or the Things & lists
   ;; screen); the picture dialog last, as it can open over the list one.
   [editor/list-dialog]
   [editor/picture-dialog]
   [parent/undo-toast]
   [parent/import-dialog]
   [parent/export-status]
   [parent/pin-pad]])
