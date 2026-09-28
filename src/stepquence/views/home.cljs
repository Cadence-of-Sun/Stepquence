(ns stepquence.views.home
  (:require
   [re-frame.core :as re-frame]
   [stepquence.config :as config]
   [stepquence.events :as events]
   [stepquence.subs :as subs]
   [stepquence.views.icons :as icons]
   [stepquence.views.parent :as parent]
   [stepquence.views.progress :as progress]))

(defn- sequence-card [{:keys [id title steps done-today? done-count]}]
  ;; Image-first once images exist; the text-only card is the fallback.
  ;; Done today: green check and filled dots. Started but unfinished: the
  ;; steps done so far are filled (it will resume there).
  [:button.sequence-card
   {:type     "button"
    :class    (when done-today? "is-done")
    :on-click #(re-frame/dispatch [::events/start-sequence id])}
   [:span.sequence-card-text
    [:span.sequence-card-title title]
    [progress/mini-dots steps done-count]]
   (when done-today?
     [:span.sequence-card-check
      [icons/check]
      [:span.visually-hidden "Done today"]])])

(defn- dev-tools []
  ;; Dev builds only (goog.DEBUG); dropped from release builds.
  (when config/debug?
    [:footer.dev-tools
     [:button.dev-button
      {:type     "button"
       :on-click #(re-frame/dispatch [::events/dev-reset-day])}
      "Dev: reset today"]]))

(defn home []
  [:main.home
   [:header.home-header
    ;; Holding the heading for 2s opens parent mode.
    [parent/long-press-heading :h1.home-title "Sequences"]]
   (for [{:keys [id name sequences]} @(re-frame/subscribe [::subs/home-groups])]
     ^{:key (str id)}
     [:section.group
      ;; A checkmark once every sequence in the group is done today.
      [:h2.group-name
       name
       (when (every? :done-today? sequences)
         [:<>
          [icons/checkmark]
          [:span.visually-hidden "All done today"]])]
      [:ul.sequence-grid
       (for [sequence sequences]
         ^{:key (str (:id sequence))}
         [:li [sequence-card sequence]])]])
   [dev-tools]])
