(ns stepquence.timer
  "Step timers: pure calculations. A timer is for the action, not the step:
  it never starts by itself, never advances, and never blocks Done.

  State (ephemeral, :ui :timer), one at a time, for the current step:
    {:step-id … :duration-ms n :status :running|:paused|:done
     :started-at ms   ; when it last started or resumed
     :banked ms       ; running time before the last pause
     :token n}        ; identifies a run segment (for the finish check)
  Time comes from the clock, so it stays right while the app is in the
  background.")

(defn elapsed [{:keys [status started-at banked duration-ms]} now]
  (case status
    :running (+ banked (- now started-at))
    :paused  banked
    :done    duration-ms
    0))

(defn remaining
  "Milliseconds left (never negative)."
  [timer now]
  (max 0 (- (:duration-ms timer) (elapsed timer now))))

(defn fraction-left
  "1 when full, 0 when empty: how much of the ring is still there."
  [timer now]
  (let [d (:duration-ms timer)]
    (if (pos? d) (/ (remaining timer now) d) 0)))

(defn start [step-id seconds now]
  {:step-id     step-id
   :duration-ms (* 1000 seconds)
   :status      :running
   :started-at  now
   :banked      0
   :token       now})

(defn toggle
  "Tap: start (or start again after finishing), pause, or resume."
  [timer step-id seconds now]
  (cond
    (or (nil? timer) (not= step-id (:step-id timer)) (= :done (:status timer)))
    (start step-id seconds now)

    (= :running (:status timer))
    (assoc timer :status :paused :banked (elapsed timer now))

    :else
    (assoc timer :status :running :started-at now :token now)))

(defn clock
  "m:ss, rounding up so it shows 0:00 only at the very end."
  [ms]
  (let [s (js/Math.ceil (/ ms 1000))]
    (str (quot s 60) ":" (let [r (mod s 60)] (if (< r 10) (str "0" r) r)))))

(def presets
  "Timer lengths offered in the editor, in seconds."
  [15 30 45 60 90 120 180 300 600 900 1200])

(defn label
  "How a length reads to a parent: \"30 sec\", \"1 min\", \"1½ min\"."
  [seconds]
  (cond
    (< seconds 60)       (str seconds " sec")
    (= 30 (mod seconds 60)) (str (quot seconds 60) "½ min")
    (zero? (mod seconds 60)) (str (quot seconds 60) " min")
    :else                (str (quot seconds 60) " min " (mod seconds 60) " sec")))
