(ns stepquence.views.picture
  "Showing library pictures. The frame (sized by its class) reserves its
  space while the picture loads, so nothing shifts. If the picture can't be
  shown (missing or damaged file), nothing renders at all and the layout
  falls back to text only; never a broken-image icon."
  (:require
   [stepquence.images :as images]))

(defn- reduced-motion? []
  (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))

(defn soft-bounce!
  "A gentle, springy response to a tap: press in slightly, overshoot a
  little, settle. With reduced motion, a brief soft dim instead, so a tap
  still gets an answer. A new tap simply restarts it."
  [el]
  (if (reduced-motion?)
    (.animate el (clj->js [{:opacity 1} {:opacity 0.8 :offset 0.4} {:opacity 1}])
              #js {:duration 400 :easing "ease-out"})
    (.animate el (clj->js [{:transform "scale(1)"}
                           {:transform "scale(0.96)" :offset 0.18}
                           {:transform "scale(1.03)" :offset 0.5}
                           {:transform "scale(0.995)" :offset 0.78}
                           {:transform "scale(1)"}])
              #js {:duration 560 :easing "ease-out"})))

(defn picture
  "`kind` :full or :thumb; `fit` :contain (whole picture) or :cover
  (filled, cropped around `position`); `alt` is the step title (or \"\"
  when a title is already next to it). With `bouncy?`, a tap gets a soft
  springy response (it doesn't do anything else)."
  [{:keys [image-id kind fit position alt class bouncy?]}]
  (let [u (images/url image-id kind)]
    (when-not (images/failed? u)
      [:span.picture {:class    [class (when bouncy? "is-bouncy")]
                      :on-click (when bouncy? #(soft-bounce! (.-currentTarget %)))}
       (when u
         [:img {:src      u
                :alt      alt
                :draggable false
                :style    {:object-fit      (name (or fit :contain))
                           :object-position (or position "50% 50%")}
                :on-error #(images/mark-failed! image-id kind)}])])))
