(ns stepquence.images
  "Pictures: files in their own IndexedDB store, never in app-db (which
  keeps only the library records, {:id :name :source :width :height}).

  Each picture is stored twice, both resized on import: :full (about 1024px
  on its long edge, for the step view) and :thumb (256px, for the editor,
  overview and pickers). Phone photos of several MB become ~100-200 KB.

  Shown through object URLs, created on first use and kept in a plain
  Reagent atom (ephemeral, not app-db: they are handles, not data), and
  revoked when a picture is deleted."
  (:require
   ["idb-keyval" :as idb]
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [stepquence.schema :as schema]))

(def full-edge 1024)
(def thumb-edge 256)

(defonce ^:private store
  (delay (idb/createStore "stepquence-images" "blobs")))

(defn- blob-key [id kind]
  (str id "/" (name kind)))

;; ---------------------------------------------------------------------------
;; Import: decode (camera orientation applied), resize, encode

(defn- encode
  "WebP where the browser can encode it, else JPEG. (Safari's canvas
  quietly returns PNG for WebP it can't encode, so check what came back.)"
  [canvas]
  (js/Promise.
   (fn [resolve reject]
     (.toBlob canvas
              (fn [webp]
                (if (and webp (= "image/webp" (.-type webp)))
                  (resolve webp)
                  (.toBlob canvas
                           #(if % (resolve %) (reject (js/Error. "Couldn't encode the picture")))
                           "image/jpeg" 0.82)))
              "image/webp" 0.8))))

(defn- scaled [bitmap edge]
  (let [w     (.-width bitmap)
        h     (.-height bitmap)
        scale (min 1 (/ edge (max w h)))
        cw    (max 1 (js/Math.round (* w scale)))
        ch    (max 1 (js/Math.round (* h scale)))
        c     (.createElement js/document "canvas")
        ctx   (.getContext c "2d")]
    (set! (.-width c) cw)
    (set! (.-height c) ch)
    (set! (.-imageSmoothingQuality ctx) "high")
    (.drawImage ctx bitmap 0 0 cw ch)
    c))

(defn- picture-name
  "The file's name without its extension, clipped to the name cap."
  [file]
  (let [n (str/replace (or (.-name file) "") #"\.[^.]*$" "")
        n (if (str/blank? n) "Picture" n)]
    (subs n 0 (min (count n) schema/max-name))))

(defn import!
  "Decode, resize and store a picture file under `id`. Resolves to its
  library record."
  [file id]
  (-> (js/createImageBitmap file #js {:imageOrientation "from-image"})
      (.then (fn [bitmap]
               (let [full-c  (scaled bitmap full-edge)
                     thumb-c (scaled bitmap thumb-edge)]
                 (.close bitmap)
                 (-> (js/Promise.all #js [(encode full-c) (encode thumb-c)])
                     (.then (fn [[full thumb]]
                              (-> (idb/setMany #js [#js [(blob-key id :full) full]
                                                    #js [(blob-key id :thumb) thumb]]
                                               @store)
                                  (.then (fn [_]
                                           {:id     id
                                            :name   (picture-name file)
                                            :source :local
                                            :width  (.-width full-c)
                                            :height (.-height full-c)})))))))))))

(re-frame/reg-fx
 :images/import
 ;; Dispatches (conj on-done record) or (conj on-error message).
 (fn [{:keys [file id on-done on-error]}]
   (-> (import! file id)
       (.then #(re-frame/dispatch (conj on-done %)))
       (.catch (fn [e]
                 (js/console.error "Picture import failed" e)
                 (re-frame/dispatch (conj on-error "That picture couldn't be added. Try a different photo.")))))))

;; ---------------------------------------------------------------------------
;; Display: object URLs, loaded on first use

(defonce ^:private urls
  ;; [id kind] -> object URL, or ::failed when the blob is missing/unreadable.
  (r/atom {}))

(defonce ^:private loading (atom #{}))

(defn- load! [id kind]
  (let [k [id kind]]
    (when-not (contains? @loading k)
      (swap! loading conj k)
      (-> (idb/get (blob-key id kind) @store)
          (.then (fn [blob]
                   (swap! urls assoc k (if blob (js/URL.createObjectURL blob) ::failed))))
          (.catch (fn [_] (swap! urls assoc k ::failed)))
          (.finally #(swap! loading disj k))))))

(defn url
  "The object URL for picture `id` (`kind` :full or :thumb): a string, nil
  while loading (it starts loading on first ask), or ::failed. Reading it
  in a component re-renders that component once the URL is ready."
  [id kind]
  (when id
    (let [u (get @urls [id kind])]
      (when-not u (load! id kind))
      u)))

(defn failed? [u]
  (= ::failed u))

(defn mark-failed!
  "An <img> couldn't show it after all (e.g. a damaged file)."
  [id kind]
  (swap! urls assoc [id kind] ::failed))

(re-frame/reg-fx
 :images/delete
 ;; Remove a picture's files and release its URLs.
 (fn [id]
   (doseq [kind [:full :thumb]]
     (let [u (get @urls [id kind])]
       (when (string? u) (js/URL.revokeObjectURL u)))
     (swap! urls dissoc [id kind]))
   (idb/delMany #js [(blob-key id :full) (blob-key id :thumb)] @store)))
