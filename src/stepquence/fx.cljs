(ns stepquence.fx
  "Effect and coeffect handlers for browser APIs."
  (:require
   [re-frame.core :as re-frame]
   [reagent.core :as r]))

(re-frame/reg-cofx
 :now
 (fn [cofx]
   (assoc cofx :now (js/Date.now))))

;; ---------------------------------------------------------------------------
;; View transitions

(defn- reduced-motion? []
  (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))

(defn- scroll-into-view! [selector]
  (when-let [el (and selector (.querySelector js/document selector))]
    (.scrollIntoView el #js {:block "center"})))

(re-frame/reg-fx
 :view-transition/dispatch
 ;; Handle `event` inside document.startViewTransition so the change
 ;; animates (elements sharing a view-transition-name move between their
 ;; old and new positions).
 ;;
 ;; Reagent renders asynchronously, so inside the callback the event is
 ;; handled with dispatch-sync and the DOM forced with r/flush; otherwise
 ;; the "after" snapshot would equal the "before" and nothing would move.
 ;; (This relies on React 17's synchronous legacy render.) The callback
 ;; always runs after the current event has finished, so dispatch-sync is
 ;; safe there.
 ;;
 ;; `scroll-into-view` (a selector) is scrolled to before the "after"
 ;; snapshot. Where the API is missing, or motion is reduced, the change is
 ;; a plain swap.
 (fn [{:keys [event scroll-into-view]}]
   (if (and (.-startViewTransition js/document) (not (reduced-motion?)))
     (.startViewTransition js/document
                           (fn []
                             (re-frame/dispatch-sync event)
                             (r/flush)
                             (scroll-into-view! scroll-into-view)))
     (do (re-frame/dispatch event)
         (r/after-render #(scroll-into-view! scroll-into-view))))))

;; ---------------------------------------------------------------------------
;; Parent-mode idle lock
;;
;; No polling. Two capture-phase listeners (pointerdown, keydown) stamp the
;; time of the last input into a plain atom, not app-db, so input causes no
;; re-render and no save. While parent mode is unlocked a single timer runs:
;; when it fires it checks how long input has been idle, and either
;; dispatches `on-idle` or sleeps again for the remaining time. At most one
;; wake-up per timeout period.

(defonce ^:private activity (atom {:last 0 :timer nil}))

(defn- note-activity! [_]
  (swap! activity assoc :last (js/Date.now)))

(defn install-activity-listeners!
  "Called once at startup."
  []
  (let [opts #js {:capture true :passive true}]
    (.addEventListener js/document "pointerdown" note-activity! opts)
    (.addEventListener js/document "keydown" note-activity! opts)))

(re-frame/reg-fx
 :parent/idle-lock
 ;; {:timeout-ms n :on-idle event} starts (or restarts) the watch; nil stops it.
 (fn [{:keys [timeout-ms on-idle]}]
   (some-> (:timer @activity) js/clearTimeout)
   (swap! activity assoc :timer nil)
   (when timeout-ms
     (note-activity! nil)
     (letfn [(check []
               (let [idle (- (js/Date.now) (:last @activity))]
                 (if (>= idle timeout-ms)
                   (do (swap! activity assoc :timer nil)
                       (re-frame/dispatch on-idle))
                   (swap! activity assoc :timer (js/setTimeout check (- timeout-ms idle))))))]
       (swap! activity assoc :timer (js/setTimeout check timeout-ms))))))

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
