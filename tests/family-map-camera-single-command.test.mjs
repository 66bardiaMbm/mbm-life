import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");

function extractBalancedFunction(src, signature) {
  const start = src.indexOf(signature);
  assert.ok(start >= 0, `${signature} must exist`);
  let depth = 0;
  let quote = null;
  let escaped = false;
  let lineComment = false;
  let blockComment = false;
  let seenBrace = false;
  for (let i = start; i < src.length; i++) {
    const ch = src[i], next = src[i + 1];
    if (lineComment) {
      if (ch === "\n") lineComment = false;
      continue;
    }
    if (blockComment) {
      if (ch === "*" && next === "/") { blockComment = false; i++; }
      continue;
    }
    if (quote) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === "/" && next === "/") { lineComment = true; i++; continue; }
    if (ch === "/" && next === "*") { blockComment = true; i++; continue; }
    if (ch === "'" || ch === '"' || ch === "`") { quote = ch; continue; }
    if (ch === "{") { depth++; seenBrace = true; }
    else if (ch === "}") {
      depth--;
      if (seenBrace && depth === 0) return src.slice(start, i + 1);
    }
  }
  throw new Error(`unterminated ${signature}`);
}

const paintSrc = extractBalancedFunction(html, "function famLivePaintMarkers(members, sel){");
const camStart = html.indexOf("const FamilyMapCamera=(function(){");
const camEnd = html.indexOf("\n})();", camStart) + "\n})();".length;
const camSrc = html.slice(camStart, camEnd);

function makeMap() {
  const calls = [];
  return {
    calls,
    get: () => ({ id: "map" }),
    setCamera: (opts) => calls.push({ type: "setCamera", opts }),
    setView: (center, zoom) => calls.push({ type: "setView", center, zoom }),
    fitAll: (points) => calls.push({ type: "fitAll", points }),
    setPadding: (padding) => calls.push({ type: "setPadding", padding }),
    cancelCameraAnimation: () => calls.push({ type: "cancel" }),
    setMarker: () => {},
    setClusterMarker: () => {},
    clearMarkersExcept: () => {},
    drawRoute: () => {},
    setAccuracy: () => {},
    setHeadingCone: () => {}
  };
}

function harness({ uid = "me", viewerUid = "me" } = {}) {
  const MBMMap = makeMap();
  const camFactory = new Function(
    "MBMMap", "Date", "setTimeout", "clearTimeout",
    `let _famFollow=false; ${camSrc}; return FamilyMapCamera;`
  );
  const FamilyMapCamera = camFactory(MBMMap, Date, setTimeout, clearTimeout);
  const active = { id: "trip_1" };
  const FB = { user: { uid: viewerUid } };
  const FamilyBackend = { driving: { activeSession: (id) => id === uid ? active : null } };
  const buildPaint = (lastCentredUid, follow) => new Function(
    "MBMMap", "FamilyMapCamera", "FB", "FamilyBackend",
    "esc", "famHaversineM", "famComposeStatusText", "famAccuracyInfo",
    "famLiveApply", "famEnsureSheetVisible",
    `
      let _famSelUid=${JSON.stringify(uid)};
      let _famLastCentredUid=${JSON.stringify(lastCentredUid)};
      let _famFollow=${follow ? "true" : "false"};
      ${paintSrc}
      return famLivePaintMarkers;
    `
  )(
    MBMMap, FamilyMapCamera, FB, FamilyBackend,
    (v) => v, () => 999999, () => ({ activityType: "driving", l1: "", l2: "" }),
    () => ({ approx: false }), () => {}, () => {}
  );
  const member = (lat, lng, speed = 12) => ({
    uid, hasFix: true, name: "Me", photo: null, color: "#8652ff",
    meta: { tone: "purple" }, consent: { enabled: true, paused: false },
    state: { lat, lng, moving: true, heading: 90, speed, accuracy: 8, battery: 90 }
  });
  return { MBMMap, FamilyMapCamera, active, buildPaint, member };
}

// A selected remote family member is followed too. The camera authority is
// the selected profile, not permanently the signed-in local user.
{
  const h = harness({ uid: "family_member", viewerUid: "me" });
  const remote = h.member(3.1, 4.2, 9);
  h.buildPaint(null, false)([remote], remote);
  const camera = h.MBMMap.calls.filter((c) => c.type === "setCamera" || c.type === "setView");
  assert.equal(camera.length, 1, "selecting a remote member must start follow with one camera command");
  assert.deepEqual(camera[0].opts?.center || camera[0].center, [3.1, 4.2]);
}

