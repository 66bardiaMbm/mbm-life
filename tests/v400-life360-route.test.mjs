import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");
const repository = fs.readFileSync(
  new URL(
    "../android-app/app/src/main/java/com/mbmlife/companion/data/TrackingRepository.kt",
    import.meta.url
  ),
  "utf8"
);
const gradle = fs.readFileSync(
  new URL("../android-app/app/build.gradle.kts", import.meta.url),
  "utf8"
);

assert.match(html, /const APP_VERSION='v404'/);
assert.match(html, /viewport-fit=cover/);
assert.match(html, /height:30vh/);
assert.match(gradle, /versionName = "0\.7\.4-v404-native-invite-share"/);
assert.match(gradle, /asset=v404/);

assert.match(
  html,
  /const remote=normalizeSessionSamples\(await sessionSamplesRemote\(uid,sessionId\)\)/,
  "ended trips must read authoritative Android route chunks from Firestore"
);
assert.match(
  html,
  /if\(stored\.length<2\)\{[^}]*return remote;\s*\}/,
  "an empty or incomplete local cache must not suppress a valid remote route"
);
assert.match(
  html,
  /sample\.lat!=null\?sample\.lat:sample\.latitude/,
  "legacy native latitude/longitude sample shapes must be normalized"
);
assert.match(
  html,
  /if\(!t&&sample\.capturedAtMs!=null\)/,
  "legacy native millisecond timestamps must be normalized"
);
const tripMountStart = html.indexOf("function drvTripMount()");
const tripMountEnd = html.indexOf("function famLaterTile", tripMountStart);
const tripMount = html.slice(tripMountStart, tripMountEnd);
assert.doesNotMatch(
  tripMount,
  /:\[-42\.88,147\.32\]/,
  "Trip Detail must not initialize at a fabricated Hobart coordinate"
);
assert.match(
  html,
  /MBMMap\.fitAll\(samples\.map\(s=>\[s\.lat,s\.lng\]\)\)/,
  "Trip Detail must fit the real route without mutating the live follow controller"
);

assert.match(
  repository,
  /trip\.sampleCount % 25 == 0/,
  "native tracking must checkpoint active route chunks"
);
assert.match(repository, /\.put\("capturedAtMs", p\.capturedAtMs\)/);
assert.match(repository, /\.put\("accepted", p\.accepted\)/);

const composeStart = html.indexOf("function famComposeStatusText(m)");
const composeEnd = html.indexOf("function famLiveStatusLine", composeStart);
const compose = html.slice(composeStart, composeEnd);
assert.match(
  compose,
  /activityType:'driving', speedKph/,
  "driving speed must still be supplied to the marker activity pill"
);
assert.doesNotMatch(
  compose,
  /const l2=speedKph!=null/,
  "live speed must not be duplicated into the callout/detail text"
);
assert.doesNotMatch(
  html,
  /<div class="lbl">\$\{L==='fa'\?'وضعیت':'Status'\}<\/div><div class="val">\$\{L==='fa'\?'در حال رانندگی':'Driving'\}/,
  "the bottom sheet must not duplicate the marker's driving state"
);

console.log("Life360 route regression tests passed");
