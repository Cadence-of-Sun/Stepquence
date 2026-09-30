(ns stepquence.transfer
  "Delivering export files (backups, shared sequences): the system share
  sheet where files can be shared (iOS and macOS Safari, Android Chrome,
  Chromium on Windows/ChromeOS), a download otherwise.

  Pictures are read from storage asynchronously before the file can be
  written, but browsers only open the share sheet during (or just after) a
  tap. So: prepare the file, then share straight away if the tap still
  counts (navigator.userActivation), otherwise ask for one more tap (the
  `on-prompt` event shows a \"ready: Share\" prompt, whose button calls
  :file/export within its own click)."
  (:require
   [re-frame.core :as re-frame]
   [stepquence.backup :as backup]
   [stepquence.images :as images]
   [stepquence.share :as share]))

(defn- download! [text filename]
  (let [url (js/URL.createObjectURL (js/Blob. #js [text] #js {:type "application/edn"}))
        a   (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.appendChild (.-body js/document) a)
    (.click a)
    (.remove a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 10000)))

(defn- file-of [{:keys [text filename]}]
  (js/File. #js [text] filename #js {:type "text/plain"}))

(defn can-share? [payload]
  (and (.-canShare js/navigator)
       (.canShare js/navigator #js {:files #js [(file-of payload)]})))

(defn- still-a-tap?
  "Whether the tap that started this is still usable for opening the share
  sheet. Where the browser can't say, assume not (the prompt is safe)."
  []
  (boolean (some-> js/navigator .-userActivation .-isActive)))

(defn share-or-download!
  "Share (must be within a tap) or download; dispatches `on-done` once the
  file has gone out (not if the parent closes the share sheet)."
  [{:keys [text filename title on-done] :as payload}]
  (if (can-share? payload)
    (-> (.share js/navigator #js {:files #js [(file-of payload)] :title title})
        (.then #(some-> on-done re-frame/dispatch))
        (.catch (fn [e]
                  ;; AbortError: the parent closed the share sheet.
                  (when-not (= "AbortError" (.-name e))
                    (download! text filename)
                    (some-> on-done re-frame/dispatch)))))
    (do (download! text filename)
        (some-> on-done re-frame/dispatch))))

(defonce ^:private pending
  ;; A prepared file waiting for the prompt's tap. Kept here, not in
  ;; app-db: it can be several MB of text (embedded pictures).
  (atom nil))

(defn- deliver! [payload on-prompt]
  (if (and (can-share? payload) (not (still-a-tap?)))
    (do (reset! pending payload)
        (re-frame/dispatch on-prompt))
    (share-or-download! payload)))

(re-frame/reg-fx
 :transfer/share-pending
 ;; From the prompt's Share button (within its click).
 (fn [_]
   (when-let [payload @pending]
     (reset! pending nil)
     (share-or-download! payload))))

(re-frame/reg-fx
 :transfer/drop-pending
 (fn [_]
   (reset! pending nil)))

(re-frame/reg-fx
 :transfer/export
 ;; {:kind :backup, :db, :now} or {:kind :sequences, :db, :ids, :now},
 ;; plus :on-done (after it's gone out), :on-prompt (needs another tap),
 ;; and :on-error.
 (fn [{:keys [kind db ids now on-done on-prompt on-error]}]
   (let [content (:content db)
         img-ids (case kind
                   :backup    (backup/image-ids db)
                   :sequences (share/used-images content ids))]
     (-> (images/export-data img-ids)
         (.then (fn [image-data]
                  (deliver! (case kind
                              :backup    {:text     (backup/encode db now image-data)
                                          :filename (backup/filename now)
                                          :title    "Stepquence backup"
                                          :on-done  on-done}
                              :sequences {:text     (share/encode content ids now image-data)
                                          :filename (share/filename content ids now)
                                          :title    "Stepquence sequences"
                                          :on-done  on-done})
                            on-prompt)))
         (.catch (fn [e]
                   (js/console.error "Export failed" e)
                   (some-> on-error re-frame/dispatch)))))))
