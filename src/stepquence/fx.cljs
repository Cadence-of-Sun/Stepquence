(ns stepquence.fx
  "Effect and coeffect handlers for browser APIs."
  (:require
   [re-frame.core :as re-frame]
   [reagent.core :as r]))

(re-frame/reg-cofx
 :now
 (fn [cofx]
   (assoc cofx :now (js/Date.now))))

(re-frame/reg-cofx
 :app-version
 ;; The published build's version, from a <meta name="app-version"> the
 ;; publish script stamps into the HTML. nil in development (no tag), and
 ;; then not shown, rather than a placeholder.
 (fn [cofx]
   (assoc cofx :app-version
          (some-> (.querySelector js/document "meta[name=\"app-version\"]")
                  (.getAttribute "content")))))

(re-frame/reg-cofx
 :new-id
 (fn [cofx]
   (assoc cofx :new-id (random-uuid))))

(re-frame/reg-fx
 :focus
 ;; Focus the element matching {:selector …} once the pending render is
 ;; done; with :select? true, also select its text (a freshly added step's
 ;; placeholder title, ready to be typed over).
 (fn [{:keys [selector select?]}]
   (r/after-render
    #(when-let [el (.querySelector js/document selector)]
       (.focus el)
       (when select? (.select el))))))

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
;; Backup export: the system share sheet where files can be shared (iOS and
;; macOS Safari, Android Chrome, Chromium on Windows/ChromeOS), a download
;; otherwise. Must run inside the click (dispatch-sync), because sharing
;; needs a user gesture.

(defn- download! [text filename]
  (let [url (js/URL.createObjectURL (js/Blob. #js [text] #js {:type "application/edn"}))
        a   (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.appendChild (.-body js/document) a)
    (.click a)
    (.remove a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 10000)))

(re-frame/reg-fx
 :backup/export
 ;; Dispatches `on-done` once the file has been shared or downloaded (not
 ;; if the parent cancels the share sheet).
 (fn [{:keys [text filename on-done]}]
   (let [file   (js/File. #js [text] filename #js {:type "text/plain"})
         files  #js {:files #js [file]}
         share? (and (.-canShare js/navigator) (.canShare js/navigator files))]
     (if share?
       (-> (.share js/navigator #js {:files #js [file] :title "Stepquence backup"})
           (.then #(re-frame/dispatch on-done))
           (.catch (fn [e]
                     ;; AbortError: the parent closed the share sheet.
                     (when-not (= "AbortError" (.-name e))
                       (download! text filename)
                       (re-frame/dispatch on-done)))))
       (do (download! text filename)
           (re-frame/dispatch on-done))))))

;; ---------------------------------------------------------------------------
;; Theme

(def ^:private theme-storage-key "stepquence.theme")

(def ^:private legacy-theme-storage-key
  "The earlier working name's key; read only as a fallback."
  "stepwise.theme")

(defn system-dark-query []
  (js/matchMedia "(prefers-color-scheme: dark)"))

(defn system-theme []
  (if (.-matches (system-dark-query)) :dark :light))

(re-frame/reg-cofx
 :theme/initial
 ;; :saved is the user's choice (nil until they press the toggle).
 (fn [cofx]
   (let [stored (try (or (.getItem js/localStorage theme-storage-key)
                               (.getItem js/localStorage legacy-theme-storage-key))
                           (catch :default _ nil))]
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
