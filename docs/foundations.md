# Stepwise — Project Foundations

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
- Parent mode entry: a subtle hidden gesture (e.g. long-press a corner) plus a PIN.
- Parent mode auto-locks on inactivity timeout and on `visibilitychange` (app backgrounded).
- Home screen: image-first sequence cards, organized into parent-defined groups. Groups are renamed and reordered in place on the home screen in parent mode.

## Walkthrough (step view)
- One step per screen as a focused card. Horizontal orientation; transitions move left-to-right.
- Step content: `:title` (required), `:description` (optional), image (optional).
- The image-less layout is a first-class design: large centered title, description below. It is also the fallback when an image fails to load. No generic placeholder icons.
- Image frame:
  - Fixed aspect ratio (4:3), `max-height` in `dvh`, neutral background, rounded corners.
  - Image is `object-fit: contain` by default. Per-step override to `cover` + `object-position` is allowed.
  - The frame reserves its space, so there is no layout shift.
  - The Done button is always visible without scrolling. Step title is used as `alt`.
- Progress: a dot bar (filled = done, highlighted = current, hollow = future) plus "3 of 7" text.
- A "Next:" thumbnail of the upcoming step only (deliberately asymmetric: the past is shown by filled dots). On the last step, the Next slot shows a finish marker ("All done!").
- A single "Done" action marks the step complete AND advances. Lock input for ~700ms after advancing to prevent double-tap skips.
- Back navigation is allowed, de-emphasized, and view-only. Going back never undoes completion.
- Buttons are the primary input. No swipe navigation (possibly opt-in later, off by default).
- Sequence end: an unambiguous "All done!" screen with one big button back to the menu.
- HARD CAP: 12 steps per sequence (a single named constant).
- Screen Wake Lock API is active during walkthroughs.

## Overview
- Toggle rearranges the same steps into a vertical list: fixed-size thumbnails, title, and the same progress dots with the same state.
- The dots are shared elements between the overview list and the walkthrough progress bar, animated via the View Transitions API (`document.startViewTransition`).
  - Each dot gets `view-transition-name: step-dot-<id>`.
  - Off-screen dots animate from their computed positions. Consider per-dot durations to avoid long-distance dots moving too fast.
  - Unrendered elements (`display: none`, `content-visibility: auto`) only cross-fade.
  - A plain swap is the fallback where unsupported.
- RE-FRAME GOTCHA: Reagent renders asynchronously. Inside the `startViewTransition` callback, dispatch and then force the DOM update (`reagent.core/flush`, or resolve a promise via `r/after-render`). Otherwise the "after" snapshot equals the "before" and nothing animates. Wrap this once as an effect handler, e.g. `:view-transition/dispatch`.

## Requirements ("What you need")
- Optional per sequence: a vector of item references.
- Shown as a checklist screen before step 1, with its own distinct marker dot at the start of the progress bar.
- The child taps each item as gathered. The "Ready" button is disabled until all are checked; it should read as "not yet," not "broken," and animate to life when enabled.
- The checklist resets on every run.

## Choice steps
- `:kind :choice` referencing a shared collection of items as options. (Optional steps were considered and REJECTED: "do I have to?" ambiguity.)
- Progress marker: a stack of dots. After choosing, ALL dots remain; the chosen one is styled as complete and the others are muted. The choice stays visible in the overview.
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
- The daily slice is `{:date "YYYY-MM-DD" :done #{seq-ids}}`. It resets when the stored date differs from today.
- Configurable day boundary (default ~3am).
- Check the date on load AND on `visibilitychange`/resume, since PWAs stay open for days.
- Optionally keep the previous day's snapshot (e.g. for a "yesterday" view).
- Framing is always positive for the child. Any "X of Y" score view is parent-facing.
- Resume: if he exits mid-sequence, resume if same day, otherwise restart. Resume positions live in the per-day slice, so they reset automatically.

## Data model
- Sequences contain their steps INLINE (a vector; order = vector order). Steps are not shared between sequences.
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
- Image blobs live in IndexedDB, never in app-db. app-db holds only IDs.
- On import, resize via canvas to ~1024px long edge, WebP/JPEG ~80%. Store a small thumbnail blob too, for the overview and pickers.
- Display via `URL.createObjectURL`. Cache these URLs in ephemeral state and revoke unused ones.
- Encourage device photos and uploads: they avoid CORS problems and are more concrete for the child (his toothbrush, his bathroom).
- URL images:
  - `<img>` displays cross-origin fine, but fetching or canvas-drawing requires CORS. Offer "Save a local copy" when the host allows it.
  - Avoid caching opaque responses (Chrome pads their quota cost heavily).
- Health check (parent mode): test URL images with `new Image()` onload/onerror across the library, badge affected sequences, and record walkthrough-time failures for the parent.
- app-db persists to IndexedDB as an EDN string (`pr-str` / `cljs.reader/read-string`, or Transit) via a small wrapper like `idb-keyval`. Writes are debounced.
- Boot is asynchronous: loading state → read → initialize event.
- Call `navigator.storage.persist()`. Show a gentle "last backed up N days ago" nudge in parent mode.

## Export / import / sharing
- Export: one file (JSON/EDN) with sequences, libraries, and images embedded as base64. Base64 exists ONLY for transfer and backup. (A zip with image files plus a manifest is a possible later optimization.)
- Import: a file picker in parent mode. Decode base64 → Blob (e.g. `fetch(dataUrl).then(r => r.blob())`) → IndexedDB. Handle ID collisions.
- Send: Web Share API with files where `navigator.canShare({files})` is true (iOS/macOS Safari, Android Chrome, Chromium on Windows/ChromeOS); a download link otherwise.
- Later: single-sequence export for sharing with teachers or therapists.

## Platform and accessibility
- Service worker caches the app shell for offline use.
- Highly legible font (Atkinson Hyperlegible or Lexend). Touch targets well above 48px. High contrast. Use `dvh` units.
- Document for parents: iOS Guided Access and Android app pinning lock the device to the app (both work with browsers and installed PWAs).

## Explicitly out of scope
- Optional steps
- Nested sequences
- Reusable steps
- Any backend or sync service
- Long-term statistics or history

## Low priority / future
- Text-to-speech (Web Speech API) or parent-recorded audio for pre-readers
- Opt-in swipe navigation
- Optional group times to highlight the current group

## Open questions
- Garden mechanics (petal per step vs. per sequence; completion ritual)
- Max options shown in a choice step
- Whether resume feels right in practice
- The exact parent-mode gesture
- Whether the 12-step cap is right after real use
