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

(defn backspace []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill            "none"
           :stroke          "currentColor"
           :stroke-width    2
           :stroke-linejoin "round"
           :d               "M8.5 5H20a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H8.5L2.5 12z"}]
   [:path {:stroke         "currentColor"
           :stroke-width   2
           :stroke-linecap "round"
           :d              "M11 9l6 6M17 9l-6 6"}]])

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

(defn grip
  "Drag handle: two columns of dots."
  []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   (for [x [9 15] y [6 12 18]]
     ^{:key (str x y)}
     [:circle {:cx x :cy y :r 1.8 :fill "currentColor"}])])

(defn trash []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill            "none"
           :stroke          "currentColor"
           :stroke-width    2
           :stroke-linecap  "round"
           :stroke-linejoin "round"
           :d               "M4 7h16M9.5 7V4.5h5V7M6.5 7l1 12.5h9l1-12.5M10 11v5M14 11v5"}]])

(defn plus []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:stroke         "currentColor"
           :stroke-width   2.5
           :stroke-linecap "round"
           :d              "M12 5v14M5 12h14"}]])

(defn chevron-up []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill "none" :stroke "currentColor" :stroke-width 2.5
           :stroke-linecap "round" :stroke-linejoin "round" :d "M6 15l6-6 6 6"}]])

(defn chevron-down []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill "none" :stroke "currentColor" :stroke-width 2.5
           :stroke-linecap "round" :stroke-linejoin "round" :d "M6 9l6 6 6-6"}]])

(defn link
  "Two chain links: a step that walks through another sequence."
  []
  [:svg.icon {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:fill "none" :stroke "currentColor" :stroke-width 2.2
           :stroke-linecap "round" :stroke-linejoin "round"
           :d "M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1"}]])
