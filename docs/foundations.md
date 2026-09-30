# Stepquence — Project Foundations

A partially offline-ready PWA built with ClojureScript + re-frame for creating and walking through customizable step-by-step sequences (routines). Built for an autistic child who reads well; designed to teach sequences as coordinated steps and requirements, and to serve as a household/personal routine resource. A parent creates and edits; the child walks through.

## Guiding principles
- Predictability over novelty: fixed layouts, nothing shifts position between steps.
- Calm by default; stimulation is opt-in.
- Child-first UX: large touch targets, minimal decisions per screen, no dead ends that feel broken.
- Local-only, private data. No backend, no accounts, ever. File-based transfer is a feature.

## Interface model
- ONE interface: the sequence explorer. Parent mode is an overlay that reveals extra controls in the same views (WYSIWYG editing).
- Child mode: linear navigation only, view-only for content.
- Parent mode adds:
  - In-place editing
  - Clickable progress dots for jumping between steps
  - Drag-to-reorder in the overview
  - Add/delete controls
  - Library screens
- Parent-mode navigation is preview only. It NEVER records completion or daily progress.
- Parent mode entry: a hidden gesture plus a PIN.
  - Gesture: hold the "Sequences" heading for 2 seconds. No visual feedback, so to a child it's just a heading. Lifting, moving off or scrolling cancels.
  - PIN: 4–8 digits, on an opaque PIN pad (keypad, dot readout, OK labeled Unlock / Next / Save; keyboard digits, Backspace, Enter and Escape work too). The first time, the parent chooses a PIN and enters it twice. Stored as plain digits in settings: it keeps a child out on a family device, it isn't security.
- Parent mode auto-locks on inactivity timeout (`:parent-timeout-seconds`, default 120) and on `visibilitychange` (app backgrounded).
  - Idle detection, no polling: capture-phase `pointerdown`/`keydown` listeners stamp the last-input time into a plain atom (not app-db, so input costs no re-render or save). While unlocked, one timer checks the idle time when it fires and either locks or sleeps for the remainder. At most one wake-up per timeout period.
  - Locking during a walkthrough returns to the sequence list, so a preview can never continue as a real run.
- While unlocked, a banner on every screen ("Parent mode", a Settings gear, Lock) makes the mode unambiguous.
- Settings popover (gear): theme, backup (status, Export, Import), and the build's version at the bottom. The version comes from a `<meta name="app-version">` that the publish script stamps into the published HTML; it's absent in development, so nothing is shown there. The popover closes on Escape (focus returns to the gear) or a press outside it. It's the same pattern as brainboard's settings panel (`stepquence.dismiss` is ported from it). Nothing about backup sits on the sequence list itself.
- Home screen ("Sequences"): compact sequence cards (image-first once images exist) with a mini dot per step, organized into parent-defined groups. Groups are renamed and reordered in place on the home screen in parent mode.
  - Done today: green edge, check, all dots filled. Started but unfinished today: the steps done so far are filled (it will resume there).
  - A group whose sequences are all done today gets a plain checkmark after its name, in the heading's own color and size.
- Leaving a walkthrough is labeled "Exit" (not "Home"/"Back"), everywhere, so one word always means one action.

