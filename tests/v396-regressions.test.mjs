import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");
const mainActivity = fs.readFileSync(
  new URL(
    "../android-app/app/src/main/java/com/mbmlife/companion/MainActivity.kt",
    import.meta.url
  ),
  "utf8"
);

assert.doesNotMatch(
  mainActivity,
  /app\.preferences\.trackingEnabled\s*->\s*return/,
  "a persisted preference must not prevent foreground-service reconciliation"
);
assert.match(
  mainActivity,
  /Always reconcile the real[\s\S]{0,700}!TrackingService\.isRunning[\s\S]{0,160}startForegroundService/,
  "opening the app must reconcile the real native tracking service"
);
assert.match(
  mainActivity,
  /replay of Room's last accepted sample[\s\S]{0,500}sample\.capturedAtMs/,
  "a replayed Room sample must preserve its real freshness timestamp"
);

assert.match(
  html,
  /prev\.speedKph!==meta\.speedKph/,
  "a new live speed must refresh the visible marker activity badge"
);
assert.match(
  html,
  /_staleRecoveryIds\.has\(sess\.id\)[\s\S]{0,700}sess\.status='ended'/,
  "a Firestore echo must not resurrect an already recovered active trip"
);

const start = html.indexOf("function drvSanitizeRouteSamples");
const end = html.indexOf("function drvTripPaintRoute", start);
assert.ok(start >= 0 && end > start, "Trip Detail route sanitizer must exist");

const sanitizerSource = html.slice(start, end);
const famHaversineM = (lat1, lng1, lat2, lng2) => {
  const R = 6371000;
  const toR = value => (value * Math.PI) / 180;
  const dLat = toR(lat2 - lat1);
  const dLng = toR(lng2 - lng1);
  const value =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toR(lat1)) *
      Math.cos(toR(lat2)) *
      Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(value)));
};
const sanitize = Function(
  "famHaversineM",
  `${sanitizerSource}; return drvSanitizeRouteSamples;`
)(famHaversineM);

const route = sanitize([
  {
    lat: -42.882,
    lng: 147.325,
    t: "2026-07-29T08:00:00.000Z",
    accuracyM: 5,
    accepted: true
  },
  {
    lat: 0,
    lng: 0,
    t: "2026-07-29T08:00:05.000Z",
    accuracyM: 5,
    accepted: true
  },
  {
    lat: -42.8815,
    lng: 147.326,
    t: "2026-07-29T08:00:10.000Z",
    accuracyM: 5,
    accepted: true
  }
]);

assert.equal(route.length, 2, "an impossible ocean jump must be removed");
assert.deepEqual(
  route.map(point => [point.lat, point.lng]),
  [
    [-42.882, 147.325],
    [-42.8815, 147.326]
  ],
  "valid road-level points must remain unchanged"
);

console.log("v396 regression tests passed");
