import fs from 'node:fs';
import assert from 'node:assert/strict';

const html=fs.readFileSync(new URL('../index.html',import.meta.url),'utf8');

assert.match(html,/const APP_VERSION='v405'/);
assert.match(html,/const FAM_LOW_BATTERY_PCT=15/);
assert.match(html,/if\(Number\(pct\)<=FAM_LOW_BATTERY_PCT\)return 'low'/);
assert.match(html,/if\(Number\(pct\)<=60\)return 'mid'/);
assert.match(html,/fam-batt-pill\.lvl-high\{color:#2fce6b\}/);
assert.match(html,/fam-batt-pill\.lvl-mid\{color:#f5a623\}/);
assert.match(html,/fam-batt-pill\.lvl-low\{color:#e5484d\}/);
assert.match(html,/if\(previous&&previous\.low\)return/);
assert.match(html,/if\(pct>FAM_LOW_BATTERY_PCT\)/);
assert.match(html,/family-low-battery-\$\{member\.uid\}/);
assert.match(html,/شارژ گوشی \$\{name\} رو به اتمام است/);
assert.match(html,/withFixList\.forEach\(famMaybeAlertLowBattery\)/);

const start=html.indexOf('const FAM_LOW_BATTERY_PCT=15;');
const end=html.indexOf('/* end family low-battery policy */',start);
assert.ok(start>=0&&end>start,'battery implementation must be extractable');
assert.ok(
  start<html.indexOf('const MBMMap = (function(){'),
  'battery policy must be global so Family UI renderers can call it'
);
const source=html.slice(start,end);
const storage=new Map();
const localStorage={
  getItem:(key)=>storage.has(key)?storage.get(key):null,
  setItem:(key,value)=>storage.set(key,String(value))
};
const messages=[];
const api=new Function(
  'localStorage','Notification','document','toast','L',
  `${source}; return {famBattLevel,famMaybeAlertLowBattery};`
)(localStorage,undefined,{visibilityState:'visible'},(msg)=>messages.push(msg),'en');

assert.equal(api.famBattLevel(100),'high');
assert.equal(api.famBattLevel(61),'high');
assert.equal(api.famBattLevel(60),'mid');
assert.equal(api.famBattLevel(16),'mid');
assert.equal(api.famBattLevel(15),'low');
assert.equal(api.famBattLevel(0),'low');

const member=(pct)=>({
  uid:'person-1',name:'Bahman',consent:{enabled:true,paused:false},
  state:{battery:pct}
});
api.famMaybeAlertLowBattery(member(15));
api.famMaybeAlertLowBattery(member(14));
assert.equal(messages.length,1,'one low-battery episode must warn only once');
assert.match(messages[0],/Bahman.*15%/);
api.famMaybeAlertLowBattery(member(16)); // recharge above threshold re-arms
api.famMaybeAlertLowBattery(member(15));
assert.equal(messages.length,2,'a later low-battery episode must warn again');

console.log('v403 low-battery regression tests passed');
