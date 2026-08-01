import fs from 'node:fs';
import assert from 'node:assert/strict';

const html=fs.readFileSync(new URL('../index.html',import.meta.url),'utf8');
const start=html.indexOf('    const AWAY_CONFIRM_MS');
const end=html.indexOf('    /* ── local cache accessors',start);
assert.ok(start>0&&end>start,'presence engine source must be extractable');
const source=html.slice(start,end);
const classify=new Function(`
  const MOVE_MIN_M=40;
  function haversine(a,b){
    const R=6371000,toR=x=>x*Math.PI/180;
    const dLat=toR(b.lat-a.lat),dLng=toR(b.lng-a.lng);
    const q=Math.sin(dLat/2)**2+Math.cos(toR(a.lat))*Math.cos(toR(b.lat))*Math.sin(dLng/2)**2;
    return 2*R*Math.asin(Math.min(1,Math.sqrt(q)));
  }
  ${source}
  return classifyMovement;
`)();

const iso=ms=>new Date(ms).toISOString();
function ingest(prev,at,lat,lng,accuracy=5){
  const fix={capturedAt:iso(at),reportedAt:iso(at),lat,lng,accuracy,source:'test'};
  return Object.assign({},prev||{},fix,classify(prev,fix));
}

// A: opening at an already-occupied location must not manufacture arrival.
let state=ingest(null,0,0,0);
assert.equal(state.stayStart,null);
assert.equal(state._presence.state,'unconfirmed');

// B: even 30 minutes of stationary post-launch fixes cannot reveal when the
// user arrived before tracking began.
for(let t=10_000;t<=30*60_000;t+=10_000) state=ingest(state,t,0,0);
assert.equal(state.stayStart,null);
assert.equal(state._presence.state,'unconfirmed');

// C: only a sustained observed departure followed by a sustained observed
// arrival creates a new stayStart, anchored to the first steady return fix.
state=ingest(state,30*60_000+10_000,0.002,0);
state=ingest(state,30*60_000+100_000,0.002,0);
assert.equal(state._presence.state,'away');
assert.equal(state.stayStart,null);
const arrivalCandidateAt=30*60_000+110_000;
state=ingest(state,arrivalCandidateAt,0.004,0);
state=ingest(state,arrivalCandidateAt+60_000,0.004,0);
assert.equal(state._presence.state,'steady');
assert.equal(state.stayStart,iso(arrivalCandidateAt));

// D: reopening/replaying after a confirmed arrival preserves the exact
// persisted timestamp; neither first post-open fix nor its capturedAt wins.
const trusted=state.stayStart;
state=ingest(state,arrivalCandidateAt+2*60*60_000,0.004,0);
assert.equal(state.stayStart,trusted);

console.log('v418 presence behaviour A-D passed');
