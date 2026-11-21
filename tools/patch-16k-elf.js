#!/usr/bin/env node
/**
 * Realign ELF PT_LOAD segments to a 16 KB page size (Android 15+ / 16 KB devices).
 *
 * Google's requirement: every PT_LOAD must have p_align >= 16384 AND
 * (p_vaddr - p_offset) % 16384 == 0. Prebuilt libs (e.g. conscrypt-android
 * 2.5.2) are 4 KB-aligned, which makes them refuse to load on 16 KB kernels.
 *
 * The patch inserts zero padding in front of offending segments so the
 * congruence holds, then sets p_align. Virtual addresses and section bytes
 * never change - only file offsets shift.
 *
 * usage: node tools/patch-16k-elf.js <file.so> [--check]
 * exit 0 = aligned (or successfully patched), exit 2 = still misaligned.
 */
'use strict';

const fs = require('fs');

const PAGE = 0x4000; // 16 KB
const PT_LOAD = 1;
const PT_NULL = 0;

// ELF layout differs between 32-bit and 64-bit images.
const LAYOUT = {
  1: {
    phoff: [0x1c, 4], shoff: [0x20, 4],
    phentsize: [0x2a, 2], phnum: [0x2c, 2], shentsize: [0x2e, 2], shnum: [0x30, 2],
    p_type: [0, 4], p_offset: [4, 4], p_vaddr: [8, 4], p_align: [0x1c, 4],
    sh_offset: [0x10, 4],
  },
  2: {
    phoff: [0x20, 8], shoff: [0x28, 8],
    phentsize: [0x36, 2], phnum: [0x38, 2], shentsize: [0x3a, 2], shnum: [0x3c, 2],
    p_type: [0, 4], p_offset: [0x08, 8], p_vaddr: [0x10, 8], p_align: [0x30, 8],
    sh_offset: [0x18, 8],
  },
};

function readU(buf, off, size, le) {
  if (size === 8) return Number(le ? buf.readBigUInt64LE(off) : buf.readBigUInt64BE(off));
  if (size === 4) return le ? buf.readUInt32LE(off) : buf.readUInt32BE(off);
  if (size === 2) return le ? buf.readUInt16LE(off) : buf.readUInt16BE(off);
  return buf.readUInt8(off);
}

function writeU(buf, off, size, value, le) {
  if (size === 8) return le ? buf.writeBigUInt64LE(BigInt(value), off) : buf.writeBigUInt64BE(BigInt(value), off);
  if (size === 4) return le ? buf.writeUInt32LE(value >>> 0, off) : buf.writeUInt32BE(value >>> 0, off);
  if (size === 2) return le ? buf.writeUInt16LE(value & 0xffff, off) : buf.writeUInt16BE(value & 0xffff, off);
  return buf.writeUInt8(value & 0xff, off);
}

function fail(msg) {
  console.error(msg);
  process.exit(2);
}

