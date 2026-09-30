(ns stepquence.install
  "Installing as an app. Ported from brainboard's install namespace.

  Chromium browsers fire `beforeinstallprompt` once their install criteria
  are met (manifest, service worker, not already installed); a button that
  shows only while `install-event` holds something appears exactly when
  installing is possible. Firefox and Safari never fire it. iOS Safari
  installs through Share > Add to Home Screen, so there we show a hint.

  A raw browser event isn't app data, so it lives in a plain Reagent atom
  rather than app-db."
  (:require
   [reagent.core :as r]))

(def install-event
  "The captured beforeinstallprompt event, ready to .prompt(), or nil."
  (r/atom nil))

(defn listen!
  "Called once at startup (see core/init)."
  []
  (.addEventListener js/window "beforeinstallprompt"
                     (fn [e]
                       (.preventDefault e)
                       (reset! install-event e)))
  ;; Once installed, the prompt is spent either way; also covers installing
  ;; through the browser's own address-bar affordance instead of ours.
  (.addEventListener js/window "appinstalled"
                     (fn [_] (reset! install-event nil))))

(defn install!
  "Shows the captured browser prompt, if one is waiting."
  []
  (when-let [e @install-event]
    (.prompt e)
    (reset! install-event nil)))

(defn standalone?
  "Already running as an installed app."
  []
  (or (.-matches (js/matchMedia "(display-mode: standalone)"))
      (true? (.-standalone js/navigator))))

(defn ios-safari?
  "iOS (including iPadOS, which reports itself as a Mac with touch)."
  []
  (let [ua (.-userAgent js/navigator)]
    (or (boolean (re-find #"iPad|iPhone|iPod" ua))
        (and (boolean (re-find #"Macintosh" ua))
             (> (.-maxTouchPoints js/navigator) 1)))))
