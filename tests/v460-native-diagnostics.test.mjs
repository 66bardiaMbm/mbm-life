import assert from 'node:assert/strict';
import fs from 'node:fs';

const service = fs.readFileSync(
  'android-app/app/src/main/java/com/mbmlife/companion/tracking/TrackingService.kt',
  'utf8'
);
const activity = fs.readFileSync(
  'android-app/app/src/main/java/com/mbmlife/companion/MainActivity.kt',
  'utf8'
);
const movement = fs.readFileSync(
  'android-app/app/src/main/java/com/mbmlife/companion/engine/MovementStateDetector.kt',
  'utf8'
);
const driving = fs.readFileSync(
  'android-app/app/src/main/java/com/mbmlife/companion/engine/DrivingDetector.kt',
  'utf8'
);
const html = fs.readFileSync('index.html', 'utf8');

assert.match(service, /"fix_decision"/);
assert.match(service, /"timer_transition"/);
assert.match(service, /"activity_update"/);
for (const field of [
  'rawSpeedMps', 'filteredSpeedMps', 'fallbackSpeedMps',
  'distanceFromLastValidFixM', 'dtFromLastValidFixMs',
  'movementStateBefore', 'movementStateAfter', 'decisionReason',
  'activeTripIdBefore', 'activeTripIdAfter', 'tripTransition'
]) {
  assert.match(service, new RegExp(`\\.put\\("${field}"`), `missing native field ${field}`);
}
assert.match(movement, /fun diagnosticSnapshot\(\) = MovementDiagnosticSnapshot/);
assert.match(driving, /fun diagnosticSnapshot\(\) = DrivingDiagnosticSnapshot/);
assert.match(activity, /fun recentDecisionDiagnostics\(limit: Int\)/);
assert.match(html, /recentDecisionDiagnostics\(80\)/);
assert.match(html, /Native movement history/);
assert.match(html, /GEOCODE DIAG/);
assert.match(html, /const APP_VERSION='v460'/);
assert.match(html, /function hideSplash\(\)/);
assert.match(html, /<\/body>\s*<\/html>\s*$/);

console.log('PASS v460 native diagnostics wiring contract');
