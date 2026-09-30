(ns stepquence.views.picture
  "Showing library pictures. The frame (sized by its class) reserves its
  space while the picture loads, so nothing shifts. If the picture can't be
  shown (missing or damaged file), nothing renders at all and the layout
  falls back to text only; never a broken-image icon."
  (:require
   [stepquence.images :as images]))

(defn picture
  "`kind` :full or :thumb; `fit` :contain (whole picture) or :cover
  (filled, cropped around `position`); `alt` is the step title (or \"\"
  when a title is already next to it)."
  [{:keys [image-id kind fit position alt class]}]
  (let [u (images/url image-id kind)]
    (when-not (images/failed? u)
      [:span.picture {:class class}
       (when u
         [:img {:src      u
                :alt      alt
                :draggable false
                :style    {:object-fit      (name (or fit :contain))
                           :object-position (or position "50% 50%")}
                :on-error #(images/mark-failed! image-id kind)}])])))
