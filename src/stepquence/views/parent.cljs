(ns stepquence.views.parent
  "Parent mode: the hidden entry gesture, the PIN pad, and the banner shown
  while unlocked."
  (:require
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [stepquence.dismiss :as dismiss]
   [stepquence.install :as install]
   [stepquence.events :as events]
   [stepquence.subs :as subs]
   [stepquence.views.icons :as icons]))

(def long-press-ms 2000)

(defn long-press-heading
  "A heading that opens parent mode when held for 2 seconds. No visual
  feedback: to a child it is just a heading, and a short press does
  nothing. Lifting, moving off, or scrolling (pointercancel) cancels."
  [tag text]
  (r/with-let [timer   (atom nil)
               cancel! #(when-let [t @timer]
                          (js/clearTimeout t)
                          (reset! timer nil))]
    [tag {:class             "long-press"
          :on-pointer-down   (fn [_]
                               (cancel!)
                               (reset! timer (js/setTimeout
                                              (fn []
                                                (reset! timer nil)
                                                (re-frame/dispatch [::events/parent-gesture]))
                                              long-press-ms)))
          :on-pointer-up     cancel!
          :on-pointer-leave  cancel!
          :on-pointer-cancel cancel!
          ;; No long-press context menu / selection callout.
          :on-context-menu   #(.preventDefault %)}
     text]
    (finally (cancel!))))

;; ---------------------------------------------------------------------------
;; PIN pad

