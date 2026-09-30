(ns stepquence.views.editor
  "Parent mode's overview: the sequence editor.

  The same list as the child's overview (same markers, same shared-element
  transitions), plus: titles and descriptions edited in place, a drag
  handle per step (arrow keys on the handle also move it), delete with
  undo, an add-step row, and a → on every row to preview that step."
  (:require
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [stepquence.edit :as edit]
   [stepquence.events :as events]
   [stepquence.schema :as schema]
   [stepquence.subs :as subs]
   [stepquence.views.fields :as fields]
   [stepquence.views.icons :as icons]
   [stepquence.views.picture :as picture]
   [stepquence.views.progress :as progress]))

;; ---------------------------------------------------------------------------
;; Text editing

(defn- description-field [step-id description]
  (r/with-let [adding? (r/atom false)]
    (if (or description @adding?)
      [fields/text-field {:value        (or description "")
                   :multiline?   true
                   :allow-blank? true
                   :max-length   schema/max-description
                   :class        "overview-description-input"
                   :label        "Description"
                   :auto-focus?  (and @adding? (not description))
                   :on-done      #(reset! adding? false)
                   :on-commit    #(re-frame/dispatch [::events/edit-step-description step-id %])}]
      [:button.add-description
       {:type     "button"
        :on-click #(reset! adding? true)}
       "+ Description"])))

(defn heading
  "The sequence title, editable. Keeps the shared view-transition name so
  it still moves between the step view's header and here."
  []
  (let [title        (:title @(re-frame/subscribe [::subs/run-sequence]))
        parent-title @(re-frame/subscribe [::subs/parent-title])]
    [:div.sequence-heading
     ^{:key title}
     [fields/text-field {:value      title
                  :max-length schema/max-name
                  :class      "sequence-heading-title sequence-title-input"
                  :label      "Sequence title"
                  :attrs      {:style {:view-transition-name "sequence-title"}}
                  :on-commit  #(re-frame/dispatch [::events/edit-sequence-title %])}]
     (when parent-title
       [:p.sequence-heading-context
        {:style {:view-transition-name "sequence-context"}}
        "Part of " [:strong parent-title]])]))

;; ---------------------------------------------------------------------------
;; Reordering: drag the handle (pointer events, so touch works), or use the
;; arrow keys on it. While dragging, the row follows the pointer and the rows
;; it passes shift by its height; on release the move is committed.

(defn- row-shift
  "The transform for step row `i` during a drag."
  [{:keys [from to height dy scroll-dy]} i]
  (cond
    (nil? from)                   nil
    (= i from)                    (str "translateY(" (+ dy scroll-dy) "px)")
    (and (< from i) (<= i to))    (str "translateY(" (- height) "px)")
    (and (<= to i) (< i from))    (str "translateY(" height "px)")))

(def ^:private edge-px
  "Dragging this close to the top or bottom of the list scrolls it."
  48)

(defn- handle-attrs [drag list-el {:keys [id title target]} n]
  (let [i target]
    {:type                  "button"
     :aria-label            (str "Move \"" title "\". Drag, or use the arrow keys.")
     :data-handle-for       (str id)
     :on-pointer-down       (fn [e]
                              (.preventDefault e)
                              (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                              (let [rows    (vec (.querySelectorAll @list-el "[data-step-index]"))
                                    rects   (mapv #(.getBoundingClientRect %) rows)
                                    centers (mapv #(+ (.-top %) (/ (.-height %) 2)) rects)
                                    gap     (if (> (count rects) 1)
                                              (- (.-top (rects 1)) (.-bottom (rects 0)))
                                              0)
                                    scroll  (.closest @list-el ".overview-scroll")]
                                (reset! drag {:from      i
                                              :to        i
                                              :dy        0
                                              :scroll-dy 0
                                              :start-y   (.-clientY e)
                                              :centers   centers
                                              :height    (+ (.-height (rects i)) gap)
                                              :scroll    scroll
                                              :scroll0   (.-scrollTop scroll)})))
     :on-pointer-move       (fn [e]
                              (when-let [{:keys [from centers start-y scroll scroll0]} @drag]
                                (let [y    (.-clientY e)
                                      rect (.getBoundingClientRect scroll)]
                                  (cond
                                    (< y (+ (.-top rect) edge-px))    (set! (.-scrollTop scroll) (- (.-scrollTop scroll) 8))
                                    (> y (- (.-bottom rect) edge-px)) (set! (.-scrollTop scroll) (+ (.-scrollTop scroll) 8)))
                                  (let [dy     (- y start-y)
                                        sdy    (- (.-scrollTop scroll) scroll0)
                                        center (+ (centers from) dy sdy)
                                        to     (count (filter #(< % center)
                                                              (keep-indexed (fn [k c] (when (not= k from) c)) centers)))]
                                    (swap! drag assoc :dy dy :scroll-dy sdy :to to)))))
     :on-pointer-up         (fn [_]
                              (when-let [{:keys [from to]} @drag]
                                (reset! drag nil)
                                (when (not= from to)
                                  (re-frame/dispatch [::events/move-step from to]))))
     :on-pointer-cancel     #(reset! drag nil)
     :on-lost-pointer-capture #(reset! drag nil)
     :on-key-down           (fn [e]
                              (let [to (case (.-key e)
                                         "ArrowUp"   (dec i)
                                         "ArrowDown" (inc i)
                                         nil)]
                                (when (and to (< -1 to n))
                                  (.preventDefault e)
                                  (re-frame/dispatch [::events/move-step i to
                                                      (str "[data-handle-for=\"" id "\"]")]))))}))

;; ---------------------------------------------------------------------------
;; Rows

(defn- marker-cell
  "The row's dot, as a button that previews the step (like the dots in
  the step view)."
  [{:keys [current? target title] :as row}]
  [:span.overview-marker
   [progress/marker :button.marker.is-selectable row
    {:type       "button"
     :aria-label (str "Preview: " title)
     :on-click   #(re-frame/dispatch [::events/preview-step target])}]
   (when current?
     [:span.overview-indicator
      {:style {:view-transition-name progress/indicator-transition-name}}
      [:span.indicator-shape]])])

(defn- go-button [{:keys [target title]}]
  [:button.row-action.is-go
   {:type       "button"
    :aria-label (str "Preview: " title)
    :on-click   #(re-frame/dispatch [::events/preview-step target])}
   [icons/arrow-right]])

(defn- requirements-row [{:keys [item-count current? done?] :as row}]
  (let [seq-id @(re-frame/subscribe [::subs/run-sequence-id])]
  [:li.overview-row {:class [(when current? "is-current") (when done? "is-done")]}
   [:div.overview-row-inner
    [:span.drag-spacer]
    [marker-cell row]
    [:span.overview-text
     [:span.overview-title "What you need"]
     [:button.add-description
      {:type     "button"
       :on-click #(re-frame/dispatch [::events/open-list-editor {:requirements seq-id}])}
      (str item-count (if (= 1 item-count) " thing" " things") " · Edit")]]
    [:span.row-actions
     [go-button row]]]]))

(defn- picture-slot
  "The step's picture as a small thumbnail (tap to change), or \"+ Picture\"."
  [{:keys [id title image-id]}]
  (if image-id
    [:button.picture-slot
     {:type       "button"
      :aria-label (str "Picture for \"" title "\"")
      :on-click   #(re-frame/dispatch [::events/open-picture-editor {:step-id id}])}
     [picture/picture {:image-id image-id :kind :thumb :fit :cover :alt "" :class "picture-slot-thumb"}]]
    [:button.add-description
     {:type     "button"
      :on-click #(re-frame/dispatch [::events/open-picture-editor {:step-id id}])}
     "+ Picture"]))

(defn- step-row [{:keys [id kind title description target current? done? collection-name] :as row}
                 {:keys [transform dragging? handle can-delete?]}]
  [:li.overview-row {:data-step-index target
                     :class           [(when current? "is-current")
                                       (when done? "is-done")
                                       (when dragging? "is-dragging")]
                     :style           {:transform transform}}
   [:div.overview-row-inner
    [:button.drag-handle handle [icons/grip]]
    [marker-cell row]
    [:span.overview-text
     (if (= :link kind)
       ;; A link shows the linked sequence's own title.
       [:<>
        [:span.overview-title title]
        [:span.overview-detail "Linked sequence"]]
       [:<>
        ^{:key (str id "|" title)}
        [fields/text-field {:value      title
                     ;; Wraps and grows (titles run to 60 characters);
                     ;; Enter still saves.
                     :multiline? true
                     :max-length schema/max-step-title
                     :class      "overview-title-input"
                     :label      "Step title"
                     :attrs      {:data-title-for (str id)}
                     :on-commit  #(re-frame/dispatch [::events/edit-step-title id %])}]
        (when (= :choice kind)
          [:button.add-description
           {:type     "button"
            :on-click #(re-frame/dispatch [::events/open-list-editor {:step-id id}])}
           (str "Options: " (or collection-name "none yet") " · Edit")])
        ^{:key (str id "|" description)}
        [description-field id description]
        (when (= :task kind)
          [picture-slot row])])]
    [:span.row-actions
     [:button.row-action
      {:type       "button"
       :disabled   (not can-delete?)
       :title      (when-not can-delete? "A sequence keeps at least one step")
       :aria-label (str "Delete \"" title "\"")
       :on-click   #(re-frame/dispatch [::events/delete-step id])}
      [icons/trash]]
     [go-button row]]]])

(defn- link-picker
  "Choose the sequence a new link step walks through."
  [close!]
  (let [candidates @(re-frame/subscribe [::subs/link-candidates])]
    [:div.link-picker {:role "group" :aria-label "Link a sequence"}
     [:p.link-picker-title "Link a sequence"]
     (if (seq candidates)
       [:ul.link-picker-list
        (for [{:keys [id title steps]} candidates]
          ^{:key (str id)}
          [:li
           [:button.link-choice
            {:type     "button"
             :on-click (fn []
                         (close!)
                         (re-frame/dispatch [::events/add-link-step id]))}
            [:span.link-choice-title title]
            [progress/mini-dots steps]]])]
       [:p.setting-note "Every other sequence already leads back to this one, so linking would make a loop."])
     [:button.link-picker-cancel {:type "button" :on-click close!} "Cancel"]]))

(defn- add-row
  "+ Add step, and beside it smaller buttons for the other kinds: a choice
  step, and (with at least two sequences) a step that links another
  sequence."
  []
  (r/with-let [picking? (r/atom false)]
    [:<>
     [:div.add-row
      [:button.add-step
       {:type     "button"
        :on-click #(re-frame/dispatch [::events/add-step])}
       [icons/plus] "Add step"]
      [:button.add-link
       {:type       "button"
        :aria-label "Add a choice step"
        :title      "Add a choice"
        :on-click   #(re-frame/dispatch [::events/add-choice-step])}
       [icons/choice]]
      (when (>= @(re-frame/subscribe [::subs/sequence-count]) 2)
        [:button.add-link
         {:type          "button"
          :aria-label    "Add a step that links another sequence"
          :title         "Link a sequence"
          :aria-expanded @picking?
          :on-click      #(swap! picking? not)}
         [icons/link]])]
     (when @picking?
       [link-picker #(reset! picking? false)])]))

(defn overview-editor []
  (r/with-let [drag    (r/atom nil)
               list-el (atom nil)]
    (let [rows        @(re-frame/subscribe [::subs/overview-rows])
          can-add?    @(re-frame/subscribe [::subs/can-add-step?])
          can-delete? @(re-frame/subscribe [::subs/can-delete-step?])
          d           @drag
          n           (count (filter #(number? (:target %)) rows))]
      [:ol.overview.is-editing {:ref   #(reset! list-el %)
                                :class (when d "is-sorting")}
       (for [{:keys [id target] :as row} rows]
         (if (= :requirements target)
           ^{:key (str id)}
           [requirements-row row]
           ^{:key (str id)}
           [step-row row {:transform   (row-shift d target)
                          :dragging?   (= target (:from d))
                          :handle      (handle-attrs drag list-el row n)
                          :can-delete? can-delete?}]))
       [:li.overview-add
        (if can-add?
          [add-row]
          [:p.add-step-full (str "A sequence can have up to " schema/max-steps " steps.")])]])))

;; ---------------------------------------------------------------------------
;; Sequence settings, below the steps

(def ^:private week
  [[:mon "Mon" "Monday"] [:tue "Tue" "Tuesday"] [:wed "Wed" "Wednesday"]
   [:thu "Thu" "Thursday"] [:fri "Fri" "Friday"] [:sat "Sat" "Saturday"]
   [:sun "Sun" "Sunday"]])

(def ^:private presets
  [["Every day" edit/every-day] ["Weekdays" edit/weekdays] ["Weekends" edit/weekends]])

(defn sequence-settings []
  (when-let [{:keys [id days group-id groups links-to image-id requirements]} @(re-frame/subscribe [::subs/sequence-settings])]
    (let [set-days! #(re-frame/dispatch [::events/set-sequence-days id %])]
      [:section.sequence-settings
       [:h2.settings-heading "Sequence settings"]

       [:div.setting
        [:span.setting-label "What you need"]
        [:button.settings-button
         {:type     "button"
          :on-click #(re-frame/dispatch [::events/open-list-editor {:requirements id}])}
         (if (seq requirements)
           (str (count requirements) (if (= 1 (count requirements)) " thing" " things") " · Edit")
           "+ Add a checklist")]
        [:p.setting-note "Things to gather before step 1, ticked off by the child."]]

       [:div.setting
        [:span.setting-label "Card picture"]
        (if image-id
          [:button.picture-slot
           {:type       "button"
            :aria-label "Change the card picture"
            :on-click   #(re-frame/dispatch [::events/open-picture-editor {:sequence-id id}])}
           [picture/picture {:image-id image-id :kind :thumb :fit :cover :alt "" :class "picture-slot-thumb"}]]
          [:button.settings-button
           {:type     "button"
            :on-click #(re-frame/dispatch [::events/open-picture-editor {:sequence-id id}])}
           "+ Picture"])
        [:p.setting-note "Shown on the sequence's card in the list."]]

       [:div.setting
        [:label.setting-label {:for "sequence-group"} "Group"]
        [:select.setting-select
         {:id        "sequence-group"
          :value     (str group-id)
          :on-change #(re-frame/dispatch [::events/set-sequence-group id (uuid (.. % -target -value))])}
         (for [{gid :id gname :name} groups]
           ^{:key (str gid)}
           [:option {:value (str gid)} gname])]]

       [:div.setting
        [:span.setting-label {:id "sequence-days"} "Days"]
        [:div.chips
         (for [[label preset] presets]
           ^{:key label}
           [:button.chip {:type         "button"
                          :aria-pressed (= days preset)
                          :on-click     #(set-days! preset)}
            label])]
        [:div.chips.days {:role "group" :aria-labelledby "sequence-days"}
         (for [[k short long] week]
           ^{:key k}
           [:button.chip.day
            {:type         "button"
             :aria-pressed (contains? days k)
             :aria-label   long
             :on-click     #(set-days! ((if (contains? days k) disj conj) days k))}
            short])]
        (when (empty? days)
          [:p.setting-note "Not scheduled on any day, so it won't appear on the child's list."])]

       [:div.setting
        [:button.settings-button
         {:type     "button"
          ;; Synchronous, so sharing happens within the click.
          :on-click #(re-frame/dispatch-sync [::events/export-sequences [id]])}
         "Share this sequence"]
        (when (seq @(re-frame/subscribe [::subs/link-closure-titles id]))
          [:p.setting-note
           (str "Also includes " (str/join ", " @(re-frame/subscribe [::subs/link-closure-titles id]))
                ", which it links to.")])]

       [:div.setting
        [:button.danger-button
         {:type     "button"
          :disabled (boolean (seq links-to))
          :on-click #(re-frame/dispatch [::events/delete-sequence id])}
         [icons/trash] "Delete sequence"]
        (when (seq links-to)
          [:p.setting-note
           (let [one? (= 1 (count links-to))]
             (str (str/join ", " (map #(str (:title %) " (step " (:step %) ")") links-to))
                  (if one? " links" " link")
                  " to this sequence. Remove "
                  (if one? "that step" "those steps")
                  " first to delete it."))])]])))

;; ---------------------------------------------------------------------------
;; Picture dialog: a step's picture, or a sequence's card picture

(defn- photo-button
  "Opens the file picker; on phones it offers the camera and the gallery."
  [target label]
  (r/with-let [input (atom nil)]
    [:<>
     [:input.visually-hidden
      {:type        "file"
       :accept      "image/*"
       :aria-hidden true
       :tab-index   -1
       :ref         #(reset! input %)
       :on-change   (fn [e]
                      (let [el (.-target e)]
                        (when-let [file (aget (.-files el) 0)]
                          (re-frame/dispatch [::events/picture-file-chosen target file]))
                        (set! (.-value el) "")))}]
     [:button.dialog-button.is-primary
      {:type "button" :on-click #(some-> @input .click)}
      label]]))

(defn- preview
  "The picture as the step will show it. When filled, tapping sets the part
  kept in the middle. (Card pictures always fill their small frame.)"
  [{:keys [id title image-id image-fit image-position]} step?]
  (let [cover? (and step? (= :cover image-fit))]
    [:div.picture-preview
     {:class    (when cover? "is-focusable")
      :on-click (when cover?
                  (fn [e]
                    (let [r (.getBoundingClientRect (.-currentTarget e))]
                      (re-frame/dispatch [::events/set-step-image-position id
                                          (* 100 (/ (- (.-clientX e) (.-left r)) (.-width r)))
                                          (* 100 (/ (- (.-clientY e) (.-top r)) (.-height r)))]))))}
     [picture/picture {:image-id image-id :kind :full :fit (if step? image-fit :cover)
                       :position image-position :alt title :class "step-picture"}]
     (when cover?
       (let [[x y] (str/split (or image-position "50% 50%") #" ")]
         [:span.focus-mark {:style {:left x :top y}}]))]))

(defn- library-grid [target current-id close!]
  (let [images (remove #(= current-id (:id %)) @(re-frame/subscribe [::subs/image-library]))]
    [:div.picture-library
     (if (seq images)
       [:ul.picture-library-grid
        (for [{:keys [id name]} images]
          ^{:key (str id)}
          [:li
           [:button.picture-choice
            {:type     "button"
             :on-click (fn []
                         (close!)
                         (re-frame/dispatch [::events/use-library-image target id]))}
            [picture/picture {:image-id id :kind :thumb :fit :cover :alt "" :class "picture-choice-thumb"}]
            [:span.picture-choice-name name]]])]
       [:p.setting-note "No other pictures yet."])]))

(defn picture-dialog []
  (r/with-let [browsing? (r/atom false)]
    (when-let [{:keys [target subject busy? error]} @(re-frame/subscribe [::subs/picture-editor])]
      (let [{:keys [id title image-id image-fit]} subject
            step?  (contains? target :step-id)
            close! #(do (reset! browsing? false)
                        (re-frame/dispatch [::events/close-picture-editor]))]
        [:div.dialog-overlay
         [:div.dialog.picture-dialog {:role "dialog" :aria-modal true :aria-labelledby "picture-title"}
          [:h2#picture-title.dialog-title
           (cond
             (contains? target :sequence-id) (str "Card picture for \"" title "\"")
             :else                           (str "Picture for \"" title "\""))]
          (cond
            busy?    [:p.setting-note "Adding the picture…"]
            error    [:p.setting-note error]
            image-id [preview subject step?])
          (when (and image-id step?)
            [:<>
             [:div.chips {:role "group" :aria-label "Framing"}
              [:button.chip {:type "button" :aria-pressed (not= :cover image-fit)
                             :on-click #(re-frame/dispatch [::events/set-step-image-fit id :contain])}
               "Fit"]
              [:button.chip {:type "button" :aria-pressed (= :cover image-fit)
                             :on-click #(re-frame/dispatch [::events/set-step-image-fit id :cover])}
               "Fill"]]
             [:p.setting-note (if (= :cover image-fit)
                                "Fill crops the edges. Tap the picture to choose the part to keep in the middle."
                                "Fit shows the whole picture.")]])
          [:div.picture-actions
           [photo-button target (if image-id "New photo" "Take or choose a photo")]
           [:button.dialog-button
            {:type "button" :aria-expanded @browsing? :on-click #(swap! browsing? not)}
            "From the library"]
           (when image-id
             [:button.dialog-button
              {:type "button" :on-click #(re-frame/dispatch [::events/remove-picture target])}
              "Remove picture"])]
          (when @browsing?
            [library-grid target image-id #(reset! browsing? false)])
          [:div.dialog-actions
           [:button.dialog-button {:type "button" :on-click close!} "Done"]]]]))))

;; ---------------------------------------------------------------------------
;; List editor: a checklist ("What you need") or a choice step's options.
;; Things are shared: renaming or re-picturing one changes it everywhere.

(defn- list-row [target {:keys [id title image-id]} i n]
  (let [move! (fn [to]
                ;; Keep keyboard focus on an arrow that still works: at the
                ;; top the up arrow is disabled, at the bottom the down one.
                (let [attr (cond (zero? to) "data-down" (= to (dec n)) "data-up"
                                 (< to i) "data-up" :else "data-down")]
                  #(re-frame/dispatch [::events/list-move target i to (str "[" attr "=\"" id "\"]")])))]
    [:li.list-row
     (if image-id
       [:button.picture-slot
        {:type "button" :aria-label (str "Picture for \"" title "\"")
         :on-click #(re-frame/dispatch [::events/open-picture-editor {:item-id id}])}
        [picture/picture {:image-id image-id :kind :thumb :fit :cover :alt "" :class "list-thumb"}]]
       [:button.list-add-picture
        {:type "button" :aria-label (str "Add a picture for \"" title "\"")
         :on-click #(re-frame/dispatch [::events/open-picture-editor {:item-id id}])}
        [icons/plus]])
     ^{:key title}
     [fields/text-field {:value      title
                         :max-length schema/max-name
                         :class      "list-name"
                         :label      "Name"
                         :attrs      {:data-item-for (str id)}
                         :on-commit  #(re-frame/dispatch [::events/rename-item id %])}]
     [:span.list-actions
      [:button.row-action {:type "button" :disabled (zero? i) :data-up (str id)
                           :aria-label (str "Move \"" title "\" up") :on-click (move! (dec i))}
       [icons/chevron-up]]
      [:button.row-action {:type "button" :disabled (= i (dec n)) :data-down (str id)
                           :aria-label (str "Move \"" title "\" down") :on-click (move! (inc i))}
       [icons/chevron-down]]
      [:button.row-action {:type "button" :aria-label (str "Take \"" title "\" off the list")
                           :on-click #(re-frame/dispatch [::events/list-remove target id])}
       [icons/trash]]]]))

(defn- existing-picker [target others close!]
  [:div.list-existing
   (if (seq others)
     [:ul.link-picker-list
      (for [{:keys [id title image-id]} others]
        ^{:key (str id)}
        [:li
         [:button.link-choice.list-existing-choice
          {:type "button"
           :on-click #(re-frame/dispatch [::events/list-add-existing target id])}
          (when image-id
            [picture/picture {:image-id image-id :kind :thumb :fit :cover :alt "" :class "list-thumb"}])
          [:span.link-choice-title title]]])]
     [:p.setting-note "Every thing is already on this list."])
   [:button.link-picker-cancel {:type "button" :on-click close!} "Done adding"]])

(defn list-dialog []
  (r/with-let [adding? (r/atom false)]
    (when-let [{:keys [target kind title items others collection collections uses]}
               @(re-frame/subscribe [::subs/list-editor])]
      (let [close! #(do (reset! adding? false) (re-frame/dispatch [::events/close-list-editor]))
            choice? (= :choice kind)]
        [:div.dialog-overlay
         [:div.dialog.picture-dialog {:role "dialog" :aria-modal true :aria-labelledby "list-title"}
          [:h2#list-title.dialog-title
           (if choice? (str "Options for \"" title "\"") (str "What you need for \"" title "\""))]
          (when choice?
            [:div.setting
             [:label.setting-label {:for "list-choice"} "List"]
             [:select.setting-select
              {:id        "list-choice"
               :value     (str (:id collection))
               :on-change (fn [e]
                            (let [v (.. e -target -value)]
                              (if (= v "new")
                                (re-frame/dispatch [::events/new-list-for-step (:step-id target)])
                                (re-frame/dispatch [::events/set-step-collection (:step-id target) (uuid v)]))))}
              (when-not collection [:option {:value ""} "Choose a list…"])
              (for [{cid :id cname :name} collections]
                ^{:key (str cid)}
                [:option {:value (str cid)} cname])
              [:option {:value "new"} "New list"]]
             (when collection
               ^{:key (str (:id collection) (:name collection))}
               [fields/text-field {:value      (:name collection)
                                   :max-length schema/max-name
                                   :class      "list-name-input"
                                   :label      "List name"
                                   :on-commit  #(re-frame/dispatch [::events/rename-collection (:id collection) %])}])
             (when (> (count uses) 1)
               [:p.setting-note (str "This list is used by " (count uses)
                                     " choice steps; changes apply to all of them.")])])
          (if (seq items)
            [:ol.list-rows
             (for [[i item] (map-indexed vector items)]
               ^{:key (str (:id item))}
               [list-row target item i (count items)])]
            [:p.setting-note (if choice? "No options yet." "Nothing on the list yet.")])
          (when (or (not choice?) collection)
            [:div.picture-actions
             [:button.dialog-button.is-primary
              {:type "button" :on-click #(re-frame/dispatch [::events/list-add-new target])}
              "+ New thing"]
             [:button.dialog-button
              {:type "button" :aria-expanded @adding? :on-click #(swap! adding? not)}
              "+ From existing"]])
          (when @adding?
            [existing-picker target others #(reset! adding? false)])
          [:p.setting-note "Names and pictures of things are shared: changing one changes it everywhere it's used."]
          [:div.dialog-actions
           [:button.dialog-button {:type "button" :on-click close!} "Done"]]]]))))
