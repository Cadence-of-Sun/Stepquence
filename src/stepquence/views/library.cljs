(ns stepquence.views.library
  "The Pictures screen (parent mode, from Settings): the whole library,
  where each picture is used, rename, replace with another everywhere, and
  delete (with undo)."
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

(defn- usage-text [steps sequences]
  (let [part (fn [n one many] (when (pos? n) (str n " " (if (= 1 n) one many))))]
    (if-let [parts (seq (remove nil? [(part steps "step" "steps") (part sequences "card" "cards")]))]
      (str "Used in " (str/join " and " parts))
      "Not used")))

(defn- add-button
  "Several pictures at once (phones offer the gallery and the camera)."
  []
  (r/with-let [input (atom nil)]
    [:<>
     [:input.visually-hidden
      {:type        "file"
       :accept      "image/*"
       :multiple    true
       :aria-hidden true
       :tab-index   -1
       :ref         #(reset! input %)
       :on-change   (fn [e]
                      (let [el (.-target e)]
                        (re-frame/dispatch [::events/library-files-chosen (vec (array-seq (.-files el)))])
                        (set! (.-value el) "")))}]
     [:button.new-sequence
      {:type "button" :on-click #(some-> @input .click)}
      [icons/plus] "Add pictures"]]))

(defn- replace-chooser [from others close!]
  [:div.library-replace
   [:p.setting-note "Replace it everywhere with:"]
   (if (seq others)
     [:ul.picture-library-grid
      (for [{:keys [id name]} others]
        ^{:key (str id)}
        [:li
         [:button.picture-choice
          {:type     "button"
           :on-click (fn []
                       (close!)
                       (re-frame/dispatch [::events/replace-image from id]))}
          [picture/picture {:image-id id :kind :thumb :fit :cover :alt "" :class "picture-choice-thumb"}]
          [:span.picture-choice-name name]]])]
     [:p.setting-note "There are no other pictures to use instead."])
   [:button.link-picker-cancel {:type "button" :on-click close!} "Cancel"]])

(defn- library-card [{:keys [id name usage-steps usage-sequences]} others]
  (r/with-let [mode (r/atom nil)] ; nil | :replace | :confirm-delete
    (let [used? (pos? (+ usage-steps usage-sequences))]
      [:li.library-card
       [picture/picture {:image-id id :kind :thumb :fit :cover :alt name :class "library-thumb"}]
       ^{:key name}
       [fields/text-field {:value      name
                           :max-length schema/max-name
                           :class      "library-name-input"
                           :label      "Picture name"
                           :on-commit  #(re-frame/dispatch [::events/rename-image id %])}]
       [:p.library-usage (usage-text usage-steps usage-sequences)]
       (case @mode
         :replace
         [replace-chooser id others #(reset! mode nil)]

         :confirm-delete
         [:div.library-confirm
          [:p (str "It's " (str/lower-case (usage-text usage-steps usage-sequences))
                   ". Those will show text only. You can undo this for a few seconds.")]
          [:div.library-actions
           [:button.settings-button {:type "button" :on-click #(reset! mode nil)} "Cancel"]
           [:button.danger-button {:type "button" :on-click #(re-frame/dispatch [::events/delete-image id])}
            "Delete"]]]

         [:div.library-actions
          (when used?
            [:button.settings-button {:type "button" :on-click #(reset! mode :replace)} "Replace with…"])
          [:button.danger-button
           {:type     "button"
            :on-click #(if used?
                         (reset! mode :confirm-delete)
                         (re-frame/dispatch [::events/delete-image id]))}
           [icons/trash] "Delete"]])])))

(defn library []
  (let [images              @(re-frame/subscribe [::subs/image-library])
        {:keys [busy error]} @(re-frame/subscribe [::subs/library-status])]
    [:main.home.library
     [:header.library-header
      [:button.library-back
       {:type "button" :on-click #(re-frame/dispatch [::events/close-library])}
       [icons/arrow-left] [:span "Sequences"]]
      [:h1.home-title "Pictures"]]
     [:div.library-add
      [add-button]
      (when (pos? busy)
        [:p.setting-note (str "Adding " busy (if (= 1 busy) " picture…" " pictures…"))])
      (when error
        [:p.setting-note error])]
     (if (seq images)
       [:ul.library-grid
        (for [{:keys [id] :as image} images]
          ^{:key (str id)}
          [library-card image (remove #(= id (:id %)) images)])]
       [:p.setting-note "No pictures yet. Add some here, or from a step in a sequence's editor."])]))
