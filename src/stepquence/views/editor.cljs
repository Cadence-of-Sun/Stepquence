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
   [stepquence.events :as events]
   [stepquence.schema :as schema]
   [stepquence.subs :as subs]
   [stepquence.views.icons :as icons]
   [stepquence.views.progress :as progress]))

;; ---------------------------------------------------------------------------
;; Text editing

(defn- text-field
  "Edits `value` in place: saved when the field loses focus or on Enter;
  Escape reverts. A blank value reverts unless `allow-blank?`. `on-done`
  runs on every blur. Callers key this on `value` so outside changes
  (undo, clipping) remount it with the new text."
  [{:keys [value on-commit on-done max-length multiline? allow-blank?
           class label auto-focus? attrs]}]
  (r/with-let [draft   (r/atom value)
               commit! (fn []
                         (let [v (str/trim @draft)]
                           (if (or (= v value) (and (str/blank? v) (not allow-blank?)))
                             (reset! draft value)
                             (on-commit v))
                           (when on-done (on-done))))]
    [(if multiline? :textarea.text-field :input.text-field)
     (merge {:class       class
             :value       @draft
             :max-length  max-length
             :aria-label  label
             :rows        (when multiline? 1)
             :auto-focus  auto-focus?
             :on-change   #(reset! draft (.. % -target -value))
             :on-blur     commit!
             :on-key-down (fn [e]
                            (case (.-key e)
                              "Enter"  (do (.preventDefault e)
                                           (.blur (.-target e)))
                              "Escape" (do (reset! draft value)
                                           (.blur (.-target e)))
                              nil))}
            attrs)]))

(defn- description-field [step-id description]
  (r/with-let [adding? (r/atom false)]
    (if (or description @adding?)
      [text-field {:value        (or description "")
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
     [text-field {:value      title
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
  [:li.overview-row {:class [(when current? "is-current") (when done? "is-done")]}
   [:div.overview-row-inner
    [:span.drag-spacer]
    [marker-cell row]
    [:span.overview-text
     [:span.overview-title "What you need"]
     [:span.overview-detail (str item-count (if (= 1 item-count) " item" " items"))]]
    [:span.row-actions
     [go-button row]]]])

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
        [text-field {:value      title
                     :max-length schema/max-step-title
                     :class      "overview-title-input"
                     :label      "Step title"
                     :attrs      {:data-title-for (str id)}
                     :on-commit  #(re-frame/dispatch [::events/edit-step-title id %])}]
        (when (= :choice kind)
          [:span.overview-detail (str "Choice from " (or collection-name "a missing collection"))])
        ^{:key (str id "|" description)}
        [description-field id description]])]
    [:span.row-actions
     [:button.row-action
      {:type       "button"
       :disabled   (not can-delete?)
       :title      (when-not can-delete? "A sequence keeps at least one step")
       :aria-label (str "Delete \"" title "\"")
       :on-click   #(re-frame/dispatch [::events/delete-step id])}
      [icons/trash]]
     [go-button row]]]])

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
          [:button.add-step
           {:type     "button"
            :on-click #(re-frame/dispatch [::events/add-step])}
           [icons/plus] "Add step"]
          [:p.add-step-full (str "A sequence can have up to " schema/max-steps " steps.")])]])))

(defn undo-toast []
  (when-let [{:keys [message]} @(re-frame/subscribe [::subs/undo])]
    [:div.undo-toast {:role "status"}
     [:span message]
     [:button.undo-button
      {:type     "button"
       :on-click #(re-frame/dispatch [::events/undo])}
      "Undo"]]))
