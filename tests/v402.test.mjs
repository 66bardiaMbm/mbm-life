import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");
const trackingService = fs.readFileSync(
  new URL(
    "../android-app/app/src/main/java/com/mbmlife/companion/tracking/TrackingService.kt",
    import.meta.url
  ),
  "utf8"
);
const repository = fs.readFileSync(
  new URL(
    "../android-app/app/src/main/java/com/mbmlife/companion/data/TrackingRepository.kt",
    import.meta.url
  ),
  "utf8"
);

assert.match(html, /const APP_VERSION='v405'/);

assert.match(
  html,
  /\.fam-trip-vehicle-icon svg\{[^}]*stroke:currentColor;fill:none;/,
  "Trip History car icons must render as visible line icons, not black SVG fills."
);
assert.match(
  html,
  /class="fam-trip-vehicle-icon\$\{isActive\?' active':''\}"/,
  "Every Trip History row must use the styled vehicle icon."
);

const markerPresenterStart = html.indexOf("function famMarkerCalloutText(txt)");
const markerPresenterEnd = html.indexOf("function famLiveStatusLine", markerPresenterStart);
const markerPresenter = html.slice(markerPresenterStart, markerPresenterEnd);
assert.match(
  markerPresenter,
  /txt\.activityType==='stationary'[\s\S]*txt\.calloutL2\|\|txt\.l2/,
  "Stationary marker must display the Here-for value derived from the shared arrival state."
);
assert.match(
  markerPresenter,
  /return \{l1:'',l2:''\};/,
  "Moving markers must not render a duplicate text callout."
);

assert.doesNotMatch(
  trackingService,
  /setMinUpdateDistanceMeters/,
  "Native foreground tracking must not starve stationary freshness behind a distance gate."
);
assert.match(trackingService, /\.setMaxUpdateDelayMillis\(10_000L\)/);

for (const state of ["walking", "bicycling", "motorcycle", "driving"]) {
  assert.match(
    repository,
    new RegExp(`"${state}"`),
    `Firestore moving flag must understand ${state}.`
  );
}

for (const state of ["driving", "walking", "bicycling", "motorcycle"]) {
  assert.match(
    html,
    new RegExp(`${state}:`),
    `The map marker icon registry must support ${state}.`
  );
}

console.log("v402 targeted regression tests passed");
