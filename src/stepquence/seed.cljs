(ns stepquence.seed
  "Sample content for development until parent-mode editing exists.
  Ids are fixed so they stay stable across reloads.")

(defn- id [n]
  (uuid (str "00000000-0000-4000-8000-" (.padStart (str n) 12 "0"))))

(def ungrouped-id
  "Well-known id of the default group. Deleting a group moves its sequences here."
  (id 0))

(def everyday #{:mon :tue :wed :thu :fri :sat :sun})
(def weekdays #{:mon :tue :wed :thu :fri})

(defn- task
  ([n title] (task n title nil))
  ([n title opts] (merge {:id (id n) :kind :task :title title} opts)))

;; items 100s, collections 200s, groups 300s, sequences 400s, steps 1000s

(def items
  (into {}
        (map (fn [[n title]] [(id n) {:id (id n) :title title}]))
        [[101 "Toothbrush"]
         [102 "Toothpaste"]
         [103 "Cup"]
         [111 "Apple"]
         [112 "Crackers"]
         [113 "Yogurt"]]))

(def collections
  {(id 201) {:id (id 201) :name "Snacks" :item-ids [(id 111) (id 112) (id 113)]}})

(def sequences
  {(id 401)
   {:id (id 401)
    :title "Get dressed"
    :days weekdays
    :requirements []
    :steps [(task 1001 "Underwear")
            (task 1002 "Socks")
            (task 1003 "Trousers")
            (task 1004 "Shirt" {:description "Label goes at the back."})
            (task 1005 "Shoes")]}

   (id 402)
   {:id (id 402)
    :title "Brush teeth"
    :days everyday
    :requirements [(id 101) (id 102) (id 103)]
    :steps [(task 1011 "Wet the toothbrush")
            (task 1012 "Put on toothpaste" {:description "A pea-sized blob."})
            (task 1013 "Brush the top teeth" {:timer-seconds 60})
            (task 1014 "Brush the bottom teeth" {:timer-seconds 60})
            (task 1015 "Spit and rinse")
            (task 1016 "Put the toothbrush away")]}

   (id 403)
   {:id (id 403)
    :title "Snack time"
    :days everyday
    :requirements []
    :steps [(task 1021 "Wash your hands")
            {:id (id 1022) :kind :choice :title "Choose a snack" :collection-id (id 201)}
            (task 1023 "Sit at the table")
            (task 1024 "Put your plate in the sink")]}

   ;; Exactly max-steps long, to exercise the progress bar at full width.
   (id 404)
   {:id (id 404)
    :title "Bedtime"
    :days everyday
    :requirements []
    :steps [(task 1031 "Tidy toys")
            (task 1032 "Bath")
            (task 1033 "Dry off")
            (task 1034 "Pyjamas on")
            (task 1035 "Brush teeth")
            (task 1036 "Toilet")
            (task 1037 "Wash hands")
            (task 1038 "Choose a book")
            (task 1039 "Get into bed")
            (task 1040 "Story time")
            (task 1041 "Lights down")
            (task 1042 "Goodnight")]}})

(def groups
  [{:id (id 301) :name "Morning" :sequence-ids [(id 401) (id 402)]}
   {:id (id 302) :name "Afternoon" :sequence-ids [(id 403)]}
   {:id (id 303) :name "Evening" :sequence-ids [(id 404)]}
   {:id ungrouped-id :name "Ungrouped" :sequence-ids []}])

(def content
  {:sequences sequences
   :groups groups
   :images {}
   :items items
   :collections collections})
