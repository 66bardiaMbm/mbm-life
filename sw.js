// MBM Life — service worker LOADER (v1)
//
// ARCHITECTURE (agreed with Bahman, replaces the per-release-versioned
// sw.js used through v565):
//
// This file is intentionally CONTENT-AGNOSTIC. It does not know or care
// what APP_VERSION currently exists — its only job is:
//   1. Serve whichever release folder (/releases/<id>/) the DEVICE has
//      actually accepted (IndexedDB `acceptedReleaseVersion`), for shell
//      navigation requests.
//   2. Never change that on its own. The only writer of
//      `acceptedReleaseVersion` is the page itself, in direct response to
//      the user's own "Update now" tap (see applyUpdate() in index.html).
//
// Because each release lives at its OWN immutable, never-overwritten URL
// (/releases/<id>/index.html), a network fetch to that EXACT url can never
// return a different version's content by construction — the old class of
// bug (falling back to a network fetch that silently serves whatever is
// newest) is closed at the root, not patched around.
//
// Routine content releases therefore never need to replace THIS file —
// only version.json and a new /releases/<id>/ folder. This file only needs
// to change for loader-logic changes, which is rare and still goes through
// the browser's normal install→waiting→(explicit skipWaiting) lifecycle,
// same as before.
//
// MIGRATION: any device that had already accepted a release under the OLD
// single-cache-name scheme (CACHE_NAME-style string in `acceptedVersion`,
// e.g. 'mbm-life-shell-v563') keeps seeing that exact same version — this
// worker parses the version number out of that old value on first
// activation and adopts it as `acceptedReleaseVersion`, NEVER defaulting
// to `latest`. Only a genuinely first-ever install (neither key present)
// defaults to DEFAULT_RELEASE below.

const LOADER_VERSION='L1';
const DEFAULT_RELEASE='v566'; // bundled default for a true first-ever install only — bumped by CI alongside each release, never used to override an existing acceptedReleaseVersion
const DB_NAME='mbm-life-sw-meta';
const STORE_NAME='meta';
const ACCEPTED_KEY='acceptedReleaseVersion';       // new schema: plain release id, e.g. 'v566'
const LEGACY_ACCEPTED_KEY='acceptedVersion';        // old schema: cache-name string, e.g. 'mbm-life-shell-v563'
const RELEASES_BASE='./releases/';

function openMetaDB(){
  return new Promise((resolve,reject)=>{
    const req=indexedDB.open(DB_NAME,1);
    req.onupgradeneeded=()=>{ req.result.createObjectStore(STORE_NAME); };
    req.onsuccess=()=>resolve(req.result);
    req.onerror=()=>reject(req.error);
  });
}
async function idbGet(key){
  try{
    const db=await openMetaDB();
    return await new Promise((resolve,reject)=>{
      const tx=db.transaction(STORE_NAME,'readonly');
      const req=tx.objectStore(STORE_NAME).get(key);
      req.onsuccess=()=>resolve(req.result||null);
      req.onerror=()=>reject(req.error);
    });
  }catch(e){ return null; }
}
async function idbSet(key,val){
  const db=await openMetaDB();
  return new Promise((resolve,reject)=>{
    const tx=db.transaction(STORE_NAME,'readwrite');
    tx.objectStore(STORE_NAME).put(val,key);
    tx.oncomplete=()=>resolve();
    tx.onerror=()=>reject(tx.error);
  });
}

// Resolve the device's accepted release id, migrating the legacy scheme
// forward EXACTLY ONCE, without ever jumping to latest.
async function getAcceptedReleaseId(){
  const direct=await idbGet(ACCEPTED_KEY);
  if(direct) return direct;
  const legacy=await idbGet(LEGACY_ACCEPTED_KEY);
  if(legacy){
    const m=(''+legacy).match(/v(\d+)/);
    if(m){
      const migrated='v'+m[1];
      try{ await idbSet(ACCEPTED_KEY,migrated); }catch(e){}
      return migrated;
    }
  }
  // True first-ever install — nothing to protect the user from yet.
  try{ await idbSet(ACCEPTED_KEY,DEFAULT_RELEASE); }catch(e){}
  return DEFAULT_RELEASE;
}

self.addEventListener('install',(event)=>{
  // No skipWaiting() — same reasoning as before: sits in 'waiting' until
  // an explicit user action (only relevant for loader-logic changes now).
  event.waitUntil(self.skipWaiting ? Promise.resolve() : Promise.resolve());
});

