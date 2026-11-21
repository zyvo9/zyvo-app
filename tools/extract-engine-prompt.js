#!/usr/bin/env node
/*
 * Extracts the bundled system prompt(s) from a zyvo engine binary into markdown files.
 * usage: node tools/extract-engine-prompt.js <binary> <outDir>
 * Writes outDir/android-old-1.md, -2.md, ... (one file per embedded copy).
 */
const fs = require('fs');
const path = require('path');

const binPath = process.argv[2];
const outDir = process.argv[3];
if (!binPath || !outDir) { console.error('usage: extract-engine-prompt.js <binary> <outDir>'); process.exit(2); }

const buf = fs.readFileSync(binPath);
const marker = Buffer.from('var default_default = `', 'utf8');
fs.mkdirSync(outDir, { recursive: true });

let from = 0, n = 0;
while (true) {
  const at = buf.indexOf(marker, from);
  if (at < 0) break;
  from = at + marker.length;
  let i = at + marker.length;
  while (i < buf.length) {
    const c = buf[i];
    if (c === 0x5c) { i += 2; continue; }
    if (c === 0x60) break;
    i++;
  }
  const raw = buf.slice(at + marker.length, i).toString('utf8');
  const text = eval('`' + raw + '`');
  n++;
  const out = path.join(outDir, 'android-old-' + n + '.md');
  fs.writeFileSync(out, text, 'utf8');
  console.log('[+] copy ' + n + ' @ ' + (at + marker.length) + ' -> ' + out + '  (' + text.length + ' chars)');
}
if (!n) { console.error('[x] no prompt literal found'); process.exit(1); }
