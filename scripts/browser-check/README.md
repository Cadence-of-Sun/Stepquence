# Browser checks

Small helpers for driving the app in headless Chromium over the DevTools
protocol: click through flows, type, drag, pick files, read state and take
screenshots. Used to verify UI changes at phone and tablet sizes.

Serve the built app first (`npm run watch` builds it), then:

```sh
cd resources/public && python3 -m http.server 8391 &
S=/tmp/stepquence-check STEPS="$(python3 my_steps.py)" node scripts/browser-check/cdp.mjs 390 844 phone
```

- `cdp.mjs <width> <height> <prefix>` runs the JSON step list in `$STEPS`.
  Screenshots go to `$S/shots/<prefix>-<name>.png`; each run uses a fresh
  browser profile under `$S`, so it starts from the seed content.
- `steps.py` has helpers for building step lists in Python (reading and
  setting app-db, e.g. `PARENT` to enter parent mode, and `LIST` to read
  the sequence list). Start a step file with
  `exec(open("scripts/browser-check/steps.py").read())` and print
  `json.dumps(steps)`.

Step kinds: `click` (button text or a selector), `clickfast`, `shot`,
`eval`, `reload`, `type [selector, text]`, `key <name>`,
`drag [selector, dy]`, `save [expr, path]`, `file [input-selector, path]`.
