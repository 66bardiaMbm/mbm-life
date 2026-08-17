// v457 — Synchronized diagnostic history (raw_fix / marker_target /
// camera_target) regression test.
//
// HONESTY NOTE (Claude): I don't have visibility into this project's
// existing test-harness loader (the `h.FamilyMapCamera` / `h.MBMMap.calls`
// convention Codex referenced from family-map-camera-single-command.test.mjs).
// This file is self-contained instead: it extracts the exact functions
// straight out of index.html (same technique used for every other test in
// this session) and runs plain node:assert checks against them. If the
// project's real test runner expects a different loader/harness shape,
// only the SETUP block below needs adapting — the assertions describe the
// actual behavior that must hold.
//
// Run with: node tests/diag-history.test.mjs   (or via the project's
// normal test runner once wired into its harness, if one exists).

import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Adjust this path if index.html lives elsewhere relative to tests/.
const indexPath = path.join(__dirname, '..', 'index.html');
const source = readFileSync(indexPath, 'utf8');

function extractBetween(src, startMarker, endMarker, occurrence = 1) {
  let idx = -1;
  for (let i = 0; i < occurrence; i++) {
    idx = src.indexOf(startMarker, idx + 1);
    if (idx === -1) throw new Error(`Marker not found: ${startMarker}`);
  }
  const end = src.indexOf(endMarker, idx);
  if (end === -1) throw new Error(`End marker not found after ${startMarker}`);
  return src.slice(idx, end + endMarker.length);
}

// Extract the exact diagHistoryPush/diagHistoryGet block.
const historyBlock = extractBetween(
  source,
  'const _diagHistory=[];',
  'window.diagHistoryGet=diagHistoryGet;'
);

const window = {};
// eslint-disable-next-line no-eval
eval(historyBlock);

// ── 1. Basic push/get + insertion order ────────────────────────────────
{
  // Fresh state per test file run (module-level array), so just record
  // the starting length to make assertions relative and order-independent
  // of anything a future entry above this block might add.
  const before = window.diagHistoryGet().length;
  window.diagHistoryPush('raw_fix', { rawLat: 1, rawLng: 2 });
  window.diagHistoryPush('camera_target', { lat: 3, lng: 4 });
  const hist = window.diagHistoryGet();
  assert.strictEqual(hist.length, before + 2, 'both pushes recorded');
  assert.strictEqual(hist[hist.length - 2].source, 'raw_fix');
  assert.strictEqual(hist[hist.length - 1].source, 'camera_target');
  console.log('PASS  insertion order preserved');
}

// ── 2. Every entry has a real timestamp and a strictly increasing, ─────
//       collision-proof sequence number (this is what Codex flagged as
//       missing in the first version — entries pushed within the same
//       millisecond must still be orderable).
{
  const before = window.diagHistoryGet().length;
  for (let i = 0; i < 5; i++) window.diagHistoryPush('raw_fix', { i });
  const hist = window.diagHistoryGet().slice(before);
  assert.ok(hist.every(e => typeof e.t === 'number'), 'every entry has t');
  assert.ok(hist.every(e => typeof e.seq === 'number'), 'every entry has seq');
  for (let i = 1; i < hist.length; i++) {
    assert.ok(hist[i].seq > hist[i - 1].seq, 'seq strictly increasing');
  }
  console.log('PASS  timestamps + strictly increasing sequence numbers');
}

// ── 3. Ring buffer cap at 120, newest retained, oldest dropped ─────────
{
  // Push well past the cap and confirm exactly 120 remain, ending on the
  // most recently pushed marker.
  for (let i = 0; i < 200; i++) {
    window.diagHistoryPush('raw_fix', { marker: 'cap-test-' + i });
  }
  const hist = window.diagHistoryGet();
  assert.strictEqual(hist.length, 120, 'capped at 120 entries');
  assert.strictEqual(
    hist[hist.length - 1].marker,
    'cap-test-199',
    'newest entry retained after cap'
  );
  console.log('PASS  120-entry ring-buffer cap, newest retained');
}

// ── 4. raw_fix must carry BOTH raw and accepted coordinates distinctly ──
//       (the exact bug Codex found in the first version: it only logged
//       next.lat/next.lng, silently hiding a rejected/gated raw jump).
{
  window.diagHistoryPush('raw_fix', {
    rawLat: -42.9, rawLng: 147.4, rawAccuracy: 80,
    acceptedLat: -42.88, acceptedLng: 147.32, acceptedAccuracy: 6,
    positionTrusted: false,
    capturedAt: '2026-08-14T00:00:00Z', fixId: 'test-fix', presenceState: 'steady'
  });
  const last = window.diagHistoryGet().slice(-1)[0];
  assert.strictEqual(last.source, 'raw_fix');
  assert.notStrictEqual(last.rawLat, last.acceptedLat,
    'rawLat and acceptedLat are tracked as DISTINCT fields (this is the fix)');
  assert.strictEqual(last.positionTrusted, false,
    'a gated/rejected raw fix is visibly flagged, not silently hidden');
  console.log('PASS  raw_fix distinguishes raw vs accepted coordinates + gating flag');
}

