(ns stepquence.views.home
  (:require
   [re-frame.core :as re-frame]
   [stepquence.config :as config]
   [stepquence.events :as events]
   [stepquence.schema :as schema]
   [stepquence.subs :as subs]
   [stepquence.views.fields :as fields]
   [stepquence.views.icons :as icons]
   [stepquence.views.parent :as parent]
   [stepquence.views.progress :as progress]))

(defn- sequence-card
  "Image-first once images exist; the text-only card is the fallback.
  Done today: green check and filled dots. Started but unfinished: the
  steps done so far are filled (it will resume there). In parent mode the
  schedule is shown, and sequences not scheduled today are dimmed."
  [{:keys [id title steps done-today? done-count today? days-label]} parent?]
  [:button.sequence-card
   {:type     "button"
    :class    [(when done-today? "is-done")
               (when (and parent? (not today?)) "is-not-today")]
    :on-click #(re-frame/dispatch [::events/start-sequence id])}
   [:span.sequence-card-text
    [:span.sequence-card-title title]
    [progress/mini-dots steps done-count]
    (when parent?
      [:span.sequence-card-days (if today? days-label (str days-label " · not today"))])]
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

(defn- child-groups []
  [:<>
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
         [:li [sequence-card sequence false]])]])])

(defn- group-header
  "Parent mode: the name is editable; ↑ ↓ reorder; groups other than
  Ungrouped can be deleted (their sequences move to Ungrouped)."
  [{:keys [id name first? last? ungrouped?]}]
  (let [move! (fn [delta attr]
                #(re-frame/dispatch [::events/move-group id delta (str "[" attr "=\"" id "\"]")]))]
    [:div.group-header
     ^{:key name}
     [fields/text-field {:value      name
                         :max-length schema/max-name
                         :class      "group-name-input"
                         :label      "Group name"
                         :attrs      {:data-group-name-for (str id)}
                         :on-commit  #(re-frame/dispatch [::events/rename-group id %])}]
     [:span.group-actions
      [:button.row-action {:type "button" :disabled first? :data-group-up (str id)
                           :aria-label (str "Move \"" name "\" up")
                           :on-click (move! -1 "data-group-up")}
       [icons/chevron-up]]
      [:button.row-action {:type "button" :disabled last? :data-group-down (str id)
                           :aria-label (str "Move \"" name "\" down")
                           :on-click (move! 1 "data-group-down")}
       [icons/chevron-down]]
      (when-not ungrouped?
        [:button.row-action {:type       "button"
                             :aria-label (str "Delete group \"" name "\"")
                             :on-click   #(re-frame/dispatch [::events/delete-group id])}
         [icons/trash]])]]))

(defn- parent-groups []
  [:<>
   (for [{:keys [id sequences] :as group} @(re-frame/subscribe [::subs/all-groups])]
     ^{:key (str id)}
     [:section.group
      [group-header group]
      [:ul.sequence-grid
       (for [sequence sequences]
         ^{:key (str (:id sequence))}
         [:li [sequence-card sequence true]])
       [:li
        [:button.new-sequence
         {:type     "button"
          :on-click #(re-frame/dispatch [::events/new-sequence id])}
         [icons/plus] "New sequence"]]]])
   [:button.new-group
    {:type     "button"
     :on-click #(re-frame/dispatch [::events/new-group])}
    [icons/plus] "New group"]])

(defn home []
  [:main.home
   [:header.home-header
    ;; Holding the heading for 2s opens parent mode.
    [parent/long-press-heading :h1.home-title "Sequences"]]
   (if @(re-frame/subscribe [::subs/parent?])
     [parent-groups]
     [child-groups])
   [dev-tools]])
