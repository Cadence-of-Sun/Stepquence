(ns stepquence.views.home
  (:require
   [re-frame.core :as re-frame]
   [stepquence.events :as events]
   [stepquence.subs :as subs]
   [stepquence.views.icons :as icons]
   [stepquence.views.progress :as progress]))

(defn- sequence-card [{:keys [id title steps]}]
  ;; Image-first once images exist; the text-only card is the fallback.
  [:button.sequence-card
   {:type     "button"
    :on-click #(re-frame/dispatch [::events/start-sequence id])}
   [:span.sequence-card-title title]
   [progress/mini-dots steps]])

(defn- theme-toggle []
  ;; Shows which theme is active (the system's until pressed); pressing
  ;; switches to the other and remembers it. Temporary home until
  ;; parent-mode settings exist.
  (let [showing @(re-frame/subscribe [::subs/theme-showing])
        other   (if (= :dark showing) "light" "dark")]
    [:button.theme-toggle
     {:type       "button"
      :aria-label (str "Switch to " other " theme")
      :on-click   #(re-frame/dispatch [::events/toggle-theme])}
     [:span.theme-option {:class (when (= :light showing) "is-active")} [icons/sun]]
     [:span.theme-slash "/"]
     [:span.theme-option {:class (when (= :dark showing) "is-active")} [icons/moon]]]))

(defn home []
  [:main.home
   [:header.home-header
    [:h1.home-title "Sequences"]
    [theme-toggle]]
   (for [{:keys [id name sequences]} @(re-frame/subscribe [::subs/home-groups])]
     ^{:key (str id)}
     [:section.group
      [:h2.group-name name]
      [:ul.sequence-grid
       (for [sequence sequences]
         ^{:key (str (:id sequence))}
         [:li [sequence-card sequence]])]])])
