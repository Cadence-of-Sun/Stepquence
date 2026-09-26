(ns stepquence.views
  (:require
   [re-frame.core :as re-frame]
   [stepquence.subs :as subs]))

(defn home []
  [:main.home
   (for [{:keys [id name sequences]} @(re-frame/subscribe [::subs/home-groups])]
     ^{:key id}
     [:section.group
      [:h2 name]
      [:ul
       (for [{:keys [id title steps]} sequences]
         ^{:key id}
         [:li title " (" (count steps) " steps)"])]])])

(defn main-panel []
  [home])
