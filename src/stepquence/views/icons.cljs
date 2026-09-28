(ns stepquence.views.icons
  "Small inline SVG icons. Stroke/fill use currentColor.")

(defn sun []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:circle {:cx 12 :cy 12 :r 4.5 :fill "currentColor"}]
   [:g {:stroke "currentColor" :stroke-width 2 :stroke-linecap "round"}
    (for [angle (range 0 360 45)]
      ^{:key angle}
      [:line {:x1 12 :y1 2.5 :x2 12 :y2 4.8
              :transform (str "rotate(" angle " 12 12)")}])]])

(defn moon []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill "currentColor"
           :d    "M20 14.6A8.5 8.5 0 0 1 9.4 4a8.5 8.5 0 1 0 10.6 10.6Z"}]])

(defn one-step
  "A single card: the one-step view."
  []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:rect {:x 5 :y 4 :width 14 :height 16 :rx 2.5
           :fill "none" :stroke "currentColor" :stroke-width 2.2}]
   [:path {:d "M9 12h6" :stroke "currentColor" :stroke-width 2.2 :stroke-linecap "round"}]])

(defn all-steps
  "A list of dotted rows: the overview."
  []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   (for [y [6 12 18]]
     ^{:key y}
     [:g
      [:circle {:cx 5 :cy y :r 2 :fill "currentColor"}]
      [:path {:d (str "M10 " y "h10") :stroke "currentColor" :stroke-width 2.2 :stroke-linecap "round"}]])])

(defn arrow-right []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill            "none"
           :stroke          "currentColor"
           :stroke-width    2.5
           :stroke-linecap  "round"
           :stroke-linejoin "round"
           :d               "M4 12h15M13 6l6 6-6 6"}]])

(defn arrow-left []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill            "none"
           :stroke          "currentColor"
           :stroke-width    2.5
           :stroke-linecap  "round"
           :stroke-linejoin "round"
           :d               "M20 12H5M11 6l-6 6 6 6"}]])

(defn checkmark
  "A bare checkmark stroke, sized and colored like the surrounding text
  (1em, currentColor)."
  []
  [:svg.checkmark {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill            "none"
           :stroke          "currentColor"
           :stroke-width    3
           :stroke-linecap  "round"
           :stroke-linejoin "round"
           :d               "M4.5 12.5l5 5L19.5 6.5"}]])

(defn check
  "A plain check in a filled circle."
  []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:circle {:cx 12 :cy 12 :r 11 :fill "currentColor"}]
   [:path {:fill            "none"
           :stroke          "var(--color-surface)"
           :stroke-width    2.8
           :stroke-linecap  "round"
           :stroke-linejoin "round"
           :d               "M7 12.5l3.3 3.2L17 8.8"}]])

(defn check-circle
  "Completion mark. The circle and check are drawn in by CSS
  (.finished-mark), check after circle."
  []
  [:svg.finished-mark {:viewBox "0 0 64 64" :aria-hidden true}
   [:circle.finished-mark-circle {:cx 32 :cy 32 :r 28}]
   [:path.finished-mark-check {:d "M20 33.5l8 8 16-18"}]])
