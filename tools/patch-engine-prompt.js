#!/usr/bin/env node
/*
 * Replaces the bundled system prompt inside a zyvo engine binary (libzyvo.so / zyvo.exe).
 *
 * The prompt ships as a template literal:  var default_default = `...`   (android engine)
 *                                   or     var Iw = `...`                (windows build)
 * The engine may embed the same module more than once (two bundles), so EVERY copy is
 * patched. Slot length is fixed by the bundle layout: the replacement is written into the
 * same byte range and leftover space is parked inside an empty interpolation
 * (a template placeholder whose expression is an empty string) -> evaluates to "",
 * never reaches the model, costs no tokens.
 *
 * usage: node tools/patch-engine-prompt.js <binary> [--prompt <file.md>] [--dry]
 */
const fs = require('fs');
const path = require('path');

const MARKERS = ['var default_default=`', 'var default_default = `'];

function findAllSlots(buf) {
  const slots = [];
  for (const m of MARKERS) {
    const mb = Buffer.from(m, 'utf8');
    let from = 0;
    while (true) {
      const at = buf.indexOf(mb, from);
      if (at < 0) break;
      from = at + mb.length;
      const textStart = at + mb.length;
      let i = textStart;
      while (i < buf.length) {
        const c = buf[i];
        if (c === 0x5c) { i += 2; continue; }
        if (c === 0x60) break;
        i++;
      }
      if (i >= buf.length) throw new Error('unterminated prompt literal at ' + textStart);
      if (!slots.some((s) => s.textStart === textStart)) {
        slots.push({ marker: m, at, textStart, rawLen: i - textStart, closeAt: i });
      }
    }
    if (slots.length) break; // first marker style that matches wins
  }
  return slots;
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
const slots = findAllSlots(buf);
if (!slots.length) {
  console.error('[x] prompt literal not found in ' + binPath);
  process.exit(1);
}

const prompt = fs.readFileSync(promptPath, 'utf8').trim() + '\n';
const esc = escapeJs(prompt);
const head = '${/*';
const tail = '*/""}';

const out = Buffer.from(buf);
let changed = 0;
for (const slot of slots) {
  const padLen = slot.rawLen - Buffer.byteLength(esc, 'utf8') - head.length - tail.length;
  console.log('copy @ ' + slot.textStart + ' (0x' + slot.textStart.toString(16) + ')  slot ' + slot.rawLen +
    ' B  prompt ' + Buffer.byteLength(esc, 'utf8') + ' B  pad ' + padLen + ' B');
  if (padLen < 0) {
    console.error('[x] prompt is ' + (-padLen) + ' bytes too long for the slot at ' + slot.textStart);
    process.exit(1);
  }
  if (dry) continue;
  const payload = Buffer.from(esc + head + ' '.repeat(padLen) + tail, 'utf8');
  if (payload.length !== slot.rawLen) throw new Error('size mismatch at ' + slot.textStart);
  payload.copy(out, slot.textStart);
  changed++;
}

if (dry) { console.log('[dry run] ' + slots.length + ' slot(s) measured, nothing written'); process.exit(0); }
fs.writeFileSync(binPath, out);
console.log('[+] patched ' + changed + ' copy/copies in ' + binPath);
