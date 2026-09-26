(ns stepquence.subs
  (:require
   [re-frame.core :as re-frame]))

(re-frame/reg-sub
 ::content
 (fn [db]
   (:content db)))

(re-frame/reg-sub
 ::home-groups
 :<- [::content]
 (fn [{:keys [groups sequences]}]
   ;; Resolve ids defensively: dangling references are skipped, and
   ;; empty groups are hidden from the child.
   (into []
         (keep (fn [group]
                 (let [seqs (into [] (keep sequences) (:sequence-ids group))]
                   (when (seq seqs)
                     (assoc group :sequences seqs)))))
         groups)))
