import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");

const drivingStart = html.indexOf("const FamilyDriving = (function(){");
const drivingSchema = html.indexOf("const SCHEMA_VERSION", drivingStart);
assert.ok(drivingStart >= 0 && drivingSchema > drivingStart, "FamilyDriving must exist");
assert.match(
  html.slice(drivingStart, drivingSchema),
  /function nowISO\(\)\{\s*return new Date\(\)\.toISOString\(\);\s*\}/,
  "FamilyDriving must own the timestamp helper used by stale-session recovery"
);

const clockStart = html.indexOf("function famAbsTime(iso)");
const clockEnd = html.indexOf("function famAccuracyInfo", clockStart);
const clockSource = html.slice(clockStart, clockEnd);
assert.match(clockSource, /hour12:false/, "Since times must explicitly use 24-hour time");
assert.match(clockSource, /hourCycle:'h23'/, "Since times must use the 00–23 hour cycle");

assert.match(
  html,
  /let _famFollow=true;/,
  "the selected member must be followed when Location opens"
);
assert.match(
  html,
  /if\(center\) FamilyMapCamera\.resume\(center,16\);/,
  "an asynchronously-created map must resume follow, not perform a one-off centre"
);
assert.match(
  html,
  /case 'nav-location':\{[\s\S]{0,260}_famSelUid=my;[\s\S]{0,160}_famFollow=true;/,
  "returning to Location must select and follow the signed-in member"
);

const sanitizerStart = html.indexOf("function drvSanitizeRouteSamples");
const sanitizerEnd = html.indexOf("function drvTripPaintRoute", sanitizerStart);
assert.ok(sanitizerStart >= 0 && sanitizerEnd > sanitizerStart, "route sanitizer must exist");
const sanitizerSource = html.slice(sanitizerStart, sanitizerEnd);
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

const cleaned = sanitize(
  [
    { lat: 0, lng: 0, t: "2026-07-29T08:00:00.000Z", accuracyM: 5 },
    { lat: -42.882, lng: 147.325, t: "2026-07-29T08:00:05.000Z", accuracyM: 5 },
    { lat: -42.8815, lng: 147.326, t: "2026-07-29T08:00:10.000Z", accuracyM: 5 }
  ],
  { distanceM: 27400 }
);
assert.deepEqual(
  cleaned.map(point => [point.lat, point.lng]),
  [
    [-42.882, 147.325],
    [-42.8815, 147.326]
  ],
  "a corrupted first coordinate must not anchor Trip Detail in the ocean"
);

const sparseCleaned = sanitize(
  [
    { lat: 0, lng: 0, t: "2026-07-29T08:00:00.000Z", accuracyM: 5 },
    { lat: -42.8815, lng: 147.326, t: "2026-07-29T08:00:10.000Z", accuracyM: 5 }
  ],
  { distanceM: 1000, endLat: -42.8815, endLng: 147.326 }
);
assert.deepEqual(
  sparseCleaned.map(point => [point.lat, point.lng]),
  [[-42.8815, 147.326]],
  "a sparse old trip must prefer its valid session endpoint over a 0,0 sample"
);

console.log("v397 critical hotfix tests passed");
