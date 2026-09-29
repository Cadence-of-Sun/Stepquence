(ns stepquence.day
  "The per-day slice: what \"today\" is, and resetting when it changes.")

(defn- pad2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn today
  "Local date \"YYYY-MM-DD\" at epoch-ms `now`, where the day starts at
  `boundary-hour` rather than midnight (so 1am still counts as yesterday
  with the default boundary of 3)."
  [now boundary-hour]
  (let [d (js/Date. (- now (* boundary-hour 60 60 1000)))]
    (str (.getFullYear d) "-" (pad2 (inc (.getMonth d))) "-" (pad2 (.getDate d)))))

(def weekdays
  "In Date.getDay order."
  [:sun :mon :tue :wed :thu :fri :sat])

(defn weekday
  "Keyword weekday of a \"YYYY-MM-DD\" date."
  [date]
  (let [[y m d] (map js/parseInt (.split date "-"))]
    (weekdays (.getDay (js/Date. y (dec m) d)))))

(defn fresh [date]
  {:date date :done #{} :resume {}})

(defn roll-over
  "Reset the per-day slice if its date isn't `date`."
  [day date]
  (if (= date (:date day))
    day
    (fresh date)))
