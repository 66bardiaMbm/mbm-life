import assert from 'node:assert/strict';
import fs from 'node:fs';

const source=fs.readFileSync('index.html','utf8');
const start=source.indexOf('  let _camAnim=null;');
const end=source.indexOf('  // v456: converts a screen-pixel offset',start);
assert.ok(start>=0&&end>start,'real camera implementation found in index.html');
const cameraSource=source.slice(start,end);

let now=0,rafSeq=0;
const rafs=new Map();
const events=[];
const state={lat:-42.88,lng:147.32,zoom:16};
const window={diagHistoryPush:(source,payload)=>events.push({source,...payload})};
const performance={now:()=>now};
const requestAnimationFrame=fn=>{ const id=++rafSeq; rafs.set(id,fn); return id; };
const cancelAnimationFrame=id=>rafs.delete(id);
const available=()=>true;
const _pad={top:0,right:0,bottom:0,left:0};
const _map={
  getCenter:()=>({lat:()=>state.lat,lng:()=>state.lng}),
  getZoom:()=>state.zoom,
  moveCamera:({center,zoom})=>Object.assign(state,center,{zoom})
};
const _offsetLatLngByPixels=(lat,lng)=>({lat,lng});

// Evaluate the exact production functions extracted above with controlled
// requestAnimationFrame, clock, map and diagnostic dependencies.
eval(`${cameraSource}\n;globalThis.__cameraLifecycle={setCamera,cancelCameraAnimation};`);
const camera=globalThis.__cameraLifecycle;

function runNext(at){
  now=at;
  const entry=rafs.entries().next().value;
  assert.ok(entry,'animation frame queued');
  const [id,fn]=entry;
  rafs.delete(id);
  fn(now);
}

// A completed animation has one linked target/start/end lifecycle.
camera.setCamera({center:[-42.87,147.33],zoom:16,reason:'driving-follow'});
runNext(0);
runNext(650);
const target=events.find(e=>e.source==='camera_target');
const started=events.find(e=>e.source==='camera_anim_start');
const ended=events.find(e=>e.source==='camera_anim_end');
assert.ok(target&&started&&ended,'complete lifecycle recorded');
assert.equal(target.animationId,started.animationId);
assert.equal(started.animationId,ended.animationId);
assert.equal(ended.durationMs,650);
console.log('PASS real setCamera records linked start/end lifecycle');

// A replacement records cancellation of the unfinished animation, including
// elapsed time and the actual visual camera position at cancellation.
events.length=0;
now=1000;
camera.setCamera({center:[-42.86,147.34],zoom:16,reason:'driving-follow'});
runNext(1100);
camera.setCamera({center:[-42.85,147.35],zoom:16,reason:'driving-follow'});
const cancelled=events.find(e=>e.source==='camera_anim_cancel');
const starts=events.filter(e=>e.source==='camera_anim_start');
assert.ok(cancelled,'replacement cancellation recorded');
assert.equal(cancelled.animationId,starts[0].animationId);
assert.equal(cancelled.reason,'replaced');
assert.equal(cancelled.elapsedMs,100);
assert.equal(cancelled.lat,state.lat);
assert.equal(cancelled.lng,state.lng);
assert.notEqual(starts[0].animationId,starts[1].animationId);
console.log('PASS real setCamera records replaced/cancelled lifecycle');

// External owners can identify why an active animation was cancelled.
events.length=0;
camera.cancelCameraAnimation('gesture');
assert.equal(events[0].source,'camera_anim_cancel');
assert.equal(events[0].reason,'gesture');
console.log('PASS real cancelCameraAnimation records owner reason');

delete globalThis.__cameraLifecycle;
