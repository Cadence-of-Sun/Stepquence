(ns stepquence.views.things
  "The Things & lists screen (parent mode, its own banner button): every
  list (used by choice steps) and every thing (on checklists and lists),
  where each is used, rename, and delete per the deletion policy: a thing
  comes off every checklist and list; a list can't be deleted while a
  choice step uses it."
  (:require
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [stepquence.events :as events]
   [stepquence.schema :as schema]
   [stepquence.subs :as subs]
   [stepquence.views.fields :as fields]
   [stepquence.views.icons :as icons]
   [stepquence.views.picture :as picture]))

(defn- steps-text [uses]
  (str/join ", " (map #(str (:title %) " (step " (:step %) ")") uses)))

(defn- list-card [{:keys [id name item-titles uses]}]
  [:li.library-card
   ^{:key name}
   [fields/text-field {:value      name
                       :max-length schema/max-name
                       :class      "library-name-input"
                       :label      "List name"
                       :on-commit  #(re-frame/dispatch [::events/rename-collection id %])}]
   [:p.library-usage (if (seq item-titles) (str/join ", " item-titles) "Empty")]
   [:p.library-usage (if (seq uses) (str "Used by " (steps-text uses)) "Not used")]
   [:div.library-actions
    [:button.settings-button
     {:type "button" :on-click #(re-frame/dispatch [::events/open-list-editor {:collection id}])}
     "Edit"]
    [:button.danger-button
     {:type     "button"
      :disabled (boolean (seq uses))
      :on-click #(re-frame/dispatch [::events/delete-collection id])}
     [icons/trash] "Delete"]]
   (when (seq uses)
     [:p.setting-note "Choose another list for those steps to delete this one."])])

(defn- uses-text [{:keys [checklists lists]}]
  (let [part (fn [n one many] (when (pos? n) (str n " " (if (= 1 n) one many))))]
    (if-let [parts (seq (remove nil? [(part checklists "checklist" "checklists") (part lists "list" "lists")]))]
      (str "On " (str/join " and " parts))
      "Not on any list")))

(defn- thing-row [{:keys [id title image-id uses]}]
  (r/with-let [confirming? (r/atom false)]
    (let [used? (pos? (+ (:checklists uses) (:lists uses)))]
      [:li.thing-row
       (if image-id
         [:button.picture-slot
          {:type "button" :aria-label (str "Picture for \"" title "\"")
           :on-click #(re-frame/dispatch [::events/open-picture-editor {:item-id id}])}
          [picture/picture {:image-id image-id :kind :thumb :fit :cover :alt "" :class "list-thumb"}]]
         [:button.list-add-picture
          {:type "button" :aria-label (str "Add a picture for \"" title "\"")
           :on-click #(re-frame/dispatch [::events/open-picture-editor {:item-id id}])}
          [icons/plus]])
       [:div.thing-text
        ^{:key title}
        [fields/text-field {:value      title
                            :max-length schema/max-name
                            :class      "list-name"
                            :label      "Name"
                            :attrs      {:data-item-for (str id)}
                            :on-commit  #(re-frame/dispatch [::events/rename-item id %])}]
        [:span.library-usage (uses-text uses)]
        (when @confirming?
          [:div.library-confirm
           [:p (str "It will come off " (str/lower-case (subs (uses-text uses) 3)) ". You can undo this for a few seconds.")]
           [:div.library-actions
            [:button.settings-button {:type "button" :on-click #(reset! confirming? false)} "Cancel"]
            [:button.danger-button {:type "button" :on-click #(re-frame/dispatch [::events/delete-item id])} "Delete"]]])]
       (when-not @confirming?
         [:button.row-action
          {:type       "button"
           :aria-label (str "Delete \"" title "\"")
           :on-click   #(if used? (reset! confirming? true) (re-frame/dispatch [::events/delete-item id]))}
          [icons/trash]])])))

(defn things []
  (let [{:keys [collections items]} @(re-frame/subscribe [::subs/things-library])]
    [:main.home.library
     [:header.library-header
      [:button.library-back
       {:type "button" :on-click #(re-frame/dispatch [::events/close-library])}
       [icons/arrow-left] [:span "Sequences"]]
      [:h1.home-title "Things & lists"]]

     [:section.things-section
      [:h2.group-name "Lists"]
      [:p.setting-note "Choice steps offer the things on a list."]
      (when (seq collections)
        [:ul.library-grid
         (for [c collections]
           ^{:key (str (:id c))}
           [list-card c])])
      [:button.new-sequence {:type "button" :on-click #(re-frame/dispatch [::events/new-list])}
       [icons/plus] "New list"]]

     [:section.things-section
      [:h2.group-name "Things"]
      [:p.setting-note "Things go on checklists (\"What you need\") and on lists."]
      (when (seq items)
        [:ul.thing-rows
         (for [item items]
           ^{:key (str (:id item))}
           [thing-row item])])
      [:button.new-sequence {:type "button" :on-click #(re-frame/dispatch [::events/new-thing])}
       [icons/plus] "New thing"]]]))
