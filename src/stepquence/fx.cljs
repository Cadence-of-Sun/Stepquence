(ns stepquence.fx
  "Effect and coeffect handlers for browser APIs."
  (:require
   [re-frame.core :as re-frame]))

(re-frame/reg-cofx
 :now
 (fn [cofx]
   (assoc cofx :now (js/Date.now))))

;; ---------------------------------------------------------------------------
;; Theme

(def ^:private theme-storage-key "stepwise.theme")

(defn system-dark-query []
  (js/matchMedia "(prefers-color-scheme: dark)"))

(defn system-theme []
  (if (.-matches (system-dark-query)) :dark :light))

(re-frame/reg-cofx
 :theme/initial
 ;; :saved is the user's choice (nil until they press the toggle).
 (fn [cofx]
   (let [stored (try (.getItem js/localStorage theme-storage-key) (catch :default _ nil))]
     (assoc cofx :theme/initial
            {:saved  (case stored "light" :light "dark" :dark nil)
             :system (system-theme)}))))

(re-frame/reg-fx
 :theme/apply
 ;; With no saved theme the attribute is removed and the CSS follows
 ;; prefers-color-scheme directly.
 (fn [{:keys [theme save?]}]
   (let [root (.-documentElement js/document)]
     (if theme
       (set! (.. root -dataset -theme) (name theme))
       (.removeAttribute root "data-theme")))
   (when save?
     (try (.setItem js/localStorage theme-storage-key (name theme)) (catch :default _ nil)))))

;; ---------------------------------------------------------------------------
;; Screen Wake Lock
;;
;; The browser drops the lock whenever the page is hidden, so it has to be
;; requested again on return (see ::events/app-visible). `wanted?` covers a
;; release that arrives while a request is still pending.

(defonce ^:private wake-lock (atom {:wanted? false :sentinel nil}))

(defn- release-sentinel! []
  (when-let [sentinel (:sentinel @wake-lock)]
    (swap! wake-lock assoc :sentinel nil)
    (.catch (.release sentinel) (fn [_]))))

(re-frame/reg-fx
 :wake-lock/request
 (fn [_]
   (swap! wake-lock assoc :wanted? true)
   (when-let [api (some-> js/navigator .-wakeLock)]
     (when-not (or (:sentinel @wake-lock) (:pending? @wake-lock))
       (swap! wake-lock assoc :pending? true)
       (-> (.request api "screen")
           (.then (fn [sentinel]
                    (.addEventListener
                     sentinel "release"
                     #(swap! wake-lock (fn [s]
                                         (cond-> s
                                           (identical? sentinel (:sentinel s)) (assoc :sentinel nil)))))
                    (swap! wake-lock assoc :sentinel sentinel)
                    (when-not (:wanted? @wake-lock)
                      (release-sentinel!))))
           ;; Refused (e.g. low battery, page hidden): harmless, just no lock.
           (.catch (fn [_]))
           (.finally #(swap! wake-lock assoc :pending? false)))))))

(re-frame/reg-fx
 :wake-lock/release
 (fn [_]
   (swap! wake-lock assoc :wanted? false)
   (release-sentinel!)))