## Walkthrough (step view)
- One step per screen as a focused card. Horizontal orientation; transitions move left-to-right.
- Layout:
  ```
  Exit     <sequence title>     [one step / all steps]
           (Part of <parent>)
        [ card: title, description, options ]
        [ primary button ]                    <- fixed position
  ← Back      3 of 7 / progress dots      Next: <title>
  ```
  - The primary button sits at the bottom of the main area, which has the same height on every step, so it never moves. The card fills the space above it, its content bottom-aligned just above the button. (There used to be a spacer below the button; it was given to the content.) Nothing else moves either: hidden controls use `visibility: hidden`, and the "Next:" hint has a fixed height (the Back button's) whether its title takes one line or two.
- Step content: `:title` (required), `:description` (optional), image (optional).
- The image-less layout is a first-class design: large centered title, description below. It is also the fallback when an image fails to load. No generic placeholder icons.
- Image frame:
  - Fixed aspect ratio (4:3), `max-height` in `dvh`, neutral background, rounded corners.
  - Image is `object-fit: contain` by default. Per-step override to `cover` + `object-position` is allowed.
  - The frame reserves its space, so there is no layout shift.
  - The Done button is always visible without scrolling. Step title is used as `alt`.
- Progress: a dot bar (filled = done, hollow = future) plus "3 of 7" text above it.
  - The current position is a line under its dot (not a ring). It is one element that slides between equal-width slots, and while travelling it becomes an arrow pointing the way it moves (right forward, left back).
  - On finishing, the line grows to underline the whole bar and turns green, like the rule under a finished sum.
  - Marker shapes echo their controls: requirements = a box that gets a drawn-in check; choice = a vertical stack of small dots in option order; link = a pill holding a little sequence.
- A "Next:" hint with the upcoming step's title only (deliberately asymmetric: the past is shown by filled dots). On the last step, it shows the finish marker ("All done!").
- The primary button is labeled by what it does: Ready (requirements), Done, Start (enter a linked sequence), Exit / Continue (on the finish screen). Done marks the step complete AND advances. Input is locked for ~700ms after any move to prevent double-tap skips (including a second tap landing on the next screen's options).
- Back navigation is allowed, de-emphasized, and view-only. Going back never undoes completion.
- Revisited (already-completed) positions have nothing to do, only pages to turn: the primary button is hidden (keeping its space), and the corners become a symmetric pair of neutral page turns, "← Back" and "Next →". Done returns on reaching the first not-yet-done step.
- Buttons are the primary input. No swipe navigation (possibly opt-in later, off by default).
- Sequence end: an unambiguous "All done!" screen: a check-circle that draws itself in, the sequence's title, "All done!", and one big button (Exit; Continue → inside a linked sequence). The progress bar stays, fully filled and underlined.
- HARD CAP: 12 steps per sequence (a single named constant).
- Screen Wake Lock API is active during walkthroughs.

## Overview
- Toggle rearranges the same steps into a vertical list: fixed-size thumbnails (once images exist), title, and the same progress dots with the same state.
  - The toggle sits top right in the walkthrough and follows the theme toggle's pattern: both options shown (one step / all steps), the active one highlighted, pressing switches.
  - The active sequence's title (and "Part of <parent>" inside a linked sequence) is the list's heading. In the step view it sits small in the header. It is one element with shared view-transition names (`sequence-title`, `sequence-context`), so it moves and scales between the two. The header title is centered in the viewport (aligned with the card below). On wide screens the header has three columns with equal sides; a side only grows to its button's width if a long title needs the room. On phones (under 40rem) the title takes its own full-width row under Exit and the toggle. It is hidden on the finished screen, whose card names the sequence.
  - Each row: its marker, the title (link rows show the linked sequence's title), and for a choice row, the chosen option underneath. The current row is highlighted and has the position line under its dot.
  - Descriptions: only the current row shows its description (if it has one), under the title. Showing every step's description would crowd the list.
  - In the overview, the footer (Back, bar, Next) steps aside, since the dots now live in the list. Child mode: view-only. Parent mode will add jumping to a row and drag-to-reorder.
  - Toggling is not a move: no card slide, no indicator arrow (`:nav-direction :none`).
  - The current row is a shortcut back to its step view: the whole row is a button, with a small → at its end, using the same transition as the toggle. (A Done in the overview was considered and rejected: steps complete in different ways, like a choice or a checklist, so the overview would need every mechanism. The overview is the map; the step view is where he acts.)
  - Only the list scrolls; the heading stays in view above it. Heading and list are centered together while the list fits.
  - The current row is scrolled into view before the "after" snapshot.
- The dots are shared elements between the overview list and the walkthrough progress bar, animated via the View Transitions API (`document.startViewTransition`).
  - Each dot gets `view-transition-name: step-dot-<id>`.
  - Off-screen dots animate from their computed positions. Consider per-dot durations to avoid long-distance dots moving too fast.
  - Unrendered elements (`display: none`, `content-visibility: auto`) only cross-fade.
  - A plain swap is the fallback where unsupported.
- RE-FRAME GOTCHA: Reagent renders asynchronously. Inside the `startViewTransition` callback, dispatch and then force the DOM update (`reagent.core/flush`, or resolve a promise via `r/after-render`). Otherwise the "after" snapshot equals the "before" and nothing animates. Wrap this once as an effect handler, e.g. `:view-transition/dispatch`.
  - Implemented as `:view-transition/dispatch` (`stepquence.fx`): `dispatch-sync` + `r/flush` inside the callback. The callback always runs after the triggering event finishes, so `dispatch-sync` is safe there. This relies on React 17's synchronous legacy render; moving to React 18's `createRoot` would also need `flushSync`.
  - Shared names: markers use `step-dot-<id>` (`step-dot-requirements` for the checklist marker), and the position line uses `step-indicator`.
  - Shared elements move over 950ms with an ease-in-out curve (500ms felt rushed). The rest of the page cross-fades in 450ms, so the moving pieces lead. Per-dot durations or staggering are still an option if long-distance dots feel too fast.

## Editing (parent mode)
- The overview is the editor; the step view is the preview. Editing lives in one place.
- Rows: drag handle, marker, title, description, delete, and → (preview that step). The row's dot also previews its step, like the dots in the step view. Titles wrap and grow (they run to 60 characters); Enter still saves.
  - Titles and descriptions are edited in place. Enter or leaving the field saves, Escape reverts, and a blank title reverts. The length caps apply as you type.
  - A step without a description shows a faint "+ Description". A cleared description is removed.
  - The sequence title (the overview heading) is editable too, and keeps its view-transition name.
  - Link rows show the linked sequence's title (not editable here). Choice rows show which collection they use. The requirements row shows its item count. Changing a choice's collection, a link's target or the requirement items waits for the library pickers.
- "+ Add step" at the end appends a task step titled "New step", focused with the text selected, so typing replaces it. At the 12-step cap the row says so instead of disappearing.
- Delete shows "Step deleted · Undo" for 6 seconds (no confirmation dialog). A sequence keeps at least one step.
- Reorder: drag the handle, using pointer events so touch works (the handle has `touch-action: none`). The row follows the pointer, the rows it passes make way, dragging near the list's edges scrolls it, and the drop commits. Arrow keys on a focused handle move the step by one, and focus stays on the handle.
- Structural edits (add, delete, move, undo) clear today's resume position for that sequence, since it would point at the wrong step. The preview stays on the same step, not the same slot.
- Step view in parent mode: the progress dots are buttons that jump anywhere in the preview.
- Edits apply only in parent mode, to the sequence being previewed, and save like any other change.

## Managing sequences and groups (parent mode)
- The sequence list in parent mode shows every group, including empty ones, and every sequence, each with its schedule ("Weekdays", "Mon, Wed"). Sequences not scheduled today are dimmed and marked "not today".
- Groups: the name is edited in place, ↑/↓ reorder, and delete moves the group's sequences to Ungrouped (undo moves them back). "+ New group" adds one just before Ungrouped with its name selected.
  - Ungrouped is identified by a fixed id (`schema/ungrouped-id`), not its name. It can be renamed and moved, but not deleted, and it always receives the sequences of deleted groups. It is recreated if missing (e.g. from an import).
- "+ New sequence" at the end of each group creates a sequence (one "New step", every day) and opens it in the editor with its title selected.
- The editor's "Sequence settings" section (below the steps):
  - Group: a dropdown. Moving appends to the new group. Reordering within a group isn't built yet.
  - Days: presets (Every day / Weekdays / Weekends) plus seven day toggles. The day set is the single source of truth, so a preset is simply highlighted when the set matches it. With no days selected, a note says the sequence won't appear on the child's list.
  - Delete sequence, with undo. It's refused while other sequences link to it, and the note names every one ("Get dressed (step 7), Bedtime (step 5) link to this sequence. Remove those steps first…"), per the no-dangling-references rule. Deleting also clears today's done and resume entries for it.
- Consistency: after every event, a global interceptor checks that the screen points at real data. If the open sequence (or one waiting on it through a link) no longer exists, for example because an undo removed it while it was open, the app returns to the sequence list. Selected ids that no longer exist drop out of the selection. Locking and unlocking both clear transient parent state (PIN pad, undo offers, selection, pending import).
- Undo is a short stack: each delete gets its own 6-second window. The toast (on any screen) shows the most recent, with a count when several are on offer ("Group deleted · 3"). Undo reverses the most recent, and the earlier ones stay on offer until they expire. Each entry carries the event that reverses it.
- Adding a link step: a small link-icon button next to "+ Add step" (shown when there are at least two sequences) opens a picker. It lists only sequences that can't lead back to this one through links (not itself, and no loops at any depth), so the editor can't create a cycle. The walkthrough still guards against cycles in imported data.

## Requirements ("What you need")
- Optional per sequence: a vector of item references.
- Shown as a checklist screen before step 1, with its own distinct marker dot at the start of the progress bar.
- The child taps each item as gathered. The "Ready" button is disabled until all are checked; it should read as "not yet," not "broken," and animate to life when enabled.
  - Disabled: dashed edge, faint hatching (so it can't be mistaken for a checklist row), and the word struck through. Enabled: solid accent, with a small bounce as it wakes.
- The checklist resets on every run.

## Choice steps
- `:kind :choice` referencing a shared collection of items as options. (Optional steps were considered and REJECTED: "do I have to?" ambiguity.)
- Options are a vertical list (like the requirements checklist, with a dot where the checklist has a box), in the same order as the progress marker's dot stack, so the marker reads as a miniature of the buttons. Done is gated until an option is chosen; a choice whose collection is missing or empty is not gated (never a dead end).
- Progress marker: a stack of dots. After choosing, ALL dots remain; the chosen one is styled as complete and the others are muted. The choice stays visible in the overview.

## Linked sequences
- `:kind :link` with `:sequence-id`: a step that walks through another, separate sequence (e.g. Bedtime → "Brush teeth"). Chosen instead of nesting sequences in the data: sequences stay flat and independently usable.
- The step shows the linked sequence's title (renames carry over), a mini dot preview of it, and a "Start" button.
- The linked sequence runs as normal, in whatever state it is in today (it resumes if started earlier). The header shows "Part of <parent>".
- Finishing it returns to the parent with the link step completed; the finish button says "Continue →" rather than "Exit". Exit inside a linked sequence goes up one level, leaving the link step not done.
- A link to a missing or empty sequence, or to one already in progress (a cycle), behaves as a plain step.
- Open question: the max number of options displayed at once.

## Timers
- Optional `:timer-seconds` per step.
- Tap-to-start. Never auto-start: he looks at the picture first, then the text.
- Reset (and possibly pause) controls.
- The timer is for the action, not the step: it never auto-advances and never gates Done.
- A visual emptying ring/wedge; gentle visual cue at zero, with optional sound.
- Timer state is ephemeral.

## Rewards
- Parent setting: off / calm / celebratory. Also respect `prefers-reduced-motion`. No flashing. Sound off by default.
- Per step: a brief (<1s), consistent micro-reward, e.g. the dot filling with a bounce or a checkmark drawing itself.
- Per sequence: a bigger moment, ending on the clear "All done!" screen.
- Concept under consideration: a procedural garden.
  - Flowers are SVG, seeded deterministically from sequence IDs.
  - A garden is finite: when it completes, there is a small ceremony and a new one begins.
  - Undecided: petal-per-step vs. petal-per-completed-sequence.
  - Unfinished items are shown as buds, never as failures.
- Possible stickers: open emoji sets as bundled SVG files (not system glyphs), outlined via an SVG filter (feMorphology dilate on SourceAlpha + white flood + merge) chained with a soft drop-shadow. Check licenses.

## Daily tracking and schedules
- Sequences have a schedule preset: every day / weekdays / weekends / custom days. There is no schedule library.
  - The child's list shows only sequences scheduled today (weekday of the per-day date, so the day boundary applies). Groups with none today are hidden, and a group's checkmark counts only today's sequences.
- The daily slice is `{:date "YYYY-MM-DD" :done #{seq-ids}}`. It resets when the stored date differs from today.
- Configurable day boundary (default ~3am).
- Check the date on load AND on `visibilitychange`/resume, since PWAs stay open for days. (Implemented: `stepquence.day/today` applies the boundary; a new date resets the done set and resume positions.)
- Optionally keep the previous day's snapshot (e.g. for a "yesterday" view).
- Framing is always positive for the child. Any "X of Y" score view is parent-facing.
- Resume: if he exits mid-sequence, resume if same day, otherwise restart. Resume positions live in the per-day slice, so they reset automatically.

## Data model
- Sequences contain their steps INLINE (a vector; order = vector order). Steps are not shared between sequences.
- Step kinds: `:task` (title, description, image, timer), `:choice` (collection), `:link` (another sequence).
- Text length caps, enforced in the schema (so imports too) and later as editor input limits: step title 60 characters, description 200, sequence/item/collection/group names 40. Long unbroken words wrap as a CSS backstop.
- Shared libraries, referenced by UUID:
  - Images: `{:id :name? :source :local|:url :href? :width :height}`. Steps reference `:image-id`. This enables reuse, and fixing an image once fixes every step that uses it.
  - Items: `{:id :title :image-id?}`, representing a *thing*. Used by requirements and collections.
  - Collections: named ordered lists of item IDs (e.g. "Foods", "Activities"). Used by choice steps; reference-only, so edits propagate.
  - Groups: named, ordered. Each sequence belongs to one; the default is "Ungrouped."
- Deletion policy: never leave dangling references; prefer replace over remove.
  - Image: offer "Replace with…" (repoints all steps). Plain delete is also safe (steps fall back to the text-only layout).
  - Item: remove it from requirements and collections.
  - Collection: block deletion (or offer replace) while choice steps use it.
  - Group: its sequences move to Ungrouped.
  - Show usage counts in the warning. Render defensively regardless (imports may carry partial data).
- Organize app-db top-level keys BY LIFETIME:
  - Persistent: content (sequences, libraries, groups) and settings (reward level, sound, day boundary, PIN, etc.).
  - Per-day: daily done set, resume positions.
  - Per-run: requirements checklist, choices made, current step.
  - Ephemeral: parent mode, overview toggle, timer state, object-URL cache, image health results.

## Images and storage
- Built (round 1: pictures on steps), in `stepquence.images`:
  - Files live in their own IndexedDB store (`stepquence-images`), as `<id>/full` (about 1024px on the long edge) and `<id>/thumb` (256px). They're decoded with `createImageBitmap(file, {imageOrientation: "from-image"})`, so camera orientation is applied, and resized on a canvas. They're encoded as WebP (0.8), falling back to JPEG (0.82) when the browser returns anything else (Safari may not encode WebP).
  - App data keeps only the library records (`:content :images`, `{:id :name :source :local :width :height}`); steps point at them with `:image-id`.
  - Object URLs are created on first use and cached in a plain Reagent atom (handles, not data). A picture that can't be read is marked failed and renders nothing, so the step falls back to text only.
  - Step view: the 4:3 frame above the title. Pictures get priority over text: the title and description keep their natural height, and the picture area takes all the rest, but never less than half the content area (a CSS size container). So a long description can't shrink the picture to a stamp; on a small phone with a very long description, the text scrolls instead. The description is smaller on phones (about 16.8px, and 24px on tablets). Fit (`contain`, the default) or Fill (`cover`, around a focus point).
  - Editor: each task step's row shows its thumbnail (or "+ Picture") under the description. It opens a picture dialog with a preview, Fit/Fill, tap-to-set the focus point when filled, "Take or choose a photo" (one `accept="image/*"` picker; phones offer the camera and the gallery), "From the library" (reuse), and "Remove picture" (the picture stays in the library).
  - Overview: a small thumbnail on rows whose step has a picture. Nothing on rows without one.
- Built (round 2):
  - Backups carry every picture, and shared files carry the pictures their sequences use (steps and cards), as `:image-data` (data URLs, validated). Imports store the files first and only then change any data.
  - Export timing: reading pictures is asynchronous, but sharing needs a tap. The file is prepared, then shared at once if the tap still counts (`navigator.userActivation.isActive`); otherwise a "Your file is ready · Share" prompt asks for one more tap. The prepared file waits outside app-db (it can be several MB). Browsers that can't share download it straight away.
  - The Pictures screen (Settings, "Manage pictures"): every picture, two across on a phone, with its name (editable), where it's used ("Used in 2 steps and 1 card"), "Add pictures" (several at once), "Replace with…" (repoints every use; a fill focus point is dropped; undoable), and Delete (confirmation when in use, since those uses fall back to text; undoable). A deleted picture's files are removed only after the undo window, and only if it wasn't restored.
  - Sequence card pictures: "Card picture" in the editor's Sequence settings (the same picture dialog, without fit/focus: cards always fill a small 4:3 thumbnail), shown at the card's left on the sequence list.
  - Still later: pictures from URLs (and their health check), and pictures on items (checklist and choice options), which come with the libraries step.
- Image blobs live in IndexedDB, never in app-db. app-db holds only IDs.
- On import, resize via canvas to ~1024px long edge, WebP/JPEG ~80%. Store a small thumbnail blob too, for the overview and pickers.
- Display via `URL.createObjectURL`. Cache these URLs in ephemeral state and revoke unused ones.
- Encourage device photos and uploads: they avoid CORS problems and are more concrete for the child (his toothbrush, his bathroom).
- URL images:
  - `<img>` displays cross-origin fine, but fetching or canvas-drawing requires CORS. Offer "Save a local copy" when the host allows it.
  - Avoid caching opaque responses (Chrome pads their quota cost heavily).
- Health check (parent mode): test URL images with `new Image()` onload/onerror across the library, badge affected sequences, and record walkthrough-time failures for the parent.
- app-db persists to IndexedDB as an EDN string (`pr-str` / `cljs.reader/read-string`) via `idb-keyval`, one key (`stepquence.app-db`) holding the persistent and per-day slices (`:content`, `:settings`, `:meta`, `:day`). The per-run and ephemeral slices are never written.
  - A global interceptor schedules a save only when an event changes a persisted slice. Writes are debounced (500ms) and flushed immediately when the app is hidden.
  - Saving is off until storage has loaded, so defaults can never overwrite stored data.
  - Stored data that is unreadable or fails the schema is copied to a backup key (`stepquence.app-db.unusable-<ms>`) before the app starts fresh. It is never silently discarded.
  - Loaded data passes through `storage/migrate` (keyed on `:meta :schema-version`) before validation.
  - If IndexedDB is unavailable, the app runs without saving rather than not at all.
- Boot is asynchronous: loading state (background only, no spinner) → read → `::loaded` → home.
- Call `navigator.storage.persist()`. Show a gentle "last backed up N days ago" nudge in parent mode (see Export / import).

## Export / import / sharing
- Export: one EDN file (`stepquence-backup-YYYY-MM-DD.edn`) with a format marker (`:stepquence/backup 1`; files with the earlier working name's `:stepwise/backup` still import), the schema version, the export time, all content and settings. Images will be embedded as base64 once they exist. Base64 exists ONLY for transfer and backup. (A zip with image files plus a manifest is a possible later optimization.)
  - Included: the PIN, so a restored device keeps its lock. Excluded: today's progress, and the theme (a device display preference).
- Send: Web Share API with files where `navigator.canShare({files})` is true (iOS/macOS Safari, Android Chrome, Chromium on Windows/ChromeOS); a download otherwise. Export runs synchronously inside the click (`dispatch-sync`), because sharing requires a user gesture. A cancelled share sheet doesn't count as a backup.
- Import (parent mode): a file picker. The whole file is validated against the schema before anything changes. Unreadable, foreign, newer-version or damaged files get a plain explanation and change nothing.
  - A backup import is a full restore, confirmed in a dialog that summarizes it ("4 sequences in 4 groups, from 28 Sep 2026") and says what will be replaced.
  - Before replacing, the current data is copied to `stepquence.app-db.before-import-<ms>` in IndexedDB, and the restore can be undone from the toast for a few seconds.
  - Today's progress starts fresh; the backup's export time becomes the "last backed up" time.
- The Settings popover shows "Last backed up N days ago" (or "Not backed up yet", emphasized from 14 days on) with Export and Import buttons.
- Sharing chosen sequences (e.g. with a teacher or therapist), in `stepquence.share`:
  - Choose on the parent sequence list: "Select" puts a select mark on each card, and a bar at the bottom shows the count and the actions. While selecting, taps only select, and the add and group controls step aside. The editor's Sequence settings also has "Share this sequence".
  - Select mode's actions: Export; Move to… (any group, or "New group", which creates it, moves the selection in, and puts its name up for editing); Delete. Delete is one undo for all. It's refused while a selected sequence is linked from a sequence outside the selection, and the bar names the link ("Brush teeth is linked from Bedtime"); selecting both allows it.
  - The file (`:stepquence/sequences 1`; named after the sequence when there's one, e.g. `stepquence-bedtime.edn`) is self-contained: the chosen sequences, every sequence they link to at any depth (so no link can dangle; the bar and editor say what comes along, e.g. "Also includes Brush teeth, which is linked"), the items and collections they use, and their groups holding only them. Pictures will travel the same way.
- Import (the same button) recognizes the file: a backup means a full restore; shared sequences mean a merge. The merge dialog lists each incoming sequence:
  - Not on this device: "New", added to its group (that group by id if it's here; otherwise created from the file's name; Ungrouped if the file has none for it).
  - Already here (same id, which means the same sequence): choose "Update mine" (the default; replace title, steps and requirements in place, keeping this device's group and schedule, and clearing its resume position for today) or "Keep both" (a copy with fresh sequence and step ids; links inside the file are pointed at the copies).
  - Items and collections are added if missing; ones already here keep this device's version, so a merge never quietly changes existing sequences' items.
  - The merge can be undone from the toast ("1 updated, 1 copied").

## Platform and accessibility
- Service worker caches the app shell for offline use.
  - `scripts/publish.sh` (adapted from pwa-publish-template) builds `dist/` from a separate `:dist` build, stamps the version (`YYYY.MM.DD.N`) into `<meta name="app-version">`, and generates `sw.js` from `scripts/sw.template.js`: every published file precached (fonts included) under a cache named `stepquence-<version>`, served cache-first, with old versions cleaned up by prefix.
  - The worker is registered only in release builds (not on the dev server). Paths in `index.html` are relative, so the app works from any folder of a static site.
  - `manifest.json`: standalone, the app's background color, and icons. The icon is the green check that completes a sequence: a solid green circle with a white check on the warm background. There are SVG sources plus rendered PNGs: 192/512, a maskable 512 with the check inside the safe zone, a full-square Apple touch icon, and a 32px favicon.
  - Settings shows "Install as an app" when a Chromium browser offers installation, or "Share, then Add to Home Screen" on iOS Safari (which has no prompt). The browser's toolbar color (`theme-color`) follows the theme.
- Highly legible font (Atkinson Hyperlegible Next, self-hosted). Touch targets well above 48px. High contrast. Use `dvh` units.
- Light and dark themes. The app follows the system (`prefers-color-scheme`, live) until the toggle is pressed; the toggle shows ☀ / ☾ with the active one highlighted, and each press switches and saves the choice. The choice is kept in `localStorage` (a device display preference) so an inline script can apply it before first paint.
- Document for parents: iOS Guided Access and Android app pinning lock the device to the app (both work with browsers and installed PWAs).

## Explicitly out of scope
- Optional steps
- Nested sequences in the data (see Linked sequences for the alternative)
- Reusable steps
- Any backend or sync service
- Long-term statistics or history

## Low priority / future
- Dim controls when idle (proposed, not built). The step screen has something in every corner, which can feel busy. Hiding controls until a tap was considered and set aside: it makes the screen depend on hidden state, makes taps ambiguous on choice/checklist screens, and hides options from a child who doesn't know they exist. The proposed alternative:
  - After ~6s without input, the corner controls (Exit, view toggle, Back, Next hint/button) fade over ~1s to ~30% opacity. They keep their outlines and positions, so they still read as buttons.
  - Done, the step content, the dots and "N of N" never dim.
  - Any input restores them, including a tap on the empty background (checked with `event.target === event.currentTarget`, so taps on options or checklist items don't count). A tap on a dimmed control acts immediately; nothing needs revealing first.
  - A parent setting, "Dim controls when idle", off by default, so a child learns the app with everything at full strength. Until parent settings exist, a dev toggle.
  - Also considered: "quiet chrome" (borderless controls, always visible). Rejected because the outline is a useful "this is pressable" cue for a child new to the app.
- Text-to-speech (Web Speech API) or parent-recorded audio for pre-readers
- Opt-in swipe navigation
- Optional group times to highlight the current group

## Open questions
- Title position on steps without a picture: it now sits just above the fixed Done button (content bottom-aligned), with the empty space above. The alternative is to center text-only steps vertically in the space, which puts the title at a different height than on picture steps. To be judged in real use.
- Garden mechanics (petal per step vs. per sequence; completion ritual)
- Max options shown in a choice step
- Whether resume feels right in practice
- Whether the 12-step cap is right after real use
