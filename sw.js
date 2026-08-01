// MBM Life service worker — minimal, enables standalone PWA (WebAPK) install.
// Release marker: v418.
// Network-only: it does NOT cache anything, so app updates are never blocked.
// The mere presence of a fetch handler satisfies Chrome's installability rule.
self.addEventListener('install', function(e){ self.skipWaiting(); });
self.addEventListener('activate', function(e){ e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', function(e){ /* pass-through, no interception */ });
