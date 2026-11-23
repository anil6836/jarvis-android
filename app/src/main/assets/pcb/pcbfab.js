// 🔌 Jarvis PCB studio — the files a PCB factory needs (our own code, ₹0): Gerber RS-274X (millimetres, 4.6, Protel file
// names that every factory reads), the Excellon drill files (plated / not plated), the parts list (BOM) and the pick-and-place
// list (CPL), all in one .zip. He saves / shares the zip himself and uploads it on the factory's site himself.
'use strict';
(function (root) {
  const C = root.PcbCore || (typeof require !== 'undefined' ? require('./pcbcore.js') : null);
  const P = root.PcbParts || (typeof require !== 'undefined' ? require('./pcbparts.js') : null);

  const n6 = (v) => String(Math.round(v * 1e6));
  const f6 = (v) => (Math.round(v * 1e6) / 1e6).toFixed(6);

  function Gerber(name, D) {
    this.H = D.board.h;
    this.ap = new Map();
    this.body = [];
    this.head = ['G04 Jarvis PCB studio: ' + String(D.title || 'BOARD').replace(/[^ -~]/g, '').replace(/[*%]/g, '') + ' - ' + name + '*', '%FSLAX46Y46*%', '%MOMM*%', '%LPD*%', 'G01*'];
    this.cur = null;
  }
  Gerber.prototype.aperture = function (key, def) {
    if (!this.ap.has(key)) { const d = 10 + this.ap.size; this.ap.set(key, { d, def }); }
    const a = this.ap.get(key);
    if (this.cur !== a.d) { this.body.push('D' + a.d + '*'); this.cur = a.d; }
  };
  Gerber.prototype.xy = function (x, y) { return 'X' + n6(x) + 'Y' + n6(this.H - y); };
  Gerber.prototype.flash = function (p, grow) {
    const e = grow || 0, w = p.w + 2 * e, h = p.h + 2 * e;
    if (p.s === 'r') this.aperture('R' + f6(w) + 'x' + f6(h), 'R,' + f6(w) + 'X' + f6(h));
    else if (p.s === 'o' && Math.abs(w - h) > 1e-6) this.aperture('O' + f6(w) + 'x' + f6(h), 'O,' + f6(w) + 'X' + f6(h));
    else this.aperture('C' + f6(w), 'C,' + f6(w));
    this.body.push(this.xy(p.x, p.y) + 'D03*');
  };
  Gerber.prototype.line = function (pts, w) {
    if (pts.length < 2) return;
    this.aperture('C' + f6(w), 'C,' + f6(w));
    this.body.push(this.xy(pts[0][0], pts[0][1]) + 'D02*');
    for (let i = 1; i < pts.length; i++) this.body.push(this.xy(pts[i][0], pts[i][1]) + 'D01*');
  };
  Gerber.prototype.region = function (r) {
    this.body.push('G36*', this.xy(r[0], r[1]) + 'D02*', this.xy(r[2], r[1]) + 'D01*', this.xy(r[2], r[3]) + 'D01*', this.xy(r[0], r[3]) + 'D01*', this.xy(r[0], r[1]) + 'D01*', 'G37*');
  };
  Gerber.prototype.text = function () {
    const defs = Array.from(this.ap.values()).map((a) => '%ADD' + a.d + a.def + '*%');
    return this.head.concat(defs, this.body, ['M02*']).join('\n') + '\n';
  };

  /** All the files: [{name, text}] plus a summary. fills: {F, B} ground fills (from PcbCore.pour). */
  function files(D, fills) {
    const R = C.rules(D), M = C.model(D), base = slug(D);
    const pads = M.pads, smd = pads.filter((p) => p.smd), out = [];
    const cu = (layer) => {
      const g = new Gerber(layer === 0 ? 'Top copper' : 'Bottom copper', D);
      const fl = fills && fills[layer === 0 ? 'F' : 'B'];
      if (fl) fl.rects.forEach((r) => g.region(r));
      (D.tracks || []).filter((t) => (t.l === 'B' ? 1 : 0) === layer).forEach((t) => g.line(t.p, t.w || R.track));
      (D.vias || []).forEach((v) => g.flash({ x: v.x, y: v.y, s: 'c', w: v.d || R.via, h: v.d || R.via }));
      pads.forEach((p) => { if (!p.smd || layer === 0) g.flash(p); });
      return g.text();
    };
    const mask = (layer) => {
      const g = new Gerber(layer === 0 ? 'Top solder mask' : 'Bottom solder mask', D);
      pads.forEach((p) => { if (!p.smd || layer === 0) g.flash(p, 0.05); });
      return g.text();
    };
    const silk = () => {
      const g = new Gerber('Top silkscreen', D), s = C.silk(D, M);
      s.segs.forEach((q) => g.line([[q[0], q[1]], [q[2], q[3]]], s.w));
      return g.text();
    };
    const edge = () => { const g = new Gerber('Board outline', D); g.line(C.outline(D.board, 8), 0.1); return g.text(); };
    out.push({ name: base + '.GTL', text: cu(0) }, { name: base + '.GBL', text: cu(1) }, { name: base + '.GTS', text: mask(0) }, { name: base + '.GBS', text: mask(1) },
      { name: base + '.GTO', text: silk() }, { name: base + '.GKO', text: edge() });
    if (smd.length) { const g = new Gerber('Top paste', D); smd.forEach((p) => g.flash(p)); out.push({ name: base + '.GTP', text: g.text() }); }
    const pth = pads.filter((p) => p.d).map((p) => ({ x: p.x, y: p.y, d: p.d })).concat((D.vias || []).map((v) => ({ x: v.x, y: v.y, d: v.drill || R.viaDrill })));
    out.push({ name: base + '-PTH.DRL', text: drill(D, pth, 'Plated') });
    const hs = C.holes(D);
    if (hs.length) out.push({ name: base + '-NPTH.DRL', text: drill(D, hs.map((h) => ({ x: h.x, y: h.y, d: h.d })), 'NonPlated') });
    out.push({ name: base + '-BOM.csv', text: bom(D) }, { name: base + '-CPL.csv', text: cpl(D) }, { name: 'README.txt', text: readme(D, base, pth.length, hs.length) });
    return out;
  }

  function drill(D, hits, kind) {
    const H = D.board.h, sizes = Array.from(new Set(hits.map((h) => Math.round(h.d * 1000) / 1000))).sort((a, b) => a - b);
    const L = ['M48', '; DRILL file {Jarvis PCB studio} ' + kind, '; FORMAT={-:-/ absolute / metric / decimal}', 'FMAT,2', 'METRIC'];
    sizes.forEach((d, i) => L.push('T' + (i + 1) + 'C' + d.toFixed(3)));
    L.push('%', 'G90', 'G05');
    const r3 = (v) => String(Math.round(v * 1000) / 1000);
    sizes.forEach((d, i) => {
      L.push('T' + (i + 1));
      hits.filter((h) => Math.round(h.d * 1000) / 1000 === d).forEach((h) => L.push('X' + r3(h.x) + 'Y' + r3(H - h.y)));
    });
    L.push('T0', 'M30');
    return L.join('\n') + '\n';
  }

  const csv = (v) => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"';
  function ascii(v) { return String(v || '').replace(/µ/g, 'u').replace(/Ω/g, '').replace(/[^ -~]/g, '').trim(); }
  function bom(D) {
    const groups = new Map();
    D.parts.forEach((pt) => {
      const f = P.fpOf(pt), key = ascii(pt.val) + '|' + (f ? f.name : '');
      if (!groups.has(key)) groups.set(key, { val: ascii(pt.val) || ascii(pt.lib), fp: f ? f.name : '', refs: [] });
      groups.get(key).refs.push(pt.ref);
    });
    const L = ['Comment,Designator,Footprint,Quantity'];
    groups.forEach((g) => L.push([csv(g.val), csv(g.refs.join(',')), csv(g.fp), g.refs.length].join(',')));
    return L.join('\r\n') + '\r\n';
  }
  function cpl(D) {
    const H = D.board.h, L = ['Designator,Mid X,Mid Y,Layer,Rotation'];
    D.parts.forEach((pt) => L.push([pt.ref, pt.x.toFixed(3) + 'mm', (H - pt.y).toFixed(3) + 'mm', 'Top', String((360 - (pt.rot || 0)) % 360)].join(',')));
    return L.join('\r\n') + '\r\n';
  }
  function readme(D, base, nDrill, nNp) {
    const B = D.board, F = C.FABS[D.fab] || C.FABS.jlc;
    return ['Board: ' + ascii(D.title || 'JARVIS PCB'), 'Made with: Jarvis PCB studio (Gerber RS-274X, metric, 4.6; Excellon metric decimal)', '',
      'Size: ' + B.w + ' x ' + B.h + ' mm, 2 layers, ' + (B.thick || 1.6) + ' mm FR-4, ' + (B.cu || 1) + ' oz copper, colour: ' + (B.color || 'green'),
      'Smallest track: ' + Math.min(...(D.tracks || []).map((t) => t.w || 0.4).concat([0.4])).toFixed(2) + ' mm; clearance designed: 0.30 mm; holes: ' + nDrill + ' plated' + (nNp ? ', ' + nNp + ' not plated' : ''),
      'Checked against: ' + F.name, '', 'Files:',
      base + '.GTL  top copper', base + '.GBL  bottom copper', base + '.GTS  top solder mask', base + '.GBS  bottom solder mask',
      base + '.GTO  top silkscreen', base + '.GKO  board outline', base + '-PTH.DRL  plated holes' + (nNp ? '\n' + base + '-NPTH.DRL  not plated (mounting) holes' : ''),
      base + '-BOM.csv  parts list', base + '-CPL.csv  part positions (top)', ''].join('\r\n');
  }
  function slug(D) {
    const s = ascii(D.title || 'jarvis-pcb').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    return (s || 'jarvis-pcb').slice(0, 30);
  }

  // ------------------------------------------------------------------ a .zip (stored, no compression: every factory opens it)

  const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
  function crc32(b) { let c = 0xffffffff; for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
  function zip(list, when) {
    const enc = new TextEncoder(), d = when || new Date();
    const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1), date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
    const parts = [], central = [];
    let off = 0;
    for (const f of list) {
      const name = enc.encode(f.name), data = typeof f.text === 'string' ? enc.encode(f.text) : f.bytes, crc = crc32(data);
      const h = new DataView(new ArrayBuffer(30));
      h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0, true); h.setUint16(8, 0, true); h.setUint16(10, time, true); h.setUint16(12, date, true);
      h.setUint32(14, crc, true); h.setUint32(18, data.length, true); h.setUint32(22, data.length, true); h.setUint16(26, name.length, true); h.setUint16(28, 0, true);
      parts.push(new Uint8Array(h.buffer), name, data);
      const c = new DataView(new ArrayBuffer(46));
      c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0, true); c.setUint16(10, 0, true); c.setUint16(12, time, true); c.setUint16(14, date, true);
      c.setUint32(16, crc, true); c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, name.length, true);
      c.setUint32(42, off, true);
      central.push(new Uint8Array(c.buffer), name);
      off += 30 + name.length + data.length;
    }
    const cdSize = central.reduce((s, a) => s + a.length, 0);
    const e = new DataView(new ArrayBuffer(22));
    e.setUint32(0, 0x06054b50, true); e.setUint16(8, list.length, true); e.setUint16(10, list.length, true); e.setUint32(12, cdSize, true); e.setUint32(16, off, true);
    const all = parts.concat(central, [new Uint8Array(e.buffer)]), total = all.reduce((s, a) => s + a.length, 0), out = new Uint8Array(total);
    let at = 0;
    for (const a of all) { out.set(a, at); at += a.length; }
    return out;
  }
  function b64(u8) {
    if (typeof Buffer !== 'undefined') return Buffer.from(u8).toString('base64');
    let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return btoa(s);
  }

  const api = { files, zip, b64, crc32, slug, bom, cpl };
  root.PcbFab = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
