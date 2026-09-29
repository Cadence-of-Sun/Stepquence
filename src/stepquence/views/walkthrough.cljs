(ns stepquence.views.walkthrough
  "One step per screen.

    Exit            (Part of …)
          [ card: title, description, options ]
          [ Done ]                                  <- fixed position
    ← Back      3 of 7 / dots        Next: …

  The card sits directly above the primary button and grows upward, so
  the button never moves. Only the card content changes between steps.

  On an already-completed (revisited) position there is nothing to do,
  just pages to turn: the primary button is hidden and the corners become
  a symmetric pair, ← Back and Next →."
  (:require
   [re-frame.core :as re-frame]
   [stepquence.events :as events]
   [stepquence.subs :as subs]
   [stepquence.views.editor :as editor]
   [stepquence.views.icons :as icons]
   [stepquence.views.progress :as progress]))

(defn- view-toggle [overview?]
  ;; Same pattern as the theme toggle: both options shown, the active one
  ;; highlighted; pressing switches.
  [:button.view-toggle.toggle
   {:type       "button"
    :aria-label (if overview? "Show one step" "Show all steps")
    :on-click   #(re-frame/dispatch [::events/toggle-overview])}
   [:span.toggle-option {:class (when-not overview? "is-active")} [icons/one-step]]
   [:span.toggle-slash "/"]
   [:span.toggle-option {:class (when overview? "is-active")} [icons/all-steps]]])

(defn- sequence-heading
  "The active sequence's title, with \"Part of …\" inside a linked
  sequence. Small in the step view's header, the list heading in the
  overview; shared view-transition names move it between the two."
  [title-tag]
  (let [title        (:title @(re-frame/subscribe [::subs/run-sequence]))
        parent-title @(re-frame/subscribe [::subs/parent-title])]
    [:div.sequence-heading
     [title-tag {:class "sequence-heading-title"
                 :style {:view-transition-name "sequence-title"}}
      title]
     (when parent-title
       [:p.sequence-heading-context
        {:style {:view-transition-name "sequence-context"}}
        "Part of " [:strong parent-title]])]))

(defn- header [finished? overview?]
  [:header.wt-header
   ;; Hidden on the finished screen, whose primary button leaves instead.
   ;; Hidden, not removed, so nothing shifts.
   [:button.wt-exit
    {:type        "button"
     :style       {:visibility (if finished? "hidden" "visible")}
     :aria-hidden finished?
     :on-click    #(re-frame/dispatch [::events/exit])}
    "Exit"]
   ;; The title moves to the list heading in the overview, and is hidden on
   ;; the finished screen, whose card names the sequence itself.
   [:div.wt-header-title
    {:style {:visibility (if finished? "hidden" "visible")}}
    (when-not overview?
      [sequence-heading :p])]
   [view-toggle overview?]])

;; ---------------------------------------------------------------------------
;; Overview: the same steps as a vertical list. Each row's marker is the
;; same element as in the progress bar (shared view-transition-name), so
;; toggling moves the dots between the two. View-only for the child.

(defn- overview-list []
  [:ol.overview
   (for [{:keys [id title description detail current? done?] :as m} @(re-frame/subscribe [::subs/overview-rows])]
     ^{:key (str id)}
     [:li.overview-row {:class [(when current? "is-current")
                                (when done? "is-done")]}
      ;; The current row is a shortcut back to its step view (the same
      ;; transition as the toggle). Other rows are view-only.
      [(if current? :button.overview-row-inner :div.overview-row-inner)
       (when current?
         {:type       "button"
          :aria-label (str "Go to step: " title)
          :on-click   #(re-frame/dispatch [::events/toggle-overview])})
       [:span.overview-marker
        [progress/marker m]
        (when current?
          [:span.overview-indicator
           {:style {:view-transition-name progress/indicator-transition-name}}
           [:span.indicator-shape]])]
       [:span.overview-text
        [:span.overview-title title]
        (when detail
          [:span.overview-detail detail])
        ;; Only the current step's description, if it has one.
        (when (and current? description)
          [:span.overview-description description])]
       (when current?
         [:span.overview-go [icons/arrow-right]])]])])

(defn- overview []
  ;; Only the list scrolls; the heading stays in view above it. In parent
  ;; mode the overview is the sequence editor.
  (if @(re-frame/subscribe [::subs/parent?])
    [:<>
     [editor/heading]
     [:div.overview-scroll
      [editor/overview-editor]]
     [editor/undo-toast]]
    [:<>
     [sequence-heading :h1]
     [:div.overview-scroll
      [overview-list]]]))

;; ---------------------------------------------------------------------------
;; Card

(defn- requirements-card []
  [:<>
   [:h1.card-title "What you need"]
   [:ul.checklist
    (for [{:keys [id title checked?]} @(re-frame/subscribe [::subs/requirements])]
      ^{:key (str id)}
      [:li
       [:button.check-item
        {:type         "button"
         :role         "checkbox"
         :aria-checked checked?
         :on-click     #(re-frame/dispatch [::events/toggle-requirement id])}
        [:span.check-box]
        [:span.check-label title]]])]])

(defn- choice-options [step]
  ;; Vertical, in the same order as the dot stack in the progress bar;
  ;; each option's dot fills when chosen.
  (let [options @(re-frame/subscribe [::subs/choice-options])]
    (when (seq options)
      [:ul.choices
       (for [{:keys [id title chosen?]} options]
         ^{:key (str id)}
         [:li
          [:button.choice
           {:type         "button"
            :aria-pressed chosen?
            :on-click     #(re-frame/dispatch [::events/choose (:id step) id])}
           [:span.choice-dot]
           [:span.choice-label title]]])])))

(defn- step-card []
  (let [{:keys [kind title description] :as step} @(re-frame/subscribe [::subs/current-step])]
    ;; Image-less layout: large centered title, description below. The image
    ;; frame slots in above the title once images exist.
    [:<>
     [:h1.card-title title]
     (when description
       [:p.card-description description])
     (case kind
       :choice [choice-options step]
       :link   [progress/mini-dots @(re-frame/subscribe [::subs/link-steps])]
       nil)]))

(defn- finished-card []
  [:<>
   [icons/check-circle]
   [:p.finished-sequence (:title @(re-frame/subscribe [::subs/run-sequence]))]
   [:h1.card-title.finished-title "All done!"]])

(defn- card []
  (let [phase     @(re-frame/subscribe [::subs/phase])
        direction @(re-frame/subscribe [::subs/nav-direction])]
    ;; Keyed by position so each new card mounts fresh and plays its
    ;; slide-in animation.
    ^{:key @(re-frame/subscribe [::subs/card-key])}
    [:article.card {:class (str "enter-" (name direction))}
     (case phase
       :requirements [requirements-card]
       :steps        [step-card]
       :finished     [finished-card]
       nil)]))

(defn- primary-button []
  ;; Not part of the keyed card: it stays mounted and doesn't slide.
  (let [{:keys [action label arrow? enabled? gated?]} @(re-frame/subscribe [::subs/primary])
        hidden? (= :continue action)]
    ;; Hidden (not removed) on revisits, so the content above stays put;
    ;; the corner Next button takes over.
    [:button.primary
     {:type        "button"
      :class       (when gated? "is-gated")
      :disabled    (or hidden? (not enabled?))
      :style       {:visibility (if hidden? "hidden" "visible")}
      :aria-hidden hidden?
      :on-click    #(re-frame/dispatch [::events/advance])}
     [:span.primary-label label]
     (when arrow? [icons/arrow-right])]))

;; ---------------------------------------------------------------------------
;; Footer

(defn- back-button [finished?]
  (let [visible? (and (not finished?) @(re-frame/subscribe [::subs/can-go-back?]))]
    [:button.wt-back
     {:type        "button"
      :style       {:visibility (if visible? "visible" "hidden")}
      :aria-hidden (not visible?)
      :on-click    #(re-frame/dispatch [::events/back])}
     [icons/arrow-left]
     [:span "Back"]]))

(defn- position [finished?]
  [:div.wt-progress
   ;; Non-breaking space keeps the line's height when there is no label.
   [:p.wt-position (or @(re-frame/subscribe [::subs/position-label]) " ")]
   [progress/bar
    @(re-frame/subscribe [::subs/progress-markers])
    {:move-key  @(re-frame/subscribe [::subs/card-key])
     :direction @(re-frame/subscribe [::subs/nav-direction])
     :complete? finished?
     ;; Parent preview: the dots jump.
     :on-select (when @(re-frame/subscribe [::subs/parent?])
                  #(re-frame/dispatch [::events/jump %]))}]])

(defn- up-next [finished?]
  (let [{:keys [kind title]} @(re-frame/subscribe [::subs/up-next])
        revisit?             (= :continue (:action @(re-frame/subscribe [::subs/primary])))]
    (if revisit?
      ;; Mirrors Back: a neutral page turn to a position already seen.
      [:button.wt-next
       {:type     "button"
        :on-click #(re-frame/dispatch [::events/advance])}
       [:span "Next"]
       [icons/arrow-right]]
      [:div.up-next
       {:class       (when (= :finish kind) "is-finish")
        :style       {:visibility (if finished? "hidden" "visible")}
        :aria-hidden finished?}
       [:span.up-next-label "Next:"]
       [:span.up-next-title (if (= :finish kind) "All done!" title)]])))

(defn walkthrough []
  (let [finished? (= :finished @(re-frame/subscribe [::subs/phase]))
        overview? @(re-frame/subscribe [::subs/overview?])]
    (if overview?
      [:div.walkthrough.is-overview
       [header finished? overview?]
       [:main.wt-overview
        [overview]]]
      [:div.walkthrough
       [header finished? overview?]
       [:main.wt-main
        [:div.wt-content
         [card]]
        [primary-button]]
       [:footer.wt-footer
        [back-button finished?]
        [position finished?]
        [up-next finished?]]])))
