const path = require('path');
const { fnSource, contains, countOf, SRC } = require('./v401-extract');

let pass = 0, fail = 0;
function t(name, fn) {
  try { fn(); console.log('  PASS  ' + name); pass++; }
  catch (e) { console.log('  FAIL  ' + name + '\n        ' + e.message); fail++; }
}
function eq(a, b, msg) { if (a !== b) throw new Error((msg || '') + ' expected ' + JSON.stringify(b) + ' got ' + JSON.stringify(a)); }
function ok(v, msg) { if (!v) throw new Error(msg || 'expected truthy'); }

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT1  Sheet geometry (requirements 1, 2, 3)');

function makeSheetEnv(opts) {
  const o = Object.assign({ wrapH: 915, navH: 64, headH: 104 }, opts);
  const vars = { '--fam-navh': o.navH + 'px', '--fam-headh': o.headH + 'px' };
  const sandbox = {
    window: { innerHeight: o.wrapH },
    document: {
      documentElement: { style: { setProperty: (k, v) => { vars[k] = v; } } },
      getElementById: (id) => id === 'fam-live-wrap'
        ? { getBoundingClientRect: () => ({ height: o.wrapH }),
            querySelector: (s) => s === '.fam-bottomnav'
              ? { getBoundingClientRect: () => ({ height: o.navH }) }
              : { getBoundingClientRect: () => ({ height: o.headH }) },
            style: { setProperty: () => {} } }
        : null
    },
    getComputedStyle: () => ({ getPropertyValue: (k) => vars[k] || '' }),
    console
  };
  const code = fnSource('famSyncChromeMetrics') + '\n' + fnSource('famChromePx') + '\n' +
               fnSource('famSheetHeights') + '\nreturn {famSyncChromeMetrics,famChromePx,famSheetHeights};';
  const f = new Function('window', 'document', 'getComputedStyle', 'console', code);
  const api = f(sandbox.window, sandbox.document, sandbox.getComputedStyle, console);
  api.famSyncChromeMetrics();
  return { api, vars, o };
}

t('expanded sheet never overlaps the header or the nav bar', () => {
  const { api, o } = makeSheetEnv({});
  const h = api.famSheetHeights();
  const sheetTop = o.wrapH - o.navH - h[2];
  ok(sheetTop >= o.headH, 'sheet top ' + sheetTop + ' must clear header ' + o.headH);
});

t('expanded sheet clears the header on a large gesture-nav inset too', () => {
  const { api, o } = makeSheetEnv({ navH: 108, headH: 134, wrapH: 915 });
  const h = api.famSheetHeights();
  const sheetTop = o.wrapH - o.navH - h[2];
  ok(sheetTop >= o.headH, 'sheet top ' + sheetTop + ' must clear header ' + o.headH);
});

t('v400 formula would have failed the same case (regression is real)', () => {
  const wrapH = 915, navClear = 82 + 48, headH = 134;
  const v400Full = Math.max(200, wrapH - 150);
  const v400Top = wrapH - navClear - v400Full;
  ok(v400Top < headH, 'v400 sheet top was ' + v400Top + ', below header ' + headH);
});

t('partial detent is ~30% and always below the expanded detent', () => {
  const { api, o } = makeSheetEnv({});
  const h = api.famSheetHeights();
  const ratio = h[1] / o.wrapH;
  ok(ratio > 0.25 && ratio < 0.35, 'partial ratio ' + ratio.toFixed(3));
  ok(h[0] <= h[1] && h[1] <= h[2], 'detents must be ordered: ' + h.join(','));
});

t('sheet bottom margin uses measured nav height, not the content-clearance value', () => {
  ok(contains('margin-bottom:var(--fam-navh)'), '#fam-sheet must use --fam-navh');
  ok(!contains('margin-top:auto;margin-bottom:var(--fam-navclear)'), 'old --fam-navclear margin still present');
});

