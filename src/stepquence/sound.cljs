(ns stepquence.sound
  "The gentle end-of-timer chime: two soft sine notes, generated with Web
  Audio (no file to load, works offline). Off unless the parent turns sound
  on in Settings. The audio context is created (unlocked) during a tap,
  since browsers only allow sound after a user gesture."
  (:require
   [re-frame.core :as re-frame]))

(defonce ^:private ctx (atom nil))

(defn- context []
  (or @ctx
      (when-let [AC (or (.-AudioContext js/window) (.-webkitAudioContext js/window))]
        (reset! ctx (AC.)))))

(re-frame/reg-fx
 :sound/unlock
 ;; From a tap (starting a timer): make sure audio may play later.
 (fn [_]
   (some-> (context) (.resume))))

(defn- note! [ac freq start dur]
  (let [osc  (.createOscillator ac)
        gain (.createGain ac)
        t0   (+ (.-currentTime ac) start)]
    (set! (.. osc -type) "sine")
    (.setValueAtTime (.-frequency osc) freq t0)
    ;; Soft attack and a long, gentle fade.
    (.setValueAtTime (.-gain gain) 0.0001 t0)
    (.exponentialRampToValueAtTime (.-gain gain) 0.18 (+ t0 0.04))
    (.exponentialRampToValueAtTime (.-gain gain) 0.0001 (+ t0 dur))
    (.connect osc gain)
    (.connect gain (.-destination ac))
    (.start osc t0)
    (.stop osc (+ t0 dur 0.05))))

(re-frame/reg-fx
 :sound/chime
 (fn [_]
   (when-let [ac (context)]
     (-> (.resume ac)
         (.then (fn []
                  (note! ac 659.25 0 1.2)       ; E5
                  (note! ac 880.00 0.18 1.4))))))) ; A5
