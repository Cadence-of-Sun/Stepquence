(ns stepquence.views.home
  (:require
   [clojure.string :as str]
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
  schedule is shown, and sequences not scheduled today are dimmed. While
  selecting (for sharing), a tap selects instead of opening."
  ([sequence parent?] (sequence-card sequence parent? nil))
  ([{:keys [id title steps done-today? done-count today? days-label]} parent? selection]
   (let [selecting? (some? selection)
         selected?  (contains? selection id)]
     [:button.sequence-card
      {:type         "button"
       :class        [(when done-today? "is-done")
                      (when (and parent? (not today?) (not selecting?)) "is-not-today")
                      (when selecting? "is-selectable")
                      (when selected? "is-selected")]
       :aria-pressed (when selecting? selected?)
       :on-click     #(re-frame/dispatch (if selecting?
                                           [::events/toggle-selected id]
                                           [::events/start-sequence id]))}
   [:span.sequence-card-text
    [:span.sequence-card-title title]
    [progress/mini-dots steps done-count]
    (when parent?
      [:span.sequence-card-days (if today? days-label (str days-label " · not today"))])]
   (cond
     selecting?  [:span.select-mark {:class (when selected? "is-on")} (when selected? [icons/checkmark])]
     done-today? [:span.sequence-card-check
                  [icons/check]
                  [:span.visually-hidden "Done today"]])])))

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

(defn- parent-groups
  "Everything editable. While selecting for sharing, the add and group
  controls step aside so a tap can only mean \"choose this\"."
  [selection]
  (let [selecting? (some? selection)]
    [:<>
     (for [{:keys [id name sequences] :as group} @(re-frame/subscribe [::subs/all-groups])
           :when (or (not selecting?) (seq sequences))]
       ^{:key (str id)}
       [:section.group
        (if selecting?
          [:h2.group-name name]
          [group-header group])
        [:ul.sequence-grid
         (for [sequence sequences]
           ^{:key (str (:id sequence))}
           [:li [sequence-card sequence true selection]])
         (when-not selecting?
           [:li
            [:button.new-sequence
             {:type     "button"
              :on-click #(re-frame/dispatch [::events/new-sequence id])}
             [icons/plus] "New sequence"]])]])
     (when-not selecting?
       [:button.new-group
        {:type     "button"
         :on-click #(re-frame/dispatch [::events/new-group])}
        [icons/plus] "New group"])]))

(defn- selection-bar
  "While selecting: how many, what comes along with them, and what to do
  with them: move to a group, delete, or export."
  [selection]
  (let [n        (count selection)
        extras   @(re-frame/subscribe [::subs/selection-extras])
        blockers @(re-frame/subscribe [::subs/selection-blockers])
        groups   @(re-frame/subscribe [::subs/group-options])
        none?    (zero? n)]
    [:div.selection-bar {:role "region" :aria-label "Selected sequences"}
     [:div.selection-summary
      [:span.selection-count (case n 0 "Choose sequences" 1 "1 sequence" (str n " sequences"))]
      (when (seq extras)
        [:span.selection-extras
         (str "Export also includes " (str/join ", " extras) ", which "
              (if (= 1 (count extras)) "is" "are") " linked")])
      (when (seq blockers)
        [:span.selection-extras
         (str/join "; " (map #(str (:title %) " is linked from " (str/join ", " (:linked-from %))) blockers))
         ", so " (if (= 1 (count blockers)) "it" "they") " can't be deleted"])]
     [:div.selection-actions
      [:select.selection-move
       {:value     ""
        :disabled  none?
        :aria-label "Move to group"
        :on-change (fn [e]
                     (let [v (.. e -target -value)]
                       (if (= v "new")
                         (re-frame/dispatch [::events/move-selected-to-new-group])
                         (re-frame/dispatch [::events/move-selected (uuid v)]))))}
       [:option {:value "" :disabled true} "Move to…"]
       (for [{gid :id gname :name} groups]
         ^{:key (str gid)}
         [:option {:value (str gid)} gname])
       [:option {:value "new"} "New group"]]
      [:button.selection-action
       {:type     "button"
        :disabled (or none? (boolean (seq blockers)))
        :on-click #(re-frame/dispatch [::events/delete-selected])}
       "Delete"]
      [:button.selection-action.is-primary
       {:type     "button"
        :disabled none?
        ;; Synchronous, so sharing happens within the click (a user gesture).
        :on-click #(re-frame/dispatch-sync [::events/export-sequences (vec selection)])}
       "Export"]]]))

(defn home []
  (let [parent?   @(re-frame/subscribe [::subs/parent?])
        selection (when parent? @(re-frame/subscribe [::subs/selection]))]
    [:main.home
     [:header.home-header
      ;; Holding the heading for 2s opens parent mode.
      [parent/long-press-heading :h1.home-title "Sequences"]
      (when parent?
        [:button.select-toggle
         {:type     "button"
          :on-click #(re-frame/dispatch [(if selection ::events/stop-selecting ::events/start-selecting)])}
         (if selection "Cancel" "Select")])]
     (if parent?
       [parent-groups selection]
       [child-groups])
     (when selection
       [selection-bar selection])
     [dev-tools]]))