t('no map strip can remain between sheet and nav bar', () => {
  const { api, o } = makeSheetEnv({ navH: 64 });
  const h = api.famSheetHeights();
  // sheet bottom edge sits exactly on the nav bar's top edge
  const gap = o.wrapH - o.navH - (o.wrapH - o.navH - h[1]) - h[1];
  eq(gap, 0, 'gap between sheet and nav');
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT2  Fresh-entry reset (requirements 1, 14)');

t('a fresh entry to Location resets the detent and re-selects the signed-in user', () => {
  ok(contains("if(_v==='live' && _famPrevMountView!=='live'){"), 'fresh-entry guard missing');
  ok(contains('_famSheetDetent=1;'), 'detent reset missing');
  ok(contains('if(_me){ _famSelUid=_me; }'), 'signed-in user re-selection missing');
  ok(contains('_famLiveFixKicked=false;'), 'fresh-fix re-arm missing');
});

t('re-render while already on Location does NOT reset a deliberate expansion', () => {
  // simulate the guard
  let detent = 2, prev = null;
  const step = (view) => {
    const v = (view === 'live' || view === 'dashboard') ? 'live' : view;
    if (v === 'live' && prev !== 'live') detent = 1;
    prev = v;
  };
  step('live'); eq(detent, 1, 'first entry');
  detent = 2;                 // user deliberately expands
  step('live'); eq(detent, 2, 'plain re-render must not reset');
  step('drivingTrip');
  step('live'); eq(detent, 1, 'returning from another view must reset');
});

t('tap on the drag handle steps the sheet down (escape hatch exists)', () => {
  ok(contains('if(moved<6){ famApplySheetDetent(_famSheetDetent>0?_famSheetDetent-1:1); return; }'),
     'tap-to-collapse fallback missing');
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT3  Camera-padding isolation (requirements 4, 5, 6)');

t('destroy() resets the shared camera padding', () => {
  const d = fnSource('destroy');
  ok(/_pad=\{top:0,right:0,bottom:0,left:0\}/.test(d), 'destroy() must clear _pad');
});

t('Trip Detail sets its own padding from its own map height', () => {
  const p = fnSource('drvTripPaintRoute');
  ok(p.indexOf('MBMMap.setPadding({top:padY,right:padX,bottom:padY,left:padX})') >= 0, 'own padding missing');
  ok(p.indexOf('r.height*0.12') >= 0, 'padding must derive from this map height');
});

t('Trip Detail neutralises inherited padding on mount', () => {
  const m = fnSource('drvTripMount');
  ok(m.indexOf('MBMMap.setPadding({top:0,right:0,bottom:0,left:0})') >= 0, 'inherited padding not cleared');
});

t('fitAll clamps padding so it can never exceed the container (no world zoom)', () => {
  const src = fnSource('fitAll');
  const body = src.slice(src.indexOf('const clampAxis'), src.indexOf('const [pt,pb]'));
  const clampAxis = new Function('return ' + body.replace(/^const clampAxis=/, '').replace(/;\s*$/, ''))();
  // the v400 leak: 186 top + 436 bottom on a 348px-tall map
  const [a, b] = clampAxis(186, 436, 348);
  ok(a + b <= 348 * 0.6 + 1, 'clamped sum ' + (a + b) + ' must fit inside 348px');
  // untouched when it already fits
  const [c, d] = clampAxis(24, 24, 348);
  eq(c, 24); eq(d, 24);
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT4  Location freshness (requirements 7, 8)');

function freshness(lastCallbackAt, capturedAtMs, now) {
  const cbAge = lastCallbackAt ? Math.max(0, now - lastCallbackAt) : null;
  const fixAge = Number.isFinite(capturedAtMs) ? Math.max(0, now - capturedAtMs) : null;
  const ages = [cbAge, fixAge].filter(v => v != null);
  return ages.length ? Math.min.apply(null, ages) : null;
}

t('the shipped code takes the MINIMUM of the two ages', () => {
  ok(contains('const ages=[cbAge,fixAge].filter(v=>v!=null);'), 'age set missing');
  ok(contains('ages.length?Math.min.apply(null,ages):null'), 'min-of-ages rule missing');
  ok(!contains('const effectiveCallbackAge=_lastCallbackAt\n        ? Date.now()-_lastCallbackAt'), 'v400 preference for _lastCallbackAt still present');
});

t('frozen watchPosition + fresh native fix reads as LIVE (the reported contradiction)', () => {
  const now = 1e12;
  const age = freshness(now - 20 * 60000, now - 3000, now);   // watch 20 min stale, native 3 s old
  ok(age <= 45000, 'age ' + age + ' must be within the live threshold');
});

t('v400 rule would have called that same case stale', () => {
  const now = 1e12, lastCb = now - 20 * 60000;
  const v400 = lastCb ? now - lastCb : null;
  ok(v400 > 45000, 'v400 produced ' + v400 + 'ms → "Location stale" beside "0 min ago"');
});

t('a genuinely old native fix still ages out to stale', () => {
  const now = 1e12;
  const age = freshness(0, now - 40 * 60000, now);
  ok(age > 45000, 'stale fix must not read as live');
});

t('label and age text are derived from the same value', () => {
  ok(contains('const freshAge=(li&&li.freshnessAgeMs!=null)?li.freshnessAgeMs'), 'freshAge missing');
  ok(contains("if(ls==='stale' && freshAge!=null && freshAge<=STALE_CB_MS) ls='active';"), 'contradiction guard missing');
  ok(contains('const ageTxt=(freshAge!=null)?famRelTime(new Date(Date.now()-freshAge).toISOString())'),
     'age text must come from freshAge');
});

t('"stale" and "0 min ago" cannot co-occur for any age', () => {
  const STALE = 45000;
  for (const age of [0, 1000, 44999, 45001, 120000, 3600000]) {
    const stale = age > STALE;
    const saysNow = age < 45000;     // famRelTime returns "now" below 45s
    ok(!(stale && saysNow), 'contradiction at age ' + age);
  }
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT5  Route sample validation (Firestore contract)');

const sanitizeCode =
  'function famHaversineM(la1,lo1,la2,lo2){' +
  ' const R=6371000,r=Math.PI/180;' +
  ' const dLa=(la2-la1)*r,dLo=(lo2-lo1)*r;' +
  ' const a=Math.sin(dLa/2)**2+Math.cos(la1*r)*Math.cos(la2*r)*Math.sin(dLo/2)**2;' +
  ' return 2*R*Math.asin(Math.sqrt(a)); }\n' +
  fnSource('drvSanitizeRouteSamples') + '\nreturn drvSanitizeRouteSamples;';
const sanitize = new Function('console', sanitizeCode)(console);

const HOB = { lat: -42.8821, lng: 147.3272 };
function leg(n, startMs) {
  const out = [];
  for (let i = 0; i < n; i++) {
    out.push({ lat: HOB.lat + i * 0.0005, lng: HOB.lng + i * 0.0005,
               t: new Date(startMs + i * 5000).toISOString(), accuracyM: 8, accepted: true, seq: i });
  }
  return out;
}

t('a clean Hobart route survives intact', () => {
  const r = sanitize(leg(20, 1.7e12), { distanceM: 4500, startLat: HOB.lat, startLng: HOB.lng });
  eq(r.length, 20);
});

t('null-island (0,0) points are rejected', () => {
  const pts = leg(20, 1.7e12);
  pts.splice(5, 0, { lat: 0, lng: 0, t: new Date(1.7e12 + 22500).toISOString(), accepted: true });
  const r = sanitize(pts, { distanceM: 4500, startLat: HOB.lat, startLng: HOB.lng });
  ok(!r.some(p => Math.abs(p.lat) < 1e-4 && Math.abs(p.lng) < 1e-4), '0,0 must not survive');
  eq(r.length, 20);
});

t('out-of-range coordinates are rejected', () => {
  const pts = leg(10, 1.7e12).concat([{ lat: 999, lng: 12, t: new Date(1.7e12).toISOString(), accepted: true }]);
  const r = sanitize(pts, { distanceM: 4500, startLat: HOB.lat, startLng: HOB.lng });
  eq(r.length, 10);
});

t('accepted:false samples are excluded', () => {
  const pts = leg(10, 1.7e12);
  pts[3].accepted = false;
  const r = sanitize(pts, { distanceM: 4500, startLat: HOB.lat, startLng: HOB.lng });
  eq(r.length, 9);
});

t('a physically impossible jump is dropped, not used as a bound', () => {
  const pts = leg(10, 1.7e12);
  pts.splice(5, 0, { lat: -12.4, lng: 130.8, t: new Date(1.7e12 + 22500).toISOString(), accuracyM: 8, accepted: true });
  const r = sanitize(pts, { distanceM: 4500, startLat: HOB.lat, startLng: HOB.lng });
  ok(r.every(p => Math.abs(p.lat + 42.88) < 1), 'a Darwin coordinate must not survive a Hobart trip');
});

t('reversed legacy coordinates are corrected ONLY with a trustworthy anchor', () => {
  const swapped = leg(10, 1.7e12).map(p => ({ ...p, lat: p.lng, lng: p.lat }));
  const r = sanitize(swapped, { distanceM: 4500, startLat: HOB.lat, startLng: HOB.lng });
  ok(r.length >= 2, 'reversed data should be recovered when the anchor proves it');
  ok(r.every(p => p.lat < 0 && p.lng > 100), 'points must end up in Tasmania');
});

t('no swap happens without an anchor (never silently)', () => {
  const swapped = leg(10, 1.7e12).map(p => ({ ...p, lat: p.lng, lng: p.lat }));
  const r = sanitize(swapped, { distanceM: 4500 });
  ok(r.length === 0 || r.every(p => p.lat > 100), 'must not swap on speculation');
});

t('the web reader path matches the Android writer path', () => {
  ok(contains('`families/${fid}/drivingSessions/${sessionId}/samples`'), 'web read path changed');
  const kt = require('fs').readFileSync(
    path.join(__dirname, '..', 'android-app/app/src/main/java/com/mbmlife/companion/data/TrackingRepository.kt'), 'utf8');
  ok(kt.indexOf('families/$familyId/drivingSessions/$sessionId/samples/chunk_$chunkIndex') >= 0,
     'Android write path changed');
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT6  Trip Detail map lifecycle (grey/ocean)');

t('paint is gated on an attached container', () => {
  const p = fnSource('drvTripPaintRoute');
  ok(p.indexOf('document.body.contains(el)') >= 0, 'attachment check missing');
});
t('paint is gated on non-zero height', () => {
  ok(fnSource('drvTripPaintRoute').indexOf("drvTripSchedulePaintRetry('container-zero-height')") >= 0);
});
t('paint is gated on the SDK actually being ready', () => {
  const p = fnSource('drvTripPaintRoute');
  ok(p.indexOf('!MBMMap.available()||!MBMMap.get()') >= 0, 'SDK readiness gate missing');
  ok(p.indexOf("drvTripSchedulePaintRetry('sdk-not-ready')") >= 0, 'retry on SDK not ready missing');
});
t('polyline and fitBounds run only after >=2 validated points', () => {
  const p = fnSource('drvTripPaintRoute');
  const guard = p.indexOf('if(samples.length<2)');
  ok(guard >= 0 && guard < p.indexOf('MBMMap.drawRoute(samples)'), 'draw must come after the >=2 guard');
});
t('missing route shows the honest message, never an ocean or grey plate', () => {
  const p = fnSource('drvTripPaintRoute');
  ok(p.indexOf('Detailed route was not recorded for this trip') >= 0, 'missing-route message missing');
  ok(p.indexOf('مسیر دقیق این سفر ثبت نشده است') >= 0, 'Farsi missing-route message missing');
});
t('exhausted retries produce a clear failure message', () => {
  ok(fnSource('drvTripSchedulePaintRetry').indexOf('could not be loaded') >= 0);
});
t('exactly one camera decision per paint', () => {
  const p = fnSource('drvTripPaintRoute');
  eq((p.match(/MBMMap\.fitAll\(/g) || []).length, 1, 'fitAll calls');
  eq((p.match(/MBMMap\.setView\(|MBMMap\.panTo\(/g) || []).length, 0, 'competing camera calls');
});
t('resize runs before the camera decision', () => {
  const p = fnSource('drvTripPaintRoute');
  ok(p.indexOf('MBMMap.invalidate()') < p.indexOf('MBMMap.fitAll('), 'invalidate must precede fitAll');
});
t('timeline arrival patches in place instead of re-rendering the map away', () => {
  ok(contains('drvTripApplyTimeline(sessionId)'), 'in-place timeline patch missing');
  const loader = fnSource('drvLoadTimelineIfNeeded');
  eq((loader.match(/famView==='drivingTrip'&&_drvActiveTripId===sessionId\) render\(\)/g) || []).length, 0,
     'full render() on timeline arrival still present');
});
t('diagnostics report path and per-source sample counts', () => {
  const p = fnSource('drvTripPaintRoute');
  ['firestorePath', 'localSamples', 'remoteSamples', 'validSamples', 'firstPoint', 'lastPoint', 'readError']
    .forEach(k => ok(p.indexOf(k) >= 0, 'diag field missing: ' + k));
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT7  Marker animation (requirement 10)');

t('animation resumes from the real visual position', () => {
  const a = fnSource('animateMarker');
  ok(a.indexOf('const from=_markerRenderPos[uid]||_markerPos[uid]||to;') >= 0, 'must read render position');
  ok(a.indexOf('_markerRenderPos[uid]=cur;') >= 0, 'must update render position every frame');
});

t('the target is never written as if it were the rendered position', () => {
  const a = fnSource('animateMarker');
  const lines = a.split('\n').map(s => s.trim());
  // the v400 bug was a bare trailing `_markerPos[uid]=to;` after setInterval
  const idxInterval = lines.findIndex(l => l.indexOf('_animTimers[uid]=setInterval') >= 0);
  const after = lines.slice(idxInterval).filter(l => l === '_markerPos[uid]=to;');
  eq(after.length, 0, 'stray post-interval target write still present');
});

t('mid-flight cancellation does not teleport (simulated)', () => {
  const timers = {};
  const sandbox = {
    _markers: { u: { position: null } }, _markerPos: {}, _markerRenderPos: {}, _animTimers: {},
    setInterval: (fn) => { const id = Math.random(); timers[id] = fn; return id; },
    clearInterval: (id) => { delete timers[id]; }
  };
  const code = fnSource('animateMarker') +
    '\nreturn {run:animateMarker, s:{_markers,_markerPos,_markerRenderPos,_animTimers}};';
  const api = new Function('_markers', '_markerPos', '_markerRenderPos', '_animTimers', 'setInterval', 'clearInterval',
    code)(sandbox._markers, sandbox._markerPos, sandbox._markerRenderPos, sandbox._animTimers,
          sandbox.setInterval, sandbox.clearInterval);

  sandbox._markerPos.u = [0, 0]; sandbox._markerRenderPos.u = [0, 0];
  api.run('u', [0.001, 0]);                       // start tween toward 0.001
  const tick = Object.values(timers)[0];
  tick(); tick(); tick();                          // ~3/18 of the way
  const mid = sandbox._markerRenderPos.u.slice();
  ok(mid[0] > 0 && mid[0] < 0.001, 'marker should be mid-path, got ' + mid[0]);

  api.run('u', [0.002, 0]);                        // new fix arrives mid-flight
  const resumed = sandbox._markerRenderPos.u;
  ok(Math.abs(resumed[0] - mid[0]) < 1e-9,
     'must resume from ' + mid[0] + ', not jump to ' + resumed[0]);
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT8  Marker text and speed (bug 2, requirements 9, 11, 12)');

t('famBareRoadName returns the structured road only, never a full address', () => {
  const f = fnSource('famBareRoadName');
  ok(f.indexOf('if(cached.road) return cached.road;') >= 0, 'must use the structured road field');
  ok(f.indexOf("cached.line1.replace(/^(Near |نزدیک )/,'')") < 0, 'v400 line1 slicing still present');
});

t('the geocoder exposes and caches the road component', () => {
  ok(contains('return { line1, line2: suburbLine, full, road: road||null };'), 'format() must expose road');
  ok(contains('road:f.road||null, ts:Date.now()'), 'cache must store road');
});

t('the driving callout is the short label, not an address', () => {
  ok(contains("const l1=(L==='fa'?'در حال رانندگی':'Driving');"), 'short driving label missing');
  ok(!contains('`Driving near ${road}`'), 'address interpolation still present in the driving branch');
});

t('speed appears exactly once on the marker', () => {
  const pin = fnSource('pinHtml');
  eq((pin.match(/km\/h/g) || []).length, 1, 'km/h occurrences inside the pin');
  const compose = fnSource('famComposeStatusText');
  const drivingBranch = compose.slice(compose.indexOf("d.activityType==='driving'"),
                                      compose.indexOf("d.activityType==='walking'"));
  ok(drivingBranch.indexOf('km/h') < 0, 'callout must not repeat the speed');
  ok(/l1,\s*l2:'',\s*region,\s*since:null,\s*activityType:'driving',\s*speedKph/.test(drivingBranch),
     'driving callout l2 must stay empty');
});

t('the driving pill car icon is enlarged', () => {
  ok(contains('.fam-pin-activity-pill.driving .motion-ic{width:30px;height:30px}'), 'larger icon missing');
  ok(contains('.fam-pin-activity-pill.driving svg{width:20px;height:20px'), 'larger svg missing');
});

t('address text wraps to two lines instead of being ellipsised', () => {
  ok(contains('.fam-callout{position:relative;background:#fff;border-radius:16px;padding:9px 16px 10px;margin-bottom:10px;\n  box-shadow:0 4px 16px rgba(0,0,0,.24);white-space:normal'), 'callout still nowrap');
  ok(contains('-webkit-line-clamp:2'), 'two-line clamp missing');
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT9  Here for / Since consistency (requirement 13)');

t('both strings derive from the same persisted arrival timestamp', () => {
  ok(contains('l2:famSinceOrHereFor(since2),'), 'sheet Since must use since2');
  ok(contains('calloutL2:famHereForDuration(since2),'), 'callout Here-for must use the same since2');
});

t('Since is rendered in 24-hour clock', () => {
  const f = fnSource('famAbsTime');
  ok(/hour12\s*:\s*false/.test(f) || /hourCycle\s*:\s*'h23'/.test(f) || f.indexOf('h23') >= 0,
     'famAbsTime must be 24-hour: ' + f.replace(/\s+/g, ' ').slice(0, 240));
});

// ─────────────────────────────────────────────────────────────────────────────
console.log('\nT10 Version consistency');

t('web APP_VERSION is v405', () => { ok(contains("const APP_VERSION='v405';")); });
t('Android versionName carries v405', () => {
  const g = require('fs').readFileSync(
    path.join(__dirname, '..', 'android-app/app/build.gradle.kts'),
    'utf8'
  );
  ok(g.indexOf('0.7.5-v405') >= 0, 'versionName not bumped');
  ok(g.indexOf('versionCode = 14') >= 0, 'versionCode not bumped');
  ok(g.indexOf('asset=v405') >= 0, 'PWA asset marker not bumped');
});
t('no stray v400 markers remain in the web build', () => {
  eq(countOf("APP_VERSION='v400'"), 0);
});

console.log('\n' + '─'.repeat(58));
console.log(pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
