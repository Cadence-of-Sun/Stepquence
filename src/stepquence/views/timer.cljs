(ns stepquence.views.timer
  "The step timer: a ring that empties as time passes, the time left in
  its middle. Tap to start (never automatic), tap to pause or resume; ↺
  resets. At zero the ring turns green with a check and pulses once. It
  never advances the step or blocks Done."
  (:require
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [stepquence.events :as events]
   [stepquence.subs :as subs]
   [stepquence.timer :as timer]
   [stepquence.views.icons :as icons]))

(def ^:private circumference (* 2 js/Math.PI 45))

(defn step-timer [step-id seconds]
  ;; The clock only drives the display; finishing is decided by an event.
  (r/with-let [now      (r/atom (js/Date.now))
               interval (js/setInterval #(reset! now (js/Date.now)) 250)]
    (let [t          @(re-frame/subscribe [::subs/timer])
          t          (when (= step-id (:step-id t)) t)
          status     (or (:status t) :idle)
          left       (if t (timer/remaining t @now) (* 1000 seconds))
          ;; Finished draws as a full green ring around the check.
          frac       (cond (= :done (:status t)) 1
                           t                     (timer/fraction-left t @now)
                           :else                 1)
          label      (case status
                       :idle    (str "Start the timer, " (timer/label seconds))
                       :running (str "Pause the timer, " (timer/clock left) " left")
                       :paused  (str "Resume the timer, " (timer/clock left) " left")
                       :done    "The timer has finished. Tap to start it again")]
      [:div.step-timer {:class (str "is-" (name status))}
       [:button.timer-ring
        {:type       "button"
         :aria-label label
         :on-click   #(re-frame/dispatch [::events/timer-toggle step-id seconds])}
        [:svg.timer-svg {:viewBox "0 0 100 100" :aria-hidden true}
         [:circle.timer-track {:cx 50 :cy 50 :r 45}]
         [:circle.timer-fill {:cx 50 :cy 50 :r 45
                              :style {:stroke-dasharray  circumference
                                      :stroke-dashoffset (* circumference (- 1 frac))}}]]
        [:span.timer-center
         (case status
           :idle    [:<> [icons/play] [:span.timer-time (timer/clock left)]]
           :done    [icons/checkmark]
           [:<>
            [:span.timer-time (timer/clock left)]
            (when (= :paused status) [:span.timer-paused "Paused"])])]]
       [:button.timer-reset
        {:type        "button"
         :aria-label  "Reset the timer"
         :aria-hidden (= :idle status)
         :style       {:visibility (if (= :idle status) "hidden" "visible")}
         :on-click    #(re-frame/dispatch [::events/timer-reset])}
        [icons/reset]]])
    (finally (js/clearInterval interval))))
