#!/usr/bin/env bash
#
# PWA publish script for Stepquence (adapted from pwa-publish-template):
#   resources/public/{index.html,app.css,reset.css,manifest.json,icons/,fonts/}
#   package.json, shadow-cljs.edn and scripts/ at the project root.
# Differences from the template: the stylesheet is app.css (not styles.css),
# and the self-hosted fonts/ folder is published and precached too.
#
#   scripts/publish.sh                 build ./dist
#   scripts/publish.sh ~/sites/myapp   build, then copy the result there
#
# Nothing here touches the dev build, so it is safe to run while `watch` is going.
# Each build gets a version like 2026.09.21.3 (date, then a counter that restarts
# each day). It names the service worker's cache, which is what makes browsers
# notice a new deploy and drop the old files. The counter lives in .publish-version.
#
# ONE THING TO SET UP FIRST, per project: a `:dist` build in shadow-cljs.edn,
# separate from your dev `:app` build (so publishing never disturbs a running
# `watch`, and dev-only code — devtools, tracing — is correctly stripped by
# advanced compilation). Something like:
#
#   :dist
#   {:target     :browser
#    :output-dir "dist/js/compiled"
#    :asset-path "js/compiled"
#    :modules    {:app {:init-fn your.app.core/init}}   ; <- your real init-fn
#    :release    {:build-options
#                 {:ns-aliases {day8.re-frame.tracing day8.re-frame.tracing-stubs}}}}
#                 ; ^ drop this :release block if the project doesn't use day8.re-frame.tracing
#
# The app's slug (used to name the service worker's cache, e.g.
# "myapp-2026.01.01.1", and to scope its cleanup to only its own caches even if
# another app shares the origin) comes from package.json's "name" field.

set -euo pipefail

# Always work from the project root, wherever the script is run from.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ ! -f package.json || ! -f shadow-cljs.edn ]]; then
  echo "Error: package.json and/or shadow-cljs.edn not found in $(pwd)." >&2
  exit 1
fi

PUBLIC=resources/public
DIST=dist
TARGET="${1:-}"
SLUG=$(node -e 'console.log(require("./package.json").name.toLowerCase().replace(/[^a-z0-9-]+/g, "-").replace(/^-+|-+$/g, ""))')

if [[ -z "$SLUG" || "$SLUG" == "undefined" ]]; then
  echo 'Error: package.json has no usable "name" field to build a cache slug from.' >&2
  exit 1
fi

# 1. Make sure everything the app points at exists, before spending time building.

for f in "$PUBLIC/index.html" "$PUBLIC/app.css" "$PUBLIC/reset.css" \
         "$PUBLIC/manifest.json" scripts/sw.template.js; do
  [[ -f "$f" ]] || { echo "Error: missing $f" >&2; exit 1; }
done

if ! grep -q '^\s*:dist\b' shadow-cljs.edn; then
  echo "Error: shadow-cljs.edn has no :dist build. See the comment at the top of this script." >&2
  exit 1
fi

# Every icon the manifest names must exist, relative to the manifest.
node -e '
  const fs = require("fs"), path = require("path");
  const dir = process.argv[1];
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
  const missing = (manifest.icons || [])
    .map((i) => i.src)
    .filter((src) => !fs.existsSync(path.join(dir, src)));
  if (missing.length) {
    console.error("Error: manifest.json names icons that do not exist: " + missing.join(", "));
    process.exit(1);
  }
' "$PUBLIC"

# 2. Work out this build's version. The counter is only saved once the build succeeds.

VERSION_FILE=.publish-version
today=$(date +%Y.%m.%d)
last=$(cat "$VERSION_FILE" 2>/dev/null || true)
count=1
if [[ "$last" == "$today."* && "${last##*.}" =~ ^[0-9]+$ ]]; then
  count=$(( ${last##*.} + 1 ))
fi
VERSION="$today.$count"
echo "Publishing $SLUG $VERSION"

# 3. Build the optimised app into a clean dist/.

rm -rf "$DIST"
npx shadow-cljs release dist

# 4. Add the static files. The layout mirrors resources/public, so the same
#    relative URLs work in development and in the published site.

cp "$PUBLIC/index.html" "$PUBLIC/app.css" "$PUBLIC/reset.css" "$PUBLIC/manifest.json" "$DIST/"
cp -R "$PUBLIC/fonts" "$DIST/fonts"
if [[ -d "$PUBLIC/icons" ]]; then
  cp -R "$PUBLIC/icons" "$DIST/icons"
  rm -f "$DIST/icons/site.webmanifest"   # a favicon generator's own manifest, if present; harmless if not
fi
rm -rf "$DIST/js/compiled/manifest.edn" "$DIST/js/compiled/cljs-runtime"

# Stamp the version into the page, so "which version is this?" is answerable.
sed -i "s|</head>|    <meta name=\"app-version\" content=\"$VERSION\">\n  </head>|" "$DIST/index.html"

# 5. Generate the service worker: this build's cache name, and every file in
#    dist to keep. (about.txt, if present, carries an icon licence and is not
#    needed offline.)

mapfile -t files < <(cd "$DIST" && find . -type f ! -name sw.js ! -name '*.txt' | sed 's|^\./||' | sort)

node -e '
  const fs = require("fs");
  const [slug, version, ...files] = process.argv.slice(1);
  const out = fs.readFileSync("scripts/sw.template.js", "utf8")
    .replace("__PREFIX__", () => slug + "-")
    .replace("__VERSION__", () => version)
    .replace("__PRECACHE__", () => JSON.stringify(["./", ...files], null, 2));
  fs.writeFileSync("dist/sw.js", out);
' "$SLUG" "$VERSION" "${files[@]}"

node --check "$DIST/sw.js"
if grep -q "__VERSION__\|__PRECACHE__\|__PREFIX__" "$DIST/sw.js"; then
  echo "Error: the service worker still has unfilled placeholders." >&2
  exit 1
fi

# 6. Only now is the version number spent.

echo "$VERSION" > "$VERSION_FILE"

# 7. Optionally copy the result somewhere to be hosted. Files are added and
#    overwritten; nothing already there is deleted.

if [[ -n "$TARGET" ]]; then
  mkdir -p "$TARGET"
  cp -R "$DIST"/. "$TARGET"/
  echo "Copied to $TARGET"
fi

echo
echo "Built $DIST/ (version $VERSION): ${#files[@]} files cached for offline use, $(du -sh "$DIST" | cut -f1) total."
echo "Try it locally with: npx serve $DIST   (or: python3 -m http.server -d $DIST 8000)"
