// ⚡ Jarvis's circuit lab page (Anil, 16 Nov): the board he builds on (parts, wires, live current), the 🔋 numbers, 🏠 his home's
// bill, 🧩 challenges and 📘 ready boards. Everything here is our own code: a board the AI made is data (cleaned by LabKit.clean)
// and every word on the screen goes in with textContent. Jarvis (the app) talks to this page through one message port.
'use strict';
(function () {
  const S = window.CircuitSim, K = window.LabKit;
  const $ = (id) => document.getElementById(id);
  const cv = $('cv');
  let ctx = cv.getContext('2d');   // (pointed at a picture's canvas while a picture is drawn)
  let sizeFn = null;               // (the picture's size while it is drawn)
  const CELL = 40;
  const EMOJI = '"Noto Color Emoji", "Apple Color Emoji", sans-serif';
  const FONT = '"Noto Sans Telugu", system-ui, "Noto Color Emoji", sans-serif';

  // ---------------------------------------------------------------- talking to Jarvis

  let port = null;
  const outbox = [];
  function post(m) { const s = JSON.stringify(m); if (port) port.postMessage(s); else outbox.push(s); }
  window.addEventListener('message', (e) => {
    if (port || e.data !== 'jarvis-port' || !e.ports || !e.ports[0]) return;
    port = e.ports[0];
    port.onmessage = (ev) => { let m; try { m = JSON.parse(ev.data); } catch (x) { return; } onJarvis(m); };
    outbox.splice(0).forEach((s) => port.postMessage(s));
    post({ t: 'ready' });
  });

  // ---------------------------------------------------------------- state

  let board = { title: '', parts: [], wires: [] };
  let st = {};
  let res = S.solve(board);
  let undo = [], redo = [];
  const view = { z: 1, ox: 20, oy: 50 };
  let sel = null;            // {part: id} | {wire: index}
  let wireFrom = null;       // 'id.term'
  let mode = 'edit';         // edit | meter
  let probes = [];
  let highlight = [];        // ids lit up from the safety list
  let timeSpeed = 0, simSecs = 0, timeTimer = null;
  let tab = 'board';
  let chall = null;          // {ch, broke, best, saved}
  let store = { stars: {}, rate: 7, partHours: {}, home: null, solar: {}, charge: {} };
  let names = {};
  let t0 = performance.now();

  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = String(text); return e; }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); return n; }
  function btn(text, cls, fn) { const b = el('button', 'btn' + (cls ? ' ' + cls : ''), text); b.addEventListener('click', (e) => { e.stopPropagation(); fn(e); }); return b; }
  const r1 = (x) => Math.round(x * 10) / 10;
  function fmtW(w) { w = Math.abs(w); return w >= 1000 ? r1(w / 1000) + ' kW' : w >= 10 ? Math.round(w) + ' W' : w >= 0.1 ? r1(w) + ' W' : Math.round(w * 1000) + ' mW'; }
  function fmtV(v) { v = Math.abs(v); return v >= 10 ? Math.round(v) + ' V' : r1(v) + ' V'; }
  function fmtA(i) { return S.amps(i); }
  function fmtOhm(o) { return o >= 1000 ? r1(o / 1000) + ' kΩ' : Math.round(o) + ' Ω'; }
  function fmtH(h) { if (h == null || !isFinite(h)) return '—'; const H = Math.floor(h), m = Math.round((h - H) * 60); return H > 0 ? H + ' గం ' + (m ? m + ' ని' : '') : m + ' ని'; }
  const rs = (x) => '₹' + Math.round(x).toLocaleString('en-IN');

  // ---------------------------------------------------------------- the board changes

  function snapshot() { return JSON.stringify({ board, st }); }
  function pushUndo() { undo.push(snapshot()); if (undo.length > 40) undo.shift(); redo = []; }

  function change(fn) { pushUndo(); fn(); afterChange(); }

  function afterChange(quiet) {
    const ids = new Set(board.parts.map((p) => p.id));
    for (const k of Object.keys(st)) if (!ids.has(k)) delete st[k];
    if (sel && sel.part && !ids.has(sel.part)) sel = null;
    if (sel && sel.wire != null && sel.wire >= board.wires.length) sel = null;
    resolve(quiet);
    saveSoon();
    if (sheetFor && sheetFor.part) showPart(sheetFor.part, true);
    if (tab !== 'board') renderTab();
  }

  function resolve(quiet) {
    names = K.names(board);
    res = S.solve(board, st);
    st = res.st;
    if (!res.ok) { toast('⛔ ' + res.error, 'bad'); highlight = res.bad || []; }
    for (const e of res.events) { if (!quiet) toast('🔥 ' + e.te, 'bad'); post({ t: 'event', te: e.te }); if (chall) chall.broke = true; }
    if (chall) checkChallenge();
    strip();
    kick();
  }

  function setBoard(b, opts) {
    opts = opts || {};
    if (opts.undo !== false) pushUndo();
    const clean = K.clean(b);
    board = { title: (opts.title != null ? opts.title : clean.title) || '', parts: clean.parts, wires: clean.wires };
    st = opts.keepState ? st : (b && b.state && typeof b.state === 'object' ? JSON.parse(JSON.stringify(b.state)) : {});
    sel = null; wireFrom = null; probes = []; highlight = [];
    resolve(true);
    if (tab !== 'board' && !opts.stay) showTab('board'); // (first: a hidden canvas has no size to fit to)
    else renderTab();
    if (opts.fit !== false) { if (tab === 'board') fit(); else needFit = true; }
    saveSoon();
  }
  let needFit = false;

  let saveT = null;
  function saveSoon() {
    clearTimeout(saveT);
    saveT = setTimeout(() => post({ t: 'changed', board: { title: board.title, parts: board.parts, wires: board.wires, state: st }, summary: summary() }), 1200);
  }

  function summary() {
    const e = S.estimates(board, res, { rate: store.rate, partHours: store.partHours });
    const bat = e.batteries[0];
    return { parts: board.parts.length, loadW: Math.round(e.loadW), hours: bat && bat.hours ? r1(bat.hours) : null, units: r1(e.unitsMonth), rupees: Math.round(e.billMonth) };
  }

  // ---------------------------------------------------------------- the view

  function size() {
    if (sizeFn) return sizeFn();
    const r = cv.getBoundingClientRect(), d = Math.min(window.devicePixelRatio || 1, 2.5);
    const w = Math.round(r.width * d), h = Math.round(r.height * d);
    if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
    return { w: r.width, h: r.height, d };
  }

  function bounds() {
    if (!board.parts.length) return { x0: 0, y0: 0, x1: 8, y1: 6 };
    let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    for (const p of board.parts) { const g = K.geom(p.type), w = (p.rot % 2) ? g.h : g.w, h = (p.rot % 2) ? g.w : g.h; x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x + w); y1 = Math.max(y1, p.y + h + 0.9); }
    return { x0, y0, x1, y1 };
  }

  function fit() {
    const s = size(), b = bounds();
    const top = 46, bot = 70, pad = 18;
    const z = Math.max(0.35, Math.min(1.8, Math.min((s.w - pad * 2) / ((b.x1 - b.x0) * CELL), (s.h - top - bot) / ((b.y1 - b.y0) * CELL))));
    view.z = z;
    view.ox = (s.w - (b.x1 - b.x0) * CELL * z) / 2 - b.x0 * CELL * z;
    view.oy = top + (s.h - top - bot - (b.y1 - b.y0) * CELL * z) / 2 - b.y0 * CELL * z;
    kick();
  }

  const SX = (cx) => cx * CELL * view.z + view.ox, SY = (cy) => cy * CELL * view.z + view.oy;
  function tpos(key) {
    const i = key.lastIndexOf('.'), p = board.parts.find((q) => q.id === key.slice(0, i));
    if (!p) return null;
    const c = K.termPos(p, key.slice(i + 1));
    return c ? [SX(c[0]), SY(c[1])] : null;
  }
  function wirePts(w) {
    const a = tpos(w.a), b = tpos(w.b);
    if (!a || !b) return null;
    if (Math.abs(a[1] - b[1]) < 1 || Math.abs(a[0] - b[0]) < 1) return [a, b];
    const mx = (a[0] + b[0]) / 2;
    return [a, [mx, a[1]], [mx, b[1]], b];
  }
  function partBox(p) {
    const g = K.geom(p.type), w = (p.rot % 2) ? g.h : g.w, h = (p.rot % 2) ? g.w : g.h;
    return { x: SX(p.x), y: SY(p.y), w: w * CELL * view.z, h: h * CELL * view.z };
  }

  // ---------------------------------------------------------------- drawing

  let raf = 0;
  function kick() { if (!raf) raf = requestAnimationFrame(frame); }
  function frame(now) {
    raf = 0;
    if (tab !== 'board') return;
    draw(now);
    const live = res.ok && (res.wires.some((i) => Math.abs(i) > 1e-4) || board.parts.some((p) => (res.parts[p.id] || {}).on && ((S.def(p.type) || {}).spin)) || (res.shorted || []).length);
    if (live || wireFrom || timeSpeed) kick();
  }

  function rr(x, y, w, h, r) { ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(x, y, w, h, r); else ctx.rect(x, y, w, h); }

  function draw(now) {
    const s = size();
    ctx.setTransform(s.d, 0, 0, s.d, 0, 0);
    ctx.fillStyle = '#0a0f15';
    ctx.fillRect(0, 0, s.w, s.h);
    const step = CELL * view.z;
    if (step > 14) {
      ctx.fillStyle = 'rgba(120,160,190,0.13)';
      const x0 = ((view.ox % step) + step) % step, y0 = ((view.oy % step) + step) % step;
      for (let x = x0; x < s.w; x += step) for (let y = y0; y < s.h; y += step) ctx.fillRect(x - 0.8, y - 0.8, 1.6, 1.6);
    }
    if (!board.parts.length) {
      ctx.fillStyle = '#88a5b3';
      ctx.font = '600 16px ' + FONT;
      ctx.textAlign = 'center';
      ctx.fillText('బోర్డ్ ఖాళీగా ఉంది', s.w / 2, s.h * 0.38);
      ctx.font = '14px ' + FONT;
      ctx.fillText('కింద ఏ సర్క్యూట్ కావాలో రాయండి / చెప్పండి,', s.w / 2, s.h * 0.38 + 28);
      ctx.fillText('లేదా ➕ భాగాలు, 📘 నేర్చుకో', s.w / 2, s.h * 0.38 + 50);
      return;
    }
    const t = (now - t0) / 1000;
    const shortIds = new Set(res.shorted || []);
    // wires
    board.wires.forEach((w, i) => {
      const pts = wirePts(w);
      if (!pts) return;
      const I = res.ok ? res.wires[i] || 0 : 0;
      const live = Math.abs(I) > 1e-4;
      const shortW = shortIds.size && (shortIds.has(w.a.split('.')[0]) || shortIds.has(w.b.split('.')[0]));
      ctx.lineWidth = Math.max(2, 3.2 * Math.sqrt(view.z));
      ctx.lineJoin = 'round';
      ctx.strokeStyle = sel && sel.wire === i ? '#ffffff' : shortW && live ? '#ff6b6b' : live ? '#c9a24a' : '#3a4756';
      ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]); for (let k = 1; k < pts.length; k++) ctx.lineTo(pts[k][0], pts[k][1]); ctx.stroke();
      if (live) dots(pts, I, t, shortW);
    });
    // parts
    for (const p of board.parts) drawPart(p, t, shortIds.has(p.id));
    // terminals
    for (const p of board.parts) {
      for (const term of S.def(p.type).terms) {
        const key = p.id + '.' + term, c = tpos(key);
        if (!c) continue;
        const on = wireFrom === key || probes.includes(key);
        ctx.fillStyle = on ? '#74e4ff' : '#0a0f15';
        ctx.strokeStyle = on ? '#74e4ff' : '#88a5b3';
        ctx.lineWidth = 1.6;
        ctx.beginPath(); ctx.arc(c[0], c[1], Math.max(3.5, 5 * view.z), 0, Math.PI * 2); ctx.fill(); ctx.stroke();
        if (S.def(p.type).terms.length > 2 || ['+', '-', 'l', 'n'].includes(term)) {
          ctx.fillStyle = '#88a5b3';
          ctx.font = Math.max(9, 10.5 * view.z) + 'px ' + FONT;
          ctx.textAlign = 'center';
          const lab = { '+': '+', '-': '−', l: 'L', n: 'N', 'b+': 'B+', 'b-': 'B−', 'in-l': 'IN L', 'in-n': 'IN N', 'out-l': 'OUT L', 'out-n': 'OUT N', c: 'C', 1: '1', 2: '2' }[term] || '';
          const g = K.geom(p.type), tt = g.t[term];
          const right = tt && tt[0] >= g.w - 0.01;
          ctx.textAlign = (p.rot || 0) % 2 ? 'center' : right ? 'left' : 'right';
          ctx.fillText(lab, c[0] + ((p.rot || 0) % 2 ? 0 : right ? 8 : -8), c[1] - 7);
        }
      }
    }
    if (wireFrom && pointer.x != null) {
      const c = tpos(wireFrom);
      if (c) { ctx.setLineDash([6, 5]); ctx.strokeStyle = '#74e4ff'; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(c[0], c[1]); ctx.lineTo(pointer.x, pointer.y); ctx.stroke(); ctx.setLineDash([]); }
    }
  }

  function dots(pts, I, t, red) {
    const segs = [];
    let total = 0;
    for (let k = 1; k < pts.length; k++) { const L = Math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1]); segs.push(L); total += L; }
    if (total < 4) return;
    const speed = Math.min(160, 18 + 38 * Math.log10(1 + Math.abs(I) * 20)) * view.z;
    const gap = 18 * Math.max(0.6, view.z);
    let phase = (t * speed) % gap;
    if (I < 0) phase = gap - phase;
    ctx.fillStyle = red ? '#ff6b6b' : '#ffe08a';
    for (let d = phase; d < total; d += gap) {
      let dd = d, k = 0;
      while (k < segs.length && dd > segs[k]) { dd -= segs[k]; k++; }
      if (k >= segs.length) break;
      const a = pts[k], b = pts[k + 1], f = segs[k] ? dd / segs[k] : 0;
      ctx.beginPath(); ctx.arc(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, Math.max(1.8, 2.6 * Math.sqrt(view.z)), 0, Math.PI * 2); ctx.fill();
    }
  }

  function drawPart(p, t, isShort) {
    const d = S.def(p.type), r = res.parts[p.id] || {}, o = S.props(p), s = st[p.id] || {};
    const b = partBox(p), cx = b.x + b.w / 2, cy = b.y + b.h / 2, z = view.z;
    const bad = ['burnt', 'blown', 'tripped', 'short', 'overload', 'dead'].includes(r.state) || isShort;
    // glow behind a light that is on
    if (d.glow && r.on && r.bright > 0.05) {
      const col = p.type === 'led' ? ({ red: '255,80,80', green: '90,255,140', blue: '90,160,255', yellow: '255,230,90', white: '240,248,255' }[o.color] || '255,80,80') : '255,214,100';
      const R = Math.max(b.w, b.h) * (0.55 + 0.6 * Math.min(1.2, r.bright));
      const g = ctx.createRadialGradient(cx, cy, 2, cx, cy, R);
      g.addColorStop(0, 'rgba(' + col + ',' + Math.min(0.85, 0.25 + r.bright * 0.5) + ')');
      g.addColorStop(1, 'rgba(' + col + ',0)');
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.fill();
    }
    rr(b.x + 3, b.y + 3, b.w - 6, b.h - 6, 10 * z);
    ctx.fillStyle = '#121a24';
    ctx.fill();
    ctx.lineWidth = sel && sel.part === p.id ? 2.6 : 1.4;
    const hl = highlight.includes(p.id);
    ctx.strokeStyle = sel && sel.part === p.id ? '#ffffff' : bad ? (Math.floor(t * 3) % 2 ? '#ff6b6b' : '#a33') : hl ? '#ffc861' : r.on ? '#1e6a80' : '#2b3a4a';
    ctx.stroke();
    const es = Math.min(b.w, b.h) * 0.46;
    if (p.type === 'switch') {
      const ax = b.x + b.w * 0.18, bx = b.x + b.w * 0.82, y = cy;
      ctx.strokeStyle = o.on ? '#6be3a4' : '#88a5b3'; ctx.lineWidth = Math.max(2, 4 * z); ctx.lineCap = 'round';
      ctx.beginPath(); ctx.moveTo(ax, y); ctx.lineTo(o.on ? bx : bx - b.w * 0.12, o.on ? y : y - b.h * 0.3); ctx.stroke();
      ctx.fillStyle = ctx.strokeStyle; ctx.beginPath(); ctx.arc(ax, y, 3.5 * z, 0, 7); ctx.arc(bx, y, 3.5 * z, 0, 7); ctx.fill();
    } else if (p.type === 'switch2') {
      const c0 = [b.x + b.w * 0.18, cy], t1 = [b.x + b.w * 0.82, b.y + b.h * 0.25], t2 = [b.x + b.w * 0.82, b.y + b.h * 0.75];
      ctx.strokeStyle = '#6be3a4'; ctx.lineWidth = Math.max(2, 4 * z); ctx.lineCap = 'round';
      const to = o.pos === 2 ? t2 : t1;
      ctx.beginPath(); ctx.moveTo(c0[0], c0[1]); ctx.lineTo(to[0], to[1]); ctx.stroke();
      ctx.fillStyle = '#88a5b3'; [c0, t1, t2].forEach((q) => { ctx.beginPath(); ctx.arc(q[0], q[1], 3.5 * z, 0, 7); ctx.fill(); });
    } else if (p.type === 'fuse') {
      ctx.strokeStyle = s.blown ? '#ff6b6b' : '#c9a24a'; ctx.lineWidth = Math.max(1.5, 2.5 * z);
      rr(b.x + b.w * 0.2, cy - b.h * 0.14, b.w * 0.6, b.h * 0.28, 4 * z); ctx.stroke();
      ctx.beginPath();
      if (s.blown) { ctx.moveTo(b.x + b.w * 0.25, cy); ctx.lineTo(cx - 4 * z, cy - 3 * z); ctx.moveTo(cx + 4 * z, cy + 3 * z); ctx.lineTo(b.x + b.w * 0.75, cy); }
      else { ctx.moveTo(b.x + b.w * 0.25, cy); ctx.lineTo(b.x + b.w * 0.75, cy); }
      ctx.stroke();
    } else if (p.type === 'mcb') {
      rr(cx - b.w * 0.18, b.y + b.h * 0.18, b.w * 0.36, b.h * 0.64, 5 * z); ctx.fillStyle = '#e8eef3'; ctx.fill();
      ctx.fillStyle = s.tripped ? '#ff6b6b' : '#3ddc84';
      rr(cx - b.w * 0.09, s.tripped ? cy + 2 * z : cy - b.h * 0.24, b.w * 0.18, b.h * 0.22, 3 * z); ctx.fill();
    } else if (p.type === 'resistor') {
      ctx.strokeStyle = '#d9b26a'; ctx.lineWidth = Math.max(1.5, 2.6 * z); ctx.beginPath();
      const x0 = b.x + b.w * 0.15, x1 = b.x + b.w * 0.85, n = 6, hh = b.h * 0.12;
      ctx.moveTo(x0, cy); for (let k = 0; k <= n; k++) ctx.lineTo(x0 + (x1 - x0) * (k + 0.5) / (n + 1), cy + (k % 2 ? hh : -hh)); ctx.lineTo(x1, cy); ctx.stroke();
    } else {
      let e = d.emoji;
      if (p.type === 'led') e = { red: '🔴', green: '🟢', blue: '🔵', yellow: '🟡', white: '⚪' }[o.color] || '🔴';
      ctx.save();
      ctx.translate(cx, cy - (p.type === 'battery' || p.type === 'cell' ? 4 * z : 0));
      if (d.spin && r.on) ctx.rotate((t * (4 + 8 * Math.min(1.3, r.bright)) * (r.dir || 1)) % (Math.PI * 2));
      ctx.globalAlpha = r.state === 'burnt' ? 0.35 : (d.load && !r.on && !o.off) ? 0.75 : o.off ? 0.4 : 1;
      ctx.font = es + 'px ' + EMOJI;
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText(e, 0, es * 0.06);
      ctx.restore();
      ctx.globalAlpha = 1;
      if (p.type === 'battery' || p.type === 'cell') {
        const soc = typeof s.soc === 'number' ? s.soc : 1, bw = b.w * 0.6;
        ctx.fillStyle = '#1a2532'; rr(cx - bw / 2, b.y + b.h - 14 * z, bw, 6 * z, 3 * z); ctx.fill();
        ctx.fillStyle = soc < 0.2 ? '#ff6b6b' : soc < 0.5 ? '#ffc861' : '#6be3a4'; rr(cx - bw / 2, b.y + b.h - 14 * z, bw * soc, 6 * z, 3 * z); ctx.fill();
      }
      if (isShort || r.state === 'burnt') { // ✨ sparks
        ctx.fillStyle = '#ffd36b';
        for (let k = 0; k < 6; k++) { const a = t * 7 + k * 1.05; ctx.beginPath(); ctx.arc(cx + Math.cos(a) * b.w * 0.42, cy + Math.sin(a * 1.3) * b.h * 0.42, 2.2 * z, 0, 7); ctx.fill(); }
      }
    }
    // name and reading under the box
    const fs = Math.max(9.5, 11.5 * Math.min(1.25, z));
    ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
    ctx.font = '600 ' + fs + 'px ' + FONT;
    ctx.fillStyle = '#dceef5';
    const nm = p.label || names[p.id] || d.te;
    ctx.fillText(nm.length > 22 ? nm.slice(0, 21) + '…' : nm, cx, b.y + b.h + fs + 1);
    ctx.font = fs + 'px ' + FONT;
    ctx.fillStyle = bad ? '#ff6b6b' : r.on ? '#74e4ff' : '#88a5b3';
    ctx.fillText(reading(p, r, o, s), cx, b.y + b.h + fs * 2 + 3);
  }

  function reading(p, r, o, s) {
    const d = S.def(p.type);
    if (r.state === 'burnt') return 'కాలిపోయింది';
    if (r.state === 'blown') return 'పోయింది ✗';
    if (r.state === 'tripped') return 'ట్రిప్ ↓';
    if (r.state === 'short') return '⚠️ షార్ట్ ' + fmtA(r.i);
    if (r.state === 'overload') return '⛔ ఓవర్‌లోడ్';
    if (r.state === 'dead') return p.type === 'inverter' ? '⛔ బ్యాటరీ లేదు' : '🪫 అయిపోయింది';
    if (o.off) return '⏻ ఆఫ్';
    if (d.load) return r.on ? (Math.abs(r.v) < 30 ? fmtW(r.p) + ' · ' + fmtA(r.i) : fmtW(r.p)) : (Math.abs(r.i) > 1e-4 ? 'మసక ' + fmtW(r.p) : 'ఆఫ్');
    switch (p.type) {
      case 'resistor': return fmtOhm(o.ohm) + (Math.abs(r.i) > 1e-6 ? ' · ' + fmtA(r.i) : '');
      case 'led': return r.on ? fmtA(r.i) : 'ఆఫ్';
      case 'battery': case 'cell': return fmtV(r.v || o.v) + ' · ' + Math.round((typeof s.soc === 'number' ? s.soc : 1) * 100) + '%' + (r.charging || (r.i < -0.05) ? ' ↑' : '');
      case 'mains': return o.on ? '230V · ' + (Math.abs(r.i) > 0.005 ? fmtA(r.i) : 'ఉంది') : 'కరెంట్ లేదు';
      case 'solar': return o.sun < 0.01 ? 'ఎండ లేదు' : fmtW(r.p);
      case 'inverter': return ({ mains: '⚡ మెయిన్', battery: '🔋 బ్యాటరీ' }[r.mode] || 'ఆఫ్') + ' · ' + fmtW(r.loadW || 0);
      case 'charger': return o.v + 'V' + (Math.abs(r.i) > 0.001 ? ' · ' + fmtA(r.i) : '');
      case 'fuse': case 'mcb': return o.amp + ' A' + (Math.abs(r.i) > 0.001 ? ' · ' + fmtA(r.i) : '');
      case 'switch': return o.on ? 'ఆన్' : 'ఆఫ్';
      case 'switch2': return 'C→' + (o.pos === 2 ? '2' : '1');
      default: return '';
    }
  }

  // ---------------------------------------------------------------- touch on the board

  const pointer = { x: null, y: null };
  const downs = new Map();
  let drag = null; // {kind:'part'|'pan'|'pinch', ...}

  function hitTerm(x, y) {
    let best = null, bd = 18;
    for (const p of board.parts) for (const t of S.def(p.type).terms) {
      const c = tpos(p.id + '.' + t);
      if (!c) continue;
      const d = Math.hypot(c[0] - x, c[1] - y);
      if (d < bd) { bd = d; best = p.id + '.' + t; }
    }
    return best;
  }
  function hitPart(x, y) {
    for (let i = board.parts.length - 1; i >= 0; i--) { const p = board.parts[i], b = partBox(p); if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) return p; }
    return null;
  }
  function hitWire(x, y) {
    let best = -1, bd = 11;
    board.wires.forEach((w, i) => {
      const pts = wirePts(w);
      if (!pts) return;
      for (let k = 1; k < pts.length; k++) {
        const a = pts[k - 1], b = pts[k], L2 = (b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2;
        const f = L2 ? Math.max(0, Math.min(1, ((x - a[0]) * (b[0] - a[0]) + (y - a[1]) * (b[1] - a[1])) / L2)) : 0;
        const d = Math.hypot(a[0] + (b[0] - a[0]) * f - x, a[1] + (b[1] - a[1]) * f - y);
        if (d < bd) { bd = d; best = i; }
      }
    });
    return best;
  }
  const local = (e) => { const r = cv.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };

  cv.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    try { cv.setPointerCapture(e.pointerId); } catch (x) { /* */ }
    const q = local(e);
    downs.set(e.pointerId, q);
    pointer.x = q.x; pointer.y = q.y;
    if (downs.size === 2) {
      const [a, b] = [...downs.values()];
      drag = { kind: 'pinch', d: Math.hypot(a.x - b.x, a.y - b.y), z: view.z, cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, ox: view.ox, oy: view.oy };
      return;
    }
    const term = hitTerm(q.x, q.y);
    const p = term ? null : hitPart(q.x, q.y);
    drag = { kind: p ? 'part' : 'pan', id: p && p.id, term, x: q.x, y: q.y, moved: false, px: p && p.x, py: p && p.y, ox: view.ox, oy: view.oy, at: performance.now(), before: p ? snapshot() : null };
  });
  cv.addEventListener('pointermove', (e) => {
    const q = local(e);
    pointer.x = q.x; pointer.y = q.y;
    if (downs.has(e.pointerId)) downs.set(e.pointerId, q);
    if (!drag) { if (wireFrom) kick(); return; }
    if (drag.kind === 'pinch' && downs.size >= 2) {
      const [a, b] = [...downs.values()];
      const z = Math.max(0.3, Math.min(2.6, drag.z * Math.hypot(a.x - b.x, a.y - b.y) / Math.max(10, drag.d)));
      const k = z / drag.z;
      view.z = z;
      view.ox = drag.cx - (drag.cx - drag.ox) * k + ((a.x + b.x) / 2 - drag.cx);
      view.oy = drag.cy - (drag.cy - drag.oy) * k + ((a.y + b.y) / 2 - drag.cy);
      kick();
      return;
    }
    const dx = q.x - drag.x, dy = q.y - drag.y;
    if (!drag.moved && Math.hypot(dx, dy) > 8 && !drag.term) drag.moved = true;
    if (!drag.moved) return;
    if (drag.kind === 'part') {
      const p = board.parts.find((x) => x.id === drag.id);
      if (p) { p.x = drag.px + dx / (CELL * view.z); p.y = drag.py + dy / (CELL * view.z); }
    } else if (drag.kind === 'pan') { view.ox = drag.ox + dx; view.oy = drag.oy + dy; }
    kick();
  });
  function up(e) {
    downs.delete(e.pointerId);
    const d = drag;
    if (!d) return;
    if (d.kind === 'pinch') { if (downs.size === 0) drag = null; return; }
    drag = null;
    const q = local(e);
    if (d.kind === 'part' && d.moved) {
      const p = board.parts.find((x) => x.id === d.id);
      if (p) {
        p.x = Math.round(p.x * 2) / 2; p.y = Math.round(p.y * 2) / 2;
        undo.push(d.before); if (undo.length > 40) undo.shift(); redo = [];
        saveSoon();
      }
      kick();
      return;
    }
    if (d.moved) return;
    tap(q.x, q.y, d, performance.now() - d.at);
  }
  cv.addEventListener('pointerup', up);
  cv.addEventListener('pointercancel', (e) => { downs.delete(e.pointerId); drag = null; });

  function tap(x, y, d, ms) {
    const term = d.term || hitTerm(x, y);
    if (mode === 'meter') {
      if (term) {
        probes.push(term);
        if (probes.length > 2) probes = [term];
        meterSay();
      } else {
        const p = hitPart(x, y);
        if (p) { const r = res.parts[p.id] || {}; hint('🔌 ' + (names[p.id] || p.id) + ': ' + fmtA(r.i || 0) + ' కరెంట్' + (r.v ? ', దాని మీద ' + fmtV(r.v) : '')); }
      }
      kick();
      return;
    }
    if (term) {
      if (!wireFrom) { wireFrom = term; hint('🔗 ఇప్పుడు కలపాల్సిన ఇంకో చుక్క తాకండి (ఖాళీ చోట తాకితే రద్దు)'); }
      else if (wireFrom === term) { wireFrom = null; hint(''); }
      else {
        const a = wireFrom, b = term;
        wireFrom = null;
        hint('');
        if (!board.wires.some((w) => (w.a === a && w.b === b) || (w.a === b && w.b === a))) change(() => board.wires.push({ a, b }));
      }
      kick();
      return;
    }
    if (wireFrom) { wireFrom = null; hint(''); kick(); return; }
    const p = hitPart(x, y);
    if (p) {
      sel = { part: p.id };
      if (p.type === 'switch' && ms < 450) { change(() => { p.props = Object.assign({}, p.props, { on: !S.props(p).on }); }); return; }
      if (p.type === 'switch2' && ms < 450) { change(() => { p.props = Object.assign({}, p.props, { pos: S.props(p).pos === 2 ? 1 : 2 }); }); return; }
      showPart(p.id);
      kick();
      return;
    }
    const w = hitWire(x, y);
    if (w >= 0) { sel = { wire: w }; showWire(w); kick(); return; }
    sel = null; highlight = [];
    closeSheet();
    kick();
  }

  function meterSay() {
    if (probes.length === 1) { hint('🔌 ఎరుపు ప్రోబ్ ' + termName(probes[0]) + ' మీద. నలుపు ప్రోబ్ కి ఇంకో చుక్క తాకండి.'); return; }
    const v = res.ok && res.V ? res.V(probes[0]) - res.V(probes[1]) : 0;
    hint('🔌 ' + termName(probes[0]) + ' → ' + termName(probes[1]) + ': ' + (Math.abs(v) < 0.005 ? '0 V' : (v < 0 ? '−' : '') + fmtV(v)));
  }
  function termName(key) { const i = key.lastIndexOf('.'); const id = key.slice(0, i); return (names[id] || id) + ' ' + key.slice(i + 1).toUpperCase(); }

  // ---------------------------------------------------------------- the strip, the tools, the hint, the toast

  function strip() {
    const box = clear($('strip'));
    if (!board.parts.length) return;
    const e = S.estimates(board, res, { rate: store.rate, partHours: store.partHours });
    box.appendChild(el('span', 'chip', '⚡ ' + fmtW(e.loadW)));
    for (const b of e.batteries) box.appendChild(el('span', 'chip' + (b.soc < 0.2 ? ' bad' : ''), '🔋 ' + Math.round(b.soc * 100) + '%' + (b.hours ? ' · ' + fmtH(b.hours) : b.charging ? ' ↑' : '')));
    const mains = board.parts.filter((p) => p.type === 'mains');
    if (mains.length) {
      const on = S.props(mains[0]).on;
      const c = el('span', 'chip' + (on ? '' : ' warn'), on ? '🏠 కరెంట్ ఉంది' : '🏠 కరెంట్ పోయింది');
      c.addEventListener('click', () => change(() => mains.forEach((m) => { m.props = Object.assign({}, m.props, { on: !on }); })));
      box.appendChild(c);
    }
    const sf = res.ok ? null : '⛔';
    const red = (res.shorted || []).length || res.events.length || sf;
    if (red) box.appendChild(el('span', 'chip bad', '⚠️ ' + (sf || 'చూడండి')));
    if (timeSpeed) box.appendChild(el('span', 'chip', '⏩ +' + fmtH(simSecs / 3600)));
  }

  function tools() {
    const box = clear($('tools'));
    const add = (t, cls, fn) => { const b = el('span', 'tool' + (cls ? ' ' + cls : ''), t); b.addEventListener('click', fn); box.appendChild(b); return b; };
    add('➕ భాగాలు', 'main', () => showTray());
    add('↶', '', () => { if (!undo.length) return; redo.push(snapshot()); const s = JSON.parse(undo.pop()); board = s.board; st = s.st; afterChange(true); });
    add('↷', '', () => { if (!redo.length) return; undo.push(snapshot()); const s = JSON.parse(redo.pop()); board = s.board; st = s.st; afterChange(true); });
    add('🔌 మీటర్', mode === 'meter' ? 'on' : '', () => { mode = mode === 'meter' ? 'edit' : 'meter'; probes = []; wireFrom = null; hint(mode === 'meter' ? '🔌 మీటర్: రెండు చుక్కలు తాకితే వోల్టేజ్; ఒక భాగం తాకితే కరెంట్' : ''); tools(); kick(); });
    add(timeSpeed ? '⏩ ' + ({ 60: '1 ని/సె', 600: '10 ని/సె', 3600: '1 గం/సె' }[timeSpeed]) : '⏩ సమయం', timeSpeed ? 'on' : '', () => setTime());
    add('⚠️ సేఫ్టీ', '', () => showSafety());
    add('⤢ మొత్తం', '', () => fit());
    add('🧹 కొత్తది', '', () => { if (!board.parts.length) return; change(() => { board = { title: '', parts: [], wires: [] }; st = {}; }); exitChallenge(true); post({ t: 'title', title: '' }); });
  }

  function setTime() {
    const seq = [0, 60, 600, 3600];
    timeSpeed = seq[(seq.indexOf(timeSpeed) + 1) % seq.length];
    clearInterval(timeTimer);
    if (timeSpeed) {
      timeTimer = setInterval(() => {
        if (tab !== 'board' && tab !== 'calc') return;
        st = S.advance(board, st, res, timeSpeed * 0.25);
        simSecs += timeSpeed * 0.25;
        resolve(false);
        if (tab === 'calc') renderTab();
      }, 250);
      hint('⏩ సమయం వేగంగా: బ్యాటరీ ఎలా తగ్గుతుందో / నిండుతుందో చూడండి');
    } else { simSecs = 0; hint(''); }
    tools(); strip(); kick();
  }

  let hintT = null;
  function hint(s) { const h = $('hint'); clearTimeout(hintT); if (!s) { h.style.display = 'none'; return; } h.textContent = s; h.style.display = 'block'; hintT = setTimeout(() => { h.style.display = 'none'; }, 7000); }
  let toastT = null;
  function toast(s, kind) { const t = $('toast'); t.textContent = s; t.style.borderColor = kind === 'bad' ? '#ff6b6b' : kind === 'good' ? '#6be3a4' : '#f2b24c'; t.style.display = 'block'; clearTimeout(toastT); toastT = setTimeout(() => { t.style.display = 'none'; }, 4200); }

  // ---------------------------------------------------------------- the sheet: a part, a wire, the tray, the safety list

  let sheetFor = null;
  function openSheet(fill, what) {
    const sh = clear($('sheet'));
    sh.appendChild(el('div', 'grip'));
    fill(sh);
    sh.classList.add('on');
    sheetFor = what || null;
  }
  function closeSheet() { $('sheet').classList.remove('on'); sheetFor = null; }
  $('sheet').addEventListener('click', (e) => { if (e.target.classList.contains('grip')) closeSheet(); });

  const STEPS = {
    ah: [7, 26, 40, 65, 100, 120, 150, 180, 200, 250], mah: [500, 1000, 2000, 2500, 5000, 10000, 20000],
    va: [500, 600, 700, 800, 900, 1100, 1500, 2000, 3000, 5000], amp: [0.5, 1, 2, 3, 5, 6, 10, 15, 16, 20, 25, 30, 32, 40, 63],
    ohm: [10, 22, 47, 100, 220, 330, 470, 680, 1000, 2200, 4700, 10000, 47000, 100000], speed: [0, 1, 2, 3, 4, 5], sun: [0, 0.25, 0.5, 0.75, 1],
    eff: [0.8, 0.85, 0.9, 0.95], charge: [5, 10, 15, 20], wh: [10, 15, 19, 25, 40, 74],
    w: [0.3, 0.75, 1, 3, 5, 9, 10, 15, 20, 28, 40, 55, 60, 75, 100, 150, 200, 250, 330, 370, 400, 500, 540, 750, 1000, 1200, 1500, 2000, 3000, 5000],
    v_battery: [6, 12, 24, 48], v_cell: [1.5, 3, 4.5, 6, 9, 12], v_charger: [5, 9, 12, 14.4, 20, 48, 60], v_solar: [12, 18, 24, 36, 41],
    v: [1.5, 2.5, 5, 6, 9, 12, 24, 48, 110, 230] };
  const UNIT = { v: 'V', ah: 'Ah', mah: 'mAh', va: 'VA', amp: 'A', ohm: 'Ω', w: 'W', speed: '', sun: '', eff: '', charge: 'A', wh: 'Wh' };
  const KEY_TE = { v: 'వోల్ట్స్', ah: 'Ah (సైజు)', mah: 'mAh', va: 'VA (సైజు)', amp: 'ఎన్ని A దాకా', ohm: 'Ω', w: 'వాట్స్', speed: 'స్పీడ్', sun: 'ఎండ', eff: 'సామర్థ్యం', charge: 'ఛార్జింగ్ A', wh: 'బ్యాటరీ Wh' };
  function stepVal(p, k, v, dir) {
    const list = STEPS[k + '_' + p.type] || STEPS[k] || [v];
    if (dir > 0) { for (const x of list) if (x > v + 1e-9) return x; return list[list.length - 1]; }
    for (let i = list.length - 1; i >= 0; i--) if (list[i] < v - 1e-9) return list[i];
    return list[0];
  }

  function showPart(id, refresh) {
    const p = board.parts.find((q) => q.id === id);
    if (!p) { closeSheet(); return; }
    if (refresh && !$('sheet').classList.contains('on')) return;
    const d = S.def(p.type), r = res.parts[id] || {}, o = S.props(p), s = st[id] || {};
    openSheet((sh) => {
      const h = el('h2', '', d.emoji + ' ' + (p.label || names[id] || d.te));
      sh.appendChild(h);
      if (p.label) sh.appendChild(el('div', 'muted', d.te));
      // readings
      const c = el('div', 'card');
      const row = (k, v) => { const x = el('div', 'row'); x.appendChild(el('span', 'k muted', k)); x.appendChild(el('b', '', v)); c.appendChild(x); };
      const stTe = { burnt: '🔥 కాలిపోయింది', blown: '✗ ఫ్యూజ్ పోయింది', tripped: '↓ ట్రిప్ అయింది', short: '⚠️ షార్ట్ సర్క్యూట్', overload: '⛔ ఓవర్‌లోడ్', dead: '🪫 పని చేయడం లేదు' }[r.state];
      if (stTe) row('స్థితి', stTe);
      if (d.load || p.type === 'resistor' || p.type === 'led' || d.cat === 'src') {
        row('వోల్టేజ్ (V)', fmtV(r.v || 0));
        row('కరెంట్ (I)', fmtA(r.i || 0));
        row('పవర్ (W)', fmtW(r.p || 0));
        const R = p.type === 'resistor' ? o.ohm : d.load ? (p.type === 'fan' ? (o.v * o.v) / Math.max(0.01, o.w * (0.35 + 0.65 * o.speed / 5)) : (o.v * o.v) / Math.max(0.01, o.w)) : null;
        if (R && Math.abs(r.i) > 1e-5) c.appendChild(el('div', 'muted', '📏 V = I × R: ' + r1(Math.abs(r.v)) + ' V = ' + (Math.abs(r.i) < 1 ? r1(Math.abs(r.i) * 1000) + ' mA' : r1(Math.abs(r.i)) + ' A') + ' × ' + fmtOhm(R)));
        if (d.load && r.rated > 0) c.appendChild(el('div', 'muted', 'పూర్తి ' + fmtW(r.rated) + ' లో ' + Math.round(Math.min(1.6, r.bright || 0) * 100) + '% వస్తోంది'));
      }
      if (p.type === 'fuse' || p.type === 'mcb' || p.type === 'switch') row('ఇప్పుడు వెళ్తున్న కరెంట్', fmtA(r.i || 0));
      if (p.type === 'battery' || p.type === 'cell') row('ఛార్జ్', Math.round((typeof s.soc === 'number' ? s.soc : 1) * 100) + '%' + (r.i < -0.05 ? ' (నిండుతోంది ↑)' : ''));
      if (p.type === 'inverter') { row('పని', { mains: 'కరెంట్ ఉంది: పంపుతోంది, బ్యాటరీ నింపుతోంది', battery: 'కరెంట్ లేదు: బ్యాటరీ నుంచి', overload: 'ఓవర్‌లోడ్: ఆగిపోయింది', nobattery: 'బ్యాటరీ లేదు' }[r.mode] || 'ఆఫ్'); row('లోడ్', fmtW(r.loadW || 0) + ' / ' + Math.round(o.va * 0.8) + ' W'); }
      if (c.childNodes.length) sh.appendChild(c);
      // settings
      const set = el('div', 'card');
      for (const k of Object.keys(d.props)) {
        const v0 = o[k];
        const x = el('div', 'row');
        if (typeof d.props[k] === 'number') {
          x.appendChild(el('span', 'k', KEY_TE[k] || k));
          x.appendChild(btn('−', 'small', () => change(() => { p.props = Object.assign({}, p.props, { [k]: stepVal(p, k, v0, -1) }); })));
          x.appendChild(el('span', 'val', (k === 'sun' ? Math.round(v0 * 100) + '%' : k === 'eff' ? Math.round(v0 * 100) + '%' : v0) + ' ' + (UNIT[k] || '')));
          x.appendChild(btn('+', 'small', () => change(() => { p.props = Object.assign({}, p.props, { [k]: stepVal(p, k, v0, 1) }); })));
        } else if (typeof d.props[k] === 'boolean') {
          const te = k === 'on' ? (p.type === 'mains' ? (v0 ? '🏠 కరెంట్ ఉంది' : '🏠 కరెంట్ పోయింది') : (v0 ? 'ఆన్' : 'ఆఫ్')) : k;
          x.appendChild(el('span', 'k', p.type === 'mains' ? 'కరెంట్' : 'స్విచ్'));
          x.appendChild(btn(te, v0 ? 'main' : 'warn', () => change(() => { p.props = Object.assign({}, p.props, { [k]: !v0 }); })));
        } else if (k === 'color') {
          x.appendChild(el('span', 'k', 'రంగు'));
          for (const col of ['red', 'green', 'blue', 'yellow', 'white']) x.appendChild(btn({ red: '🔴', green: '🟢', blue: '🔵', yellow: '🟡', white: '⚪' }[col], v0 === col ? 'main small' : 'small', () => change(() => { p.props = Object.assign({}, p.props, { color: col }); })));
        } else if (k === 'pos') {
          x.appendChild(el('span', 'k', 'C ని దేనికి'));
          x.appendChild(btn('1', v0 !== 2 ? 'main small' : 'small', () => change(() => { p.props = Object.assign({}, p.props, { pos: 1 }); })));
          x.appendChild(btn('2', v0 === 2 ? 'main small' : 'small', () => change(() => { p.props = Object.assign({}, p.props, { pos: 2 }); })));
        }
        if (typeof d.props[k] === 'number' && p.type === 'switch2') continue;
        set.appendChild(x);
      }
      if (d.load) {
        const hrs = typeof store.partHours[id] === 'number' ? store.partHours[id] : (S.HOURS[p.type] != null ? S.HOURS[p.type] : 2);
        const x = el('div', 'row');
        x.appendChild(el('span', 'k', 'రోజుకి ఎన్ని గంటలు (బిల్లు కోసం)'));
        x.appendChild(btn('−', 'small', () => { store.partHours[id] = Math.max(0, r1(hrs - (hrs > 2 ? 1 : 0.5))); keep('partHours'); showPart(id, true); }));
        x.appendChild(el('span', 'val', hrs + ' గం'));
        x.appendChild(btn('+', 'small', () => { store.partHours[id] = Math.min(24, r1(hrs + (hrs >= 2 ? 1 : 0.5))); keep('partHours'); showPart(id, true); }));
        set.appendChild(x);
      }
      if (set.childNodes.length) sh.appendChild(set);
      // actions
      const act = el('div', 'row');
      if (d.load || p.type === 'led') act.appendChild(btn(o.off ? '⏻ ఆన్ చెయ్' : '⏻ ఆఫ్ చెయ్', '', () => change(() => { p.props = Object.assign({}, p.props, { off: !o.off }); })));
      if (r.state === 'blown') act.appendChild(btn('🧯 కొత్త ఫ్యూజ్', 'main', () => change(() => { st[id].blown = false; })));
      if (r.state === 'tripped') act.appendChild(btn('🎚️ MCB పైకి వెయ్', 'main', () => change(() => { st[id].tripped = false; })));
      if (r.state === 'overload') act.appendChild(btn('🔁 ఇన్వర్టర్ రీసెట్', 'main', () => change(() => { st[id].overload = false; })));
      if (r.state === 'burnt') act.appendChild(btn('🔁 కొత్తది పెట్టు', 'main', () => change(() => { st[id].burnt = false; })));
      if (p.type === 'battery' || p.type === 'cell') act.appendChild(btn('🔋 100% నింపు', '', () => change(() => { st[id].soc = 1; st[id].dead = false; })));
      act.appendChild(btn('🔄 తిప్పు', '', () => change(() => { p.rot = ((p.rot || 0) + 1) % 4; })));
      act.appendChild(btn('🗑️ తీసేయి', 'warn', () => { change(() => { board.parts = board.parts.filter((q) => q.id !== id); board.wires = board.wires.filter((w) => !w.a.startsWith(id + '.') && !w.b.startsWith(id + '.')); }); closeSheet(); }));
      sh.appendChild(act);
      sh.appendChild(el('div', 'muted', 'ℹ️ ' + d.info));
    }, { part: id });
  }

  function showWire(i) {
    const w = board.wires[i];
    if (!w) return;
    openSheet((sh) => {
      sh.appendChild(el('h2', '', '〰️ వైర్'));
      sh.appendChild(el('div', 'muted', termName(w.a) + '  ↔  ' + termName(w.b)));
      const I = res.ok ? res.wires[i] || 0 : 0;
      const c = el('div', 'card');
      c.appendChild(el('div', 'big', Math.abs(I) > 1e-4 ? fmtA(I) : 'కరెంట్ లేదు'));
      if (Math.abs(I) > 1e-4) c.appendChild(el('div', 'muted', 'చుక్కలు కదిలే దిశలోనే కరెంట్ వెళ్తోంది; ఎంత వేగమైతే అంత ఎక్కువ కరెంట్.'));
      sh.appendChild(c);
      sh.appendChild(btn('🗑️ వైర్ తీసేయి', 'warn', () => { change(() => { board.wires.splice(i, 1); }); sel = null; closeSheet(); }));
    }, { wire: i });
  }

  const TRAY = [
    ['⚡ కరెంట్ ఇచ్చేవి', ['mains', 'battery', 'cell', 'solar', 'inverter', 'charger']],
    ['🎛️ కంట్రోల్ / రక్షణ', ['switch', 'switch2', 'fuse', 'mcb']],
    ['💡 ఇంట్లో వాడేవి', ['ledbulb', 'bulb', 'tube', 'fan', 'tv', 'fridge', 'mixie', 'ac', 'iron', 'geyser', 'motor']],
    ['🚗 కార్ / 12V', ['dclamp', 'headlight', 'horn', 'phone']],
    ['🔧 ఎలక్ట్రానిక్స్ కిట్', ['smallbulb', 'led', 'resistor', 'buzzer', 'dcmotor']]];

  function showTray() {
    openSheet((sh) => {
      sh.appendChild(el('h2', '', '➕ భాగాలు'));
      sh.appendChild(el('div', 'muted', 'తాకితే బోర్డ్ మీదకి వస్తుంది. తర్వాత రెండు చుక్కలు తాకితే వైర్.'));
      for (const [te, types] of TRAY) {
        sh.appendChild(el('h3', '', te));
        const g = el('div', 'grid');
        for (const type of types) {
          const d = S.def(type);
          const it = el('div', 'item');
          it.appendChild(el('span', 'e', d.emoji));
          it.appendChild(document.createTextNode(d.te));
          it.addEventListener('click', () => { addPart(type); closeSheet(); });
          g.appendChild(it);
        }
        sh.appendChild(g);
      }
    }, { tray: true });
  }

  function addPart(type) {
    const g = K.geom(type), s = size();
    const cx = (s.w / 2 - view.ox) / (CELL * view.z) - g.w / 2, cy = (s.h / 2 - view.oy) / (CELL * view.z) - g.h / 2;
    const free = (x, y) => !board.parts.some((p) => { const h = K.geom(p.type); return x < p.x + h.w + 0.5 && x + g.w + 0.5 > p.x && y < p.y + h.h + 1 && y + g.h + 1 > p.y; });
    let at = [Math.round(cx), Math.round(cy)];
    outer: for (let r = 0; r < 14; r++) for (let dx = -r; dx <= r; dx++) for (let dy = -r; dy <= r; dy++) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
      if (free(Math.round(cx) + dx * 2, Math.round(cy) + dy * 2)) { at = [Math.round(cx) + dx * 2, Math.round(cy) + dy * 2]; break outer; }
    }
    const id = K.newId(board, type);
    change(() => { board.parts.push({ id, type, x: at[0], y: at[1], rot: 0, props: {} }); });
    sel = { part: id };
    hint('🔗 ' + S.def(type).te + ' పెట్టాను. దాని చుక్క తాకి, తర్వాత కలపాల్సిన చుక్క తాకండి.');
    kick();
  }

  function showSafety() {
    const list = K.safety(board);
    openSheet((sh) => {
      sh.appendChild(el('h2', '', '⚠️ సేఫ్టీ చెక్'));
      sh.appendChild(el('div', 'muted', 'నేర్చుకోవడానికి మాత్రమే. ఇంట్లో అసలు వైరింగ్ లైసెన్స్ ఉన్న ఎలక్ట్రీషియన్ తోనే చేయించాలి.'));
      const L = el('div', 'list');
      for (const s of list) {
        const x = el('div', { red: 'red', amber: 'amber', ok: 'green', info: 'muted' }[s.level] || '', s.te);
        if (s.ids && s.ids.length) { x.appendChild(document.createTextNode(' ')); const b = btn('చూపించు', 'small', () => { highlight = s.ids; showTab('board'); closeSheet(); kick(); }); x.appendChild(b); }
        L.appendChild(x);
      }
      sh.appendChild(L);
    }, { safety: true });
  }

  // ---------------------------------------------------------------- 🧩 challenges

  function startChallenge(ch) {
    chall = { ch, broke: false, best: 0, before: board.parts.length ? JSON.stringify({ board, st }) : null };
    setBoard(Object.assign(ch.board(), { title: '🧩 ' + ch.te }), { title: '🧩 ' + ch.te });
    showGoal();
    post({ t: 'title', title: '🧩 ' + ch.te });
  }
  function showGoal() {
    const g = $('goal');
    if (!chall) { g.style.display = 'none'; return; }
    clear(g);
    const x = el('span', 'x', '✕ బయటికి');
    x.addEventListener('click', () => exitChallenge(false));
    g.appendChild(x);
    g.appendChild(el('b', '', '🎯 ' + chall.ch.te + ': '));
    g.appendChild(document.createTextNode(chall.ch.goal));
    const got = store.stars[chall.ch.id] || 0;
    if (got) { g.appendChild(document.createTextNode('  ')); g.appendChild(el('span', 'stars', '⭐'.repeat(got))); }
    g.style.display = 'block';
  }
  function exitChallenge(silent) {
    if (!chall) return;
    const before = chall.before;
    chall = null;
    showGoal();
    if (!silent && before) { const s = JSON.parse(before); board = s.board; st = s.st; afterChange(true); fit(); post({ t: 'title', title: board.title || '' }); }
  }
  function checkChallenge() {
    const s = K.stars(chall.ch, board, res, chall.broke);
    if (s > chall.best) {
      chall.best = s;
      const was = store.stars[chall.ch.id] || 0;
      if (s > was) { store.stars[chall.ch.id] = s; keep('stars'); }
      toast('🎉 గెలిచారు! ' + '⭐'.repeat(s) + (s < 3 ? '  (' + (s === 1 ? 'తక్కువ వైర్లతో' : 'ఏదీ కాల్చకుండా / షార్ట్ లేకుండా') + ' చేస్తే ఇంకో ⭐)' : ''), 'good');
      post({ t: 'won', te: chall.ch.te, stars: s });
      showGoal();
    }
  }

  // ---------------------------------------------------------------- tabs

  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => showTab(t.dataset.tab)));
  function showTab(name) {
    tab = name;
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('on', t.dataset.tab === name));
    document.querySelectorAll('main > section').forEach((s) => s.classList.toggle('on', s.id === name));
    closeSheet();
    renderTab();
    if (name === 'board') { if (needFit) { needFit = false; fit(); } kick(); }
  }
  function renderTab() {
    if (tab === 'calc') renderCalc();
    else if (tab === 'home') renderHome();
    else if (tab === 'games') renderGames();
    else if (tab === 'learn') renderLearn();
  }

  function numInput(val, on, step) { const i = el('input'); i.type = 'number'; i.inputMode = 'decimal'; i.step = step || 'any'; i.value = val == null ? '' : val; i.addEventListener('change', () => on(i.value === '' ? null : +i.value)); return i; }
  function keep(key) { post({ t: 'store', key, value: store[key] }); }

  // 🔋 the numbers
  function renderCalc() {
    const sec = clear($('calc'));
    const e = S.estimates(board, res, { rate: store.rate, partHours: store.partHours });
    sec.appendChild(el('h2', '', '🔋 లెక్కలు'));
    if (!board.parts.length) sec.appendChild(el('div', 'muted', 'బోర్డ్ ఖాళీగా ఉంది: ముందు ఒక సర్క్యూట్ పెట్టండి (📘 నేర్చుకో లో రెడీ బోర్డులు ఉన్నాయి). కింద ఛార్జింగ్, సోలార్ లెక్కలు బోర్డ్ లేకుండా కూడా పని చేస్తాయి.'));
    const c0 = el('div', 'card');
    c0.appendChild(el('div', 'muted', 'ఇప్పుడు నడుస్తున్నవి మొత్తం'));
    c0.appendChild(el('div', 'big', fmtW(e.loadW)));
    if (board.parts.length) sec.appendChild(c0);
    for (const b of e.batteries) {
      const c = el('div', 'card');
      c.appendChild(el('h3', '', '🔋 ' + b.te));
      const bar = el('div', 'bar ' + (b.soc < 0.2 ? 'red' : b.soc < 0.5 ? 'gold' : 'green')); const i = el('i'); i.style.width = Math.round(b.soc * 100) + '%'; bar.appendChild(i); c.appendChild(bar);
      c.appendChild(el('div', '', Math.round(b.soc * 100) + '% · ' + (b.charging ? 'నిండుతోంది ↑' : b.w > 0.5 ? fmtW(b.w) + ' ఇస్తోంది' : 'ఏమీ ఇవ్వడం లేదు')));
      if (b.hours) c.appendChild(el('div', 'big cyan', '⏳ సుమారు ' + fmtH(b.hours)));
      c.appendChild(el('div', 'muted', b.hours ? 'ఈ లోడ్ తో ఇంకా ఇంతసేపు వస్తుంది (80% వరకు వాడితే; పాత బ్యాటరీ అయితే తక్కువ).' : 'లోడ్ పెడితే ఎంతసేపు వస్తుందో చూపిస్తాను.'));
      sec.appendChild(c);
    }
    for (const v of e.inverters) {
      const c = el('div', 'card');
      c.appendChild(el('h3', '', '🔌 ' + v.te + ' (' + v.va + ' VA)'));
      const bar = el('div', 'bar ' + (v.pct > 1 ? 'red' : v.pct > 0.8 ? 'gold' : '')); const i = el('i'); i.style.width = Math.min(100, Math.round(v.pct * 100)) + '%'; bar.appendChild(i); c.appendChild(bar);
      c.appendChild(el('div', '', fmtW(v.w) + ' / ' + v.maxW + ' W (' + Math.round(v.pct * 100) + '%)'));
      c.appendChild(el('div', 'muted', 'ఈ లోడ్ కి కనీసం ' + Math.max(v.needVA, 300) + ' VA ఇన్వర్టర్ సరిపోతుంది (VA × 0.8 ≈ W).'));
      sec.appendChild(c);
    }
    for (const s of e.solar) {
      const c = el('div', 'card');
      c.appendChild(el('h3', '', '☀️ ' + s.te + ' (' + s.peak + ' W)'));
      c.appendChild(el('div', '', 'ఇప్పుడు ' + fmtW(s.w)));
      if (s.sunHoursToFill != null) c.appendChild(el('div', 'muted', s.sunHoursToFill > 0 ? 'బ్యాటరీ నిండటానికి సుమారు ' + fmtH(s.sunHoursToFill) + ' మంచి ఎండ' : 'బ్యాటరీ నిండుగా ఉంది') );
      if (s.fullBatterySunHours != null) c.appendChild(el('div', 'muted', 'ఖాళీ బ్యాటరీ పూర్తిగా నిండటానికి సుమారు ' + fmtH(s.fullBatterySunHours) + ' ఎండ'));
      sec.appendChild(c);
    }
    // 🧾 the month
    if (e.loads.length) {
      const c = el('div', 'card');
      c.appendChild(el('h3', '', '🧾 నెల కరెంట్ (230V వాటికి)'));
      const rr2 = el('div', 'row'); rr2.appendChild(el('span', 'k', 'ఒక యూనిట్ ధర (₹, మీ బిల్లు మీద)'));
      rr2.appendChild(numInput(store.rate, (v) => { store.rate = v == null ? 7 : Math.max(0, v); keep('rate'); renderCalc(); }, 0.5));
      c.appendChild(rr2);
      const tb = el('table'), hd = el('tr');
      ['పరికరం', 'W', 'గం/రోజు', 'యూనిట్లు', '₹'].forEach((h, i) => { const th = el('th', i ? 'num' : '', h); hd.appendChild(th); });
      tb.appendChild(hd);
      for (const l of e.loads.slice().sort((a, b) => b.units - a.units)) {
        const tr = el('tr');
        tr.appendChild(el('td', '', names[l.id] || l.te));
        tr.appendChild(el('td', 'num', fmtW(l.ratedW)));
        tr.appendChild(el('td', 'num', String(l.hours)));
        tr.appendChild(el('td', 'num', String(r1(l.units))));
        tr.appendChild(el('td', 'num', rs(l.rupees)));
        tb.appendChild(tr);
      }
      c.appendChild(tb);
      c.appendChild(el('div', 'big', '≈ ' + r1(e.unitsMonth) + ' యూనిట్లు · ' + rs(e.billMonth) + ' / నెల'));
      c.appendChild(el('div', 'muted', 'గంటలు మార్చాలంటే బోర్డ్ లో ఆ పరికరం తాకండి. స్లాబ్‌లు, ఫిక్స్‌డ్ ఛార్జీలు కలపలేదు: అంచనా మాత్రమే.'));
      sec.appendChild(c);
    }
    // 🔦 the power cut plan
    if (e.batteries.length) {
      const c = el('div', 'card');
      c.appendChild(el('h3', '', '🔦 కరెంట్ పోతే ప్లాన్'));
      const want = store.cutHours || 8;
      const row = el('div', 'row'); row.appendChild(el('span', 'k', 'బ్యాటరీ ఎన్ని గంటలు రావాలి?'));
      row.appendChild(btn('−', 'small', () => { store.cutHours = Math.max(1, want - 1); keep('cutHours'); renderCalc(); }));
      row.appendChild(el('span', 'val', want + ' గం'));
      row.appendChild(btn('+', 'small', () => { store.cutHours = Math.min(48, want + 1); keep('cutHours'); renderCalc(); }));
      c.appendChild(row);
      const plan = S.planBackup(board, res, want);
      if (plan && (plan.keep.length || plan.off.length)) {
        c.appendChild(el('div', '', '✅ ఉంచండి: ' + (plan.keep.map((l) => names[l.id] || l.te).join(', ') || 'ఏమీ లేదు')));
        if (plan.off.length) c.appendChild(el('div', 'amber', '⏻ ఆపండి: ' + plan.off.map((l) => names[l.id] || l.te).join(', ')));
        c.appendChild(el('div', 'muted', 'అలా అయితే సుమారు ' + fmtH(plan.lastsHours) + ' వస్తుంది (' + plan.budgetW + ' W లోపు).'));
        if (plan.off.length) c.appendChild(btn('⏻ ఇలా చేయి (బోర్డ్ లో)', 'main', () => { change(() => { for (const l of plan.off) { const p = board.parts.find((q) => q.id === l.id); if (p) p.props = Object.assign({}, p.props, { off: true }); } }); toast('⏻ ' + plan.off.length + ' ఆపేశాను', 'good'); }));
      } else c.appendChild(el('div', 'muted', 'బ్యాటరీ నుంచి నడిచే లోడ్ పెడితే (⚡ కరెంట్ పోయింది) ప్లాన్ చూపిస్తాను.'));
      sec.appendChild(c);
    }
    renderCharge(sec);
    renderSolar(sec, e);
    const sc = el('div', 'card');
    sc.appendChild(el('h3', '', '⚠️ సేఫ్టీ చెక్'));
    const list = board.parts.length ? K.safety(board) : [];
    const L = el('div', 'list');
    for (const s of list.slice(0, 6)) L.appendChild(el('div', { red: 'red', amber: 'amber', ok: 'green', info: 'muted' }[s.level] || '', s.te));
    if (!list.length) L.appendChild(el('div', 'muted', 'బోర్డ్ మీద సర్క్యూట్ పెడితే చెక్ చేస్తాను.'));
    sc.appendChild(L);
    if (list.length > 6) sc.appendChild(btn('అన్నీ చూడు', 'small', () => { showTab('board'); showSafety(); }));
    sec.appendChild(sc);
  }

  const DEVICES = [
    ['phone', '📱 ఫోన్', 'mah', 5000, 3.85, 20], ['powerbank', '🔋 పవర్‌బ్యాంక్', 'mah', 10000, 3.7, 18], ['bike', '🏍️ బైక్ బ్యాటరీ', 'kwh', 5, 0, 1200],
    ['car', '🚗 కార్ బ్యాటరీ', 'ah', 40, 12, 60], ['inv', '🔋 ఇన్వర్టర్ బ్యాటరీ', 'ah', 150, 12, 180], ['custom', '✏️ మీరే', 'wh', 100, 0, 50]];
  function renderCharge(sec) {
    const ch = store.charge || (store.charge = {});
    const dev = DEVICES.find((d) => d[0] === ch.dev) || DEVICES[0];
    const c = el('div', 'card');
    c.appendChild(el('h3', '', '⏱️ ఛార్జింగ్ టైమ్'));
    const row0 = el('div', 'row');
    for (const d of DEVICES) row0.appendChild(btn(d[1], d === dev ? 'main small' : 'small', () => { store.charge = { dev: d[0] }; keep('charge'); renderCalc(); }));
    c.appendChild(row0);
    const cap = ch.cap != null ? ch.cap : dev[3], volts = ch.volts != null ? ch.volts : dev[4], w = ch.w != null ? ch.w : dev[5], from = ch.from != null ? ch.from : 20;
    const set = (k, v) => { ch.dev = dev[0]; ch[k] = v; keep('charge'); renderCalc(); };
    const unit = { mah: 'mAh', kwh: 'kWh', ah: 'Ah', wh: 'Wh' }[dev[2]];
    const r1r = el('div', 'row'); r1r.appendChild(el('span', 'k', 'బ్యాటరీ సైజు (' + unit + ')')); r1r.appendChild(numInput(cap, (v) => set('cap', v))); c.appendChild(r1r);
    if (dev[2] === 'mah' || dev[2] === 'ah') { const r2 = el('div', 'row'); r2.appendChild(el('span', 'k', 'బ్యాటరీ వోల్ట్స్')); r2.appendChild(numInput(volts, (v) => set('volts', v))); c.appendChild(r2); }
    const r3 = el('div', 'row'); r3.appendChild(el('span', 'k', 'ఛార్జర్ వాట్స్ (W)')); r3.appendChild(numInput(w, (v) => set('w', v))); c.appendChild(r3);
    const r4 = el('div', 'row'); r4.appendChild(el('span', 'k', 'ఇప్పుడు ఎంత % ఉంది')); r4.appendChild(numInput(from, (v) => set('from', v))); c.appendChild(r4);
    const wh = dev[2] === 'mah' ? (cap || 0) / 1000 * (volts || 3.7) : dev[2] === 'ah' ? (cap || 0) * (volts || 12) : dev[2] === 'kwh' ? (cap || 0) * 1000 : (cap || 0);
    const h = K.chargeHours(wh, w || 1, 1 - Math.max(0, Math.min(100, from || 0)) / 100);
    c.appendChild(el('div', 'big cyan', '⏳ సుమారు ' + fmtH(h)));
    c.appendChild(el('div', 'muted', r1(wh) + ' Wh బ్యాటరీ · ' + (from || 0) + '% నుంచి 100% వరకు · ఛార్జర్ నష్టాలు, చివరి భాగం నెమ్మదిగా అవ్వడం కలిపి. ' + (dev[0] === 'bike' ? 'మీ బైక్ బ్యాటరీ kWh, ఛార్జర్ W దాని పుస్తకం / డాష్‌బోర్డ్ లో చూసి పెట్టండి.' : '')));
    sec.appendChild(c);
  }

  function renderSolar(sec, e) {
    const o = store.solar || (store.solar = {});
    const homeDaily = store.home ? K.homeBill(store.home).dailyUnits : 0;
    const daily = o.dailyUnits != null ? o.dailyUnits : r1(homeDaily || e.unitsMonth / 30 || 5);
    const c = el('div', 'card');
    c.appendChild(el('h3', '', '☀️ సోలార్ ప్లానర్'));
    const field = (k, te, v) => { const r = el('div', 'row'); r.appendChild(el('span', 'k', te)); r.appendChild(numInput(v, (x) => { o[k] = x; keep('solar'); renderCalc(); })); c.appendChild(r); };
    field('dailyUnits', 'రోజుకి యూనిట్లు' + (homeDaily ? ' (🏠 నుంచి ' + r1(homeDaily) + ')' : ''), daily);
    field('sunHours', 'రోజుకి మంచి ఎండ గంటలు', o.sunHours != null ? o.sunHours : 5);
    field('panelW', 'ఒక ప్యానెల్ వాట్స్', o.panelW != null ? o.panelW : 540);
    field('nightUnits', 'రాత్రికి కావాల్సిన యూనిట్లు (బ్యాటరీ)', o.nightUnits != null ? o.nightUnits : 0);
    field('batteryV', 'బ్యాటరీ సిస్టమ్ వోల్ట్స్', o.batteryV != null ? o.batteryV : 24);
    c.appendChild(el('div', 'muted', 'ధరలు మీరే పెట్టండి (షాపులో అడిగినవి); ఖాళీగా ఉంటే ఖర్చు లెక్క వేయను:'));
    field('pricePerW', 'ప్యానెల్ ధర ₹ / వాట్', o.pricePerW);
    field('batteryPrice', 'బ్యాటరీ ధర ₹', o.batteryPrice);
    field('inverterPrice', 'ఇన్వర్టర్ ధర ₹', o.inverterPrice);
    field('otherPrice', 'స్టాండ్, వైర్లు, బిగింపు ₹', o.otherPrice);
    const plan = K.solarPlan(Object.assign({}, o, { dailyUnits: daily, rate: store.rate }));
    const out = el('div', 'card');
    out.appendChild(el('div', 'big cyan', '☀️ ' + plan.panels + ' ప్యానెల్స్ · ' + r1(plan.kw) + ' kW'));
    out.appendChild(el('div', '', 'పైకప్పు సుమారు ' + Math.round(plan.areaSqft) + ' sq ft · నెలకి సుమారు ' + Math.round(plan.genMonth) + ' యూనిట్లు తయారు'));
    if (plan.batteryAh) out.appendChild(el('div', '', '🔋 రాత్రికి సుమారు ' + plan.batteryAh + ' Ah (' + plan.batteryV + 'V)'));
    out.appendChild(el('div', '', '💰 నెలకి సుమారు ' + rs(plan.saveMonth) + ' ఆదా (యూనిట్ ₹' + store.rate + ')'));
    if (plan.cost != null) out.appendChild(el('div', 'gold', 'మొత్తం ఖర్చు ' + rs(plan.cost) + ' · సుమారు ' + r1(plan.paybackYears) + ' ఏళ్ళలో తిరిగి వస్తుంది'));
    else out.appendChild(el('div', 'muted', 'ధరలు పెడితే ఎన్నేళ్ళలో డబ్బు తిరిగి వస్తుందో చెబుతాను.'));
    out.appendChild(el('div', 'muted', 'అంచనా మాత్రమే: నీడ, దిశ, ప్రభుత్వ సబ్సిడీ, నెట్ మీటరింగ్ బట్టి మారుతుంది.'));
    c.appendChild(out);
    sec.appendChild(c);
  }

  // 🏠 the home
  function renderHome() {
    const sec = clear($('home'));
    const home = store.home || (store.home = { rate: store.rate, rooms: [{ name: 'హాల్', items: [] }] });
    sec.appendChild(el('h2', '', '🏠 మా ఇంటి బోర్డ్'));
    sec.appendChild(el('div', 'muted', 'గదులు, వాటిలో పరికరాలు ఒక్కసారి పెడితే చాలు: ఏది ఎక్కువ కరెంట్ తింటోంది, బిల్లులో దేనికి ఎంత పోతోంది చూపిస్తాను.'));
    const save = () => { home.rate = store.rate; keep('home'); renderHome(); };
    home.rooms.forEach((room, ri) => {
      const c = el('div', 'card');
      const hr = el('div', 'row');
      const name = el('input'); name.value = room.name; name.addEventListener('change', () => { room.name = name.value.slice(0, 24) || 'గది'; save(); });
      hr.appendChild(name);
      hr.appendChild(btn('🗑️', 'small', () => { home.rooms.splice(ri, 1); save(); }));
      c.appendChild(hr);
      room.items.forEach((it, ii) => {
        const r = el('div', 'row');
        r.appendChild(el('span', 'k', (K.HOME_ITEMS.find((x) => x[0] === it.kind) || [0, it.kind])[1]));
        r.appendChild(btn('−', 'small', () => { it.count = Math.max(0, it.count - 1); if (!it.count) room.items.splice(ii, 1); save(); }));
        r.appendChild(el('span', 'val', '×' + it.count));
        r.appendChild(btn('+', 'small', () => { it.count++; save(); }));
        const w = numInput(it.w, (v) => { it.w = Math.max(0, v || 0); save(); }); w.style.width = '70px'; r.appendChild(w); r.appendChild(el('span', 'muted', 'W'));
        const h = numInput(it.hours, (v) => { it.hours = Math.max(0, Math.min(24, v || 0)); save(); }); h.style.width = '62px'; r.appendChild(h); r.appendChild(el('span', 'muted', 'గం'));
        c.appendChild(r);
      });
      const add = el('select');
      const o0 = el('option', '', '➕ పరికరం కలుపు…'); o0.value = ''; add.appendChild(o0);
      for (const x of K.HOME_ITEMS) { const o = el('option', '', x[1] + ' (' + x[2] + ' W)'); o.value = x[0]; add.appendChild(o); }
      add.addEventListener('change', () => { const x = K.HOME_ITEMS.find((q) => q[0] === add.value); if (x) { room.items.push({ kind: x[0], count: 1, w: x[2], hours: x[3] }); save(); } });
      c.appendChild(add);
      sec.appendChild(c);
    });
    sec.appendChild(btn('➕ గది', '', () => { home.rooms.push({ name: ['బెడ్‌రూమ్', 'వంటగది', 'బాత్‌రూమ్', 'బయట', 'పైన'][Math.min(4, home.rooms.length - 1)] || 'గది', items: [] }); save(); }));
    const bill = K.homeBill(Object.assign({}, home, { rate: store.rate }));
    if (bill.rows.length) {
      const c = el('div', 'card');
      c.appendChild(el('h3', '', '🧾 నెల బిల్లు అంచనా'));
      c.appendChild(el('div', 'big', '≈ ' + Math.round(bill.units) + ' యూనిట్లు · ' + rs(bill.rupees)));
      const top = bill.rows[0].units || 1;
      for (const r of bill.rows.slice(0, 10)) {
        c.appendChild(el('div', '', r.te + ' ×' + r.count + ' (' + r.room + '): ' + r1(r.units) + ' యూనిట్లు · ' + rs(r.rupees) + ' (' + Math.round(r.units / Math.max(1e-9, bill.units) * 100) + '%)'));
        const bar = el('div', 'bar gold'); const i = el('i'); i.style.width = Math.round(r.units / top * 100) + '%'; bar.appendChild(i); c.appendChild(bar);
      }
      c.appendChild(el('div', 'muted', 'యూనిట్ ధర ₹' + store.rate + ' (🔋 లెక్కలు లో మార్చొచ్చు). స్లాబ్‌లు, ఫిక్స్‌డ్ ఛార్జీలు కలపలేదు.'));
      sec.appendChild(c);
      const sv = K.homeSavings(Object.assign({}, home, { rate: store.rate }));
      const w = el('div', 'card');
      w.appendChild(el('h3', '', '💡 ఇలా చేస్తే ఎంత ఆదా? (నెలకి)'));
      const line = (te, x) => { if (x.units > 0.05) w.appendChild(el('div', '', te + ': ' + r1(x.units) + ' యూనిట్లు · ' + rs(x.rupees) + ' (ఏడాదికి ' + rs(x.rupees * 12) + ')')); };
      line('💡 పాత బల్బులన్నీ LED', sv.led);
      line('🌀 ఫ్యాన్లన్నీ BLDC (28W)', sv.bldc);
      line('❄️ AC రోజుకి ఒక గంట తక్కువ', sv.acHour);
      line('✅ మూడూ కలిపి', sv.all);
      if (w.childNodes.length === 1) w.appendChild(el('div', 'muted', 'ఇప్పుడే బాగుంది: పెద్ద ఆదా కనిపించలేదు.'));
      sec.appendChild(w);
      sec.appendChild(btn('⚡ బోర్డ్ గా చూపించు', 'main', () => { setBoard(K.homeBoard(home), { title: '🏠 మా ఇల్లు' }); post({ t: 'title', title: '🏠 మా ఇల్లు' }); }));
    }
  }

  // 🧩 challenges list
  function renderGames() {
    const sec = clear($('games'));
    sec.appendChild(el('h2', '', '🧩 ఛాలెంజ్‌లు'));
    const total = K.CHALLENGES.reduce((s, c) => s + (store.stars[c.id] || 0), 0);
    sec.appendChild(el('div', 'muted', 'వైర్లు కలిపి గోల్ పూర్తి చేయండి. తక్కువ వైర్లతో, ఏదీ కాల్చకుండా చేస్తే ఎక్కువ స్టార్లు. మొత్తం ⭐ ' + total + ' / ' + K.CHALLENGES.length * 3));
    K.CHALLENGES.forEach((ch, i) => {
      const c = el('div', 'card');
      const r = el('div', 'row');
      r.appendChild(el('span', 'k', (i + 1) + '. ' + ch.te));
      const s = store.stars[ch.id] || 0;
      r.appendChild(el('span', s ? 'stars' : 'muted', s ? '⭐'.repeat(s) + '☆'.repeat(3 - s) : '☆☆☆'));
      r.appendChild(btn(s ? 'మళ్ళీ' : 'ఆడు', 'main small', () => startChallenge(ch)));
      c.appendChild(r);
      c.appendChild(el('div', 'muted', ch.goal));
      sec.appendChild(c);
    });
  }

  // 📘 learn
  function renderLearn() {
    const sec = clear($('learn'));
    sec.appendChild(el('h2', '', '📘 నేర్చుకో'));
    sec.appendChild(el('div', 'muted', 'రెడీ బోర్డులు: తెరిచి స్విచ్‌లు తాకి, భాగాలు మార్చి చూడండి. ఏదైనా తప్పు జరిగినా బొమ్మలోనే.'));
    for (const t of K.TEMPLATES) {
      const c = el('div', 'card');
      const r = el('div', 'row');
      r.appendChild(el('b', 'k', t.te));
      r.appendChild(btn('తెరువు', 'main small', () => { exitChallenge(true); setBoard(Object.assign(t.board(), { title: t.te }), { title: t.te }); post({ t: 'title', title: t.te }); hint(t.note); }));
      c.appendChild(r);
      c.appendChild(el('div', 'muted', t.note));
      sec.appendChild(c);
    }
    const ex = [
      ['📏 V = I × R (ఓమ్ నియమం)', 'V వోల్టేజ్ = కరెంట్‌ని నెట్టే బలం; I కరెంట్ = ఎంత ప్రవహిస్తోంది; R రెసిస్టెన్స్ = ఎంత అడ్డు. నీళ్ళ పైపు లాగా: ట్యాంక్ ఎత్తు = V, నీళ్ళ ప్రవాహం = I, పైపు సన్నదనం = R. పవర్ W = V × I.'],
      ['🔁 సిరీస్ vs ప్యారలల్', 'సిరీస్: ఒకదాని తర్వాత ఒకటి, ఒకే దారి: ఒకటి తీసేస్తే అన్నీ ఆగిపోతాయి, వోల్ట్స్ పంచుకుంటాయి. ప్యారలల్: ప్రతిదానికీ తన దారి, అన్నింటికీ పూర్తి వోల్ట్స్. ఇంట్లో అన్నీ ప్యారలల్.'],
      ['〰️ AC vs DC', 'DC: ఒకే దిశలో (బ్యాటరీ, సోలార్, ఫోన్). AC: సెకనుకి 50 సార్లు దిశ మారుతుంది (ఇంటి కరెంట్ 230V). వీటిని నేరుగా కలపకూడదు: మధ్యలో ఇన్వర్టర్ (DC→AC) లేదా ఛార్జర్ (AC→DC).'],
      ['🧯 ఫ్యూజ్ vs MCB', 'రెండూ ఎక్కువ కరెంట్ వస్తే దారి తెంపుతాయి. ఫ్యూజ్ ఒకసారే (కొత్తది పెట్టాలి), MCB మళ్ళీ పైకి వేయొచ్చు. వైర్ తట్టుకునే దానికంటే చిన్నదే పెట్టాలి.'],
      ['🔩 ఎర్త్ ఎందుకు', 'ఐరన్, గీజర్, ఫ్రిజ్ లాంటి లోహపు బాడీ వాటికి లోపల వైర్ తగిలితే కరెంట్ ఎర్త్ వైర్ ద్వారా భూమిలోకి పోయి MCB / ELCB పడిపోతుంది; మనకి షాక్ కొట్టదు.'],
      ['⚠️ నిజమైన కరెంట్', 'ఇది బొమ్మ ప్రయోగశాల మాత్రమే. ఇంటి 230V చాలా ప్రమాదం: అసలు వైరింగ్ ఎప్పుడూ లైసెన్స్ ఉన్న ఎలక్ట్రీషియన్ తోనే, మెయిన్ ఆఫ్ చేసి.']];
    for (const [h, t] of ex) { const c = el('div', 'card'); c.appendChild(el('h3', '', h)); c.appendChild(el('div', '', t)); sec.appendChild(c); }
    const g = el('div', 'card');
    g.appendChild(el('h3', '', '🔤 భాగాలు, అవి ఏం చేస్తాయి'));
    const L = el('div', 'list');
    for (const [type, d] of Object.entries(S.PARTS)) { const x = el('div', ''); x.appendChild(el('b', '', d.emoji + ' ' + d.te + ': ')); x.appendChild(document.createTextNode(d.info)); L.appendChild(x); }
    g.appendChild(L);
    sec.appendChild(g);
  }

  // ---------------------------------------------------------------- pictures for the shelf / sharing

  function picture(maxW) {
    const b = bounds(), pad = 1.2;
    const z = Math.min(1.4, maxW / ((b.x1 - b.x0 + pad * 2) * CELL));
    const w = Math.max(200, Math.round((b.x1 - b.x0 + pad * 2) * CELL * z)), h = Math.max(160, Math.round((b.y1 - b.y0 + pad * 2) * CELL * z) + 30);
    const off = document.createElement('canvas');
    off.width = w; off.height = h;
    const keep = { v: Object.assign({}, view), sel, wireFrom, ctx, probes, highlight };
    ctx = off.getContext('2d');
    sizeFn = () => ({ w, h, d: 1 });
    view.z = z; view.ox = (pad - b.x0) * CELL * z; view.oy = (pad - b.y0) * CELL * z + 30;
    sel = null; wireFrom = null; probes = []; highlight = [];
    try {
      draw(performance.now());
      ctx.fillStyle = '#dceef5'; ctx.font = '700 16px ' + FONT; ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
      ctx.fillText('⚡ ' + (board.title || 'సర్క్యూట్'), 12, 22);
    } finally {
      ctx = keep.ctx; sizeFn = null; Object.assign(view, keep.v); sel = keep.sel; wireFrom = keep.wireFrom; probes = keep.probes; highlight = keep.highlight;
    }
    return off.toDataURL('image/png');
  }

  // ---------------------------------------------------------------- from Jarvis

  function onJarvis(m) {
    switch (m.t) {
      case 'init':
        if (m.store && typeof m.store === 'object') store = Object.assign(store, m.store);
        if (!store.stars || typeof store.stars !== 'object') store.stars = {};
        if (!store.partHours || typeof store.partHours !== 'object') store.partHours = {};
        if (m.board && m.board.parts) setBoard(m.board, { undo: false, title: m.board.title || '' });
        else { resolve(true); fit(); }
        tools();
        break;
      case 'load':
        exitChallenge(true);
        setBoard(m.board || {}, { title: typeof m.title === 'string' ? m.title : undefined });
        if (typeof m.note === 'string' && m.note) hint(m.note);
        break;
      case 'aiedit':
        setBoard(Object.assign({}, m.board, { state: st }), { title: board.title, fit: false, keepState: true });
        if (m.said) toast('✏️ ' + m.said, 'good');
        break;
      case 'say': {
        const r = K.voiceEdit(board, String(m.text || ''));
        if (r && !r.ask) {
          pushUndo();
          const keepSt = st;
          board = { title: board.title, parts: r.board.parts, wires: r.board.wires };
          st = keepSt;
          if (r.reset) { const s0 = st[r.reset] || (st[r.reset] = {}); s0.blown = false; s0.tripped = false; s0.overload = false; }
          afterChange(false);
          toast('✏️ ' + r.said, 'good');
          post({ t: 'said', id: m.id, text: r.said });
        } else if (r && r.ask) { post({ t: 'said', id: m.id, text: r.said, ask: true }); toast(r.said); }
        else post({ t: 'needai', id: m.id, text: m.text, board: { title: board.title, parts: board.parts, wires: board.wires } });
        break;
      }
      case 'tab': if (typeof m.name === 'string') showTab(m.name); break;
      case 'back': // (the phone's back: first the open card, then the board's tab, then Jarvis leaves the lab)
        if ($('sheet').classList.contains('on')) { closeSheet(); post({ t: 'back', used: true }); }
        else if (wireFrom || mode === 'meter') { wireFrom = null; if (mode === 'meter') { mode = 'edit'; probes = []; tools(); } hint(''); kick(); post({ t: 'back', used: true }); }
        else if (tab !== 'board') { showTab('board'); post({ t: 'back', used: true }); }
        else post({ t: 'back', used: false });
        break;
      case 'snap': post({ t: 'file', req: m.req, kind: 'png', data: board.parts.length ? picture(m.w || 720).split(',')[1] : '' }); break;
      case 'report': {
        const e = S.estimates(board, res, { rate: store.rate, partHours: store.partHours });
        const lines = ['మొత్తం లోడ్: ' + fmtW(e.loadW)];
        for (const b of e.batteries) lines.push('🔋 ' + b.te + ': ' + Math.round(b.soc * 100) + '%' + (b.hours ? ', సుమారు ' + fmtH(b.hours) : ''));
        for (const v of e.inverters) lines.push('🔌 ' + v.te + ': ' + fmtW(v.w) + ' / ' + v.maxW + ' W');
        if (e.unitsMonth > 0.05) lines.push('🧾 నెలకి సుమారు ' + r1(e.unitsMonth) + ' యూనిట్లు · ' + rs(e.billMonth) + ' (యూనిట్ ₹' + store.rate + ')');
        for (const p of board.parts) { const r = res.parts[p.id] || {}; lines.push('• ' + (p.label || names[p.id]) + ': ' + reading(p, r, S.props(p), st[p.id] || {})); }
        for (const s of K.safety(board)) lines.push((s.level === 'ok' ? '' : '⚠️ ') + s.te);
        post({ t: 'report', req: m.req, title: board.title || 'సర్క్యూట్', png: board.parts.length ? picture(1100).split(',')[1] : '', lines: lines.slice(0, 80) });
        break;
      }
      default:
    }
  }

  window.addEventListener('resize', () => { kick(); });
  tools();
  strip();
  kick();
  // (desk tests reach in here)
  window.__lab = { get board() { return board; }, get res() { return res; }, get st() { return st; }, onJarvis, setBoard, showTab, tap, fit, view, get store() { return store; }, K, S, picture };
})();
