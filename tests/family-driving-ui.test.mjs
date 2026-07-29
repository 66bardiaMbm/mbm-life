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
assert.doesNotMatch(
  html,
  /h\+=`<div style="font-size:14px;font-weight:800;margin:20px 2px 10px">\$\{L==='fa'\?'اعضا':'Members'\}/,
  "Driving must not render a Members section"
);
assert.match(
  html,
  /Member\/profile browsing belongs to Location/,
  "Driving must explicitly keep member browsing on Location"
);
assert.match(
  html,
  /const LIFE_STYLE=\[\];/,
  "the live map must keep Google's complete road-map context"
);
assert.match(
  html,
  /Android \$\{nativeVersion\}/,
  "Settings must show the actual native Android build separately"
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
