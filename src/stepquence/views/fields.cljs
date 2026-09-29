(ns stepquence.views.fields
  "Shared form controls."
  (:require
   [clojure.string :as str]
   [reagent.core :as r]))

(defn text-field
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
