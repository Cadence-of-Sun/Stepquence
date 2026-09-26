(ns stepquence.views.progress
  "The progress dot bar. Driven by ::subs/progress-markers so the overview
  can render the same markers with the same state.

  Marker shapes echo the controls they stand for: the square requirements
  box matches the checklist's boxes; the choice stack matches the vertical
  list of option dots; a link step is a pill holding a little sequence.")

(defn- requirements-marker []
  ;; The check is drawn in by CSS (stroke-dashoffset) when the marker is done.
  [:svg.req-marker {:viewBox "0 0 20 20"}
   [:rect {:x 1.5 :y 1.5 :width 17 :height 17 :rx 4}]
   [:path.req-check {:d "M5.5 10.5 L8.7 13.6 L14.5 6.8"}]])

(defn- marker-body [{:keys [kind options]}]
  (case kind
    :requirements [requirements-marker]
    :link         [:span.dot-link [:i] [:i] [:i]]
    :choice       (if (seq options)
                    [:span.dot-stack
                     (for [{:keys [id state]} options]
                       ^{:key (str id)}
                       [:span.dot.dot-option {:class (str "is-" (name state))}])]
                    [:span.dot])
    [:span.dot]))

(defn bar
  "Decorative: the position is announced by the text label above it.

  The current position is a line under the dot rather than a ring: a single
  element sliding between equal-width slots. `move-key` changes on every
  move; moving forward, the line briefly becomes an arrow. When the run is
  `complete?`, the line grows to underline the whole bar."
  [markers {:keys [move-key direction complete?]}]
  (let [current (first (keep-indexed (fn [i m] (when (:current? m) i)) markers))]
    [:div.progress {:aria-hidden true
                    :style       {"--n" (count markers)}}
     [:ol.progress-markers
      (for [{:keys [id done?] :as marker} markers]
        ^{:key (str id)}
        [:li.marker {:class (when done? "is-done")}
         [marker-body marker]])]
     [:div.progress-indicator
      {:class (cond complete?     "is-complete"
                    (nil? current) "is-hidden")
       :style {"--i" (or current 0)}}
      ;; Remounted on each move so its animation replays.
      ^{:key move-key}
      [:span.indicator-shape
       {:class (if complete? "go-complete" (str "go-" (name direction)))}]]]))

(defn mini-dots
  "A small row of dots, one per step: a sequence at a glance."
  [steps]
  [:span.mini-dots {:aria-hidden true}
   (for [{:keys [id kind]} steps]
     ^{:key (str id)}
     [:i {:class (when (= :link kind) "is-link")}])])
