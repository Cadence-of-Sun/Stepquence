(ns stepquence.dismiss
  "Closing a popover the way people expect: Escape, or a press anywhere outside it.")

(defn listen
  "While `open?` returns true, pressing Escape calls (close! :escape) and pressing
  anywhere outside the element `get-root` returns calls (close! :outside).
  Returns a function that removes the listeners."
  [get-root open? close!]
  (let [on-press (fn [e]
                   (when (open?)
                     (let [root (get-root)]
                       (when (and root (not (.contains root (.-target e))))
                         (close! :outside)))))
        on-key   (fn [e]
                   (when (and (open?) (= "Escape" (.-key e)))
                     (close! :escape)))]
    (.addEventListener js/document "pointerdown" on-press)
    (.addEventListener js/document "keydown" on-key)
    (fn []
      (.removeEventListener js/document "pointerdown" on-press)
      (.removeEventListener js/document "keydown" on-key))))
