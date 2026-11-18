#!/usr/bin/env node
/*
 * Replaces the bundled system prompt inside a zyvo engine binary (libzyvo.so / zyvo.exe).
 *
 * The prompt is a template literal: var default_default = `...`  (android engine)
 *                                or  var Iw = `...`              (windows build)
 * The slot length is fixed by the bundle layout, so the replacement is written into the
 * exact same byte range and the leftover space is parked inside an empty interpolation
 * (a template placeholder whose expression is an empty string) -> evaluates to "",
 * never reaches the model, costs no tokens.
 *
 * usage: node tools/patch-engine-prompt.js <binary> [--prompt <file.md>] [--dry]
 */
const fs = require('fs');
const path = require('path');

const MARKERS = ['var default_default=`', 'var default_default = `', 'var Iw=`', 'var Iw = `'];

function findSlot(buf) {
  for (const m of MARKERS) {
    const mb = Buffer.from(m, 'utf8');
    const at = buf.indexOf(mb);
    if (at < 0) continue;
    const textStart = at + m.length;
    let i = textStart;
    while (i < buf.length) {
      const c = buf[i];
      if (c === 0x5c) { i += 2; continue; }
      if (c === 0x60) break;
      i++;
    }
    if (i >= buf.length) throw new Error('unterminated prompt literal after ' + m);
    return { marker: m, at, textStart, rawLen: i - textStart, closeAt: i };
  }
  throw new Error('prompt literal not found in ' + path.basename(process.argv[2] || '?'));
}

function escapeJs(s) {
  return s.replace(/\\/g, '\\\\').replace(/`/g, '\\`').replace(/\$\{/g, '\\${')
    .replace(/\r/g, '\\r').replace(/\n/g, '\\n');
}

const args = process.argv.slice(2);
const binPath = args[0];
if (!binPath) { console.error('usage: patch-engine-prompt.js <binary> [--prompt <md>] [--dry]'); process.exit(2); }
const promptPath = args.includes('--prompt')
  ? args[args.indexOf('--prompt') + 1]
  : path.join(__dirname, 'system-prompt.md');
const dry = args.includes('--dry');

const buf = fs.readFileSync(binPath);
const slot = findSlot(buf);
const prompt = fs.readFileSync(promptPath, 'utf8').trim() + '\n';
let esc = escapeJs(prompt);

const head = '${/*';
const tail = '*/""}';
const padLen = slot.rawLen - Buffer.byteLength(esc, 'utf8') - head.length - tail.length;
if (padLen < 0) {
  console.error('[x] prompt is ' + (-padLen) + ' bytes too long for this slot (' + slot.rawLen + ' available)');
  process.exit(1);
}

console.log('marker   : ' + slot.marker);
console.log('offset   : ' + slot.textStart + ' (0x' + slot.textStart.toString(16) + ')');
console.log('slot     : ' + slot.rawLen + ' bytes');
console.log('prompt   : ' + Buffer.byteLength(esc, 'utf8') + ' bytes escaped');
console.log('padding  : ' + padLen + ' bytes (free, inside ${/* */""})');

if (dry) { console.log('[dry run] nothing written'); process.exit(0); }

const payload = Buffer.from(esc + head + ' '.repeat(padLen) + tail, 'utf8');
if (payload.length !== slot.rawLen) throw new Error('size mismatch: ' + payload.length + ' vs ' + slot.rawLen);
const out = Buffer.from(buf);
payload.copy(out, slot.textStart);
fs.writeFileSync(binPath, out);
console.log('[+] patched ' + binPath);
