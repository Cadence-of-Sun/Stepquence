(ns stepquence.schema
  "Shape of app-db, as malli schemas.

  These check shape only. Referential integrity (every :image-id,
  :collection-id, etc. points at something that exists) is a separate
  concern: views must render defensively regardless, since imports may
  carry partial data."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.util :as mu]))

(def max-steps
  "Hard cap on steps per sequence."
  12)

(def ungrouped-id
  "Well-known id of the default group, which always exists. Sequences of a
  deleted group move here."
  (uuid "00000000-0000-4000-8000-000000000000"))

(def schema-version
  "Bump when the persisted shape changes; import/boot migrate from older versions."
  1)

;; ---------------------------------------------------------------------------
;; Primitives

(def NonBlank [:re #"\S"])

;; Text length caps. They keep every layout predictable (nothing can
;; overflow the step card or the sequence list) and are enforced here so
;; imports are held to them too. The editor will use them as input limits.
(def max-step-title 60)
(def max-description 200)
(def max-name
  "Sequence titles, item titles, collection and group names."
  40)

(defn Text
  "Non-blank string of at most `max` characters."
  [max]
  [:and [:string {:min 1 :max max}] NonBlank])

(def Date
  "Local calendar date, \"YYYY-MM-DD\" (after applying the day boundary)."
  [:re #"^\d{4}-\d{2}-\d{2}$"])

(def Weekday [:enum :mon :tue :wed :thu :fri :sat :sun])

;; ---------------------------------------------------------------------------
;; Content (persistent)

(def Image
  [:map
   [:id :uuid]
   [:name {:optional true} :string]
   [:source [:enum :local :url]]
   [:href {:optional true} :string]
   [:width {:optional true} pos-int?]
   [:height {:optional true} pos-int?]])

(def Item
  "A *thing*: used by requirements and collections."
  [:map
   [:id :uuid]
   [:title (Text max-name)]
   [:image-id {:optional true} :uuid]])

(def Collection
  "Named, ordered list of item ids. Used by choice steps."
  [:map
   [:id :uuid]
   [:name (Text max-name)]
   [:item-ids [:vector :uuid]]])

(def StepCommon
  [:map
   [:id :uuid]
   [:title (Text max-step-title)]
   [:description {:optional true} [:string {:max max-description}]]])

(def TaskStep
  (mu/merge
   StepCommon
   [:map
    [:kind [:= :task]]
    [:image-id {:optional true} :uuid]
    ;; Frame defaults to :contain; :cover allows a per-step crop focus.
    [:image-fit {:optional true} [:enum :contain :cover]]
    [:image-position {:optional true} :string]
    [:timer-seconds {:optional true} pos-int?]]))

(def ChoiceStep
  (mu/merge
   StepCommon
   [:map
    [:kind [:= :choice]]
    [:collection-id :uuid]]))

(def LinkStep
  "Walks through another, separate sequence, then returns here. Shows that
  sequence's title, so renames carry over."
  [:map
   [:id :uuid]
   [:kind [:= :link]]
   [:sequence-id :uuid]])

(def Step
  [:multi {:dispatch :kind}
   [:task TaskStep]
   [:choice ChoiceStep]
   [:link LinkStep]])

(def Sequence
  [:map
   [:id :uuid]
   [:title (Text max-name)]
   [:image-id {:optional true} :uuid]
   ;; Schedule presets (every day / weekdays / weekends / custom) are UI
   ;; sugar over this set; the set is the single source of truth.
   [:days [:set Weekday]]
   [:requirements [:vector :uuid]]
   ;; Order = vector order.
   [:steps [:vector {:max max-steps} Step]]])

(def Group
  "Named, ordered. Each sequence belongs to exactly one group."
  [:map
   [:id :uuid]
   [:name (Text max-name)]
   [:sequence-ids [:vector :uuid]]])

(def ImageData
  "Pictures embedded in backup and shared files: data URLs per picture id.
  (Base64 exists only for transfer and backup; storage keeps blobs.)"
  [:map-of :uuid [:map
                  [:full [:re #"^data:image/"]]
                  [:thumb [:re #"^data:image/"]]]])

(def Content
  [:map
   [:sequences [:map-of :uuid Sequence]]
   [:groups [:vector Group]]
   [:images [:map-of :uuid Image]]
   [:items [:map-of :uuid Item]]
   [:collections [:map-of :uuid Collection]]])

(def Settings
  [:map
   ;; Device display preference; nil follows the system. Mirrored to
   ;; localStorage so it can be applied before first paint.
   [:theme [:maybe [:enum :light :dark]]]
   [:reward-level [:enum :off :calm :celebratory]]
   [:sound? :boolean]
   ;; Hour (0-23) at which "today" rolls over.
   [:day-boundary-hour [:int {:min 0 :max 23}]]
   ;; nil until the parent sets one.
   ;; Plain digits: it keeps a child out of parent mode on a family
   ;; device, it isn't security. Never leaves the device except in exports.
   [:pin [:maybe [:re #"^\d{4,8}$"]]]
   [:parent-timeout-seconds pos-int?]])

(def Meta
  [:map
   [:schema-version pos-int?]
   ;; Epoch ms of the last export, for the "last backed up N days ago" nudge.
   [:last-backup-at [:maybe number?]]])

;; ---------------------------------------------------------------------------
;; Per-day (persisted, resets when the date changes)

(def Resume
  "Where to pick a sequence back up today. Position = :done-count."
  [:map
   [:done-count nat-int?]
   [:choices [:map-of :uuid :uuid]]])

(def Day
  [:map
   [:date [:maybe Date]]
   [:done [:set :uuid]]
   [:resume [:map-of :uuid Resume]]])

;; ---------------------------------------------------------------------------
;; Per-run (not persisted; resume data is copied into :day)

(def Run
  [:map
   [:sequence-id :uuid]
   [:phase [:enum :requirements :steps :finished]]
   ;; Step currently shown. May be < :done-count after going back.
   [:index nat-int?]
   ;; Steps 0..done-count-1 are complete. Going back never lowers this.
   [:done-count nat-int?]
   ;; Requirements checklist has been passed (Ready pressed, or resumed).
   [:ready? :boolean]
   [:checked [:set :uuid]]
   ;; choice step id -> chosen item id
   [:choices [:map-of :uuid :uuid]]])

;; ---------------------------------------------------------------------------
;; Ephemeral (never persisted). Left open: timers, object-URL cache and
;; image health results will be added as those features land.

(def PinEntry
  [:map
   ;; :set then :confirm when no PIN exists yet; otherwise :unlock.
   [:mode [:enum :unlock :set :confirm]]
   [:digits [:re #"^\d{0,8}$"]]
   ;; The first entry while confirming a new PIN.
   [:first {:optional true} :string]
   [:message {:optional true} [:maybe :string]]])

(def Ui
  [:map
   [:screen [:enum :loading :home :walkthrough :library :things]]
   [:parent? :boolean]
   [:overview? :boolean]
   ;; Storage has loaded and saving is on (false before load, or when
   ;; IndexedDB is unavailable).
   [:persist? :boolean]
   ;; The published build's version (nil in development).
   [:app-version {:optional true} [:maybe :string]]
   ;; Tracks prefers-color-scheme; used while settings :theme is nil.
   [:system-theme [:enum :light :dark]]
   ;; Which way the walkthrough card slides in (and the indicator points).
   ;; :none when the view changed without moving (overview toggle).
   [:nav-direction {:optional true} [:enum :forward :back :none]]
   ;; Epoch ms; walkthrough input is ignored until then (double-tap guard).
   [:input-locked-until {:optional true} number?]
   ;; The last delete, while it can still be undone.
   ;; Recent deletes that can still be undone, most recent last. Each
   ;; `restore` is the event that reverses it; each expires on its own.
   [:undo {:optional true} [:maybe [:vector [:map
                                             [:message :string]
                                             [:restore [:vector :any]]
                                             [:token number?]]]]]
   ;; A file being imported: a backup {:kind :backup :data :summary} or
   ;; shared sequences {:kind :sequences :data :rows :choices} awaiting
   ;; confirmation, or {:error …}.
   [:import {:optional true} [:maybe :map]]
   ;; The picture dialog: {:target {:step-id …}|{:sequence-id …} :busy? …
   ;; :error …} while open.
   [:picture-editor {:optional true} [:maybe :map]]
   ;; The list editor (checklist or a choice step's list): {:target …}.
   [:list-editor {:optional true} [:maybe :map]]
   ;; Pictures screen: files still being added, and the last failure.
   [:library-busy {:optional true} [:maybe nat-int?]]
   [:library-error {:optional true} [:maybe :string]]
   ;; An export in progress: {:status :preparing|:ready|:error}.
   [:export {:optional true} [:maybe [:map [:status [:enum :preparing :ready :error]]]]]
   ;; Sequences chosen for sharing, while selecting on the list.
   [:selection {:optional true} [:maybe [:set :uuid]]]
   ;; PIN pad state while it is open.
   [:pin-entry {:optional true} [:maybe PinEntry]]])

;; ---------------------------------------------------------------------------

(def AppDb
  [:map
   ;; persistent
   [:content Content]
   [:settings Settings]
   [:meta Meta]
   ;; per-day
   [:day Day]
   ;; per-run
   [:run [:maybe Run]]
   ;; Runs waiting on a linked sequence, innermost last.
   [:run-stack [:vector Run]]
   ;; ephemeral
   [:ui Ui]])

(def persisted-keys
  "Top-level app-db keys written to storage."
  [:content :settings :meta :day])

(def ^:private app-db-explainer (m/explainer AppDb))

(defn explain-app-db
  "nil when valid, else humanized errors."
  [db]
  (some-> (app-db-explainer db) me/humanize))