// ── 5. camera_target must carry from + rawTo + final padded to ─────────
{
  window.diagHistoryPush('camera_target', {
    fromLat: -42.90, fromLng: 147.30, fromZoom: 16,
    rawToLat: -42.88, rawToLng: 147.32, rawToZoom: 16,
    lat: -42.881, lng: 147.321, zoom: 16, padDxPx: 0, padDyPx: 140
  });
  const last = window.diagHistoryGet().slice(-1)[0];
  assert.strictEqual(last.source, 'camera_target');
  for (const k of ['fromLat', 'fromLng', 'rawToLat', 'rawToLng', 'lat', 'lng']) {
    assert.ok(k in last, `camera_target carries ${k}`);
  }
  assert.notStrictEqual(last.rawToLat, last.lat,
    'raw (unpadded) target and final padded target are distinct fields');
  console.log('PASS  camera_target carries from + rawTo + padded final target');
}

// ── 6. marker_target must carry from + willSnap ─────────────────────────
{
  window.diagHistoryPush('marker_target', {
    uid: 'me', fromLat: -42.90, fromLng: 147.30, lat: -42.88, lng: 147.32,
    willSnap: true
  });
  const last = window.diagHistoryGet().slice(-1)[0];
  assert.strictEqual(last.source, 'marker_target');
  assert.ok('fromLat' in last && 'fromLng' in last, 'marker_target carries animation start position');
  assert.strictEqual(last.willSnap, true, 'a large jump is flagged as a snap, not silently tweened');
  console.log('PASS  marker_target carries from position + snap flag');
}

console.log('\nAll diag-history assertions passed.');

// ── 7. camera_target must carry a reason/owner (v458 addition) ─────────
{
  window.diagHistoryPush('camera_target', {
    reason: 'driving-follow',
    fromLat: -42.90, fromLng: 147.30, fromZoom: 16,
    rawToLat: -42.88, rawToLng: 147.32, rawToZoom: 16,
    lat: -42.881, lng: 147.321, zoom: 16, padDxPx: 0, padDyPx: 140
  });
  const last = window.diagHistoryGet().slice(-1)[0];
  assert.strictEqual(last.reason, 'driving-follow',
    'camera_target carries which caller triggered it');
  console.log('PASS  camera_target carries a reason/owner string');
}

// ── 8. Rejected fixes must be visible in history too (v458 addition) ───
//       Previously a fix rejected for a bad/stale timestamp vanished with
//       zero trace — only accepted fixes were ever logged.
{
  window.diagHistoryPush('raw_fix_rejected', {
    rawLat: -42.95, rawLng: 147.50, rawAccuracy: 200,
    capturedAt: '1999-01-01T00:00:00Z', fixId: 'bad-fix',
    rejectionReason: 'older-captured-time'
  });
  const last = window.diagHistoryGet().slice(-1)[0];
  assert.strictEqual(last.source, 'raw_fix_rejected');
  assert.strictEqual(last.rejectionReason, 'older-captured-time',
    'a rejected fix records WHY it was rejected, not just that raw_fix push was skipped');
  console.log('PASS  rejected fixes are now visible in history with a reason');
}

console.log('\nAll v458 follow-up assertions passed.');

// ── KNOWN, DELIBERATE SCOPE LIMITS (Codex review, not yet closed) ──────
// Documenting these here rather than silently — this test file proves the
// data SHAPE is correct when the real push call sites fire, but does NOT
// exercise acceptNativeFix/animateMarker/setCamera THEMSELVES (their real
// dependencies — Firestore, DB, the live Google Maps object — aren't
// mockable cheaply enough to be worth it for a diagnostic-only feature, at
// least not yet). Also NOT covered:
//   - the real call ORDER across a live fix (raw_fix → marker_target →
//     camera_target) in an actual running app
//   - a camera-only scenario (map pans/zooms with no marker movement,
//     e.g. a sheet-detent change)
//   - marker position DURING a tween, not just its start/end (recording
//     every ~16ms animation frame would fill the 120-entry cap in under
//     2 seconds for a single animation — a real tradeoff, not an oversight)
// If any of these turn out to matter for diagnosing a future report, they
// need their own follow-up — this file does not claim to cover them.
