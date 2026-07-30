// Pulls the REAL source text of a named function out of index.html so tests run
// against shipped code, not a reimplementation.
const fs = require('fs');

const path = require('path');
const SRC = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');

function fnSource(name) {
  const sig = 'function ' + name + '(';
  const i = SRC.indexOf(sig);
  if (i < 0) throw new Error('function not found: ' + name);
  // brace-match from the first { after the signature
  let j = SRC.indexOf('{', i);
  let depth = 0, inS = null, esc = false, line = false, block = false;
  for (let k = j; k < SRC.length; k++) {
    const c = SRC[k], n = SRC[k + 1];
    if (line) { if (c === '\n') line = false; continue; }
    if (block) { if (c === '*' && n === '/') { block = false; k++; } continue; }
    if (esc) { esc = false; continue; }
    if (inS) {
      if (c === '\\') { esc = true; continue; }
      if (c === inS) inS = null;
      continue;
    }
    if (c === '/' && n === '/') { line = true; k++; continue; }
    if (c === '/' && n === '*') { block = true; k++; continue; }
    if (c === '"' || c === "'" || c === '`') { inS = c; continue; }
    if (c === '{') depth++;
    else if (c === '}') { depth--; if (depth === 0) return SRC.slice(i, k + 1); }
  }
  throw new Error('unbalanced braces for ' + name);
}

function contains(needle) { return SRC.indexOf(needle) >= 0; }
function countOf(needle) { return SRC.split(needle).length - 1; }

module.exports = { SRC, fnSource, contains, countOf };
