import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");
const camStart = html.indexOf("const FamilyMapCamera=(function(){");
assert.ok(camStart >= 0, "FamilyMapCamera must exist");
const camEnd = html.indexOf("\n})();", camStart) + "\n})();".length;
const camSrc = html.slice(camStart, camEnd);

assert.match(camSrc, /function requestDriving\(/);
assert.match(camSrc, /function drivingZoomFor\(/);
assert.match(camSrc, /MBMMap\.cancelCameraAnimation\(\)/);
assert.doesNotMatch(
  camSrc,
  /apply\(follow\)[\s\S]{0,80}if\(follow\)\s*apply\(true\)/,
  "requestDriving must not issue two apply calls for one fix"
);

const { drivingZoomFor } = Function(
  `${camSrc}\nreturn {drivingZoomFor:FamilyMapCamera.drivingZoomFor};`
)();
assert.equal(drivingZoomFor(1, 8), 18);
assert.equal(drivingZoomFor(8, 8), 17);
assert.equal(drivingZoomFor(15, 8), 16);
assert.equal(drivingZoomFor(30, 8), 15);
assert.equal(drivingZoomFor(1, 50), 15);

console.log("driving-camera tests passed");