function main() {
  const file = process.argv[2];
  const checkOnly = process.argv.includes('--check');
  if (!file) fail('usage: node tools/patch-16k-elf.js <file.so> [--check]');

  let buf = fs.readFileSync(file);
  if (buf.length < 52 || buf.readUInt32BE(0) !== 0x7f454c46) fail('not an ELF file');
  const elfClass = buf[4]; // 1 = ELF32, 2 = ELF64
  const L = LAYOUT[elfClass];
  if (!L) fail('unsupported ELF class ' + elfClass);
  const le = buf[5] === 1;
  if (!le) fail('only little-endian supported');

  const get = (field, b) => readU(b, L[field][0], L[field][1], le);
  const put = (b, at, field, value) => writeU(b, at, L[field][1], value, le);

  const phoff = get('phoff', buf);
  const shoff = get('shoff', buf);
  const phentsize = get('phentsize', buf);
  const phnum = get('phnum', buf);
  const shentsize = get('shentsize', buf);
  const shnum = get('shnum', buf);

  // -- collect program headers -------------------------------------------
  const phdrs = [];
  for (let i = 0; i < phnum; i++) {
    const p = phoff + i * phentsize;
    phdrs.push({
      off: p,
      type: readU(buf, p + L.p_type[0], L.p_type[1], le),
      pOffset: readU(buf, p + L.p_offset[0], L.p_offset[1], le),
      pVaddr: readU(buf, p + L.p_vaddr[0], L.p_vaddr[1], le),
      pAlign: readU(buf, p + L.p_align[0], L.p_align[1], le),
    });
  }

  const loads = phdrs.filter((h) => h.type === PT_LOAD).sort((a, b) => a.pOffset - b.pOffset);
  if (!loads.length) fail('no PT_LOAD segments');

  const isAligned = (s) => {
    const need = Math.max(PAGE, s.pAlign);
    return s.pAlign >= PAGE && ((s.pVaddr - s.pOffset) % need + need) % need === 0;
  };

  if (checkOnly) {
    for (const s of loads) {
      console.log(
        `LOAD off=0x${s.pOffset.toString(16)} vaddr=0x${s.pVaddr.toString(16)} ` +
        `align=0x${s.pAlign.toString(16)} ${isAligned(s) ? 'OK' : 'MISALIGNED'}`
      );
    }
    process.exit(loads.every(isAligned) ? 0 : 2);
  }

  if (loads.every(isAligned)) {
    console.log(`already 16 KB aligned: ${loads.length} PT_LOAD`);
    process.exit(0);
  }

  // -- compute insertions -------------------------------------------------
  const insertions = []; // { pos (original file offset), len }
  let shift = 0;
  for (const s of loads) {
    const need = Math.max(PAGE, s.pAlign);
    const effective = s.pOffset + shift;
    const d = ((s.pVaddr - effective) % need + need) % need;
    if (d) {
      insertions.push({ pos: s.pOffset, len: d });
      shift += d;
    }
    s.newOffset = effective + d;
    s.newAlign = need;
  }

  const shiftFor = (pos) => {
    let add = 0;
    for (const ins of insertions) if (ins.pos <= pos) add += ins.len;
    return add;
  };

  // -- build new buffer ---------------------------------------------------
  insertions.sort((a, b) => a.pos - b.pos);
  const parts = [];
  let cursor = 0;
  for (const ins of insertions) {
    parts.push(buf.subarray(cursor, ins.pos));
    parts.push(Buffer.alloc(ins.len));
    cursor = ins.pos;
  }
  parts.push(buf.subarray(cursor));
  let out = Buffer.concat(parts);

  // -- patch program headers ---------------------------------------------
  const phNew = phoff + shiftFor(phoff);
  for (const h of phdrs) {
    if (h.type === PT_NULL) continue;
    const dst = phNew + (h.off - phoff);
    if (h.type === PT_LOAD) {
      put(out, dst + L.p_offset[0], 'p_offset', h.newOffset);
      put(out, dst + L.p_align[0], 'p_align', h.newAlign);
    } else {
      put(out, dst + L.p_offset[0], 'p_offset', h.pOffset + shiftFor(h.pOffset));
    }
  }

  // -- patch section headers (values are still original in the buffer) ----
  if (shoff) {
    const shNew = shoff + shiftFor(shoff);
    for (let i = 0; i < shnum; i++) {
      const src = shoff + i * shentsize;
      const dst = shNew + i * shentsize;
      if (src + L.sh_offset[0] + L.sh_offset[1] > buf.length) break;
      if (dst + L.sh_offset[0] + L.sh_offset[1] > out.length) break;
      const shOffset = readU(buf, src + L.sh_offset[0], L.sh_offset[1], le);
      if (shOffset) put(out, dst + L.sh_offset[0], 'sh_offset', shOffset + shiftFor(shOffset));
    }
    put(out, L.shoff[0], 'shoff', shNew);
  }

  fs.writeFileSync(file, out);

  // -- verify -------------------------------------------------------------
  buf = out;
  const mismatched = [];
  for (let i = 0; i < phnum; i++) {
    const p = phNew + i * phentsize;
    if (readU(buf, p + L.p_type[0], L.p_type[1], le) !== PT_LOAD) continue;
    const o = readU(buf, p + L.p_offset[0], L.p_offset[1], le);
    const v = readU(buf, p + L.p_vaddr[0], L.p_vaddr[1], le);
    const a = readU(buf, p + L.p_align[0], L.p_align[1], le);
    const need = Math.max(PAGE, a);
    if (a < PAGE || ((v - o) % need + need) % need !== 0) mismatched.push({ o, v, a });
  }
  if (mismatched.length) fail(`patch failed: ${JSON.stringify(mismatched)}`);

  const added = insertions.reduce((a, b) => a + b.len, 0);
  console.log(`patched: +${added} bytes, ${loads.length} PT_LOAD now 16 KB aligned`);
}

main();
