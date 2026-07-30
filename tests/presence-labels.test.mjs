import fs from 'node:fs';
import assert from 'node:assert/strict';

const html = fs.readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const sinceFormatter = html.slice(
  html.indexOf('function famSinceOrHereFor(since)'),
  html.indexOf('function famHereForDuration(since)')
);
const hereForFormatter = html.slice(
  html.indexOf('function famHereForDuration(since)'),
  html.indexOf('function famSinceDiagLineHtml')
);

assert.ok(
  sinceFormatter.includes("'از':'Since'") &&
    sinceFormatter.includes('famAbsTime(since)'),
  'The detail panel must render the persisted arrival as “Since <clock time>”.'
);

assert.ok(
  hereForFormatter.includes('famDurSince(since)') &&
    hereForFormatter.includes('`Here for ${duration}`'),
  'The map callout must render the same persisted arrival as “Here for <duration>”.'
);

assert.match(
  html,
  /l2:famSinceOrHereFor\(since2\),\s*calloutL2:famHereForDuration\(since2\),\s*since:since2/,
  'Stationary callout and detail labels must be composed from exactly the same since2 timestamp.'
);

assert.match(
  html,
  /calloutL2:\(txt\.calloutL2\|\|txt\.l2\)\?esc\(txt\.calloutL2\|\|txt\.l2\):''/,
  'The map marker must consume the dedicated Here-for callout value.'
);

assert.match(
  html,
  /const sinceTxt = movingActivity[\s\S]{0,300}: \(txt\.l2 \|\|/,
  'The detail panel must consume the dedicated Since value only while stationary.'
);

assert.doesNotMatch(
  html,
  /famHereForDuration\(s\.reportedAt\)|famHereForDuration\(s\.capturedAt\)/,
  'GPS update timestamps must never be substituted for the arrival timestamp.'
);

console.log('presence-labels tests passed');
