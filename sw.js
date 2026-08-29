// MBM Life — service worker (v1, rebuilt)
//
// PURPOSE (per explicit requirement): the currently-active version on each
// device must stay stable — cache-first — even after the app is fully
// closed and reopened, until the user explicitly taps "Update now". A new
// deployed version is downloaded into its own cache in the background and
// left WAITING; it never activates itself. Only an explicit SKIP_WAITING
// message (sent by index.html when the user taps the button) lets it take
// over, followed by a controllerchange-triggered reload on that same
// device. Choosing "Later" leaves the current version fully in control —
// including across a full close/reopen — until the user acts.
//
// SCOPE: this worker ONLY ever intercepts the app shell itself
// (navigation requests and the root/index.html document). Firebase/
// Firestore, Google Maps, Nominatim, and every other network request pass
// straight through untouched, every time — this file has no way to
// interfere with Family sync or any other live data.

// v557: CACHE_NAME now bakes in the app version — MUST be bumped together
// with APP_VERSION in index.html on every release. This is what makes it
// structurally impossible for a newly-installing worker to overwrite the
// currently-active worker's cache: they now always have different names,
// so install() opening "its own" cache can never touch the active one.
const CACHE_NAME = 'mbm-life-shell-v557';
const SHELL_URLS = ['./', './index.html'];

self.addEventListener('install', (event) => {
  // Deliberately NO self.skipWaiting() call here. A newly-installed
  // worker must sit in the 'waiting' state until the page explicitly
  // asks it to take over — that is the entire point of this rebuild.
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return Promise.all(
        SHELL_URLS.map((url) =>
          fetch(url, { cache: 'reload' })
            .then((res) => { if (res && res.ok) return cache.put(url, res); })
            .catch(() => {}) // a single shell URL failing to pre-cache should not fail install
        )
      );
    })
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((names) => Promise.all(
        names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n))
      ))
      .then(() => self.clients.claim())
  );
});

// The ONLY way a waiting worker is ever allowed to activate: an explicit
// message from the page, sent exactly when the user taps "Update now".
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

function isShellRequest(request) {
  if (request.mode === 'navigate') return true;
  try {
    const url = new URL(request.url);
    if (url.origin !== self.location.origin) return false; // never touch cross-origin (Firebase/Maps/Nominatim/etc.)
    return url.pathname === '/' || url.pathname.endsWith('/index.html');
  } catch (e) {
    return false;
  }
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET' || !isShellRequest(req)) return; // let the browser handle everything else normally — no interception at all

  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached; // cache-first: the version this worker activated with stays authoritative
      return fetch(req).then((res) => {
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(req, copy)).catch(() => {});
        }
        return res;
      }).catch(() => caches.match('./index.html'));
    })
  );
});
