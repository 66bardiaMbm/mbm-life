import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");

assert.match(
  html,
  /if\(members\.length\)\{\s*h\+=`<div class="fam-presence">`;/,
  "Location must render the member roster even for a one-person family"
);
assert.doesNotMatch(
  html,
  /else if\(members\.length===1\)/,
  "a one-person family must not be replaced by only the Add person card"
);
assert.match(
  html,
  /type="button" data-fam="drv-open-member-history"/,
  "each Driving member report must be a single explicit button"
);
assert.match(
  html,
  /function drvTripPaintRoute\(\)/,
  "Trip Detail must have one route painter"
);
assert.match(
  html,
  /famView==='drivingTrip'[\s\S]{0,120}drvTripPaintRoute\(\)/,
  "the route must repaint after the asynchronous map SDK becomes ready"
);
assert.match(
  html,
  /Detailed route was not stored for this older trip/,
  "older trips without samples must show an honest route-data state"
);

console.log("family-driving-ui tests passed");