// Establish the active drive, then test a later accepted fix. This is the
// steady-state branch the earlier v393 test accidentally skipped.
{
  const h = harness();
  h.FamilyMapCamera.drivingState(h.active, [1, 2], 12, 8);
  h.MBMMap.calls.length = 0;
  const me = h.member(1.001, 2.001);
  h.buildPaint("me", true)([me], me);
  await new Promise((resolve) => setTimeout(resolve, 500));
  const camera = h.MBMMap.calls.filter((c) => c.type === "setCamera" || c.type === "setView");
  assert.equal(camera.length, 1, `one accepted driving fix must issue one camera command: ${JSON.stringify(h.MBMMap.calls)}`);
}

// A repeated poll with the exact same accepted fix must not restart animation.
{
  const h = harness();
  h.FamilyMapCamera.drivingState(h.active, [1, 2], 12, 8);
  h.MBMMap.calls.length = 0;
  const me = h.member(1, 2);
  h.buildPaint("me", true)([me], me);
  await new Promise((resolve) => setTimeout(resolve, 500));
  const camera = h.MBMMap.calls.filter((c) => c.type === "setCamera" || c.type === "setView");
  assert.equal(camera.length, 0, "an unchanged sample must not move the camera again");
}

// Manual drag pauses driving follow; recenter resumes it exactly once.
{
  const h = harness();
  h.FamilyMapCamera.drivingState(h.active, [1, 2], 12, 8);
  h.FamilyMapCamera.pauseByGesture();
  h.MBMMap.calls.length = 0;
  const me = h.member(1.002, 2.002);
  h.buildPaint("me", false)([me], me);
  await new Promise((resolve) => setTimeout(resolve, 500));
  assert.equal(
    h.MBMMap.calls.filter((c) => c.type === "setCamera" || c.type === "setView").length,
    0,
    "manual drag must pause camera movement"
  );
  h.MBMMap.calls.length = 0;
  h.FamilyMapCamera.resume([1.002, 2.002], 17);
  assert.equal(
    h.MBMMap.calls.filter((c) => c.type === "setCamera" || c.type === "setView").length,
    1,
    "recenter must resume with one camera command"
  );
}

// A gesture pause is temporary. Accepted fixes keep replacing lastTarget and
// the controller automatically resumes on the newest coordinate.
{
  const h = harness();
  h.FamilyMapCamera.drivingState(h.active, [1, 2], 12, 8);
  h.FamilyMapCamera.pauseByGesture();
  h.MBMMap.calls.length = 0;
  h.FamilyMapCamera.drivingState(h.active, [1.004, 2.004], 14, 7);
  assert.equal(
    h.MBMMap.calls.filter((c) => c.type === "setCamera" || c.type === "setView").length,
    0,
    "accepted fixes must not move the camera during the short gesture pause"
  );
  await new Promise((resolve) => setTimeout(resolve, 8200));
  const camera = h.MBMMap.calls.filter((c) => c.type === "setCamera" || c.type === "setView");
  assert.equal(camera.length, 1, "follow must automatically resume once after the gesture pause");
  assert.deepEqual(camera[0].opts?.center || camera[0].center, [1.004, 2.004]);
}

// Padding is submitted before the controller reframes its retained target.
{
  const h = harness();
  h.FamilyMapCamera.drivingState(h.active, [1, 2], 12, 8);
  h.MBMMap.calls.length = 0;
  h.FamilyMapCamera.setPadding({ top: 96, right: 64, bottom: 420, left: 12 });
  assert.equal(h.MBMMap.calls[0]?.type, "setPadding");
  assert.equal(h.MBMMap.calls[1]?.type, "setCamera");
}

// Ending the verified drive clears driving camera mode.
{
  const h = harness();
  h.FamilyMapCamera.drivingState(h.active, [1, 2], 12, 8);
  h.FamilyMapCamera.drivingState(null, null);
  assert.equal(h.FamilyMapCamera.state().drivingMode, false);
  assert.equal(h.FamilyMapCamera.state().activeDriveId, null);
}

console.log("family-map-camera-single-command tests passed");
