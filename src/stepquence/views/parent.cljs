(ns stepquence.views.parent
  "Parent mode: the hidden entry gesture, the PIN pad, and the banner shown
  while unlocked."
  (:require
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [stepquence.events :as events]
   [stepquence.subs :as subs]
   [stepquence.views.icons :as icons]))

(def long-press-ms 2000)

(defn long-press-heading
  "A heading that opens parent mode when held for 2 seconds. No visual
  feedback: to a child it is just a heading, and a short press does
  nothing. Lifting, moving off, or scrolling (pointercancel) cancels."
  [tag text]
  (r/with-let [timer   (atom nil)
               cancel! #(when-let [t @timer]
                          (js/clearTimeout t)
                          (reset! timer nil))]
    [tag {:class             "long-press"
          :on-pointer-down   (fn [_]
                               (cancel!)
                               (reset! timer (js/setTimeout
                                              (fn []
                                                (reset! timer nil)
                                                (re-frame/dispatch [::events/parent-gesture]))
                                              long-press-ms)))
          :on-pointer-up     cancel!
          :on-pointer-leave  cancel!
          :on-pointer-cancel cancel!
          ;; No long-press context menu / selection callout.
          :on-context-menu   #(.preventDefault %)}
     text]
    (finally (cancel!))))

;; ---------------------------------------------------------------------------
;; PIN pad

(defn- pin-pad-panel [{:keys [mode digits message]}]
  (r/with-let [on-key (fn [e]
                        (let [k (.-key e)]
                          (cond
                            (re-matches #"\d" k) (re-frame/dispatch [::events/pin-digit k])
                            (= k "Backspace")    (re-frame/dispatch [::events/pin-backspace])
                            (= k "Enter")        (re-frame/dispatch [::events/pin-submit])
                            (= k "Escape")       (re-frame/dispatch [::events/pin-cancel]))))
               _      (.addEventListener js/document "keydown" on-key)]
    [:div.pin-overlay {:role "dialog" :aria-modal true :aria-label "Parent mode"}
     [:div.pin-panel
      [:h2.pin-title "Parent mode"]
      [:p.pin-prompt
       (case mode
         :unlock  "Enter your PIN"
         :set     (str "Choose a PIN (" events/pin-min " to " events/pin-max " digits)")
         :confirm "Enter it again")]
      [:div.pin-dots {:role "status" :aria-label (str (count digits) " digits entered")}
       (for [i (range (count digits))]
         ^{:key i} [:i])]
      [:p.pin-message (or message " ")]
      [:div.pin-keys
       (for [d "123456789"]
         ^{:key d}
         [:button.pin-key {:type     "button"
                           :on-click #(re-frame/dispatch [::events/pin-digit (str d)])}
          d])
       [:button.pin-key.is-quiet {:type     "button"
                                  :on-click #(re-frame/dispatch [::events/pin-cancel])}
        "Cancel"]
       [:button.pin-key {:type     "button"
                         :on-click #(re-frame/dispatch [::events/pin-digit "0"])}
        "0"]
       [:button.pin-key.is-quiet {:type       "button"
                                  :aria-label "Delete"
                                  :on-click   #(re-frame/dispatch [::events/pin-backspace])}
        [icons/backspace]]]
      [:button.primary.pin-ok
       {:type     "button"
        :disabled (< (count digits) events/pin-min)
        :on-click #(re-frame/dispatch [::events/pin-submit])}
       [:span.primary-label (case mode :unlock "Unlock" :set "Next" :confirm "Save")]]]]
    (finally (.removeEventListener js/document "keydown" on-key))))

(defn pin-pad []
  (when-let [entry @(re-frame/subscribe [::subs/pin-entry])]
    [pin-pad-panel entry]))

;; ---------------------------------------------------------------------------
;; Banner

(defn- theme-toggle []
  ;; Shows which theme is active (the system's until pressed); pressing
  ;; switches to the other and remembers it.
  (let [showing @(re-frame/subscribe [::subs/theme-showing])
        other   (if (= :dark showing) "light" "dark")]
    [:button.theme-toggle.toggle
     {:type       "button"
      :aria-label (str "Switch to " other " theme")
      :on-click   #(re-frame/dispatch [::events/toggle-theme])}
     [:span.toggle-option {:class (when (= :light showing) "is-active")} [icons/sun]]
     [:span.toggle-slash "/"]
     [:span.toggle-option {:class (when (= :dark showing) "is-active")} [icons/moon]]]))

(defn banner
  "Shown on every screen while unlocked, so the mode is never ambiguous."
  []
  (when @(re-frame/subscribe [::subs/parent?])
    [:div.parent-banner
     [:span.parent-banner-label "Parent mode"]
     [theme-toggle]
     [:button.parent-lock
      {:type     "button"
       :on-click #(re-frame/dispatch [::events/lock-parent])}
      "Lock"]]))
