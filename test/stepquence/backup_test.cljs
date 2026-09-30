(ns stepquence.backup-test
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [stepquence.backup :as backup]
   [stepquence.db :as db]
   [stepquence.schema :as schema]))

(def now (.getTime (js/Date. 2026 8 28 12 0)))

(def device
  (-> db/default-db
      (assoc-in [:settings :pin] "2468")
      (assoc-in [:settings :theme] :dark)
      (assoc-in [:day :done] #{(random-uuid)})))

(deftest round-trip
  (let [{:keys [data error]} (backup/decode (backup/encode device now))]
    (is (nil? error))
    (is (= (:content device) (:content data)))
    (is (= "2468" (-> data :settings :pin)) "the PIN is included")
    (is (not (contains? (:settings data) :theme)) "the theme is not")
    (is (= now (:exported-at data)))))

(deftest rejects-other-files
  (is (= "That file isn't a Stepquence backup." (:error (backup/decode "hello"))))
  (is (= "That file isn't a Stepquence backup." (:error (backup/decode "{:a 1}"))))
  (is (re-find #"newer version" (:error (backup/decode (pr-str {:stepquence/backup 99})))))
  (is (re-find #"damaged" (:error (backup/decode (pr-str {:stepquence/backup 1 :content {}}))))))

(deftest reads-backups-from-the-earlier-name
  (let [old (-> (backup/backup-data device now)
                (dissoc :stepquence/backup)
                (assoc :stepwise/backup 1))]
    (is (nil? (:error (backup/decode (pr-str old)))))))

(deftest restoring
  (let [{:keys [data]} (backup/decode (backup/encode device now))
        other          (-> db/default-db
                           (assoc-in [:settings :theme] :light)
                           (assoc-in [:content :sequences] {}))
        restored       (backup/restore other data now)]
    (is (= (:content device) (:content restored)))
    (is (= :light (-> restored :settings :theme)) "keeps this device's theme")
    (is (= "2468" (-> restored :settings :pin)))
    (is (= now (-> restored :meta :last-backup-at)))
    (is (empty? (-> restored :day :done)) "today's progress starts fresh")
    (is (nil? (schema/explain-app-db restored)))))

(deftest carries-pictures
  (let [img  (random-uuid)
        data {img {:full "data:image/webp;base64,AAAA" :thumb "data:image/webp;base64,BBBB"}}
        out  (backup/decode (backup/encode device now data))]
    (is (= data (-> out :data :image-data))))
  (testing "damaged picture data is refused"
    (is (re-find #"damaged"
                 (:error (backup/decode (backup/encode device now {(random-uuid) {:full "hello" :thumb "x"}})))))))
