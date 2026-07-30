import assert from 'node:assert/strict';
import fs from 'node:fs';

const html=fs.readFileSync(new URL('../index.html',import.meta.url),'utf8');
const rules=fs.readFileSync(new URL('../firestore.rules',import.meta.url),'utf8');
const gradle=fs.readFileSync(new URL('../android-app/app/build.gradle.kts',import.meta.url),'utf8');
const prefs=fs.readFileSync(new URL('../android-app/app/src/main/java/com/mbmlife/companion/data/TrackingPreferences.kt',import.meta.url),'utf8');
const service=fs.readFileSync(new URL('../android-app/app/src/main/java/com/mbmlife/companion/tracking/TrackingService.kt',import.meta.url),'utf8');

assert.match(html,/const APP_VERSION='v405'/);
assert.match(gradle,/versionCode = 14/);
assert.match(gradle,/0\.7\.5-v405-family-circles/);
assert.match(gradle,/asset=v405/);

assert.match(html,/familyLinks/);
assert.match(html,/function switchFamily/);
assert.match(html,/famView='circles'/);
assert.match(html,/circle-select/);
assert.match(html,/circle-create/);
assert.match(html,/This Circle will be added and selected/);
assert.doesNotMatch(html,/Leave your current family first to join this one/);

assert.match(html,/familyName:\(_current&&_current\.name\)/);
assert.match(html,/batch\.update\(famRef\(fid\),patch\)/);
assert.match(html,/inviteCode:code/);
assert.match(rules,/getAfter\(invitePath\)\.data\.acceptedBy == me\(\)/);
assert.match(rules,/match \/familyInvites\/\{code\}/);
assert.match(rules,/request\.resource\.data\.acceptedBy == me\(\)/);

assert.match(html,/function famBattLevel/);
assert.match(html,/Number\(pct\)<=60/);
assert.match(html,/FAM_LOW_BATTERY_PCT=15/);
assert.match(html,/batteryCharging/);
assert.match(html,/famBatteryBolt/);
assert.match(prefs,/var batteryCharging: Boolean/);
assert.match(service,/BatteryManager\.BATTERY_STATUS_CHARGING/);

assert.match(html,/scheduleAutomaticUpdateCheck\(true\)/);
assert.match(html,/UPDATE_RESUME_THROTTLE_MS=5\*60\*1000/);
assert.match(html,/function appVersionDisplay\(\)\{\s*return APP_VERSION;/);

assert.match(html,/function famSafetyView/);
assert.match(html,/function famMembershipView/);
assert.match(html,/Pricing and payments are not active yet/);
assert.match(html,/case 'nav-membership': famView='membership'/);

console.log('v405 integrated family, battery, update, safety and membership tests passed');
