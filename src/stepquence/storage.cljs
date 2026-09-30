(ns stepquence.storage
  "Persistence of the long-lived app-db slices (schema/persisted-keys) to
  IndexedDB, as one EDN string under one key.

  Image blobs will live in their own IndexedDB store; app-db only ever
  holds ids."
  (:require
   ["idb-keyval" :as idb]
   [cljs.reader :as reader]
   [malli.core :as m]
   [malli.error :as me]
   [malli.util :as mu]
   [re-frame.core :as re-frame]
   [stepquence.schema :as schema]))

(def storage-key "stepquence.app-db")

(def ^:private legacy-storage-key
  "Where data lived under the project's earlier working name. Read only
  when the current key is empty; the next save writes the current key."
  "stepwise.app-db")

(def ^:private emergency-key
  "localStorage key for a save that was still pending when the page went
  away. IndexedDB writes are asynchronous and a closing page can abandon
  them; localStorage is synchronous. Removed once IndexedDB has the data;
  if present at startup, it's the newest data."
  "stepquence.app-db.pending")

(def save-delay-ms
  "Writes are debounced: a burst of events produces one write."
  500)

;; ---------------------------------------------------------------------------
;; Encoding (pure)

(def Persisted
  (mu/select-keys schema/AppDb schema/persisted-keys))

(def ^:private persisted-explainer (m/explainer Persisted))

(defn persisted-slice [db]
  (select-keys db schema/persisted-keys))

(defn encode [db]
  (pr-str (persisted-slice db)))

(defn migrate
  "Bring stored data from an older :schema-version up to the current one.
  Nothing to migrate yet."
  [data]
  data)

(defn decode
  "Parse a stored string. Returns {:data …} when it is usable, else
  {:error …} (unreadable EDN or a shape that doesn't match the schema)."
  [s]
  (let [data (try (reader/read-string s) (catch :default e e))]
    (if (instance? js/Error data)
      {:error (str "unreadable: " (.-message data))}
      (let [data (migrate data)]
        (if-let [problems (some-> (persisted-explainer data) me/humanize)]
          {:error (str "invalid: " (pr-str problems))}
          {:data data})))))

;; ---------------------------------------------------------------------------
;; Effects

(re-frame/reg-fx
 :storage/load
 ;; Dispatches (conj on-loaded {:data …}), {:empty true} on first run, or
 ;; {:error …}. Unusable data is copied to a backup key before anything
 ;; can overwrite it.
 (fn [{:keys [on-loaded]}]
   (-> (let [emergency (try (.getItem js/localStorage emergency-key) (catch :default _ nil))]
         ;; A save cut short by closing the page is newer than IndexedDB.
         (if emergency
           (js/Promise.resolve emergency)
           (idb/get storage-key)))
       (.then (fn [s] (if (nil? s) (idb/get legacy-storage-key) s)))
       (.then (fn [s]
                (if (nil? s)
                  (re-frame/dispatch (conj on-loaded {:empty true}))
                  (let [result (decode s)]
                    (if (:error result)
                      (-> (idb/set (str storage-key ".unusable-" (js/Date.now)) s)
                          (.finally #(re-frame/dispatch (conj on-loaded result))))
                      (re-frame/dispatch (conj on-loaded result)))))))
       (.catch (fn [e]
                 ;; IndexedDB unavailable (e.g. some private modes): run
                 ;; without persistence rather than not at all.
                 (re-frame/dispatch (conj on-loaded {:error (str "unavailable: " e)
                                                     :unavailable true})))))))

(defonce ^:private pending (atom {:timer nil :db nil}))

(defn- write! [db]
  (-> (idb/set storage-key (encode db))
      (.then #(try (.removeItem js/localStorage emergency-key) (catch :default _ nil)))
      (.catch #(js/console.error "Saving failed" %))))

(defn- flush!
  "Write the pending save now. `leaving?`: the page is going away, so also
  keep a synchronous copy in case the IndexedDB write is cut short."
  ([] (flush! false))
  ([leaving?]
   (let [{:keys [timer db]} @pending]
     (when timer
       (js/clearTimeout timer)
       (reset! pending {:timer nil :db nil})
       (when leaving?
         (try (.setItem js/localStorage emergency-key (encode db)) (catch :default _ nil)))
       (write! db)))))

(re-frame/reg-fx
 :storage/save
 (fn [db]
   (when-let [timer (:timer @pending)]
     (js/clearTimeout timer))
   (reset! pending {:db db :timer (js/setTimeout flush! save-delay-ms)})))

(re-frame/reg-fx
 :storage/flush
 ;; Write any pending save now (the app is being hidden or closed).
 (fn [_]
   (flush! true)))

(re-frame/reg-fx
 :storage/save-copy
 ;; Keep a copy of the current persisted data under its own key (e.g. just
 ;; before an import replaces everything).
 (fn [{:keys [key db]}]
   (-> (idb/set key (encode db))
       (.catch #(js/console.error "Saving a copy failed" %)))))

(re-frame/reg-fx
 :storage/request-persistence
 ;; Ask the browser not to evict our data under storage pressure.
 (fn [_]
   (some-> js/navigator .-storage (.persist) (.catch (fn [_])))))
