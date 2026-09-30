// This app's service worker: it makes the app installable and lets it run
// offline. Not served in development. scripts/publish.sh turns this template
// into dist/sw.js, filling in the cache prefix, the version string and the
// list of files to keep.

// Every cache this app makes starts with the prefix (derived from
// package.json's "name"), so cleaning up old versions only ever touches this
// app's own caches, even if another app shares the origin.
const PREFIX = '__PREFIX__';
const CACHE_NAME = PREFIX + '__VERSION__';
const PRECACHE = __PRECACHE__;

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      // cache: 'reload' skips the browser's HTTP cache, so a new version is
      // built from what the server has now, not from something it kept earlier.
      // addAll is all-or-nothing: if any file fails, this version does not
      // install and the previous one keeps working.
      .then((cache) => cache.addAll(PRECACHE.map((url) => new Request(url, { cache: 'reload' }))))
      // If the page that's already open has loaded everything it will ever
      // need (a small app: one script, one stylesheet), switching versions
      // under it is safe; the next launch simply gets the new version, whole.
      // For a bigger app that lazy-loads code, drop this line and let the
      // new worker wait until every open tab has closed, the usual default.
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((names) => Promise.all(
        names
          .filter((name) => name.startsWith(PREFIX) && name !== CACHE_NAME)
          .map((name) => caches.delete(name))))
      .then(() => self.clients.claim())
  );
});

// The app shell is served from the cache first: fast, and it works offline.
self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  if (new URL(request.url).origin !== self.location.origin) return;

  event.respondWith(
    caches.open(CACHE_NAME).then(async (cache) => {
      const hit = await cache.match(request, { ignoreSearch: true });
      if (hit) return hit;
      // Any page navigation inside the scope gets the app itself.
      if (request.mode === 'navigate') {
        const shell = await cache.match('index.html');
        if (shell) return shell;
      }
      return fetch(request);
    })
  );
});