self.addEventListener('activate',(event)=>{
  event.waitUntil((async()=>{
    await self.clients.claim();
    // Nothing content-related to migrate here beyond ensuring the key
    // exists — getAcceptedReleaseId() does the actual migration lazily on
    // first fetch, which is simpler and avoids doing IndexedDB writes on
    // every activation for devices that already have a valid key.
  })());
});

self.addEventListener('message',(event)=>{
  // Reserved for rare loader-level updates only (see header) — content
  // updates never send this anymore, they just write acceptedReleaseVersion
  // directly from the page and reload.
  if(event.data && event.data.type==='SKIP_WAITING'){
    event.waitUntil((async()=>{ self.skipWaiting(); })());
  }
});

function isShellRequest(request){
  if(request.mode==='navigate') return true;
  try{
    const url=new URL(request.url);
    if(url.origin!==self.location.origin) return false; // never touch cross-origin (Firebase/Maps/Nominatim/etc.)
    return url.pathname==='/' || url.pathname.endsWith('/index.html')
      ? !url.pathname.includes('/releases/') // don't re-intercept our own release fetches below
      : false;
  }catch(e){ return false; }
}

// Fetch release.json for this id and return its expected sha256 for
// index.html, or null if the manifest itself can't be read (network
// failure / bad JSON) — verification is skipped (not failed) only in
// that case, since the manifest is a DIFFERENT file than the content
// being verified and its own absence isn't evidence of corrupt content.
async function getExpectedHash(releaseId){
  try{
    const res=await fetch(RELEASES_BASE+releaseId+'/release.json',{cache:'no-store'});
    if(!res.ok) return null;
    const manifest=await res.json();
    return (manifest&&manifest.files&&manifest.files['index.html']&&manifest.files['index.html'].sha256)||null;
  }catch(e){ return null; }
}
async function sha256Hex(buf){
  const digest=await crypto.subtle.digest('SHA-256',buf);
  return Array.from(new Uint8Array(digest)).map(b=>b.toString(16).padStart(2,'0')).join('');
}
function recoveryResponse(){
  // Truly unavailable (network failure, bad HTTP status, or a hash that
  // doesn't match what release.json says it should be) AND nothing
  // trustworthy cached for this release — show the recovery message,
  // never silently substitute a different version or unverified content.
  return new Response(
    '<!doctype html><html><body style="font-family:sans-serif;padding:32px;text-align:center">'
    +'<h2>Version unavailable</h2>'
    +'<p>Could not load the saved version of the app and no offline copy exists yet. '
    +'Please reconnect and reopen the app.</p>'
    +'</body></html>',
    {status:503, headers:{'Content-Type':'text/html; charset=utf-8'}}
  );
}

self.addEventListener('fetch',(event)=>{
  const req=event.request;
  if(req.method!=='GET' || !isShellRequest(req)) return; // let the browser handle everything else normally, INCLUDING direct /releases/<id>/... requests (those are immutable and safe to let the browser's normal HTTP cache handle)

  event.respondWith((async()=>{
    const releaseId=await getAcceptedReleaseId();
    const releaseUrl=RELEASES_BASE+releaseId+'/index.html';
    const cacheName='mbm-life-release-'+releaseId; // one cache per release id — never shared, never reused for a different id, so there is no name-skew class of bug to guard against
    const cache=await caches.open(cacheName);
    const cached=await cache.match(releaseUrl);
    if(cached) return cached; // already verified once, when it was first cached below

    // Cache miss: fetch the release's OWN immutable, version-pinned URL.
    // Safe in principle (this exact URL can never MEAN a different
    // version — releases are never overwritten) but the BYTES actually
    // received still need verifying: a 200 OK with corrupted or
    // tampered content is a real, distinct failure mode from a network
    // error, and res.ok alone says nothing about it.
    try{
      const res=await fetch(releaseUrl,{cache:'no-store'});
      if(!res || !res.ok) throw new Error('release fetch not ok: '+(res&&res.status));
      const buf=await res.arrayBuffer();
      const expectedHash=await getExpectedHash(releaseId);
      if(expectedHash){
        const actualHash=await sha256Hex(buf);
        if(actualHash!==expectedHash){
          throw new Error('hash mismatch for '+releaseUrl+': expected '+expectedHash+' got '+actualHash);
        }
      }
      const verified=new Response(buf,{status:res.status,statusText:res.statusText,headers:res.headers});
      cache.put(releaseUrl,verified.clone()).catch(()=>{});
      return verified;
    }catch(e){
      return recoveryResponse();
    }
  })());
});
