(ns stepquence.backup
  "Backup files: the whole of a device's content and settings, for export
  and full-restore import. EDN (keeps uuids, sets and keywords as they
  are); images will be embedded here once they exist.

  Excluded: today's progress (per-day) and the theme (a device display
  preference). Included: the PIN, so a restored device keeps its lock, and
  the picture files (:image-data, see stepquence.images/export-data)."
  (:require
   [cljs.reader :as reader]
   [malli.core :as m]
   [malli.error :as me]
   [stepquence.day :as day]
   [stepquence.edit :as edit]
   [stepquence.schema :as schema]))

(def format-version 1)

(defn backup-data
  ([db now] (backup-data db now {}))
  ([db now image-data]
   {:stepquence/backup format-version
    :schema-version    schema/schema-version
    :exported-at       now
    :content           (:content db)
    :settings          (dissoc (:settings db) :theme)
    :image-data        image-data}))

(defn encode
  ([db now] (encode db now {}))
  ([db now image-data]
   (pr-str (backup-data db now image-data))))

(defn image-ids
  "Every picture a backup should carry."
  [db]
  (keys (get-in db [:content :images])))

(defn filename [now]
  (str "stepquence-backup-" (day/today now 0) ".edn"))

(defn- format-of
  "The backup format number; files from the earlier working name
  (:stepwise/backup) still import."
  [data]
  (when (map? data)
    (or (:stepquence/backup data) (:stepwise/backup data))))

(def ^:private content-explainer (m/explainer schema/Content))
(def ^:private settings-explainer (m/explainer schema/Settings))
(def ^:private image-data-explainer (m/explainer [:maybe schema/ImageData]))

(defn migrate
  "Bring an older backup up to the current schema. Nothing to migrate yet."
  [data]
  data)

(defn decode
  "Parse a backup file's text: {:data …} when usable, else {:error msg}
  with a message fit to show a parent."
  [text]
  (let [data (try (reader/read-string text) (catch :default _ ::unreadable))]
    (cond
      (not (number? (format-of data)))
      {:error "That file isn't a Stepquence backup."}

      (> (format-of data) format-version)
      {:error "That backup comes from a newer version of Stepquence."}

      :else
      (let [data     (migrate data)
            problems (or (some-> (content-explainer (:content data)) me/humanize)
                         (some-> (settings-explainer (assoc (:settings data) :theme nil)) me/humanize)
                         (some-> (image-data-explainer (:image-data data)) me/humanize))]
        (if problems
          (do (js/console.error "Backup failed validation" (pr-str problems))
              {:error "That backup is damaged or incomplete, so nothing was changed."})
          {:data data})))))

(defn summary [{:keys [content exported-at]}]
  {:sequences   (count (:sequences content))
   :groups      (count (:groups content))
   :exported-at exported-at})

(defn restore
  "Replace content and settings with the backup's. Keeps this device's
  theme; today's progress starts fresh; the backup's export time counts
  as the last backup."
  [db {:keys [content settings exported-at]} now]
  (-> db
      (assoc :content (edit/ensure-ungrouped content))
      (assoc :settings (assoc settings :theme (get-in db [:settings :theme])))
      (assoc-in [:meta :last-backup-at] exported-at)
      (assoc :day (day/fresh (day/today now (:day-boundary-hour settings))))
      (assoc :run nil :run-stack [])))
