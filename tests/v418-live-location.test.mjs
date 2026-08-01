import fs from 'node:fs';
import assert from 'node:assert/strict';

const html=fs.readFileSync(new URL('../index.html',import.meta.url),'utf8');
const service=fs.readFileSync(new URL('../android-app/app/src/main/java/com/mbmlife/companion/tracking/TrackingService.kt',import.meta.url),'utf8');
const activity=fs.readFileSync(new URL('../android-app/app/src/main/java/com/mbmlife/companion/MainActivity.kt',import.meta.url),'utf8');
const movement=fs.readFileSync(new URL('../android-app/app/src/main/java/com/mbmlife/companion/engine/MovementStateDetector.kt',import.meta.url),'utf8');
const gradle=fs.readFileSync(new URL('../android-app/app/build.gradle.kts',import.meta.url),'utf8');

assert.match(html,/const APP_VERSION='v418'/);
assert.match(gradle,/versionName = "0\.7\.6-v418-live-location"/);

// App launch/first fix must not invent an arrival.
assert.doesNotMatch(activity,/next\.stayStart\s*=\s*nativeFix\.capturedAt/);
assert.doesNotMatch(service,/stayStartAtMs\s*=\s*\n?\s*movementStartedAt/);
assert.doesNotMatch(service,/stayStartAtMs\s*=\s*capturedAt/);
assert.match(html,/state:'unconfirmed'/);
assert.match(html,/Presence unconfirmed/);
assert.doesNotMatch(html.slice(html.indexOf('function classifyMovement'),html.indexOf('local cache accessors')),/stayStart:presence0\.confirmSince/);

// Live telemetry must not be batched or held behind persistence/classification.
assert.doesNotMatch(service,/setMaxUpdateDelayMillis/);
assert.match(service,/Channel<QueuedLocation>\(Channel\.CONFLATED\)/);
const process=service.slice(service.indexOf('private suspend fun processLocation'),service.indexOf('private fun broadcastForWebView'));
assert.ok(process.indexOf('broadcastForWebView(')<process.indexOf('repository.persist(stableOutput)'));
assert.match(activity,/FamilyBackend\.acceptNativeFix\(nativeFix\)/);
assert.match(html,/function acceptNativeFix\(raw\)/);
assert.match(html,/non-monotonic-sequence/);
assert.match(html,/markerRenderAtMs/);
assert.match(html,/speedRenderAtMs/);

// Movement needs accuracy-aware, consecutive evidence.
assert.match(movement,/credibleNetThreshold/);
assert.match(movement,/WALK_CONFIRM_SAMPLES = 3/);
assert.match(movement,/STATIONARY_CONFIRM_SAMPLES = 4/);

// Production UI has no reachable debug-overlay controls.
assert.doesNotMatch(html,/data-fam="drv-debug-tap"/);
assert.doesNotMatch(html,/id="drv-debug-overlay-wrap"/);

// Android photo selection uses the system document picker; web input remains a real user-gesture target.
assert.match(activity,/Intent\(Intent\.ACTION_OPEN_DOCUMENT\)/);
assert.match(html,/for="fam-photo-file-sheet"/);
assert.doesNotMatch(html,/id="fam-photo-file-sheet"[^>]*display:none/);

// A newer geocode request for the same cell is retained, not merely used to invalidate the older response.
assert.match(html,/const superseding = \{\}/);
assert.match(html,/superseding\[k\]=\{key:k,lat,lng,fixId:resolvedFixId\}/);

console.log('v418 live-location regression tests passed');
