# Stepquence

An offline-capable PWA for walking through step-by-step routines, built with
ClojureScript, re-frame and shadow-cljs. Everything stays on the device: no backend, no accounts.

The design and scope are in [`docs/foundations.md`](docs/foundations.md).

## Development

```sh
npm install
npm run watch   # then open http://localhost:8280/
```

The nREPL server runs on port 8777; the shadow-cljs build id is `:app`.

In dev builds, app-db is validated against `stepquence.schema/AppDb` after
every event. Violations are logged to the browser console.

## Source layout

- `src/stepquence/schema.cljs`: malli schemas for app-db, plus `max-steps`
- `src/stepquence/db.cljs`: initial app-db. Top-level keys are grouped by lifetime:
  persistent (`:content`, `:settings`, `:meta`), per-day (`:day`),
  per-run (`:run`), ephemeral (`:ui`)
- `src/stepquence/seed.cljs`: sample content, used until editing exists
- `src/stepquence/{events,subs,views}.cljs`: re-frame wiring

## Release

```sh
npm run release
```

## Fonts

Atkinson Hyperlegible Next is self-hosted in `resources/public/fonts/`
(SIL Open Font License 1.1).

## Browser checks

`scripts/browser-check/` drives the app in headless Chromium (clicks,
typing, drags, file picks, screenshots). See its README.
