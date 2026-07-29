import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");
const start = html.indexOf("/* v388 stale driving helpers:start");
const endMarker = "/* v388 stale driving helpers:end */";
const end = html.indexOf(endMarker, start);
assert.ok(start >= 0 && end > start, "v388 session freshness helpers must exist");

const helperSource = html.slice(start, end + endMarker.length);
const helpers = Function(`${helperSource}
  return {sessionTimeMs, sessionEvidenceAtMs, sessionIsLive,
    ACTIVE_SESSION_FRESH_MS, ACTIVE_SESSION_FUTURE_TOLERANCE_MS};`)();

const now = Date.parse("2026-07-29T12:00:00.000Z");
const iso = offsetMs => new Date(now + offsetMs).toISOString();

assert.equal(
  helpers.sessionIsLive({status: "active", updatedAt: iso(-30_000)}, null, now),
  true,
  "a recently updated active session is live"
);
assert.equal(
  helpers.sessionIsLive({status: "active", updatedAt: iso(-3 * 60_000)}, null, now),
  false,
  "an active Firestore document older than the freshness window is stale"
);
assert.equal(
  helpers.sessionIsLive(
    {status: "active", updatedAt: iso(-10 * 60_000)},
    {samples: [{t: iso(-5_000)}]},
    now
  ),
  true,
  "a fresh accepted queued sample keeps a genuine trip live"
);
assert.equal(
  helpers.sessionIsLive({status: "ended", updatedAt: iso(-5_000)}, null, now),
  false,
  "an ended session is never live"
);
assert.equal(
  helpers.sessionIsLive({status: "active", updatedAt: iso(2 * 60_000)}, null, now),
  false,
  "an implausibly future-dated session is rejected"
);

const classifierStart = html.indexOf("function famClassifyRawActivity");
const classifierEnd = html.indexOf("/* Derive (and update", classifierStart);
const classifier = html.slice(classifierStart, classifierEnd);
assert.ok(
  classifier.indexOf("nativeState") < classifier.indexOf("activeSession(m.uid)"),
  "native persisted movement state must be checked before cloud trip state"
);
assert.ok(
  !/activeSession\([^)]*\)\s*\|\|[\s\S]{0,100}status\s*===?\s*['\"]active['\"]/.test(html),
  "no UI path may bypass the central active-session freshness check"
);

console.log("stale-driving-session tests passed");