(defn- pin-pad-panel [{:keys [mode digits message]}]
  (r/with-let [on-key (fn [e]
                        (let [k (.-key e)]
                          (cond
                            (re-matches #"\d" k) (re-frame/dispatch [::events/pin-digit k])
                            (= k "Backspace")    (re-frame/dispatch [::events/pin-backspace])
                            (= k "Enter")        (re-frame/dispatch [::events/pin-submit])
                            (= k "Escape")       (re-frame/dispatch [::events/pin-cancel]))))
               _      (.addEventListener js/document "keydown" on-key)]
    [:div.pin-overlay {:role "dialog" :aria-modal true :aria-label "Parent mode"}
     [:div.pin-panel
      [:h2.pin-title "Parent mode"]
      [:p.pin-prompt
       (case mode
         :unlock  "Enter your PIN"
         :set     (str "Choose a PIN (" events/pin-min " to " events/pin-max " digits)")
         :confirm "Enter it again")]
      [:div.pin-dots {:role "status" :aria-label (str (count digits) " digits entered")}
       (for [i (range (count digits))]
         ^{:key i} [:i])]
      [:p.pin-message (or message " ")]
      [:div.pin-keys
       (for [d "123456789"]
         ^{:key d}
         [:button.pin-key {:type     "button"
                           :on-click #(re-frame/dispatch [::events/pin-digit (str d)])}
          d])
       [:button.pin-key.is-quiet {:type     "button"
                                  :on-click #(re-frame/dispatch [::events/pin-cancel])}
        "Cancel"]
       [:button.pin-key {:type     "button"
                         :on-click #(re-frame/dispatch [::events/pin-digit "0"])}
        "0"]
       [:button.pin-key.is-quiet {:type       "button"
                                  :aria-label "Delete"
                                  :on-click   #(re-frame/dispatch [::events/pin-backspace])}
        [icons/backspace]]]
      [:button.primary.pin-ok
       {:type     "button"
        :disabled (< (count digits) events/pin-min)
        :on-click #(re-frame/dispatch [::events/pin-submit])}
       [:span.primary-label (case mode :unlock "Unlock" :set "Next" :confirm "Save")]]]]
    (finally (.removeEventListener js/document "keydown" on-key))))

(defn pin-pad []
  (when-let [entry @(re-frame/subscribe [::subs/pin-entry])]
    [pin-pad-panel entry]))

;; ---------------------------------------------------------------------------
;; Banner

(defn- theme-toggle []
  ;; Shows which theme is active (the system's until pressed); pressing
  ;; switches to the other and remembers it.
  (let [showing @(re-frame/subscribe [::subs/theme-showing])
        other   (if (= :dark showing) "light" "dark")]
    [:button.theme-toggle.toggle
     {:type       "button"
      :aria-label (str "Switch to " other " theme")
      :on-click   #(re-frame/dispatch [::events/toggle-theme])}
     [:span.toggle-option {:class (when (= :light showing) "is-active")} [icons/sun]]
     [:span.toggle-slash "/"]
     [:span.toggle-option {:class (when (= :dark showing) "is-active")} [icons/moon]]]))

(def ^:private day-ms (* 24 60 60 1000))

(defn- format-date [ms]
  (.toLocaleDateString (js/Date. ms) js/undefined #js {:day "numeric" :month "short" :year "numeric"}))

(defn- backup-status
  "The gentle nudge: how long since the last backup."
  [last-backup-at]
  (if-not last-backup-at
    {:text "Not backed up yet" :stale? true}
    (let [days (js/Math.floor (/ (- (js/Date.now) last-backup-at) day-ms))]
      {:text   (case days
                 0 "Backed up today"
                 1 "Backed up yesterday"
                 (str "Last backed up " days " days ago"))
       :stale? (>= days 14)})))

(defn- merge-body
  "Shared sequences: new ones are added; for each already here, choose
  Update mine (replace in place, keeping its group and schedule here) or
  Keep both (a copy)."
  [{:keys [rows choices data]} cancel]
  [:<>
   [:h2#import-title.dialog-title "Import sequences?"]
   [:p (str "From " (format-date (:exported-at data)) ".")]
   [:ul.merge-list
    (for [{:keys [id title exists? local-title]} rows
          :let [choice (get choices id :update)]]
      ^{:key (str id)}
      [:li.merge-row
       [:span.merge-title title]
       (if-not exists?
         [:span.merge-status "New"]
         [:<>
          [:span.merge-status
           (if (= title local-title)
             "Already here"
             (str "Already here as \"" local-title "\""))]
          [:span.chips {:role "group" :aria-label (str "What to do with " title)}
           [:button.chip {:type         "button"
                          :aria-pressed (= :update choice)
                          :on-click     #(re-frame/dispatch [::events/merge-choice id :update])}
            "Update mine"]
           [:button.chip {:type         "button"
                          :aria-pressed (= :copy choice)
                          :on-click     #(re-frame/dispatch [::events/merge-choice id :copy])}
            "Keep both"]]])])]
   [:div.dialog-actions
    [:button.dialog-button {:type "button" :on-click cancel} "Cancel"]
    [:button.dialog-button.is-primary
     {:type     "button"
      :on-click #(re-frame/dispatch [::events/merge-confirm])}
     "Import"]]])

(defn import-dialog
  "Confirm a full restore or a merge of shared sequences, or explain why
  the file can't be used."
  []
  (when-let [{:keys [kind error summary busy?] :as entry} @(re-frame/subscribe [::subs/import])]
    (let [current (count (:sequences @(re-frame/subscribe [::subs/content])))
          cancel  #(re-frame/dispatch [::events/import-cancel])]
      [:div.dialog-overlay
       [:div.dialog {:role "alertdialog" :aria-modal true :aria-labelledby "import-title"}
        (cond
          busy?
          [:<>
           [:h2#import-title.dialog-title "Importing…"]
           [:p "Storing the pictures."]]

          error
          [:<>
           [:h2#import-title.dialog-title "Can't use that file"]
           [:p error]
           [:div.dialog-actions
            [:button.dialog-button.is-primary {:type "button" :on-click cancel} "OK"]]]

          (= :sequences kind)
          [merge-body entry cancel]

          :else
          (let [{:keys [sequences groups exported-at]} summary]
            [:<>
             [:h2#import-title.dialog-title "Restore this backup?"]
             [:p (str "It has " sequences (if (= 1 sequences) " sequence" " sequences")
                      " in " groups (if (= 1 groups) " group" " groups")
                      ", from " (format-date exported-at) ".")]
             [:p (str "Everything on this device will be replaced: its " current
                      (if (= 1 current) " sequence" " sequences")
                      ", groups and settings. A copy of the current data is kept on this device.")]
             [:div.dialog-actions
              [:button.dialog-button {:type "button" :on-click cancel} "Cancel"]
              [:button.dialog-button.is-danger
               {:type     "button"
                :on-click #(re-frame/dispatch [::events/import-confirm])}
               "Replace everything"]]]))]])))

(defn export-status
  "While an export is being prepared (pictures are read from storage), a
  small note; if the tap that started it no longer counts for sharing, a
  prompt for one more."
  []
  (when-let [{:keys [status]} @(re-frame/subscribe [::subs/export])]
    (case status
      :preparing [:div.undo-toast {:role "status"} [:span "Preparing the file…"]]
      :ready     [:div.dialog-overlay
                  [:div.dialog {:role "alertdialog" :aria-modal true :aria-labelledby "export-title"}
                   [:h2#export-title.dialog-title "Your file is ready"]
                   [:div.dialog-actions
                    [:button.dialog-button {:type "button" :on-click #(re-frame/dispatch [::events/export-cancel])} "Cancel"]
                    [:button.dialog-button.is-primary
                     {:type "button"
                      ;; Synchronous: sharing needs this click.
                      :on-click #(re-frame/dispatch-sync [::events/share-prepared])}
                     "Share"]]]]
      :error     [:div.dialog-overlay
                  [:div.dialog {:role "alertdialog" :aria-modal true :aria-labelledby "export-title"}
                   [:h2#export-title.dialog-title "Couldn't prepare the file"]
                   [:p "Something went wrong reading the pictures. Nothing was changed; please try again."]
                   [:div.dialog-actions
                    [:button.dialog-button.is-primary {:type "button" :on-click #(re-frame/dispatch [::events/export-done])} "OK"]]]])))

(defn- import-button
  "A button that opens the file picker (the input itself stays hidden).
  `on-chosen` runs once a file is picked (the popover closes: the dialog
  that follows takes over)."
  [on-chosen]
  (r/with-let [input (atom nil)]
    [:<>
     [:input.visually-hidden
      {:type        "file"
       :accept      ".edn,.txt,application/edn,text/plain"
       :aria-hidden true
       :tab-index   -1
       :ref         #(reset! input %)
       :on-change   (fn [e]
                      (let [el (.-target e)]
                        (when-let [file (aget (.-files el) 0)]
                          (on-chosen)
                          (-> (.text file)
                              (.then #(re-frame/dispatch [::events/import-read %]))))
                        ;; So choosing the same file again still fires.
                        (set! (.-value el) "")))}]
     [:button.settings-button
      {:type     "button"
       :on-click #(some-> @input .click)}
      "Import…"]]))

(defn- settings-panel
  "The gear in the parent banner opens Settings: theme, backup, and the
  build's version (from a <meta name=\"app-version\"> the publish script
  stamps into the published HTML; absent in development, so not shown).
  Closes on Escape (focus returns to the gear) or a press outside it. The
  same popover pattern as brainboard's settings-panel."
  []
  (let [open?    (r/atom false)
        root     (atom nil)
        unlisten (atom nil)]
    (r/create-class
     {:display-name "settings-panel"

      :component-did-mount
      (fn [_]
        (reset! unlisten
                (dismiss/listen
                 #(deref root)
                 #(deref open?)
                 (fn [reason]
                   (reset! open? false)
                   (when (= reason :escape)
                     (some-> @root (.querySelector ".settings-toggle") .focus))))))

      :component-will-unmount
      (fn [_] (when-let [stop @unlisten] (stop)))

      :reagent-render
      (fn []
        (let [{:keys [text stale?]} (backup-status @(re-frame/subscribe [::subs/last-backup-at]))
              version               @(re-frame/subscribe [::subs/app-version])]
          [:div.settings-panel {:ref #(reset! root %)}
           [:button.settings-toggle
            {:type          "button"
             :title         "Settings"
             :aria-label    "Settings"
             :aria-expanded (boolean @open?)
             :aria-controls "settings-body"
             :on-click      #(swap! open? not)}
            [icons/gear]]
           (when @open?
             [:div#settings-body.settings-body {:role "group" :aria-label "Settings"}
              [:div.settings-field
               [:span "Theme"]
               [theme-toggle]]
              [:div.settings-section
               [:span.settings-label "Pictures"]
               [:button.settings-button
                {:type     "button"
                 :on-click (fn []
                             (reset! open? false)
                             (re-frame/dispatch [::events/open-library]))}
                (let [n (count @(re-frame/subscribe [::subs/image-library]))]
                  (str "Manage pictures" (when (pos? n) (str " (" n ")"))))]]
              [:div.settings-section
               [:span.settings-label "Backup"]
               [:p.backup-status {:class (when stale? "is-stale")} text]
               [:div.settings-actions
                [:button.settings-button
                 {:type     "button"
                  ;; Synchronous, so sharing happens within the click (a
                  ;; user gesture).
                  :on-click #(re-frame/dispatch-sync [::events/export-backup])}
                 "Export backup"]
                [import-button #(reset! open? false)]]]
              ;; Only when installing is possible here: Chromium's own prompt,
              ;; or a hint on iOS Safari (no prompt exists there).
              (cond
                @install/install-event
                [:button.settings-button.settings-install
                 {:type "button" :on-click install/install!}
                 "Install as an app"]

                (and (install/ios-safari?) (not (install/standalone?)))
                [:p.settings-hint "To install: Share, then Add to Home Screen."])
              (when version
                [:p.settings-version version])])]))})))

(defn banner
  "Shown on every screen while unlocked, so the mode is never ambiguous."
  []
  (when @(re-frame/subscribe [::subs/parent?])
    [:div.parent-banner
     [:span.parent-banner-label "Parent mode"]
     [settings-panel]
     [:button.parent-lock
      {:type     "button"
       :on-click #(re-frame/dispatch [::events/lock-parent])}
      "Lock"]]))

;; ---------------------------------------------------------------------------
;; Undo, for deletes on any screen

(defn undo-toast
  "The most recent delete that can still be undone; with more than one on
  offer, a count."
  []
  (when-let [stack (seq @(re-frame/subscribe [::subs/undo]))]
    [:div.undo-toast {:role "status"}
     [:span (:message (last stack))
      (when (> (count stack) 1)
        [:span.undo-count (str " · " (count stack))])]
     [:button.undo-button
      {:type     "button"
       :on-click #(re-frame/dispatch [::events/undo])}
      "Undo"]]))
